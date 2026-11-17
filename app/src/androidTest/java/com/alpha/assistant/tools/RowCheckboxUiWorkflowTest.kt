package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real row controls must address one stable key and retain their state after reopening. */
@RunWith(AndroidJUnit4::class)
class RowCheckboxUiWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val recorded by lazy {
        instrumentation.context.assets.open("generation-reliability/row-checkbox-recorded.json")
            .bufferedReader().use { JSONObject(it.readText()) }
    }

    @Test fun twoActualRowCheckboxesPersistTheirOwnIdsAndStates() {
        val fixture = ShellFixture("row-checkbox-ui")
        val robot = ShellRobot(fixture.name)
        val database = "${fixture.name}.db-tools.db"
        val output = File(fixture.context.cacheDir, "row-checkbox-ui").apply {
            if (exists()) check(deleteRecursively())
            check(mkdirs())
        }
        val spec = ToolSpec.parse(recorded.getJSONObject("definition").toString())
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
            fixture.removeWithModel()
        }
    }

    private fun checkboxNodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        if (node.isCheckable) return listOf(node)
        return (0 until node.childCount).flatMap { checkboxNodes(node.getChild(it)) }
    }
}
