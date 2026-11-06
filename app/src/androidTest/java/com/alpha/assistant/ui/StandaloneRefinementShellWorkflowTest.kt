package com.alpha.assistant.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.R
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Message
import com.alpha.assistant.surfaces.AssistContext
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.generation.NeedsPlan
import com.alpha.assistant.tools.generation.PartClockSample
import com.alpha.assistant.tools.generation.PartsGeneration
import com.alpha.assistant.tools.generation.ToolComposition
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class StandaloneRefinementShellWorkflowTest {
    @Test fun nativeRefinementReviewsBeforeSavingAndPreservesTheCurrentDraft() {
        val fixture = ShellFixture("standalone-refinement-shell")
        val robot = ShellRobot("standalone-refinement-shell")
        val database = "${fixture.store.databaseName}-tools.db"
        val selected = Conversation("selected", "Current chat", 1, 2, draft = Draft("Keep this draft", listOf(
            AssistContext.textAttachment("Keep this reviewed attachment"),
        )))
        val linked = Conversation("linked", "Existing app chat", 1, 1, messages = listOf(
            Message("card", "assistant", "Saved app", 1, "complete", toolId = "linked-app", toolRevision = 1),
        ))
        try {
            fixture.store.saveConversation(selected)
            fixture.store.saveConversation(linked)
            fixture.store.saveNewDraft(Draft("Keep the new-chat draft"))
            fixture.store.saveSetting("selected", selected.id)
            val storedChat = fixture.store.load()
            val sealed = sealedWorkshop()
            assertTrue(sealed.isSealedStandalone)
            val changedState = JSONObject(sealed.initialState).put("p5_count_value", 7).toString()
            sealed.validateState(changedState)
            ToolStore(fixture.context, database).use { tools ->
                val linkedTool = ToolRecord("linked-app", 1, 2, sealed.json, changedState, sealed.ui.toString(), linked.id, saved = true)
                val orphanTool = linkedTool.copy(id = "orphan-app", conversationId = null, lastConversationId = null)
                tools.saveTool(linkedTool, revision = true)
                tools.saveTool(orphanTool, revision = true)
                val savedRevisions = tools.revisions(linkedTool.id)
                val ruleName = "Workshop reminder"
                val rule = AutomationStore(fixture.context, tools.automationDatabase).use { rules ->
                    val saved = rules.save(null, JSONObject()
                        .put("name", ruleName)
                        .put("trigger", JSONObject().put("type", "manual"))
                        .put("conditions", JSONObject())
                        .put("actions", JSONArray().put(JSONObject().put("id", "set").put("capability", "tool.state.set")
                            .put("args", JSONObject().put("toolId", linkedTool.id).put("revision", linkedTool.revision)
                                .put("field", "p5_count_value").put("value", 8)))))
                    rules.approve(saved.id, saved.revision)
                    saved
                }
                val calls = mutableListOf<CloudRequest>()
                val provider = object : ChatProvider {
                    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession,
                        emit: suspend (CloudEvent) -> Unit) {
                        synchronized(calls) { calls.add(request) }
                        val payload = JSONObject(request.turns.single().text)
                        val operation = JSONObject().put("op", "update_due").put("part", "p1")
                            .put("slot", "s1").put("offsetDays", 1)
                        emit(CloudEvent.Text(JSONObject().put("token", payload.getJSONObject("context").getString("token"))
                            .put("operations", JSONArray().put(operation)).toString()))
                    }
                }
                fixture.install()
                CloudTestActivity.factory = { app ->
                    ShellViewModel(app, provider, fixture.store, fixture.keys).also { fixture.model = it }
                }
                fixture.launch().use { scenario ->
                    val model = checkNotNull(fixture.model)
                    robot.waitUntil { !model.loading && model.tools.tools.size == 2 }
                    scenario.onActivity { model.openSurface(linkedTool.id) }
                    robot.waitUntil { !model.surfacePending }
                    val initialSurface = model.surfaceSequence
                    fun assertUnchanged() {
                        assertEquals(storedChat, model.snapshot)
                        assertEquals(storedChat, fixture.store.load())
                        assertEquals(initialSurface, model.surfaceSequence)
                        assertFalse(model.surfacePending)
                        assertTrue(tools.drafts().isEmpty())
                    }
                    fun preview() {
                        robot.edit(robot.text(R.string.tool_native_edit_instruction), "Move the filter deadline to tomorrow")
                        robot.click(robot.text(R.string.tool_native_edit_prepare))
                        robot.waitFor(robot.text(R.string.tool_native_edit_after))
                        robot.click(robot.text(R.string.tool_native_edit_before))
                        robot.click(robot.text(R.string.tool_native_edit_after))
                    }

                    robot.click(robot.text(R.string.tool_refine))
                    preview()
                    robot.reveal(ruleName)
                    assertUnchanged()
                    assertEquals(linkedTool, tools.tool(linkedTool.id))
                    robot.click(robot.text(R.string.cancel))
                    assertUnchanged()
                    assertEquals(savedRevisions, tools.revisions(linkedTool.id))

                    scenario.onActivity { model.refineTool(orphanTool.id) }
                    preview()
                    robot.click(robot.text(R.string.tool_native_edit_confirm))
                    robot.waitUntil { tools.tool(orphanTool.id)?.revision == 2 }
                    assertEquals(7, JSONObject(checkNotNull(tools.tool(orphanTool.id)).state).getInt("p5_count_value"))
                    assertUnchanged()
                    assertEquals(2, synchronized(calls) { calls.size })
                    AutomationStore(fixture.context, tools.automationDatabase).use { rules ->
                        assertTrue(checkNotNull(rules.rule(rule.id)).enabled)
                    }

                    val replacement = ToolSpec.parse("""{"formatVersion":3,"title":"Replacement","description":"",
                        "capabilities":[],"stateSchema":{"count":"number"},"initialState":{"count":0},
                        "ui":{"type":"text","text":"New version","bind":"count"},
                        "source":"return {state:input.state};"}""")
                    val updated = linkedTool.copy(revision = 2, spec = replacement.json, state = replacement.initialState, ui = replacement.ui.toString())
                    scenario.onActivity {
                        assertEquals(1, model.tools.tools.single { it.id == linkedTool.id }.revision)
                        // Keep the displayed revision stable on Main while a separate store writes its replacement.
                        tools.saveTool(updated, revision = true)
                        model.clearRequestError()
                        model.refineTool(linkedTool.id)
                    }
                    robot.waitUntil { model.requestError == robot.text(R.string.tool_refinement_current_version) }
                    assertUnchanged()
                    assertEquals(updated, tools.tool(linkedTool.id))
                    assertEquals(2, synchronized(calls) { calls.size })

                    scenario.onActivity { model.clearRequestError(); model.refineTool("missing-app") }
                    robot.waitUntil { model.requestError != null }
                    assertUnchanged()
                }
            }
        } finally {
            fixture.remove()
        }
    }

    private fun sealedWorkshop(): ToolSpec = runBlocking {
        val plan = NeedsPlan.parse("""{"title":"Workshop","description":"Workshop deadlines and attempts","language":"en",
            "inputs":[],"questions":[],"constants":[{"id":"windowDays","value":3}],"needs":[
                {"id":"due","type":"due_items","label":"Deadlines","description":"Track deadlines",
                    "uses":{"window":"windowDays"},"items":[{"name":"Filter","offsetDays":2,"repeatDays":14}]},
                {"id":"count","type":"counter","label":"Attempts","description":"Count attempts"}]}""")
        val clock = PartClockSample(LocalDate.of(2026, 3, 8), 540)
        val assembly = PartsGeneration.build(plan, lessonTarget = null, selectPresentation = false, clock = clock) { body ->
            val questions = JSONObject(body).getJSONArray("questions")
            val answers = JSONArray()
            repeat(questions.length()) { index ->
                val question = questions.getJSONObject(index)
                val probabilities = JSONObject()
                question.getJSONObject("choices").keys().forEach { label -> probabilities.put(label, if (label == "A") 1 else 0) }
                answers.put(JSONObject().put("id", question.getString("id")).put("choice", "A")
                    .put("confidence", 1).put("probabilities", probabilities))
            }
            JSONObject().put("answers", answers).toString()
        }
        assertTrue(assembly.requiresReadProjection)
        ToolComposition.sealStandalone(assembly)
    }
}
