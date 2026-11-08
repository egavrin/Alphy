package com.alpha.assistant.tools

import com.alpha.assistant.ui.seedOpenChat
import android.graphics.Bitmap
import android.graphics.Rect
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartWorkflowTest {
    private val robot = ShellRobot("plan11-charts")
    private val title = "Продажи по месяцам"
    private val series = "Продажи"
    private val categories = listOf("Янв", "Фев", "Мар")
    private val next = "Следующий набор"

    @Test fun areaAndPieDrawTheirShapesAndFollowSavedNativeUpdates() {
        for (kind in listOf("area", "pie")) {
            val first = if (kind == "area") listOf(-4, 4, 2) else listOf(1, 3, 0)
            val second = if (kind == "area") listOf(5, 5, 5) else listOf(2, 0, 2)
            val state = state(categories, first).put("step", 0)
            val source = """
                if (input.event.actionId === 'next') {
                    var step = ++input.state.step;
                    if (step === 1) input.state.values = ${JSONArray(second)};
                    if (step === 2) {
                        input.state.categories = Array.from({length: 256}, (_, i) => String(i + 1));
                        input.state.values = Array.from({length: 256}, (_, i) => i % 2 + 1);
                    }
                    if (step === 3) {
                        input.state.categories = ${if (kind == "area") "[]" else JSONArray(categories)};
                        input.state.values = ${if (kind == "area") "[]" else "[0,0,0]"};
                    }
                    if (step === 4) { input.state.categories = []; input.state.values = []; }
                }
                return {state: input.state};
            """.trimIndent()
            val spec = ToolSpec.parse(definition(ui(kind, listOf(button(next, "next"))), state, source).toString())
            withSavedChart(spec) { store, _ ->
                inspectShape(kind, "initial", description(categories, first))
                robot.click(next)
                robot.waitUntil { JSONObject(checkNotNull(store.tool("chart")).state).getInt("step") == 1 }
                inspectShape(kind, "updated", description(categories, second))
                robot.click(next)
                val maximumLabels = (1..256).map(Int::toString)
                val maximumValues = List(256) { it % 2 + 1 }
                robot.waitFor(description(maximumLabels, maximumValues))
                assertVisible(description(maximumLabels, maximumValues))
                assertEquals(256, JSONObject(checkNotNull(store.tool("chart")).state).getJSONArray("values").length())
                robot.click(next)
                val empty = robot.text(R.string.tool_chart_empty)
                robot.waitFor(empty)
                assertVisible(empty)
                if (kind == "pie") {
                    assertVisible("$empty. ${description(categories, listOf(0, 0, 0))}")
                    robot.click(next)
                    robot.waitUntil { JSONObject(checkNotNull(store.tool("chart")).state).getJSONArray("categories").length() == 0 }
                    robot.waitUntil { robot.find(empty)?.let { it.isVisibleToUser && it.contentDescription == null } == true }
                }
            }
        }
    }

    @Test fun invalidChartsLeaveTheSavedAppAndRevisionIntact() {
        val initial = state(categories, listOf(1, 3, 0))
        val invalid = linkedMapOf(
            "unequal" to "input.state.values = [1];",
            "nonfinite" to "input.state.values = [1e309, 3, 0];",
            "negative" to "input.state.values = [-1, 3, 0];",
            "kind" to "return {state: input.state, ui: ${chart("unknown")}};",
            "series" to "return {state: input.state, ui: ${chart("pie", secondSeries = true)}};",
            "points" to "input.state.categories = Array(257).fill('A'); input.state.values = Array(257).fill(1);",
            "work" to """
                input.state.categories = Array(256).fill('A');
                input.state.values = Array(256).fill(1);
                return {state: input.state, ui: {type: 'column', children: Array(8).fill(${chart("pie")})}};
            """.trimIndent(),
        )
        val source = invalid.entries.joinToString("\n", postfix = "\nreturn {state: input.state};") { (id, js) ->
            "if (input.event.actionId === '$id') { $js }"
        }
        val spec = ToolSpec.parse(definition(ui("pie", invalid.keys.map { button("Проверить $it", it) }), initial, source).toString())
        // JSON cannot carry Infinity through a JavaScript result. Also exercise the raw spec boundary.
        val overflowingNumber = definition(chart("pie"), initial).toString()
            .replace("\"values\":[1,3,0]", "\"values\":[1e309,3,0]")
        assertTrue("The fixture must contain the nonfinite literal", "1e309" in overflowingNumber)
        assertTrue(runCatching { ToolSpec.parse(overflowingNumber) }.isFailure)
        withSavedChart(spec) { store, fixture ->
            val before = checkNotNull(store.tool("chart"))
            val revisions = store.revisions("chart")
            for (id in invalid.keys) {
                val label = "Проверить $id"
                robot.reveal(label)
                robot.click(label)
                robot.waitUntil { fixture.model?.tools?.let { !it.busy && it.error != null } == true }
                assertEquals(id, before, store.tool("chart"))
                assertEquals(id, revisions, store.revisions("chart"))
                robot.click(robot.text(R.string.tool_dismiss))
                robot.waitUntil { fixture.model?.tools?.error == null }
            }
            assertVisible(description(categories, listOf(1, 3, 0)))
        }
    }

    private fun inspectShape(kind: String, stage: String, label: String) {
        val canvas = assertVisible(label)
        val bounds = Rect().also(canvas::getBoundsInScreen)
        val density = robot.instrumentation.targetContext.resources.displayMetrics.density
        assertTrue("Chart is not clipped: $bounds", bounds.height() >= (180 * density).toInt() && bounds.width() >= (120 * density).toInt())
        robot.awaitFrames(2)
        val image = checkNotNull(robot.automation.takeScreenshot())
        try {
            fun sample(x: Float, y: Float) = image.getPixel(bounds.left + (x * bounds.width()).toInt(), bounds.top + (y * bounds.height()).toInt())
            val background = sample(.05f, .05f)
            if (kind == "area") {
                val fill = sample(.65f, .30f)
                assertNotEquals("Pixels between the value line and zero must be filled", background, fill)
                val outside = if (stage == "initial") sample(.65f, .75f) else sample(.65f, .05f)
                assertEquals("Pixels outside the area must retain the background", background, outside)
            } else {
                val first = sample(.56f, .35f)
                val second = sample(.44f, .65f)
                assertNotEquals("First slice", background, first)
                assertNotEquals("Second slice", background, second)
                assertNotEquals("Slices must be visually distinct", first, second)
                assertEquals("The pie leaves the canvas corners clear", background, sample(.05f, .95f))
            }
            val directory = File(robot.instrumentation.targetContext.getExternalFilesDir(null), "plan11-charts").apply { mkdirs() }
            File(directory, "$kind-$stage.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }

    private fun assertVisible(label: String) = robot.reveal(label).also { assertTrue(label, it.isVisibleToUser) }

    private fun withSavedChart(spec: ToolSpec, exercise: (ToolStore, ShellFixture) -> Unit) {
        val fixture = ShellFixture("plan11-chart")
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { tools ->
                tools.saveTool(ToolRecord("chart", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat", true), true)
                fixture.store.saveConversation(Conversation("chat", "Графики", 1, 1, messages = listOf(
                    Message("artifact", "assistant", "", 1, "complete", toolId = "chart", toolRevision = 1),
                )))
                fixture.store.seedOpenChat("chat")
                fixture.install()
                fixture.launch().use {
                    robot.click(robot.text(R.string.tool_open))
                    robot.waitUntil { fixture.model?.tools?.busy == false }
                    exercise(tools, fixture)
                    assertFalse(checkNotNull(tools.tool("chart")).archived)
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally { fixture.remove() }
    }

    private fun description(labels: List<String>, values: List<Int>) = buildString {
        append("$title. $series: ")
        labels.zip(values).forEach { (label, value) -> append("$label $value; ") }
    }

    private fun chart(kind: String, secondSeries: Boolean = false) = JSONObject().put("type", "chart").put("kind", kind)
        .put("text", title).put("categories", "categories")
        .put("series", JSONArray().put(JSONObject().put("text", series).put("bind", "values")).apply {
            if (secondSeries) put(JSONObject().put("text", "Второй ряд").put("bind", "values"))
        })

    private fun ui(kind: String, controls: List<JSONObject>) = JSONObject().put("type", "column")
        .put("children", JSONArray(controls + chart(kind)))

    private fun button(text: String, id: String) = JSONObject().put("type", "button").put("text", text).put("action", id)

    private fun state(labels: List<String>, values: List<Int>) = JSONObject()
        .put("categories", JSONArray(labels)).put("values", JSONArray(values))

    private fun definition(ui: JSONObject, state: JSONObject, source: String = "return {state: input.state};") = JSONObject()
        .put("formatVersion", 3).put("title", "Графики").put("description", "Локальные данные").put("capabilities", JSONArray())
        .put("stateSchema", JSONObject("""{"categories":{"type":"array","maxItems":257,"items":"string"},"values":{"type":"array","maxItems":257,"items":"number"},"step":"number"}"""))
        .put("initialState", state.put("step", 0)).put("ui", ui).put("source", source)
}
