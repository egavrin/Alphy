package com.alpha.assistant.tools

import com.alpha.assistant.sandbox.SandboxContract
import org.json.JSONArray
import org.json.JSONObject

/** Closed drawing data only. Generated code has no native bindings. */
data class ToolSurface(val id: String,val width: Int,val height: Int,val x: Int?=null,val y: Int?=null,val sizing: String="fixed")
object GraphicsContract {
    const val FRAME_BYTES=32768
    /** Asynchronous submission-to-result watchdog, not script CPU time or a frame-rate guarantee. */
    const val EVALUATION_MS=100L
    class Budget {
        internal var commands=0
        internal var pairs=0
        internal var chars=0
        internal var segments=0
        internal var stops=0
        internal var bytes=0
        internal val bitmapHandles=mutableSetOf<String>()
    }
    private fun keys(o: JSONObject)=o.keys().asSequence().toSet()
    private fun number(o: JSONObject,key: String,min: Double = -8192.0,max: Double = 8192.0): Double = scalar(o.get(key),min,max)
    private fun scalar(value: Any,min: Double = -8192.0,max: Double = 8192.0): Double {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() in min..max) {"Drawing number exceeds its limit."}
        return value.toDouble()
    }
    fun declaration(o: JSONObject,schema: JSONObject): ToolSurface {
        val positioned=o.has("x") || o.has("y")
        require(keys(o)==setOf("id","width","height")+(if(positioned)setOf("x","y") else emptySet())+(if(o.has("sizing"))setOf("sizing") else emptySet()))
        require(!o.has("sizing") || o.get("sizing") in setOf("fixed","viewport"))
        require(o.get("id") is String && o.getString("id").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
        require(o.get("width") is Int && o.get("height") is Int)
        require(o.getInt("width") in 64..2048 && o.getInt("height") in 64..2048)
        if(positioned) {
            require(o.get("x") is Int && o.get("y") is Int)
            require(o.getInt("x") in 0..4096-o.getInt("width") && o.getInt("y") in 0..4096-o.getInt("height"))
        }
        return ToolSurface(o.getString("id"),o.getInt("width"),o.getInt("height"),if(positioned)o.getInt("x") else null,if(positioned)o.getInt("y") else null,o.optString("sizing","fixed"))
    }
    fun event(type: String,dt: Int = 0,x: Double = 0.0,y: Double = 0.0,phase: String = "move",direction: String = "left",pointer: Int = 0,surfaceId: String = "main"): JSONObject =
        JSONObject().put("type",type).apply {when(type) {
            "frame" -> {put("dt",dt);put("surfaceId",surfaceId)}
            "pointer" -> {put("surfaceId",surfaceId);put("x",x);put("y",y);put("phase",phase);put("pointer",pointer)}
            "control" -> {put("surfaceId",surfaceId);put("direction",direction)}
            "start","pause","resume","restart","stop" -> put("surfaceId",surfaceId)
        }}.also(::validateEvent)
    fun viewport(surface: ToolSurface): JSONObject = JSONObject().put("type","viewport").put("surfaceId",surface.id)
        .put("width",surface.width).put("height",surface.height).also(::validateEvent)
    fun validateSurfaceEvent(event: JSONObject,surfaces: Map<String,ToolSurface>) {
        validateEvent(event)
        if(!event.has("surfaceId"))return
        val surface=requireNotNull(surfaces[event.getString("surfaceId")]) {"Unknown surface ID."}
        when(event.getString("type")) {
            "viewport" -> require(surface.sizing=="viewport") {"Only viewport surfaces accept viewport events."}
            "pointer" -> require(event.getDouble("x")<=surface.width && event.getDouble("y")<=surface.height) {"Pointer coordinates exceed the effective surface."}
        }
    }
    fun validateEvent(o: JSONObject) {
        require(o.get("type") is String)
        val expected=when(o.getString("type")) {
            "start","pause","resume","restart","stop" -> setOf("type","surfaceId")
            "viewport" -> {require(o.get("width") is Int && o.getInt("width") in 64..2048 && o.get("height") is Int && o.getInt("height") in 64..2048);setOf("type","surfaceId","width","height")}
            "frame" -> {require(o.get("dt") is Int);number(o,"dt",0.0,50.0);setOf("type","surfaceId","dt")}
            "pointer" -> {number(o,"x",0.0,2048.0);number(o,"y",0.0,2048.0);require(o.get("pointer") is Int && o.getInt("pointer") in 0..1);require(o.get("phase") in setOf("down","move","up","cancel"));setOf("type","surfaceId","x","y","phase","pointer")}
            "control" -> {require(o.get("direction") in setOf("left","right"));setOf("type","surfaceId","direction")}
            "sensor" -> {
                require(o.get("sensor") == "accelerometer")
                for (key in listOf("sequence", "timestampMillis")) {
                    val value = o.get(key)
                    require((value is Int || value is Long) && (value as Number).toLong() > 0)
                }
                for (key in listOf("x", "y", "z")) number(o, key, -Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble())
                setOf("type", "sensor", "sequence", "timestampMillis", "x", "y", "z")
            }
            "timer" -> {require(o.get("timerId") is String && o.getString("timerId").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")));setOf("type","timerId")}
            else -> error("Unsupported artifact event")
        }
        require(keys(o)==expected)
        if(o.has("surfaceId"))require(o.get("surfaceId") is String && o.getString("surfaceId").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
    }
    private val hexColor=Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
    private val bitmapHandle=Regex("[0-9a-f]{64}")
    private fun color(value: Any) {
        require(value is String && (value in ToolUiCatalog.tones || value.matches(hexColor))) {"Unsupported drawing color."}
    }
    private val identity=doubleArrayOf(1.0,0.0,0.0,1.0,0.0,0.0)
    private fun transform(parent: DoubleArray,raw: JSONArray): DoubleArray {
        require(raw.length()==6)
        val m=DoubleArray(6) {scalar(raw.get(it),if(it<4)-1024.0 else -8192.0,if(it<4)1024.0 else 8192.0)}
        require(kotlin.math.abs(m[0]*m[3]-m[1]*m[2])>=0.00000001) {"Singular drawing transform."}
        val p=parent
        return doubleArrayOf(p[0]*m[0]+p[2]*m[1],p[1]*m[0]+p[3]*m[1],p[0]*m[2]+p[2]*m[3],p[1]*m[2]+p[3]*m[3],p[0]*m[4]+p[2]*m[5]+p[4],p[1]*m[4]+p[3]*m[5]+p[5]).also {a -> require(a.all(Double::isFinite) && a.take(4).all {it in -1024.0..1024.0}) {"Composed transform exceeds its limit."}}
    }
    fun frame(raw: String,eventType: String,budget: Budget=Budget()): JSONObject {
        budget.bytes+=raw.toByteArray(Charsets.UTF_8).size
        require(budget.bytes<=FRAME_BYTES)
        val o=SandboxContract.objectFrom(raw)
        require(keys(o)==setOf("commands"))
        fun point(x: Double,y: Double,m: DoubleArray,count: Boolean=true) {
            if(count)require(++budget.pairs<=2048)
            require((m[0]*x+m[2]*y+m[4]).let {it.isFinite() && it in -8192.0..8192.0})
            require((m[1]*x+m[3]*y+m[5]).let {it.isFinite() && it in -8192.0..8192.0})
        }
        fun bounds(x: Double,y: Double,w: Double,h: Double,m: DoubleArray) {
            point(x,y,m);point(x+w,y,m,false);point(x,y+h,m,false);point(x+w,y+h,m,false)
        }
        fun paint(c: JSONObject,m: DoubleArray): Set<String> {
            require(c.has("color") xor c.has("gradient")) {"Use exactly one color or gradient."}
            if(c.has("color")) {color(c.get("color"));return setOf("color")}
            val g=c.getJSONObject("gradient")
            val x=number(g,"x");val y=number(g,"y");point(x,y,m)
            val fields=when(g.getString("type")) {
                "linear" -> {val x2=number(g,"x2");val y2=number(g,"y2");require(x.toFloat()!=x2.toFloat() || y.toFloat()!=y2.toFloat());point(x2,y2,m);setOf("x2","y2")}
                "radial" -> {val r=number(g,"radius",0.1,2048.0);bounds(x-r,y-r,r*2,r*2,m);setOf("radius")}
                else -> error("Unsupported gradient")
            }
            require(keys(g)==setOf("type","x","y","stops")+fields)
            val stops=g.getJSONArray("stops");require(stops.length() in 2..32)
            budget.stops+=stops.length();require(budget.stops<=128)
            var previous=-1.0
            repeat(stops.length()) {
                val stop=stops.getJSONObject(it);require(keys(stop)==setOf("offset","color"))
                val offset=number(stop,"offset",0.0,1.0);require(offset>previous);previous=offset;color(stop.get("color"))
            }
            require(stops.getJSONObject(0).getDouble("offset")==0.0 && previous==1.0)
            return setOf("gradient")
        }
        fun visit(array: JSONArray,depth: Int,m: DoubleArray) {
            require(depth<=6 && array.length()<=512)
            repeat(array.length()) {i ->
                require(++budget.commands<=512)
                val c=array.getJSONObject(i);val type=c.getString("type")
                if(type=="group") {
                    require(keys(c)==setOf("type","children")+(if(c.has("transform"))setOf("transform") else emptySet()))
                    val next=if(c.has("transform"))transform(m,c.getJSONArray("transform")) else m
                    point(0.0,0.0,next,false)
                    visit(c.getJSONArray("children"),depth+1,next)
                    return@repeat
                }
                if(type=="bitmap") {
                    require(keys(c)==setOf("type","resourceId","label","x","y","width","height"))
                    val handle=c.get("resourceId")
                    require(handle is String && handle.matches(bitmapHandle)) {"Bitmap drawing needs an opaque image handle."}
                    budget.bitmapHandles.add(handle)
                    require(budget.bitmapHandles.size<=ToolUiCatalog.MAX_IMAGES) {"Drawing exceeds its image limit."}
                    val label=c.get("label")
                    require(label is String && label.isNotBlank()) {"Bitmap drawing needs an accessibility label."}
                    budget.chars+=label.length;require(budget.chars<=2048)
                    val width=number(c,"width",0.0);val height=number(c,"height",0.0)
                    require(width.toFloat()>0 && height.toFloat()>0)
                    bounds(number(c,"x"),number(c,"y"),width,height,m)
                    return@repeat
                }
                val fields=when(type) {
                    "rect","roundRect","arc" -> {
                        bounds(number(c,"x"),number(c,"y"),number(c,"width",0.0),number(c,"height",0.0),m)
                        val extra=when(type) {
                            "roundRect" -> {number(c,"radius",0.0,1024.0);setOf("radius")}
                            "arc" -> {number(c,"start",-360.0,360.0);number(c,"sweep",-360.0,360.0);number(c,"stroke",0.1,128.0);setOf("start","sweep","stroke")}
                            else -> emptySet()
                        }
                        setOf("type","x","y","width","height")+extra
                    }
                    "circle" -> {val r=number(c,"radius",0.0,2048.0);bounds(number(c,"x")-r,number(c,"y")-r,r*2,r*2,m);setOf("type","x","y","radius")}
                    "line" -> {point(number(c,"x"),number(c,"y"),m);point(number(c,"x2"),number(c,"y2"),m);number(c,"stroke",0.1,128.0);setOf("type","x","y","x2","y2","stroke")}
                    "polyline","polygon" -> {
                        val points=c.getJSONArray("points");require(points.length() in (if(type=="polygon")3 else 2)..2048)
                        repeat(points.length()) {val p=points.getJSONArray(it);require(p.length()==2);point(scalar(p.get(0)),scalar(p.get(1)),m)}
                        if(type=="polyline")number(c,"stroke",0.1,128.0)
                        setOf("type","points")+(if(type=="polyline")setOf("stroke") else emptySet())
                    }
                    "path" -> {
                        val segments=c.getJSONArray("segments");require(segments.length() in 2..512)
                        budget.segments+=segments.length();require(budget.segments<=512)
                        var open=false
                        repeat(segments.length()) {n ->
                            val s=segments.getJSONObject(n);val segment=s.getString("type")
                            require(n!=0 || segment=="move")
                            fun p(x: String,y: String) {point(number(s,x),number(s,y),m)}
                            val expected=when(segment) {
                                "move" -> {p("x","y");open=true;setOf("x","y")}
                                "line" -> {require(open);p("x","y");setOf("x","y")}
                                "quad" -> {require(open);p("cx","cy");p("x","y");setOf("cx","cy","x","y")}
                                "cubic" -> {require(open);p("cx1","cy1");p("cx2","cy2");p("x","y");setOf("cx1","cy1","cx2","cy2","x","y")}
                                "close" -> {require(open);open=false;emptySet()}
                                else -> error("Unsupported path segment")
                            }
                            require(keys(s)==setOf("type")+expected)
                        }
                        if(c.has("stroke"))number(c,"stroke",0.1,128.0)
                        setOf("type","segments")+(if(c.has("stroke"))setOf("stroke") else emptySet())
                    }
                    "text" -> {point(number(c,"x"),number(c,"y"),m);number(c,"size",1.0,128.0);require(c.get("text") is String);budget.chars+=c.getString("text").length;require(budget.chars<=2048);setOf("type","x","y","size","text")}
                    else -> error("Unsupported drawing command")
                }
                require(keys(c)==fields+paint(c,m))
            }
        }
        visit(o.getJSONArray("commands"),1,identity)
        return o
    }
}
