package com.alpha.assistant.ui

import android.graphics.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.GraphicsSession
import com.alpha.assistant.tools.StagePreviewFrame
import com.alpha.assistant.tools.ToolGenerationTraceEvent
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.agent.AppFileNames
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.kitPreview
import com.alpha.assistant.tools.withPlanStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Task 16: the stage draws the preview of an app that is not stored, from memory, with the control off. The live app then takes its place
 * with the bounds and the drawing of the last preview, so no empty frame and no move shows. Each test calls ToolController.stagePreviews
 * directly, in the place of the host half of the task. The controller runs the class at the phone and the fold size.
 */
class StagePreviewWorkflowTest {
    private val stage = StageShell("stage-preview")
    private val robot get() = stage.robot
    private val context get() = stage.fixture.context
    private val operation = UUID.randomUUID().toString()
    @After fun remove() = stage.remove()

    /** The preview shows the title and the drawing with the control off while the hand-off works, and it writes no store row and no draft row. */
    @Test fun aPreviewDrawsOnTheStageWithTheControlOff() {
        val frame = frame(SHIP)
        val provider = Screens(SHIP, holdRouting = true)
        live(provider) { scenario, model ->
            stage.socket.say(APP_REQUEST, "d1", 100)
            robot.waitUntil(30_000) { provider.routed.isCompleted && robot.composedTextIn(scenario, STRIP, robot.text(R.string.live_voice_working)) }
            show(model, frame)
            // The whole preview, with its control, so the check of the control below is not empty.
            robot.waitUntil { robot.composedTextIn(scenario, STAGE, SHIP_TITLE) && canvasLabels() == SHIP_LABELS &&
                robot.composedIn(scenario, STAGE) { SemanticsActions.OnClick in it } }
            assertFalse("the control of the preview is off", controlOn())
            assertTrue("the strip keeps its working status", robot.composedTextIn(scenario, STRIP, robot.text(R.string.live_voice_working)))
            ToolStore(context, "${stage.fixture.name}.db-tools.db").use { store ->
                assertEquals("store rows and draft rows", 0 to 0, store.tools().size to store.drafts().size)
            }
        }
    }

    /**
     * Plan "one scene", decision 22: a slider of a preview on the stage shows no input note of the host and no «Применить», also after a
     * drag. In the study they showed for about 3 s, and the screen jumped when the live app came (lane C, other findings, item 1).
     */
    @Test fun aSliderPreviewOnTheStageHasNoWarning() {
        val frame = frame(SLIDER)
        live(Screens(SLIDER)) { scenario, model ->
            show(model, frame)
            robot.waitUntil { robot.composedTextIn(scenario, STAGE, SLIDER_TITLE) && robot.composedIn(scenario, STAGE, ::isSlider) }
            val slider = checkNotNull(robot.composedBoundsIn(scenario, STAGE, ::isSlider))
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val y = origin[1] + slider.centerY()
            robot.shell("input swipe ${origin[0] + slider.left + slider.width() / 4} $y ${origin[0] + slider.left + slider.width() * 3 / 4} $y 350")
            during(1_000) { assertEquals("live input texts on the stage", emptyList<String>(), liveInputTexts()) }
        }
    }

    /** A frame of another operation takes the place of the stored app on the stage. */
    @Test fun aLaterNewScreenReplacesTheShownApp() {
        val frame = frame(SHIP)
        stage.onStage(appFilePlan("calc")) { scenario, model ->
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, CALC_LABEL) }
            show(model, frame)
            robot.waitUntil { robot.composedTextIn(scenario, STAGE, SHIP_TITLE) && !robot.composedTextIn(scenario, STAGE, CALC_LABEL) }
            assertFalse("the control of the preview is off", controlOn())
        }
    }

    /** gone removes the preview, and a later frame of the same operation never shows. */
    @Test fun goneRemovesThePreviewAndDropsLateFrames() {
        val frame = frame(SHIP)
        live(Screens(SHIP)) { scenario, model ->
            show(model, frame)
            robot.waitUntil { robot.composedTextIn(scenario, STAGE, SHIP_TITLE) }
            main { model.tools.stagePreviews.gone(operation) }
            robot.waitUntil { !robot.composedTextIn(scenario, STAGE, SHIP_TITLE) && robot.composedIn(scenario, STAGE, ::isCharacter) }
            show(model, frame)
            during(1_000) { assertFalse("a late frame showed after gone", robot.composedTextIn(scenario, STAGE, SHIP_TITLE)) }
            assertNull(model.tools.stagePreviews.current)
        }
    }

    /**
     * The preview, then the store of the same file and the commit. Until the live drawing, the canvas shows the texts of the preview. The
     * title, the control, and the canvas of the live app have the bounds of the last preview within 1 dp.
     */
    @Test fun theLiveAppTakesTheBoundsAndTheDrawingOfTheLastPreview() {
        val frame = frame(SHIP)
        live(Screens(SHIP)) { scenario, model ->
            // This test drives the previews by hand, so the run streams none of its own.
            main { model.tools.previewStream = false }
            show(model, frame)
            robot.waitUntil { canvasLabels() == SHIP_LABELS }
            val preview = layout(scenario)
            stage.socket.say(APP_REQUEST, "d1", 100)
            val seen = linkedSetOf<String?>()
            // The uncommitted preview keeps the stage while the app is stored.
            robot.waitUntil(60_000) { seen += canvasLabels(); model.stageToolId != null && !model.tools.busy }
            seen += commitAndWatch(model, stored(model, checkNotNull(model.stageToolId)))
            assertEquals("the canvas from the preview to the live drawing", setOf(SHIP_LABELS), seen)
            val live = layout(scenario)
            val px = ceil(context.resources.displayMetrics.density).toInt()
            fun near(a: Rect, b: Rect) = abs(a.left - b.left) <= px && abs(a.top - b.top) <= px && abs(a.right - b.right) <= px && abs(a.bottom - b.bottom) <= px
            assertTrue("the live app $live moved from the last preview $preview by more than 1 dp",
                near(preview.title, live.title) && near(preview.control, live.control) && near(preview.canvas, live.canvas))
        }
    }

    /**
     * A stage tool with the live drawing A, a change preview with the drawing B, then the commit: the stored drawing A stays while the change
     * call waits (plan "one scene", decision 22), and from the store the canvas shows B until the new live drawing, never A.
     */
    @Test fun aChangeOfTheSameToolSeedsTheLastPreviewDrawing() {
        val changed = frame(SHIP_CHANGED)
        val provider = Screens(SHIP)
        live(provider) { _, model ->
            // This test drives the previews by hand, so the run streams none of its own.
            main { model.tools.previewStream = false }
            stage.socket.say(APP_REQUEST, "d1", 100)
            robot.waitUntil(60_000) { model.stageToolId != null && !model.tools.busy && canvasLabels() == SHIP_LABELS }
            val before = stored(model, checkNotNull(model.stageToolId))
            stage.socket.say(CHANGE_REQUEST, "d2", 2_000)
            robot.waitUntil(30_000) { provider.changeCalled.isCompleted }
            show(model, changed, before.id)
            during(1_000) { assertEquals("the canvas while the change call waits", SHIP_LABELS, canvasLabels()) }
            val seen = linkedSetOf<String?>()
            provider.changeGo.complete(Unit)
            // The revision is read before the canvas, so each kept read comes after the store.
            robot.waitUntil(60_000) {
                val stored = stored(model, before.id).revision == before.revision + 1
                canvasLabels().let { if (stored) seen += it }
                stored && !model.tools.busy
            }
            seen += commitAndWatch(model, stored(model, before.id))
            assertEquals("the canvas from the store to the new live drawing", setOf(CHANGED_LABELS), seen)
        }
    }

    /** The stored app stays on the stage while the change runs, so the value that the person moved stays in view and reaches the changed app. */
    @Test fun aChangePreviewKeepsTheValueThatThePersonMoved() {
        val changed = frame(SHIP_CHANGED)
        val provider = Screens(SHIP)
        live(provider) { _, model ->
            stage.socket.say(APP_REQUEST, "d1", 100)
            robot.waitUntil(60_000) { model.stageToolId != null && !model.tools.busy && canvasLabels() == SHIP_LABELS }
            val before = stored(model, checkNotNull(model.stageToolId))
            robot.click(OPTION)
            // s3 shows for the ship only, so the canvas has no text after the press.
            robot.waitUntil { canvasLabels() == "" }
            stage.socket.say(CHANGE_REQUEST, "d2", 2_000)
            robot.waitUntil(30_000) { provider.changeCalled.isCompleted }
            show(model, changed, before.id)
            during(500) { assertEquals("the canvas while the change call waits", "", canvasLabels()) }
            provider.changeGo.complete(Unit)
            robot.waitUntil(60_000) { stored(model, before.id).revision == before.revision + 1 && !model.tools.busy }
            // The state of the ship holds the value of its control only, under a generated key.
            val state = JSONObject(stored(model, before.id).state)
            assertEquals("the value after the change", listOf(OPTION), state.keys().asSequence().map(state::get).toList())
        }
    }

    /** The trace has controls_on after the accept, busy has ended at that mark, and the control of the stage is on from then, never before. */
    @Test fun controlsOnComesAfterBusyEnds() {
        // controls_on marks a screen run only, so the routing gives a screen.
        live(Screens(SHIP, screen = true)) { _, model ->
            val phases = CopyOnWriteArrayList<Pair<String, String?>>()
            val busyAtMark = AtomicReference<Boolean?>(null)
            main {
                model.tools.generationTraceObserver = { event ->
                    (event as? ToolGenerationTraceEvent.Phase)?.let { phase ->
                        if (phase.name == CONTROLS_ON) busyAtMark.set(model.tools.busy)
                        phases += phase.name to phase.detail
                    }
                }
            }
            fun marked() = phases.indexOfFirst { it.first == CONTROLS_ON }
            stage.socket.say(APP_REQUEST, "d1", 100)
            val early = mutableListOf<List<Pair<String, String?>>>()
            robot.waitUntil(60_000) {
                // A read of the stage runs on the main thread, so it comes after the whole main-thread pass that ends busy and marks controls_on.
                if (controlOn() && marked() < 0) early += phases.toList()
                marked() >= 0
            }
            robot.waitUntil { controlOn() }
            val accepted = phases.indexOf(AppFileNames.PLAN_OUTCOME to "accepted")
            assertTrue("controls_on after the accept: $phases", accepted >= 0 && accepted < marked())
            assertEquals("busy at the mark controls_on", false, busyAtMark.get())
            assertTrue("the control was on before controls_on: $early", early.isEmpty())
        }
    }

    /**
     * Commits the preview as [tool] while a second session holds the drawing slot, so the stage shows the seed for a while, then frees the
     * slot. Gives each canvas text that the stage showed from the commit to the first live drawing, and null for a frame with no canvas.
     */
    private fun commitAndWatch(model: ShellViewModel, tool: ToolRecord): Set<String?> {
        val seen = linkedSetOf<String?>()
        var holder: GraphicsSession? = null
        robot.waitUntil {
            val session = GraphicsSession(context)
            val held = runBlocking { session.open("return {state:input.state};", "{}") }.status == "OK"
            if (held) holder = session else session.close()
            held
        }
        try {
            main { model.tools.stagePreviews.committed(operation, tool.id, tool.revision) }
            during(1_500) { seen += canvasLabels() }
        } finally { holder?.close() }
        // The first live snapshot of the committed revision ends the preview.
        robot.waitUntil(30_000) { seen += canvasLabels(); model.tools.stagePreviews.current == null }
        seen += canvasLabels()
        return seen
    }

    /** Installs [provider] with the plan step on and runs [test] in the voice mode. */
    private fun live(provider: ChatProvider, test: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) =
        withPlanStep(context, AiProvider.OPENAI, true) {
            stage.fixture.install(chat = provider, liveVoice = stage.fixture.liveVoice(stage.socket, stage.audio))
            stage.live(test)
        }

    /** The frame of [text] from the kit preview step, made before the shell runs, so no other work holds the transient slot. */
    private fun frame(text: String): StagePreviewFrame = checkNotNull(runBlocking { kitPreview(context, text) }) { "previewStep gave no frame" }

    private fun main(block: () -> Unit) = robot.instrumentation.runOnMainSync(block)

    private fun show(model: ShellViewModel, frame: StagePreviewFrame, toolId: String? = null) =
        main { model.tools.stagePreviews.show(operation, frame, toolId) }

    private fun stored(model: ShellViewModel, id: String) = model.tools.tools.first { it.id == id }

    private fun isCharacter(config: SemanticsConfiguration) = config.getOrNull(SemanticsProperties.TestTag) == CHARACTER

    private fun isSlider(config: SemanticsConfiguration) = SemanticsProperties.ProgressBarRangeInfo in config

    /** Each text on the stage that is an input note of the host (tool_live_input_*) or the «Применить» button of the slider. */
    private fun liveInputTexts(): List<String> {
        val notes = listOf(R.string.tool_live_input_pending, R.string.tool_live_input_unsaved, R.string.tool_live_input_unavailable,
            R.string.tool_live_input_finish_first).map { robot.text(it) } +
            listOf(R.string.tool_live_input_finish, R.string.tool_generated_apply).map { robot.text(it, SLIDER_LABEL) }
        return readStage(robot) { nodes -> nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } } }
            .filter { it.trimEnd() in notes }
    }

    private fun isShipCanvas(config: SemanticsConfiguration) =
        config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(SHIP_TITLE) } == true

    /**
     * The texts of the ship drawing on the stage after its label as "A. B", "" for the label alone, or null when the stage shows no such
     * canvas. It reads with no wait for an idle main thread (readStage).
     */
    private fun canvasLabels() = shipCanvasTexts(robot)?.joinToString(". ")

    /** True when the stage shows a control that a tap can press. The stage of these tests has no other tap target. */
    private fun controlOn() = stageControlOn(robot)

    private data class Layout(val title: Rect, val control: Rect, val canvas: Rect)

    private fun layout(scenario: ActivityScenario<CloudTestActivity>) = Layout(
        checkNotNull(robot.composedBoundsIn(scenario, STAGE, robot.hasText(SHIP_TITLE))) { "the title" },
        checkNotNull(robot.composedBoundsIn(scenario, STAGE, robot.hasText(OPTION))) { "the control" },
        checkNotNull(robot.composedBoundsIn(scenario, STAGE, ::isShipCanvas)) { "the canvas" })

    /**
     * The routing call creates an app for [APP_REQUEST], or gives a screen with [screen], and changes the shown app for [CHANGE_REQUEST]. With
     * [holdRouting] it waits with no end after it completes [routed]. Each generation call answers [plan]. After the routing of
     * [CHANGE_REQUEST], it completes [changeCalled] and answers [CHANGE_LINE] when [changeGo] completes.
     */
    private class Screens(private val plan: String, private val holdRouting: Boolean = false, private val screen: Boolean = false) : ChatProvider {
        val routed = CompletableDeferred<Unit>()
        val changeCalled = CompletableDeferred<Unit>()
        val changeGo = CompletableDeferred<Unit>()
        @Volatile private var changing = false
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            if (request.generationStage != null) {
                val reply = if (!changing) plan else { changeCalled.complete(Unit); changeGo.await(); CHANGE_LINE }
                emit(CloudEvent.Text(reply))
                return emit(CloudEvent.RoundComplete(reply, false))
            }
            routed.complete(Unit)
            if (holdRouting) awaitCancellation()
            val turn = request.decisionContext?.originalUserTurn?.text
            if (turn == CHANGE_REQUEST) changing = true
            emit(CloudEvent.RoundComplete(when (turn) {
                APP_REQUEST -> if (screen) JSONObject(chatDecision("screen", "Готово.", "Make the app")).put("show", true).toString()
                    else chatDecision("create", "Готово.", "Make the app")
                CHANGE_REQUEST -> chatDecision("refine", "Готово.", "Change the shown app")
                else -> chatDecision("answer", "Fixture response")
            }, false))
        }
    }

    private companion object {
        const val STAGE = "dialogue_stage"
        const val STRIP = "dialogue_strip"
        const val CHARACTER = "live_voice_character"
        const val APP_REQUEST = "Покажи, почему корабль не тонет"
        const val CHANGE_REQUEST = "Поменяй подпись"
        /** The trace phase after busy ends for an accepted screen run (Task 16, host half). */
        const val CONTROLS_ON = "controls_on"
        /** A figure label of the calc app. */
        const val CALC_LABEL = "Per person"
        /** The example file of design section 1 (Global Constraints). */
        val SHIP: String by lazy { appFilePlan("template/ship") }
        const val SHIP_TITLE = "Почему корабль не тонет?"
        /** The second option of the ship control. */
        const val OPTION = "Гвоздь"
        /** The text of s3, which shows at the start value of the control. */
        const val SHIP_LABELS = "Воздух внутри"
        const val CHANGED_LABELS = "Пустота внутри"
        /** A change of s3 only. */
        const val CHANGE_LINE = "s3 = text(150, 50, \"Пустота внутри\", \"ink\", 14, visible=\$/v == \"Корабль\")"
        val SHIP_CHANGED: String by lazy { SHIP.lines().joinToString("\n") { if (it.startsWith("s3 =")) CHANGE_LINE else it } }
        const val SLIDER_TITLE = "Что такое большинство?"
        const val SLIDER_LABEL = "Голоса за"
        /** The cells template of `template/majority` in Russian: a live slider, as the template of the study had. */
        const val SLIDER = "cells(\"$SLIDER_TITLE\", lang=\"ru\", palette=\"teal\")\n" +
            "control = slider(\"$SLIDER_LABEL\", 0, 20, 1, 11)\n" +
            "card = card(\"Больше половины\", \"Большинство — больше половины голосов: 11 из 20 — большинство, а 10 из 20 — только половина.\")"
    }
}
