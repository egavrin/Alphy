package com.alpha.assistant.tools.agent

import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.tools.AppFileLimits
import com.alpha.assistant.tools.AppFileReply
import com.alpha.assistant.tools.ToolGenerationProgress
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.AppFileRead
import kotlinx.coroutines.delay
import org.json.JSONObject

/** The outcome of the plan step. Only [Clean] can become the app. */
internal sealed interface AppFileOutcome {
    /** [definition] holds the key language of the compile, as a model definition does. [calls] counts the model calls. */
    class Clean(val appFile: String, val definition: JSONObject, val calls: Int) : AppFileOutcome
    class Outside(val word: String) : AppFileOutcome
    /** [reason] is a reason of the trace detail fallback:<reason>. */
    class Fallback(val reason: String) : AppFileOutcome
}

/**
 * The plan step of a new app: one plan call, its compile in the sandbox, and at most one repair call.
 * [phase] marks the trace phases. [progress] shows the states that the loop shows at its start.
 */
internal class AppFileSteps(
    private val compiler: AppFileCompiler,
    private val prompt: AppFilePrompt,
    private val call: suspend (CloudGenerationStage, String, String, AppFileLimits) -> AppFileReply,
    private val phase: (String, String?, Long?) -> Unit,
    private val progress: (ToolGenerationProgress) -> Unit,
) {
    /** Sends [task] (the generation prompt of the request) to the plan call. */
    suspend fun plan(task: String, limits: AppFileLimits): AppFileOutcome {
        phase("plan_call_start", null, null)
        val first = when (val reply = planCall(task, limits)) {
            is AppFileReply.Failed -> return AppFileOutcome.Fallback(reply.reason)
            is AppFileReply.Text -> compiled { compiler.compileReply(reply.text) }
        }
        if (first is AppFileRead.Outside) return AppFileOutcome.Outside(first.word)
        first as AppFileRead.Plan
        outcome(first, 1)?.let { return it }
        phase("repair_call_start", null, null)
        val repaired = when (val reply = planCall(prompt.repairText(task, first.text, first.compilation.faults), limits)) {
            is AppFileReply.Failed -> return AppFileOutcome.Fallback(reply.reason)
            is AppFileReply.Text -> compiled { compiler.patch(first.text, reply.text) }
        }
        if (repaired is AppFileRead.Outside) return AppFileOutcome.Fallback("outside")
        return outcome(repaired as AppFileRead.Plan, 2) ?: AppFileOutcome.Fallback("faults")
    }

    private suspend fun planCall(input: String, limits: AppFileLimits): AppFileReply {
        progress(ToolGenerationProgress.COMPLETING)
        return call(CloudGenerationStage.APP_FILE, prompt.system, input, limits)
    }

    /** The outcome of a compiled plan, or null for faults that a repair call can fix. */
    private fun outcome(plan: AppFileRead.Plan, calls: Int): AppFileOutcome? {
        val compilation = plan.compilation
        return when {
            compilation.hole -> AppFileOutcome.Fallback("hole")
            compilation.status == "ok" -> AppFileOutcome.Clean(plan.text, JSONObject(checkNotNull(compilation.definition))
                .apply { compilation.language?.let { put("language", it) } }, calls)
            compilation.status != "faults" -> AppFileOutcome.Fallback("compile_${compilation.status}")
            compilation.tooLong -> AppFileOutcome.Fallback("faults")
            else -> null
        }
    }

    /** One compile. A busy sandbox (an open app runs an event) gets one more try after [BUSY_WAIT_MS]. */
    private suspend fun compiled(read: suspend () -> AppFileRead): AppFileRead {
        progress(ToolGenerationProgress.VALIDATING)
        var tries = 0
        while (true) {
            val started = System.nanoTime()
            val result = read()
            val status = (result as? AppFileRead.Plan)?.compilation?.status ?: "ok"
            phase("compile", status, (System.nanoTime() - started) / 1_000_000)
            if (status != "BUSY" || ++tries > 1) return result
            delay(BUSY_WAIT_MS)
        }
    }

    private companion object {
        const val BUSY_WAIT_MS = 500L
    }
}
