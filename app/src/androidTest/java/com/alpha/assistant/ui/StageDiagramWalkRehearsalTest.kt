package com.alpha.assistant.ui

import android.os.SystemClock
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import com.alpha.assistant.tools.SceneStep
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.voice.LiveProtocol.MARK
import com.alpha.assistant.voice.inputDelta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk of a diagram and of a scene with steps and motion on the voice stage (stage 7, fix list 1, F5). The real shell walks the kit
 * fixtures `heart` and `m7` with a fake GPT-Live socket: each sentence that the voice says moves the walk to the next part or step.
 */
class StageDiagramWalkRehearsalTest {
    private val rig = LiveStageRig("stage-diagram-walk")
    private val robot = rig.robot
    private val socket = rig.socket
    private val shell = rig.shell
    private lateinit var talk: StreamTalk
    @After fun remove() = rig.remove()

    /** G2: the walk of the heart gives the sentence of each part in order, and the stage marks that part when the voice says its sentence. */
    @Test fun aDiagramWalkSaysEachPartSentenceAndMarksThatPart() = walk("heart") { model, scene ->
        HEART_SAYS.forEachIndexed { index, say ->
            robot.waitUntil(10_000) { rig.thinkings().size == index + 1 }
            assertEquals(MARK + say, rig.thinkings()[index])
            rig.speak(say, marker = index + 1, atMs = 2_000L + index * 1_000)
            waitStep(model, scene, index + 1) { it == SceneStep(index + 1, HEART_PARTS.size, HEART_PARTS[index]) }
        }
        assertEquals(HEART_SAYS.map { MARK + it }, rig.thinkings())
    }

    /** M7: in one walk of a scene with steps and motion, each step moves the stage, and the picture count grows in each step. */
    @Test fun aWalkOfStepsWithMotionDrawsNewPicturesInEachStep() = walk("m7") { model, scene ->
        M7_SAYS.forEachIndexed { index, say ->
            robot.waitUntil(10_000) { rig.thinkings().size == index + 1 }
            assertEquals(MARK + say, rig.thinkings()[index])
            rig.speak(say, marker = index + 1, atMs = 2_000L + index * 1_000)
            waitStep(model, scene, index + 1) { it?.index == index + 1 }
            val before = pictures()
            SystemClock.sleep(700)
            val grown = pictures() - before
            assertTrue("step ${index + 1} drew $grown new pictures in 700 ms", grown >= 10)
        }
        assertEquals(M7_SAYS.map { MARK + it }, rig.thinkings())
    }

    /** The follow call on a diagram: a question about a part and a free answer of the voice end in a follow press that marks that part. */
    @Test fun aFollowPressOnADiagramMarksThePart() = walk("heart") { model, scene ->
        robot.waitUntil(10_000) { step(model, scene)?.index == 1 }
        talk.follow = CloudEvent.Follow("press", "", "Часть: аорта")
        socket.emit(inputDelta("А что делает аорта?", 3_000, 3_500))
        rig.speak("Аорта несёт кровь ко всему телу.", 9, 3_700)
        waitStep(model, scene, 7) { it == SceneStep(7, 7, "аорта") }
        assertTrue(talk.follows.isNotEmpty())
    }

    /** Waits for step [k], and on a timeout names the step, the shown step, the busy state, and each lost press with its cause (fix list 2, H1). */
    private fun waitStep(model: ShellViewModel, scene: ToolRecord, k: Int, shown: (SceneStep?) -> Boolean) {
        runCatching { robot.waitUntil(10_000) { shown(step(model, scene)) } }.onFailure {
            val presses = robot.shell("logcat -d -s AlphyPress").lines().drop(pressLines).filter { "AlphyPress" in it }
            throw AssertionError("step $k was lost: shown ${step(model, scene)}, busy ${model.tools.busy}, lost presses $presses", it)
        }
    }

    private val pressLines = robot.shell("logcat -d -s AlphyPress").lines().size

    private fun pictures() = SurfaceFrameMetrics.snapshot()?.pictures ?: 0L

    private fun step(model: ShellViewModel, scene: ToolRecord) = ShownScreen.of(shell.stored(model, scene.id))?.step

    /** Shows the kit fixture [name] for the spoken screen request, and runs [test] with the stored scene. [talk] answers each call. */
    private fun walk(name: String, test: (ShellViewModel, ToolRecord) -> Unit) {
        talk = StreamTalk(listOf(Streamed(halves(appFilePlan("template/$name")), gapMs = 30)))
        rig.live(talk) { model, _, _ -> test(model, rig.steppedScene(model)) }
    }

    private companion object {
        val HEART_PARTS = listOf("полая вена", "правое предсердие", "правый желудочек", "лёгочная артерия", "левое предсердие", "левый желудочек", "аорта")
        val HEART_SAYS = listOf("Приносит в сердце кровь от тела.", "Принимает кровь от тела.", "Толкает кровь в лёгкие.", "Несёт кровь в лёгкие.",
            "Принимает кровь из лёгких.", "Толкает кровь ко всему телу.", "Несёт кровь ко всему телу.")
        val M7_SAYS = listOf("Утром город выходит из тени на свет.", "Днём Солнце светит прямо на город.", "Вечером город уходит к краю света.", "Ночью город в тени Земли.")
    }
}
