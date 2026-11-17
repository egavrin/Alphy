package com.alpha.assistant.tools

import com.alpha.assistant.tools.agent.AppStepError
import com.alpha.assistant.tools.agent.AppTarget
import com.alpha.assistant.capabilities.TiltContract
import com.alpha.assistant.cloud.CloudFailure

import com.alpha.assistant.capabilities.MedicineContract
import com.alpha.assistant.capabilities.NavigationContract
import com.alpha.assistant.capabilities.OwnedCalendarContract
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.sandbox.SandboxResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Only event kinds and structural indices can leave this local workflow. */
internal class ToolSmokeFailure(val event: String, val path: String, cause: Exception,
    val replay: List<String>? = null) : Exception(null, cause) {
    fun correction(rejectedPath: String = path) = "Smoke event: $event. Path: $rejectedPath. Return valid state, UI, drawing, and subscriptions for this event." +
        when ((cause as? CloudFailure)?.code) {
            "widget_timer_host" -> " Remove custom timer subscriptions from an app without a drawing surface."
            "javascript_script_error" -> " Read the event from input.event. Declare local variables before use."
            "javascript_invalid_result" -> if (event == "action")
                " If the action requests effects, check their capability schemas. Effect IDs must match [A-Za-z][A-Za-z0-9_]{0,47}." else ""
            else -> ""
        }
}

internal const val MAX_PROBE_EVENTS = 600
private const val PROBE_FRAME_MS = 50L

/** Replaces Date in a disposable probe isolate. It reads __alphyNow from the current evaluation. */
private const val DATE_SHIM = "(function(){const g=globalThis;const D=g.__alphyDate||(g.__alphyDate=g.Date);" +
    "function F(...a){if(!new.target)return new D(__alphyNow).toString();return a.length?new D(...a):new D(__alphyNow);}" +
    "F.prototype=D.prototype;F.now=function(){return __alphyNow;};F.UTC=D.UTC;F.parse=D.parse;g.Date=F;})();"

/** No store, broker, launcher, permission, grant, scheduler, or native result producer is available here. */
internal class ToolGenerationSmoke(
    private val sandbox: SandboxClient,
    private val spec: ToolSpec,
    private val id: String,
    private val revision: Int,
    private val initialState: String,
    private val checkActive: suspend ()->Unit,
    private val generation: ToolGenerationService,
    private val policy: ToolExecutionPolicy? = null,
    private val onInitialPreview: (suspend (ToolPreview) -> Unit)? = null,
    private val checkUi: (suspend (ToolPreview) -> Unit)? = null,
    private val observeReplay: ((List<String>) -> Unit)? = null,
) {
    private data class Control(val event: JSONObject,val path: String)
    internal inner class Branch(private val execute: suspend (String,String)->SandboxResult,initialUi: String, seedState: String) {
        var state=seedState
        var ui=initialUi
        var surfaces=JSONObject()
        var effectiveSurfaces=spec.surfaces
        val playback=ArtifactPlayback()
        var frames=emptySet<String>()
        var timers=emptyMap<String,Int>()
        var fenced=false
        var fencedEffects = emptyList<com.alpha.assistant.capabilities.CapabilityRequest>()
            private set
        private val replay = observeReplay?.let { mutableListOf<String>() }
        suspend fun event(event: JSONObject,path: String): ToolPreview {
            checkActive()
            replay?.add(event.toString())
            try {
                val type=event.getString("type")
                if(type !in setOf("preview","render","open","action",SandboxContract.LIVE_INPUT_EVENT)) {
                    GraphicsContract.validateSurfaceEvent(event,effectiveSurfaces)
                    if(type=="frame") require(event.getString("surfaceId") in frames)
                    if(type=="timer") require(event.getString("timerId") in timers)
                }
                val result=generation.executeEvent(spec,id,revision,state,event,ui,policy) { source,input ->
                    execute(source,input.toString())
                }
                if (spec.surfaces.isEmpty() && !result.timers.isNullOrEmpty()) {
                    throw ToolSmokeFailure(type, "$.timers[0]",
                        CloudFailure("widget_timer_host", "Custom timer subscriptions require a drawing surface."), replay?.toList())
                }
                val merged=JSONObject(surfaces.toString())
                result.surfaces?.let {output -> output.keys().forEach {merged.put(it,output.getJSONObject(it))}}
                ArtifactProtocol.validateSurfaces(merged)
                if(type=="viewport") {
                    val surface=effectiveSurfaces.getValue(event.getString("surfaceId"))
                    effectiveSurfaces=effectiveSurfaces+(surface.id to surface.copy(width=event.getInt("width"),height=event.getInt("height")))
                }
                checkUi?.invoke(result)
                state=result.state;ui=result.ui;surfaces=merged
                frames=result.frames ?: frames;timers=result.timers ?: timers
                playback.update(result.playback,type)
                fencedEffects = result.effects
                fenced=fencedEffects.isNotEmpty() || result.records.length()>0
                replay?.let { observeReplay?.invoke(it) }
                return result
            } catch(cancel: CancellationException) {throw cancel}
              catch(failure: ToolSmokeFailure) {throw failure}
              catch(failure: Exception) {throw ToolSmokeFailure(event.getString("type"),path,failure,replay?.toList())}
        }
        suspend fun subscriptions() {
            if(fenced || playback.value!="playing")return
            for((index,surface) in spec.surfaces.values.withIndex()) for(dt in listOf(0,16,50)) {
                if(playback.value=="playing" && surface.id in frames) event(GraphicsContract.event("frame",dt,surfaceId=surface.id),"$.surfaces[$index]")
            }
            // Each checkpoint exercises current subscriptions once, without a real clock or catch-up work.
            for((index,timer) in timers.keys.toList().withIndex()) if(playback.value=="playing" && timer in timers)
                event(JSONObject().put("type","timer").put("timerId",timer),"$.timers[$index]")
        }
        suspend fun surfaces() {
            for ((index, surface) in spec.surfaces.values.withIndex()) {
                if (surface.sizing == "viewport") event(GraphicsContract.viewport(surface), "$.surfaces[$index]")
            }
            val userStart = spec.hostControls.playback && ArtifactPlayback.primaryControl(playback.value) == "start"
            for ((index, surface) in spec.surfaces.values.withIndex()) {
                if (userStart || playback.automaticStart) {
                    event(GraphicsContract.event("start", surfaceId = surface.id), "$.surfaces[$index]")
                }
            }
            subscriptions()
            for((index,surface) in spec.surfaces.values.withIndex()) {
                val path="$.surfaces[$index]"
                if(surface.sizing=="viewport") {
                    val resized=surface.copy(width=640,height=360)
                    event(GraphicsContract.viewport(resized),path);subscriptions()
                    event(GraphicsContract.viewport(surface),path)
                }
                for(pointer in 0..1) for(phase in listOf("down","move","up","down","cancel")) {
                    val x=if(phase=="move") surface.width.toDouble() else surface.width/2.0
                    val y=if(phase=="move") surface.height.toDouble() else surface.height/2.0
                    event(GraphicsContract.event("pointer",x=x,y=y,phase=phase,pointer=pointer,surfaceId=surface.id),path)
                }
                if (index == 0 && TiltContract.ID in spec.capabilities) event(GraphicsContract.tilt(2.0, -1.0, 1, 1), "$.capabilities")
            }
            if (spec.hostControls.playback) {
                for (type in listOf("pause", "resume", "restart")) {
                    if (type == "restart" || ArtifactPlayback.primaryControl(playback.value) == type) {
                        for ((index, surface) in spec.surfaces.values.withIndex()) {
                            event(GraphicsContract.event(type, surfaceId = surface.id), "$.surfaces[$index]")
                        }
                        subscriptions()
                    }
                }
            }
        }
    }
    private suspend fun <T> branch(initialUi: String=spec.ui.toString(), seedState: String=initialState,
        block: suspend Branch.()->T): T =
        sandbox.disposable {execute -> Branch(execute,initialUi,seedState).block()}

    private suspend fun preview(): ToolPreview = branch {
        val result = event(JSONObject().put("type", "preview"), "$.ui")
        event(JSONObject().put("type", "render"), "$.ui")
        result
    }

    suspend fun run(): ToolPreview {
        val preview = preview()
        onInitialPreview?.let {
            checkActive()
            it(preview)
            checkActive()
        }
        val initialControls=branch(preview.ui) {
            event(JSONObject().put("type","open"),"$.ui")
            val controls=controls(JSONObject(ui),JSONObject(state))
            surfaces();subscriptions()
            controls
        }
        fun identity(control: Control) = control.event.getString("type") to control.event.getString("actionId")
        val initialIds=initialControls.map(::identity).toSet()
        fun currentControl(available: List<Control>, sample: Control, index: Int): Control =
            available.filter { identity(it) == identity(sample) }.getOrNull(index) ?: sample
        suspend fun Branch.reveal(sample: Control, index: Int): List<Control> {
            event(JSONObject().put("type","open"),"$.ui")
            val control = currentControl(controls(JSONObject(ui), JSONObject(state)), sample, index)
            event(control.event, control.path)
            // A request has no result in preview. Native work fences autonomous events in production too.
            if (fenced) return emptyList()
            event(JSONObject().put("type","render"), control.path)
            surfaces();subscriptions()
            return controls(JSONObject(ui), JSONObject(state)).filter { identity(it) !in initialIds }
        }
        for (samples in initialControls.groupBy(::identity).values) for ((index, sample) in samples.withIndex()) {
            val revealed = branch(preview.ui) { reveal(sample, index) }
            // Rebuild each sample in its own branch so a row key belongs to the state that receives it.
            for (targets in revealed.groupBy(::identity).values) for ((ordinal, target) in targets.withIndex()) branch(preview.ui) {
                val current = reveal(sample, index)
                if (!fenced) {
                    val next = currentControl(current, target, ordinal)
                    event(next.event, next.path)
                    if (!fenced) event(JSONObject().put("type","render"), next.path)
                }
            }
        }
        return preview
    }
    /**
     * Runs agent steps on a disposable copy of the app from [seedState].
     * A test clock replaces Date for the script and drives native timer displays.
     */
    internal suspend fun <T> probe(seedState: String = spec.initialState, block: suspend AppProbe.() -> T): T {
        var now = System.currentTimeMillis() / 1000 * 1000
        suspend fun start(branch: Branch): T {
            val probe = AppProbe(branch, { now }) { now += it }
            probe.open()
            return probe.block()
        }
        return sandbox.disposable { execute ->
            start(Branch({ source, input -> execute("const __alphyNow=$now;$DATE_SHIM\n$source", input) }, spec.ui.toString(), seedState))
        }
    }

    internal inner class AppProbe(private val branch: Branch, private val clock: () -> Long, private val advance: (Long) -> Unit) : AppTarget {
        override val ui: JSONObject get() = JSONObject(branch.ui)
        override val state: JSONObject get() = JSONObject(branch.state)
        override val surfaces: JSONObject get() = branch.surfaces
        override val nowMillis: Long get() = clock()
        override val surfaceIds: Set<String> get() = branch.effectiveSurfaces.keys
        override val surfaceSizes: Map<String, ToolSurface> get() = branch.effectiveSurfaces
        override var requested: List<String> = emptyList()
            private set

        internal suspend fun open() {
            branch.event(JSONObject().put("type", "open"), "$.ui")
            for ((index, surface) in spec.surfaces.values.withIndex()) {
                if (surface.sizing == "viewport") branch.event(GraphicsContract.viewport(surface), "$.surfaces[$index]")
            }
            val userStart = spec.hostControls.playback && ArtifactPlayback.primaryControl(branch.playback.value) == "start"
            if (!userStart && branch.playback.automaticStart) for ((index, surface) in spec.surfaces.values.withIndex()) {
                branch.event(GraphicsContract.event("start", surfaceId = surface.id), "$.surfaces[$index]")
            }
            settle()
        }

        // On the phone, an inline app gets tilt only after it requests the stream, and until it stops it.
        private var tiltStarted = false

        private fun settle() {
            requested = branch.fencedEffects.map { it.capability } + if (branch.fenced && branch.fencedEffects.isEmpty()) listOf("records") else emptyList()
            branch.fencedEffects.lastOrNull { it.capability == TiltContract.ID }?.let { tiltStarted = it.args.optBoolean("enabled") }
            // Native results are outside this probe. Later steps continue as if the request finished without a result event.
            branch.fenced = false
        }

        override suspend fun control(id: String, payload: Any?) {
            val root = ui
            val live = ToolUiCatalog.actionTarget(root, id)?.node?.let(ToolUiCatalog::isLiveInput) == true
            val event = controlEvent(root, state, id, payload, live)
            branch.event(event, "$.ui")
            settle()
        }

        override suspend fun playback(type: String) {
            for ((index, surface) in spec.surfaces.values.withIndex()) {
                branch.event(GraphicsContract.event(type, surfaceId = surface.id), "$.surfaces[$index]")
            }
            settle()
        }

        override suspend fun pointer(surfaceId: String, path: List<Pair<Double, Double>>) {
            val surface = checkNotNull(branch.effectiveSurfaces[surfaceId])
            for ((index, point) in path.withIndex()) {
                val phase = when (index) { 0 -> "down"; path.lastIndex -> "up"; else -> "move" }
                branch.event(GraphicsContract.event("pointer", x = point.first * surface.width, y = point.second * surface.height,
                    phase = phase, surfaceId = surfaceId), "$.surfaces[${spec.surfaces.keys.toList().indexOf(surfaceId)}]")
            }
            settle()
        }

        private var tiltSequence = 0L

        override suspend fun tilt(x: Double, y: Double) {
            if (TiltContract.ID !in spec.capabilities) throw AppStepError("Declare ${TiltContract.ID} in capabilities to receive tilt.")
            if (spec.presentation != "immersive" && !tiltStarted) throw AppStepError("On the phone, this app gets no tilt. " +
                "The host streams tilt to an immersive app while it plays. An inline app gets tilt only after a button requests ${TiltContract.ID} with enabled true.")
            branch.event(GraphicsContract.tilt(x, y, ++tiltSequence, clock()), "$.capabilities")
            settle()
        }

        /** Advances the clock in 50 ms ticks and delivers subscribed frames and timers. */
        override suspend fun wait(milliseconds: Long): Int {
            val maxEvents = MAX_PROBE_EVENTS
            var delivered = 0
            var elapsed = 0L
            val due = branch.timers.mapValues { it.value.toLong() }.toMutableMap()
            while (elapsed < milliseconds) {
                val tick = minOf(PROBE_FRAME_MS, milliseconds - elapsed)
                elapsed += tick
                advance(tick)
                if (branch.playback.value != "playing" || delivered >= maxEvents) continue
                for ((index, surface) in spec.surfaces.values.withIndex()) if (surface.id in branch.frames && delivered < maxEvents) {
                    branch.event(GraphicsContract.event("frame", tick.toInt(), surfaceId = surface.id), "$.surfaces[$index]")
                    delivered++
                }
                for ((timer, interval) in branch.timers) {
                    val next = due[timer] ?: interval.toLong()
                    if (elapsed >= next && delivered < maxEvents) {
                        branch.event(JSONObject().put("type", "timer").put("timerId", timer), "$.timers")
                        delivered++
                        due[timer] = next + interval
                    }
                }
            }
            settle()
            return delivered
        }
    }

    private fun controlEvent(root: JSONObject, state: JSONObject, id: String, payload: Any?, liveInput: Boolean): JSONObject {
        val selected = if (liveInput) ToolUiCatalog.validateLiveInput(root,id,payload,state)
            else ToolUiCatalog.validateAction(root,id,payload,state)
        val event = if (liveInput) liveInputEvent(id,payload)
            else JSONObject().put("type","action").put("actionId",id).put("value",payload ?: JSONObject.NULL)
        val request = when (selected.getString("type")) {
            "calendar" -> OwnedCalendarContract.request(selected)
            "medicine" -> MedicineContract.request(selected)
            "navigation" -> NavigationContract.request(selected)
            else -> null
        }
        request?.let { event.put(selected.getString("type"),JSONObject().put("capability",it.capability).put("args",it.args)) }
        return event
    }

    private fun controls(root: JSONObject,state: JSONObject): List<Control> {
        val result=mutableListOf<Control>()
        fun add(id: String,value: Any?,path: String,key: String?,liveInput: Boolean=false) {
            val payload=if(key==null)value else JSONObject().put("key",key).put("value",value ?: JSONObject.NULL)
            val event = try { controlEvent(root,state,id,payload,liveInput) }
                catch(_: IllegalStateException) { return }
            if(result.none {it.event.toString()==event.toString()})result+=Control(event,path)
        }
        fun visit(node: JSONObject,values: JSONObject,path: String,key: String?=null) {
            if(!ToolUiCatalog.visible(node,values) || !ToolUiCatalog.enabled(node,values))return
            if(node.optString("type")=="transition" && ToolUiCatalog.value(values,node.getString("bind"))!=true)return
            val type=node.getString("type")
            val stack=if(type=="routeStack") ToolUiCatalog.routeStack(node,values) else emptyList()
            val id=node.optString("action")
            if(id.isNotEmpty() && !(type == "timer" && node.has("foreground"))) {
                val current=if(node.has("bind"))ToolUiCatalog.value(values,node.getString("bind")) else null
                val samples: List<Any?> = when(type) {
                    "input","search" -> node.optInt("maxLength",2048).let {limit -> listOf((current as String).take(limit),"","Preview".take(limit))}
                    "numberInput","slider" -> ToolUiCatalog.bounds(node).let {(min,max)->listOf(current,min,max)}
                    "toggle","checkbox","chip" -> listOf(false,true)
                    "timeInput" -> listOf(current,0,1439)
                    "dateInput" -> listOf(current,"2000-02-29")
                    "rangeSlider" -> ToolUiCatalog.bounds(node).let {(min,max)->listOf(current,JSONArray().put(min).put(max),JSONArray().put(min).put(min))}
                    "dateRangeInput" -> listOf(current,JSONArray().put("2000-02-28").put("2000-02-29"))
                    "multiChoice" -> node.getJSONArray("options").let {options->listOf(current,JSONArray(),JSONArray().apply {repeat(options.length()){put(options.getJSONObject(it).get("value"))}})}
                    "pager" -> listOf(current,0,node.getJSONArray("children").length()-1)
                    "routeStack" -> if(stack.size>1) listOf(JSONArray(stack)) else emptyList()
                    in ToolUiCatalog.choiceTypes -> node.getJSONArray("options").let {listOf(it.getJSONObject(0).get("value"),it.getJSONObject(it.length()-1).get("value"),current)}
                    "list","table","groupedList","lazyList","lazyGrid" -> ToolUiCatalog.records(node,values).let {rows -> (0 until rows.length()).take(1).plus(if(rows.length()>1)listOf(rows.length()-1) else emptyList()).map {ToolUiCatalog.stableKey(rows.getJSONObject(it))}}
                    else -> listOf(null)
                }
                for(sample in samples)add(id,sample,path,key,ToolUiCatalog.isLiveInput(node))
            }
            node.optString("dismissAction").takeIf {it.isNotEmpty()}?.let {add(it,null,path,key)}
            ToolUiCatalog.interactionActions(node).forEach {(kind,id)->
                if(kind=="focus") {add(id,true,path,key);add(id,false,path,key)} else add(id,null,path,key)
            }
            if(type == "text") node.optJSONArray("spans")?.let {spans -> repeat(spans.length()) {index ->
                val span=spans.getJSONObject(index)
                if(span.has("action")) add(span.getString("action"),span.getString("url"),"$path.spans[$index]",key)
            }}
            if(type in setOf("list","table","groupedList","lazyList","lazyGrid")) {
                val rows=ToolUiCatalog.records(node,values)
                val indices=(0 until rows.length()).take(1).plus(if(rows.length()>1)listOf(rows.length()-1) else emptyList())
                for(index in indices) {
                    val row=rows.getJSONObject(index);val rowKey=ToolUiCatalog.stableKey(row)
                    node.optJSONObject("rowTemplate")?.let {visit(it,row,"$path.rowTemplate",rowKey)}
                    node.optJSONArray("rowActions")?.let {actions -> repeat(actions.length()) {i ->
                        if(ToolUiCatalog.rowActionEnabled(actions.getJSONObject(i),row))add(actions.getJSONObject(i).getString("action"),rowKey,"$path.rowActions[$i]",null)
                    }}
                }
            }
            node.optJSONArray("children")?.let {children ->
                when(type) {
                    "pager" -> {
                        val page=(ToolUiCatalog.value(values,node.getString("bind")) as Number).toInt()
                        visit(children.getJSONObject(page),values,"$path.children[$page]",key)
                    }
                    "routeStack" -> {
                        val routes=node.getJSONArray("routes")
                        val page=(0 until routes.length()).first {routes.getString(it)==stack.last()}
                        visit(children.getJSONObject(page),values,"$path.children[$page]",key)
                    }
                    else -> repeat(children.length()) {visit(children.getJSONObject(it),values,"$path.children[$it]",key)}
                }
            }
        }
        visit(root,state,"$.ui")
        return result
    }
}
