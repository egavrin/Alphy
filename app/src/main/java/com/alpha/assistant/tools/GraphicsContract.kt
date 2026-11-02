package com.alpha.assistant.tools

import com.alpha.assistant.sandbox.SandboxContract
import org.json.JSONArray
import org.json.JSONObject

/** Closed drawing data only. Generated code has no native bindings. */
data class ToolSurface(val width: Int,val height: Int,val source: String,val checkpointFields: Set<String>)
object GraphicsContract {
    const val FRAME_BYTES=32768
    const val EVALUATION_MS=25L
    private fun keys(o: JSONObject)=o.keys().asSequence().toSet()
    private fun number(o: JSONObject,key: String,min: Double = -8192.0,max: Double = 8192.0): Double {
        val n=o.get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() in min..max); return n.toDouble()
    }
    fun declaration(o: JSONObject,schema: JSONObject): ToolSurface {
        require(keys(o)==setOf("width","height","source","checkpointFields"))
        require(o.get("width") is Int && o.get("height") is Int)
        require(o.getInt("width") in 64..2048 && o.getInt("height") in 64..2048)
        require(o.get("source") is String && o.getString("source").isNotBlank() && o.getString("source").toByteArray().size <= 32768)
        val list=o.getJSONArray("checkpointFields"); require(list.length() <= 8)
        val fields=(0 until list.length()).map {require(list.get(it) is String);list.getString(it)}.toSet()
        require(fields.size==list.length() && fields.all {schema.opt(it) in setOf("number","string","boolean")})
        return ToolSurface(o.getInt("width"),o.getInt("height"),o.getString("source"),fields)
    }
    fun event(type: String,dt: Int = 0,x: Double = 0.0,y: Double = 0.0,phase: String = "move",direction: String = "left",pointer: Int = 0): JSONObject =
        JSONObject().put("type",type).apply {when(type) {
            "frame" -> put("dt",dt)
            "pointer" -> {put("x",x);put("y",y);put("phase",phase);put("pointer",pointer)}
            "control" -> put("direction",direction)
        }}.also(::validateEvent)
    fun validateEvent(o: JSONObject) {
        require(o.get("type") is String)
        val expected=when(o.getString("type")) {
            "start","pause","resume","restart","checkpoint" -> setOf("type")
            "frame" -> {require(o.get("dt") is Int);number(o,"dt",0.0,50.0);setOf("type","dt")}
            "pointer" -> {number(o,"x",0.0,2048.0);number(o,"y",0.0,2048.0);require(o.get("pointer") is Int && o.getInt("pointer") in 0..1);require(o.get("phase") in setOf("down","move","up","cancel"));setOf("type","x","y","phase","pointer")}
            "control" -> {require(o.get("direction") in setOf("left","right"));setOf("type","direction")}
            else -> error("Unsupported surface event")
        };require(keys(o)==expected)
    }
    fun frame(raw: String,eventType: String): JSONObject {
        require(raw.toByteArray().size <= FRAME_BYTES)
        val o=SandboxContract.objectFrom(raw)
        require(keys(o).all {it=="commands" || (eventType=="checkpoint" && it=="checkpoint")})
        if(o.has("checkpoint")) {val p=o.getJSONObject("checkpoint");require(p.toString().toByteArray().size <= 4096 && p.length() <= 8)}
        var count=0;var pairs=0;var chars=0
        fun visit(array: JSONArray,depth: Int) {
            require(depth <= 6 && array.length() <= 512)
            repeat(array.length()) { i ->
                require(++count <= 512);val c=array.getJSONObject(i);require(c.get("type") is String)
                val type=c.getString("type")
                val fields=when(type) {
                    "group" -> {visit(c.getJSONArray("children"),depth+1);setOf("type","children")}
                    "rect","roundRect","arc" -> {
                        number(c,"x");number(c,"y");pairs++;number(c,"width",0.0);number(c,"height",0.0)
                        val extra=when(type) {"roundRect" -> {number(c,"radius",0.0,1024.0);setOf("radius")};"arc" -> {number(c,"start",-360.0,360.0);number(c,"sweep",-360.0,360.0);number(c,"stroke",0.1,128.0);setOf("start","sweep","stroke")};else -> emptySet()}
                        setOf("type","x","y","width","height","color")+extra
                    }
                    "circle" -> {number(c,"x");number(c,"y");pairs++;number(c,"radius",0.0,2048.0);setOf("type","x","y","radius","color")}
                    "line" -> {for(k in listOf("x","y","x2","y2"))number(c,k);pairs+=2;number(c,"stroke",0.1,128.0);setOf("type","x","y","x2","y2","stroke","color")}
                    "polyline","polygon" -> {
                        val points=c.getJSONArray("points");require(points.length() in (if(type=="polygon") 3 else 2)..2048);pairs+=points.length()
                        repeat(points.length()) {val p=points.getJSONArray(it);require(p.length()==2);repeat(2) {n -> val v=p.get(n);require(v is Number && v.toDouble().isFinite() && v.toDouble() in -8192.0..8192.0)}}
                        if(type=="polyline")number(c,"stroke",0.1,128.0)
                        setOf("type","points","color")+(if(type=="polyline")setOf("stroke") else emptySet())
                    }
                    "text" -> {number(c,"x");number(c,"y");pairs++;number(c,"size",1.0,128.0);require(c.get("text") is String);chars+=c.getString("text").length;setOf("type","x","y","size","text","color")}
                    else -> error("Unsupported drawing command")
                }
                require(keys(c)==fields && pairs <= 2048 && chars <= 2048)
                if(type!="group")require(c.get("color") is String && c.getString("color").matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")))
            }
        };visit(o.getJSONArray("commands"),1);return o
    }
    fun patch(spec: ToolSpec,p: JSONObject): JSONObject {
        val surface=checkNotNull(spec.surface)
        require(p.toString().toByteArray().size <= 4096 && p.length() <= 8 && keys(p).all {it in surface.checkpointFields})
        p.keys().forEach {ToolValueSchema.validate(spec.schema.get(it),p.get(it))};return p
    }
}
