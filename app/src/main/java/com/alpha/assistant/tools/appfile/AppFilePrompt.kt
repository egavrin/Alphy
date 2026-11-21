package com.alpha.assistant.tools.appfile

import android.content.Context
import org.json.JSONObject

private const val PROMPT_ASSET="appfile/prompt.json"
private val PLACEHOLDER=Regex("""\{\{(\w+)\}\}""")

/**
 * The prompt texts that kit/build.js writes from kit/prompt.md: the sections System and Dialogue, and the templates of a repair call and a change call.
 * [template] is the section Template, the instructions of each template call, with the templates of its repair call and its change call.
 */
internal class AppFilePrompt(val system: String,
    /** No code reads it yet: the voice path will use it. */ val dialogue: String,
    private val repairTask: String,private val changeTask: String,val template: String,
    private val templateRepairTask: String,private val templateChangeTask: String,templateHead: String) {
    /** TEMPLATE_HEAD of kit/src/template.js. */
    private val head=Regex(templateHead)
    /** The text of a repair call, as repairText of kit/prompt.js builds it. */
    fun repairText(task: String,plan: String,faults: List<String>)=fill(repairTask,mapOf("task" to task,"plan" to plan.trimEnd(),"faults" to faults.joinToString("\n- ")))
    /** The text of a change call, as changeText of kit/prompt.js builds it. */
    fun changeText(request: String,plan: String)=fill(changeTask,mapOf("request" to request,"plan" to plan.trimEnd()))
    /** The text of a template repair call, as templateRepairText of kit/prompt.js builds it. */
    fun templateRepairText(task: String,plan: String,faults: List<String>)=
        fill(templateRepairTask,mapOf("task" to task,"plan" to plan.trimEnd(),"faults" to faults.joinToString("\n- ")))
    /** The text of a template change call, as templateChangeText of kit/prompt.js builds it. */
    fun templateChangeText(request: String,plan: String)=fill(templateChangeTask,mapOf("request" to request,"plan" to plan.trimEnd()))
    /** True for a template file, as isTemplateFile of kit/src/template.js tests it: on the first line that is not empty. */
    fun isTemplate(appFile: String)=head.containsMatchIn(appFile.lineSequence().firstOrNull {it.isNotBlank()}.orEmpty())

    companion object {
        @Volatile private var loaded: AppFilePrompt?=null
        /** The asset, read once. */
        fun load(context: Context): AppFilePrompt=loaded ?: JSONObject(context.assets.open(PROMPT_ASSET).use {it.readBytes().decodeToString()}).let {
            AppFilePrompt(it.getString("system"),it.getString("dialogue"),it.getString("repairTask"),it.getString("changeTask"),it.getString("template"),
                it.getString("templateRepairTask"),it.getString("templateChangeTask"),it.getString("templateHead"))
        }.also {loaded=it}
        // One pass, so a placeholder text inside a request or a plan is not filled again. The text of the lambda is no replacement pattern, so $/x stays.
        private fun fill(template: String,values: Map<String,String>)=PLACEHOLDER.replace(template) {values[it.groupValues[1]] ?: it.value}
    }
}
