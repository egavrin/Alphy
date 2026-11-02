package com.alpha.assistant.sandbox

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

class SandboxClient internal constructor(context: Context,private val runtime: AndroidJsRuntime) {
    constructor(context: Context): this(context,AndroidJsRuntime.get(context))
    suspend fun availability(): SandboxResult=runtime.availability()
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
