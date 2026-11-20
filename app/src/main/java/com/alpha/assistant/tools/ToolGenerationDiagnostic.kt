package com.alpha.assistant.tools

import com.alpha.assistant.tools.three.ThreeSceneProblem

import android.os.SystemClock
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.GenerationTransportFailure
import com.alpha.assistant.tools.agent.AppFileNames
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal enum class ToolGenerationTraceOutcome { VALIDATED, FAILED, CANCELED }

/**
 * One model completion in nanoseconds from its request, a provider retry included. It holds counts only, never text.
 * The wait before the first text is mostly reasoning. Leading blank text is not writing.
 */
internal data class ModelCompletionTiming(val firstTextNanos: Long?, val endNanos: Long, val textChars: Int, val completed: Boolean)

/** Operation evidence contains no candidate data or diagnostic details from generated content. */
internal sealed interface ToolGenerationTraceEvent {
    val operationId: UUID
    val attempt: Int
    val atNanos: Long

    data class Transport(
        override val operationId: UUID,
        override val attempt: Int,
        override val atNanos: Long,
        val failure: GenerationTransportFailure,
    ) : ToolGenerationTraceEvent

    /** One agent step. The text stays in memory for an opt-in test observer. */
    data class AgentStep(
        override val operationId: UUID,
        override val attempt: Int,
        override val atNanos: Long,
        val tool: String,
        val reply: String,
        val result: String,
        val durationNanos: Long,
        val completions: List<ModelCompletionTiming> = emptyList(),
    ) : ToolGenerationTraceEvent

    /** VALIDATED marks the candidate boundary. The caller owns publication. */
    data class Finished(
        override val operationId: UUID,
        override val attempt: Int,
        override val atNanos: Long,
        val outcome: ToolGenerationTraceOutcome,
        val failureCode: String,
    ) : ToolGenerationTraceEvent

    /** One named point of the time before the first screen: a listed name, a listed detail value (safePhaseDetail), and times. */
    data class Phase(
        override val operationId: UUID,
        override val attempt: Int,
        override val atNanos: Long,
        val name: String,
        val detail: String?,
        val ms: Long?,
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

    /** A phase outside a generation run, such as the draft: attempt 0. */
    fun phase(name: String, detail: String? = null, ms: Long? = null) = emitPhase(0, name, detail, ms)

    /** A name of no list is dropped. A detail leaves the device only as a listed value (safePhaseDetail). */
    private fun emitPhase(attempt: Int, name: String, detail: String?, ms: Long?) {
        if (name in phaseNames) emit { ToolGenerationTraceEvent.Phase(operationId, attempt, it, name, safePhaseDetail(name, detail), ms) }
    }

    inner class Run {
        private var lastAttempt = 1
        private val completions = mutableListOf<ModelCompletionTiming>()

        fun phase(name: String, detail: String? = null, ms: Long? = null) = emitPhase(lastAttempt, name, detail, ms)

        /** The next agent step takes the completions made before it. */
        fun completion(timing: ModelCompletionTiming) { completions += timing }

        fun agentStep(step: Int, tool: String, reply: String, result: String, durationNanos: Long) {
            lastAttempt = maxOf(lastAttempt, step)
            val taken = completions.toList().also { completions.clear() }
            emit { ToolGenerationTraceEvent.AgentStep(operationId, step, it, tool, reply, result, durationNanos, taken) }
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
                ToolGenerationTraceOutcome.FAILED -> safeGenerationCode(cloudCode ?: "unknown")
            }
            emit { ToolGenerationTraceEvent.Finished(operationId, lastAttempt, it, outcome, code) }
        }
    }
}

private val phaseNames = setOf("draft_start", "draft_end", "capability_guidance_start", "capability_guidance_end",
    "agent_call_start") + AppFileNames.PHASES

/** "ok", "faults", or a sandbox status such as TIMEOUT or BUSY (AppFileCompiler). */
private val compileStatus = Regex("ok|faults|[A-Z_]{1,32}")

/** The word of outside("word"). The prompt has words such as 3d. */
private val outsideWord = Regex("[a-z0-9]{1,16}")

/**
 * Only listed values leave the device: the status of a compile, and the outcome of a plan step. The word of an outside
 * reply is model text, so only a short plain word passes. Any other value becomes "other". The other phases have no detail.
 */
internal fun safePhaseDetail(name: String, detail: String?): String? = when {
    detail == null -> null
    name == AppFileNames.COMPILE -> detail.takeIf { it.matches(compileStatus) } ?: "other"
    name != AppFileNames.PLAN_OUTCOME -> null
    detail == "accepted" -> detail
    detail.startsWith("outside:") -> "outside:" + (detail.removePrefix("outside:").takeIf { it.matches(outsideWord) } ?: "other")
    detail.startsWith("fallback:") -> detail.removePrefix("fallback:").let { reason ->
        "fallback:" + (reason.takeIf { it in AppFileNames.FALLBACKS || it.startsWith(AppFileNames.COMPILE_STATUS) &&
            it.removePrefix(AppFileNames.COMPILE_STATUS).matches(compileStatus) } ?: "other")
    }
    else -> "other"
}

/** Only known failure codes leave the device. Any other code becomes "unknown". */
internal fun safeGenerationCode(code: String)=code.takeIf {it in safeGenerationCodes || it.matches(Regex("http_[0-9]{3}"))} ?: "unknown"

/** The failure code of a sandbox status, as javascript_timeout for TIMEOUT. */
internal fun sandboxCode(status: String) = "javascript_" + status.lowercase(java.util.Locale.ROOT)

/** The failure code of each sandbox status. It stays above safeGenerationCodes, which reads it as the file loads. */
internal val SANDBOX_CODES = setOf("javascript_unavailable","javascript_busy","javascript_sandbox_died","javascript_closed","javascript_memory_limit",
    "javascript_script_error","javascript_timeout","javascript_invalid_request","javascript_invalid_result")

private val safeGenerationCodes=setOf("none","unknown","auth","cancelled","empty_response","incomplete","provider","quota","rate_limit","refusal",
    "response_length","response_size","stream_invalid","stream_timeout","stream_io","pre_headers_io",
    "pre_headers_timeout","source_attribution","decision_language",
    "unavailable","tool_invalid","tool_limit","widget_generation","widget_response_size",
    "widget_request","widget_plan","widget_owner","widget_selector","widget_questions","widget_incomplete",
    "three_behavior","three_declined","three_deadline","three_unavailable",
    "widget_spec","widget_state","widget_behavior","widget_patch","widget_preview","widget_preview_state","widget_preview_effects","widget_timer_host","drawing.command_fields",
    "records_with_effects") + SANDBOX_CODES +
    ThreeSceneProblem.entries.map { it.code }
