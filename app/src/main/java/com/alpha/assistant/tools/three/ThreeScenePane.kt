package com.alpha.assistant.tools.three

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.alpha.assistant.tools.ToolContent
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

@Composable
internal fun ThreeScenePane(
    tool: ToolRecord,
    spec: ToolSpec,
    enabled: Boolean,
    save: suspend (ToolRecord, ThreeSceneSnapshot) -> ToolRecord,
) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = enabled && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var current by remember(tool.id, tool.revision) { mutableStateOf(tool) }
    var session by remember(tool.id, tool.revision) { mutableStateOf<ThreeSceneSession?>(null) }
    var snapshot by remember(tool.id, tool.revision) { mutableStateOf(ThreeSceneSnapshot(tool.state, tool.ui)) }
    var error by remember(tool.id, tool.revision) { mutableStateOf<String?>(null) }
    var paused by remember(tool.id, tool.revision) { mutableStateOf(false) }
    var retry by remember(tool.id, tool.revision) { mutableStateOf(0) }
    val events = remember(tool.id, tool.revision, visible, retry) { Channel<JSONObject>(32) }
    val lifetime = remember(tool.id, tool.revision) { Mutex() }
    val moves = remember(events) { linkedMapOf<Int, JSONObject>() }
    val pointers = remember(events) { linkedMapOf<Int, JSONObject>() }
    var viewportPending by remember(events) { mutableStateOf(false) }

    LaunchedEffect(tool.id, tool.revision, visible, retry) {
        if (!visible) return@LaunchedEffect
        lifetime.withLock {
            error = null
            val active = try {
                ThreeSceneSession(context, spec, current.id, current.revision, current.state, current.ui, awaitAudio = false)
            } catch (failure: Exception) {
                error = failure.message ?: "The 3D scene is unavailable."
                return@LaunchedEffect
            }
            session = active
            active.web.view.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                for (input in active.pointerEvents(event)) {
                    val id = input.getInt("pointerId")
                    when (input.getString("type")) {
                        "pointerdown" -> {
                            pointers[id] = input
                            if (events.trySend(input).isFailure) error = "The scene input queue is full."
                        }
                        "pointermove" -> if (id in pointers) {
                            pointers[id] = input
                            moves[id] = input
                        }
                        else -> if (pointers.remove(id) != null) {
                            moves.remove(id)?.let { events.trySend(it) }
                            if (events.trySend(input).isFailure) error = "The scene input queue is full."
                        }
                    }
                }
                true
            }
            active.web.view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) viewportPending = true
            }
            try {
                while (active.web.view.width == 0 || active.web.view.height == 0) delay(16)
                active.start()
                if (paused) active.event(JSONObject().put("type", "pause"))
                var lastFrame = SystemClock.uptimeMillis()
                var lastSave = lastFrame
                while (error == null) {
                    val next = events.tryReceive().getOrNull()
                    if (next != null) {
                        when (val type = next.getString("type")) {
                            "action" -> active.action(next.getString("actionId"), next.opt("value").takeUnless { it == JSONObject.NULL })
                            else -> {
                                active.event(next)
                                if (type == "pause") paused = true
                                if (type == "resume" || type == "restart") paused = false
                            }
                        }
                    } else if (viewportPending) {
                        viewportPending = false
                        for (pointer in pointers.values) active.event(JSONObject(pointer.toString()).put("type", "pointercancel"))
                        pointers.clear()
                        moves.clear()
                        active.viewport()
                    } else if (moves.isNotEmpty()) {
                        val id = moves.keys.first()
                        active.event(checkNotNull(moves.remove(id)))
                    } else {
                        delay(16)
                        val now = SystemClock.uptimeMillis()
                        if (!paused) active.frame((now - lastFrame).coerceIn(1, 50).toInt())
                        lastFrame = now
                    }
                    if (snapshot != active.snapshot) snapshot = active.snapshot
                    val now = SystemClock.uptimeMillis()
                    if ((now - lastSave >= 1000 || next != null) &&
                        (current.state != snapshot.state || current.ui != snapshot.ui)) {
                        withContext(NonCancellable) { current = save(current, snapshot) }
                        lastSave = now
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: "The 3D scene stopped."
            } finally {
                if (session === active) session = null
                withContext(NonCancellable) {
                    runCatching { active.finish() }
                    if (current.state != active.snapshot.state || current.ui != active.snapshot.ui) {
                        runCatching { save(current, active.snapshot) }.onSuccess { current = it }
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val active = session
        if (active != null) {
            key(active) {
                AndroidView(factory = { active.web.view }, modifier = Modifier.fillMaxWidth().height(320.dp))
            }
            Row {
                TextButton(onClick = { events.trySend(JSONObject().put("type", if (paused) "resume" else "pause")) }) {
                    Text(if (paused) "Resume" else "Pause")
                }
                TextButton(onClick = { events.trySend(JSONObject().put("type", "restart")) }) { Text("Restart") }
            }
        } else if (visible && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            Text(message)
            TextButton(onClick = { retry++ }) { Text("Retry") }
        }
        ToolContent(snapshot.ui, snapshot.state, active != null && visible, embedded = true, appLanguage = spec.appLanguage) { id, value ->
            events.trySend(JSONObject().put("type", "action").put("actionId", id).put("value", value ?: JSONObject.NULL))
        }
    }
}
