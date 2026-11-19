package com.alpha.assistant.tools

import android.content.Context
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.agent.GenerationSettings
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

/** The model calls and the trace of one generation, as the plan step and the change step mark them. */
internal open class TracedGeneration(private val calls: List<CloudRequest>, val events: List<ToolGenerationTraceEvent>) {
    val phases get() = events.filterIsInstance<ToolGenerationTraceEvent.Phase>()
    val stages get() = calls.map { it.generationStage }
    val outcome get() = phases.single { it.name == "plan_outcome" }.detail
    val compiles get() = phases.filter { it.name == "compile" }.map { it.detail }
    fun at(name: String) = phases.first { it.name == name }.atNanos
    fun msBetween(from: String, to: String) = (at(to) - at(from)) / 1_000_000
}

/** The agent reply that writes [app]. */
internal fun writeAppReply(app: JSONObject) = JSONObject().put("tool", "write_app").put("app", app).toString()

/** Sets the plan-step switch of [provider] to [on] for [block], and restores the earlier value after it. */
internal inline fun <T> withPlanStep(context: Context, provider: AiProvider, on: Boolean, block: () -> T): T {
    val settings = GenerationSettings(context)
    val before = settings.planStep(provider)
    settings.setPlanStep(provider, on)
    try {
        return block()
    } finally {
        settings.setPlanStep(provider, before)
    }
}
