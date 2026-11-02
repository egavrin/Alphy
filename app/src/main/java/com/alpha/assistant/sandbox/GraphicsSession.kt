package com.alpha.assistant.sandbox

import android.content.Context
import com.alpha.assistant.tools.GraphicsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Retained transient JavaScript closure. A closed or failed session never replays an event. */
class GraphicsSession(context: Context) {
    val id=UUID.randomUUID().toString()
    private val runtime=AndroidJsRuntime.get(context)
    private val lock=Any()
    private val busy=AtomicBoolean(false)
    private var isolate: AndroidJsRuntime.Isolate?=null
    private var closed=false
    private var sequence=0L
    internal val engineGeneration: Long get()=synchronized(lock) {isolate?.generation ?: 0}
    suspend fun open(source: String,state: String): SandboxResult {
        if(!busy.compareAndSet(false,true))return SandboxResult("BUSY")
        try {
            synchronized(lock) {if(closed || sequence!=0L)return SandboxResult("CLOSED");sequence=1}
            try {require(source.isNotBlank() && source.toByteArray().size <= 32768);SandboxContract.objectFrom(state)}
            catch(_: Exception) {close();return SandboxResult("INVALID_REQUEST")}
            val active=try {runtime.acquire(retained=true,maxReturnBytes=GraphicsContract.FRAME_BYTES)}
                catch(failure: RuntimeFailure) {close();return SandboxResult(failure.status)}
            synchronized(lock) {if(closed) {active.close();return SandboxResult("CLOSED")};isolate=active}
            val started=android.os.SystemClock.elapsedRealtime()
            val init=active.evaluate("globalThis.__surface=(function(state){'use strict';\n$source\n})(JSON.parse(${JSONObject.quote(state)})); if(typeof __surface !== 'function') throw Error('handler'); 'ready';",GraphicsContract.EVALUATION_MS)
            android.util.Log.i("AndroidJsRuntime","graphics factory status=${init.status} elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
            if(init.status!="OK") {close();return init}
            return send(active,GraphicsContract.event("start"),1)
        } catch(cancel: CancellationException) {close();throw cancel}
        finally {busy.set(false)}
    }
    suspend fun event(event: JSONObject): SandboxResult {
        if(!busy.compareAndSet(false,true))return SandboxResult("BUSY")
        try {
            val (active,seq)=synchronized(lock) {
                isolate?.status?.let {return SandboxResult(it)}
                if(closed || isolate==null)return SandboxResult("CLOSED")
                checkNotNull(isolate) to ++sequence
            }
            try {GraphicsContract.validateEvent(event)}catch(_: Exception) {close();return SandboxResult("INVALID_REQUEST")}
            return send(active,event,seq)
        } catch(cancel: CancellationException) {close();throw cancel}
        finally {busy.set(false)}
    }
    private suspend fun send(active: AndroidJsRuntime.Isolate,event: JSONObject,seq: Long): SandboxResult {
        val started=android.os.SystemClock.elapsedRealtime()
        val result=active.evaluate("JSON.stringify(__surface(JSON.parse(${JSONObject.quote(event.toString())})))",GraphicsContract.EVALUATION_MS)
        if(event.getString("type")=="start")android.util.Log.i("AndroidJsRuntime","graphics start status=${result.status} elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
        currentCoroutineContext().ensureActive()
        synchronized(lock) {if(closed || isolate !== active || sequence!=seq)return SandboxResult("CLOSED")}
        if(result.status!="OK") {close();return result}
        if(runCatching {GraphicsContract.frame(checkNotNull(result.json),event.getString("type"))}.isFailure) {close();return SandboxResult("INVALID_RESULT")}
        return result
    }
    fun close() {
        val active=synchronized(lock) {if(closed)return;closed=true;++sequence;isolate.also {isolate=null}}
        active?.close()
    }
}
