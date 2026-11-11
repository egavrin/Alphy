package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject

/** Protocol 3 has one handler, one state, and a closed native event/result envelope. */
object ArtifactProtocol {
    const val QUEUE_LIMIT=32
    const val FLUSH_MS=250L
    const val MAX_TIMERS=8
    data class Output(val surfaces: JSONObject?,val frames: Set<String>?,val timers: Map<String,Int>?,val playback: String?)
    fun validatePlayback(value: Any): String {
        require(value is String && value in setOf("ready","playing","paused","ended")) {"Unsupported playback status."}
        return value
    }
    fun output(spec: ToolSpec,json: JSONObject): Output {
        val surfaces=if(json.has("surfaces"))json.getJSONObject("surfaces") else null
        surfaces?.keys()?.forEach {id -> require(id in spec.surfaces) {"Unknown surface ID."} }
        surfaces?.let(::validateSurfaces)
        val frames=if(json.has("frames")) {
            val list=json.getJSONArray("frames");require(list.length()<=4)
            (0 until list.length()).map {require(list.get(it) is String);list.getString(it)}.also {
                require(it.distinct().size==it.size && it.all(spec.surfaces::containsKey))
            }.toSet()
        } else null
        val timers=if(json.has("timers")) {
            val list=json.getJSONArray("timers");require(list.length()<=MAX_TIMERS)
            (0 until list.length()).map {
                val timer=list.getJSONObject(it)
                require(timer.keys().asSequence().toSet()==setOf("id","intervalMs"))
                require(timer.get("id") is String && timer.getString("id").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
                require(timer.get("intervalMs") is Int && timer.getInt("intervalMs") in 16..60000)
                timer.getString("id") to timer.getInt("intervalMs")
            }.also {require(it.map(Pair<String,Int>::first).distinct().size==it.size)}.toMap()
        } else null
        val playback=if(json.has("playback"))validatePlayback(json.get("playback")) else null
        return Output(surfaces,frames,timers,playback)
    }
    /** Call this on merged retained surfaces before publication, as well as each returned surface map. */
    fun validateSurfaces(surfaces: JSONObject) {
        require(surfaces.toString().toByteArray(Charsets.UTF_8).size<=GraphicsContract.FRAME_BYTES)
        val budget=GraphicsContract.Budget()
        surfaces.keys().asSequence().forEachIndexed { index, id ->
            GraphicsContract.frame(surfaces.getJSONObject(id).toString(),"frame",budget,"$.surfaces[$index]")
        }
    }
    /** Reads handle IDs from validated drawing commands. This lookup does not resolve resources. */
    internal fun bitmapHandles(surfaces: JSONObject): Set<String> = buildSet {
        fun visit(commands: JSONArray) {
            repeat(commands.length()) {index ->
                val command=commands.getJSONObject(index)
                when(command.getString("type")) {
                    "bitmap" -> add(command.getString("resourceId"))
                    "group" -> visit(command.getJSONArray("children"))
                }
            }
        }
        surfaces.keys().forEach {visit(surfaces.getJSONObject(it).getJSONArray("commands"))}
    }
    fun modelGuidance()="""Artifact protocol 3 uses one source function body for every native control and surface. Optional presentation is exactly {mode:'app'|'immersive'} and defaults to app. Immersive viewport presentation draws across the entire app window. Native controls retract during play. A small accessible menu handle at the safe upper-right corner opens exit, App controls, and any declared movement or playback controls. Close the menu to retract the panel. The canvas dimensions never change when the panel opens or closes. Generated UI remains available in the App controls sheet. Optional surfaces is an array of at most four {id:'main',width:320,height:420}, with unique ASCII IDs and dimensions 64..2048. A surface can include paired integer x/y offsets, 0..4096 minus its dimension. Omitted offsets stack surfaces vertically. Later declarations paint above earlier declarations and receive overlapping pointer downs first. A surface declaration contains only its ID, dimensions, optional position, and optional sizing:'fixed'|'viewport' (default fixed). Viewport sizing requires exactly one unpositioned surface. Use immersive presentation and viewport sizing when the requested interaction needs the full app window. The host sends {type:'viewport',surfaceId,width,height} before start and after app window resize. Dimensions are logical units 64..2048. Use these dimensions for drawing and input geometry, preserve normalized positions and progress on resize, and handle viewport without effects or records. The host cancels owned pointers before viewport and resumes eligible frames after it. Optional hostControls is {"playback":true,"movement":["main"]}. Its optional playback Boolean defaults to false and requires a declared surface when true. It exposes one playback control group for the whole app and sends its events to all surfaces in declaration order. The optional movement array defaults to empty and contains unique declared surface IDs. It exposes Left and Right only for those surfaces. Declare a control only when the requested workflow needs it and implement its event. Touch input and automatic start work independently of hostControls. Optional output playback is exactly 'ready'|'playing'|'paused'|'ended'. Opt-in apps derive it from durable state on open and after changes. Omission retains the last explicit value within a session. A new session defaults to playing if open omits it. Until the first explicit playback output, native start/resume/restart set playing and pause/stop set paused. Once explicit playback appears, only validated outputs change it. Native controls read this same playback value: Start for ready, Pause for playing, Resume for paused, and Restart for all states. Automatic session start events apply only while playback is playing and the program has supplied no explicit playback value. An explicit value controls playback, including on reopen. Frames and timers require playing. Pointer input accepts ready/playing. Opening App controls or backgrounding temporarily suspends host input/frames without gameplay pause/resume events or changes to saved playback. Close flushes state. Use the same state for generated controls, drawing, and any declared host controls. Show task status from application state. Input is {artifactId,revision,state,event}. Native events also include {type:'open'}, {type:'start'|'pause'|'resume'|'restart'|'stop',surfaceId}, {type:'frame',surfaceId,dt:0..50}, {type:'pointer',surfaceId,pointer:0|1,phase:'down'|'move'|'up'|'cancel',x,y}, {type:'control',surfaceId,direction:'left'|'right'}, and {type:'timer',timerId}. Return {state,...}, optionally ui, surfaces:{main:{commands:[]}}, frames:['main'], timers:[{id:'clock',intervalMs:1000}], effects, records. Omitted surfaces/subscriptions retain their last validated value. Explicit empty subscriptions stop them. Native foreground frame subscriptions follow the display clock. Use dt in milliseconds for simulation rather than assuming a fixed frame rate. Timers are native, at most eight, interval 16..60000ms. Viewport, frames, pointers, timers, lifecycle and result events can update local state/UI/drawing but cannot request effects or records. Only an explicitly validated action control can request effects or records. Use state for all values shared with native actions. Global JavaScript values are transient and disappear when the session closes. Continuous snapshots flush within 250ms, and pause/close flush immediately. Actions, records and effects commit atomically. Pending native effects/results fence autonomous events until delivery or cancellation. Each drawing result is at most 32 KiB, 512 commands, depth 6, 2048 coordinate pairs and 2048 text characters. Continuous frame, pointer, timer, and control evaluations have a 100ms asynchronous watchdog. Other evaluations have 500ms. The monotonic watchdog starts before native submission and includes execution and asynchronous result completion. Timeout closes the isolate, rejects the result, and never retries the event. It is not a script CPU budget or a display-rate guarantee. Drawing commands: rect/roundRect/arc {x,y,width,height}, circle {x,y,radius}, line {x,y,x2,y2,stroke}, polyline/polygon {points:[[x,y],...]}, text {x,y,size,text}, path {segments:[...]}, group {children:[...]}. roundRect adds radius. arc adds start/sweep degrees and stroke. polyline requires stroke; path optionally uses stroke, otherwise it fills. Every leaf requires exactly one color or gradient. A solid color or gradient stop accepts default, muted, accent, success, warning, error, #RRGGBB, or #AARRGGBB. Use roles for text and the frame of a scene. Use hex values for objects that need a specific color. The canvas background uses the system surface color. Gradient is {type:'linear',x,y,x2,y2,stops:[{offset:0,color:'accent'},{offset:1,color:'muted'}]} or {type:'radial',x,y,radius,stops:[...]}. Stops are strictly increasing from 0 to 1, 2..32 per gradient and 128 total. Linear endpoints must differ. Path segments are {type:'move',x,y}, {type:'line',x,y}, {type:'quad',cx,cy,x,y}, {type:'cubic',cx1,cy1,cx2,cy2,x,y}, or {type:'close'}. Start each path or closed subpath with move. Paths total at most 512 segments. A group optionally has transform:[a,b,c,d,tx,ty], mapping (x,y) to (a*x+c*y+tx,b*x+d*y+ty). Transforms compose parent times child, require a nonsingular matrix, and have coefficients bounded to +/-1024 after composition. Positions and transformed points stay within +/-8192. Radius is 0..2048 (roundRect 0..1024); stroke is 0.1..128; text size is 1..128. Drawing limits aggregate across surfaces. All objects reject unknown fields. Surfaces clip drawing and groups restore canvas state. Bitmap is exactly {type:'bitmap',resourceId,label,x,y,width,height}. Use an existing opaque 64-character lowercase hexadecimal image handle supplied by this mini-app's native resource result. Label is nonempty accessible text. Width and height are positive. Bitmap commands share the drawing limits and use at most ${ToolUiCatalog.MAX_IMAGES} distinct image handles across all surfaces. Missing images show a placeholder. Do not supply a path, URI, URL, pixels, color, or gradient. No host APIs, imports, network, generated Kotlin, native callbacks, JavaScript timers or promises."""
}

data class ArtifactSnapshot(val tool: ToolRecord,val sequence: Long,val surfaces: JSONObject=JSONObject(),val frames: Set<String> = emptySet(),val timers: Map<String,Int> = emptyMap(),val fenced: Boolean=false,val sessionGeneration: Long=0,val playback: String="playing",val frameOwner: Boolean=true,val viewport: ToolSurface?=null,val controlVersion: Int?=null)

interface ArtifactUiHost {
    suspend fun open(): ArtifactSnapshot
    suspend fun startSession(): ArtifactSnapshot
    suspend fun snapshot(): ArtifactSnapshot
    suspend fun flush(): ArtifactSnapshot
    suspend fun event(event: JSONObject): ArtifactSnapshot
    suspend fun close()
    suspend fun drawingImageAvailable(sessionGeneration: Long,handle: String): Boolean = false
    /** Transfers a fresh bitmap to the drawing cache after the host checks access. */
    suspend fun loadDrawingImage(sessionGeneration: Long,handle: String,maxBytes: Int): android.graphics.Bitmap? = null
}

internal data class ArtifactHostControls(val playback: Boolean = false, val movement: Set<String> = emptySet()) {
    companion object {
        fun parse(controls: JSONObject, surfaces: Set<String>): ArtifactHostControls {
            require(controls.keys().asSequence().all { it in setOf("playback", "movement") })
            val playback = if (controls.has("playback")) controls.get("playback") as? Boolean ?: error("Expected Boolean playback.") else false
            require(!playback || surfaces.isNotEmpty())
            val movement = if (controls.has("movement")) controls.getJSONArray("movement") else JSONArray()
            require(movement.length() <= surfaces.size)
            val targets = buildSet {
                for (index in 0 until movement.length()) {
                    val id = movement.get(index)
                    require(id is String && id in surfaces && add(id))
                }
            }
            return ArtifactHostControls(playback, targets)
        }
    }
}

/** Explicit handler output is authoritative after the first supplied value in a session. */
internal class ArtifactPlayback {
    var value="playing"
        private set
    private var explicit=false
    val automaticStart get() = !explicit && value == "playing"
    fun update(output: String?,eventType: String?=null) {
        if(output!=null) {value=output;explicit=true}
        else if(!explicit) when(eventType) {
            "start","resume","restart" -> value="playing"
            "pause","stop" -> value="paused"
        }
    }

    companion object {
        fun primaryControl(value: String?) = when (value) {
            "ready" -> "start"
            "playing" -> "pause"
            "paused" -> "resume"
            else -> null
        }
    }
}
