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
class IdeaDeckProjectionWorkflowTest {
    // Failure cases: wrong filter, cycling an old card, duplicate completion, losing
    // yesterday's history, invalid inputs, and writes from the mixed fill partition.
    private val raw = """
        {
          "title":"Занятия на перерыв","description":"Локальные идеи","language":"ru","inputs":[],"questions":[],"constants":[],
          "needs":[{"id":"breakIdeas","type":"idea_deck","label":"Перерыв",
            "description":"Сделай подборку занятий на перерыв. Идеи: «Разобрать карандаши» — 2 минуты и мало сил, «Набросать эскиз» — 5 минут и мало сил, «Разложить инструменты» — 5 минут и средне, «Разобрать полку» — 12 минут и много сил. Показывай одну идею с кнопкой «Другая». Нужны фильтры по времени и силам и отметка «Сделано сегодня».",
            "ideas":[{"name":"Разобрать карандаши","minutes":2,"effort":"low"},
             {"name":"Набросать эскиз","minutes":5,"effort":"low"},
             {"name":"Разложить инструменты","minutes":5,"effort":"medium"},
             {"name":"Разобрать полку","minutes":12,"effort":"high"}]}]
        }
    """.trimIndent()

    private val fill = """
        {"capabilities":[],"stateSchema":{"fill_value":"number"},"initialState":{"fill_value":0},
         "ui":{"type":"column","children":[{"type":"text","text":"Справка"}]},"source":"return {state:input.state};"}
    """.trimIndent()

    private val day = LocalDate.of(2026, 3, 8)
    private val clock = PartClockSample(day, 9 * 60)

    private suspend fun build(plan: String = raw, sample: PartClockSample? = clock): PartsAssembly =
        PartsGeneration.build(NeedsPlan.parse(plan), lessonTarget = null, selectPresentation = false, clock = sample) { body ->
            val questions = JSONObject(body).getJSONArray("questions")
            val answers = JSONArray()
            repeat(questions.length()) { index ->
                answers.put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                    .put("choice", "A").put("confidence", 0.9).put("probabilities", JSONObject().put("A", 0.9).put("B", 0.1)))
            }
            JSONObject().put("answers", answers).toString()
        }

    private suspend fun mixed(): ToolSpec {
        val plan = JSONObject(raw)
        plan.getJSONArray("needs").put(JSONObject().put("id", "help").put("type", "action")
            .put("label", "Справка").put("description", "Дополнительная справка"))
        val assembly = build(plan.toString())
        assertTrue(assembly.requiresReadProjection)
        assertEquals("idea_deck", assembly.parts.single().partId)
        assertEquals(listOf("help"), assembly.holes.single().needIds)
        return ToolSpec.parsePersisted(ToolComposition.complete(assembly, fill).json)
    }

    private class Deck(val spec: ToolSpec) {
        val composition = checkNotNull(spec.composition)
        private val children = spec.ui.getJSONArray("children").getJSONObject(0).getJSONArray("children")
        private val nodes = (0 until children.length()).map(children::getJSONObject)
        val list = nodes.single { it.getString("type") == "list" }
        private val actions = list.getJSONArray("rowActions")
        val time = nodes.single { it.getString("type") == "numberInput" }.getString("action")
        val effort = nodes.single { it.getString("type") == "singleChoice" }.getString("action")
        val next = action("Другая")
        val complete = action("Сделано сегодня")
        val available = nodes.single { it.optString("text") == "Доступно" }.getString("bind")
        val completed = nodes.single { it.optString("text") == "Сделано сегодня" && it.getString("type") == "metric" }.getString("bind")

        private fun action(label: String): String =
            (0 until actions.length()).map(actions::getJSONObject).single { it.getString("text") == label }.getString("action")

        fun view(state: JSONObject, clock: PartClockSample): JSONObject =
            composition.projectState(state, clock).also { spec.validateUi(spec.ui, spec.validateState(it.toString())) }

        fun featured(view: JSONObject): JSONObject? = view.getJSONArray(list.getString("bind")).optJSONObject(0)

        fun act(state: JSONObject, clock: PartClockSample, action: String, value: Any): JSONObject {
            val event = JSONObject().put("type", "action").put("actionId", action).put("value", value)
            val next = checkNotNull(composition.applyPartEvent(event, spec.ui, state, clock))
            return spec.validateState(next.toString())
        }
    }

    @Test fun frozenIdeasFilterCycleAndCompleteOnce(): Unit = runBlocking {
        val deck = Deck(mixed())
        var state = deck.spec.validateState(deck.spec.initialState)
        assertEquals(4, deck.view(state, clock).getInt(deck.available))
        state = deck.act(state, clock, deck.time, 5)
        state = deck.act(state, clock, deck.effort, "low")
        val filtered = deck.view(state, clock)
        assertEquals(2, filtered.getInt(deck.available))
        val pencils = checkNotNull(deck.featured(filtered))
        assertEquals("Разобрать карандаши", pencils.getString("name"))
        state = deck.act(state, clock, deck.next, pencils.getString("id"))
        val sketch = checkNotNull(deck.featured(deck.view(state, clock)))
        assertEquals("Набросать эскиз", sketch.getString("name"))
        state = deck.act(state, clock, deck.next, sketch.getString("id"))
        val pencilsAgain = checkNotNull(deck.featured(deck.view(state, clock)))
        assertEquals("Разобрать карандаши", pencilsAgain.getString("name"))
        assertNotEquals(pencils.getString("id"), pencilsAgain.getString("id"))
        assertTrue(runCatching { deck.act(state, clock, deck.next, pencils.getString("id")) }.isFailure)

        state = deck.act(state, clock, deck.complete, pencilsAgain.getString("id"))
        val after = deck.view(state, clock)
        assertEquals(1, after.getInt(deck.completed))
        assertEquals(1, after.getInt(deck.available))
        assertEquals("Набросать эскиз", deck.featured(after)?.getString("name"))
        val saved = state.toString()
        assertTrue(runCatching { deck.act(state, clock, deck.complete, pencilsAgain.getString("id")) }.isFailure)
        assertEquals(saved, state.toString())

        state = deck.act(state, clock, deck.time, 1)
        val empty = deck.view(state, clock)
        assertNull(deck.featured(empty))
        assertEquals(0, empty.getInt(deck.available))
        assertEquals(1, empty.getInt(deck.completed))
    }

    @Test fun localDayAndDstRefreshPreserveDurableHistoryAndRejectOldTargets(): Unit = runBlocking {
        val deck = Deck(mixed())
        val zone = ZoneId.of("America/New_York")
        val beforeJump = PartClockSample.from(Instant.parse("2026-03-08T06:59:00Z"), zone)
        val afterJump = PartClockSample.from(Instant.parse("2026-03-08T07:00:00Z"), zone)
        val state = deck.spec.validateState(deck.spec.initialState)
        val first = checkNotNull(deck.featured(deck.view(state, beforeJump)))
        val complete = deck.act(state, beforeJump, deck.complete, first.getString("id"))
        val durable = complete.toString()
        assertEquals(deck.view(complete, beforeJump).toString(), deck.view(complete, afterJump).toString())
        assertEquals(1, deck.view(complete, afterJump).getInt(deck.completed))
        assertEquals(0, complete.getInt(deck.completed))

        val beforeMidnight = PartClockSample.from(Instant.parse("2026-03-09T03:59:00Z"), zone)
        val afterMidnight = PartClockSample.from(Instant.parse("2026-03-09T04:00:00Z"), zone)
        val oldCard = checkNotNull(deck.featured(deck.view(complete, beforeMidnight)))
        val reopened = Deck(ToolSpec.parsePersisted(deck.spec.json))
        val saved = reopened.spec.validateState(durable)
        val newView = reopened.view(saved, afterMidnight)
        assertEquals(0, newView.getInt(reopened.completed))
        assertEquals(4, newView.getInt(reopened.available))
        assertEquals(durable, saved.toString())
        assertTrue(runCatching { reopened.act(saved, afterMidnight, reopened.complete, oldCard.getString("id")) }.isFailure)
        val again = checkNotNull(reopened.featured(newView))
        val marked = reopened.act(saved, afterMidnight, reopened.complete, again.getString("id"))
        assertEquals(1, reopened.view(marked, afterMidnight).getInt(reopened.completed))
        assertEquals(1, reopened.view(marked, beforeMidnight).getInt(reopened.completed))

        assertEquals(setOf("fill_value"), reopened.composition.fillState(newView).keys().asSequence().toSet())
        assertTrue(runCatching { reopened.composition.mergeFillState(saved, JSONObject().put("fill_value", 0)
            .put(reopened.list.getString("bind"), newView.get(reopened.list.getString("bind")))) }.isFailure)
        assertTrue(runCatching { reopened.composition.projectState(newView, afterMidnight) }.isFailure)
    }

    @Test fun invalidDefinitionsAndTargetsFailAndStandaloneRemainsGated(): Unit = runBlocking {
        for ((field, value) in listOf("minutes" to 0, "minutes" to 1.5, "effort" to "maximum", "effort" to "all")) {
            val invalid = JSONObject(raw)
            invalid.getJSONArray("needs").getJSONObject(0).getJSONArray("ideas").getJSONObject(0).put(field, value)
            assertTrue(runCatching { NeedsPlan.parse(invalid.toString()) }.exceptionOrNull() is PartsContractFailure)
        }
        val noClock = build(sample = null)
        assertEquals(listOf("breakIdeas"), noClock.holes.single().needIds)
        val standalone = build()
        assertTrue(standalone.requiresReadProjection)
        assertTrue(runCatching { standalone.requireComplete() }.isFailure)

        val deck = Deck(mixed())
        val state = deck.spec.validateState(deck.spec.initialState)
        val durable = state.toString()
        assertTrue(runCatching { deck.act(state, clock, deck.time, 2.5) }.isFailure)
        assertTrue(runCatching { deck.act(state, clock, deck.effort, "invalid") }.isFailure)
        assertTrue(runCatching { deck.act(state, clock, deck.complete, "forged") }.isFailure)
        assertEquals(durable, state.toString())
    }
}
