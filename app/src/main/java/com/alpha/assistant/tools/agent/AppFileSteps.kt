package com.alpha.assistant.tools.agent

import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.tools.AppFileLimits
import com.alpha.assistant.tools.AppFileReply
import com.alpha.assistant.tools.ToolGenerationProgress
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.AppFilePrevious
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.AppFileRead
import kotlinx.coroutines.delay
import org.json.JSONObject

/** The outcome of the plan step or the change step. Only [Clean] can become the app. */
internal sealed interface AppFileOutcome {
    /**
     * [definition] holds the key language of the compile, as a model definition does. [calls] counts the model calls.
     * [state]: the next state that the kit computed from the stored state of a change. Null for a new app.
     */
    class Clean(val appFile: String, val definition: JSONObject, val calls: Int, val state: String? = null) : AppFileOutcome
    class Outside(val word: String) : AppFileOutcome
    /** [reason] is a reason of the trace detail fallback:<reason>. */
    class Fallback(val reason: String) : AppFileOutcome
}

/**
 * The plan step of a new app and the change step of a saved app with an app file: one model call, its compile in the sandbox,
 * and at most one repair call. [phase] marks the trace phases. [progress] shows the states that the loop shows at its start.
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
        return steps(CloudGenerationStage.APP_FILE, task, null, limits) { compiler.compileReply(it) }
    }

    /**
     * Sends [request] with the app file of [stored] to the change call. The reply holds only the lines that change: the kit merges them
     * into the stored plan and computes the next state from the stored state. A merged plan equal to the stored plan is Fallback("empty").
     */
    suspend fun change(request: String, stored: AppFilePrevious, limits: AppFileLimits): AppFileOutcome {
        phase("change_call_start", null, null)
        return steps(CloudGenerationStage.APP_FILE_CHANGE, prompt.changeText(request, stored.appFile), stored, limits) {
            compiler.patch(stored.appFile, it, stored)
        }
    }

    /** The first call of [stage] with [task], the compile of its reply by [read], and at most one repair call. [stored]: the stored app of a change. */
    private suspend fun steps(stage: CloudGenerationStage, task: String, stored: AppFilePrevious?, limits: AppFileLimits,
        read: suspend (String) -> AppFileRead): AppFileOutcome {
        val first = when (val reply = modelCall(stage, task, limits)) {
            is AppFileReply.Failed -> return AppFileOutcome.Fallback(reply.reason)
            is AppFileReply.Text -> compiled { read(reply.text) }
        }
        if (first is AppFileRead.Outside) return AppFileOutcome.Outside(first.word)
        first as AppFileRead.Plan
        outcome(first, 1, stored)?.let { return it }
        phase("repair_call_start", null, null)
        val repaired = when (val reply = modelCall(CloudGenerationStage.APP_FILE, prompt.repairText(task, first.text, first.compilation.faults), limits)) {
            is AppFileReply.Failed -> return AppFileOutcome.Fallback(reply.reason)
            is AppFileReply.Text -> compiled { compiler.patch(first.text, reply.text, stored) }
        }
        if (repaired is AppFileRead.Outside) return AppFileOutcome.Fallback("outside")
        return outcome(repaired as AppFileRead.Plan, 2, stored) ?: AppFileOutcome.Fallback("faults")
    }

    private suspend fun modelCall(stage: CloudGenerationStage, input: String, limits: AppFileLimits): AppFileReply {
        progress(ToolGenerationProgress.COMPLETING)
        return call(stage, prompt.system, input, limits)
    }

    /** The outcome of a compiled plan, or null for faults that a repair call can fix. A model cannot fix the saved data, so it gets no repair call. */
    private fun outcome(plan: AppFileRead.Plan, calls: Int, stored: AppFilePrevious?): AppFileOutcome? {
        val compilation = plan.compilation
        return when {
            compilation.blockedBySavedData || compilation.savedDataMismatch -> AppFileOutcome.Fallback("data_blocks")
            compilation.hole -> AppFileOutcome.Fallback("hole")
            compilation.status == "ok" && plan.text == stored?.appFile -> AppFileOutcome.Fallback("empty")
            compilation.status == "ok" -> AppFileOutcome.Clean(plan.text, JSONObject(checkNotNull(compilation.definition))
                .apply { compilation.language?.let { put("language", it) } }, calls, compilation.state)
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
