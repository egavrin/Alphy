package com.alpha.assistant.ui

import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_REQUEST
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage lifetime and the work card of plan "one scene" (Task 45, decision 21): the real shell, kit, stage, bridge, and session with a
 * scripted chat and a scripted voice. The two tests of a waiting plan card are in [ShellLiveVoiceWorkflowTest], which has the plan fixture.
 */
class StageLifetimeRehearsalTest {
    private val fixture = ShellFixture("stage-lifetime")
    private val robot = ShellRobot("stage-lifetime")
    private val socket = FakeLiveTransport()
    private val audio = FakeLiveAudio()
    private val shell = TemplateShell(fixture, robot, socket)
    @After fun remove() = shell.remove()

    /**
     * While a hand-off searches, the stage shows «Ищу: » and the subject, cut at a word end to at most 48 characters, and no old scene. After
     * the answer with no app, the old scene does not come back, and the chat still holds it.
     */
    @Test fun aSearchShowsTheWorkCard() {
        val gate = CompletableDeferred<Unit>()
        val talk = SearchTalk(StreamTalk(listOf(Streamed(halves(SAID), gapMs = 30))), gate)
        shell.live(talk, audio = audio) { _, model, _ ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            val scene = shell.shown(model)
            socket.say(SEARCH_REQUEST, "d2", 3_000)
            val card = "Ищу: $SEARCH_SUBJECT"
            robot.waitUntil(10_000) { model.liveWorkCard == card }
            robot.waitUntil { stageHasText(robot, card) }
            assertTrue(SEARCH_SUBJECT.length <= 48)
            assertNull(shipCanvasTexts(robot))
            gate.complete(Unit)
            robot.waitUntil(30_000) { "d2" to "${com.alpha.assistant.voice.LiveProtocol.MARK}$FOUND" in socket.commentary() }
            robot.waitUntil { model.liveWorkCard == null }
            during(1_500) { assertNull(model.stageToolId); assertNull(shipCanvasTexts(robot)) }
            assertTrue(shell.messages(model).any { it.toolId == scene.id })
        }
    }

    /** After the screen of a hand-off starts with no frame, the stage shows «Собираю экран: » and the subject. The first preview frame replaces the card. */
    @Test fun aBuildShowsTheWorkCard() {
        val gate = CompletableDeferred<Unit>()
        val talk = StreamTalk(listOf(Streamed(halves(SAID), gapMs = 30, gateAt = 0, gate = gate)))
        shell.live(talk, audio = audio) { _, model, _ ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            val card = "Собираю экран: $SCREEN_REQUEST"
            robot.waitUntil(30_000) { model.liveWorkCard == card }
            robot.waitUntil { stageHasText(robot, card) }
            gate.complete(Unit)
            robot.waitUntil(30_000) { shipCanvasTexts(robot) != null }
            assertNull(model.liveWorkCard)
            assertFalse(stageHasText(robot, card))
        }
    }

    /** A scene that left the stage at a follow clear stays in the chat, and its card still opens it from there. */
    @Test fun aSceneThatLeftStaysInTheChat() {
        val talk = StreamTalk(listOf(Streamed(halves(SAID), gapMs = 30)))
        talk.follow = CloudEvent.Follow("clear", "", "")
        shell.live(talk, audio = audio) { _, model, _ ->
            socket.say(SCREEN_REQUEST, "d1", 100)
            val scene = shell.shown(model)
            socket.emit(com.alpha.assistant.voice.inputDelta("Хватит про корабли", 3_000, 3_500))
            socket.emit(com.alpha.assistant.voice.outputDelta("Хорошо.", 3_700, 4_000))
            robot.waitUntil(10_000) { model.stageToolId == null }
            val message = shell.messages(model).single { it.toolId == scene.id }
            assertEquals(scene.id, message.toolId)
            assertTrue(model.tools.tools.any { it.id == scene.id && !it.archived })
        }
    }

    /** A routing call for [SEARCH_REQUEST] reports a search that runs until [gate], then a found source and a spoken answer. Each other call goes to [inner]. */
    private class SearchTalk(private val inner: StreamTalk, private val gate: CompletableDeferred<Unit>) : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            if (request.follow || request.generationStage != null || request.decisionContext?.originalUserTurn?.text != SEARCH_REQUEST)
                return inner.respond(key, request, session, emit)
            emit(CloudEvent.Search("searching", emptyList()))
            gate.await()
            emit(CloudEvent.Search("found", listOf(TemplateTalk.FRESH_SOURCE)))
            emit(CloudEvent.RoundComplete(chatDecision("answer", FOUND), false))
        }
    }

    private companion object {
        const val SEARCH_REQUEST = "Найди свежие новости о кораблях, которые плавают во время сильного шторма"
        /** [SEARCH_REQUEST] cut at the last word end within 48 characters. */
        const val SEARCH_SUBJECT = "Найди свежие новости о кораблях, которые плавают"
        const val FOUND = "Корабли выдерживают шторм."
        /** [TALKING], whose control is a choice, with one narration line. */
        val SAID get() = TALKING + "\nsay = \"Полый корабль вытесняет много воды и поэтому плавает.\""
    }
}
