package com.alpha.assistant.tools

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import androidx.compose.ui.platform.LocalContext
import com.alpha.assistant.capabilities.TiltContract
import com.alpha.assistant.device.AccelerometerStream
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import kotlinx.coroutines.*
import com.alpha.assistant.R
import com.alpha.assistant.cloud.CloudFailure
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

/** A host call of a revision after a newer revision was saved. The surface keeps its drawing until it composes the new revision. */
internal class SurfaceRevisionReplaced(cause: Throwable): Exception("A newer revision replaced this mini-app.",cause)

// Each busy try also waits up to 4 s for the slot in the runtime, so a waiting surface tries about every 5 s.
private const val OPEN_RETRIES=3
private const val OPEN_RETRY_MS=1_000L

/** One log line for each exception that a surface drops: its class, its code, and what the surface does next. A replaced revision logs the call failure under it. Never its message. */
private fun logDropped(failure: Throwable,outcome: String) {
    val dropped=(failure as? SurfaceRevisionReplaced)?.cause ?: failure
    android.util.Log.w("AlphyGeneration","surface ${dropped.javaClass.simpleName} ${(dropped as? CloudFailure)?.code ?: "-"} ${if(failure is SurfaceRevisionReplaced) "replaced" else outcome}")
}

private data class DrawingHost(val host: ArtifactUiHost,val generation: Long)

/** The screen boxes of the text that [snapshot] draws on a stage at [stage]. Text in transformed groups is left out. */
private fun drawnTextBounds(snapshot: ArtifactSnapshot?,surfaces: List<ToolSurface>,stage: Rect): List<Rect> {
    val placements=surfacePlacements(surfaces)
    if(snapshot==null || placements.isEmpty()) return emptyList()
    val sx=stage.width/placements.maxOf {it.x+it.surface.width}
    val sy=stage.height/placements.maxOf {it.y+it.surface.height}
    return placements.flatMap {p ->
        surfaceTextBoxes(snapshot.surfaces.optJSONObject(p.surface.id)?.optJSONArray("commands") ?: JSONArray()).map {r ->
            Rect(stage.left+(p.x+r.left)*sx,stage.top+(p.y+r.top)*sy,stage.left+(p.x+r.right)*sx,stage.top+(p.y+r.bottom)*sy)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NativeToolSurface(tool: ToolRecord,spec: ToolSpec,enabled: Boolean,maxCanvasHeight: Dp,host: ArtifactUiHost,immersive: Boolean=false,covered: Boolean=false,onCoverReady: (Boolean)->Unit = {},topControls: @Composable () -> Unit = {},stageAvailableHeight: Dp? = null,
    // False turns off only the touch of the drawing and its playback buttons: the frames and timers go on, as on the stage during a change.
    input: Boolean = true,
    content: @Composable () -> Unit = {},drawingBefore: () -> ArtifactSnapshot? = {null},onSnapshot: (ArtifactSnapshot) -> Unit = {}) {
    val context=LocalContext.current
    val view=LocalView.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var attempt by remember(tool.id,tool.revision) {mutableIntStateOf(0)}
    var retries by remember(tool.id,tool.revision) {mutableIntStateOf(0)}
    var slotWait by remember(tool.id,tool.revision) {mutableStateOf(false)}
    var error by remember(tool.id,tool.revision) {mutableStateOf<Int?>(null)}
    var controlsExpanded by remember(tool.id,tool.revision) {mutableStateOf(false)}
    // The full-screen menu button moves to another top position when the game draws text under it. It stays there after.
    var menuAlignment by remember(tool.id) {mutableStateOf<Alignment>(Alignment.TopEnd)}
    var screenBounds by remember {mutableStateOf<Rect?>(null)}
    var stageBounds by remember {mutableStateOf<Rect?>(null)}
    var menuBounds by remember {mutableStateOf<Rect?>(null)}
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
    val effectiveSurfaces=(snapshot ?: drawingBefore())?.viewport?.let {listOf(it)} ?: spec.surfaces.values.toList()
    val router=remember(tool.id,tool.revision,attempt) {SurfacePointerRouter(spec.surfaces.values.toList())}
    val latestCovered=rememberUpdatedState(covered)
    val latestCoverReady=rememberUpdatedState(onCoverReady)
    val latestHost=rememberUpdatedState(host)
    val latestSnapshot=rememberUpdatedState(onSnapshot)
    // A template scene glides between two drawings (block 2 plan, M5, M6, C1): a press with an ease-out, a step of the motion timer linearly.
    val gliding=remember(spec) {spec.kitScene}
    val glides=remember(tool.id,tool.revision) {mutableMapOf<String,SurfaceGlide>()}
    val glideSequences=remember(tool.id,tool.revision) {mutableMapOf<String,Long>()}
    // The sequence of the last snapshot that a timer event gave: a glide to it is a motion step.
    val motionSequence=remember(tool.id,tool.revision) {java.util.concurrent.atomic.AtomicLong(-1)}
    var glideVersion by remember(tool.id,tool.revision) {mutableIntStateOf(0)}
    var glideTick by remember(tool.id,tool.revision) {mutableLongStateOf(0L)}
    // The sequence of the last drawing that the canvas counted as a picture. A plain holder: the draw writes it.
    val pictured=remember(tool.id,tool.revision) {java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)}
    LaunchedEffect(glideVersion) {
        if(glideVersion==0)return@LaunchedEffect
        // Only the draw of the canvas reads the tick, so the frames go on only while a glide runs, and one more frame draws its end.
        while(glides.values.any {it.running(System.nanoTime())}) withFrameNanos {glideTick=it}
        withFrameNanos {glideTick=it}
    }
    fun enqueue(event: JSONObject) {if(enabled && error==null)try {queue.add(event)}catch(failure: Exception) {logDropped(failure,"input");error=R.string.tool_session_input_limit}}
    LaunchedEffect(tool.id,tool.revision,enabled,attempt) {
        if(!enabled)return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val active=latestHost.value
            val metrics=if(spec.surfaces.isNotEmpty())SurfaceFrameMetrics.start(java.util.UUID.randomUUID().toString(),0) else null
            trace=metrics
            queue.clear();router.cancel();error=null;acceptedViewport=null
            var published=false
            fun publish(value: ArtifactSnapshot,touchAtNs: Long?=null) {
                requestedImages=ArtifactProtocol.bitmapHandles(value.surfaces)
                snapshot=value;latestSnapshot.value(value);metrics?.validated(value.sequence,touchAtNs);published=true
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
            // An immersive game reads tilt while it plays with the menu closed and the window focused. Inline apps start it with a button.
            val tiltWanted=immersive && TiltContract.ID in spec.capabilities
            var tilt: AccelerometerStream?=null
            var tiltUnavailable=false
            var replaced=false
            var waits=false
            var retry=false
            try {
                val opened=active.open()
                if(slotWait) {slotWait=false;android.util.Log.i("AlphyGeneration","surface javascript_busy opened")}
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
                    val tilting=tiltWanted && !tiltUnavailable && !coverActive && !controlsExpanded && view.hasWindowFocus() &&
                        snapshot?.playback=="playing" && snapshot?.fenced==false && snapshot?.frameOwner==true
                    if(tilting && tilt==null) {
                        val stream=AccelerometerStream(context)
                        if(stream.start()) tilt=stream else {stream.close();tiltUnavailable=true}
                    } else if(!tilting && tilt!=null) {tilt?.close();tilt=null}
                    tilt?.samples?.tryReceive()?.getOrNull()?.let {deliver(it)}
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
                                motionSequence.set(deliver(JSONObject().put("type","timer").put("timerId",id)).sequence);timers[id]=interval to now
                            }
                        }
                    } else {clock.reset();timers.clear()}
                    metrics?.paused(coverActive || snapshot?.playback!="playing")
                }
            } catch(cancel: CancellationException) {throw cancel}
              catch(failure: Exception) {
                // A replaced revision ends with no line, a busy slot is no failure and waits with no bound, and another failed open tries a few times first.
                replaced=failure is SurfaceRevisionReplaced
                waits=!published && (failure as? CloudFailure)?.code=="javascript_busy"
                retry=waits || !replaced && !published && retries<OPEN_RETRIES
                if(!waits || !slotWait) logDropped(failure,when {waits -> "wait";retry -> "retry";else -> "stopped"})
                slotWait=waits
                if(!replaced && !retry) error=R.string.tool_session_stopped
              }
            finally {
                tilt?.close()
                if(drawingHost==openedDrawingHost) drawingHost=null
                withContext(NonCancellable) {
                    for(cancel in input.cancel())runCatching {deliver(cancel)}.onFailure {logDropped(it,"input")}
                    router.cancel()
                    if(error==null && published && !replaced)try {publish(active.flush())}catch(failure: Exception) {
                        logDropped(failure,"save");if(failure !is SurfaceRevisionReplaced) error=R.string.tool_session_save_failed
                    }
                    try {active.close()} catch(failure: Exception) {logDropped(failure,"save");error=R.string.tool_session_save_failed}
                }
                metrics?.stop(if(error==null)"STOPPED" else "ERROR")
                queue.clear()
            }
            if(retry) {delay(OPEN_RETRY_MS);if(!waits)retries++;attempt++}
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
    val appColors = remember(spec.ui, scheme) { rootThemeColors(spec.ui.toString(), scheme.background.luminance() < 0.5f) }
    val stage: @Composable (List<String>, String, Modifier, Boolean) -> Unit = { ids, description, modifier, interactive ->
        // A full-screen surface takes the app theme background. An inline canvas takes the color of the card or page around it.
        val colors = if (immersive) appColors ?: scheme else MaterialTheme.colorScheme
        val canvasBackground = if (immersive) colors.background else LocalToolBackground.current.takeOrElse { colors.surface }
        val roleColors = remember(colors, canvasBackground) { colors.drawingRoles(canvasBackground) }
        val selected = effectiveSurfaces.filter { it.id in ids }
        val placements = remember(selected) { surfacePlacements(selected) }
        if(placements.isNotEmpty()) {
            val stageWidth=placements.maxOf {it.x+it.surface.width}
            val stageHeight=placements.maxOf {it.y+it.surface.height}
            val inputWidth=acceptedViewport?.width ?: stageWidth
            val inputHeight=acceptedViewport?.height ?: stageHeight
            val current=snapshot
            // Until the open publishes, the canvas keeps the drawing of the revision that this one replaced.
            val drawn=current ?: drawingBefore()
            // The images of the app's own resources, and the pictures of the library (stage 6, I4).
            val pictures=rememberLibraryImages(drawn?.surfaces)
            val images=remember(drawingImages.images,pictures) {drawingImages.images+pictures}
            val resources=LocalResources.current
            val labels=placements.joinToString(" ") {
                surfaceLabels(drawn?.surfaces?.optJSONObject(it.surface.id)?.optJSONArray("commands") ?: JSONArray(),drawingImages.images.keys) {label ->
                    resources.getString(R.string.tool_image_unavailable,label)
                }
            }
            // Each new drawing of a surface is the new target of its glide, from the picture that shows now (M6).
            if(gliding && current!=null) SideEffect {
                var changed=false
                val now=System.nanoTime()
                for(placement in placements) {
                    val id=placement.surface.id
                    if(glideSequences[id]==current.sequence)continue
                    glideSequences[id]=current.sequence
                    val commands=current.surfaces.optJSONObject(id)?.optJSONArray("commands") ?: JSONArray()
                    val glide=glides.getOrPut(id) {SurfaceGlide(maxOf(placement.surface.width,placement.surface.height)/4.0)}
                    if(glide.retarget(commands,now,current.sequence==motionSequence.get(),SurfaceGlide.pressTime(now)))changed=true
                }
                if(changed)glideVersion++
            }
            BoxWithConstraints(if(immersive)modifier.fillMaxSize() else modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                var measuredSize by remember { mutableStateOf(IntSize.Zero) }
                val ratio=stageWidth.toFloat()/stageHeight
                // In a fitted stage column (FittedColumn) the picture takes the height that the stage leaves, at least 0.6 of the column.
                // The large stage picture (Stage A, AH3) takes all of it with no cap: its card is that high, and its box keeps the ratio in the center.
                val largePicture=LocalStagePicture.current && viewportSurface==null && !immersive
                val room=fittedPicture(constraints,if(largePicture) Dp.Infinity else stageAvailableHeight ?: maxCanvasHeight)
                val large=largePicture && room!=null
                // A shape past the box shows up to the edges of the card. With more than one surface, each surface keeps its own box.
                val clipBox=!large || placements.size>1
                val canvasModifier=if(viewportSurface!=null) {
                    val measured=if(immersive)Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(room ?: if (stageAvailableHeight != null) maxHeight else minOf(maxCanvasHeight, maxHeight))
                    measured
                } else Modifier.width(minOf(maxWidth,(if(immersive)maxHeight else room ?: minOf(stageAvailableHeight ?: maxCanvasHeight,maxHeight))*ratio)).aspectRatio(ratio)
                Box(if(large) Modifier.fillMaxWidth().height(checkNotNull(room)).clipToBounds().background(canvasBackground).testTag(STAGE_PICTURE_TAG) else Modifier,
                    contentAlignment=Alignment.Center) {
                Canvas(canvasModifier.onSizeChanged {
                    measuredSize = it
                    if (viewportSurface != null && it.width > 0 && it.height > 0) {
                        requestedViewport = surfaceViewport(viewportSurface,it.width,it.height,density)
                    }
                }.then(if (immersive) Modifier.onGloballyPositioned {stageBounds=it.boundsInRoot()} else if (large) Modifier else Modifier.clip(MaterialTheme.shapes.medium))
                    .background(canvasBackground)
                    .semantics {contentDescription="$description. $labels"}
                    .pointerInput(ids,measuredSize,interactive,enabled,input,snapshot?.playback in setOf("ready","playing"),error==null,attempt,acceptedViewport,requestedViewport,covered) {
                        if(!interactive || !enabled || !input || covered || snapshot?.playback !in setOf("ready","playing") || error!=null || viewportSurface!=null && acceptedViewport!=requestedViewport)return@pointerInput
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
                    // The tick of a running glide draws each display frame. One clock serves the glide and its targets.
                    glideTick
                    val now=System.nanoTime()
                    val metrics=trace
                    var newPicture=false
                    try {
                        canvas.scale(size.width/stageWidth,size.height/stageHeight)
                        for(placement in placements) {
                            val surface=placement.surface
                            val commands=drawn?.surfaces?.optJSONObject(surface.id)?.optJSONArray("commands") ?: JSONArray()
                            val surfaceSaved=canvas.save()
                            try {
                                canvas.translate(placement.x.toFloat(),placement.y.toFloat())
                                val glide=glides[surface.id]?.takeIf {gliding && it.target!=null}
                                if(glide==null) drawSurfaceFrame(canvas,surface,commands,surface.width.toFloat(),surface.height.toFloat(),roleColors,images,clipBox)
                                else {
                                    if(glide.running(now))newPicture=true
                                    drawSurfaceLayers(canvas,surface,glide.frame(now),surface.width.toFloat(),surface.height.toFloat(),roleColors,images,canvasBackground.toArgb(),clipBox)
                                    if(glide.pressEnded(now))metrics?.glideEnded()
                                }
                            } finally {canvas.restoreToCount(surfaceSaved)}
                        }
                    } finally {canvas.restoreToCount(saved)}
                    // C1 counts each picture that differs from the one before it: a frame of a glide, or a new drawing.
                    if(current!=null && current.sequence!=pictured.get()) {pictured.set(current.sequence);newPicture=true}
                    if(newPicture)metrics?.pictured()
                    if(current!=null && metrics!=null) {
                        metrics.submitted(current.sequence)
                        if(view.isHardwareAccelerated && view.viewTreeObserver.isAlive)
                            view.viewTreeObserver.registerFrameCommitCallback {metrics.committed(current.sequence)}
                    }
                }
                }
            }
        }
    }
    val playbackControls: @Composable () -> Unit = {
        ToolPlaybackControls(ArtifactPlayback.primaryControl(snapshot?.playback), enabled && input && !covered,
            restartEnabled = snapshot != null, appLanguage = spec.appLanguage) { event ->
            for (id in spec.surfaces.keys) enqueue(GraphicsContract.event(event, surfaceId = id))
        }
    }
    // With no touch, as on the stage during a change, a stopped session keeps its last drawing and shows no error until the touch comes back.
    val shownError = error.takeIf { input }
    val recovery: @Composable (Int) -> Unit = { failure ->
        StatusLine(Glyph.Close, scheme.error, stringResource(failure))
        TextButton(onClick = { attempt++; error = null }, enabled = enabled && !covered,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.retry))
        }
    }
    val showControls = stringResource(R.string.tool_game_controls_open)
    val gameControls = stringResource(R.string.tool_game_controls)
    if(immersive) {
        val surfaces by rememberUpdatedState(effectiveSurfaces.filter {it.id in spec.surfaces.keys})
        LaunchedEffect(tool.id) {
            while(true) {
                delay(500)
                val screen=screenBounds ?: continue
                val menu=menuBounds ?: continue
                val stage=stageBounds ?: continue
                val texts=drawnTextBounds(snapshot,surfaces,stage)
                if(menuAlignment==Alignment.TopCenter || texts.none {it.overlaps(menu)}) continue
                val mirrored=Rect(screen.left+screen.right-menu.right,menu.top,screen.left+screen.right-menu.left,menu.bottom)
                menuAlignment=if(texts.any {it.overlaps(mirrored)}) Alignment.TopCenter
                    else if(menuAlignment==Alignment.TopEnd) Alignment.TopStart else Alignment.TopEnd
            }
        }
    }
    if(immersive) Box(Modifier.fillMaxSize().onGloballyPositioned {screenBounds=it.boundsInRoot()}) {
        stage(spec.surfaces.keys.toList(), spec.title, Modifier, true)
        if(!controlsExpanded && shownError==null)IconButton(onClick={controlsExpanded=true},modifier=Modifier.align(menuAlignment).windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))).size(48.dp).onGloballyPositioned {menuBounds=it.boundsInRoot()}.semantics {contentDescription=showControls}) {
            Surface(shape = MaterialTheme.shapes.small, color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
                AlphaIcon(Glyph.Menu, Modifier.padding(6.dp), color = LocalContentColor.current)
            }
        }
        if(controlsExpanded || shownError!=null)Surface(Modifier.align(Alignment.TopCenter).safeDrawingPadding().windowInsetsPadding(WindowInsets.systemGestures),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).semantics {contentDescription=gameControls}) {
                TextButton(onClick={controlsExpanded=false},modifier=Modifier.align(Alignment.End).heightIn(min=48.dp)) {Text(stringResource(R.string.tool_game_controls_close))}
                topControls()
                val failure = shownError
                if (failure != null) recovery(failure)
                else if (spec.hostControls.playback) playbackControls()
            }
        }
    } else {
        val inline = canvasSurfaceIds(spec.ui).isNotEmpty()
        val playback: @Composable () -> Unit = {
            val failure = shownError
            if (failure != null) recovery(failure)
            else if (slotWait) StatusLine(Glyph.History, LocalContentColor.current, stringResource(R.string.tool_session_waiting))
            else {
                if (spec.hostControls.playback) {
                    val (icon, label) = when(snapshot?.playback) {
                        "ready" -> Glyph.Check to R.string.tool_game_ready
                        "playing" -> Glyph.Arrow to R.string.tool_game_playing
                        "paused" -> Glyph.Stop to R.string.tool_game_paused
                        "ended" -> Glyph.Stop to R.string.tool_game_ended
                        else -> Glyph.History to R.string.tool_game_starting
                    }
                    StatusLine(icon, LocalContentColor.current, stringResource(label))
                    playbackControls()
                }
            }
        }
        val appControls: @Composable () -> Unit = {
            CompositionLocalProvider(LocalToolCanvas provides stage, content = content)
        }
        if (!inline && stageAvailableHeight != null) {
            ToolStageLayout(stageAvailableHeight,
                stage = { stage(spec.surfaces.keys.toList(), spec.title, Modifier, true) },
                controls = {
                    Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) { appControls() }
                    playback()
                })
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!inline) stage(spec.surfaces.keys.toList(), spec.title, Modifier, true)
            playback()
            appControls()
        }
    }
}

@Composable
internal fun ToolPlaybackControls(
    primary: String?,
    enabled: Boolean = true,
    restartEnabled: Boolean = true,
    appLanguage: String? = null,
    onControl: (String) -> Unit,
) = CompositionLocalProvider(LocalMiniAppControlsLanguage provides appLanguage) {
    val colors = MaterialTheme.colorScheme
    val target = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (primary != null) {
                val label = when (primary) {
                    "start" -> R.string.tool_game_start
                    "resume" -> R.string.tool_game_resume
                    else -> R.string.tool_game_pause
                }
                Button(onClick = { onControl(primary) }, modifier = target, enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceContainerHighest, contentColor = colors.onSurface)) {
                    AlphaIcon(if (primary == "pause") Glyph.Pause else Glyph.Play, color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text(miniAppString(label))
                }
            }
            TextButton(onClick = { onControl("restart") }, modifier = target, enabled = enabled && restartEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                AlphaIcon(Glyph.Refresh, color = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
                Text(miniAppString(R.string.tool_game_restart))
            }
        }
    }
}

@Composable
internal fun ToolStageLayout(
    availableHeight: Dp?,
    stage: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Box { stage() }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
        },
    ) { children, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val controlsPlaceable = children[1].measure(loose)
        val gap = 8.dp.roundToPx()
        // The stage keeps at least 55% of the screen. Taller controls scroll below it.
        val stageHeight = availableHeight?.let {
            (it.roundToPx() - controlsPlaceable.height - gap)
                .coerceAtLeast(maxOf(160.dp.roundToPx(), it.roundToPx() * 55 / 100)).coerceAtMost(loose.maxHeight)
        } ?: loose.maxHeight
        val stagePlaceable = children[0].measure(loose.copy(maxHeight = stageHeight))
        layout(constraints.maxWidth, constraints.constrainHeight(stagePlaceable.height + gap + controlsPlaceable.height)) {
            stagePlaceable.placeRelative(0, 0)
            controlsPlaceable.placeRelative(0, stagePlaceable.height + gap)
        }
    }
}
