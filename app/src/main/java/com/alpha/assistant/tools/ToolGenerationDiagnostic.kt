package com.alpha.assistant.tools

import com.alpha.assistant.tools.three.ThreeSceneProblem

import android.os.SystemClock
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.GenerationTransportFailure
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
        private val completions = mutableListOf<ModelCompletionTiming>()

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

/** Only known failure codes leave the device. Any other code becomes "unknown". */
internal fun safeGenerationCode(code: String)=code.takeIf {it in safeGenerationCodes || it.matches(Regex("http_[0-9]{3}"))} ?: "unknown"

private val safeGenerationCodes=setOf("none","unknown","auth","cancelled","empty_response","incomplete","provider","quota","rate_limit",
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
