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

    @Test fun standaloneScheduledCalendarReadStillHonorsPermissions(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
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
        val automationDatabase = "scheduled-calendar-${UUID.randomUUID()}.db"
        try {
            AutomationStore(context, automationDatabase).use { rules ->
                val at = day + 8 * 3600000
                val trigger = JSONObject().put("type", "interval").put("minutes", 15).put("anchor", at)
                val read = CapabilityRequest("read", "calendar.read.rolling",
                    JSONObject().put("calendarId", calendarId).put("days", 2).put("zone", "UTC"))
                val rule = rules.save(null, JSONObject().put("name", "Calendar read")
                    .put("trigger", trigger).put("conditions", JSONObject()).put("actions", JSONArray().put(read.json())))
                rules.approve(rule.id, rule.revision)
                val executor = AutomationRuntime.executor(context, rules)
                suspend fun deliver(time: Long): AutomationRun? {
                    rules.next(rule.id, rule.revision, time)
                    return AutomationSchedule.deliver(rules, checkNotNull(rules.rule(rule.id)), time, time, executor)
                }
                val result = deliver(at)
                assertEquals(result?.details, "succeeded", result?.status)
                assertTrue(result?.details.orEmpty().contains("2 событий"))
                assertEquals(1, calendar.instanceReads)
                allowed = false
                assertEquals("revoked", deliver(at + 15 * 60000)?.status)
                assertEquals(1, calendar.instanceReads)
                assertFalse(rules.active(rule.id, rule.revision))
            }
        } finally {
            app.deleteDatabase(automationDatabase)
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
