package com.alpha.assistant.tools

import com.alpha.assistant.images.ImageFailure
import com.alpha.assistant.images.ImageGeneration
import com.alpha.assistant.images.ImageIntent
import com.alpha.assistant.images.ImageSettings

import com.alpha.assistant.cloud.*
import com.alpha.assistant.capabilities.*
import com.alpha.assistant.automations.AutomationRuntime
import com.alpha.assistant.automations.AutomationStore
import org.json.JSONArray
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.sandbox.SandboxResult
import com.alpha.assistant.tools.three.ThreeSceneAssetCleanup
import com.alpha.assistant.tools.three.ThreeSceneAssetRef
import com.alpha.assistant.tools.three.ThreeSceneAssets
import com.alpha.assistant.tools.three.checkThreeScene
import com.alpha.assistant.tools.agent.AppAgent
import com.alpha.assistant.tools.agent.AppAgentGuide
import com.alpha.assistant.tools.agent.AppAgentRequest
import com.alpha.assistant.tools.agent.AppAgentResult
import com.alpha.assistant.tools.agent.AppImageMaker
import com.alpha.assistant.tools.agent.AppLessonTarget
import com.alpha.assistant.tools.agent.AppSnapshot
import com.alpha.assistant.tools.agent.GenerationSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

internal const val MAX_TOOL_PROMPT_LENGTH = 64_000

internal fun isValidToolPrompt(prompt: String) = prompt.isNotBlank() && prompt.length <= MAX_TOOL_PROMPT_LENGTH

data class ToolPreview(val spec: ToolSpec, val state: String, val ui: String, val effects: List<CapabilityRequest> = emptyList(), val records: JSONArray = JSONArray(), val surfaces: JSONObject? = null, val frames: Set<String>? = null, val timers: Map<String,Int>? = null, val playback: String? = null)
/** The model-facing reason for a rejected generated result. User-facing messages stay short. */
internal interface GeneratedOutputDetail { val detail: String }

internal data class LessonToolPreview(val preview: ToolPreview, val sourceIds: List<String>, val illustration: ImageIntent? = null)

internal class ToolForegroundAction(val tool: ToolRecord, val network: CapabilityRequest?)
enum class ToolGenerationProgress { COMPLETING, VALIDATING, IMAGES }



internal fun liveInputEvent(actionId: String, value: Any?): JSONObject =
    JSONObject().put("type",SandboxContract.LIVE_INPUT_EVENT).put("actionId",actionId)
        .put("value",value ?: JSONObject.NULL).also(SandboxContract::validateLiveInputEvent)

/** Host checks for a restricted artifact surface. The callbacks receive immutable JSON. */
class ToolExecutionPolicy internal constructor(
    internal val validateDefinition: (String) -> Unit,
    internal val validateResult: (String) -> Unit,
)

class ToolGenerationService internal constructor(private val provider: ChatProvider, private val sandbox: SandboxClient,
    internal val imageGeneration: ImageGeneration) {
    constructor(provider: ChatProvider, sandbox: SandboxClient): this(provider,sandbox,ImageGeneration(sandbox.context))
    internal fun retainedSession()=sandbox.retainedSession()

    private enum class GenerationProblem(val code: String, val message: String, val correction: String) {
        SPEC("widget_spec", "The generator returned an invalid tool definition, state schema, or controls. Retry the request.",
            "The tool definition was invalid. Follow the exact schema and native control limits."),
        RESULT("widget_preview", "The generated tool failed its safe preview. It must finish execution and return valid state and controls. Retry the request.",
            "The safe smoke returned invalid state, controls, drawing, subscriptions, or requests. Return valid state and optional outputs from the exact result schema."),
        RECORDS_WITH_EFFECTS(RESULT.code, RESULT.message,
            RESULT.correction + " Record requests cannot share an action with device effects."),
        PREVIEW_STATE("widget_preview_state", "The generated preview tried to change saved data. Previews must preserve state. Existing data is unchanged.",
            "The preview changed state. Preview and render events must return input.state unchanged. Other permitted events can update local state."),
        PREVIEW_EFFECTS("widget_preview_effects", "The generated preview requested a device action. Previews must have no device effects. Existing data is unchanged.",
            "The preview requested effects. Preview and render events must return no effects. Request effects only for action events.");

        fun failure() = CloudFailure(code,message)
    }
    private class InvalidGeneratedOutput(val problem: GenerationProblem, val validation: ToolValidationFailure? = null,
        val causeText: String? = null) : Exception(problem.message), GeneratedOutputDetail {
        override val detail get() = listOfNotNull(problem.correction, validation?.message, validation?.uiDiagnostic?.correction, causeText)
            .filter { it.isNotBlank() }.distinct().joinToString(" ")
    }
    private fun <T> generatedOutput(problem: GenerationProblem, validate: () -> T): T = try { validate() }
        catch(failure: Exception) {
            if(failure is CancellationException) throw failure
            if(failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is org.json.JSONException) throw failure
            val validation=when(failure) {
                is ToolValidationFailure -> failure
                is ToolNumericRangeFailure -> ToolValidationFailure(ToolValidationCategory.STATE_NUMBER)
                else -> null
            }
            throw InvalidGeneratedOutput(problem,validation,causeText=failure.message)
        }
    /** Only model output accepts scalar objects. Stored artifacts keep the strict canonical contract. */
    private fun normalizeDefinition(json: JsonObject): JsonObject {
        val scalarTypes = listOf("number","string","boolean").map(::JsonPrimitive)
        fun normalizeScalars(fields: JsonObject) = JsonObject(fields.mapValues { (_, value) ->
            if(value is JsonObject && value.keys == setOf("type") && value["type"] in scalarTypes) value.getValue("type") else value
        })
        val schema = json["stateSchema"] as? JsonObject ?: return json
        val normalized = JsonObject(normalizeScalars(schema).mapValues { (_, value) ->
            if(value is JsonObject && value["type"] == JsonPrimitive("list") && value["fields"] is JsonObject) {
                JsonObject(value + ("fields" to normalizeScalars(value.getValue("fields") as JsonObject)))
            } else value
        })
        // JsonPrimitive preserves numeric literals. JSONObject would turn values such as 2.0 into integers.
        return JsonObject(json + ("stateSchema" to normalized))
    }

    private suspend fun enforcePolicy(check: ((String) -> Unit)?, json: String, problem: GenerationProblem) {
        if(check==null)return
        currentCoroutineContext().ensureActive()
        try { check(json) }
        catch(cancel: CancellationException) { throw cancel }
        catch(failure: Exception) {
            currentCoroutineContext().ensureActive()
            if(failure is CloudFailure && failure.code=="cancelled") throw failure
            throw problem.failure()
        }
        currentCoroutineContext().ensureActive()
    }

    internal suspend fun testAndFix(key: String, request: String, base: ToolRecord, privateState: Boolean,
        session: CloudSession): TestFixResult {
        val agent = appAgent(key, session)
        val result = try {
            agent.run(AppAgentRequest(request, base, emptyList(), base.id, privateState,
                extraInstructions = AppAgentGuide.testFix, testFix = true), session)
        } catch (failure: CloudFailure) {
            if (failure.code != "widget_agent_incomplete") throw failure
            val status = if (agent.edited || agent.reproduced) TestFixStatus.UNREPAIRED else TestFixStatus.UNVERIFIED
            return TestFixResult(status, failure.message)
        }
        return if (result.changed) TestFixResult(TestFixStatus.VERIFIED, result.summary, candidate = result.preview)
        else TestFixResult(TestFixStatus.PASSED, result.summary)
    }

    private fun appAgent(key: String, session: CloudSession) = AppAgent(this, sandbox) { turns, instructions ->
        response(key, turns, session, CloudGenerationStage.AGENT, instructions, expandedWriterOutput = true)
    }

    internal suspend fun response(key: String, turns: List<CloudTurn>, session: CloudSession, stage: CloudGenerationStage?,
        instructions: String = "", expandedWriterOutput: Boolean = false): String {
        val maxBytes = if (expandedWriterOutput) ToolSpec.MAX_DEFINITION_BYTES else SandboxContract.MAX_BYTES
        val output = StringBuilder()
        fun tooLarge() = CloudFailure("widget_response_size", "The generator response exceeds the allowed size. Request a smaller tool.")
        session.checkActive(currentCoroutineContext())
        try {
            provider.respond(key, CloudRequest(turns, false, systemInstructions = instructions, jsonObject = true,
                generationStage = stage, expandedWriterOutput = expandedWriterOutput), session) { event ->
                if (event is CloudEvent.Text) {
                    if (event.value.length > maxBytes - output.length) throw tooLarge()
                    output.append(event.value)
                }
            }
        } catch (failure: java.io.IOException) {
            session.checkActive(currentCoroutineContext())
            if (failure is CloudFailure) throw failure
            throw CloudFailure("widget_generation", "The tool generation connection failed. Retry the request.")
        }
        session.checkActive(currentCoroutineContext())
        return output.toString().also { if (it.toByteArray(Charsets.UTF_8).size > maxBytes) throw tooLarge() }
    }

    suspend fun generate(key: String, prompt: String, base: ToolRecord?, images: List<String>, session: CloudSession, artifactId: String,
        privateState: Boolean = false,
        progress: ((ToolGenerationProgress) -> Unit)? = null): ToolPreview =
        generate(key,prompt,base,images,session,artifactId,privateState,progress,trace=null)

    internal suspend fun generate(key: String, prompt: String, base: ToolRecord?, images: List<String>, session: CloudSession, artifactId: String,
        privateState: Boolean,
        progress: ((ToolGenerationProgress) -> Unit)? = null, trace: ToolGenerationTrace?,
        onPublicUpdate: (suspend (String) -> Unit)? = null, checkOwner: suspend () -> Unit = {},
        imageOperation: String? = null, imageSelection: ImageSettings.Selection? = null,
        sceneAssets: List<ThreeSceneAssetRef> = emptyList()): ToolPreview {
        val run = trace?.Run()
        val previousTransportObserver = session.generationTransportObserver
        if (run != null) session.generationTransportObserver = {
            run.transport(it)
            previousTransportObserver?.invoke(it)
        }
        try {
            // Each generate_image call is one new operation, so a repeated call cannot resubmit an earlier one.
            var imageCalls = 0
            val imageMaker = if (imageOperation == null || imageSelection == null) null else AppImageMaker { intents ->
                imageGeneration.generate("$imageOperation:${++imageCalls}", intents, imageSelection, intents.size)
            }
            return generateWithAgent(key, prompt, base, images, session, artifactId, privateState, progress, onPublicUpdate,
                onStep = run?.let { { step, tool, reply, result, nanos -> it.agentStep(step, tool, reply, result, nanos) } },
                imageMaker = imageMaker, sceneAssets = sceneAssets)
                .preview.also { checkOwner(); run?.finish() }
        } catch (failure: Throwable) {
            run?.finish(failure)
            throw failure
        } finally {
            if (run != null) session.generationTransportObserver = previousTransportObserver
        }
    }

    private suspend fun generateWithAgent(key: String, prompt: String, base: ToolRecord?, images: List<String>, session: CloudSession,
        artifactId: String, privateState: Boolean, progress: ((ToolGenerationProgress) -> Unit)?,
        onPublicUpdate: (suspend (String) -> Unit)?, extraInstructions: String = "",
        onStep: ((Int, String, String, String, Long) -> Unit)? = null, imageMaker: AppImageMaker? = null,
        sceneAssets: List<ThreeSceneAssetRef> = emptyList(), lesson: AppLessonTarget? = null,
        lessonIllustration: Boolean = false): AppAgentResult {
        val screenshot: (suspend (ToolPreview) -> String)? = if (!GenerationSettings(sandbox.context).screenshotReview) null else { preview ->
            val shot = AppSnapshot.capture(sandbox.context, preview)
            try { AppSnapshot.dataUrl(shot) } finally { shot.recycle() }
        }
        return appAgent(key, session).run(AppAgentRequest(prompt, base, images, artifactId, privateState, extraInstructions,
            onPublicUpdate, progress, onStep, imageMaker = imageMaker, sceneAssets = sceneAssets, screenshot = screenshot, lesson = lesson,
            lessonIllustration = lessonIllustration), session)
    }

    /** Accepts scalar schema objects from model output. Stored artifacts keep the strict canonical contract. */
    internal fun normalizeAgentDefinition(raw: String): String =
        normalizeDefinition(Json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException("The app must be a JSON object.")).toString()

    /** Builds one course slide with the app agent. The course code supplies the rules in [lesson]. */
    internal suspend fun generateLesson(
        key: String, prompt: String, session: CloudSession, artifactId: String, lesson: AppLessonTarget,
    ): LessonToolPreview {
        val result = generateWithAgent(key, prompt, null, emptyList(), session, artifactId, privateState = false,
            progress = null, onPublicUpdate = null, lesson = lesson, lessonIllustration = imageGeneration.settings.selection() != null)
        val made = checkNotNull(result.lesson)
        return LessonToolPreview(result.preview, made.sourceIds, made.illustration)
    }

    suspend fun evaluate(spec: ToolSpec, id: String, revision: Int, state: String, event: JSONObject, fallbackUi: String,
        retained: com.alpha.assistant.sandbox.GraphicsSession? = null, policy: ToolExecutionPolicy? = null): ToolPreview {
        check(!spec.threeScene) { "A Three.js scene requires its foreground worker." }
        enforcePolicy(policy?.validateDefinition,spec.json,GenerationProblem.SPEC)
        return try {
            executeEvent(spec,id,revision,state,event,fallbackUi,policy) { source,input ->
                if(retained == null) sandbox.execute(source,input.toString())
                else retained.event(event,input.getJSONObject("state").toString())
            }
        } catch(failure: CloudFailure) {
            if(failure.code == "javascript_invalid_result") throw InvalidGeneratedOutput(GenerationProblem.RESULT, causeText = failure.message)
            throw failure
        }
    }

    internal suspend fun executeEvent(spec: ToolSpec, id: String, revision: Int, state: String, event: JSONObject, fallbackUi: String,
        policy: ToolExecutionPolicy? = null,
        execute: suspend (source: String, input: JSONObject) -> SandboxResult): ToolPreview {
        currentCoroutineContext().ensureActive()
        val type=event.getString("type")
        val liveInput=type==SandboxContract.LIVE_INPUT_EVENT
        val controlEvent=type=="action" || liveInput
        if(liveInput) generatedOutput(GenerationProblem.RESULT) { SandboxContract.validateLiveInputEvent(event) }
        val fullState=spec.validateState(state)
        fun validateControl(ui: JSONObject, values: JSONObject) {
            val id=event.getString("actionId")
            val payload=event.opt("value").takeUnless {it==JSONObject.NULL}
            when {
                liveInput -> ToolUiCatalog.validateLiveInput(ui,id,payload,values)
                event.has("video") -> {
                    require(payload is String)
                    ToolUiCatalog.validateVideoAction(ui,id,payload,values)
                }
                else -> ToolUiCatalog.validateAction(ui,id,payload,values)
            }
        }
        if(controlEvent) generatedOutput(GenerationProblem.RESULT) { validateControl(JSONObject(fallbackUi),fullState) }
        val input=JSONObject().put("artifactId",id).put("revision",revision).put("state",fullState).put("event",event)
        val result=execute(spec.source,input)
        currentCoroutineContext().ensureActive()
        if(result.status != "OK") throw CloudFailure("javascript_${result.status.lowercase(java.util.Locale.ROOT)}",result.message)
        val raw=checkNotNull(result.json)
        enforcePolicy(policy?.validateResult,raw,GenerationProblem.RESULT)
        return validateResult(spec,state,event,fallbackUi,raw)
    }

    internal fun validateResult(spec: ToolSpec,state: String,event: JSONObject,fallbackUi: String,raw: String): ToolPreview =
        generatedOutput(GenerationProblem.RESULT) {
            SandboxContract.validateResult(raw,liveInput=event.getString("type")==SandboxContract.LIVE_INPUT_EVENT)
            val json = JSONObject(raw)
            val next = spec.validateState(json.getJSONObject("state").toString())
            val ui = spec.validateUi(json.optJSONObject("ui") ?: JSONObject(fallbackUi),next,ToolUiValidationStage.PREVIEW,null).toString()
            val requestedEffects = json.optJSONArray("effects") ?: JSONArray()
            val records=json.optJSONArray("records") ?: JSONArray()
            ToolRecords.validateOperations(records)
            if (records.length() != 0 && requestedEffects.length() != 0)
                throw InvalidGeneratedOutput(GenerationProblem.RECORDS_WITH_EFFECTS)
            repeat(records.length()) {
                val operation=records.getJSONObject(it)
                require(spec.collections.has(operation.getString("collection")))
                if(operation.getString("op")=="put") {
                    ToolValueSchema.validate(spec.collections.get(operation.getString("collection")),operation.get("value"))
                    val record=JSONObject().put("key",operation.getString("key")).put("value",operation.get("value"))
                    // A native 64-character token adds 75 UTF-8 bytes to this closed record envelope.
                    require(record.toString().toByteArray(Charsets.UTF_8).size+75<=8192) {"The record exceeds 8 KiB."}
                }
            }
            val type = event.getString("type")
            val readOnly = type == "preview" || type == "render"
            if (type != "action") {
                require(records.length() == 0) { "Only explicit action events can request records." }
                if(requestedEffects.length() != 0) throw GenerationProblem.PREVIEW_EFFECTS.failure()
                if(readOnly && !sameToolJson(next, JSONObject(state))) throw GenerationProblem.PREVIEW_STATE.failure()
                if(type == "capabilityResults") require(spec.capabilityResults)
                if(type == "recordResults") require(spec.collections.length() > 0)
            }
            val effects = CapabilityCatalog.parseEffects(requestedEffects,spec.capabilities,type)
            val output=ArtifactProtocol.output(spec,json)
            ToolPreview(spec,if (readOnly) state else next.toString(),ui,effects,records,output.surfaces,output.frames,output.timers,output.playback)
        }
}

/** Revision and state tokens reject commands from old cards and drafts. */
class ToolEngine(private val store: ToolStore, private val generation: ToolGenerationService,
    private val permissionCheck: (String)->Boolean = store::permissionGranted) {
    private val sessionOwner=Any()
    @Volatile private var artifactsClosed=false
    private val mutex = Mutex()
    private val imageApprovals = mutableMapOf<String, ImageSettings.Selection>()
    suspend fun createDraft(prompt: String, toolId: String?, conversationId: String? = null, operationId: String? = null, expected: ToolRecord? = null): ToolDraft = mutex.withLock {
        require(isValidToolPrompt(prompt))
        val id=operationId ?: UUID.randomUUID().toString()
        ArtifactCoordinator.get(store,toolId ?: id).ordered {
            val base=toolId?.let {
                val current=current(store,it)
                if(expected!=null)check(current==expected) {"The mini-app changed. Request the change again."}
                ToolSpec.parsePersisted(current.spec)
                flush(store)
                checkNotNull(store.tool(it))
            }
            check(store.draft(id)==null) {"This operation already exists."}
            ToolDraft(id,toolId,base?.revision ?: 0,base?.stateVersion ?: 0,prompt,null,null,null,
                conversationId).also(store::saveDraft)
        }
    }
    suspend fun generate(draftId: String, key: String, images: List<String>, session: CloudSession,
        progress: ((ToolGenerationProgress) -> Unit)? = null): ToolDraft =
        generate(draftId,key,images,session,progress,previewObserver=null)

    suspend fun generate(draftId: String, key: String, images: List<String>, session: CloudSession,
        progress: ((ToolGenerationProgress) -> Unit)? = null, previewObserver: ToolDraftPreviewObserver?): ToolDraft {
        return generate(draftId, key, images, session, progress, previewObserver, trace = null)
    }

    internal suspend fun generate(draftId: String, key: String, images: List<String>, session: CloudSession,
        progress: ((ToolGenerationProgress) -> Unit)? = null, previewObserver: ToolDraftPreviewObserver?,
        trace: ToolGenerationTrace?,
        sceneAssets: List<ThreeSceneAssetRef> = emptyList(), onPublicUpdate: (suspend (String) -> Unit)? = null,
        checkOwner: suspend () -> Unit = {}): ToolDraft {
        val draftPreview=previewObserver?.let {ToolDraftPreviewPublisher(draftId,it)}
        val imageSelection = generation.imageGeneration.settings.selection()
        val assetLease = when {
            imageSelection != null -> generation.imageGeneration.lease()
            sceneAssets.isNotEmpty() -> ThreeSceneAssetCleanup(store.context).lease()
            else -> null
        }
        try {
            val (draft, base) = mutex.withLock { val d = checkNotNull(store.draft(draftId)); check(d.acceptedRevision == null); d to d.toolId?.let { current(it,d.baseRevision,d.baseStateVersion) } }
            val preview = generation.generate(key, draft.prompt, base, images, session, draft.toolId ?: draft.id,
                privateState = base?.let { store.privateResults(it.id) } == true, progress = progress,
                trace = trace?.takeIf { it.operationId.toString() == draftId }, onPublicUpdate = onPublicUpdate,
                checkOwner = checkOwner, imageOperation = "app:$draftId", imageSelection = imageSelection, sceneAssets = sceneAssets)
            val existingImages = base?.let { ToolSpec.parsePersisted(it.spec).generatedImages }.orEmpty()
            val newImages = preview.spec.generatedImages - existingImages.toSet()
            val approval = imageSelection?.takeIf { newImages.isNotEmpty() }
            if (approval != null) ensureImageApproval(approval)
            session.checkActive(currentCoroutineContext())
            checkOwner()
            return mutex.withLock {
                session.checkActive(currentCoroutineContext())
                if (approval != null) ensureImageApproval(approval)
                val next = draft.copy(candidate = preview.spec.json, previewState = preview.state, previewUi = preview.ui,
                    previewSurfaces = preview.surfaces?.toString())
                store.transaction {
                    check(store.draft(draftId) == draft) { "Черновик изменился" }
                    draft.toolId?.let { current(it,draft.baseRevision,draft.baseStateVersion) }
                    store.saveDraft(next)
                }
                if (approval != null) imageApprovals[draftId] = approval
                else imageApprovals.remove(draftId)
                next
            }
        } catch(failure: Throwable) {
            draftPreview?.clear()
            throw failure
        } finally {
            assetLease?.close()
        }
    }
    internal suspend fun acceptTestFix(operationId: String, conversationId: String, expected: ToolRecord,
        request: String, candidate: ToolPreview, canCommit: () -> Boolean): ToolRecord {
        val draft = ToolDraft(operationId, expected.id, expected.revision, expected.stateVersion, request,
            candidate.spec.json, candidate.state, candidate.ui, conversationId)
        return acceptDraft(operationId, true, canCommit, draft, expected)
    }

    private fun ensureImageApproval(selection: ImageSettings.Selection) {
        try {
            generation.imageGeneration.ensureCurrent(selection)
        } catch (failure: ImageFailure) {
            throw CloudFailure("image_generation", failure.message.orEmpty())
        }
    }

    suspend fun accept(draftId: String, retainReceipt: Boolean = false, canCommit: () -> Boolean = { true }): ToolRecord =
        acceptDraft(draftId, retainReceipt, canCommit)

    private suspend fun acceptDraft(draftId: String, retainReceipt: Boolean, canCommit: () -> Boolean,
        verifiedRepair: ToolDraft? = null, expected: ToolRecord? = null): ToolRecord = mutex.withLock {
        val context=currentCoroutineContext()
        context.ensureActive()
        val d = verifiedRepair ?: checkNotNull(store.draft(draftId))
        check(d.acceptedRevision == null) { "This operation is already complete." }
        fun ArtifactCoordinator.commit(rules: AutomationStore?): ToolRecord {
            val old=d.toolId?.let {
                if (expected != null && (store.tool(it) != expected || store.artifactWorkPending(it)))
                    throw TestFixNotSaved("The saved app changed during testing.")
                val saved=current(it,d.baseRevision,d.baseStateVersion)
                check(current(store,it)==saved) { "Инструмент изменился. Открой текущую версию." }
                saved
            }
            val spec=ToolSpec.parsePersisted(checkNotNull(d.candidate))
            val existingImages = old?.let { ToolSpec.parsePersisted(it.spec).generatedImages }.orEmpty()
            val newImages = spec.generatedImages - existingImages.toSet()
            fun checkCommit() {
                context.ensureActive()
                check(canCommit()) { "The request was canceled." }
                if (newImages.isNotEmpty()) {
                    val selection = imageApprovals[draftId]
                        ?: throw CloudFailure("image_generation", "Generate the images with current consent before saving this draft.")
                    ensureImageApproval(selection)
                }
            }
            val state=spec.validateState(checkNotNull(d.previewState)).toString()
            val ui=spec.validateUi(JSONObject(checkNotNull(d.previewUi)),JSONObject(state)).toString()
            val next=ToolRecord(old?.id ?: d.id,(old?.revision ?: 0)+1,(old?.stateVersion ?: 0)+1,spec.json,state,ui,
                old?.conversationId,true,createdAt=old?.createdAt ?: System.currentTimeMillis(),
                lastConversationId=d.conversationId ?: old?.lastConversationId,onDesktop=old?.onDesktop ?: false)
            if(rules==null) store.transaction {
                checkCommit()
                store.commitTool(next,null,true,d,retainReceipt=retainReceipt)
            } else AutomationRuntime.commitToolRevision(store, rules, next.id, validateImpact = if (verifiedRepair != null) { impact ->
                if (impact.isNotEmpty()) throw TestFixNotSaved("Saving this repair would change a dependent automation.")
            } else null) { stopped ->
                checkCommit()
                if (verifiedRepair != null) {
                    check(store.draft(d.id) == null) { "This operation already exists." }
                    store.saveDraft(d)
                }
                store.commitTool(next,old,true,d,retainReceipt=retainReceipt,
                    revisionLabel=d.prompt.trim().take(100),stoppedAutomations=stopped)
            }
            imageApprovals.remove(draftId)
            invalidate()
            committed(next)
            return next
        }
        val owner=d.toolId
        if(owner==null) ArtifactCoordinator.get(store,d.id).ordered { commit(null) }
        else AutomationRuntime.withToolRevisionChange(store,owner) { rules -> commit(rules) }
    }
    suspend fun reject(draftId: String) = mutex.withLock {
        store.deleteDraft(draftId)
        imageApprovals.remove(draftId)
        Unit
    }

    internal suspend fun exportPortable(expected: ToolRecord, includeState: Boolean, output: java.io.OutputStream) {
        val tool = ArtifactCoordinator.get(store, expected.id).ordered {
            val saved = checkNotNull(store.tool(expected.id)) { "Мини-приложение недоступно." }
            check(saved.saved && saved.revision == expected.revision && saved.createdAt == expected.createdAt) {
                "Мини-приложение изменилось. Откройте текущую версию."
            }
            saved
        }
        currentCoroutineContext().ensureActive()
        check(ToolSpec.parsePersisted(tool.spec).generatedImages.isEmpty() &&
            (!includeState || store.resources(tool.id).none { it.kind == com.alpha.assistant.capabilities.MiniAppImage.RESOURCE })) {
            store.context.getString(com.alpha.assistant.R.string.image_export_unavailable)
        }
        com.alpha.assistant.portability.MiniAppArchive.write(tool, includeState, output)
    }

    internal suspend fun importPortable(input: java.io.InputStream): ToolRecord {
        val context = currentCoroutineContext()
        val archive = com.alpha.assistant.portability.MiniAppArchive.read(input, store.context.cacheDir.toPath()) {
            context.ensureActive()
        }
        val spec = archive.spec
        return ArtifactCoordinator.get(store, archive.id).ordered {
            fun checkConflict() {
                check(!store.isDeleted(archive.id) && store.tool(archive.id) == null &&
                    store.tools().none { ToolSpec.storedTitle(it.spec).trim().equals(spec.title.trim(), ignoreCase = true) }) {
                    "В библиотеке уже есть мини-приложение с таким идентификатором или названием."
                }
            }
            checkConflict()
            val preview = generation.evaluate(spec, archive.id, 1, archive.state,
                JSONObject().put("type", "render"), spec.ui.toString())
            val imported = ToolRecord(archive.id, 1, 1, spec.json, archive.state, preview.ui, null, saved = true)
            store.transaction {
                checkConflict()
                context.ensureActive()
                store.commitTool(imported, old = null, revision = true, revisionLabel = "Импорт")
            }
            committed(imported)
            imported
        }
    }

    internal suspend fun saveManualDefinition(expected: ToolRecord, definition: String): ToolRecord =
        AutomationRuntime.withToolRevisionChange(store, expected.id) { rules ->
            val old = current(store, expected.id, expected.revision, expected.stateVersion)
            check(old == expected && old.saved && !store.artifactWorkPending(old.id)) {
                "Мини-приложение изменилось. Откройте текущую версию."
            }
            val previous = ToolSpec.parsePersisted(old.spec)
            val spec = ToolSpec.parsePersisted(definition).withAppLanguage(previous.appLanguage).withIcon(previous.iconId)
            val state = ToolSpec.stateForRevision(previous, spec, old.state)
            val revision = Math.incrementExact(old.revision)
            val preview = generation.evaluate(spec, old.id, revision, state,
                JSONObject().put("type", "render"), spec.ui.toString())
            val next = old.copy(revision = revision, stateVersion = Math.incrementExact(old.stateVersion),
                spec = spec.json, state = state, ui = preview.ui)
            val context = currentCoroutineContext()
            AutomationRuntime.commitToolRevision(store, rules, old.id) { stopped ->
                context.ensureActive()
                store.commitTool(next, old, true, revisionLabel = "Ручное изменение", stoppedAutomations = stopped)
            }
            invalidate()
            committed(next)
            next
        }

    suspend fun openArtifact(expected: ToolRecord): ArtifactSnapshot =
        openArtifact(expected.id,expected.revision,expected.stateVersion)

    internal suspend fun openArtifactHost(id: String,revision: Int,canStart: ()->Boolean={true}): ArtifactSnapshot =
        openArtifact(id,revision,stateVersion=null,waitForRetainedRelease=true,canStart=canStart)

    private suspend fun openArtifact(id: String,revision: Int,stateVersion: Int?,waitForRetainedRelease: Boolean=false,
        canStart: ()->Boolean={true}): ArtifactSnapshot =
        ArtifactCoordinator.get(store,id).ordered {
            check(!artifactsClosed) {"The artifact owner is closed."}
            open(store,generation,id,revision,stateVersion,sessionOwner,waitForRetainedRelease) {!artifactsClosed && canStart()}
        }

    internal suspend fun startArtifactHostSession(id: String,revision: Int,sessionGeneration: Long): ArtifactSnapshot =
        ArtifactCoordinator.get(store,id).ordered {
            ensureAvailable();current(store,id,revision)
            startHostSession(store,id,revision,sessionOwner,sessionGeneration)
        }

    suspend fun artifactSnapshot(id: String,revision: Int,sessionGeneration: Long?=null): ArtifactSnapshot = ArtifactCoordinator.get(store,id).ordered {
        ensureAvailable();current(store,id,revision);refreshHostSnapshot(store,sessionOwner,sessionGeneration)
    }
    suspend fun flushArtifact(id: String,revision: Int,sessionGeneration: Long?=null): ArtifactSnapshot = ArtifactCoordinator.get(store,id).ordered {
        ensureAvailable();current(store,id,revision)
        if(hostSnapshot(store,sessionOwner,sessionGeneration).frameOwner) flush(store)
        hostSnapshot(store,sessionOwner,sessionGeneration)
    }
    suspend fun artifactEvent(id: String,revision: Int,event: JSONObject,sessionGeneration: Long?=null): ArtifactSnapshot = ArtifactCoordinator.get(store,id).ordered {
        ensureAvailable();current(store,id,revision)
        hostEvent(store,id,revision,sessionOwner,sessionGeneration,event)
    }
    suspend fun closeArtifact(id: String,revision: Int,sessionGeneration: Long?=null) = ArtifactCoordinator.get(store,id).ordered {closeOwned(store,revision,sessionOwner,sessionGeneration)}
    suspend fun closeArtifacts() {artifactsClosed=true;ArtifactCoordinator.closeOwned(store,sessionOwner)}
    suspend fun interact(id: String, revision: Int, stateVersion: Int, actionId: String, value: Any?,
        allowDeviceEffects: Boolean = true): ToolRecord =
        interact(id,revision,stateVersion,actionId,value,allowDeviceEffects,onWidgetEvaluated=null)

    internal suspend fun interact(id: String, revision: Int, stateVersion: Int, actionId: String, value: Any?,
        allowDeviceEffects: Boolean, onWidgetEvaluated: (suspend (ToolPreview)->Unit)?): ToolRecord =
        interactAction(id,revision,stateVersion,actionId,value,allowDeviceEffects,onWidgetEvaluated,acceptNetwork=false).tool

    internal suspend fun interactForeground(id: String, revision: Int, stateVersion: Int, actionId: String, value: Any?,
        acceptNetwork: Boolean, acceptAi: Boolean = false): ToolForegroundAction =
        interactAction(id,revision,stateVersion,actionId,value,allowDeviceEffects=true,onWidgetEvaluated=null,acceptNetwork=acceptNetwork,acceptAi=acceptAi)

    internal suspend fun interactLocalButton(expected: ToolRecord, actionId: String, active: () -> Boolean): ToolRecord =
        interactAction(expected.id, expected.revision, expected.stateVersion, actionId, null,
            allowDeviceEffects = true, onWidgetEvaluated = null, acceptNetwork = false,
            localButton = LocalButtonAction(expected.createdAt, active)).tool

    private class VideoAction(val control: NativeVideoControl, val active: () -> Boolean)
    private class LocalButtonAction(val createdAt: Long, val active: () -> Boolean)

    internal suspend fun interactVideo(expected: ToolRecord, control: NativeVideoControl, active: () -> Boolean): ToolRecord =
        interactAction(expected.id,expected.revision,expected.stateVersion,control.actionId,control.resourceId,allowDeviceEffects=true,
            onWidgetEvaluated=null,acceptNetwork=false,video=VideoAction(control,active)).tool

    private suspend fun interactAction(id: String, revision: Int, stateVersion: Int, actionId: String, value: Any?,
        allowDeviceEffects: Boolean, onWidgetEvaluated: (suspend (ToolPreview)->Unit)?, acceptNetwork: Boolean,
        video: VideoAction? = null, acceptAi: Boolean = false,
        localButton: LocalButtonAction? = null): ToolForegroundAction = ArtifactCoordinator.get(store,id).ordered {
        ensureAvailable()
        if (localButton?.active?.invoke() == false) throw CancellationException("The mini-app closed.")
        if(video!=null) check(video.active()) {"The video host is unavailable."}
        val widgetBase=if(allowDeviceEffects) null else checkNotNull(store.tool(id))
        // A tick may commit between the click and this queue slot. The action is validated against the current UI below.
        val captured=current(store,id,revision,stateVersion.takeUnless {ticksOnlySince(store,id,it)})
        if(allowDeviceEffects) flush(store)
        val input=if(allowDeviceEffects) current(store,id,revision) else captured
        val spec = ToolSpec.parsePersisted(input.spec)
        val state=spec.validateState(input.state)
        val ui=spec.validateUi(JSONObject(input.ui),state)
        val selected=if(video==null) ToolUiCatalog.validateAction(ui,actionId,value,state) else {
            check(input.saved && VideoContract.PLAY in spec.capabilities && video.active()) {"The video host is unavailable."}
            ToolUiCatalog.validateVideoAction(ui,actionId,video.control.resourceId,state)
        }
        if (localButton != null) {
            check(input.createdAt == localButton.createdAt && selected.getString("type") == "button" && spec.canQueueLocalButtons)
        }
        val event = JSONObject().put("type","action").put("actionId",actionId).put("value",value ?: JSONObject.NULL)
        if(video!=null) event.put("video",JSONObject().put("resourceId",video.control.resourceId))
        if(selected.getString("type") == "calendar") {
            val calendar=OwnedCalendarContract.request(selected)
            event.put("calendar",JSONObject().put("capability",calendar.capability).put("args",calendar.args))
        }
        if(selected.getString("type") == "medicine") {
            val medicine=MedicineContract.request(selected)
            event.put("medicine",JSONObject().put("capability",medicine.capability).put("args",medicine.args))
        }
        if(selected.getString("type") == "navigation") {
            val navigation=NavigationContract.request(selected)
            event.put("navigation",JSONObject().put("capability",navigation.capability).put("args",navigation.args))
        }
        val result=if(allowDeviceEffects) evaluate(generation,input,event) else {
            // A rejected widget action must not mutate a foreground isolate or flush live state.
            val preview=generation.evaluate(spec,input.id,input.revision,input.state,event,input.ui)
            if(preview.effects.isNotEmpty())
                throw CloudFailure("widget_foreground_required","Откройте мини-приложение, чтобы выполнить это системное действие.")
            val validated=prepareOutput(preview)
            onWidgetEvaluated?.invoke(validated)
            validated
        }
        val network=result.effects.singleOrNull()?.takeIf {it.capability==NetworkEffectContract.ID}
        if (localButton != null && (!localButton.active() || result.effects.isNotEmpty() || result.records.length() != 0)) {
            invalidate()
            if (!localButton.active()) throw CancellationException("The mini-app closed.")
            error("A local button cannot request effects or record operations.")
        }
        val videoEffect=result.effects.firstOrNull {it.capability==VideoContract.PLAY}
        val invalidVideo=if(video==null) videoEffect!=null else {
            result.effects.size!=1 || videoEffect?.args?.getString("resourceId")!=video.control.resourceId ||
                runCatching {ToolUiCatalog.validateVideoAction(JSONObject(result.ui),actionId,video.control.resourceId,JSONObject(result.state))}.isFailure
        }
        if(invalidVideo) {
            invalidate()
            throw CloudFailure("video_control_required","Видео требует текущего видимого элемента воспроизведения.")
        }
        if(network!=null && !acceptNetwork) {
            invalidate()
            throw CloudFailure("network_foreground_required","Откройте мини-приложение, чтобы отправить запрос.")
        }
        if (result.effects.any { it.capability == com.alpha.assistant.capabilities.MiniAppAi.ID } &&
            (!acceptAi || !input.saved || !spec.capabilityResults)) {
            invalidate()
            throw CloudFailure("ai_foreground_required", "Для запроса к DeepSeek откройте сохранённое мини-приложение с поддержкой результатов.")
        }
        if (result.effects.any { it.capability == com.alpha.assistant.capabilities.MiniAppImage.ID } &&
            (!acceptAi || !input.saved || !spec.capabilityResults || spec.threeScene || value != null ||
                selected.optString("type") != "button" || selected.optString("action") != actionId)) {
            invalidate()
            throw CloudFailure("image_foreground_required", "Откройте сохранённое мини-приложение, чтобы создать изображение.")
        }
        val old=if(widgetBase==null) input else {
            check(store.tool(id)==widgetBase) {"Saved data changed during widget evaluation. Reopen the mini-app."}
            // A flush may persist the captured live state and advance its token exactly once.
            val flushedVersion=widgetBase.stateVersion+if(input.state!=widgetBase.state || input.ui!=widgetBase.ui) 1 else 0
            flush(store)
            current(store,id,revision,flushedVersion).also {
                check(it==input.copy(stateVersion=flushedVersion)) {"Saved data changed during widget evaluation. Reopen the mini-app."}
            }
        }
        if (result.effects.any { it.capability == TiltContract.ID } && spec.surfaces.isEmpty())
            throw CloudFailure("tilt_surface_required", "Tilt input requires a drawing surface.")
        if (result.effects.any { it.capability == VideoContract.CAPTURE } && !spec.capabilityResults)
            throw CloudFailure("video_result_required", "Video capture requires capability results.")
        val next=old.copy(stateVersion=old.stateVersion+1,state=result.state,ui=result.ui)
        if(video!=null && !video.active()) {
            invalidate()
            throw CancellationException("The video surface lease closed before commit.")
        }
        val context=currentCoroutineContext(); context.ensureActive()
        if(result.records.length() > 0) {
            ToolRecords(store).commitAction(old,next,result.records) { context.ensureActive(); true }
            committed(next,result)
            deliverRecordResults(id,this)
            ToolForegroundAction(checkNotNull(store.tool(id)).also {committed(it)},null)
        } else {
            context.ensureActive()
            store.commitTool(next,old,false,effects=result.effects)
            committed(next,result)
            ToolForegroundAction(next,network)
        }
    }
    /** Pure result evaluation occurs outside SQLite. The receipt and state share one guarded commit. */
    suspend fun deliverCapabilityResults(owner: String) = deliverCapabilityResults(owner,null,{ true })

    internal suspend fun deliverForegroundEffectResults(changed: ToolRecord, active: () -> Boolean) =
        deliverCapabilityResults(changed.id,changed,active)

    internal suspend fun deliverNetworkEffectResult(changed: ToolRecord, effect: CapabilityEffect, result: CapabilityResult,
        active: () -> Boolean, authorizePublication: () -> Boolean) =
        deliverCapabilityResults(changed.id,changed,active,effect.copy(result=result),authorizePublication)

    private suspend fun deliverCapabilityResults(owner: String, foregroundEffect: ToolRecord?, active: () -> Boolean,
        networkResult: CapabilityEffect? = null, authorizePublication: () -> Boolean = { true }) = ToolStore.deliveryMutex.withLock {
      ArtifactCoordinator.get(store,owner).ordered {
        if(!active()) return@ordered
        store.recoverCapabilityWork()
        if(store.tool(owner)?.let {ToolSpec.availablePersisted(it.spec)}==null) return@ordered
        if(store.tool(owner)?.archived==true) {
            ToolRecords(store).claimDelivery(owner)
            store.claimDelivery(owner,permissionCheck)
            store.releaseRejectedResources(owner)
            return@ordered
        }
        current(store,owner)
        flush(store)
        if(foregroundEffect==null) deliverRecordResults(owner,this)
        while(active()) {
            currentCoroutineContext().ensureActive()
            val delivery=store.claimDelivery(owner,foregroundEffect,networkResult?.key,permissionCheck)
            store.releaseRejectedResources(owner)
            if(delivery == null) break
            var foregroundDispatched=false
            try {
                val old=current(store,owner,delivery.revision,delivery.stateVersion)
                val event=if(networkResult==null) JSONObject(checkNotNull(delivery.event)) else {
                    check(store.effect(networkResult.key)?.result?.status==networkResult.result.status)
                    CapabilityResultEvent.event(delivery.id,listOf(networkResult))
                }
                val result=evaluate(generation,old,event) {
                    if(foregroundEffect!=null) {
                        if(!active()) throw CancellationException("The foreground operation closed before delivery.")
                        foregroundDispatched=true
                    }
                }
                val context=currentCoroutineContext()
                context.ensureActive()
                val next=old.copy(stateVersion=old.stateVersion+1,state=result.state,ui=result.ui)
                val accepted=store.commitDelivery(delivery,next,canCommit={ context.ensureActive(); active() },authorizePublication=authorizePublication) {
                    context.ensureActive(); permissionCheck(it)
                }
                if(accepted)committed(next,result)
                else if(foregroundDispatched)invalidate()
            } catch(cancel: CancellationException) {
                store.rejectDelivery(delivery.id)
                if(foregroundDispatched)invalidate()
                throw cancel
            } catch(_: Exception) {
                if(foregroundEffect==null) store.retryDelivery(delivery.id)
                else {
                    store.rejectDelivery(delivery.id)
                    if(foregroundDispatched)invalidate()
                }
            }
            finally { store.releaseRejectedResources(owner) }
        }
      }
    }
    private suspend fun deliverRecordResults(owner: String,coordinator: ArtifactCoordinator) {
        val records=ToolRecords(store)
        while(true) {
            currentCoroutineContext().ensureActive()
            val delivery=records.claimDelivery(owner) ?: break
            try {
                val old=coordinator.current(store,owner,delivery.revision,delivery.stateVersion)
                val result=coordinator.evaluate(generation,old,JSONObject(delivery.event))
                val context=currentCoroutineContext(); context.ensureActive()
                val next=old.copy(stateVersion=old.stateVersion+1,state=result.state,ui=result.ui)
                if(records.commitDelivery(delivery,next) {context.ensureActive();true})coordinator.committed(next,result)
            } catch(cancel: CancellationException) { throw cancel }
              catch(_: Exception) { if(delivery.attempts >= 2) records.reject(delivery) }
        }
    }
    suspend fun restoreAvailability(expected: ToolRecord,target: Int): ToolRestoreAvailability =
        ArtifactCoordinator.get(store,expected.id).ordered {
            try {
                check(current(store,expected.id)==expected)
                val spec = restoreSpec(expected, target)
                if (spec.threeScene) {
                    spec.validateState(expected.state)
                    ThreeSceneAssets(store.context).workerAssets(spec.sceneAssets)
                } else renderRestore(expected, spec)
                check(store.tool(expected.id)==expected)
                ToolRestoreAvailability()
            } catch(cancel: CancellationException) {
                throw cancel
            } catch(failure: Exception) {
                when(failure) {
                    is CloudFailure -> ToolRestoreAvailability(failure.message,failure.code)
                    else -> ToolRestoreAvailability("Эта версия не прошла проверку на текущих данных.")
                }
            }
        }

    private fun restoreSpec(current: ToolRecord, target: Int): ToolSpec {
        check(!store.artifactWorkPending(current.id)) { "Дождись завершения текущего действия." }
        val previous = checkNotNull(store.revisions(current.id).find { it.revision == target }) { "Версия недоступна." }
        val activeSpec = ToolSpec.parsePersisted(current.spec)
        val targetSpec = ToolSpec.parsePersisted(previous.spec)
        check(activeSpec.threeScene == targetSpec.threeScene) { "Версия использует другой способ запуска." }
        return ToolSpec.forRestore(activeSpec, targetSpec)
    }

    private suspend fun renderRestore(current: ToolRecord, spec: ToolSpec): ToolPreview =
        if (spec.threeScene) checkThreeScene(spec, current.id, current.revision + 1, current.state,
            checkActive = { currentCoroutineContext().ensureActive() })
        else generation.evaluate(spec, current.id, current.revision + 1, current.state,
            JSONObject().put("type", "render"), spec.ui.toString())

    suspend fun rollback(id: String, revision: Int, stateVersion: Int, target: Int): ToolRecord =
        AutomationRuntime.withToolRevisionChange(store,id) { rules ->
            val old=current(id,revision,stateVersion)
            check(current(store,id)==old) { "Инструмент изменился. Открой текущую версию." }
            val preview=renderRestore(old,restoreSpec(old,target))
            val next=old.copy(revision=revision+1,stateVersion=stateVersion+1,spec=preview.spec.json,ui=preview.ui)
            currentCoroutineContext().ensureActive()
            AutomationRuntime.commitToolRevision(store,rules,id) { stopped ->
                check(!store.artifactWorkPending(id)) { "Дождись завершения текущего действия." }
                store.commitTool(next,old,true,revisionLabel="Восстановление v$target",stoppedAutomations=stopped)
            }
            invalidate()
            committed(next)
            next
        }

    suspend fun setDesktopPlacement(expected: ToolRecord,placed: Boolean): ToolRecord =
        updateArtifactMetadata(expected) { store.setDesktopPlacement(it,placed) }

    suspend fun recordLastConversation(expected: ToolRecord,conversationId: String): ToolRecord =
        updateArtifactMetadata(expected) { store.recordLastConversation(it,conversationId) }

    private suspend fun updateArtifactMetadata(expected: ToolRecord,update: (ToolRecord)->ToolRecord): ToolRecord =
        ArtifactCoordinator.get(store,expected.id).ordered {
            val current=current(store,expected.id,expected.revision)
            check(expected.saved && !expected.archived && current.saved && current.createdAt==expected.createdAt) {
                "Инструмент изменился. Открой текущую версию."
            }
            flush(store)
            update(current(store,expected.id,expected.revision)).also { committed(it) }
        }

    suspend fun save(id: String) = ArtifactCoordinator.get(store,id).ordered {
        current(store,id);flush(store);store.markSaved(id);committed(checkNotNull(store.tool(id)))
    }
    private fun current(id: String, revision: Int, stateVersion: Int) = checkNotNull(store.tool(id)).also {
        check(!it.archived) { "Инструмент в архиве. Сначала восстанови его." }
        check(it.revision == revision && it.stateVersion == stateVersion) { "Инструмент изменился. Открой текущую версию." }
    }
}
