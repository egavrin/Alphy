package com.alpha.assistant.tools

import org.json.JSONObject

internal enum class ToolGenerationStage(val id: String) {
    AVAILABILITY("availability"), PROVIDER("provider"), DEFINITION("definition"), STATE("state"), SMOKE("smoke")
}
internal enum class ToolGenerationOutcome(val id: String) {
    ACCEPTED("accepted"), REJECTED("rejected"), PROVIDER_FAILURE("providerFailure"),
    RUNTIME_UNAVAILABLE("runtimeUnavailable"), CANCELED("canceled")
}

/** No candidate, exception message, private field name, or generated identifier enters this record.
 * Binding subrules come from validator checks, not candidate text or exception messages.
 * JSON has no typed parser cause. Its category remains tool_json without an inferred subrule.
 */
internal data class ToolGenerationAttemptDiagnostic(
    val attempt: Int,
    val outcome: ToolGenerationOutcome,
    val stage: ToolGenerationStage,
    val failureCode: String,
    val category: ToolValidationCategory?=null,
    val ui: ToolUiDiagnostic?=null,
    val schemaRule: String?=null,
    val smokeEvent: String?=null,
    val smokePath: String?=null,
    val smokeCode: String?=null
) {
    fun report()=JSONObject().put("attempt",attempt).put("outcome",outcome.id).put("stage",stage.id)
        .put("failureCode",safeCode(failureCode)).put("category",category?.id ?: "unknown").apply {
            ui?.let {diagnostic -> put("ui",JSONObject().put("stage",diagnostic.stage.name.lowercase(java.util.Locale.ROOT))
                .put("category",diagnostic.category.id).put("rule",diagnostic.rule).put("path",diagnostic.path)
                .put("expected",diagnostic.expected).put("observed",diagnostic.observed).put("count",diagnostic.count ?: JSONObject.NULL))}
            schemaRule?.takeIf {it in schemaRules}?.let {put("schema",JSONObject().put("rule",it).put("path","unknown"))}
            if(smokeEvent!=null) put("smoke",JSONObject()
                .put("event",smokeEvent.takeIf {it in smokeEvents} ?: "unknown")
                .put("path",smokePath?.takeIf {it.matches(smokeLocation)} ?: "unknown")
                .put("failureCode",safeCode(smokeCode ?: "unknown")))
        }
    companion object {
        private val codes=setOf("none","unknown","auth","cancelled","empty_response","incomplete","provider","quota","rate_limit",
            "response_size","stream_invalid","unavailable","tool_invalid","tool_limit","widget_generation","widget_response_size",
            "widget_spec","widget_state","widget_preview","widget_preview_state","widget_preview_effects",
            "javascript_unavailable","javascript_busy","javascript_sandbox_died","javascript_closed","javascript_memory_limit",
            "javascript_script_error","javascript_timeout","javascript_invalid_request","javascript_invalid_result")
        private val schemaRules=setOf("schema.name","schema.nodes","schema.structuredNodes","schema.depth","schema.scalar",
            "schema.constructor","schema.type","object.keys","object.fields","list.keys","list.fields","list.maxItems","list.id",
            "list.field","array.keys","array.maxItems","state.fields","collections.count")
        private val smokeEvents=setOf("preview","render","open","action","viewport","frame","timer","start","pointer","control","pause","resume","restart","stop")
        private val smokeLocation=Regex("\\$\\.(?:ui(?:\\.(?:children|rowActions)\\[[0-9]{1,3}\\]|\\.rowTemplate)*|(?:surfaces|timers)\\[[0-9]{1,3}\\])")
        internal fun safeCode(code: String)=code.takeIf {it in codes || it.matches(Regex("http_[0-9]{3}"))} ?: "unknown"
    }
}
