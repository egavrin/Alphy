package com.alpha.assistant.ui

import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_REQUEST
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_RATE
import com.alpha.assistant.voice.outputAudio
import com.alpha.assistant.voice.outputDelta

/**
 * The rig of the rehearsal tests of the voice stage (block 2 plan, V6): the real shell with a fake GPT-Live socket and fake audio, and the
 * helpers that `ClassifierRehearsalTest`, `FollowTurnRehearsalTest`, and `StageTapRehearsalTest` share. [name] names the shell fixture.
 */
internal class LiveStageRig(name: String) {
    val fixture = ShellFixture(name)
    val robot = ShellRobot(name)
    val socket = FakeLiveTransport()
    val audio = FakeLiveAudio()
    val shell = TemplateShell(fixture, robot, socket)

    fun remove() = shell.remove()

    /** Runs [test] in the voice mode with the scripted provider [talk]. The test gets the trace phases of the shell. */
    fun live(talk: StreamTalk, test: (ShellViewModel, StreamTalk, List<String>) -> Unit) =
        shell.live(talk, audio = audio) { _, model, phases -> test(model, talk, phases) }

    /** The spoken screen request on an empty stage: the stored scene, after the first sentence of its walk went out as thinking. */
    fun steppedScene(model: ShellViewModel): ToolRecord {
        socket.say(SCREEN_REQUEST, "d1", 100)
        val scene = shell.shown(model)
        robot.waitUntil(30_000) { thinkings().isNotEmpty() }
        return scene
    }

    /** The voice says [text] in one transcript piece with 600 ms of voiced audio of [marker]. */
    fun speak(text: String, marker: Int, atMs: Long) {
        socket.emit(outputDelta(text, atMs, atMs + 600))
        socket.emit(outputAudio(marker, LIVE_RATE * 6 / 10))
    }

    /** The count of the routing calls of the spoken request [text]. */
    fun routedOf(talk: StreamTalk, text: String) = talk.routed.count { it.decisionContext?.originalUserTurn?.text == text }

    /** The text of each thinking append, in order. */
    fun thinkings() = socket.thinking().map { it.second }

    companion object {
        const val ANSWER_REQUEST = "Какая погода"
        /** [TALKING], whose control is a choice, with one narration line. */
        val SAID get() = TALKING + "\nsay = \"Полый корабль вытесняет много воды и поэтому плавает.\""

        /** A new stepped scene with its narration, each line as two quick deltas. */
        fun stepped() = Streamed(halves(STEPPED) + halves(NARRATION_LINES.joinToString("\n")), gapMs = 30)
    }
}
