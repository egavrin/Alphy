package com.alpha.assistant.agent

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.capabilities.PHOTO_CAPTURE
import com.alpha.assistant.capabilities.PhotoLens
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
    private val photos = AgentPhotos(context, name)
    private var recoveredPhotos = false

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

    fun approve(id: String, revision: Int, continueAfterClock: Boolean = false): AgentPlan? {
        val plan = journal.read(id)?.takeIf { it.revision == revision && !it.stopRequested && it.claimed == null &&
            if (continueAfterClock) it.phase == AgentPhase.WAITING_FOR_CAMERA && it.results.size == 1 && it.timerThenPhoto()
            else it.phase == AgentPhase.REVIEW && it.results.isEmpty()
        } ?: return null
        val running = plan.copy(phase = AgentPhase.RUNNING)
        return running.takeIf { journal.replace(plan, it) }
    }

    fun stop(id: String) {
        journal.update(id) {
            when (it.phase) {
                AgentPhase.RUNNING -> it.copy(stopRequested = true)
                AgentPhase.WAITING_FOR_CAMERA -> it.copy(phase = AgentPhase.STOPPED, stopRequested = true)
                else -> it
            }
        }
    }

    fun discard(plan: AgentPlan) {
        if (plan.phase in editable || plan.phase == AgentPhase.WAITING_FOR_CAMERA)
            journal.replace(plan, plan.copy(phase = AgentPhase.STOPPED))
    }

    fun latest(conversation: String) = journal.latest(conversation)
    fun forget(conversation: String) = synchronized(photoLock) {
        val owned = journal.photos(conversation)
        journal.forget(conversation)
        owned.forEach { photos.file(it.planId, it.step).delete() }
    }

    fun keptPhotos(conversation: String): List<AgentPhoto> = synchronized(photoLock) {
        if (!recoveredPhotos) {
            photos.prune(journal.photos(null).map { photos.file(it.planId, it.step).name }.toSet())
            recoveredPhotos = true
        }
        journal.photos(conversation)
    }

    fun photoFile(photo: AgentPhoto) = photos.file(photo.planId, photo.step)

    fun deletePhoto(conversation: String, photo: AgentPhoto) = synchronized(photoLock) {
        val plan = journal.read(photo.planId)?.takeIf { it.conversationId == conversation && photo.step in it.keptPhotos }
            ?: return@synchronized
        journal.update(plan.id) { it.copy(keptPhotos = it.keptPhotos - photo.step) }
        photoFile(photo).delete()
        Unit
    }

    suspend fun execute(run: AgentPlan, active: suspend () -> Boolean, launch: suspend (Intent) -> Unit,
        capture: suspend (PhotoLens) -> Pair<String, Bitmap?> = { "unavailable" to null },
        onUpdate: suspend (AgentPlan) -> Unit = {}): AgentPlan {
        require(run.phase == AgentPhase.RUNNING)
        var interrupted = false
        try {
            for (index in run.results.size until run.steps.size) {
                val step = run.steps[index]
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
                var photo: Bitmap? = null
                val result = try {
                    if (!mayStart()) {
                        com.alpha.assistant.capabilities.CapabilityResult("cancelled", context.getString(R.string.agent_step_stopped))
                    } else if (target !in actions.resolve(step.command, run.conversationId)) {
                        com.alpha.assistant.capabilities.CapabilityResult("cancelled", context.getString(R.string.agent_target_changed))
                    } else if (!mayStart()) {
                        com.alpha.assistant.capabilities.CapabilityResult("cancelled", context.getString(R.string.agent_step_stopped))
                    } else if (step.command.function == PHOTO_CAPTURE) {
                        val (status, bitmap) = capture(PhotoLens.requested(step.command.args()))
                        photo = bitmap
                        val outcome = when {
                            status != "succeeded" -> status
                            bitmap == null -> "failed"
                            !actions.hasPermission(android.Manifest.permission.CAMERA) -> "denied"
                            !mayStart() -> "cancelled"
                            else -> status
                        }
                        CapabilityResult(outcome, context.getString(when (outcome) {
                            "succeeded" -> R.string.agent_photo_kept
                            "cancelled" -> R.string.camera_cancelled
                            "denied" -> R.string.capability_permission_required
                            else -> R.string.camera_unavailable
                        }))
                    } else actions.execute(step.command, target, run.conversationId, ::mayStart, launch)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: SecurityException) {
                    com.alpha.assistant.capabilities.CapabilityResult("denied", context.getString(R.string.capability_permission_required))
                } catch (_: Exception) {
                    com.alpha.assistant.capabilities.CapabilityResult("unknownOutcome", context.getString(R.string.agent_step_unknown))
                }
                val next = recordStep(run, index, result, photo)
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

    private fun recordStep(run: AgentPlan, index: Int, result: CapabilityResult, photo: Bitmap?): AgentPlan = synchronized(photoLock) {
        val file = if (result.status == "succeeded" && photo != null) photos.file(run.id, index) else null
        var kept = false
        try {
            val outcome = if (file == null) result else try {
                photos.save(file, checkNotNull(photo))
                result
            } catch (_: Exception) {
                CapabilityResult("failed", context.getString(R.string.camera_unavailable))
            }
            val recorded = journal.update(run.id) {
                check(it.claimed == index)
                val phase = when {
                    it.stopRequested -> AgentPhase.STOPPED
                    outcome.status == "succeeded" && index < run.steps.lastIndex -> AgentPhase.RUNNING
                    outcome.status in setOf("succeeded", "launched") && index == run.steps.lastIndex -> AgentPhase.COMPLETED
                    outcome.status == "launched" && index == 0 && run.timerThenPhoto() -> AgentPhase.WAITING_FOR_CAMERA
                    outcome.status == "launched" || (outcome.status == "cancelled" && run.steps[index].command.function == PHOTO_CAPTURE) -> AgentPhase.STOPPED
                    else -> AgentPhase.FAILED
                }
                val keep = file != null && outcome.status == "succeeded" && !it.stopRequested
                val message = if (file != null && it.stopRequested) context.getString(R.string.camera_cancelled) else outcome.message
                it.copy(claimed = null, phase = phase, results = it.results + message,
                    keptPhotos = if (keep) it.keptPhotos + index else it.keptPhotos)
            }
            kept = index in recorded.keptPhotos
            recorded
        } finally {
            if (!kept && file != null) file.delete()
        }
    }

    private fun finish(id: String, phase: AgentPhase) = journal.update(id) {
        if (it.phase != AgentPhase.RUNNING) it else it.copy(phase = phase, claimed = null,
            results = if (it.claimed == null) it.results else it.results + context.getString(R.string.agent_step_unknown))
    }

    override fun close() = journal.close()

    companion object {
        private val photoLock = Any()
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

        fun photos(conversation: String?): List<AgentPhoto> = readableDatabase.query(
            "plans", arrayOf("body"), conversation?.let { "conversation=?" }, conversation?.let { arrayOf(it) },
            null, null, "rowid DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val plan = decode(cursor.getString(0))
                    for (index in plan.keptPhotos) {
                        add(AgentPhoto(plan.id, index, PhotoLens.requested(plan.steps[index].command.args())))
                    }
                }
            }
        }

        private fun query(where: String, argument: String): AgentPlan? = readableDatabase.query(
            "plans", arrayOf("body", "process"), where, arrayOf(argument), null, null, "rowid DESC", "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val plan = decode(cursor.getString(0))
            if (plan.phase in setOf(AgentPhase.RUNNING, AgentPhase.WAITING_FOR_CAMERA) && cursor.getString(1) != process) {
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
                .apply { if (plan.keptPhotos.isNotEmpty()) put("photos", JSONArray(plan.keptPhotos.sorted())) }
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
                    json.optionalIndex("claimed"), results, json.getBoolean("stop"),
                    json.optJSONArray("photos")?.items { getInt(it) }?.toSet().orEmpty())
            }

            private fun JSONObject.optionalIndex(key: String) = if (has(key)) getInt(key) else null
            private inline fun <T> JSONArray.items(read: JSONArray.(Int) -> T) = List(length()) { read(it) }
        }
    }
}
