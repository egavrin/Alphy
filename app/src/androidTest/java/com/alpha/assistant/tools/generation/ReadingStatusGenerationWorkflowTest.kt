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
class ReadingStatusGenerationWorkflowTest {
    // Failure cases: treating a boundary as exclusive, inventing or reordering bands,
    // wrong chart order, invalid input changing readings, and mixed fill changing host state.
    private val raw = """
        {
          "title":"Обороты вентилятора","description":"Журнал показаний","language":"ru","inputs":[],"questions":[],
          "constants":[{"id":"first","value":1200},{"id":"unit","value":"об/мин"}],
          "needs":[{"id":"speed","type":"reading_status","label":"Обороты",
            "description":"Сделай журнал оборотов тестового вентилятора. Первая запись — 1200 об/мин. Мои метки: ниже 1000 — «Медленно», от 1000 до 1499 — «Рабочий режим», от 1500 — «Быстро». Покажи последнее значение и метку, историю от новых к старым и график, если записей две или больше. Дай удалять записи.",
            "uses":{"initial":"first","unit":"unit"},
            "bands":[{"label":"Медленно"},{"label":"Рабочий режим","min":1000},{"label":"Быстро","min":1500}]}]
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

    @Test fun frozenFanReadingsClassifyChartAndDeleteThroughGeneratedActions(): Unit = runBlocking {
        val assembly = build()
        val spec = assembly.requireComplete()
        assertEquals("reading_status", assembly.parts.single().partId)
        assertTrue(spec.capabilities.isEmpty())
        val sandbox = SandboxClient(InstrumentationRegistry.getInstrumentation().targetContext)
        exercise(spec) { event, state ->
            val input = JSONObject().put("artifactId", "reading_status_fixture").put("revision", 1)
                .put("state", state).put("event", event)
            val result = sandbox.execute(spec.source, input.toString())
            assertEquals("OK", result.status)
            val output = JSONObject(checkNotNull(result.json))
            assertEquals(setOf("state"), output.keys().asSequence().toSet())
            output.getJSONObject("state")
        }
    }

    @Test fun persistedMixedReadingsUseHostActionsAndKeepFillIsolated(): Unit = runBlocking {
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

    @Test fun arbitraryOrderedBandsWorkAndInvalidDefinitionsFail(): Unit = runBlocking {
        fun bands(plan: JSONObject) = plan.getJSONArray("needs").getJSONObject(0).getJSONArray("bands")
        for ((row, value) in listOf(0 to 0, 1 to "1000", 2 to 1000, 2 to 500)) {
            val invalid = JSONObject(raw)
            bands(invalid).getJSONObject(row).put("min", value)
            assertTrue(runCatching { NeedsPlan.parse(invalid.toString()) }.exceptionOrNull() is PartsContractFailure)
        }
        val invalidSeed = JSONObject(raw)
        invalidSeed.getJSONArray("constants").getJSONObject(0).put("value", "1200")
        assertTrue(build(invalidSeed.toString()).holes.single().slotFailures.isNotEmpty())

        val arbitrary = JSONObject(raw)
        arbitrary.getJSONArray("constants").getJSONObject(0).put("value", -0.5)
        val custom = JSONArray("""[{"label":"Ниже"},{"label":"На отметке","min":-0.5},
            {"label":"Выше","min":1.2},{"label":"Максимум","min":4.7}]""")
        arbitrary.getJSONArray("needs").getJSONObject(0).put("bands", custom)
        val spec = build(arbitrary.toString()).requireComplete()
        val history = nodes(spec.ui).single { it.getString("type") == "list" }.getString("bind")
        val row = JSONObject(spec.initialState).getJSONArray(history).getJSONObject(0)
        assertEquals(-0.5, row.getDouble("value"), 0.0)
        assertEquals("На отметке", row.getString("status"))
    }

    private fun nodes(root: JSONObject): List<JSONObject> = buildList {
        add(root)
        root.optJSONArray("children")?.let { children ->
            repeat(children.length()) { addAll(nodes(children.getJSONObject(it))) }
        }
    }

    private suspend fun exercise(spec: ToolSpec, execute: suspend (JSONObject, JSONObject) -> JSONObject) {
        val controls = nodes(spec.ui)
        val input = controls.single { it.getString("type") == "input" }.getString("action")
        val add = controls.single { it.getString("type") == "button" && it.getString("text") == "Добавить показание" }
            .getString("action")
        val list = controls.single { it.getString("type") == "list" }
        val history = list.getString("bind")
        val remove = list.getJSONArray("rowActions").getJSONObject(0).getString("action")
        val chart = controls.single { it.getString("type") == "chart" }
        val chartValues = chart.getJSONArray("series").getJSONObject(0).getString("bind")
        val latest = controls.single { it.optString("text") == "Последнее (об/мин)" }.getString("bind")
        val status = controls.single { it.optString("text") == "Метка" && it.getString("type") == "metric" }.getString("bind")
        val invalid = controls.single { it.optString("text") == "Введите число" }
        var state = spec.validateState(spec.initialState)
        fun readings(): List<JSONObject> {
            val rows = state.getJSONArray(history)
            return (0 until rows.length()).map(rows::getJSONObject)
        }
        fun assertLatest(value: Int, label: String) {
            assertEquals(value, state.getInt(latest))
            assertEquals(label, state.getString(status))
        }
        suspend fun act(id: String, value: Any? = null) {
            ToolUiCatalog.validateAction(spec.ui, id, value, state)
            val event = JSONObject().put("type", "action").put("actionId", id).put("value", value ?: JSONObject.NULL)
            state = spec.validateState(execute(event, state).toString())
            spec.validateUi(spec.ui, state)
        }
        suspend fun record(text: String) {
            act(input, text)
            act(add)
        }

        assertEquals(1, readings().size)
        assertLatest(1200, "Рабочий режим")
        assertFalse(ToolUiCatalog.visible(chart, state))
        record("999")
        assertLatest(999, "Медленно")
        assertTrue(ToolUiCatalog.visible(chart, state))
        record("1000")
        assertLatest(1000, "Рабочий режим")
        record("1500")
        assertLatest(1500, "Быстро")
        assertEquals(listOf(1500, 1000, 999, 1200), readings().map { it.getInt("value") })
        val series = state.getJSONArray(chartValues)
        assertEquals(listOf(1200, 999, 1000, 1500), (0 until series.length()).map(series::getInt))

        act(remove, readings().first().getString("id"))
        assertLatest(1000, "Рабочий режим")
        val retained = state.getJSONArray(history).toString()
        record("abc")
        assertEquals(retained, state.getJSONArray(history).toString())
        assertLatest(1000, "Рабочий режим")
        assertTrue(ToolUiCatalog.visible(invalid, state))
        assertEquals(state.toString(), ToolSpec.parsePersisted(spec.json).validateState(state.toString()).toString())
    }
}
