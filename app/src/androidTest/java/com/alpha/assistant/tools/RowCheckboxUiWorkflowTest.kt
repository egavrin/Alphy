package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.MainActivity
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.tools.generation.GenerationRepair
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real row controls must address one stable key and retain their state after reopening. */
@RunWith(AndroidJUnit4::class)
class RowCheckboxUiWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val recorded by lazy {
        instrumentation.context.assets.open("generation-reliability/row-checkbox-recorded.json")
            .bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun recordedWriter(call: Int): JSONObject {
        val replies = recorded.getJSONArray("candidates")
        val repair = GenerationRepair.writer(ToolSpec.generatedFields + "behaviorBindings", null)
        var text = replies.getJSONObject(0).getJSONObject("response").toString()
        repair.retain(text)
        for (index in 1..call - 3) text = repair.apply(replies.getJSONObject(index).getJSONObject("response").toString()).text
        return JSONObject(text)
    }

    @Test fun twoActualRowCheckboxesPersistTheirOwnIdsAndStates() {
        val fixture = ShellFixture("row-checkbox-ui")
        val robot = ShellRobot(fixture.name)
        val database = "${fixture.name}.db-tools.db"
        val output = File(fixture.context.cacheDir, "row-checkbox-ui").apply {
            if (exists()) check(deleteRecursively())
            check(mkdirs())
        }
        val writer = recordedWriter(4)
        writer.remove("behaviorBindings")
        val spec = ToolSpec.parse(writer.toString())
        val id = "row_checkbox_fixture"
        val proof = JSONArray()
        try {
            ToolStore(fixture.context, database).use { store ->
                store.saveTool(ToolRecord(id, 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true), revision = true)
                fixture.install()
                fixture.launch().use {
                    val model = checkNotNull(fixture.model)
                    robot.waitUntil { !model.loading && !model.tools.busy }
                    robot.instrumentation.runOnMainSync { model.openSurface(id) }
                    robot.waitFor(robot.text(R.string.tool_refine))
                    fun state() = JSONObject(checkNotNull(store.tool(id)).state)
                    fun checkpoint(name: String, expected: List<Boolean>, remaining: Int) {
                        robot.waitUntil {
                            val saved = state()
                            val rows = saved.getJSONArray("items")
                            rows.length() == expected.size && saved.getInt("remaining") == remaining &&
                                expected.indices.all { rows.getJSONObject(it).getBoolean("done") == expected[it] }
                        }
                        val saved = state()
                        val rows = saved.getJSONArray("items")
                        assertEquals(expected.indices.map { "t${it + 1}" }, expected.indices.map { rows.getJSONObject(it).getString("id") })
                        robot.waitUntil {
                            val shown = checkboxNodes(robot.diagnostics.freshRoot())
                            shown.size == expected.size && shown.map { it.isChecked } == expected
                        }
                        val checks = checkboxNodes(robot.diagnostics.freshRoot())
                        assertEquals(expected.size, checks.size)
                        assertEquals(expected, checks.map { it.isChecked })
                        robot.automation.waitForIdle(100, 3000)
                        android.os.SystemClock.sleep(500)
                        val bitmap = checkNotNull(robot.automation.takeScreenshot())
                        try { File(output, "$name.png").outputStream().use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) } }
                        finally { bitmap.recycle() }
                        File(output, "$name-state.json").writeText(saved.toString(2))
                        proof.put(JSONObject().put("checkpoint", name).put("expectedDone", JSONArray(expected))
                            .put("remaining", remaining).put("observed", saved))
                    }
                    fun add(text: String, count: Int) {
                        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                        assertTrue(robot.field("New task").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                        robot.click(robot.text(R.string.tool_generated_apply, "New task"))
                        robot.waitUntil { state().getString("newItem") == text }
                        robot.click("Add")
                        robot.waitUntil { state().getJSONArray("items").length() == count }
                    }
                    fun tick(index: Int) {
                        val target = checkboxNodes(robot.diagnostics.freshRoot())[index]
                        assertTrue(target.isEnabled && target.isVisibleToUser && target.isCheckable)
                        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }
                    add("Buy milk", 1)
                    add("Read book", 2)
                    checkpoint("two-added", listOf(false, false), 2)
                    tick(0)
                    checkpoint("first-checked", listOf(true, false), 1)
                    tick(1)
                    checkpoint("both-checked", listOf(true, true), 0)
                    tick(0)
                    checkpoint("first-unchecked", listOf(false, true), 1)
                    val before = state().toString()
                    robot.click(robot.text(R.string.tool_library_back))
                    robot.waitGone("New task")
                    robot.instrumentation.runOnMainSync { model.openSurface(id) }
                    robot.waitFor("New task")
                    checkpoint("reopened", listOf(false, true), 1)
                    assertEquals(before, state().toString())
                    assertEquals(0, fixture.provider.calls)
                    File(output, "workflow.json").writeText(JSONObject().put("request", recorded.getString("request"))
                        .put("providerCalls", fixture.provider.calls).put("checkpoints", proof).toString(2))
                }
            }
        } finally {
            fixture.model?.let { model ->
                runBlocking { model.tools.closeAndJoin() }
                model.courseStore.close()
                model.memoryStore.close()
            }
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.name}.db-courses.db")
            fixture.context.deleteDatabase("${fixture.name}.db-memory.db")
        }
    }

    @Test fun polishRowScopeMismatchKeepsWriterBudgetAndVerifiedCoreApproval() = runBlocking {
        val request = "Create a to-do list. Use a bright result label."
        val plan = JSONObject(recorded.getJSONObject("plan").toString()).apply {
            getJSONObject("behavior").getJSONObject("scenario").getJSONArray("steps").getJSONObject(4).put("value", true)
            put("visualReview", JSONArray().put("Use a bright result label."))
        }
        val core = recordedWriter(5)
        val revised = JSONObject(core.getJSONObject("ui").toString()).apply {
            getJSONArray("children").getJSONObject(1).put("action", "spare")
            getJSONArray("children").getJSONObject(3).getJSONObject("rowTemplate")
                .getJSONArray("children").getJSONObject(0).put("action", "toggle")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            for (choice in listOf("Cancel", "Save")) {
                val session = CloudSession()
                val events = mutableListOf<ToolGenerationTraceEvent>()
                val stages = mutableListOf<CloudGenerationStage?>()
                val provider = object : ChatProvider {
                    override suspend fun respond(key: String, call: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                        stages += call.generationStage
                        val response = when (call.generationStage) {
                            CloudGenerationStage.PLAN -> if (call.systemInstructions.startsWith("Plan only native mini-app illustrations."))
                                """{"disposition":"unchanged","excerpt":"","imageRequests":[],"removeImageSlots":[]}"""
                            else call.withFixtureCheckEvidence(plan.toString())
                            CloudGenerationStage.COMPLETE -> core.toString()
                            CloudGenerationStage.REFINE -> JSONObject().put("fields", JSONObject().put("ui", revised)).toString()
                            else -> error("Unexpected polish stage ${call.generationStage}")
                        }
                        emit(CloudEvent.Text(response))
                    }
                }
                val result = runCatching {
                    withTimeout(30_000) {
                        val approve = launch(Dispatchers.IO) {
                            val automation = instrumentation.uiAutomation
                            while (true) {
                                val root = automation.rootInActiveWindow
                                val notice = root?.findAccessibilityNodeInfosByText("Missing: requested appearance changes").orEmpty()
                                val buttons = root?.findAccessibilityNodeInfosByText(choice).orEmpty()
                                if (notice.isNotEmpty() && buttons.any { button -> button.isClickable &&
                                        button.text?.toString().equals(choice, ignoreCase = true) &&
                                        button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }) break
                                delay(100)
                            }
                        }
                        try {
                            ToolGenerationService(provider, SandboxClient(instrumentation.targetContext)).generate(
                                "offline", request, null, emptyList(), session, "row-polish", privateState = false, draftPreview = null,
                                trace = ToolGenerationTrace(UUID.randomUUID(), { events += it }))
                        } finally { approve.cancelAndJoin() }
                    }
                }
                if (choice == "Cancel") assertEquals("widget_declined", (result.exceptionOrNull() as? CloudFailure)?.code)
                else assertEquals("toggle", JSONObject(result.getOrThrow().ui).getJSONArray("children").getJSONObject(1).getString("action"))
                assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.PLAN,
                    CloudGenerationStage.REFINE), stages)
                assertFalse(events.filterIsInstance<ToolGenerationTraceEvent.Repair>().any { it.providerStage == CloudGenerationStage.COMPLETE })
                val mismatch = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>().single { it.behaviorBinding?.rule == BehaviorFailureRule.ROW_SCOPE_MISMATCH }
                assertEquals("widget_plan", mismatch.failureCode)
                assertEquals("writer_binding", mismatch.behaviorBinding!!.report().getString("discoveryStage"))
                assertEquals(1, session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
            }
        }
    }

    private fun checkboxNodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        if (node.isCheckable) return listOf(node)
        return (0 until node.childCount).flatMap { checkboxNodes(node.getChild(it)) }
    }
}
