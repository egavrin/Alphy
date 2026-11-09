package com.alpha.assistant.tools

import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.sandbox.GraphicsSession
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.sandbox.SandboxResult
import com.alpha.assistant.tools.generation.PartClockSample
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Database identity and artifact identity share ordering across foreground, widgets, and automation engines. */
internal class ArtifactCoordinator private constructor() {
    private val mutex=Mutex()
    private val queued=AtomicInteger()
    @Volatile private var session: GraphicsSession?=null
    private class Host(val owner: Any,val generation: ToolGenerationService,val nonGraphics: Boolean=false,
        var viewport: ToolSurface?=null,var input: LiveInputLease?=null)
    private val hosts=linkedMapOf<Long,Host>()
    private var sessionGeneration=0L
    private var durable: ToolRecord?=null
    private var live: ToolRecord?=null
    private var surfaces=JSONObject()
    private var effectiveSurfaces=emptyMap<String,ToolSurface>()
    private var viewportApplied=false
    private val startedSurfaces=mutableSetOf<String>()
    private var playback=ArtifactPlayback()
    private var frames=emptySet<String>()
    private var timers=emptyMap<String,Int>()
    private var sequence=0L
    private var controlVersion=0
    private var flushJob: Job?=null
    @Volatile private var failure: String?=null
    private var liveRecoveryRequired=false
    private val dirty get()=live?.let {it.state!=durable?.state || it.ui!=durable?.ui} == true
    private val liveInputs=ArtifactLiveInputQueue()
    private val liveFrameScheduled=AtomicBoolean()
    private data class InputView(val record: ToolRecord,val saved: ToolRecord,val version: LiveInputVersion,val leases: Set<LiveInputLease>)
    private var inputView: InputView?=null

    private fun updateInputView()=synchronized(liveInputs) {
        val record=live
        val saved=durable
        inputView=if(record==null || saved==null) null else InputView(record,saved,inputVersion(record),hosts.values.mapNotNull {it.input}.toSet())
    }

    private fun inputVersion(record: ToolRecord)=LiveInputVersion(record.revision,controlVersion,controlVersion.toLong())

    /** Checks an existing drawing registration without dispatching or refreshing an artifact event. */
    fun drawingHostCurrent(id: String,revision: Int,generation: Long): Boolean = synchronized(liveInputs) {
        val view=inputView ?: return false
        val retained=session ?: return false
        failure==null && retained.failureStatus==null && view.record.id==id && view.record.revision==revision &&
            view.leases.any {it.generation==generation}
    }

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
            val previous=durable
            if(previous!=null && (previous.revision!=saved.revision || previous.spec!=saved.spec)) invalidate()
            durable=saved;live=saved
            ++controlVersion
            updateInputView()
        }
        return checkNotNull(live)
    }
    fun ensureAvailable() {check(failure==null) {checkNotNull(failure)}}
    private fun resetOutput() {
        surfaces=JSONObject();effectiveSurfaces=emptyMap();viewportApplied=false;startedSurfaces.clear()
        playback=ArtifactPlayback();frames=emptySet();timers=emptyMap()
    }
    fun prepareOutput(result: ToolPreview): ToolPreview {
        val update=result.surfaces ?: return result
        val merged=JSONObject(surfaces.toString())
        update.keys().forEach {merged.put(it,JSONObject(update.getJSONObject(it).toString()))}
        ArtifactProtocol.validateSurfaces(merged)
        return result.copy(surfaces=merged)
    }
    /** Surface maps are complete candidates validated before any corresponding durable commit. */
    private fun publish(next: ToolRecord,result: ToolPreview?=null,eventType: String?=null) {
        if (eventType != "frame" && eventType != "timer" ||
            live?.let { ToolUiCatalog.liveInputContractChanged(it.ui, next.ui) } == true) ++controlVersion
        live=next;++sequence
        playback.update(result?.playback,eventType)
        result?.surfaces?.let {surfaces=it}
        result?.frames?.let {frames=it};result?.timers?.let {timers=it}
        updateInputView()
    }
    fun committed(next: ToolRecord,result: ToolPreview?=null) {durable=next;publish(next,result);flushJob?.cancel();flushJob=null}
    private fun effectiveViewport() = effectiveSurfaces.values.singleOrNull()?.takeIf { it.sizing=="viewport" }
    fun snapshot(store: ToolStore)=snapshot(store.artifactWorkPending(checkNotNull(live).id))
    private fun snapshot(fenced: Boolean)=ArtifactSnapshot(checkNotNull(live),sequence,JSONObject(surfaces.toString()),
        frames.toSet(),timers.toMap(),fenced,sessionGeneration,playback.value,viewport=effectiveViewport(),controlVersion=controlVersion)

    private fun host(owner: Any,generation: Long?) = checkNotNull(
        hosts.entries.lastOrNull { it.value.owner===owner && (generation==null || it.key==generation) }
    ) { "The artifact host is closed. Reopen the mini-app." }

    fun hostSnapshot(store: ToolStore,owner: Any,generation: Long?): ArtifactSnapshot {
        val registration=host(owner,generation)
        return snapshot(store).copy(sessionGeneration=registration.key,frameOwner=registration.key==hosts.keys.last())
    }

    fun attachLiveInput(store: ToolStore,id: String,revision: Int,generation: Long): LiveInputLease {
        ensureAvailable();current(store,id,revision)
        val host=checkNotNull(hosts[generation]) {"The artifact host is closed. Reopen the mini-app."}
        check(host.input==null) {"The artifact host already owns live input."}
        return LiveInputLease(generation).also {host.input=it;updateInputView()}
    }

    fun offerLiveInput(store: ToolStore,lease: LiveInputLease,expected: ArtifactSnapshot,actionId: String,value: Any?,
        finished: Boolean,active: ()->Boolean): LiveInputSubmission {
        if(!active() || expected.sessionGeneration!=lease.generation)
            return LiveInputSubmission.Rejected(LiveInputRejection.Inactive)
        val encoded=try {liveInputEvent(actionId,value).toString()} catch(_: Exception) {
            return LiveInputSubmission.Rejected(LiveInputRejection.Unavailable)
        }
        val event=JSONObject(encoded)
        val row=event.optJSONObject("value")?.getString("key")
        val key=LiveInputKey(lease,actionId,row)
        repeat(2) {
            val view=synchronized(liveInputs) {inputView}
            if(view==null || lease !in view.leases) return LiveInputSubmission.Rejected(LiveInputRejection.Inactive)
            val id=view.record.id
            val saved=store.tool(id)
            val unavailable=liveUnavailable(store,view.record,event)
            val admitted=synchronized(liveInputs) {
                when {
                    !active() -> LiveInputSubmission.Rejected(LiveInputRejection.Inactive)
                    inputView!==view -> null
                    expected.tool.id!=id || saved!=view.saved -> LiveInputSubmission.Rejected(LiveInputRejection.Conflict)
                    unavailable!=null -> LiveInputSubmission.Rejected(unavailable.reason)
                    else -> liveInputs.submit(key,encoded,expected.liveInputVersion,view.version,finished,active)
                }
            }
            if(admitted!=null) return admitted
        }
        return LiveInputSubmission.Rejected(LiveInputRejection.Conflict)
    }

    fun cancelLiveInput(lease: LiveInputLease) = liveInputs.cancel(lease)
    fun pendingFinalLiveInput()=liveInputs.finalReceipts()

    fun liveInputFrame(store: ToolStore,id: String,frameTimeNanos: Long) {
        if(!liveInputs.needsFrame || !liveFrameScheduled.compareAndSet(false,true)) return
        scope.launch {
            val delivery=try {liveInputs.takeFrame(frameTimeNanos)} finally {liveFrameScheduled.set(false)}
            delivery?.let {deliverLiveInput(store,id,it)}
        }
    }

    private suspend fun deliverLiveInput(store: ToolStore,id: String,delivery: ArtifactLiveInputQueue.Delivery) {
        val result=try {
            ordered {
                val lease=delivery.key.lease
                val host=hosts[lease.generation]?.takeIf {it.input===lease}
                val expected=liveInputs.expected(delivery)
                if(host==null || expected==null || !delivery.active()) return@ordered rejectLive(LiveInputRejection.Inactive)
                val old=try {current(store,id,expected.revision)} catch(_: IllegalStateException) {
                    return@ordered rejectLive(LiveInputRejection.Conflict)
                }
                if(inputVersion(old)!=expected) return@ordered rejectLive(LiveInputRejection.Conflict)
                val event=JSONObject(delivery.event)
                val unavailable=liveUnavailable(store,old,event)
                if(unavailable!=null) return@ordered unavailable
                evaluateLiveInput(store,host.generation,old,event,
                    checkDispatch={liveDispatchFailure(store,id) {delivery.active() && liveInputs.expected(delivery)!=null}}) {output ->
                    synchronized(liveInputs) {
                        when {
                            !delivery.active() || liveInputs.expected(delivery)==null -> rejectLive(LiveInputRejection.Inactive)
                            store.tool(id)!=durable -> rejectLive(LiveInputRejection.Conflict)
                            store.artifactWorkPending(id) -> rejectLive(LiveInputRejection.Unavailable)
                            else -> {
                                commitLiveInput(store,old,output)
                                val accepted=snapshot(fenced=false).copy(sessionGeneration=lease.generation,frameOwner=lease.generation==hosts.keys.last())
                                LiveInputResult.Accepted(accepted).also {
                                    liveInputs.complete(delivery,it)
                                }
                            }
                        }
                    }
                }
            }
        } catch(failed: Exception) {
            val reason=liveFailure(failed)
            if(failure!=null) synchronized(liveInputs) {inputView?.leases.orEmpty().forEach {liveInputs.cancel(it,reason)}}
            rejectLive(reason)
        }
        liveInputs.complete(delivery,result)
    }

    suspend fun applyLiveInput(store: ToolStore,generation: ToolGenerationService,expected: ToolRecord,actionId: String,value: Any?,
        active: ()->Boolean): LiveInputResult = try {
        ordered {
            if(!active()) return@ordered rejectLive(LiveInputRejection.Inactive)
            val old=try {current(store,expected.id,expected.revision,expected.stateVersion)} catch(_: IllegalStateException) {
                return@ordered rejectLive(LiveInputRejection.Conflict)
            }
            if(old!=expected || dirty) return@ordered rejectLive(LiveInputRejection.Conflict)
            val event=try {liveInputEvent(actionId,value)} catch(_: Exception) {
                return@ordered rejectLive(LiveInputRejection.Unavailable)
            }
            liveUnavailable(store,old,event)?.let {return@ordered it}
            val caller=currentCoroutineContext()[Job]
            withContext(NonCancellable) {
                evaluateLiveInput(store,generation,old,event,
                    checkDispatch={liveDispatchFailure(store,old.id) {caller?.isActive!=false && active()}}) {output ->
                    when {
                        caller?.isActive==false || !active() -> rejectLive(LiveInputRejection.Inactive)
                        store.tool(old.id)!=durable -> rejectLive(LiveInputRejection.Conflict)
                        store.artifactWorkPending(old.id) -> rejectLive(LiveInputRejection.Unavailable)
                        else -> {
                            commitLiveInput(store,old,output)
                            LiveInputResult.Accepted(snapshot(fenced=false))
                        }
                    }
                }
            }
        }
    } catch(cancel: CancellationException) {throw cancel}
      catch(failed: Exception) {rejectLive(liveFailure(failed))}

    private fun liveUnavailable(store: ToolStore,record: ToolRecord,event: JSONObject): LiveInputResult.Rejected? {
        if(failure!=null || session?.failureStatus!=null || store.artifactWorkPending(record.id)) return rejectLive(LiveInputRejection.Unavailable)
        return try {validateLiveControl(record,event);null} catch(_: Exception) {rejectLive(LiveInputRejection.Unavailable)}
    }

    private fun validateLiveControl(record: ToolRecord,event: JSONObject) {
        val spec=ToolSpec.parsePersisted(record.spec)
        val ui=JSONObject(record.ui)
        val state=JSONObject(record.state)
        val composition=spec.composition
        val actionId = event.getString("actionId")
        val fill = composition != null && ToolUiCatalog.action(composition.fillUi(ui), actionId) != null
        ToolUiCatalog.validateLiveInput(if (fill) checkNotNull(composition).fillUi(ui) else ui, actionId,
            event.get("value"), if (fill) checkNotNull(composition).fillState(state) else state)
    }

    private fun commitLiveInput(store: ToolStore,old: ToolRecord,output: ToolPreview) {
        val saved=checkNotNull(durable)
        val next=old.copy(state=output.state,ui=output.ui,stateVersion=saved.stateVersion+1)
        store.commitTool(next,saved,false,autonomous=true)
        committed(next,output)
    }

    private suspend fun evaluateLiveInput(store: ToolStore,generation: ToolGenerationService,old: ToolRecord,event: JSONObject,
        checkDispatch: ()->LiveInputRejection?,accept: (ToolPreview)->LiveInputResult): LiveInputResult {
        val retained=session
        var dispatched=false
        val result=try {
            val output=evaluate(generation,old,event) {
                checkDispatch()?.let {throw LiveDispatchStopped(it)}
                dispatched=true
            }
            currentCoroutineContext().ensureActive()
            accept(output)
        } catch(failed: Exception) {
            rejectLive(liveFailure(failed))
        }
        // An uncommitted handler may have changed retained JavaScript globals.
        if(result is LiveInputResult.Rejected && retained!=null && dispatched) interruptLiveSession(store,retained,result.reason)
        return result
    }

    private fun interruptLiveSession(store: ToolStore,retained: GraphicsSession,reason: LiveInputRejection) {
        try {flush(store)} catch(_: Exception) {liveRecoveryRequired=dirty}
        finally {
            retained.close();session=null
            failure=failure ?: "An uncommitted input stopped the mini-app session. Reopen the mini-app."
            hosts.values.forEach {it.input?.let {lease -> liveInputs.cancel(lease,reason)}}
        }
    }

    private fun rejectLive(reason: LiveInputRejection)=LiveInputResult.Rejected(reason)
    private class LiveDispatchStopped(val reason: LiveInputRejection): CancellationException("The live input cannot dispatch.")

    private fun liveDispatchFailure(store: ToolStore,id: String,active: ()->Boolean)=when {
        !active() -> LiveInputRejection.Inactive
        store.tool(id)!=durable -> LiveInputRejection.Conflict
        store.artifactWorkPending(id) -> LiveInputRejection.Unavailable
        else -> null
    }

    private fun liveFailure(failure: Exception)=when {
        failure is LiveDispatchStopped -> failure.reason
        failure is CancellationException -> LiveInputRejection.Inactive
        failure is CloudFailure && failure.code=="javascript_timeout" -> LiveInputRejection.TimedOut
        failure is CloudFailure && failure.code=="javascript_busy" -> LiveInputRejection.Unavailable
        else -> LiveInputRejection.Failed
    }

    private suspend fun reconcileViewport(store: ToolStore) {
        val active=hosts.values.lastOrNull() ?: return
        val requested=active.viewport ?: return
        if(viewportApplied && requested==effectiveViewport()) return
        val tool=checkNotNull(live)
        event(store,active.generation,tool.id,tool.revision,GraphicsContract.viewport(requested))
    }

    suspend fun refreshHostSnapshot(store: ToolStore,owner: Any,generation: Long?): ArtifactSnapshot {
        if(host(owner,generation).key==hosts.keys.last()) reconcileViewport(store)
        return hostSnapshot(store,owner,generation)
    }

    suspend fun startHostSession(store: ToolStore,id: String,revision: Int,owner: Any,generation: Long): ArtifactSnapshot {
        val registration=host(owner,generation)
        if(registration.key==hosts.keys.last()) {
            reconcileViewport(store)
            for(surface in effectiveSurfaces.keys - startedSurfaces) {
                if (playback.automaticStart) {
                    val result=event(store,registration.value.generation,id,revision,GraphicsContract.event("start",surfaceId=surface))
                    if(result.fenced) break
                }
                startedSurfaces.add(surface)
            }
        }
        return hostSnapshot(store,owner,generation)
    }

    suspend fun hostEvent(store: ToolStore,id: String,revision: Int,owner: Any,generation: Long?,raw: JSONObject): ArtifactSnapshot {
        val registration=host(owner,generation)
        val frameOwner=registration.key==hosts.keys.last()
        val source=registration.value
        val type=raw.getString("type")
        require(type != "sensor") { "Sensor events require an active native session." }
        if(frameOwner && type!="viewport") reconcileViewport(store)
        when(type) {
            "viewport" -> {
                GraphicsContract.validateSurfaceEvent(raw,effectiveSurfaces)
                if(!store.artifactWorkPending(id)) {
                    source.viewport=effectiveSurfaces.getValue(raw.getString("surfaceId")).copy(width=raw.getInt("width"),height=raw.getInt("height"))
                    if(frameOwner) reconcileViewport(store)
                }
            }
            "frame","timer" -> if(frameOwner) event(store,source.generation,id,revision,raw)
            "pointer" -> event(store,source.generation,id,revision,mapPointer(raw,source.viewport))
            else -> event(store,source.generation,id,revision,raw)
        }
        return hostSnapshot(store,owner,generation)
    }

    // Each host maps touch into its measured viewport. The retained program uses the frame owner's viewport.
    private fun mapPointer(event: JSONObject,source: ToolSurface?): JSONObject {
        val target=effectiveViewport() ?: return event
        if(source==null || source==target) return event
        GraphicsContract.validateSurfaceEvent(event,mapOf(source.id to source))
        return JSONObject(event.toString())
            .put("x",event.getDouble("x")/source.width*target.width)
            .put("y",event.getDouble("y")/source.height*target.height)
    }
    suspend fun open(store: ToolStore,generation: ToolGenerationService,id: String,revision: Int,stateVersion: Int?,owner: Any,
        waitForRetainedRelease: Boolean,canStart: ()->Boolean): ArtifactSnapshot {
        if(!canStart()) throw CancellationException("The artifact host is no longer active.")
        recoverOpen(store)
        val old=current(store,id,revision,stateVersion)
        val spec=ToolSpec.parsePersisted(old.spec)
        if(spec.isSealedStandalone) return openWithoutRetained(store,generation,old,spec,owner,canStart)
        if(hosts.values.any {it.nonGraphics}) throw transientBusy()
        if(session!=null) {
            ensureAvailable();flush(store);flushJob?.cancel();flushJob=null
            hosts[++sessionGeneration]=Host(owner,generation)
            updateInputView()
            return snapshot(store)
        }
        failure=null
        effectiveSurfaces=spec.surfaces
        playback=ArtifactPlayback()
        val active=generation.retainedSession();session=active;hosts[++sessionGeneration]=Host(owner,generation)
        try {
            val result=prepareOutput(generation.executeEvent(spec,old.id,old.revision,old.state,JSONObject().put("type","open"),old.ui) {source,input ->
                active.open(source,input,waitForRetainedRelease,canStart)
            })
            ensureCurrentEvaluation(store,old.id,active)
            if(!canStart()) throw CancellationException("The artifact host is no longer active.")
            check(!store.artifactWorkPending(old.id) || result.state==old.state && result.ui==old.ui) {"A native result is pending. Open cannot change its state."}
            publish(old.copy(state=result.state,ui=result.ui),result);scheduleFlush(store)
            return snapshot(store)
        } catch(failed: Throwable) {active.close();session=null;failure=failed.message ?: "The artifact session stopped.";throw failed}
    }

    suspend fun openNonGraphics(store: ToolStore,generation: ToolGenerationService,id: String,revision: Int,owner: Any,
        canStart: ()->Boolean): ArtifactSnapshot {
        if(!canStart()) throw CancellationException("The artifact host is no longer active.")
        recoverOpen(store)
        val old=current(store,id,revision)
        val spec=ToolSpec.parsePersisted(old.spec)
        require(spec.surfaces.isEmpty()) {"This mini-app requires a drawing host."}
        return openWithoutRetained(store,generation,old,spec,owner,canStart)
    }

    private suspend fun openWithoutRetained(store: ToolStore,generation: ToolGenerationService,old: ToolRecord,spec: ToolSpec,
        owner: Any,canStart: ()->Boolean): ArtifactSnapshot {
        if(session!=null) throw transientBusy()
        if(hosts.isEmpty()) {
            val event=JSONObject().put("type","open")
            suspend fun evaluateOpen(): ToolPreview {
                if(!canStart()) throw CancellationException("The artifact host is no longer active.")
                return generation.evaluate(spec,old.id,old.revision,old.state,event,old.ui)
            }
            val output=prepareOutput(if(spec.isSealedStandalone) evaluateOpen() else withTransientHost {evaluateOpen()})
            currentCoroutineContext().ensureActive()
            if(!canStart()) throw CancellationException("The artifact host is no longer active.")
            check(store.tool(old.id)==durable) {"Saved data changed during evaluation. Reopen the mini-app."}
            check(!store.artifactWorkPending(old.id) || output.state==old.state && output.ui==old.ui) {"A native result is pending. Open cannot change its state."}
            publish(old.copy(state=output.state,ui=output.ui),output)
            scheduleFlush(store)
        } else {ensureAvailable();flush(store)}
        val host=++sessionGeneration
        hosts[host]=Host(owner,generation,nonGraphics=true)
        updateInputView()
        return hostSnapshot(store,owner,host)
    }

    private fun recoverOpen(store: ToolStore) {
        if(failure!=null && session==null) {
            if(liveRecoveryRequired) flush(store)
            invalidate()
        }
    }
    private fun ensureRetained(active: GraphicsSession) {
        ensureAvailable()
        val status=active.failureStatus ?: return
        throw CloudFailure("javascript_${status.lowercase(java.util.Locale.ROOT)}",SandboxResult(status).message)
    }

    private suspend fun ensureCurrentEvaluation(store: ToolStore,id: String,active: GraphicsSession) {
        currentCoroutineContext().ensureActive()
        ensureRetained(active)
        if(store.tool(id)!=durable) {
            val message="Saved data changed during evaluation. Reopen the mini-app."
            active.close();session=null;failure=message
            error(message)
        }
    }

    suspend fun evaluate(generation: ToolGenerationService,old: ToolRecord,event: JSONObject,clock: PartClockSample?=null,
        beforeDispatch: ()->Unit={}): ToolPreview {
        ensureAvailable()
        var nativeOnly=false
        return try {
            val spec=ToolSpec.parsePersisted(old.spec)
            nativeOnly=spec.isSealedStandalone
            val sample=clock ?: generation.partClockFor(spec)
            if(nativeOnly) {
                beforeDispatch()
                return prepareOutput(generation.evaluateWithClock(spec,old.id,old.revision,old.state,event,old.ui,sample))
            }
            val active=session
            val result=if(active==null) {
                suspend fun run(): ToolPreview {
                    beforeDispatch()
                    return generation.evaluateWithClock(spec,old.id,old.revision,old.state,event,old.ui,sample)
                }
                if(event.optString("type")==SandboxContract.LIVE_INPUT_EVENT || hosts.values.any {it.nonGraphics})
                    withTransientHost {run()} else run()
            }
            else {
                ensureRetained(active)
                generation.executeEvent(spec,old.id,old.revision,old.state,event,old.ui,clock=sample) { _, input ->
                    beforeDispatch()
                    active.dispatch(input)
                }.also {ensureRetained(active)}
            }
            prepareOutput(result)
        } catch(failed: Throwable) {
            val transientRejection=session==null && (event.optString("type")==SandboxContract.LIVE_INPUT_EVENT ||
                failed is CloudFailure && failed.code=="javascript_busy")
            if(!nativeOnly && failed !is LiveDispatchStopped && !transientRejection) {
                session?.close();session=null;failure=failed.message ?: "The artifact session stopped."
            }
            throw failed
        }
    }
    suspend fun event(store: ToolStore,generation: ToolGenerationService,id: String,revision: Int,event: JSONObject,
        publishAllowed: () -> Boolean = { true }): ArtifactSnapshot {
        if (!publishAllowed()) throw CancellationException("The native input session ended.")
        ensureAvailable();val old=current(store,id,revision)
        val active=session
        check(active!=null || hosts.values.any {it.nonGraphics}) {"The artifact session is closed. Reopen the mini-app."}
        GraphicsContract.validateSurfaceEvent(event,effectiveSurfaces)
        if(event.getString("type")=="frame")require(event.getString("surfaceId") in frames) {"The surface did not subscribe to frames."}
        if(event.getString("type")=="timer")require(event.getString("timerId") in timers) {"The timer did not subscribe."}
        if(store.artifactWorkPending(id))return snapshot(store)
        val result=evaluate(generation,old,event)
        if(active!=null) ensureCurrentEvaluation(store,id,active)
        else {
            currentCoroutineContext().ensureActive()
            check(store.tool(id)==durable) {"Saved data changed during evaluation. Reopen the mini-app."}
        }
        if(event.getString("type")=="viewport") {
            val surface=effectiveSurfaces.getValue(event.getString("surfaceId"))
            effectiveSurfaces=effectiveSurfaces+(surface.id to surface.copy(width=event.getInt("width"),height=event.getInt("height")))
            viewportApplied=true
        }
        if (!publishAllowed()) throw CancellationException("The native input session ended.")
        publish(old.copy(state=result.state,ui=result.ui),result,event.getString("type"))
        if(event.getString("type") in setOf("frame", "sensor") || event.getString("type")=="pointer" && event.getString("phase")=="move")scheduleFlush(store) else flush(store)
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
        updateInputView()
    }
    fun close(store: ToolStore,revision: Int) {
        if(durable?.revision!=revision)return
        try {flush(store)} catch(failed: Exception) {failure=failed.message ?: "The local snapshot could not commit.";throw failed} finally {
            session?.close();session=null;clearHosts();flushJob?.cancel();flushJob=null
            if(!dirty) {durable=null;live=null;resetOutput()}
            updateInputView()
        }
    }
    fun invalidate() {
        session?.close();session=null;clearHosts();flushJob?.cancel();flushJob=null
        durable=null;live=null;failure=null;liveRecoveryRequired=false;resetOutput()
        updateInputView()
    }
    fun closeOwned(store: ToolStore,revision: Int,owner: Any,generation: Long?=null) {
        if(durable?.revision!=revision) return
        val frameHost=hosts.keys.lastOrNull()
        hosts.entries.removeAll {
            val remove=it.value.owner===owner && (generation==null || it.key==generation)
            if(remove) it.value.input?.let(::cancelLiveInput)
            remove
        }
        updateInputView()
        if(frameHost!=null && frameHost !in hosts) {
            if(hosts.isEmpty()) close(store,revision)
            else flush(store)
        }
    }

    private fun clearHosts() {
        hosts.values.forEach {it.input?.let(::cancelLiveInput)}
        hosts.clear()
    }
    companion object {
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val coordinators=ConcurrentHashMap<String,ArtifactCoordinator>()
        private val transientHost=Mutex()
        private val waitingTransientHosts=AtomicInteger()
        private fun transientBusy()=CloudFailure("javascript_busy",SandboxResult("BUSY").message)
        private suspend fun <T> withTransientHost(block: suspend ()->T): T {
            if(waitingTransientHosts.incrementAndGet()>ArtifactProtocol.QUEUE_LIMIT) {
                waitingTransientHosts.decrementAndGet()
                throw transientBusy()
            }
            var locked=false
            try {
                if(withTimeoutOrNull(4_000) {transientHost.lock();locked=true}==null) throw transientBusy()
                return block()
            } finally {
                if(locked) transientHost.unlock()
                waitingTransientHosts.decrementAndGet()
            }
        }
        fun get(store: ToolStore,id: String)=coordinators.getOrPut(store.artifactIdentity+":"+id) {ArtifactCoordinator()}
        suspend fun closeOwned(store: ToolStore,owner: Any) {
            coordinators.filterKeys {it.startsWith(store.artifactIdentity+":")}.values.forEach {coordinator ->
                coordinator.ordered {durable?.let {closeOwned(store,it.revision,owner)}}
            }
        }
    }
}
