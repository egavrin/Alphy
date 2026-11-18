package com.alpha.assistant.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.AlarmClock
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
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Message
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.agentReply
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

    @Test fun miniAppSendsProgressThenReady() {
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

    @Test fun courseTopicEndsWithContinueOnScreen() = endsOnScreen("Научи меня испанскому языку",
        { fixture.provider.courseTopic = "Испанский язык" }) { it.courseSetup != null }

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
        // The session reports the output end after the default reply gap of 900 ms without voiced output audio.
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

    private fun install(chat: ChatProvider = fixture.provider, voice: VoiceFactory? = null) = fixture.install(chat = chat, voice = voice,
        liveVoice = fixture.liveVoice(socket, audio))

    /** Launches the shell, starts the voice mode, and answers `session.started`. */
    private fun live(voiceApproval: Boolean = false, test: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) = fixture.launch().use { scenario ->
        val model = checkNotNull(fixture.model)
        robot.waitUntil { !model.loading }
        scenario.onActivity { model.startLiveVoice(voiceApproval = voiceApproval) }
        robot.waitUntil { socket.connected }
        socket.emit(LIVE_STARTED)
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
        test(scenario, model)
    }

    /** The sent commentary appends as (delegation id, text), in order. */
    private fun commentary() = socket.sent.map(::JSONObject).filter { it.getString("type") == "session.commentary.append" }
        .map { it.getString("delegation_id") to it.getString("content") }

    private fun messages(model: ShellViewModel) = model.snapshot.selected?.messages.orEmpty()

    private companion object {
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
