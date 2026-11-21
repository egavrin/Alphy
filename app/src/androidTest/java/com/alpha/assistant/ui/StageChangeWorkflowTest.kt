package com.alpha.assistant.ui

import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.ChatToolReply
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage.AGENT
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_CHANGE
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_TEMPLATE
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.SLOW_LAP
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.sameToolJson
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling FX-6: a spoken change of the stage app while that app commits its state. The request reads its app before its routing call,
 * so a touch or a tick of the app during that call comes between the read and the draft of the change (R1 of the owner's dialogue).
 */
class StageChangeWorkflowTest {
    private val fixture = ShellFixture("stage-change")
    private val robot = ShellRobot("stage-change")
    private val socket = FakeLiveTransport()
    private val shell = TemplateShell(fixture, robot, socket)
    private val prompt get() = AppFilePrompt.load(fixture.context)
    private val ship get() = shipTemplate()
    /** The task of the spoken change of [TemplateTalk], as the shell gives it to the change step. */
    private val changeTask get() = ChatToolReply.generationPrompt(TemplateTalk.CHANGE_REQUEST, TemplateTalk.CHANGE_BRIEF, "Готово.", emptyList())
    @After fun remove() = shell.remove()

    // Failure case (R1): a touch of the stage app during the routing call of a spoken change ended the change with the generic error.
    @Test fun aTouchDuringTheRoutingKeepsTheChange() = changeWhileTheAppCommits("calc", SEATS, move = { model, app ->
        robot.instrumentation.runOnMainSync { model.tools.interact(app, "people_field", 5) }
        // The touch is complete when its operation ends, as it is 3 to 5 s before the change in a spoken talk.
        robot.waitUntil { people(stored(model, app.id)) == 5 && !model.tools.busy }
    }) { saved ->
        // The change counts from the value of the touch.
        assertEquals(5, people(saved))
        assertEquals("10", JSONObject(saved.state).getJSONObject("d").getString("seats_text"))
    }

    // Failure case (R1): the ticks of a running animate of the stage app during the routing call ended the change with the generic error.
    // The stage keeps its frames and timers while the change runs, so the ticks also come during the change call and at the save.
    @Test fun aRunningAnimateKeepsTheChange() = changeWhileTheAppCommits("lesson", SLOW_LAP, move = { model, app ->
        robot.instrumentation.runOnMainSync { model.tools.interact(app, "lap_button", null) }
        // The press and at least one tick of the lap.
        robot.waitUntil { stored(model, app.id).stateVersion >= app.stateVersion + 2 && !model.tools.busy }
    }) { saved -> assertEquals(true, "Медленный круг" in saved.ui) }

    /**
     * The voice mode puts the app of [plan] on the stage. Then a spoken change waits in its routing call until [move] commits a state of
     * that app. The change by [line] is then saved as revision 2, and [check] gets the saved record.
     */
    private fun changeWhileTheAppCommits(plan: String, line: String, move: (ShellViewModel, ToolRecord) -> Unit, check: (ToolRecord) -> Unit) {
        val provider = HeldChange(AgentScriptProvider(appFilePlan(plan), line))
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            fixture.install(chat = provider, liveVoice = fixture.liveVoice(socket, FakeLiveAudio()))
            fixture.launch().use { scenario ->
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading }
                scenario.onActivity { model.startLiveVoice(voiceApproval = false) }
                robot.waitUntil { socket.connected }
                socket.emit(LIVE_STARTED)
                robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
                socket.say(APP_REQUEST, "d1", 100)
                robot.waitUntil(60_000) { model.stageToolId != null }
                val before = stored(model, checkNotNull(model.stageToolId))
                val count = messages(model).size
                socket.say(CHANGE_REQUEST, "d2", 2_000)
                robot.waitUntil(30_000) { provider.routed.isCompleted }
                move(model, before)
                provider.held.complete(Unit)
                robot.waitUntil(60_000) { messages(model).size == count + 2 && messages(model).last().status !in unfinishedStatuses }
                val answer = messages(model).last()
                assertEquals("${answer.status} ${answer.errorCode} ${answer.error}", listOf("complete", before.id, 2),
                    listOf(answer.status, answer.toolId, answer.toolRevision))
                robot.waitUntil { stored(model, before.id).revision == 2 }
                val saved = stored(model, before.id)
                assertFalse(sameToolJson(JSONObject(before.state), JSONObject(saved.state)))
                check(saved)
            }
        }
    }

    // Plan of stage templates, Task 5: a spoken change of a template screen sends the template file, and the kit merges the changed lines.
    @Test fun aSpokenChangeOfATemplateScreenSendsTheTemplateFile() = templateChange(Scripted.Text(SHIP_S4)) { model, talk, before, _ ->
        assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages)
        val call = talk.calls[1]
        assertEquals(prompt.template, call.systemInstructions)
        assertEquals(listOf(prompt.templateChangeText(changeTask, ship)), call.turns.map { it.text })
        val saved = saved(model, before)
        assertEquals(listOf(shipWithS4(), NAIL), listOf(appFileOf(saved), shipSubject(saved)))
    }

    @Test fun aDeleteLineRemovesThePart() = templateChange(Scripted.Text("delete s3")) { model, talk, before, _ ->
        assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages)
        val saved = saved(model, before)
        assertEquals(listOf(ship.lines().filterNot { it.startsWith("s3 = ") }.joinToString("\n"), NAIL),
            listOf(appFileOf(saved), shipSubject(saved)))
    }

    // The bad s2 stays bad after its one repair: the kit leaves it out and keeps the stored s2, and s4 is saved.
    @Test fun aChangeWithAFaultyLineKeepsTheStoredLine() =
        templateChange(Scripted.Text("$BAD_S2\n$SHIP_S4"), Scripted.Text(BAD_S2)) { model, talk, before, _ ->
            assertEquals(List(3) { APP_FILE_TEMPLATE }, talk.stages)
            val saved = saved(model, before)
            assertEquals(listOf(shipWithS4(), NAIL), listOf(appFileOf(saved), shipSubject(saved)))
        }

    @Test fun aTemplateOutsideRunsTodaysChangeOnTheExpansion() {
        val expanded = shipExpansion(fixture.context)
        templateChange(Scripted.Text(NO_TEMPLATE), plans = AgentScriptProvider(SHIP_CARD_CHANGE)) { model, talk, before, _ ->
            assertEquals(List(2) { APP_FILE_TEMPLATE } + APP_FILE_CHANGE, talk.stages)
            assertEquals(listOf(prompt.changeText(changeTask, expanded)), talk.calls[2].turns.map { it.text })
            val saved = saved(model, before)
            assertEquals(shipCardChanged(expanded), appFileOf(saved))
            assertFalse(prompt.isTemplate(checkNotNull(appFileOf(saved))))
            assertEquals(NAIL, shipSubject(saved))
        }
    }

    // The new screen of the fallback and the full generation give no app either, so the voice says the reason.
    @Test fun aWholeFaultOfAChangeKeepsTheApp() {
        val plans = AgentScriptProvider(List(2) { Scripted.Text(BROKEN_PLAN) } + Scripted.Text(OUTSIDE) + Scripted.Fails("provider"))
        templateChange(Scripted.Text(ITEMS_HEAD), Scripted.Text(ITEMS_HEAD), plans = plans) { model, talk, before, phases ->
            assertEquals(List(3) { APP_FILE_TEMPLATE } + List(3) { APP_FILE } + AGENT, talk.stages)
            assertTrue(phases.toString(), "plan_outcome:fallback:template_whole" in phases)
            assertEquals(listOf(before.revision, before.state), stored(model, before.id).let { listOf(it.revision, it.state) })
            robot.waitUntil { socket.commentary().count { it.first == "d2" } == 2 }
            robot.awaitFrames(5)
            assertEquals(listOf("d2" to FULL_WAIT, "d2" to FULL_NO_ANSWER), socket.commentary().filter { it.first == "d2" })
        }
    }

    // A reply of the stored s1 line merges into the stored text again.
    @Test fun anEmptyTemplateChangeIsEmpty() = templateChange(Scripted.Text(lineOf(ship, "s1"))) { model, talk, before, phases ->
        assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages.filter { it == APP_FILE_TEMPLATE })
        assertTrue(phases.toString(), "plan_outcome:fallback:empty" in phases)
        assertEquals(listOf(before.revision, before.state), stored(model, before.id).let { listOf(it.revision, it.state) })
    }

    /**
     * The voice mode puts the ship template on the stage, and the person sets its choice to [NAIL]. Then a spoken change gets [changes] as
     * the replies of its template calls and [plans] as the replies of each other generation call. [check] gets the model, the provider,
     * the stored app before the change, and the trace phases, after the answer of the change.
     */
    private fun templateChange(vararg changes: Scripted, plans: AgentScriptProvider = AgentScriptProvider(appFilePlan("calc")),
        check: (ShellViewModel, TemplateTalk, ToolRecord, List<String>) -> Unit) {
        val talk = TemplateTalk(AgentScriptProvider(listOf(Scripted.Text(ship)) + changes), plans)
        shell.live(talk) { _, model, phases ->
            val app = shell.screen(model)
            robot.instrumentation.runOnMainSync { model.tools.interact(app, SHIP_CONTROL, NAIL) }
            robot.waitUntil { shipSubject(stored(model, app.id)) == NAIL && !model.tools.busy }
            val before = stored(model, app.id)
            val count = messages(model).size
            socket.say(TemplateTalk.CHANGE_REQUEST, "d2", 2_000)
            shell.waitForTheAnswer(model, count)
            check(model, talk, before, phases)
        }
    }

    /** The change of [before] saved as revision 2 of the same app. */
    private fun saved(model: ShellViewModel, before: ToolRecord): ToolRecord {
        robot.waitUntil { stored(model, before.id).revision == 2 }
        return stored(model, before.id)
    }

    private fun stored(model: ShellViewModel, id: String) = model.tools.tools.first { it.id == id }

    private fun people(record: ToolRecord) = JSONObject(record.state).getInt("people")

    private fun messages(model: ShellViewModel) = model.snapshot.selected?.messages.orEmpty()

    /**
     * The routing call of [APP_REQUEST] creates an app, and the routing call of [CHANGE_REQUEST] completes [routed] and changes the app after
     * [held]. Each plan call and change call answers from [script]. A template call gets [NO_TEMPLATE]. The plan call that starts beside a
     * routing call waits for its cancel.
     */
    private class HeldChange(private val script: AgentScriptProvider) : ChatProvider {
        val routed = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            if (answerNoTemplate(request, emit)) return
            if (request.generationStage != null) {
                if (EARLY_BRIEF in request.turns.last().text) awaitCancellation()
                return script.respond(key, request, session, emit)
            }
            val change = request.decisionContext?.originalUserTurn?.text == CHANGE_REQUEST
            if (change) { routed.complete(Unit); held.await() }
            emit(CloudEvent.RoundComplete(if (change) chatDecision("refine", "Готово.", "Change the shown app")
                else chatDecision("create", "Готово.", "Make the app"), false))
        }
    }

    private companion object {
        const val APP_REQUEST = "Сделай приложение"
        const val CHANGE_REQUEST = "Измени его"
        /** The plan call beside the routing call starts before a brief exists, so its prompt has an empty brief line. */
        const val EARLY_BRIEF = "\nWidget brief: \n"
        /** A change line of the calc plan: a figure from the count of people. */
        const val SEATS = "need seats = figure(\"count\", \"Seats\", \$/people * 2, example=[{people: 4}, 8])"
        /** A first line of another template kind: the stored parts do not fit it, so no part can show. */
        const val ITEMS_HEAD = "items(\"Почему корабль не тонет?\", lang=\"ru\")"
        const val OUTSIDE = "outside(\"game\")"
        const val FULL_WAIT = "Быстро не получилось. Alphy собирает экран заново, это займёт около минуты."
        const val FULL_NO_ANSWER = "Экран не собран: сервис ИИ не ответил. Скажите запрос полностью, одной фразой."
        /** The calc plan with a fault in the line of perPerson. */
        val BROKEN_PLAN get() = appFilePlan("calc").replace("/ \$/people, example", "/ , example")
    }
}
