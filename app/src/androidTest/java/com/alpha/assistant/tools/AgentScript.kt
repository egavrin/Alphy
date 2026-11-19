package com.alpha.assistant.tools

import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** One scripted model reply. */
internal sealed interface Scripted {
    data class Text(val text: String) : Scripted
    /** The call fails with a CloudFailure of [code]. */
    data class Fails(val code: String) : Scripted
    /** No text for [firstTextMs], then [text]. A cancel of the call ends the wait. */
    data class Waits(val firstTextMs: Long, val text: String) : Scripted
    /** [firstLine] at once, then no text for [stallMs]. */
    data class Stalls(val firstLine: String, val stallMs: Long) : Scripted
}

/** A scripted model for agent loop tests. It gives the replies in order and then repeats the last one. */
internal class AgentScriptProvider(private val replies: List<Scripted>) : ChatProvider {
    constructor(vararg replies: String) : this(replies.map { Scripted.Text(it) })

    val calls: MutableList<CloudRequest> = CopyOnWriteArrayList()
    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        calls += request
        suspend fun text(value: String) {
            emit(CloudEvent.Text(value))
            emit(CloudEvent.RoundComplete(value, false))
        }
        when (val reply = replies.getOrElse(calls.lastIndex) { replies.last() }) {
            is Scripted.Text -> text(reply.text)
            is Scripted.Fails -> throw CloudFailure(reply.code, "The scripted call failed.")
            is Scripted.Waits -> { delay(reply.firstTextMs); text(reply.text) }
            is Scripted.Stalls -> { emit(CloudEvent.Text(reply.firstLine)); delay(reply.stallMs); emit(CloudEvent.RoundComplete(reply.firstLine, false)) }
        }
    }
    fun feedback(call: Int) = calls[call].turns.last().text
}

/** The agent reply that writes [app]. */
internal fun writeAppReply(app: JSONObject) = JSONObject().put("tool", "write_app").put("app", app).toString()
