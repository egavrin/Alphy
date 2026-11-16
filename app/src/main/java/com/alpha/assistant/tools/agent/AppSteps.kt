package com.alpha.assistant.tools.agent

import com.alpha.assistant.tools.ToolGenerationSmoke
import com.alpha.assistant.tools.GeneratedOutputDetail
import com.alpha.assistant.tools.ToolSmokeFailure
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.sandbox.RuntimeFailure
import com.alpha.assistant.sandbox.SandboxResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * The executable behavior checker for generated apps.
 * The agent writes steps against real action IDs and visible outputs. The host runs them and decides the result.
 */
internal sealed interface AppStep {
    data class Tap(val id: String, val key: String?, val row: Int?) : AppStep
    data class Set(val id: String, val value: Any?, val key: String?, val row: Int?) : AppStep
    data class Pointer(val surface: String, val x: Double, val y: Double) : AppStep
    data class Move(val surface: String, val direction: String) : AppStep
    data class Playback(val event: String) : AppStep
    data class Wait(val milliseconds: Long) : AppStep
    /** A raw control event from a host smoke replay. The model cannot write this step. */
    data class Event(val id: String, val payload: Any?) : AppStep
    data class Expect(val kind: String, val text: String?, val target: String?, val value: Any?) : AppStep {
        fun describe() = when (kind) {
            "text" -> "visible text \"$text\""
            "absent" -> "no visible text \"$text\""
            "value" -> "$target equals ${JSONObject.wrap(value)}"
            "effect" -> "a $text request"
            else -> "$target is $kind"
        }
    }
}

internal class AppStepError(message: String) : IllegalArgumentException(message)

internal object AppSteps {
    const val MAX_STEPS = 80
    private const val MAX_WAIT_MS = 24L * 3600 * 1000

    fun parse(raw: Any?): List<AppStep> {
        val steps = raw as? JSONArray ?: throw AppStepError("steps must be an array.")
        if (steps.length() > MAX_STEPS) throw AppStepError("Use at most $MAX_STEPS steps.")
        return (0 until steps.length()).map { index ->
            val step = steps.opt(index) as? JSONObject ?: throw AppStepError("Step ${index + 1} must be an object.")
            try { parse(step) } catch (failure: AppStepError) { throw AppStepError("Step ${index + 1}: ${failure.message}") }
        }
    }

    private fun parse(step: JSONObject): AppStep {
        fun string(name: String) = step.opt(name) as? String ?: throw AppStepError("$name must be a string.")
        fun number(name: String) = (step.opt(name) as? Number)?.toDouble() ?: throw AppStepError("$name must be a number.")
        val key = step.opt("key")?.let { it as? String ?: throw AppStepError("key must be a string.") }
        val row = step.opt("row")?.let { (it as? Number)?.toInt()?.takeIf { index -> index >= 0 } ?: throw AppStepError("row must be a row index from 0.") }
        return when {
            step.has("tap") -> AppStep.Tap(string("tap"), key, row)
            step.has("set") -> {
                if (!step.has("value")) throw AppStepError("set needs value.")
                AppStep.Set(string("set"), step.get("value").takeUnless { it == JSONObject.NULL }, key, row)
            }
            step.has("pointer") -> AppStep.Pointer(string("pointer"), number("x").also { require01(it, "x") }, number("y").also { require01(it, "y") })
            step.has("move") -> AppStep.Move(string("move"), string("direction").also {
                if (it !in setOf("left", "right")) throw AppStepError("direction must be left or right.")
            })
            step.has("playback") -> AppStep.Playback(string("playback").also {
                if (it !in setOf("start", "pause", "resume", "restart")) throw AppStepError("playback must be start, pause, resume, or restart.")
            })
            step.has("wait") -> AppStep.Wait(number("wait").toLong().also {
                if (it !in 1..MAX_WAIT_MS) throw AppStepError("wait must be 1 to $MAX_WAIT_MS milliseconds.")
            })
            step.has("expect") -> when (val kind = string("expect")) {
                "text", "absent" -> AppStep.Expect(kind, string("contains").also { if (it.isBlank()) throw AppStepError("contains must not be empty.") }, null, null)
                "value" -> {
                    if (!step.has("equals")) throw AppStepError("value expectations need equals.")
                    AppStep.Expect(kind, null, string("target"), step.get("equals"))
                }
                "enabled", "disabled" -> AppStep.Expect(kind, null, string("target"), null)
                "effect" -> AppStep.Expect(kind, string("capability"), null, null)
                else -> throw AppStepError("expect must be text, absent, value, enabled, disabled, or effect.")
            }
            else -> throw AppStepError("Use one of tap, set, pointer, move, playback, wait, or expect.")
        }
    }

    private fun require01(value: Double, name: String) {
        if (value !in 0.0..1.0) throw AppStepError("$name must be a fraction from 0 to 1.")
    }

    /** Who caused a failed step: the app script, a failed expectation, or an invalid step. */
    enum class FailureKind { APP, EXPECTATION, STEP }

    /** The result of one step list. [failure] names the first step that did not run or did not match. */
    class Outcome(val screens: List<Pair<Int, AppScreen>>, val failure: String?, val final: AppScreen?, val expectations: Int,
        val failedStep: Int? = null, val failureKind: FailureKind? = null)

    suspend fun run(probe: ToolGenerationSmoke.AppProbe, steps: List<AppStep>, everyStep: Boolean): Outcome {
        val screens = mutableListOf<Pair<Int, AppScreen>>()
        var expectations = 0
        fun screen() = AppScreen.render(probe.ui, probe.state, probe.surfaces, probe.nowMillis, probe.surfaceSizes)
        for ((index, step) in steps.withIndex()) {
            val number = index + 1
            var kind = if (step is AppStep.Expect) FailureKind.EXPECTATION else FailureKind.STEP
            val problem = try {
                execute(probe, step)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: AppStepError) {
                kind = FailureKind.STEP
                failure.message
            } catch (failure: ToolSmokeFailure) {
                sandboxFailure(failure.cause)?.let { throw it }
                kind = FailureKind.APP
                "the app failed while handling ${failure.event}: ${failureText(failure.cause)}"
            } catch (failure: Exception) {
                sandboxFailure(failure)?.let { throw it }
                kind = FailureKind.STEP
                "the app rejected this step: ${failureText(failure)}"
            }
            if (step is AppStep.Expect && problem == null) expectations++
            if (problem != null) {
                val current = runCatching { screen() }.getOrNull()
                return Outcome(screens, "step $number ${stepText(step)}: $problem", current, expectations, index, kind)
            }
            if (everyStep && step !is AppStep.Expect) screens += number to screen()
        }
        return Outcome(screens, null, screen(), expectations)
    }

    private suspend fun execute(probe: ToolGenerationSmoke.AppProbe, step: AppStep): String? = when (step) {
        is AppStep.Tap -> {
            try {
                probe.control(step.id, payload(probe, step.id, null, step.key, step.row))
            } catch (failure: IllegalArgumentException) {
                if (failure is AppStepError || failure.message != "Invalid action value.") throw failure
                throw AppStepError("\"${step.id}\" needs a value. Use set with a value, not tap.")
            }
            null
        }
        is AppStep.Set -> { probe.control(step.id, payload(probe, step.id, step.value, step.key, step.row)); null }
        is AppStep.Pointer -> {
            if (step.surface !in probe.surfaceIds) throw AppStepError("No drawing surface ${step.surface}. Surfaces: ${probe.surfaceIds.joinToString().ifEmpty { "none" }}.")
            probe.pointer(step.surface, step.x, step.y); null
        }
        is AppStep.Move -> {
            if (step.surface !in probe.surfaceIds) throw AppStepError("No drawing surface ${step.surface}.")
            probe.control(step.surface, step.direction); null
        }
        is AppStep.Playback -> { probe.playback(step.event); null }
        is AppStep.Wait -> { probe.wait(step.milliseconds); null }
        is AppStep.Event -> { probe.control(step.id, step.payload); null }
        is AppStep.Expect -> expect(probe, step)
    }

    private fun expect(probe: ToolGenerationSmoke.AppProbe, step: AppStep.Expect): String? {
        val screen = AppScreen.render(probe.ui, probe.state, probe.surfaces, probe.nowMillis, probe.surfaceSizes)
        return when (step.kind) {
            "text" -> if (screen.shows(checkNotNull(step.text))) null else "the screen does not show \"${step.text}\""
            "absent" -> if (screen.shows(checkNotNull(step.text))) "the screen still shows \"${step.text}\"" else null
            "effect" -> if (step.text in probe.requested) null
                else "the last operation requested ${probe.requested.joinToString().ifEmpty { "nothing" }}"
            "value" -> {
                val target = checkNotNull(step.target)
                val node = visibleNode(probe.ui, probe.state, target)
                    ?: return "no visible control or output has action ID or bind \"$target\""
                val bind = node.optString("bind").takeIf { it.isNotEmpty() }
                    ?: return "\"$target\" has no bound value"
                val actual = runCatching { ToolUiCatalog.value(probe.state, bind) }.getOrNull()
                if (same(actual, step.value)) null else "$target is ${JSONObject.wrap(actual)}"
            }
            else -> {
                val target = checkNotNull(step.target)
                if (ToolUiCatalog.actionTarget(probe.ui, target) == null) return "no control has action ID \"$target\""
                val enabled = activeIn(probe.ui, probe.state, target)
                if (enabled == (step.kind == "enabled")) null else "$target is ${if (enabled) "enabled" else "disabled or hidden"}"
            }
        }
    }

    private fun same(actual: Any?, expected: Any?): Boolean = when {
        actual is Number && expected is Number -> abs(actual.toDouble() - expected.toDouble()) <= 1e-9 * maxOf(1.0, abs(expected.toDouble()))
        actual is String && expected is String -> actual.trim() == expected.trim()
        actual is JSONArray || actual is JSONObject -> actual.toString() == JSONObject.wrap(expected).toString()
        else -> actual == expected
    }

    /** Finds the first visible node whose action ID or bind path equals [target]. */
    private fun visibleNode(root: JSONObject, state: JSONObject, target: String): JSONObject? {
        fun visit(node: JSONObject): JSONObject? {
            if (!shownNode(node, state)) return null
            if (node.optString("action") == target || node.optString("bind") == target) return node
            return shownChildren(node, state).firstNotNullOfOrNull(::visit)
        }
        return visit(root)
    }

    /** True when the control is on a visible and enabled path in the current UI. */
    private fun activeIn(root: JSONObject, state: JSONObject, id: String): Boolean {
        fun visit(node: JSONObject): Boolean {
            if (!shownNode(node, state) || !ToolUiCatalog.enabled(node, state)) return false
            if (node.optString("action") == id || node.optString("dismissAction") == id || isRowAction(node, id)) return true
            return shownChildren(node, state).any(::visit)
        }
        return visit(root)
    }

    /** Converts a step into the control's wire payload, including the row key for collection controls. */
    private fun payload(probe: ToolGenerationSmoke.AppProbe, id: String, value: Any?, key: String?, row: Int?): Any? {
        val root = probe.ui
        val target = ToolUiCatalog.actionTarget(root, id)
            ?: throw AppStepError("No control has action ID \"$id\". Available: ${actionIds(root).joinToString().ifEmpty { "none" }}.")
        val collection = collectionFor(root, id)
        if (collection == null) {
            if (key != null || row != null) throw AppStepError("\"$id\" is not inside a list. Remove key and row.")
            return value
        }
        val rows = runCatching { ToolUiCatalog.records(collection, probe.state) }.getOrNull() ?: JSONArray()
        val rowKey = key ?: row?.let {
            if (it >= rows.length()) throw AppStepError("Row $it does not exist. The list has ${rows.length()} rows.")
            ToolUiCatalog.stableKey(rows.getJSONObject(it))
        } ?: if (target.inRowTemplate || collection.optString("action") == id || isRowAction(collection, id))
            throw AppStepError("\"$id\" is a row control. Add row (an index from 0) or key.") else return value
        return ToolUiCatalog.scenarioRowPayload(collection, id, rowKey, value)
            ?: throw AppStepError("\"$id\" does not accept a value with a row. Use tap with row.")
    }

    private fun isRowAction(collection: JSONObject, id: String) = collection.optJSONArray("rowActions")?.let { actions ->
        (0 until actions.length()).any { actions.getJSONObject(it).optString("action") == id }
    } == true

    private fun collectionFor(root: JSONObject, id: String): JSONObject? {
        fun visit(node: JSONObject): JSONObject? {
            if (node.optString("type") in setOf("list", "table", "groupedList", "lazyList", "lazyGrid")) {
                val owns = node.optString("action") == id || isRowAction(node, id) ||
                    node.optJSONObject("rowTemplate")?.let { ToolUiCatalog.actionTarget(it, id) } != null
                if (owns) return node
            }
            val children = node.optJSONArray("children") ?: return null
            for (index in 0 until children.length()) visit(children.getJSONObject(index))?.let { return it }
            return null
        }
        return visit(root)
    }

    fun actionIds(root: JSONObject): List<String> = buildList {
        fun visit(node: JSONObject) {
            node.optString("action").takeIf { it.isNotEmpty() }?.let(::add)
            node.optString("dismissAction").takeIf { it.isNotEmpty() }?.let(::add)
            node.optJSONArray("rowActions")?.let { actions -> repeat(actions.length()) { add(actions.getJSONObject(it).optString("action")) } }
            node.optJSONObject("rowTemplate")?.let(::visit)
            node.optJSONArray("children")?.let { children -> repeat(children.length()) { visit(children.getJSONObject(it)) } }
        }
        visit(root)
    }.distinct()

    /** The control events of a smoke replay. Other event types are not replayed. */
    fun fromSmokeEvents(events: List<String>): List<AppStep> = events.map(::JSONObject)
        .filter { it.optString("type") == "action" || it.optString("type") == "liveInput" }
        .map { AppStep.Event(it.getString("actionId"), it.opt("value").takeUnless { value -> value == JSONObject.NULL }) }

    fun stepText(step: AppStep): String = when (step) {
        is AppStep.Tap -> "tap ${step.id}" + rowText(step.key, step.row)
        is AppStep.Set -> "set ${step.id} to ${JSONObject.wrap(step.value)}" + rowText(step.key, step.row)
        is AppStep.Pointer -> "pointer on ${step.surface} at ${step.x}, ${step.y}"
        is AppStep.Move -> "move ${step.direction} on ${step.surface}"
        is AppStep.Playback -> "playback ${step.event}"
        is AppStep.Wait -> "wait ${step.milliseconds} ms"
        is AppStep.Event -> "control ${step.id} with ${JSONObject.wrap(step.payload)}"
        is AppStep.Expect -> "expect ${step.describe()}"
    }

    private fun rowText(key: String?, row: Int?) = key?.let { " key $it" } ?: row?.let { " row $it" }.orEmpty()

    private val sandboxCodes = setOf("javascript_unavailable", "javascript_busy", "javascript_sandbox_died", "javascript_closed",
        "javascript_memory_limit", "cancelled")

    /** A failure of the sandbox, not of the app. It stops the run, because the model cannot fix it. */
    fun sandboxFailure(failure: Throwable?): CloudFailure? = when (failure) {
        is RuntimeFailure -> CloudFailure("javascript_${failure.status.lowercase(java.util.Locale.ROOT)}", SandboxResult(failure.status).message)
        is CloudFailure -> failure
        else -> null
    }?.takeIf { it.code in sandboxCodes }

    fun failureText(failure: Throwable?): String {
        val cause = failure?.cause?.takeIf { failure is ToolSmokeFailure } ?: failure
        return when (cause) {
            is GeneratedOutputDetail -> cause.detail
            // The engine names every non-action event a preview, so the agent needs the rule and the alternative.
            is CloudFailure -> "${cause.code}: ${cause.message}" + if (cause.code != "widget_preview_effects") "" else
                " Only action events may request effects. Frame, timer, and live input events cannot. " +
                    "For an alert when a countdown ends, use a timer node with foreground."
            null -> "unknown failure"
            else -> cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        }.take(600)
    }
}
