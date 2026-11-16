package com.alpha.assistant.tools

import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import org.json.JSONObject

private val acceptedAgentResult = Regex("^(write_app|edit_app): OK\\b")

/** True when the app agent's host accepted the last app version in this request. */
internal val CloudRequest.agentAppAccepted: Boolean
    get() = generationStage == CloudGenerationStage.AGENT && turns.size > 1 && acceptedAgentResult.containsMatchIn(turns.last().text)

/** The scripted app agent finishes with one check that only needs the app to open. */
internal const val agentFixtureFinish =
    """{"tool":"finish","summary":"Done.","checks":[{"name":"opens","steps":[{"expect":"absent","contains":"fixture text that never shows"}]}]}"""

/** Finishes when the host accepted the last version. Otherwise the caller replies with [agentWrite]. */
internal suspend fun CloudRequest.replyWithAgentFinish(emit: suspend (CloudEvent) -> Unit): Boolean {
    if (!agentAppAccepted) return false
    emit(CloudEvent.Text(agentFixtureFinish))
    return true
}

/** One write_app step with a full definition in the old fixture format. */
internal fun agentWrite(definition: String): String =
    JSONObject().put("tool", "write_app").put("app", JSONObject(definition)).toString()

/** The host rejects this step without running the app, so a failing agent reaches its step limit quickly. */
internal const val agentStall = """{"tool":"finish","summary":"Not done.","checks":[]}"""

/** Writes [definition] in the first step and finishes after the host accepts it. After a rejection the agent stalls. */
internal fun CloudRequest.agentReply(definition: String): String = when {
    agentAppAccepted -> agentFixtureFinish
    turns.size == 1 -> agentWrite(definition)
    else -> agentStall
}
