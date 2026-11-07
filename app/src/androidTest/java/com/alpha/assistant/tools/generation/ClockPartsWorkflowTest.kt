package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClockPartsWorkflowTest {
    @Test fun nativeClocksPauseResetAndReopenWithoutACodeWriter(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "clock-parts-${UUID.randomUUID()}.db"
        val store = ToolStore(context, name)
        var now = Instant.parse("2030-01-01T09:00:00Z")
        var boot = 1
        fun clock() = PartClockSample.from(now, ZoneId.of("UTC")).copy(bootCount = boot)
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                val output = when (request.generationStage) {
                    CloudGenerationStage.PLAN -> """{"title":"Clocks","description":"","language":"en","inputs":[],
                        "constants":[{"id":"seconds","value":10}],"needs":[
                        {"id":"down","type":"countdown","label":"Countdown","description":"Ten seconds","uses":{"length":"seconds"}},
                        {"id":"up","type":"stopwatch","label":"Stopwatch","description":"Measure elapsed seconds"}],"questions":[],
                        "behavior":{"status":"unchecked","reason":"external_outcome"}}"""
                    CloudGenerationStage.SELECTOR -> {
                        val questions = JSONObject(request.turns.single().text).getJSONArray("questions")
                        JSONObject().put("answers", jsonArray((0 until questions.length()).map { i ->
                            val question = questions.getJSONObject(i)
                            val choices = question.getJSONObject("choices")
                            JSONObject().put("id", question.getString("id")).put("choice", "A").put("confidence", 1)
                                .put("probabilities", JSONObject().apply { choices.keys().forEach { put(it, if (it == "A") 1 else 0) } })
                        })).toString()
                    }
                    else -> error("Native clocks must not call the writer.")
                }
                emit(CloudEvent.Text(output))
            }
        }
        val service = ToolGenerationService(provider, SandboxClient(context), ::clock)
        val engine = ToolEngine(store, service)
        try {
            val draft = engine.createDraft("A ten-second countdown and a stopwatch.", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            var saved = engine.accept(draft.id)
            suspend fun act(id: String) { saved = engine.interact(saved.id, saved.revision, saved.stateVersion, id, null) }
            fun shown(id: String): Double {
                val spec = ToolSpec.parsePersisted(checkNotNull(store.tool(saved.id)).spec)
                return spec.composition!!.projectState(JSONObject(saved.state), clock())
                    .getDouble("${partInstanceId(id)}_value")
            }
            act("p4_down_start"); act("p2_up_start")
            now = now.plusSeconds(3)
            assertEquals(7.0, shown("down"), 0.0)
            assertEquals(3.0, shown("up"), 0.0)
            act("p4_down_pause"); act("p2_up_pause")
            now = now.plusSeconds(20)
            assertEquals(7.0, shown("down"), 0.0)
            assertEquals(3.0, shown("up"), 0.0)
            act("p4_down_start")
            now = now.plusSeconds(8)
            assertEquals(0.0, shown("down"), 0.0)
            act("p4_down_reset"); act("p2_up_reset")
            assertEquals(10.0, shown("down"), 0.0)
            assertEquals(0.0, shown("up"), 0.0)
            act("p2_up_start")
            now = now.plusSeconds(2)
            act("p2_up_pause"); act("p2_up_start")
            boot++
            now = now.plusSeconds(40)
            assertEquals(2.0, shown("up"), 0.0)
            act("p2_up_start")
            now = now.plusSeconds(3)
            assertEquals(5.0, shown("up"), 0.0)
        } finally {
            engine.closeArtifacts()
            store.close()
            context.deleteDatabase(name)
            context.deleteDatabase(store.automationDatabase)
        }
    }
}
