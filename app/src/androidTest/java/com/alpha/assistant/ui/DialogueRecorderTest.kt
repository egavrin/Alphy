package com.alpha.assistant.ui

import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.SceneAction
import com.alpha.assistant.tools.SceneStep
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.ToolGenerationTraceEvent
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.send
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LiveListener
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveProtocol
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
import java.util.UUID
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
                when (request.generationStage) {
                    null -> emit(CloudEvent.RoundComplete(JSONObject(chatDecision("screen", ANSWER, "The Moon turns once on each orbit"))
                        .put("show", false).toString(), false))
                    // No template fits, so the free plan makes the screen.
                    CloudGenerationStage.APP_FILE_TEMPLATE -> Scripted.Text(ScriptedChat.NO_TEMPLATE).send(emit)
                    else -> plans.respond(key, request, session, emit)
                }
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

    /**
     * Plan of 2026-11-21, Task 19: the stream marks of each request come from the trace phases on the trace clock. A streamed screen,
     * a turn whose frame went away, and a screen with no preview frame, which is complete at controls on.
     */
    @Test fun theStreamMarksComeFromTheTracePhases() {
        val recorder = DialogueRecorder()
        val operation = UUID.randomUUID()
        fun at(ms: Long) = ms * 1_000_000
        fun phase(name: String, detail: String?, ms: Long) = recorder.phase(ToolGenerationTraceEvent.Phase(operation, 1, at(ms), name, detail, null))
        recorder.speechEnd(at(1_000))
        listOf("frame" to 4_100L, "parts" to 4_500L, "parts" to 4_900L, "card" to 6_000L, "whole" to 6_400L).forEach { (detail, ms) -> phase("stage_preview", detail, ms) }
        phase("controls_on", null, 8_000)
        recorder.speechEnd(at(20_000))
        phase("stage_preview", "frame", 23_000)
        phase("stage_preview_gone", null, 24_000)
        recorder.speechEnd(at(30_000))
        phase("controls_on", null, 36_400)
        val requests = recorder.report().getJSONArray("requests").let { r -> (0 until r.length()).map(r::getJSONObject) }
        val names = listOf("first_line_visible", "first_part_visible", "picture_complete", "controls_on", "preview_updates", "previews_gone")
        fun values(request: JSONObject) = names.map { if (request.has(it)) request.getLong(it) else null }
        assertEquals(requests.toString(), listOf(listOf(3_100L, 3_500L, 5_000L, 7_000L, 5L, 0L), listOf(3_000L, null, null, null, 1L, 1L),
            listOf(null, null, 6_400L, 6_400L, 0L, 0L)), requests.map(::values))
        recorder.close()
    }

    /**
     * Walk fix: the clock of a walk. Each thinking append is a mark, and each change of the step of the same stage app is a mark at its time
     * with the who of the action of the bridge that set it, also when the bridge records that action a poll later. A new app is no move.
     */
    @Test fun theClockOfAWalkHasItsSentencesAndItsSteps() {
        var now = 0L
        fun at(ms: Long) { now = ms * 1_000_000 }
        val recorder = DialogueRecorder { now }
        val socket = FakeLiveTransport()
        val connection = recorder.transport(socket).connect("ws://127.0.0.1:4000", "", object : LiveListener {
            override fun onText(text: String) = Unit
            override fun onFailure(status: Int?, message: String) = Unit
            override fun onClosed() = Unit
        })
        fun step(index: Int, label: String) = SceneStep(index, 3, label)
        fun action(who: String, label: String) = SceneAction(who, CONTROL, label)
        at(1_000); recorder.speechEnd(now)
        at(1_500); connection.send(LiveProtocol.thinking("t1", LiveProtocol.MARK + SAY))
        at(2_000); recorder.stage(SCENE, step(1, "Корабль"), CONTROL, emptyList())
        at(3_000); recorder.stage(SCENE, step(2, "Гвоздь"), CONTROL, emptyList())
        at(3_100); recorder.stage(SCENE, step(2, "Гвоздь"), CONTROL, listOf(action("narrator", "Гвоздь")))
        at(4_000); recorder.stage(SCENE, step(3, "Лодка"), CONTROL, listOf(action("narrator", "Гвоздь"), action("tap", "Лодка")))
        at(5_000); recorder.stage("other", step(1, "Корабль"), CONTROL, emptyList())
        assertEquals(1, socket.sent.size)
        val marks = recorder.marks().filter { it.name in setOf(DialogueRecorder.THINKING, DialogueRecorder.STAGE_STEP) }
        assertEquals(listOf("thinking:null:1500", "stage_step:2/3 narrator:3000", "stage_step:3/3 tap:4000"),
            marks.map { "${it.name}:${it.detail}:${it.atNanos / 1_000_000}" })
        val request = recorder.report().getJSONArray("requests").getJSONObject(0)
        assertEquals("[500]", request.getJSONArray("thinkingMs").toString())
        val moves = request.getJSONArray("stepMoves")
        assertEquals(listOf("2000 2/3 narrator", "3000 3/3 tap"),
            List(moves.length()) { moves.getJSONObject(it).let { move -> "${move.getLong("ms")} ${move.getString("step")} ${move.getString("who")}" } })
        // The clock holds names only: no word of a sentence or of a step.
        val recorded = recorder.marks().toString() + recorder.report()
        assertFalse(recorded, listOf("Корабль", "Гвоздь", "Лодка", SAY).any { it in recorded })
        recorder.close()
    }

    /** Lane V: the pass bar judges a step by its structure, the stage, the kind, an error, and the classes of the lines of Alphy. */
    @Test fun eachStepIsJudgedByItsStructure() {
        assertEquals("answer", DialogueRecorder.lineOf(LiveProtocol.MARK + "Готово."))
        assertEquals("question", DialogueRecorder.lineOf(LiveProtocol.MARK + "Показать извержение?"))
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
        // The plan of 2026-11-21: a change counts with a changed template file of the same app, a request to learn with a changed stage
        // and no template file, and a turn with no screen with a spoken answer and no app. A frame that stays fails a turn with no screen.
        fun screen(shown: Boolean, change: String) = JSONObject().put("shown", shown).put("change", change)
        val template = listOf(row(1, "change", "changed").put("appFileChanged", true).put("templateFile", true),
            row(2, "change", "changed").put("appFileChanged", true).put("templateFile", false),
            row(3, "learn", "changed").put("screen", screen(true, "new")).put("templateFile", false),
            row(4, "learn", "changed").put("screen", screen(true, "new")).put("templateFile", true),
            row(5, "answer", "plain").put("spoke", "Привет!").put("screen", screen(false, "none")),
            row(6, "answer", "question").put("spoke", "Чем помочь?").put("screen", screen(false, "none")),
            row(7, "answer", "plain").put("spoke", "Привет!").put("screen", screen(false, "none")).put("previewLeft", true),
            row(8, "answer", "changed").put("spoke", "Вот экран.").put("screen", screen(true, "new")))
        val templateCoverage = judge(template, listOf("change", "learn", "answer"))
        assertEquals(listOf("passed", "passed", "passed", "passed", "passed", "passed", "failed", "failed"), template.map { it.getString("verdict") })
        assertEquals("passed at step 1", templateCoverage.getString("change"))
        assertEquals("passed at step 3", templateCoverage.getString("learn"))
        assertEquals("passed at step 5, passed at step 6, failed at step 7", templateCoverage.getString("answer"))
        // The UX study of 2026-11-21: a probe step and the step inserted right after it are observed, never failed. A step after them is
        // judged again.
        val probes = listOf(row(1, PROBE, "plain"), row(2, PROBE, "question"), row(3, "yes", "plain"), row(4, PROBE, "error"),
            row(5, "retry", "error"), row(6, "screen", "plain"))
        judge(probes, listOf(PROBE, "screen"))
        assertEquals(listOf("observed", "observed", "observed", "observed", "observed", "failed"), probes.map { it.getString("verdict") })
        assertTrue(isTemplateAppFile("\n  scene(\"Почему корабль не тонет?\", lang=\"ru\")\ncontrol = slider(\"Размер\", 0, 10, 1, 4)"))
        assertTrue(listOf("items(\"Части слова\", lang=\"ru\")", "cells (\"What is a majority?\", lang=\"en\")").all(::isTemplateAppFile))
        assertFalse(isTemplateAppFile(appFilePlan("lesson")))
        assertFalse(isTemplateAppFile(null))
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
                listOf("APP_FILE" to Scripted.Text(appFilePlan("lesson")))),
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
            listOf("APP_FILE" to Scripted.Text(appFilePlan("lesson")))))) }.exceptionOrNull()
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
        // The early template call that no template fits is the fifth call (plan of 2026-11-21, Task 4).
        val dialogue = Dialogue("full-rehearsal", listOf(ask("pendulum-motion", MOTION, "motion")), maxCalls = 5, adaptive = true)
        val directory = folder(dialogue)
        val broken = Scripted.Text(appFilePlan("calc").replace("/ \$/people, example", "/ , example"))
        val plans = AtomicInteger()
        val report = rehearse(dialogue, directory, listOf(MOTION), mapOf(1 to StepReplies(
            JSONObject(chatDecision("screen", ANSWER, "A pendulum that swings")).put("show", true).toString(),
            listOf("APP_FILE" to broken, "APP_FILE" to broken, "APP_FILE" to Scripted.Text(appFilePlan("lesson")))))) { inner ->
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
        assertEquals(row.toString(), mapOf("routing" to 1, "APP_FILE" to 3),
            row.getJSONObject("calls").let { c -> c.keys().asSequence().filter { it != "APP_FILE_TEMPLATE" }.associateWith(c::getInt) })
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
        const val SCENE = "scene"
        const val CONTROL = "Предмет"
        const val SAY = "Корабль полый, и вода держит его."
    }
}
