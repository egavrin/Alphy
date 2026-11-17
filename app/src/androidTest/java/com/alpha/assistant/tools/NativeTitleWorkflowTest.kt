package com.alpha.assistant.tools

import android.view.accessibility.AccessibilityNodeInfo
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure case: generated apps named one thing twice. A section "Today's doses" held one list "Dose list",
 * and tabs repeated their name above the tab row. A section "Składnik" held a choice "Składnik".
 * The title bar showed "Stromkosten-Rechne" with "r" on a second line.
 */
class NativeTitleWorkflowTest {
    private val robot = ShellRobot("native-title")

    private fun list(name: String, bind: String) = JSONObject().put("type", "list").put("text", name).put("bind", bind)
        .put("rowTemplate", JSONObject().put("type", "text").put("text", "").put("bind", "name"))
    private fun group(type: String, title: String, vararg children: JSONObject) =
        JSONObject().put("type", type).put("children", JSONArray(children.toList())).apply { if (title.isNotEmpty()) put("text", title) }
    private val end = JSONObject("""{"type":"text","text":"End of names"}""")

    private fun lists(vararg binds: String): Pair<JSONObject, JSONObject> {
        val fields = JSONObject(); val state = JSONObject()
        for (bind in binds) {
            fields.put(bind, JSONObject("""{"type":"list","maxItems":5,"fields":{"id":"string","name":"string"}}"""))
            state.put(bind, JSONArray().put(JSONObject().put("id", "1").put("name", "Row of $bind")))
        }
        return fields to state
    }

    /** The nodes in the active window that show [label] as text. A content description does not count. */
    private fun shownTimes(label: String): Int {
        fun walk(node: AccessibilityNodeInfo?): Int = if (node == null) 0 else
            (if (node.text?.toString()?.trimEnd() == label) 1 else 0) + (0 until node.childCount).sumOf { walk(node.getChild(it)) }
        return walk(robot.diagnostics.freshRoot())
    }
    private fun shown(label: String) = shownTimes(label) > 0

    @Test fun aLoneListTakesItsNameFromTheTitleAbove() {
        val ui = themedColumn(group("section", "Today's doses", list("Dose list", "a")), group("card", "Groceries", list("Grocery list", "b")), end)
        val (fields, state) = lists("a", "b")
        assertEquals(null, validateNativeUi(ui, fields, state))
        robot.renderApps("title", listOf(ui.toString()), state.toString(), "End of names") {
            assertTrue(shown("Today's doses") && shown("Groceries"))
            assertFalse("the list repeats the section title", shown("Dose list"))
            assertFalse("the list repeats the card title", shown("Grocery list"))
            assertNotNull("a screen reader still gets the list name", robot.find("Dose list"))
        }
    }

    @Test fun listNamesStayWhereNoTitleNamesThem() {
        val ui = themedColumn(group("section", "Week", list("Morning list", "a"), list("Evening list", "b")),
            group("section", "", list("Loose list", "c")), group("section", "Medicine", list("Pill list", "d")), end)
        val (fields, state) = lists("a", "b", "c", "d")
        assertEquals(null, validateNativeUi(ui, fields, state))
        // The host shows "Medicine" as the app title, so that section draws no title of its own.
        robot.renderApps("title", listOf(ui.toString()), state.toString(), "End of names", heading = "Medicine") {
            for (name in listOf("Morning list", "Evening list", "Loose list", "Pill list")) assertTrue("$name is shown", shown(name))
        }
    }

    @Test fun tabsShowOnlyTheirOptions() {
        val ui = themedColumn(JSONObject("""{"type":"tabs","text":"Period","bind":"period","action":"setPeriod",
            "options":[{"text":"Day","value":"day"},{"text":"Week","value":"week"}]}"""),
            JSONObject("""{"type":"select","text":"Unit","bind":"unit","action":"setUnit",
            "options":[{"text":"Liters","value":"l"},{"text":"Cups","value":"cup"}]}"""), end)
        val fields = JSONObject("""{"period":"string","unit":"string"}""")
        val state = JSONObject("""{"period":"day","unit":"l"}""")
        assertEquals(null, validateNativeUi(ui, fields, state))
        robot.renderApps("title", listOf(ui.toString()), state.toString(), "End of names") {
            assertTrue(shown("Day") && shown("Week"))
            assertFalse("the tab row repeats its name", shown("Period"))
            assertNotNull("a screen reader still gets the tab row name", robot.find("Period"))
            assertTrue("a select keeps its label", shown("Unit"))
        }
    }

    @Test fun aChoiceThatRepeatsTheTitleTakesItsName() {
        fun choice(type: String, name: String, bind: String) = JSONObject().put("type", type).put("text", name).put("bind", bind)
            .put("action", "set_$bind").put("options", JSONArray("""[{"text":"First $bind","value":"a"},{"text":"Second $bind","value":"b"}]"""))
        val ui = themedColumn(group("section", "Składnik", choice("segmented", "Składnik", "item"), choice("segmented", "Jednostka", "unit")),
            group("card", "Size", choice("select", "size", "size")), end)
        val fields = JSONObject("""{"item":"string","unit":"string","size":"string"}""")
        val state = JSONObject("""{"item":"a","unit":"a","size":"a"}""")
        assertEquals(null, validateNativeUi(ui, fields, state))
        robot.renderApps("title", listOf(ui.toString()), state.toString(), "End of names") {
            assertEquals("the section title is the only Składnik", 1, shownTimes("Składnik"))
            assertEquals("the card title is the only Size", 1, shownTimes("Size"))
            assertFalse("the select repeats the card title", shown("size"))
            assertTrue("a choice with its own name keeps its label", shown("Jednostka"))
            assertTrue(shown("First item") && shown("First size"))
        }
    }

    @Test fun aLongAppTitleBreaksOnlyBetweenWords() {
        val fixture = ShellFixture("native-title")
        val id = "title_fixture"
        val spec = ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", "Stromkosten-Rechner").put("description", "")
            .put("capabilities", JSONArray()).put("stateSchema", JSONObject().put("n", "number")).put("initialState", JSONObject().put("n", 0))
            .put("source", "return {state:input.state};")
            .put("ui", JSONObject("""{"type":"column","children":[{"type":"text","text":"Meter readings"}]}""")).toString())
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
                store.saveTool(ToolRecord(id, 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true), revision = true)
                fixture.install()
                fixture.launch().use { scenario ->
                    val model = checkNotNull(fixture.model)
                    robot.waitUntil { !model.loading && !model.tools.busy }
                    robot.instrumentation.runOnMainSync { model.openSurface(id) }
                    robot.waitFor(robot.text(R.string.tool_refine))
                    robot.waitFor("Meter readings")
                    val title = textLayout(scenario, "Stromkosten-Rechner")
                    assertTrue("the title breaks inside a word in ${title.lineCount} lines", wholeWords(title) && !title.hasVisualOverflow)
                }
            }
        } finally {
            fixture.removeWithModel()
        }
    }
}
