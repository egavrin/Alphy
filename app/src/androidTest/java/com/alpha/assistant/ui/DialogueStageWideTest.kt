package com.alpha.assistant.ui

import android.graphics.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Plan D, Da2: the stage puts the main visual with its controls at the left and the cards at the right at the fold and the tablet size. */
class DialogueStageWideTest {
    private val stage = StageShell("dialogue-stage-wide")
    private val robot get() = stage.robot
    @After fun remove() = stage.remove()

    @Test fun foldStagePutsTheCardsRight() {
        assumeTrue("a fold or a tablet", stage.smallestWidth >= 600)
        stage.onStage(appFilePlan("lesson")) { scenario, _ ->
            val orbit = lessonDrawn(scenario)
            listOf(LESSON_STEP, QUESTION).forEach { card ->
                val bounds = checkNotNull(stage.boundsIn(scenario, STAGE, StageShell.text(card))) { card }
                assertTrue("$card $bounds is right of the orbit $orbit", bounds.left > orbit.right)
            }
            assertTitleUnderTheLabel(scenario)
        }
    }

    @Test fun phoneStageStaysOneColumn() {
        assumeTrue("a phone", stage.smallestWidth < 600)
        stage.onStage(appFilePlan("lesson")) { scenario, _ ->
            lessonDrawn(scenario)
            val lap = checkNotNull(stage.boundsIn(scenario, STAGE, StageShell.text(LAP)))
            val step = checkNotNull(stage.boundsIn(scenario, STAGE, StageShell.text(LESSON_STEP)))
            assertTrue("the lesson card $step is under «Один круг» $lap", step.top >= lap.bottom)
            assertTitleUnderTheLabel(scenario)
        }
    }

    /** Ruling 1 of the controller: with no scroll, the orbit and its first control (the field under it) are fully above the strip. */
    @Test fun theOrbitAndItsFieldFitAboveTheStrip() {
        stage.onStage(appFilePlan("lesson")) { scenario, _ ->
            val orbit = lessonDrawn(scenario)
            val field = checkNotNull(stage.boundsIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.EditableText) != null })
            val top = robot.composedBounds(scenario, STAGE).first.top
            val strip = robot.composedBounds(scenario, STRIP).first.top
            assertTrue("the orbit $orbit is under the top of the stage $top", orbit.top >= top)
            assertTrue("the orbit $orbit and the field $field are above the strip $strip", orbit.bottom <= strip && field.bottom <= strip)
        }
    }

    /** Waits for the first frame of the orbit drawing on the stage, and gives its bounds. */
    private fun lessonDrawn(scenario: ActivityScenario<CloudTestActivity>): Rect {
        robot.waitUntil(30_000) { stage.boundsIn(scenario, STAGE, ::isOrbit) != null && stage.boundsIn(scenario, STAGE, StageShell.text(QUESTION)) != null }
        return checkNotNull(stage.boundsIn(scenario, STAGE, ::isOrbit))
    }

    private fun assertTitleUnderTheLabel(scenario: ActivityScenario<CloudTestActivity>) {
        val label = checkNotNull(stage.boundsIn(scenario, STAGE, StageShell.text(KIND)))
        val title = checkNotNull(stage.boundsIn(scenario, STAGE, StageShell.text(TITLE)))
        assertTrue("the title $title is under the kind label $label", title.top >= label.bottom)
    }

    private fun isOrbit(config: SemanticsConfiguration) =
        config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("$ORBIT.") } == true

    private companion object {
        const val STAGE = "dialogue_stage"
        const val STRIP = "dialogue_strip"
        const val KIND = "Alphy Learn"
        const val TITLE = "Почему Луна не отворачивается?"
        const val ORBIT = "Земля в центре, Луна на орбите и метка её стороны"
        const val LAP = "Один круг"
        /** The title of the first step of the lesson card. */
        const val LESSON_STEP = "Следи за бирюзовой меткой"
        const val QUESTION = "Если Луна не вращается вокруг оси, мы видим только одну её сторону?"
    }
}

/**
 * The real shell in the voice mode with a scripted GPT-Live socket and audio, for the tests of the dialogue stage. With the plan step on, the
 * hand-off of [APP_REQUEST] puts the app of a plan on the stage, and each other request gets the answer «Fixture response».
 */
internal class StageShell(prefix: String) {
    val fixture = ShellFixture(prefix)
    val robot = ShellRobot(prefix)
    val socket = FakeLiveTransport()
    val audio = FakeLiveAudio()
    val smallestWidth: Int get() = fixture.context.resources.configuration.smallestScreenWidthDp

    fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
        fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
    }

    /** The plan call answers with [plan]. The routing call creates an app for [APP_REQUEST] only. */
    fun provider(plan: String): ChatProvider {
        val script = AgentScriptProvider(listOf(Scripted.Text(plan)))
        return object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) = when {
                request.generationStage != null -> script.respond(key, request, session, emit)
                request.decisionContext?.originalUserTurn?.text == APP_REQUEST ->
                    emit(CloudEvent.RoundComplete(chatDecision("create", "Готово.", "Make the app"), false))
                else -> emit(CloudEvent.RoundComplete(chatDecision("answer", "Fixture response"), false))
            }
        }
    }

    /** The voice mode with the app of [plan] on the stage. */
    fun onStage(plan: String, test: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) =
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            fixture.install(chat = provider(plan), liveVoice = fixture.liveVoice(socket, audio))
            live { scenario, model ->
                socket.say(APP_REQUEST, "d1", 100)
                robot.waitUntil(60_000) { model.stageToolId != null }
                test(scenario, model)
            }
        }

    /** Launches the installed shell, starts the voice mode, and answers `session.started`. */
    fun live(test: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) = fixture.launch().use { scenario ->
        val model = checkNotNull(fixture.model)
        robot.waitUntil { !model.loading }
        scenario.onActivity { model.startLiveVoice() }
        robot.waitUntil { socket.connected }
        socket.emit(LIVE_STARTED)
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
        test(scenario, model)
    }

    /**
     * The window bounds of the first composed node inside the node with [tag] that matches, or null. The bounds come from the position
     * and the size of the node, so a scroll container or the strip does not clip them.
     */
    fun boundsIn(scenario: ActivityScenario<*>, tag: String, match: (SemanticsConfiguration) -> Boolean): Rect? {
        var result: Rect? = null
        scenario.onActivity { activity ->
            fun root(view: android.view.View): ViewRootForTest? = view as? ViewRootForTest
                ?: (view as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
            fun find(node: SemanticsNode): SemanticsNode? = node.takeIf { match(it.config) } ?: node.children.firstNotNullOfOrNull(::find)
            val node = checkNotNull(root(activity.window.decorView)).semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                .filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.firstNotNullOfOrNull(::find) ?: return@onActivity
            val at = node.positionInWindow
            result = Rect(at.x.roundToInt(), at.y.roundToInt(), (at.x + node.size.width).roundToInt(), (at.y + node.size.height).roundToInt())
        }
        return result
    }

    companion object {
        const val APP_REQUEST = "Сделай калькулятор чаевых"
        /** A node whose text is [value]. */
        fun text(value: String): (SemanticsConfiguration) -> Boolean =
            { config -> config.getOrNull(SemanticsProperties.Text)?.any { it.text.trimEnd() == value } == true }
    }
}
