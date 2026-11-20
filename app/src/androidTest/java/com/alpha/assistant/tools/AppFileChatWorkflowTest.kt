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
import com.alpha.assistant.data.MessageProvenance
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.AppFilePrevious
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

    /** Runs at the start of each generation call, inside it. */
    @Volatile private var duringCall: (CloudRequest) -> Unit = {}

    @After fun remove() = fixture.remove()

    /** Opens the shell with the switch on and a chat model that gives [decisions] in order. The generation calls answer from [script]. */
    private fun launch(script: AgentScriptProvider, decisions: List<CloudEvent.RoundComplete>, test: (ShellViewModel) -> Unit) =
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            val next = ArrayDeque(decisions)
            fixture.install(chat = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                    if (request.generationStage == null) emit(next.removeFirst())
                    else { duringCall(request); script.respond(key, request, session, emit) }
            })
            fixture.launch().use {
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading }
                test(model)
            }
        }

    /** Sends [request] in a new chat that routes it to a new app. The generation calls answer from [script]. The saved app goes to [test]. */
    private fun create(request: String, script: AgentScriptProvider, test: (ToolRecord) -> Unit) =
        launch(script, listOf(CloudEvent.RoundComplete(chatDecision("create", "Готово.", "A new app for the request"), false))) { model ->
            test(saved(model, send(model, request)))
        }

    /**
     * The shopping list from one plan call in a new chat. The next chat decision is [next], and its generation call answers [change].
     * [test] gets the chat model, the saved app, and the script of the generation calls.
     */
    private fun shopping(change: Scripted, next: CloudEvent.RoundComplete, test: (ShellViewModel, ToolRecord, AgentScriptProvider) -> Unit) {
        val script = AgentScriptProvider(listOf(Scripted.Text(plan("shopping")), change))
        launch(script, listOf(CloudEvent.RoundComplete(chatDecision("create", "Готово.", "A shopping list by aisle"), false), next)) { model ->
            test(model, saved(model, send(model, "Список покупок по рядам магазина")), script)
        }
    }

    /** The saved record of [id] as the chat holds it. */
    private fun record(model: ShellViewModel, id: String): ToolRecord {
        var record: ToolRecord? = null
        robot.instrumentation.runOnMainSync { record = model.tools.tools.single { it.id == id } }
        return checkNotNull(record)
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
        return record(model, checkNotNull(card.toolId))
    }

    // Failure case: a change that the saved data of the person blocks showed no reason in the chat, or it changed the app.
    @Test fun aChangeThatTheSavedDataBlocksSaysWhyInTheChat() =
        shopping(Scripted.Text(AISLE_NUMBER), CloudEvent.RoundComplete(chatDecision("refine", "Меняю.", "Make the aisle a number"), false)) { model, created, script ->
            // The person marks Milk as bought.
            robot.instrumentation.runOnMainSync { model.tools.interact(created, "items_done", row("r1", true)) }
            robot.waitUntil { record(model, created.id).stateVersion > created.stateVersion }
            val pressed = record(model, created.id)
            val card = send(model, "Пусть ряд будет числом")
            assertEquals(listOf(APP_FILE, APP_FILE_CHANGE), script.calls.map { it.generationStage })
            assertEquals("error", card.status)
            val why = fixture.context.getString(R.string.tool_generation_state)
            assertEquals(why, card.error)
            robot.waitFor(robot.text(R.string.tool_generation_state))
            assertEquals(pressed, record(model, created.id))
        }

    /** Sends the test and fix FIX of the shopping list: it makes one change call, and the chat shows [text]. Gives the chat card. */
    private fun assertTestFixShows(model: ShellViewModel, script: AgentScriptProvider, text: String): Message {
        val card = send(model, FIX)
        assertEquals(listOf(APP_FILE, APP_FILE_CHANGE), script.calls.map { it.generationStage })
        assertEquals(card.error, "complete", card.status)
        assertEquals(text, card.text)
        val headline = text.lines().first()
        robot.waitUntil { robot.texts().any { headline in it } }
        return card
    }

    // Failure case: the chat said that a fix by lines was checked, but no run checked it.
    @Test fun aTestFixByLinesAsksThePersonToCheckTheFix() = shopping(Scripted.Text(BOUGHT), TEST_FIX) { model, created, script ->
        val card = assertTestFixShows(model, script, robot.text(R.string.chat_app_test_saved_unchecked, 2) + "\nShopping list")
        assertEquals(2, card.toolRevision)
        assertEquals(2, record(model, created.id).revision)
    }

    // Failure case: a fix by lines that a different write stopped from its save said that the fix was checked, but no run checked it.
    @Test fun anUnsavedTestFixByLinesDoesNotSayItWasChecked() = shopping(Scripted.Text(BOUGHT), TEST_FIX) { model, created, script ->
        val tools = ToolStore(fixture.context, "${fixture.name}.db-tools.db")
        // A writer other than the chat (an automation, for example) marks Milk as bought while the change call runs.
        duringCall = {
            if (it.generationStage == APP_FILE_CHANGE) {
                val stored = checkNotNull(tools.tool(created.id))
                val state = JSONObject(stored.state).apply { getJSONArray("items").getJSONObject(0).put("done", true) }
                tools.saveTool(stored.copy(state = state.toString(), stateVersion = stored.stateVersion + 1), false)
            }
        }
        tools.use {
            assertTestFixShows(model, script, robot.text(R.string.chat_app_test_unsaved_unchecked, 1) +
                "\nShopping list The saved app changed during testing.")
            assertEquals(1, it.revisions(created.id).size)
        }
    }

    // Failure case: a test and fix that changed no line said that the checks of the version passed, but no check ran.
    @Test fun aTestFixWithNoLineSaysThatAlphyFoundNoLine() = shopping(Scripted.Text(plan("shopping").lines()[1]), TEST_FIX) { model, created, script ->
        val before = record(model, created.id)
        assertTestFixShows(model, script, robot.text(R.string.chat_app_test_unverified, 1) + "\n" + robot.text(R.string.tool_test_fix_no_line))
        assertEquals(before, record(model, created.id))
    }

    /** The steps of the behavior checker on the saved app, from its start state: each step runs, and each expectation passes. */
    private fun works(saved: ToolRecord, steps: String) = runBlocking {
        val list = JSONArray(steps)
        val (outcome, _) = checked(SandboxClient(fixture.context), ToolSpec.parsePersisted(saved.spec), saved.id, list)
        assertNull(outcome.final?.text, outcome.failure)
        assertEquals((0 until list.length()).count { list.getJSONObject(it).has("expect") }, outcome.expectations)
    }

    /** Waits until the chat card of [saved] has opened its host. That open uses the one transient sandbox slot, so a probe during it gets BUSY. */
    private fun cardHostOpened(saved: ToolRecord) = ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
        val coordinator = ArtifactCoordinator.get(store, saved.id)
        // The open adds its host in one ordered block after its event leaves the slot. Before the open, the snapshot fails or has no host.
        robot.waitUntil { runBlocking { coordinator.ordered { runCatching { snapshot(store).sessionGeneration }.getOrDefault(0L) } } > 0 }
    }

    // Failure case: the plan step worked only below the chat, so a person never got the fast app.
    @Test fun aModelPlanBecomesASavedAppThatWorks() {
        val tip = plan("model/holdout-tip-ru")
        val script = AgentScriptProvider(listOf(Scripted.Text(tip)))
        create("Калькулятор чаевых: сумма счёта, процент чаевых и на сколько человек делим.", script) { saved ->
            assertEquals(listOf(APP_FILE), script.calls.map { it.generationStage })
            assertEquals(tip, ToolSpec.parsePersisted(saved.spec).appFile)
            cardHostOpened(saved)
            // The start values give 1000 × 1.10 / 2 = 550. A bill of 1200 with a tip of 20 % for 3 people gives 1200 × 1.20 / 3 = 480.
            works(saved, """[{"expect":"value","target":"d.perperson_text","equals":"550,00"},
                {"set":"bill_field","value":1200},{"set":"tip_field","value":20},{"set":"people_field","value":3},
                {"expect":"value","target":"d.perperson_text","equals":"480,00"},{"expect":"text","contains":"480,00"}]""")
        }
    }

    // Failure case: a refused manual edit of a part-built app said only that the save failed, so the person did not know what to change.
    @Test fun aRefusedManualEditSaysWhatToChange() {
        val shopping = plan("shopping")
        create("Список покупок по рядам магазина", AgentScriptProvider(listOf(Scripted.Text(shopping)))) { saved ->
            val model = checkNotNull(fixture.model)
            robot.instrumentation.runOnMainSync { model.openSurface(saved.id) }
            robot.click(robot.text(R.string.tool_options))
            robot.click(robot.text(R.string.tool_manual_edit))
            val broken = shopping + BROKEN + "\n"
            val previous = AppFilePrevious(shopping, ToolSpec.parsePersisted(saved.spec).schema.toString(), saved.state)
            val faults = runBlocking { AppFileCompiler(fixture.context, SandboxClient(fixture.context)).compile(broken, previous) }.faults
            // A plan with a fault shows the fault of the kit, which names the line. A changed compiled field shows the sentence on the plan.
            val shown = listOf(JSONObject(saved.spec).put("appFile", broken) to faults.joinToString("\n"),
                JSONObject(saved.spec).put("title", "Groceries") to robot.text(R.string.tool_manual_edit_app_file))
            val input = robot.text(R.string.tool_manual_edit_definition)
            for ((definition, text) in shown) {
                robot.edit(input, definition.toString())
                robot.click(robot.text(R.string.tool_manual_edit_save))
                robot.reveal(text)
            }
            assertEquals(saved, record(model, saved.id))
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

    private companion object {
        const val FIX = "Купленные не считаются. Проверь и исправь приложение."
        /** The chat decision of a test and fix of the one app of the chat. */
        val TEST_FIX = CloudEvent.RoundComplete("", false, MessageProvenance.CLEAN, testFix = CloudEvent.TestFixRequest(null))
    }
}
