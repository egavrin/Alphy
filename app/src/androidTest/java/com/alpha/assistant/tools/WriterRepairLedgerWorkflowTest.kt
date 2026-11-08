package com.alpha.assistant.tools

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.generation.GenerationRepair
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WriterRepairLedgerWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val request = "Сделай счёт очков для команд «Янтарь» и «Мел». За викторину начисляй 3 очка, за пазл — 2. Подсказка стоит 5 очков, в долг её брать нельзя. Покажи отдельный баланс каждой команды и историю начислений и списаний. Дай отменить последнее действие."
    private val plan = """{"wholeScreen":true,"behavior":{"status":"checkable","scenario":{
        "slots":{"quiz":{"kind":"action"},"amberBalance":{"kind":"value"},"chalkBalance":{"kind":"value"}},
        "steps":[{"op":"action","slot":"quiz"},{"op":"assert","slot":"amberBalance","test":"equals","value":3},
        {"op":"assert","slot":"chalkBalance","test":"equals","value":0}]}}}""".trimIndent()
    private val correctedBindings = """{"quiz":"postQuiz","amberBalance":[0,0],"chalkBalance":[0,1]}"""
    private val brokenBindings = """{"quiz":"postQuiz","amberBalance":[0,1],"chalkBalance":[0,1]}"""
    private val patch = JSONObject().put("behaviorBindings", JSONObject(correctedBindings)).toString()
    private val cutoffMs = 240_000L

    private fun definition(bindings: String): String {
        val artifact = instrumentation.context.assets.open("behavior-replay/ledger-with-prefixed-balances.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        return artifact.getJSONObject("spec").apply {
            remove("appLanguage")
            put("behaviorBindings", JSONObject(bindings))
        }.toString()
    }

    private suspend fun generate(
        initial: String,
        trace: ToolGenerationTrace? = null,
        correction: (CloudRequest) -> String = { error("The frozen candidate should pass without a correction.") },
    ): ToolPreview {
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                val text = when (request.generationStage) {
                    CloudGenerationStage.PLAN -> plan
                    CloudGenerationStage.COMPLETE -> if (request.turns.size == 1) initial else correction(request)
                    else -> error("The frozen whole-screen plan needs no other stage.")
                }
                emit(CloudEvent.Text(text))
            }
        }
        return ToolGenerationService(provider, SandboxClient(context)).generate(
            "fixture", request, null, emptyList(), CloudSession(), "ledger",
            privateState = false, draftPreview = null, trace = trace)
    }

    @Test fun patchAndReplacementPassTheSameFrozenLedgerCheck(): Unit = runBlocking {
        val original = definition(brokenBindings)
        val replacement = definition(correctedBindings)
        val events = mutableListOf<ToolGenerationTraceEvent>()
        var corrections = 0
        val repaired = generate(original, ToolGenerationTrace(UUID.randomUUID(), { events += it })) {
            assertEquals(original, it.turns[1].text)
            assertTrue(it.turns.last().text.contains("action at step 1, slot 1"))
            assertFalse(it.turns.last().text.contains("postQuiz"))
            corrections++
            patch
        }
        assertEquals(1, corrections)
        assertEquals(generate(replacement).spec.json, repaired.spec.json)
        val recorded = events.filterIsInstance<ToolGenerationTraceEvent.Repair>().single()
        assertEquals(1, recorded.repairNumber)
        assertEquals("patch", recorded.mode.wire)
        assertEquals("applied", recorded.result)
        assertEquals(bytes(patch), recorded.responseBytes)
        assertTrue(bytes(patch) < bytes(replacement))
        println("LEDGER_REPAIR fixture=${sha256(original)} patch_bytes=${bytes(patch)} replacement_bytes=${bytes(replacement)}")
    }

    @Test fun liveMatchedLedgerRepair(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val suppliedRunId = arguments.getString("writerRepairRunId")
        assumeNotNull(suppliedRunId)
        val runId = checkNotNull(suppliedRunId)
        require(runId.matches(Regex("[a-f0-9]{32}")))
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "writer-repair/$runId")
        check(directory.mkdirs())
        val initial = definition(brokenBindings)
        val report = JSONObject().put("run_id", runId).put("revision", arguments.getString("writerRepairRevision"))
            .put("source_sha256", arguments.getString("writerRepairSourceSha"))
            .put("fixture_sha256", sha256(initial)).put("plan_sha256", sha256(plan)).put("request_sha256", sha256(request))
            .put("model", "deepseek-flash").put("reasoning_effort", "low").put("cutoff_ms", cutoffMs)
        fun save(phase: String) {
            report.put("phase", phase)
            File(directory, "report.tmp").apply {
                writeText(report.toString(2))
                check(renameTo(File(directory, "report.json")))
            }
        }
        val key = OneUseCredential.receive(runId) { save("credentialReady") }
        save("running")
        var patchRequest: CloudRequest? = null
        generate(initial) { observed ->
            check(patchRequest == null)
            patchRequest = observed
            patch
        }
        val edit = checkNotNull(patchRequest)
        val sharedFeedback = edit.turns.last().text.substringBefore("\nReturn one JSON patch object only.")
        val rewrite = edit.copy(
            systemInstructions = edit.systemInstructions.removeSuffix(GenerationRepair.FORMAT_OVERRIDE),
            turns = edit.turns.dropLast(1) + edit.turns.last().copy(
                text = "$sharedFeedback\nReturn the complete corrected app definition as one JSON object."),
        )
        val live = DeepSeekProvider()
        for ((mode, input) in listOf("rewrite" to rewrite, "patch" to edit)) {
            val output = StringBuilder()
            val metrics = JSONObject().put("request_text_sha256", sha256(input.turns.last().text))
            report.put(mode, metrics)
            val start = SystemClock.elapsedRealtimeNanos()
            val failure = runCatching {
                coroutineScope {
                    val session = CloudSession()
                    val watchdog = launch { delay(cutoffMs); session.cancel() }
                    try {
                        live.respond(key, input, session) { event ->
                            if (event is CloudEvent.Text) output.append(event.value)
                        }
                    } finally {
                        watchdog.cancel()
                    }
                }
            }.exceptionOrNull()
            metrics.put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
            metrics.put("response_bytes", bytes(output.toString()))
            metrics.put("provider_outcome", (failure as? CloudFailure)?.code ?: if (failure == null) "complete" else "error")
            if (failure == null) {
                val verified = runCatching {
                    if (mode == "patch") {
                        var used = false
                        generate(initial) {
                            check(!used)
                            used = true
                            output.toString()
                        }
                        check(used)
                    } else generate(output.toString())
                }
                metrics.put("validated", verified.isSuccess)
                verified.exceptionOrNull()?.let { metrics.put("validation_outcome", (it as? CloudFailure)?.code ?: "rejected") }
            }
            save("running")
        }
        save("complete")
    }

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
