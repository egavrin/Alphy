package com.alpha.assistant.automations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.*
import com.alpha.assistant.tools.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationRepairWorkflowTest {
    private fun notice(trigger: JSONObject) = JSONObject().put("name","Fixture").put("trigger",trigger).put("conditions",JSONObject())
        .put("actions",JSONArray().put(JSONObject().put("id","notice").put("capability","notification.post").put("args",JSONObject().put("title","Fixture").put("text","Constant"))))
    private fun daily(exact: Boolean) = JSONObject().put("type","schedule").put("repeat","daily").put("time","08:00").put("zone","UTC").put("exact",exact)

    @Test fun failedRegistrationInvalidatesFingerprintAndSameDesiredRulesRetry() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="automation-geo-repair-${UUID.randomUUID()}.db"
        try { AutomationStore(context,name).use { store ->
            store.setState("geofences","A")
            var removals=0; var additions=0
            assertTrue(runCatching { AutomationRuntime.registerGeofences(store,"A",true,{removals++},{additions++; error("Fixture failure")}) }.isFailure)
            assertNotEquals("A",store.state("geofences"))
            assertTrue(AutomationRuntime.registerGeofences(store,"A",false,{removals++},{additions++}))
            assertEquals(2,removals); assertEquals(2,additions); assertEquals("A",store.state("geofences"))
            assertFalse(AutomationRuntime.registerGeofences(store,"A",false,{error("Unexpected removal")},{error("Unexpected addition")}))
        } } finally {context.deleteDatabase(name)}
    }

    @Test fun deliveredAlarmsExpireAtSixtyMinutesAndOccurrencesSpanRevisions() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="automation-alarm-repair-${UUID.randomUUID()}.db"
        val now=Instant.parse("2026-10-30T10:00:00Z").toEpochMilli()
        try { AutomationStore(context,name).use { store ->
            var calls=0
            val executor=AutomationExecutor(store,{null},{_,_->null}) {_,_,_->calls++; CapabilityResult("succeeded","Fixture")}
            for(exact in listOf(false,true)) {
                val rule=store.save(null,notice(daily(exact))); store.approve(rule.id,rule.revision)
                val at=now-3600001L; store.next(rule.id,rule.revision,at)
                assertEquals("missed",AutomationSchedule.deliver(store,checkNotNull(store.rule(rule.id)),at,now,executor)?.status)
            }
            assertEquals(0,calls)
            val original=store.save(null,notice(daily(false))); store.approve(original.id,original.revision)
            val at=now-3600000L; store.next(original.id,original.revision,at)
            assertEquals("succeeded",AutomationSchedule.deliver(store,checkNotNull(store.rule(original.id)),at,now,executor)?.status)
            assertEquals(1,calls)
            val edited=store.save(original.id,notice(daily(false)).put("name","Edited"),original.revision); store.approve(edited.id,edited.revision)
            store.next(edited.id,edited.revision,at)
            val duplicate=AutomationSchedule.deliver(store,checkNotNull(store.rule(edited.id)),at,now,executor)
            assertEquals(original.revision,duplicate?.revision); assertEquals(1,calls)
            assertEquals(1,store.history(original.id).size)
        } } finally {context.deleteDatabase(name)}
    }

    @Test fun rollingCalendarFeedsRenderedToolRecordsAndDryRunChangesNothing() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val automationName="automation-flow-${UUID.randomUUID()}.db"
        val toolName="automation-flow-tools-${UUID.randomUUID()}.db"
        try { AutomationStore(context,automationName).use { store -> ToolStore(context,toolName).use { tools ->
            val tool=AutomationTools.createDayTool(tools)
            val read=CapabilityRequest("calendar","calendar.read.rolling",JSONObject().put("calendarId",7).put("days",1).put("zone","UTC"))
            val sync=CapabilityRequest("sync","tool.records.calendar",JSONObject().put("toolId",tool.id).put("revision",tool.revision).put("field","events").put("source","calendar"))
            val data=notice(daily(false)).put("actions",JSONArray().put(read.json()).put(sync.json()))
            val rule=store.save(null,data); store.approve(rule.id,rule.revision)
            val day=Instant.parse("2026-10-30T08:00:00Z").toEpochMilli()
            val resolved=AutomationDataflow.resolveRead(read,day)
            assertEquals(Instant.parse("2026-10-30T00:00:00Z").toEpochMilli(),resolved.args.getLong("from"))
            assertEquals(Instant.parse("2026-10-31T00:00:00Z").toEpochMilli(),resolved.args.getLong("to"))
            assertNotEquals(resolved.args.getLong("from"),AutomationDataflow.resolveRead(read,day+86400000).args.getLong("from"))
            val render=AutomationTools.renderer(context,tools)
            var writes=0
            val executor=AutomationExecutor(store,{null},{_,_->null},preview={ owner,action,step ->
                if(action.capability == "calendar.read.rolling") calendarFixture()
                else AutomationTools.execute(tools,owner.definition,action,step,render) {store.active(owner.id,owner.revision)}
            }) { owner,action,step ->
                if(action.capability == "calendar.read.rolling") calendarFixture()
                else {writes++; AutomationTools.execute(tools,owner.definition,action,step,render) {store.active(owner.id,owner.revision)}}
            }
            val before=tools.tool(tool.id)
            val dry=executor.run(rule.id,rule.revision,AutomationEvent("manual","dry"),true,day)
            assertEquals("dryRun",dry?.status); assertEquals(0,writes); assertEquals(before,tools.tool(tool.id))
            assertTrue(dry!!.details.contains("Meeting"))
            assertEquals("succeeded",executor.run(rule.id,rule.revision,AutomationEvent("manual","real"),false,day)?.status)
            val saved=checkNotNull(tools.tool(tool.id))
            assertEquals(1,JSONObject(saved.state).getJSONArray("events").length())
            assertTrue(saved.ui.contains("1")); assertEquals(before!!.stateVersion+1,saved.stateVersion)
            assertTrue(JSONObject(saved.state).getJSONArray("events").getJSONObject(0).getString("text").contains("Meeting"))
            assertEquals(1793347200L,JSONObject(saved.state).getJSONArray("events").getJSONObject(0).getLong("start"))
            val current=JSONObject(saved.state).getJSONArray("events")
            current.put(JSONObject().put("id","manual").put("text","Keep").put("done",false).put("start",0).put("end",0))
            tools.commitTool(saved.copy(state=JSONObject(saved.state).put("events",current).toString(),stateVersion=saved.stateVersion+1),saved,false)
            executor.run(rule.id,rule.revision,AutomationEvent("manual","again"),false,day)
            assertEquals(2,JSONObject(tools.tool(tool.id)!!.state).getJSONArray("events").length())
        } } } finally {context.deleteDatabase(automationName); context.deleteDatabase(toolName)}
    }
    private fun calendarFixture() = CapabilityResult("succeeded","Fixture",JSONObject().put("events",JSONArray().put(JSONObject().put("id",42).put("title","Meeting").put("start",1793347200000L).put("end",1793350800000L))))

    @Test fun renderEffectsCannotCommitAndInvalidIntervalIsRejected() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="automation-render-${UUID.randomUUID()}.db"
        try {ToolStore(context,name).use { tools ->
            val initial=AutomationTools.createDayTool(tools)
            val badSpec=JSONObject(initial.spec).put("capabilities",JSONArray().put("notification.post"))
                .put("source","return {state:input.state,effects:[{id:'bad',capability:'notification.post',args:{title:'Bad',text:'Bad'}}]};").toString()
            val bad=initial.copy(spec=badSpec,revision=initial.revision+1)
            tools.commitTool(bad,initial,true)
            val action=CapabilityRequest("set","tool.state.set",JSONObject().put("toolId",bad.id).put("revision",bad.revision).put("field","title").put("value","Changed"))
            val definition=AutomationDefinition.parse(notice(JSONObject().put("type","manual")).put("actions",JSONArray().put(action.json())))
            assertTrue(runCatching {AutomationTools.execute(tools,definition,action,AutomationStep("fixture",0,emptyMap(),false),AutomationTools.renderer(context,tools)) {true}}.isFailure)
            assertEquals(bad,tools.tool(bad.id))
            assertTrue(runCatching {AutomationDefinition.parse(notice(JSONObject().put("type","interval").put("minutes",14).put("anchor",1000)))}.isFailure)
        }} finally {context.deleteDatabase(name)}
    }
}
