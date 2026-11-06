package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.Base64
import androidx.compose.ui.graphics.toArgb
import com.alpha.assistant.ui.AlphaLightColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

internal fun surfaceLabels(commands: JSONArray,availableImages: Set<String> = emptySet(),
    unavailableImage: (String)->String = {"$it · image unavailable"}): String=buildString {
    fun visit(array: JSONArray) {
        repeat(array.length()) {index ->
            val command=array.getJSONObject(index)
            when(command.getString("type")) {
                "text" -> append(command.getString("text")).append(". ")
                "bitmap" -> {
                    val label=command.getString("label")
                    append(if(command.getString("resourceId") in availableImages) label else unavailableImage(label)).append(". ")
                }
                "group" -> visit(command.getJSONArray("children"))
            }
        }
    }
    visit(commands)
}

/** Each surface and group restores the native canvas. Each leaf owns its paint. */
internal fun drawSurfaceFrame(canvas: Canvas,surface: ToolSurface,commands: JSONArray,width: Float,height: Float,roleColors: Map<String,Int>,
    images: Map<String,Bitmap> = emptyMap()) {
    val saved=canvas.save()
    try {
        canvas.scale(width/surface.width,height/surface.height)
        canvas.clipRect(0f,0f,surface.width.toFloat(),surface.height.toFloat())
        drawSurface(canvas,commands,roleColors,images)
    } finally {canvas.restoreToCount(saved)}
}
/** Compare the selected shapes and their contribution to a bounded reference rendering. */
internal data class SurfaceGeometry(val width: Int, val height: Int, val shape: List<String>, val visible: String, val hasInk: Boolean) {
    fun movedFrom(previous: SurfaceGeometry) = width == previous.width && height == previous.height &&
        hasInk && previous.hasInk && visible != previous.visible && shape != previous.shape
}

internal fun observeSurfaceGeometry(surface: ToolSurface, commands: JSONArray, selection: List<Int>): SurfaceGeometry? {
    fun group(command: JSONObject, children: JSONArray) =
        JSONObject().put("type", "group").put("children", children).apply {
            command.optJSONArray("transform")?.let { put("transform", it) }
        }
    fun partition(array: JSONArray, depth: Int): Pair<JSONArray, JSONArray>? {
        if (depth == selection.size) return array to JSONArray()
        val index = selection[depth]
        val command = array.optJSONObject(index) ?: return null
        val remainder = JSONArray()
        val selected = if (depth == selection.lastIndex) JSONArray().put(command) else {
            val children = command.optJSONArray("children") ?: return null
            val (inside, outside) = partition(children, depth + 1) ?: return null
            repeat(array.length()) { n -> remainder.put(if (n == index) group(command, outside) else array.getJSONObject(n)) }
            return JSONArray().put(group(command, inside)) to remainder
        }
        repeat(array.length()) { n -> if (n != index) remainder.put(array.getJSONObject(n)) }
        return selected to remainder
    }
    fun shapes(array: JSONArray, transforms: List<String>, output: MutableList<String>) {
        repeat(array.length()) { index ->
            val command = array.getJSONObject(index)
            when (command.getString("type")) {
                "text" -> Unit
                "group" -> shapes(command.getJSONArray("children"),
                    transforms + listOfNotNull(command.optJSONArray("transform")?.toString()), output)
                else -> output += transforms.toString() + command.keys().asSequence()
                    .filterNot { it in setOf("color", "gradient", "label", "resourceId") }.sorted()
                    .joinToString { key -> "$key=${command.get(key)}" }
            }
        }
    }
    fun withoutText(array: JSONArray): JSONArray = JSONArray().apply {
        repeat(array.length()) { index ->
            val command = array.getJSONObject(index)
            when (command.getString("type")) {
                "text" -> Unit
                "group" -> put(group(command, withoutText(command.getJSONArray("children"))))
                else -> put(command)
            }
        }
    }
    val (selected, remainder) = partition(commands, 0) ?: return null
    val shape = mutableListOf<String>().apply { shapes(selected, emptyList(), this); sort() }
    val width = minOf(surface.width, 512)
    val height = minOf(surface.height, 512)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val scheme = AlphaLightColors
    val background = scheme.surface.toArgb()
    val colors = ToolUiCatalog.tones.associateWith { scheme.toolInk(it, scheme.surface).toArgb() }
    try {
        val canvas = Canvas(bitmap)
        fun render(array: JSONArray, pixels: IntArray) {
            bitmap.eraseColor(background)
            drawSurfaceFrame(canvas, surface, withoutText(array), width.toFloat(), height.toFloat(), colors)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        }
        val complete = IntArray(width * height)
        val without = IntArray(complete.size)
        render(commands, complete)
        render(remainder, without)
        val bytes = ByteBuffer.allocate(complete.size * Int.SIZE_BYTES)
        val pixels = bytes.asIntBuffer()
        var hasInk = false
        for (index in complete.indices) {
            val difference = complete[index] xor without[index]
            if (difference != 0) hasInk = true
            pixels.put(difference)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.array())
        return SurfaceGeometry(width, height, shape, Base64.encodeToString(digest, Base64.NO_WRAP), hasInk)
    } finally {
        bitmap.recycle()
    }
}

private fun drawSurface(canvas: Canvas,commands: JSONArray,roleColors: Map<String,Int>,images: Map<String,Bitmap>) {
    repeat(commands.length()) {index ->
        val c=commands.getJSONObject(index)
        if(c.getString("type")=="group") {
            val saved=canvas.save()
            try {
                c.optJSONArray("transform")?.let {m ->
                    canvas.concat(Matrix().apply {setValues(floatArrayOf(m.getDouble(0).toFloat(),m.getDouble(2).toFloat(),m.getDouble(4).toFloat(),m.getDouble(1).toFloat(),m.getDouble(3).toFloat(),m.getDouble(5).toFloat(),0f,0f,1f))})
                }
                drawSurface(canvas,c.getJSONArray("children"),roleColors,images)
            } finally {canvas.restoreToCount(saved)}
            return@repeat
        }
        fun f(key: String)=c.getDouble(key).toFloat()
        if(c.getString("type")=="bitmap") {
            val destination=RectF(f("x"),f("y"),f("x")+f("width"),f("y")+f("height"))
            drawBitmapCommand(canvas,destination,images[c.getString("resourceId")],roleColors["muted"] ?: Color.GRAY)
            return@repeat
        }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.FILL
            if(c.has("color"))color=drawingColor(c.getString("color"),roleColors) else shader=gradient(c.getJSONObject("gradient"),roleColors)
        }
        when(c.getString("type")) {
            "rect" -> canvas.drawRect(f("x"),f("y"),f("x")+f("width"),f("y")+f("height"),paint)
            "roundRect" -> canvas.drawRoundRect(f("x"),f("y"),f("x")+f("width"),f("y")+f("height"),f("radius"),f("radius"),paint)
            "circle" -> canvas.drawCircle(f("x"),f("y"),f("radius"),paint)
            "line" -> {paint.strokeWidth=f("stroke");canvas.drawLine(f("x"),f("y"),f("x2"),f("y2"),paint)}
            "arc" -> {paint.style=Paint.Style.STROKE;paint.strokeWidth=f("stroke");canvas.drawArc(f("x"),f("y"),f("x")+f("width"),f("y")+f("height"),f("start"),f("sweep"),false,paint)}
            "polyline","polygon" -> {
                val points=c.getJSONArray("points");val path=Path()
                repeat(points.length()) {val p=points.getJSONArray(it);if(it==0)path.moveTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())else path.lineTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())}
                if(c.getString("type")=="polygon")path.close() else {paint.style=Paint.Style.STROKE;paint.strokeWidth=f("stroke")}
                canvas.drawPath(path,paint)
            }
            "path" -> {
                val path=Path();val segments=c.getJSONArray("segments")
                repeat(segments.length()) {
                    val s=segments.getJSONObject(it)
                    fun p(k: String)=s.getDouble(k).toFloat()
                    when(s.getString("type")) {
                        "move" -> path.moveTo(p("x"),p("y"))
                        "line" -> path.lineTo(p("x"),p("y"))
                        "quad" -> path.quadTo(p("cx"),p("cy"),p("x"),p("y"))
                        "cubic" -> path.cubicTo(p("cx1"),p("cy1"),p("cx2"),p("cy2"),p("x"),p("y"))
                        "close" -> path.close()
                    }
                }
                if(c.has("stroke")) {paint.style=Paint.Style.STROKE;paint.strokeWidth=f("stroke")}
                canvas.drawPath(path,paint)
            }
            "text" -> {paint.textSize=f("size");canvas.drawText(c.getString("text"),f("x"),f("y"),paint)}
        }
    }
}

private fun drawBitmapCommand(canvas: Canvas,destination: RectF,image: Bitmap?,ink: Int) {
    val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if(image!=null && !image.isRecycled) {
        canvas.drawBitmap(image,null,destination,paint)
    } else {
        val saved=canvas.save()
        try {
            canvas.clipRect(destination)
            paint.color=ink;paint.alpha=48
            canvas.drawRect(destination,paint)
            paint.alpha=255;paint.strokeWidth=1f
            canvas.drawLine(destination.left,destination.top,destination.right,destination.bottom,paint)
            canvas.drawLine(destination.right,destination.top,destination.left,destination.bottom,paint)
        } finally {canvas.restoreToCount(saved)}
    }
}
private fun gradient(g: JSONObject,roleColors: Map<String,Int>): Shader {
    val stops=g.getJSONArray("stops")
    val colors=IntArray(stops.length()) {drawingColor(stops.getJSONObject(it).getString("color"),roleColors)}
    val positions=FloatArray(stops.length()) {stops.getJSONObject(it).getDouble("offset").toFloat()}
    fun f(key: String)=g.getDouble(key).toFloat()
    return if(g.getString("type")=="linear")LinearGradient(f("x"),f("y"),f("x2"),f("y2"),colors,positions,Shader.TileMode.CLAMP)
    else RadialGradient(f("x"),f("y"),f("radius"),colors,positions,Shader.TileMode.CLAMP)
}

private fun drawingColor(value: String,roleColors: Map<String,Int>) = roleColors[value] ?: android.graphics.Color.parseColor(value)

/** One shared byte budget covers drawing pixels and in-progress decodes across all hosts. */
internal class SurfaceBitmapMemory(private val limit: Int = ToolUiCatalog.MAX_IMAGE_BYTES) {
    private var used=0
    val ownedBytes get()=synchronized(this) {used}

    @Synchronized fun reserve(): Lease? {
        val remaining=limit-used
        if(remaining<4) return null
        used+=remaining
        return Lease(remaining)
    }

    inner class Lease internal constructor(val capacity: Int) {
        private var bytes=capacity

        fun keep(size: Int): Boolean = synchronized(this@SurfaceBitmapMemory) {
            if(size !in 1..bytes) return false
            used-=bytes-size
            bytes=size
            true
        }

        fun release() = synchronized(this@SurfaceBitmapMemory) {
            used-=bytes
            bytes=0
        }
    }

    companion object {val shared=SurfaceBitmapMemory()}
}

/** Cache ownership is local to one host generation. Call from its single native lifecycle coroutine. */
internal class SurfaceBitmapCache(private val memory: SurfaceBitmapMemory = SurfaceBitmapMemory.shared) {
    private val leases=mutableMapOf<String,SurfaceBitmapMemory.Lease>()
    private val failed=mutableSetOf<String>()
    private var closed=false
    var images by mutableStateOf<Map<String,Bitmap>>(emptyMap())
        private set

    suspend fun refresh(handles: Set<String>,available: suspend (String)->Boolean,load: suspend (String,Int)->Bitmap?,
        stillRequested: (String)->Boolean = {true}) {
        if(closed) return
        leases.keys.filter {it !in handles}.forEach(::drop)
        failed.retainAll(handles)
        for(handle in handles) {
            if(closed || !stillRequested(handle)) continue
            val permitted=available(handle)
            if(closed || !stillRequested(handle)) continue
            if(!permitted) {
                drop(handle);failed.remove(handle)
                continue
            }
            if(handle in leases || handle in failed) continue
            val lease=memory.reserve() ?: continue
            var decoded: Bitmap?=null
            var installed=false
            try {
                decoded=try {load(handle,lease.capacity)} catch(cancel: CancellationException) {throw cancel} catch(_: Exception) {null}
                currentCoroutineContext().ensureActive()
                if(!available(handle) || closed || !stillRequested(handle)) continue
                val image=decoded
                if(image==null || image.isRecycled || image.width !in 1..ToolUiCatalog.MAX_IMAGE_EDGE ||
                    image.height !in 1..ToolUiCatalog.MAX_IMAGE_EDGE || !lease.keep(image.allocationByteCount)) {
                    failed.add(handle)
                    continue
                }
                leases[handle]=lease
                images=images+(handle to image)
                installed=true
            } finally {
                if(!installed) {
                    decoded?.takeUnless(Bitmap::isRecycled)?.recycle()
                    lease.release()
                }
            }
        }
    }

    private fun drop(handle: String) {
        // A hardware display list may still own a reference to published pixels.
        images=images-handle
        leases.remove(handle)?.release()
    }

    fun close() {
        closed=true
        clear()
    }

    fun clear() {
        images=emptyMap()
        leases.values.forEach(SurfaceBitmapMemory.Lease::release)
        leases.clear();failed.clear()
    }
}
