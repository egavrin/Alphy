package com.alpha.assistant.ui

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.ChatToolReply
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.SceneMotion
import com.alpha.assistant.tools.SceneStep
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.ui.MotionTalk.Companion.FASTER
import com.alpha.assistant.ui.MotionTalk.Companion.HEART_REQUEST
import com.alpha.assistant.ui.MotionTalk.Companion.MOTION_REQUEST
import com.alpha.assistant.ui.MotionTalk.Companion.NEXT
import com.alpha.assistant.ui.MotionTalk.Companion.SLOWER
import com.alpha.assistant.ui.MotionTalk.Companion.STOP
import com.alpha.assistant.ui.MotionTalk.Companion.THIRD
import com.alpha.assistant.voice.FakeLiveTransport
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * The motion and the diagrams of a scene on the dialogue stage (block 2 plan, stage 7): a play scene moves at once with at least 30 pictures
 * each second (M1, C1), the routing presses its speed, its pause, and its play with no template call, and the shown screen names its state
 * (M2, C6). A press glides to its new drawing (M5). The motion stops while the stage is not on the screen (C7). A diagram walks its parts (G1
 * to G3). The real shell, kit, and sandbox run with scripted providers. The scenes are the kit fixtures `orbit` and `heart`.
 */
class StageMotionWorkflowTest {
    private val fixture = ShellFixture("stage-motion")
    private val robot = ShellRobot("stage-motion")
    private val socket = FakeLiveTransport()
    private val shell = TemplateShell(fixture, robot, socket)
    private val orbit get() = appFilePlan("template/orbit")
    private val heart get() = appFilePlan("template/heart")
    @After fun remove() = shell.remove()

    /**
     * M1 and C1: the scene moves from its first state with no press, and the stage draws different pictures for 5 s. The gate of C1 is a run of
     * this test alone on a quiet emulator with `-e stageMotionGate 30`: at least 30 pictures each second. A run in a batch asks for 15, because
     * an emulator beside a second busy emulator drew 29.4 where it drew 35.8 alone.
     */
    @Test fun aPlaySceneMovesAtOnceWithThirtyPicturesEachSecond() {
        val bar = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("stageMotionGate")?.toInt() ?: 15
        shell.live(MotionTalk(AgentScriptProvider(orbit))) { scenario, model, _ ->
            socket.say(MOTION_REQUEST, "d1", 100)
            shell.finalFile(model, shell.shown(model).id)
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, ORBIT_CAPTION) && pictures() > 0 }
            SystemClock.sleep(1_000)
            val trace = checkNotNull(SurfaceFrameMetrics.snapshot())
            val start = SystemClock.elapsedRealtimeNanos()
            SystemClock.sleep(5_000)
            val end = checkNotNull(SurfaceFrameMetrics.snapshot())
            val seconds = (SystemClock.elapsedRealtimeNanos() - start) / 1e9
            val count = end.pictures - trace.pictures
            println(JSONObject().put("gate", "C1").put("file", "orbit").put("pictures", count).put("seconds", seconds).put("perSecond", count / seconds)
                .put("timerSnapshots", end.acceptedFrames - trace.acceptedFrames).put("sameTrace", end.generation == trace.generation))
            assertEquals("one trace for the whole count", trace.generation, end.generation)
            assertTrue("pictures in $seconds s: $count, and the bar is $bar each second", count >= bar * seconds)
        }
    }

    /**
     * M2 and C6: the routing presses «быстрее», «стоп», and «дальше» with no template call. The shown screen names the motion with its speed,
     * a stopped scene draws no new picture, and «дальше» on a stopped scene with no steps sees the stopped state and the play button. The
     * play row shows only in a scene with no control line, such as the orbit (stage 7, fix list 2, H4).
     */
    @Test fun theRoutingPressesTheSpeedThePauseAndThePlay() {
        val talk = MotionTalk(AgentScriptProvider(orbit))
        shell.live(talk) { scenario, model, _ ->
            socket.say(MOTION_REQUEST, "d1", 100)
            val app = shell.shown(model)
            shell.finalFile(model, app.id)
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, ORBIT_CAPTION) }
            fun shown() = checkNotNull(ShownScreen.of(shell.stored(model, app.id)))
            assertEquals(SceneMotion(true, "Обычно"), shown().motion)
            assertEquals(listOf("Пауза", "Движение: Медленно", "Движение: Обычно", "Движение: Быстро"), shown().controls.map { it.label })
            fun spoken(text: String, id: String, atMs: Long) {
                val count = shell.messages(model).size
                socket.say(text, id, atMs)
                shell.waitForTheAnswer(model, count)
                // The answer of a press that ran is the routing text, not the host line of a missing or failed press.
                val answer = shell.messages(model).last().text
                assertTrue("$text: $answer", answer != LiveVoiceBridge.PRESS_MISSING && answer != LiveVoiceBridge.PRESS_FAILED)
            }
            fun motionIs(want: SceneMotion) = try { robot.waitUntil(10_000) { shown().motion == want } } catch (failure: AssertionError) {
                throw AssertionError("the motion is ${shown().motion}, not $want, with the state ${shell.stored(model, app.id).state.take(300)}", failure)
            }
            spoken(FASTER, "d2", 2_000)
            motionIs(SceneMotion(true, "Быстро"))
            spoken(STOP, "d3", 4_000)
            motionIs(SceneMotion(false, "Быстро"))
            SystemClock.sleep(500)
            val stopped = pictures()
            SystemClock.sleep(1_000)
            assertTrue("a stopped scene draws no new picture: ${pictures() - stopped}", pictures() - stopped <= 2)
            spoken(NEXT, "d4", 6_000)
            // The routing call of «дальше» saw the stopped motion and the play button.
            val line = checkNotNull(talk.routes.last().shownScreen)
            assertTrue(line, "\"motion\":{\"state\":\"stopped\",\"speed\":\"Быстро\"}" in line && "\"Пуск\"" in line)
            assertTrue(ChatToolReply.shownScreenInstructions.contains("Go on, next, or again on a screen with motion and no step presses the play button."))
            motionIs(SceneMotion(true, "Быстро"))
            val played = pictures()
            SystemClock.sleep(1_000)
            assertTrue("the scene moves again: ${pictures() - played}", pictures() - played >= 20)
            // Each press moved the scene with no template call.
            assertEquals(1, talk.calls.size)
        }
    }

    /** M4 (stage 7, fix list 1, F5): «медленнее» presses the speed before the present one. At the slow speed it presses nothing. */
    @Test fun slowerPressesTheSpeedBeforeAndNothingAtTheSlowSpeed() {
        val talk = MotionTalk(AgentScriptProvider(orbit))
        shell.live(talk) { scenario, model, _ ->
            socket.say(MOTION_REQUEST, "d1", 100)
            val app = shell.shown(model)
            shell.finalFile(model, app.id)
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, ORBIT_CAPTION) }
            fun shown() = checkNotNull(ShownScreen.of(shell.stored(model, app.id)))
            fun slower(id: String, atMs: Long) { val count = shell.messages(model).size; socket.say(SLOWER, id, atMs); shell.waitForTheAnswer(model, count) }
            slower("d2", 2_000)
            robot.waitUntil(10_000) { shown().motion == SceneMotion(true, "Медленно") }
            slower("d3", 4_000)
            assertTrue(checkNotNull(talk.routes.last().shownScreen).contains("\"speed\":\"Медленно\""))
            assertEquals(MotionTalk.SLOWEST, shell.messages(model).last().text)
            during(1_000) { assertEquals(SceneMotion(true, "Медленно"), shown().motion) }
            assertEquals(1, talk.calls.size)
        }
    }

    /**
     * F1 (stage 7, fix lists 1 and 2, H3): 20 taps on the running orbit (each speed, the pause, and the play, four times). The robot taps a
     * control only when it is enabled, as a person can. Each tap takes effect, and the median time from the tap to the new state is at most
     * 300 ms. No tap waits more than 1 s (stage 7b). The log gives each time and the time that the controls look disabled.
     */
    @Test fun twentyTapsOnARunningSceneTakeEffectInAMedianOf300Ms() {
        shell.live(MotionTalk(AgentScriptProvider(orbit))) { scenario, model, _ ->
            socket.say(MOTION_REQUEST, "d1", 100)
            shell.finalFile(model, shell.shown(model).id)
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, ORBIT_CAPTION) && pictures() > 0 }
            SystemClock.sleep(1_000)
            fun gray(nodes: List<SemanticsNode>) = nodes.count { SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled in it.config }
            val start = readStage(robot, ::gray)
            val results = List(4) { listOf("Быстро", "Медленно", "Обычно", PAUSE, PLAY) }.flatten().map { label ->
                robot.waitUntil(5_000) { readStage(robot, ::gray) <= start }
                val pressed = SystemClock.elapsedRealtimeNanos()
                val found = readStage(robot) { nodes -> press(nodes, label) }
                var shownMs: Double? = null
                var grayMs: Double? = null
                var readyMs: Double? = null
                var stalled = false
                while (found) {
                    val ms = (SystemClock.elapsedRealtimeNanos() - pressed) / 1e6
                    // A press that shows nothing after 800 ms prints the threads that wait on the store, to name what holds it.
                    if (shownMs == null && ms > 800 && !stalled) {
                        stalled = true
                        println("F1 stall $label: " + Thread.getAllStackTraces().filter { (_, stack) ->
                            stack.any { frame -> listOf("ToolStore", "SQLite", "ArtifactCoordinator").any { it in frame.className } } }
                            .map { (thread, stack) -> thread.name + "\n  " + stack.take(25).joinToString("\n  ") })
                    }
                    val (done, off) = readStage(robot) { nodes -> pressedState(nodes, label) to (gray(nodes) > start) }
                    if (done && shownMs == null) shownMs = ms
                    if (off && grayMs == null) grayMs = ms
                    if (!off && grayMs != null && readyMs == null) readyMs = ms
                    // The known lock stall holds a tap for about 2.7 s, so the wait for its state is 8 s.
                    if (ms > 8_000 || shownMs != null && !off && ms > 300) break
                    SystemClock.sleep(5)
                }
                Triple(label, shownMs, grayMs?.let { it to readyMs })
            }
            val text = results.joinToString { (label, shown, gray) -> "$label ${shown?.roundToInt()} ms" +
                (gray?.let { (from, to) -> " disabled ${from.roundToInt()} to ${to?.roundToInt()} ms" } ?: "") }
            println(JSONObject().put("gate", "F1").put("presses", text))
            assertTrue("each tap takes effect: $text", results.all { (_, shown, _) -> shown != null })
            // Stage 7b (R1): with no lock cycle of the store, no tap waits more than 1 s.
            assertTrue("no tap waits more than 1 s: $text", results.all { (_, shown, _) -> shown!! <= 1_000 })
            val median = results.mapNotNull { it.second }.sorted().let { (it[9] + it[10]) / 2 }
            assertTrue("the median time from a tap to its state is $median ms, above 300 ms: $text", median <= 300)
        }
    }

    /**
     * A tap on the control with the text or the accessible name [label]: the click action of the nearest node around it that has one. A speed
     * is a tap of the speed chip of the large picture, which selects the next speed (stage A, AH5), so [label] must be the speed after the
     * present one. False when the stage shows no such control.
     */
    private fun press(nodes: List<SemanticsNode>, label: String): Boolean {
        val node = if (label in ORBIT_SPEEDS) nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(MOTION) }?.also { chip ->
            val present = chip.config.getOrNull(SemanticsProperties.StateDescription)
            check(ORBIT_SPEEDS[(ORBIT_SPEEDS.indexOf(present) + 1) % ORBIT_SPEEDS.size] == label) { "the speed after $present is not $label" }
        } else nodes.firstOrNull { robot.hasText(label)(it.config) || it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
        if (node == null) return false
        val target = generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }
        checkNotNull(target.config[SemanticsActions.OnClick].action).invoke()
        return true
    }

    /** True when the stage shows the state after a tap of [label]: the speed chip shows the speed, or the button shows the other state. */
    private fun pressedState(nodes: List<SemanticsNode>, label: String): Boolean {
        fun named(name: String) = nodes.any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true }
        return when (label) {
            PAUSE -> named(PLAY)
            PLAY -> named(PAUSE)
            else -> nodes.any { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(MOTION) &&
                it.config.getOrNull(SemanticsProperties.StateDescription) == label }
        }
    }

    /** M5: a press of a choice of a template scene glides to the new drawing, and its last frame comes at most 300 ms after the press. */
    @Test fun aPressGlidesToItsNewDrawingInAtMost300Ms() {
        shell.live(MotionTalk(AgentScriptProvider(shipTemplate()))) { scenario, model, _ ->
            socket.say(MOTION_REQUEST, "d1", 100)
            val app = shell.shown(model)
            shell.finalFile(model, app.id)
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, SHIP_CAPTION) && pictures() > 0 }
            SystemClock.sleep(1_000)
            val before = checkNotNull(SurfaceFrameMetrics.snapshot()).glideEnds.size
            val pressed = SystemClock.elapsedRealtimeNanos()
            tap(scenario, NAIL)
            robot.waitUntil(5_000) { checkNotNull(SurfaceFrameMetrics.snapshot()).glideEnds.size > before }
            val ms = (checkNotNull(SurfaceFrameMetrics.snapshot()).glideEnds[before] - pressed) / 1e6
            println(JSONObject().put("gate", "M5").put("file", "ship").put("pressToLastFrameMs", ms))
            robot.waitUntil(10_000) { shipSubject(shell.stored(model, app.id)) == NAIL }
            assertTrue("the last frame of the glide came $ms ms after the press", ms <= 300)
        }
    }

    /** C7: the motion stops when the stage leaves the screen, and it runs again when the stage is back. */
    @Test fun theMotionStopsWhileTheStageIsNotOnTheScreen() {
        shell.live(MotionTalk(AgentScriptProvider(orbit))) { scenario, model, _ ->
            socket.say(MOTION_REQUEST, "d1", 100)
            shell.finalFile(model, shell.shown(model).id)
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, ORBIT_CAPTION) && pictures() > 0 }
            val running = checkNotNull(SurfaceFrameMetrics.snapshot())
            scenario.moveToState(Lifecycle.State.STARTED)
            robot.waitUntil(5_000) { SurfaceFrameMetrics.snapshot()?.stoppedAtNs != null }
            val stopped = checkNotNull(SurfaceFrameMetrics.snapshot())
            SystemClock.sleep(1_000)
            val later = checkNotNull(SurfaceFrameMetrics.snapshot())
            assertEquals("the stage started no trace and no timer: $later", stopped.generation, later.generation)
            assertEquals(stopped.acceptedFrames, later.acceptedFrames)
            scenario.moveToState(Lifecycle.State.RESUMED)
            robot.waitUntil(10_000) { SurfaceFrameMetrics.snapshot().let { it != null && it.generation > running.generation && it.stoppedAtNs == null && it.pictures >= 20 } }
        }
    }

    /**
     * G1 to G3: a diagram shows its first part with its name chip and its caption, the routing presses «Покажи третий шаг», and the chip and
     * the caption follow the part. The caption is the sentence of the part (stage A, K1).
     */
    @Test fun aDiagramWalksItsPartsAndThePressMovesTheCaption() {
        val talk = MotionTalk(AgentScriptProvider(heart))
        shell.live(talk) { scenario, model, _ ->
            socket.say(HEART_REQUEST, "d1", 100)
            val app = shell.shown(model)
            shell.finalFile(model, app.id)
            robot.waitUntil(30_000) { drew(scenario, "полая вена") && robot.composedTextIn(scenario, STAGE, "Приносит в сердце кровь от тела.") }
            fun shown() = checkNotNull(ShownScreen.of(shell.stored(model, app.id)))
            assertEquals(SceneStep(1, 7, "полая вена"), shown().step)
            // Only the marked part has its name chip, so the chip of the third part comes with the press.
            assertFalse("the chip of the third part shows at step 1", drew(scenario, "правый желудочек"))
            assertTrue(robot.composedIn(scenario, STAGE) { config -> config.getOrNull(SemanticsProperties.StateDescription)?.let { COUNT.matches(it) && it.startsWith("1 ") } == true })
            val count = shell.messages(model).size
            socket.say(THIRD, "d2", 2_000)
            shell.waitForTheAnswer(model, count)
            robot.waitUntil(10_000) { drew(scenario, "правый желудочек") && robot.composedTextIn(scenario, STAGE, "Толкает кровь в лёгкие.") }
            assertEquals(SceneStep(3, 7, "правый желудочек"), shown().step)
            assertTrue(robot.composedIn(scenario, STAGE) { config -> config.getOrNull(SemanticsProperties.StateDescription)?.let { COUNT.matches(it) && it.startsWith("3 ") } == true })
            assertEquals(1, talk.calls.size)
        }
    }

    private fun pictures() = SurfaceFrameMetrics.snapshot()?.pictures ?: 0L

    /** True when a label of the stage canvas holds [text] in any case: the canvas draws a text part with it, such as a name chip. */
    private fun drew(scenario: ActivityScenario<CloudTestActivity>, text: String) = robot.composedIn(scenario, STAGE) { config ->
        config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(text, ignoreCase = true) } == true
    }

    /** A tap on the control with [text]: the click action of the nearest node around it that has one. */
    private fun tap(scenario: ActivityScenario<CloudTestActivity>, text: String) = scenario.onActivity { activity ->
        val node = robot.nodes(activity).first { robot.hasText(text)(it.config) }
        val target = generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }
        checkNotNull(target.config[SemanticsActions.OnClick].action).invoke()
    }

    private companion object {
        const val STAGE = "dialogue_stage"
        const val ORBIT_CAPTION = "Земля обходит Солнце за год, а Луна обходит Землю примерно за месяц."
        const val PAUSE = "Пауза"
        const val PLAY = "Пуск"
        /** The label of the play line of the orbit, the accessible name of its speed chip, and its speeds in the order of the chip. */
        const val MOTION = "Движение"
        val ORBIT_SPEEDS = listOf("Медленно", "Обычно", "Быстро")
        /** The count of a stepper, such as "3 из 7" (or "3 of 7" in the English strings): its accessible state on the stage (Stage A, AH4). */
        val COUNT = Regex("""\d+ (из|of) 7""")
    }
}

/**
 * The scripted provider of a talk with motion and diagrams (block 2 plan, stage 7). Each template call answers from [templates]. The early
 * call beside the routing call goes on for a screen request and waits for its cancel otherwise. The routing call gives a screen for
 * [MOTION_REQUEST] and [HEART_REQUEST], and a press for each spoken command, as the routing model does from SHOWN_SCREEN. [routes] holds each
 * routing call, and [calls] each template call that answered.
 */
internal class MotionTalk(private val templates: AgentScriptProvider) : ChatProvider {
    val calls = CopyOnWriteArrayList<CloudRequest>()
    val routes = CopyOnWriteArrayList<CloudRequest>()
    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        if (request.follow) return emit(CloudEvent.Follow("none", "", ""))
        if (request.generationStage != null) {
            val last = request.turns.last().text
            if (EARLY_BRIEF in last && SCREENS.none { it in last }) awaitCancellation()
            calls += request
            return templates.respond(key, request, session, emit)
        }
        routes += request
        emit(CloudEvent.RoundComplete(when (request.decisionContext?.originalUserTurn?.text) {
            MOTION_REQUEST, HEART_REQUEST -> JSONObject(chatDecision("screen", "Смотри на экран.", "How it moves or what parts it has")).put("show", true).toString()
            FASTER -> chatDecision("press", "Быстрее.", "Движение: Быстро")
            STOP -> chatDecision("press", "Стоп.", "Пауза")
            NEXT -> chatDecision("press", "Дальше.", "Пуск")
            THIRD -> chatDecision("press", "Правый желудочек.", "Часть: правый желудочек")
            // As the routing model does: the speed before the present one in SHOWN_SCREEN, or an answer with no press at the slow speed.
            SLOWER -> JSONObject(checkNotNull(request.shownScreen).removePrefix(ShownScreen.PREFIX)).getJSONObject("motion").getString("speed")
                .let { speed -> SPEEDS.indexOf(speed).takeIf { it > 0 }?.let { chatDecision("press", "Медленнее.", "Движение: " + SPEEDS[it - 1]) }
                    ?: chatDecision("answer", SLOWEST) }
            else -> chatDecision("answer", "Fixture response")
        }, false))
    }

    companion object {
        const val MOTION_REQUEST = "Покажи, как Земля вращается вокруг Солнца"
        const val HEART_REQUEST = "Покажи, как устроено сердце"
        const val FASTER = "Быстрее"
        const val STOP = "Стоп"
        const val NEXT = "Дальше"
        const val THIRD = "Покажи третий шаг"
        const val SLOWER = "Медленнее"
        const val SLOWEST = "Это самая медленная скорость."
        private val SPEEDS = listOf("Медленно", "Обычно", "Быстро")
        /** The early call starts before a brief exists, so its prompt has an empty brief line. */
        private const val EARLY_BRIEF = "\nWidget brief: \n"
        private val SCREENS = listOf(MOTION_REQUEST, HEART_REQUEST)
    }
}
