package com.alpha.assistant.tools

import com.alpha.assistant.capabilities.MedicineContract
import com.alpha.assistant.capabilities.NavigationContract
import com.alpha.assistant.capabilities.OwnedCalendarContract
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.sandbox.SandboxResult
import com.alpha.assistant.tools.generation.GeometryScope
import com.alpha.assistant.tools.generation.framesSinceCapture
import com.alpha.assistant.tools.generation.GenerationBehavior
import com.alpha.assistant.tools.generation.GenerationBehavior.Binding
import com.alpha.assistant.tools.generation.GenerationBehavior.Kind
import com.alpha.assistant.tools.generation.GenerationBehavior.Step
import com.alpha.assistant.tools.generation.behaviorUiObservation
import com.alpha.assistant.tools.generation.sameToolJson
import com.alpha.assistant.tools.generation.execute
import com.alpha.assistant.tools.generation.visibleBehaviorNode
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Only event kinds and structural indices can leave this local workflow. */
internal class ToolSmokeFailure(val event: String, val path: String, cause: Exception,
    val replay: List<String>? = null) : Exception(null, cause) {
    val correction get()="Smoke event: $event. Path: $path. Return valid state, UI, drawing, and subscriptions for this event."
}

internal class BehaviorCheckFailure(val step: Int?, val reason: String,
    val bindingDiagnostic: BehaviorBindingDiagnostic? = null, val precedingEvent: String? = null) : IllegalStateException() {
    val detail get() = if (step == null) reason else "step ${step + 1}: $reason"
    val correction get() = (bindingDiagnostic?.let { "$detail. Binding diagnostic: ${it.report()}" } ?: detail) +
        precedingEvent?.let { ". Preceding $it" }.orEmpty()
}

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
    private val clockSample: com.alpha.assistant.tools.generation.PartClockSample? = null,
    private val observeReplay: ((List<String>) -> Unit)? = null,
) {
    private data class Control(val event: JSONObject,val path: String)
    private inner class Branch(private val execute: suspend (String,String)->SandboxResult,initialUi: String, seedState: String) {
        var state=seedState
        var ui=initialUi
        var surfaces=JSONObject()
        var effectiveSurfaces=spec.surfaces
        val playback=ArtifactPlayback()
        var frames=emptySet<String>()
        var timers=emptyMap<String,Int>()
        var fenced=false
        private var clock = clockSample ?: generation.partClockFor(spec)
        private val replay = observeReplay?.let { mutableListOf<String>() }
        fun displayState(): JSONObject = projectToolState(spec, JSONObject(state), clock)
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
                clock = clockSample ?: generation.partClockFor(spec)
                val result=generation.executeEvent(spec,id,revision,state,event,ui,policy,clock) { source,input ->
                    execute(source,input.toString())
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
                fenced=result.effects.isNotEmpty() || result.records.length()>0
                replay?.let { observeReplay?.invoke(it) }
                return result
            } catch(cancel: CancellationException) {throw cancel}
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
                if (surface.id in spec.hostControls.movement) {
                    for (direction in listOf("left", "right")) {
                        event(GraphicsContract.event("control", direction = direction, surfaceId = surface.id), path)
                    }
                }
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
    private suspend fun <T> branch(initialUi: String=spec.ui.toString(), seedState: String=initialState, block: suspend Branch.()->T): T =
        if(spec.isSealedStandalone) Branch({ _, _ -> error("A sealed standalone app has no executor.") },initialUi,seedState).block()
        else sandbox.disposable {execute -> Branch(execute,initialUi,seedState).block()}

    suspend fun checkBehavior(scenario: GenerationBehavior.Scenario, bindings: Map<String, Binding>, preview: ToolPreview,
        route: ToolGenerationRoute, nativeSlots: Set<String>,
        requireVisibleDrawing: Boolean = false, liveInputSlots: Set<String> = emptySet(),
        allowAssertionFailure: (Int) -> Boolean = { false }) {
        val freshUi = if (initialState == spec.initialState) preview.ui else branch(seedState = spec.initialState) {
            event(JSONObject().put("type", "preview"), "$.ui").ui
        }
        branch(initialUi = freshUi, seedState = spec.initialState) {
            var stepIndex: Int? = null
            var precedingEvent: String? = null
            var sent = 0
            fun recordEvent(kind: String, slot: String) {
                val slotNumber = scenario.slots.keys.indexOf(slot) + 1
                precedingEvent = "$kind at step ${checkNotNull(stepIndex) + 1}, slot $slotNumber"
            }
            fun fail(reason: String, rule: BehaviorFailureRule, slot: String? = null,
                nodeKind: String? = null, valueShape: BehaviorValueShape? = null,
                comparison: BehaviorComparison? = null, observation: BehaviorObservation? = null,
                frames: Int? = null, requiredChanges: Int? = null, observedChanges: Int? = null): Nothing {
                val bindingKind = slot?.let {
                    when (bindings[it]) {
                        is Binding.Id -> "id"
                        is Binding.Ui -> "ui"
                        is Binding.Drawing -> "drawing"
                        null -> "missing"
                    }
                }
                throw BehaviorCheckFailure(stepIndex, reason, BehaviorBindingDiagnostic(route, rule, stepIndex?.plus(1),
                    slot?.let { scenario.slots.keys.indexOf(it) + 1 }, scenario.slots[slot]?.kind,
                    slot?.let { it in nativeSlots }, bindingKind, nodeKind, valueShape,
                    (bindings[slot] as? Binding.Drawing)?.let { GeometryScope.of(it, threeScene = false) },
                    comparison, observation, frames, requiredChanges, observedChanges), precedingEvent)
            }
            fun target(slot: String) = bindings[slot] ?: fail("missing binding", BehaviorFailureRule.BINDING_MISSING, slot)
            fun targetId(slot: String) = (target(slot) as Binding.Id).value
            fun surface(id: String, slot: String) = effectiveSurfaces[id]
                ?: fail("missing surface", BehaviorFailureRule.SURFACE_UNAVAILABLE, slot)
            suspend fun send(event: JSONObject) {
                if (fenced) fail("native or record result required", BehaviorFailureRule.EXTERNAL_RESULT_REQUIRED)
                if (event.optString("type") != "frame" && ++sent > GenerationBehavior.MAX_EVENTS) fail("event limit reached", BehaviorFailureRule.EVENT_LIMIT)
                val path = stepIndex?.let { "\$.behavior.scenario.steps[$it]" } ?: "\$.behavior"
                this.event(event, path)
            }
            fun read(slot: String): Any {
                if (fenced) fail("native or record result required", BehaviorFailureRule.EXTERNAL_RESULT_REQUIRED, slot)
                val kind = scenario.slots.getValue(slot).kind
                return when (val binding = target(slot)) {
                    is Binding.Ui -> {
                        val values = displayState()
                        val node = visibleBehaviorNode(JSONObject(ui), values, binding.path) { rule, type ->
                            fail("visible output unavailable", rule, slot, type)
                        }
                        val type = node.getString("type")
                        checkNotNull(behaviorUiObservation(node, values, kind, binding.native) { rule, shape ->
                            fail("compatible visible output unavailable", rule, slot, type, shape)
                        })
                    }
                    is Binding.Drawing -> {
                        if (requireVisibleDrawing && canvasSurfaceIds(spec.ui).isNotEmpty() &&
                            binding.surface !in canvasSurfaceIds(JSONObject(ui), displayState())) {
                            fail("the required canvas is hidden", BehaviorFailureRule.DRAWING_UNAVAILABLE, slot)
                        }
                        val commands = surfaces.optJSONObject(binding.surface)?.optJSONArray("commands")
                            ?: fail("drawing output unavailable", BehaviorFailureRule.DRAWING_UNAVAILABLE, slot)
                        val geometry = observeSurfaceGeometry(surface(binding.surface, slot), commands, binding.path)
                            ?: fail("drawing selection unavailable", BehaviorFailureRule.DRAWING_UNAVAILABLE, slot)
                        if (requireVisibleDrawing && !geometry.hasInk) {
                            fail("the required drawing produces no visible pixels", BehaviorFailureRule.DRAWING_UNAVAILABLE, slot)
                        }
                        geometry
                    }
                    is Binding.Id -> fail("invalid observation binding", BehaviorFailureRule.BINDING_SHAPE, slot)
                }
            }
            fun numeric(value: Any?, slot: String) = (value as? Number)?.toDouble()
                ?: fail("numeric output unavailable", BehaviorFailureRule.SCALAR_SHAPE, slot, valueShape = BehaviorValueShape.of(value))
            send(JSONObject().put("type", "open"))
            for (surface in effectiveSurfaces.values) {
                if (surface.sizing == "viewport") send(GraphicsContract.viewport(surface))
                if (playback.automaticStart) send(GraphicsContract.event("start", surfaceId = surface.id))
            }
            scenario.execute(
                before = { index ->
                    stepIndex = index
                    checkActive()
                },
                read = ::read,
                perform = { step ->
                    when (step) {
                        is Step.Action -> {
                            val root = JSONObject(ui)
                            val action = when (val binding = target(step.slot)) {
                                is Binding.Id -> binding.value
                                is Binding.Ui -> {
                                    val node = visibleBehaviorNode(root, displayState(), binding.path) { rule, type ->
                                        fail("action binding path is unavailable", rule, step.slot, type)
                                    }
                                    node.optString("action").takeIf(String::isNotEmpty)
                                        ?: fail("action binding path is unavailable", BehaviorFailureRule.ACTION_UNAVAILABLE,
                                            step.slot, node.getString("type"))
                                }
                                is Binding.Drawing -> fail("invalid action binding", BehaviorFailureRule.BINDING_SHAPE, step.slot)
                            }
                            val value = step.value.takeUnless { it == JSONObject.NULL }
                            val control = ToolUiCatalog.action(root, action)
                            val liveInput = control?.let(ToolUiCatalog::isLiveInput) == true
                            if (step.slot in liveInputSlots && !liveInput) {
                                fail("the planned scene control requires live input", BehaviorFailureRule.ACTION_UNAVAILABLE, step.slot)
                            }
                            val event = try {
                                controlEvent(root, displayState(), action, value, liveInput)
                            } catch (_: IllegalArgumentException) { fail("action or input unavailable", BehaviorFailureRule.ACTION_UNAVAILABLE, step.slot) }
                              catch (_: IllegalStateException) { fail("action or input unavailable", BehaviorFailureRule.ACTION_UNAVAILABLE, step.slot) }
                            send(event)
                            recordEvent("action", step.slot)
                        }
                        is Step.Surface -> {
                            val surface = surface(targetId(step.slot), step.slot)
                            when (step.event) {
                                "viewport" -> {
                                    if (surface.sizing != "viewport") fail("viewport event unavailable", BehaviorFailureRule.SURFACE_UNAVAILABLE, step.slot)
                                    send(GraphicsContract.viewport(surface))
                                }
                                "pointer" -> {
                                    if (playback.value !in setOf("playing", "ready")) fail("pointer event unavailable", BehaviorFailureRule.SURFACE_UNAVAILABLE, step.slot)
                                    send(GraphicsContract.event("pointer", x = checkNotNull(step.x)*surface.width,
                                        y = checkNotNull(step.y)*surface.height, phase = checkNotNull(step.phase), surfaceId = surface.id))
                                }
                                "control" -> {
                                    if (surface.id !in spec.hostControls.movement) {
                                        fail("movement control unavailable", BehaviorFailureRule.SURFACE_UNAVAILABLE, step.slot)
                                    }
                                    send(GraphicsContract.event("control", direction = checkNotNull(step.direction), surfaceId = surface.id))
                                }
                                else -> {
                                    if (!spec.hostControls.playback || step.event != "restart" && ArtifactPlayback.primaryControl(playback.value) != step.event) {
                                        fail("playback control unavailable", BehaviorFailureRule.SURFACE_UNAVAILABLE, step.slot)
                                    }
                                    for (id in effectiveSurfaces.keys) send(GraphicsContract.event(step.event, surfaceId = id))
                                }
                            }
                            recordEvent("surface event", step.slot)
                        }
                        is Step.Advance -> {
                            val id = targetId(step.slot)
                            val isFrame = scenario.slots.getValue(step.slot).kind == Kind.SURFACE
                            if (step.observe != null && (target(step.observe) as Binding.Drawing).surface != id)
                                fail("frame and observation must use the same surface", BehaviorFailureRule.BINDING_SHAPE, step.observe)
                            var previous = step.observe?.let { read(it) as SurfaceGeometry }
                            var changes = 0
                            repeat(step.count) {
                                if (playback.value != "playing" || (if (isFrame) id !in frames else id !in timers))
                                    fail("active subscription unavailable", BehaviorFailureRule.SUBSCRIPTION_UNAVAILABLE, step.slot)
                                send(if (isFrame) GraphicsContract.event("frame", dt = step.frameMs ?: 16, surfaceId = id)
                                    else JSONObject().put("type", "timer").put("timerId", id))
                                step.observe?.let {
                                    val current = read(it) as SurfaceGeometry
                                    if (current.movedFrom(checkNotNull(previous))) changes++
                                    previous = current
                                }
                            }
                            recordEvent(if (isFrame) "frame advance" else "timer advance", step.slot)
                            if (changes < step.changes) fail("assertion failed, repeated geometry change required", BehaviorFailureRule.ASSERTION_FAILED,
                                step.observe, comparison = BehaviorComparison.MOTION, observation = BehaviorObservation.TOO_FEW_CHANGES,
                                frames = step.count, requiredChanges = step.changes, observedChanges = changes)
                        }
                        else -> error("The shared checker handles observations.")
                    }
                },
                completionFailed = { step, frames ->
                    val reason = if (frames == 0) "terminal result was already present" else "terminal result was not reached"
                    fail(reason, BehaviorFailureRule.ASSERTION_FAILED, step.observe, comparison = BehaviorComparison.EQUALS,
                        observation = BehaviorObservation.COMPARISON_FAILED, frames = frames)
                },
                assert = { index, step, current, expected ->
                        val equal = if (current is SurfaceGeometry && expected is SurfaceGeometry) {
                            current.width == expected.width && current.height == expected.height && current.visible == expected.visible
                        } else sameToolJson(current, expected)
                        val passed = when (step.test) {
                            "equals", "unchanged" -> equal
                            "changed" -> !equal
                            "greater" -> numeric(current, step.slot) > numeric(expected, step.slot)
                            "less" -> numeric(current, step.slot) < numeric(expected, step.slot)
                            else -> when (current) {
                                is String -> current.isNotBlank()
                                is SurfaceGeometry -> current.hasInk
                                is Number -> scenario.slots.getValue(step.slot).kind == Kind.COLLECTION && current.toInt() > 0
                                else -> false
                            }
                        }
                        if (!passed && !allowAssertionFailure(index)) fail("assertion failed (${step.test})", BehaviorFailureRule.ASSERTION_FAILED,
                            step.slot, valueShape = BehaviorValueShape.of(current), comparison = BehaviorComparison.from(step.test),
                            observation = if (current is SurfaceGeometry && expected is SurfaceGeometry) {
                                if (equal) BehaviorObservation.UNCHANGED else BehaviorObservation.CHANGED
                            } else BehaviorObservation.COMPARISON_FAILED, frames = scenario.framesSinceCapture(index, step))
                },
            )
        }

    }

    suspend fun run(): ToolPreview {
        val preview=branch {
            val result=event(JSONObject().put("type","preview"),"$.ui")
            event(JSONObject().put("type","render"),"$.ui")
            result
        }
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
        for(control in initialControls) {
            val revealed=branch(preview.ui) {
                event(JSONObject().put("type","open"),"$.ui")
                event(control.event,control.path)
                // A request has no result in preview. Native work fences autonomous events in production too.
                if(!fenced) {
                    event(JSONObject().put("type","render"),control.path)
                    surfaces();subscriptions()
                    controls(JSONObject(ui),JSONObject(state)).filter {identity(it) !in initialIds}
                } else emptyList()
            }
            // One bounded reveal step covers a newly opened modal or page without an unbounded action graph.
            for(next in revealed) branch(preview.ui) {
                event(JSONObject().put("type","open"),"$.ui")
                event(control.event,control.path)
                if(!fenced) {
                    event(next.event,next.path)
                    if(!fenced)event(JSONObject().put("type","render"),next.path)
                }
            }
        }
        return preview
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
