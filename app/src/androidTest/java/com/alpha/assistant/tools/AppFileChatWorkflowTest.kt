package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage.AGENT
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_CHANGE
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Message
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.agent.GenerationSettings
import com.alpha.assistant.tools.appfile.checked
import com.alpha.assistant.tools.appfile.plan
import com.alpha.assistant.tools.appfile.row
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import com.alpha.assistant.ui.chatDecision
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The plan step through the chat path with the switch on: a request that the kit serves becomes a saved app that works,
 * and a game request, which the kit cannot serve, still becomes a working app through the loop.
 * The expected values come from the request and are computed in the test.
 */
@RunWith(AndroidJUnit4::class)
class AppFileChatWorkflowTest {
    private val fixture = ShellFixture("appfile-chat")
    private val robot = ShellRobot("appfile-chat")
    private val settings = GenerationSettings(fixture.context)
    private val switchBefore = settings.planStep(AiProvider.OPENAI)

    @Before fun switchOn() = settings.setPlanStep(AiProvider.OPENAI, true)

    @After fun restore() {
        settings.setPlanStep(AiProvider.OPENAI, switchBefore)
        fixture.remove()
    }

    /** Sends [request] in a new chat that routes it to a new app. The generation calls answer from [script]. The saved app goes to [test]. */
    private fun create(request: String, script: AgentScriptProvider, test: (ToolRecord) -> Unit) {
        fixture.install(chat = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                if (request.generationStage != null) script.respond(key, request, session, emit)
                else emit(CloudEvent.RoundComplete(chatDecision("create", "Готово.", "A new app for the request"), false))
        })
        fixture.launch().use {
            val model = checkNotNull(fixture.model)
            robot.waitUntil { !model.loading }
            test(saved(model, send(model, request)))
        }
    }

    /** Sends [request] in the open chat and gives the last message after the request ends. */
    private fun send(model: ShellViewModel, request: String): Message {
        val before = model.snapshot.selected?.messages?.size ?: 0
        robot.instrumentation.runOnMainSync { model.editDraft(Draft(request)); model.send(search = false) }
        robot.waitUntil(60_000) { model.activeConversationId == null && !model.tools.busy &&
            model.snapshot.selected?.messages.orEmpty().let { it.size > before && it.last().status in setOf("complete", "error") } }
        return checkNotNull(model.snapshot.selected).messages.last()
    }

    /** The saved app of [card]. */
    private fun saved(model: ShellViewModel, card: Message): ToolRecord {
        assertEquals(card.error, "complete", card.status)
        // The chat card of the app shows its open button.
        robot.waitFor(robot.text(R.string.tool_open))
        var saved: ToolRecord? = null
        robot.instrumentation.runOnMainSync { saved = model.tools.tools.single { it.id == card.toolId } }
        return checkNotNull(saved)
    }

    // Failure case: a change that the saved data of the person blocks showed no reason in the chat, or it changed the app.
    @Test fun aChangeThatTheSavedDataBlocksSaysWhyInTheChat() {
        val shopping = plan("shopping")
        // The saved rows hold a text in the column aisle, so the kit blocks a number there.
        val aisleNumber = "need items = entries(\"Items\", fields={name: \"text\", aisle: \"number\", done: \"check\"}, sort=\$aisle, " +
            "labels={name: \"Product\", aisle: \"Aisle\"}, rows=[{name: \"Milk\", aisle: 2, done: false}, {name: \"Bread\", aisle: 1, done: false}, " +
            "{name: \"Apples\", aisle: 3, done: false}])"
        val script = AgentScriptProvider(listOf(Scripted.Text(shopping), Scripted.Text(aisleNumber)))
        val decisions = ArrayDeque(listOf(chatDecision("create", "Готово.", "A shopping list by aisle"),
            chatDecision("refine", "Меняю.", "Make the aisle a number")))
        fixture.install(chat = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                if (request.generationStage != null) script.respond(key, request, session, emit)
                else emit(CloudEvent.RoundComplete(decisions.removeFirst(), false))
        })
        fixture.launch().use {
            val model = checkNotNull(fixture.model)
            robot.waitUntil { !model.loading }
            val created = saved(model, send(model, "Список покупок по рядам магазина"))
            // The person marks Milk as bought.
            robot.instrumentation.runOnMainSync { model.tools.interact(created, "items_done", row("r1", true)) }
            robot.waitUntil { model.tools.tools.single { it.id == created.id }.stateVersion > created.stateVersion }
            var pressed: ToolRecord? = null
            robot.instrumentation.runOnMainSync { pressed = model.tools.tools.single { it.id == created.id } }
            val card = send(model, "Пусть ряд будет числом")
            assertEquals(listOf(APP_FILE, APP_FILE_CHANGE), script.calls.map { it.generationStage })
            assertEquals("error", card.status)
            val why = fixture.context.getString(R.string.tool_generation_state)
            assertEquals(why, card.error)
            robot.waitFor(robot.text(R.string.tool_generation_state))
            robot.instrumentation.runOnMainSync { assertEquals(pressed, model.tools.tools.single { it.id == created.id }) }
        }
    }

    /** The steps of the behavior checker on the saved app, from its start state: each step runs, and each expectation passes. */
    private fun works(saved: ToolRecord, steps: String) = runBlocking {
        val list = JSONArray(steps)
        val (outcome, _) = checked(SandboxClient(fixture.context), ToolSpec.parsePersisted(saved.spec), saved.id, list)
        assertNull(outcome.final?.text, outcome.failure)
        assertEquals((0 until list.length()).count { list.getJSONObject(it).has("expect") }, outcome.expectations)
    }

    // Failure case: the plan step worked only below the chat, so a person never got the fast app.
    @Test fun aModelPlanBecomesASavedAppThatWorks() {
        val tip = plan("model/holdout-tip-ru")
        val script = AgentScriptProvider(listOf(Scripted.Text(tip)))
        create("Калькулятор чаевых: сумма счёта, процент чаевых и на сколько человек делим.", script) { saved ->
            assertEquals(listOf(APP_FILE), script.calls.map { it.generationStage })
            assertEquals(tip, ToolSpec.parsePersisted(saved.spec).appFile)
            // The start values give 1000 × 1.10 / 2 = 550. A bill of 1200 with a tip of 20 % for 3 people gives 1200 × 1.20 / 3 = 480.
            works(saved, """[{"expect":"value","target":"d.perperson_text","equals":"550,00"},
                {"set":"bill_field","value":1200},{"set":"tip_field","value":20},{"set":"people_field","value":3},
                {"expect":"value","target":"d.perperson_text","equals":"480,00"},{"expect":"text","contains":"480,00"}]""")
        }
    }

    // Failure case: a request that the kit cannot serve lost its app when the plan step was on.
    @Test fun aGameRequestGoesThroughOutsideToTheLoop() {
        // The basket moves to lane 0, where the first star falls. The star lands after 700 ms: one point. The canvas draws the score.
        val play = """[{"playback":"start"},{"pointer":"field","x":0.1,"y":0.9},{"wait":800},{"expect":"text","contains":"Очки: 1 · Промахи: 0"}]"""
        val finish = JSONObject().put("tool", "finish").put("summary", "The game is ready.")
            .put("checks", JSONArray().put(JSONObject().put("name", "catch").put("steps", JSONArray(play)))).toString()
        val script = AgentScriptProvider(listOf(Scripted.Text("outside(\"game\")"),
            Scripted.Text(agentWrite(RequestGameplayExamples.starCatchSpec())), Scripted.Text(finish)))
        create(RequestGameplayExamples.starCatchRequest, script) { saved ->
            assertEquals(listOf(APP_FILE, AGENT, AGENT), script.calls.map { it.generationStage })
            val spec = ToolSpec.parsePersisted(saved.spec)
            assertNull(spec.appFile)
            assertEquals(0.0, JSONObject(saved.state).getDouble("score"), 0.0)
            works(saved, play)
        }
    }

    // Failure case: an account on a model that no check measured got the plan step.
    @Test fun aDifferentChatModelSendsNoPlanCall() {
        fixture.models.selectChat(AiProvider.OPENAI, "gpt-6.1-sol")
        val script = AgentScriptProvider(listOf(Scripted.Text(agentWrite(com.alpha.assistant.ui.FIXTURE_APP)), Scripted.Text(agentFixtureFinish)))
        create("Make a counter", script) { saved ->
            assertEquals(listOf(AGENT, AGENT), script.calls.map { it.generationStage })
            assertNull(ToolSpec.parsePersisted(saved.spec).appFile)
        }
    }
}
