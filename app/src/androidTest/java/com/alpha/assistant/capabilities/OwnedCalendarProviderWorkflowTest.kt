package com.alpha.assistant.capabilities

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Sync-adapter access exists only in test setup/cleanup of this isolated synthetic calendar. */
internal class SyntheticOwnedCalendar(private val context: Context): AutoCloseable {
    private val account="alphy-test-${UUID.randomUUID()}"
    private val uri=CalendarContract.Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME,account).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,CalendarContract.ACCOUNT_TYPE_LOCAL).build()
    val id: Long
    init {
        check(OwnedCalendarCapabilities.hasPermissions(context)) {"Grant READ_CALENDAR and WRITE_CALENDAR before the calendar fixture suite."}
        val inserted=context.contentResolver.insert(uri,ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME,account);put(CalendarContract.Calendars.ACCOUNT_TYPE,CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME,account);put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,"Alphy synthetic calendar")
            put(CalendarContract.Calendars.OWNER_ACCOUNT,account);put(CalendarContract.Calendars.CALENDAR_COLOR,0xff336699.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.VISIBLE,1);put(CalendarContract.Calendars.SYNC_EVENTS,1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE,"UTC");put(CalendarContract.Calendars.ALLOWED_REMINDERS,"1");put(CalendarContract.Calendars.MAX_REMINDERS,1)
        })
        id=ContentUris.parseId(checkNotNull(inserted))
    }
    fun native()=AndroidOwnedCalendarProvider(context).calendars().single {it.id==id}
    override fun close() {
        context.contentResolver.delete(uri,"${CalendarContract.Calendars._ID}=? AND ${CalendarContract.Calendars.ACCOUNT_NAME}=?",arrayOf(id.toString(),account))
    }
}

@RunWith(AndroidJUnit4::class)
class OwnedCalendarProviderWorkflowTest {
    @Test fun dailySeriesUsesProviderRecurrenceAndPreservesNeighbor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SyntheticOwnedCalendar(context).use { fixture ->
            val provider = AndroidOwnedCalendarProvider(context)
            val details = JSONObject().put("title", "Synthetic daily event").put("start", 1893485700000L)
                .put("end", 1893486000000L).put("timeZone", "UTC").put("deviceTimeZone", java.time.ZoneId.systemDefault().id)
                .put("location", "").put("reminderMinutes", 10).put("repeatDaily", true)
            CapabilityCatalog.definition("calendar.createOwned").validate(JSONObject(details.toString())
                .put("version", 1).put("calendarRef", "a".repeat(64)).put("eventKey", "daily"))
            val marker = "daily-${UUID.randomUUID()}"
            val neighbor = "neighbor-${UUID.randomUUID()}"
            provider.insert(fixture.id, context.packageName, neighbor, JSONObject(details.toString()).apply { remove("repeatDaily") })
            val id = provider.insert(fixture.id, context.packageName, marker, details)
            val read = provider.find(fixture.id, context.packageName, marker).single()
            assertTrue(OwnedCalendarContract.equal(details,
                JSONObject(read.details.toString()).put("deviceTimeZone", java.time.ZoneId.systemDefault().id)))
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uri, details.getLong("start"))
            ContentUris.appendId(uri, details.getLong("start") + 3 * 86_400_000L)
            val starts = context.contentResolver.query(uri.build(), arrayOf(CalendarContract.Instances.BEGIN),
                "${CalendarContract.Instances.EVENT_ID}=?", arrayOf(id.toString()), CalendarContract.Instances.BEGIN)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
            assertNotNull(starts)
            assertTrue(checkNotNull(starts).size >= 2)
            assertEquals(86_400_000L, starts[1] - starts[0])
            val events = CalendarContract.Events.CONTENT_URI
            val row = ContentUris.withAppendedId(events, id)
            assertEquals(1, context.contentResolver.update(row, ContentValues().apply {
                put(CalendarContract.Events.EXDATE, "20300102T081500Z")
            }, null, null))
            assertTrue(provider.find(fixture.id, context.packageName, marker).single().details.optBoolean("unsupported"))
            assertTrue(runCatching { provider.delete(id, fixture.id, context.packageName, marker, details) }.isFailure)
            assertFalse(provider.find(fixture.id, context.packageName, marker).single().deleted)
            assertEquals(1, context.contentResolver.update(row, ContentValues().apply {
                putNull(CalendarContract.Events.EXDATE)
            }, null, null))
            assertEquals(1, provider.delete(id, fixture.id, context.packageName, marker, details))
            assertTrue(provider.find(fixture.id, context.packageName, neighbor).single().deleted.not())
        }
    }

    @Test fun realProviderBatchReadbackAndGuardedDelete(): Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val name="owned-calendar-provider-${UUID.randomUUID()}.db";val store=ToolStore(context,name)
        val evidence=JSONObject().put("command","am instrument -w -e class com.alpha.assistant.capabilities.OwnedCalendarProviderWorkflowTest com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner")
            .put("expected","exact event/reminder readback, one insert for duplicate key, guarded deletion, neighboring event retained")
        try {SyntheticOwnedCalendar(context).use {fixture ->
            val spec=ToolSpec.parse(JSONObject().put("formatVersion",3).put("title","Synthetic calendar provider fixture").put("description","")
                .put("capabilities",JSONArray(OwnedCalendarContract.ids.toList())).put("stateSchema",JSONObject("{\"ready\":\"boolean\"}")).put("initialState",JSONObject("{\"ready\":true}"))
                .put("ui",JSONObject("{\"type\":\"text\",\"text\":\"Fixture\"}")).put("source","return {state:input.state};").toString())
            store.saveTool(ToolRecord("provider",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true),true)
            val native=OwnedCalendarCapabilities(context,store,unlocked={true});val broker=CapabilityBroker(context,store)
            var sequence=0
            suspend fun execute(capability: String,args: JSONObject): CapabilityResult {
                val old=store.tool("provider")!!;val request=CapabilityRequest("op${sequence++}",capability,args)
                store.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false,effects=listOf(request))
                return broker.executeOwned(store.effects("provider").last().key) {effect ->
                    if(capability=="calendar.select")native.select(effect,fixture.native(),{true}) else native.execute(effect,{true}) {}
                }
            }
            val selected=execute("calendar.select",JSONObject().put("version",1));assertEquals("succeeded",selected.status)
            val details=JSONObject().put("title","Synthetic owned visit").put("start",1893456000000L).put("end",1893459600000L)
                .put("timeZone","UTC").put("deviceTimeZone",java.time.ZoneId.systemDefault().id).put("location","").put("reminderMinutes",10)
            val create=JSONObject(details.toString()).put("version",1).put("calendarRef",selected.data.getString("calendarRef")).put("eventKey","synthetic")
            val created=execute("calendar.createOwned",create);assertEquals(created.message,"succeeded",created.status)
            OwnedCalendarContract.validateResult("calendar.createOwned",created)
            val repeat=execute("calendar.createOwned",create);assertEquals(created.data.getString("eventRef"),repeat.data.getString("eventRef"))
            val adapter=AndroidOwnedCalendarProvider(context)
            val neighborMarker="synthetic-neighbor-${UUID.randomUUID()}"
            adapter.insert(fixture.id,context.packageName,neighborMarker,details)
            val eventRef=created.data.getString("eventRef")
            assertEquals("succeeded",execute("calendar.readOwned",JSONObject().put("version",1).put("eventRef",eventRef)).status)
            val delete=JSONObject(details.toString()).put("version",1).put("eventRef",eventRef)
            val deleted=execute("calendar.deleteOwned",delete);assertEquals(deleted.message,"succeeded",deleted.status)
            assertEquals("succeeded",execute("calendar.deleteOwned",delete).status)
            assertEquals(1,adapter.find(fixture.id,context.packageName,neighborMarker).size)
            val row=OwnedCalendarLedger(store).event("provider",eventRef)!!
            assertTrue(adapter.find(fixture.id,context.packageName,row.marker).all {it.deleted})
            evidence.put("observed","PASS").put("create",created.json()).put("delete",deleted.json()).put("neighborCount",1)
        }} catch(failure: Throwable) {evidence.put("observed","FAIL").put("failure",failure.javaClass.simpleName);throw failure}
        finally {
            File(context.getExternalFilesDir(null),"owned-calendar-evidence").apply {mkdirs()}.resolve("provider-workflow.json").writeText(evidence.toString(2))
            store.close();context.deleteDatabase(name)
        }
    }
}
