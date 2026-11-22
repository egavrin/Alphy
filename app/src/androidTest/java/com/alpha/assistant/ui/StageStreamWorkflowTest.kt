package com.alpha.assistant.ui

import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage.AGENT
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_TEMPLATE
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Draft
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.ToolGenerationTraceEvent
import com.alpha.assistant.tools.agentFixtureFinish
import com.alpha.assistant.tools.agentWrite
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_BRIEF
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_REQUEST
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_TEXT
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LiveProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The stream of a new screen (plan of stage templates, Task 17): the template call hands each text delta to the stage, which shows the frame
 * of each complete line, then the compiled screen while the host check runs, then the stored app. The real shell, kit, and sandbox run with
 * scripted providers whose template calls send text deltas with gaps.
 */
class StageStreamWorkflowTest {
    private val fixture = ShellFixture("stage-stream")
    private val robot = ShellRobot("stage-stream")
    private val socket = FakeLiveTransport()
    private val shell = TemplateShell(fixture, robot, socket)
    private val prompt get() = AppFilePrompt.load(fixture.context)
    @After fun remove() {
        AppFileCompiler.previewHoldMs = 0
        shell.remove()
    }

    // The owner's requirement: the screen shows at its first complete line. The call holds the last delta of the second line until then.
    @Test fun theFirstCompleteLineShowsAFrame() {
        val gate = CompletableDeferred<Unit>()
        val talk = StreamTalk(listOf(Streamed(halves(TALKING), gateAt = 3, gate = gate)))
        shell.live(talk) { _, model, phases ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            val shown = runCatching { robot.waitUntil(15_000) { stageHasText(robot, SHIP_TITLE) } }.isSuccess
            gate.complete(Unit)
            assertTrue("the title showed before the last delta of the second line", shown)
            assertEquals(TALKING, appFileOf(shell.shown(model)))
            val first = phases.indexOfFirst { it.startsWith("$PREVIEW:") }
            assertTrue(phases.toString(), first >= 0 && first < phases.indexOf("plan_reply_end"))
        }
    }

    @Test fun eachLaterLineDrawsWhenComplete() {
        val talk = StreamTalk(listOf(Streamed(halves(TALKING))))
        shell.live(talk) { _, model, _ ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            val samples = canvasUntilStored(model)
            val seen = samples.mapNotNull { it.texts }.filter { it.isNotEmpty() }.distinct()            // The first texts are those of s1 alone, and each later state adds the next line and keeps each earlier text.
            assertEquals("$seen of ${samples.size} samples: ${samples.map { "${it.at - samples.first().at} ${it.texts} ${it.holds}" }} ${talk.stages}", listOf(WATER), seen.firstOrNull())
            assertTrue(seen.toString(), seen.zipWithNext().all { (before, after) -> after.size > before.size && after.take(before.size) == before })
            assertEquals(listOf(WATER, HULL, AIR), seen.last())
        }
    }

    @Test fun anUnfinishedLineNeverDraws() {
        val gate = CompletableDeferred<Unit>()
        // Parts 8 and 9 are the halves of s3: the call holds the second half.
        val talk = StreamTalk(listOf(Streamed(halves(TALKING), gateAt = 9, gate = gate)))
        shell.live(talk) { _, model, _ ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            try {
                robot.waitUntil(30_000) { canvasTexts() == listOf(WATER, HULL) }
                during(1_500) { assertEquals(listOf(WATER, HULL), canvasTexts()) }
            } finally { gate.complete(Unit) }
            robot.waitUntil(30_000) { canvasTexts() == listOf(WATER, HULL, AIR) }
            shell.shown(model)
        }
    }

    // The owner's requirement: a fault draws one line again, never the whole template.
    @Test fun aFaultyLineAppearsAfterItsRepairAndTheOthersStay() {
        val talk = StreamTalk(listOf(Streamed(halves(shipWith(TALKING, BAD_S2))), Streamed(listOf("$S2\n"), gapMs = 0)))
        shell.live(talk) { _, model, phases ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            val samples = canvasUntilStored(model) + sample()
            val seen = samples.mapNotNull { it.texts }.distinct()
            assertTrue("the preview without s2: $seen", listOf(WATER, AIR) in seen)
            for (text in listOf(WATER, AIR)) {
                val from = samples.indexOfFirst { text in it.texts.orEmpty() }
                val lost = samples.drop(from).indexOfFirst { text !in it.texts.orEmpty() }
                assertTrue("$text left the canvas: $seen", from >= 0 && lost < 0)
            }
            val px = ceil(fixture.context.resources.displayMetrics.density).toInt()
            fun steady(name: String, bounds: List<Rect>) = assertTrue("the $name moved: $bounds", bounds.isNotEmpty() && bounds.all { near(it, bounds.first(), px) })
            steady("title", samples.mapNotNull { it.title })
            steady("control", samples.mapNotNull { it.control })
            assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages)
            assertEquals(1, phases.count { it == "repair_call_start" })
            assertEquals(listOf(WATER, HULL, AIR), samples.last().texts)
            assertEquals(TALKING, appFileOf(shell.stored(model, checkNotNull(model.stageToolId))))
        }
    }

    // The reply ends while a preview holds the transient slot: the final compile waits for it and meets no busy slot.
    @Test fun theFinalCompileNeverMeetsABusySlotFromThePreview() {
        AppFileCompiler.previewHoldMs = 800
        val lines = TALKING.lines().filter { it.isNotEmpty() }.map { "$it\n" }
        val talk = StreamTalk(listOf(Streamed(lines, gaps = listOf(300L) + List(lines.size - 1) { 60L })))
        shell.live(talk) { _, model, phases ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            assertEquals(TALKING, appFileOf(shell.shown(model)))
            assertTrue(phases.toString(), phases.none { it == "compile:BUSY" || it == "host_check_retry:javascript_busy" })
            assertTrue(phases.toString(), "template_outcome:clean0" in phases && "plan_outcome:accepted" in phases)
            // The stage holds the stored app with its control on after the accept (stage templates, decision 11).
            robot.waitUntil { "controls_on" in phases }
            assertTrue(phases.toString(), phases.indexOf("plan_outcome:accepted") < phases.indexOf("controls_on"))
        }
    }

    @Test fun theFinalFrameShowsDuringTheHostCheck() = heldAtTheAccept(StreamTalk(listOf(Streamed(halves(TALKING))))) { phases ->
        assertTrue(phases.toString(), phases.indexOf("$PREVIEW:whole") in 0 until phases.indexOf("plan_outcome:accepted"))
        assertTrue(stageHasText(robot, SHIP_CARD))
        assertFalse("the control of the preview is off", controlOn())
    }

    @Test fun aFreePlanShowsItsCompiledPlanDuringTheHostCheck() =
        heldAtTheAccept(StreamTalk(listOf(Streamed(listOf("outside(\"learn\")\n"))), AgentScriptProvider(appFilePlan("calc")))) { phases ->
            assertTrue(phases.toString(), "template_outcome:outside" in phases)
            assertTrue(phases.toString(), phases.indexOf("$PREVIEW:whole") in 0 until phases.indexOf("plan_outcome:accepted"))
            assertTrue(stageHasText(robot, CALC_LABEL))
            assertFalse("the control of the preview is off", controlOn())
        }

    // A frame before the routing call goes away when the turn is no screen. With the gate off, no frame shows before the routing.
    @Test fun aFrameBeforeRoutingGoesAwayWhenTheTurnIsNoScreen() {
        val routes = List(2) { CompletableDeferred<Unit>() }
        val second = Streamed(halves(TALKING), gapMs = 50)
        val talk = StreamTalk(listOf(Streamed(halves(TALKING), gapMs = 150), second), routes = routes)
        shell.live(talk) { _, model, phases ->
            socket.say(ANSWER_REQUEST, "d1", 100)
            robot.waitUntil(30_000) { stageHasText(robot, SHIP_TITLE) }
            routes[0].complete(Unit)
            robot.waitUntil(30_000) { shell.messages(model).lastOrNull()?.status == "complete" }
            robot.waitUntil { model.tools.stagePreviews.current == null && !stageHasText(robot, SHIP_TITLE) }
            assertTrue(phases.toString(), GONE in phases)
            assertEquals("", shell.messages(model).last().error)
            robot.waitUntil { socket.commentary().isNotEmpty() }
            robot.awaitFrames(5)
            assertEquals(listOf("d1" to ANSWER_SPOKEN), socket.commentary())
            // The gate off: the same turn shows no frame, before and after its routing.
            val before = phases.size
            robot.instrumentation.runOnMainSync { model.tools.previewBeforeRouting = false }
            socket.say(ANSWER_REQUEST, "d2", 2_000)
            robot.waitUntil(30_000) { second.sent.size == second.parts.size }
            during(1_000) { assertNull(model.tools.stagePreviews.current); assertFalse(stageHasText(robot, SHIP_TITLE)) }
            routes[1].complete(Unit)
            robot.waitUntil(30_000) { socket.commentary().size == 2 }
            during(500) { assertNull(model.tools.stagePreviews.current) }
            assertTrue(phases.drop(before).toString(), phases.drop(before).none { it.startsWith(PREVIEW) })
            assertEquals(listOf("d2" to ANSWER_SPOKEN), socket.commentary().drop(1))
        }
    }

    @Test fun withTheGateOffTheFrameWaitsForTheRouting() {
        val routes = listOf(CompletableDeferred<Unit>())
        val reply = Streamed(halves(TALKING), gapMs = 50)
        shell.live(StreamTalk(listOf(reply), routes = routes)) { _, model, phases ->
            robot.instrumentation.runOnMainSync { model.tools.previewBeforeRouting = false }
            socket.say(SCREEN_REQUEST, "d1", 100)
            robot.waitUntil(30_000) { reply.sent.size == reply.parts.size }
            during(1_000) { assertNull(model.tools.stagePreviews.current); assertFalse(stageHasText(robot, SHIP_TITLE)) }
            assertTrue(phases.toString(), phases.none { it.startsWith(PREVIEW) })
            routes[0].complete(Unit)
            robot.waitUntil(30_000) { stageHasText(robot, SHIP_TITLE) }
            assertEquals(TALKING, appFileOf(shell.shown(model)))
            assertTrue(phases.toString(), phases.any { it.startsWith("$PREVIEW:") })
        }
    }

    // Each update holds the slot for 800 ms, so an update is in flight when the routing answers and the frame goes.
    @Test fun aLateFrameAfterGoneNeverShows() {
        AppFileCompiler.previewHoldMs = 800
        val routes = listOf(CompletableDeferred<Unit>())
        shell.live(StreamTalk(listOf(Streamed(halves(TALKING))), routes = routes)) { _, model, phases ->
            socket.say(ANSWER_REQUEST, "d1", 100)
            robot.waitUntil(30_000) { stageHasText(robot, SHIP_TITLE) }
            routes[0].complete(Unit)
            robot.waitUntil(30_000) { GONE in phases }
            during(2_500) { assertNull(model.tools.stagePreviews.current); assertFalse(stageHasText(robot, SHIP_TITLE)) }
            assertTrue(phases.toString(), phases.drop(phases.indexOf(GONE)).none { it.startsWith("$PREVIEW:") })
        }
    }

    // The faulty first line comes from the repair, after the stage showed the frame of the stream.
    @Test fun aWholeFailureRemovesTheFrame() {
        val full = CompletableDeferred<Unit>()
        val talk = StreamTalk(listOf(Streamed(halves(shipWith(TALKING, BAD_S2)), gapMs = 150), Streamed(listOf("$SKY_HEAD\n"), gapMs = 0)),
            fullGeneration(), full)
        shell.live(talk) { _, model, phases ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            try {
                robot.waitUntil(30_000) { stageHasText(robot, SHIP_TITLE) }
                robot.waitUntil(30_000) { "d1" to FULL_WAIT in socket.commentary() }
                assertNull(model.tools.stagePreviews.current)
                robot.waitUntil { !stageHasText(robot, SHIP_TITLE) }
                assertTrue(phases.toString(), "template_outcome:whole" in phases && GONE in phases)
            } finally { full.complete(Unit) }
            assertEquals("Fixture", shell.shown(model).let { com.alpha.assistant.tools.ToolSpec.parsePersisted(it.spec).title })
            assertEquals(List(2) { APP_FILE_TEMPLATE } + APP_FILE + List(2) { AGENT }, talk.stages)
        }
    }

    @Test fun aStoppedCallAsksOnceForTheMissingLines() {
        val full = CompletableDeferred<Unit>()
        val stopped = { Streamed(halves(TALKING), gapMs = 150, stopAfter = 8) }
        val rest = Streamed(listOf("$S3\n", "$CARD\n"), gapMs = 0)
        val talk = StreamTalk(listOf(stopped(), rest, stopped(), Streamed(emptyList(), stopAfter = 0)), fullGeneration(), full)
        shell.live(talk) { _, model, phases ->
            // The call stops after s2. The rest call sends the four complete lines, and its reply completes the screen.
            val app = shell.screen(model)
            assertEquals(TALKING, appFileOf(app))
            assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages)
            val linesSoFar = TALKING.lines().take(4).joinToString("\n", postfix = "\n")
            assertEquals(listOf(prompt.templateRestText(talk.calls[0].turns.single().text, linesSoFar)), talk.calls[1].turns.map { it.text })
            assertEquals(prompt.template, talk.calls[1].systemInstructions)
            // The rest call of the next screen fails too: the frame over the shown app goes, and the full generation runs.
            val gone = phases.count { it == GONE }
            socket.say(SCREEN_REQUEST, "d2", 2_000)
            try {
                robot.waitUntil(30_000) { model.tools.stagePreviews.current?.revision == null && model.tools.stagePreviews.current != null }
                robot.waitUntil(30_000) { "d2" to FULL_WAIT in socket.commentary() }
                assertNull(model.tools.stagePreviews.current)
                assertEquals(gone + 1, phases.count { it == GONE })
                assertEquals(List(4) { APP_FILE_TEMPLATE }, talk.stages)
            } finally { full.complete(Unit) }
            robot.waitUntil(60_000) { model.stageToolId != app.id && !model.tools.busy }
            assertEquals(List(4) { APP_FILE_TEMPLATE } + APP_FILE + List(2) { AGENT }, talk.stages)
            assertTrue(canvasTexts() == null)
        }
    }

    @Test fun typedChatShowsNoPreview() {
        val talk = StreamTalk(emptyList(), AgentScriptProvider(appFilePlan("calc")))
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            fixture.install(chat = talk)
            fixture.launch().use { scenario ->
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading }
                val phases = CopyOnWriteArrayList<String>()
                scenario.onActivity {
                    model.tools.generationTraceObserver = { event -> if (event is ToolGenerationTraceEvent.Phase) phases += event.name }
                    model.editDraft(Draft(APP_REQUEST)); model.send()
                }
                val previews = mutableSetOf<Any?>()
                robot.waitUntil(60_000) { previews += model.tools.stagePreviews.current; shell.messages(model).any { it.toolId != null } }
                assertEquals(setOf(null), previews)
                assertTrue(phases.toString(), phases.none { it.startsWith(PREVIEW) } && "plan_outcome" in phases)
                assertEquals(listOf(APP_FILE), talk.stages)
            }
        }
    }

    /**
     * The spoken screen of [talk], held in its run after the host check (the mark plan_outcome:accepted, on the thread of the run) until
     * [check] has read the stage. Then the app is stored.
     */
    private fun heldAtTheAccept(talk: StreamTalk, check: (List<String>) -> Unit) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        shell.live(talk, observe = { if (it == "plan_outcome:accepted") { held.countDown(); release.await(30, TimeUnit.SECONDS) } }) { _, model, phases ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            try {
                assertTrue("the run did not reach its accept", held.await(60, TimeUnit.SECONDS))
                robot.waitUntil { stageHasText(robot, SHIP_CARD) || stageHasText(robot, CALC_LABEL) }
                check(phases.toList())
                assertNull("the run stored its app while it was held", model.stageToolId)
            } finally { release.countDown() }
            shell.shown(model)
        }
    }

    private class Sample(val texts: List<String>?, val title: Rect?, val control: Rect?, val at: Long = SystemClock.uptimeMillis(), val holds: String? = null)

    private fun sample() = Sample(shipCanvasTexts(robot), stageBounds(robot, SHIP_TITLE), stageBounds(robot, NAIL),
        holds = checkNotNull(fixture.model).tools.stagePreviews.current?.let { "${it.frame.holds}/${it.revision}" })

    /** The stage every 30 ms until the operation of the request stored its app and ended. */
    private fun canvasUntilStored(model: ShellViewModel): List<Sample> {
        val samples = mutableListOf<Sample>()
        val end = SystemClock.uptimeMillis() + 60_000
        while (!(model.stageToolId != null && !model.tools.busy)) {
            check(SystemClock.uptimeMillis() < end) { "the app was not stored within 60 s" }
            samples += sample()
            SystemClock.sleep(30)
        }
        return samples
    }

    private fun canvasTexts() = shipCanvasTexts(robot)

    private fun controlOn() = stageControlOn(robot)

    private fun near(a: Rect, b: Rect, px: Int) = abs(a.left - b.left) <= px && abs(a.top - b.top) <= px && abs(a.right - b.right) <= px &&
        abs(a.bottom - b.bottom) <= px

    /** The replies of the full generation after a screen with no app: its plan call replies outside, and the loop writes the fixture app. */
    private fun fullGeneration() = AgentScriptProvider(listOf(Scripted.Text("outside(\"game\")"), Scripted.Text(agentWrite(FIXTURE_APP)),
        Scripted.Text(agentFixtureFinish)))

    private companion object {
        const val STAGE = "dialogue_stage"
        const val PREVIEW = "stage_preview"
        const val GONE = "stage_preview_gone"
        const val APP_REQUEST = "Сделай калькулятор чаевых"
        const val ANSWER_REQUEST = "Какая погода"
        const val ANSWER_SPOKEN = "${LiveProtocol.MARK}Fixture response."
        const val SHIP_CARD = "Вытеснение воды"
        /** A figure label of the calc app. */
        const val CALC_LABEL = "Per person"
        /** A first line with a palette that the kit does not have: a whole fault. */
        const val SKY_HEAD = "scene(\"Почему корабль не тонет?\", lang=\"ru\", palette=\"sky\")"
        const val FULL_WAIT = LiveVoiceBridge.FULL_WAIT
    }
}

/** The title of the ship template and of [TALKING]. */
internal const val SHIP_TITLE = "Почему корабль не тонет?"
internal const val WATER = "Вода держит"
internal const val HULL = "Корпус полый"
internal const val AIR = "Воздух внутри"
internal const val S1 = "s1 = text(150, 30, \"$WATER\", \"ink\", 14)"
internal const val S2 = "s2 = text(150, 90, \"$HULL\", \"ink\", 14, visible=\$/v == \"Корабль\")"
/** The ship template with texts in s1 and s2, so the labels of the canvas name each part. Its s3 and card are those of the ship. */
internal val TALKING: String get() = shipWith(shipWith(shipTemplate(), S1), S2)
internal val S3: String get() = lineOf(TALKING, "s3")
internal val CARD: String get() = TALKING.lines().single { it.startsWith("card = ") }

/** Each line of [text] as two text deltas: its first half, then the rest with its line end. */
internal fun halves(text: String) = text.lines().filter { it.isNotEmpty() }.flatMap { listOf(it.take(it.length / 2), it.drop(it.length / 2) + "\n") }

/**
 * [read] of each semantics node in the stage of the resumed activity, on the main thread now. A read through the scenario first waits for an
 * idle main queue, which the shell does not reach while a run shows a frame: such a read waited 3.4 s and saw no frame of the stream, and one
 * waited until the run ended at its 45 s limit.
 */
internal fun <T> readStage(robot: ShellRobot, read: (List<SemanticsNode>) -> T): T {
    var result: T? = null
    robot.instrumentation.runOnMainSync {
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        result = read(activity?.let(robot::nodes).orEmpty().filter { it.config.getOrNull(SemanticsProperties.TestTag) == "dialogue_stage" }.flatMap(::all))
    }
    @Suppress("UNCHECKED_CAST") return result as T
}

/** Each text of a node in the stage, read as [readStage] reads. */
internal fun stageTexts(robot: ShellRobot): List<String> =
    readStage(robot) { nodes -> nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } } }

/**
 * The texts that the canvas of a ship template on the stage draws, in their order, or null when the stage shows no such canvas. The canvas
 * label is the title and then each text with a period, as "title. A. B.".
 */
internal fun shipCanvasTexts(robot: ShellRobot): List<String>? = readStage(robot) { nodes ->
    nodes.firstNotNullOfOrNull { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it.startsWith(SHIP_TITLE) } }
}?.removePrefix(SHIP_TITLE)?.trim('.', ' ')?.split(". ")?.filter { it.isNotBlank() }

/** True when the stage shows a control that a tap can press. */
internal fun stageControlOn(robot: ShellRobot) =
    readStage(robot) { nodes -> nodes.any { SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled !in it.config } }

/** True when a node in the stage has [text]. */
internal fun stageHasText(robot: ShellRobot, text: String) = readStage(robot) { nodes -> nodes.any { robot.hasText(text)(it.config) } }

/** The window bounds of the first node in the stage with [text], or null. */
internal fun stageBounds(robot: ShellRobot, text: String): Rect? = readStage(robot) { nodes ->
    nodes.firstOrNull { robot.hasText(text)(it.config) }?.let { node ->
        val at = node.positionInWindow
        Rect(at.x.roundToInt(), at.y.roundToInt(), (at.x + node.size.width).roundToInt(), (at.y + node.size.height).roundToInt())
    }
}

/** Runs [check] every 30 ms for [ms]. */
internal fun during(ms: Long, check: () -> Unit) {
    val end = SystemClock.uptimeMillis() + ms
    while (SystemClock.uptimeMillis() < end) { check(); SystemClock.sleep(30) }
}

/**
 * One reply of a template call: each of [parts] as one text delta after its gap ([gaps], else [gapMs]), then the end of the round. With
 * [stopAfter], the call fails after that many parts, as a lost connection. With [gate], the call waits for it before the part [gateAt].
 * [sent] holds the clock time of each part that it sent.
 */
internal class Streamed(val parts: List<String>, private val gapMs: Long = 300, private val gaps: List<Long>? = null,
    private val stopAfter: Int? = null, private val gateAt: Int? = null, private val gate: CompletableDeferred<Unit>? = null) {
    val sent = CopyOnWriteArrayList<Long>()

    suspend fun send(emit: suspend (CloudEvent) -> Unit) {
        parts.forEachIndexed { index, part ->
            if (index == stopAfter) throw CloudFailure("stream_io", "The scripted call stopped.")
            if (index == gateAt) gate?.await()
            delay(gaps?.get(index) ?: gapMs)
            emit(CloudEvent.Text(part))
            sent += SystemClock.uptimeMillis()
        }
        if (stopAfter == parts.size) throw CloudFailure("stream_io", "The scripted call stopped.")
        emit(CloudEvent.RoundComplete(parts.joinToString(""), false))
    }
}

/**
 * A talk whose template calls stream. Each template call answers from [templates] in its order, and each other generation call from
 * [plans]; the first plan call waits for [planGate]. The routing call with the index i waits for [routes] i when it exists, then gives a
 * screen that the person asked to see for [SCREEN_REQUEST], an app for "Сделай калькулятор чаевых", and a spoken answer for each other request.
 * [calls] holds each generation call in its order.
 */
internal class StreamTalk(private val templates: List<Streamed>, private val plans: AgentScriptProvider = AgentScriptProvider(appFilePlan("calc")),
    private val planGate: CompletableDeferred<Unit>? = null, private val routes: List<CompletableDeferred<Unit>> = emptyList()) : ChatProvider {
    val calls = CopyOnWriteArrayList<CloudRequest>()
    val stages get() = calls.map { it.generationStage }
    @Volatile private var routings = 0

    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        val stage = request.generationStage
        if (stage == APP_FILE_TEMPLATE) {
            val index = calls.count { it.generationStage == APP_FILE_TEMPLATE }
            calls += request
            return templates[index].send(emit)
        }
        if (stage != null) {
            if (plans.calls.isEmpty()) planGate?.await()
            calls += request
            return plans.respond(key, request, session, emit)
        }
        routes.getOrNull(routings++)?.await()
        emit(CloudEvent.RoundComplete(when (request.decisionContext?.originalUserTurn?.text) {
            SCREEN_REQUEST -> JSONObject(chatDecision("screen", SCREEN_TEXT, SCREEN_BRIEF)).put("show", true).toString()
            "Сделай калькулятор чаевых" -> chatDecision("create", "Готово.", "Make the app")
            else -> chatDecision("answer", "Fixture response")
        }, false))
    }
}
