package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.agent.AgentCommand
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.data.Draft
import com.alpha.assistant.semantic.SemanticRuntime
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import com.alpha.assistant.ui.openSeededChat
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class LivePhoneSelectionWorkflow {
    @Test fun traceWholeRequests() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val runId = checkNotNull(args.getString("liveRunId"))
        val fixture = ShellFixture("phone-selection-live")
        val dir = File(checkNotNull(fixture.context.getExternalFilesDir(null)), "phone-selection/$runId")
        check(dir.mkdirs())
        val corpus = JSONArray(instrumentation.context.assets.open("phone-selection-scenarios.json")
            .bufferedReader().use { it.readText() })
        val cases = (0 until corpus.length()).flatMap { index ->
            val spec = corpus.getJSONObject(index)
            (1..spec.optInt("repeat", 1)).map { attempt ->
                JSONObject(spec.toString()).put("attempt", attempt).put("chat", "${spec.getString("id")}-$attempt")
            }
        }
        val rows = JSONArray()
        val report = JSONObject().put("revision", args.getString("testedRevision")).put("runId", runId)
            .put("cases", rows)
        fun save() = File(dir, "report.json").writeText(report.toString(2))
        val credential = OneUseCredential.receive(runId) {
            report.put("phase", "credentialReady")
            save()
        }
        report.put("phase", "running")
        save()

        val calls = CopyOnWriteArrayList<JSONObject>()
        val rejections = CopyOnWriteArrayList<String>()
        val http = CopyOnWriteArrayList<String>()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                val stage = if (request.agentPlan) "plan" else if (request.chatDecision) "decision" else "other"
                val entry = JSONObject().put("stage", stage)
                if (request.agentPlan) {
                    val prefix = "Candidates by step: "
                    val candidates = request.systemInstructions.substringAfter(prefix, "").substringBefore('\n')
                    entry.put("candidates", JSONArray(candidates))
                    entry.put("definitionIds", JSONArray(request.systemInstructions.lineSequence()
                        .dropWhile { !it.startsWith(prefix) }.drop(1)
                        .takeWhile { !it.startsWith("calendar.createOwned.daily means") }
                        .filter { it.isNotBlank() }.map { it.substringBefore(' ') }.toList()))
                }
                calls += entry
                session.decisionRejectionObserver = { rejections += "${it.stage}:${it.shape.rule}:${it.shape.path}" }
                try {
                    DeepSeekProvider("https://api.deepseek.com", onHttpCall = { http += stage }) {}.respond(credential, request, session) { event ->
                        if (event is CloudEvent.RoundComplete) {
                            event.devicePlanSteps?.let { entry.put("extracted", JSONArray(it.map { step -> step.json() })) }
                            if (event.memoryFact != null) entry.put("prepared", "memory_fact")
                            if (event.automationDraft != null) entry.put("prepared", "automation")
                            entry.put("output", event.text)
                        }
                        emit(event)
                    }
                } catch (failure: CloudFailure) {
                    entry.put("failureCode", failure.code)
                    throw failure
                }
            }
        }
        cases.forEachIndexed { index, spec ->
            val chat = spec.getString("chat")
            fixture.seedChat(chat, chat, "Synthetic QA", selected = index == 0)
        }
        fixture.install()
        CloudTestActivity.factory = { app ->
            ShellViewModel(app, provider, fixture.store, fixture.keys)
                .openSeededChat(fixture.store).also { fixture.model = it }
        }
        val robot = ShellRobot("phone-selection-live")
        var savedGood = false
        var savedLoss = false
        try {
            fixture.launch().use { scenario ->
                val model = checkNotNull(fixture.model)
                cases.forEach { spec ->
                    val chat = spec.getString("chat")
                    val input = spec.getString("text")
                    val row = JSONObject(spec.toString())
                    rows.put(row)
                    val callStart = calls.size
                    val rejectStart = rejections.size
                    val httpStart = http.size
                    val start = android.os.SystemClock.elapsedRealtime()
                    try {
                        scenario.onActivity {
                            model.select(chat)
                            model.editDraft(Draft(input))
                            model.send(search = false)
                        }
                        robot.waitUntil(100_000) {
                            model.snapshot.conversations.find { it.id == chat }?.messages?.lastOrNull()
                                ?.let { it.role == "assistant" && it.status in setOf("complete", "error") } == true
                        }
                        row.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
                        scenario.onActivity {
                            val reply = model.snapshot.conversations.single { it.id == chat }.messages.last()
                            row.put("chatStatus", reply.status).put("chatErrorCode", reply.errorCode).put("chatText", reply.text)
                            model.agent.plan?.takeIf { it.conversationId == chat }?.let { plan ->
                                row.put("phase", plan.phase.name)
                                row.put("reviewSteps", JSONArray(plan.steps.map { it.command.json() }))
                                row.put("reviewTargets", JSONArray(plan.steps.map { it.target?.label ?: "" }))
                                row.put("reviewValues", JSONArray(plan.steps.map { it.target?.after ?: "" }))
                            }
                        }
                        val expected = row.getJSONArray("expected").length()
                        val actual = row.optJSONArray("reviewSteps")?.length() ?: 0
                        if ((expected > 1 && actual == expected && !savedGood) || (expected > actual && !savedLoss)) {
                            val name = if (expected == actual) "complete-plan" else "incomplete-plan"
                            if (row.optString("phase") == "REVIEW") {
                                runCatching { robot.reveal(robot.text(com.alpha.assistant.R.string.agent_execute)) }
                            }
                            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                                File(dir, "$name.png").outputStream().use {
                                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                screenshot.recycle()
                            }
                            if (expected == actual) savedGood = true else savedLoss = true
                        }
                    } catch (failure: Exception) {
                        row.put("harnessFailure", failure.javaClass.simpleName)
                    } finally {
                        val caseCalls = calls.drop(callStart)
                        row.put("calls", JSONArray(caseCalls)).put("httpStages", JSONArray(http.drop(httpStart)))
                            .put("rejections", JSONArray(rejections.drop(rejectStart)))
                        val extracted = caseCalls.firstOrNull { it.has("extracted") }?.getJSONArray("extracted")
                        if (extracted != null && caseCalls.none { it.optString("stage") == "plan" }) {
                            val diagnostics = runBlocking {
                                val runtime = SemanticRuntime.get(fixture.context)
                                (0 until extracted.length()).map { index ->
                                    runCatching { JSONArray(AgentCommand.shortlist(extracted.getJSONObject(index).getString("context"), runtime)) }
                                        .getOrElse { JSONObject().put("error", it.javaClass.simpleName) }
                                }
                            }
                            row.put("localShortlistReplay", JSONArray(diagnostics))
                        }
                        save()
                        println("PHONE_SELECTION_CASE $chat ${row.optString("chatStatus")} ${row.optString("phase")} ${row.optJSONArray("reviewSteps")?.length() ?: 0}")
                    }
                }
            }
            report.put("phase", "complete")
        } finally {
            save()
            fixture.remove()
        }
    }
}
