package com.alpha.assistant.tools

import com.alpha.assistant.ui.seedOpenChat
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordReorderWorkflowTest {
    private val robot = ShellRobot("plan11-record-reorder")

    @Test fun dragAndAccessibleMoveCommitOnlyThroughTheSavedAction() {
        for (kind in listOf("list", "lazyList")) {
            withSavedList(kind) { tools, fixture ->
                val initial = checkNotNull(tools.tool("order"))
                val alpha = handle("Альфа")
                val beta = handle("Бета")
                val first = robot.assertTarget(alpha)
                val second = robot.assertTarget(beta)
                beginDrag(first, second).finish(MotionEvent.ACTION_CANCEL, second)
                robot.awaitFrames(2)
                assertEquals(initial, tools.tool("order"))

                beginDrag(first, second).finish(MotionEvent.ACTION_UP, second)
                awaitOrder(tools, fixture, listOf("b", "a", "c"))
                assertEquals(initial.stateVersion + 1, checkNotNull(tools.tool("order")).stateVersion)
                assertTrue("Beta now appears first", robot.bounds(beta).centerY() < robot.bounds(alpha).centerY())

                robot.click(beta)
                robot.click(robot.text(R.string.tool_reorder_move_down))
                awaitOrder(tools, fixture, listOf("a", "b", "c"))
                assertEquals(initial.stateVersion + 2, checkNotNull(tools.tool("order")).stateVersion)

                robot.back()
                robot.waitFor(robot.text(R.string.tool_library_content))
                val title = ToolSpec.parsePersisted(initial.spec).title
                robot.reveal(title)
                robot.click(title)
                robot.waitFor(alpha)
                assertTrue("The saved order appears again", robot.bounds(alpha).centerY() < robot.bounds(beta).centerY())
                assertEquals(listOf("a", "b", "c"), order(checkNotNull(tools.tool("order"))))
                assertEquals(1, tools.revisions("order").size)
            }
        }
    }

    @Test fun removalDuringDragRejectsTheOldDropWithoutMutatingTheCurrentRows() {
        withSavedList("list") { tools, fixture ->
            val first = robot.assertTarget(handle("Альфа"))
            val second = robot.assertTarget(handle("Бета"))
            val pointer = beginDrag(first, second)
            val displayed = checkNotNull(tools.tool("order"))
            val changed = JSONObject(displayed.state).apply { getJSONArray("rows").remove(1) }
            val newer = displayed.copy(state = changed.toString(), stateVersion = displayed.stateVersion + 1)
            try {
                tools.commitTool(newer, displayed, false)
                robot.waitGone(handle("Бета"))
            } catch (failure: Throwable) {
                pointer.finish(MotionEvent.ACTION_CANCEL, second)
                throw failure
            }
            pointer.finish(MotionEvent.ACTION_UP, second)
            robot.awaitFrames(2)
            assertEquals("The removed row cannot be replaced by the row at its old position", newer, tools.tool("order"))

            robot.instrumentation.runOnMainSync {
                checkNotNull(fixture.model).tools.interact(displayed, "move",
                    JSONObject("""{"sourceKey":"a","destinationKey":"b"}"""))
            }
            robot.waitUntil { fixture.model?.tools?.let { !it.busy && it.error != null } == true }
            assertEquals(newer, tools.tool("order"))
            assertEquals(listOf("a", "c"), order(checkNotNull(tools.tool("order"))))
            assertEquals(1, tools.revisions("order").size)
        }
    }

    @Test fun catalogRejectsForgedOrIneligibleKeysAndGroupedCollections() {
        val selected = spec("list")
        val state = JSONObject(selected.initialState)
        val ui = selected.ui
        val valid = JSONObject("""{"sourceKey":"a","destinationKey":"b"}""")
        ToolUiCatalog.validateAction(ui, "move", valid, state)
        for (raw in listOf(
            """{"sourceKey":"outside","destinationKey":"b"}""",
            """{"sourceKey":"a","destinationKey":"outside"}""",
            """{"sourceKey":"a","destinationKey":"a"}""",
            """{"sourceKey":"a","destinationKey":1}""",
            """{"sourceKey":"a","destinationKey":"b","index":1}""",
        )) rejects { ToolUiCatalog.validateAction(ui, "move", JSONObject(raw), state) }
        for (field in listOf("shown", "available")) {
            for (row in listOf(0, 1)) {
                val ineligible = JSONObject(state.toString()).apply { getJSONArray("rows").getJSONObject(row).put(field, false) }
                rejects { ToolUiCatalog.validateAction(ui, "move", valid, ineligible) }
            }
        }
        val removed = JSONObject(state.toString()).apply { getJSONArray("rows").remove(1) }
        rejects { ToolUiCatalog.validateAction(ui, "move", valid, removed) }
        for (flag in listOf("visible", "enabled")) {
            val ancestor = JSONObject().put("type", "column").put(flag, false).put("children", JSONArray().put(ui))
            rejects { ToolUiCatalog.validateAction(ancestor, "move", valid, state) }
        }
        for (kind in listOf("groupedList", "lazyGrid", "table")) rejects { spec(kind) }
        rejects { spec("list") { put("groupBy", "name") } }
        rejects { spec("lazyList") { put("groupBy", "name") } }
        rejects { spec("list") { put("action", "move") } }
        rejects { spec("list") { getJSONObject("reorder").put("enabledField", "name") } }
    }

    private fun handle(name: String) = robot.text(R.string.tool_reorder_handle, name)

    private fun beginDrag(start: Rect, target: Rect): Drag {
        val drag = Drag(SystemClock.uptimeMillis())
        drag.send(MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
        robot.awaitFrames(1)
        for (step in 1..4) {
            val fraction = step / 4f
            drag.send(MotionEvent.ACTION_MOVE,
                start.exactCenterX() + (target.exactCenterX() - start.exactCenterX()) * fraction,
                start.exactCenterY() + (target.exactCenterY() - start.exactCenterY()) * fraction)
            robot.awaitFrames(1)
        }
        return drag
    }

    private inner class Drag(private val down: Long) {
        fun finish(action: Int, at: Rect) = send(action, at.exactCenterX(), at.exactCenterY())
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Touch event $action", robot.automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }

    private fun order(tool: ToolRecord): List<String> {
        val rows = JSONObject(tool.state).getJSONArray("rows")
        return List(rows.length()) { rows.getJSONObject(it).getString("id") }
    }

    private fun awaitOrder(tools: ToolStore, fixture: ShellFixture, expected: List<String>) {
        robot.waitUntil { fixture.model?.tools?.busy == false && tools.tool("order")?.let(::order) == expected }
    }

    private fun rejects(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    private fun withSavedList(kind: String, exercise: (ToolStore, ShellFixture) -> Unit) {
        val fixture = ShellFixture("plan11-reorder")
        try {
            val spec = spec(kind)
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { tools ->
                tools.saveTool(ToolRecord("order", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat", true), true)
                fixture.store.saveConversation(Conversation("chat", "Список", 1, 1, messages = listOf(
                    Message("artifact", "assistant", "", 1, "complete", toolId = "order", toolRevision = 1),
                )))
                fixture.store.seedOpenChat("chat")
                fixture.install()
                fixture.launch().use {
                    robot.click(robot.text(R.string.tool_open))
                    robot.waitUntil { fixture.model?.tools?.busy == false }
                    exercise(tools, fixture)
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally { fixture.remove() }
    }

    private fun spec(kind: String, change: JSONObject.() -> Unit = {}): ToolSpec {
        val ui = JSONObject("""{
            "type":"$kind","text":"Порядок","bind":"rows","columns":[{"text":"Запись","field":"name"}],
            "reorder":{"action":"move","labelField":"name","visibleField":"shown","enabledField":"available"}
        }""").apply(change)
        return ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", "Порядок записей")
            .put("description", "Локальный список").put("capabilities", JSONArray()).put("ui", ui)
            .put("stateSchema", JSONObject("""{"rows":{"type":"list","maxItems":8,"fields":{"id":"string","name":"string","shown":"boolean","available":"boolean"}}}"""))
            .put("initialState", JSONObject("""{"rows":[
                {"id":"a","name":"Альфа","shown":true,"available":true},
                {"id":"b","name":"Бета","shown":true,"available":true},
                {"id":"c","name":"Гамма","shown":true,"available":true}
            ]}"""))
            .put("source", """
                if (input.event.actionId === 'move') {
                    const rows = input.state.rows;
                    const {sourceKey, destinationKey} = input.event.value;
                    const source = rows.findIndex(row => row.id === sourceKey);
                    const destination = rows.findIndex(row => row.id === destinationKey);
                    rows.splice(destination, 0, rows.splice(source, 1)[0]);
                }
                return {state: input.state};
            """.trimIndent()).toString())
    }
}
