package com.alpha.assistant.automations

import android.Manifest
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScheduledCalendarWorkflowTest {
    private val day = Instant.parse("2026-10-30T00:00:00Z").toEpochMilli()
    private val first = day + 9 * 3600000
    private val second = first + 86400000
    private val calendarId = 7L
    private val eventId = 42L

    @Test fun scheduledImportKeepsSeparateRecurringInstancesAndExistingCompletion(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val toolDatabase = "scheduled-calendar-${UUID.randomUUID()}.db"
        val calendar = CalendarFixture()
        val resolver = ContentResolver.wrap(calendar)
        var allowed = true
        val context = object : ContextWrapper(app) {
            override fun getContentResolver() = resolver
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.READ_CALENDAR) {
                    if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                } else super.checkSelfPermission(permission)
        }
        val automationDatabase = ToolStore(context, toolDatabase).use { it.automationDatabase }
        try {
            ToolStore(context, toolDatabase).use { tools ->
                AutomationStore(context, automationDatabase).use { rules ->
                    val tool = AutomationTools.createDayTool(tools)
                    val at = day + 8 * 3600000
                    val trigger = JSONObject().put("type", "interval").put("minutes", 15).put("anchor", at)
                    val read = CapabilityRequest("read", "calendar.read.rolling",
                        JSONObject().put("calendarId", calendarId).put("days", 2).put("zone", "UTC"))
                    val sync = CapabilityRequest("sync", "tool.records.calendar",
                        JSONObject().put("toolId", tool.id).put("revision", tool.revision).put("field", "events").put("source", read.id))
                    val rule = rules.save(null, JSONObject().put("name", "Calendar import")
                        .put("trigger", trigger).put("conditions", JSONObject())
                        .put("actions", JSONArray().put(read.json()).put(sync.json())))
                    rules.approve(rule.id, rule.revision)
                    val executor = AutomationRuntime.executor(context, rules)
                    suspend fun deliver(time: Long): AutomationRun? {
                        rules.next(rule.id, rule.revision, time)
                        return AutomationSchedule.deliver(rules, checkNotNull(rules.rule(rule.id)), time, time, executor)
                    }

                    val imported = deliver(at)
                    assertEquals(imported?.details, "succeeded", imported?.status)
                    val initial = checkNotNull(tools.tool(tool.id))
                    val state = JSONObject(initial.state)
                    val events = state.getJSONArray("events")
                    assertEquals(2, events.length())
                    val completed = events.getJSONObject(0)
                    val completedId = completed.getString("id")
                    val otherId = events.getJSONObject(1).getString("id")
                    assertNotEquals(completedId, otherId)
                    assertEquals(first / 1000, completed.getLong("start"))
                    assertEquals(second / 1000, events.getJSONObject(1).getLong("start"))
                    completed.put("done", true)
                    tools.commitTool(initial.copy(state = state.toString(), stateVersion = initial.stateVersion + 1), initial, false)

                    calendar.title = "Updated appointment"
                    val repeated = deliver(at + 15 * 60000)
                    assertEquals(repeated?.details, "succeeded", repeated?.status)
                    val saved = checkNotNull(tools.tool(tool.id))
                    val updated = JSONObject(saved.state).getJSONArray("events")
                    assertEquals(2, updated.length())
                    assertEquals(completedId, updated.getJSONObject(0).getString("id"))
                    assertEquals(otherId, updated.getJSONObject(1).getString("id"))
                    assertTrue(updated.getJSONObject(0).getBoolean("done"))
                    assertFalse(updated.getJSONObject(1).getBoolean("done"))
                    assertTrue(updated.getJSONObject(0).getString("text").contains(calendar.title))

                    allowed = false
                    assertEquals("revoked", deliver(at + 30 * 60000)?.status)
                    assertEquals(2, calendar.instanceReads)
                    assertEquals(saved, tools.tool(tool.id))
                    assertFalse(rules.active(rule.id, rule.revision))
                }
            }
        } finally {
            app.deleteDatabase(automationDatabase)
            app.deleteDatabase(toolDatabase)
        }
    }

    private inner class CalendarFixture : ContentProvider() {
        var title = "Appointment"
        var instanceReads = 0

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = checkNotNull(projection).toList().toTypedArray()
            return MatrixCursor(columns).apply {
                if (uri == CalendarContract.Calendars.CONTENT_URI) {
                    addRow(arrayOf<Any>(calendarId, "Fixture calendar"))
                } else {
                    val range = CalendarContract.Instances.CONTENT_URI.buildUpon()
                        .appendPath(day.toString()).appendPath((day + 2 * 86400000).toString()).build()
                    assertEquals(range, uri)
                    assertEquals("${CalendarContract.Instances.CALENDAR_ID}=?", selection)
                    assertEquals(listOf(calendarId.toString()), selectionArgs?.toList())
                    assertEquals(listOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN, CalendarContract.Instances.END), columns.toList())
                    instanceReads++
                    addRow(arrayOf<Any>(eventId, title, first, first + 3600000))
                    addRow(arrayOf<Any>(eventId, title, second, second + 3600000))
                }
            }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read-only fixture")
    }
}
