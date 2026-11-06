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
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class QuickLogGenerationWorkflowTest {
    // Failure cases: settings counted as readings, inert taps, Undo reaching across days,
    // lost history, changed preview state, invalid slot values, and mixed authority.
    private val request = "Журнал воды. Кнопка записывает 250 мл, цель 2000 мл. Сегодня уже выпито 300 и 100 мл. Показывай итог за день, историю и отмену последней записи."

    private val select: JevAsk = { raw ->
        val questions = JSONObject(raw).getJSONArray("questions")
        val answers = JSONArray()
        repeat(questions.length()) { index ->
            val question = questions.getJSONObject(index)
            val choices = question.getJSONObject("choices")
            val scores = JSONObject()
            choices.keys().forEach { key -> scores.put(key, if (key == "A") 0.9 else 0.1 / (choices.length() - 1)) }
            answers.put(JSONObject().put("id", question.getString("id")).put("choice", "A")
                .put("confidence", 0.9).put("probabilities", scores))
        }
        JSONObject().put("answers", answers).toString()
    }

    private fun plan(initial: Boolean = true, amount: Any = 250, unit: String = "мл", goal: Int = 2000): JSONObject {
        val uses = JSONObject()
        val constants = JSONArray()
        fun slot(role: String, value: Any) {
            val id = "value${constants.length()}"
            uses.put(role, id)
            constants.put(JSONObject().put("id", id).put("value", value))
        }
        slot("amount", amount)
        slot("unit", unit)
        slot("goal", goal)
        slot("button", "Записать стакан")
        if (initial) {
            slot("initial1", 300)
            slot("initial2", 100)
        }
        return JSONObject().put("title", "Вода").put("description", "Дневной журнал").put("language", "ru")
            .put("inputs", JSONArray()).put("questions", JSONArray()).put("constants", constants)
            .put("needs", JSONArray().put(JSONObject().put("id", "water").put("type", "quick_log")
                .put("label", "Вода").put("description", request).put("uses", uses)))
    }

    private suspend fun build(raw: JSONObject): PartsAssembly =
        PartsGeneration.build(NeedsPlan.parse(raw.toString()), lessonTarget = null, selectPresentation = true, ask = select)

    private class Run(private val spec: ToolSpec, initial: JSONObject = JSONObject(spec.initialState)) {
        private val sandbox by lazy { SandboxClient(InstrumentationRegistry.getInstrumentation().targetContext) }
        var state = initial
            private set
        var ui = spec.ui
            private set

        suspend fun event(type: String, day: LocalDate, action: String? = null, payload: Any? = null, minute: Int = 0) {
            val e = JSONObject().put("type", type)
            if (action != null) {
                ToolUiCatalog.validateAction(ui, action, payload, state)
                e.put("actionId", action).put("value", payload ?: JSONObject.NULL)
            }
            val input = JSONObject().put("artifactId", "quick_log_fixture").put("revision", 1)
                .put("state", state).put("event", e)
            val date = "const Date = class extends globalThis.Date {" +
                "constructor(...args) { super(...(args.length ? args : [${day.year}, ${day.monthValue - 1}, ${day.dayOfMonth}, 9, $minute])); }};\n"
            val result = sandbox.execute(date + spec.source, input.toString())
            assertEquals("OK", result.status)
            val output = JSONObject(checkNotNull(result.json))
            assertEquals(setOf("state", "ui"), output.keys().asSequence().toSet())
            state = spec.validateState(output.getJSONObject("state").toString())
            ui = spec.validateUi(output.getJSONObject("ui"), state)
        }
    }

    @Test fun exactWaterRequestCreatesAnInteractiveLocalDayLog(): Unit = runBlocking {
        val assembly = build(plan())
        val spec = assembly.requireComplete()
        val part = assembly.parts.single()
        assertEquals("quick_log", part.partId)
        assertEquals(part.primaryActionId, spec.nativePresentation?.primaryActionId)
        val controls = part.full.getJSONArray("children")
        val log = (0 until controls.length()).map(controls::getJSONObject).single { it.getString("type") == "list" }
        val history = log.getString("bind")
        val initial = JSONObject(spec.initialState)
        val readings = initial.getJSONArray(history)
        assertEquals(2, readings.length())
        assertEquals(400, (0 until readings.length()).sumOf { readings.getJSONObject(it).getInt("amount") })
        val firstDay = LocalDate.parse(readings.getJSONObject(0).getString("day"))
        val run = Run(spec, initial)
        val add = checkNotNull(part.primaryActionId)
        val undo = part.secondaryActionIds.single()

        run.event("preview", firstDay)
        assertEquals(initial.toString(), run.state.toString())
        assertTrue(run.ui.toString().contains("Сегодня: 400 из 2000 мл"))
        assertEquals("Записать стакан", checkNotNull(ToolUiCatalog.action(run.ui, add)).getString("text"))
        run.event("action", firstDay, add)
        assertEquals(3, run.state.getJSONArray(history).length())
        assertEquals(250, run.state.getJSONArray(history).getJSONObject(0).getInt("amount"))
        assertTrue(run.ui.toString().contains("Сегодня: 650 из 2000 мл"))
        run.event("action", firstDay, undo)
        assertEquals(readings.toString(), run.state.getJSONArray(history).toString())
        run.event("render", firstDay.plusDays(1))
        assertEquals(readings.toString(), run.state.getJSONArray(history).toString())
        assertTrue(run.ui.toString().contains("Сегодня: 0 из 2000 мл"))
        assertTrue(run.ui.toString().contains("Вчера: 400 мл"))
        assertNull(ToolUiCatalog.action(run.ui, undo))
        run.event("action", firstDay.plusDays(1), add)
        assertTrue(run.ui.toString().contains("Сегодня: 250 из 2000 мл"))
        assertEquals(3, run.state.getJSONArray(history).length())
        val stored = ToolSpec.parsePersisted(spec.json)
        assertEquals(spec.source, stored.source)
        assertEquals(3, stored.validateState(run.state.toString()).getJSONArray(history).length())
    }

    private fun paperPlan() = JSONObject("""
        {"title":"Нарезанные листы","description":"Дневной журнал","language":"ru","inputs":[],"questions":[],
         "constants":[{"id":"three","value":3},{"id":"five","value":5},{"id":"sheets","value":"лист."},
          {"id":"target","value":20},{"id":"addThree","value":"Добавить 3 листа"},{"id":"addFive","value":"Добавить 5 листов"}],
         "needs":[{"id":"paper","type":"quick_log","label":"Листы",
          "description":"Сделай быстрый журнал нарезанных листов бумаги. Две кнопки: добавить 3 листа или 5 листов. Моя цель — 20 листов за день. Покажи сумму за сегодня, время каждой записи и вчерашнюю сумму. Нужны отмена последнего добавления и удаление отдельной записи.",
          "uses":{"amount":"three","alternateAmount":"five","unit":"sheets","goal":"target","button":"addThree","alternateButton":"addFive"}}]}
    """.trimIndent())

    // Frozen reference-quick-log case. Each tap must retain its amount and time.
    @Test fun frozenPaperCaseLogsThreeAndFiveAndRollsOver(): Unit = runBlocking {
        val assembly = build(paperPlan())
        val spec = assembly.requireComplete()
        val children = assembly.parts.single().full.getJSONArray("children")
        val controls = (0 until children.length()).map(children::getJSONObject)
        fun button(label: String) = controls.single { it.optString("text") == label }.getString("action")
        val log = controls.single { it.getString("type") == "list" }
        val history = log.getString("bind")
        val remove = log.getJSONArray("rowActions").getJSONObject(0).getString("action")
        val run = Run(spec)
        val day = LocalDate.of(2026, 11, 3)
        val three = button("Добавить 3 листа")
        val five = button("Добавить 5 листов")
        assertNotEquals(three, five)
        assertEquals(0, run.state.getJSONArray(history).length())

        run.event("action", day, three, minute = 0)
        run.event("action", day, three, minute = 1)
        run.event("action", day, five, minute = 2)
        val rows = run.state.getJSONArray(history)
        val entries = (0 until rows.length()).map(rows::getJSONObject)
        assertEquals(listOf(5, 3, 3), entries.map { it.getInt("amount") })
        assertEquals(listOf("09:02", "09:01", "09:00"), entries.map { it.getString("at").substringAfter(' ') })
        assertTrue(run.ui.toString().contains("Сегодня: 11 из 20 лист."))

        run.event("action", day, button("Отменить последнюю"), minute = 3)
        assertTrue(run.ui.toString().contains("Сегодня: 6 из 20 лист."))
        run.event("action", day, remove, entries.last().getString("id"), minute = 4)
        val retained = run.state.getJSONArray(history)
        assertEquals(1, retained.length())
        assertTrue(retained.getJSONObject(0).getString("at").endsWith("09:01"))
        assertTrue(run.ui.toString().contains("Сегодня: 3 из 20 лист."))

        run.event("render", day.plusDays(1))
        assertEquals(retained.toString(), run.state.getJSONArray(history).toString())
        assertTrue(run.ui.toString().contains("Сегодня: 0 из 20 лист."))
        assertTrue(run.ui.toString().contains("Вчера: 3 лист."))
        assertNull(ToolUiCatalog.action(run.ui, button("Отменить последнюю")))
    }

    @Test fun duplicateOrIncompleteAlternateButtonLeavesAHole(): Unit = runBlocking {
        for (alternate in listOf(0, 3, "5", 1000001)) {
            val raw = paperPlan()
            raw.getJSONArray("constants").getJSONObject(1).put("value", alternate)
            assertTrue(build(raw).holes.single().slotFailures.isNotEmpty())
        }
        val withoutAmount = paperPlan()
        withoutAmount.getJSONArray("constants").remove(1)
        withoutAmount.getJSONArray("needs").getJSONObject(0).getJSONObject("uses").remove("alternateAmount")
        assertTrue(build(withoutAmount).holes.single().slotFailures.isNotEmpty())
        val duplicateLabel = paperPlan()
        duplicateLabel.getJSONArray("constants").getJSONObject(5).put("value", "Добавить 3 листа")
        assertTrue(build(duplicateLabel).holes.single().slotFailures.isNotEmpty())
    }

    @Test fun provisionalCapacityPreservesAllRowsAndDisablesBothAdds(): Unit = runBlocking {
        val assembly = build(paperPlan())
        val spec = assembly.requireComplete()
        val children = assembly.parts.single().full.getJSONArray("children")
        val nodes = (0 until children.length()).map(children::getJSONObject)
        val history = nodes.single { it.getString("type") == "list" }.getString("bind")
        val addActions = nodes.filter { it.optString("text").startsWith("Добавить ") }.map { it.getString("action") }
        assertEquals(2, addActions.size)
        val day = LocalDate.of(2026, 11, 3)
        val rows = JSONArray()
        repeat(99) { index ->
            rows.put(JSONObject().put("id", "seed$index").put("amount", 3).put("day", day.toString()).put("at", "$day 08:00"))
        }
        val seeded = JSONObject(spec.initialState).put(history, rows)
        val run = Run(spec, spec.validateState(seeded.toString()))
        run.event("render", day)
        run.event("action", day, addActions[1])
        val full = run.state.getJSONArray(history)
        assertEquals(100, full.length())
        assertEquals(5, full.getJSONObject(0).getInt("amount"))
        assertEquals((0 until 99).map { "seed$it" }, (1 until 100).map { full.getJSONObject(it).getString("id") })
        addActions.forEach { assertNull(ToolUiCatalog.action(run.ui, it)) }
        assertTrue(run.ui.toString().contains("Журнал заполнен"))
        println("Quick-log capacity fixture: ${full.length()} entries, ${run.state.toString().toByteArray().size} state bytes, " +
            "${run.ui.toString().toByteArray().size} UI bytes.")
    }

    @Test fun settingsNeverSeedReadingsAndUnsupportedConfigurationLeavesAHole(): Unit = runBlocking {
        val empty = build(plan(initial = false, unit = "г", goal = 1800))
        val part = empty.parts.single()
        val children = part.full.getJSONArray("children")
        val list = (0 until children.length()).map(children::getJSONObject).single { it.getString("type") == "list" }
        val spec = empty.requireComplete()
        assertEquals(0, JSONObject(spec.initialState).getJSONArray(list.getString("bind")).length())
        assertTrue(spec.ui.toString().contains("Сегодня: 0 из 1800 г"))
        for (value in listOf(0, -1, "250", 1000001)) {
            val invalid = build(plan(amount = value))
            assertEquals(listOf("water"), invalid.holes.single().needIds)
            assertTrue(invalid.holes.single().slotFailures.isNotEmpty())
        }
    }

    @Test fun mixedAssemblyKeepsTheClockDependentNeedAsAnExplicitHole(): Unit = runBlocking {
        val raw = plan().apply {
            getJSONArray("needs")
                .put(JSONObject().put("id", "cups").put("type", "counter").put("label", "Чашки").put("description", "Счёт чашек"))
                .put(JSONObject().put("id", "custom").put("type", "action").put("label", "Ещё").put("description", "Отдельная функция"))
        }
        val assembled = build(raw)
        assertEquals(listOf("cups"), assembled.parts.map { it.needId })
        assertEquals(listOf("water", "custom"), assembled.holes.flatMap { it.needIds })
        assertFalse(assembled.preview.source.contains("new Date"))

        raw.getJSONArray("needs").getJSONObject(2).put("type", "other")
        val wholeScreen = build(raw)
        assertTrue(wholeScreen.parts.isEmpty())
        assertEquals(HoleKind.SCREEN, wholeScreen.holes.single().kind)
        assertEquals(listOf("water", "cups", "custom"), wholeScreen.holes.single().needIds)
    }
}
