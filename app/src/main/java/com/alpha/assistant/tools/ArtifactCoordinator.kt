package com.alpha.assistant.tools

import com.alpha.assistant.sandbox.GraphicsSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Database identity and artifact identity share ordering across foreground, widgets, and automation engines. */
internal class ArtifactCoordinator private constructor() {
    private val mutex=Mutex()
    private val queued=AtomicInteger()
    @Volatile private var session: GraphicsSession?=null
    private var sessionOwner: Any?=null
    private var sessionGeneration=0L
    private var durable: ToolRecord?=null
    private var live: ToolRecord?=null
    private var surfaces=JSONObject()
    private var effectiveSurfaces=emptyMap<String,ToolSurface>()
    private var playback=ArtifactPlayback()
    private var frames=emptySet<String>()
    private var timers=emptyMap<String,Int>()
    private var sequence=0L
    private var flushJob: Job?=null
    @Volatile private var failure: String?=null
    private val dirty get()=live?.let {it.state!=durable?.state || it.ui!=durable?.ui} == true

    suspend fun <T> ordered(block: suspend ArtifactCoordinator.()->T): T {
        if(queued.incrementAndGet()>ArtifactProtocol.QUEUE_LIMIT) {
            queued.decrementAndGet();failure="The artifact event queue exceeded 32 entries. Reopen the mini-app.";session?.close()
            error(checkNotNull(failure))
        }
        try {return mutex.withLock {block()}} finally {queued.decrementAndGet()}
    }
    fun current(store: ToolStore,id: String,revision: Int?=null,stateVersion: Int?=null): ToolRecord {
        val saved=checkNotNull(store.tool(id)) {"The mini-app is unavailable."}
        check(!saved.archived) {"The mini-app is archived."}
        check(revision==null || saved.revision==revision) {"The mini-app revision changed. Reopen it."}
        check(stateVersion==null || saved.stateVersion==stateVersion) {"The mini-app data changed. Reopen it."}
        if(durable!=saved) {
            check(!dirty) {"Saved data changed before the local snapshot could commit. Reopen the mini-app."}
            if(durable?.revision!=null && durable?.revision!=saved.revision) {session?.close();session=null;resetOutput()}
            durable=saved;live=saved
        }
        return checkNotNull(live)
    }
    fun ensureAvailable() {check(failure==null) {checkNotNull(failure)}}
    private fun resetOutput() {surfaces=JSONObject();effectiveSurfaces=emptyMap();playback=ArtifactPlayback();frames=emptySet();timers=emptyMap()}
    fun prepareOutput(result: ToolPreview): ToolPreview {
        val update=result.surfaces ?: return result
        val merged=JSONObject(surfaces.toString())
        update.keys().forEach {merged.put(it,JSONObject(update.getJSONObject(it).toString()))}
        ArtifactProtocol.validateSurfaces(merged)
        return result.copy(surfaces=merged)
    }
    /** Surface maps are complete candidates validated before any corresponding durable commit. */
    private fun publish(next: ToolRecord,result: ToolPreview?=null,eventType: String?=null) {
        live=next;++sequence
        playback.update(result?.playback,eventType)
        result?.surfaces?.let {surfaces=it}
        result?.frames?.let {frames=it};result?.timers?.let {timers=it}
    }
    fun committed(next: ToolRecord,result: ToolPreview?=null) {durable=next;publish(next,result);flushJob?.cancel();flushJob=null}
    fun snapshot(store: ToolStore)=ArtifactSnapshot(checkNotNull(live),sequence,JSONObject(surfaces.toString()),frames.toSet(),timers.toMap(),store.artifactWorkPending(checkNotNull(live).id),sessionGeneration,playback.value)
    fun requireOwner(owner: Any,generation: Long?) {
        check(sessionOwner===owner && (generation==null || generation==sessionGeneration)) {"The artifact session belongs to another host. Reopen the mini-app."}
    }
    suspend fun open(store: ToolStore,generation: ToolGenerationService,expected: ToolRecord,owner: Any): ArtifactSnapshot {
        if(failure!=null && session==null) {durable=null;live=null;resetOutput();failure=null}
        val old=current(store,expected.id,expected.revision,expected.stateVersion)
        if(session!=null) {
            ensureAvailable();flush(store);flushJob?.cancel();flushJob=null
            sessionOwner=owner;++sessionGeneration
            return snapshot(store)
        }
        failure=null
        val spec=ToolSpec.parse(old.spec)
        effectiveSurfaces=spec.surfaces
        playback=ArtifactPlayback()
        val active=generation.retainedSession();session=active;sessionOwner=owner;++sessionGeneration
        try {
            val raw=active.open(spec.source,old.state,old.id,old.revision)
            check(raw.status=="OK") {raw.message}
            val result=prepareOutput(generation.validateResult(spec,old.state,JSONObject().put("type","open"),old.ui,checkNotNull(raw.json)))
            check(!store.artifactWorkPending(old.id) || result.state==old.state && result.ui==old.ui) {"A native result is pending. Open cannot change its state."}
            publish(old.copy(state=result.state,ui=result.ui),result);scheduleFlush(store)
            return snapshot(store)
        } catch(failed: Throwable) {active.close();session=null;failure=failed.message ?: "The artifact session stopped.";throw failed}
    }
    suspend fun evaluate(generation: ToolGenerationService,old: ToolRecord,event: JSONObject): ToolPreview {
        ensureAvailable()
        return try {prepareOutput(generation.evaluate(ToolSpec.parse(old.spec),old.id,old.revision,old.state,event,old.ui,session))}
        catch(failed: Throwable) {session?.close();session=null;failure=failed.message ?: "The artifact session stopped.";throw failed}
    }
    suspend fun event(store: ToolStore,generation: ToolGenerationService,id: String,revision: Int,event: JSONObject): ArtifactSnapshot {
        ensureAvailable();val old=current(store,id,revision)
        check(session!=null) {"The artifact session is closed. Reopen the mini-app."}
        GraphicsContract.validateSurfaceEvent(event,effectiveSurfaces)
        if(event.getString("type")=="frame")require(event.getString("surfaceId") in frames) {"The surface did not subscribe to frames."}
        if(event.getString("type")=="timer")require(event.getString("timerId") in timers) {"The timer did not subscribe."}
        if(store.artifactWorkPending(id))return snapshot(store)
        val result=evaluate(generation,old,event)
        currentCoroutineContext().ensureActive()
        if(event.getString("type")=="viewport") {
            val surface=effectiveSurfaces.getValue(event.getString("surfaceId"))
            effectiveSurfaces=effectiveSurfaces+(surface.id to surface.copy(width=event.getInt("width"),height=event.getInt("height")))
        }
        publish(old.copy(state=result.state,ui=result.ui),result,event.getString("type"))
        if(event.getString("type")=="frame" || event.getString("type")=="pointer" && event.getString("phase")=="move")scheduleFlush(store) else flush(store)
        return snapshot(store)
    }
    private fun scheduleFlush(store: ToolStore) {
        if(!dirty || flushJob!=null)return
        flushJob=scope.launch {
            delay(ArtifactProtocol.FLUSH_MS)
            mutex.withLock {
                flushJob=null
                try {flush(store)} catch(failed: Exception) {failure=failed.message ?: "The local snapshot could not commit.";session?.close();session=null}
            }
        }
    }
    fun flush(store: ToolStore) {
        if(!dirty)return
        val old=checkNotNull(durable);val next=checkNotNull(live).copy(stateVersion=old.stateVersion+1)
        check(!store.artifactWorkPending(old.id)) {"A native result must finish before local state can commit."}
        store.commitTool(next,old,false,autonomous=true);durable=next;live=next
    }
    fun close(store: ToolStore,revision: Int) {
        if(durable?.revision!=revision)return
        try {flush(store)} catch(failed: Exception) {failure=failed.message ?: "The local snapshot could not commit.";throw failed} finally {
            session?.close();session=null;sessionOwner=null;flushJob?.cancel();flushJob=null
            if(!dirty) {durable=null;live=null;resetOutput()}
        }
    }
    fun closeOwned(store: ToolStore,revision: Int,owner: Any,generation: Long?=null) {
        if(sessionOwner===owner && (generation==null || generation==sessionGeneration))close(store,revision)
    }
    companion object {
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val coordinators=ConcurrentHashMap<String,ArtifactCoordinator>()
        fun get(store: ToolStore,id: String)=coordinators.getOrPut(store.artifactIdentity+":"+id) {ArtifactCoordinator()}
        suspend fun closeOwned(store: ToolStore,owner: Any) {
            coordinators.filterKeys {it.startsWith(store.artifactIdentity+":")}.values.forEach {coordinator ->
                coordinator.ordered {durable?.let {closeOwned(store,it.revision,owner)}}
            }
        }
    }
}
