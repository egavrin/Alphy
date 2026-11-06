package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DueItemsProjectionWorkflowTest {
    // Failure cases: seeding without a host date, changing durable state on read, stale
    // occurrence actions, fixed-root tampering, fill partition writes, and DST date drift.
    private val raw = """
        {
          "title":"Сроки мастерской","description":"Локальные сроки","language":"ru","inputs":[],"questions":[],
          "constants":[{"id":"days","value":3}],
          "needs":[{"id":"due","type":"due_items","label":"Сроки",
            "description":"Сделай список сроков для мастерской. Срок талона истёк позавчера, бумагу нужно заказать завтра, а фильтр заменить через 10 дней. Фильтр меняют каждые 14 дней. Отсортируй по сроку и отдельно посчитай просроченные записи и записи со сроком в ближайшие три дня. Когда я отмечу замену фильтра, отсчитай его следующий срок от сегодняшнего дня.",
            "uses":{"window":"days"},"items":[{"name":"Талон","offsetDays":-2},
             {"name":"Бумага","offsetDays":1},{"name":"Фильтр","offsetDays":10,"repeatDays":14}]}]
        }
    """.trimIndent()

    private val fill = """
        {"capabilities":[],"stateSchema":{"fill_value":"number"},"initialState":{"fill_value":0},
         "ui":{"type":"column","children":[{"type":"text","text":"Справка"}]},"source":"return {state:input.state};"}
    """.trimIndent()

    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 3, 8)
    private val buildClock = PartClockSample(today, 9 * 60)

    private suspend fun build(plan: String = raw, clock: PartClockSample? = buildClock): PartsAssembly =
        PartsGeneration.build(NeedsPlan.parse(plan), lessonTarget = null, selectPresentation = false, clock = clock) { body ->
            val questions = JSONObject(body).getJSONArray("questions")
            val answers = JSONArray()
            repeat(questions.length()) { index ->
                answers.put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                    .put("choice", "A").put("confidence", 0.9)
                    .put("probabilities", JSONObject().put("A", 0.9).put("B", 0.1)))
            }
            JSONObject().put("answers", answers).toString()
        }

    private suspend fun mixed(): ToolSpec {
        val plan = JSONObject(raw)
        plan.getJSONArray("needs").put(JSONObject().put("id", "help").put("type", "action")
            .put("label", "Справка").put("description", "Дополнительная справка"))
        val assembly = build(plan.toString())
        assertTrue(assembly.requiresReadProjection)
        assertEquals(listOf("help"), assembly.holes.single().needIds)
        return ToolSpec.parsePersisted(ToolComposition.complete(assembly, fill).json)
    }

    private fun partNodes(spec: ToolSpec): List<JSONObject> {
        val children = spec.ui.getJSONArray("children").getJSONObject(0).getJSONArray("children")
        return (0 until children.length()).map(children::getJSONObject)
    }

    private fun entries(state: JSONObject, list: JSONObject): List<JSONObject> {
        val rows = state.getJSONArray(list.getString("bind"))
        return (0 until rows.length()).map(rows::getJSONObject)
    }

    @Test fun frozenDatesUseTheHostDayAndRejectStaleOccurrenceActions(): Unit = runBlocking {
        val spec = mixed()
        val composition = checkNotNull(spec.composition)
        assertTrue(composition.hasReadProjections)
        val controls = partNodes(spec)
        val list = controls.single { it.getString("type") == "list" }
        val overdue = controls.single { it.optString("text") == "Просрочено" }.getString("bind")
        val upcoming = controls.single { it.optString("text") == "В ближайшие 3 дн." }.getString("bind")
        val action = list.getJSONArray("rowActions").getJSONObject(0).getString("action")
        val durable = spec.validateState(spec.initialState)
        val before = durable.toString()
        val shown = composition.projectState(durable, buildClock)
        spec.validateUi(spec.ui, spec.validateState(shown.toString()))
        assertEquals(before, durable.toString())
        assertTrue(entries(durable, list).isEmpty())
        val rows = entries(shown, list)
        assertEquals(listOf("Талон", "Бумага", "Фильтр"), rows.map { it.getString("name") })
        assertEquals(listOf("2026-03-06", "2026-03-09", "2026-03-18"), rows.map { it.getString("date") })
        assertEquals(1, shown.getInt(overdue))
        assertEquals(1, shown.getInt(upcoming))

        fun event(key: String) = JSONObject().put("type", "action").put("actionId", action).put("value", key)
        val filter = event(rows.last().getString("id"))
        assertTrue(runCatching { composition.applyPartEvent(filter, spec.ui, durable) }.isFailure)
        val marked = checkNotNull(composition.applyPartEvent(filter, spec.ui, durable, buildClock))
        assertEquals(before, durable.toString())
        assertTrue(entries(marked, list).isEmpty())
        val reopened = ToolSpec.parsePersisted(spec.json)
        val reopenedState = reopened.validateState(marked.toString())
        val after = checkNotNull(reopened.composition).projectState(reopenedState, buildClock)
        val scheduled = entries(after, list)
        assertEquals(listOf("2026-03-06", "2026-03-09", "2026-03-22"), scheduled.map { it.getString("date") })
        assertNotEquals(rows.last().getString("id"), scheduled.last().getString("id"))
        assertTrue(runCatching { composition.applyPartEvent(filter, spec.ui, marked, buildClock) }.isFailure)
        val earlier = PartClockSample(today.minusDays(4), 9 * 60)
        val sameDate = checkNotNull(composition.applyPartEvent(filter, spec.ui, durable, earlier))
        val sameDateRow = entries(composition.projectState(sameDate, earlier), list).last()
        assertEquals(rows.last().getString("date"), sameDateRow.getString("date"))
        assertNotEquals(rows.last().getString("id"), sameDateRow.getString("id"))
        assertTrue(runCatching { composition.applyPartEvent(filter, spec.ui, sameDate, earlier) }.isFailure)
        val completed = checkNotNull(composition.applyPartEvent(event(rows.first().getString("id")), spec.ui, marked, buildClock))
        assertEquals(listOf("Бумага", "Фильтр"), entries(composition.projectState(completed, buildClock), list).map { it.getString("name") })
        assertTrue(runCatching { composition.applyPartEvent(event(rows.first().getString("id")), spec.ui, completed, buildClock) }.isFailure)
    }

    @Test fun midnightAndDstRefreshOnlyTheViewAndKeepFillIsolated(): Unit = runBlocking {
        val spec = mixed()
        val composition = checkNotNull(spec.composition)
        val state = spec.validateState(spec.initialState)
        val before = state.toString()
        val list = partNodes(spec).single { it.getString("type") == "list" }
        val beforeJump = PartClockSample.from(Instant.parse("2026-03-08T06:59:00Z"), zone)
        val afterJump = PartClockSample.from(Instant.parse("2026-03-08T07:00:00Z"), zone)
        assertEquals(today, beforeJump.localDate)
        assertEquals(today, afterJump.localDate)
        assertEquals(119, beforeJump.minuteOfDay)
        assertEquals(180, afterJump.minuteOfDay)
        assertEquals(composition.projectState(state, beforeJump).toString(), composition.projectState(state, afterJump).toString())

        val instant = Instant.parse("2026-03-09T03:59:00Z")
        val beforeMidnight = PartClockSample.from(instant, zone)
        val afterMidnight = PartClockSample.from(instant.plusSeconds(60), zone)
        val utc = PartClockSample.from(instant, ZoneId.of("UTC"))
        val oldView = composition.projectState(state, beforeMidnight)
        val newView = composition.projectState(state, afterMidnight)
        assertEquals("Скоро", entries(oldView, list)[1].getString("status"))
        assertEquals("Сегодня", entries(newView, list)[1].getString("status"))
        assertEquals(newView.toString(), composition.projectState(state, utc).toString())
        assertEquals(before, state.toString())
        assertEquals(setOf("fill_value"), composition.fillState(newView).keys().asSequence().toSet())
        val displayKey = list.getString("bind")
        assertTrue(runCatching { composition.mergeFillState(state, JSONObject().put("fill_value", 0)
            .put(displayKey, newView.get(displayKey))) }.isFailure)
        assertTrue(runCatching { composition.projectState(newView, afterMidnight) }.isFailure)
        val changed = JSONObject(spec.ui.toString())
        changed.getJSONArray("children").getJSONObject(0).getJSONArray("children").getJSONObject(0).put("text", "Изменено")
        assertTrue(runCatching { spec.validateUi(changed, newView) }.isFailure)
    }

    @Test fun missingClockAndUnsupportedStandaloneSealFailClosed(): Unit = runBlocking {
        val noClock = build(clock = null)
        assertEquals(listOf("due"), noClock.holes.single().needIds)
        assertTrue(noClock.holes.single().slotFailures.isNotEmpty())
        val hostOnly = build()
        assertTrue(hostOnly.requiresReadProjection)
        assertTrue(runCatching { hostOnly.requireComplete() }.isFailure)
        assertTrue(runCatching { ToolComposition.complete(hostOnly, fill) }.isFailure)
        assertFalse(hostOnly.preview.source.contains("Date"))

        for (value in listOf(0, -1, 1.5, "14")) {
            val invalid = JSONObject(raw)
            invalid.getJSONArray("needs").getJSONObject(0).getJSONArray("items").getJSONObject(2).put("repeatDays", value)
            assertTrue(runCatching { NeedsPlan.parse(invalid.toString()) }.exceptionOrNull() is PartsContractFailure)
        }
    }
}
