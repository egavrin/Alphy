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
class EditableItineraryGenerationWorkflowTest {
    // Failure cases: invented stops, a fabricated start, wrong elapsed time, stale row keys,
    // edits that change identity, invalid durations, data loss after reopen, and fill writes.
    private val raw = """
        {
          "title":"Пересадка в Казани","description":"Локальный план остановок","language":"ru","inputs":[],"questions":[],
          "constants":[{"id":"window","value":300}],
          "needs":[{"id":"layover","type":"editable_itinerary","label":"Пересадка",
            "description":"Сделай план пятичасовой пересадки в Казани. Время начала, названия остановок и минуты на каждой я введу сам. Покажи время остановок по порядку без промежутков, время окончания пересадки и сколько минут осталось после последней остановки.",
            "uses":{"duration":"window"}}]
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

    @Test fun frozenCityRequestUsesOnlyEnteredStopsAndKeepsItsWindow(): Unit = runBlocking {
        val assembly = build()
        val spec = assembly.requireComplete()
        assertEquals("editable_itinerary", assembly.parts.single().partId)
        assertTrue(spec.capabilities.isEmpty())
        assertTrue(spec.surfaces.isEmpty())
        assertEquals(0, spec.collections.length())
        val sandbox = SandboxClient(InstrumentationRegistry.getInstrumentation().targetContext)
        exercise(spec) { event, state ->
            val input = JSONObject().put("artifactId", "city_itinerary_fixture").put("revision", 1)
                .put("state", state).put("event", event)
            val result = sandbox.execute(spec.source, input.toString())
            assertEquals("OK", result.status)
            val output = JSONObject(checkNotNull(result.json))
            assertEquals(setOf("state"), output.keys().asSequence().toSet())
            output.getJSONObject("state")
        }
    }

    @Test fun persistedMixedEditableItineraryKeepsHostRowsAndRejectsFillWrites(): Unit = runBlocking {
        val plan = JSONObject(raw)
        plan.getJSONArray("needs").put(JSONObject().put("id", "help").put("type", "action")
            .put("label", "Справка").put("description", "Дополнительная справка"))
        val assembly = build(plan.toString())
        assertEquals(listOf("help"), assembly.holes.single().needIds)
        val spec = ToolSpec.parsePersisted(ToolComposition.complete(assembly, fill).json)
        val composition = checkNotNull(spec.composition)
        exercise(spec) { event, state ->
            checkNotNull(composition.applyPartEvent(event, spec.ui, state)).also {
                assertEquals(setOf("fill_value"), composition.fillState(it).keys().asSequence().toSet())
                assertEquals(0, it.getInt("fill_value"))
            }
        }
        val initial = JSONObject(spec.initialState)
        val owned = assembly.parts.single().ids.state.first()
        assertTrue(runCatching {
            composition.mergeFillState(initial, JSONObject().put("fill_value", 1).put(owned, initial.get(owned)))
        }.isFailure)
    }

    @Test fun acceptsOtherWindowsAndRejectsInvalidDurations(): Unit = runBlocking {
        for (value in listOf(0, -30, 2.5, "300")) {
            val invalid = JSONObject(raw)
            invalid.getJSONArray("constants").getJSONObject(0).put("value", value)
            val result = build(invalid.toString())
            assertEquals(listOf("layover"), result.holes.single().needIds)
            assertTrue(result.holes.single().slotFailures.isNotEmpty())
        }
        val shorter = JSONObject(raw)
        shorter.getJSONArray("constants").getJSONObject(0).put("value", 90)
        shorter.getJSONArray("constants").put(JSONObject().put("id", "givenStart").put("value", 1430))
        shorter.getJSONArray("needs").getJSONObject(0).getJSONObject("uses").put("start", "givenStart")
        val spec = build(shorter.toString()).requireComplete()
        val controls = children(spec.ui.getJSONArray("children").getJSONObject(0))
        val end = controls.single { it.optString("text") == "Окончание" }
        val state = JSONObject(spec.initialState)
        assertTrue(ToolUiCatalog.visible(end, state))
        assertEquals("01:20 (+1 дн.)", state.getString(end.getString("bind")))
    }

    private fun children(root: JSONObject): List<JSONObject> {
        val nodes = root.getJSONArray("children")
        return (0 until nodes.length()).map(nodes::getJSONObject)
    }

    private suspend fun exercise(spec: ToolSpec, execute: suspend (JSONObject, JSONObject) -> JSONObject) {
        val controls = children(spec.ui.getJSONArray("children").getJSONObject(0))
        val start = controls.single { it.getString("type") == "timeInput" }
        val end = controls.single { it.optString("text") == "Окончание" }
        val remaining = controls.single { it.optString("text") == "Осталось, мин" }.getString("bind")
        val list = controls.single { it.getString("type") == "list" }
        val history = list.getString("bind")
        val input = controls.single { it.getString("type") == "input" }
        val minutes = controls.single { it.getString("type") == "numberInput" }
        val add = controls.single { it.getString("type") == "button" }
        val rowControls = children(list.getJSONObject("rowTemplate"))
        val editName = rowControls.single { it.getString("type") == "input" }
        val editMinutes = rowControls.single { it.getString("type") == "numberInput" }
        val remove = rowControls.single { it.getString("type") == "button" }
        var state = spec.validateState(spec.initialState)
        fun rows(): List<JSONObject> {
            val data = state.getJSONArray(history)
            return (0 until data.length()).map(data::getJSONObject)
        }
        fun ranges() = rows().map { it.getString("range") }
        suspend fun act(node: JSONObject, payload: Any? = null) {
            val id = node.getString("action")
            ToolUiCatalog.validateAction(spec.ui, id, payload, state)
            val event = JSONObject().put("type", "action").put("actionId", id).put("value", payload ?: JSONObject.NULL)
            state = spec.validateState(execute(event, state).toString())
            spec.validateUi(spec.ui, state)
        }
        suspend fun append(name: String, duration: Int) {
            act(input, name)
            act(minutes, duration)
            act(add)
        }
        fun rowEvent(id: String, value: Any?) = JSONObject().put("key", id).put("value", value ?: JSONObject.NULL)

        assertTrue(rows().isEmpty())
        assertFalse(ToolUiCatalog.visible(end, state))
        act(add)
        assertTrue(rows().isEmpty())
        act(start, 600)
        append("Музей", 20)
        append("Книжный магазин", 35)
        assertEquals(listOf("Музей", "Книжный магазин"), rows().map { it.getString("name") })
        assertEquals(listOf("10:00–10:20", "10:20–10:55"), ranges())
        assertEquals("15:00", state.getString(end.getString("bind")))
        assertEquals(245, state.getInt(remaining))

        val saved = ToolSpec.parsePersisted(spec.json).validateState(state.toString())
        assertEquals(state.toString(), saved.toString())
        state = saved
        val first = rows()[0].getString("id")
        val second = rows()[1].getString("id")
        act(editName, rowEvent(first, "Новый музей"))
        act(editMinutes, rowEvent(first, 25))
        assertEquals(listOf(first, second), rows().map { it.getString("id") })
        assertEquals("Новый музей", rows()[0].getString("name"))
        assertEquals(listOf("10:00–10:25", "10:25–11:00"), ranges())
        assertEquals(240, state.getInt(remaining))
        act(editMinutes, rowEvent(first, 2.5))
        assertEquals(listOf("10:00–10:25", "10:25–11:00"), ranges())

        act(remove, rowEvent(first, null))
        assertEquals(listOf(second), rows().map { it.getString("id") })
        assertEquals(listOf("10:00–10:35"), ranges())
        append("Парк", 10)
        assertTrue(rows().none { it.getString("id") == first })
        assertTrue(runCatching { ToolUiCatalog.validateAction(spec.ui, editName.getString("action"), rowEvent(first, "Старая"), state) }.isFailure)
        assertEquals(255, state.getInt(remaining))
    }
}
