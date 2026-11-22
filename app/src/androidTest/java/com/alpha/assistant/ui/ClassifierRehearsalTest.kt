package com.alpha.assistant.ui

import android.os.SystemClock
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_REQUEST
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_RATE
import com.alpha.assistant.voice.LiveProtocol.MARK
import com.alpha.assistant.voice.LiveProtocol.SPOKEN_STEP
import com.alpha.assistant.voice.LiveProtocol.USER_COMMAND
import com.alpha.assistant.voice.LiveProtocol.USER_STEP
import com.alpha.assistant.voice.classified
import com.alpha.assistant.voice.inputDelta
import com.alpha.assistant.voice.liveDecision
import com.alpha.assistant.voice.liveDelegation
import com.alpha.assistant.voice.liveItemCreated
import com.alpha.assistant.voice.outputAudio
import com.alpha.assistant.voice.outputDelta
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier of plan "one scene", part K (design 9.5), and C4: the real shell, kit, stage, bridge, and session with a scripted chat, a
 * scripted voice, and scripted `decision.event` messages. Each scene is [STEPPED] with [SAYS] as its narration.
 */
class ClassifierRehearsalTest {
    private val fixture = ShellFixture("stage-classifier")
    private val robot = ShellRobot("stage-classifier")
    private val socket = FakeLiveTransport()
    private val audio = FakeLiveAudio()
    private val shell = TemplateShell(fixture, robot, socket)
    @After fun remove() { LiveVoiceBridge.FREE_ANSWER_MOVES = true; shell.remove() }

    /** K3: next, go_to, back, and repeat move the step and say its sentence with no routing call. The chat keeps the words and the sentence. */
    @Test fun spokenCommandsMoveTheStepWithNoRoutingCall() = live { model, talk ->
        val scene = scene(model)
        val routed = talk.routed.size
        command(NEXT_REQUEST, "p1", "d2", 2_000, classified(USER_COMMAND, "next", 0.9), classified(USER_STEP, "none", 0.5))
        robot.waitUntil(10_000) { "d2" to MARK + SAYS[1] in socket.commentary() }
        shows(model, scene, NAIL)
        command(GO_TO, "p2", "d3", 4_000, classified(USER_COMMAND, "go_to", 0.9), classified(USER_STEP, "3", 0.7))
        robot.waitUntil(10_000) { "d3" to MARK + SAYS[2] in socket.commentary() }
        shows(model, scene, STEPS[2])
        command(BACK, "p3", "d4", 6_000, classified(USER_COMMAND, "back", 0.8))
        robot.waitUntil(10_000) { "d4" to MARK + SAYS[1] in socket.commentary() }
        shows(model, scene, NAIL)
        command(REPEAT, "p4", "d5", 8_000, classified(USER_COMMAND, "repeat", 0.8))
        robot.waitUntil(10_000) { "d5" to MARK + SAYS[1] in socket.commentary() }
        shows(model, scene, NAIL)
        assertEquals(routed, talk.routed.size)
        robot.waitUntil { shell.messages(model).size >= 10 }
        assertEquals(listOf("user" to NEXT_REQUEST, "assistant" to SAYS[1], "user" to GO_TO, "assistant" to SAYS[2], "user" to BACK,
            "assistant" to SAYS[1], "user" to REPEAT, "assistant" to SAYS[1]), shell.messages(model).takeLast(8).map { it.role to it.text })
    }

    /** K3: back at the first step answers «Это первый шаг.» and moves nothing. */
    @Test fun backAtTheFirstStepSaysSo() = live { model, talk ->
        val scene = scene(model)
        val routed = talk.routed.size
        command(BACK, "p1", "d2", 2_000, classified(USER_COMMAND, "back", 0.9))
        robot.waitUntil(10_000) { "d2" to LiveVoiceBridge.FIRST_STEP in socket.commentary() }
        shows(model, scene, STEPS[0])
        assertEquals(routed, talk.routed.size)
    }

    /** K3: a command below 0.75 goes to the routing call. */
    @Test fun aWeakCommandGoesToTheRoutingCall() = live { model, talk ->
        scene(model)
        command(NEXT_REQUEST, "p1", "d2", 2_000, classified(USER_COMMAND, "next", 0.7))
        robot.waitUntil(10_000) { routedOf(talk, NEXT_REQUEST) == 1 }
    }

    /** K3: only the answer of the last piece counts. A middle piece says back at 0.83, and the last piece says none: nothing moves. */
    @Test fun onlyTheLastPieceCounts() = live { model, talk ->
        val scene = scene(model)
        socket.emit(inputDelta("Назад, ", 2_000, 2_300, "p1"))
        socket.emit(liveDecision("p1", true, classified(USER_COMMAND, "back", 0.83)))
        socket.emit(inputDelta("нет, расскажи про гвоздь", 2_300, 2_900, "p2"))
        socket.emit(liveDecision("p2", true, classified(USER_COMMAND, "none", 0.9), classified(USER_STEP, "none", 0.6)))
        socket.emit(liveDelegation("d2", 3_000))
        robot.waitUntil(10_000) { talk.routed.any { it.decisionContext?.originalUserTurn?.text?.contains("гвоздь") == true } }
        shows(model, scene, STEPS[0])
    }

    /** K3: with no hand-off, a command acts 1000 ms after the last piece. A later hand-off of that turn gets an empty append and no routing call. */
    @Test fun aCommandWithNoHandOffActsAtThePhraseEnd() = live { model, talk ->
        val scene = scene(model)
        val routed = talk.routed.size
        socket.emit(inputDelta(NEXT_REQUEST, 2_000, 2_500, "p1"))
        socket.emit(liveDecision("p1", true, classified(USER_COMMAND, "next", 0.9)))
        robot.waitUntil(5_000) { "" to MARK + SAYS[1] in socket.thinking() }
        shows(model, scene, NAIL)
        socket.emit(liveDelegation("d2", 4_000))
        robot.waitUntil { "d2" to "" in socket.thinking() }
        during(1_000) { assertEquals(routed, talk.routed.size) }
        assertEquals(listOf("user" to NEXT_REQUEST, "assistant" to SAYS[1]), shell.messages(model).takeLast(2).map { it.role to it.text })
    }

    /**
     * K3 over the voice (live check of Alpha 20): the caption pieces of the person come between pieces of the voice. They are one turn:
     * one command acts, and the chat keeps the whole phrase of the person.
     */
    @Test fun aCommandSpokenOverTheVoiceIsOneTurn() = live { model, talk ->
        val scene = scene(model)
        val routed = talk.routed.size
        socket.emit(outputDelta("Смотри ", 2_000, 2_300, "o1"))
        socket.emit(inputDelta("Давай ", 2_100, 2_400, "p1"))
        socket.emit(liveDecision("p1", true, classified(USER_COMMAND, "none", 0.6)))
        socket.emit(outputDelta("на ", 2_300, 2_600, "o2"))
        socket.emit(inputDelta("дальше", 2_500, 2_900, "p2"))
        socket.emit(liveDecision("p2", true, classified(USER_COMMAND, "next", 0.9)))
        socket.emit(outputDelta("экран.", 2_600, 3_000, "o3"))
        robot.waitUntil(5_000) { "" to MARK + SAYS[1] in socket.thinking() }
        shows(model, scene, NAIL)
        assertEquals(routed, talk.routed.size)
        assertEquals(listOf("user" to "Давай дальше", "assistant" to SAYS[1]), shell.messages(model).takeLast(2).map { it.role to it.text })
        assertTrue(shell.messages(model).none { it.spoken })
    }

    /** K3: at a hand-off, the answer of the last piece may come up to 300 ms late. An answer after 400 ms leaves the turn to the routing call. */
    @Test fun theAnswerOfTheLastPieceMayComeLate() = live { model, talk ->
        val scene = scene(model)
        val routed = talk.routed.size
        socket.emit(inputDelta(NEXT_REQUEST, 2_000, 2_500, "p1"))
        socket.emit(liveDelegation("d2", 2_600))
        SystemClock.sleep(200)
        socket.emit(liveDecision("p1", true, classified(USER_COMMAND, "next", 0.9)))
        robot.waitUntil(10_000) { "d2" to MARK + SAYS[1] in socket.commentary() }
        shows(model, scene, NAIL)
        assertEquals(routed, talk.routed.size)
        socket.emit(inputDelta(NEXT_REQUEST, 5_000, 5_500, "p2"))
        socket.emit(liveDelegation("d3", 5_600))
        SystemClock.sleep(400)
        socket.emit(liveDecision("p2", true, classified(USER_COMMAND, "next", 0.9)))
        robot.waitUntil(10_000) { routedOf(talk, NEXT_REQUEST) == 1 }
    }

    /**
     * K3: a hand-off stops the 1000 ms phrase timer. The hand-off comes 800 ms after the last piece and its answer 250 ms later: the command
     * acts with one answer on that hand-off, and no sentence goes out as thinking.
     */
    @Test fun aHandOffStopsThePhraseTimer() = live { model, talk ->
        val scene = scene(model)
        val routed = talk.routed.size
        socket.emit(inputDelta(NEXT_REQUEST, 2_000, 2_500, "p1"))
        SystemClock.sleep(800)
        socket.emit(liveDelegation("d2", 2_600))
        SystemClock.sleep(250)
        socket.emit(liveDecision("p1", true, classified(USER_COMMAND, "next", 0.9)))
        robot.waitUntil(10_000) { "d2" to MARK + SAYS[1] in socket.commentary() }
        shows(model, scene, NAIL)
        during(1_000) { assertEquals(1, socket.commentary().count { it.first == "d2" }) }
        assertEquals(listOf(MARK + SAYS[0]), thinkings())
        assertEquals(routed, talk.routed.size)
    }

    /** K3: a caption piece that comes after the end of a phrase starts a new person turn, so its hand-off gets a routing call, not an empty append. */
    @Test fun aPieceAfterThePhraseEndStartsANewTurn() = live { model, talk ->
        scene(model)
        val routed = talk.routed.size
        socket.emit(inputDelta(NEXT_REQUEST, 2_000, 2_500, "p1"))
        socket.emit(liveDecision("p1", true, classified(USER_COMMAND, "next", 0.9)))
        robot.waitUntil(5_000) { "" to MARK + SAYS[1] in socket.thinking() }
        SystemClock.sleep(100)
        socket.emit(inputDelta(" а почему гвоздь тонет", 3_600, 4_100, "p2"))
        socket.emit(liveDecision("p2", true, classified(USER_COMMAND, "none", 0.9), classified(USER_STEP, "none", 0.6)))
        socket.emit(liveDelegation("d2", 4_200))
        robot.waitUntil(10_000) { talk.routed.size == routed + 1 }
        assertTrue(socket.thinking().none { it.first == "d2" })
    }

    /** K3: a command turn where the voice already said a waiting line starts no follow call and saves no spoken pair. */
    @Test fun aCommandTurnWithAWaitingLineStartsNoFollowCall() = live { model, talk ->
        scene(model)
        socket.emit(inputDelta(NEXT_REQUEST, 2_000, 2_500, "p1"))
        socket.emit(outputDelta("Сейчас.", 2_600, 2_900))
        socket.emit(liveDecision("p1", true, classified(USER_COMMAND, "next", 0.9)))
        robot.waitUntil(5_000) { "" to MARK + SAYS[1] in socket.thinking() }
        during(1_500) { assertTrue(talk.follows.isEmpty()) }
        assertTrue(shell.messages(model).none { it.spoken })
        assertEquals(listOf("user" to NEXT_REQUEST, "assistant" to SAYS[1]), shell.messages(model).takeLast(2).map { it.role to it.text })
    }

    /**
     * K4: a question about step 3 while step 1 shows moves the picture to step 3, says nothing, and goes on to the routing call. A first
     * piece that names step 2 at 0.88 with a last piece that names none moves nothing.
     */
    @Test fun aQuestionAboutAStepMovesThePicture() = live { model, talk ->
        val scene = scene(model)
        val routed = talk.routed.size
        command("А почему лодка плавает?", "p1", "d2", 2_000, classified(USER_COMMAND, "none", 0.9), classified(USER_STEP, "3", 0.85))
        robot.waitUntil(10_000) { talk.routed.size == routed + 1 }
        shows(model, scene, STEPS[2])
        assertTrue(socket.thinking().none { it.second == MARK + SAYS[2] })
        socket.emit(inputDelta("Про гвоздь ", 5_000, 5_300, "p2"))
        socket.emit(liveDecision("p2", true, classified(USER_COMMAND, "none", 0.9), classified(USER_STEP, "2", 0.88)))
        socket.emit(inputDelta("я уже знаю, а что ещё бывает?", 5_300, 5_900, "p3"))
        socket.emit(liveDecision("p3", true, classified(USER_COMMAND, "none", 0.9), classified(USER_STEP, "none", 0.9)))
        socket.emit(liveDelegation("d3", 6_000))
        robot.waitUntil(10_000) { talk.routed.size == routed + 2 }
        shows(model, scene, STEPS[2])
    }

    /** K5: in a walk, the voice starts the next sentence with changed words, and the classifier names its step: the step moves, and the next sentence goes out. */
    @Test fun aChangedWalkSentenceMovesByTheClassifier() = live { model, _ ->
        val scene = scene(model)
        speak(SAYS[0], marker = 1, atMs = 2_000)
        robot.waitUntil { thinkings().size == 2 }
        socket.emit(outputDelta("Железный гвоздь весь сплошной, поэтому тонет.", 4_000, 4_600, "o2"))
        socket.emit(liveDecision("o2", false, classified(SPOKEN_STEP, "2", 0.6)))
        robot.waitUntil { thinkings().size == 3 }
        assertEquals(SAYS.map { MARK + it }, thinkings())
        shows(model, scene, NAIL)
    }

    /** K5: in a walk, an answer for an earlier step or for a step two ahead moves nothing. */
    @Test fun aWalkIgnoresOtherSteps() = live { model, _ ->
        val scene = scene(model)
        speak(SAYS[0], marker = 1, atMs = 2_000)
        robot.waitUntil { thinkings().size == 2 }
        socket.emit(outputDelta("Сначала вспомним.", 4_000, 4_400, "o2"))
        socket.emit(liveDecision("o2", false, classified(SPOKEN_STEP, "1", 0.9)))
        socket.emit(outputDelta(" А потом посмотрим.", 4_400, 4_800, "o3"))
        socket.emit(liveDecision("o3", false, classified(SPOKEN_STEP, "3", 0.9)))
        during(1_000) { assertEquals(2, thinkings().size); assertEquals(STEPS[0], shipSubject(shell.stored(model, scene.id))) }
    }

    /** K6: a reply to a host text moves nothing. A free answer of the voice that names an earlier step moves the picture back. */
    @Test fun aFreeAnswerMovesThePictureBack() = live { model, _ ->
        val scene = scene(model)
        command(NEXT_REQUEST, "p1", "d2", 2_000, classified(USER_COMMAND, "next", 0.9))
        robot.waitUntil(10_000) { "d2" to MARK + SAYS[1] in socket.commentary() }
        shows(model, scene, NAIL)
        socket.emit(outputDelta(SAYS[1], 3_000, 3_600, "o1"))
        socket.emit(liveDecision("o1", false, classified(SPOKEN_STEP, "1", 0.9)))
        during(800) { assertEquals(NAIL, shipSubject(shell.stored(model, scene.id))) }
        socket.emit(inputDelta("Расскажи про корабль", 6_000, 6_500, "p2"))
        socket.emit(outputDelta("Корабль внутри полый.", 6_700, 7_200, "o2"))
        socket.emit(liveDecision("o2", false, classified(SPOKEN_STEP, "1", 0.7)))
        shows(model, scene, STEPS[0])
    }

    /** K6 has one switch: with it off, a free answer moves nothing. */
    @Test fun withTheSwitchOffAFreeAnswerMovesNothing() = live { model, _ ->
        LiveVoiceBridge.FREE_ANSWER_MOVES = false
        val scene = scene(model)
        socket.emit(inputDelta("Расскажи про гвоздь", 2_000, 2_500, "p1"))
        socket.emit(outputDelta("Гвоздь сплошной.", 2_700, 3_200, "o1"))
        socket.emit(liveDecision("o1", false, classified(SPOKEN_STEP, "2", 0.9)))
        during(1_000) { assertEquals(STEPS[0], shipSubject(shell.stored(model, scene.id))) }
    }

    /** K7: after a classifier move, the follow call of that turn makes no new screen. */
    @Test fun noFollowScreenAfterAClassifierMove() = live { model, talk ->
        talk.follow = CloudEvent.Follow("screen", "Небо", "", answered = true)
        val scene = scene(model)
        val calls = talk.calls.size
        socket.emit(inputDelta("Расскажи про гвоздь", 2_000, 2_500, "p1"))
        socket.emit(outputDelta("Гвоздь сплошной.", 2_700, 3_200, "o1"))
        socket.emit(liveDecision("o1", false, classified(SPOKEN_STEP, "2", 0.7)))
        shows(model, scene, NAIL)
        robot.waitUntil(5_000) { talk.follows.size == 1 }
        during(1_500) { assertEquals(scene.id, model.stageToolId); assertEquals(calls, talk.calls.size) }
    }

    /** K2: the scene item holds the title and each step with its sentence and no present step. Another scene deletes the old item first. */
    @Test fun theSceneItemHoldsTheStepsAndANewSceneReplacesIt() =
        live(StreamTalk(listOf(stepped(), Streamed(halves(STEPPED) + halves(OTHER_LINES.joinToString("\n")), gapMs = 30)))) { model, _ ->
            val scene = scene(model)
            val (create, text) = socket.itemCreates().single()
            assertEquals("На экране сцена «$SHIP_TITLE». Шаги сцены:\n" + STEPS.indices.joinToString("\n") { "${it + 1}. ${STEPS[it]}: ${SAYS[it]}" }, text)
            socket.emit(liveItemCreated(create, "item_1"))
            socket.say(SCREEN_REQUEST, "d2", 3_000)
            robot.waitUntil(60_000) { model.stageToolId.let { it != null && it != scene.id } && !model.tools.busy }
            robot.waitUntil(30_000) { socket.itemCreates().size == 2 }
            val items = socket.sent.map(::JSONObject).filter { it.getString("type").startsWith("decision.item.") }
            assertEquals(listOf("decision.item.create", "decision.item.delete", "decision.item.create"), items.map { it.getString("type") })
            assertEquals("item_1", items[1].getString("item_id"))
            assertTrue(socket.itemCreates()[1].second.contains(OTHER_SAYS[0]))
        }

    /** K2: with no stepped scene on the stage, the host sends no scene item, and a command goes to the routing call. */
    @Test fun withNoSteppedSceneAnswersAreIgnored() = live(StreamTalk(listOf(Streamed(halves(SAID), gapMs = 30)))) { model, talk ->
        socket.say(SCREEN_REQUEST, "d1", 100)
        shell.shown(model)
        robot.waitUntil(30_000) { thinkings().isNotEmpty() }
        command(SOME_NEXT, "p1", "d2", 3_000, classified(USER_COMMAND, "next", 0.95))
        robot.waitUntil(10_000) { routedOf(talk, SOME_NEXT) == 1 }
        assertTrue(socket.itemCreates().isEmpty())
    }

    /** C4: a change that turns a scene with no steps into a stepped scene walks from step 1, with no extra sentence of the change. */
    @Test fun aChangeIntoAStepsSceneWalksFromTheFirstStep() =
        live(StreamTalk(listOf(Streamed(halves(TALKING), gapMs = 30), Streamed(listOf("$STEPS_CONTROL\n") + NARRATION_LINES.map { "$it\n" }, gapMs = 0)))) { model, _ ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            val scene = shell.shown(model)
            assertTrue(thinkings().isEmpty())
            val count = shell.messages(model).size
            socket.say(TemplateTalk.CHANGE_REQUEST, "d2", 2_000)
            shell.waitForTheAnswer(model, count)
            robot.waitUntil(30_000) { thinkings().isNotEmpty() }
            assertEquals(listOf(MARK + SAYS[0]), thinkings())
            assertTrue(model.liveWalking)
            assertEquals(STEPS[0], shipSubject(shell.stored(model, scene.id)))
        }

    private fun live(talk: StreamTalk = StreamTalk(listOf(stepped())), test: (ShellViewModel, StreamTalk) -> Unit) =
        shell.live(talk, audio = audio) { _, model, _ -> test(model, talk) }

    private fun stepped() = Streamed(halves(STEPPED) + halves(NARRATION_LINES.joinToString("\n")), gapMs = 30)

    /** The spoken screen request on an empty stage: the stored scene, after its walk started and its scene item went to the classifier. */
    private fun scene(model: ShellViewModel): ToolRecord {
        socket.say(SCREEN_REQUEST, "d1", 100)
        val scene = shell.shown(model)
        robot.waitUntil(30_000) { thinkings().isNotEmpty() && socket.itemCreates().isNotEmpty() }
        assertEquals(listOf(MARK + SAYS[0]), thinkings())
        return scene
    }

    /** The person says [text] as the piece [piece], the classifier answers it with [answers], and GPT-Live hands it over as [id]. */
    private fun command(text: String, piece: String, id: String, atMs: Long, vararg answers: Triple<String, String, Double>) {
        socket.emit(inputDelta(text, atMs, atMs + 500, piece))
        socket.emit(liveDecision(piece, true, *answers))
        socket.emit(liveDelegation(id, atMs + 600))
    }

    /** The voice says [text] in one transcript piece with 600 ms of voiced audio of [marker]. */
    private fun speak(text: String, marker: Int, atMs: Long) {
        socket.emit(outputDelta(text, atMs, atMs + 600))
        socket.emit(outputAudio(marker, LIVE_RATE * 6 / 10))
    }

    /** The stored step of [scene] becomes [step] and stays it for 500 ms. */
    private fun shows(model: ShellViewModel, scene: ToolRecord, step: String) {
        robot.waitUntil(10_000) { shipSubject(shell.stored(model, scene.id)) == step }
        during(500) { assertEquals(step, shipSubject(shell.stored(model, scene.id))) }
    }

    private fun routedOf(talk: StreamTalk, text: String) = talk.routed.count { it.decisionContext?.originalUserTurn?.text == text }

    private fun thinkings() = socket.thinking().map { it.second }

    private companion object {
        const val GO_TO = "Покажи лодку"
        const val BACK = "Назад"
        const val REPEAT = "Повтори"
        const val SOME_NEXT = "Следующий"
        const val STEPS_CONTROL = "control = steps(\"$STEP_CONTROL\", [\"Корабль\", \"$NAIL\", \"Лодка\"], \"Корабль\")"
        val OTHER_SAYS = listOf("Корабль лёгкий для своего размера.", "$NAIL тяжёлый для своего размера.", "Лодка лёгкая и плавает.")
        val OTHER_LINES = OTHER_SAYS.mapIndexed { index, say -> "say${index + 1} = \"$say\"" }
        /** [TALKING], whose control is a choice, with one narration line. */
        val SAID get() = TALKING + "\nsay = \"Полый корабль вытесняет много воды и поэтому плавает.\""
    }
}
