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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import kotlinx.coroutines.*
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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NativeToolSurface(tool: ToolRecord,spec: ToolSpec,enabled: Boolean,maxCanvasHeight: Dp,host: ArtifactUiHost,immersive: Boolean=false,covered: Boolean=false,onCoverReady: (Boolean)->Unit = {},topControls: @Composable () -> Unit = {},onSnapshot: (ArtifactSnapshot) -> Unit = {}) {
    val context=LocalContext.current
    val view=LocalView.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var attempt by remember(tool.id,tool.revision) {mutableIntStateOf(0)}
    var error by remember(tool.id,tool.revision) {mutableStateOf<String?>(null)}
    var controlsExpanded by remember(tool.id,tool.revision) {mutableStateOf(false)}
    var snapshot by remember(tool.id,tool.revision) {mutableStateOf<ArtifactSnapshot?>(null)}
    var trace by remember(tool.id,tool.revision) {mutableStateOf<SurfaceFrameMetrics.Trace?>(null)}
    val density=LocalDensity.current.density
    val viewportSurface=spec.surfaces.values.singleOrNull()?.takeIf {it.sizing=="viewport"}
    var requestedViewport by remember(tool.id,tool.revision) {mutableStateOf<ToolSurface?>(null)}
    var acceptedViewport by remember(tool.id,tool.revision) {mutableStateOf<ToolSurface?>(null)}
    if(immersive) ImmersiveSystemBars(error==null)
    val queue=remember(tool.id,tool.revision,attempt) {SurfaceEventQueue()}
    val effectiveSurfaces=acceptedViewport?.let {listOf(it)} ?: spec.surfaces.values.toList()
    val placements=remember(effectiveSurfaces) {surfacePlacements(effectiveSurfaces)}
    val router=remember(tool.id,tool.revision,attempt) {SurfacePointerRouter(spec.surfaces.values.toList())}
    val latestCovered=rememberUpdatedState(covered)
    val latestCoverReady=rememberUpdatedState(onCoverReady)
    val latestHost=rememberUpdatedState(host)
    val latestSnapshot=rememberUpdatedState(onSnapshot)
    fun enqueue(event: JSONObject) {if(enabled && error==null)try {queue.add(event)}catch(e: Exception) {error=e.message}}
    LaunchedEffect(tool.id,tool.revision,enabled,attempt) {
        if(!enabled)return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val active=latestHost.value
            val metrics=if(spec.surfaces.isNotEmpty())SurfaceFrameMetrics.start(java.util.UUID.randomUUID().toString(),0) else null
            trace=metrics
            queue.clear();router.cancel();error=null
            fun publish(value: ArtifactSnapshot,touchAtNs: Long?=null) {
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
            try {
                publish(active.open())
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
                for(id in spec.surfaces.keys)deliver(GraphicsContract.event("start",surfaceId=id))
                while(isActive && error==null) {
                    // Only this coroutine evaluates. The next display tick follows completion, without catch-up.
                    val tick=withFrameNanos {it}
                    val now=SystemClock.elapsedRealtime()
                    publish(active.snapshot())
                    check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked && context.getSystemService(PowerManager::class.java).isInteractive) {"Session stopped while device is locked. Reopen the mini-app."}
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
                    if(snapshot?.playback=="playing" && !coverActive && snapshot?.fenced==false) {
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
              catch(e: Exception) {error=e.message ?: "Session stopped. Reopen the mini-app."}
            finally {
                withContext(NonCancellable) {
                    for(cancel in input.cancel())runCatching {deliver(cancel)}
                    router.cancel()
                    if(error==null)try {publish(active.flush())}catch(e: Exception) {error=e.message ?: "The local snapshot could not commit."}
                    try {active.close()} catch(e: Exception) {error=e.message ?: "The local snapshot could not commit."}
                }
                metrics?.stop(if(error==null)"STOPPED" else "ERROR")
                queue.clear()
            }
        }
    }
    val stage: @Composable () -> Unit = {
        if(placements.isNotEmpty()) {
            val stageWidth=placements.maxOf {it.x+it.surface.width}
            val stageHeight=placements.maxOf {it.y+it.surface.height}
            val current=snapshot
            val labels=placements.joinToString(" ") {surfaceLabels(current?.surfaces?.optJSONObject(it.surface.id)?.optJSONArray("commands") ?: JSONArray())}
            BoxWithConstraints(if(immersive)Modifier.fillMaxSize() else Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                val ratio=stageWidth.toFloat()/stageHeight
                val canvasModifier=if(viewportSurface!=null) {
                    val measured=if(immersive)Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(maxCanvasHeight)
                    measured.onSizeChanged {if(it.width>0 && it.height>0)requestedViewport=surfaceViewport(viewportSurface,it.width,it.height,density)}
                } else Modifier.width(minOf(maxWidth,(if(immersive)maxHeight else minOf(maxCanvasHeight,maxHeight))*ratio)).aspectRatio(ratio)
                Canvas(canvasModifier.background(Color(0xFF101827))
                    .semantics {contentDescription="${spec.title}. $labels"}
                    .pointerInput(tool.id,tool.revision,enabled,snapshot?.playback in setOf("ready","playing"),error==null,attempt,acceptedViewport,requestedViewport,covered) {
                        if(!enabled || covered || snapshot?.playback !in setOf("ready","playing") || error!=null || viewportSurface!=null && acceptedViewport!=requestedViewport)return@pointerInput
                        try {awaitPointerEventScope {
                            while(true) {
                                val event=awaitPointerEvent()
                                for(change in event.changes) {
                                    if(!change.pressed && !change.previousPressed)continue
                                    val phase=when {change.pressed && !change.previousPressed -> "down";!change.pressed -> "up";else -> "move"}
                                    val routed=router.route(change.id.value,phase,change.position.x.toDouble()/size.width*stageWidth,change.position.y.toDouble()/size.height*stageHeight)
                                    if(routed!=null) {enqueue(routed);change.consume()}
                                }
                            }
                        }} finally {router.cancel().forEach(::enqueue)}
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
                                drawSurfaceFrame(canvas,surface,commands,surface.width.toFloat(),surface.height.toFloat())
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
            val playback=snapshot?.playback
            if(playback!="ended")TextButton(onClick={
                val event=when(playback) {"ready" -> "start"; "paused" -> "resume"; else -> "pause"}
                for(id in spec.surfaces.keys)enqueue(GraphicsContract.event(event,surfaceId=id))
            },enabled=enabled && !covered && error==null && snapshot!=null,modifier=Modifier.heightIn(min=48.dp)) {
                Text(when(playback) {"ready" -> "Start"; "paused" -> "Resume"; else -> "Pause"})
            }
            TextButton(onClick={if(error!=null) {attempt++;error=null}else for(id in spec.surfaces.keys)enqueue(GraphicsContract.event("restart",surfaceId=id))},enabled=enabled && !covered,modifier=Modifier.heightIn(min=48.dp)) {Text("Restart")}
    }
    val movementControls: @Composable (Modifier,ToolSurface) -> Unit = {modifier,surface ->
        for(direction in listOf("left","right"))Button(onClick={enqueue(GraphicsContract.event("control",direction=direction,surfaceId=surface.id))},enabled=enabled && !covered && error==null,modifier=modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=8.dp)) {Text(direction.replaceFirstChar(Char::uppercase))}
    }
    if(immersive) Box(Modifier.fillMaxSize()) {
        stage()
        if(!controlsExpanded && error==null)IconButton(onClick={controlsExpanded=true},modifier=Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))).size(48.dp).semantics {contentDescription="Show game controls"}) {
            Canvas(Modifier.size(21.dp)) {
                for(y in listOf(0.25f,0.5f,0.75f))drawLine(Color.White,Offset(size.width*0.15f,size.height*y),Offset(size.width*0.85f,size.height*y),strokeWidth=2.dp.toPx())
            }
        }
        if(controlsExpanded || error!=null)Surface(Modifier.align(Alignment.TopCenter).safeDrawingPadding().windowInsetsPadding(WindowInsets.systemGestures),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).semantics {contentDescription="Native game controls"}) {
                TextButton(onClick={controlsExpanded=false},modifier=Modifier.align(Alignment.End).heightIn(min=48.dp)) {Text("Hide game controls")}
                topControls()
                if(spec.surfaces.isNotEmpty())FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    for(surface in spec.surfaces.values)movementControls(Modifier.widthIn(min=48.dp),surface)
                    playbackControls()
                }
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            }
        }
    } else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        stage()
        for(surface in spec.surfaces.values)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {movementControls(Modifier.weight(1f),surface)}
        if(error!=null || spec.surfaces.isNotEmpty())Text(error ?: snapshot?.playback?.replaceFirstChar(Char::uppercase) ?: "Starting",style=MaterialTheme.typography.bodySmall,color=if(error==null)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        if(spec.surfaces.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {playbackControls()}
    }
}
