package com.alpha.assistant.tools.agent

import com.alpha.assistant.tools.GraphicsContract
import com.alpha.assistant.tools.ToolSurface
import com.alpha.assistant.tools.ToolUiCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A text view of what a mini-app shows. The agent reads it after each step.
 * [lines] describe visible nodes with action IDs. [visibleText] holds only what a person can read.
 */
/** True when the screen shows [node]: it is visible, and a transition shows its content only while its value is true. */
internal fun shownNode(node: JSONObject, state: JSONObject): Boolean = ToolUiCatalog.visible(node, state) &&
    (node.optString("type") != "transition" || runCatching { ToolUiCatalog.value(state, node.getString("bind")) }.getOrNull() == true)

/** The children that the screen shows: the current page of a pager, the top route of a route stack, or every child. */
internal fun shownChildren(node: JSONObject, state: JSONObject): List<JSONObject> {
    val children = node.optJSONArray("children") ?: return emptyList()
    return when (node.optString("type")) {
        "pager" -> listOfNotNull(runCatching { (ToolUiCatalog.value(state, node.getString("bind")) as Number).toInt() }
            .getOrNull()?.let(children::optJSONObject))
        "routeStack" -> listOfNotNull(runCatching { ToolUiCatalog.routeChild(node, ToolUiCatalog.routeStack(node, state).last()) }.getOrNull())
        else -> List(children.length()) { children.getJSONObject(it) }
    }
}

internal class AppScreen private constructor(val lines: List<String>, val visibleText: List<String>) {
    val text: String get() = lines.joinToString("\n")

    fun shows(fragment: String): Boolean {
        val wanted = normalize(fragment)
        return wanted.isNotEmpty() && visibleText.any { normalize(it).contains(wanted) }
    }

    companion object {
        private const val MAX_ROWS = 6
        private const val MAX_LINES = 160
        private val collections = setOf("list", "table", "groupedList", "lazyList", "lazyGrid")
        private val flattened = setOf("column", "scroll", "theme", "box", "motion", "transform", "transition")
        private val silent = setOf("divider", "spacer", "skeleton")

        fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

        /** Where the drawing lands, so the model can center and size it on the surface. */
        private fun extent(frame: JSONObject): String {
            val budget = GraphicsContract.Budget()
            if (runCatching { GraphicsContract.frame(frame.toString(), "frame", budget) }.isFailure || !budget.left.isFinite()) return ""
            fun n(value: Double) = value.roundToInt()
            return " within x ${n(budget.left)}..${n(budget.right)}, y ${n(budget.top)}..${n(budget.bottom)}"
        }

        fun render(ui: JSONObject, state: JSONObject, surfaces: JSONObject, nowMillis: Long,
            sizes: Map<String, ToolSurface> = emptyMap()): AppScreen {
            val lines = mutableListOf<String>()
            val visible = mutableListOf<String>()
            fun quote(text: String) = "\"" + text.replace("\n", " ").take(160) + "\""
            fun scalar(value: Any?): String = when (value) {
                null, JSONObject.NULL -> "null"
                is String -> quote(value)
                is JSONArray, is JSONObject -> value.toString().take(200)
                else -> value.toString()
            }
            fun bound(node: JSONObject, values: JSONObject): Any? =
                node.optString("bind").takeIf { it.isNotEmpty() }?.let { runCatching { ToolUiCatalog.value(values, it) }.getOrNull() }
            fun line(depth: Int, text: String) {
                if (lines.size < MAX_LINES) lines += "  ".repeat(depth) + text
                else if (lines.size == MAX_LINES) lines += "... more nodes are not shown"
            }
            fun timerText(node: JSONObject, values: JSONObject): String {
                if (node.has("foreground")) {
                    val foreground = node.getJSONObject("foreground")
                    val seconds = foreground.optLong("durationMs") / 1000
                    val alerts = listOf("vibrationMs" to "vibration", "toneMs" to "tone", "notification" to "notification")
                        .filter { foreground.has(it.first) }.joinToString { it.second }
                    return "countdown ${clock(seconds)} with host Start and Reset, alerts: $alerts"
                }
                val timer = ToolUiCatalog.timerBinding(node, values) ?: return "unavailable"
                val seconds = timer.secondsAt(nowMillis) ?: return "idle"
                if (timer.countdown && seconds == 0L) return "finished"
                return (if (timer.countdown) "remaining " else "elapsed ") + clock(seconds)
            }

            fun visit(node: JSONObject, values: JSONObject, depth: Int, enabled: Boolean, rowKey: String? = null) {
                if (!shownNode(node, values)) return
                val type = node.optString("type")
                val active = enabled && ToolUiCatalog.enabled(node, values)
                val label = node.optString("text")
                val action = node.optString("action").takeIf { it.isNotEmpty() }
                val id = action?.let { " [" + it + (rowKey?.let { key -> " key=$key" } ?: "") + "]" }.orEmpty()
                val off = if (active) "" else " (disabled)"
                fun childNodes(next: Int) = shownChildren(node, values).forEach { visit(it, values, next, active, rowKey) }
                if (label.isNotEmpty() && type !in collections) visible += label
                when {
                    type in silent -> Unit
                    type in collections -> {
                        val rows = runCatching { ToolUiCatalog.records(node, values) }.getOrNull() ?: JSONArray()
                        line(depth, "$type$id${if (label.isNotEmpty()) " " + quote(label) else ""}: ${rows.length()} rows$off")
                        if (label.isNotEmpty()) visible += label
                        val rowActions = node.optJSONArray("rowActions")?.let { actions ->
                            (0 until actions.length()).map { actions.getJSONObject(it) }
                        }.orEmpty()
                        val columns = node.optJSONArray("columns")?.let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
                        for (index in 0 until minOf(rows.length(), MAX_ROWS)) {
                            val row = rows.getJSONObject(index)
                            val key = runCatching { ToolUiCatalog.stableKey(row) }.getOrDefault("?")
                            val record = if (row.has("token") && row.has("value") && row.has("key")) row.optJSONObject("value") ?: row else row
                            val cells = columns?.joinToString(", ") { column ->
                                val value = runCatching { ToolUiCatalog.value(record, column.getString("field")) }.getOrNull()
                                visible += value?.toString().orEmpty()
                                "${column.optString("text")}: ${scalar(value)}"
                            }
                            val template = node.optJSONObject("rowTemplate")
                            val buttons = rowActions.filter { ToolUiCatalog.rowActionEnabled(it, record) }
                                .joinToString(" ") { "[${it.getString("action")} key=$key] ${quote(it.optString("text"))}" }
                            line(depth + 1, "row $index key=$key" + (cells?.let { ": $it" } ?: if (template == null) ": " + record.toString().take(200) else "") +
                                (if (buttons.isNotEmpty()) " $buttons" else ""))
                            if (columns == null && template == null) ToolValueTexts.of(record).forEach { visible += it }
                            template?.let { visit(it, record, depth + 2, active, key) }
                        }
                        if (rows.length() > MAX_ROWS) line(depth + 1, "... ${rows.length() - MAX_ROWS} more rows")
                        if (rows.length() == 0) node.optString("emptyText").takeIf { it.isNotEmpty() }?.let { visible += it; line(depth + 1, "empty: ${quote(it)}") }
                    }
                    type in flattened && label.isEmpty() && action == null -> childNodes(depth)
                    node.has("children") -> {
                        val extra = when (type) {
                            "grid" -> " columns=${node.opt("columns") ?: "auto"}"
                            "theme" -> " preset=${node.optString("preset")}"
                            else -> ""
                        }
                        line(depth, "$type$id${if (label.isNotEmpty()) " " + quote(label) else ""}$extra$off")
                        childNodes(depth + 1)
                    }
                    type == "timer" -> {
                        val shown = timerText(node, values)
                        visible += shown
                        line(depth, "timer$id ${quote(label)}: $shown")
                    }
                    type == "canvas" -> {
                        val ids = node.optJSONArray("surfaces")?.let { list -> (0 until list.length()).map { list.getString(it) } }.orEmpty()
                        val drawn = ids.joinToString { surface ->
                            val frame = surfaces.optJSONObject(surface)
                            val count = frame?.optJSONArray("commands")?.length() ?: 0
                            "$surface${sizes[surface]?.let { " ${it.width}x${it.height}" }.orEmpty()}: " +
                                "$count drawing command${if (count == 1) "" else "s"}${frame?.let(::extent).orEmpty()}"
                        }
                        line(depth, "canvas ${quote(label)} ($drawn)")
                    }
                    type in ToolUiCatalog.choiceTypes -> {
                        val value = bound(node, values)
                        val options = node.optJSONArray("options")?.let { list -> (0 until list.length()).map { list.getJSONObject(it) } }.orEmpty()
                        val selected = { option: JSONObject ->
                            val v = option.opt("value")
                            if (value is JSONArray) (0 until value.length()).any { value.opt(it) == v } else v == value || (v is Number && value is Number && v.toDouble() == value.toDouble())
                        }
                        options.forEach { visible += it.optString("text") }
                        line(depth, "$type$id ${quote(label)} = ${scalar(value)}; options: " +
                            options.joinToString(", ") { "${scalar(it.opt("value"))} ${quote(it.optString("text"))}" + if (selected(it)) " (selected)" else "" } + off)
                    }
                    type in setOf("text", "metric", "badge", "fact", "timelineItem") -> {
                        val shown = runCatching { ToolUiCatalog.display(node, values) }.getOrDefault(label)
                        val value = bound(node, values)
                        if (value != null) visible += value.toString()
                        // The screen line shows "Label: value", so a check can quote it as the model reads it.
                        visible += shown
                        val emphasis = node.optJSONObject("style")?.optString("emphasis")?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
                        line(depth, "$type$id ${quote(shown)}$emphasis$off")
                    }
                    type in setOf("button", "icon", "actionChip", "snackbar", "listItem") -> {
                        val variant = node.optString("variant").takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
                        line(depth, "$type$id ${quote(label)}$variant$off")
                    }
                    type in setOf("progress", "progressRing", "slider") -> {
                        val value = bound(node, values)
                        if (type != "slider" && value is Number) runCatching { ToolUiCatalog.display(node, values) }.getOrNull()?.let { visible += it }
                        val (min, max) = runCatching { ToolUiCatalog.bounds(node) }.getOrDefault(0.0 to 100.0)
                        line(depth, "$type$id ${quote(label)} = ${scalar(value)} of $min..$max$off")
                    }
                    type == "chart" -> {
                        val categories = runCatching { ToolUiCatalog.value(values, node.getString("categories")) as JSONArray }.getOrNull()
                        line(depth, "chart ${node.optString("kind")} ${quote(label)}: ${categories?.length() ?: 0} points")
                    }
                    type in setOf("calendar", "medicine", "navigation") ->
                        line(depth, "$type$id ${node.optString("capability")} ${node.optJSONObject("args")?.toString()?.take(200).orEmpty()}$off")
                    else -> {
                        val value = bound(node, values)
                        if (value is String || value is Number || value is Boolean) visible += value.toString()
                        line(depth, "$type$id ${quote(label)}${if (node.has("bind")) " = " + scalar(value) else ""}$off")
                    }
                }
                node.optString("dismissAction").takeIf { it.isNotEmpty() }?.let { line(depth + 1, "dismiss [$it]") }
            }
            visit(ui, state, 0, true)
            return AppScreen(lines, visible.filter { it.isNotBlank() })
        }

        fun clock(seconds: Long): String {
            val hours = seconds / 3600
            val minutes = seconds / 60 % 60
            val rest = seconds % 60
            return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, rest) else "%d:%02d".format(Locale.ROOT, minutes, rest)
        }
    }
}

/** Scalar texts inside a record, for rows without columns or a template. */
private object ToolValueTexts {
    fun of(value: Any?): List<String> = when (value) {
        is JSONObject -> value.keys().asSequence().flatMap { of(value.opt(it)) }.toList()
        is JSONArray -> (0 until value.length()).flatMap { of(value.opt(it)) }
        null, JSONObject.NULL -> emptyList()
        else -> listOf(value.toString())
    }
}
