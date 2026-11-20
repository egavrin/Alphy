package com.alpha.assistant.ui

import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.SLOW_LAP
import com.alpha.assistant.tools.ToolRecord
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
import org.junit.Test

/**
 * Ruling FX-6: a spoken change of the stage app while that app commits its state. The request reads its app before its routing call,
 * so a touch or a tick of the app during that call comes between the read and the draft of the change (R1 of the owner's dialogue).
 */
class StageChangeWorkflowTest {
    private val fixture = ShellFixture("stage-change")
    private val robot = ShellRobot("stage-change")
    private val socket = FakeLiveTransport()
    @After fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
        fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
    }

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

    private fun stored(model: ShellViewModel, id: String) = model.tools.tools.first { it.id == id }

    private fun people(record: ToolRecord) = JSONObject(record.state).getInt("people")

    private fun messages(model: ShellViewModel) = model.snapshot.selected?.messages.orEmpty()

    /**
     * The routing call of [APP_REQUEST] creates an app, and the routing call of [CHANGE_REQUEST] completes [routed] and changes the app after
     * [held]. Each plan call and change call answers from [script]. The plan call that starts beside a routing call waits for its cancel.
     */
    private class HeldChange(private val script: AgentScriptProvider) : ChatProvider {
        val routed = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
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
    }
}
