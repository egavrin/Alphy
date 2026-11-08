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
    @Test fun stopwatchKeepsItsNativeClockWhenPresentationIsInvalid(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stopwatch-parts-${UUID.randomUUID()}.db"
        val store = ToolStore(context, name)
        var elapsed = 10_000L
        fun clock() = PartClockSample.from(Instant.EPOCH, ZoneId.of("UTC")).copy(elapsedMillis = elapsed, bootCount = 1)
        val stages = mutableListOf<CloudGenerationStage?>()
        val batches = mutableListOf<String>()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                stages += request.generationStage
                val output = when (request.generationStage) {
                    CloudGenerationStage.PLAN -> """{"title":"Stopwatch","description":"","language":"en","inputs":[],
                        "constants":[],"needs":[{"id":"clock","type":"stopwatch","label":"Elapsed","description":"Start, pause, and reset"}],
                        "questions":[]}"""
                    CloudGenerationStage.SELECTOR -> {
                        val body = JSONObject(request.turns.single().text)
                        batches += body.getString("stage")
                        selectorAnswer(body, invalidPresentation = true)
                    }
                    CloudGenerationStage.COMPLETE -> """{"formatVersion":3,"title":"Stopwatch","description":"",
                        "capabilities":[],"stateSchema":{"value":"number"},"initialState":{"value":0},
                        "ui":{"type":"column","children":[{"type":"metric","text":"Elapsed","bind":"value"},
                        {"type":"button","text":"Reset","action":"reset"}]},
                        "source":"return {state:input.state};"}"""
                    else -> error("Unexpected stopwatch stage.")
                }
                emit(CloudEvent.Text(output))
            }
        }
        val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context), ::clock))
        try {
            val draft = engine.createDraft("A simple stopwatch", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertEquals(listOf("sections", "parts"), batches)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR), stages)
            var saved = engine.accept(draft.id)
            val spec = ToolSpec.parsePersisted(saved.spec)
            assertNull(spec.nativePresentation)
            val clockPart = checkNotNull(spec.composition)
            val metric = JSONObject(saved.ui).getJSONArray("children").getJSONObject(0)
                .getJSONArray("children").getJSONObject(0)
            val visibleBinding = metric.getString("bind")
            assertEquals("metric", metric.getString("type"))
            assertEquals("${partInstanceId("clock")}_value", visibleBinding)
            fun shown() = clockPart.projectState(JSONObject(saved.state), clock()).getDouble(visibleBinding)
            suspend fun act(member: String) {
                saved = engine.interact(saved.id, saved.revision, saved.stateVersion, "${partInstanceId("clock")}_$member", null)
            }
            assertEquals(0.0, shown(), 0.0)
            act("start")
            elapsed += 3_000
            assertEquals(3.0, shown(), 0.0)
            act("pause")
            elapsed += 3_000
            assertEquals(3.0, shown(), 0.0)
            act("reset")
            assertEquals(0.0, shown(), 0.0)
        } finally {
            engine.closeArtifacts()
            store.close()
            context.deleteDatabase(name)
            context.deleteDatabase(store.automationDatabase)
        }
    }

    private fun selectorAnswer(body: JSONObject, invalidPresentation: Boolean = false): String {
        val questions = body.getJSONArray("questions")
        return JSONObject().put("answers", jsonArray((0 until questions.length()).map { index ->
            val question = questions.getJSONObject(index)
            val id = question.getString("id")
            val choices = question.getJSONObject("choices")
            val choice = if (invalidPresentation && id == "v_profile") "Z" else "A"
            JSONObject().put("id", id).put("choice", choice).put("confidence", 1)
                .put("probabilities", JSONObject().apply { choices.keys().forEach { put(it, if (it == "A") 1 else 0) } })
        })).toString()
    }

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
                        {"id":"up","type":"stopwatch","label":"Stopwatch","description":"Measure elapsed seconds"}],"questions":[]}"""
                    CloudGenerationStage.SELECTOR -> selectorAnswer(JSONObject(request.turns.single().text))
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
