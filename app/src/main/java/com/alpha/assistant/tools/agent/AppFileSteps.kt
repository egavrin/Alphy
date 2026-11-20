package com.alpha.assistant.tools.agent

import com.alpha.assistant.R
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.AppFileLimits
import com.alpha.assistant.tools.AppFileReply
import com.alpha.assistant.tools.ToolGenerationProgress
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.holdsDataOfThePerson
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.AppFilePrevious
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.AppFileRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The names that the plan step and the change step give the trace, and the failure codes of a change by lines.
 * The allow-lists of the trace (ToolGenerationDiagnostic) are [FALLBACKS] and [PHASES], so each emitter takes its names from here.
 */
internal object AppFileNames {
    // The reasons of the trace detail fallback:<reason>.
    const val CALL_FAILED = "call_failed"
    const val FIRST_TEXT_TIMEOUT = "first_text_timeout"
    const val TOTAL_TIMEOUT = "total_timeout"
    const val DATA_BLOCKS = "data_blocks"
    const val HOLE = "hole"
    const val EMPTY = "empty"
    const val FAULTS = "faults"
    const val OUTSIDE = "outside"
    const val HOST_CHECK = "host_check"
    /** The start of the reason of a compile that ended with a sandbox status, such as compile_TIMEOUT. */
    const val COMPILE_STATUS = "compile_"
    /** The reasons of an app-file call that ended before a reply (appFileCall). */
    val CALL_FAILURES = setOf(CALL_FAILED, FIRST_TEXT_TIMEOUT, TOTAL_TIMEOUT)
    /** Each reason other than [COMPILE_STATUS] with its status. */
    val FALLBACKS = CALL_FAILURES + setOf(DATA_BLOCKS, HOLE, EMPTY, FAULTS, OUTSIDE, HOST_CHECK)

    // The trace phases of the plan step and the change step.
    const val PLAN_CALL_START = "plan_call_start"
    const val CHANGE_CALL_START = "change_call_start"
    const val REPAIR_CALL_START = "repair_call_start"
    const val PLAN_FIRST_TEXT = "plan_first_text"
    const val PLAN_REPLY_END = "plan_reply_end"
    const val COMPILE = "compile"
    const val PLAN_OUTCOME = "plan_outcome"
    // The plan call of a spoken request that starts beside its routing call (ToolController.startEarlyPlan), and its end.
    const val EARLY_PLAN_START = "early_plan_start"
    const val EARLY_PLAN_USED = "early_plan_used"
    const val EARLY_PLAN_CANCELED = "early_plan_canceled"
    val PHASES = setOf(PLAN_CALL_START, PLAN_FIRST_TEXT, PLAN_REPLY_END, COMPILE, REPAIR_CALL_START, CHANGE_CALL_START, PLAN_OUTCOME,
        EARLY_PLAN_START, EARLY_PLAN_USED, EARLY_PLAN_CANCELED)

    // The reasons of a ScreenFallback that has no plan outcome. They are not on the trace.
    /** The plan step cannot run for this screen run, for example because the switch is off. */
    const val CLOSED = "closed"
    /** The early plan of the screen run was cancelled, and the request goes on. */
    const val EARLY_CANCELED = "early_canceled"

    // The failure codes of a change by lines that the kit could not make. ToolEngine.testAndFix reads them back as UNREPAIRED.
    /** A failed call or compile made no change, so a retry can work. */
    const val RETRY_FAILURE = "widget_generation"
    /** A change that the kit cannot apply to the saved app. */
    const val STATE_FAILURE = "widget_state"
    val BY_LINES_FAILURES = setOf(RETRY_FAILURE, STATE_FAILURE)
}

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
 * A screen of a spoken talk. Only the plan step builds it, never the loop: the voice has answered, and a screen from the loop comes too late.
 * [early]: the outcome of the plan call that started beside the routing call (ToolController.startEarlyPlan). Null: the run makes its own plan call.
 */
internal class ScreenRun(val early: Deferred<AppFileOutcome>? = null)

/** A screen run that gave no app. [reason] is a name of [AppFileNames], such as faults. The caller leaves the stage as it was. */
internal class ScreenFallback(val reason: String) : Exception(reason)

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
    /** The steps with the compiler and the prompt of [sandbox]. */
    constructor(sandbox: SandboxClient, call: suspend (CloudGenerationStage, String, String, AppFileLimits) -> AppFileReply,
        phase: (String, String?, Long?) -> Unit, progress: (ToolGenerationProgress) -> Unit) :
        this(AppFileCompiler(sandbox.context, sandbox), AppFilePrompt.load(sandbox.context), call, phase, progress)

    /** Sends [task] (the generation prompt of the request) to the plan call. */
    suspend fun plan(task: String, limits: AppFileLimits): AppFileOutcome {
        phase(AppFileNames.PLAN_CALL_START, null, null)
        return steps(CloudGenerationStage.APP_FILE, task, null, limits) { compiler.compileReply(it) }
    }

    /**
     * Sends [request] with the app file of [stored] to the change call. The reply holds only the lines that change: the kit merges them
     * into the stored plan and computes the next state from the stored state. A merged plan equal to the stored plan is Fallback("empty").
     */
    suspend fun change(request: String, stored: AppFilePrevious, limits: AppFileLimits): AppFileOutcome {
        phase(AppFileNames.CHANGE_CALL_START, null, null)
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
        phase(AppFileNames.REPAIR_CALL_START, null, null)
        val repaired = when (val reply = modelCall(CloudGenerationStage.APP_FILE, prompt.repairText(task, first.text, first.compilation.faults), limits)) {
            is AppFileReply.Failed -> return AppFileOutcome.Fallback(reply.reason)
            is AppFileReply.Text -> compiled { compiler.patch(first.text, reply.text, stored) }
        }
        if (repaired is AppFileRead.Outside) return AppFileOutcome.Fallback(AppFileNames.OUTSIDE)
        return outcome(repaired as AppFileRead.Plan, 2, stored) ?: AppFileOutcome.Fallback(AppFileNames.FAULTS)
    }

    private suspend fun modelCall(stage: CloudGenerationStage, input: String, limits: AppFileLimits): AppFileReply {
        progress(ToolGenerationProgress.COMPLETING)
        return call(stage, prompt.system, input, limits)
    }

    /** The outcome of a compiled plan, or null for faults that a repair call can fix. A model cannot fix the saved data, so it gets no repair call. */
    private fun outcome(plan: AppFileRead.Plan, calls: Int, stored: AppFilePrevious?): AppFileOutcome? {
        val compilation = plan.compilation
        return when {
            compilation.savedDataFault -> AppFileOutcome.Fallback(AppFileNames.DATA_BLOCKS)
            compilation.hole -> AppFileOutcome.Fallback(AppFileNames.HOLE)
            compilation.status == "ok" && plan.text == stored?.appFile -> AppFileOutcome.Fallback(AppFileNames.EMPTY)
            compilation.status == "ok" -> AppFileOutcome.Clean(plan.text, JSONObject(checkNotNull(compilation.definition))
                .apply { compilation.language?.let { put("language", it) } }, calls, compilation.state)
            compilation.status != "faults" -> AppFileOutcome.Fallback(AppFileNames.COMPILE_STATUS + compilation.status)
            compilation.tooLong -> AppFileOutcome.Fallback(AppFileNames.FAULTS)
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
            phase(AppFileNames.COMPILE, status, (System.nanoTime() - started) / 1_000_000)
            if (status != "BUSY" || ++tries > 1) return result
            delay(BUSY_WAIT_MS)
        }
    }

    private companion object {
        const val BUSY_WAIT_MS = 500L
    }
}

/**
 * The plan step of a new app and the change step of a saved app with an app file, before the loop of [AppAgent.run].
 * It returns the app of a clean plan that passes the host checks ([kitApp]), throws the failure of a change that the kit cannot make,
 * returns [rebuild] for such a change of an app that holds no data of the person, or returns null: the loop goes on.
 * A screen run with no base never returns null: it returns the app or throws [ScreenFallback] (or the stop of the request).
 * [previous]: the stored spec of the base, null for a rebuild. [checkActive] stops a cancelled run. [byLines] marks the start of the change step.
 */
internal suspend fun routeAppFile(
    request: AppAgentRequest,
    previous: ToolSpec?,
    sandbox: SandboxClient,
    appFileCall: (suspend (CloudGenerationStage, String, String, AppFileLimits) -> AppFileReply)?,
    checkActive: suspend () -> Unit,
    kitApp: suspend (AppFileOutcome.Clean) -> AppAgentResult?,
    byLines: () -> Unit,
    rebuild: suspend () -> AppAgentResult,
): AppAgentResult? {
    fun appFileSteps(call: suspend (CloudGenerationStage, String, String, AppFileLimits) -> AppFileReply) =
        AppFileSteps(sandbox, call, request::phase) { request.progress?.invoke(it) }

    // The plan step of a new app. A clean plan that passes the host checks is the app.
    // Each other outcome goes on with the loop of today, and the loop gets nothing of the plan.
    // A screen run (a new screen of a spoken talk) ends at the plan step: each other outcome and a closed gate throw ScreenFallback.
    val planStep = request.planStep
    val screen = request.screen.takeIf { request.base == null }
    val gate = planStep != null && appFileCall != null && request.base == null && request.lesson == null && request.images.isEmpty() &&
        request.sceneAssets.isEmpty() && !request.testFix
    if (screen != null && !gate) throw ScreenFallback(AppFileNames.CLOSED)
    if (gate) {
        val outcome = screen?.early?.let { earlyOutcome(it, checkActive) } ?: appFileSteps(appFileCall).plan(request.prompt, planStep)
        checkActive()
        val reason = when (outcome) {
            is AppFileOutcome.Outside -> AppFileNames.OUTSIDE
            is AppFileOutcome.Fallback -> outcome.reason
            is AppFileOutcome.Clean -> kitApp(outcome)?.let { return it } ?: AppFileNames.HOST_CHECK
        }
        request.phase(AppFileNames.PLAN_OUTCOME, if (outcome is AppFileOutcome.Outside) "outside:${outcome.word}" else "fallback:$reason")
        if (screen != null) throw ScreenFallback(reason)
    }

    // The change step of a saved app with an app file, for a change and for test and fix. The report of test and fix is the request.
    // The loop never gets such an app, so no write or edit can drop a saved value.
    val base = request.base
    if (previous?.appFile != null && base != null) {
        val call = checkNotNull(appFileCall) { "An app with an app file changes only through the kit." }
        byLines()
        val outcome = appFileSteps(call).change(request.prompt, AppFilePrevious(previous.appFile, previous.schema.toString(), base.state),
            AppFileLimits())
        checkActive()
        val reason = when (outcome) {
            is AppFileOutcome.Clean -> kitApp(outcome)?.let { return it } ?: AppFileNames.HOST_CHECK
            is AppFileOutcome.Outside -> AppFileNames.OUTSIDE
            is AppFileOutcome.Fallback -> outcome.reason
        }
        request.phase(AppFileNames.PLAN_OUTCOME, "fallback:$reason")
        val context = sandbox.context
        // A change that changes no line is not finished. Test and fix then found no line to repair.
        if (reason == AppFileNames.EMPTY) throw CloudFailure("widget_agent_incomplete",
            context.getString(if (request.testFix) R.string.tool_test_fix_no_line else R.string.tool_generation_unchanged))
        // A failed call or compile made no change, so a retry can work. Each other reason is a change that the kit cannot apply.
        val failure = if (reason in AppFileNames.CALL_FAILURES || reason.startsWith(AppFileNames.COMPILE_STATUS))
            CloudFailure(AppFileNames.RETRY_FAILURE, context.getString(R.string.tool_generation_connection))
        else CloudFailure(AppFileNames.STATE_FAILURE, context.getString(R.string.tool_generation_state))
        // Test and fix by lines never goes to the loop, which has no app to run here.
        // An app that holds data of the person keeps the app, its data, and its revision.
        if (request.testFix || previous.holdsDataOfThePerson(base.state)) throw failure
        return rebuild()
    }
    return null
}

/**
 * The outcome of the plan call that started beside the routing call. A stop of the request goes up as a stop ([checkActive]).
 * A cancel of the early plan alone is a fallback: a stop would leave the request with no end state, and an error would show a failure.
 */
private suspend fun earlyOutcome(early: Deferred<AppFileOutcome>, checkActive: suspend () -> Unit): AppFileOutcome = try {
    early.await()
} catch (failure: Exception) {
    if (failure !is CancellationException && (failure as? CloudFailure)?.code != "cancelled") throw failure
    checkActive()
    throw ScreenFallback(AppFileNames.EARLY_CANCELED)
}
