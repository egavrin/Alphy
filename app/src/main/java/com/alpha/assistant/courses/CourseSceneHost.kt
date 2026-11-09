package com.alpha.assistant.courses

import android.util.Log
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.sandbox.GraphicsSession
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.tools.ArtifactLiveInputQueue
import com.alpha.assistant.tools.ArtifactPlayback
import com.alpha.assistant.tools.ArtifactProtocol
import com.alpha.assistant.tools.ArtifactSnapshot
import com.alpha.assistant.tools.GraphicsContract
import com.alpha.assistant.tools.LiveArtifactUiHost
import com.alpha.assistant.tools.LiveInputKey
import com.alpha.assistant.tools.LiveInputLease
import com.alpha.assistant.tools.LiveInputRejection
import com.alpha.assistant.tools.LiveInputResult
import com.alpha.assistant.tools.LiveInputSubmission
import com.alpha.assistant.tools.ToolExecutionPolicy
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.liveInputEvent
import com.alpha.assistant.tools.liveInputVersion
import com.alpha.assistant.tools.generation.sameToolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/** The scene uses the same canvas host contract. Its record is an in-memory view of a course slide. */
internal class CourseSceneHost(
    private val generation: ToolGenerationService,
    private val store: CourseStore,
    private val page: CoursePage.Slide,
    private val policy: ToolExecutionPolicy,
) : LiveArtifactUiHost {
    val spec = checkNotNull(page.scene)
    val initial = ToolRecord("course-${page.course.id}-${page.step.slide.id}", 1, 0, spec.json, page.state, page.ui, null, false)
    private val hash = MessageDigest.getInstance("SHA-256").digest(spec.json.toByteArray()).joinToString("") { "%02x".format(it) }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArtifactLiveInputQueue()
    private val fields = settingFields(spec.ui)
    private var session: GraphicsSession? = null
    private var playback = ArtifactPlayback()
    private var viewport = spec.surfaces
    private var settings: CourseStore.SceneSettings? = null
    private var started = false
    @Volatile private var disposed = false
    @Volatile private var failed = false
    @Volatile private var lease = LiveInputLease(0)
    @Volatile private var current = ArtifactSnapshot(initial, 0, controlVersion = 0)

    override suspend fun open(): ArtifactSnapshot = mutex.withLock {
        check(!disposed)
        if (session != null) return@withLock current
        failed = false
        if (settings == null) {
            settings = withContext(Dispatchers.IO) {
                store.sceneSettings(page.course.id, page.step.slide.id, page.sceneRevision, hash)
            }
            val state = JSONObject(initial.state)
            val values = JSONObject(checkNotNull(settings).values)
            for (field in fields) if (values.has(field)) {
                val path = field.split('.')
                val owner = path.dropLast(1).fold(state) { value, key -> value.getJSONObject(key) }
                owner.put(path.last(), values.get(field))
            }
            spec.validateState(state.toString())
            current = current.copy(tool = initial.copy(state = state.toString()))
        }
        lease = LiveInputLease(lease.generation + 1)
        playback = ArtifactPlayback()
        viewport = spec.surfaces
        started = false
        current = current.copy(surfaces = JSONObject(), frames = emptySet(), timers = emptyMap(),
            sessionGeneration = lease.generation, viewport = null)
        policy.validateDefinition(spec.json)
        generation.retainedSession().also { session = it }
        execute(JSONObject().put("type", "open"), opening = true)
    }

    override suspend fun startSession(): ArtifactSnapshot = mutex.withLock {
        if (!started && playback.automaticStart) {
            started = true
            for (id in spec.surfaces.keys) execute(GraphicsContract.event("start", surfaceId = id))
        }
        current
    }

    override suspend fun event(event: JSONObject): ArtifactSnapshot = mutex.withLock { execute(event) }
    override suspend fun snapshot(): ArtifactSnapshot {
        check(!failed) { "The lesson scene stopped." }
        return current
    }
    override suspend fun flush() = current

    private suspend fun execute(event: JSONObject, opening: Boolean = false, active: () -> Boolean = { true }): ArtifactSnapshot {
        val runtime = checkNotNull(session)
        val old = current
        val type = event.getString("type")
        val input = type == "action" || type == SandboxContract.LIVE_INPUT_EVENT
        val settingEvent = input || type == "pointer" && event.optString("phase") in setOf("down", "move", "up")
        var stage = "validate"
        try {
            check(!disposed && active())
            if (!opening && !input) {
                GraphicsContract.validateSurfaceEvent(event, viewport)
                if (type == "frame") check(event.getString("surfaceId") in old.frames)
                if (type == "timer") check(event.getString("timerId") in old.timers)
            }
            stage = "evaluate"
            val output = generation.executeEvent(spec, initial.id, initial.revision, old.tool.state, event, old.tool.ui, policy) { source, request ->
                if (opening) runtime.open(source, request, waitForRetainedRelease = true) { !disposed }
                else runtime.dispatch(request)
            }
            val surfaces = JSONObject(old.surfaces.toString())
            output.surfaces?.let { changes -> changes.keys().forEach { surfaces.put(it, changes.get(it)) } }
            ArtifactProtocol.validateSurfaces(surfaces)
            val state = JSONObject(output.state)
            val prior = JSONObject(old.tool.state)
            val changed = fields.any { !sameToolJson(ToolUiCatalog.value(state, it), ToolUiCatalog.value(prior, it)) }
            check(settingEvent || !changed) { "Autonomous events cannot change lesson settings." }
            currentCoroutineContext().ensureActive()
            check(!disposed && active())
            if (settingEvent && changed) {
                stage = "persist"
                val values = JSONObject()
                for (field in fields) values.put(field, ToolUiCatalog.value(state, field))
                settings = withContext(Dispatchers.IO) {
                    check(!disposed && active())
                    store.saveSceneSettings(page.course.id, page.course.revision, page.step.slide.id, page.sceneRevision,
                        spec.json, hash, checkNotNull(settings).version, values.toString())
                }
            }
            if (type == "viewport") {
                val id = event.getString("surfaceId")
                viewport = viewport + (id to viewport.getValue(id).copy(width = event.getInt("width"), height = event.getInt("height")))
            }
            playback.update(output.playback, type)
            val changedControl = type != "frame" && type != "timer" || ToolUiCatalog.liveInputContractChanged(old.tool.ui, output.ui)
            val control = checkNotNull(old.controlVersion) + if (changedControl) 1 else 0
            return old.copy(tool = old.tool.copy(state = output.state, ui = output.ui, stateVersion = control),
                sequence = old.sequence + 1, surfaces = surfaces, frames = output.frames ?: old.frames,
                timers = output.timers ?: old.timers, playback = playback.value, controlVersion = control,
                viewport = viewport.values.singleOrNull()?.takeIf { it.sizing == "viewport" }).also { current = it }
        } catch (failure: Exception) {
            if (!disposed && failure !is CancellationException) {
                val reason = (failure as? CloudFailure)?.code ?: failure.javaClass.simpleName
                Log.w("AlphyCourseScene", "event=$type stage=$stage failure=$reason")
            }
            runtime.close()
            failed = true
            session = null
            settings = null
            cancelLiveInput()
            throw failure
        }
    }

    override suspend fun offerLiveInput(expected: ArtifactSnapshot, actionId: String, value: Any?, isActive: () -> Boolean) =
        offer(expected, actionId, value, false, isActive)

    override suspend fun finishLiveInput(expected: ArtifactSnapshot, actionId: String, value: Any?, isActive: () -> Boolean) =
        offer(expected, actionId, value, true, isActive)

    private fun offer(expected: ArtifactSnapshot, actionId: String, value: Any?, finished: Boolean, active: () -> Boolean): LiveInputSubmission {
        val lease = lease
        val shown = current
        if (disposed || !active() || expected.sessionGeneration != lease.generation) {
            return LiveInputSubmission.Rejected(LiveInputRejection.Inactive)
        }
        val event = liveInputEvent(actionId, value)
        return queue.submit(LiveInputKey(lease, actionId, null), event.toString(),
            expected.liveInputVersion, shown.liveInputVersion, finished, active)
    }

    override fun liveInputFrame(frameTimeNanos: Long) {
        val delivery = queue.takeFrame(frameTimeNanos) ?: return
        scope.launch {
            try {
                mutex.withLock {
                    if (delivery.key.lease !== lease || queue.expected(delivery) != current.liveInputVersion || !delivery.active()) {
                        if (delivery.finished) Log.w("AlphyCourseScene", "Final input no longer matches the active control")
                        queue.complete(delivery, LiveInputResult.Rejected(LiveInputRejection.Conflict))
                    } else {
                        val result = execute(JSONObject(delivery.event)) { delivery.active() && queue.expected(delivery) != null }
                        queue.complete(delivery, LiveInputResult.Accepted(result))
                    }
                }
            } catch (cancel: CancellationException) {
                queue.complete(delivery, LiveInputResult.Rejected(LiveInputRejection.Inactive))
                throw cancel
            } catch (_: Exception) {
                queue.complete(delivery, LiveInputResult.Rejected(LiveInputRejection.Failed))
            }
        }
    }

    override fun cancelLiveInput() = queue.cancel(lease)

    override suspend fun close() = mutex.withLock {
        cancelLiveInput()
        session?.close()
        session = null
        settings = null
    }

    fun dispose() {
        disposed = true
        cancelLiveInput()
        scope.coroutineContext.cancelChildren()
    }

    private fun settingFields(node: JSONObject): Set<String> = buildSet {
        if (node.has("action") && node.has("bind")) add(node.getString("bind"))
        node.optJSONArray("children")?.let { children ->
            repeat(children.length()) { addAll(settingFields(children.getJSONObject(it))) }
        }
    }
}
