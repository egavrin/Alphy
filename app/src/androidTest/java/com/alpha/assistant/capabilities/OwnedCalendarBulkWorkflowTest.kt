package com.alpha.assistant.capabilities

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** One native snapshot must remove more than three owned events and preserve all unrelated rows. */
class OwnedCalendarBulkWorkflowTest {
    @Test fun oneExplicitSnapshotDeletesFiveOwnedEventsOnlyAndDuplicateDoesNotReplay()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="calendar-bulk-${UUID.randomUUID()}.db";val store=ToolStore(context,name)
        try {SyntheticOwnedCalendar(context).use {fixture ->
            val spec=ToolSpec.parse(JSONObject().put("formatVersion",2).put("title","Bulk calendar fixture").put("description","")
                .put("capabilities",JSONArray(OwnedCalendarContract.ids.toList())).put("stateSchema",JSONObject("{\"ready\":\"boolean\"}")).put("initialState",JSONObject("{\"ready\":true}"))
                .put("ui",JSONObject("{\"type\":\"calendar\",\"action\":\"removeAll\",\"capability\":\"calendar.deleteAllOwned\",\"args\":{\"version\":1}}"))
                .put("source","return {state:input.state};").toString())
            store.saveTool(ToolRecord("bulk",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true),true)
            OwnedCalendarContract.ids.forEach {store.grant("bulk",it,true)}
            val native=OwnedCalendarCapabilities(context,store,unlocked={true});val broker=CapabilityBroker(context,store)
            var seq=0
            fun enqueue(cap: String,args: JSONObject): String {
                val old=store.tool("bulk")!!
                store.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false,effects=listOf(CapabilityRequest("op${seq++}",cap,args)))
                return store.effects("bulk").last().key
            }
            val selectKey=enqueue("calendar.select",JSONObject().put("version",1))
            val selected=broker.executeOwned(selectKey,true) {native.select(it,fixture.native(),{true})}
            val details=JSONObject().put("title","Synthetic bulk event").put("start",1893456000000L).put("end",1893459600000L)
                .put("timeZone","UTC").put("deviceTimeZone",java.time.ZoneId.systemDefault().id).put("location","").put("reminderMinutes",10)
            repeat(5) {index ->
                val key=enqueue("calendar.createOwned",JSONObject(details.toString()).put("version",1).put("calendarRef",selected.data.getString("calendarRef")).put("eventKey","stop-$index"))
                assertEquals("succeeded",broker.executeOwned(key,true) {native.execute(it,{true}){}}.status)
            }
            val snapshot=OwnedCalendarLedger(store).snapshot(store.tool("bulk")!!)
            assertEquals(5,snapshot.entries.size)
            val adapter=AndroidOwnedCalendarProvider(context)
            val foreign="bulk-unowned-${UUID.randomUUID()}";adapter.insert(fixture.id,context.packageName,foreign,details)
            val bulkKey=enqueue("calendar.deleteAllOwned",JSONObject().put("version",1))
            val deleted=broker.executeOwned(bulkKey,true) {native.deleteAll(it,snapshot,{true})}
            assertEquals(deleted.message,"succeeded",deleted.status);assertEquals(5,deleted.data.getInt("deleted"))
            assertEquals(0,deleted.data.getInt("unknown"));assertEquals(0,deleted.data.getInt("remaining"))
            assertTrue(OwnedCalendarLedger(store).snapshot(store.tool("bulk")!!).entries.isEmpty())
            assertEquals(1,adapter.find(fixture.id,context.packageName,foreign).size)
            var replayed=false
            assertEquals("succeeded",broker.executeOwned(bulkKey,true) {replayed=true;native.deleteAll(it,snapshot,{true})}.status)
            assertFalse(replayed)
            OwnedCalendarContract.validateResult("calendar.deleteAllOwned",deleted)
            File(context.getExternalFilesDir(null),"owned-calendar-evidence").apply {mkdirs()}.resolve("bulk-workflow.json").writeText(JSONObject()
                .put("expected","five owned events deleted from one frozen snapshot, unowned neighbor preserved, duplicate not replayed")
                .put("observed",deleted.json()).put("unownedCount",1).put("replayed",replayed).toString(2))
        }}finally {store.close();context.deleteDatabase(name)}
    }
}
