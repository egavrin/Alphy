package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.AndroidJsRuntime
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.NativeToolEditRequest
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StandaloneNativeEditHostWorkflowTest {
    private val clock = PartClockSample(LocalDate.of(2026,3,8),540)
    private val requestText = "Move the filter deadline to tomorrow"

    @Test fun reviewIsPrivateAndOneConfirmationSavesAndReopensTheExactEdit(): Unit = runBlocking {
        withHost { host, before ->
            val request = NativeToolEditRequest("LOCAL-REQUEST-ID")
            val preview = host.engine.prepareNativeEdit(before,request,requestText,"LOCAL-KEY")
            val call = host.calls.single()
            assertEquals(StandaloneNativeEdit.providerGuidance,call.systemInstructions)
            assertFalse(call.search)
            val turn = call.turns.single()
            assertTrue(turn.images.isEmpty())
            val payload = JSONObject(turn.text)
            assertEquals(setOf("context","request"),payload.keys().asSequence().toSet())
            assertEquals(requestText,payload.getString("request"))
            val context = payload.getJSONObject("context")
            assertEquals(setOf("token","parts"),context.keys().asSequence().toSet())
            for (secret in listOf(before.id,before.conversationId!!,request.id,"LOCAL-KEY","2026-03-05","2026-03-22",
                "p3_due_items","occurrence","p5_ideas_history","cursor")) {
                assertFalse(secret,turn.text.contains(secret))
            }
            assertTrue(preview.before.stateJson.contains("2026-03-22"))
            assertTrue(preview.after.stateJson.contains("2026-03-09"))
            assertEquals(before,host.store.tool(before.id))
            assertEquals(1,host.store.revisions(before.id).size)
            assertTrue(host.store.drafts().isEmpty())

            val outcomes = coroutineScope {
                List(2) { async(Dispatchers.Default) { runCatching { host.engine.confirmNativeEdit(request) } } }.awaitAll()
            }
            val saved = outcomes.single { it.isSuccess }.getOrThrow()
            assertEquals("tool_native_edit_stale",(outcomes.single { it.isFailure }.exceptionOrNull() as? CloudFailure)?.code)
            assertEquals(before.revision+1,saved.revision)
            assertEquals(before.stateVersion+1,saved.stateVersion)
            assertEquals(before.conversationId,saved.conversationId)
            assertEquals(before.lastConversationId,saved.lastConversationId)
            assertEquals(JSONObject(before.state).getJSONArray("p5_ideas_history").toString(),
                JSONObject(saved.state).getJSONArray("p5_ideas_history").toString())
            assertEquals(0,JSONObject(saved.state).getJSONArray("p3_due_display").length())
            assertEquals("2026-03-09",JSONObject(saved.state).getJSONArray("p3_due_items").getJSONObject(0).getString("date"))
            assertEquals(saved,host.engine.openArtifact(saved).tool)
            assertEquals(2,host.store.revisions(saved.id).size)
            assertTrue(host.engine.restoreAvailability(saved,before.revision).reason != null)
            assertFailure("tool_native_edit_stale") { host.engine.confirmNativeEdit(request) }
            assertEquals(saved,host.store.tool(saved.id))
            assertTrue(host.store.effects(saved.id).isEmpty())
            assertEquals(0,host.sandboxProbes)
        }
    }

    @Test fun cancellationDuringProviderWorkAndAfterPreviewCannotCommit(): Unit = runBlocking {
        withHost { host, before ->
            val inFlight = NativeToolEditRequest("in-flight")
            val started = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            host.providerGate = { started.complete(Unit); returned.await() }
            coroutineScope {
                val task = async {
                    runCatching { host.engine.prepareNativeEdit(before,inFlight,requestText,"LOCAL-KEY") }
                }
                started.await()
                inFlight.cancel()
                returned.complete(Unit)
                assertTrue(task.await().isFailure)
            }
            assertFailure("tool_native_edit_stale") { host.engine.confirmNativeEdit(inFlight) }
            host.providerGate = {}
            val reviewed = NativeToolEditRequest("reviewed")
            host.engine.prepareNativeEdit(before,reviewed,requestText,"LOCAL-KEY")
            reviewed.cancel()
            assertFailure("tool_native_edit_stale") { host.engine.confirmNativeEdit(reviewed) }
            assertEquals(before,host.store.tool(before.id))
            assertEquals(1,host.store.revisions(before.id).size)
            assertTrue(host.store.drafts().isEmpty())
        }
    }

    @Test fun staleStateDayAndForgedProviderPlanLeaveCurrentWorkIntact(): Unit = runBlocking {
        withHost { host, before ->
            val stale = NativeToolEditRequest("stale")
            host.engine.prepareNativeEdit(before,stale,requestText,"LOCAL-KEY")
            val changed = host.engine.interact(before.id,before.revision,before.stateVersion,"p3_due_complete","due1@1")
            assertFailure("tool_native_edit_stale") { host.engine.confirmNativeEdit(stale) }
            val midnight = NativeToolEditRequest("midnight")
            host.engine.prepareNativeEdit(changed,midnight,requestText,"LOCAL-KEY")
            host.clock = clock.copy(localDate = clock.localDate.plusDays(1))
            assertFailure("tool_native_edit_stale") { host.engine.confirmNativeEdit(midnight) }
            val forged = NativeToolEditRequest("forged")
            host.forgeToken = true
            assertFailure("tool_native_edit_unsupported") { host.engine.prepareNativeEdit(changed,forged,requestText,"LOCAL-KEY") }
            assertFailure("tool_native_edit_stale") { host.engine.confirmNativeEdit(forged) }
            assertEquals(changed,host.store.tool(changed.id))
            assertEquals(1,host.store.revisions(changed.id).size)
            assertTrue(host.store.effects(changed.id).isEmpty())
        }
    }

    @Test fun automationImpactIsCapturedAfterTheProviderAndStopsOnlyTheReviewedEnabledRuleOnce(): Unit = runBlocking {
        withHost { host, before ->
            AutomationStore(host.store.context,host.store.automationDatabase).use { rules ->
                val disabled = rules.save(null,dependentRule(before,"Private disabled rule"))
                val unrelated = rules.save(null,dependentRule(before.copy(id="another-app"),"Unrelated rule"))
                rules.approve(unrelated.id,unrelated.revision)
                lateinit var enabledId: String
                host.providerGate = {
                    val enabled = rules.save(null,dependentRule(before,"Private enabled rule"))
                    enabledId = enabled.id
                    rules.approve(enabled.id,enabled.revision)
                    rules.setState("approval-archive:$enabledId","PRIVATE-APPROVAL-TOKEN")
                }
                val request = NativeToolEditRequest("automation-impact")
                val preview = host.engine.prepareNativeEdit(before,request,requestText,"LOCAL-KEY")
                assertEquals(listOf("Private enabled rule"),preview.stoppedAutomationNames)
                assertEquals(listOf("Private disabled rule"),preview.automationReviewNames)
                val turn = host.calls.single().turns.single().text
                for (hidden in listOf(enabledId,disabled.id,"Private disabled rule","Private enabled rule","PRIVATE-APPROVAL-TOKEN")) {
                    assertFalse(turn.contains(hidden))
                }
                assertTrue(rules.active(enabledId,1))
                assertTrue(rules.history(enabledId).isEmpty())
                val saved = host.engine.confirmNativeEdit(request)
                assertEquals(saved,host.store.tool(before.id))
                assertFalse(rules.active(enabledId,1))
                assertFalse(rules.rule(disabled.id)!!.enabled)
                assertTrue(rules.rule(disabled.id)!!.attention.isNotBlank())
                assertTrue(rules.active(unrelated.id,unrelated.revision))
                assertEquals(listOf("revoked"),rules.history(enabledId).map { it.status })
                assertTrue(rules.history(disabled.id).isEmpty())
                assertEquals(listOf(enabledId),host.store.revisions(before.id).first { it.revision==saved.revision }
                    .stoppedAutomations.map { it.id })
                assertFailure("tool_native_edit_stale") { host.engine.confirmNativeEdit(request) }
                assertEquals(1,rules.history(enabledId).size)
            }
        }
    }

    @Test fun addedRemovedPausedEditedOrReapprovedRulesInvalidateTheReviewedImpactWithoutWrites(): Unit = runBlocking {
        for (change in listOf("add","remove","pause","edit","reapprove","add-disabled")) withHost { host, before ->
            AutomationStore(host.store.context,host.store.automationDatabase).use { rules ->
                val rule = rules.save(null,dependentRule(before,"Reviewed rule"))
                rules.approve(rule.id,rule.revision)
                val request = NativeToolEditRequest("impact-$change")
                val preview = host.engine.prepareNativeEdit(before,request,requestText,"LOCAL-KEY")
                assertEquals(listOf("Reviewed rule"),preview.stoppedAutomationNames)
                assertTrue(preview.automationReviewNames.isEmpty())
                when (change) {
                    "add" -> rules.save(null,dependentRule(before,"Reviewed rule")).let { rules.approve(it.id,it.revision) }
                    "remove" -> {
                        rules.stopForDeletion(rule.id,rule.revision)
                        rules.removeStoppedRule(rule.id,rule.revision)
                    }
                    "pause" -> assertTrue(rules.pause(rule.id,rule.revision))
                    "edit" -> rules.save(rule.id,dependentRule(before,"Renamed rule"),rule.revision)
                        .let { rules.approve(it.id,it.revision) }
                    "reapprove" -> {
                        assertTrue(rules.pause(rule.id,rule.revision))
                        rules.approve(rule.id,rule.revision)
                    }
                    "add-disabled" -> rules.save(null,dependentRule(before,"Added disabled rule"))
                }
                fun snapshot() = rules.rules().map {
                    listOf(it.id,it.revision,it.approvedRevision,it.enabled,it.definition.json,it.nextAt,it.attention,
                        rules.approvalToken(it.id),rules.history(it.id))
                }
                val afterChange = snapshot()
                assertFailure("tool_native_edit_impact_changed") { host.engine.confirmNativeEdit(request) }
                assertEquals(change,afterChange,snapshot())
                assertEquals(before,host.store.tool(before.id))
                assertEquals(1,host.store.revisions(before.id).size)
                assertTrue(host.store.effects(before.id).isEmpty())
                assertFailure("tool_native_edit_stale") { host.engine.confirmNativeEdit(request) }
            }
        }
    }

    private fun dependentRule(tool: ToolRecord,name: String) = JSONObject().put("name",name)
        .put("trigger",JSONObject().put("type","manual")).put("conditions",JSONObject())
        .put("actions",JSONArray().put(JSONObject().put("id","set").put("capability","tool.state.set")
            .put("args",JSONObject().put("toolId",tool.id).put("revision",tool.revision)
                .put("field","p5_ideas_limit").put("value",7))))

    private suspend fun assertFailure(code: String, work: suspend () -> Any?) {
        assertEquals(code,(runCatching { work() }.exceptionOrNull() as? CloudFailure)?.code)
    }

    private suspend fun withHost(work: suspend (Host, ToolRecord) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "native-edit-host-${UUID.randomUUID()}.db"
        try {
            ToolStore(context,database).use { store ->
                val host = Host(store)
                try {
                    val spec = fixtureSpec()
                    val seed = ToolRecord("LOCAL-APP-ID",1,1,spec.json,spec.initialState,spec.ui.toString(),"LOCAL-CHAT-ID",true)
                    store.commitTool(seed,null,true)
                    val due = host.engine.interact(seed.id,seed.revision,seed.stateVersion,"p3_due_complete","due1@0")
                    val before = host.engine.interact(seed.id,seed.revision,due.stateVersion,"p5_ideas_complete","idea1@2026-03-08#0")
                    work(host,before)
                } finally { host.engine.closeArtifacts() }
            }
        } finally {
            context.deleteDatabase(database)
            context.deleteDatabase("$database-automations.db")
        }
    }

    private suspend fun fixtureSpec() = ToolComposition.sealStandalone(PartsGeneration.build(NeedsPlan.parse("""
        {"title":"Workshop","description":"","language":"en","inputs":[],"questions":[],
         "constants":[{"id":"days","value":3}],"needs":[
          {"id":"due","type":"due_items","label":"Deadlines","description":"Track filter","uses":{"window":"days"},
           "items":[{"name":"Filter","date":"2026-03-05","repeatDays":14}]},
          {"id":"ideas","type":"idea_deck","label":"Break","description":"Pick an idea",
           "ideas":[{"name":"Sketch","minutes":5,"effort":"low"},{"name":"Read","minutes":10,"effort":"medium"}]}]}
    """.trimIndent()),null,false,clock) { body ->
        val questions = JSONObject(body).getJSONArray("questions")
        JSONObject().put("answers",jsonArray((0 until questions.length()).map { index ->
            JSONObject().put("id",questions.getJSONObject(index).getString("id")).put("choice","A").put("confidence",1)
                .put("probabilities",JSONObject().put("A",1).put("B",0))
        })).toString()
    })

    private inner class Host(val store: ToolStore) {
        var clock = this@StandaloneNativeEditHostWorkflowTest.clock
        var sandboxProbes = 0
        var forgeToken = false
        var providerGate: suspend () -> Unit = {}
        val calls = mutableListOf<CloudRequest>()
        private val provider = object : ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent) -> Unit) {
                calls += request
                providerGate()
                val token = JSONObject(request.turns.single().text).getJSONObject("context").getString("token")
                val operation = JSONObject().put("op","update_due").put("part","p1").put("slot","s1").put("offsetDays",1)
                emit(CloudEvent.Text(JSONObject().put("token",if(forgeToken) "forged" else token)
                    .put("operations",JSONArray().put(operation)).toString()))
            }
        }
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = ToolEngine(store,ToolGenerationService(provider,
            SandboxClient(context,AndroidJsRuntime(context,supported = {sandboxProbes++;false})),partClock = {clock}))
    }
}
