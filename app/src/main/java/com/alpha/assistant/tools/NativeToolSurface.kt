package com.alpha.assistant.tools

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.alpha.assistant.sandbox.GraphicsSession
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Main-thread queue. Move coalescing never crosses a pointer transition. Overflow stops play. */
internal class SurfaceEventQueue {
    private val events=ArrayDeque<JSONObject>()
    fun add(event: JSONObject) {
        GraphicsContract.validateEvent(event)
        if(event.optString("type")=="pointer" && event.optString("phase")=="move") {
            val last=events.lastOrNull()
            if(last?.optString("type")=="pointer" && last.optString("phase")=="move" && last.optInt("pointer")==event.getInt("pointer"))events.removeLast()
        }
        check(events.size < 32) {"Input queue exceeded its limit. Restart the mini-app."};events.addLast(event)
    }
    fun next()=events.removeFirstOrNull()
    fun clear()=events.clear()
}

@Composable internal fun NativeToolSurface(tool: ToolRecord,spec: ToolSpec,enabled: Boolean,maxCanvasHeight: Dp,checkpoint: suspend(ToolRecord,JSONObject)->ToolRecord) {
    val surface=checkNotNull(spec.surface)
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var attempt by remember(tool.id,tool.revision) {mutableIntStateOf(0)}
    var error by remember(tool.id,tool.revision) {mutableStateOf<String?>(null)}
    var status by remember(tool.id,tool.revision) {mutableStateOf("Starting")}
    var frame by remember(tool.id,tool.revision) {mutableStateOf(JSONObject().put("commands",JSONArray()))}
    var paused by remember(tool.id,tool.revision) {mutableStateOf(false)}
    val queue=remember(tool.id,tool.revision,attempt) {SurfaceEventQueue()}
    val save=rememberUpdatedState(checkpoint)
    val latestTool=rememberUpdatedState(tool)
    fun enqueue(event: JSONObject) {if(enabled && error==null)try {queue.add(event)}catch(e: Exception) {error=e.message}}
    LaunchedEffect(tool.id,tool.revision,enabled,attempt) {
        if(!enabled)return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val session=GraphicsSession(context)
            var trace: SurfaceFrameMetrics.Trace?=null
            var failed=false
            var expected=latestTool.value
            queue.clear();error=null;paused=false;status="Starting"
            suspend fun deliver(event: JSONObject) {
                val acceptedStart=SystemClock.elapsedRealtimeNanos()
                val result=session.event(event)
                check(result.status=="OK") {result.message}
                val next=GraphicsContract.frame(checkNotNull(result.json),event.getString("type"))
                frame=next
                if(event.getString("type")=="frame")trace?.accepted(SystemClock.elapsedRealtimeNanos()-acceptedStart)
                if(event.getString("type")=="checkpoint" && next.has("checkpoint")) {
                    expected=save.value(expected,GraphicsContract.patch(spec,next.getJSONObject("checkpoint")))
                    status="Checkpoint saved"
                }
            }
            try {
                val start=session.open(surface.source,expected.state)
                check(start.status=="OK") {start.message}
                frame=GraphicsContract.frame(checkNotNull(start.json),"start");status="Playing"
                trace=SurfaceFrameMetrics.start(session.id,session.engineGeneration)
                var previous=SystemClock.elapsedRealtime()
                while(isActive && error==null) {
                    val begin=SystemClock.elapsedRealtime()
                    check(latestTool.value.revision==expected.revision && latestTool.value.stateVersion<=expected.stateVersion) {"Saved data changed in another operation. Reopen this mini-app before saving."}
                    check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked && context.getSystemService(PowerManager::class.java).isInteractive) {"Session stopped while device is locked. Restart to play."}
                    var event=queue.next()
                    while(event!=null) {
                        deliver(event)
                        when(event.getString("type")) {"pause" -> {paused=true;trace.paused(true);status="Paused"};"resume" -> {paused=false;trace.paused(false);status="Playing"}}
                        event=queue.next()
                    }
                    if(!paused)deliver(GraphicsContract.event("frame",(begin-previous).coerceIn(0,50).toInt()))
                    previous=begin
                    delay((34-(SystemClock.elapsedRealtime()-begin)).coerceAtLeast(1))
                }
            } catch(cancel: CancellationException) {throw cancel}
              catch(e: Exception) {failed=true;error=e.message ?: "Session stopped. Restart to play."}
            finally {trace?.stop(if(failed)"FAILED" else "STOPPED");session.close();queue.clear()}
        }
    }
    val commands=frame.getJSONArray("commands")
    val labels=surfaceLabels(commands)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        val ratio=surface.width.toFloat()/surface.height
        Canvas(Modifier.width(minOf(maxWidth,maxCanvasHeight*ratio)).aspectRatio(ratio).background(Color(0xFF101827))
            .semantics {contentDescription="${spec.title}. $labels"}
            .pointerInput(tool.id,tool.revision,enabled,attempt) {
                val pointers=mutableMapOf<PointerId,Int>()
                try {awaitPointerEventScope {
                    while(true) {
                        val event=awaitPointerEvent()
                        for(change in event.changes) {
                            if(change.pressed && !change.previousPressed && pointers.size < 2) pointers[change.id]=if(0 in pointers.values)1 else 0
                            val slot=pointers[change.id] ?: continue
                            val phase=when {change.pressed && !change.previousPressed -> "down";!change.pressed -> "up";else -> "move"}
                            enqueue(GraphicsContract.event("pointer",x=(change.position.x/size.width*surface.width).toDouble().coerceIn(0.0,surface.width.toDouble()),y=(change.position.y/size.height*surface.height).toDouble().coerceIn(0.0,surface.height.toDouble()),phase=phase,pointer=slot))
                            change.consume();if(!change.pressed)pointers.remove(change.id)
                        }
                    }
                }} finally {pointers.values.forEach {enqueue(GraphicsContract.event("pointer",phase="cancel",pointer=it))}}
            }) {
            val canvas=drawContext.canvas.nativeCanvas
            val saved=canvas.save()
            canvas.scale(size.width/surface.width,size.height/surface.height)
            canvas.clipRect(0f,0f,surface.width.toFloat(),surface.height.toFloat())
            drawSurface(canvas,commands,Paint(Paint.ANTI_ALIAS_FLAG))
            canvas.restoreToCount(saved)
        }
        }
        Text(error ?: status,style=MaterialTheme.typography.bodySmall,color=if(error==null)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for(direction in listOf("left","right"))Button(onClick={enqueue(GraphicsContract.event("control",direction=direction))},enabled=enabled && error==null,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text(direction.replaceFirstChar(Char::uppercase))}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={enqueue(GraphicsContract.event(if(paused)"resume" else "pause"))},enabled=enabled && error==null,modifier=Modifier.heightIn(min=48.dp)) {Text(if(paused)"Resume" else "Pause")}
            TextButton(onClick={if(error!=null) {attempt++;error=null}else enqueue(GraphicsContract.event("restart"))},enabled=enabled,modifier=Modifier.heightIn(min=48.dp)) {Text("Restart")}
            if(surface.checkpointFields.isNotEmpty())TextButton(onClick={enqueue(GraphicsContract.event("checkpoint"))},enabled=enabled && error==null,modifier=Modifier.heightIn(min=48.dp)) {Text("Save score")}
        }
    }
}
private fun surfaceLabels(commands: JSONArray): String = buildString {
    repeat(commands.length()) {val c=commands.getJSONObject(it);when(c.getString("type")) {"text" -> append(c.getString("text")).append(". ");"group" -> append(surfaceLabels(c.getJSONArray("children")))}}
}
private fun drawSurface(canvas: AndroidCanvas,commands: JSONArray,paint: Paint) {
    repeat(commands.length()) { index ->
        val c=commands.getJSONObject(index);val type=c.getString("type")
        if(type=="group") {drawSurface(canvas,c.getJSONArray("children"),paint);return@repeat}
        fun f(key: String)=c.getDouble(key).toFloat()
        paint.color=android.graphics.Color.parseColor(c.getString("color"));paint.style=Paint.Style.FILL
        when(type) {
            "rect" -> canvas.drawRect(f("x"),f("y"),f("x")+f("width"),f("y")+f("height"),paint)
            "roundRect" -> canvas.drawRoundRect(f("x"),f("y"),f("x")+f("width"),f("y")+f("height"),f("radius"),f("radius"),paint)
            "circle" -> canvas.drawCircle(f("x"),f("y"),f("radius"),paint)
            "line" -> {paint.strokeWidth=f("stroke");canvas.drawLine(f("x"),f("y"),f("x2"),f("y2"),paint)}
            "arc" -> {paint.style=Paint.Style.STROKE;paint.strokeWidth=f("stroke");canvas.drawArc(f("x"),f("y"),f("x")+f("width"),f("y")+f("height"),f("start"),f("sweep"),false,paint)}
            "polyline","polygon" -> {
                val points=c.getJSONArray("points");val path=Path()
                repeat(points.length()) {val p=points.getJSONArray(it);if(it==0)path.moveTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())else path.lineTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())}
                if(type=="polygon")path.close() else {paint.style=Paint.Style.STROKE;paint.strokeWidth=f("stroke")};canvas.drawPath(path,paint)
            }
            "text" -> {paint.textSize=f("size");canvas.drawText(c.getString("text"),f("x"),f("y"),paint)}
        }
    }
}
