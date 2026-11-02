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
    val correction get() = "UI rule: $rule. Path: $path. Expected: $expected. Observed type: $observed. Stage: ${stage.name}."
}

internal class ToolUiValidationTrace(
    private val stage: ToolUiValidationStage,
    private val observer: ((ToolUiDiagnostic) -> Unit)? = null
) {
    private var rule="ui_object"
    private var path="$.ui"
    private var expected="object"
    private var observed="object"
    private var count: Int?=null

    fun at(rule: String,path: String,expected: String,value: Any?=null,count: Int?=null) {
        this.rule=rule; this.path=path; this.expected=expected; this.count=count
        observed=when(value) {
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

    fun failure(category: ToolValidationCategory): ToolUiDiagnostic {
        val diagnostic=ToolUiDiagnostic(stage,category,rule,path,expected,observed,count)
        // The observer cannot change validation, including cancellation and correction behavior.
        try {observer?.invoke(diagnostic)} catch(_: Throwable) {}
        return diagnostic
    }
}
