package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.ToolValueSchema
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ItineraryGenerationWorkflowTest {
    // Failure cases: a gap after the last stop, changing stop order, clearing visits on start
    // changes, coupled visit flags, losing the day offset, invalid slots, and mixed fill ownership.
    private val raw = """
        {
          "title":"Прогулка","description":"План остановок","language":"ru","inputs":[],"questions":[],
          "constants":[{"id":"startTime","value":550},{"id":"transfer","value":10}],
          "needs":[{"id":"walk","type":"itinerary","label":"Прогулка",
            "description":"Сделай план прогулки. Начинаю в 09:10: выставочный зал на 25 минут, сквер на 35 минут и книжный павильон на 20 минут. Между остановками по 10 минут. Покажи время каждой остановки и конец прогулки. Дай менять время старта и отмечать, где я уже побывал.",
            "uses":{"start":"startTime","gap":"transfer"},
            "stops":[{"name":"Выставочный зал","minutes":25},{"name":"Сквер","minutes":35},
                     {"name":"Книжный павильон","minutes":20}]}]
        }
    """.trimIndent()

    private val fill = """
        {"capabilities":[],"stateSchema":{"fill_value":"number"},"initialState":{"fill_value":0},
         "ui":{"type":"column","children":[{"type":"text","text":"Справка"}]},"source":"return {state:input.state};"}
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

    @Test fun frozenWalkKeepsStopOrderAndUpdatesTimesAndVisitMarks(): Unit = runBlocking {
        val assembly = build()
        val spec = assembly.requireComplete()
        assertEquals("itinerary", assembly.parts.single().partId)
        assertTrue(spec.capabilities.isEmpty())
        val sandbox = SandboxClient(InstrumentationRegistry.getInstrumentation().targetContext)
        exercise(spec) { event, state ->
            val input = JSONObject().put("artifactId", "itinerary_fixture").put("revision", 1)
                .put("state", state).put("event", event)
            val result = sandbox.execute(spec.source, input.toString())
            assertEquals("OK", result.status)
            val output = JSONObject(checkNotNull(result.json))
            assertEquals(setOf("state"), output.keys().asSequence().toSet())
            output.getJSONObject("state")
        }
    }

    @Test fun persistedMixedItineraryKeepsHostActionsAndRejectsFillWrites(): Unit = runBlocking {
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
        val initial = JSONObject(spec.initialState)
        val owned = assembly.parts.single().ids.state.first()
        assertTrue(runCatching {
            composition.mergeFillState(initial, JSONObject().put("fill_value", 1).put(owned, initial.get(owned)))
        }.isFailure)
    }

    @Test fun invalidSlotsLeaveHolesAndTwoStopsCanCrossMidnight(): Unit = runBlocking {
        for ((index, value) in listOf(0 to 1440, 0 to "09:10", 1 to -1)) {
            val plan = JSONObject(raw)
            plan.getJSONArray("constants").getJSONObject(index).put("value", value)
            val result = build(plan.toString())
            assertEquals(listOf("walk"), result.holes.single().needIds)
            assertTrue(result.holes.single().slotFailures.isNotEmpty())
        }
        for (value in listOf(0, 1.5, "25")) {
            val invalid = JSONObject(raw)
            invalid.getJSONArray("needs").getJSONObject(0).getJSONArray("stops").getJSONObject(0).put("minutes", value)
            assertTrue(runCatching { NeedsPlan.parse(invalid.toString()) }.exceptionOrNull() is PartsContractFailure)
        }
        val plan = JSONObject(raw)
        plan.getJSONArray("constants").getJSONObject(0).put("value", 1430)
        plan.getJSONArray("needs").getJSONObject(0).getJSONArray("stops").remove(2)
        val spec = build(plan.toString()).requireComplete()
        val nodes = childNodes(spec)
        val history = nodes.single { it.getString("type") == "list" }.getString("bind")
        val end = nodes.single { it.getString("type") == "metric" }.getString("bind")
        val state = JSONObject(spec.initialState)
        assertEquals(listOf("Выставочный зал", "Сквер"), rows(state, history).map { it.getString("name") })
        assertEquals(listOf("23:50–00:15 (+1 дн.)", "00:25 (+1 дн.)–01:00 (+1 дн.)"),
            rows(state, history).map { it.getString("range") })
        assertEquals("01:00 (+1 дн.)", state.getString(end))
    }

    @Test fun itineraryUsesTheExistingNativeListBudget(): Unit = runBlocking {
        val plan = JSONObject(raw)
        val stops = JSONArray()
        repeat(ToolValueSchema.LIST_MAX_ITEMS) { index ->
            stops.put(JSONObject().put("name", "Остановка ${index + 1}").put("minutes", 1))
        }
        plan.getJSONArray("needs").getJSONObject(0).put("stops", stops)
        val spec = build(plan.toString()).requireComplete()
        val history = childNodes(spec).single { it.getString("type") == "list" }.getString("bind")
        val saved = spec.validateState(spec.initialState)
        assertEquals(ToolValueSchema.LIST_MAX_ITEMS, rows(saved, history).size)
        spec.validateUi(spec.ui, saved)
        println("Itinerary budget fixture: ${rows(saved, history).size} stops, ${spec.initialState.toByteArray().size} state bytes.")
    }

    private fun childNodes(spec: ToolSpec): List<JSONObject> {
        val children = spec.ui.getJSONArray("children").getJSONObject(0).getJSONArray("children")
        return (0 until children.length()).map(children::getJSONObject)
    }

    private fun rows(state: JSONObject, history: String): List<JSONObject> {
        val rows = state.getJSONArray(history)
        return (0 until rows.length()).map(rows::getJSONObject)
    }

    private suspend fun exercise(spec: ToolSpec, execute: suspend (JSONObject, JSONObject) -> JSONObject) {
        val nodes = childNodes(spec)
        val start = nodes.single { it.getString("type") == "timeInput" }
        val list = nodes.single { it.getString("type") == "list" }
        val history = list.getString("bind")
        val end = nodes.single { it.getString("type") == "metric" }.getString("bind")
        val rowControls = list.getJSONObject("rowTemplate").getJSONArray("children")
        val mark = (0 until rowControls.length()).map(rowControls::getJSONObject).single { it.getString("type") == "toggle" }
        var state = spec.validateState(spec.initialState)
        fun schedule() = rows(state, history).map { it.getString("range") } + state.getString(end)
        fun visited() = rows(state, history).map { it.getBoolean("visited") }

        suspend fun act(node: JSONObject, payload: Any) {
            val id = node.getString("action")
            ToolUiCatalog.validateAction(spec.ui, id, payload, state)
            val event = JSONObject().put("type", "action").put("actionId", id).put("value", payload)
            state = spec.validateState(execute(event, state).toString())
            spec.validateUi(spec.ui, state)
        }
        suspend fun visit(index: Int, value: Boolean) {
            val id = rows(state, history)[index].getString("id")
            act(mark, JSONObject().put("key", id).put("value", value))
        }

        assertEquals(listOf("Выставочный зал", "Сквер", "Книжный павильон"), rows(state, history).map { it.getString("name") })
        assertEquals(listOf("09:10–09:35", "09:45–10:20", "10:30–10:50", "10:50"), schedule())
        assertEquals(listOf(false, false, false), visited())
        act(start, 570)
        assertEquals(listOf("09:30–09:55", "10:05–10:40", "10:50–11:10", "11:10"), schedule())
        visit(0, true)
        visit(1, true)
        assertEquals(listOf(true, true, false), visited())
        visit(0, false)
        assertEquals(listOf(false, true, false), visited())
        act(start, 550)
        assertEquals(listOf(false, true, false), visited())
        assertEquals(listOf("09:10–09:35", "09:45–10:20", "10:30–10:50", "10:50"), schedule())
        assertEquals(state.toString(), ToolSpec.parsePersisted(spec.json).validateState(state.toString()).toString())
    }
}
