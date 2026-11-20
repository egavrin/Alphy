package com.alpha.assistant.ui

import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.ScriptedLiveServer
import com.alpha.assistant.voice.WavFeeder
import com.alpha.assistant.voice.wavSamples
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Plan D, Dd2: one clock of a scripted voice talk, from the end of the recorded speech to the screen on the stage. */
class DialogueRecorderTest {
    private val fixture = ShellFixture("dialogue-recorder")
    private val robot = ShellRobot("dialogue-recorder")
    @After fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
        fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
    }

    @Test fun oneClockFromSpeechToScreen() = withPlanStep(fixture.context, AiProvider.OPENAI, true) {
        val recorder = DialogueRecorder()
        val audio = FakeLiveAudio(realTime = true)
        val feeder = WavFeeder(audio)
        val plans = AgentScriptProvider(listOf(Scripted.Text(appFilePlan("lesson"))))
        val chat = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                if (request.generationStage != null) plans.respond(key, request, session, emit)
                else emit(CloudEvent.RoundComplete(JSONObject(chatDecision("screen", ANSWER, "The Moon turns once on each orbit"))
                    .put("show", false).toString(), false))
        }
        fixture.install(chat = recorder.chat(chat),
            liveVoice = fixture.liveVoice(recorder.transport(ScriptedLiveServer(listOf(REQUEST))), audio, Endpoints { "http://127.0.0.1:4000" }))
        fixture.launch().use { scenario ->
            val model = checkNotNull(fixture.model)
            robot.waitUntil { !model.loading }
            scenario.onActivity { model.startLiveVoice() }
            robot.waitUntil(15_000) { model.liveVoice.phase == LivePhase.LISTENING }
            recorder.watch(model)
            feeder.start()
            try {
                val samples = wavSamples(robot.instrumentation.context.assets.open("voice/explain-moon.wav").use { it.readBytes() })
                recorder.speechEnd(feeder.say(samples))
                robot.waitUntil(60_000) { recorder.marks().map { it.name }.containsAll(listOf("voice_out", "screen_visible")) }
                val marks = recorder.marks()
                fun at(name: String, detail: String? = null) =
                    checkNotNull(marks.firstOrNull { it.name == name && (detail == null || it.detail == detail) }) { "No $name $detail in $marks" }.atNanos
                val order = listOf(at("speech_end"), at("delegation"), at("call_start", "routing"), at("commentary"), at("voice_out"))
                assertEquals(marks.toString(), order.sorted(), order)
                assertTrue(marks.toString(), at("call_end", "APP_FILE") < at("screen_visible"))
                assertTrue(marks.toString(), marks.zipWithNext().all { (a, b) -> a.atNanos <= b.atNanos })
                val requests = recorder.report().getJSONArray("requests")
                assertEquals(requests.toString(), 1, requests.length())
                val request = requests.getJSONObject(0)
                assertEquals("screen", request.getString("kind"))
                assertEquals("accepted", request.getString("planOutcome"))
                assertTrue(request.toString(), request.getLong("speechEndToScreenMs") > request.getLong("speechEndToDelegationMs"))
                // The clock holds names only: no word of the request or of the answer.
                val recorded = marks.toString() + recorder.report()
                assertFalse(recorded, listOf("Луна", "Moon", "alpha-test-only").any { it in recorded })
            } finally {
                feeder.stop()
                recorder.close()
                scenario.onActivity { model.endLiveVoice() }
            }
        }
    }

    /** Lane V: the pass bar judges a step by its structure, the stage, the kind, an error, and the classes of the lines of Alphy. */
    @Test fun eachStepIsJudgedByItsStructure() {
        assertEquals("answer", DialogueRecorder.lineOf(LiveVoiceBridge.ANSWER + "Готово."))
        assertEquals("question", DialogueRecorder.lineOf(LiveVoiceBridge.ANSWER + "Показать извержение?"))
        assertEquals("progress", DialogueRecorder.lineOf(LiveVoiceBridge.MINI_APP_STARTED))
        // Each line of the app that is no reply is a host line: a failed press, the waiting line, and each reason of a failed full generation.
        for (line in listOf(LiveVoiceBridge.PRESS_MISSING, LiveVoiceBridge.FULL_WAIT, LiveVoiceBridge.FULL_NO_ANSWER + LiveVoiceBridge.FULL_HINT,
            LiveVoiceBridge.FULL_TOO_BIG + LiveVoiceBridge.FULL_HINT, LiveVoiceBridge.FULL_NOT_BUILT + LiveVoiceBridge.FULL_HINT, LiveVoiceBridge.FULL_HINT))
            assertEquals(line, "host", DialogueRecorder.lineOf(line))
        assertEquals("changed", stepOutcome("revision", "refine", false, listOf("answer"), true, false))
        assertEquals("error", stepOutcome("revision", "refine", true, listOf("answer"), true, false))
        // The controller's ruling: a failed full generation ends as an error message and fails its step, also after a spoken reason.
        assertEquals("error", stepOutcome("same", "refine", true, listOf("host", "host"), true, false))
        assertEquals("pressed", stepOutcome("same", "press", false, listOf("answer"), true, false))
        assertEquals("reason", stepOutcome("same", "press", false, listOf("host"), true, false))
        assertEquals("reason", stepOutcome("same", "refine", false, listOf("answer", "host"), true, false))
        // The owner's T3: a plain text after a request to change the screen. A host line that the voice did not speak is no reason.
        assertEquals("plain", stepOutcome("same", "answer", false, listOf("answer"), true, false))
        assertEquals("plain", stepOutcome("same", "refine", false, listOf("host"), false, false))
        assertEquals("question", stepOutcome("same", "clarify", false, listOf("question"), true, false))
        assertEquals("question", stepOutcome("none", null, false, emptyList(), false, true))
        assertEquals("Попробуй ещё раз.", followUp("reason")?.words)
        assertEquals("Попробуй ещё раз.", followUp("error")?.words)
        assertEquals("Да.", followUp("question")?.words)
        assertNull(followUp("plain"))
        fun row(step: Int, goal: String, outcome: String) = JSONObject().put("step", step).put("goal", goal).put("outcome", outcome)
        val rows = listOf(row(1, "screen", "changed"), row(2, "search", "reason"), row(3, "retry", "changed"), row(4, "replace", "question"),
            row(5, "yes", "plain"), row(6, "moving", "changed").put("stateCommit", false), row(7, "motion", "changed").put("fullGeneration", false))
        val coverage = judge(rows, listOf("screen", "search", "replace", "moving", "motion"))
        assertEquals(listOf("passed", "passed", "passed", "failed", "failed", "passed", "passed"), rows.map { it.getString("verdict") })
        assertEquals("passed at step 3", coverage.getString("retry"))
        assertEquals("failed at step 5", coverage.getString("yes"))
        assertEquals("failed at step 4", coverage.getString("replace"))
        // A search with no sources, a change with no state commit, and motion with no full generation did not reach their element.
        assertEquals("not exercised", coverage.getString("search"))
        assertEquals("not exercised", coverage.getString("moving"))
        assertEquals("not exercised", coverage.getString("motion"))
    }

    /**
     * Lane V: an adaptive dialogue on the scripted voice server and a scripted chat. A question back gets «Да», a press that names no
     * control gets the bare retry, and the touch taps the app while the routing call runs. No model call and no voice socket.
     */
    @Test fun anAdaptiveDialogueAnswersAQuestionAndRetriesAFailure() {
        val dialogue = Dialogue("adaptive-rehearsal", listOf(ask("volcano-explain", EXPLAIN, "screen"),
            ask("water-label", LABEL, "touch", touch = true), ask("pendulum-longer", LONGER, "press")), maxCalls = 8, adaptive = true)
        val directory = folder(dialogue)
        val report = rehearse(dialogue, directory, listOf(EXPLAIN, LABEL, "Да.", LONGER, "Попробуй ещё раз."), mapOf(
            1 to StepReplies(JSONObject(chatDecision("screen", ANSWER, "A screen for the request")).put("show", false).toString(),
                listOf("APP_FILE" to appFilePlan("lesson"))),
            2 to StepReplies(chatDecision("clarify", "Показать, как вулкан извергается?")),
            3 to StepReplies(chatDecision("press", "Нажимаю.", "Дальше")),
            4 to StepReplies(chatDecision("press", "Нажимаю.", "Нет такой кнопки")),
            // The touch can have moved the lesson on, so the retry goes back, which works on each later lesson step.
            5 to StepReplies(chatDecision("press", "Нажимаю.", "Назад"))))
        assertEquals(report.toString(), "complete", report.getString("phase"))
        val steps = report.getJSONArray("steps").let { s -> (0 until s.length()).map(s::getJSONObject) }
        assertEquals(listOf("screen", "touch", "yes", "press", "retry"), steps.map { it.getString("goal") })
        assertEquals(steps.toString(), listOf("changed", "question", "pressed", "reason", "pressed"), steps.map { it.getString("outcome") })
        assertTrue(steps.toString(), steps.all { it.getString("verdict") == "passed" })
        val coverage = report.getJSONObject("coverage")
        assertEquals("passed at step 3", coverage.getString("yes"))
        assertEquals("passed at step 5", coverage.getString("retry"))
        assertEquals(steps[1].toString(), if (steps[1].has("touched") && steps[1].getBoolean("stateCommit")) "passed at step 2" else "not exercised",
            coverage.getString("touch"))
        assertTrue((1..5).all { File(directory, "step-$it.png").isFile } && File(directory, "sheet.png").isFile)
    }

    /** Lane V: the call over the cap is refused before it is sent, and the dialogue stops with that reason. */
    @Test fun theCallCapStopsADialogue() {
        val dialogue = Dialogue("capped-rehearsal", listOf(ask("volcano-explain", EXPLAIN, "screen")), maxCalls = 1, adaptive = true)
        val directory = folder(dialogue)
        val failure = runCatching { rehearse(dialogue, directory, listOf(EXPLAIN), mapOf(1 to StepReplies(
            JSONObject(chatDecision("screen", ANSWER, "A screen for the request")).put("show", false).toString(),
            listOf("APP_FILE" to appFilePlan("lesson"))))) }.exceptionOrNull()
        val report = JSONObject(File(directory, "report.json").readText())
        assertTrue(failure.toString(), failure is AssertionError)
        assertEquals(report.toString(), "failed", report.getString("phase"))
        assertTrue(report.toString(), report.getJSONObject("failedStep").getString("reason").endsWith("more than 1"))
    }

    /**
     * Lane V: a screen whose plan and repair have faults goes on to the full generation (ruling FX-4). Its plan step builds the app
     * with no AGENT call, so the waiting line marks the full generation, and the voice speaks it before the new app shows.
     */
    @Test fun aFullGenerationIsMarkedByItsWaitingLine() {
        val dialogue = Dialogue("full-rehearsal", listOf(ask("pendulum-motion", MOTION, "motion")), maxCalls = 4, adaptive = true)
        val directory = folder(dialogue)
        val broken = appFilePlan("calc").replace("/ \$/people, example", "/ , example")
        val plans = AtomicInteger()
        val report = rehearse(dialogue, directory, listOf(MOTION), mapOf(1 to StepReplies(
            JSONObject(chatDecision("screen", ANSWER, "A pendulum that swings")).put("show", true).toString(),
            listOf("APP_FILE" to broken, "APP_FILE" to broken, "APP_FILE" to appFilePlan("lesson"))))) { inner ->
            object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    // A real full generation takes about a minute, so the plan of the full generation waits for the waiting line.
                    if (request.generationStage == CloudGenerationStage.APP_FILE && plans.incrementAndGet() == 3) delay(4_000)
                    inner.respond(key, request, session, emit)
                }
            }
        }
        assertEquals(report.toString(), "complete", report.getString("phase"))
        val row = report.getJSONArray("steps").getJSONObject(0)
        assertEquals(row.toString(), listOf("changed", "passed"), listOf(row.getString("outcome"), row.getString("verdict")))
        assertEquals(row.toString(), mapOf("routing" to 1, "APP_FILE" to 3), row.getJSONObject("calls").let { c -> c.keys().asSequence().associateWith(c::getInt) })
        assertTrue(row.toString(), row.getBoolean("fullGeneration") && row.getBoolean("waitingLine") && "host" in row.getJSONArray("lines").toString())
        assertEquals("passed at step 1", report.getJSONObject("coverage").getString("motion"))
    }

    private fun folder(dialogue: Dialogue) =
        File(checkNotNull(fixture.context.getExternalFilesDir(null)), "dialogue-rehearsal/${dialogue.name}").apply { deleteRecursively() }

    private fun rehearse(dialogue: Dialogue, directory: File, words: List<String>, replies: Map<Int, StepReplies>,
        wrap: (ChatProvider) -> ChatProvider = { it }): JSONObject {
        lateinit var run: DialogueRun
        run = DialogueRun(dialogue, directory, "rehearsal")
        return run.run(ScriptedLiveServer(words), wrap(ScriptedChat(replies) { run.step }), Endpoints { "http://127.0.0.1:4000" })
    }

    private companion object {
        const val REQUEST = "Объясни, почему Луна всегда повёрнута к Земле одной стороной."
        const val ANSWER = "Луна делает один оборот вокруг оси за один круг вокруг Земли."
        const val EXPLAIN = "Объясни, как устроен вулкан."
        const val LABEL = "Подпиши на картинке каждый этап."
        const val LONGER = "Сделай нить маятника вдвое длиннее."
        const val MOTION = "Пусть маятник качается сам, без остановки."
    }
}
