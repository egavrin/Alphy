package com.alpha.assistant.tools

import org.json.JSONObject
import kotlin.math.roundToInt

internal fun surfaceViewport(surface: ToolSurface,widthPx: Int,heightPx: Int,density: Float): ToolSurface {
    require(widthPx>0 && heightPx>0 && density.isFinite() && density>0)
    return surface.copy(width=(widthPx/density).roundToInt().coerceIn(64,2048),height=(heightPx/density).roundToInt().coerceIn(64,2048))
}

internal data class SurfacePlacement(val surface: ToolSurface,val x: Int,val y: Int)
internal fun surfacePlacements(surfaces: Collection<ToolSurface>): List<SurfacePlacement> {
    var bottom=0
    return surfaces.map {s ->
        SurfacePlacement(s,s.x ?: 0,s.y ?: bottom).also {bottom=maxOf(bottom,it.y+s.height)}
    }
}

/** Declaration order defines paint order. Ownership survives movement outside the selected surface. */
internal class SurfacePointerRouter(surfaces: List<ToolSurface>) {
    private var placements=surfacePlacements(surfaces)
    fun resize(surfaces: List<ToolSurface>) {check(owners.isEmpty());placements=surfacePlacements(surfaces)}
    private data class Owner(val placement: SurfacePlacement,val slot: Int,var x: Double,var y: Double)
    private val owners=linkedMapOf<Long,Owner>()
    fun route(id: Long,phase: String,x: Double,y: Double): JSONObject? {
        require(phase in setOf("down","move","up","cancel") && x.isFinite() && y.isFinite())
        if(phase=="down") {
            if(id in owners || owners.size>=2)return null
            val placement=placements.lastOrNull {x>=it.x && x<it.x+it.surface.width && y>=it.y && y<it.y+it.surface.height} ?: return null
            owners[id]=Owner(placement,if(owners.values.any {it.slot==0})1 else 0,0.0,0.0)
        }
        val owner=owners[id] ?: return null
        owner.x=(x-owner.placement.x).coerceIn(0.0,owner.placement.surface.width.toDouble())
        owner.y=(y-owner.placement.y).coerceIn(0.0,owner.placement.surface.height.toDouble())
        val event=GraphicsContract.event("pointer",x=owner.x,y=owner.y,phase=phase,pointer=owner.slot,surfaceId=owner.placement.surface.id)
        if(phase=="up" || phase=="cancel")owners.remove(id)
        return event
    }
    fun cancel(): List<JSONObject> = owners.values.map {GraphicsContract.event("pointer",x=it.x,y=it.y,phase="cancel",pointer=it.slot,surfaceId=it.placement.surface.id)}.also {owners.clear()}
}

/** Only consumed events advance this ledger. Producer ownership can include future queued input. */
internal class SurfaceInputState {
    private val pointers=linkedMapOf<Int,JSONObject>()
    private var paused=false
    fun playback(value: String): List<JSONObject> {
        paused=value in setOf("paused","ended")
        return if(paused)cancel() else emptyList()
    }
    fun events(event: JSONObject): List<JSONObject> = when(event.getString("type")) {
        "pause","stop" -> {paused=true;cancel()+event}
        "restart","viewport" -> cancel()+event
        "resume" -> {paused=false;listOf(event)}
        "pointer" -> {
            val slot=event.getInt("pointer")
            val previous=pointers[slot]
            val phase=event.getString("phase")
            if(paused || phase=="down" && previous!=null || phase!="down" && previous?.getString("surfaceId")!=event.getString("surfaceId"))emptyList()
            else {
                if(phase=="up" || phase=="cancel")pointers.remove(slot) else pointers[slot]=JSONObject(event.toString())
                listOf(event)
            }
        }
        else -> listOf(event)
    }
    fun cancel(): List<JSONObject> = pointers.values.map {JSONObject(it.toString()).put("phase","cancel")}.also {pointers.clear()}
}

/** withFrameNanos supplies display ticks. Work between ticks is never replayed. */
internal class SurfaceFrameClock {
    private var previous: Long?=null
    fun tick(now: Long): Int {
        val dt=previous?.let {((now-it)/1_000_000).coerceIn(0,50).toInt()} ?: 0
        previous=now
        return dt
    }
    fun reset() {previous=null}
}
