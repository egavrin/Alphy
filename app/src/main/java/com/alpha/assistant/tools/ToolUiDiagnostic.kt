package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject

internal enum class ToolUiValidationStage { DEFINITION, STATE, PREVIEW }

/** Contains only fixed contract terms, structural indices, generic types, and counts. */
internal data class ToolUiDiagnostic(
    val stage: ToolUiValidationStage,
    val category: ToolValidationCategory,
    val rule: String,
    val path: String,
    val expected: String,
    val observed: String,
    val count: Int?
) {
    val correction get() = buildString {
        append("UI rule: $rule. Path: $path. Expected: $expected. Observed type: $observed. Stage: ${stage.name}.")
        if (count != null) append(" Observed count: $count.")
        if (rule == "component_text") {
            append(" Give this node a short label in the requested language. Omit text only if the catalog allows the node without text.")
        }
        if (rule == "component_action") {
            append(" Use distinct IDs for dismissAction and every child action. If both close the modal, handle both IDs in source.")
        }
        if (rule == "native_capability_arguments") {
            append(" Use the native argument schema for this control. If it needs an owned reference, omit the control until a matching native receipt supplies that reference. Hidden controls must also have valid arguments.")
        }
    }
}

internal class ToolUiValidationTrace(
    private val stage: ToolUiValidationStage,
    private val observer: ((ToolUiDiagnostic) -> Unit)? = null
) {
    private data class Context(
        val rule: String, val path: String, val expected: String, val observed: String, val count: Int?
    )

    private var active = Context("ui_object", "$.ui", "object", "object", null)
    private var escaping: Context? = null

    fun <T> checking(rule: String, path: String, expected: String, value: Any?, count: Int?, block: () -> T): T {
        val parent = active
        active = Context(rule, path, expected, observedType(value), count)
        escaping = null
        return try {
            block().also { escaping = null }
        } catch (failure: Throwable) {
            if (escaping == null) escaping = active
            throw failure
        } finally {
            active = parent
        }
    }

    fun failure(category: ToolValidationCategory): ToolUiDiagnostic {
        val context = escaping ?: active
        val diagnostic = with(context) { ToolUiDiagnostic(stage, category, rule, path, expected, observed, count) }
        // The observer cannot change validation, including cancellation and correction behavior.
        try { observer?.invoke(diagnostic) } catch (_: Throwable) {}
        return diagnostic
    }

    private fun observedType(value: Any?) = when (value) {
        null -> "missing"
        JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is String -> "string"
        is Int, is Long -> "integer"
        is Number -> "number"
        is Boolean -> "boolean"
        else -> "other"
    }
}

internal fun <T> ToolUiValidationTrace?.checking(
    rule: String, path: String, expected: String, value: Any? = null, count: Int? = null, block: () -> T
): T = if (this == null) block() else checking(rule, path, expected, value, count, block)
