package com.alpha.assistant.ui

import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Message
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.FakeSpeechInput
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveVoiceSession
import com.alpha.assistant.voice.inputDelta
import com.alpha.assistant.voice.liveDelegation
import com.alpha.assistant.voice.outputDelta
import com.alpha.assistant.voice.VoiceSession
import com.alpha.assistant.voice.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-off bridge of spec 5.5 to 5.8: a scripted GPT-Live socket and scripted audio drive the real shell model. */
class ShellLiveVoiceWorkflowTest {
    private val fixture = ShellFixture("shell-live-voice")
    private val robot = ShellRobot("shell-live-voice")
    private val socket = FakeLiveTransport()
    private val audio = FakeLiveAudio()
    @After fun remove() = fixture.remove()

    @Test fun startPreloadsTheSelectedConversation() {
        fixture.store.saveConversation(Conversation("chat", "Чат", 1, 2, messages = listOf(
            Message("m1", "user", "Первый вопрос", 1, "complete"), Message("m2", "assistant", "Первый ответ", 2, "complete"))))
        fixture.store.seedOpenChat("chat")
        install()
        live { _, _ ->
            val input = JSONObject(socket.sent.first()).getJSONObject("session").getJSONArray("input")
            assertEquals(listOf("Первый вопрос", "Первый ответ"),
                (0 until input.length()).map { input.getJSONObject(it).getJSONArray("content").getJSONObject(0).getString("text") })
        }
    }

    @Test fun delegationSubmitsTheTranscriptAndSpeaksTheAnswer() {
        install()
        live { scenario, model ->
            scenario.onActivity { model.editDraft(Draft("Черновик")) }
            say("Какая погода", "d1", 100)
            robot.waitUntil { commentary().isNotEmpty() }
            assertEquals("Какая погода", messages(model).first().text)
            assertEquals(messages(model).single { it.role == "assistant" }.id, model.liveVoiceCardId)
            assertTrue(checkNotNull(fixture.provider.lastRequest).search)
            // spoken() ends a reply without a sentence end with a period.
            assertEquals(listOf("d1" to "Ответ Alphy: Fixture response."), commentary())
            // The text that the user typed on the voice screen stays in the composer.
            assertEquals("Черновик", model.snapshot.draft.text)
        }
    }

    @Test fun blankDelegationAsksAgain() {
        install()
        live { _, model ->
            socket.emit(liveDelegation("d1", 700))
            robot.waitUntil { commentary().isNotEmpty() }
            assertEquals(listOf("d1" to "Alphy не расслышал запрос."), commentary())
            assertTrue(model.snapshot.conversations.isEmpty())
            assertEquals(0, fixture.provider.calls)
        }
    }

    @Test fun miniAppSendsProgressThenReady() {
        fixture.provider.createApp = true
        install()
        live { _, model ->
            say("Сделай счётчик", "d1", 100)
            robot.waitUntil(60_000) { commentary().size == 2 }
            assertEquals(listOf("d1" to "Alphy готовит мини-приложение, оно появится на экране.",
                "d1" to "Мини-приложение готово. Ответ Alphy: Fixture response."), commentary())
            assertNotNull(messages(model).last().toolId)
        }
    }

    @Test fun failureIsSpoken() {
        fixture.provider.failure = CloudFailure("x", "Сбой")
        install()
        live { _, model ->
            say("Какая погода", "d1", 100)
            robot.waitUntil { commentary().isNotEmpty() }
            assertEquals(listOf("d1" to "Alphy не смог выполнить запрос: Сбой"), commentary())
            assertEquals("error", messages(model).last().status)
        }
    }

    @Test fun twoDelegationsAnswerTheirOwnIds() {
        val gate = CompletableDeferred<Unit>()
        install(chat = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                gate.await()
                val asked = checkNotNull(request.decisionContext).originalUserTurn.text
                emit(CloudEvent.RoundComplete(chatDecision("answer", "Ответ на: $asked"), false))
            }
        })
        live { _, model ->
            say("Первый", "d1", 100)
            say("Второй", "d2", 1000)
            robot.waitUntil { messages(model).count { it.role == "assistant" } == 2 }
            val (first, second) = messages(model).filter { it.role == "assistant" }
            assertEquals("queued", second.status)
            assertEquals(second.id, model.liveVoiceCardId)
            assertTrue(model.liveVoiceWorking)
            gate.complete(Unit)
            robot.waitUntil { commentary().size == 2 }
            assertEquals(setOf("d1" to "Ответ Alphy: Ответ на: Первый.", "d2" to "Ответ Alphy: Ответ на: Второй."), commentary().toSet())
            assertEquals(listOf(first.id, second.id), messages(model).filter { it.role == "assistant" && it.status == "complete" }.map { it.id })
            assertFalse(model.liveVoiceWorking)
        }
    }

    @Test fun connectionLossKeepsTheRunningRequest() = endKeepsTheRequest(R.string.live_voice_connection) { _, _ -> socket.closeRemote() }

    @Test fun endDoesNotCancelTheRequest() = endKeepsTheRequest(null) { scenario, model -> scenario.onActivity { model.endLiveVoice() } }

    @Test fun smallTalkIsNotSaved() {
        install()
        live { _, model ->
            socket.emit(inputDelta("Как дела?", 100, 600))
            // The output transcript ends the user turn at once, so the host gets it before the barrier below.
            socket.emit(outputDelta("Хорошо, спасибо!", 800, 1500))
            robot.instrumentation.runOnMainSync { }
            assertTrue(model.snapshot.conversations.isEmpty())
            assertEquals(0, fixture.provider.calls)
        }
    }

    @Test fun typedTurnGoesToTheAgentAndThinking() {
        install()
        live { scenario, model ->
            scenario.onActivity { model.editDraft(Draft("Привет")); model.sendLiveTyped() }
            robot.waitUntil { socket.types().contains("session.thinking.append") }
            assertEquals("Привет", messages(model).first().text)
            assertEquals("complete", messages(model).last().status)
            assertEquals(messages(model).last().id, model.liveVoiceCardId)
            val thinking = socket.sent.map(::JSONObject).single { it.getString("type") == "session.thinking.append" }
            assertEquals("Пользователь написал: Привет. Ответ Alphy на экране: Fixture response.", thinking.getString("content"))
            assertTrue(commentary().isEmpty())
        }
    }

    @Test fun refinementAsksForTheScreen() {
        ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { tools ->
            listOf("a", "b").forEach { id ->
                val spec = ToolSpec.parse(FIXTURE_APP)
                tools.saveTool(ToolRecord(id, 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat", true), true)
            }
        }
        fixture.store.saveConversation(Conversation("chat", "Чат", 1, 1, messages = listOf("a", "b").map {
            Message("card-$it", "assistant", "", 1, "complete", toolId = it, toolRevision = 1)
        }))
        fixture.store.seedOpenChat("chat")
        // Two apps in the chat and a change request without a target: Alphy asks which app to change (AutomaticToolWorkflowTest).
        install(chat = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                emit(CloudEvent.RoundComplete(chatDecision("refine", "Готово.", "Change the counter"), false))
        })
        live { _, model ->
            say("Измени приложение", "d1", 100)
            robot.waitUntil { commentary().isNotEmpty() }
            assertEquals(listOf("d1" to "Нужно подтверждение на экране."), commentary())
            assertEquals(2, messages(model).last().refinementChoices.size)
        }
    }

    @Test fun providerChangeEndsTheMode() {
        install()
        live { scenario, model ->
            robot.waitUntil { model.accountProvider == AiProvider.OPENAI }
            // The key panel saves a DeepSeek key, so accountProvider becomes DEEPSEEK.
            scenario.onActivity { model.saveKey(fakeDeepSeekKey(), AiProvider.DEEPSEEK) {} }
            robot.waitUntil { !model.liveVoice.active }
            assertEquals(robot.text(R.string.live_voice_openai_only), model.liveVoice.status)
        }
    }

    @Test fun memoryFactEndsWithContinueOnScreen() = endsOnScreen("Запомни: я предпочитаю краткие ответы",
        { fixture.provider.memoryFact = "я предпочитаю краткие ответы" }) { it.memoryPage.reviewingChatFact }

    @Test fun automationDraftEndsWithContinueOnScreen() = endsOnScreen("Каждый вечер в 20:00 напоминай полить растения", {
        fixture.provider.automationDraft = """
            {"name":"Полив","trigger":{"type":"schedule","repeat":"daily","time":"20:00","zone":"device","exact":true},
            "conditions":{},"actions":[{"id":"task","capability":"agent.run","args":{"instruction":"Напомни полить растения."}}]}
        """.trimIndent()
    }) { it.chatAutomationSetup != null }

    @Test fun courseTopicEndsWithContinueOnScreen() = endsOnScreen("Научи меня испанскому языку",
        { fixture.provider.courseTopic = "Испанский язык" }) { it.courseSetup != null }

    @Test fun liveAndContinuousVoiceExclude() {
        val input = FakeSpeechInput { awaitCancellation() }
        install(voice = { app, scope, transcript -> VoiceSession(app, scope, transcript, VoiceSettings(app, fixture.voiceStore)) { input } })
        fixture.launch().use { scenario ->
            robot.allowMicrophone(scenario)
            val model = checkNotNull(fixture.model)
            robot.waitUntil { !model.loading }
            scenario.onActivity { model.startVoice(oneShot = false) }
            robot.waitUntil { model.voice.active && input.captures.get() == 1 }
            scenario.onActivity { model.startLiveVoice() }
            assertFalse(model.voice.active)
            assertTrue(model.liveVoice.active)
            scenario.onActivity { model.startComposerVoice(oneShot = false, destination = null) }
            assertFalse(model.liveVoice.active)
            robot.waitUntil { model.voice.active }
        }
    }

    /** A hand-off runs to its end after the mode ends by [end] with the status [status] (spec 5.5.9). */
    private fun endKeepsTheRequest(status: Int?, end: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) {
        fixture.provider.gate = CompletableDeferred()
        install()
        live { scenario, model ->
            say("Какая погода", "d1", 100)
            robot.waitUntil { fixture.provider.calls == 1 }
            end(scenario, model)
            robot.waitUntil { !model.liveVoice.active }
            assertEquals(status?.let { robot.text(it) }.orEmpty(), model.liveVoice.status)
            checkNotNull(fixture.provider.gate).complete(Unit)
            robot.waitUntil { messages(model).last().status == "complete" }
            assertEquals("Fixture response", messages(model).last().text)
            assertTrue(commentary().isEmpty())
        }
    }

    /** A hand-off that opens an editor or a sheet in the chat ends the mode with «Продолжите на экране» (spec 5.5.8). */
    private fun endsOnScreen(request: String, setup: () -> Unit, opened: (ShellViewModel) -> Boolean) {
        setup()
        install()
        live { _, model ->
            say(request, "d1", 100)
            robot.waitUntil { !model.liveVoice.active }
            assertEquals(robot.text(R.string.live_voice_on_screen), model.liveVoice.status)
            assertTrue(opened(model))
            assertTrue(commentary().isEmpty())
        }
    }

    private fun install(chat: ChatProvider = fixture.provider, voice: VoiceFactory? = null) = fixture.install(chat = chat, voice = voice,
        liveVoice = { app, scope -> LiveVoiceSession(app, scope, socket, { audio }, { fixture.keys.account().getOrNull() }, LOOPBACK) })

    /** Launches the shell, starts the voice mode, and answers `session.started`. */
    private fun live(test: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) = fixture.launch().use { scenario ->
        val model = checkNotNull(fixture.model)
        robot.waitUntil { !model.loading }
        scenario.onActivity { model.startLiveVoice() }
        robot.waitUntil { socket.connected }
        socket.emit(LIVE_STARTED)
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
        test(scenario, model)
    }

    /** The user says [text] at [atMs] of the session timeline, and GPT-Live hands it to Alphy as [id]. */
    private fun say(text: String, id: String, atMs: Long) {
        socket.emit(inputDelta(text, atMs, atMs + 500))
        socket.emit(liveDelegation(id, atMs + 600))
    }

    /** The sent commentary appends as (delegation id, text), in order. */
    private fun commentary() = socket.sent.map(::JSONObject).filter { it.getString("type") == "session.commentary.append" }
        .map { it.getString("delegation_id") to it.getString("content") }

    private fun messages(model: ShellViewModel) = model.snapshot.selected?.messages.orEmpty()

    private companion object {
        val LOOPBACK = Endpoints { "http://127.0.0.1:4000" }
    }
}
