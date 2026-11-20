package com.alpha.assistant.tools.appfile

import android.content.Context
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolSpec
import org.json.JSONException
import org.json.JSONObject

internal const val APP_FILE_COMPILE_MS=3000L
internal const val APP_FILE_RETURN_BYTES=384*1024
/** The longest plan that the compile step reads. The longest saved plan of the kit has 2314 characters. */
internal const val APP_FILE_MAX_CHARS=65536
private const val KIT_ASSET="appfile/kit.js"
/** The start of the fault of a text past APP_FILE_MAX_CHARS. */
private const val TOO_LONG="the plan has "

/**
 * status: "ok" (a definition and no fault), "faults", or the sandbox status that failed (TIMEOUT, BUSY, and so on). definition is JSON text.
 * hole is true when the plan has a need of kind hole. state is the next state of the stored app as JSON text: only with a previous and no fault.
 * The kit sets the two flags of a change (kit/src/change.js): blockedBySavedData when a saved value of the person blocks the change, and
 * savedDataMismatch when the stored state does not fit its stored app file.
 */
internal data class AppFileCompilation(val status: String,val faults: List<String>,val notes: List<String>,val definition: String?,val language: String?,
    val hole: Boolean=false,val state: String?=null,val blockedBySavedData: Boolean=false,val savedDataMismatch: Boolean=false) {
    /** The text is longer than the compile step reads. A repair call cannot shorten it in a useful way. */
    val tooLong get()=faults.any {it.startsWith(TOO_LONG)}
    /** The saved data of the person stops the compile: one of the two flags of the kit. No reader tells the two apart. */
    val savedDataFault get()=blockedBySavedData || savedDataMismatch
}

/** The stored app of a change, as the host keeps it: its app file, and its state schema and state as JSON text. */
internal data class AppFilePrevious(val appFile: String,val stateSchema: String,val state: String)

/** A reply of a model: the one line outside("word"), or the plan that the compilation is of. */
internal sealed interface AppFileRead {
    data class Outside(val word: String): AppFileRead
    data class Plan(val text: String,val compilation: AppFileCompilation): AppFileRead
}

/**
 * Runs the kit bundle (kit/build.js) in a fresh sandbox isolate: one evaluation of compileStep (kit/src/compile.js) for each call. It reads the
 * reply (when read is true), merges it into plan (when plan is not null), and compiles the text with the stored app. A text past cap is not compiled.
 */
internal class AppFileCompiler(private val context: Context,private val sandbox: SandboxClient) {
    private val kit by lazy {context.assets.open(KIT_ASSET).use {it.readBytes().decodeToString()}}

    /** A plan with the stored app of a change. Without read, no result is Outside. */
    suspend fun compile(appFile: String,previous: AppFilePrevious?=null): AppFileCompilation=(evaluate(appFile,null,false,previous) as AppFileRead.Plan).compilation
    /** A reply of a plan call. Plan.text is the plan as readPlanReply reads it, or the reply when the sandbox fails. */
    suspend fun compileReply(reply: String): AppFileRead=evaluate(reply,null,true,null)
    /** A repair reply or a change reply merged into plan. Plan.text is the merged plan, or plan when no merge ran. */
    suspend fun patch(plan: String,reply: String,previous: AppFilePrevious?=null): AppFileRead=evaluate(reply,plan,true,previous)

    private suspend fun evaluate(text: String,plan: String?,read: Boolean,previous: AppFilePrevious?): AppFileRead {
        val given=plan ?: text
        // A longer text is a fault with no call of the sandbox, so its text never takes the memory of an isolate.
        listOfNotNull(plan,text).firstOrNull {it.length>APP_FILE_MAX_CHARS}?.let {return AppFileRead.Plan(given,overCap(it.length))}
        // A stored app past the limits of the host is a status, not a fault, so no repair call sends it to a model.
        if(previous!=null && (previous.appFile.length>APP_FILE_MAX_CHARS || previous.state.toByteArray().size>ToolSpec.MAX_STATE_BYTES))
            return AppFileRead.Plan(given,failed("INVALID_REQUEST"))
        // All inputs come in as one JSON text, so they stay data.
        val input=JSONObject().put("text",text).put("plan",plan ?: JSONObject.NULL).put("read",read).put("cap",APP_FILE_MAX_CHARS)
            .put("previous",previous?.let {JSONObject().put("appFile",it.appFile).put("stateSchema",it.stateSchema).put("state",it.state)} ?: JSONObject.NULL)
        val result=sandbox.evaluateScript(kit,"compileStep(JSON.parse(${JSONObject.quote(input.toString())})).then(JSON.stringify)",APP_FILE_COMPILE_MS,APP_FILE_RETURN_BYTES)
        if(result.status!="OK") return AppFileRead.Plan(given,failed(result.status))
        // A promise that the engine does not resolve gives an empty text, not JSON. An output without its faults or notes is not a result either.
        return try {
            val json=JSONObject(checkNotNull(result.json))
            if(json.getString("kind") == "outside") AppFileRead.Outside(json.getString("word")) else {
                val merged=json.getString("text")
                AppFileRead.Plan(merged,if(json.isNull("result")) overCap(merged.length) else read(json.getJSONObject("result")))
            }
        } catch(_: JSONException) {AppFileRead.Plan(given,failed("INVALID_RESULT"))} catch(_: IllegalStateException) {AppFileRead.Plan(given,failed("INVALID_RESULT"))}
    }

    private fun failed(status: String)=AppFileCompilation(status,emptyList(),emptyList(),null,null)
    private fun overCap(length: Int)=AppFileCompilation("faults",
        listOf("$TOO_LONG$length characters, and Alphy reads at most $APP_FILE_MAX_CHARS. Use shorter texts and fewer lines"),emptyList(),null,null)

    private fun read(json: JSONObject): AppFileCompilation {
        fun texts(key: String)=json.getJSONArray(key).let {list -> List(list.length()) {list.getString(it)}}
        fun objectText(key: String)=if(json.isNull(key)) null else json.getJSONObject(key).toString()
        val faults=texts("faults")
        val definition=objectText("definition")
        return AppFileCompilation(if(definition!=null && faults.isEmpty()) "ok" else "faults",faults,texts("notes"),definition,
            if(json.isNull("language")) null else json.getString("language"),json.getBoolean("hole"),objectText("state"),
            blockedBySavedData=json.getBoolean("blockedBySavedData"),savedDataMismatch=json.getBoolean("savedDataMismatch"))
    }
}
