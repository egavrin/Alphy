package com.alpha.assistant.ui

import android.graphics.Rect
import android.os.SystemClock
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
            robot.waitUntil { robot.composedTextIn(scenario, STAGE, SHIP_TITLE) && canvasLabels(scenario) == SHIP_LABELS }
            assertTrue("the preview shows its control", robot.composedIn(scenario, STAGE) { SemanticsActions.OnClick in it })
            assertFalse("the control of the preview is off", controlOn(scenario))
            assertTrue("the strip keeps its working status", robot.composedTextIn(scenario, STRIP, robot.text(R.string.live_voice_working)))
            ToolStore(context, "${stage.fixture.name}.db-tools.db").use { store ->
                assertEquals("store rows and draft rows", 0 to 0, store.tools().size to store.drafts().size)
            }
        }
    }

    /** A frame of another operation takes the place of the stored app on the stage. */
    @Test fun aLaterNewScreenReplacesTheShownApp() {
        val frame = frame(SHIP)
        stage.onStage(appFilePlan("calc")) { scenario, model ->
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, CALC_LABEL) }
            show(model, frame)
            robot.waitUntil { robot.composedTextIn(scenario, STAGE, SHIP_TITLE) && !robot.composedTextIn(scenario, STAGE, CALC_LABEL) }
            assertFalse("the control of the preview is off", controlOn(scenario))
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
            show(model, frame)
            robot.waitUntil { canvasLabels(scenario) == SHIP_LABELS }
            val preview = layout(scenario)
            stage.socket.say(APP_REQUEST, "d1", 100)
            val seen = linkedSetOf<String?>()
            // The uncommitted preview keeps the stage while the app is stored.
            robot.waitUntil(60_000) { seen += canvasLabels(scenario); model.stageToolId != null && !model.tools.busy }
            seen += commitAndWatch(scenario, model, stored(model, checkNotNull(model.stageToolId)))
            assertEquals("the canvas from the preview to the live drawing", setOf(SHIP_LABELS), seen)
            val live = layout(scenario)
            val px = ceil(context.resources.displayMetrics.density).toInt()
            fun near(a: Rect, b: Rect) = abs(a.left - b.left) <= px && abs(a.top - b.top) <= px && abs(a.right - b.right) <= px && abs(a.bottom - b.bottom) <= px
            assertTrue("the live app $live moved from the last preview $preview by more than 1 dp",
                near(preview.title, live.title) && near(preview.control, live.control) && near(preview.canvas, live.canvas))
        }
    }

    /** A stage tool with the live drawing A, a change preview with the drawing B, then the commit: the canvas shows B until the new live drawing, never A. */
    @Test fun aChangeOfTheSameToolSeedsTheLastPreviewDrawing() {
        val changed = frame(SHIP_CHANGED)
        val provider = Screens(SHIP)
        live(provider) { scenario, model ->
            stage.socket.say(APP_REQUEST, "d1", 100)
            robot.waitUntil(60_000) { model.stageToolId != null && !model.tools.busy && canvasLabels(scenario) == SHIP_LABELS }
            val before = stored(model, checkNotNull(model.stageToolId))
            stage.socket.say(CHANGE_REQUEST, "d2", 2_000)
            robot.waitUntil(30_000) { provider.changeCalled.isCompleted }
            show(model, changed, before.id)
            robot.waitUntil { canvasLabels(scenario) == CHANGED_LABELS }
            val seen = linkedSetOf<String?>()
            provider.changeGo.complete(Unit)
            // The uncommitted change preview keeps the stage while the change is stored.
            robot.waitUntil(60_000) { seen += canvasLabels(scenario); stored(model, before.id).revision == before.revision + 1 && !model.tools.busy }
            seen += commitAndWatch(scenario, model, stored(model, before.id))
            assertEquals("the canvas from the change preview to the new live drawing", setOf(CHANGED_LABELS), seen)
        }
    }

    /** The trace has controls_on after the accept, busy has ended at that mark, and the control of the stage is on from then, never before. */
    @Test fun controlsOnComesAfterBusyEnds() {
        live(Screens(SHIP)) { scenario, model ->
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
                if (controlOn(scenario) && marked() < 0) early += phases.toList()
                marked() >= 0
            }
            robot.waitUntil { controlOn(scenario) }
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
    private fun commitAndWatch(scenario: ActivityScenario<CloudTestActivity>, model: ShellViewModel, tool: ToolRecord): Set<String?> {
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
            during(1_500) { seen += canvasLabels(scenario) }
        } finally { holder?.close() }
        // The first live snapshot of the committed revision ends the preview.
        robot.waitUntil(30_000) { seen += canvasLabels(scenario); model.tools.stagePreviews.current == null }
        seen += canvasLabels(scenario)
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

    /** Runs [check] every 30 ms for [ms]. */
    private fun during(ms: Long, check: () -> Unit) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { check(); SystemClock.sleep(30) }
    }

    private fun isCharacter(config: SemanticsConfiguration) = config.getOrNull(SemanticsProperties.TestTag) == CHARACTER

    private fun isShipCanvas(config: SemanticsConfiguration) =
        config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(SHIP_TITLE) } == true

    /**
     * The texts of the ship drawing on the stage after its label, or null when the stage shows no such canvas. The preview canvas has
     * "label. A. B." or the label alone, and the live canvas has "label. A. B. ", so both give "A. B" here.
     */
    private fun canvasLabels(scenario: ActivityScenario<CloudTestActivity>): String? {
        var labels: String? = null
        robot.composedIn(scenario, STAGE) { config ->
            labels = config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it.startsWith(SHIP_TITLE) }
                ?.removePrefix(SHIP_TITLE)?.trim('.', ' ')
            labels != null
        }
        return labels
    }

    /** True when the stage shows a control that a tap can press. The stage of these tests has no other tap target. */
    private fun controlOn(scenario: ActivityScenario<CloudTestActivity>) =
        robot.composedIn(scenario, STAGE) { SemanticsActions.OnClick in it && SemanticsProperties.Disabled !in it }

    private data class Layout(val title: Rect, val control: Rect, val canvas: Rect)

    private fun layout(scenario: ActivityScenario<CloudTestActivity>) = Layout(
        checkNotNull(robot.composedBoundsIn(scenario, STAGE, robot.hasText(SHIP_TITLE))) { "the title" },
        checkNotNull(robot.composedBoundsIn(scenario, STAGE, robot.hasText(OPTION))) { "the control" },
        checkNotNull(robot.composedBoundsIn(scenario, STAGE, ::isShipCanvas)) { "the canvas" })

    /**
     * The routing call creates an app for [APP_REQUEST] and changes the shown app for [CHANGE_REQUEST]. With [holdRouting] it waits with no
     * end after it completes [routed]. Each generation call answers [plan]. After the routing of [CHANGE_REQUEST], it completes
     * [changeCalled] and answers [CHANGE_LINE] when [changeGo] completes.
     */
    private class Screens(private val plan: String, private val holdRouting: Boolean = false) : ChatProvider {
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
                APP_REQUEST -> chatDecision("create", "Готово.", "Make the app")
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
    }
}
