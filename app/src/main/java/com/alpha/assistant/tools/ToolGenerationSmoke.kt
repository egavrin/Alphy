package com.alpha.assistant.tools

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
internal class ToolSmokeFailure(val event: String,val path: String,cause: Exception): Exception(null,cause) {
    val correction get()="Smoke event: $event. Path: $path. Return valid state, UI, drawing, and subscriptions for this event."
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
    private val checkUi: (suspend (String) -> Unit)? = null,
) {
    private data class Control(val event: JSONObject,val path: String)
    private inner class Branch(private val execute: suspend (String,String)->SandboxResult,initialUi: String) {
        var state=initialState
        var ui=initialUi
        var surfaces=JSONObject()
        var effectiveSurfaces=spec.surfaces
        val playback=ArtifactPlayback()
        var frames=emptySet<String>()
        var timers=emptyMap<String,Int>()
        var fenced=false
        suspend fun event(event: JSONObject,path: String): ToolPreview {
            checkActive()
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
                val merged=JSONObject(surfaces.toString())
                result.surfaces?.let {output -> output.keys().forEach {merged.put(it,output.getJSONObject(it))}}
                ArtifactProtocol.validateSurfaces(merged)
                if(type=="viewport") {
                    val surface=effectiveSurfaces.getValue(event.getString("surfaceId"))
                    effectiveSurfaces=effectiveSurfaces+(surface.id to surface.copy(width=event.getInt("width"),height=event.getInt("height")))
                }
                checkUi?.invoke(result.ui)
                state=result.state;ui=result.ui;surfaces=merged
                frames=result.frames ?: frames;timers=result.timers ?: timers
                playback.update(result.playback,type)
                fenced=result.effects.isNotEmpty() || result.records.length()>0
                return result
            } catch(cancel: CancellationException) {throw cancel}
              catch(failure: Exception) {throw ToolSmokeFailure(event.getString("type"),path,failure)}
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
            for((index,surface) in spec.surfaces.values.withIndex()) {
                val path="$.surfaces[$index]"
                if(surface.sizing=="viewport")event(GraphicsContract.viewport(surface),path)
                event(GraphicsContract.event("start",surfaceId=surface.id),path);subscriptions()
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
                for(direction in listOf("left","right")) event(GraphicsContract.event("control",direction=direction,surfaceId=surface.id),path)
                event(GraphicsContract.event("pause",surfaceId=surface.id),path)
                event(GraphicsContract.event("resume",surfaceId=surface.id),path);subscriptions()
                event(GraphicsContract.event("restart",surfaceId=surface.id),path);subscriptions()
                event(GraphicsContract.event("stop",surfaceId=surface.id),path)
            }
        }
    }
    private suspend fun <T> branch(initialUi: String=spec.ui.toString(),block: suspend Branch.()->T): T =
        if(spec.isSealedStandalone) Branch({ _, _ -> error("A sealed standalone app has no executor.") },initialUi).block()
        else sandbox.disposable {execute -> Branch(execute,initialUi).block()}
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
    private fun controls(root: JSONObject,state: JSONObject): List<Control> {
        val result=mutableListOf<Control>()
        fun add(id: String,value: Any?,path: String,key: String?,liveInput: Boolean=false) {
            val payload=if(key==null)value else JSONObject().put("key",key).put("value",value ?: JSONObject.NULL)
            val selected=try {
                if(liveInput) ToolUiCatalog.validateLiveInput(root,id,payload,state) else ToolUiCatalog.validateAction(root,id,payload,state)
            }catch(_: IllegalStateException) {return}
            val event=if(liveInput) liveInputEvent(id,payload)
                else JSONObject().put("type","action").put("actionId",id).put("value",payload ?: JSONObject.NULL)
            val request=when(selected.getString("type")) {
                "calendar" -> OwnedCalendarContract.request(selected)
                "medicine" -> MedicineContract.request(selected)
                "navigation" -> NavigationContract.request(selected)
                else -> null
            }
            request?.let {event.put(selected.getString("type"),JSONObject().put("capability",it.capability).put("args",it.args))}
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
