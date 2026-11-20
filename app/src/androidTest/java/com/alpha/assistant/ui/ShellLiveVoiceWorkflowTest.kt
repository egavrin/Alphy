package com.alpha.assistant.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.agent.AgentPhase
import com.alpha.assistant.agent.AgentPlan
import com.alpha.assistant.agent.PhoneActionEvidence
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.MessageProvenance
import com.alpha.assistant.data.ScreenContext
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.surfaces.AssistContext
import com.alpha.assistant.surfaces.AssistantCapture
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.agentReply
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.FakeSpeechInput
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.inputDelta
import com.alpha.assistant.voice.liveDelegation
import com.alpha.assistant.voice.outputAudio
import com.alpha.assistant.voice.outputDelta
import com.alpha.assistant.voice.VoiceSession
import com.alpha.assistant.voice.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The hand-off bridge of spec 5.5 to 5.8: a scripted GPT-Live socket and scripted audio drive the real shell model. */
class ShellLiveVoiceWorkflowTest {
    private val fixture = ShellFixture("shell-live-voice")
    private val robot = ShellRobot("shell-live-voice")
    private val socket = FakeLiveTransport()
    private val audio = FakeLiveAudio()
    private val memory = "${fixture.store.databaseName}-memory.db"
    @After fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase(memory)
        fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
    }

    @Test fun startPreloadsTheSelectedConversation() {
        fixture.store.saveConversation(Conversation("chat", "Чат", 1, 2, messages = listOf(
            // A sent request and its answer, as the chat saves them (ruling R13).
            Message("m1", "user", "Первый вопрос", 1, "sent"), Message("m2", "assistant", "Первый ответ", 2, "complete"))))
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
            socket.say("Какая погода", "d1", 100)
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

    /** The hand-off of a create request to the agent loop. [miniAppHandOffGetsThePlanStep] is the same hand-off with the plan step. */
    @Test fun miniAppSendsProgressThenReady() = withPlanStep(fixture.context, AiProvider.OPENAI, false) {
        fixture.provider.createApp = true
        install()
        live { _, model ->
            socket.say("Сделай счётчик", "d1", 100)
            robot.waitUntil(60_000) { commentary().size == 2 }
            assertEquals(listOf("d1" to "Alphy готовит мини-приложение, оно появится на экране.",
                "d1" to "Мини-приложение готово. Ответ Alphy: Fixture response."), commentary())
            assertNotNull(messages(model).last().toolId)
        }
    }

    /** A hand-off of a create request takes the chat path, so with the switch on it gets one plan call and no agent call (loop plan, task 7). */
    @Test fun miniAppHandOffGetsThePlanStep() {
        val script = AgentScriptProvider(listOf(Scripted.Text(appFilePlan("calc"))))
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            install(chat = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                    if (request.generationStage != null) script.respond(key, request, session, emit)
                    else emit(CloudEvent.RoundComplete(chatDecision("create", "Готово.", "Make a tip calculator"), false))
            })
            live { _, model ->
                socket.say("Сделай калькулятор чаевых", "d1", 100)
                robot.waitUntil(60_000) { commentary().size == 2 }
                assertEquals(listOf("d1" to "Alphy готовит мини-приложение, оно появится на экране.",
                    "d1" to "Мини-приложение готово. Ответ Alphy: Готово."), commentary())
                assertEquals(listOf(CloudGenerationStage.APP_FILE), script.calls.map { it.generationStage })
                val card = checkNotNull(messages(model).last().toolId)
                // The library list of the controller refreshes after the card.
                var saved: ToolRecord? = null
                robot.waitUntil { robot.instrumentation.runOnMainSync { saved = model.tools.tools.singleOrNull { it.id == card } }; saved != null }
                assertEquals(appFilePlan("calc"), ToolSpec.parsePersisted(checkNotNull(saved).spec).appFile)
            }
        }
    }

    /** Plan D, Db1: a spoken request with the plan step on can get the kind screen. With the switch off, or typed, it cannot. */
    @Test fun voiceHandOffMarksTheRequest() = withPlanStep(fixture.context, AiProvider.OPENAI, true) {
        install()
        live { scenario, model ->
            socket.say("Как работает сердце", "d1", 100)
            robot.waitUntil { commentary().size == 1 }
            assertTrue(checkNotNull(fixture.provider.lastRequest).voiceScreen)
            scenario.onActivity { model.editDraft(Draft("Привет")); model.sendLiveTyped() }
            robot.waitUntil { socket.types().contains("session.thinking.append") }
            assertFalse(checkNotNull(fixture.provider.lastRequest).voiceScreen)
            withPlanStep(fixture.context, AiProvider.OPENAI, false) {
                socket.say("Как работает печень", "d2", 3_000)
                robot.waitUntil { commentary().size == 2 }
                assertFalse(checkNotNull(fixture.provider.lastRequest).voiceScreen)
            }
        }
    }

    /** Plan D, Db1: a screen takes the create path, and its plan call gets the dialogue text of the prompt after the request line. */
    @Test fun voiceScreenBuildsAnApp() {
        val script = AgentScriptProvider(listOf(Scripted.Text(appFilePlan("calc"))))
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            install(chat = stageProvider("calc", script = script))
            live { _, model ->
                socket.say(SCREEN_REQUEST, "d1", 100)
                robot.waitUntil(60_000) { messages(model).lastOrNull()?.let { it.role == "assistant" && it.status !in unfinishedStatuses } == true }
                val message = messages(model).last()
                assertEquals("complete", message.status)
                assertEquals(listOf(CloudGenerationStage.APP_FILE), script.calls.map { it.generationStage })
                val dialogue = AppFilePrompt.load(fixture.context).dialogue
                assertTrue(script.calls.single().turns.single().text.startsWith("User request: $SCREEN_REQUEST\n$dialogue\nWidget brief: $SCREEN_BRIEF"))
                robot.waitUntil { model.stageToolId != null }
                assertEquals(message.toolId, model.stageToolId)
            }
        }
    }

    /** Plan D, Db1, review focus 1: a phone action by voice stays a phone plan that waits for a tap, also when the screen kind is offered. */
    @Test fun timerByVoiceIsStillAPlan() = withPlanStep(fixture.context, AiProvider.OPENAI, true) {
        val routed = CopyOnWriteArrayList<CloudRequest>()
        fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence(TIMER_REQUEST, "таймер"))
        fixture.provider.agentProposal = """{"steps":[{"function":"clock.setTimer","args":{"seconds":600}}]}"""
        install(chat = routing(routed))
        live { _, model ->
            val plan = proposed(model, TIMER_REQUEST, "d1", 100)
            assertTrue(routed.single().voiceScreen)
            assertEquals(AgentPhase.REVIEW, plan.phase)
            assertNull(messages(model).last().toolId)
            assertEquals(listOf("d1" to ON_SCREEN), commentary())
        }
    }

    /** Plan D, Db1, ruling OD-D4: a spoken request with the screen kind gets no course decision, so the mode goes on. */
    @Test fun courseRequestByVoiceKeepsTheMode() = withPlanStep(fixture.context, AiProvider.OPENAI, true) {
        val routed = CopyOnWriteArrayList<CloudRequest>()
        install(chat = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                routed += request
                // The routing call offers the course decision only when the request allows it.
                emit(if (request.allowCourseDecision) CloudEvent.RoundComplete("", false, MessageProvenance.CLEAN, courseTopic = "Испанский язык")
                    else CloudEvent.RoundComplete(chatDecision("answer", "Fixture response"), false))
            }
        })
        live { _, model ->
            socket.say("Научи меня испанскому языку", "d1", 100)
            robot.waitUntil { commentary().isNotEmpty() || !model.liveVoice.active }
            assertFalse(routed.first().allowCourseDecision)
            assertTrue(model.liveVoice.active)
            assertNull(model.courseSetup)
        }
    }

    /** Plan D, Db1, review focus 4: the first hand-off of a press carries the screen, so it gets no screen kind. A later one does. */
    @Test fun noScreenKindWithAScreenshot() = withPlanStep(fixture.context, AiProvider.OPENAI, true) {
        install()
        val press = beginCapture(ScreenContext(visibleText = "Screen"))
        try {
            live(press = press) { _, model ->
                socket.say("Что на экране?", "d1", 100)
                robot.waitUntil { commentary().size == 1 }
                val first = checkNotNull(fixture.provider.lastRequest)
                assertNotNull(first.screenContext)
                assertFalse(first.voiceScreen)
                socket.say("Как работает сердце", "d2", 3_000)
                robot.waitUntil { commentary().size == 2 }
                assertTrue(checkNotNull(fixture.provider.lastRequest).voiceScreen)
                // The message owns its screenshot file, and the fixture removes only the databases.
                messages(model).flatMap { it.attachments }.filter { it.automaticScreen }
                    .forEach { AssistContext.mediaFile(fixture.context, Uri.parse(it.uri)).delete() }
            }
        } finally {
            robot.instrumentation.runOnMainSync { AssistantCapture.clear(press) }
        }
    }

    /** Plan D, Db2: the voice says the answer of a screen when the routing call ends, before the plan call. The app goes to the stage with no line. */
    @Test fun aScreenSpeaksTheAnswerBeforeTheScreen() = gatedRequest { _, model, gate ->
        robot.awaitFrames(5)
        assertEquals(listOf("d1" to SCREEN_ANSWER), commentary())
        gate.complete(Unit)
        robot.waitUntil(60_000) { model.stageToolId != null }
        assertEquals(messages(model).last().toolId, model.stageToolId)
        robot.awaitFrames(5)
        assertEquals(listOf("d1" to SCREEN_ANSWER), commentary())
    }

    /** Plan D, Db2: a screen with no app makes only the plan calls, keeps the stage, shows no error, and the voice says only the answer. */
    @Test fun aFallbackScreenRunsNoLoopAndStaysSilent() = fallbackScreen(show = false, SCREEN_ANSWER)

    /** Plan D, Db2: a screen with no app that the person asked to see gets one line after the answer. */
    @Test fun aFallbackOfAShowRequestSaysOneLine() = fallbackScreen(show = true, SCREEN_ANSWER, SCREEN_FAILED)

    /** Plan D, Db2: a stop of a screen run stays a stop. The voice says the stop after the answer, and the mode goes on. */
    @Test fun aStopOfAScreenStaysAStop() = gatedRequest(show = true) { scenario, model, gate ->
        scenario.onActivity { model.cancelRequest() }
        robot.waitUntil { commentary().size == 2 }
        val message = messages(model).last()
        assertEquals("cancelled", message.status)
        assertNull(message.toolId)
        assertEquals(listOf("d1" to SCREEN_ANSWER, "d1" to "Alphy не смог выполнить запрос: Запрос остановлен."), commentary())
        assertTrue(model.liveVoice.active)
        gate.complete(Unit)
        robot.awaitFrames(5)
        assertEquals(2, commentary().size)
        assertNull(model.stageToolId)
    }

    /** Plan D, Da1: the app of a hand-off fills the stage, with no card around it. */
    @Test fun stageShowsTheMiniAppOfAHandOff() = onStage { scenario, model, app ->
        assertEquals(messages(model).last().toolId, app)
        robot.waitUntil { inStage(scenario, CALC_LABEL) }
        assertFalse(inStage(scenario, robot.text(R.string.tool_open)))
    }

    /** Plan D, Da1: the stage draws the drawing of an app with its canvas host, not as a plain text. */
    @Test fun stageDrawsTheDrawingOfAHandOff() = onStage(plan = "lesson") { scenario, _, _ ->
        robot.waitUntil(30_000) { robot.composedIn(scenario, STAGE) {
            it.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("$ORBIT.") } == true
        } }
    }

    /** Plan D, Da1: an answer with no app leaves the app on the stage, and the voice retells the answer. */
    @Test fun aLaterAnswerKeepsTheScreen() = onStage { scenario, model, app ->
        robot.waitUntil { inStage(scenario, CALC_LABEL) }
        socket.say("Какая погода", "d2", 2_000)
        robot.waitUntil { "d2" to "Ответ Alphy: Fixture response." in commentary() }
        assertEquals(app, model.stageToolId)
        assertTrue(inStage(scenario, CALC_LABEL))
        assertFalse(inStage(scenario, "Fixture response"))
    }

    /** Plan D, Da1: the app of the stage is a stored app of the chat, so the chat shows it after the end. */
    @Test fun theStageScreenIsInTheChatAfterTheEnd() = onStage { scenario, model, app ->
        scenario.onActivity { model.endLiveVoice() }
        assertNull(model.stageToolId)
        robot.waitUntil { !robot.composed(scenario, "live_voice_layer") }
        assertTrue(messages(model).any { it.toolId == app })
        robot.waitFor(robot.text(R.string.tool_open))
    }

    /** Plan D, Da1: each start of the mode begins with an empty stage. */
    @Test fun aNewSessionStartsWithAnEmptyStage() = onStage { scenario, model, _ ->
        scenario.onActivity { model.endLiveVoice() }
        restart(scenario, model)
        assertNull(model.stageToolId)
        robot.waitUntil { robot.composedIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.TestTag) == CHARACTER } }
    }

    /** Plan D, Da1: an app that a request makes after the end of the mode does not go to the stage of a later session. */
    @Test fun aRequestThatEndsAfterTheModeSetsNoStage() = gatedRequest(APP_REQUEST) { scenario, model, gate ->
        scenario.onActivity { model.endLiveVoice() }
        gate.complete(Unit)
        robot.waitUntil(60_000) { messages(model).last().toolId != null }
        val app = messages(model).last().toolId
        // The stage reads only an app of the library list, so the test waits for it there.
        robot.waitUntil { model.tools.tools.any { it.id == app } }
        assertNull(model.stageToolId)
        restart(scenario, model)
        robot.awaitFrames(5)
        assertNull(model.stageToolId)
    }

    @Test fun failureIsSpoken() {
        fixture.provider.failure = CloudFailure("x", "Сбой")
        install()
        live { _, model ->
            socket.say("Какая погода", "d1", 100)
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
            socket.say("Первый", "d1", 100)
            socket.say("Второй", "d2", 1000)
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
            val thinking = typedThinking(scenario, model, "Привет")
            assertEquals("Привет", messages(model).first().text)
            assertEquals("complete", messages(model).last().status)
            assertEquals(messages(model).last().id, model.liveVoiceCardId)
            assertEquals("Пользователь написал: Привет. Ответ Alphy: Fixture response.", thinking)
            assertTrue(commentary().isEmpty())
        }
    }

    /** A typed message that made an app gets the same retell text as a hand-off with an app. */
    @Test fun typedAppTurnSaysTheAppIsReady() = withPlanStep(fixture.context, AiProvider.OPENAI, true) {
        install(chat = stageProvider("calc"))
        live { scenario, model ->
            assertEquals("Пользователь написал: $APP_REQUEST. Мини-приложение готово. Ответ Alphy: Готово.",
                typedThinking(scenario, model, APP_REQUEST))
        }
    }

    /** Sends [text] as a typed message of the voice mode and returns the content of its single thinking append. */
    private fun typedThinking(scenario: ActivityScenario<CloudTestActivity>, model: ShellViewModel, text: String): String {
        scenario.onActivity { model.editDraft(Draft(text)); model.sendLiveTyped() }
        robot.waitUntil(60_000) { socket.types().contains("session.thinking.append") }
        return socket.sent.map(::JSONObject).single { it.getString("type") == "session.thinking.append" }.getString("content")
    }

    @Test fun refinementAsksForTheScreen() = refinement { _, model ->
        assertEquals(listOf("d1" to "Нужно подтверждение на экране."), commentary())
        assertEquals(2, messages(model).last().refinementChoices.size)
    }

    /** A choice on the card runs the same message again, and its new result goes to the same delegation (ruling R14). */
    @Test fun refinementChoiceSpeaksTheNewResult() = refinement { scenario, model ->
        scenario.onActivity { model.chooseRefinementTarget(messages(model).last().id, "a") }
        robot.waitUntil(60_000) { commentary().size == 3 }
        assertEquals(listOf("d1" to "Нужно подтверждение на экране.", "d1" to "Alphy готовит мини-приложение, оно появится на экране.",
            "d1" to "Мини-приложение готово. Ответ Alphy: Готово."), commentary())
        assertEquals("a", messages(model).last().toolId)
    }

    /** Two apps in the chat and a change request without a target: Alphy asks which app to change (AutomaticToolWorkflowTest). */
    private fun refinement(test: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) {
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
        install(chat = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                // The app agent of the chosen refinement writes a changed app.
                if (request.generationStage != null) emit(CloudEvent.Text(request.agentReply(FIXTURE_APP.replace("\"Fixture\"", "\"Fixture Plus\""))))
                else emit(CloudEvent.RoundComplete(chatDecision("refine", "Готово.", "Change the counter"), false))
        })
        live { scenario, model ->
            socket.say("Измени приложение", "d1", 100)
            robot.waitUntil { commentary().isNotEmpty() }
            test(scenario, model)
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

    /** A chat change ends the mode without a status, as it stops the continuous voice session (ruling R15). */
    @Test fun selectionChangeEndsTheMode() {
        fixture.seedChat("a", "Первый", "Привет")
        install()
        live { scenario, model ->
            listOf<(ShellViewModel) -> Unit>({ it.select("a") }, { it.deleteConversation("a") }, { it.clearHistory() }).forEach { change ->
                scenario.onActivity { model.startLiveVoice() }
                assertTrue(model.liveVoice.active)
                scenario.onActivity { change(model) }
                assertFalse(model.liveVoice.active)
                assertEquals("", model.liveVoice.status)
            }
        }
    }

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

    /** Spec 5.5.10: the proposal goes out, and a «да» after GPT-Live has spoken it runs the same plan revision. */
    @Test fun reviewPlanIsReadAndYesRunsIt() = reviewed { _, model, review ->
        assertEquals(AgentPhase.REVIEW, review.phase)
        // No delegation follows this turn, so it ends after the turn gap, and the plan's hand-off gets «Запускаю.».
        socket.emit(inputDelta("да", 6_000, 6_400))
        robot.waitUntil { model.agent.plan?.phase == AgentPhase.COMPLETED }
        assertEquals(review.id to review.revision, checkNotNull(model.agent.plan).let { it.id to it.revision })
        assertEquals(listOf("Favorite color is green"), MemoryStore(fixture.context, memory).use { it.list().map { fact -> fact.text } })
        robot.waitUntil { commentary().size == 3 }
        assertEquals(listOf("d1" to PROPOSAL_ADD, "d1" to STARTED, "d1" to DONE), commentary())
    }

    @Test fun noDismissesThePlan() = reviewed { _, model, _ ->
        socket.say("нет", "d2", 6_000)
        robot.waitUntil { commentary().size == 2 }
        assertEquals(listOf("d1" to PROPOSAL_ADD, "d2" to CANCELLED), commentary())
        assertNull(model.agent.plan)
        assertEquals(listOf(PLAN_REQUEST), messages(model).filter { it.role == "user" }.map { it.text })
    }

    /** Spec 5.5.10 rule 6: a longer turn is a normal hand-off, and the plan waits for its own approval. */
    @Test fun otherTurnBecomesAHandOffAndRunsNothing() = reviewed { _, model, review ->
        fixture.provider.devicePlanSteps = null
        socket.say("да, но на десять минут", "d2", 6_000)
        robot.waitUntil { commentary().size == 2 }
        assertEquals(listOf("d1" to PROPOSAL_ADD, "d2" to "Ответ Alphy: Fixture response."), commentary())
        assertEquals(listOf(PLAN_REQUEST, "да, но на десять минут"), messages(model).filter { it.role == "user" }.map { it.text })
        assertEquals(review, model.agent.plan)
    }

    /** Spec 5.5.10 rules 3, 5, and 8. Only a clean «да» in the window of the current revision runs the plan. */
    @Test fun overlapLateOrChangedRevisionRunsNothing() = reviewed { scenario, model, first ->
        audio.playbackActive = true
        answer("да", 6_000)
        audio.playbackActive = false
        // The session marks a turn as overlapped when it saw playback within the turn length (400 ms) before its delta.
        SystemClock.sleep(600)
        // `execute` sets `busy` at once and changes `plan` only after an IO hop, so each negative case checks both.
        assertFalse(model.agent.busy)
        assertEquals(first, model.agent.plan)
        scenario.onActivity { model.agent.revise(first, "Use green") }
        robot.waitUntil(60_000) { commentary().size == 2 }
        val second = checkNotNull(model.agent.plan)
        assertEquals(first.id to first.revision + 1, second.id to second.revision)
        // Inside the window of the first revision, [5,000, 35,000) ms, which the revision closed.
        answer("да", 7_000)
        assertFalse(model.agent.busy)
        assertEquals(second, model.agent.plan)
        assertEquals(listOf("d1" to PROPOSAL_ADD, "d1" to PROPOSAL_ADD), commentary())
        spoken(8_000)
        answer("да", 38_000)
        assertFalse(model.agent.busy)
        assertEquals(second, model.agent.plan)
        assertEquals(2, commentary().size)
        // ApprovalWindow reads only the start time, so this turn is still the first one inside [8,000, 38,000) ms.
        // No run is in flight here, so only this «да» can start the run and send «Запускаю.».
        answer("да", 9_000)
        robot.waitUntil { model.agent.plan?.phase == AgentPhase.COMPLETED }
        assertEquals(second.revision, checkNotNull(model.agent.plan).revision)
        robot.waitUntil { commentary().size == 4 }
        assertEquals(listOf("d1" to PROPOSAL_ADD, "d1" to PROPOSAL_ADD, "d1" to STARTED, "d1" to DONE), commentary())
    }

    /** Spec 5.5.10 rule 7. */
    @Test fun approvalDelegationSubmitsNoMessage() = reviewed { _, model, _ ->
        socket.say("да", "d2", 6_000)
        robot.waitUntil { commentary().size == 3 }
        assertEquals(listOf("d1" to PROPOSAL_ADD, "d2" to STARTED, "d1" to DONE), commentary())
        assertEquals(listOf(PLAN_REQUEST, robot.text(R.string.agent_plan_ready)), messages(model).map { it.text })
    }

    /** Spec 5.5.10 rule 7, when the turn gap ended the «да» turn before GPT-Live opened its delegation. */
    @Test fun lateApprovalDelegationSubmitsNoMessage() = reviewed { _, model, _ ->
        socket.emit(inputDelta("да", 6_000, 6_400))
        robot.waitUntil { commentary().size == 3 }
        socket.emit(liveDelegation("d2", 6_600))
        robot.waitUntil { commentary().size == 4 }
        assertEquals(listOf("d1" to PROPOSAL_ADD, "d1" to STARTED, "d1" to DONE, "d2" to STARTED), commentary())
        assertEquals(listOf(PLAN_REQUEST, robot.text(R.string.agent_plan_ready)), messages(model).map { it.text })
    }

    /** Ruling R16: the proposal reads the card details of each step, and «; » separates the steps. */
    @Test fun proposalSpeaksTheCardDetails() {
        // Two steps of 500-character facts fit in one append (1,181 characters).
        val facts = listOf("A", "B").map { it.repeat(500) }
        reviewed(prepare = { writes(facts) }) { _, _, _ ->
            val proposal = commentary().single().second
            assertEquals("Alphy предлагает: ${added(facts[0])}; ${added(facts[1])}.$ASK", proposal)
            assertTrue(proposal.length <= 1_200)
        }
    }

    /** Review I1: a step list that does not fit in one append needs the screen, so a «да» cannot run steps the user did not hear. */
    @Test fun longPlanNeedsTheScreen() = reviewed(prepare = { writes(listOf("A", "B", "C").map { it.repeat(500) }) }) { _, model, review ->
        assertEquals(AgentPhase.REVIEW, review.phase)
        answer("да", 6_000)
        assertFalse(model.agent.busy)
        assertEquals(review, model.agent.plan)
        SystemClock.sleep(500)
        assertEquals(listOf("d1" to ON_SCREEN), commentary())
    }

    /** Spec 5.5.10 rule 10. */
    @Test fun withoutEchoCheckOnlyTapRuns() = reviewed(voiceApproval = false) { scenario, model, review ->
        assertEquals(listOf("d1" to ON_SCREEN), commentary())
        answer("да", 6_000)
        assertFalse(model.agent.busy)
        assertEquals(review, model.agent.plan)
        scenario.onActivity { model.agent.execute(review) }
        robot.waitUntil { commentary().size == 2 }
        assertEquals(listOf("d1" to ON_SCREEN, "d1" to DONE), commentary())
    }

    /** Spec 5.5.8: the end of each run goes to the hand-off of its plan once. A tap runs each plan. */
    @Test fun endPhasesAreSpoken() {
        val fact = MemoryStore(fixture.context, memory).use { it.saveExplicitFact("Favorite color is blue") }
        install()
        live { scenario, model ->
            plan(EDIT)
            val stale = proposed(model, PLAN_REQUEST, "d1", 100)
            // The target changes after the review, so the run fails (AgentWorkflowTest.staleTargetCannotWriteMemory).
            MemoryStore(fixture.context, memory).use { it.edit(fact.id, fact.revision, "Favorite color is purple") }
            scenario.onActivity { model.agent.execute(stale) }
            robot.waitUntil { model.agent.plan?.phase == AgentPhase.FAILED }
            val failed = "Не получилось. " + checkNotNull(model.agent.plan).results.last()
            plan(ADD)
            val add = proposed(model, PLAN_REQUEST, "d2", 1_000)
            scenario.onActivity { model.agent.execute(add) }
            robot.waitUntil { model.agent.plan?.phase == AgentPhase.COMPLETED }
            fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence("Set a five-minute timer", "five-minute"),
                PhoneActionEvidence("change my memory of my favorite color to green", "favorite color"))
            fixture.provider.agentProposal = """{"steps":[{"function":"clock.setTimer","args":{"seconds":300}},""" +
                """{"function":"memory.write","args":{"text":"Favorite color is green"}}]}"""
            val timer = proposed(model, "Set a five-minute timer and change my memory of my favorite color to green", "d3", 2_000)
            // The Clock request launches, which stops the remaining step. The test blocks it, so no real timer exists.
            val clock = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent) =
                    if (intent.action == AlarmClock.ACTION_SET_TIMER) Instrumentation.ActivityResult(Activity.RESULT_OK, null) else null
            }
            robot.instrumentation.addMonitor(clock)
            try {
                scenario.onActivity { model.agent.execute(timer) }
                robot.waitUntil { model.agent.plan?.phase == AgentPhase.STOPPED }
            } finally { robot.instrumentation.removeMonitor(clock) }
            robot.waitUntil { commentary().size == 6 }
            SystemClock.sleep(500)
            assertEquals(listOf("d1" to ON_SCREEN, "d1" to failed, "d2" to ON_SCREEN, "d2" to DONE, "d3" to ON_SCREEN, "d3" to CANCELLED),
                commentary())
        }
    }

    /** Spec 5.5.10 rule 9: a permission or a selection needs the screen, once for each revision, and voice cannot approve it. */
    @Test fun screenPhasesAskForTheScreen() {
        MemoryStore(fixture.context, memory).use { store -> listOf("blue", "green").forEach { store.saveExplicitFact("Favorite color is $it") } }
        assertNotEquals("The camera permission must be denied on the test emulator", PackageManager.PERMISSION_GRANTED,
            fixture.context.checkSelfPermission(Manifest.permission.CAMERA))
        plan(EDIT)
        install()
        live(voiceApproval = true) { _, model ->
            val selection = proposed(model, PLAN_REQUEST, "d1", 100)
            assertEquals(AgentPhase.NEEDS_SELECTION, selection.phase)
            spoken(5_000)
            answer("да", 6_000)
            assertFalse(model.agent.busy)
            assertEquals(selection, model.agent.plan)
            fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence("Take a selfie.", "selfie"))
            fixture.provider.agentProposal = """{"steps":[{"function":"camera.capture","args":{"lens":"front"}}]}"""
            val permission = proposed(model, "Take a selfie.", "d2", 7_000)
            assertEquals(AgentPhase.NEEDS_PERMISSION, permission.phase)
            assertEquals(selection.id to selection.revision + 1, permission.id to permission.revision)
            SystemClock.sleep(500)
            assertEquals(listOf("d1" to ON_SCREEN, "d2" to ON_SCREEN), commentary())
        }
    }

    /** The plan of [prepare] for the hand-off d1, whose first commentary GPT-Live has acknowledged and spoken until 5,000 ms. */
    private fun reviewed(voiceApproval: Boolean = true, prepare: () -> Unit = { plan(ADD) },
        test: (ActivityScenario<CloudTestActivity>, ShellViewModel, AgentPlan) -> Unit) {
        prepare()
        install()
        live(voiceApproval) { scenario, model ->
            val review = proposed(model, PLAN_REQUEST, "d1", 100)
            spoken(5_000)
            test(scenario, model, review)
        }
    }

    /** The chat agent answers [PLAN_REQUEST] with a phone plan of [proposal], one step for each of the [anchors]. */
    private fun plan(proposal: String, anchors: List<String> = listOf(PLAN_REQUEST)) {
        fixture.provider.devicePlanSteps = anchors.map { PhoneActionEvidence(PLAN_REQUEST, it) }
        fixture.provider.agentProposal = proposal
    }

    /** A plan with one memory.write step for each of the (at most 3) [facts]. Each step has its own anchor in [PLAN_REQUEST]. */
    private fun writes(facts: List<String>) = plan(JSONObject().put("steps", JSONArray(facts.map {
        JSONObject().put("function", "memory.write").put("args", JSONObject().put("text", it))
    })).toString(), listOf("memory", "favorite color", "green").take(facts.size))

    /** Says [request] as the hand-off [id] at [atMs], and waits for the first commentary of its plan. */
    private fun proposed(model: ShellViewModel, request: String, id: String, atMs: Long): AgentPlan {
        socket.say(request, id, atMs)
        // The plan agent ranks the phone capabilities on the device.
        robot.waitUntil(60_000) { commentary().any { it.first == id } }
        return checkNotNull(model.agent.plan)
    }

    /** GPT-Live acknowledges the latest commentary and speaks it until [endMs] of the session timeline. */
    private fun spoken(endMs: Long) {
        val event = socket.sent.map(::JSONObject).last { it.getString("type") == "session.commentary.append" }.getString("event_id")
        socket.emit("""{"type":"session.commentary.appended","client_event_id":"$event"}""")
        socket.emit(outputDelta("Скажите «да» или «нет».", endMs - 500, endMs))
        socket.emit(outputAudio(1))
        // No chunk follows, so the session reports the output end after the default reply gap of 800 ms.
        SystemClock.sleep(1_300)
        robot.instrumentation.runOnMainSync { }
    }

    /** The user says [text] at [atMs], and GPT-Live answers it without a hand-off, which ends the turn at once. */
    private fun answer(text: String, atMs: Long) {
        socket.emit(inputDelta(text, atMs, atMs + 400))
        socket.emit(outputDelta("Хорошо.", atMs + 600, atMs + 900))
        robot.instrumentation.runOnMainSync { }
    }

    /** A hand-off runs to its end after the mode ends by [end] with the status [status] (spec 5.5.9). */
    private fun endKeepsTheRequest(status: Int?, end: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) {
        fixture.provider.gate = CompletableDeferred()
        install()
        live { scenario, model ->
            socket.say("Какая погода", "d1", 100)
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
            socket.say(request, "d1", 100)
            robot.waitUntil { !model.liveVoice.active }
            assertEquals(robot.text(R.string.live_voice_on_screen), model.liveVoice.status)
            assertTrue(opened(model))
            assertTrue(commentary().isEmpty())
        }
    }

    /**
     * The voice mode with the plan step: the hand-off of [APP_REQUEST] puts the app of [plan] on the stage, and [test] gets its id.
     * Each other request gets the answer «Fixture response».
     */
    private fun onStage(plan: String = "calc", show: Boolean = false,
        script: AgentScriptProvider = AgentScriptProvider(listOf(Scripted.Text(appFilePlan(plan)))),
        test: (ActivityScenario<CloudTestActivity>, ShellViewModel, String) -> Unit) =
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            install(chat = stageProvider(plan, show = show, script = script))
            live { scenario, model ->
                socket.say(APP_REQUEST, "d1", 100)
                robot.waitUntil(60_000) { model.stageToolId != null }
                test(scenario, model, checkNotNull(model.stageToolId))
            }
        }

    /**
     * The voice mode with the plan step: the plan call of the hand-off [request] (a screen with [show] for [SCREEN_REQUEST]) waits for
     * the gate that [test] gets. [test] starts when the message generates and the voice has its first line.
     */
    private fun gatedRequest(request: String = SCREEN_REQUEST, show: Boolean = false,
        test: (ActivityScenario<CloudTestActivity>, ShellViewModel, CompletableDeferred<Unit>) -> Unit) {
        val gate = CompletableDeferred<Unit>()
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            install(chat = stageProvider("calc", gate, show = show))
            live { scenario, model ->
                socket.say(request, "d1", 100)
                robot.waitUntil(60_000) { messages(model).lastOrNull()?.status == "generating" && commentary().isNotEmpty() }
                test(scenario, model, gate)
            }
        }
    }

    /** The calc app is on the stage. Then the plan and the repair of [SCREEN_REQUEST] have faults, and the voice says [lines] for it. */
    private fun fallbackScreen(show: Boolean, vararg lines: String) {
        val broken = appFilePlan("calc").replace("/ \$/people, example", "/ , example")
        val script = AgentScriptProvider(listOf(Scripted.Text(appFilePlan("calc")), Scripted.Text(broken)))
        onStage(show = show, script = script) { _, model, app ->
            val before = messages(model).size
            socket.say(SCREEN_REQUEST, "d2", 2_000)
            robot.waitUntil(60_000) { messages(model).size == before + 2 && messages(model).last().status !in unfinishedStatuses }
            val message = messages(model).last()
            assertEquals("complete", message.status)
            assertEquals(SCREEN_TEXT, message.text)
            assertNull(message.toolId)
            assertEquals("" to "", message.errorCode to message.error)
            assertEquals(List(3) { CloudGenerationStage.APP_FILE }, script.calls.map { it.generationStage })
            robot.waitUntil { commentary().count { it.first == "d2" } == lines.size }
            robot.awaitFrames(5)
            assertEquals(lines.map { "d2" to it }, commentary().filter { it.first == "d2" })
            assertEquals(app, model.stageToolId)
            assertTrue(model.liveVoice.active)
            assertTrue(model.tools.drafts.none { it.id == message.toolOperationId })
            assertNull(model.tools.error)
        }
    }

    /**
     * The model calls of an app answer from [script] (the app file [plan]) after [gate]. The routing call creates an app for
     * [APP_REQUEST], gives a screen with [show] for [SCREEN_REQUEST], and answers each other request.
     */
    private fun stageProvider(plan: String, gate: CompletableDeferred<Unit>? = null, show: Boolean = false,
        script: AgentScriptProvider = AgentScriptProvider(listOf(Scripted.Text(appFilePlan(plan))))): ChatProvider = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) = when {
            request.generationStage != null -> { gate?.await(); script.respond(key, request, session, emit) }
            else -> emit(CloudEvent.RoundComplete(when (request.decisionContext?.originalUserTurn?.text) {
                APP_REQUEST -> chatDecision("create", "Готово.", "Make the app")
                SCREEN_REQUEST -> JSONObject(chatDecision("screen", SCREEN_TEXT, SCREEN_BRIEF)).put("show", show).toString()
                else -> chatDecision("answer", "Fixture response")
            }, false))
        }
    }

    /** The fixture provider, which keeps each routing request in [routed]. */
    private fun routing(routed: MutableList<CloudRequest>) = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            if (request.chatDecision) routed += request
            fixture.provider.respond(key, request, session, emit)
        }
    }

    /** Starts the voice mode again and answers `session.started`. */
    private fun restart(scenario: ActivityScenario<CloudTestActivity>, model: ShellViewModel) {
        val starts = socket.types().count { it == "session.start" }
        scenario.onActivity { model.startLiveVoice() }
        robot.waitUntil { socket.types().count { it == "session.start" } == starts + 1 }
        socket.emit(LIVE_STARTED)
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
    }

    private fun inStage(scenario: ActivityScenario<CloudTestActivity>, text: String) = robot.composedTextIn(scenario, STAGE, text)

    private fun install(chat: ChatProvider = fixture.provider, voice: VoiceFactory? = null) = fixture.install(chat = chat, voice = voice,
        liveVoice = fixture.liveVoice(socket, audio))

    /** Launches the shell, starts the voice mode (for the assistant [press] when not null), and answers `session.started`. */
    private fun live(voiceApproval: Boolean = false, press: String? = null, test: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) = fixture.launch().use { scenario ->
        val model = checkNotNull(fixture.model)
        robot.waitUntil { !model.loading }
        scenario.onActivity {
            press?.let(model::bindAssistantCapture)
            model.startLiveVoice(press, AssistantCapture.current?.takeIf { it.id == press }, voiceApproval = voiceApproval)
        }
        robot.waitUntil { socket.connected }
        socket.emit(LIVE_STARTED)
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
        test(scenario, model)
    }

    private fun commentary() = socket.commentary()

    private fun messages(model: ShellViewModel) = model.snapshot.selected?.messages.orEmpty()

    private companion object {
        const val STAGE = "dialogue_stage"
        const val CHARACTER = "live_voice_character"
        const val APP_REQUEST = "Сделай калькулятор чаевых"
        const val SCREEN_REQUEST = "Объясни, как работает сердце"
        const val SCREEN_TEXT = "Сердце — насос."
        const val SCREEN_BRIEF = "A heart that pumps blood"
        const val SCREEN_ANSWER = "Ответ Alphy: $SCREEN_TEXT"
        const val SCREEN_FAILED = "Картинку показать не получилось."
        const val TIMER_REQUEST = "Поставь таймер на 10 минут"
        /** A figure label of the calc app. */
        const val CALC_LABEL = "Per person"
        /** The text of the drawing of the lesson app. */
        const val ORBIT = "Земля в центре, Луна на орбите и метка её стороны"
        const val PLAN_REQUEST = "Change my memory of my favorite color to green"
        const val ADD = """{"steps":[{"function":"memory.write","args":{"text":"Favorite color is green"}}]}"""
        const val EDIT = """{"steps":[{"function":"memory.write","args":{"match":"Favorite color","text":"Favorite color is green"}}]}"""
        const val ASK = " Скажите «да», чтобы выполнить, или «нет», чтобы отменить."
        val PROPOSAL_ADD = "Alphy предлагает: ${added("Favorite color is green")}.$ASK"
        const val ON_SCREEN = "Нужно подтверждение на экране."
        const val STARTED = "Запускаю."
        const val CANCELLED = "Действие отменено."
        const val DONE = "Готово. План завершён."

        /** A memory-add step as the proposal reads its card: the label, the «after» text, and the non-reversible note. */
        fun added(fact: String) = "Добавить факт, После: $fact, Нельзя отменить через Alphy"
    }
}
