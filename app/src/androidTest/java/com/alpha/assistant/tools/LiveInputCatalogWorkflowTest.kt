package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveInputCatalogWorkflowTest {
    @Test fun applyAndLiveActionsUseSeparateValidationPaths() {
        val spec = spec(column(
            input("defaultText"), input("explicitText").put("inputMode", "apply"),
            input("liveText").put("inputMode", "live"),
            node("""{"type":"slider","text":"Уровень","bind":"level","action":"liveLevel","min":1,"max":5,"step":0.5,"inputMode":"live"}"""),
        ))
        val state = JSONObject(spec.initialState)
        for (id in listOf("defaultText", "explicitText")) {
            assertFalse(ToolUiCatalog.isLiveInput(ToolUiCatalog.validateAction(spec.ui, id, "текст", state)))
            rejects(id) { ToolUiCatalog.validateLiveInput(spec.ui, id, "текст", state) }
        }
        for (value in listOf("", "текст", "a".repeat(8))) {
            assertTrue(ToolUiCatalog.isLiveInput(ToolUiCatalog.validateLiveInput(spec.ui, "liveText", value, state)))
            rejects("ordinary $value") { ToolUiCatalog.validateAction(spec.ui, "liveText", value, state) }
        }
        for (value in listOf(1, 2.5, 5)) {
            assertEquals("slider", ToolUiCatalog.validateLiveInput(spec.ui, "liveLevel", value, state).getString("type"))
            rejects("ordinary $value") { ToolUiCatalog.validateAction(spec.ui, "liveLevel", value, state) }
        }
        for (value in listOf(null, JSONObject.NULL, false, 1, "a".repeat(9))) {
            rejects("text $value") { ToolUiCatalog.validateLiveInput(spec.ui, "liveText", value, state) }
        }
        for (value in listOf(null, "2", true, 0, 6, Double.NaN, Double.POSITIVE_INFINITY)) {
            rejects("slider $value") { ToolUiCatalog.validateLiveInput(spec.ui, "liveLevel", value, state) }
        }
    }

    @Test fun inputModeIsClosedAndOnlyTextInputAndScalarSliderSupportIt() {
        for (mode in listOf("", "Live", "debounce", 1, true, JSONObject.NULL)) {
            rejects("mode $mode") { spec(input("edit").put("inputMode", mode)) }
        }
        val search = input("search").put("type", "search")
        val number = node("""{"type":"numberInput","text":"Число","bind":"level","action":"number"}""")
        val button = node("""{"type":"button","text":"Запуск","action":"run"}""")
        for (other in listOf(search, number, button)) {
            spec(other)
            for (mode in listOf("apply", "live")) {
                rejects("${other.getString("type")} $mode") { spec(JSONObject(other.toString()).put("inputMode", mode)) }
            }
        }
    }

    @Test fun onlyVisibleEnabledControlsOnTheActiveRouteAndPageCanDeliver() {
        val editor = column(input("edit").put("inputMode", "live"))
            .put("visibleBind", "shown").put("enabledBind", "allowed")
        val empty = node("""{"type":"text","text":"Обзор"}""")
        val pages = node("""{"type":"pager","text":"Страницы","bind":"page","action":"turn"}""")
            .put("children", JSONArray().put(empty).put(editor))
        val routes = node("""{"type":"routeStack","text":"Маршруты","bind":"stack","action":"back","routes":["home","editor"]}""")
            .put("children", JSONArray().put(empty).put(pages))
        val spec = spec(routes)
        val state = JSONObject(spec.initialState)
        fun edit() = ToolUiCatalog.validateLiveInput(spec.ui, "edit", "значение", state)
        rejects("inactive route") { edit() }
        state.put("stack", JSONArray(listOf("home", "editor")))
        rejects("inactive page") { edit() }
        state.put("page", 1)
        assertEquals("edit", edit().getString("action"))
        state.put("shown", false)
        rejects("hidden ancestor") { edit() }
        state.put("shown", true).put("allowed", false)
        rejects("disabled ancestor") { edit() }
    }

    @Test fun repeatedEditorsRequireAValidCurrentRowEnvelope() {
        val spec = spec(node("""{"type":"list","text":"Записи","bind":"rows","rowTemplate":
            {"type":"input","text":"Название","bind":"name","action":"rowEdit","inputMode":"live",
             "maxLength":4,"visibleBind":"shown","enabledBind":"allowed"}}"""))
        val state = JSONObject(spec.initialState)
        fun payload(key: String, value: Any) = JSONObject().put("key", key).put("value", value)
        fun edit(value: Any?) = ToolUiCatalog.validateLiveInput(spec.ui, "rowEdit", value, state)
        val selected = payload("first", "ново")
        assertEquals("rowEdit", edit(selected).getString("action"))
        rejects("ordinary row action") { ToolUiCatalog.validateAction(spec.ui, "rowEdit", selected, state) }
        for (invalid in listOf("ново", payload("unknown", "ново"), payload("first", "длинное"),
            payload("first", 1), payload("first", "ново").put("extra", true), node("""{"key":1,"value":"ново"}"""))) {
            rejects(invalid.toString()) { edit(invalid) }
        }
        val row = state.getJSONArray("rows").getJSONObject(0)
        row.put("shown", false)
        rejects("hidden row") { edit(selected) }
        row.put("shown", true).put("allowed", false)
        rejects("disabled row") { edit(selected) }
        state.put("rows", JSONArray())
        rejects("removed row") { edit(selected) }
    }

    private fun spec(ui: JSONObject) = ToolSpec.parse(node("""
        {"formatVersion":3,"title":"Ввод","description":"","capabilities":[],"source":"return {state:input.state};",
         "stateSchema":{"note":"string","level":"number","page":"number","shown":"boolean","allowed":"boolean",
           "stack":{"type":"array","items":"string","maxItems":4},
           "rows":{"type":"list","maxItems":4,"fields":{"id":"string","name":"string","shown":"boolean","allowed":"boolean"}}},
         "initialState":{"note":"","level":2,"page":0,"shown":true,"allowed":true,"stack":["home"],
           "rows":[{"id":"first","name":"имя","shown":true,"allowed":true}]}}
    """).put("ui", ui).toString())

    private fun input(action: String) = node("""{"type":"input","text":"Заметка","bind":"note","maxLength":8}""")
        .put("action", action)

    private fun column(vararg children: JSONObject) = JSONObject().put("type", "column").put("children", JSONArray(children.toList()))

    private fun node(json: String) = JSONObject(json.trimIndent())

    private fun rejects(label: String, block: () -> Unit) {
        assertTrue(label, runCatching(block).isFailure)
    }
}
