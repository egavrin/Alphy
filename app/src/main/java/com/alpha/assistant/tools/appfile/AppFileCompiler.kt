package com.alpha.assistant.tools.appfile

import android.content.Context
import android.util.Log
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.LibraryImages
import com.alpha.assistant.tools.StagePreviewFrame
import com.alpha.assistant.tools.ToolSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal const val APP_FILE_COMPILE_MS=3000L
internal const val APP_FILE_RETURN_BYTES=384*1024
/** The deadline of one stage preview update in its new transient isolate (gate 1a: 65 ms median at the phone size, 93 ms at the fold size). */
internal const val APP_FILE_PREVIEW_MS=1000L
/** The longest plan that the compile step reads. The longest saved plan of the kit has 2314 characters. */
internal const val APP_FILE_MAX_CHARS=65536
private const val KIT_ASSET="appfile/kit.js"
/** The start of the fault of a text past APP_FILE_MAX_CHARS. */
private const val TOO_LONG="the plan has "
/** A name of the miss log that goes to the release log as it is: a library name such as "solar_panel". Each other name is "other". */
private val MISS_NAME=Regex("[a-z0-9_]{1,40}")

/**
 * status: "ok" (a definition and no fault), "faults", or the sandbox status that failed (TIMEOUT, BUSY, and so on). definition is JSON text.
 * hole is true when the plan has a need of kind hole. state is the next state of the stored app as JSON text: only with a previous and no fault.
 * The kit sets the two flags of a change (kit/src/change.js): blockedBySavedData when a saved value of the person blocks the change, and
 * savedDataMismatch when the stored state does not fit its stored app file.
 * A template file (kit/src/template.js) also gives template (its word), kept (the text to store), expanded (the app file of kept), and leftOut
 * (the names of the lines that it left out). Its definition also comes with the faults of those lines, so its status is then faults.
 * frameJson is the frame of a compile with a definition (kit frameOf): {ui, state, drawing, holds} with no definition.
 */
internal data class AppFileCompilation(val status: String,val faults: List<String>,val notes: List<String>,val definition: String?,val language: String?,
    val hole: Boolean=false,val state: String?=null,val blockedBySavedData: Boolean=false,val savedDataMismatch: Boolean=false,
    val template: String?=null,val kept: String?=null,val expanded: String?=null,val leftOut: List<String> = emptyList(),
    val frameJson: String?=null,
    /** The value of a searched data form that no source excerpt holds, which turned the form into nodata (kit checkData), or null. */
    val rejected: String?=null) {
    /** The frame that the stage shows while the host check runs, read only when a caller needs it. Null with no definition. */
    val frame: StagePreviewFrame? get()=frameJson?.let {stagePreviewFrame(JSONObject(it),definition)}
    /** The text is longer than the compile step reads. A repair call cannot shorten it in a useful way. */
    val tooLong get()=faults.any {it.startsWith(TOO_LONG)}
    /** The saved data of the person stops the compile: one of the two flags of the kit. No reader tells the two apart. */
    val savedDataFault get()=blockedBySavedData || savedDataMismatch
}

/**
 * The host check of a searched data form (block 2 plan, decision 3): each number of the form is in [excerpts], the titles and summaries of
 * the sources, and a form that passes shows its source as [site] and [time] (D6). A known-facts form has no check.
 */
internal data class DataCheck(val excerpts: String,val site: String,val time: String) {
    fun json(): JSONObject=JSONObject().put("excerpts",excerpts).put("site",site).put("time",time)

    companion object {
        /**
         * The check of a form from [sources], or null with no source: the excerpts are the title and the summary of each source, as the
         * generation prompt holds them. The site is the site name (or the host) of the first two sources, and the time is the search time [now].
         */
        fun of(sources: List<com.alpha.assistant.data.Source>,now: Long): DataCheck? {
            val links=sources.distinctBy {it.url}.takeIf {it.isNotEmpty()} ?: return null
            val sites=links.mapNotNull {source -> source.siteName?.takeIf {it.isNotBlank()} ?: runCatching {java.net.URI(source.url).host?.removePrefix("www.")}.getOrNull()}.distinct()
            val time=java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",java.util.Locale.ROOT).format(java.util.Date(now))
            return DataCheck(links.joinToString("\n") {it.title+"\n"+it.summary},sites.take(2).joinToString(", ").ifEmpty {"—"}.take(100),time)
        }
    }
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
    /** The image entries of the library whose image the assets do not hold: the kit treats each one as a name it does not have (stage 6, I4). */
    internal var missingImages: Set<String>?=null
    private fun missing()=JSONArray((missingImages ?: LibraryImages.missing(context.assets).also {missingImages=it}).sorted())

    /** A plan with the stored app of a change. Without read, no result is Outside. */
    suspend fun compile(appFile: String,previous: AppFilePrevious?=null): AppFileCompilation=(evaluate(appFile,null,false,previous) as AppFileRead.Plan).compilation
    /** A reply of a plan call. Plan.text is the plan as readPlanReply reads it, or the reply when the sandbox fails. */
    suspend fun compileReply(reply: String,check: DataCheck?=null): AppFileRead=evaluate(reply,null,true,null,check)
    /** A repair reply or a change reply merged into plan. Plan.text is the merged plan, or plan when no merge ran. */
    suspend fun patch(plan: String,reply: String,previous: AppFilePrevious?=null,check: DataCheck?=null): AppFileRead=evaluate(reply,plan,true,previous,check)

    /**
     * The frame of the complete lines [text] of a streamed reply (kit previewStep), merged into [plan] with the stored app [previous] of a
     * change. It runs in a new transient isolate with the deadline [APP_FILE_PREVIEW_MS]. A busy slot, a deadline, and a step with no frame
     * give null, so the stream skips this update.
     */
    suspend fun preview(text: String,plan: String?,previous: AppFilePrevious?,check: DataCheck?=null): StagePreviewFrame? {
        if(listOfNotNull(text,plan,previous?.appFile).any {it.length>APP_FILE_MAX_CHARS}) return null
        val input=JSONObject().put("text",text).put("plan",plan ?: JSONObject.NULL).put("previous",previousJson(previous))
            .put("data",check?.json() ?: JSONObject.NULL).put("missing",missing())
        val hold=previewHoldMs.takeIf {it>0}?.let {"(()=>{const end=Date.now()+$it;while(Date.now()<end){}})();\n"}.orEmpty()
        val result=sandbox.evaluateScript(kit,"${hold}Promise.resolve(previewStep(JSON.parse(${JSONObject.quote(input.toString())}))).then(JSON.stringify)",
            APP_FILE_PREVIEW_MS,APP_FILE_RETURN_BYTES)
        if(result.status!="OK") return null
        return try {
            val json=JSONObject(checkNotNull(result.json))
            if(json.getString("kind")!="preview") null else json.getJSONObject("frame").let {stagePreviewFrame(it,it.get("definition").toString())}
        } catch(_: JSONException) {null} catch(_: IllegalStateException) {null} catch(_: IllegalArgumentException) {null}
    }

    private suspend fun evaluate(text: String,plan: String?,read: Boolean,previous: AppFilePrevious?,check: DataCheck?=null): AppFileRead {
        val given=plan ?: text
        // A longer text is a fault with no call of the sandbox, so its text never takes the memory of an isolate.
        listOfNotNull(plan,text).firstOrNull {it.length>APP_FILE_MAX_CHARS}?.let {return AppFileRead.Plan(given,overCap(it.length))}
        // A stored app past the limits of the host is a status, not a fault, so no repair call sends it to a model.
        if(previous!=null && (previous.appFile.length>APP_FILE_MAX_CHARS || previous.state.toByteArray().size>ToolSpec.MAX_STATE_BYTES))
            return AppFileRead.Plan(given,failed("INVALID_REQUEST"))
        // All inputs come in as one JSON text, so they stay data.
        val input=JSONObject().put("text",text).put("plan",plan ?: JSONObject.NULL).put("read",read).put("cap",APP_FILE_MAX_CHARS)
            .put("previous",previousJson(previous)).put("data",check?.json() ?: JSONObject.NULL).put("missing",missing())
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
        fun textsOrEmpty(key: String)=if(json.isNull(key)) emptyList() else texts(key)
        fun objectText(key: String)=if(json.isNull(key)) null else json.getJSONObject(key).toString()
        // A free app file has no template fields.
        fun text(key: String)=if(json.isNull(key)) null else json.getString(key)
        val faults=texts("faults")
        val definition=objectText("definition")
        // The miss log of the library (spec 12.8): each name of a library line that the catalog does not have, as a plain name or "other".
        textsOrEmpty("misses").takeIf {it.isNotEmpty()}?.let {misses ->
            Log.i("AlphyLibrary","miss names=${misses.joinToString(",") {name -> name.takeIf {it.matches(MISS_NAME)} ?: "other"}}")
        }
        return AppFileCompilation(if(definition!=null && faults.isEmpty()) "ok" else "faults",faults,texts("notes"),definition,
            text("language"),json.getBoolean("hole"),objectText("state"),
            blockedBySavedData=json.getBoolean("blockedBySavedData"),savedDataMismatch=json.getBoolean("savedDataMismatch"),
            template=text("template"),kept=text("kept"),expanded=text("expanded"),leftOut=textsOrEmpty("leftOut"),frameJson=objectText("frame"),
            rejected=if(json.has("rejected")) text("rejected") else null)
    }

    companion object {
        /** Test seam (Tasks 17 and 20): a value above 0 adds a busy wait of that many milliseconds inside each preview script, so the transient slot stays held. */
        @Volatile internal var previewHoldMs=0L
    }
}

/** The frame of a kit frame object with [definition], or null with no definition or with a definition that the host does not take. */
private fun stagePreviewFrame(frame: JSONObject,definition: String?): StagePreviewFrame?=definition?.let {
    try {
        StagePreviewFrame(ToolSpec.parse(it),frame.get("ui").toString(),frame.get("state").toString(),frame.optJSONObject("drawing")?.toString(),frame.getString("holds"))
    } catch(_: JSONException) {null} catch(_: IllegalArgumentException) {null}
}

/** The stored app of a change as the kit reads it ({appFile, stateSchema, state}), or JSON null for a new app. */
private fun previousJson(previous: AppFilePrevious?): Any=
    previous?.let {JSONObject().put("appFile",it.appFile).put("stateSchema",it.stateSchema).put("state",it.state)} ?: JSONObject.NULL
