package com.alpha.assistant.ui

import android.os.SystemClock
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.doOnAttach
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.data.Source
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.voice.LIVE_RATE
import com.alpha.assistant.voice.ScriptedLiveServer
import com.alpha.assistant.voice.wavSamples
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plan D, Dd3: the driver of the live dialogues on a scripted voice server and a scripted chat with fixture plans, so a fault of the
 * driver, its waits, its pictures, its sheet, or its report does not spend a paid run. No model call and no voice socket.
 * The argument `dialogueSteps` (default 4) sets the steps of a rehearsal: steps 3 and 4 are the spoken press and the spoken change.
 */
class DialogueStageRehearsalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val files = checkNotNull(context.getExternalFilesDir(null))

    @Test fun heart() { rehearse("heart", drawing = LESSON_DRAWING) }

    @Test fun moon() { rehearse("moon", drawing = LESSON_DRAWING) }

    @Test fun japanese() { rehearse("japanese") }

    /**
     * Ruling OD-D2 b: the stored replies of a finished run (the argument `dialogueRun`, a folder under the files of the app, such as
     * `live-check/<run id>`) make its screens again at the display size of this device, with the words of that run under the pictures.
     * Without the argument, it replays a rehearsal of `moon`.
     */
    @Test fun replay() {
        val source = arguments.getString("dialogueRun")?.let { name ->
            require(name.matches(Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*"))) { "dialogueRun is a folder under the files of the app." }
            File(files, name)
        } ?: rehearse("moon", "dialogue-rehearsal/replay-source")
        val stored = JSONObject(File(source, "replay.json").readText())
        val dialogue = DIALOGUES.getValue(stored.getString("dialogue"))
        val size = context.resources.configuration.let { "${it.screenWidthDp}x${it.screenHeightDp}dp" }
        val directory = File(source, "replay-$size").apply { deleteRecursively() }
        lateinit var run: DialogueRun
        run = DialogueRun(dialogue, directory, "replay", count = stored.getJSONArray("steps").length(), stored = stored)
        val report = run.run(server(dialogue), ScriptedChat(storedReplies(stored)) { run.step }, LOOPBACK)
        assertEquals(report.toString(), "complete", report.getString("phase"))
        assertTrue(File(directory, "sheet.png").isFile)
    }

    /**
     * Plan of 2026-11-21, Task 10b: each template dialogue with a template file for each request to explain, `outside("learn")` and a
     * free plan for the request to learn, and the changed lines for the spoken change. Each step passes the bar of the driver and
     * reaches its goal.
     */
    @Test fun eachTemplateDialoguePassesTheRehearsal() { for (name in listOf("tpl-a", "tpl-b", "tpl-c")) templateRehearsal(name, stream = false) }

    /**
     * Task 19: the stream dialogues, with each template reply and each change as text deltas that end inside lines. Each step passes the
     * bar and reaches its goal, each step gets preview frames, and each screen shows its frame, its first part, the complete picture, and
     * its controls in this order, with no frame that goes away.
     */
    @Test fun eachStreamDialoguePassesTheRehearsal() {
        // All three run before the marks are read, so a fault of a dialogue shows before a fault of the stream.
        val runs = listOf("tpl-d", "tpl-e", "tpl-f").associateWith { rows(templateRehearsal(it, stream = true)) }
        for ((name, steps) in runs) {
            for (row in steps) assertTrue("$name: $row", row.getInt("preview_updates") >= 1)
            for (row in steps.filter { it.getString("goal") == "screen" }) streamed(row, "$name: ")
        }
    }

    /**
     * Task 19: a first screen whose template reply comes as text deltas that end inside lines, [DELTA_MS] apart. A text of a line shows
     * on the stage only after the delta that completes its line, and the marks of the stream come in their order.
     */
    @Test fun shipStreams() {
        val dialogue = Dialogue("ship-stream", listOf(ask("volcano-explain", "Объясни, как устроен вулкан.", "screen")), adaptive = true)
        val lines = SHIP.lines()
        // Each watched text and the index of its line: the title, the control label, a shape text, and the card title.
        val watched = mapOf("Почему корабль не тонет?" to 0, "Предмет" to 1, "Воздух внутри" to 4, "Вытеснение воды" to 5)
        val sent = CopyOnWriteArrayList<Pair<Long, String>>()
        val seen = ConcurrentHashMap<String, Long>()
        val report = run(dialogue, mapOf(1 to StepReplies(screen("Сейчас покажу на экране."), listOf(TEMPLATE to deltas(SHIP)))),
            File(files, "dialogue-rehearsal/${dialogue.name}").apply { deleteRecursively() }, wrap = { inner -> templateText(inner, sent) }) { _, node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            for (text in watched.keys) if (texts.any { text in it }) seen.putIfAbsent(text, SystemClock.elapsedRealtimeNanos())
        }
        assertEquals(report.toString(), "complete", report.getString("phase"))
        val row = rows(report).single()
        assertEquals(row.toString(), "passed", row.getString("verdict"))
        for ((text, line) in watched) {
            val complete = sent.firstOrNull { (_, so) -> lines[line] + "\n" in so }?.first
            assertNotNull("the line of «$text» never completed: $sent", complete)
            val shown = seen[text]
            assertNotNull("«$text» never showed on the stage", shown)
            assertTrue("«$text» showed before the delta that completes its line", shown!! >= complete!!)
        }
        streamed(row, "")
    }

    /**
     * Task 19: the no-screen probe, each turn the first of a new talk, and each routing gives a spoken answer. In the first turn the
     * routing ends after the first lines of the early template call, so a frame shows and goes away. The other turns get no template
     * reply, so no frame shows.
     */
    @Test fun aNoScreenTurnCountsAGonePreview() {
        val dialogue = DIALOGUES.getValue("no-screen")
        val answer = chatDecision("answer", "Отвечаю голосом.")
        val replies = (1..dialogue.steps.size).associateWith { StepReplies(answer) } +
            (1 to StepReplies(answer, listOf(TEMPLATE to deltas(SHIP, GONE_DELTA_MS)), decisionMs = GONE_ROUTING_MS))
        val report = run(dialogue, replies, File(files, "dialogue-rehearsal/${dialogue.name}").apply { deleteRecursively() })
        assertEquals(report.toString(), "complete", report.getString("phase"))
        val steps = rows(report)
        assertEquals(steps.toString(), List(6) { "answer" }, steps.map { it.getString("goal") })
        assertTrue(steps.toString(), steps.all { it.getString("verdict") == "passed" && !it.getJSONObject("screen").getBoolean("shown") })
        // Each turn is the first of a new talk: its own voice session and its own chat.
        assertEquals(steps.toString(), 6, steps.map { it.getInt("voiceSession") }.toSet().size)
        assertEquals(steps.toString(), 6, steps.map { it.getString("talk") }.toSet().size)
        assertEquals(steps.toString(), listOf(1, 0, 0, 0, 0, 0), steps.map { it.getInt("previews_gone") })
        assertTrue(steps.toString(), steps[0].getInt("preview_updates") >= 1)
        assertEquals(steps.toString(), List(5) { 0 }, steps.drop(1).map { it.getInt("preview_updates") })
        assertEquals((1..6).joinToString { "passed at step $it" }, report.getJSONObject("coverage").getString("answer"))
    }

    /**
     * The UX study of 2026-11-21: each research dialogue has 5 probe steps and the cap [RESEARCH_CALLS], and the fixture of each step
     * is a 16 kHz mono PCM16 recording of more than 0.5 s in the test assets, so a missing file cannot stop a paid run.
     */
    @Test fun eachResearchStepHasItsRecording() {
        // ux-images-b reuses the recordings of ux-images in another order, so it has no fixtures of its own.
        val research = DIALOGUES.values.filter { it.name.startsWith("ux-") && it.name != "ux-images-b" }
        assertEquals(research.map { it.name }.toString(), 12, research.size)
        for (dialogue in research) {
            assertEquals(dialogue.name, RESEARCH_CALLS, dialogue.maxCalls)
            assertEquals(dialogue.name, List(5) { PROBE }, dialogue.steps.map { it.goal })
            dialogue.steps.forEachIndexed { i, step ->
                val fixture = checkNotNull(step.speech).fixture
                assertEquals("${dialogue.name}-${i + 1}", fixture)
                val samples = wavSamples(InstrumentationRegistry.getInstrumentation().context.assets.open("voice/$fixture.wav").use { it.readBytes() })
                assertTrue(fixture, samples.size > LIVE_RATE / 2)
            }
        }
        val tap = DIALOGUES.getValue("ux-tap").steps
        assertEquals(listOf(true, true, false, false, false), tap.map { it.tapWhileSpeaking })
        assertEquals(listOf(false, false, true, false, false), tap.map { it.touch })
    }

    /**
     * The UX study of 2026-11-21: a research dialogue on the scripted voice and the scripted chat. Step 1 gets a spoken answer and
     * leaves the stage empty, and step 2 still runs. Step 2 shows a screen with a choice, and the driver taps an option while the voice
     * speaks the long reply. Step 3 touches the other option while the step runs. Each step is observed, with frames.
     */
    @Test fun aResearchDialogueRunsPastAnEmptyStageAndTapsWhileTheVoiceSpeaks() {
        val dialogue = Dialogue("ux-rehearsal", listOf(probe("ux-tap-4", "Что сейчас на экране?"),
            probe("ux-tap-1", "Объясни, как работает рычаг.", tapWhileSpeaking = true), probe("ux-tap-3", "Сделай груз тяжелее.", touch = true)),
            RESEARCH_CALLS, adaptive = true)
        val directory = File(files, "dialogue-rehearsal/${dialogue.name}").apply { deleteRecursively() }
        // Steps 1 and 3 get the spoken answer of the scripted chat, so they bring no screen.
        val report = run(dialogue, mapOf(2 to StepReplies(screen(LONG_ANSWER), listOf(TEMPLATE to Scripted.Text(SHIP)))), directory)
        assertEquals(report.toString(), "complete", report.getString("phase"))
        val steps = rows(report)
        assertEquals(steps.toString(), List(3) { "observed" }, steps.map { it.getString("verdict") })
        assertFalse(steps[0].toString(), steps[0].getJSONObject("screen").getBoolean("shown"))
        assertTrue(steps[0].toString(), steps[0].has("appHead") && steps[0].isNull("appHead"))
        assertEquals(steps[1].toString(), SHIP.lines().first(), steps[1].getString("appHead"))
        assertTrue(File(directory, "step-2.appfile.txt").readText().startsWith(SHIP.lines().first()))
        val tap = steps[1].getJSONObject("tap")
        assertEquals(tap.toString(), listOf("Гвоздь", "choice", "SPEAKING"), listOf(tap.optString("label"), tap.optString("kind"), tap.optString("phaseBefore")))
        assertTrue(tap.toString(), tap.getLong("atMs") > 0 && tap.getLong("speakingMs") >= 1_500 && tap.getInt("spokeCharsBefore") > 0)
        assertEquals(tap.toString(), 16, tap.getJSONArray("phasesAfter").length())
        assertTrue(tap.toString(), tap.getJSONObject("sentTypes").optInt("session.input_audio.append") > 0 && tap.getJSONObject("receivedTypes").length() > 0)
        assertFalse(tap.toString(), tap.getBoolean("newHandOff"))
        // The tap chose «Гвоздь», so the touch of step 3 finds «Корабль» as the option that is not selected.
        assertEquals(steps[2].toString(), "Корабль", steps[2].getString("touched"))
        for (row in steps) {
            val frames = row.getJSONArray("frames").let { f -> (0 until f.length()).map(f::getString) }
            // The first frame comes while the person speaks, so its time is negative.
            assertTrue(row.toString(), frames.size >= 2 && frames.first().startsWith("step-${row.getInt("step")}--"))
            assertTrue(row.toString(), frames.all { File(directory, "frames/$it").length() > 0 } && !row.has("framesError"))
        }
        assertTrue(steps[1].toString(), steps[1].getLong("speechEndToVoiceEndMs") > steps[1].getLong("speechEndToCommentaryMs"))
        assertTrue(report.toString(), report.getJSONObject("display").has("nightMode"))
    }

    /** The UX study of 2026-11-21: on a template screen with a slider, the tap while the voice speaks moves the slider. */
    @Test fun aTapWhileTheVoiceSpeaksMovesASlider() {
        val dialogue = Dialogue("ux-slider-rehearsal", listOf(probe("ux-tap-1", "Объясни, как работает рычаг.", tapWhileSpeaking = true)),
            RESEARCH_CALLS, adaptive = true)
        val directory = File(files, "dialogue-rehearsal/${dialogue.name}").apply { deleteRecursively() }
        val label = "Расстояние до скрепки"
        // The slider shows its label with its value, such as "Расстояние до скрепки: 60".
        val values = ConcurrentHashMap.newKeySet<String>()
        val report = run(dialogue, mapOf(1 to StepReplies(screen(LONG_ANSWER), listOf(TEMPLATE to Scripted.Text(TEMPLATES.getValue("tpl-a").first)))),
            directory) { _, node -> node.config.getOrNull(SemanticsProperties.Text)?.forEach { if (it.text.startsWith("$label: ")) values += it.text } }
        assertEquals(report.toString(), "complete", report.getString("phase"))
        val row = rows(report).single()
        val tap = row.getJSONObject("tap")
        assertEquals(tap.toString(), listOf(label, "slider"), listOf(tap.optString("label"), tap.optString("kind")))
        assertEquals(row.toString(), "observed", row.getString("verdict"))
        // The slider moved from its first value to the far end of its range.
        assertTrue(values.toString(), values.size >= 2)
    }

    /**
     * Runs the rehearsal of the dialogue [name] into [folder] and checks its files. Returns the folder. Each frame that the window draws
     * must not show the stopped line on the stage, and the canvas of [drawing] must not be empty on the stage after it drew (ruling FX-8).
     */
    private fun rehearse(name: String, folder: String = "dialogue-rehearsal/$name", drawing: String? = null): File {
        val dialogue = DIALOGUES.getValue(name)
        val directory = File(files, folder).apply { deleteRecursively() }
        val count = arguments.getString("dialogueSteps")?.toInt() ?: 4
        val stopped = context.getString(R.string.tool_session_stopped)
        val faults = CopyOnWriteArrayList<String>()
        var drew = false
        val report = run(dialogue, REHEARSALS.getValue(name), directory, count) { step, node ->
            if (step >= 2) {
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == stopped } == true)
                    faults.addIfAbsent("step $step: the stage shows «$stopped»")
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { drawing != null && it.startsWith("$drawing. ") }?.let {
                    if (it != "$drawing. ") drew = true else if (drew) faults.addIfAbsent("step $step: the canvas on the stage is empty")
                }
            }
        }
        assertEquals(faults.toString(), emptyList<String>(), faults)
        if (drawing != null) assertTrue("the stage never drew «$drawing»", drew)
        assertEquals(report.toString(), "complete", report.getString("phase"))
        val steps = report.getJSONArray("steps")
        assertEquals(count, steps.length())
        for (i in 0 until count) {
            val step = dialogue.steps[i]
            val row = steps.getJSONObject(i)
            assertTrue("step-${i + 1}.png", File(directory, "step-${i + 1}.png").isFile)
            // A pause of 300 ms or more in a recording would split it into two turns of the scripted server.
            if (step.speech != null) assertEquals(row.toString(), if (step.handOff) 1 else 0, row.getInt("handOffs"))
            if (step.screen) assertTrue(row.toString(), row.getJSONObject("screen").getBoolean("shown"))
        }
        if (count >= 2) assertEquals(steps.toString(), "new", steps.getJSONObject(1).getJSONObject("screen").getString("change"))
        // The spoken change edits the app of «Шаг 2», so the stage shows a new revision of the same app.
        if (count >= 4) assertEquals(steps.toString(), "revision", steps.getJSONObject(3).getJSONObject("screen").getString("change"))
        val checks = report.getJSONArray("checks")
        assertTrue(checks.toString(), (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("pass") })
        assertTrue(File(directory, "sheet.png").isFile)
        val stored = JSONObject(File(directory, "replay.json").readText()).getJSONArray("steps")
        assertEquals(REHEARSALS.getValue(name)[2]?.decision, stored.getJSONObject(1).optString("decision"))
        return directory
    }

    /**
     * The rehearsal of the template dialogue [name] with the replies of [TEMPLATES], as text deltas when [stream] is true. Each step
     * passes the bar, no step is inserted, each goal is reached at its steps, the stage never shows the stopped line, and each check of
     * the run holds. Returns the report.
     */
    private fun templateRehearsal(name: String, stream: Boolean): JSONObject {
        val dialogue = DIALOGUES.getValue(name)
        val written = TEMPLATES.getValue(name)
        val english = name == "tpl-e"
        fun reply(text: String) = if (stream) deltas(text) else Scripted.Text(text)
        val shown = screen(if (english) "Here it is on the screen." else "Сейчас покажу на экране.")
        val search = dialogue.steps[1].goal == "search"
        val change = JSONObject(chatDecision("refine", if (english) "Done, the screen is changed." else "Готово, экран изменён.",
            "Change the screen as asked")).apply { if (search) put("search", "required") }.toString()
        val replies = mapOf(1 to StepReplies(shown, listOf(TEMPLATE to reply(written.first))),
            2 to StepReplies(change, listOf(TEMPLATE to reply(written.change)), sources = if (search) listOf(FRESH_SOURCE) else emptyList()),
            3 to StepReplies(shown, listOf(TEMPLATE to Scripted.Text("""outside("learn")"""), "APP_FILE" to Scripted.Text(appFilePlan("quiz")))),
            4 to StepReplies(shown, listOf(TEMPLATE to reply(written.next))))
        val stopped = context.getString(R.string.tool_session_stopped)
        val faults = CopyOnWriteArrayList<String>()
        val report = run(dialogue, replies, File(files, "dialogue-rehearsal/$name").apply { deleteRecursively() }) { step, node ->
            if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == stopped } == true) faults.addIfAbsent("step $step: the stage shows «$stopped»")
        }
        assertEquals("$name: $faults", emptyList<String>(), faults)
        assertEquals("$name: $report", "complete", report.getString("phase"))
        val steps = rows(report)
        assertEquals("$name: $steps", dialogue.steps.map { it.goal }, steps.map { it.getString("goal") })
        assertTrue("$name: $steps", steps.all { it.getString("verdict") == "passed" })
        val coverage = report.getJSONObject("coverage")
        dialogue.steps.withIndex().groupBy({ checkNotNull(it.value.goal) }, { "passed at step ${it.index + 1}" }).forEach { (goal, verdicts) ->
            assertEquals("$name: $coverage", verdicts.joinToString(), coverage.getString(goal))
        }
        val checks = report.getJSONArray("checks")
        assertTrue("$name: $checks", (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("pass") })
        return report
    }

    /**
     * Runs [dialogue] on the scripted voice server and the scripted chat of [replies], wrapped by [wrap], into [directory]. Each frame that
     * the window draws passes each semantics node inside the stage to [onStage] with the number of the step. Returns the report.
     */
    private fun run(dialogue: Dialogue, replies: Map<Int, StepReplies>, directory: File, count: Int = dialogue.steps.size,
        wrap: (ChatProvider) -> ChatProvider = { it }, onStage: (Int, SemanticsNode) -> Unit = { _, _ -> }): JSONObject {
        lateinit var run: DialogueRun
        run = DialogueRun(dialogue, directory, "rehearsal", count = count)
        fun visit(node: SemanticsNode, stage: Boolean) {
            val inStage = stage || node.config.getOrNull(SemanticsProperties.TestTag) == "dialogue_stage"
            if (inStage) onStage(run.step, node)
            node.children.forEach { visit(it, inStage) }
        }
        ViewRootForTest.onViewCreatedCallback = { root ->
            root.view.doOnAttach { it.viewTreeObserver.addOnDrawListener { visit(root.semanticsOwner.unmergedRootSemanticsNode, false) } }
        }
        return try { run.run(server(dialogue), wrap(ScriptedChat(replies) { run.step }), LOOPBACK) }
            finally { ViewRootForTest.onViewCreatedCallback = null }
    }

    private fun server(dialogue: Dialogue): ScriptedLiveServer {
        val spoken = dialogue.steps.filter { it.speech != null }
        return ScriptedLiveServer(spoken.map { checkNotNull(it.speech).words }, spoken.indices.filter { !spoken[it].handOff }.toSet())
    }

    private fun rows(report: JSONObject) = report.getJSONArray("steps").let { s -> (0 until s.length()).map(s::getJSONObject) }

    /** The marks of a streamed screen in [row]: the frame, the first part, the complete picture, and controls on in this order, no gone frame. */
    private fun streamed(row: JSONObject, name: String) {
        val marks = listOf("first_line_visible", "first_part_visible", "picture_complete", "controls_on")
        assertTrue("$name$row", marks.all(row::has))
        assertEquals("$name$row", marks.map(row::getLong).sorted(), marks.map(row::getLong))
        assertTrue("$name$row", row.getInt("preview_updates") >= 2)
        assertEquals("$name$row", 0, row.getInt("previews_gone"))
    }

    /** [inner] that notes, for each text delta of a template call, its time and the text of the call so far, before the app gets it. */
    private fun templateText(inner: ChatProvider, sent: MutableList<Pair<Long, String>>) = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            val text = StringBuilder()
            inner.respond(key, request, session) { event ->
                if (request.generationStage == CloudGenerationStage.APP_FILE_TEMPLATE && event is CloudEvent.Text)
                    sent += SystemClock.elapsedRealtimeNanos() to text.append(event.value).toString()
                emit(event)
            }
        }
    }

    /** The replies of the steps of a template dialogue: the first screen, the changed lines, and the next screen. */
    private class TemplateFiles(val first: String, val change: String, val next: String)

    private companion object {
        val LOOPBACK = Endpoints { "http://127.0.0.1:4000" }
        const val TEMPLATE = "APP_FILE_TEMPLATE"
        /** The text of the drawing of `lesson.plan`, the start of the label of its canvas. */
        const val LESSON_DRAWING = "Земля в центре, Луна на орбите и метка её стороны"
        /** A change by lines of `lesson.plan`: the lap button gets a new label and a slower lap. */
        const val LESSON_CHANGE = """need lap = button("Медленный круг", [set(${'$'}/angle, 0), animate(${'$'}/angle, 360, 10000)])"""
        /** A change by lines of `quiz.plan`: the button that counts a learned card gets a Russian label. */
        const val QUIZ_CHANGE = """learnedButton = Button("Знаю", action=[inc(${'$'}/learned), set(${'$'}/index, (${'$'}/index + 1) % len(${'$'}/cards)), set(${'$'}/shown, false)])"""
        /** The gap between two text deltas of a streamed reply, as the plan of 2026-11-21 streams the ship. */
        const val DELTA_MS = 300L
        /** In the no-screen probe the template call streams slower and the routing ends inside it, as in the live routing of 3.1 to 5.5 s. */
        const val GONE_DELTA_MS = 500L
        const val GONE_ROUTING_MS = 2_500L
        /**
         * A reply of 51 words: the scripted voice speaks 200 ms for each word, so it speaks for more than 10 s, through the tap and most
         * of the 8 s after it.
         */
        const val LONG_ANSWER = "Рычаг — это твёрдая палка, которая может поворачиваться вокруг неподвижной точки опоры. Если приложить силу к " +
            "длинному плечу рычага, то на коротком плече получится сила во много раз больше. Поэтому длинной палкой легко поднять тяжёлый " +
            "камень, а ножницы и плоскогубцы помогают резать и сжимать даже очень твёрдые вещи без большого усилия."
        val FRESH_SOURCE = Source("Ураган", "https://example.com/hurricane", "Последний ураган сезона ослаб до шторма")

        /** A routing decision of a screen that the person did not ask to see, with the spoken [answer]. */
        fun screen(answer: String) = JSONObject(chatDecision("screen", answer, "A screen for the request")).put("show", false).toString()

        /**
         * [text] as text deltas [gapMs] apart, cut in the middle of each line, so each delta but the last ends inside a line and a line
         * is complete only in the delta after its middle.
         */
        fun deltas(text: String, gapMs: Long = DELTA_MS): Scripted.Deltas {
            var start = 0
            val cuts = text.split("\n").map { line -> (start + line.length / 2).also { start += line.length + 1 } }.filter { it in 1 until text.length }
            return Scripted.Deltas((listOf(0) + cuts + text.length).zipWithNext { from, to -> gapMs to text.substring(from, to) }.filter { it.second.isNotEmpty() })
        }

        /** «Шаг 2» a screen of [plan], «Шаг 3» a press of the control [press], «Шаг 4» a change by the lines [change]. */
        fun rehearsal(plan: String, answer: String, press: String, change: String) = mapOf(
            2 to StepReplies(screen(answer), listOf("APP_FILE" to Scripted.Text(appFilePlan(plan)))),
            3 to StepReplies(chatDecision("press", "Нажимаю на экране.", press)),
            4 to StepReplies(chatDecision("refine", "Готово, экран изменён.", "Change the screen as asked"),
                listOf("APP_FILE_CHANGE" to Scripted.Text(change))))

        val REHEARSALS = mapOf(
            "heart" to rehearsal("lesson", "Сердце — насос из четырёх камер.", "Дальше", LESSON_CHANGE),
            "moon" to rehearsal("lesson", "Луна делает один оборот вокруг оси за один круг вокруг Земли.", "Вращение Луны: Без вращения",
                LESSON_CHANGE),
            "japanese" to rehearsal("quiz", "По-японски считают так: ити, ни, сан, си, го.", "答えを見る", QUIZ_CHANGE))

        /** `kit/test/fixtures/template/ship.plan`, the example template file of the design. */
        val SHIP = """
            scene("Почему корабль не тонет?", lang="ru", palette="blue")
            control = choice("Предмет", ["Корабль", "Гвоздь"])
            s1 = rect(0, 110, 300, 110, "water", 0)
            s2 = polygon([45, 75, 255, 75, 225, 140, 75, 140], "ink", visible=${'$'}/v == "Корабль")
            s3 = text(150, 50, "Воздух внутри", "ink", 14, visible=${'$'}/v == "Корабль")
            card = card("Вытеснение воды", "Полый корабль вытесняет достаточно воды, чтобы удержаться на плаву.")
        """.trimIndent() + "\n"

        /** The template replies of each template dialogue. Each file compiles clean with the kit, and each change merges clean. */
        val TEMPLATES = mapOf(
            "tpl-a" to TemplateFiles("""
                scene("Почему магнит тянет железо?", lang="ru", palette="red")
                control = slider("Расстояние до скрепки", 20, 120, 20, 60)
                s1 = rect(30, 80, 70, 60, "red", 6)
                s2 = text(65, 115, "Магнит", "surface", 14)
                s3 = circle(110 + ${'$'}/v, 110, 12, "muted")
                s4 = text(150, 190, "Чем ближе, тем сильнее", "ink", 14)
                card = card("Магнитное поле", "Магнит создаёт вокруг себя поле. Железо в этом поле само становится магнитом и тянется к нему.")
            """, """
                s5 = line(98 + ${'$'}/v, 110, 104, 110, "ink", 3)
                s6 = text(150, 40, "Скрепку тянет к магниту", "ink", 14)
            """, """
                scene("Откуда берётся эхо?", lang="ru", palette="indigo")
                control = slider("Расстояние до скалы", 40, 200, 40, 160)
                s1 = rect(260, 20, 30, 180, "muted", 4)
                s2 = circle(250 - ${'$'}/v, 170, 12, "accent")
                s3 = line(250 - ${'$'}/v, 160, 260, 70, "ink", 2)
                s4 = line(260, 70, 250 - ${'$'}/v, 180, "accent", 2)
                s5 = text(150, 30, "Звук отражается от скалы", "ink", 13)
                card = card("Отражённый звук", "Звук долетает до скалы, отражается и возвращается. Чем дальше скала, тем позже слышно эхо.")
            """),
            "tpl-b" to TemplateFiles("""
                scene("Как рождается ураган?", lang="ru", palette="teal")
                control = choice("Стадия", ["Тёплый океан", "Вихрь", "Ураган"])
                s1 = rect(0, 170, 300, 50, "water", 0)
                s2 = text(150, 150, "Тёплая вода испаряется", "ink", 13, visible=${'$'}/v == "Тёплый океан")
                s3 = circle(150, 90, 40, "muted", stroke=true, visible=${'$'}/v == "Вихрь")
                s4 = circle(150, 90, 60, "muted", visible=${'$'}/v == "Ураган")
                s5 = circle(150, 90, 12, "surface", visible=${'$'}/v == "Ураган")
                card = card("Тепло океана", "Над тёплым океаном влажный воздух поднимается и закручивается. Чем теплее вода, тем сильнее вихрь.")
            """, """
                s6 = text(150, 20, "Последний ураган ослаб", "ink", 13)
                card = card("Тепло океана", "Над тёплым океаном влажный воздух поднимается и закручивается. По свежим новостям, последний ураган уже ослаб.")
            """, """
                scene("Почему гром позже молнии?", lang="ru", palette="purple")
                control = slider("Расстояние до грозы, км", 1, 5, 1, 3)
                s1 = polygon([60, 20, 40, 90, 60, 90, 45, 150, 90, 70, 68, 70, 85, 20], "yellow")
                s2 = circle(70 + 40 * ${'$'}/v, 180, 10, "ink")
                s3 = text(150, 210, "Звук идёт 3 секунды на км", "ink", 12)
                card = card("Свет быстрее звука", "Свет молнии приходит сразу, а звук грома идёт около трёх секунд на каждый километр.")
            """),
            "tpl-c" to TemplateFiles("""
                scene("Зачем велосипеду передачи?", lang="ru", palette="green")
                control = choice("Передача", ["Лёгкая", "Тяжёлая"])
                s1 = circle(80, 120, 50, "ink", stroke=true)
                s2 = circle(230, 120, 30, "ink", stroke=true, visible=${'$'}/v == "Лёгкая")
                s3 = circle(230, 120, 15, "ink", stroke=true, visible=${'$'}/v == "Тяжёлая")
                s4 = line(80, 70, 230, ${'$'}/v == "Лёгкая" ? 90 : 105, "muted", 2)
                s5 = line(80, 170, 230, ${'$'}/v == "Лёгкая" ? 150 : 135, "muted", 2)
                s6 = text(150, 205, "Цепь передаёт усилие", "ink", 13)
                card = card("Звёздочки разного размера", "Большая задняя звёздочка делает педали лёгкими, а маленькая делает езду быстрой, но тяжёлой.")
            """, """
                s2 = circle(230, 120, 40, "ink", stroke=true, visible=${'$'}/v == "Лёгкая")
                s4 = line(80, 70, 230, ${'$'}/v == "Лёгкая" ? 80 : 105, "muted", 2)
                s5 = line(80, 170, 230, ${'$'}/v == "Лёгкая" ? 160 : 135, "muted", 2)
            """, """
                scene("Как идут часы со стрелками?", lang="ru", palette="brown")
                control = slider("Минуты", 0, 55, 5, 15)
                s1 = circle(150, 110, 90, "ink", stroke=true)
                s2 = line(150, 110, 150, 50, "ink", 4)
                s3 = line(150, 110, 150 + 80 * sin(${'$'}/v * 6), 110 - 80 * cos(${'$'}/v * 6), "accent", 2)
                s4 = text(150, 35, "12", "ink", 14)
                card = card("Минутная стрелка", "За час минутная стрелка проходит полный круг, а часовая стрелка проходит только одно деление из двенадцати.")
            """),
            "tpl-d" to TemplateFiles("""
                items("Из гусеницы в бабочку", lang="ru", palette="orange")
                control = choice("Стадия")
                i1 = item("Яйцо", "на листе", "Бабочка откладывает яйца на лист. Через несколько дней из яйца выходит гусеница.")
                i2 = item("Гусеница", "ест и растёт", "Гусеница много ест и быстро растёт, несколько раз сбрасывая старую кожу.")
                i3 = item("Куколка", "в оболочке", "Гусеница прячется в твёрдую оболочку, и внутри её тело полностью перестраивается.")
                i4 = item("Бабочка", "летает", "Из куколки выходит бабочка, расправляет крылья и скоро начинает летать.")
                card = card("Полное превращение")
            """, """
                i1 = item("Яйцо", "5 дней", "Бабочка откладывает яйца на лист. Примерно через пять дней из яйца выходит гусеница.")
                i2 = item("Гусеница", "2 недели", "Гусеница две недели много ест и быстро растёт, несколько раз сбрасывая старую кожу.")
                i3 = item("Куколка", "10 дней", "Около десяти дней гусеница сидит в твёрдой оболочке, и её тело полностью перестраивается.")
                i4 = item("Бабочка", "до месяца", "Из куколки выходит бабочка, расправляет крылья и живёт обычно до месяца.")
            """, """
                scene("Откуда у дерева кольца?", lang="ru", palette="brown")
                control = slider("Возраст дерева, лет", 1, 5, 1, 3)
                s1 = circle(150, 110, 20 + 18 * ${'$'}/v, "sand")
                s2 = circle(150, 110, 38, "brown", stroke=true, visible=${'$'}/v >= 2)
                s3 = circle(150, 110, 56, "brown", stroke=true, visible=${'$'}/v >= 3)
                s4 = circle(150, 110, 74, "brown", stroke=true, visible=${'$'}/v >= 4)
                s5 = circle(150, 110, 92, "brown", stroke=true, visible=${'$'}/v >= 5)
                card = card("Одно кольцо за один год", "Летом дерево растёт быстро и даёт светлый слой, осенью медленно и даёт тёмный. Так за год выходит одно кольцо.")
            """),
            "tpl-e" to TemplateFiles("""
                scene("How does a plane stay up?", lang="en", palette="blue")
                control = slider("Speed of the plane", 1, 4, 1, 2)
                s1 = polygon([60, 120, 240, 110, 240, 125, 60, 130], "ink")
                s2 = line(150, 115, 150, 115 - 20 * ${'$'}/v, "accent", 4)
                s3 = text(150, 30, "Faster air means more lift", "ink", 13)
                card = card("Lift from the wings", "Air flows faster over the curved wing, so the pressure above it drops and the wing is pushed up.")
            """, """
                s4 = line(150, 115 - 20 * ${'$'}/v, 140, 127 - 20 * ${'$'}/v, "accent", 4)
                s5 = line(150, 115 - 20 * ${'$'}/v, 160, 127 - 20 * ${'$'}/v, "accent", 4)
                s6 = text(200, 70, "Lift", "accent", 14)
            """, """
                scene("How does a parachute work?", lang="en", palette="orange")
                control = choice("Parachute", ["Closed", "Open"], "Open")
                s1 = polygon([90, 80, 150, 30, 210, 80], "orange", visible=${'$'}/v == "Open")
                s2 = line(90, 80, 150, 150, "ink", 1, visible=${'$'}/v == "Open")
                s3 = line(210, 80, 150, 150, "ink", 1, visible=${'$'}/v == "Open")
                s4 = circle(150, ${'$'}/v == "Open" ? 160 : 190, 10, "ink")
                s5 = text(150, 210, "Air drag slows the fall", "ink", 13)
                card = card("Drag against the air", "The wide canopy pushes against a lot of air. This drag grows until it balances your weight, so you fall slowly.")
            """),
            "tpl-f" to TemplateFiles("""
                cells("Что такое площадь фигуры?", lang="ru", palette="teal")
                control = slider("Клеток в фигуре", 0, 24, 1, 12)
                card = card("Площадь в клетках", "Площадь показывает, сколько одинаковых квадратиков помещается в фигуру. Каждый квадратик здесь равен одной единице.")
            """, """
                control = slider("Клеток в фигуре", 0, 100, 1, 50)
            """, """
                items("Как устроены римские цифры", lang="ru", palette="indigo")
                control = choice("Цифра")
                i1 = item("Один", "I", "Одна палочка значит единицу. Две палочки II значат два, а три палочки III значат три.")
                i2 = item("Пять", "V", "V значит пять. Палочка перед V вычитает, IV это четыре, а палочка после прибавляет, VI это шесть.")
                i3 = item("Десять", "X", "X значит десять. IX это девять, XI это одиннадцать, а XX это двадцать.")
                i4 = item("Пятьдесят", "L", "L значит пятьдесят. XL это сорок, а LX это шестьдесят.")
                card = card("Римская цифра")
            """),
        // A change ends with no line end, as the model sends its last line, so the round end completes it.
        ).mapValues { (_, raw) -> raw.run { TemplateFiles(file(first), file(change).trimEnd(), file(next)) } }

        /** A raw template text of [TEMPLATES] as a file: no indent and one line end after each line. */
        fun file(raw: String) = raw.trimIndent() + "\n"
    }
}
