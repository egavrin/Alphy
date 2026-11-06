package com.alpha.assistant.capabilities

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Failure cases: access or target changes after claim, invalid data, terminal replacement, duplicate execution,
 * pending foreground work, picker resources and late callbacks, cancellation, and uncertain calendar writes.
 * Fake adapters and permission checks expose these boundaries without changing device permissions or native data.
 */
@RunWith(AndroidJUnit4::class)
class CapabilityOutcomeWorkflowTest {
    private class Permissions(context: Context): ContextWrapper(context) {
        var allowed = true
        override fun checkSelfPermission(permission: String) =
            if(allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fixture(request: CapabilityRequest = CapabilityRequest("one","notification.post",JSONObject().put("title","Fixture").put("text","Fixture")),
        block: suspend (Permissions,ToolStore,ToolRecord,String) -> Unit) {
        val name="capability-outcome-${UUID.randomUUID()}.db"
        val permissions=Permissions(context)
        try { ToolStore(permissions,name).use { store ->
            val spec=ToolSpec.parse(JSONObject().put("formatVersion",3).put("title","Outcome fixture").put("description","")
                .put("capabilities",JSONArray().put(request.capability)).put("capabilityResults",true)
                .put("stateSchema",JSONObject("{\"ready\":\"boolean\"}")).put("initialState",JSONObject("{\"ready\":true}"))
                .put("ui",JSONObject("{\"type\":\"text\",\"text\":\"Fixture\"}")).put("source","return {state:input.state};").toString())
            val tool=ToolRecord("owner",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            store.commitTool(tool,null,true,effects=listOf(request))
            block(permissions,store,tool,store.effects(tool.id).single().key)
        } } finally { context.deleteDatabase(name) }
    }
    private suspend fun consistent(store: ToolStore,key: String,immediate: CapabilityResult,status: String,phase: String="terminal") {
        assertEquals(status,immediate.status)
        assertEquals(phase,store.effect(key)!!.phase)
        assertEquals(immediate.json().toString(),store.effect(key)!!.result.json().toString())
        ToolStore(context,store.databaseName).use { reopened ->
            assertEquals(immediate.json().toString(),reopened.effect(key)!!.result.json().toString())
            val duplicate=CapabilityBroker(context,reopened).execute(key) { error("Duplicate adapter execution") }
            assertEquals(immediate.json().toString(),duplicate.json().toString())
        }
    }
    @Test fun postClaimChangesReturnTheDurableDecisionWithoutReplay(): Unit = runBlocking {
        for(mode in listOf("legacyRow","state","revision","archive","permission","invalidData","terminal")) fixture { permissions,store,tool,key ->
            var calls=0
            val result=CapabilityBroker(permissions,store).execute(key) {
                calls++; assertEquals("running",store.effect(key)!!.phase)
                when(mode) {
                    "legacyRow" -> store.seedLegacyDeviceGrant(tool.id,it.capability,false)
                    "state" -> store.commitTool(tool.copy(stateVersion=2),tool,false)
                    "revision" -> store.commitTool(tool.copy(revision=2),tool,true)
                    "archive" -> store.setArchived(tool,true)
                    "permission" -> permissions.allowed=false
                    "terminal" -> store.finishEffect(key,CapabilityResult("cancelled","Earlier terminal decision"))
                }
                CapabilityResult("succeeded","Adapter reply",if(mode=="invalidData") JSONObject().put("unexpected",true) else JSONObject())
            }
            consistent(store,key,result,when(mode) {
                "legacyRow" -> "succeeded"
                "permission" -> "denied"
                "invalidData" -> "failed"
                else -> "cancelled"
            })
            assertEquals(mode,1,calls)
        }
    }
    @Test fun terminalStatusesAndAdapterErrorsRemainDurable(): Unit = runBlocking {
        for(status in CapabilityResult.statuses - "awaitingUser") fixture { permissions,store,_,key ->
            var calls=0
            val result=CapabilityBroker(permissions,store).execute(key) { calls++; CapabilityResult(status,"Adapter $status") }
            consistent(store,key,result,status); assertEquals(1,calls)
        }
        for(security in listOf(true,false)) fixture { permissions,store,_,key ->
            var calls=0
            val result=CapabilityBroker(permissions,store).execute(key) {
                calls++; if(security) throw SecurityException("Fixture") else error("Fixture")
            }
            consistent(store,key,result,if(security) "denied" else "unknownOutcome"); assertEquals(1,calls)
        }
    }
    @Test fun pendingForegroundWorkCanResumeAndThenCannotReplay(): Unit = runBlocking { fixture { permissions,store,_,key ->
        val broker=CapabilityBroker(permissions,store); var calls=0
        val pending=broker.execute(key) { calls++; CapabilityResult("awaitingUser","Open the app") }
        assertEquals("pending",store.effect(key)!!.phase)
        assertEquals(pending.json().toString(),store.effect(key)!!.result.json().toString())
        val result=broker.execute(key) { calls++; CapabilityResult("succeeded","Resumed") }
        consistent(store,key,result,"succeeded"); assertEquals(2,calls)
    } }
    @Test fun pickerWaitsForOneResourceCallbackAndRejectsLateResults(): Unit = runBlocking {
        fixture(CapabilityRequest("pick","photo.pick",JSONObject())) { permissions,store,tool,key ->
            var calls=0
            val waiting=CapabilityBroker(permissions,store).execute(key) {
                calls++; CapabilityResult("awaitingUser","Picker",JSONObject().put("launched",true))
            }
            consistent(store,key,waiting,"awaitingUser","waiting"); assertEquals(1,calls)
            val resource=CapabilityResource("a".repeat(64),tool.id,"photo","content://fixture/photo","image/jpeg",3,false,false,ToolStore.processSession,Long.MAX_VALUE)
            assertTrue(store.finishUserEffect(key,CapabilityResult("succeeded","Selected",resource.metadata()),resource))
            consistent(store,key,store.effect(key)!!.result,"succeeded")
            assertEquals(resource,store.resource(resource.id))
            assertFalse(store.finishUserEffect(key,CapabilityResult("cancelled","Late cancellation")))
            assertEquals(1,store.resources(tool.id).size); assertEquals("succeeded",store.effect(key)!!.result.status)
        }
    }
    @Test fun rejectedPickerLaunchCannotAcceptAResource(): Unit = runBlocking {
        fixture(CapabilityRequest("pick","photo.pick",JSONObject())) { permissions,store,tool,key ->
            var calls=0
            val result=CapabilityBroker(permissions,store).execute(key) {
                calls++; store.setArchived(tool,true)
                CapabilityResult("awaitingUser","Picker",JSONObject().put("launched",true))
            }
            consistent(store,key,result,"cancelled")
            val resource=CapabilityResource("b".repeat(64),tool.id,"photo","content://fixture/late","image/jpeg",3,false,false,ToolStore.processSession,Long.MAX_VALUE)
            assertFalse(store.finishUserEffect(key,CapabilityResult("succeeded","Late selection",resource.metadata()),resource))
            assertTrue(store.resources(tool.id).isEmpty()); assertEquals(1,calls)
        }
    }
    @Test fun cancellationPersistsUnknownOutcomeAndNeverReplays(): Unit = runBlocking { fixture { permissions,store,_,key ->
        var calls=0
        val interrupted=runCatching { CapabilityBroker(permissions,store).execute(key) { calls++; throw CancellationException("Fixture") } }
        assertTrue(interrupted.exceptionOrNull() is CancellationException)
        consistent(store,key,store.effect(key)!!.result,"unknownOutcome"); assertEquals(1,calls)
    } }
    @Test fun changedCalendarAccessReturnsUncertaintyWithoutClaimingSuccess(): Unit = runBlocking {
        for(capability in listOf("calendar.createOwned","calendar.deleteOwned","calendar.deleteAllOwned")) {
            val args=JSONObject().put("version",1)
            if(capability!="calendar.deleteAllOwned") {
                args.put("title","Fixture").put("start",1893456000000L).put("end",1893459600000L)
                    .put("timeZone","UTC").put("deviceTimeZone","UTC").put("reminderMinutes",0)
                if(capability=="calendar.createOwned") args.put("calendarRef","c".repeat(64)).put("eventKey","fixture")
                else args.put("eventRef","d".repeat(64))
            }
            for(mode in listOf("revision","state","archive","permission")) fixture(
                CapabilityRequest("write",capability,args)) { permissions,store,tool,key ->
                var calls=0
                val result=CapabilityBroker(permissions,store).executeOwned(key) { effect ->
                    calls++
                    when(mode) {
                        "revision" -> store.commitTool(tool.copy(revision=2),tool,true)
                        "state" -> store.commitTool(tool.copy(stateVersion=2),tool,false)
                        "archive" -> store.setArchived(tool,true)
                        "permission" -> permissions.allowed=false
                    }
                    CapabilityResult("succeeded","Adapter reply",JSONObject().put("version",1).put("total",0).put("deleted",0).put("unknown",0).put("remaining",0))
                }
                consistent(store,key,result,"unknownOutcome")
                assertEquals(0,result.data.length()); assertEquals(1,calls)
                OwnedCalendarContract.validateResult(capability,result)
            }
        }
    }
}
