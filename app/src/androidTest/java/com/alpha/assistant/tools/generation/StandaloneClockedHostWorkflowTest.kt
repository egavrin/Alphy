package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.automations.AutomationTools
import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.AndroidJsRuntime
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ArtifactCoordinator
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.projectToolState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
class StandaloneClockedHostWorkflowTest {
    private val due = """{"id":"due","type":"due_items","label":"Deadlines","description":"Track deadlines",
        "uses":{"window":"days"},"items":[{"name":"Filter","offsetDays":1,"repeatDays":14}]}"""
    private val ideas = """{"id":"ideas","type":"idea_deck","label":"Ideas","description":"Pick a daily idea",
        "ideas":[{"name":"Sketch","minutes":10,"effort":"low"},{"name":"Read","minutes":15,"effort":"medium"}]}"""
    private val today = PartClockSample(LocalDate.of(2026, 3, 8), 1439)
    private val tomorrow = PartClockSample(today.localDate.plusDays(1), 0)

    @Test fun dueIdeaAndMultiPartGenerateSaveAndOpenWithNoJavaScriptRuntime(): Unit = runBlocking {
        for (needs in listOf(due, ideas, "$due,$ideas")) withHost(needs) { host ->
            val saved = host.create()
            val spec = ToolSpec.parsePersisted(saved.spec)
            assertTrue(spec.isSealedStandalone)
            assertEquals(1, host.store.revisions(saved.id).size)
            assertEquals(if (needs == "$due,$ideas") 2 else 1, spec.ui.getJSONArray("children").length())
            val opened = host.engine.openArtifact(saved)
            val owner = Any()
            val coordinator = ArtifactCoordinator.get(host.store, saved.id)
            val second = coordinator.ordered { openNonGraphics(host.store,host.generation,saved.id,saved.revision,owner) { true } }
            assertEquals(saved, opened.tool)
            assertEquals(saved, second.tool)
            coordinator.ordered { closeOwned(host.store,saved.revision,owner,second.sessionGeneration) }
            host.engine.closeArtifact(saved.id,saved.revision,opened.sessionGeneration)
            assertEquals(saved, host.store.tool(saved.id))
            assertEquals(saved, host.engine.openArtifact(saved).tool)
            for (type in listOf("preview","render","open")) {
                val result = host.generation.executeEvent(spec,saved.id,saved.revision,saved.state,event(type),saved.ui) { _, _ ->
                    error("A sealed standalone event called the executor.")
                }
                assertEquals(saved.state,result.state)
                assertTrue(result.effects.isEmpty())
                assertEquals(0,result.records.length())
            }
            assertTrue(host.store.effects(saved.id).isEmpty())
            assertEquals(0, host.sandboxProbes)
        }
    }

    @Test fun midnightRejectsOldIdeaAndCancellationKeepsTheNewOccurrenceUnchanged(): Unit = runBlocking {
        withHost(ideas) { host ->
            val saved = host.create()
            val spec = ToolSpec.parsePersisted(saved.spec)
            val list = spec.ui.getJSONArray("children").getJSONObject(0).getJSONArray("children").getJSONObject(4)
            val action = list.getJSONArray("rowActions").getJSONObject(1).getString("action")
            fun occurrence(sample: PartClockSample) = projectToolState(spec,JSONObject(saved.state),sample)
                .getJSONArray(list.getString("bind")).getJSONObject(0).getString("id")
            val old = occurrence(today)
            host.clock = tomorrow
            val current = occurrence(tomorrow)
            assertNotEquals(old,current)
            assertTrue(runCatching { host.engine.interact(saved.id,saved.revision,saved.stateVersion,action,old) }.isFailure)
            coroutineScope {
                val pending = async(start = CoroutineStart.LAZY) {
                    host.engine.interact(saved.id,saved.revision,saved.stateVersion,action,current)
                }
                host.sampled = { pending.cancel() }
                pending.start()
                try { assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException) }
                finally { host.sampled = {} }
            }
            assertEquals(saved,host.store.tool(saved.id))
            val calls = host.samples
            val changed = host.engine.interact(saved.id,saved.revision,saved.stateVersion,action,current)
            assertEquals(calls+1,host.samples)
            assertEquals(saved.stateVersion+1,changed.stateVersion)
            assertEquals(0,JSONObject(changed.state).getJSONArray(list.getString("bind")).length())
            assertEquals("Read",projectToolState(spec,JSONObject(changed.state),tomorrow)
                .getJSONArray(list.getString("bind")).getJSONObject(0).getString("name"))
            assertTrue(host.store.effects(saved.id).isEmpty())
            assertEquals(0,host.sandboxProbes)
        }
    }

    @Test fun forgedInputAndRefinementCannotChangeTheSealedRecord(): Unit = runBlocking {
        withHost(due) { host ->
            val draft = host.engine.createDraft("Track filter replacement",null)
            val prepared = host.engine.generate(draft.id,"fixture",emptyList(),CloudSession())
            val spec = ToolSpec.parsePersisted(checkNotNull(prepared.candidate))
            val forged = projectToolState(spec,JSONObject(prepared.previewState!!),today).toString()
            host.store.saveDraft(prepared.copy(previewState = forged))
            assertTrue(runCatching { host.engine.accept(draft.id) }.isFailure)
            assertTrue(host.store.tools().isEmpty())
            host.store.saveDraft(prepared)
            val saved = host.engine.accept(draft.id)
            val providerStages = host.stages.toList()
            assertTrue(runCatching { host.engine.createDraft("Edit",saved.id) }.exceptionOrNull() is StandaloneRefinementUnavailable)
            assertTrue(runCatching {
                host.generation.generate("fixture","Edit",saved,emptyList(),CloudSession(),saved.id)
            }.exceptionOrNull() is StandaloneRefinementUnavailable)
            assertEquals(providerStages,host.stages)
            assertTrue(host.store.drafts().isEmpty())
            val forgedUi = JSONObject(saved.ui).apply { getJSONArray("children").getJSONObject(0).put("partId","counter") }
            val forgedEvents = listOf(
                event("action").put("actionId","fill_forged").put("value",JSONObject.NULL),
                event("preview").put("extra",true),
                event("capabilityResults"),
                event("recordResults"),
            )
            for (invalid in forgedEvents) {
                assertTrue(runCatching { host.generation.evaluate(spec,saved.id,saved.revision,saved.state,invalid,saved.ui) }.isFailure)
            }
            assertTrue(runCatching {
                host.generation.evaluate(spec,saved.id,saved.revision,saved.state,event("open"),forgedUi.toString())
            }.isFailure)
            assertEquals(saved,host.store.tool(saved.id))
            assertTrue(host.store.effects(saved.id).isEmpty())
            assertEquals(0,host.sandboxProbes)
        }
    }

    @Test fun automationCannotSetASealedFieldAndStillUpdatesAnOrdinaryMixedFill(): Unit = runBlocking {
        withHost(ideas) { host ->
            fun action(tool: ToolRecord, field: String) = CapabilityRequest("set","tool.state.set",JSONObject()
                .put("toolId",tool.id).put("revision",tool.revision).put("field",field).put("value",7))
            val saved = host.create()
            val history = host.store.revisions(saved.id)
            val field = JSONObject(saved.state).keys().asSequence().single { it.endsWith("_limit") }
            val sealedAction = action(saved,field)
            val testFailure = runCatching {
                AutomationTools.planTestWrite(saved,sealedAction) { _, _ ->
                    error("A sealed standalone write reached render.")
                }
            }.exceptionOrNull()
            assertTrue(testFailure is IllegalArgumentException)
            val scheduledFailure = runCatching {
                AutomationTools.execute(host.store,sealedAction,false,{ _, _ ->
                    error("A sealed standalone write reached render.")
                }) { true }
            }.exceptionOrNull()
            assertTrue(scheduledFailure is IllegalArgumentException)
            assertEquals(saved,host.store.tool(saved.id))
            assertEquals(history,host.store.revisions(saved.id))
            assertTrue(host.store.effects(saved.id).isEmpty())

            val extra = """{"id":"help","type":"action","label":"Help","description":"Custom help"}"""
            val assembly = PartsGeneration.build(NeedsPlan.parse(plan("$ideas,$extra")),null,false,today,ask=::selectorAnswer)
            val mixed = ToolComposition.complete(assembly, """
                {"capabilities":[],"stateSchema":{"fill_value":"number"},"initialState":{"fill_value":0},
                 "ui":{"type":"column","children":[{"type":"text","text":"Help","bind":"fill_value"}]},
                 "source":"return {state:input.state};"}
            """.trimIndent())
            val ordinary = ToolRecord(UUID.randomUUID().toString(),1,1,mixed.json,mixed.initialState,mixed.ui.toString(),null,true)
            host.store.commitTool(ordinary,null,true)
            val mixedAction = action(ordinary,"fill_value")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val render = AutomationTools.isolatedRenderer(context)
            val preview = AutomationTools.planTestWrite(ordinary,mixedAction,render)
            assertEquals(7,JSONObject(preview.after.state).getInt("fill_value"))
            assertEquals(ordinary,host.store.tool(ordinary.id))
            assertEquals("succeeded",AutomationTools.execute(host.store,mixedAction,false,render) { true }.status)
            assertEquals(preview.after,host.store.tool(ordinary.id))
            assertTrue(host.store.effects(ordinary.id).isEmpty())
        }
    }

    private fun event(type: String) = JSONObject().put("type",type)

    private fun plan(needs: String): String {
        val constants = if (due in needs) """[{"id":"days","value":3}]""" else "[]"
        return """{"title":"Clocked fixture","description":"","language":"en","inputs":[],"questions":[],
            "constants":$constants,"needs":[$needs]}"""
    }

    private fun selectorAnswer(body: String): String {
        val questions = JSONObject(body).getJSONArray("questions")
        val rows = (0 until questions.length()).map { index ->
            val question = questions.getJSONObject(index)
            JSONObject().put("id",question.getString("id")).put("choice","A").put("confidence",1)
                .put("probabilities",JSONObject().apply {
                    question.getJSONObject("choices").keys().forEach { put(it,if(it=="A") 1 else 0) }
                })
        }
        return JSONObject().put("answers",JSONArray(rows)).toString()
    }

    private suspend fun withHost(needs: String, work: suspend (Host) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "standalone-clock-${UUID.randomUUID()}.db"
        try {
            ToolStore(context,database).use { store ->
                val host = Host(store,needs)
                try { work(host) } finally { host.engine.closeArtifacts() }
            }
        } finally { context.deleteDatabase(database) }
    }

    private inner class Host(val store: ToolStore, needs: String) {
        var clock = today
        var samples = 0
        var sampled: () -> Unit = {}
        var sandboxProbes = 0
        val stages = mutableListOf<CloudGenerationStage>()
        private val provider = object : ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent) -> Unit) {
                val stage = checkNotNull(request.generationStage)
                stages += stage
                val answer = when(stage) {
                    CloudGenerationStage.PLAN -> plan(needs)
                    CloudGenerationStage.SELECTOR -> selectorAnswer(request.turns.single().text)
                    else -> error("A complete clocked app requested generated code.")
                }
                emit(CloudEvent.Text(answer))
            }
        }
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val sandbox = SandboxClient(context,AndroidJsRuntime(context,supported = { sandboxProbes++; false }))
        val generation = ToolGenerationService(provider,sandbox,partClock = { samples++; sampled(); clock })
        val engine = ToolEngine(store,generation)

        suspend fun create(): ToolRecord {
            val draft = engine.createDraft("Create a local clocked mini-app",null)
            engine.generate(draft.id,"fixture",emptyList(),CloudSession())
            assertEquals(listOf(CloudGenerationStage.PLAN,CloudGenerationStage.SELECTOR,CloudGenerationStage.SELECTOR),stages)
            return engine.accept(draft.id)
        }
    }
}
