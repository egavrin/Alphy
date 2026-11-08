package com.alpha.assistant.agent

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.alpha.assistant.R
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

/** Owns only user-reviewed phone actions. Generated code and scheduled work cannot enter this ledger. */
internal class AgentRuntime(
    private val context: Context,
    name: String,
    memory: MemoryStore,
    tools: ToolStore,
) : Closeable {
    private val actions = AgentActions(context, name, memory, tools)
    private val journal = Journal(context, name)

    suspend fun prepare(conversation: String, instruction: String, raw: String, replacing: AgentPlan? = null): AgentPlan {
        val commands = AgentCommand.parse(raw)
        val plan = resolved(conversation, instruction, commands, replacing)
        if (replacing == null) journal.create(plan) else check(journal.replace(replacing, plan))
        return plan
    }

    suspend fun refreshPermissions(plan: AgentPlan): AgentPlan {
        require(plan.phase == AgentPhase.NEEDS_PERMISSION)
        val next = resolved(plan.conversationId, plan.instruction, plan.steps.map(AgentStep::command), plan)
        check(journal.replace(plan, next))
        return next
    }

    private suspend fun resolved(conversation: String, instruction: String, commands: List<AgentCommand>, replacing: AgentPlan?): AgentPlan {
        val permissions = commands.flatMap(actions::permissions).distinct().filterNot(actions::hasPermission)
        val steps = commands.map { command ->
            val options = if (permissions.isEmpty()) {
                try { actions.resolve(command, conversation) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyList() }
            } else emptyList()
            AgentStep(command, options, options.indices.filter { options[it].available }.singleOrNull())
        }
        return AgentPlan(replacing?.id ?: UUID.randomUUID().toString(), conversation, instruction,
            (replacing?.revision ?: 0) + 1, steps, permissions, phase(steps, permissions))
    }

    fun select(id: String, revision: Int, index: Int, candidate: Int): AgentPlan {
        val plan = checkNotNull(journal.read(id))
        require(plan.revision == revision && plan.phase in editable && plan.steps[index].options.getOrNull(candidate)?.available == true)
        val steps = plan.steps.mapIndexed { i, step -> if (i == index) step.copy(selected = candidate) else step }
        val next = plan.copy(revision = revision + 1, steps = steps, phase = phase(steps, plan.permissions))
        check(journal.replace(plan, next))
        return next
    }

    fun approve(id: String, revision: Int): AgentPlan? {
        val plan = journal.read(id)?.takeIf { it.revision == revision && it.phase == AgentPhase.REVIEW } ?: return null
        val running = plan.copy(phase = AgentPhase.RUNNING)
        return running.takeIf { journal.replace(plan, it) }
    }

    fun stop(id: String) {
        journal.update(id) { if (it.phase == AgentPhase.RUNNING) it.copy(stopRequested = true) else it }
    }

    fun discard(plan: AgentPlan) {
        if (plan.phase in editable) journal.replace(plan, plan.copy(phase = AgentPhase.STOPPED))
    }

    fun latest(conversation: String) = journal.latest(conversation)
    fun forget(conversation: String) = journal.forget(conversation)

    suspend fun execute(run: AgentPlan, active: suspend () -> Boolean, launch: suspend (Intent) -> Unit,
        onUpdate: suspend (AgentPlan) -> Unit = {}): AgentPlan {
        require(run.phase == AgentPhase.RUNNING)
        var interrupted = false
        try {
            for ((index, step) in run.steps.withIndex()) {
                val current = checkNotNull(journal.read(run.id))
                // The recorded prefix and claim make reentrant execution harmless.
                if (current.phase != AgentPhase.RUNNING || current.revision != run.revision ||
                    current.results.size != index || current.claimed != null) return current
                if (current.stopRequested || !active()) return finish(current.id, AgentPhase.STOPPED)
                val claimed = current.copy(claimed = index)
                if (!journal.replace(current, claimed)) {
                    val latest = checkNotNull(journal.read(run.id))
                    return if (latest.stopRequested && latest.claimed == null) finish(run.id, AgentPhase.STOPPED) else latest
                }
                onUpdate(claimed)
                val target = checkNotNull(step.target)
                suspend fun mayStart() = active() && journal.read(run.id)?.stopRequested == false
                val result = try {
                    if (!mayStart()) {
                        com.alpha.assistant.capabilities.CapabilityResult("cancelled", context.getString(R.string.agent_step_stopped))
                    } else if (target !in actions.resolve(step.command, run.conversationId)) {
                        com.alpha.assistant.capabilities.CapabilityResult("cancelled", context.getString(R.string.agent_target_changed))
                    } else if (!mayStart()) {
                        com.alpha.assistant.capabilities.CapabilityResult("cancelled", context.getString(R.string.agent_step_stopped))
                    } else actions.execute(step.command, target, run.conversationId, ::mayStart, launch)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: SecurityException) {
                    com.alpha.assistant.capabilities.CapabilityResult("denied", context.getString(R.string.capability_permission_required))
                } catch (_: Exception) {
                    com.alpha.assistant.capabilities.CapabilityResult("unknownOutcome", context.getString(R.string.agent_step_unknown))
                }
                val next = journal.update(run.id) {
                    check(it.claimed == index)
                    val phase = when {
                        it.stopRequested -> AgentPhase.STOPPED
                        result.status == "succeeded" && index < run.steps.lastIndex -> AgentPhase.RUNNING
                        result.status in setOf("succeeded", "launched") && index == run.steps.lastIndex -> AgentPhase.COMPLETED
                        result.status == "launched" -> AgentPhase.STOPPED
                        else -> AgentPhase.FAILED
                    }
                    it.copy(claimed = null, phase = phase, results = it.results + result.message)
                }
                onUpdate(next)
                if (next.phase != AgentPhase.RUNNING) return next
            }
            return checkNotNull(journal.read(run.id))
        } catch (cancel: CancellationException) {
            interrupted = true
            throw cancel
        } finally {
            if (interrupted) withContext(NonCancellable) {
                val state = finish(run.id, AgentPhase.INTERRUPTED)
                onUpdate(state)
            }
        }
    }

    private fun finish(id: String, phase: AgentPhase) = journal.update(id) {
        if (it.phase != AgentPhase.RUNNING) it else it.copy(phase = phase, claimed = null,
            results = if (it.claimed == null) it.results else it.results + context.getString(R.string.agent_step_unknown))
    }

    override fun close() = journal.close()

    companion object {
        val editable = setOf(AgentPhase.NEEDS_PERMISSION, AgentPhase.NEEDS_SELECTION, AgentPhase.UNAVAILABLE, AgentPhase.REVIEW)
        private fun phase(steps: List<AgentStep>, permissions: List<String>) = when {
            permissions.isNotEmpty() -> AgentPhase.NEEDS_PERMISSION
            steps.any { step -> step.options.none(AgentTarget::available) } -> AgentPhase.UNAVAILABLE
            steps.any { it.target == null } -> AgentPhase.NEEDS_SELECTION
            else -> AgentPhase.REVIEW
        }
    }

    private class Journal(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE plans (id TEXT PRIMARY KEY, conversation TEXT NOT NULL, process TEXT NOT NULL, body TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unknown agent ledger version")

        fun create(plan: AgentPlan) {
            writableDatabase.insertOrThrow("plans", null, values(plan).apply { put("id", plan.id); put("conversation", plan.conversationId) })
        }

        fun replace(old: AgentPlan, next: AgentPlan) = writableDatabase.update("plans", values(next),
            "id=? AND body=?", arrayOf(old.id, encode(old))) == 1

        fun read(id: String) = query("id=?", id)
        fun latest(conversation: String) = query("conversation=?", conversation)
        fun forget(conversation: String) { writableDatabase.delete("plans", "conversation=?", arrayOf(conversation)) }

        private fun query(where: String, argument: String): AgentPlan? = readableDatabase.query(
            "plans", arrayOf("body", "process"), where, arrayOf(argument), null, null, "rowid DESC", "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val plan = decode(cursor.getString(0))
            if (plan.phase == AgentPhase.RUNNING && cursor.getString(1) != process) {
                val interrupted = plan.copy(phase = AgentPhase.INTERRUPTED)
                replace(plan, interrupted)
                interrupted
            } else plan
        }

        fun update(id: String, operation: (AgentPlan) -> AgentPlan): AgentPlan {
            while (true) {
                val old = checkNotNull(read(id))
                val next = operation(old)
                if (next == old || replace(old, next)) return next
            }
        }

        private fun values(plan: AgentPlan) = ContentValues().apply { put("body", encode(plan)); put("process", process) }

        companion object {
            private val process = UUID.randomUUID().toString()
            private fun encode(plan: AgentPlan) = JSONObject().put("id", plan.id).put("conversation", plan.conversationId)
                .put("instruction", plan.instruction).put("revision", plan.revision).put("phase", plan.phase.name)
                .put("claimed", plan.claimed).put("stop", plan.stopRequested).put("results", JSONArray(plan.results))
                .put("permissions", JSONArray(plan.permissions)).put("steps", JSONArray().apply {
                    plan.steps.forEach { step -> put(step.command.json().put("selected", step.selected)
                        .put("options", JSONArray(step.options.map(AgentTarget::json)))) }
                }).toString()

            private fun decode(raw: String): AgentPlan {
                val json = JSONObject(raw)
                val steps = json.getJSONArray("steps").items { index ->
                    val step = getJSONObject(index)
                    val command = AgentCommand(step.getString("function"), step.getJSONObject("args").toString())
                    val options = step.getJSONArray("options").items { option ->
                        val value = getJSONArray(option)
                        AgentTarget(value.getString(0), value.getString(1), value.getString(2), value.getString(3), value.optBoolean(4, true))
                    }
                    AgentStep(command, options, step.optionalIndex("selected"))
                }
                val permissions = json.getJSONArray("permissions").items { getString(it) }
                val results = json.getJSONArray("results").items { getString(it) }
                return AgentPlan(json.getString("id"), json.getString("conversation"), json.getString("instruction"),
                    json.getInt("revision"), steps, permissions, AgentPhase.valueOf(json.getString("phase")),
                    json.optionalIndex("claimed"), results, json.getBoolean("stop"))
            }

            private fun JSONObject.optionalIndex(key: String) = if (has(key)) getInt(key) else null
            private inline fun <T> JSONArray.items(read: JSONArray.(Int) -> T) = List(length()) { read(it) }
        }
    }
}
