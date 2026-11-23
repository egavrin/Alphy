package com.alpha.assistant.tools.agent

import com.alpha.assistant.R
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.AppFileLimits
import com.alpha.assistant.tools.AppFileReply
import com.alpha.assistant.tools.StagePreviewFrame
import com.alpha.assistant.tools.ToolGenerationProgress
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.holdsDataOfThePerson
import com.alpha.assistant.tools.logFallback
import com.alpha.assistant.tools.appfile.AppFileCompilation
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.AppFilePrevious
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.AppFileRead
import com.alpha.assistant.tools.appfile.DataCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

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
    /** A template call whose reply, after its repair, gives no definition: the first line stays faulty, or no part is valid. */
    const val TEMPLATE_WHOLE = "template_whole"
    /** A clean template of a new screen that fails a host check. It gets no repair call. */
    const val TEMPLATE_CHECK = "template_check"
    /** The start of the reason of a compile that ended with a sandbox status, such as compile_TIMEOUT. */
    const val COMPILE_STATUS = "compile_"
    /** The reasons of an app-file call that ended before a reply (appFileCall). */
    val CALL_FAILURES = setOf(CALL_FAILED, FIRST_TEXT_TIMEOUT, TOTAL_TIMEOUT)

    // The reasons of a failed host check of a clean plan (KitCheck.Failed): the trace detail fallback:host_check:<reason>.
    /** The sandbox statuses that are a fault of the host, not of the plan. The host check runs once more after one of them. */
    val HOST_CHECK_RETRIES = setOf("javascript_timeout", "javascript_busy")
    /** Each sandbox status of a host check, as sandboxCode names it. */
    val SANDBOX_STATUSES = HOST_CHECK_RETRIES + setOf("javascript_unavailable", "javascript_sandbox_died", "javascript_closed",
        "javascript_memory_limit", "javascript_script_error", "javascript_invalid_result", "javascript_invalid_request")
    /** The check of AppAgent.validate that refused the plan: a fault of the plan, which gets no second try. */
    enum class Finding { DEFINITION, PICTURE, THREE_D, STATE, LESSON, LANGUAGE, PRESENTATION, SMOKE;
        val reason get() = name.lowercase(Locale.ROOT)
    }
    /** A raw float number on the first screen, which the loop refuses in finish. */
    const val LONG_DECIMAL = "long_decimal"
    /** Any other failure of a host check. */
    const val HOST_ERROR = "error"
    val HOST_CHECKS = SANDBOX_STATUSES + Finding.entries.map { it.reason } + LONG_DECIMAL + HOST_ERROR

    /** Each reason other than [COMPILE_STATUS] with its status. */
    val FALLBACKS = CALL_FAILURES + setOf(DATA_BLOCKS, HOLE, EMPTY, FAULTS, OUTSIDE, TEMPLATE_WHOLE, TEMPLATE_CHECK) + HOST_CHECKS.map { "$HOST_CHECK:$it" }

    // The trace phases of the plan step and the change step.
    const val PLAN_CALL_START = "plan_call_start"
    const val CHANGE_CALL_START = "change_call_start"
    const val REPAIR_CALL_START = "repair_call_start"
    const val PLAN_FIRST_TEXT = "plan_first_text"
    const val PLAN_REPLY_END = "plan_reply_end"
    const val COMPILE = "compile"
    const val PLAN_OUTCOME = "plan_outcome"
    // The template call of a new screen and its outcome, with a detail of TEMPLATE_OUTCOMES.
    const val TEMPLATE_CALL_START = "template_call_start"
    const val TEMPLATE_OUTCOME = "template_outcome"
    /** The host check of a searched data form turned it into nodata (block 2 plan, decision 3). The trace keeps no value of it. */
    const val DATA_REJECTED = "data_rejected"
    /** Clean with no repair, clean after the repair, lines left out after the repair, outside, a whole fault, or a failed call or sandbox. */
    val TEMPLATE_OUTCOMES = setOf("clean0", "clean1", "partial1", "outside", "whole", "failed")
    // The plan call of a spoken request that starts beside its routing call (ToolController.startEarlyPlan), and its end.
    const val EARLY_PLAN_START = "early_plan_start"
    const val EARLY_PLAN_USED = "early_plan_used"
    const val EARLY_PLAN_CANCELED = "early_plan_canceled"
    /** The start of the phase of the second try of a host check, such as host_check_retry:javascript_busy. */
    const val HOST_CHECK_RETRY = "host_check_retry:"
    /** The first condition that closed the plan step of a new app, in the order of [routeAppFile]: the phase plan_skipped:<condition>. */
    enum class PlanSkip { PLAN_STEP, PLAN_CALL, LESSON, IMAGES, SCENE_ASSETS, TEST_FIX;
        val phase get() = "plan_skipped:" + name.lowercase(Locale.ROOT)
    }
    // The stage previews of a screen run (stage templates, Task 16): a frame that the stage shows, with a detail of PREVIEW_HOLDS, a
    // shown frame that went away with no app, and the end of busy after an accepted screen run.
    const val STAGE_PREVIEW = "stage_preview"
    const val STAGE_PREVIEW_GONE = "stage_preview_gone"
    const val CONTROLS_ON = "controls_on"
    // The narration of a scene whose controls came on before its call ended (plan "one scene", decision 6): saved at the same revision, or dropped.
    const val NARRATION_SAVED = "narration_saved"
    const val NARRATION_DROPPED = "narration_dropped"
    // The decision of a follow call (plan "one scene", decision 18), with its kind as the detail.
    const val FOLLOW = "follow"
    /** The candidates of the library in a template call or a template change call (fix list 4, K4): a count and vectors, words, or none. */
    const val LIBRARY_CANDIDATES = "library_candidates"
    /** The holds value of a frame (kit previewStep): the first line, parts, the card, or a whole compiled plan. */
    val PREVIEW_HOLDS = setOf("frame", "parts", "card", "whole")
    val PHASES = setOf(PLAN_CALL_START, PLAN_FIRST_TEXT, PLAN_REPLY_END, COMPILE, REPAIR_CALL_START, CHANGE_CALL_START, PLAN_OUTCOME,
        EARLY_PLAN_START, EARLY_PLAN_USED, EARLY_PLAN_CANCELED, TEMPLATE_CALL_START, TEMPLATE_OUTCOME, STAGE_PREVIEW, STAGE_PREVIEW_GONE,
        CONTROLS_ON, NARRATION_SAVED, NARRATION_DROPPED, FOLLOW, DATA_REJECTED, LIBRARY_CANDIDATES) + HOST_CHECK_RETRIES.map { HOST_CHECK_RETRY + it } + PlanSkip.entries.map { it.phase }

    // The reasons of a ScreenFallback that has no plan outcome. They are not on the trace.
    /** The plan step cannot run for this screen run, for example because the switch is off. */
    const val CLOSED = "closed"
    /** The early plan of the screen run was cancelled, and the request goes on. */
    const val EARLY_CANCELED = "early_canceled"
    /** The ScreenFallback reason of a change by lines of the shown screen that gave no app. Its plan outcome is on the trace. */
    const val CHANGE = "change"
    /** The prompt of the screen with the dialogue text passes the generator limit, so no plan call starts. */
    const val PROMPT_SIZE = "prompt_size"

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
     * [frame]: the frame of the compile, which the stage shows while the host check runs.
     * [narration]: the template call of a new scene that goes on after its clean head (plan "one scene", decision 6). It gives the kept text
     * of the whole reply with its narration lines, or null. Its owner cancels it when it does not save it.
     */
    class Clean(val appFile: String, val definition: JSONObject, val calls: Int, val state: String? = null,
        val frame: StagePreviewFrame? = null, val narration: Deferred<String?>? = null) : AppFileOutcome
    class Outside(val word: String) : AppFileOutcome
    /** [reason] is a reason of the trace detail fallback:<reason>. */
    class Fallback(val reason: String) : AppFileOutcome
}

/**
 * A screen of a spoken talk. Only the plan step builds it, never the loop: the voice has answered, and a screen from the loop comes too late.
 * [early]: the outcome of the plan call that started beside the routing call (ToolController.startEarlyPlan). Null: the run makes its own plan call.
 * [template]: the task of the template call of a new screen (AppFileSteps.screen). Null: the run sends only the plan call.
 * [preview]: shows a frame on the stage (ToolController sets it). It gets the frame of each complete line of a template call and the frame
 * of a clean plan before its host check. It never blocks. Null: the run shows no frame.
 * [outlive]: the scope of the template call of a new screen, which can go on after the run (AppFileSteps).
 * [narration]: set by a run whose app came from a clean head, for the caller that stores the app (AppFileOutcome.Clean.narration).
 * [data]: the template call has the section Template data (block 2 plan, D1). [check]: the host check of a searched data form, null for known facts.
 */
internal class ScreenRun(val early: Deferred<AppFileOutcome>? = null, val template: String? = null,
    val preview: ((StagePreviewFrame) -> Unit)? = null, val outlive: CoroutineScope? = null,
    val data: Boolean = false, val check: DataCheck? = null,
    /** The detail of the trace mark library_candidates of its template call or template change call, such as "24 vectors" (fix list 4, K4). */
    val library: String? = null) {
    @Volatile var narration: Deferred<String?>? = null
}

/** The app-file call of the plan step and the change step: stage, instructions, input, limits, and a consumer of each text delta. */
internal typealias AppFileCall = suspend (CloudGenerationStage, String, String, AppFileLimits, ((String) -> Unit)?) -> AppFileReply

/**
 * A screen run that gave no app. [reason] is a name of [AppFileNames], such as faults. [inner]: the plan outcome behind
 * [AppFileNames.CHANGE], or the host check behind [AppFileNames.TEMPLATE_CHECK], for the release log. The caller leaves the stage as it was.
 */
internal class ScreenFallback(val reason: String, val inner: String? = null) : Exception(reason)

/** The host checks of a clean plan (kitApp of [routeAppFile]): the app, or the check that failed. */
internal sealed interface KitCheck {
    class App(val result: AppAgentResult) : KitCheck
    /** [reason] is a name of [AppFileNames.HOST_CHECKS]. */
    class Failed(val reason: String) : KitCheck {
        val fallback get() = "${AppFileNames.HOST_CHECK}:$reason"
    }
}

/** The wait before one more try of a sandbox step that met a busy slot or a deadline: an open app runs an event in the slot. */
internal const val SANDBOX_RETRY_MS = 500L
/** The tries of the narration compile after a busy slot: 8 s in all (plan "one scene", decision 6). */
private const val NARRATION_BUSY_TRIES = 16

/**
 * The plan step of a new app and the change step of a saved app with an app file: one model call, its compile in the sandbox,
 * and at most one repair call. [phase] marks the trace phases. [progress] shows the states that the loop shows at its start.
 * [outlive]: a scope that outlives the run (the scope of ToolController). With it, the template call of a new screen runs there, so a clean head
 * can become the app while the call goes on with the narration lines (plan "one scene", decision 6). Null: the run waits for the end of the call.
 */
internal class AppFileSteps(
    private val compiler: AppFileCompiler,
    val prompt: AppFilePrompt,
    private val call: AppFileCall,
    private val phase: (String, String?, Long?) -> Unit,
    private val progress: (ToolGenerationProgress) -> Unit,
    private val outlive: CoroutineScope? = null,
) {
    /** The steps with the compiler and the prompt of [sandbox]. */
    constructor(sandbox: SandboxClient, call: AppFileCall, phase: (String, String?, Long?) -> Unit, progress: (ToolGenerationProgress) -> Unit,
        outlive: CoroutineScope? = null) :
        this(AppFileCompiler(sandbox.context, sandbox), AppFilePrompt.load(sandbox.context), call, phase, progress, outlive)

    /** Sends [task] (the generation prompt of the request) to the plan call. */
    suspend fun plan(task: String, limits: AppFileLimits): AppFileOutcome {
        phase(AppFileNames.PLAN_CALL_START, null, null)
        return steps(CloudGenerationStage.APP_FILE, task, null, limits) { compiler.compileReply(it) }
    }

    /**
     * A new screen of a spoken talk: sends [templateTask] to the template call. A reply outside sends [planTask] to the plan call ([plan]).
     * A template with a definition is the app with its kept text, also with lines that the kit left out after the repair.
     * [preview] gets the frame of the complete lines of the first template call while it streams (stage templates, Task 17).
     * With [data], the template call has the section Template data, and [check] is the host check of each compile of a searched form.
     */
    suspend fun screen(templateTask: String, planTask: String, limits: AppFileLimits,
        preview: ((StagePreviewFrame) -> Unit)? = null, data: Boolean = false, check: DataCheck? = null, library: String? = null): AppFileOutcome {
        library?.let { phase(AppFileNames.LIBRARY_CANDIDATES, it, null) }
        phase(AppFileNames.TEMPLATE_CALL_START, null, null)
        val instructions = if (data) prompt.templateData else prompt.template
        val outcome = templateSteps(templateTask, null, limits, preview, instructions, check) { compiler.compileReply(it, check) }
        return if (outcome is AppFileOutcome.Outside) plan(planTask, limits) else outcome
    }

    /**
     * Sends [request] with the app file of [stored] to the change call. The reply holds only the lines that change: the kit merges them
     * into the stored plan and computes the next state from the stored state. A merged plan equal to the stored plan is Fallback("empty").
     * With [template] (a spoken change of a stored template file), the template change call goes first, and its reply outside goes on with
     * the change call. The change call gets the app file of the expansion of a stored template file, so the app then has a free app file.
     * [preview] gets the frame of the complete changed lines of the template change call over the stored file and state (Task 18).
     * [templateTask] is the request of the template change call with the ready objects of the library, and [library] the detail of its trace
     * mark (fix list 4, K3 and K4). The change call outside a template gets [request] alone.
     */
    suspend fun change(request: String, stored: AppFilePrevious, limits: AppFileLimits, template: Boolean,
        preview: ((StagePreviewFrame) -> Unit)? = null, templateTask: String? = null, library: String? = null): AppFileOutcome {
        if (template) library?.let { phase(AppFileNames.LIBRARY_CANDIDATES, it, null) }
        phase(AppFileNames.CHANGE_CALL_START, null, null)
        if (template) templateSteps(prompt.templateChangeText(templateTask ?: request, stored.appFile), stored, limits, preview, prompt.template, null) {
            compiler.patch(stored.appFile, it, stored)
        }.let { if (it !is AppFileOutcome.Outside) return it }
        val free = if (!prompt.isTemplate(stored.appFile)) stored else {
            val compilation = (compiled { AppFileRead.Plan(stored.appFile, compiler.compile(stored.appFile)) } as AppFileRead.Plan).compilation
            stored.copy(appFile = compilation.expanded ?: return AppFileOutcome.Fallback(if (compilation.status == "ok" || compilation.status == "faults")
                AppFileNames.TEMPLATE_WHOLE else AppFileNames.COMPILE_STATUS + compilation.status))
        }
        return steps(CloudGenerationStage.APP_FILE_CHANGE, prompt.changeText(request, free.appFile), free, limits) {
            compiler.patch(free.appFile, it, free)
        }
    }

    /**
     * The template call with [task], the compile of its reply by [read], and at most one repair call that merges its lines by name.
     * [stored]: the stored template of a change. A new screen marks its outcome on the trace.
     * The first call streams its complete lines to [preview] ([streamed]). When the first call of a new screen stops after a complete first
     * line, one call asks for the missing lines, and the kit merges its reply by name (design section 2, case 3).
     * [instructions] is the section of each call. [check] is the host check of a searched data form in each compile and frame.
     */
    private suspend fun templateSteps(task: String, stored: AppFilePrevious?, limits: AppFileLimits, preview: ((StagePreviewFrame) -> Unit)?,
        instructions: String, check: DataCheck?, read: suspend (String) -> AppFileRead): AppFileOutcome {
        fun end(detail: String, outcome: AppFileOutcome) = outcome.also { if (stored == null) phase(AppFileNames.TEMPLATE_OUTCOME, detail, null) }
        var calls = 1
        val streamed = streamed(task, limits, stored, preview, instructions, check, read)
        if (streamed is Streamed.Early) return end("clean0", streamed.outcome)
        val (reply, arrived) = streamed as Streamed.Reply
        val first = when (reply) {
            is AppFileReply.Text -> compiled { read(reply.text) }
            is AppFileReply.Failed -> {
                if (stored != null || !prompt.isTemplate(arrived)) return end("failed", AppFileOutcome.Fallback(reply.reason))
                calls++
                when (val rest = templateCall(instructions, prompt.templateRestText(task, arrived), limits)) {
                    is AppFileReply.Failed -> return end("failed", AppFileOutcome.Fallback(rest.reason))
                    is AppFileReply.Text -> compiled { compiler.patch(arrived, rest.text, null, check) }
                }
            }
        }
        // A rest reply outside cannot finish a screen that the stage already shows.
        if (first is AppFileRead.Outside) return if (calls == 1) end("outside", AppFileOutcome.Outside(first.word))
            else end("whole", AppFileOutcome.Fallback(AppFileNames.TEMPLATE_WHOLE))
        first as AppFileRead.Plan
        if (first.compilation.rejected != null) phase(AppFileNames.DATA_REJECTED, null, null)
        templateOutcome(first, false, stored, calls)?.let { return end(templateDetail(it, first, false), it) }
        // A reply that is no template file gets the repair too. The kit merges a repair into a plan of its own kind.
        phase(AppFileNames.REPAIR_CALL_START, null, null)
        val repaired = when (val repair = templateCall(instructions, prompt.templateRepairText(task, first.text, first.compilation.faults), limits)) {
            is AppFileReply.Failed -> return end("failed", AppFileOutcome.Fallback(repair.reason))
            is AppFileReply.Text -> compiled { compiler.patch(first.text, repair.text, stored, check) }
        }
        if (repaired is AppFileRead.Outside) return end("whole", AppFileOutcome.Fallback(AppFileNames.TEMPLATE_WHOLE))
        repaired as AppFileRead.Plan
        if (repaired.compilation.rejected != null) phase(AppFileNames.DATA_REJECTED, null, null)
        val outcome = checkNotNull(templateOutcome(repaired, true, stored, calls + 1))
        return end(templateDetail(outcome, repaired, true), outcome)
    }

    /** The reply of the first template call and its complete lines, or the clean head of a new screen whose call goes on ([streamed]). */
    private sealed interface Streamed {
        data class Reply(val reply: AppFileReply, val arrived: String) : Streamed
        class Early(val outcome: AppFileOutcome.Clean) : Streamed
    }

    /**
     * The first template call with the stream of its text (stage templates, Task 17). The buffer always takes the text, so a stopped call
     * keeps its complete lines. With [preview], a loop shows the frame of the newest complete lines when it is free, over [stored] for a
     * change. The loop closes when the call ends, and the join waits only for the update in flight, so the compile after it meets no busy
     * slot. Gives the reply and the complete lines that arrived.
     * With [outlive], a new screen compiles the complete lines before its first narration line when that line starts and they hold the card.
     * A clean compile is the app at once, and the call goes on in [outlive] for the narration. Each other case waits for the end of the call.
     */
    private suspend fun streamed(input: String, limits: AppFileLimits, stored: AppFilePrevious?,
        preview: ((StagePreviewFrame) -> Unit)?, instructions: String, check: DataCheck?, read: suspend (String) -> AppFileRead): Streamed = coroutineScope {
        val stream = TemplateStream()
        val loop = preview?.let { show -> launch { stream.collect { lines -> compiler.preview(lines, stored?.appFile, stored, check)?.let(show) } } }
        val outlive = outlive?.takeIf { stored == null }
        if (outlive == null) {
            val reply = try { templateCall(instructions, input, limits, stream::append) } finally { stream.close() }
            loop?.join()
            return@coroutineScope Streamed.Reply(reply, stream.completeLines())
        }
        val call = outlive.async(Dispatchers.IO) { try { templateCall(instructions, input, limits, stream::append) } finally { stream.close() } }
        var handed = false
        try {
            val head = stream.narration.await()
            if (head != null && CARD_LINE.containsMatchIn(head)) {
                // The preview stops first, so the compile of the head meets no busy slot.
                stream.stopCollect()
                loop?.join()
                val plan = compiled { read(head) } as? AppFileRead.Plan
                val clean = plan?.let { templateOutcome(it, false, null, 1) } as? AppFileOutcome.Clean
                if (clean != null) {
                    // The host check of a searched data form runs on the head too, and a rejected head is a stored nodata card.
                    if (plan.compilation.rejected != null) phase(AppFileNames.DATA_REJECTED, null, null)
                    handed = true
                    val narration = outlive.async(Dispatchers.IO) { try { narrated(call, clean.appFile, check) } finally { call.cancel() } }
                    return@coroutineScope Streamed.Early(AppFileOutcome.Clean(clean.appFile, clean.definition, clean.calls, clean.state, clean.frame, narration))
                }
            }
            val reply = call.await()
            loop?.join()
            Streamed.Reply(reply, stream.completeLines())
        } finally { if (!handed) call.cancel() }
    }

    /**
     * The kept text of the whole reply of [call] when it adds only narration lines to [head], the kept text of the stored scene. Null for a
     * failed call, a faulty or busy compile, no narration, or another changed line: the stored definition must stay the one of [head].
     */
    private suspend fun narrated(call: Deferred<AppFileReply>, head: String, check: DataCheck?): String? {
        val reply = call.await() as? AppFileReply.Text ?: return null
        var tries = 0
        while (true) {
            val compilation = (compiler.compileReply(reply.text, check) as? AppFileRead.Plan)?.compilation ?: return null
            // The host check of the head and the first events of the stored scene hold the slot at this time, and the voice says the opener
            // first, so the narration waits longer for the slot than a compile that the screen waits on.
            if (compilation.status == "BUSY" && tries++ < NARRATION_BUSY_TRIES) { delay(SANDBOX_RETRY_MS); continue }
            val kept = compilation.kept?.takeIf { compilation.status == "ok" && compilation.faults.isEmpty() && compilation.definition != null } ?: return null
            // A nodata head gets the say line of its card (kit D5), and the narration of the reply replaces it.
            return kept.takeIf { it != head && it.lines().filterNot(NARRATION_LINE::matches) == head.lines().filterNot(NARRATION_LINE::matches) }
        }
    }

    private companion object {
        val CARD_LINE = Regex("""(?m)^[ \t]*card[ \t]*=""")
        /** A narration line as the kit keeps it (template.js). */
        val NARRATION_LINE = Regex("""say(?:[1-9]\d*)? = .*""")
    }

    private suspend fun templateCall(instructions: String, input: String, limits: AppFileLimits, onText: ((String) -> Unit)? = null): AppFileReply {
        progress(ToolGenerationProgress.COMPLETING)
        return call(CloudGenerationStage.APP_FILE_TEMPLATE, instructions, input, limits, onText)
    }

    /**
     * The outcome of a compiled template, or null for faults that a repair call can fix ([repaired] false). A template with a definition is
     * Clean with its kept text: after the repair also with faults, because the kit left out each faulty line. A model cannot fix the saved data.
     * [calls] counts the model calls so far.
     */
    private fun templateOutcome(plan: AppFileRead.Plan, repaired: Boolean, stored: AppFilePrevious?, calls: Int): AppFileOutcome? {
        val compilation = plan.compilation
        val kept = compilation.kept.takeIf { compilation.template != null && compilation.definition != null }
        return when {
            compilation.savedDataFault -> AppFileOutcome.Fallback(AppFileNames.DATA_BLOCKS)
            compilation.status != "ok" && compilation.status != "faults" -> AppFileOutcome.Fallback(AppFileNames.COMPILE_STATUS + compilation.status)
            kept != null && (repaired || compilation.faults.isEmpty()) ->
                if (kept == stored?.appFile) AppFileOutcome.Fallback(AppFileNames.EMPTY)
                else AppFileOutcome.Clean(kept, definitionOf(compilation), calls, compilation.state, compilation.frame)
            repaired || compilation.tooLong -> AppFileOutcome.Fallback(AppFileNames.TEMPLATE_WHOLE)
            else -> null
        }
    }

    /** The detail of [AppFileNames.TEMPLATE_OUTCOME] for [outcome], the outcome of [plan]. */
    private fun templateDetail(outcome: AppFileOutcome, plan: AppFileRead.Plan, repaired: Boolean) = when {
        outcome is AppFileOutcome.Clean -> if (!repaired) "clean0" else if (plan.compilation.faults.isEmpty()) "clean1" else "partial1"
        (outcome as? AppFileOutcome.Fallback)?.reason == AppFileNames.TEMPLATE_WHOLE -> "whole"
        else -> "failed"
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
        return call(stage, prompt.system, input, limits, null)
    }

    /** The outcome of a compiled plan, or null for faults that a repair call can fix. A model cannot fix the saved data, so it gets no repair call. */
    private fun outcome(plan: AppFileRead.Plan, calls: Int, stored: AppFilePrevious?): AppFileOutcome? {
        val compilation = plan.compilation
        return when {
            compilation.savedDataFault -> AppFileOutcome.Fallback(AppFileNames.DATA_BLOCKS)
            compilation.hole -> AppFileOutcome.Fallback(AppFileNames.HOLE)
            compilation.status == "ok" && plan.text == stored?.appFile -> AppFileOutcome.Fallback(AppFileNames.EMPTY)
            compilation.status == "ok" -> AppFileOutcome.Clean(plan.text, definitionOf(compilation), calls, compilation.state, compilation.frame)
            compilation.status != "faults" -> AppFileOutcome.Fallback(AppFileNames.COMPILE_STATUS + compilation.status)
            compilation.tooLong -> AppFileOutcome.Fallback(AppFileNames.FAULTS)
            else -> null
        }
    }

    /** The definition of a compile with its key language, as a model definition holds it. */
    private fun definitionOf(compilation: AppFileCompilation) =
        JSONObject(checkNotNull(compilation.definition)).apply { compilation.language?.let { put("language", it) } }

    /** One compile. A busy sandbox (an open app runs an event) gets one more try after [SANDBOX_RETRY_MS]. */
    private suspend fun compiled(read: suspend () -> AppFileRead): AppFileRead {
        progress(ToolGenerationProgress.VALIDATING)
        var tries = 0
        while (true) {
            val started = System.nanoTime()
            val result = read()
            val status = (result as? AppFileRead.Plan)?.compilation?.status ?: "ok"
            phase(AppFileNames.COMPILE, status, (System.nanoTime() - started) / 1_000_000)
            if (status != "BUSY" || ++tries > 1) return result
            delay(SANDBOX_RETRY_MS)
        }
    }
}

/**
 * The plan step of a new app and the change step of a saved app with an app file, before the loop of [AppAgent.run].
 * It returns the app of a clean plan that passes the host checks ([kitApp]), throws the failure of a change that the kit cannot make,
 * returns [rebuild] for such a change of an app that holds no data of the person, or returns null: the loop goes on.
 * A screen run never returns null and never calls [rebuild]: it returns the app or throws [ScreenFallback] (or the stop of the request).
 * [previous]: the stored spec of the base, null for a rebuild. [checkActive] stops a cancelled run. [byLines] marks the start of the change step.
 */
internal suspend fun routeAppFile(
    request: AppAgentRequest,
    previous: ToolSpec?,
    sandbox: SandboxClient,
    appFileCall: AppFileCall?,
    checkActive: suspend () -> Unit,
    kitApp: suspend (AppFileOutcome.Clean) -> KitCheck,
    byLines: () -> Unit,
    rebuild: suspend () -> AppAgentResult,
): AppAgentResult? {
    fun appFileSteps(call: AppFileCall) = AppFileSteps(sandbox, call, request::phase, { request.progress?.invoke(it) }, request.screen?.outlive)

    // The plan step of a new app. A clean plan that passes the host checks is the app.
    // Each other outcome goes on with the loop of today, and the loop gets nothing of the plan.
    // A screen run (a new screen of a spoken talk) ends at the plan step: each other outcome and a closed gate throw ScreenFallback.
    // A new app with no plan step names the first condition that closed it.
    val planStep = request.planStep
    val screen = request.screen.takeIf { request.base == null }
    val skip = when {
        planStep == null -> AppFileNames.PlanSkip.PLAN_STEP
        appFileCall == null -> AppFileNames.PlanSkip.PLAN_CALL
        request.lesson != null -> AppFileNames.PlanSkip.LESSON
        request.images.isNotEmpty() -> AppFileNames.PlanSkip.IMAGES
        request.sceneAssets.isNotEmpty() -> AppFileNames.PlanSkip.SCENE_ASSETS
        request.testFix -> AppFileNames.PlanSkip.TEST_FIX
        else -> null
    }
    val gate = request.base == null && skip == null
    if (request.base == null) skip?.let { request.phase(it.phase) }
    if (screen != null && !gate) throw ScreenFallback(AppFileNames.CLOSED)
    if (gate) {
        var check: String? = null
        val steps = appFileSteps(checkNotNull(appFileCall))
        val outcome = screen?.early?.let { earlyOutcome(it, checkActive) }
            ?: screen?.template?.let { steps.screen(it, request.prompt, checkNotNull(planStep), screen.preview, screen.data, screen.check, screen.library) }
            ?: steps.plan(request.prompt, checkNotNull(planStep))
        // Only an accepted screen keeps the call of its narration: each other end cancels it.
        val narration = (outcome as? AppFileOutcome.Clean)?.narration
        var handed = false
        val reason = try {
            checkActive()
            // The stage shows the compiled plan while the host check runs, a template and a free plan alike (design section 3).
            if (outcome is AppFileOutcome.Clean) outcome.frame?.let { screen?.preview?.invoke(it) }
            when (outcome) {
                is AppFileOutcome.Outside -> AppFileNames.OUTSIDE
                is AppFileOutcome.Fallback -> outcome.reason
                is AppFileOutcome.Clean -> when (val kit = kitApp(outcome)) {
                    is KitCheck.App -> {
                        screen?.narration = narration
                        handed = true
                        return kit.result
                    }
                    // A template gets no repair call for a host check: the screen run falls back, and the release log keeps the check.
                    is KitCheck.Failed -> if (steps.prompt.isTemplate(outcome.appFile)) AppFileNames.TEMPLATE_CHECK.also { check = kit.fallback } else kit.fallback
                }
            }
        } finally { if (!handed) narration?.cancel() }
        request.phase(AppFileNames.PLAN_OUTCOME, if (outcome is AppFileOutcome.Outside) "outside:${outcome.word}" else "fallback:$reason")
        if (screen != null) throw ScreenFallback(reason, check)
        // The shell logs a screen run with its decision kind. Each other build logs here, where it goes on to the loop (ruling FX-9).
        logFallback(request.artifactId, "plan", reason)
    }

    // The change step of a saved app with an app file, for a change and for test and fix. The report of test and fix is the request.
    // The loop never gets such an app, so no write or edit can drop a saved value.
    // A screen run with a base (a spoken change of the shown screen) ends at the change step: each other outcome and a closed gate throw ScreenFallback.
    val base = request.base
    if (request.screen != null && base != null && (previous?.appFile == null || appFileCall == null)) throw ScreenFallback(AppFileNames.CLOSED)
    if (previous?.appFile != null && base != null) {
        val call = checkNotNull(appFileCall) { "An app with an app file changes only through the kit." }
        byLines()
        val steps = appFileSteps(call)
        // Only a spoken change sends a template change call. Each other change makes the app a free app file.
        val template = request.screen != null && steps.prompt.isTemplate(previous.appFile)
        val outcome = steps.change(request.prompt, AppFilePrevious(previous.appFile, previous.schema.toString(), base.state),
            AppFileLimits(), template, request.screen?.preview, request.screen?.template, request.screen?.library)
        checkActive()
        // A change by template lines shows its compiled file while the host check runs. A change outside a template has no preview (Task 18).
        if (template && outcome is AppFileOutcome.Clean && steps.prompt.isTemplate(outcome.appFile)) outcome.frame?.let { request.screen.preview?.invoke(it) }
        val reason = when (outcome) {
            is AppFileOutcome.Clean -> when (val check = kitApp(outcome)) {
                is KitCheck.App -> return check.result
                is KitCheck.Failed -> check.fallback
            }
            is AppFileOutcome.Outside -> AppFileNames.OUTSIDE
            is AppFileOutcome.Fallback -> outcome.reason
        }
        request.phase(AppFileNames.PLAN_OUTCOME, "fallback:$reason")
        if (request.screen != null) throw ScreenFallback(AppFileNames.CHANGE, reason)
        logFallback(request.artifactId, "change", AppFileNames.CHANGE, reason)
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
