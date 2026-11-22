package com.alpha.assistant.ui

import android.os.SystemClock
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.alpha.assistant.tools.SceneNarration
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_REQUEST
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_RATE
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveProtocol.MARK
import com.alpha.assistant.voice.outputAudio
import com.alpha.assistant.voice.outputDelta
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * A tap, a press, and a walk on a stepped scene in the voice mode (plan "one scene", Tasks 29, 31, and 32, decisions 10, 11, and 14 to 17).
 * The real shell, kit, stage, bridge, and session run with a scripted chat and a scripted voice: the test plays the transcript pieces and
 * the audio of the voice. Each scene is [STEPPED] with [SAYS] as its narration.
 */
class StageTapRehearsalTest {
    private val fixture = ShellFixture("stage-tap")
    private val robot = ShellRobot("stage-tap")
    private val socket = FakeLiveTransport()
    private val audio = FakeLiveAudio()
    private val shell = TemplateShell(fixture, robot, socket)
    @After fun remove() = shell.remove()

    @Test fun aTapOnAStepWhileSilentSaysItsSentence() = live { model, talk ->
        val scene = scene(model)
        val calls = listOf(talk.calls.size, talk.routed.size)
        robot.click(NAIL)
        robot.waitUntil { thinkings().size == 2 }
        assertEquals(listOf(MARK + SAYS[0], MARK + SAYS[1]), thinkings())
        // A tap stops the walk (decision 11), and the chat gets no call.
        assertFalse(model.liveWalking)
        assertEquals(calls, listOf(talk.calls.size, talk.routed.size))
        // Review focus 5: the voice still says say1, which the stop skipped. The stage keeps the tapped step, and the speaker plays none of
        // it. The tapped sentence then plays in full.
        speak(SAYS[0], marker = 7, atMs = 3_000)
        SystemClock.sleep(REPLY_END_MS)
        speak(SAYS[1], marker = 8, atMs = 5_000)
        robot.waitUntil { played(8) }
        assertFalse("the speaker played the skipped say1", played(7))
        assertEquals(NAIL, shipSubject(shell.stored(model, scene.id)))
        assertEquals(listOf(MARK + SAYS[0], MARK + SAYS[1]), thinkings())
    }

    @Test fun aTapWhileTheVoiceSpeaksSaysNothing() = live { model, talk ->
        val scene = scene(model)
        val voice = thread {
            socket.emit(outputDelta("Сейчас покажу, почему так.", 2_000, 2_600))
            repeat(60) { socket.emit(outputAudio(5, CHUNK_100)); SystemClock.sleep(100) }
        }
        robot.waitUntil { model.liveVoice.speaksNow }
        robot.click(NAIL)
        robot.waitUntil { shipSubject(shell.stored(model, scene.id)) == NAIL }
        during(2_000) { assertEquals(listOf(MARK + SAYS[0]), thinkings()) }
        assertFalse(model.liveWalking)
        voice.join()
        ask(talk, ANSWER_REQUEST, "d2", 10_000)
        assertEquals(listOf("tap:$STEP_CONTROL:$NAIL"), actionsOf(talk, ANSWER_REQUEST)?.takeLast(1))
    }

    /** A tap on a choice says nothing, a scene with one sentence says it once, and a later revision of the scene says nothing. */
    @Test fun aTapOnAChoiceSaysNothing() = live(StreamTalk(listOf(Streamed(halves(SAID), gapMs = 30), Streamed(listOf("$SHIP_S4\n"), gapMs = 0)))) { model, _ ->
        socket.say(SCREEN_REQUEST, "d1", 100)
        val scene = shell.shown(model)
        robot.waitUntil(30_000) { thinkings().isNotEmpty() }
        assertEquals(listOf(MARK + ONE_SAY), thinkings())
        assertFalse(model.liveWalking)
        robot.click(NAIL)
        robot.waitUntil { shipSubject(shell.stored(model, scene.id)) == NAIL }
        during(1_500) { assertEquals(listOf(MARK + ONE_SAY), thinkings()) }
        val count = shell.messages(model).size
        socket.say(TemplateTalk.CHANGE_REQUEST, "d2", 3_000)
        shell.waitForTheAnswer(model, count)
        robot.waitUntil { shell.stored(model, scene.id).revision == scene.revision + 1 }
        during(1_500) { assertEquals(listOf(MARK + ONE_SAY), thinkings()) }
    }

    /**
     * Speech stops the walk, «Дальше» presses the next step and says its sentence, and each action names who did it. The words of «Дальше»
     * are the speech that stops the walk: words with no output after them join the next request of the transcript.
     */
    @Test fun eachHostPressHasItsWho() = live(StreamTalk(listOf(stepped(), stepped()))) { model, talk ->
        val scene = scene(model)
        ask(talk, NEXT_REQUEST, "d2", 2_000)
        assertFalse(model.liveWalking)
        // The voice press ran, so the commentary is the sentence of the step and not PRESS_FAILED.
        robot.waitUntil(30_000) { "d2" to MARK + SAYS[1] in socket.commentary() }
        stays(model, scene, NAIL)
        assertEquals(listOf(MARK + SAYS[0]), thinkings())
        ask(talk, ANSWER_REQUEST, "d3", 8_000)
        assertEquals(listOf("voice:$STEP_CONTROL:$NAIL"), actionsOf(talk, ANSWER_REQUEST))
        // A new scene on the stage starts with no action.
        socket.say(SCREEN_REQUEST, "d4", 11_000)
        robot.waitUntil(60_000) { model.stageToolId.let { it != null && it != scene.id } && !model.tools.busy }
        ask(talk, OTHER_REQUEST, "d5", 14_000)
        assertEquals(emptyList<String>(), actionsOf(talk, OTHER_REQUEST))
    }

    /**
     * Fix list item 1: «Дальше» while the voice says sentence 2, before its sync fire. The walk stops, its fire never comes, and the stage
     * goes to step 2 by the voice press only.
     */
    @Test fun aNextInTheMiddleOfASentenceGoesToTheNextStep() = live { model, talk ->
        val scene = scene(model)
        speak(SAYS[0], marker = 1, atMs = 2_000)
        robot.waitUntil { thinkings().size == 2 }
        socket.emit(outputDelta(SAYS[1].take(12), 4_000, 4_300))
        ask(talk, NEXT_REQUEST, "d2", 4_400)
        robot.waitUntil(30_000) { "d2" to MARK + SAYS[1] in socket.commentary() }
        stays(model, scene, NAIL)
        assertEquals(listOf(MARK + SAYS[0], MARK + SAYS[1]), thinkings())
        ask(talk, ANSWER_REQUEST, "d3", 8_000)
        assertEquals(listOf("voice:$STEP_CONTROL:$NAIL"), actionsOf(talk, ANSWER_REQUEST))
    }

    /**
     * Fix list item 1: «Дальше» right after the sync fire of sentence 2. The narrator pressed step 2, and the voice press goes to step 3. No
     * press of the walk comes after the stop.
     */
    @Test fun aNextRightAfterASyncFireGoesToTheNextStep() = live { model, talk ->
        val scene = scene(model)
        speak(SAYS[0], marker = 1, atMs = 2_000)
        robot.waitUntil { thinkings().size == 2 }
        speak(SAYS[1], marker = 2, atMs = 4_000)
        robot.waitUntil { thinkings().size == 3 }
        ask(talk, NEXT_REQUEST, "d2", 5_000)
        robot.waitUntil(30_000) { "d2" to MARK + SAYS[2] in socket.commentary() }
        stays(model, scene, STEPS[2])
        assertEquals(SAYS.map { MARK + it }, thinkings())
        ask(talk, ANSWER_REQUEST, "d3", 8_000)
        assertEquals(listOf("narrator:$STEP_CONTROL:$NAIL", "voice:$STEP_CONTROL:${STEPS[2]}"), actionsOf(talk, ANSWER_REQUEST))
    }

    /** Fix list item 4: after a change of the stepped scene that changes say2, a tap on step 2 says the changed sentence. */
    @Test fun aTapAfterAChangeSaysTheChangedSentence() =
        live(StreamTalk(listOf(stepped(), Streamed(listOf("$SHIP_S4\n", "say2 = \"$CHANGED_SAY\"\n"), gapMs = 0)))) { model, _ ->
            val scene = scene(model)
            val count = shell.messages(model).size
            socket.say(TemplateTalk.CHANGE_REQUEST, "d2", 2_000)
            shell.waitForTheAnswer(model, count)
            robot.waitUntil { shell.stored(model, scene.id).revision == scene.revision + 1 }
            assertEquals(CHANGED_SAY, SceneNarration.of(appFileOf(shell.stored(model, scene.id))).sentence(2))
            robot.click(NAIL)
            robot.waitUntil { thinkings().size == 2 }
            assertEquals(listOf(MARK + SAYS[0], MARK + CHANGED_SAY), thinkings())
        }

    /**
     * The walk of a new scene with the voice: the stage shows its answer step during the opener, and each sentence moves the stage to its
     * step when its start plays. Between two sentences, the strip keeps the speaking status and the mode stays on (decision 17).
     */
    @Test fun theWalkGoesFromTheFirstStepToTheLast() = live { model, talk ->
        val scene = scene(model)
        assertEquals(STEPS[0], shipSubject(scene))
        speak("Сейчас покажу, почему корабль плавает.", marker = 1, atMs = 2_000)
        during(1_500) { assertEquals(STEPS[0], shipSubject(shell.stored(model, scene.id))); assertEquals(1, thinkings().size) }
        // Step 1 is the answer step, which the stage already shows.
        speak(SAYS[0], marker = 2, atMs = 4_000)
        robot.waitUntil { thinkings().size == 2 }
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
        val listening = fixture.context.getString(com.alpha.assistant.R.string.live_voice_listening)
        val speaking = fixture.context.getString(com.alpha.assistant.R.string.live_voice_speaking)
        during(1_000) {
            assertTrue(model.liveWalking)
            assertNotEquals(LivePhase.IDLE, model.liveVoice.phase)
            assertTrue("the strip lost the speaking status", shows(speaking) && !shows(listening))
        }
        speak(SAYS[1], marker = 3, atMs = 6_000)
        robot.waitUntil { thinkings().size == 3 }
        assertEquals(NAIL, shipSubject(shell.stored(model, scene.id)))
        speak(SAYS[2], marker = 4, atMs = 8_000)
        robot.waitUntil { shipSubject(shell.stored(model, scene.id)) == STEPS[2] }
        robot.waitUntil { !model.liveWalking }
        assertEquals(SAYS.map { MARK + it }, thinkings())
        // Review focus 1: the presses of the walk are the narrator's, so they do not stop it, and no action is a tap.
        ask(talk, ANSWER_REQUEST, "d2", 11_000)
        assertEquals(listOf("narrator:$STEP_CONTROL:$NAIL", "narrator:$STEP_CONTROL:${STEPS[2]}"), actionsOf(talk, ANSWER_REQUEST))
    }

    /** A new hand-off stops the walk. «Продолжай» presses the continue control of the line and walks from the present step to the last. */
    @Test fun continueWalksTheRest() = live { model, talk ->
        val scene = scene(model)
        ask(talk, ANSWER_REQUEST, "d2", 2_000)
        assertFalse(model.liveWalking)
        robot.waitUntil(30_000) { "d2" to "${MARK}Fixture response." in socket.commentary() }
        ask(talk, CONTINUE_REQUEST, "d3", 5_000)
        val line = JSONObject(checkNotNull(talk.routed.last().shownScreen).removePrefix(ShownScreen.PREFIX))
        assertTrue(line.toString(), CONTINUE_LABEL in line.getJSONArray("controls").let { controls -> List(controls.length()) { controls.getString(it) } })
        // The host says the sentence of step 2 for the press, and the walk goes on from it.
        robot.waitUntil(30_000) { "d3" to MARK + SAYS[1] in socket.commentary() }
        assertTrue(model.liveWalking)
        speak(SAYS[1], marker = 2, atMs = 8_000)
        robot.waitUntil { thinkings().size == 2 }
        assertEquals(NAIL, shipSubject(shell.stored(model, scene.id)))
        speak(SAYS[2], marker = 3, atMs = 10_000)
        robot.waitUntil { shipSubject(shell.stored(model, scene.id)) == STEPS[2] }
        robot.waitUntil { !model.liveWalking }
        assertEquals(listOf(MARK + SAYS[0], MARK + SAYS[2]), thinkings())
    }

    private fun live(talk: StreamTalk = StreamTalk(listOf(stepped())), test: (ShellViewModel, StreamTalk) -> Unit) =
        shell.live(talk, audio = audio) { _, model, _ -> test(model, talk) }

    /** A new stepped scene with its narration, each line as two quick deltas. */
    private fun stepped() = Streamed(halves(STEPPED) + halves(NARRATION_LINES.joinToString("\n")), gapMs = 30)

    /** The spoken screen request on an empty stage: the stored scene, after its walk started with the append of say1. */
    private fun scene(model: ShellViewModel): ToolRecord {
        socket.say(SCREEN_REQUEST, "d1", 100)
        val scene = shell.shown(model)
        robot.waitUntil(30_000) { thinkings().isNotEmpty() }
        assertEquals(listOf(MARK + SAYS[0]), thinkings())
        assertTrue(model.liveWalking)
        return scene
    }

    /** The voice says [text] in one transcript piece with 600 ms of voiced audio of [marker] after a quiet run. */
    private fun speak(text: String, marker: Int, atMs: Long) {
        socket.emit(outputDelta(text, atMs, atMs + 600))
        socket.emit(outputAudio(marker, VOICED_600))
    }

    /** The person says [text] as hand-off [id], and the routing call of it came. */
    private fun ask(talk: StreamTalk, text: String, id: String, atMs: Long) {
        val before = talk.routed.count { it.decisionContext?.originalUserTurn?.text == text }
        socket.say(text, id, atMs)
        robot.waitUntil(30_000) { talk.routed.count { it.decisionContext?.originalUserTurn?.text == text } == before + 1 }
    }

    /** The actions of the shown-screen line of the last routing call of [text], each as who:control:value. */
    private fun actionsOf(talk: StreamTalk, text: String): List<String>? {
        val line = talk.routed.last { it.decisionContext?.originalUserTurn?.text == text }.shownScreen ?: return null
        val actions = JSONObject(line.removePrefix(ShownScreen.PREFIX)).optJSONArray("actions") ?: return emptyList()
        return List(actions.length()) { actions.getJSONObject(it).let { action -> "${action.getString("who")}:${action.getString("control")}:${action.get("value")}" } }
    }

    /**
     * The stored step of [scene] becomes [step] and stays it for 1.5 s: the stage commits a press, and the list of tools reloads after the
     * store changes, so a read right after the commentary can still see the step before.
     */
    private fun stays(model: ShellViewModel, scene: ToolRecord, step: String) {
        robot.waitUntil { shipSubject(shell.stored(model, scene.id)) == step }
        during(1_500) { assertEquals(step, shipSubject(shell.stored(model, scene.id))) }
    }

    private fun thinkings() = socket.sent.map(::JSONObject).filter { it.getString("type") == "session.thinking.append" }.map { it.getString("content") }

    private fun played(marker: Int) = audio.played.any { it.firstOrNull() == (marker * 1_000).toShort() }

    /** True when a node of the resumed activity shows [text], read on the main thread now. */
    private fun shows(text: String): Boolean {
        var shown = false
        robot.instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
            shown = activity?.let(robot::nodes).orEmpty().any { robot.hasText(text)(it.config) }
        }
        return shown
    }

    private companion object {
        const val ANSWER_REQUEST = "Какая погода"
        const val OTHER_REQUEST = "Который час"
        const val ONE_SAY = "Полый корабль вытесняет много воды и поэтому плавает."
        /** The say2 of the change of [STEPPED] in the test of fix list item 4. */
        const val CHANGED_SAY = "$NAIL тяжелее воды, которую он вытесняет."
        /** [TALKING], whose control is a choice, with one narration line. */
        val SAID get() = TALKING + "\nsay = \"$ONE_SAY\""
        const val VOICED_600 = LIVE_RATE * 6 / 10
        const val CHUNK_100 = LIVE_RATE / 10
        /** The reply gap of the default timing of the session, with a margin. */
        const val REPLY_END_MS = 1_300L
    }
}
