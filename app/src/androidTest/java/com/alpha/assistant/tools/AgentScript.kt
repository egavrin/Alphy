package com.alpha.assistant.tools

import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import org.json.JSONObject

/** A scripted model for agent loop tests. It gives the replies in order and then repeats the last one. */
internal class AgentScriptProvider(private vararg val replies: String) : ChatProvider {
    val calls = mutableListOf<CloudRequest>()
    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        calls += request
        val reply = replies.getOrElse(calls.lastIndex) { replies.last() }
        emit(CloudEvent.Text(reply))
        emit(CloudEvent.RoundComplete(reply, false))
    }
    fun feedback(call: Int) = calls[call].turns.last().text
}

/** The agent reply that writes [app]. */
internal fun writeAppReply(app: JSONObject) = JSONObject().put("tool", "write_app").put("app", app).toString()
