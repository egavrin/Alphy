package com.alpha.assistant.sandbox

import android.content.Context
import com.alpha.assistant.tools.GraphicsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One retained artifact handler serves native controls and all identified surfaces. No event is retried. */
class GraphicsSession(context: Context) {
    val id=UUID.randomUUID().toString()
    private val runtime=AndroidJsRuntime.get(context)
    private val lock=Any()
    private val busy=AtomicBoolean(false)
    private var isolate: AndroidJsRuntime.Isolate?=null
    private var closed=false
    private var sequence=0L
    private var artifactId="session"
    private var revision=1
    private var standaloneState="{}"
    private var standalone=false
    internal val engineGeneration: Long get()=synchronized(lock) {isolate?.generation ?: 0}
    internal val failureStatus: String? get()=synchronized(lock) {
        val active=isolate
        if(closed || active==null) "CLOSED" else active.status
    }

    suspend fun open(source: String,state: String,artifactId: String="session",revision: Int=1): SandboxResult =
        openRequest(source,rememberState=true) { input(artifactId,revision,JSONObject().put("type","open"),state) }

    /** The coordinator supplies the sandbox partition and retains the accepted state. */
    internal suspend fun open(source: String,input: JSONObject,waitForRetainedRelease: Boolean,canStart: ()->Boolean): SandboxResult =
        openRequest(source,rememberState=false,waitForRetainedRelease=waitForRetainedRelease,canStart=canStart) {input}

    private suspend fun openRequest(source: String,rememberState: Boolean,waitForRetainedRelease: Boolean=false,
        canStart: ()->Boolean={true},request: ()->JSONObject): SandboxResult {
        if(!busy.compareAndSet(false,true))return SandboxResult("BUSY")
        try {
            synchronized(lock) {if(closed || sequence!=0L)return SandboxResult("CLOSED");sequence=1}
            val payload=try {
                val input=request()
                input.toString().also {
                    SandboxContract.validateRequest(source,it)
                    require(source.toByteArray(Charsets.UTF_8).size<=32768 && input.getJSONObject("event").getString("type")=="open")
                    artifactId=input.getString("artifactId")
                    revision=input.getInt("revision")
                    standalone=rememberState
                }
            } catch(_: Exception) {close();return SandboxResult("INVALID_REQUEST")}
            val active=try {runtime.acquire(retained=true,maxReturnBytes=SandboxContract.MAX_BYTES,waitForRetainedRelease=waitForRetainedRelease)}
                catch(failure: RuntimeFailure) {close();return SandboxResult(failure.status)}
            synchronized(lock) {if(closed) {active.close();return SandboxResult("CLOSED")};isolate=active}
            currentCoroutineContext().ensureActive()
            if(!canStart()) throw CancellationException("The artifact host is no longer active.")
            val body=JSONObject.quote("'use strict';\n$source\n")
            val init=active.evaluate("""
                (() => {
                    const parse = JSON.parse;
                    const stringify = JSON.stringify;
                    const handler = Function("input", $body);
                    Object.defineProperty(globalThis, "__alphaArtifactDispatch", {
                        value: payload => stringify(handler(parse(payload)))
                    });
                    return "ready";
                })()
            """.trimIndent(),SandboxContract.EVALUATION_MS)
            if(init.status!="OK") {close();return init}
            return send(active,payload,"open",1,rememberState)
        } catch(cancel: CancellationException) {close();throw cancel}
        finally {busy.set(false)}
    }

    suspend fun event(event: JSONObject,state: String=standaloneState): SandboxResult =
        eventRequest(rememberState=true) { input(artifactId,revision,event,state) }

    internal suspend fun dispatch(input: JSONObject): SandboxResult = eventRequest(rememberState=false) { input }

    private suspend fun eventRequest(rememberState: Boolean,request: ()->JSONObject): SandboxResult {
        if(!busy.compareAndSet(false,true))return SandboxResult("BUSY")
        try {
            val (active,seq)=synchronized(lock) {
                isolate?.status?.let {return SandboxResult(it)}
                if(closed || isolate==null)return SandboxResult("CLOSED")
                checkNotNull(isolate) to ++sequence
            }
            val (payload,type)=try {
                require(standalone==rememberState)
                val input=request()
                val json=input.toString()
                SandboxContract.validateRequest("handler",json)
                require(input.getString("artifactId")==artifactId && input.getInt("revision")==revision)
                json to input.getJSONObject("event").getString("type")
            } catch(_: Exception) {close();return SandboxResult("INVALID_REQUEST")}
            return send(active,payload,type,seq,rememberState)
        } catch(cancel: CancellationException) {close();throw cancel}
        finally {busy.set(false)}
    }

    private fun input(id: String,revision: Int,event: JSONObject,state: String) =
        JSONObject().put("artifactId",id).put("revision",revision).put("state",JSONObject(state)).put("event",event)

    private suspend fun send(active: AndroidJsRuntime.Isolate,payload: String,type: String,seq: Long,rememberState: Boolean): SandboxResult {
        val deadline=if(type in setOf("frame","pointer","timer","control",SandboxContract.LIVE_INPUT_EVENT)) GraphicsContract.EVALUATION_MS else SandboxContract.EVALUATION_MS
        val result=active.evaluate("__alphaArtifactDispatch(${JSONObject.quote(payload)})",deadline)
        currentCoroutineContext().ensureActive()
        synchronized(lock) {if(closed || isolate !== active || sequence!=seq)return SandboxResult("CLOSED")}
        if(result.status!="OK") {close();return result}
        if(rememberState) {
            try {
                val json=checkNotNull(result.json)
                SandboxContract.validateResult(json,liveInput=type==SandboxContract.LIVE_INPUT_EVENT)
                standaloneState=JSONObject(json).getJSONObject("state").toString()
            } catch(_: Exception) {close();return SandboxResult("INVALID_RESULT")}
        }
        return result
    }

    fun close() {
        val active=synchronized(lock) {if(closed)return;closed=true;++sequence;isolate.also {isolate=null}}
        active?.close()
    }
}
