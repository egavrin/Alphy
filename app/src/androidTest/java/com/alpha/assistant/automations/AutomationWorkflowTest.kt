package com.alpha.assistant.automations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationWorkflowTest {
    private fun definition(trigger: JSONObject = JSONObject().put("type","manual")) = JSONObject()
        .put("name","Fixture").put("trigger",trigger).put("conditions",JSONObject())
        .put("actions",JSONArray().put(JSONObject().put("id","notice").put("capability","notification.post")
            .put("args",JSONObject().put("title","Fixture").put("text","Local only"))))
    private val event = AutomationEvent("manual","occurrence-1")

    @Test fun approvedRevisionDeduplicatesAndPersistsWithoutReplayingUnknownResults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "automation-test-${UUID.randomUUID()}.db"
        var calls = 0
        try {
            AutomationStore(context,name).use { store ->
                val draft = store.save(null,definition())
                val executor = AutomationExecutor(store,{ null },{ _,_ -> null }) { _,_,_ -> calls++; CapabilityResult("succeeded","Fixture") }
                assertNull(executor.run(draft.id,draft.revision,event))
                store.approve(draft.id,draft.revision)
                assertEquals("succeeded",executor.run(draft.id,draft.revision,event)?.status)
                assertEquals("succeeded",executor.run(draft.id,draft.revision,event)?.status)
                assertEquals(1,calls)
                executor.run(draft.id,draft.revision,AutomationEvent("manual","dry"),true)
                assertEquals(1,calls)
                store.pause(draft.id)
                assertNull(executor.run(draft.id,draft.revision,AutomationEvent("manual","paused")))
                store.approve(draft.id,draft.revision)
                val unknown = AutomationExecutor(store,{ null },{ _,_ -> null }) { _,_,_ -> calls++; error("Adapter interrupted") }
                assertEquals("unknownOutcome",unknown.run(draft.id,draft.revision,AutomationEvent("manual","unknown"))?.status)
                assertEquals("unknownOutcome",unknown.run(draft.id,draft.revision,AutomationEvent("manual","unknown"))?.status)
                assertEquals(2,calls)
                val edited = store.save(draft.id,definition().put("name","Edited"),draft.revision)
                assertFalse(edited.enabled)
                assertEquals(0,edited.approvedRevision)
                assertNull(executor.run(draft.id,draft.revision,AutomationEvent("manual","old")))
            }
            AutomationStore(context,name).use { reopened ->
                assertEquals(1,reopened.rules().size)
                assertEquals("unknownOutcome",reopened.history(reopened.rules().single().id).first { it.token == "unknown" }.status)
                reopened.prune(System.currentTimeMillis()+31L*86400000)
                assertTrue(reopened.history(reopened.rules().single().id).isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun conditionsAndPerEffectRevocationBlockExecution() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "automation-revoke-${UUID.randomUUID()}.db"
        try { AutomationStore(context,name).use { store ->
            val data = definition().put("conditions",JSONObject().put("charging",true))
            data.getJSONArray("actions").put(JSONObject().put("id","second").put("capability","notification.post").put("args",JSONObject().put("title","Second").put("text","Blocked")))
            val rule = store.save(null,data); store.approve(rule.id,rule.revision)
            var calls = 0
            val executor = AutomationExecutor(store,{ false },{ _,_ -> null }) { _,_,_ -> calls++; store.pause(rule.id); CapabilityResult("succeeded","First") }
            assertEquals("skipped",executor.run(rule.id,rule.revision,event)?.status); assertEquals(0,calls)
            val allowed = AutomationExecutor(store,{ true },{ _,_ -> null }) { _,_,_ -> calls++; store.pause(rule.id); CapabilityResult("succeeded","First") }
            assertEquals("cancelled",allowed.run(rule.id,rule.revision,AutomationEvent("manual","next"))?.status)
            assertEquals(1,calls)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun safeReadRetriesAreBoundedAndDeferredLaunchClaimsDoNotReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "automation-retry-${UUID.randomUUID()}.db"
        try { AutomationStore(context,name).use { store ->
            val data=definition()
            data.getJSONArray("actions").getJSONObject(0).put("capability","calendar.read")
                .put("args",JSONObject().put("calendarId",1).put("from",1000).put("to",2000))
            val rule=store.save(null,data); store.approve(rule.id,rule.revision)
            var calls=0
            val executor=AutomationExecutor(store,{ null },{ _,_ -> null }) { _,_,_ ->
                calls++; if(calls == 1) CapabilityResult("unavailable","Fixture") else CapabilityResult("succeeded","Fixture")
            }
            assertEquals("succeeded",executor.run(rule.id,rule.revision,event)?.status)
            assertEquals(2,calls)
            assertEquals("succeeded",executor.run(rule.id,rule.revision,event)?.status)
            assertEquals(2,calls)
            val open=definition()
            open.getJSONArray("actions").getJSONObject(0).put("capability","app.open").put("args",JSONObject().put("app","settings"))
            val external=store.save(null,open); store.approve(external.id,external.revision)
            val deferred=AutomationExecutor(store,{ null },{ _,_ -> null }) { _,_,_ -> CapabilityResult("awaitingUser","Open Alpha") }
            val run=checkNotNull(deferred.run(external.id,external.revision,event))
            assertTrue(store.claimUserRun(run))
            assertFalse(store.claimUserRun(run))
            assertEquals("unknownOutcome",store.occurrence(external.id,external.revision,event.token)?.status)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun scheduleAndSourcesAreStrict() {
        val daily = JSONObject().put("type","schedule").put("repeat","daily").put("time","02:30").put("zone","Europe/Berlin").put("exact",true)
        val parsed = AutomationDefinition.parse(definition(daily))
        assertEquals("schedule:2026-03-29T02:30",parsed.occurrenceToken(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli()))
        val before = Instant.parse("2026-03-28T03:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(),parsed.nextAfter(before))
        assertEquals(Instant.parse("2026-03-30T00:30:00Z").toEpochMilli(),parsed.nextAfter(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli()))
        val invalid = definition(daily)
        invalid.getJSONArray("actions").getJSONObject(0).put("capability","app.open").put("args",JSONObject().put("app","settings"))
        assertTrue(runCatching { AutomationDefinition.parse(invalid) }.isFailure)
        assertTrue(runCatching { AutomationDefinition.parse(definition(JSONObject().put("type","notification").put("packages",JSONArray().put("com.alpha.assistant")))) }.isFailure)
        assertTrue(runCatching { AutomationDefinition.parse(definition(JSONObject().put("type","geofence").put("latitude",91).put("longitude",0).put("radius",200).put("transition","enter"))) }.isFailure)
    }
}
