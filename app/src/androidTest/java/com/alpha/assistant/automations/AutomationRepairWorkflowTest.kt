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

    @Test fun renderEffectsCannotCommitAndInvalidIntervalIsRejected() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="automation-render-${UUID.randomUUID()}.db"
        try {ToolStore(context,name).use { tools ->
            val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Fixture","description":"","capabilities":[],
                "stateSchema":{"title":"string"},"initialState":{"title":"Fixture"},
                "ui":{"type":"text","text":"Fixture","bind":"title"},"source":"return {state:input.state};"}""")
            val initial = ToolRecord("target",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            tools.commitTool(initial,null,true)
            val badSpec=JSONObject(initial.spec).put("capabilities",JSONArray().put("notification.post"))
                .put("source","return {state:input.state,effects:[{id:'bad',capability:'notification.post',args:{title:'Bad',text:'Bad'}}]};").toString()
            val bad=initial.copy(spec=badSpec,revision=initial.revision+1)
            tools.commitTool(bad,initial,true)
            val action=CapabilityRequest("set","tool.state.set",JSONObject().put("toolId",bad.id).put("revision",bad.revision).put("field","title").put("value","Changed"))
            assertTrue(runCatching {AutomationTools.execute(tools,action,false,AutomationTools.renderer(context,tools)) {true}}.isFailure)
            assertEquals(bad,tools.tool(bad.id))
            assertTrue(runCatching {AutomationDefinition.parse(notice(JSONObject().put("type","interval").put("minutes",14).put("anchor",1000)))}.isFailure)
        }} finally {context.deleteDatabase(name)}
    }
}
