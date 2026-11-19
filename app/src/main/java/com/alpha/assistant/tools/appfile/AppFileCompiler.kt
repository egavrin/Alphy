package com.alpha.assistant.tools.appfile

import android.content.Context
import com.alpha.assistant.sandbox.SandboxClient
import org.json.JSONException
import org.json.JSONObject

internal const val APP_FILE_COMPILE_MS=3000L
internal const val APP_FILE_RETURN_BYTES=384*1024
/** The longest plan that the compile step reads. The longest saved plan of the kit has 2314 characters. */
internal const val APP_FILE_MAX_CHARS=65536
private const val KIT_ASSET="appfile/kit.js"

/** status: "ok" (a definition and no fault), "faults", or the sandbox status that failed (TIMEOUT, BUSY, and so on). definition is JSON text. */
internal data class AppFileCompilation(val status: String,val faults: List<String>,val notes: List<String>,val definition: String?,val language: String?)

/** Runs compileAppFile of the kit bundle (kit/build.js) in a fresh sandbox isolate. */
internal class AppFileCompiler(private val context: Context,private val sandbox: SandboxClient) {
    private val kit by lazy {context.assets.open(KIT_ASSET).use {it.readBytes().decodeToString()}}

    suspend fun compile(appFile: String): AppFileCompilation {
        // A longer plan is a fault with no call of the sandbox, so its text never takes the memory of an isolate.
        if(appFile.length>APP_FILE_MAX_CHARS) return AppFileCompilation("faults",
            listOf("the plan has ${appFile.length} characters, and Alphy reads at most $APP_FILE_MAX_CHARS. Use shorter texts and fewer lines"),emptyList(),null,null)
        val result=sandbox.evaluateScript(kit,"compileAppFile(${JSONObject.quote(appFile)}).then(JSON.stringify)",APP_FILE_COMPILE_MS,APP_FILE_RETURN_BYTES)
        if(result.status!="OK") return AppFileCompilation(result.status,emptyList(),emptyList(),null,null)
        // A promise that the engine does not resolve gives an empty text, not JSON. An output without its faults or notes is not a result either.
        return try {read(JSONObject(checkNotNull(result.json)))} catch(_: JSONException) {invalid} catch(_: IllegalStateException) {invalid}
    }

    private val invalid=AppFileCompilation("INVALID_RESULT",emptyList(),emptyList(),null,null)

    private fun read(json: JSONObject): AppFileCompilation {
        fun texts(key: String)=json.getJSONArray(key).let {list -> List(list.length()) {list.getString(it)}}
        val faults=texts("faults")
        val definition=if(json.isNull("definition")) null else json.getJSONObject("definition").toString()
        return AppFileCompilation(if(definition!=null && faults.isEmpty()) "ok" else "faults",faults,texts("notes"),definition,
            if(json.isNull("language")) null else json.getString("language"))
    }
}
