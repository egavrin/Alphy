package com.alpha.assistant.tools.agent

import com.alpha.assistant.R
import com.alpha.assistant.capabilities.CapabilityCatalog
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudTurn
import com.alpha.assistant.images.GeneratedImage
import com.alpha.assistant.images.ImageFailure
import com.alpha.assistant.images.ImageAspect
import com.alpha.assistant.images.ImageIntent
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.semantic.SemanticRuntime
import com.alpha.assistant.semantic.SemanticUnavailable
import com.alpha.assistant.tools.AppFileLimits
import com.alpha.assistant.tools.AppFileReply
import com.alpha.assistant.tools.AppLanguage
import com.alpha.assistant.tools.GenerationLanguage
import com.alpha.assistant.tools.ToolGenerationProgress
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolGenerationSmoke
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.canvasSurfaceIds
import com.alpha.assistant.tools.observeSurfaceGeometry
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSmokeFailure
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.ToolUiDiagnostic
import com.alpha.assistant.tools.ToolUiValidationStage
import com.alpha.assistant.tools.ToolValidationFailure
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.AppFilePrevious
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.imageSlots
import com.alpha.assistant.tools.sameToolJson
import com.alpha.assistant.tools.three.ThreeSceneAssetRef
import com.alpha.assistant.tools.three.ThreeSceneFailure
import com.alpha.assistant.tools.three.ThreeSceneProblem
import com.alpha.assistant.tools.three.ThreeSceneWebView
import com.alpha.assistant.tools.three.checkThreeScene
import com.alpha.assistant.tools.three.withThreeScene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class AppAgentRequest(
    val prompt: String,
    val base: ToolRecord?,
    val images: List<String>,
    val artifactId: String,
    val privateState: Boolean,
    val extraInstructions: String = "",
    val onNote: (suspend (String) -> Unit)? = null,
    val progress: ((ToolGenerationProgress) -> Unit)? = null,
    val onStep: ((step: Int, tool: String, reply: String, result: String, durationNanos: Long) -> Unit)? = null,
    /** Test and fix: finish may keep the app unchanged, and edits need a reproduced problem first. */
    val testFix: Boolean = false,
    /** Makes pictures for this generation. Null when pictures are off. */
    val imageMaker: AppImageMaker? = null,
    /** 3D models that the user attached to this request. */
    val sceneAssets: List<ThreeSceneAssetRef> = emptyList(),
    /** The optional screenshot review: draws the first screen as an image data URL. Null when the review is off. */
    val screenshot: (suspend (ToolPreview) -> String)? = null,
    /** A course slide. Null for a mini-app. */
    val lesson: AppLessonTarget? = null,
    /** A lesson finish can request one illustration. True only when pictures are on. */
    val lessonIllustration: Boolean = false,
    /** Marks a named phase in the generation trace (ToolGenerationTrace.Run.phase). Null when no trace observer is set. */
    val onPhase: ((name: String, detail: String?, ms: Long?) -> Unit)? = null,
    /** The limits of the plan call before the loop. Null when the plan step is off for this request. */
    val planStep: AppFileLimits? = null,
) {
    fun phase(name: String, detail: String? = null, ms: Long? = null) {
        onPhase?.let { runCatching { it(name, detail, ms) } }
    }
}

/** Each call submits one new image operation. The host checks consent before each submission. */
internal fun interface AppImageMaker {
    suspend fun make(intents: List<ImageIntent>): List<GeneratedImage>
}

internal class AppAgentResult(val preview: ToolPreview, val summary: String, val steps: Int, val changed: Boolean,
    val lesson: AppLessonResult? = null)

/**
 * One generation: the model calls one tool in each step, and the host replies with the tool result.
 * The host validates every version and runs the model's checks. Only a passing finish ends the loop.
 */
internal class AppAgent(
    private val service: ToolGenerationService,
    private val sandbox: SandboxClient,
    /** The plan call of the plan step. Null: no plan step. */
    private val appFileCall: (suspend (stage: CloudGenerationStage, instructions: String, input: String, limits: AppFileLimits) -> AppFileReply)? = null,
    private val complete: suspend (turns: List<CloudTurn>, instructions: String) -> String,
) {
    private class Feedback(message: String) : Exception(message)

    private class Candidate(val definition: JSONObject, val spec: ToolSpec, val state: String, val preview: ToolPreview)

    private class Finished(val summary: String, val lesson: AppLessonResult? = null)

    private class Review(val image: String) {
        override fun toString() = SCREENSHOT_REVIEW
    }

    /** True after the host accepted a changed version. Read it also after a failed run. */
    var edited = false
        private set

    /** Test and fix: true after a run on the unchanged app confirmed a problem. */
    var reproduced = false
        private set

    /** True after the change step of an app with an app file started. No run reproduces a report on this path. */
    var byLines = false
        private set

    suspend fun run(request: AppAgentRequest, session: CloudSession): AppAgentResult = run(request, session, rebuild = false)

    /**
     * [rebuild]: the kit could not serve a change of an app with an app file, and the app holds no data of the person.
     * The loop then builds a new app in its place: it gets no plan step and nothing of the saved app.
     */
    private suspend fun run(request: AppAgentRequest, session: CloudSession, rebuild: Boolean): AppAgentResult {
        val stored = request.base?.let { ToolSpec.parsePersisted(it.spec) }
        val previous = stored.takeUnless { rebuild }
        // Device results, collections, and live state stay local. The model never sees their values or the saved layout.
        val privateApp = previous != null && (request.privateState || previous.capabilityResults || previous.collections.length() > 0)
        val lesson = request.lesson
        // The course player has no 3D renderer, and course slides have no capabilities. Lazy: an accepted plan does not pay the WebView check.
        val threeD by lazy { lesson == null && ThreeSceneWebView.available() }
        val history = mutableListOf<CloudTurn>()
        var draft: JSONObject? = previous?.let { JSONObject(it.providerDefinition()).apply { remove("formatVersion") } }
        var current: Candidate? = null
        val reproductions = mutableListOf<List<AppStep>>()
        var lastWriteIndex = -1
        var draftMatchesLastWrite = true
        var lastProblem = ""
        val savedImages = previous?.generatedImages.orEmpty()
        // Saved scene assets hold the user's models and the textures. Textures follow the pictures that the source binds.
        val models = (previous?.sceneAssets.orEmpty().filter { asset -> savedImages.none { it.asset.id == asset.id } } +
            request.sceneAssets).distinctBy { it.id }
        val madeImages = mutableListOf<GeneratedImage>()
        var submittedImages = 0
        var reviewed = false

        suspend fun checkActive() = session.checkActive(currentCoroutineContext())

        /**
         * [appFile]: the plan that the kit compiled into [next]. A model definition never sets it.
         * [migrated]: the next state that the kit computed from the saved state of a change.
         */
        suspend fun validate(next: JSONObject, appFile: String? = null, migrated: String? = null,
            onReplay: ((List<String>) -> Unit)? = null): Candidate {
            val definition = JSONObject(next.toString())
            val tag = definition.remove("language")
            val language: AppLanguage? = when {
                tag != null -> try { GenerationLanguage.parse(tag) }
                    catch (_: Exception) { throw Feedback("language must be a lowercase language tag, for example \"en\" or \"ru\".") }
                previous?.appLanguage != null -> AppLanguage(previous.appLanguage)
                else -> null
            }
            definition.put("formatVersion", 3)
            // A literal value in stateSchema names its type unambiguously.
            definition.optJSONObject("stateSchema")?.let { schema ->
                schema.keys().asSequence().toList().forEach { field ->
                    when (val value = schema.opt(field)) {
                        is Boolean -> schema.put(field, "boolean")
                        is Number -> schema.put(field, "number")
                        // A string that repeats its initial value is a literal, not a type name.
                        is String -> if (value !in scalarTypes && definition.optJSONObject("initialState")?.opt(field) == value) schema.put(field, "string")
                    }
                }
            }
            if (!definition.has("description")) definition.put("description", "")
            if (!definition.has("capabilities")) definition.put("capabilities", JSONArray())
            moveToneIntoStyle(definition.opt("ui"))
            val parsed = try {
                ToolSpec.parse(service.normalizeAgentDefinition(definition.toString()))
                    .withAppLanguage(language?.tag).withIcon(stored?.iconId).withAppFile(appFile)
            } catch (failure: ToolValidationFailure) {
                throw Feedback(listOfNotNull(failure.message, failure.uiDiagnostic?.correction,
                    failure.uiDiagnostic?.let { definitionHint(definition, it) }).joinToString(" "))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is JSONException) throw failure
                throw Feedback("The app definition is invalid: ${failure.message ?: failure.javaClass.simpleName}")
            }
            // The model sees image nodes, not the saved image records. The host attaches the records for the slots in use.
            val pictures = (savedImages + madeImages).associateBy { it.slot }
            val used = imageSlots(parsed.ui)
            (used - pictures.keys).firstOrNull()?.let { slot ->
                throw Feedback("The picture \"$slot\" does not exist. " + if (request.imageMaker != null)
                    "Make it with generate_image first, or remove the image node." else "Pictures are off. Remove the image node.")
            }
            val spec = if (!parsed.threeScene) parsed.withGeneratedImages(pictures.values.filter { it.slot in used }) else {
                if (!threeD) throw Feedback(NO_THREE)
                // The worker requires a binding for every listed texture, so only the slots that the source names are listed.
                val textures = pictures.values.filter { "\"${it.slot}\"" in parsed.source || "'${it.slot}'" in parsed.source }
                try {
                    parsed.withThreeAssets((models + textures.map { it.asset }).distinctBy { it.id }).withGeneratedImages(textures)
                } catch (failure: CancellationException) { throw failure } catch (failure: Exception) {
                    throw Feedback("The 3D app definition is invalid: ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
            val state = if (previous == null || request.base == null) spec.initialState else try {
                ToolSpec.stateForRevision(previous, spec, request.base.state, migrated)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw Feedback("The new state schema cannot hold the saved data. Keep every existing state field with its type, " +
                    "and give new fields initial values. ${failure.message.orEmpty()}")
            }
            lesson?.let { target ->
                try { target.policy.validateDefinition(spec.json) } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { throw Feedback("The slide breaks the course rules: ${failure.message.orEmpty()}") }
            }
            checkActive()
            if (spec.threeScene) {
                val preview = try {
                    checkThreeScene(spec, request.artifactId, (request.base?.revision ?: 0) + 1, state, checkActive = { checkActive() })
                } catch (failure: ThreeSceneFailure) {
                    if (failure.problem == ThreeSceneProblem.FOREGROUND) throw CloudFailure(failure.problem.code, failure.problem.guidance)
                    throw Feedback("The 3D scene check failed. ${AppSteps.failureText(failure)}")
                }
                if (language != null && GenerationLanguage.Check(language).mismatches(JSONObject(preview.ui), spec.title, spec.description)) {
                    throw Feedback("The visible text is not in ${GenerationLanguage.name(language.tag)}. Translate every title, label, option, and message.")
                }
                presentationProblem(JSONObject(preview.ui), JSONObject(preview.state))?.let { throw Feedback(it) }
                return Candidate(definition, spec, state, preview)
            }
            val smoke = ToolGenerationSmoke(sandbox, spec, request.artifactId, (request.base?.revision ?: 0) + 1, state,
                checkActive = { checkActive() }, generation = service, policy = lesson?.policy,
                observeReplay = onReplay?.let { { _: List<String> -> } })
            val preview = try {
                smoke.run()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: ToolSmokeFailure) {
                AppSteps.sandboxFailure(failure.cause)?.let { throw it }
                onReplay?.invoke(failure.replay.orEmpty())
                throw Feedback("The smoke test failed. ${failure.correction()} Cause: ${AppSteps.failureText(failure)}")
            } catch (failure: CloudFailure) {
                AppSteps.sandboxFailure(failure)?.let { throw it }
                throw Feedback("The smoke test failed: ${failure.code}: ${failure.message}")
            } catch (failure: Exception) {
                AppSteps.sandboxFailure(failure)?.let { throw it }
                if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is JSONException) throw failure
                throw Feedback("The smoke test failed: ${failure.message ?: failure.javaClass.simpleName}")
            }
            if (language != null && GenerationLanguage.Check(language).mismatches(JSONObject(preview.ui), spec.title, spec.description)) {
                throw Feedback("The visible text is not in ${GenerationLanguage.name(language.tag)}. Translate every title, label, option, and message.")
            }
            presentationProblem(JSONObject(preview.ui), JSONObject(preview.state))?.let { throw Feedback(it) }
            lesson?.let { checkLessonSlide(it, spec, preview) }
            return Candidate(definition, spec, state, preview)
        }

        // The 3D check already started the scene, so its first screen comes from that snapshot.
        suspend fun firstScreen(candidate: Candidate): String = if (candidate.spec.threeScene)
            "3D viewport \"${ThreeSceneTarget.SCENE}\": the first frame shows content.\n" + AppScreen.render(JSONObject(candidate.preview.ui),
                JSONObject(candidate.preview.state), JSONObject(), System.currentTimeMillis(), emptyMap()).text
        else smokeFor(candidate, request).probe { AppScreen.render(ui, state, surfaces, nowMillis, surfaceSizes).text }

        suspend fun accepted(tool: String, candidate: Candidate): String {
            current = candidate
            edited = true
            if (privateApp) return "$tool: OK. The app passed validation and the smoke test. $PRIVATE_SCREEN"
            val hints = designHints(JSONObject(candidate.preview.ui))
            return "$tool: OK. The app passed validation and the smoke test.\nFirst screen:\n${firstScreen(candidate)}\n$REVIEW_SCREEN" +
                if (hints.isEmpty()) "" else "\nDesign hints: ${hints.joinToString(" ")}"
        }

        suspend fun finishing(reply: JSONObject, candidate: Candidate): Any {
            if (!edited && !request.testFix) throw Feedback("Change the app before finish. The request asks for a change.")
            if (edited) for (steps in reproductions) {
                val outcome = drive(candidate, request) { AppSteps.run(this, steps, everyStep = false) }
                if (outcome.failure != null) return "finish: FAILED. The reproduced problem still fails: ${shown(outcome.failure, outcome, privateApp)}" +
                    (outcome.final?.takeUnless { privateApp }?.let { "\nScreen:\n${it.text}" } ?: "")
            }
            val finished = finish(reply, candidate, request, privateApp)
            val shot = request.screenshot.takeIf { lesson == null }
            return if (finished !is Finished || shot == null || reviewed || privateApp || candidate.spec.threeScene) finished
            else {
                // One review for each generation keeps the added time to one model step.
                reviewed = true
                // A capture failure skips the review. It must not block an app that passed its checks.
                try { Review(shot(candidate.preview)) } catch (failure: CancellationException) { throw failure } catch (_: Exception) { finished }
            }
        }

        // A write or an edit can carry the finish arguments, so a passing version ends the loop in the same step.
        suspend fun acceptedThenFinish(tool: String, candidate: Candidate, reply: JSONObject): Any {
            val text = accepted(tool, candidate)
            if (!reply.has("checks")) return text
            val result = try { finishing(reply, candidate) } catch (failure: Feedback) { "finish: REJECTED. ${failure.message}" }
            return if (result is String) "$text\n$result" else result
        }

        suspend fun handle(reply: JSONObject): Any {
            reply.optString("note").trim().takeIf { it.isNotEmpty() }?.let { note -> request.onNote?.invoke(note.take(160)) }
            return when (val tool = toolOf(reply)) {
                "write_app" -> {
                    request.progress?.invoke(ToolGenerationProgress.COMPLETING)
                    if (request.testFix && !reproduced) throw Feedback(REPRODUCE_FIRST)
                    if (privateApp) throw Feedback(PRIVATE_EDIT)
                    val app = objectArgument(reply, "app").also { liftFinish(reply, it) }
                    draft = app
                    lastWriteIndex = history.lastIndex
                    draftMatchesLastWrite = true
                    current = null
                    acceptedThenFinish(tool, validate(app), reply)
                }
                "edit_app" -> {
                    request.progress?.invoke(ToolGenerationProgress.COMPLETING)
                    if (request.testFix && !reproduced) throw Feedback(REPRODUCE_FIRST)
                    val base = draft ?: throw Feedback("There is no app yet. Call write_app first.")
                    if (privateApp && reply.optJSONObject("set")?.let { it.has("ui") || it.has("initialState") } == true) throw Feedback(PRIVATE_EDIT)
                    val next = applyEdits(base, reply, uiLocked = privateApp)
                    draft = next
                    draftMatchesLastWrite = false
                    current = null
                    acceptedThenFinish(tool, validate(next), reply)
                }
                "run" -> {
                    request.progress?.invoke(ToolGenerationProgress.VALIDATING)
                    val candidate = current ?: throw Feedback(noValidApp(draft))
                    val steps = try { AppSteps.parse(reply.opt("steps")) } catch (failure: AppStepError) { throw Feedback(failure.message.orEmpty()) }
                    val outcome = drive(candidate, request) { AppSteps.run(this, steps, everyStep = false) to requested }
                    val (result, requested) = outcome
                    val confirms = request.testFix && !edited && result.failure != null && confirmsProblem(steps, result, request.prompt)
                    if (confirms) {
                        reproduced = true
                        reproductions += steps
                    }
                    buildString {
                        append("run: ").append(result.failure?.let { "FAILED at ${shown(it, result, privateApp)}" } ?: "OK, ${steps.size} steps ran.")
                        if (confirms) append("\nThis confirms the problem. Fix the app. finish runs these steps again.")
                        else if (request.testFix && !edited && result.failure != null)
                            append("\nThis does not confirm a problem. Only an app failure, or an expected value written in the user's request, confirms one.")
                        if (requested.isNotEmpty()) append("\nThe last operation requested: ${requested.joinToString()}.")
                        if (privateApp) append("\n").append(PRIVATE_SCREEN) else result.final?.let { append("\nScreen:\n").append(it.text) }
                    }
                }
                "finish" -> {
                    request.progress?.invoke(ToolGenerationProgress.VALIDATING)
                    finishing(reply, current ?: throw Feedback(noValidApp(draft)))
                }
                "generate_image" -> {
                    val maker = request.imageMaker ?: throw Feedback("Pictures are off for this request. Build the app without image nodes.")
                    val remaining = MAX_NEW_IMAGES - submittedImages
                    if (remaining <= 0) throw Feedback("This request already made $MAX_NEW_IMAGES pictures. Use them.")
                    val intents = try { ImageIntent.list(reply.opt("images"), remaining) } catch (failure: CancellationException) { throw failure }
                        catch (_: Exception) { null }
                    if (intents.isNullOrEmpty()) throw Feedback("images must hold 1 to $remaining requests with distinct slots. ${ImageIntent.GUIDANCE}")
                    request.progress?.invoke(ToolGenerationProgress.IMAGES)
                    submittedImages += intents.size
                    val made = try { maker.make(intents.map(::appPicture)) } catch (failure: ImageFailure) {
                        throw Feedback("The pictures could not be made: ${failure.message} Build the app without them.")
                    }
                    madeImages.removeAll { old -> made.any { it.slot == old.slot } }
                    madeImages += made
                    "generate_image: OK. Pictures ready: ${made.joinToString { "\"${it.slot}\" (${it.aspect.wire})" }}. " +
                        "Show one with {\"type\":\"image\",\"assetSlot\":\"${made.first().slot}\",\"text\":\"${made.first().alt}\"}."
                }
                "describe_capability" -> {
                    val ids = reply.optJSONArray("ids") ?: throw Feedback("describe_capability needs ids.")
                    val known = CapabilityCatalog.definitions.associateBy { it.id }
                    "describe_capability: " + (0 until minOf(ids.length(), 6)).joinToString("\n") { index ->
                        val id = ids.optString(index)
                        known[id]?.json()?.apply { remove("title") }?.toString() ?: "$id is not available. Available: ${known.keys.joinToString()}"
                    }
                }
                else -> throw Feedback("Unknown tool \"$tool\". Use write_app, edit_app, run, describe_capability, " +
                    (if (request.imageMaker != null) "generate_image, " else "") + "or finish.")
            }
        }

        fun appFileSteps(call: suspend (CloudGenerationStage, String, String, AppFileLimits) -> AppFileReply) =
            AppFileSteps(AppFileCompiler(sandbox.context, sandbox), AppFilePrompt.load(sandbox.context), call, request::phase) {
                request.progress?.invoke(it)
            }

        // The app of a clean plan of the kit when it passes the host checks. Null: a host check failed, or the check itself failed.
        suspend fun kitApp(outcome: AppFileOutcome.Clean): AppAgentResult? = try {
            val candidate = validate(outcome.definition, appFile = outcome.appFile, migrated = outcome.state)
            val preview = candidate.preview
            // The loop finds a raw float number on the screen in finish, which an accepted plan does not reach.
            if (longDecimalOn(AppScreen.render(JSONObject(preview.ui), JSONObject(preview.state), preview.surfaces ?: JSONObject(),
                    System.currentTimeMillis(), candidate.spec.surfaces)) != null) null
            else {
                edited = true
                request.phase("plan_outcome", "accepted")
                // A kit definition has an empty description, so the summary is its title.
                AppAgentResult(preview, candidate.spec.description.ifBlank { candidate.spec.title }, outcome.calls, changed = true)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (failure is CloudFailure && failure.code == "cancelled") throw failure
            null
        }

        // The plan step of a new app. A clean plan that passes the host checks is the app.
        // Each other outcome goes on with the loop of today, and the loop gets nothing of the plan.
        val planStep = request.planStep
        if (planStep != null && appFileCall != null && request.base == null && lesson == null && request.images.isEmpty() &&
            request.sceneAssets.isEmpty() && !request.testFix) {
            val outcome = appFileSteps(appFileCall).plan(request.prompt, planStep)
            checkActive()
            val detail = when (outcome) {
                is AppFileOutcome.Outside -> "outside:${outcome.word}"
                is AppFileOutcome.Fallback -> "fallback:${outcome.reason}"
                is AppFileOutcome.Clean -> kitApp(outcome)?.let { return it } ?: "fallback:host_check"
            }
            request.phase("plan_outcome", detail)
        }

        // The change step of a saved app with an app file, for a change and for test and fix. The report of test and fix is the request.
        // The loop never gets such an app, so no write or edit can drop a saved value.
        val base = request.base
        if (previous?.appFile != null && base != null) {
            val call = checkNotNull(appFileCall) { "An app with an app file changes only through the kit." }
            byLines = true
            val outcome = appFileSteps(call).change(request.prompt, AppFilePrevious(previous.appFile, previous.schema.toString(), base.state),
                AppFileLimits())
            checkActive()
            val reason = when (outcome) {
                is AppFileOutcome.Clean -> kitApp(outcome)?.let { return it } ?: "host_check"
                is AppFileOutcome.Outside -> "outside"
                is AppFileOutcome.Fallback -> outcome.reason
            }
            request.phase("plan_outcome", "fallback:$reason")
            val context = sandbox.context
            // A change that changes no line is not finished. Test and fix then found no line to repair.
            if (reason == "empty") throw CloudFailure("widget_agent_incomplete",
                context.getString(if (request.testFix) R.string.tool_test_fix_no_line else R.string.tool_generation_unchanged))
            // A failed call or compile made no change, so a retry can work. Each other reason is a change that the kit cannot apply.
            val failure = if (reason in CALL_FAILURES || reason.startsWith("compile_"))
                CloudFailure("widget_generation", context.getString(R.string.tool_generation_connection))
            else CloudFailure("widget_state", context.getString(R.string.tool_generation_state))
            // Test and fix by lines never goes to the loop, which has no app to run here.
            // A value that differs from the start state is data of the person: the app, its data, and its revision stay.
            if (request.testFix || !sameToolJson(JSONObject(base.state), JSONObject(previous.initialState))) throw failure
            return run(request, session, rebuild = true)
        }

        val guidance = if (lesson != null) "" else {
            request.phase("capability_guidance_start")
            capabilityGuidance(request.prompt).also { request.phase("capability_guidance_end") }
        }
        val instructions = AppAgentGuide.system(guidance,
            listOfNotNull(request.extraInstructions.ifBlank { null }, lesson?.let { lessonGuide(it, request.lessonIllustration) })
                .joinToString("\n\n"),
            pictures = request.imageMaker != null, threeD = threeD)
        // shortcut: reads the "User request: ...\nWidget brief:" format of ChatToolReply.generationPrompt; a direct prompt is used whole.
        val requestLanguage = if (previous != null || lesson != null) null else
            GenerationLanguage.identifyText(request.prompt.substringAfter("User request: ").substringBefore("\nWidget brief:"))
        var task = taskMessage(request, previous, privateApp, requestLanguage)
        if (models.isNotEmpty() && threeD) task += "\n\nAttached 3D models: " +
            JSONArray(models.map { JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime) }) +
            ". Load one in createScene with (await new context.addons.GLTFLoader().parseAsync(await context.readAsset(id), \"\")).scene."

        draft?.let { saved ->
            var smokeEvents = emptyList<String>()
            try {
                current = validate(saved) { smokeEvents = it }
            } catch (failure: Feedback) {
                // The saved app itself fails the host checks. Its failing smoke events are the reproduction.
                if (request.testFix) {
                    reproduced = true
                    reproductions += AppSteps.fromSmokeEvents(smokeEvents)
                }
                task += "\n\nThe current app fails the host check: ${failure.message}"
            }
        }

        for (step in 1..AppAgentGuide.MAX_STEPS) {
            checkActive()
            val turns = buildList {
                add(CloudTurn("user", task, request.images))
                history.forEachIndexed { index, turn ->
                    add(when {
                        turn.role == "assistant" && index != lastWriteIndex -> CloudTurn("assistant", compact(turn.text))
                        // Only the newest screenshot is sent. An older one shows a screen that has changed.
                        turn.images.isNotEmpty() && index != history.lastIndex -> turn.copy(images = emptyList())
                        else -> turn
                    })
                }
                if (!draftMatchesLastWrite && draft != null && isNotEmpty()) {
                    val last = removeAt(lastIndex)
                    add(last.copy(text = last.text + "\n\nCurrent app definition:\n" + modelView(checkNotNull(draft), privateApp)))
                }
            }
            if (step == 1) request.phase("agent_call_start")
            val started = System.nanoTime()
            val reply = completeWithRetry(turns, instructions, session)
            history += CloudTurn("assistant", reply)
            var parsed: JSONObject? = null
            val result = try {
                parsed = parseReply(reply)
                handle(parsed)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Feedback) {
                val tool = parsed?.optString("tool").orEmpty().ifEmpty { "reply" }
                lastProblem = failure.message.orEmpty()
                "$tool: REJECTED. ${failure.message}"
            }
            val tool = parsed?.optString("tool").orEmpty()
            runCatching { request.onStep?.invoke(step, tool, reply, if (result is Finished) "finish: PASSED" else result.toString(), System.nanoTime() - started) }
            if (result is Finished) {
                val accepted = checkNotNull(current)
                return AppAgentResult(accepted.preview, result.summary, step, edited, result.lesson)
            }
            val remaining = AppAgentGuide.MAX_STEPS - step
            val footer = when {
                remaining == 0 -> ""
                remaining <= 3 -> "\n\n$remaining steps remain. Fix the app and call finish."
                else -> "\n\nStep $step of ${AppAgentGuide.MAX_STEPS} used."
            }
            history += CloudTurn("user", result.toString() + footer, listOfNotNull((result as? Review)?.image))
        }
        throw CloudFailure("widget_agent_incomplete",
            "Alphy could not finish this app in ${AppAgentGuide.MAX_STEPS} steps." +
                lastProblem.takeIf { it.isNotBlank() }?.let { " Last problem: ${it.take(300)}" }.orEmpty())
    }

    private suspend fun finish(reply: JSONObject, candidate: Candidate, request: AppAgentRequest, privateApp: Boolean): Any {
        val checks = reply.optJSONArray("checks") ?: throw Feedback("finish needs checks.")
        if (checks.length() !in 1..12) throw Feedback("Give 1 to 12 checks.")
        val parsed = (0 until checks.length()).map { index ->
            val check = checks.optJSONObject(index) ?: throw Feedback("Check ${index + 1} must be an object.")
            val name = check.optString("name").ifBlank { "check ${index + 1}" }.take(80)
            val steps = try { AppSteps.parse(check.opt("steps")) } catch (failure: AppStepError) {
                throw Feedback("check \"$name\": ${failure.message}")
            }
            if (steps.lastOrNull() !is AppStep.Expect) throw Feedback("check \"$name\" must end with an expectation after its last operation.")
            name to steps
        }
        for ((name, steps) in parsed) {
            val outcome = drive(candidate, request) { AppSteps.run(this, steps, everyStep = false) }
            if (outcome.failure != null) {
                return "finish: FAILED. check \"$name\": ${shown(outcome.failure, outcome, privateApp)}" +
                    (outcome.final?.takeUnless { privateApp }?.let { "\nScreen at the failure:\n${it.text}" } ?: "") +
                    "\nFix the app if it is wrong. Fix the check only if the expected value does not follow from the request."
            }
            outcome.final?.let(::longDecimalOn)?.let { value ->
                if (privateApp) return "finish: FAILED. check \"$name\": the screen shows a long decimal number. Round displayed numbers in the source."
                return "finish: FAILED. check \"$name\": the screen shows $value. Round displayed numbers in the source." +
                    "\nScreen:\n${outcome.final.text}"
            }
        }
        val summary = reply.optString("summary").trim().take(600)
        return Finished(summary, request.lesson?.let { lessonResult(reply, it, request.lessonIllustration) })
    }

    /** Names the model's own keys or bind for a rejected node. The diagnostic itself holds only catalog terms. */
    private fun definitionHint(definition: JSONObject, diagnostic: ToolUiDiagnostic): String? {
        if (diagnostic.stage != ToolUiValidationStage.DEFINITION) return null
        var node: Any? = definition
        for (part in Regex("\\.([A-Za-z]+)|\\[(\\d+)]").findAll(diagnostic.path.removePrefix("$"))) {
            val (key, index) = part.destructured
            node = if (key.isNotEmpty()) (node as? JSONObject)?.opt(key) else (node as? JSONArray)?.opt(index.toInt())
        }
        return when (diagnostic.rule) {
            "component_properties" -> {
                val target = node as? JSONObject ?: return null
                val component = ToolUiCatalog.components[target.optString("type")] ?: return null
                val extra = target.keys().asSequence().filter { it !in component.properties }.toList().ifEmpty { return null }
                "This ${component.type} has unsupported properties: ${extra.joinToString()}. Allowed: ${component.properties.sorted().joinToString()}."
            }
            "component_binding_missing_path" -> (node as? String)?.let {
                "bind \"$it\" is not a field in both stateSchema and initialState. Declare it or bind an existing field."
            }
            else -> null
        }
    }

    private fun smokeFor(candidate: Candidate, request: AppAgentRequest) = ToolGenerationSmoke(sandbox, candidate.spec,
        request.artifactId, (request.base?.revision ?: 0) + 1, candidate.spec.initialState,
        checkActive = {}, generation = service, policy = request.lesson?.policy)

    /** Runs the course contract on an accepted version. A scene slide must also draw something in its first screen. */
    private fun checkLessonSlide(lesson: AppLessonTarget, spec: ToolSpec, preview: ToolPreview) {
        try { lesson.checkSlide(spec, preview) } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { throw Feedback(failure.message ?: "The slide breaks the course rules.") }
        if (!lesson.scene) return
        for (id in canvasSurfaceIds(JSONObject(preview.ui), JSONObject(preview.state))) {
            val surface = spec.surfaces[id] ?: continue
            val commands = preview.surfaces?.optJSONObject(id)?.optJSONArray("commands")
            if (commands == null || observeSurfaceGeometry(surface, commands, emptyList())?.hasInk != true) {
                throw Feedback("The canvas \"$id\" draws nothing visible in the first screen. Draw the illustration in the first preview.")
            }
        }
    }

    /** Reads the lesson arguments of a passing finish. The host checks each cited source against the course. */
    private fun lessonResult(reply: JSONObject, lesson: AppLessonTarget, illustrations: Boolean): AppLessonResult {
        val raw = reply.opt("sources")
        val sources = when (raw) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> List(raw.length()) { raw.opt(it) as? String ?: throw Feedback("sources must hold source ID strings.") }
            else -> throw Feedback("sources must be an array of source IDs.")
        }
        val allowed = lesson.allowedSourceIds
        if (allowed.isEmpty() && sources.isNotEmpty()) throw Feedback("This course has no sources. Omit sources.")
        if (allowed.isNotEmpty() && sources.isEmpty()) throw Feedback("Give sources: the IDs of the search results that the slide uses. Available: ${allowed.joinToString()}.")
        sources.firstOrNull { it !in allowed }?.let { throw Feedback("Unknown source \"$it\". Available: ${allowed.joinToString()}.") }
        if (sources.distinct().size != sources.size) throw Feedback("Give each source once.")
        val illustration = reply.opt("illustration")?.takeUnless { it == JSONObject.NULL }?.let { value ->
            if (!illustrations) throw Feedback("Pictures are off. Remove illustration.")
            try { ImageIntent.list(JSONArray().put(value), 1).single() } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { throw Feedback("illustration must be one image request. ${ImageIntent.GUIDANCE}") }
        }
        return AppLessonResult(sources, illustration)
    }

    /** Tells the model the host's lesson arguments of finish. The course code adds the slide rules. */
    private fun lessonGuide(lesson: AppLessonTarget, illustrations: Boolean): String = buildString {
        append(lesson.guide)
        append("\n\nWhen you call finish for this slide, or add checks to write_app or edit_app, add \"sources\": ")
        if (lesson.allowedSourceIds.isEmpty()) append("leave it out, because this course has no sources.")
        else append("the IDs of the trusted search results that the slide uses, in the order that the slide uses them. ")
            .append("Available IDs: ").append(lesson.allowedSourceIds.joinToString()).append('.')
        if (illustrations) append("\nYou can add \"illustration\": one image request for a picture above the slide. ")
            .append(ImageIntent.GUIDANCE)
    }

    /** Runs [block] on a fresh copy of the app: in the sandbox, or for a 3D app in a foreground scene preview. */
    private suspend fun <T> drive(candidate: Candidate, request: AppAgentRequest, block: suspend AppTarget.() -> T): T =
        if (!candidate.spec.threeScene) smokeFor(candidate, request).probe { block() }
        else withThreeScene(candidate.spec, request.artifactId, (request.base?.revision ?: 0) + 1, candidate.spec.initialState,
            checkActive = {}) { session, _ -> ThreeSceneTarget(session).block() }

    private suspend fun completeWithRetry(turns: List<CloudTurn>, instructions: String, session: CloudSession): String {
        var attempt = 0
        while (true) {
            try {
                return complete(turns, instructions)
            } catch (failure: CloudFailure) {
                // A blank completion is a provider glitch like a lost connection. The same request usually succeeds.
                val transient = failure.code in setOf("pre_headers_io", "pre_headers_timeout", "stream_io", "stream_timeout",
                    "widget_generation", "empty_response")
                if (!transient || ++attempt > 2) throw failure
                session.checkActive(currentCoroutineContext())
            }
        }
    }

    private fun parseReply(raw: String): JSONObject {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        try {
            return JSONObject(text)
        } catch (failure: JSONException) {
            // A reply that lacks only its closing brackets is complete in intent. Validation still runs on it.
            ReplyJson.closers(text)?.let { closers -> runCatching { JSONObject(text + closers) }.getOrNull()?.let { return it } }
            nativeCallReply(raw)?.let { return it }
            val prefix = "Reply with one JSON object that names a tool. The JSON is invalid: "
            // The parser position is far from the real fault when an array stays open. The opener excerpt is quotable, the reply is not.
            ReplyJson.unclosedOpener(text)?.let {
                val (kind, closer) = if (it.bracket == '[') "array" to "]" else "object" to "}"
                throw Feedback("${prefix}the $kind that starts at «${it.excerpt}» (character ${it.index}) has no closing $closer.")
            }
            // Only a position message is safe to quote. "Value X of type ..." repeats the reply itself.
            val reason = failure.message.orEmpty().takeIf { " at character " in it }?.substringBefore(" of ")?.take(120)
                ?: "the reply is not one JSON object"
            throw Feedback("$prefix$reason.")
        }
    }

    /**
     * DeepSeek sometimes answers in its native call markup instead of JSON, and the opening tags can be missing.
     * The host reads the parameters it can see. It names an untagged first parameter and a missing tool by their shape.
     */
    private fun nativeCallReply(raw: String): JSONObject? {
        if (!nativeMarker.containsMatchIn(raw)) return null
        val reply = JSONObject()
        nativeInvoke.find(raw)?.let { reply.put("tool", it.groupValues[1]) }
        for (segment in nativeClose.split(raw).dropLast(1)) {
            val tag = nativeOpen.findAll(segment).lastOrNull()
            val body = (if (tag != null) segment.substring(tag.range.last + 1) else segment).trim()
            val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull()?.takeIf { it is JSONObject || it is JSONArray }
            val name = tag?.groupValues?.get(1) ?: when (parsed) {
                is JSONArray -> if (parsed.optJSONObject(0)?.has("steps") == true) "checks" else "steps"
                is JSONObject -> if (parsed.has("ui") || parsed.has("source")) "app" else return null
                else -> return null
            }
            reply.put(name, if (tag?.groupValues?.get(2) == "true" || parsed == null) body else parsed)
        }
        if (!reply.has("tool")) reply.put("tool", when {
            reply.has("checks") -> "finish"
            reply.has("app") -> "write_app"
            reply.has("steps") -> "run"
            reply.has("set") || reply.has("source_edits") -> "edit_app"
            reply.has("images") -> "generate_image"
            else -> return null
        })
        return reply
    }

    /** The tool of [reply]. Checks in an edit set belong to the reply, and an edit that only carries checks is a finish. */
    private fun toolOf(reply: JSONObject): String {
        val tool = reply.optString("tool")
        if (tool != "edit_app") return tool
        reply.optJSONObject("set")?.let { liftFinish(reply, it) }
        val changes = (reply.optJSONObject("set")?.length() ?: 0) + (reply.optJSONArray("source_edits")?.length() ?: 0)
        return if (changes == 0 && reply.has("checks")) "finish" else tool
    }

    // The model often nests the finish arguments in app or set. A rejection there costs a whole new app, so the host moves them up.
    private fun liftFinish(reply: JSONObject, holder: JSONObject) {
        for (key in listOf("checks", "summary")) holder.remove(key)?.let { if (!reply.has(key)) reply.put(key, it) }
    }

    private fun objectArgument(reply: JSONObject, name: String): JSONObject = when (val value = reply.opt(name)) {
        is JSONObject -> value
        is String -> try { JSONObject(value) } catch (_: JSONException) { throw Feedback("$name must be a JSON object.") }
        else -> throw Feedback("$name must be a JSON object.")
    }

    private fun applyEdits(base: JSONObject, reply: JSONObject, uiLocked: Boolean): JSONObject {
        val next = JSONObject(base.toString())
        val set = reply.opt("set")?.let { it as? JSONObject ?: throw Feedback("set must be an object.") }
        val edits = reply.opt("source_edits")?.let { it as? JSONArray ?: throw Feedback("source_edits must be an array.") }
        if ((set == null || set.length() == 0) && (edits == null || edits.length() == 0)) throw Feedback("edit_app needs set or source_edits.")
        set?.keys()?.forEach { key ->
            if (key !in editableFields) throw Feedback("set cannot change \"$key\". Fields: ${editableFields.joinToString()}.")
            val value = set.get(key)
            if (value == JSONObject.NULL) next.remove(key) else next.put(key, value)
        }
        if (edits != null) {
            var source = next.optString("source")
            // The model often patches the ui as it wrote it. Compact JSON keeps its key order, so a copied node matches.
            val ui = next.optJSONObject("ui")?.toString().orEmpty()
            var nextUi = ui
            for (index in 0 until edits.length()) {
                val edit = edits.optJSONObject(index) ?: throw Feedback("source_edits[$index] must be an object.")
                val find = edit.optString("find")
                if (find.isEmpty() || !edit.has("replace")) throw Feedback("source_edits[$index] needs find and replace.")
                val inSource = occurrences(source, find)
                val inUi = if (uiLocked) 0 else occurrences(nextUi, find)
                when {
                    inSource == 1 && inUi == 0 -> source = source.replace(find, edit.getString("replace"))
                    inSource == 0 && inUi == 1 -> nextUi = nextUi.replace(find, edit.getString("replace"))
                    else -> throw Feedback("source_edits[$index].find matches ${inSource + inUi} times. " +
                        "It must match the current source${if (uiLocked) "" else " or the compact ui JSON"} exactly once.")
                }
            }
            next.put("source", source)
            if (nextUi != ui) next.put("ui", try { JSONObject(nextUi) } catch (_: JSONException) {
                throw Feedback("source_edits breaks the ui JSON. Replace whole JSON objects.")
            })
        }
        return next
    }

    private fun occurrences(text: String, part: String): Int {
        var count = 0
        var from = text.indexOf(part)
        while (from >= 0) {
            count++
            from = text.indexOf(part, from + 1)
        }
        return count
    }

    private fun noValidApp(draft: JSONObject?) = if (draft == null) "There is no app yet. Call write_app first."
        else "The current app did not pass validation. Fix it with edit_app or write_app first."

    private fun compact(reply: String): String {
        val json = runCatching { JSONObject(reply) }.getOrNull() ?: return reply.take(2000)
        if (json.has("app")) json.put("app", "(an earlier app version, omitted)")
        json.optJSONObject("set")?.let { set ->
            set.keys().asSequence().toList().forEach { key ->
                if (set.get(key).toString().length > 1500) set.put(key, "(omitted)")
            }
        }
        return json.toString()
    }

    private fun taskMessage(request: AppAgentRequest, previous: ToolSpec?, privateApp: Boolean, requestLanguage: String?): String = buildString {
        append("User request:\n").append(request.prompt.trim())
        val now = ZonedDateTime.now()
        append("\n\nToday is ").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd (EEEE), HH:mm", Locale.ENGLISH)))
            .append(", time zone ").append(now.zone.id).append('.')
        if (previous != null) {
            append("\n\nThis request changes the user's saved app. Change only what the request asks. ")
            append("Keep the other controls, texts, list columns, and layout as they are, also when you would design them differently. ")
            append("Keep every existing state field with its type so saved data stays. Give new fields initial values. ")
            append("Use edit_app for small changes.")
            previous.appLanguage?.let { append("\nThe app language is ").append(GenerationLanguage.name(it)).append('.') }
            if (previous.generatedImages.isNotEmpty()) append("\nSaved pictures: ")
                .append(previous.generatedImages.joinToString { "\"${it.slot}\" (${it.alt})" }).append(". Keep their image nodes unless the request removes them.")
            if (privateApp) append("\n").append(PRIVATE_EDIT).append(' ').append(PRIVATE_SCREEN)
            append("\nCurrent app definition:\n").append(modelView(JSONObject(previous.providerDefinition()).apply { remove("formatVersion") }, privateApp))
        } else if (request.lesson != null) {
            append("\n\nBuild the reached course slide. Write it in the language that the course requests. ")
            append("If the course names no language, use the language of the reached slide goal")
            request.lesson.languageHint?.let { append(", which is ").append(GenerationLanguage.name(it)) }
            append('.')
        } else {
            append("\n\nBuild a new app. Write the interface in the language of the user's request text, unless that text names another language. ")
            append("The widget brief and reply excerpt can be in a different language. They do not set the interface language.")
            requestLanguage?.let { append(" The user's request text is in ").append(GenerationLanguage.name(it)).append('.') }
        }
    }

    private suspend fun capabilityGuidance(prompt: String): String {
        val ranked = try {
            // The request and brief come first. Source excerpts after them only add embedding time: about 0.1 s per 216 bytes.
            SemanticRuntime.get(sandbox.context).rank(prompt.take(1500), CapabilityCatalog.definitions,
                { "${it.title} (${it.id}). ${it.explanation}" }, cache = true)
                .filter { it.score >= 0.72f }.take(5).map { it.item.id }.toSet()
        } catch (_: SemanticUnavailable) {
            emptySet()
        }
        return "Device capabilities. Declare an ID in capabilities before an effect uses it. Use describe_capability for argument schemas.\n" +
            CapabilityCatalog.definitions.joinToString("\n") { "- ${it.id}: ${it.explanation.substringBefore(". ").take(140)}" } +
            (if (ranked.isEmpty()) "" else "\n" + CapabilityCatalog.modelGuidance(ranked, titles = false))
    }

    /** A private app keeps its layout and initial values out of model requests. */
    private fun modelView(definition: JSONObject, privateApp: Boolean): String =
        if (!privateApp) definition.toString() else JSONObject(definition.toString()).apply { remove("ui"); remove("initialState") }.toString()

    /** For a private app, a failed expectation does not show the actual value. */
    private fun shown(failure: String, outcome: AppSteps.Outcome, privateApp: Boolean): String =
        if (privateApp && outcome.failureKind == AppSteps.FailureKind.EXPECTATION) "step ${checkNotNull(outcome.failedStep) + 1}: the expectation did not pass"
        else failure

    /** An app failure, or a failed expectation whose value the user wrote, confirms a reported problem. */
    private fun confirmsProblem(steps: List<AppStep>, outcome: AppSteps.Outcome, prompt: String): Boolean =
        when (outcome.failureKind) {
            AppSteps.FailureKind.APP -> true
            AppSteps.FailureKind.EXPECTATION -> (steps[checkNotNull(outcome.failedStep)] as AppStep.Expect).let { expect ->
                val expected = (expect.text ?: expect.value?.let { if (it is Number) formatNumber(it) else it.toString() }).orEmpty().trim()
                expected.isNotEmpty() && prompt.contains(expected, ignoreCase = true)
            }
            else -> false
        }

    private fun formatNumber(value: Number): String = java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    /** First-screen problems that validation does not see: a placeholder word as a value, and grid buttons that look alike. */
    /** An app picture gets a product art style from the host, so the model writes only the content. A 3D texture keeps its prompt. */
    private fun appPicture(intent: ImageIntent) = if (intent.aspect == ImageAspect.EQUIRECTANGULAR) intent
        else intent.copy(prompt = intent.prompt.take(1500 - APP_PICTURE_STYLE.length) + APP_PICTURE_STYLE)

    /** Advice about the look of an accepted app. A hint never rejects the app, because each rejection costs a model turn. */
    private fun designHints(ui: JSONObject): List<String> {
        fun descendants(node: JSONObject): List<JSONObject> = listOf(node) + (node.optJSONArray("children")?.let { children ->
            (0 until children.length()).mapNotNull(children::optJSONObject).flatMap(::descendants)
        } ?: emptyList())
        val nodes = descendants(ui)
        return buildList {
            if (nodes.none { it.optString("type") == "theme" && it.has("palette") }) add("Set a palette on the theme that suits the topic.")
            // An emoji illustration names the topic of its card too.
            if (nodes.any { it.optString("type") in setOf("card", "listItem", "emptyState") && !it.has("icon") && descendants(it).none { d -> d.optString("type") == "emoji" } })
                add("Give each card, listItem, and emptyState an icon or an emoji that names its topic.")
            if (nodes.any { node -> node.optString("type") == "card" && descendants(node).count { it.optString("type") == "metric" } > 3 })
                add("Show one main metric in a card and at most 2 more. Remove repeated or minor values.")
        }
    }

    private fun presentationProblem(ui: JSONObject, state: JSONObject): String? {
        // A list row template repeats its buttons on purpose, so the walk follows children only.
        fun descendants(node: JSONObject): List<JSONObject> = listOf(node) + (node.optJSONArray("children")?.let { children ->
            (0 until children.length()).mapNotNull(children::optJSONObject).flatMap(::descendants)
        } ?: emptyList())
        val nodes = descendants(ui)
        for (node in nodes) {
            if (node.optString("type") !in setOf("metric", "text", "fact", "badge") || !node.has("bind")) continue
            val value = runCatching { ToolUiCatalog.boundText(node, state).trim() }.getOrNull() ?: continue
            if (value in placeholderValues) return "The value of \"${node.optString("text")}\" shows \"$value\". " +
                "When a value is unavailable, show a dash or a short message in the interface language."
        }
        for (grid in nodes.filter { it.optString("type") == "grid" }) {
            val buttons = descendants(grid).filter { it.optString("type") == "button" && it.has("action") }
            // Symbols such as + and − are usual in steppers. A word label must name its cell.
            val shared = buttons.groupBy { it.optString("text").trim() }.entries.firstOrNull { (label, same) ->
                label.any(Char::isLetter) && same.map { it.optString("action") }.distinct().size > 1
            } ?: continue
            return "Buttons with different actions in one grid share the label \"${shared.key}\". " +
                "Give each button a distinct label, such as its cell number or name, so the user can tell them apart."
        }
        return null
    }

    private companion object {
        const val REPRODUCE_FIRST = "Reproduce the reported problem with run on the current app before you change it."
        /** The fallback reasons of a change call that ended before a reply. */
        val CALL_FAILURES = setOf("call_failed", "first_text_timeout", "total_timeout")
        const val PRIVATE_EDIT = "This app keeps private device data. The host keeps its ui and initialState private and unchanged. " +
            "Use edit_app without ui or initialState."
        const val REVIEW_SCREEN = "Review this screen as the user: a calculator shows real results for sample inputs, not 0 or dashes, and no label or value repeats."
        const val SCREENSHOT_REVIEW = "finish: REVIEW. The checks passed. The attached image is a screenshot of the first screen on a phone. " +
            "Look at it as the user: colors, icons, spacing, pictures, text that is cut or breaks inside a word, and large empty areas. " +
            "Fix real problems with edit_app, then call finish again. If the screen looks good, call finish again now."
        const val PRIVATE_SCREEN = "The host shows no screen text for this private app."
        const val MAX_NEW_IMAGES = 4
        const val APP_PICTURE_STYLE = ". Polished app artwork: a clear photo or a soft illustration, natural light, simple clean background, no text, no letters, no watermark."
        const val NO_THREE = "3D scenes are not available on this device. Remove runtime and draw the app with a canvas."
        val editableFields = setOf("title", "description", "language", "capabilities", "stateSchema", "initialState", "ui", "source", "runtime",
            "surfaces", "presentation", "hostControls", "capabilityResults", "collections")
        // Float artifacts such as 0.30000000000000004 have many digits. Conversion constants such as 0.621371 have six or fewer.
        private const val NATIVE = "<[\\uFF5C|]+\\s*DSML\\s*[\\uFF5C|]+\\s*"
        private val nativeMarker = Regex("[\\uFF5C|]+\\s*DSML\\s*[\\uFF5C|]+")
        private val nativeInvoke = Regex(NATIVE + "invoke name=\"([A-Za-z_]+)\"")
        private val nativeOpen = Regex(NATIVE + "parameter name=\"([A-Za-z_]+)\"(?:\\s+string=\"(true|false)\")?[^>]*>")
        private val nativeClose = Regex("</[\\uFF5C|]+\\s*DSML\\s*[\\uFF5C|]+\\s*parameter>")
        val scalarTypes = setOf("number", "string", "boolean")
        val placeholderValues = setOf("None", "null", "undefined", "NaN", "[object Object]")
        val longDecimal = Regex("\\d+\\.\\d{7,}")

        /** The first raw float number that [screen] shows, such as 28.333333333333332, or null. */
        fun longDecimalOn(screen: AppScreen): String? = screen.visibleText.firstNotNullOfOrNull { longDecimal.find(it)?.value }

        /**
         * Moves a tone that the model put beside style into style, when the node's type accepts a style tone.
         * A tone already in style wins. A style that is not an object stays for validation to reject.
         */
        private fun moveToneIntoStyle(node: Any?) {
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) moveToneIntoStyle(node.opt(i))
                is JSONObject -> {
                    val style = node.opt("style")
                    if (node.has("tone") && (style == null || style is JSONObject) &&
                        "tone" in ToolUiCatalog.components[node.optString("type")]?.styles.orEmpty()) {
                        val tone = node.remove("tone")
                        val target = style as? JSONObject ?: JSONObject().also { node.put("style", it) }
                        if (!target.has("tone")) target.put("tone", tone)
                    }
                    for (key in node.keys().asSequence().toList()) moveToneIntoStyle(node.opt(key))
                }
            }
        }
    }
}
