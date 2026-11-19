package com.alpha.assistant.tools.appfile

import android.content.Context
import org.json.JSONObject

private const val PROMPT_ASSET="appfile/prompt.json"
private val PLACEHOLDER=Regex("""\{\{(\w+)\}\}""")

/** The prompt texts that kit/build.js writes from kit/prompt.md: the sections System and Dialogue, and the templates of a repair call and a change call. */
internal class AppFilePrompt(val system: String,
    /** No code reads it yet: the voice path will use it. */ val dialogue: String,
    private val repairTask: String,private val changeTask: String) {
    /** The text of a repair call, as repairText of kit/prompt.js builds it. */
    fun repairText(task: String,plan: String,faults: List<String>)=fill(repairTask,mapOf("task" to task,"plan" to plan.trimEnd(),"faults" to faults.joinToString("\n- ")))
    /** The text of a change call, as changeText of kit/prompt.js builds it. */
    fun changeText(request: String,plan: String)=fill(changeTask,mapOf("request" to request,"plan" to plan.trimEnd()))

    companion object {
        @Volatile private var loaded: AppFilePrompt?=null
        /** The asset, read once. */
        fun load(context: Context): AppFilePrompt=loaded ?: JSONObject(context.assets.open(PROMPT_ASSET).use {it.readBytes().decodeToString()}).let {
            AppFilePrompt(it.getString("system"),it.getString("dialogue"),it.getString("repairTask"),it.getString("changeTask"))
        }.also {loaded=it}
        // One pass, so a placeholder text inside a request or a plan is not filled again. The text of the lambda is no replacement pattern, so $/x stays.
        private fun fill(template: String,values: Map<String,String>)=PLACEHOLDER.replace(template) {values[it.groupValues[1]] ?: it.value}
    }
}
