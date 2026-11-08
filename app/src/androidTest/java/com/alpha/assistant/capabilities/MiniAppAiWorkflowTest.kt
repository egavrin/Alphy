package com.alpha.assistant.capabilities

import com.alpha.assistant.ui.openSeededChat
import com.alpha.assistant.ui.seedOpenChat
import android.app.AlertDialog
import androidx.lifecycle.Lifecycle
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MiniAppAiWorkflowTest {
    @Test fun visibleActionsUseExistingConsentWithoutNetworkGrantAndCancelWhenHidden() {
        val fixture = ShellFixture("mini-app-ai")
        val robot = ShellRobot("mini-app-ai")
        val prompts = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                assertEquals("alpha-test-only", key)
                assertFalse(request.search)
                assertFalse(request.chatDecision)
                val prompt = request.turns.single().text
                prompts += prompt
                if (prompt.startsWith("pending")) try { awaitCancellation() } finally { cancelled += prompt }
                emit(CloudEvent.RoundComplete("Answer for $prompt", false))
            }
        }
        val spec = ToolSpec.parse("""{
            "formatVersion":3,"title":"AI test","description":"","capabilities":["ai.ask"],"capabilityResults":true,
            "stateSchema":{"prompt":"string","answer":"string"},"initialState":{"prompt":"first","answer":""},
            "ui":{"type":"column","children":[{"type":"text","text":"Result","bind":"answer"},
                {"type":"input","text":"Prompt","action":"edit","bind":"prompt"},{"type":"button","text":"Ask DeepSeek","action":"ask"}]},
            "source":"if(input.event.actionId==='edit') input.state.prompt=input.event.value; if(input.event.actionId==='ask') return {state:input.state,effects:[{id:'ask',capability:'ai.ask',args:{prompt:input.state.prompt}}]}; if(input.event.type==='capabilityResults'){ const result=input.event.entries[0]; input.state.answer=result.data.text||result.status; } return {state:input.state};"
        }""".trimIndent())
        val record = ToolRecord("ai-app", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat", true)
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
                store.saveTool(record, true)
                fixture.store.saveConversation(Conversation("chat", "AI chat", 1, 1, messages = listOf(
                    Message("artifact", "assistant", "", 1, "complete", toolId = record.id, toolRevision = 1),
                )))
                fixture.store.seedOpenChat("chat")
                CloudTestActivity.factory = { app ->
                    ShellViewModel(app, provider, fixture.store, fixture.keys).openSeededChat(fixture.store).also { fixture.model = it }
                }
                fun edit(prompt: String) {
                    robot.reveal("Prompt", forward = null)
                    robot.edit("Prompt", prompt)
                    robot.click(robot.text(R.string.tool_generated_apply, "Prompt"))
                    robot.waitUntil { JSONObject(checkNotNull(store.tool(record.id)).state).getString("prompt") == prompt && fixture.model?.tools?.busy == false }
                }
                fun ask() {
                    robot.reveal("Ask DeepSeek", forward = null)
                    robot.click("Ask DeepSeek")
                }
                fun result(answer: String) {
                    robot.waitUntil { JSONObject(checkNotNull(store.tool(record.id)).state).getString("answer") == answer }
                    assertTrue(robot.reveal("Result: $answer", forward = null).isVisibleToUser)
                }
                fixture.launch().use { scenario ->
                    robot.click(robot.text(R.string.tool_open))
                    assertFalse(store.networkGrantEnabled(record.id))
                    ask()
                    result("denied")
                    assertTrue(prompts.isEmpty())

                    fixture.keys.save("alpha-test-only").getOrThrow()
                    ask()
                    result("Answer for first")
                    edit("second")
                    ask()
                    result("Answer for second")
                    assertEquals(listOf("first", "second"), prompts.toList())

                    edit("pending-cover")
                    ask()
                    robot.waitUntil { prompts.lastOrNull() == "pending-cover" }
                    var cover: AlertDialog? = null
                    scenario.onActivity { activity -> cover = AlertDialog.Builder(activity).setTitle("Cover AI").show() }
                    robot.waitFor("Cover AI")
                    assertEquals(Lifecycle.State.RESUMED, scenario.state)
                    robot.waitUntil { "pending-cover" in cancelled && fixture.model?.tools?.busy == false }
                    assertEquals("cancelled", store.effects(record.id).last().result.status)
                    scenario.onActivity { cover?.dismiss() }
                    result("Answer for second")

                    edit("pending-background")
                    ask()
                    robot.waitUntil { prompts.lastOrNull() == "pending-background" }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    robot.waitUntil { "pending-background" in cancelled && fixture.model?.tools?.busy == false }
                    assertEquals("cancelled", store.effects(record.id).last().result.status)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    result("Answer for second")
                    assertEquals(listOf("first", "second", "pending-cover", "pending-background"), prompts.toList())
                    assertFalse(store.networkGrantEnabled(record.id))
                    robot.diagnostics.capture("no-grant")
                }
            }
        } finally { fixture.remove() }
    }
}
