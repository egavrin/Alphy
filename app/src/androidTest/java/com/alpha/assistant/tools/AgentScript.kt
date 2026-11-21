package com.alpha.assistant.tools

import android.content.Context
import android.content.SharedPreferences
import com.alpha.assistant.cloud.AiProvider
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
    /** For each part: no text for its delay in ms, then the part as one text delta. Then the round ends with the joined text. */
    data class Deltas(val parts: List<Pair<Long, String>>) : Scripted
}

/** A scripted model for agent loop tests. It gives the replies in order and then repeats the last one. */
internal class AgentScriptProvider(private val replies: List<Scripted>) : ChatProvider {
    constructor(vararg replies: String) : this(replies.map { Scripted.Text(it) })

    val calls: MutableList<CloudRequest> = CopyOnWriteArrayList()
    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        calls += request
        replies.getOrElse(calls.lastIndex) { replies.last() }.send(emit)
    }
    fun feedback(call: Int) = calls[call].turns.last().text
}

/** Sends this reply as a model sends it: its text deltas and the end of the round, or its failure. */
internal suspend fun Scripted.send(emit: suspend (CloudEvent) -> Unit) {
    suspend fun whole(value: String) {
        emit(CloudEvent.Text(value))
        emit(CloudEvent.RoundComplete(value, false))
    }
    when (this) {
        is Scripted.Text -> whole(text)
        is Scripted.Fails -> throw CloudFailure(code, "The scripted call failed.")
        is Scripted.Waits -> { delay(firstTextMs); whole(text) }
        is Scripted.Stalls -> { emit(CloudEvent.Text(firstLine)); delay(stallMs); emit(CloudEvent.RoundComplete(firstLine, false)) }
        is Scripted.Deltas -> {
            parts.forEach { (ms, part) -> delay(ms); emit(CloudEvent.Text(part)) }
            emit(CloudEvent.RoundComplete(parts.joinToString("") { it.second }, false))
        }
    }
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

/** Sets the plan-step switch of [provider] to [on] for [block], and restores the earlier entry exactly after it, an absent one too. */
internal inline fun <T> withPlanStep(context: Context, provider: AiProvider, on: Boolean, block: () -> T): T =
    withStoredPlanSteps(context, mapOf(provider to on), block)

/** Runs [block] with no stored plan-step switch for any provider, as after a fresh install, and restores each earlier entry exactly after it. */
internal inline fun <T> withNoStoredPlanStep(context: Context, block: () -> T): T =
    withStoredPlanSteps(context, AiProvider.entries.associateWith { null }, block)

/** Stores the switch of each provider of [entries] for [block] (null: no entry), and restores each earlier entry exactly after it, an absent one too. */
internal inline fun <T> withStoredPlanSteps(context: Context, entries: Map<AiProvider, Boolean?>, block: () -> T): T {
    // The store of GenerationSettings (tools/agent). With no entry, planStep gives the default of the provider, so a restore keeps an absent entry absent.
    val prefs = context.getSharedPreferences("alpha_generation", Context.MODE_PRIVATE)
    val next = entries.mapKeys { "plan_step_${it.key.id}" }
    val before = next.mapValues { prefs.all[it.key] as Boolean? }
    storePlanSteps(prefs, next)
    try {
        return block()
    } finally {
        storePlanSteps(prefs, before)
    }
}

internal fun storePlanSteps(prefs: SharedPreferences, entries: Map<String, Boolean?>) =
    check(prefs.edit().apply { entries.forEach { (key, on) -> if (on == null) remove(key) else putBoolean(key, on) } }.commit())
