package com.alpha.assistant.tools.three

import android.graphics.Outline
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.alpha.assistant.R
import com.alpha.assistant.tools.ImmersiveSystemBars
import com.alpha.assistant.tools.InlineToolViewportCornerRadius
import com.alpha.assistant.tools.ToolContent
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolPlaybackControls
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStageLayout
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

@Composable
internal fun ThreeScenePane(
    controller: ToolController,
    tool: ToolRecord,
    spec: ToolSpec,
    inlineHeight: Dp,
    availableHeight: Dp?,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val inlineCornerRadius = with(LocalDensity.current) { InlineToolViewportCornerRadius.toPx() }
    val inlineOutline = remember(inlineCornerRadius) {
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, inlineCornerRadius)
            }
        }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = enabled && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var session by remember(tool.id, tool.revision) { mutableStateOf<ThreeSceneSession?>(null) }
    var snapshot by remember(tool.id, tool.revision) { mutableStateOf(ThreeSceneSnapshot(tool.state, tool.ui)) }
    var error by remember(tool.id, tool.revision) { mutableStateOf<String?>(null) }
    var paused by rememberSaveable(tool.id, tool.revision) { mutableStateOf(false) }
    var retry by remember(tool.id, tool.revision) { mutableStateOf(0) }
    var fullscreen by remember(visible, session) { mutableStateOf(false) }
    val events = remember(tool.id, tool.revision, visible, retry) { Channel<JSONObject>(32) }
    val moves = remember(events) { linkedMapOf<Int, JSONObject>() }
    val pointers = remember(events) { linkedMapOf<Int, JSONObject>() }
    var viewportPending by remember(events) { mutableStateOf(false) }

    LaunchedEffect(controller, tool.id, tool.revision, visible, retry) {
        if (!visible) return@LaunchedEffect
        controller.threeSceneLifetime.withLock {
            error = null
            var current: ToolRecord
            val active = try {
                current = controller.loadThreeScene(tool)
                snapshot = ThreeSceneSnapshot(current.state, current.ui)
                ThreeSceneSession(context, spec, current.id, current.revision, current.state, current.ui, awaitAudio = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: "The 3D scene is unavailable."
                return@LaunchedEffect
            }
            session = active
            fun enqueue(input: JSONObject) {
                if (events.trySend(input).isFailure) error = "The scene input queue is full."
            }
            fun flushMoves() {
                moves.values.forEach(::enqueue)
                moves.clear()
            }
            active.web.view.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                if (viewportPending) return@setOnTouchListener true
                if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                for (input in active.pointerEvents(event)) {
                    val id = input.getInt("pointerId")
                    when (input.getString("type")) {
                        "pointerdown" -> {
                            flushMoves()
                            pointers[id] = input
                            enqueue(input)
                        }
                        "pointermove" -> if (id in pointers) {
                            pointers[id] = input
                            moves[id] = input
                        }
                        else -> if (pointers.remove(id) != null) {
                            flushMoves()
                            enqueue(input)
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
                        if (!active.web.view.isAttachedToWindow || active.web.view.width == 0 || active.web.view.height == 0) {
                            delay(16)
                            continue
                        }
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
                        withContext(NonCancellable) { current = controller.saveThreeScene(current, snapshot) }
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
                        runCatching { controller.saveThreeScene(current, active.snapshot) }
                    }
                }
            }
        }
    }

    val playback: @Composable () -> Unit = {
        ToolPlaybackControls(if (paused) "resume" else "pause") { event ->
            events.trySend(JSONObject().put("type", event))
        }
    }
    val wideControls = remember(snapshot.ui) { ToolUiCatalog.isSceneChoice(JSONObject(snapshot.ui)) }
    val sceneControls: @Composable (String?) -> Unit = { heading ->
        val width = if (wideControls) Modifier else Modifier.widthIn(max = 640.dp)
        Box(width.fillMaxWidth()) {
            ToolContent(snapshot.ui, snapshot.state, session != null && visible, heading = heading, embedded = true,
                appLanguage = spec.appLanguage) { id, value ->
                events.trySend(JSONObject().put("type", "action").put("actionId", id).put("value", value ?: JSONObject.NULL))
            }
        }
    }
    val active = session
    if (active != null) key(active) {
        val view = active.web.view
        val inline = remember { FrameLayout(context) }
        ToolStageLayout(availableHeight, stage = {
            Box(Modifier.fillMaxWidth().then(if (availableHeight == null) Modifier.height(inlineHeight) else Modifier.fillMaxSize())) {
                AndroidView(factory = {
                    inline.apply {
                        clipToOutline = true
                        attachSceneView(view)
                    }
                }, modifier = Modifier.fillMaxSize(), update = { container ->
                    if (container.outlineProvider !== inlineOutline) container.outlineProvider = inlineOutline
                })
                if (!fullscreen) SceneOverlay(paused, expanded = false, onFullscreen = { fullscreen = true })
            }
        }, controls = {
            sceneControls(spec.title)
            playback()
        })
        if (fullscreen) {
            Dialog(onDismissRequest = { fullscreen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                ImmersiveSystemBars(enabled = true)
                val expanded = remember { FrameLayout(context) }
                DisposableEffect(expanded) {
                    onDispose {
                        if (view.parent === expanded) {
                            expanded.removeView(view)
                            if (session === active && inline.isAttachedToWindow) {
                                viewportPending = true
                                inline.attachSceneView(view)
                            }
                        }
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val controlHeight = minOf(180.dp, maxHeight / 3)
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                AndroidView(factory = {
                                    viewportPending = true
                                    expanded.apply { attachSceneView(view) }
                                }, modifier = Modifier.fillMaxSize())
                                SceneOverlay(paused, expanded = true, onFullscreen = { fullscreen = false })
                            }
                            Column(Modifier.fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                                .padding(horizontal = 12.dp)) {
                                Column(Modifier.fillMaxWidth().heightIn(max = controlHeight)
                                    .verticalScroll(rememberScrollState())) {
                                    sceneControls(null)
                                }
                                playback()
                            }
                        }
                    }
                }
            }
        }
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visible && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            Text(message)
            TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
        }
        sceneControls(spec.title)
    }
}

@Composable
private fun BoxScope.SceneOverlay(paused: Boolean, expanded: Boolean, onFullscreen: () -> Unit) {
    val insets = if (expanded) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier
    val colors = MaterialTheme.colorScheme
    if (paused) Surface(Modifier.align(Alignment.TopStart).then(insets).padding(8.dp),
        color = colors.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
        Text(stringResource(R.string.tool_scene_paused), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge)
    }
    val fullscreenLabel = stringResource(if (expanded) R.string.tool_full_screen_exit else R.string.tool_full_screen_enter)
    Surface(Modifier.align(Alignment.TopEnd).then(insets).padding(8.dp),
        color = colors.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
        IconButton(onFullscreen, Modifier.size(48.dp).semantics { contentDescription = fullscreenLabel }) {
            AlphaIcon(if (expanded) Glyph.Close else Glyph.Fullscreen)
        }
    }
}

private fun FrameLayout.attachSceneView(view: View) {
    (view.parent as? ViewGroup)?.removeView(view)
    addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}
