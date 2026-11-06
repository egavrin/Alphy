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
class StandaloneNativeEditWorkflowTest {
    // Failure cases: provider authority and hidden data, stale bases or row targets,
    // slot reuse, incompatible restore, changed history, and extra commit differences.
    private val clock = PartClockSample(LocalDate.of(2026, 3, 8), 540)
    private val base = NativeEditBase("private-library", "private-artifact", 3, 8, "private-request")
    private val request = """
        {"title":"Workshop","description":"Plans","language":"en","inputs":[],"questions":[],
         "constants":[{"id":"days","value":3}],
         "needs":[
           {"id":"due","type":"due_items","label":"Maintenance","description":"Track dates","uses":{"window":"days"},
            "items":[{"name":"Filter","date":"2026-03-05","repeatDays":14},{"name":"Paper","date":"2026-03-06"}]},
           {"id":"ideas","type":"idea_deck","label":"Break","description":"Pick an idea",
            "ideas":[{"name":"Sketch","minutes":5,"effort":"low"},{"name":"Organize","minutes":10,"effort":"medium"}]},
           {"id":"count","type":"counter","label":"Attempts","description":"Count attempts"}
         ]}
    """.trimIndent()

    private suspend fun sealed(): ToolSpec {
        val assembly = PartsGeneration.build(NeedsPlan.parse(request), null, false, clock) { body ->
            val questions = JSONObject(body).getJSONArray("questions")
            JSONObject().put("answers", jsonArray((0 until questions.length()).map { index ->
                JSONObject().put("id", questions.getJSONObject(index).getString("id")).put("choice", "A")
                    .put("confidence", 0.9).put("probabilities", JSONObject().put("A", 0.9).put("B", 0.1))
            })).toString()
        }
        return ToolComposition.sealStandalone(assembly)
    }

    private fun act(spec: ToolSpec, state: JSONObject, action: String, value: Any): JSONObject =
        checkNotNull(spec.composition?.applyPartEvent(JSONObject().put("type", "action").put("actionId", action)
            .put("value", value), spec.ui, state, clock))

    private fun stateWithHistory(spec: ToolSpec): JSONObject {
        val initial = spec.validateState(spec.initialState)
        val due = act(spec, initial, "p3_due_complete", "due1@0")
        return act(spec, due, "p5_ideas_complete", "idea1@2026-03-08#0")
    }

    private fun operation(op: String, part: String? = null, slot: String? = null): JSONObject = JSONObject()
        .put("op", op).apply { if (part != null) put("part", part); if (slot != null) put("slot", slot) }

    private fun plan(edit: StandaloneNativeEdit, vararg operations: JSONObject): String =
        JSONObject().put("token", JSONObject(edit.providerContext()).getString("token"))
            .put("operations", jsonArray(operations.asIterable())).toString()

    private fun reject(block: () -> Any?) {
        assertTrue("Expected native edit rejection.", runCatching(block).isFailure)
    }

    @Test fun explicitEditsPreserveStateAndAppendStableSlots(): Unit = runBlocking {
        val before = sealed()
        val state = stateWithHistory(before)
        val edit = StandaloneNativeEdit.begin(base, before, state.toString(), clock)
        val context = edit.providerContext()
        listOf("private-", "2026-03", "p3_due", "p5_ideas", "history", "cursor", "Attempts", "source").forEach {
            assertFalse(it, context.contains(it))
        }
        val raw = plan(edit,
            operation("update_app").put("title", "Workshop plan").put("description", "Daily plans"),
            operation("rename_part", "p1").put("label", "Equipment"),
            operation("set_due_window", "p1").put("days", 7),
            operation("update_due", "p1", "s1").put("name", "Air filter").put("repeatDays", 21),
            operation("update_due", "p1", "s2").put("date", "2026-03-12").put("repeatDays", 0),
            operation("append_due", "p1").put("name", "Paint").put("date", "2026-03-20"),
            operation("update_idea", "p2", "s1").put("name", "Draw").put("minutes", 6).put("effort", "high"),
            operation("append_idea", "p2").put("name", "Stretch").put("minutes", 3).put("effort", "low"))
        val result = edit.apply(raw)
        edit.validateCommit(raw, result, base, before, state.toString(), clock)
        val after = ToolSpec.parsePersisted(result.spec)
        val next = after.validateState(result.state)
        assertTrue(after.isSealedStandalone)
        assertEquals(before.source, after.source)
        assertTrue(after.capabilities.isEmpty())
        assertEquals("Workshop plan", after.title)
        assertEquals("Daily plans", after.description)
        assertTrue(after.ui.toString().contains("Equipment"))
        assertEquals(before.schema.toString(), after.schema.toString())
        assertEquals(state.get("p5_count_value"), next.get("p5_count_value"))
        assertEquals(state.getJSONArray("p5_ideas_history").toString(), next.getJSONArray("p5_ideas_history").toString())
        val rows = next.getJSONArray("p3_due_items")
        assertEquals("Air filter", rows.getJSONObject(0).getString("name"))
        assertEquals("2026-03-22", rows.getJSONObject(0).getString("date"))
        assertEquals(1, rows.getJSONObject(0).getInt("occurrence"))
        assertEquals(21, rows.getJSONObject(0).getInt("repeatDays"))
        assertEquals("2026-03-12", rows.getJSONObject(1).getString("date"))
        assertEquals("due3", rows.getJSONObject(2).getString("id"))
        assertEquals(10, next.getInt("p5_ideas_limit"))
        reject { act(after, next, "p3_due_complete", "due1@1") }
        reject { act(after, next, "p5_ideas_complete", "idea2@2026-03-08#1") }
        reject { ToolSpec.forRestore(after, before) }
    }

    @Test fun retirementAndRestorationKeepTheSameIdentitiesAndHistory(): Unit = runBlocking {
        val before = sealed()
        val state = stateWithHistory(before)
        val edit = StandaloneNativeEdit.begin(base, before, state.toString(), clock)
        val retired = edit.apply(plan(edit, operation("retire", "p1", "s1"), operation("retire", "p2", "s1")))
        val middle = ToolSpec.parsePersisted(retired.spec)
        assertEquals(state.toString(), retired.state)
        val hidden = checkNotNull(middle.composition).projectState(JSONObject(retired.state), clock)
        assertEquals("due2", hidden.getJSONArray("p3_due_display").getJSONObject(0).getString("id").substringBefore('@'))
        assertEquals(1, hidden.getInt("p5_ideas_completed"))
        val nextBase = base.copy(revision = 4, stateVersion = 9, requestId = "second-private-request")
        val reopen = StandaloneNativeEdit.begin(nextBase, middle, retired.state, clock)
        val restored = reopen.apply(plan(reopen, operation("restore", "p1", "s1"), operation("restore", "p2", "s1"),
            operation("append_idea", "p2").put("name", "Stretch").put("minutes", 2).put("effort", "low")))
        val after = ToolSpec.parsePersisted(restored.spec)
        val next = after.validateState(restored.state)
        val visible = checkNotNull(after.composition).projectState(next, clock)
        assertEquals(1, visible.getInt("p5_ideas_completed"))
        assertEquals("due1", next.getJSONArray("p3_due_items").getJSONObject(0).getString("id"))
        assertEquals(1, next.getJSONArray("p3_due_items").getJSONObject(0).getInt("occurrence"))
        assertEquals(0, next.getJSONArray("p5_ideas_history").getJSONObject(0).getInt("idea"))
        val newClock = clock.copy(localDate = clock.localDate.plusDays(1))
        val nextDay = after.composition.projectState(next, newClock)
        assertEquals(0, nextDay.getInt("p5_ideas_completed"))
        assertEquals("Sketch", nextDay.getJSONArray("p5_ideas_display").getJSONObject(0).getString("name"))
        val part = JSONObject(restored.spec).getJSONObject("composition").getJSONArray("parts").getJSONObject(1)
        assertEquals("Stretch", part.getJSONObject("config").getJSONArray("ideas").getJSONObject(2).getString("name"))
    }

    @Test fun forgedOrAmbiguousPlansFailBeforeProducingAChange(): Unit = runBlocking {
        val before = sealed()
        val state = stateWithHistory(before)
        val edit = StandaloneNativeEdit.begin(base, before, state.toString(), clock)
        val cases = listOf(
            operation("replace_source", "p1").put("source", "return {};"),
            operation("rename_part", "p3").put("label", "Ineligible"),
            operation("update_due", "p1", "s3").put("date", "2026-03-20"),
            operation("update_due", "p1", "s1").put("date", "tomorrow"),
            operation("update_due", "p1", "s1").put("date", "2026-03-09").put("offsetDays", 1),
            operation("append_due", "p1").put("name", "Paint").put("date", "2026-03-09").put("offsetDays", 1),
            operation("update_due", "p1", "s1").put("offsetDays", 0.5),
            operation("update_due", "p1", "s1").put("offsetDays", PLAN_NUMBER_LIMIT + 1),
            operation("update_due", "p1", "s1").put("repeatDays", -1),
            operation("update_due", "p1", "s1").put("occurrence", 0),
            operation("append_due", "p1").put("name", "Paint"),
            operation("append_idea", "p2").put("name", "Stretch").put("minutes", 0).put("effort", "low"),
            operation("update_idea", "p1", "s1").put("name", "Wrong type"),
            operation("restore", "p2", "s1"),
            operation("update_app").put("capabilities", JSONArray())
        )
        cases.forEach { bad -> reject { edit.apply(plan(edit, bad)) } }
        reject { edit.apply(plan(edit, operation("retire", "p1", "s1"), operation("update_due", "p1", "s1").put("name", "Conflict"))) }
        val raw = JSONObject(plan(edit, operation("retire", "p1", "s1"))).put("token", "another-request")
        reject { edit.apply(raw.toString()) }
        assertEquals("2026-03-22", state.getJSONArray("p3_due_items").getJSONObject(0).getString("date"))
        val forged = JSONObject(state.toString())
        forged.getJSONArray("p3_due_items").getJSONObject(0).put("id", "unknown")
        reject { StandaloneNativeEdit.begin(base, before, forged.toString(), clock) }
    }

    @Test fun commitRejectsStaleRequestBaseStateAndClockOrExtraChanges(): Unit = runBlocking {
        val before = sealed()
        val state = stateWithHistory(before)
        val edit = StandaloneNativeEdit.begin(base, before, state.toString(), clock)
        val raw = plan(edit, operation("set_due_window", "p1").put("days", 9))
        val result = edit.apply(raw)
        edit.validateCommit(raw, result, base, before, state.toString(), clock.copy(minuteOfDay = 541))
        listOf(base.copy(libraryId = "other"), base.copy(artifactId = "other"), base.copy(requestId = "other"),
            base.copy(revision = 4), base.copy(stateVersion = 9)).forEach {
            reject { edit.validateCommit(raw, result, it, before, state.toString(), clock) }
        }
        reject { edit.validateCommit(raw, result, base, before, before.initialState, clock) }
        reject { edit.validateCommit(raw, result, base, before, state.toString(), clock.copy(localDate = clock.localDate.plusDays(1))) }
        reject { edit.validateCommit(raw, result.copy(spec = before.json), base, before, state.toString(), clock) }
        val injected = JSONObject(result.state).put("p5_count_value", 5)
        reject { edit.validateCommit(raw, result.copy(state = injected.toString()), base, before, state.toString(), clock) }
    }

    @Test fun relativeDueDatesUseTheCapturedLocalDayAcrossDaylightSavingTime(): Unit = runBlocking {
        val before = sealed()
        val state = stateWithHistory(before).toString()
        val zone = ZoneId.of("America/New_York")
        val captured = PartClockSample.from(Instant.parse("2026-03-08T06:59:00Z"), zone)
        val afterShift = PartClockSample.from(Instant.parse("2026-03-08T07:00:00Z"), zone)
        val tomorrow = PartClockSample.from(Instant.parse("2026-03-09T04:00:00Z"), zone)
        val edit = StandaloneNativeEdit.begin(base, before, state, captured)
        listOf("2026-03-08", "2026-03-05", "2026-03-22").forEach { assertFalse(edit.providerContext().contains(it)) }
        val raw = plan(edit,
            operation("update_due", "p1", "s1").put("offsetDays", 1),
            operation("append_due", "p1").put("name", "Paint").put("offsetDays", 0),
            operation("append_due", "p1").put("name", "Sand").put("offsetDays", -1))
        val result = edit.apply(raw)
        val rows = JSONObject(result.state).getJSONArray("p3_due_items")
        assertEquals("2026-03-09", rows.getJSONObject(0).getString("date"))
        assertEquals(1, rows.getJSONObject(0).getInt("occurrence"))
        assertEquals("2026-03-08", rows.getJSONObject(2).getString("date"))
        assertEquals("2026-03-07", rows.getJSONObject(3).getString("date"))
        val reopened = ToolSpec.parsePersisted(result.spec)
        assertFalse(reopened.json.contains("offsetDays"))
        edit.validateCommit(raw, result, base, before, state, afterShift)
        reject { edit.validateCommit(raw, result, base, before, state, tomorrow) }
    }
}
