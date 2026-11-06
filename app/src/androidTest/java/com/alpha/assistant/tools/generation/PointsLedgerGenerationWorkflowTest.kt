package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PointsLedgerGenerationWorkflowTest {
    // Failure cases: debt, a failed redemption entering history, shared balances,
    // Undo reversing the wrong team, invalid configuration, and fill writes into trusted state.
    private val raw = """
        {
          "title":"Счёт команд","description":"Баланс и история","language":"ru","inputs":[],"questions":[],
          "constants":[{"id":"amber","value":"Янтарь"},{"id":"chalk","value":"Мел"},
            {"id":"quizPoints","value":3},{"id":"puzzlePoints","value":2},{"id":"hintCost","value":5},
            {"id":"quiz","value":"Викторина"},{"id":"puzzle","value":"Пазл"},{"id":"hint","value":"Подсказка"}],
          "needs":[{"id":"scores","type":"points_ledger","label":"Счёт команд",
            "description":"Сделай счёт очков для команд «Янтарь» и «Мел». За викторину начисляй 3 очка, за пазл — 2. Подсказка стоит 5 очков, в долг её брать нельзя. Покажи отдельный баланс каждой команды и историю начислений и списаний. Дай отменить последнее действие.",
            "uses":{"team1":"amber","team2":"chalk","earn1":"quizPoints","earn2":"puzzlePoints","cost":"hintCost",
              "earn1Label":"quiz","earn2Label":"puzzle","spendLabel":"hint"}}]
        }
    """.trimIndent()

    private val fill = """
        {"capabilities":[],"stateSchema":{"fill_value":"number"},"initialState":{"fill_value":0},
         "ui":{"type":"column","children":[{"type":"text","text":"Справка"}]},
         "source":"return {state:input.state};"}
    """.trimIndent()

    private suspend fun build(plan: String = raw): PartsAssembly = PartsGeneration.build(NeedsPlan.parse(plan)) { body ->
        val questions = JSONObject(body).getJSONArray("questions")
        val answers = JSONArray()
        repeat(questions.length()) { index ->
            answers.put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                .put("choice", "A").put("confidence", 0.9)
                .put("probabilities", JSONObject().put("A", 0.9).put("B", 0.1)))
        }
        JSONObject().put("answers", answers).toString()
    }

    @Test fun frozenRussianLedgerRunsThroughTheGeneratedToolSpec(): Unit = runBlocking {
        val assembly = build()
        val spec = assembly.requireComplete()
        assertEquals("points_ledger", assembly.parts.single().partId)
        assertTrue(spec.capabilities.isEmpty())
        val sandbox = SandboxClient(InstrumentationRegistry.getInstrumentation().targetContext)
        exercise(spec) { event, state ->
            val input = JSONObject().put("artifactId", "points_ledger_fixture").put("revision", 1)
                .put("state", state).put("event", event)
            val result = sandbox.execute(spec.source, input.toString())
            assertEquals("OK", result.status)
            val output = JSONObject(checkNotNull(result.json))
            assertEquals(setOf("state"), output.keys().asSequence().toSet())
            output.getJSONObject("state")
        }
    }

    @Test fun persistedMixedLedgerRunsThroughHostActionsAndKeepsItsOwnership(): Unit = runBlocking {
        val plan = JSONObject(raw)
        plan.getJSONArray("needs").put(JSONObject().put("id", "help").put("type", "action")
            .put("label", "Справка").put("description", "Дополнительная справка"))
        val assembly = build(plan.toString())
        assertEquals(listOf("help"), assembly.holes.single().needIds)
        val spec = ToolSpec.parsePersisted(ToolComposition.complete(assembly, fill).json)
        val composition = checkNotNull(spec.composition)
        exercise(spec) { event, state ->
            checkNotNull(composition.applyPartEvent(event, spec.ui, state)).also {
                assertEquals(0, it.getInt("fill_value"))
                assertEquals(setOf("fill_value"), composition.fillState(it).keys().asSequence().toSet())
            }
        }
        val state = JSONObject(spec.initialState)
        val reserved = assembly.parts.single().ids.state.first()
        val forged = JSONObject().put("fill_value", 1).put(reserved, state.get(reserved))
        assertTrue(runCatching { composition.mergeFillState(state, forged) }.isFailure)
    }

    @Test fun invalidLedgerSlotsRemainExplicitHoles(): Unit = runBlocking {
        for ((index, value) in listOf(1 to "Янтарь", 2 to 0, 4 to -5, 4 to "5", 7 to "Викторина")) {
            val plan = JSONObject(raw)
            plan.getJSONArray("constants").getJSONObject(index).put("value", value)
            val assembly = build(plan.toString())
            assertEquals(listOf("scores"), assembly.holes.single().needIds)
            assertTrue(assembly.holes.single().slotFailures.isNotEmpty())
        }
    }

    private suspend fun exercise(spec: ToolSpec, execute: suspend (JSONObject, JSONObject) -> JSONObject) {
        val root = spec.ui.getJSONArray("children").getJSONObject(0).getJSONArray("children")
        val nodes = (0 until root.length()).map(root::getJSONObject)
        fun named(label: String) = nodes.single { it.optString("text") == label }
        val choice = nodes.single { it.getString("type") == "singleChoice" }
        val choices = choice.getJSONArray("options")
        val chalk = (0 until choices.length()).map(choices::getJSONObject).single { it.getString("text") == "Мел" }.get("value")
        val list = nodes.single { it.getString("type") == "list" }
        val history = list.getString("bind")
        var state = spec.validateState(spec.initialState)
        fun balance(team: String): Int = state.getInt(named(team).getString("bind"))
        fun rows(): List<JSONObject> {
            val historyRows = state.getJSONArray(history)
            return (0 until historyRows.length()).map(historyRows::getJSONObject)
        }
        suspend fun act(node: JSONObject, value: Any? = null) {
            val action = node.getString("action")
            ToolUiCatalog.validateAction(spec.ui, action, value, state)
            val event = JSONObject().put("type", "action").put("actionId", action).put("value", value ?: JSONObject.NULL)
            state = spec.validateState(execute(event, state).toString())
            spec.validateUi(spec.ui, state)
        }

        assertEquals(0, balance("Янтарь"))
        assertEquals(0, balance("Мел"))
        act(named("Подсказка"))
        assertEquals(0, balance("Янтарь"))
        assertTrue(rows().isEmpty())
        assertTrue(ToolUiCatalog.visible(named("Недостаточно очков"), state))

        act(named("Викторина"))
        act(named("Викторина"))
        assertEquals(6, balance("Янтарь"))
        assertFalse(ToolUiCatalog.visible(named("Недостаточно очков"), state))
        act(named("Подсказка"))
        assertEquals(1, balance("Янтарь"))

        act(choice, chalk)
        act(named("Пазл"))
        assertEquals(2, balance("Мел"))
        assertEquals("Мел", rows().first().getString("team"))
        act(named("Отменить последнее"))
        assertEquals(0, balance("Мел"))
        assertEquals(1, balance("Янтарь"))
        assertEquals(listOf(-5, 3, 3), rows().map { it.getInt("points") })
        assertEquals(listOf("Подсказка", "Викторина", "Викторина"), rows().map { it.getString("operation") })
        assertTrue(rows().all { it.getString("team") == "Янтарь" })
        val restored = ToolSpec.parsePersisted(spec.json).validateState(state.toString())
        assertEquals(state.toString(), restored.toString())
    }
}
