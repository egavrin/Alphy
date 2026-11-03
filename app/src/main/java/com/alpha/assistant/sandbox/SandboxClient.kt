package com.alpha.assistant.sandbox

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

class SandboxClient internal constructor(context: Context,private val runtime: AndroidJsRuntime) {
    private val context=context.applicationContext
    fun retainedSession()=GraphicsSession(context)
    constructor(context: Context): this(context,AndroidJsRuntime.get(context))
    suspend fun availability(): SandboxResult=runtime.availability()
    /** Generation owns only the transient slot. Closing this lease never replaces a foreground session. */
    internal suspend fun <T> disposable(block: suspend (suspend (String,String)->SandboxResult)->T): T {
        val isolate=runtime.acquire(retained=false,maxReturnBytes=SandboxContract.MAX_BYTES)
        try {
            return block {source,input ->
                val valid=runCatching {SandboxContract.validateRequest(source,input)}.isSuccess
                if(!valid) SandboxResult("INVALID_REQUEST") else {
                    val type=JSONObject(input).getJSONObject("event").getString("type")
                    val deadline=if(type in setOf("frame","pointer","timer","control")) com.alpha.assistant.tools.GraphicsContract.EVALUATION_MS else SandboxContract.EVALUATION_MS
                    val result=isolate.evaluate("JSON.stringify((function(input){'use strict';\n$source\n})(JSON.parse(${JSONObject.quote(input)})))",deadline)
                    currentCoroutineContext().ensureActive()
                    if(result.status=="OK" && runCatching {SandboxContract.validateResult(checkNotNull(result.json))}.isFailure) SandboxResult("INVALID_RESULT") else result
                }
            }
        } finally {isolate.close()}
    }
    suspend fun execute(source: String,input: String): SandboxResult {
        try {SandboxContract.validateRequest(source,input)}
        catch(_: Exception) {return SandboxResult("INVALID_REQUEST")}
        currentCoroutineContext().ensureActive()
        val isolate=try {runtime.acquire(retained=false,maxReturnBytes=SandboxContract.MAX_BYTES)}
            catch(cancel: CancellationException) {throw cancel}
            catch(failure: RuntimeFailure) {return SandboxResult(failure.status)}
        try {
            val result=isolate.evaluate("JSON.stringify((function(input){'use strict';\n$source\n})(JSON.parse(${JSONObject.quote(input)})))",SandboxContract.EVALUATION_MS)
            if(result.status=="OK" && runCatching {SandboxContract.validateResult(checkNotNull(result.json))}.isFailure)return SandboxResult("INVALID_RESULT")
            currentCoroutineContext().ensureActive()
            return result
        } finally {isolate.close()}
    }
}
