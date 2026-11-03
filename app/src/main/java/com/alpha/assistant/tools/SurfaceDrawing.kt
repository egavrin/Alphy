package com.alpha.assistant.tools

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject

internal fun surfaceLabels(commands: JSONArray): String=buildString {
    repeat(commands.length()) {val c=commands.getJSONObject(it);when(c.getString("type")) {"text" -> append(c.getString("text")).append(". ");"group" -> append(surfaceLabels(c.getJSONArray("children")))}}
}

/** Each surface and group restores the native canvas. Each leaf owns its paint. */
internal fun drawSurfaceFrame(canvas: Canvas,surface: ToolSurface,commands: JSONArray,width: Float,height: Float) {
    val saved=canvas.save()
    try {
        canvas.scale(width/surface.width,height/surface.height)
        canvas.clipRect(0f,0f,surface.width.toFloat(),surface.height.toFloat())
        drawSurface(canvas,commands)
    } finally {canvas.restoreToCount(saved)}
}
private fun drawSurface(canvas: Canvas,commands: JSONArray) {
    repeat(commands.length()) {index ->
        val c=commands.getJSONObject(index)
        if(c.getString("type")=="group") {
            val saved=canvas.save()
            try {
                c.optJSONArray("transform")?.let {m ->
                    canvas.concat(Matrix().apply {setValues(floatArrayOf(m.getDouble(0).toFloat(),m.getDouble(2).toFloat(),m.getDouble(4).toFloat(),m.getDouble(1).toFloat(),m.getDouble(3).toFloat(),m.getDouble(5).toFloat(),0f,0f,1f))})
                }
                drawSurface(canvas,c.getJSONArray("children"))
            } finally {canvas.restoreToCount(saved)}
            return@repeat
        }
        fun f(key: String)=c.getDouble(key).toFloat()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.FILL
            if(c.has("color"))color=android.graphics.Color.parseColor(c.getString("color")) else shader=gradient(c.getJSONObject("gradient"))
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
private fun gradient(g: JSONObject): Shader {
    val stops=g.getJSONArray("stops")
    val colors=IntArray(stops.length()) {android.graphics.Color.parseColor(stops.getJSONObject(it).getString("color"))}
    val positions=FloatArray(stops.length()) {stops.getJSONObject(it).getDouble("offset").toFloat()}
    fun f(key: String)=g.getDouble(key).toFloat()
    return if(g.getString("type")=="linear")LinearGradient(f("x"),f("y"),f("x2"),f("y2"),colors,positions,Shader.TileMode.CLAMP)
    else RadialGradient(f("x"),f("y"),f("radius"),colors,positions,Shader.TileMode.CLAMP)
}
