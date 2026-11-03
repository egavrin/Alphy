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
    private var state="{}"
    internal val engineGeneration: Long get()=synchronized(lock) {isolate?.generation ?: 0}
    suspend fun open(source: String,state: String,artifactId: String="session",revision: Int=1): SandboxResult {
        if(!busy.compareAndSet(false,true))return SandboxResult("BUSY")
        try {
            synchronized(lock) {if(closed || sequence!=0L)return SandboxResult("CLOSED");sequence=1}
            this.artifactId=artifactId;this.revision=revision;this.state=state
            try {SandboxContract.validateRequest(source,input(JSONObject().put("type","open"),state));require(source.toByteArray().size<=32768)}
            catch(_: Exception) {close();return SandboxResult("INVALID_REQUEST")}
            val active=try {runtime.acquire(retained=true,maxReturnBytes=SandboxContract.MAX_BYTES)}
                catch(failure: RuntimeFailure) {close();return SandboxResult(failure.status)}
            synchronized(lock) {if(closed) {active.close();return SandboxResult("CLOSED")};isolate=active}
            val init=active.evaluate("globalThis.__artifact=function(input){'use strict';\n$source\n}; 'ready';",SandboxContract.EVALUATION_MS)
            if(init.status!="OK") {close();return init}
            return send(active,JSONObject().put("type","open"),state,1)
        } catch(cancel: CancellationException) {close();throw cancel}
        finally {busy.set(false)}
    }
    suspend fun event(event: JSONObject,state: String=this.state): SandboxResult {
        if(!busy.compareAndSet(false,true))return SandboxResult("BUSY")
        try {
            val (active,seq)=synchronized(lock) {
                isolate?.status?.let {return SandboxResult(it)}
                if(closed || isolate==null)return SandboxResult("CLOSED")
                checkNotNull(isolate) to ++sequence
            }
            try {SandboxContract.validateRequest("handler",input(event,state))}catch(_: Exception) {close();return SandboxResult("INVALID_REQUEST")}
            return send(active,event,state,seq)
        } catch(cancel: CancellationException) {close();throw cancel}
        finally {busy.set(false)}
    }
    private fun input(event: JSONObject,state: String)=JSONObject().put("artifactId",artifactId).put("revision",revision).put("state",JSONObject(state)).put("event",event).toString()
    private suspend fun send(active: AndroidJsRuntime.Isolate,event: JSONObject,state: String,seq: Long): SandboxResult {
        val continuous=event.getString("type") in setOf("frame","pointer","timer","control")
        val result=active.evaluate("JSON.stringify(__artifact(JSON.parse(${JSONObject.quote(input(event,state))})))",if(continuous)GraphicsContract.EVALUATION_MS else SandboxContract.EVALUATION_MS)
        currentCoroutineContext().ensureActive()
        synchronized(lock) {if(closed || isolate !== active || sequence!=seq)return SandboxResult("CLOSED")}
        if(result.status!="OK") {close();return result}
        try {SandboxContract.validateResult(checkNotNull(result.json));this.state=JSONObject(result.json).getJSONObject("state").toString()}
        catch(_: Exception) {close();return SandboxResult("INVALID_RESULT")}
        return result
    }
    fun close() {
        val active=synchronized(lock) {if(closed)return;closed=true;++sequence;isolate.also {isolate=null}}
        active?.close()
    }
}
