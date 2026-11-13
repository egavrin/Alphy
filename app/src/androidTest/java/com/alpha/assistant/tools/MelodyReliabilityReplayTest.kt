package com.alpha.assistant.tools

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.capabilities.TorchSequence
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.generation.sameToolJson
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MelodyReliabilityReplayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val case by lazy {
        instrumentation.context.assets.open("generation-reliability/melody-recorded.json")
            .bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun replies(stage: CloudGenerationStage): List<String> {
        val array = case.getJSONArray("replies")
        return (0 until array.length()).map(array::getJSONObject)
            .filter { it.getString("stage") == stage.name }.map { it.getString("content") }
    }

    private inner class FixtureProvider(
        plans: List<String>, private val writer: String,
    ) : ChatProvider {
        private val planReplies = plans.iterator()
        val calls = mutableListOf<CloudRequest>()
        override suspend fun respond(
            key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit,
        ) {
            calls += request
            val reply = when (request.generationStage) {
                CloudGenerationStage.PLAN -> planReplies.next()
                CloudGenerationStage.COMPLETE -> when {
                    calls.count { it.generationStage == CloudGenerationStage.COMPLETE } == 1 -> writer
                    else -> throw CloudFailure("offline_fixture_end", request.turns.last().text.take(500))
                }
                else -> error("Unexpected stage: ${request.generationStage}")
            }
            val planned = if (request.generationStage == CloudGenerationStage.PLAN && request.turns.size == 1) {
                JSONObject(reply).put("requiredCapabilities", JSONArray().put(JSONObject()
                    .put("id", "audio.torchSequence").put("excerpt", case.getString("request").take(300)))).toString()
            } else reply
            emit(CloudEvent.Text(planned))
        }
    }

    private suspend fun generate(provider: FixtureProvider, events: MutableList<ToolGenerationTraceEvent>): ToolPreview =
        ToolGenerationService(provider, SandboxClient(context)).generate(
            "offline", case.getString("context"), null, emptyList(), CloudSession(), "melody-replay",
            privateState = false, draftPreview = null, originalRequest = case.getString("request"),
            trace = ToolGenerationTrace(UUID.randomUUID(), { events += it }),
        )

    @Test fun recordedMelodyReplansAtTheRequiredEffectFence(): Unit = runBlocking {
        val corrected = """{"fields":{"behavior":{"status":"unchecked","reason":"external_outcome"}}}"""
        val writer = replies(CloudGenerationStage.COMPLETE).first()
        val provider = FixtureProvider(replies(CloudGenerationStage.PLAN) + corrected, writer)
        val events = mutableListOf<ToolGenerationTraceEvent>()
        val result = generate(provider, events)
        val rejection = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
            .single { it.planShape?.rule == "behavior.required_receipt" }
        assertEquals(ToolGenerationStage.PLAN, rejection.stage)
        assertEquals(BehaviorFailureRule.EXTERNAL_RESULT_REQUIRED, rejection.behaviorBinding?.rule)
        assertEquals(2, rejection.behaviorBinding?.step)
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.PLAN),
            events.filterIsInstance<ToolGenerationTraceEvent.Repair>().map { it.providerStage })
        assertEquals(1, provider.calls.count { it.generationStage == CloudGenerationStage.COMPLETE })
        assertTrue(sameToolJson(JSONObject(writer).apply { remove("behaviorBindings") },
            JSONObject(result.spec.providerDefinition())))
        assertEquals(setOf("audio.torchSequence"), result.spec.capabilities)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun generatedMelodyWaitsForTheMatchingOfflineReceipt() = runBlocking {
        val plan = JSONObject(replies(CloudGenerationStage.PLAN).first())
            .put("behavior", JSONObject("""{"status":"unchecked","reason":"external_outcome"}"""))
        val writer = JSONObject("""{"formatVersion":3,"title":"Melody","description":"Play a melody with beat pulses",
            "capabilities":["audio.torchSequence"],"capabilityResults":true,
            "stateSchema":{"status":"string","beats":"number","canPlay":"boolean","pending":"string"},
            "initialState":{"status":"idle","beats":0,"canPlay":true,"pending":""},
            "ui":{"type":"column","children":[{"type":"button","text":"Play","action":"play","enabledBind":"canPlay"},
                {"type":"metric","text":"Beats","bind":"beats"},{"type":"text","text":"Status","bind":"status"}]}}""")
            .put("source", """
                const e = input.event, s = input.state;
                if (e.type === 'action' && e.actionId === 'play' && s.canPlay) {
                    s.status = 'playing'; s.beats = 0; s.canPlay = false; s.pending = 'sequence';
                    return {state:s,effects:[{id:s.pending,capability:'audio.torchSequence',
                        args:{notes:'60,64,67,72',bpm:120,durationMillis:5000,torchPulseMillis:80}}]};
                }
                if (e.type === 'capabilityResults' && s.pending) {
                    const result = e.entries.find(entry => entry.capability === 'audio.torchSequence' && entry.requestId === s.pending);
                    if (result) {
                        s.status = result.status === 'succeeded' ? 'finished' : result.status;
                        s.beats = result.status === 'succeeded' ? result.data.beats : 0;
                        s.canPlay = true; s.pending = '';
                    }
                }
                return {state:s};
            """.trimIndent())
        val provider = FixtureProvider(listOf(plan.toString()), writer.toString())
        val events = mutableListOf<ToolGenerationTraceEvent>()
        val preview = generate(provider, events)
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE), provider.calls.map { it.generationStage })
        assertTrue(provider.calls.first().systemInstructions.contains("when the requested outcome depends on a native receipt"))
        assertTrue(provider.calls.last().systemInstructions.contains("matching capabilityResults receipt"))
        assertTrue(preview.effects.isEmpty())
        assertTrue(events.none { it is ToolGenerationTraceEvent.Repair })

        val database = "melody-replay-${UUID.randomUUID()}.db"
        try {
            ToolStore(context, database, permissionCheck = { true }).use { store ->
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                try {
                    for (mode in listOf("succeeded", "failed", "cancelled", "stale")) {
                        val saved = ToolRecord(mode, 1, 1, preview.spec.json, preview.state, preview.ui, null, true)
                        store.saveTool(saved, revision = true)
                        val playing = engine.interact(saved.id, saved.revision, saved.stateVersion, "play", null)
                        val pending = JSONObject(playing.state)
                        assertEquals("playing", pending.getString("status"))
                        assertEquals(0, pending.getInt("beats"))
                        assertFalse(pending.getBoolean("canPlay"))
                        val effect = store.effects(saved.id).single()
                        assertEquals(TorchSequence.ID, effect.request.capability)
                        val sequence = TorchSequence.parse(effect.request.args)
                        assertEquals(8000, sequence.sampleRate)
                        assertEquals(40_000, sequence.samples.size)
                        assertEquals((0L..4500L step 500).toList(), sequence.beats)
                        val receipt = JSONObject().put("sampleRate", sequence.sampleRate)
                            .put("sampleCount", sequence.samples.size).put("beats", sequence.beats.size)
                            .put("pcmSha256", sequence.pcmSha256).put("physicalVerified", false)
                            .put("pulses", JSONArray(sequence.beats.map { JSONArray().put(it).put(it + sequence.pulseMillis) }))
                        assertNotNull(store.claimEffect(effect.key))
                        engine.deliverForegroundEffectResults(playing) { true }
                        assertEquals(playing, store.tool(saved.id))
                        assertEquals("pending", store.deliveries(saved.id).single().phase)
                        if (mode == "stale") {
                            store.commitTool(playing.copy(revision = 2, stateVersion = playing.stateVersion + 1), playing, true)
                        }
                        // The fixture completes the claimed receipt directly. No native playback adapter runs.
                        val result = if (mode == "succeeded" || mode == "stale") CapabilityResult("succeeded", "Offline receipt", receipt)
                            else CapabilityResult(mode, "Offline receipt")
                        store.finishEffect(effect.key, result)
                        engine.deliverForegroundEffectResults(checkNotNull(store.tool(saved.id))) { true }
                        val after = checkNotNull(store.tool(saved.id))
                        val state = JSONObject(after.state)
                        val displayed = JSONObject(after.ui).getJSONArray("children").getJSONObject(1)
                        assertEquals(if (mode == "succeeded") 10 else 0, ToolUiCatalog.value(state, displayed.getString("bind")))
                        assertEquals(when (mode) { "succeeded" -> "finished"; "stale" -> "playing"; else -> mode }, state.getString("status"))
                        assertEquals(if (mode == "stale") "rejected" else "committed", store.deliveries(saved.id).single().phase)
                        engine.deliverForegroundEffectResults(after) { true }
                        assertEquals(after, store.tool(saved.id))
                        assertEquals(1, store.deliveries(saved.id).size)
                        Log.i("WorkflowReliability", "bench:melody offline $mode status=${state.getString("status")} beats=${state.getInt("beats")}")
                    }
                } finally {
                    engine.closeArtifacts()
                }
            }
        } finally {
            context.deleteDatabase(database)
        }
    }
}
