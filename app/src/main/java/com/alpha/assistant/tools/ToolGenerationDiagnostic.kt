package com.alpha.assistant.tools

import com.alpha.assistant.tools.three.ThreeSceneProblem

import android.os.SystemClock
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.GenerationTransportFailure
import com.alpha.assistant.cloud.ResponseShape
import com.alpha.assistant.tools.generation.GenerationBehavior.Kind
import com.alpha.assistant.tools.generation.GeometryScope
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import com.alpha.assistant.tools.generation.GenerationRepair
import java.util.UUID

internal enum class ToolGenerationStage(val id: String) {
    AVAILABILITY("availability"), PLAN("plan"), SELECTOR("selector"), ASSEMBLY("assembly"), PROVIDER("provider"),
    DEFINITION("definition"), STATE("state"), SMOKE("smoke"), BEHAVIOR("behavior")
}
internal enum class ToolGenerationOutcome(val id: String) {
    COMPLETED("completed"), VALIDATED("validated"), REJECTED("rejected"), PROVIDER_FAILURE("providerFailure"),
    RUNTIME_UNAVAILABLE("runtimeUnavailable"), CANCELED("canceled")
}
internal enum class ToolGenerationDisposition(val id: String) {
    CONTINUE("continue"), CORRECT("correct"), FINISH("finish")
}
internal enum class ToolGenerationTraceOutcome { VALIDATED, FAILED, CANCELED }

internal enum class ToolGenerationRoute(val id: String) {
    NATIVE("native"), MIXED("mixed"), WHOLE_SCREEN("whole_screen")
}

internal enum class BehaviorFailureRule(val id: String) {
    ROW_SCOPE_MISMATCH("row_scope_mismatch"), BINDING_SHAPE("binding_shape"), BINDING_MISSING("binding_missing"), TARGET_MISSING("target_missing"),
    TARGET_HIDDEN("target_hidden"), OBSERVATION_KIND("observation_kind"), SCALAR_MISSING("scalar_missing"),
    SCALAR_SHAPE("scalar_shape"), ACTION_UNAVAILABLE("action_unavailable"), BOOLEAN_ACTION_PAYLOAD("boolean_action_payload"), SURFACE_UNAVAILABLE("surface_unavailable"),
    DRAWING_UNAVAILABLE("drawing_unavailable"), SUBSCRIPTION_UNAVAILABLE("subscription_unavailable"),
    ASSERTION_FAILED("assertion_failed"), EVENT_LIMIT("event_limit"), EXTERNAL_RESULT_REQUIRED("external_result_required")
}

internal enum class BehaviorValueShape(val id: String) {
    MISSING("missing"), NULL("null"), STRING("string"), NUMBER("number"), BOOLEAN("boolean"), ARRAY("array"), OBJECT("object");

    companion object {
        fun of(value: Any?): BehaviorValueShape? = when {
            value == null -> MISSING
            value === JSONObject.NULL -> NULL
            value is String -> STRING
            value is Number -> NUMBER
            value is Boolean -> BOOLEAN
            value is JSONArray -> ARRAY
            value is JSONObject -> OBJECT
            else -> null
        }
    }
}

internal enum class BehaviorComparison {
    EQUALS, UNCHANGED, CHANGED, GREATER, LESS, NONEMPTY, EMPTY, MOTION, AVAILABILITY;

    val wire get() = name.lowercase(java.util.Locale.ROOT)

    companion object {
        fun from(test: String) = entries.first { it.wire == test }
    }
}

internal enum class BehaviorDiscoveryStage(val id: String) {
    WRITER_BINDING("writer_binding"), BEHAVIOR_EXECUTION("behavior_execution")
}

internal enum class BehaviorObservation { CHANGED, UNCHANGED, COMPARISON_FAILED, TOO_FEW_CHANGES }

/** Step and slot numbers start at one. Both follow the validated scenario order. */
internal data class BehaviorBindingDiagnostic(
    val route: ToolGenerationRoute,
    val rule: BehaviorFailureRule,
    val step: Int? = null,
    val slot: Int? = null,
    val slotKind: Kind? = null,
    val native: Boolean? = null,
    val bindingKind: String? = null,
    val nodeKind: String? = null,
    val valueShape: BehaviorValueShape? = null,
    val scope: GeometryScope? = null,
    val comparison: BehaviorComparison? = null,
    val observation: BehaviorObservation? = null,
    val frames: Int? = null,
    val requiredChanges: Int? = null,
    val observedChanges: Int? = null,
    val discoveryStage: BehaviorDiscoveryStage? = null,
) {
    fun report(): JSONObject = JSONObject().put("route", route.id).put("rule", rule.id)
        .put("step", step).put("slot", slot).put("slotKind", slotKind?.wire)
        .put("origin", native?.let { if (it) "native" else "writer" } ?: "unknown")
        .put("bindingKind", bindingKind).put("nodeKind", ToolUiCatalog.components[nodeKind]?.type)
        .put("valueShape", valueShape?.id)
        .put("scope", scope?.wire).put("comparison", comparison?.wire)
        .put("observation", observation?.name?.lowercase(java.util.Locale.ROOT))
        .put("frames", frames).put("requiredChanges", requiredChanges).put("observedChanges", observedChanges)
        .put("contractOwner", "plan".takeIf {
            rule == BehaviorFailureRule.ROW_SCOPE_MISMATCH || rule == BehaviorFailureRule.BOOLEAN_ACTION_PAYLOAD
        })
        .put("discoveryStage", discoveryStage?.id)
}

/** Operation evidence contains no candidate data or diagnostic details from generated content. */
internal sealed interface ToolGenerationTraceEvent {
    val operationId: UUID
    val attempt: Int
    val atNanos: Long

    data class Stage(
        override val operationId: UUID,
        override val attempt: Int,
        override val atNanos: Long,
        val stage: ToolGenerationStage,
        val started: Boolean,
        val selectorBatch: String?,
        val providerStage: CloudGenerationStage?,
    ) : ToolGenerationTraceEvent

    data class Attempt(
        override val operationId: UUID,
        override val attempt: Int,
        override val atNanos: Long,
        val stage: ToolGenerationStage,
        val outcome: ToolGenerationOutcome,
        val disposition: ToolGenerationDisposition,
        val failureCode: String,
        val validationCategory: ToolValidationCategory?,
        val validationRule: String?,
        val selectorBatch: String?,
        val planPath: String?,
        val planShape: ResponseShape?,
        val behavior: String?,
        val smoke: JSONObject?,
        val behaviorBinding: BehaviorBindingDiagnostic? = null,
    ) : ToolGenerationTraceEvent

    data class Repair(
        override val operationId: UUID,
        override val attempt: Int,
        override val atNanos: Long,
        val providerStage: CloudGenerationStage,
        val repairNumber: Int,
        val mode: GenerationRepair.Mode,
        val result: String,
        val responseBytes: Int?,
        val candidateBytes: Int?,
        val changes: Int?,
        val sourceEdits: Int?,
        val durationNanos: Long,
    ) : ToolGenerationTraceEvent

    data class Transport(
        override val operationId: UUID,
        override val attempt: Int,
        override val atNanos: Long,
        val failure: GenerationTransportFailure,
    ) : ToolGenerationTraceEvent

    /** VALIDATED marks the candidate boundary. The caller owns publication. */
    data class Finished(
        override val operationId: UUID,
        override val attempt: Int,
        override val atNanos: Long,
        val outcome: ToolGenerationTraceOutcome,
        val failureCode: String,
        val partsOnly: Boolean?,
        val route: ToolGenerationRoute? = null,
    ) : ToolGenerationTraceEvent
}

/** The opt-in host owns collection and retention. The generation path retains no events. */
internal class ToolGenerationTrace(
    val operationId: UUID,
    private val onEvent: (ToolGenerationTraceEvent) -> Unit,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private inline fun emit(event: (Long) -> ToolGenerationTraceEvent) {
        try { onEvent(event(clockNanos())) } catch (_: Throwable) {}
    }

    inner class Run {
        private var lastAttempt = 1
        var partsOnly: Boolean? = null
        var route: ToolGenerationRoute? = null

        fun stage(attempt: Int, stage: ToolGenerationStage, started: Boolean, selectorBatch: String?,
            providerStage: CloudGenerationStage?) {
            lastAttempt = maxOf(lastAttempt, attempt)
            emit { ToolGenerationTraceEvent.Stage(operationId, attempt, it, stage, started,
                safeSelectorBatch(selectorBatch), providerStage) }
        }

        fun diagnostic(diagnostic: ToolGenerationAttemptDiagnostic) {
            lastAttempt = maxOf(lastAttempt, diagnostic.attempt)
            emit { ToolGenerationTraceEvent.Attempt(operationId, diagnostic.attempt, it, diagnostic.stage,
                diagnostic.outcome, diagnostic.disposition, diagnostic.failureCode,
                diagnostic.category, diagnostic.ui?.rule ?: diagnostic.safeSchemaRule,
                safeSelectorBatch(diagnostic.selectorBatch), diagnostic.planPath?.takeIf(needsPlanPath::matches),
                diagnostic.planShape?.takeIf { needsPlanPath.matches(it.path) }, diagnostic.behavior, diagnostic.safeSmoke(),
                diagnostic.behaviorBinding) }
        }

        fun repair(attempt: Int, stage: CloudGenerationStage, repairNumber: Int, mode: GenerationRepair.Mode,
            result: String, responseBytes: Int?, candidateBytes: Int?, changes: Int?, sourceEdits: Int?, durationNanos: Long) {
            lastAttempt = maxOf(lastAttempt, attempt)
            emit { ToolGenerationTraceEvent.Repair(operationId, attempt, it, stage, repairNumber, mode, result,
                responseBytes, candidateBytes, changes, sourceEdits, durationNanos) }
        }

        fun transport(failure: GenerationTransportFailure) {
            emit { ToolGenerationTraceEvent.Transport(operationId, lastAttempt, it, failure) }
        }

        fun finish(failure: Throwable? = null) {
            val cloudCode = (failure as? CloudFailure)?.code
            val outcome = when {
                failure == null -> ToolGenerationTraceOutcome.VALIDATED
                failure is CancellationException || cloudCode == "cancelled" -> ToolGenerationTraceOutcome.CANCELED
                else -> ToolGenerationTraceOutcome.FAILED
            }
            val code = when (outcome) {
                ToolGenerationTraceOutcome.VALIDATED -> "none"
                ToolGenerationTraceOutcome.CANCELED -> "cancelled"
                ToolGenerationTraceOutcome.FAILED -> ToolGenerationAttemptDiagnostic.safeCode(cloudCode ?: "unknown")
            }
            emit { ToolGenerationTraceEvent.Finished(operationId, lastAttempt, it, outcome, code, partsOnly, route) }
        }
    }
}

internal inline fun <T> ToolGenerationTrace.Run?.timed(
    attempt: Int,
    stage: ToolGenerationStage,
    selectorBatch: String? = null,
    providerStage: CloudGenerationStage? = null,
    block: () -> T,
): T {
    this?.stage(attempt, stage, true, selectorBatch, providerStage)
    try {
        return block()
    } finally {
        this?.stage(attempt, stage, false, selectorBatch, providerStage)
    }
}

private fun safeSelectorBatch(batch: String?) = batch?.takeIf { it == "sections" || it == "parts" }
private val needsPlanPath = Regex(
    """\$(?:\.(?:wholeScreen|scene|checks|checkEvidence|visualReview|requiredCapabilities|excerpt|behavior|scenario|steps|title|description|language|inputs|constants|needs|questions|id|label|type|value|uses|sourceIds|stops|bands|items|ideas|name|minutes|min|max|step|control|expression|example|unit|date|offsetDays|repeatDays|effort|ask|options|count|maxFrames|milliseconds)(?:\[\d{1,2}])?)*|\$\.(?:behavior|scene\.checks)\.scenario\.slots\[\d{1,2}](?:\.(?:valueType|rowValueType))?"""
)

/** No candidate, exception message, private field name, or generated identifier enters this record.
 * Binding subrules come from validator checks, not candidate text or exception messages.
 * JSON has no typed parser cause. Its category remains tool_json without an inferred subrule.
 */
internal data class ToolGenerationAttemptDiagnostic(
    val attempt: Int,
    val outcome: ToolGenerationOutcome,
    val stage: ToolGenerationStage,
    val failureCode: String,
    val disposition: ToolGenerationDisposition,
    val category: ToolValidationCategory?=null,
    val ui: ToolUiDiagnostic?=null,
    val schemaRule: String?=null,
    val smokeEvent: String?=null,
    val smokePath: String?=null,
    val smokeCode: String?=null,
    val selectorBatch: String?=null,
    val planPath: String?=null,
    val planShape: ResponseShape?=null,
    val behavior: String?=null,
    val behaviorBinding: BehaviorBindingDiagnostic?=null,
) {
    internal val safeSchemaRule get() = schemaRule?.takeIf { it in schemaRules }

    internal fun safeSmoke() = smokeEvent?.let { event ->
        JSONObject().put("event",event.takeIf {it in smokeEvents} ?: "unknown")
            .put("path",smokePath?.takeIf {it.matches(smokeLocation)} ?: "unknown")
            .put("failureCode",safeCode(smokeCode ?: "unknown"))
    }

    fun report()=JSONObject().put("attempt",attempt).put("outcome",outcome.id).put("stage",stage.id)
        .put("failureCode",safeCode(failureCode)).put("category",category?.id ?: "unknown")
        .put("disposition",disposition.id).apply {
            safeSelectorBatch(selectorBatch)?.let {put("selectorBatch",it)}
            ui?.let {diagnostic -> put("ui",JSONObject().put("stage",diagnostic.stage.name.lowercase(java.util.Locale.ROOT))
                .put("category",diagnostic.category.id).put("rule",diagnostic.rule).put("path",diagnostic.path)
                .put("expected",diagnostic.expected).put("observed",diagnostic.observed).put("count",diagnostic.count ?: JSONObject.NULL))}
            safeSchemaRule?.let {put("schema",JSONObject().put("rule",it).put("path","unknown"))}
            behavior?.let { put("behavior", it) }
            behaviorBinding?.let { put("behaviorBinding", it.report()) }
            safeSmoke()?.let { put("smoke", it) }
        }
    companion object {
        private val codes=setOf("none","unknown","auth","cancelled","empty_response","incomplete","provider","quota","rate_limit",
            "response_length","response_size","stream_invalid","stream_timeout","stream_io","pre_headers_io",
            "pre_headers_timeout","source_attribution","decision_language",
            "unavailable","tool_invalid","tool_limit","widget_generation","widget_response_size",
            "widget_request","widget_plan","widget_owner","widget_selector","widget_questions","widget_incomplete",
            "three_behavior","three_declined","three_deadline","three_unavailable",
            "widget_spec","widget_state","widget_behavior","widget_patch","widget_preview","widget_preview_state","widget_preview_effects","widget_timer_host","drawing.command_fields",
            "records_with_effects",
            "javascript_unavailable","javascript_busy","javascript_sandbox_died","javascript_closed","javascript_memory_limit",
            "javascript_script_error","javascript_timeout","javascript_invalid_request","javascript_invalid_result") +
            ThreeSceneProblem.entries.map { it.code }
        private val schemaRules=setOf("schema.name","schema.nodes","schema.structuredNodes","schema.depth","schema.scalar",
            "schema.constructor","schema.type","object.keys","object.fields","list.keys","list.fields","list.maxItems","list.id",
            "list.field","array.keys","array.maxItems","state.fields","collections.count")
        private val smokeEvents=setOf("preview","render","open","action","liveInput","viewport","frame","timer","start","pointer","control","pause","resume","restart","stop")
        private val smokeLocation = Regex(
            """\$\.(?:ui(?:\.(?:children|rowActions)\[[0-9]{1,3}\]|\.rowTemplate)*|timers\[[0-9]{1,3}\]|surfaces\[[0-9]{1,3}\](?:\.commands\[[0-9]{1,3}\](?:\.children\[[0-9]{1,3}\])*(?:\.stroke)?)?|(?:behavior|scene\.checks)(?:\.scenario\.steps\[[0-9]{1,2}\])?)"""
        )
        internal fun safeCode(code: String)=code.takeIf {it in codes || it.matches(Regex("http_[0-9]{3}"))} ?: "unknown"
    }
}
