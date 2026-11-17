package com.alpha.assistant.cloud

import org.json.JSONArray
import org.json.JSONObject

internal data class ToolCall(val id: String, val name: String, val arguments: String)

/** [replay] is the reasoning of a tool round in the form of its wire. Alphy sends it back unchanged. */
internal data class Completion(val text: String, val calls: List<ToolCall>, val replay: JSONArray? = null)

internal enum class CompletionStage(val strictDecision: Boolean = false) {
    GENERAL, GENERATION, DECISION(true),
    AUTOMATION_DRAFT, SEARCH_PLAN, ANSWER, GROUNDED_DECISION
}

internal data class StreamLimits(
    val maxText: Int = 2 * 1024 * 1024,
    val allowTools: Boolean = true,
    val reportLength: Boolean = false,
    val stage: CompletionStage,
    val maxWireChars: Int = 2 * 1024 * 1024,
    val retainReasoning: Boolean = false,
)

/**
 * Sends one canonical request in the format of one provider and parses the streamed reply.
 * The canonical body is Chat Completions JSON without `model`. Its `thinking` type (`enabled` or `disabled`)
 * selects the reasoning control. An assistant `tool_calls` message can carry [Completion.replay] as
 * `reasoning_replay`. A wire never changes the canonical body.
 */
internal interface ChatWire {
    suspend fun complete(key: String, body: JSONObject, limits: StreamLimits, session: CloudSession,
        onText: suspend (String) -> Unit): Completion
}
