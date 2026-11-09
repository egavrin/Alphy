package com.alpha.assistant.tools

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import kotlinx.coroutines.*
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.StatusLine
import com.alpha.assistant.ui.Glyph
import org.json.JSONArray
import org.json.JSONObject

/** Bounded native input queue. All transitions remain ordered. Overflow stops the session. */
internal class SurfaceEventQueue {
    data class Entry(val event: JSONObject,val receivedAtNs: Long)
    private val events=ArrayDeque<Entry>()
    fun add(event: JSONObject) {
        GraphicsContract.validateEvent(event)
        check(events.size<32) {"Input queue exceeded its limit. Restart the mini-app."}
        events.addLast(Entry(event,SystemClock.elapsedRealtimeNanos()))
    }
    fun next()=events.removeFirstOrNull()
    fun clear()=events.clear()
}

internal val LocalToolCanvas = compositionLocalOf<(@Composable (List<String>, String, Modifier, Boolean) -> Unit)?> { null }

private data class DrawingHost(val host: ArtifactUiHost,val generation: Long)

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NativeToolSurface(tool: ToolRecord,spec: ToolSpec,enabled: Boolean,maxCanvasHeight: Dp,host: ArtifactUiHost,immersive: Boolean=false,covered: Boolean=false,onCoverReady: (Boolean)->Unit = {},topControls: @Composable () -> Unit = {},content: @Composable () -> Unit = {},onSnapshot: (ArtifactSnapshot) -> Unit = {}) {
    val context=LocalContext.current
    val view=LocalView.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var attempt by remember(tool.id,tool.revision) {mutableIntStateOf(0)}
    var error by remember(tool.id,tool.revision) {mutableStateOf<Int?>(null)}
    var controlsExpanded by remember(tool.id,tool.revision) {mutableStateOf(false)}
    var snapshot by remember(tool.id,tool.revision) {mutableStateOf<ArtifactSnapshot?>(null)}
    var drawingHost by remember(tool.id,tool.revision,attempt) {mutableStateOf<DrawingHost?>(null)}
    var requestedImages by remember(tool.id,tool.revision,attempt) {mutableStateOf<Set<String>>(emptySet())}
    var trace by remember(tool.id,tool.revision) {mutableStateOf<SurfaceFrameMetrics.Trace?>(null)}
    val density=LocalDensity.current.density
    val viewportSurface=spec.surfaces.values.singleOrNull()?.takeIf {it.sizing=="viewport"}
    var requestedViewport by remember(tool.id,tool.revision) {mutableStateOf<ToolSurface?>(null)}
    var acceptedViewport by remember(tool.id,tool.revision) {mutableStateOf<ToolSurface?>(null)}
    if(immersive) ImmersiveSystemBars(error==null)
    val queue=remember(tool.id,tool.revision,attempt) {SurfaceEventQueue()}
    val effectiveSurfaces=snapshot?.viewport?.let {listOf(it)} ?: spec.surfaces.values.toList()
    val router=remember(tool.id,tool.revision,attempt) {SurfacePointerRouter(spec.surfaces.values.toList())}
    val latestCovered=rememberUpdatedState(covered)
    val latestCoverReady=rememberUpdatedState(onCoverReady)
    val latestHost=rememberUpdatedState(host)
    val latestSnapshot=rememberUpdatedState(onSnapshot)
    fun enqueue(event: JSONObject) {if(enabled && error==null)try {queue.add(event)}catch(_: Exception) {error=R.string.tool_session_input_limit}}
    LaunchedEffect(tool.id,tool.revision,enabled,attempt) {
        if(!enabled)return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val active=latestHost.value
            val metrics=if(spec.surfaces.isNotEmpty())SurfaceFrameMetrics.start(java.util.UUID.randomUUID().toString(),0) else null
            trace=metrics
            queue.clear();router.cancel();error=null;acceptedViewport=null
            fun publish(value: ArtifactSnapshot,touchAtNs: Long?=null) {
                requestedImages=ArtifactProtocol.bitmapHandles(value.surfaces)
                snapshot=value;latestSnapshot.value(value);metrics?.validated(value.sequence,touchAtNs)
            }
            suspend fun deliver(event: JSONObject,touchAtNs: Long?=null): ArtifactSnapshot {
                val begin=SystemClock.elapsedRealtimeNanos()
                val result=active.event(event)
                metrics?.evaluated(SystemClock.elapsedRealtimeNanos()-begin)
                publish(result,touchAtNs)
                return result
            }
            val clock=SurfaceFrameClock()
            val timers=mutableMapOf<String,Pair<Int,Long>>()
            val input=SurfaceInputState()
            var coverActive=false
            var openedDrawingHost: DrawingHost?=null
            try {
                val opened=active.open()
                if(spec.surfaces.isNotEmpty()) {
                    openedDrawingHost=DrawingHost(active,opened.sessionGeneration)
                    drawingHost=openedDrawingHost
                }
                publish(opened)
                if(viewportSurface!=null) {
                    val size=snapshotFlow {requestedViewport}.filterNotNull().first()
                    var ready=false
                    while(!ready) {
                        while(snapshot?.fenced==true) {withFrameNanos {it};publish(active.snapshot())}
                        if(!deliver(GraphicsContract.viewport(size)).fenced) {
                            router.resize(listOf(size));acceptedViewport=size;ready=true
                        }
                    }
                }
                publish(active.startSession())
                while(isActive && error==null) {
                    // Only this coroutine evaluates. The next display tick follows completion, without catch-up.
                    val tick=withFrameNanos {it}
                    val now=SystemClock.elapsedRealtime()
                    val previous=snapshot
                    val current=active.snapshot()
                    publish(current)
                    if(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked || !context.getSystemService(PowerManager::class.java).isInteractive) {
                        error=R.string.tool_session_locked
                        break
                    }
                    if(current.frameOwner && !current.fenced && (previous?.frameOwner!=true || previous.fenced)) {
                        publish(active.startSession())
                    }
                    for(cancel in input.playback(checkNotNull(snapshot).playback))deliver(cancel)
                    if(checkNotNull(snapshot).playback in setOf("paused","ended"))router.cancel()
                    var entry=queue.next()
                    while(entry!=null && error==null) {
                        for(event in input.events(entry.event)) {
                            val type=event.getString("type")
                            if(type=="pause" || type=="restart") {clock.reset();timers.clear()}
                            deliver(event,if(type=="pointer" && entry.event.getString("type")=="pointer")entry.receivedAtNs else null)
                            for(cancel in input.playback(checkNotNull(snapshot).playback))deliver(cancel)
                            if(type in setOf("start","resume","restart")) {clock.reset();timers.clear()}
                        }
                        entry=queue.next()
                    }
                    if(latestCovered.value!=coverActive && snapshot?.fenced==false) {
                        coverActive=latestCovered.value
                        if(coverActive) {
                            for(cancel in input.cancel())deliver(cancel)
                            router.cancel()
                            publish(active.flush())
                        }
                        clock.reset();timers.clear()
                        latestCoverReady.value(coverActive)
                    }
                    if(error!=null)break
                    val resized=requestedViewport
                    if(resized!=null && resized!=acceptedViewport && snapshot?.fenced==false) {
                        for(cancel in input.cancel())deliver(cancel)
                        router.cancel()
                        if(!deliver(GraphicsContract.viewport(resized)).fenced) {
                            router.resize(listOf(resized));acceptedViewport=resized
                        }
                        clock.reset();timers.clear()
                    }
                    if(snapshot?.playback=="playing" && !coverActive && snapshot?.fenced==false && snapshot?.frameOwner==true) {
                        val dt=clock.tick(tick)
                        val subscribed=checkNotNull(snapshot).frames.toList()
                        for(id in subscribed) {
                            if(snapshot?.fenced==true || snapshot?.playback!="playing")break
                            if(id in checkNotNull(snapshot).frames) {
                                metrics?.requested()
                                deliver(GraphicsContract.event("frame",dt,surfaceId=id))
                            }
                        }
                        timers.keys.retainAll(checkNotNull(snapshot).timers.keys)
                        for((id,interval) in checkNotNull(snapshot).timers.toMap()) {
                            if(snapshot?.fenced==true || snapshot?.playback!="playing")break
                            val last=timers[id]
                            if(last==null || last.first!=interval)timers[id]=interval to now
                            else if(now-last.second>=interval) {
                                deliver(JSONObject().put("type","timer").put("timerId",id));timers[id]=interval to now
                            }
                        }
                    } else {clock.reset();timers.clear()}
                    metrics?.paused(coverActive || snapshot?.playback!="playing")
                }
            } catch(cancel: CancellationException) {throw cancel}
              catch(_: Exception) {error=R.string.tool_session_stopped}
            finally {
                if(drawingHost==openedDrawingHost) drawingHost=null
                withContext(NonCancellable) {
                    for(cancel in input.cancel())runCatching {deliver(cancel)}
                    router.cancel()
                    if(error==null)try {publish(active.flush())}catch(_: Exception) {error=R.string.tool_session_save_failed}
                    try {active.close()} catch(_: Exception) {error=R.string.tool_session_save_failed}
                }
                metrics?.stop(if(error==null)"STOPPED" else "ERROR")
                queue.clear()
            }
        }
    }
    val imageHost=drawingHost.takeIf {enabled && error==null}
    val drawingImages=remember(imageHost) {SurfaceBitmapCache()}
    DisposableEffect(drawingImages) {onDispose {drawingImages.close()}}
    LaunchedEffect(drawingImages,lifecycle) {
        val source=imageHost ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                val checks=flow {while(currentCoroutineContext().isActive) {emit(Unit);delay(1000)}}
                combine(snapshotFlow {requestedImages},checks) {handles,_ -> handles}.conflate().collect {handles ->
                    drawingImages.refresh(handles,
                        available={source.host.drawingImageAvailable(source.generation,it)},
                        load={handle,budget -> source.host.loadDrawingImage(source.generation,handle,budget)},
                        stillRequested={handle -> drawingHost==source && error==null && handle in requestedImages})
                }
            } finally {drawingImages.clear()}
        }
    }
    val scheme = MaterialTheme.colorScheme
    val canvasBackground = scheme.surface
    val roleColors = remember(scheme) {
        ToolUiCatalog.tones.associateWith { scheme.toolInk(it, canvasBackground).toArgb() }
    }
    val stage: @Composable (List<String>, String, Modifier, Boolean) -> Unit = { ids, description, modifier, interactive ->
        val selected = effectiveSurfaces.filter { it.id in ids }
        val placements = remember(selected) { surfacePlacements(selected) }
        if(placements.isNotEmpty()) {
            val stageWidth=placements.maxOf {it.x+it.surface.width}
            val stageHeight=placements.maxOf {it.y+it.surface.height}
            val inputWidth=acceptedViewport?.width ?: stageWidth
            val inputHeight=acceptedViewport?.height ?: stageHeight
            val current=snapshot
            val resources=LocalResources.current
            val labels=placements.joinToString(" ") {
                surfaceLabels(current?.surfaces?.optJSONObject(it.surface.id)?.optJSONArray("commands") ?: JSONArray(),drawingImages.images.keys) {label ->
                    resources.getString(R.string.tool_image_unavailable,label)
                }
            }
            BoxWithConstraints(if(immersive)modifier.fillMaxSize() else modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                var measuredSize by remember { mutableStateOf(IntSize.Zero) }
                val ratio=stageWidth.toFloat()/stageHeight
                val canvasModifier=if(viewportSurface!=null) {
                    val measured=if(immersive)Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(maxCanvasHeight)
                    measured
                } else Modifier.width(minOf(maxWidth,(if(immersive)maxHeight else minOf(maxCanvasHeight,maxHeight))*ratio)).aspectRatio(ratio)
                Canvas(canvasModifier.onSizeChanged {
                    measuredSize = it
                    if (viewportSurface != null && it.width > 0 && it.height > 0) {
                        requestedViewport = surfaceViewport(viewportSurface,it.width,it.height,density)
                    }
                }.background(canvasBackground)
                    .semantics {contentDescription="$description. $labels"}
                    .pointerInput(ids,measuredSize,interactive,enabled,snapshot?.playback in setOf("ready","playing"),error==null,attempt,acceptedViewport,requestedViewport,covered) {
                        if(!interactive || !enabled || covered || snapshot?.playback !in setOf("ready","playing") || error!=null || viewportSurface!=null && acceptedViewport!=requestedViewport)return@pointerInput
                        try {awaitPointerEventScope {
                            while(true) {
                                val event=awaitPointerEvent()
                                for(change in event.changes) {
                                    if(!change.pressed && !change.previousPressed)continue
                                    val phase=when {change.pressed && !change.previousPressed -> "down";!change.pressed -> "up";else -> "move"}
                                    val routed=router.route(change.id.value,phase,change.position.x.toDouble()/size.width*inputWidth,change.position.y.toDouble()/size.height*inputHeight, placements)
                                    if(routed!=null) {enqueue(routed);change.consume()}
                                }
                            }
                        }} finally {router.cancel(ids.toSet()).forEach(::enqueue)}
                    }) {
                    val canvas=drawContext.canvas.nativeCanvas
                    val saved=canvas.save()
                    try {
                        canvas.scale(size.width/stageWidth,size.height/stageHeight)
                        for(placement in placements) {
                            val surface=placement.surface
                            val commands=current?.surfaces?.optJSONObject(surface.id)?.optJSONArray("commands") ?: JSONArray()
                            val surfaceSaved=canvas.save()
                            try {
                                canvas.translate(placement.x.toFloat(),placement.y.toFloat())
                                drawSurfaceFrame(canvas,surface,commands,surface.width.toFloat(),surface.height.toFloat(),roleColors,drawingImages.images)
                            } finally {canvas.restoreToCount(surfaceSaved)}
                        }
                    } finally {canvas.restoreToCount(saved)}
                    val metrics=trace
                    if(current!=null && metrics!=null) {
                        metrics.submitted(current.sequence)
                        if(view.isHardwareAccelerated && view.viewTreeObserver.isAlive)
                            view.viewTreeObserver.registerFrameCommitCallback {metrics.committed(current.sequence)}
                    }
                }
            }
        }
    }
    val playbackControls: @Composable () -> Unit = {
        val event = ArtifactPlayback.primaryControl(snapshot?.playback)
        if (event != null) TextButton(onClick = {
            for (id in spec.surfaces.keys) enqueue(GraphicsContract.event(event, surfaceId = id))
        }, enabled = enabled && !covered, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(when (event) {
                "start" -> R.string.tool_game_start
                "resume" -> R.string.tool_game_resume
                else -> R.string.tool_game_pause
            }))
        }
        TextButton(onClick = {
            for (id in spec.surfaces.keys) enqueue(GraphicsContract.event("restart", surfaceId = id))
        }, enabled = enabled && !covered && snapshot != null, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.tool_game_restart))
        }
    }
    val recovery: @Composable (Int) -> Unit = { failure ->
        StatusLine(Glyph.Close, scheme.error, stringResource(failure))
        TextButton(onClick = { attempt++; error = null }, enabled = enabled && !covered,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.retry))
        }
    }
    val movementSurfaces = spec.surfaces.values.filter { it.id in spec.hostControls.movement }
    val movementControls: @Composable (Modifier,ToolSurface) -> Unit = {modifier,surface ->
        for(direction in listOf("left","right"))Button(onClick={enqueue(GraphicsContract.event("control",direction=direction,surfaceId=surface.id))},enabled=enabled && !covered && error==null,modifier=modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=8.dp)) {Text(stringResource(if(direction == "left") R.string.tool_game_left else R.string.tool_game_right))}
    }
    val showControls = stringResource(R.string.tool_game_controls_open)
    val gameControls = stringResource(R.string.tool_game_controls)
    if(immersive) Box(Modifier.fillMaxSize()) {
        stage(spec.surfaces.keys.toList(), spec.title, Modifier, true)
        if(!controlsExpanded && error==null)IconButton(onClick={controlsExpanded=true},modifier=Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))).size(48.dp).semantics {contentDescription=showControls}) {
            Surface(shape = MaterialTheme.shapes.small, color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
                AlphaIcon(Glyph.Menu, Modifier.padding(6.dp), color = LocalContentColor.current)
            }
        }
        if(controlsExpanded || error!=null)Surface(Modifier.align(Alignment.TopCenter).safeDrawingPadding().windowInsetsPadding(WindowInsets.systemGestures),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).semantics {contentDescription=gameControls}) {
                TextButton(onClick={controlsExpanded=false},modifier=Modifier.align(Alignment.End).heightIn(min=48.dp)) {Text(stringResource(R.string.tool_game_controls_close))}
                topControls()
                val failure = error
                if (failure != null) recovery(failure)
                else if (movementSurfaces.isNotEmpty() || spec.hostControls.playback) FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    for(surface in movementSurfaces)movementControls(Modifier.widthIn(min=48.dp),surface)
                    if (spec.hostControls.playback) playbackControls()
                }
            }
        }
    } else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        val inline = canvasSurfaceIds(spec.ui).isNotEmpty()
        if (!inline) stage(spec.surfaces.keys.toList(), spec.title, Modifier, true)
        val failure = error
        if (failure != null) recovery(failure)
        else {
            for(surface in movementSurfaces)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {movementControls(Modifier.weight(1f),surface)}
            if (spec.hostControls.playback) {
                val (icon, label) = when(snapshot?.playback) {
                    "ready" -> Glyph.Check to R.string.tool_game_ready
                    "playing" -> Glyph.Arrow to R.string.tool_game_playing
                    "paused" -> Glyph.Stop to R.string.tool_game_paused
                    "ended" -> Glyph.Stop to R.string.tool_game_ended
                    else -> Glyph.History to R.string.tool_game_starting
                }
                StatusLine(icon, LocalContentColor.current, stringResource(label))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {playbackControls()}
            }
        }
        CompositionLocalProvider(LocalToolCanvas provides stage, content = content)
    }
}
