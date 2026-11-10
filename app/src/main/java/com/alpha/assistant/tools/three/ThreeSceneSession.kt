package com.alpha.assistant.tools.three

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import com.alpha.assistant.media.DeviceMediaPlayback
import com.alpha.assistant.media.MediaPlaybackResult
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.generation.sameToolJson
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object ThreeSceneForeground {
    private var current = WeakReference<Activity>(null)

    fun resumed(activity: Activity) { current = WeakReference(activity) }
    fun paused(activity: Activity) { if (current.get() === activity) current.clear() }
    fun activeActivity(): Activity? = current.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    fun activity(): Activity = activeActivity() ?: throw ThreeSceneFailure(ThreeSceneProblem.FOREGROUND)
}

internal data class ThreeSceneSnapshot(val state: String, val ui: String)

internal class ThreeSceneSession(
    context: Context,
    private val spec: ToolSpec,
    artifactId: String,
    revision: Int,
    initialState: String,
    initialUi: String,
    private val awaitAudio: Boolean = true,
) {
    private val assets = ThreeSceneAssets(context)
    private val assetLease = ThreeSceneAssetCleanup(context.applicationContext).lease()
    private val audioIds: Set<String>
    val web: ThreeSceneWebView

    init {
        try {
            val workerAssets = assets.workerAssets(spec.sceneAssets)
            audioIds = workerAssets.filter { it.mediaType.startsWith("audio/") }.map { it.id }.toSet()
            web = ThreeSceneWebView(context, artifactId, revision, spec.source, workerAssets, spec.generatedImages)
        } catch (failure: Throwable) {
            assetLease.close()
            throw failure
        }
    }

    private val media = DeviceMediaPlayback()
    private val audioJob = SupervisorJob()
    private val audioScope = CoroutineScope(audioJob + Dispatchers.Main.immediate)
    private var pendingAudio: Deferred<Pair<String, String>>? = null
    private var closed = false
    private var started = false
    private var elapsedMs = 0L
    private var foreground = true
    var snapshot = ThreeSceneSnapshot(initialState, initialUi)
        private set
    var lastAudio: String? = null
        private set

    suspend fun start() {
        val expected = spec.validateState(snapshot.state)
        val initialized = startupSnapshot(web.start(expected), expected, snapshot.ui, ThreeSceneOperation.INIT)
        val opened = startupSnapshot(web.event(JSONObject().put("type", "open")), expected,
            initialized.ui, ThreeSceneOperation.OPEN)
        snapshot = opened.copy(state = expected.toString())
        started = true
    }

    suspend fun event(event: JSONObject) {
        val type = event.getString("type")
        if (type == "pause") {
            foreground = false
            pendingAudio?.cancel()
            pendingAudio = null
        }
        drainAudio()
        val audioAllowed = type == "pointerup" || type == "action" || type == "liveInput"
        if (audioAllowed) lastAudio = null
        if (type in setOf("resume", "start", "restart")) foreground = true
        if (type == "restart") elapsedMs = 0
        accept(web.event(event), audioAllowed)
    }

    suspend fun action(id: String, value: Any?) {
        val ui = JSONObject(snapshot.ui)
        val state = JSONObject(snapshot.state)
        val node = ToolUiCatalog.action(ui, id) ?: throw ThreeSceneFailure(ThreeSceneProblem.INVALID_BINDING)
        val type = if (ToolUiCatalog.isLiveInput(node)) {
            ToolUiCatalog.validateLiveInput(ui, id, value, state)
            "liveInput"
        } else {
            ToolUiCatalog.validateAction(ui, id, value, state)
            "action"
        }
        event(JSONObject().put("type", type).put("actionId", id).put("value", value ?: JSONObject.NULL))
    }

    suspend fun frame(dtMs: Int) {
        drainAudio()
        elapsedMs += dtMs
        accept(web.frame(elapsedMs, dtMs), audioAllowed = false)
    }

    suspend fun viewport() = event(web.viewport().put("type", "viewport"))

    fun pointer(type: String, id: Int, x: Double, y: Double): JSONObject {
        val dimensions = web.viewport()
        val cx = x.coerceIn(0.0, 1.0) * dimensions.getInt("width")
        val cy = y.coerceIn(0.0, 1.0) * dimensions.getInt("height")
        return JSONObject().put("type", type).put("pointerId", id).put("pointerType", "touch")
            .put("clientX", cx).put("clientY", cy).put("pageX", cx).put("pageY", cy)
            .put("x", x.coerceIn(0.0, 1.0)).put("y", y.coerceIn(0.0, 1.0))
            .put("button", 0).put("buttons", if (type in setOf("pointerup", "pointercancel")) 0 else 1)
    }

    suspend fun finish() {
        try {
            if (started && !closed) accept(web.dispose(), audioAllowed = false)
        } finally {
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            audioJob.cancel()
            try { media.close() } finally { web.close() }
        } finally {
            audioJob.invokeOnCompletion { assetLease.close() }
        }
    }

    private fun validatedSnapshot(result: JSONObject, fallbackUi: String): ThreeSceneSnapshot = try {
        require(result.keys().asSequence().all { it in setOf("state", "ui", "audio") })
        val state = spec.validateState(result.getJSONObject("state").toString())
        val ui = if (result.has("ui")) result.getJSONObject("ui") else JSONObject(fallbackUi)
        spec.validateUi(ui, state)
        ThreeSceneSnapshot(state.toString(), ui.toString())
    } catch (_: Exception) {
        throw ThreeSceneFailure(ThreeSceneProblem.INVALID_SNAPSHOT)
    }

    private fun startupSnapshot(result: JSONObject, expected: JSONObject, fallbackUi: String,
        operation: ThreeSceneOperation): ThreeSceneSnapshot {
        val checked = try {
            validatedSnapshot(result, fallbackUi)
        } catch (failure: ThreeSceneFailure) {
            throw ThreeSceneFailure(failure.problem, operation)
        }
        if (result.has("audio")) throw ThreeSceneFailure(ThreeSceneProblem.INVALID_AUDIO, operation)
        if (!sameToolJson(expected, JSONObject(checked.state)))
            throw ThreeSceneFailure(ThreeSceneProblem.STARTUP_MUTATION, operation)
        return checked
    }

    private suspend fun accept(result: JSONObject, audioAllowed: Boolean) {
        val checked = validatedSnapshot(result, snapshot.ui)
        val audio = if (result.has("audio")) {
            if (!audioAllowed || !foreground) throw ThreeSceneFailure(ThreeSceneProblem.INVALID_AUDIO)
            val id = result.get("audio") as? String
            if (id == null || id !in audioIds) throw ThreeSceneFailure(ThreeSceneProblem.INVALID_AUDIO)
            id
        } else null
        snapshot = checked
        if (audio == null) return
        if (pendingAudio != null) {
            deliverAudio(audio, MediaPlaybackResult.BUSY.name.lowercase())
            return
        }
        pendingAudio = audioScope.async { audio to play(audio) }
        if (awaitAudio) drainAudio(wait = true)
    }

    private suspend fun drainAudio(wait: Boolean = false) {
        val pending = pendingAudio ?: return
        if (!wait && !pending.isCompleted) return
        pendingAudio = null
        val (id, status) = pending.await()
        deliverAudio(id, status)
    }

    private suspend fun play(id: String): String = try {
        withContext(Dispatchers.IO) {
            assets.audio(spec.sceneAssets, id).use { descriptor ->
                withContext(Dispatchers.Main.immediate) {
                    media.playAudio(descriptor) { start ->
                        if (closed || !foreground || !web.view.isShown) false
                        else {
                            start()
                            true
                        }
                    }.name.lowercase()
                }
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        MediaPlaybackResult.PLAYBACK_FAILED.name.lowercase()
    }

    private suspend fun deliverAudio(id: String, status: String) {
        lastAudio = status
        accept(web.event(JSONObject().put("type", "audioResult").put("id", id)
            .put("status", status)), audioAllowed = false)
    }
}

internal fun ThreeSceneSession.pointerEvents(event: MotionEvent): List<JSONObject> {
    val width = web.view.width.coerceAtLeast(1)
    val height = web.view.height.coerceAtLeast(1)
    val type = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> "pointerdown"
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> "pointerup"
        MotionEvent.ACTION_MOVE -> "pointermove"
        MotionEvent.ACTION_CANCEL -> "pointercancel"
        else -> return emptyList()
    }
    val indices = if (type in setOf("pointermove", "pointercancel")) 0 until event.pointerCount
        else event.actionIndex..event.actionIndex
    return indices.map { index ->
        pointer(type, event.getPointerId(index), event.getX(index).toDouble() / width, event.getY(index).toDouble() / height)
    }
}
