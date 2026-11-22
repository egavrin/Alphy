package com.alpha.assistant.ui

import android.os.SystemClock
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_TEMPLATE
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.MessageProvenance
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_REQUEST
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_RATE
import com.alpha.assistant.voice.LiveProtocol.MARK
import com.alpha.assistant.voice.inputDelta
import com.alpha.assistant.voice.liveDelegation
import com.alpha.assistant.voice.outputAudio
import com.alpha.assistant.voice.outputDelta
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow lane and the saved voice-only turns of plan "one scene" (Task 44, decisions 18 to 20) and C5: the real shell, kit, stage,
 * bridge, and session with a scripted chat and a scripted voice. The scripted chat answers each follow call with [StreamTalk.follow].
 */
class FollowTurnRehearsalTest {
    private val fixture = ShellFixture("stage-follow")
    private val robot = ShellRobot("stage-follow")
    private val socket = FakeLiveTransport()
    private val audio = FakeLiveAudio()
    private val shell = TemplateShell(fixture, robot, socket)
    @After fun remove() = shell.remove()

    @Test fun aVoiceOnlyTurnStartsOneFollowCall() = live { model, talk, _ ->
        said(model)
        voiceOnly("Как дела?", "Хорошо.", 3_000)
        robot.waitUntil(10_000) { talk.follows.size == 1 }
        val request = talk.follows.single()
        assertEquals(listOf("user" to "Как дела?", "assistant" to "Хорошо."), request.turns.map { it.role to it.text })
        assertTrue(checkNotNull(request.shownScreen).startsWith(ShownScreen.PREFIX))
        assertTrue(request.spokenContext.orEmpty().any { SCREEN_REQUEST in it.text })
        during(1_000) { assertEquals(1, talk.follows.size) }
    }

    /** A tap sentence and a typed turn are replies to a host text, so they start no follow call. */
    @Test fun aReplyToAHostTextStartsNoFollowCall() = live(StreamTalk(listOf(stepped()))) { model, talk, _ ->
        socket.say(SCREEN_REQUEST, "d1", 100)
        shell.shown(model)
        robot.waitUntil(30_000) { socket.thinking().isNotEmpty() }
        robot.click(NAIL)
        robot.waitUntil { socket.thinking().size == 2 }
        speak(SAYS[1], 3_000)
        val count = shell.messages(model).size
        robot.instrumentation.runOnMainSync { model.editDraft(Draft(TYPED)); model.sendLiveTyped() }
        shell.waitForTheAnswer(model, count)
        speak("Fixture response.", 6_000)
        during(1_500) { assertTrue(talk.follows.isEmpty()) }
    }

    /** A new turn cancels the follow call that still runs, and the stage does not change. */
    @Test fun aNewTurnCancelsTheFollowCall() = live { model, talk, _ ->
        talk.follow = CloudEvent.Follow("screen", "Небо", "")
        talk.followDelayMs = 3_000
        val scene = said(model)
        val calls = talk.calls.size
        voiceOnly("Покажи небо", "Небо голубое.", 3_000)
        robot.waitUntil { talk.follows.size == 1 }
        SystemClock.sleep(1_000)
        socket.emit(inputDelta("Подожди", 5_000, 5_400))
        robot.waitUntil { talk.followCancels.get() == 1 }
        during(3_000) { assertEquals(scene.id, model.stageToolId); assertEquals(calls, talk.calls.size) }
    }

    @Test fun followNoneKeepsTheStage() = live { model, talk, phases ->
        val scene = said(model)
        val calls = talk.calls.size
        voiceOnly("Как дела?", "Хорошо.", 3_000)
        robot.waitUntil { phases.contains("follow:none") }
        during(1_000) { assertEquals(scene.id, model.stageToolId); assertEquals(calls, talk.calls.size) }
    }

    /**
     * Kind screen of a turn where the voice gave the answer (C5, second test): the template call runs with no routing call. The scene shows
     * on the stage with no append to the voice and no walk. Its message is the assistant message of the pair.
     */
    @Test fun followScreenShowsASceneWithNoOpenerAndNoWalk() = live(StreamTalk(listOf(stepped(), stepped()))) { model, talk, _ ->
        talk.follow = CloudEvent.Follow("screen", "Небо", "", answered = true)
        val first = steppedScene(model)
        val routed = talk.routed.size
        voiceOnly("Покажи ещё раз", "Корабль полый, поэтому плавает.", 3_000)
        robot.waitUntil(60_000) { model.stageToolId.let { it != null && it != first.id } && !model.tools.busy }
        val texts = socket.commentary().size to socket.thinking().size
        assertEquals(2, talk.stages.count { it == APP_FILE_TEMPLATE })
        assertEquals(routed, talk.routed.size)
        during(2_000) { assertEquals(texts, socket.commentary().size to socket.thinking().size); assertFalse(model.liveWalking) }
        val message = shell.messages(model).single { it.toolId == model.stageToolId }
        assertEquals("assistant" to "Корабль полый, поэтому плавает.", message.role to message.text)
        assertTrue(message.spoken)
    }

    /** Kind screen of a turn where the voice only promised the answer (C5): the new scene walks as a new scene of a hand-off does. */
    @Test fun aWaitingLineTurnGetsASceneThatWalks() = live(StreamTalk(listOf(stepped()))) { model, talk, _ ->
        talk.follow = CloudEvent.Follow("screen", "Why a ship floats", "", answered = false)
        voiceOnly("Почему корабль не тонет?", "Сейчас объясню.", 1_000)
        robot.waitUntil(60_000) { model.stageToolId != null && !model.tools.busy }
        robot.waitUntil(30_000) { socket.thinking().isNotEmpty() }
        assertEquals(listOf("" to MARK + SAYS[0]), socket.thinking())
        assertTrue(model.liveWalking)
    }

    /** Kinds mark and press select the option quietly with who voice, and the trace detail names the kind. */
    @Test fun followMarkAndPressSelectTheOption() = live(StreamTalk(listOf(stepped()))) { model, talk, phases ->
        val scene = steppedScene(model)
        talk.follow = CloudEvent.Follow("mark", "", "$STEP_CONTROL: $NAIL")
        val texts = socket.commentary().size
        // A reply ends only after voiced audio, and only a reply start makes the spoken pair of the next turn.
        socket.emit(inputDelta("А гвоздь?", 3_000, 3_500))
        speak("Гвоздь тонет.", 3_700)
        robot.waitUntil(10_000) { shipSubject(shell.stored(model, scene.id)) == NAIL }
        assertTrue(phases.contains("follow:mark"))
        // A quick follow call can show the mark before the reply ends (800 ms with no chunk) and before the turn of the session ends (1200 ms).
        SystemClock.sleep(1_300)
        talk.follow = CloudEvent.Follow("press", "", "$STEP_CONTROL: ${STEPS[2]}")
        voiceOnly("А лодка?", "Лодка плавает.", 6_000)
        robot.waitUntil(10_000) { shipSubject(shell.stored(model, scene.id)) == STEPS[2] }
        assertTrue(phases.contains("follow:press"))
        assertEquals(texts, socket.commentary().size)
        socket.say(ANSWER_REQUEST, "d2", 9_000)
        robot.waitUntil(30_000) { talk.routed.any { it.decisionContext?.originalUserTurn?.text == ANSWER_REQUEST } }
        val line = JSONObject(checkNotNull(talk.routed.last().shownScreen).removePrefix(ShownScreen.PREFIX))
        val actions = line.getJSONArray("actions").let { list -> List(list.length()) { list.getJSONObject(it) } }
        assertEquals("voice" to STEPS[2], actions.last().let { it.getString("who") to it.get("value").toString() })
    }

    /** A follow press of the continue control walks the rest of the steps as the routing press does: its first sentence goes out as thinking. */
    @Test fun followPressOfTheContinueControlWalksTheRest() = live(StreamTalk(listOf(stepped()))) { model, talk, phases ->
        steppedScene(model)
        talk.follow = CloudEvent.Follow("press", "", CONTINUE_LABEL)
        voiceOnly(CONTINUE_REQUEST, "Сейчас.", 3_000)
        robot.waitUntil(10_000) { "" to MARK + SAYS[1] in socket.thinking() }
        assertTrue(phases.contains("follow:press"))
        assertTrue(model.liveWalking)
    }

    /** Kind clear empties the stage, and the chat still holds the card of the scene. */
    @Test fun followClearEmptiesTheStage() = live { model, talk, _ ->
        talk.follow = CloudEvent.Follow("clear", "", "")
        val scene = said(model)
        voiceOnly("Хватит про корабли", "Хорошо.", 3_000)
        robot.waitUntil(10_000) { model.stageToolId == null }
        assertTrue(shell.messages(model).any { it.toolId == scene.id })
    }

    /** Decision 20: a voice-only turn is a spoken pair in the chat, and the next routing request reads it as spoken context. */
    @Test fun aVoiceOnlyTurnIsSavedAsASpokenPair() = live { model, talk, _ ->
        said(model)
        socket.emit(inputDelta(SMALL_TALK, 3_000, 3_500))
        speak(SMALL_REPLY, 3_700)
        robot.waitUntil { talk.follows.size == 1 }
        // The reply ends after the reply gap with no chunk, and the pair persists then.
        SystemClock.sleep(1_300)
        val pair = shell.messages(model).takeLast(2)
        assertEquals(listOf("user" to SMALL_TALK, "assistant" to SMALL_REPLY), pair.map { it.role to it.text })
        assertTrue(pair.all { it.spoken && it.status == "complete" && it.provenance == MessageProvenance.CLEAN })
        socket.say(ANSWER_REQUEST, "d2", 7_000)
        robot.waitUntil(30_000) { talk.routed.any { it.decisionContext?.originalUserTurn?.text == ANSWER_REQUEST } }
        val context = talk.routed.last().spokenContext.orEmpty().map { it.text }
        assertTrue(context.toString(), context.any { SMALL_TALK in it } && context.any { SMALL_REPLY in it })
        val stored = fixture.store.load().conversations.single { it.id == model.snapshot.selectedId }.messages
        assertEquals(pair.map { listOf(it.id, it.role, it.text, it.spoken) }, stored.filter { it.id in pair.map { m -> m.id } }.map { listOf(it.id, it.role, it.text, it.spoken) })
    }

    /**
     * A caption piece of the person can come after the voice began its reply (live check of Alpha 20): it joins the same turn, so the
     * pair holds the whole words of the person, and the turn gets its one follow call.
     */
    @Test fun aLateCaptionJoinsTheSpokenPair() = live { model, talk, _ ->
        said(model)
        socket.emit(inputDelta("Что такое чёрная", 3_000, 3_800))
        socket.emit(outputDelta("Это область", 4_000, 4_300))
        socket.emit(inputDelta(" дыра", 3_800, 4_100))
        socket.emit(outputDelta(" в космосе.", 4_300, 4_700))
        robot.waitUntil(10_000) { talk.follows.size == 1 }
        val whole = listOf("user" to "Что такое чёрная дыра", "assistant" to "Это область в космосе.")
        assertEquals(whole, talk.follows.single().turns.map { it.role to it.text })
        assertEquals(whole, shell.messages(model).takeLast(2).map { it.role to it.text })
        assertTrue(shell.messages(model).takeLast(2).all { it.spoken })
        during(1_000) { assertEquals(1, talk.follows.size) }
    }

    /** Review focus 3: the voice speaks a few words and then hands off in the same turn. One user message stays, and the follow call stops. */
    @Test fun wordsThenAHandOffLeaveOneUserMessage() = live { model, talk, _ ->
        talk.followDelayMs = 3_000
        socket.emit(inputDelta(ANSWER_REQUEST, 1_000, 1_500))
        socket.emit(outputDelta("Хорошо.", 1_700, 2_000))
        robot.waitUntil { talk.follows.size == 1 }
        socket.emit(liveDelegation("d1", 2_100))
        robot.waitUntil(30_000) { shell.messages(model).size == 2 && shell.messages(model).last().status == "complete" }
        robot.waitUntil { talk.followCancels.get() == 1 }
        assertEquals(listOf(ANSWER_REQUEST), shell.messages(model).filter { it.role == "user" }.map { it.text })
        assertTrue(shell.messages(model).none { it.spoken })
        assertNull(model.snapshot.conversations.singleOrNull { c -> c.messages.any { it.spoken } })
    }

    private fun live(talk: StreamTalk = StreamTalk(listOf(Streamed(halves(SAID), gapMs = 30))), test: (ShellViewModel, StreamTalk, List<String>) -> Unit) =
        shell.live(talk, audio = audio) { _, model, phases -> test(model, talk, phases) }

    private fun stepped() = Streamed(halves(STEPPED) + halves(NARRATION_LINES.joinToString("\n")), gapMs = 30)

    /** A scene with one narration line from the spoken screen request, after the voice said its line. */
    private fun said(model: ShellViewModel): ToolRecord {
        socket.say(SCREEN_REQUEST, "d1", 100)
        val scene = shell.shown(model)
        robot.waitUntil(30_000) { socket.thinking().isNotEmpty() }
        speak(socket.thinking().last().second.removePrefix(MARK), 1_500)
        SystemClock.sleep(1_300)
        return scene
    }

    /** A stepped scene from the spoken screen request, after its walk started. */
    private fun steppedScene(model: ShellViewModel): ToolRecord {
        socket.say(SCREEN_REQUEST, "d1", 100)
        val scene = shell.shown(model)
        robot.waitUntil(30_000) { socket.thinking().isNotEmpty() }
        return scene
    }

    /** The person says [words], and the voice answers [reply] with no hand-off. */
    private fun voiceOnly(words: String, reply: String, atMs: Long) {
        socket.emit(inputDelta(words, atMs, atMs + 500))
        socket.emit(outputDelta(reply, atMs + 700, atMs + 1_200))
    }

    /** The voice says [text] in one transcript piece with 600 ms of voiced audio. */
    private fun speak(text: String, atMs: Long) {
        socket.emit(outputDelta(text, atMs, atMs + 600))
        socket.emit(outputAudio(3, LIVE_RATE * 6 / 10))
    }

    private companion object {
        const val ANSWER_REQUEST = "Какая погода"
        const val TYPED = "Который час"
        const val SMALL_TALK = "Как дела?"
        const val SMALL_REPLY = "Хорошо, спасибо."
        /** [TALKING], whose control is a choice, with one narration line. */
        val SAID get() = TALKING + "\nsay = \"Полый корабль вытесняет много воды и поэтому плавает.\""
    }
}
