package com.alpha.assistant.surfaces

import com.alpha.assistant.ui.openSeededChat
import com.alpha.assistant.ui.seedOpenChat
import android.content.ContextWrapper
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.surfaces.widgetauthoring.WidgetLayoutStore
import com.alpha.assistant.surfaces.widgetauthoring.WidgetPresentation
import com.alpha.assistant.surfaces.widgetauthoring.WidgetPresentationRow
import com.alpha.assistant.surfaces.widgetauthoring.WidgetSaveProposal
import com.alpha.assistant.tools.ArtifactCoordinator
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WidgetLayoutWorkflowTest {
    @Test fun savedLayoutRestrictsWidgetActionsAndStalePreviewCannotReplaceIt() {
        val fixture = ShellFixture("widget-layout")
        val robot = ShellRobot("widget-layout")
        val calls = AtomicInteger()
        val spec = ToolSpec.parse("""{
            "formatVersion":3,"title":"Widget test","description":"","capabilities":[],
            "stateSchema":{"count":"number","secret":"string"},"initialState":{"count":4,"secret":"fixture-secret"},
            "ui":{"type":"column","children":[{"type":"text","text":"Count","bind":"count"},
                {"type":"button","text":"Add","action":"add"},{"type":"button","text":"Clear","action":"clear"}]},
            "source":"if(input.event.actionId==='add')input.state.count++;if(input.event.actionId==='clear')input.state.count=0;return {state:input.state};"
        }""".trimIndent())
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                assertEquals("alpha-test-only", key)
                assertTrue(request.jsonObject)
                assertFalse(request.search)
                val input = JSONObject(request.turns.single().text)
                assertFalse(input.toString().contains("fixture-secret"))
                assertEquals("count", input.getJSONObject("allowed").getJSONArray("bindings").getString(0))
                val label = if (calls.incrementAndGet() == 1) "Custom total" else "Stale total"
                emit(CloudEvent.RoundComplete("""{"rows":[{"type":"text","label":"$label","binding":"count"},
                    {"type":"button","label":"Custom add","action":"add"}]}""", false))
            }
        }
        val record = ToolRecord("widget-layout", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat", true)
        val context = object : ContextWrapper(fixture.context) {
            override fun getSharedPreferences(name: String, mode: Int) = fixture.context.getSharedPreferences("${fixture.name}-$name", mode)
        }
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
                val layouts = WidgetLayoutStore(store)
                store.saveTool(record, true)
                fixture.store.saveConversation(Conversation("chat", "Widget chat", 1, 1, messages = listOf(
                    Message("artifact", "assistant", "", 1, "complete", toolId = record.id, toolRevision = 1),
                )))
                fixture.store.seedOpenChat("chat")
                fixture.keys.save("alpha-test-only").getOrThrow()
                CloudTestActivity.factory = { app -> ShellViewModel(app, provider, fixture.store, fixture.keys).openSeededChat(fixture.store).also { fixture.model = it } }

                fun draft(label: String) {
                    val edit = robot.text(R.string.widget_layout_edit)
                    robot.reveal(edit)
                    robot.click(edit)
                    robot.edit(robot.text(R.string.widget_layout_instruction), "Show count and add")
                    val generate = robot.text(R.string.widget_layout_generate)
                    robot.reveal(generate)
                    robot.click(generate)
                    robot.reveal(label)
                }
                fixture.launch().use {
                    robot.click(robot.text(R.string.tool_open))
                    robot.click(robot.text(R.string.tool_options))
                    robot.click("Закрепить")
                    robot.click(robot.text(R.string.tool_widget_add))
                    draft("Custom total: 4")
                    val save = robot.text(R.string.widget_save)
                    robot.reveal(save)
                    robot.click(save)
                    robot.reveal(robot.text(R.string.widget_layout_saved))
                    val layout = checkNotNull(layouts.read(record.id))
                    assertEquals(1L, layout.version)
                    assertEquals(record, store.tool(record.id))
                    assertEquals(WidgetPresentationRow.Text("Custom total", "count"), layout.presentation.rows.first())

                    draft("Stale total: 4")
                    val newer = record.copy(stateVersion = 2, state = JSONObject(record.state).put("count", 9).toString())
                    store.commitTool(newer, record, false)
                    robot.reveal(save)
                    robot.click(save)
                    robot.reveal(robot.text(R.string.widget_save_failed))
                    assertEquals(newer, store.tool(record.id))
                    assertEquals(layout.presentation.rows, layouts.read(record.id)?.presentation?.rows)
                }

                ToolWidgets.bind(context, 1301, record.id, WidgetPreferences())
                val current = checkNotNull(store.tool(record.id))
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                runBlocking {
                    assertFalse(ToolWidgets.perform(context, ToolWidgets.actionIntent(context, 1301, current, "clear", null), store, engine, locked = false))
                    assertEquals(current, store.tool(record.id))
                    assertTrue(ToolWidgets.perform(context, ToolWidgets.actionIntent(context, 1301, current, "add", null), store, engine, locked = false))
                }
                assertEquals(10, JSONObject(checkNotNull(store.tool(record.id)).state).getInt("count"))
                runBlocking {
                    val before = checkNotNull(store.tool(record.id))
                    val proposal = WidgetSaveProposal(checkNotNull(layouts.snapshot(before)).base,
                        WidgetPresentation(listOf(WidgetPresentationRow.Text("Read only", "count"))))
                    val intent = ToolWidgets.actionIntent(context, 1301, before, "add", null)
                    val (saved, action) = ArtifactCoordinator.get(store,record.id).ordered {
                        val pendingSave = async(start = CoroutineStart.UNDISPATCHED) { layouts.save(proposal) }
                        val pendingAction = async(start = CoroutineStart.UNDISPATCHED) {
                            ToolWidgets.perform(context, intent, store, engine, locked = false)
                        }
                        pendingSave to pendingAction
                    }
                    assertTrue(saved.await())
                    assertFalse(action.await())
                    assertEquals(before, store.tool(record.id))
                }
                assertEquals(2, calls.get())
            }
        } finally {
            context.getSharedPreferences("alpha-widgets", ContextWrapper.MODE_PRIVATE).edit().clear().commit()
            fixture.remove()
        }
    }
}
