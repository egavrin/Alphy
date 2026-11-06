package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.localPartClock
import com.alpha.assistant.tools.projectToolState
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MixedClockHostWorkflowTest {
    // Failure cases: stale occurrence, second sample in one action, a projected write,
    // a fill-owned state leak, and refresh dispatch of the fill open handler.
    @Test fun savedMiniAppShowsTheDueRowAndItsNativeAction(): Unit = runBlocking {
        val fixture = ShellFixture("mixed-clock-visible")
        val sample = localPartClock()
        val spec = spec(sample)
        val tool = ToolRecord(UUID.randomUUID().toString(), 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
        val robot = ShellRobot("mixed-clock-visible")
        val initialDate = sample.localDate.plusDays(1).toString()
        val oldOccurrence = displayRows(spec,tool,sample).getJSONObject(0).getString("id")
        val dueAction = dueList(spec).getJSONArray("rowActions").getJSONObject(0).getString("action")
        fun dueDate(record: ToolRecord) = displayRows(spec,record,sample).getJSONObject(0).getString("date")
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
                store.commitTool(tool, null, true)
                CloudTestActivity.factory = { app -> ShellViewModel(app, offline, fixture.store, fixture.keys).also { fixture.model = it } }
                fixture.launch().use { scenario ->
                    robot.waitUntil { fixture.model?.tools?.tools?.any { it.id == tool.id && it.saved && !it.archived } == true }
                    robot.openDrawer(scenario)
                    robot.click(robot.text(R.string.mini_apps))
                    robot.awaitDrawerClosed(scenario)
                    robot.waitFor(robot.text(R.string.tool_library_content))
                    if (robot.find(spec.title) == null) {
                        assertTrue("The saved-app library must scroll to its first item.", robot.scrollForward())
                    }
                    robot.click(spec.title)
                    robot.waitFor(robot.text(R.string.tool_content))
                    if (robot.find("Отметить") == null) {
                        assertTrue("The mini-app content must scroll to its due item.", robot.scrollForward())
                    }
                    robot.waitFor("Фильтр")
                    robot.waitFor(initialDate)
                    robot.click("Отметить")
                    val model = checkNotNull(fixture.model)
                    robot.waitUntil {
                        assertNull("The visible due action must succeed.", model.tools.error)
                        store.tool(tool.id)?.let { dueDate(it) != initialDate } == true
                    }
                    val saved = checkNotNull(store.tool(tool.id))
                    robot.waitFor(dueDate(saved))
                    robot.waitUntil { !model.tools.busy && saved in model.tools.tools }
                    assertEquals(0, JSONObject(saved.state).getInt("fill_actions"))
                    assertEquals(1, store.revisions(tool.id).size)
                    assertTrue(store.effects(tool.id).isEmpty())

                    scenario.onActivity { model.tools.interact(saved,dueAction,oldOccurrence) }
                    robot.waitUntil { !model.tools.busy && model.tools.error != null }
                    assertEquals(saved, store.tool(tool.id))
                    assertTrue(store.effects(tool.id).isEmpty())
                }
            }
        } finally { fixture.remove() }
    }

    @Test fun refreshCompletionAndReopenKeepDatesAndStorageIndependent(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "mixed-clock-${UUID.randomUUID()}.db"
        val zone = ZoneId.of("America/New_York")
        val instant = Instant.parse("2026-03-09T03:59:00Z")
        val before = PartClockSample.from(instant, zone)
        val after = PartClockSample.from(instant.plusSeconds(60), zone)
        val timezoneChange = PartClockSample.from(instant, ZoneId.of("UTC"))
        var clock = before
        var samples = 0
        val sandbox = SandboxClient(context)
        val generation = ToolGenerationService(offline, sandbox, partClock = { samples++; clock })
        try {
            ToolStore(context, database).use { store ->
                val spec = spec(before)
                val engine = ToolEngine(store, generation)
                val tool = ToolRecord(UUID.randomUUID().toString(), 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
                store.commitTool(tool, null, true)
                val revisionHistory = store.revisions(tool.id)
                val list = dueList(spec)
                val action = list.getJSONArray("rowActions").getJSONObject(0).getString("action")
                val rows = list.getString("bind")
                fun display(record: ToolRecord, sample: PartClockSample) = displayRows(spec,record,sample)

                val today = display(tool, before).getJSONObject(0)
                val tomorrow = display(tool, after).getJSONObject(0)
                assertEquals("Скоро", today.getString("status"))
                assertEquals("Сегодня", tomorrow.getString("status"))
                assertEquals(tomorrow.toString(), display(tool, timezoneChange).getJSONObject(0).toString())
                assertEquals(tool, store.tool(tool.id))
                assertEquals(0, samples)

                try {
                    val opened = engine.openArtifact(tool)
                    assertEquals(1, JSONObject(opened.tool.state).getInt("fill_open"))
                    val durable = engine.flushArtifact(tool.id, tool.revision, opened.sessionGeneration).tool
                    assertEquals(durable, store.tool(tool.id))
                    assertEquals(0, JSONObject(durable.state).getJSONArray(rows).length())
                    clock = after
                    val beforeAction = samples
                    val changed = engine.interact(tool.id, tool.revision, durable.stateVersion, action, tomorrow.getString("id"))
                    assertEquals(beforeAction + 1, samples)
                    assertEquals("2026-03-23", display(changed, after).getJSONObject(0).getString("date"))
                    assertEquals(0, JSONObject(changed.state).getJSONArray(rows).length())
                    assertEquals(1, JSONObject(changed.state).getInt("fill_open"))
                    assertEquals(0, JSONObject(changed.state).getInt("fill_actions"))
                    assertEquals(revisionHistory, store.revisions(tool.id))
                    assertTrue(store.effects(tool.id).isEmpty())

                    assertTrue(runCatching { engine.interact(tool.id, tool.revision, changed.stateVersion, action, tomorrow.getString("id")) }.isFailure)
                    assertEquals(changed, store.tool(tool.id))
                    val filled = engine.interact(tool.id, tool.revision, changed.stateVersion, "fill_action", null)
                    assertEquals(1, JSONObject(filled.state).getInt("fill_actions"))
                    engine.closeArtifact(tool.id, tool.revision, opened.sessionGeneration)
                    val reopened = engine.openArtifact(filled)
                    assertEquals(2, JSONObject(reopened.tool.state).getInt("fill_open"))
                    assertEquals("2026-03-23", display(reopened.tool, after).getJSONObject(0).getString("date"))
                    engine.closeArtifact(tool.id, tool.revision, reopened.sessionGeneration)
                    val saved = checkNotNull(store.tool(tool.id))
                    assertEquals(0, JSONObject(saved.state).getJSONArray(rows).length())
                    assertEquals(revisionHistory, store.revisions(tool.id))
                } finally { engine.closeArtifacts() }
            }
        } finally { context.deleteDatabase(database) }
    }

    private fun dueList(spec: ToolSpec): JSONObject {
        val children = spec.ui.getJSONArray("children").getJSONObject(0).getJSONArray("children")
        return (0 until children.length()).map(children::getJSONObject).single { it.getString("type") == "list" }
    }

    private fun displayRows(spec: ToolSpec, record: ToolRecord, sample: PartClockSample): JSONArray {
        val display = projectToolState(spec, JSONObject(record.state), sample)
        spec.validateUi(JSONObject(record.ui), display)
        return display.getJSONArray(dueList(spec).getString("bind"))
    }

    private suspend fun spec(sample: PartClockSample): ToolSpec {
        val plan = NeedsPlan.parse("""
            {"title":"Мастерская","description":"","language":"ru","inputs":[],"questions":[],
             "constants":[{"id":"days","value":3}],
             "needs":[{"id":"due","type":"due_items","label":"Сроки","description":"Track filter replacements.",
               "uses":{"window":"days"},"items":[{"name":"Фильтр","offsetDays":1,"repeatDays":14}]},
               {"id":"help","type":"action","label":"Help","description":"Custom help"}]}
        """.trimIndent())
        val assembly = PartsGeneration.build(plan, null, false, sample) { body ->
            val questions = JSONObject(body).getJSONArray("questions")
            val answers = JSONArray()
            repeat(questions.length()) { index ->
                answers.put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                    .put("choice", "A").put("confidence", 0.9)
                    .put("probabilities", JSONObject().put("A", 0.9).put("B", 0.1)))
            }
            JSONObject().put("answers", answers).toString()
        }
        return ToolComposition.complete(assembly, """
            {"capabilities":[],"stateSchema":{"fill_open":"number","fill_actions":"number"},
             "initialState":{"fill_open":0,"fill_actions":0},
             "ui":{"type":"column","children":[{"type":"button","text":"Help","action":"fill_action"}]},
             "source":"if (Object.keys(input.state).sort().join(',') !== 'fill_actions,fill_open') throw Error('Foreign state'); if (input.event.type === 'open') input.state.fill_open += 1; if (input.event.type === 'action') { if (input.event.actionId !== 'fill_action') throw Error('Foreign action'); input.state.fill_actions += 1; } return {state:input.state};"}
        """.trimIndent())
    }

    private val offline = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            error("A mixed runtime action must not call a provider.")
        }
    }
}
