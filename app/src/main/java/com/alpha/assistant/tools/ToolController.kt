package com.alpha.assistant.tools

import com.alpha.assistant.tools.three.ThreeSceneAssetCleanup
import com.alpha.assistant.tools.three.ThreeSceneAssets

import com.alpha.assistant.tools.three.ThreeSceneAssetRef
import com.alpha.assistant.tools.three.ThreeSceneSnapshot

import android.app.Application
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Looper
import android.content.pm.ShortcutManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.result.ActivityResult
import com.alpha.assistant.R
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.network.ui.MiniAppNetworkAccessHost
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.scheduling.SourceOffer
import com.alpha.assistant.scheduling.RecurringSearch
import com.alpha.assistant.scheduling.ScheduledTicket
import com.alpha.assistant.scheduling.SourceRefresh
import com.alpha.assistant.scheduling.SourceRefreshJobService
import com.alpha.assistant.surfaces.ToolWidgets
import com.alpha.assistant.surfaces.projectWidget
import com.alpha.assistant.surfaces.widgetActionId
import com.alpha.assistant.surfaces.widgetauthoring.WidgetAuthoring
import com.alpha.assistant.surfaces.widgetauthoring.WidgetAuthoringResult
import com.alpha.assistant.surfaces.widgetauthoring.WidgetDraftRequest
import com.alpha.assistant.surfaces.widgetauthoring.WidgetLayoutStore
import com.alpha.assistant.surfaces.widgetauthoring.WidgetSaveProposal
import com.alpha.assistant.surfaces.widgetauthoring.generateWidgetLayout
import kotlinx.coroutines.*
import com.alpha.assistant.capabilities.*
import com.alpha.assistant.automations.AutomationRuntime
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.tools.generation.StandaloneRefinementUnavailable
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class ToolController(private val app: Application, private val scope: CoroutineScope, private val provider: ChatProvider,
    private val keys: SecureKeyStore, private val store: ToolStore,
    private val readImage: (Attachment) -> String = { ImageInput.dataUrl(app.contentResolver,it) }) {
    private val librarySearch = com.alpha.assistant.semantic.SemanticIndex.get(app, "apps:${store.databaseName}")

    private fun libraryRecords() = store.tools().filter(ToolRecord::saved).map {
        val metadata = JSONObject(it.spec)
        com.alpha.assistant.semantic.SemanticRecord(it.id, it.revision.toString(),
            "${metadata.optString("title")}\n${metadata.optString("description")}")
    }

    private fun appCurrent(id: String, revision: String) =
        store.tool(id)?.let { it.saved && it.revision.toString() == revision } == true

    internal suspend fun searchLibrary(query: String, archived: Boolean): List<ToolRecord> = withContext(Dispatchers.IO) {
        val hits = librarySearch.search(query, 0.70f, 100, ::libraryRecords, ::appCurrent) { id -> store.tool(id)?.archived == archived }
        hits.mapNotNull { hit ->
            store.tool(hit.id)?.takeIf { it.saved && it.archived == archived && it.revision.toString() == hit.revision }
        }
    }

    init {
        scope.launch {
            store.changes.drop(1).collect {
                try {
                    librarySearch.update(::libraryRecords, ::appCurrent)
                } catch (failure: com.alpha.assistant.semantic.SemanticUnavailable) {
                    android.util.Log.w("AlphySemantic", "Library indexing unavailable", failure)
                }
            }
        }
    }

    private val generationService = ToolGenerationService(provider,SandboxClient(app))
    private val engine = ToolEngine(store,generationService)
    private val sources = SourceRefresh(store)
    private val recurringSearch = RecurringSearch(store, keys::consentIdentity)
    internal val sourceChanges get() = store.changes
    internal var sourceOffer by mutableStateOf<SourceOffer?>(null)
        private set

    internal suspend fun readRecurringSearch(tool: ToolRecord) = withContext(Dispatchers.IO) {
        try {
            recurringSearch.status(tool.id)?.takeIf { it.revision == tool.revision && it.createdAt == tool.createdAt }
        } catch (_: ToolLibraryUnavailable) {
            null
        }
    }

    internal suspend fun scheduleRecurringSearch(tool: ToolRecord, query: String, stop: ScheduledTicket? = null): Boolean = withContext(Dispatchers.IO) {
        if (closing || !store.primaryLibrary) return@withContext false
        val previous = recurringSearch.ticket(tool.id)
        if (stop != null) {
            val cancelled = recurringSearch.cancel(tool.id, stop.delivery.activationId) ?: return@withContext false
            SourceRefreshJobService.cancel(store, cancelled)
            return@withContext true
        }
        keys.cloudKey(app)
        val ticket = recurringSearch.activate(tool, query) ?: return@withContext false
        if (previous != null && previous != ticket) SourceRefreshJobService.cancel(store, previous)
        if (SourceRefreshJobService.enqueue(store, ticket)) return@withContext true
        recurringSearch.cancel(tool.id, ticket.delivery.activationId)
        false
    }

    internal suspend fun readSource(tool: ToolRecord) = withContext(Dispatchers.IO) {
        try {
            sources.status(tool.id)?.takeIf { it.offer.matches(tool) }?.let {
                it to (sources.ticket(tool.id) != null)
            }
        } catch (_: ToolLibraryUnavailable) {
            null
        }
    }

    internal suspend fun approveSource(offer: SourceOffer): Boolean = withContext(Dispatchers.IO) {
        val previous = sources.ticket(offer.owner)
        if (closing || !sources.approve(offer)) return@withContext false
        if (previous != null && sources.ticket(offer.owner) != previous) SourceRefreshJobService.cancel(store, previous)
        withContext(Dispatchers.Main.immediate) { if (sourceOffer == offer) sourceOffer = null }
        true
    }

    internal suspend fun refreshSource(offer: SourceOffer): Boolean = withContext(Dispatchers.IO) {
        if (closing || !sources.refresh(offer)) return@withContext false
        if (store.primaryLibrary) ToolWidgets.updateAll(app)
        true
    }

    internal suspend fun scheduleSource(offer: SourceOffer, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (closing || !store.primaryLibrary || sources.status(offer.owner)?.offer != offer) return@withContext false
        if (enabled) {
            val ticket = sources.activate(offer) ?: return@withContext false
            if (SourceRefreshJobService.enqueue(store, ticket)) return@withContext true
            sources.cancel(offer.owner)
            false
        } else {
            sources.cancel(offer.owner)?.let { SourceRefreshJobService.cancel(store, it) }
            true
        }
    }
    private val broker = CapabilityBroker(app,store)
    private val videoJobs = SupervisorJob(scope.coroutineContext[Job])
    private val videoScope = CoroutineScope(videoJobs+Dispatchers.Main.immediate)
    private val videoHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<ToolVideoHost>()
    var dependentRules by mutableStateOf<List<String>>(emptyList()); private set
    internal val automationDatabase=store.automationDatabase
    internal fun foregroundTimerDriver(tool: ToolRecord, spec: com.alpha.assistant.timer.ForegroundTimerSpec,
        scope: CoroutineScope, live: () -> Boolean) = com.alpha.assistant.timer.ForegroundTimerDriver(store, tool, spec, scope) { !closing && live() }

    internal suspend fun prepareWidgetLayout(tool: ToolRecord, instruction: String) = withContext(Dispatchers.IO) {
        if (closing || instruction.isBlank() || instruction.length > 1_000) return@withContext WidgetAuthoringResult.Rejected.INVALID_REQUEST
        val host = WidgetLayoutStore(store)
        val snapshot = host.snapshot(tool) ?: return@withContext WidgetAuthoringResult.Rejected.STALE
        WidgetAuthoring(host) { request, references -> generateWidgetLayout(app, provider, keys, request, references) }
            .prepare(WidgetDraftRequest(instruction), snapshot)
    }

    internal suspend fun saveWidgetLayout(proposal: WidgetSaveProposal): Boolean = withContext(Dispatchers.IO) {
        if (closing || !WidgetLayoutStore(store).save(proposal)) return@withContext false
        if (store.primaryLibrary) ToolWidgets.updateAll(app)
        true
    }
    var effects by mutableStateOf<List<CapabilityEffect>>(emptyList()); private set
    private val selections = CapabilitySelectionState(store.artifactIdentity)
    private val deviceReadHosts = DeviceReadHosts(::inputDeviceActive)
    private val images = com.alpha.assistant.images.ImageGeneration(app)
    private val tiltJobs = SupervisorJob(scope.coroutineContext[Job])
    @Volatile private var tilt: TiltRun? = null
    internal var tiltOwner by mutableStateOf<String?>(null); private set

    private class TiltRun(val host: DeviceReadHost, val source: com.alpha.assistant.device.AccelerometerStream) {
        var job: Job? = null
    }
    private var locationPermissionAnswer: CompletableDeferred<LocationPermissionAnswer>? = null
    internal var capabilityPrompt by mutableStateOf<CapabilityPrompt?>(null); private set
    internal val settlingPhoto: Boolean
        get() = (capabilityPrompt as? CapabilityPrompt.Reading)?.launch?.capability == com.alpha.assistant.capabilities.PHOTO_CAPTURE
    internal var devicePermissionIssue by mutableStateOf<String?>(null); private set
    internal var networkAccessHost by mutableStateOf<MiniAppNetworkAccessHost?>(null); private set
    @Volatile private var networkAuthority: ToolNetworkAuthority? = null
    private val networkSessionOwner = Any()
    private var selectionJob: Job? = null
    var tools by mutableStateOf<List<ToolRecord>>(emptyList()); private set
    var drafts by mutableStateOf<List<ToolDraft>>(emptyList()); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var selectedDraftId by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var errorCode by mutableStateOf<String?>(null); private set // the CloudFailure code of error
    internal var storageUnavailable by mutableStateOf(false); private set
    @Volatile private var closing = false
    private var job: Job? = null
    private data class LocalButton(val tool: ToolRecord, val action: String, val host: Any, val active: () -> Boolean)
    private data class LocalButtonChain(val firstStateVersion: Int, val committed: ToolRecord)
    private val localButtons = ArrayDeque<LocalButton>()
    private val localButtonChains = mutableMapOf<Any, LocalButtonChain>()
    private var localButtonJob: Job? = null
    private var shutdown: Job? = null
    private val session = AtomicReference<CloudSession?>(null)
    private val nativeEdit = AtomicReference<NativeToolEditRequest?>(null)
    private val previews = ToolControllerDraftPreviews(scope) { id -> withContext(Dispatchers.IO) {store.draft(id)} }
    private val snapshotMutex=kotlinx.coroutines.sync.Mutex()

    private val watchStore=scope.launch {
        store.changes.drop(1).collect {
            try { withContext(Dispatchers.IO) { loadSnapshots() } }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { android.util.Log.e("ToolController","Saved mini-apps could not be read.",failure) }
        }
    }
    init { run {
        if(!AutomationRuntime.recoverToolDeletions(store)) {
            throw CloudFailure("tool_delete_cleanup","Мини-приложение удалено. Очистка завершится при повторном открытии.")
        }
        refresh()
    } }
    fun select(id: String?) { selectedId = id; selectedDraftId = null; run { refresh() } }
    fun reload() { run { refresh() } }
    fun selectDraft(id: String) { selectedDraftId = id; selectedId = drafts.find { it.id == id }?.toolId; run { refresh() } }
    fun clearError() { error = null; errorCode = null }
    internal fun previewFor(draftId: String): ToolDraftPreview? {
        val preview=previews.previewFor(draftId) ?: return null
        val draft=drafts.find {it.id==draftId}
        return preview.takeIf {draft==null || it.matchesDraft(draft,allowPending=true)}
    }
    fun generate(prompt: String, toolId: String?, conversationId: String?, images: List<Attachment>) {
        run {
            previews.clear()
            val draft = engine.createDraft(prompt,toolId,conversationId)
            withContext(Dispatchers.Main) { selectedDraftId = draft.id; selectedId = draft.toolId }
            refresh()
            generateDraft(draft.id,images)
        }
    }
    internal suspend fun referencedTools(ids: List<String>): List<ToolRecord> = withContext(Dispatchers.IO) {
        // Open storage even when the chat has no cards, so creation fails before the provider if storage is unavailable.
        store.readableDatabase
        ids.mapNotNull { id -> store.tool(id)?.takeIf { it.saved && !it.archived && ToolSpec.availablePersisted(it) != null } }
    }

    internal val toolCardUpgradeMarker get() = "message-backed-tool-cards:${store.databaseName}"

    internal fun savedCardOrigins() = store.tools().filter { it.saved && it.conversationId != null }

    internal fun importPortable(uri: Uri) = run {
        require(uri.scheme == android.content.ContentResolver.SCHEME_CONTENT) { "Выберите файл через Android." }
        val imported = checkNotNull(app.contentResolver.openInputStream(uri)) { "Не удалось открыть файл." }
            .use { engine.importPortable(it) }
        withContext(Dispatchers.Main) {
            selectedId = imported.id
            selectedDraftId = null
        }
    }

    internal suspend fun exportPortable(tool: ToolRecord, includeState: Boolean): Uri = withContext(Dispatchers.IO) {
        check(!closing)
        val directory = java.io.File(app.cacheDir, "mini-app-exports")
        check(directory.isDirectory || directory.mkdirs())
        val file = java.io.File.createTempFile("mini-app-", ".zip", directory)
        try {
            file.outputStream().use { engine.exportPortable(tool, includeState, it) }
            ensureActive()
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.mini-app-export", file)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    internal suspend fun saveManualDefinition(tool: ToolRecord, definition: String) = withContext(Dispatchers.IO) {
        check(!closing)
        engine.saveManualDefinition(tool, definition)
        if (store.primaryLibrary) AutomationRuntime.refresh(app)
    }

    internal suspend fun prepareNativeEdit(expected: ToolRecord,requestId: String,instruction: String): NativeToolEditPreview =
        coroutineScope {
            require(requestId.isNotBlank() && requestId.length<=128)
            if(closing) throw nativeEditStale()
            val request=NativeToolEditRequest(requestId)
            if(!nativeEdit.compareAndSet(null,request))
                throw CloudFailure("widget_busy","Завершите текущее изменение мини-приложения.")
            try {
                if(closing) throw nativeEditStale()
                val preview=suspendCancellableCoroutine<NativeToolEditPreview> { result ->
                    val worker=launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
                        result.resumeWith(runCatching {
                            val key=keys.cloudKey(app)
                            val proposed=engine.prepareNativeEdit(expected,request,instruction,key)
                            ensureActive()
                            if(keys.cloudKey(app)!=key) throw nativeEditStale()
                            proposed
                        })
                    }
                    result.invokeOnCancellation {
                        request.cancel()
                        nativeEdit.compareAndSet(request,null)
                        worker.cancel()
                    }
                    worker.start()
                }
                if(nativeEdit.get()!==request || request.isClosed) throw CancellationException("The native edit was canceled.")
                preview
            } catch(failure: Throwable) {
                request.cancel()
                nativeEdit.compareAndSet(request,null)
                throw failure
            } finally { request.cloud.cancel() }
        }

    internal suspend fun confirmNativeEdit(requestId: String): ToolRecord {
        val request=nativeEdit.get()?.takeIf { it.id==requestId && !closing } ?: throw nativeEditStale()
        try {
            return withContext(Dispatchers.IO) {
                engine.confirmNativeEdit(request).also { if(store.primaryLibrary) AutomationRuntime.refresh(app) }
            }
        } catch(cancel: CancellationException) {
            request.cancel()
            throw cancel
        } finally {
            if(request.isClosed) nativeEdit.compareAndSet(request,null)
        }
    }

    internal fun cancelNativeEdit(requestId: String?=null) {
        val request=nativeEdit.get()?.takeIf {requestId==null || it.id==requestId} ?: return
        request.cancel()
        nativeEdit.compareAndSet(request,null)
    }

    suspend fun recoverAutomatic(operationId: String, conversationId: String): Pair<String,Int>? = withContext(Dispatchers.IO) {
        val receipt = store.draft(operationId) ?: return@withContext null
        val revision = receipt.acceptedRevision ?: return@withContext null
        val id = receipt.toolId ?: return@withContext null
        if(receipt.conversationId != conversationId) return@withContext null
        val tool = store.tool(id) ?: return@withContext null
        val accepted = store.revisions(id).find { it.revision == revision } ?: return@withContext null
        if(accepted.spec != receipt.candidate || accepted.state != receipt.previewState || accepted.ui != receipt.previewUi) return@withContext null
        id to revision
    }
    /** Shell owns cancellation. The final guarded commit and message update run on Main without a suspension. */
    suspend fun generateAutomatic(prompt: String, target: ToolRecord?, conversationId: String, operationId: String,
        key: String, images: List<String>, call: CloudSession, cancelRequest: () -> Unit,
        prepared: suspend () -> Unit, onPublicUpdate: (String) -> Unit,
        canCommit: () -> Boolean, committed: (ToolRecord) -> Unit,
        originalRequest: String = prompt, sceneAttachments: List<Attachment> = emptyList()) {
        if(busy || closing) throw CloudFailure("widget_busy","Another tool operation is active. Retry after it completes.")
        busy = true; error = null; errorCode = null; automaticCancel = cancelRequest; automaticJob = currentCoroutineContext()[Job]
        previews.clear()
        var accepted = false
        var created = false
        val assetLease = if (sceneAttachments.any { it.mime == ThreeSceneAssets.ATTACHMENT_MIME })
            ThreeSceneAssetCleanup(app).lease() else null
        try {
            prepared()
            withContext(Dispatchers.IO) {
                store.draft(operationId)?.let {
                    check(it.conversationId == conversationId && it.acceptedRevision == null)
                    engine.reject(operationId)
                }
                engine.createDraft(prompt,target?.id,conversationId,operationId,target)
                created = true
            }
            withContext(Dispatchers.IO) {
                generateWithPreview(operationId, key, images, call, originalRequest,
                    ThreeSceneAssets.attachmentRefs(sceneAttachments), onPublicUpdate = { note ->
                        withContext(Dispatchers.Main.immediate) { onPublicUpdate(note) }
                    }, checkOwner = {
                        withContext(Dispatchers.Main.immediate) {
                            if (!canCommit()) throw CancellationException("The mini-app operation is no longer active.")
                        }
                    })
            }
            currentCoroutineContext().ensureActive(); call.check()
            previews.clear(operationId)
            val result = engine.accept(operationId,retainReceipt = true,canCommit = canCommit)
            accepted = true
            committed(result)
            if(target!=null && store.primaryLibrary) AutomationRuntime.refresh(app)
        } finally {
            previews.clear(operationId)
            automaticCancel = null
            try {
                withContext(NonCancellable) {
                    try {
                        withContext(Dispatchers.IO) { if(created && !accepted) engine.reject(operationId) }
                        refresh()
                    } catch (_: Exception) {
                        error = if(accepted) "The widget is saved. Reopen its details to refresh the display."
                            else "The widget request stopped. Existing saved tools are unchanged."
                        errorCode = null
                    }
                }
            } finally {
                assetLease?.close()
                busy = false
                automaticJob = null
            }
        }
    }
    internal suspend fun testAndFix(request: String, target: ToolRecord, conversationId: String, operationId: String,
        key: String, call: CloudSession, cancelRequest: () -> Unit, prepared: suspend () -> Unit, canCommit: () -> Boolean,
        completed: (TestFixResult, ToolRecord?) -> Unit) {
        if (busy || closing) throw CloudFailure("widget_busy", "Another tool operation is active. Retry after it completes.")
        busy = true
        automaticCancel = cancelRequest
        automaticJob = currentCoroutineContext()[Job]
        var saved: ToolRecord? = null
        try {
            prepared()
            val result = withContext(Dispatchers.IO) {
                if (store.tool(target.id) != target || store.isDeleted(target.id))
                    throw CloudFailure("tool_changed", "The mini-app changed. Review the current revision before testing.")
                generationService.testAndFix(key, request, target, store.privateResults(target.id), call)
            }
            call.checkActive(currentCoroutineContext())
            val candidate = result.candidate
            if (candidate == null) {
                completed(result, null)
                return
            }
            try {
                saved = engine.acceptTestFix(operationId, conversationId, target, request, candidate, canCommit)
            } catch (failure: TestFixNotSaved) {
                completed(result.copy(detail = result.detail + " " + failure.message), null)
                return
            }
            completed(result, saved)
            if (store.primaryLibrary) AutomationRuntime.refresh(app)
        } finally {
            automaticCancel = null
            try {
                withContext(NonCancellable) {
                    if (saved != null) refresh()
                }
            } finally {
                busy = false
                automaticJob = null
            }
        }
    }

    private var automaticCancel: (() -> Unit)? = null
    private var automaticJob: Job? = null
    fun retry(id: String) { run {
        previews.clear()
        generateDraft(id,emptyList())
    } }
    private suspend fun generateDraft(id: String, images: List<Attachment>) {
        val call = CloudSession()
        session.set(call)
        try {
            currentCoroutineContext().ensureActive()
            val key = keys.cloudKey(app)
            val data = images.map {
                currentCoroutineContext().ensureActive()
                readImage(it)
            }
            currentCoroutineContext().ensureActive()
            call.check()
            generateWithPreview(id,key,data,call)
        } finally { session.compareAndSet(call,null) }
        refresh()
    }
    internal var generationTraceObserver: ((ToolGenerationTraceEvent) -> Unit)? = null

    private suspend fun generateWithPreview(id: String,key: String,images: List<String>,call: CloudSession,originalRequest: String? = null,
        sceneAssets: List<ThreeSceneAssetRef> = emptyList(), onPublicUpdate: (suspend (String) -> Unit)? = null,
        checkOwner: suspend () -> Unit = {}) {
        check(!closing) {"The tool controller is closed."}
        val requestId=UUID.randomUUID().toString()
        val observer=previews.begin(id,requestId)
        try {
            val trace = generationTraceObserver?.let { ToolGenerationTrace(UUID.fromString(id), it) }
            val completed = engine.generate(id, key, images, call, previewObserver = observer,
                trace = trace, originalRequest = originalRequest, sceneAssets = sceneAssets, onPublicUpdate = onPublicUpdate,
                checkOwner = checkOwner)
            previews.complete(requestId,completed)
        } catch(failure: Throwable) {
            previews.clearRequest(requestId)
            throw failure
        }
    }
    internal class ManualChatSave(val canCommit: () -> Boolean, val finish: suspend (ToolRecord?) -> Unit)

    internal fun accept(id: String, prepareChat: suspend (ToolDraft) -> ManualChatSave? = { null }) {
        val selection = selectedId to selectedDraftId
        run {
            previews.clear(id)
            val draft = checkNotNull(store.draft(id))
            val result = withContext(Dispatchers.Main.immediate) {
                val chat = prepareChat(draft) ?: return@withContext null
                val accepted = try {
                    engine.accept(id,retainReceipt = true,canCommit = chat.canCommit)
                } catch (cancel: CancellationException) {
                    if (!closing) withContext(NonCancellable) { chat.finish(null) }
                    throw cancel
                } catch (failure: Exception) {
                    chat.finish(null)
                    throw failure
                }
                chat.finish(accepted)
                accepted
            } ?: engine.accept(id)
            withContext(Dispatchers.Main) {
                if ((selectedId to selectedDraftId) == selection) {
                    selectedId = result.id
                    selectedDraftId = null
                }
            }
            if(result.revision>1 && store.primaryLibrary) AutomationRuntime.refresh(app)
            refresh()
        }
    }
    fun reject(id: String) {
        val selection = selectedId to selectedDraftId
        run {
            previews.clear(id)
            val owner = drafts.find { it.id == id }?.toolId
            engine.reject(id)
            withContext(Dispatchers.Main) {
                if ((selectedId to selectedDraftId) == selection) {
                    selectedDraftId = null
                    selectedId = owner
                }
            }
            refresh()
        }
    }
    /** Metadata-only recheck for visible cached images, including expiry, removal, and Android URI access. */
    suspend fun nativeImageAvailable(owner: String, handle: String): Boolean = withContext(Dispatchers.IO) {
        try {CapabilityResources(app,store).resolve(owner,handle).mimeType.startsWith("image/")}
        catch(cancel: CancellationException) {throw cancel} catch(_: Exception) {false}
    }
    /** Only validated, owner-scoped image handles enter the native decoder. */
    suspend fun nativeImage(owner: String, handle: String): Bitmap? = decodeNativeImage(owner,handle,ToolUiCatalog.MAX_IMAGE_BYTES)

    private suspend fun decodeNativeImage(owner: String,handle: String,maxBytes: Int): Bitmap? {
        var decoded: Bitmap?=null
        try {
            return withContext(Dispatchers.IO) {
                require(maxBytes in 4..ToolUiCatalog.MAX_IMAGE_BYTES)
                require(handle.matches(Regex("[0-9a-f]{64}")))
                val selected=CapabilityResources(app,store).resolve(owner,handle)
                require(selected.mimeType.startsWith("image/") && selected.sizeBytes<=CapabilityResources.MAX_FILE_BYTES)
                if (selected.kind == com.alpha.assistant.capabilities.MiniAppImage.RESOURCE) {
                    return@withContext com.alpha.assistant.tools.three.ThreeSceneAssets(app).generatedBitmap(
                        com.alpha.assistant.tools.three.ThreeSceneAssetRef(selected.locator, "image", selected.mimeType, selected.sizeBytes))
                        .also { decoded = it }
                }
                val bytes=(if(selected.kind == "cameraPhoto") java.io.File(selected.locator).inputStream()
                    else checkNotNull(app.contentResolver.openInputStream(Uri.parse(selected.locator)))).use { input ->
                    val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val read=input.read(buffer)
                        if(read<0) break
                        require(read>0 && output.size()+read<=CapabilityResources.MAX_FILE_BYTES)
                        output.write(buffer,0,read)
                    }
                    output.toByteArray()
                }
                val options=BitmapFactory.Options().apply {inJustDecodeBounds=true; inSampleSize=1; inPreferredConfig=Bitmap.Config.ARGB_8888; inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                require(options.outWidth > 0 && options.outHeight > 0)
                fun sampledEdge(edge: Int)=(edge.toLong()+options.inSampleSize-1)/options.inSampleSize
                while(sampledEdge(options.outWidth)>ToolUiCatalog.MAX_IMAGE_EDGE || sampledEdge(options.outHeight)>ToolUiCatalog.MAX_IMAGE_EDGE ||
                    sampledEdge(options.outWidth)*sampledEdge(options.outHeight)>maxBytes/4) {
                    require(options.inSampleSize < (1 shl 30))
                    options.inSampleSize*=2
                }
                options.inJustDecodeBounds=false
                val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options))
                decoded=image
                require(image.width <= ToolUiCatalog.MAX_IMAGE_EDGE && image.height <= ToolUiCatalog.MAX_IMAGE_EDGE && image.allocationByteCount <= maxBytes) {"Decoded image exceeds the native memory limit."}
                // Recheck access after provider IO, before the image enters the UI.
                CapabilityResources(app,store).resolve(owner,handle)
                image
            }
        } catch(cancel: CancellationException) {decoded?.recycle();throw cancel}
          catch(_: Exception) {decoded?.recycle();return null}
    }
    private val publishedSequences=mutableMapOf<String,Long>()
    private val liveInputHosts=java.util.concurrent.ConcurrentHashMap.newKeySet<LiveArtifactUiHost>()
    private var waitingDiscreteInput: Job?=null
    private fun inputDeviceActive() = !closing && !app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
        app.getSystemService(android.os.PowerManager::class.java).isInteractive

    internal val threeSceneLifetime = kotlinx.coroutines.sync.Mutex()

    internal suspend fun loadThreeScene(expected: ToolRecord): ToolRecord = withContext(Dispatchers.IO) {
        val current = checkNotNull(store.tool(expected.id))
        check(!current.archived && current.revision == expected.revision) { "This 3D scene revision changed." }
        current
    }

    internal suspend fun saveThreeScene(expected: ToolRecord, snapshot: ThreeSceneSnapshot): ToolRecord =
        withContext(Dispatchers.IO) {
            lateinit var saved: ToolRecord
            store.transaction {
                val old = checkNotNull(store.tool(expected.id))
                check(!old.archived && old.revision == expected.revision &&
                    old.stateVersion == expected.stateVersion) { "This 3D scene revision changed." }
                val spec = ToolSpec.parsePersisted(old.spec)
                check(spec.threeScene)
                spec.validateUi(JSONObject(snapshot.ui), spec.validateState(snapshot.state))
                saved = old.copy(state = snapshot.state, ui = snapshot.ui, stateVersion = old.stateVersion + 1)
                store.commitTool(saved, old, revision = false)
            }
            withContext(Dispatchers.Main) {
                tools = tools.map { if (it.id == saved.id && it.revision == saved.revision &&
                    it.stateVersion <= saved.stateVersion) saved else it }
            }
            saved
        }

    fun artifactHost(t: ToolRecord): ArtifactUiHost=createArtifactHost(t,nonGraphics=false)
    internal fun nonGraphicsLiveInputHost(t: ToolRecord): LiveArtifactUiHost=createArtifactHost(t,nonGraphics=true)

    private fun createArtifactHost(t: ToolRecord,nonGraphics: Boolean): LiveArtifactUiHost=object: LiveArtifactUiHost {
        private val coordinator=ArtifactCoordinator.get(store,t.id)
        private val owner=Any()
        private val inputEpoch=java.util.concurrent.atomic.AtomicLong()
        @Volatile private var generation: Long?=null
        @Volatile private var inputLease: LiveInputLease?=null
        private suspend fun publish(snapshot: ArtifactSnapshot): ArtifactSnapshot {
            withContext(Dispatchers.Main) {
                val next=snapshot.tool
                val current=tools.find {it.id==next.id}
                if(current!=null && !current.archived && current.revision==next.revision &&
                    current.stateVersion<=next.stateVersion && snapshot.sequence>=publishedSequences.getOrDefault(next.id,0)) {
                    publishedSequences[next.id]=snapshot.sequence
                    tools=tools.map {if(it.id==next.id) next else it}
                }
            }
            return snapshot
        }
        override suspend fun open(): ArtifactSnapshot {
            var acquired: Long?=null
            val host=this
            try {
                return withContext(Dispatchers.IO) {
                    check(!closing) {"The artifact owner is closed."}
                    val snapshot=if(nonGraphics) coordinator.ordered {openNonGraphics(store,generationService,t.id,t.revision,owner,::inputDeviceActive)}
                        else engine.openArtifactHost(t.id,t.revision,::inputDeviceActive)
                    acquired=snapshot.sessionGeneration;generation=snapshot.sessionGeneration
                    inputLease=coordinator.ordered {attachLiveInput(store,t.id,t.revision,snapshot.sessionGeneration)}
                    liveInputHosts.add(host)
                    if(!inputDeviceActive()) throw CancellationException("The artifact host is no longer active.")
                    publish(snapshot)
                }
            } catch(failure: Throwable) {
                cancelLiveInput()
                inputLease=null
                liveInputHosts.remove(host)
                withContext(NonCancellable+Dispatchers.IO) {
                    try {acquired?.let {closeGeneration(it)}}
                    catch(cleanup: Exception) {failure.addSuppressed(cleanup)}
                }
                throw failure
            }
        }
        override suspend fun startSession(): ArtifactSnapshot=withContext(Dispatchers.IO) {
            publish(if(nonGraphics) localHost {startHostSession(store,t.id,t.revision,owner,it)}
                else engine.startArtifactHostSession(t.id,t.revision,checkNotNull(generation)))
        }
        override suspend fun snapshot(): ArtifactSnapshot=withContext(Dispatchers.IO) {
            publish(if(nonGraphics) localHost {refreshHostSnapshot(store,owner,it)}
                else engine.artifactSnapshot(t.id,t.revision,checkNotNull(generation)))
        }
        override suspend fun flush(): ArtifactSnapshot=withContext(Dispatchers.IO) {
            publish(if(nonGraphics) localHost {
                if(hostSnapshot(store,owner,it).frameOwner) flush(store)
                hostSnapshot(store,owner,it)
            } else engine.flushArtifact(t.id,t.revision,checkNotNull(generation)))
        }
        override suspend fun event(event: org.json.JSONObject): ArtifactSnapshot=withContext(Dispatchers.IO) {
            publish(if(nonGraphics) localHost {hostEvent(store,t.id,t.revision,owner,it,event)}
                else engine.artifactEvent(t.id,t.revision,event,checkNotNull(generation)))
        }

        private suspend fun localHost(block: suspend ArtifactCoordinator.(Long)->ArtifactSnapshot): ArtifactSnapshot = coordinator.ordered {
            ensureAvailable();current(store,t.id,t.revision);block(checkNotNull(generation))
        }

        private suspend fun closeGeneration(generation: Long) {
            if(nonGraphics) coordinator.ordered {closeOwned(store,t.revision,owner,generation)}
            else engine.closeArtifact(t.id,t.revision,generation)
        }

        override suspend fun close() {
            cancelLiveInput()
            inputLease=null
            liveInputHosts.remove(this)
            withContext(Dispatchers.IO) {generation?.let {closeGeneration(it)}}
        }

        override suspend fun offerLiveInput(expected: ArtifactSnapshot,actionId: String,value: Any?,isActive: ()->Boolean) =
            submitLiveInput(expected,actionId,value,finished=false,isActive)

        override suspend fun finishLiveInput(expected: ArtifactSnapshot,actionId: String,value: Any?,isActive: ()->Boolean) =
            submitLiveInput(expected,actionId,value,finished=true,isActive)

        private suspend fun submitLiveInput(expected: ArtifactSnapshot,actionId: String,value: Any?,finished: Boolean,
            isActive: ()->Boolean): LiveInputSubmission {
            val lease=inputLease ?: return LiveInputSubmission.Rejected(LiveInputRejection.Inactive)
            val epoch=inputEpoch.get()
            return withContext(Dispatchers.IO) {
                coordinator.offerLiveInput(store,lease,expected,actionId,value,finished) {
                    inputLease===lease && inputEpoch.get()==epoch && inputDeviceActive() && isActive()
                }
            }
        }

        override fun liveInputFrame(frameTimeNanos: Long) {
            if(inputLease==null) return
            if(inputDeviceActive()) coordinator.liveInputFrame(store,t.id,frameTimeNanos)
            else cancelLiveInput()
        }

        override fun cancelLiveInput() {
            inputEpoch.incrementAndGet()
            inputLease?.let(coordinator::cancelLiveInput)
        }

        private fun drawingSessionActive(expectedGeneration: Long): Boolean =
            generation==expectedGeneration && inputDeviceActive() && coordinator.drawingHostCurrent(t.id,t.revision,expectedGeneration) &&
                store.tool(t.id)?.let {!it.archived && it.revision==t.revision}==true

        override suspend fun drawingImageAvailable(sessionGeneration: Long,handle: String): Boolean = withContext(Dispatchers.IO) {
            try {drawingSessionActive(sessionGeneration) && nativeImageAvailable(t.id,handle) && drawingSessionActive(sessionGeneration)}
            catch(cancel: CancellationException) {throw cancel} catch(_: Exception) {false}
        }

        override suspend fun loadDrawingImage(sessionGeneration: Long,handle: String,maxBytes: Int): Bitmap? {
            if(!drawingImageAvailable(sessionGeneration,handle)) return null
            val image=decodeNativeImage(t.id,handle,maxBytes) ?: return null
            try {
                if(drawingImageAvailable(sessionGeneration,handle)) return image
            } catch(cancel: CancellationException) {image.recycle();throw cancel}
            image.recycle()
            return null
        }
    }

    internal suspend fun applyLiveInput(expected: ToolRecord,actionId: String,value: Any?,isActive: ()->Boolean): LiveInputResult =
        withContext(Dispatchers.IO) {
            val coordinator=ArtifactCoordinator.get(store,expected.id)
            coordinator.pendingFinalLiveInput().forEach {it.await()}
            coordinator.applyLiveInput(store,generationService,expected,actionId,value) {inputDeviceActive() && isActive()}
        }
    fun interact(t: ToolRecord, action: String, value: Any?, foreground: ()->Boolean = { false }, launch: ((android.content.Intent)->Unit)? = null) {
        interactNative(t,action,value,foreground,launch,null,null)
    }
    internal fun interact(t: ToolRecord, action: String, value: Any?, host: DeviceReadHost?, foreground: ()->Boolean, launch: (android.content.Intent)->Unit) {
        interactNative(t,action,value,foreground,launch,null,host)
    }

    internal fun interactFromDesktop(tool: ToolRecord, action: String, value: Any?, active: () -> Boolean) {
        if (!active() || storageUnavailable) return
        afterLiveFinal(tool.id) {
            check(projectWidget(tool).nodes.firstNotNullOfOrNull { it.widgetActionId() } == action)
            engine.interact(tool.id, tool.revision, tool.stateVersion, action, value, allowDeviceEffects = false,
                onWidgetEvaluated = {
                    if (!withContext(Dispatchers.Main.immediate) { active() }) throw CancellationException("The desktop closed.")
                    check(store.tool(tool.id)?.let { it.createdAt == tool.createdAt && it.saved && it.onDesktop && !it.archived } == true) {
                        app.getString(R.string.tool_missing)
                    }
                })
        }
    }

    internal fun queueLocalButton(tool: ToolRecord, action: String, host: Any, active: () -> Boolean) {
        if (closing || busy || !active()) return
        if (localButtons.size >= ArtifactProtocol.QUEUE_LIMIT) {
            error = app.getString(R.string.tool_local_input_full)
            return
        }
        localButtons.addLast(LocalButton(tool, action, host, active))
        if (localButtonJob?.isActive == true) return
        clearError()
        localButtonJob = scope.launch {
            var changed = false
            try {
                while (localButtons.isNotEmpty()) {
                    val button = localButtons.removeFirst()
                    if (!button.active()) continue
                    val base = button.tool
                    val (first, previous) = localButtonChains[button.host]
                        ?.takeIf { base.stateVersion <= it.committed.stateVersion }
                        ?: LocalButtonChain(base.stateVersion, base)
                    check(base.stateVersion in first..previous.stateVersion && base.id == previous.id &&
                        base.revision == previous.revision && base.createdAt == previous.createdAt)
                    val committed = withContext(Dispatchers.IO) {
                        ArtifactCoordinator.get(store, base.id).pendingFinalLiveInput().forEach { it.await() }
                        engine.interactLocalButton(previous, button.action, button.active)
                    }
                    if (button.active()) localButtonChains[button.host] = LocalButtonChain(first, committed)
                    changed = true
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = app.getString(R.string.tool_local_input_failed)
                errorCode = (failure as? CloudFailure)?.code
            } finally {
                localButtons.clear()
                if (changed && store.primaryLibrary && !closing) scope.launch(Dispatchers.IO) { ToolWidgets.updateAll(app) }
            }
        }
    }
    internal fun releaseLocalButtonHost(host: Any) {
        localButtonChains.remove(host)
        localButtons.removeAll { it.host === host }
    }
    internal fun updateDeviceReadHost(host: DeviceReadHost, resumed: Boolean?) {
        deviceReadHosts.update(host, resumed)
        if (resumed != true) finishDeviceInputs(host)
    }

    internal fun updateDeviceReadFocus(host: DeviceReadHost, focused: Boolean) {
        deviceReadHosts.updateFocus(host, focused)
        if (!focused && tilt?.host === host) stopTilt()
    }

    internal fun stopDeviceReadHost(host: DeviceReadHost) {
        deviceReadHosts.stop(host)
        finishDeviceInputs(host)
    }

    private fun finishDeviceInputs(host: DeviceReadHost) {
        if (tilt?.host === host) stopTilt()
        (capabilityPrompt as? CapabilityPrompt.Camera)?.takeIf { it.origin === host }?.capture?.finish("cancelled")
    }

    internal fun stopTilt() {
        val current = tilt ?: return
        tilt = null
        tiltOwner = null
        current.source.close()
        current.job?.cancel()
    }

    private fun setTilt(host: DeviceReadHost, enabled: Boolean): CapabilityResult {
        if (closing || !deviceReadHosts.initiating(host, requireFocus = true))
            return CapabilityResult("cancelled", "Open and focus the mini-app to use tilt input.")
        if (!enabled) {
            if (tilt?.host === host) stopTilt()
            return CapabilityResult("succeeded", "Tilt input stopped.", org.json.JSONObject().put("active", false))
        }
        if (tilt?.host !== host) {
            stopTilt()
            val source = com.alpha.assistant.device.AccelerometerStream(app)
            if (!source.start()) {
                source.close()
                return CapabilityResult("unavailable", "The accelerometer is unavailable.")
            }
            val running = TiltRun(host, source)
            tilt = running
            tiltOwner = host.owner
            val coordinator = ArtifactCoordinator.get(store, host.owner)
            val active = { tilt === running && !closing && deviceReadHosts.initiating(host, requireFocus = true) }
            running.job = scope.launch(Dispatchers.IO + tiltJobs) {
                try {
                    for (sample in source.samples) {
                        if (!active()) break
                        coordinator.ordered {
                            val record = current(store, host.owner, host.revision)
                            val spec = ToolSpec.parsePersisted(record.spec)
                            if (!active() || record.archived || TiltContract.ID !in spec.capabilities || spec.surfaces.isEmpty())
                                throw CancellationException("The tilt host changed.")
                            event(store, generationService, host.owner, host.revision, sample, active)
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    android.util.Log.w("ArtifactRuntime", "Tilt session ended during evaluation", failure)
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (tilt === running) stopTilt()
                    }
                }
            }
        }
        return CapabilityResult("succeeded", "Tilt input started.", org.json.JSONObject().put("active", true))
    }

    internal fun nativeVideoHost(host: DeviceReadHost, displayed: () -> ToolRecord): ToolVideoHost {
        val effects=ToolVideoEffects(app,store,engine)
        return ToolVideoHost(videoScope,displayed,{deviceReadHosts.initiating(host)},execute=effects::execute,canStart={!busy && !closing})
    }
    internal fun attachVideoHost(host: ToolVideoHost) { if(closing) host.close() else videoHosts.add(host) }
    internal fun detachVideoHost(host: ToolVideoHost) { host.close();videoHosts.remove(host) }

    internal fun canLaunchPicker(prompt: CapabilityPrompt.Picker) = capabilityPrompt == prompt &&
        (prompt.origin?.let(deviceReadHosts::initiating) ?: !closing)

    internal fun canLaunchLocationPermission(request: CapabilityPrompt.LocationPermission) =
        capabilityPrompt == request && deviceReadHosts.initiating(request.host)

    internal fun receiveLocationPermission(request: CapabilityPrompt.LocationPermission, answer: LocationPermissionAnswer) {
        if (capabilityPrompt == request) locationPermissionAnswer?.complete(answer)
    }

    internal fun cancelLocationPermission(request: CapabilityPrompt.LocationPermission) {
        if (capabilityPrompt == request) locationPermissionAnswer?.cancel()
    }

    private suspend fun requestLocationPermission(host: DeviceReadHost, effect: CapabilityEffect): LocationPermissionAnswer =
        withContext(Dispatchers.Main.immediate) {
            if (capabilityPrompt != null || !deviceReadHosts.initiating(host)) throw CancellationException()
            val prompt = CapabilityPrompt.LocationPermission(UUID.randomUUID().toString(), effect.key, host)
            val answer = CompletableDeferred<LocationPermissionAnswer>()
            locationPermissionAnswer = answer
            capabilityPrompt = prompt
            try { answer.await() }
            finally {
                if (capabilityPrompt == prompt) capabilityPrompt = null
                if (locationPermissionAnswer == answer) locationPermissionAnswer = null
            }
        }

    internal fun calendarUiHost(t: ToolRecord,foreground: ()->Boolean,launch: (android.content.Intent)->Unit)=object: OwnedCalendarUiHost {
        override suspend fun snapshot(): OwnedCalendarSnapshot?=withContext(Dispatchers.IO) {runCatching {OwnedCalendarLedger(store).snapshot(t)}.getOrNull()}
        override fun deleteAll(action: String,snapshot: OwnedCalendarSnapshot) {interactNative(t,action,null,foreground,launch,snapshot,null)}
    }
    private fun interactNative(t: ToolRecord,action: String,value: Any?,foreground: ()->Boolean,launch: ((android.content.Intent)->Unit)?,calendarSnapshot: OwnedCalendarSnapshot?,deviceHost: DeviceReadHost?) = afterLiveFinal(t.id) {
        // Only dedicated controls need a frozen effect selection. The engine validates ordinary actions against its current view.
        val type=ToolUiCatalog.action(org.json.JSONObject(t.ui),action)?.optString("type")
        val selected=if(type=="navigation") NavigationInteraction.from(t,action,value) else null
        val calendarSelection=if(type=="calendar") OwnedCalendarInteraction.from(t,action,value) else null
        val medicineSelection=if(type=="medicine") MedicineInteraction.from(t,action,value) else null
        if(calendarSnapshot!=null)check(OwnedCalendarLedger(store).snapshotCurrent(checkNotNull(store.tool(t.id)),calendarSnapshot)) {
            "The displayed owned-event snapshot changed. Review the current native control."
        }
        val foregroundRequest = deviceHost != null && deviceReadHosts.initiating(deviceHost) &&
            selected == null && medicineSelection == null && calendarSelection == null
        val prepared=engine.interactForeground(t.id,t.revision,t.stateVersion,action,value,
            acceptNetwork=foregroundRequest, acceptAi=foregroundRequest && deviceHost?.windowFocused == true)
        val changed=prepared.tool
        if(prepared.network!=null) {
            val offer = SourceOffer.from(changed, prepared.network.id, prepared.network.args)
            if (offer != null && changed.saved && sources.status(changed.id)?.offer != offer) {
                withContext(Dispatchers.Main.immediate) { sourceOffer = offer }
            }
            ToolNetworkEffects(store,engine,networkAuthority).execute(prepared,deviceHost,deviceReadHosts,networkSessionOwner)
            refresh(deliverResults=false)
            return@afterLiveFinal
        }
        val batch=store.effects(t.id).filter {it.revision==changed.revision && it.stateVersion==changed.stateVersion && it.phase=="pending"}
        val foregroundEffects=batch.any {it.request.capability in ForegroundCapabilityEffects.deliveryIds}
        var deferredSelection=false
        try {
            for(effect in batch) {
                val capability=effect.request.capability
                var locationSettingsRequired=true
                val allowed = when(capability) {
                    in NavigationContract.ids -> batch.size==1 && selected?.matches(effect)==true
                    in MedicineContract.ids -> batch.size==1 && medicineSelection?.matches(effect)==true
                    in OwnedCalendarContract.ids -> batch.size==1 && calendarSelection?.matches(effect)==true &&
                        (capability!="calendar.deleteAllOwned" || calendarSnapshot!=null)
                    else -> selected==null && medicineSelection==null && calendarSelection==null
                }
                val result = when {
                    !allowed -> store.rejectPendingEffect(effect.key,CapabilityResult("denied","Системное действие не совпадает с выбранным элементом управления."))
                    launch==null -> broker.cancel(effect.key)
                    capability == TiltContract.ID -> {
                        if (deviceHost == null) broker.cancel(effect.key)
                        else broker.executeOwned(effect.key) { claimed ->
                            withContext(Dispatchers.Main.immediate) {
                                if (!store.effectTargetValid(claimed) || !foreground()) CapabilityResult("cancelled", "The tilt host changed.")
                                else setTilt(deviceHost, claimed.request.args.getBoolean("enabled"))
                            }
                        }
                    }
                    capability == com.alpha.assistant.capabilities.MiniAppImage.ID -> {
                        val selection = images.settings.selection()
                        if (selection == null) store.rejectPendingEffect(effect.key,
                            CapabilityResult("denied", app.getString(R.string.image_disabled)))
                        else {
                            deferredSelection = withContext(Dispatchers.Main.immediate) {
                                if (closing || !foreground() || capabilityPrompt != null || deviceHost == null ||
                                    !deviceReadHosts.initiating(deviceHost, requireFocus = true)) false
                                else {
                                    capabilityPrompt = CapabilityPrompt.Image(effect.key,
                                        com.alpha.assistant.capabilities.MiniAppImage.intent(effect), deviceHost, selection)
                                    true
                                }
                            }
                            if (deferredSelection) null else broker.cancel(effect.key)
                        }
                    }
                    capability == MiniAppAi.ID -> {
                        if (deviceHost == null) broker.cancel(effect.key)
                        else broker.executeAi(effect.key, deviceHost, deviceReadHosts) { prompt, active ->
                            MiniAppAi.ask(app, provider, keys, prompt, active)
                        }
                    }
                    capability in AudioContract.playbackIds -> {
                        if(deviceHost==null) broker.cancel(effect.key) else broker.executeAudio(effect.key,deviceHost,deviceReadHosts)
                    }
                    (capability==AudioContract.PICK || capability in setOf(VideoContract.PICK, VideoContract.CAPTURE)) &&
                        (deviceHost==null || !deviceReadHosts.initiating(deviceHost)) -> broker.cancel(effect.key)
                    capability in DeviceReadContract.ids -> {
                        if(deviceHost==null) broker.cancel(effect.key)
                        else broker.executeDeviceRead(effect.key,deviceHost,deviceReadHosts,{ claimed ->
                            val answer=requestLocationPermission(deviceHost,claimed)
                            locationSettingsRequired=answer.settingsRequired
                            answer.granted
                        })
                    }
                    capability == "share.text" -> {
                        val opened = withContext(Dispatchers.Main.immediate) {
                            if (!closing && foreground() && capabilityPrompt == null && deviceHost != null &&
                                deviceReadHosts.initiating(deviceHost)) {
                                capabilityPrompt = CapabilityPrompt.Share(effect.key, effect.request.args.getString("text"), deviceHost, launch)
                                true
                            } else false
                        }
                        if (opened) null else broker.cancel(effect.key)
                    }
                    capability == com.alpha.assistant.capabilities.PHOTO_CAPTURE -> {
                        if (deviceHost == null) broker.cancel(effect.key)
                        else withContext(Dispatchers.Main.immediate) {
                            val receipt = beginPhotoCapture(effect.key, deviceHost, foreground)
                            deferredSelection = deferredSelection || receipt.status == "awaitingUser"
                            receipt
                        }
                    }
                    capability=="calendar.select" -> {
                        val opened=withContext(Dispatchers.Main.immediate) {
                            if (!closing && foreground() && capabilityPrompt==null) {
                                capabilityPrompt=CapabilityPrompt.Calendar(effect.key)
                                true
                            } else false
                        }
                        if (opened) null else broker.cancel(effect.key)
                    }
                    capability=="calendar.deleteAllOwned" -> broker.executeCalendarDeleteAll(effect.key,checkNotNull(calendarSnapshot),foreground)
                    CapabilityCatalog.definition(capability).selection && withContext(Dispatchers.Main.immediate) {capabilityPrompt!=null} ->
                        store.rejectPendingEffect(effect.key,CapabilityResult("unavailable","Завершите открытый выбор в Android и повторите действие."))
                    else -> broker.executeNative(effect.key,foreground) { claimed,intent ->
                        check(!closing && foreground())
                        if(CapabilityCatalog.definition(claimed.request.capability).selection) {
                            check(capabilityPrompt==null)
                            val picker=checkNotNull(selections.begin(claimed.key,claimed.request.capability))
                            val origin=deviceHost.takeIf {foregroundEffects}
                            try {
                                val selectionIntent = if (capability == VideoContract.CAPTURE)
                                    CapabilityResources(app, store).prepareVideoCapture(picker, claimed.owner, intent) else intent
                                capabilityPrompt = CapabilityPrompt.Picker(picker, selectionIntent, origin)
                            } catch (failure: Exception) {
                                selections.consume(picker.token)
                                if (capability == VideoContract.CAPTURE) CapabilityResources(app, store).discardVideoCapture(picker)
                                throw failure
                            }
                            deferredSelection=origin!=null
                        } else launch(intent)
                    }
                }
                if(result?.status=="awaitingUser" && !result.data.optBoolean("launched")) broker.cancel(effect.key)
                if(result?.status=="denied" && !store.permissionGranted(capability) && locationSettingsRequired) showDevicePermissionIssue(result.message)
            }
            if(foregroundEffects && !deferredSelection && deviceHost!=null) {
                val requireFocus = batch.any { it.request.capability in setOf(MiniAppAi.ID, com.alpha.assistant.capabilities.MiniAppImage.ID) }
                deviceReadHosts.whileVisible(deviceHost, requireFocus) { active -> engine.deliverForegroundEffectResults(changed,active) }
            }
        } catch(failure: Exception) {
            val calendar=(capabilityPrompt as? CapabilityPrompt.Calendar)?.effectKey
            runCatching {
                batch.filter {it.key!=calendar && store.effect(it.key)?.phase=="pending"}.forEach {broker.cancel(it.key)}
            }
            throw failure
        } finally {
            if(foregroundEffects && !deferredSelection) store.rejectForegroundEffectDelivery(changed.id,changed.revision,changed.stateVersion)
        }
        refresh(deliverResults=!foregroundEffects)
    }

    private fun afterLiveFinal(id: String,work: suspend ()->Unit) {
        if(busy || closing || settlingPhoto || waitingDiscreteInput?.isActive==true) return
        val finalInputs=ArtifactCoordinator.get(store,id).pendingFinalLiveInput()
        if(finalInputs.isEmpty()) run(work)
        else waitingDiscreteInput=scope.launch {
            try {finalInputs.forEach {it.await()}}
            finally {waitingDiscreteInput=null}
            if (!settlingPhoto) run(work)
        }
    }
    internal suspend fun writableCalendars(): List<NativeOwnedCalendar> = withContext(Dispatchers.IO) {OwnedCalendarCapabilities(app,store).writableCalendars()}
    internal fun finishImage(prompt: CapabilityPrompt.Image, confirmed: Boolean) {
        if (capabilityPrompt !== prompt || busy || closing) return
        capabilityPrompt = null
        run {
            val effect = store.effect(prompt.effectKey) ?: return@run
            val tool = store.tool(effect.owner)
            val host = prompt.origin
            try {
                // The confirmation dialog temporarily owns window focus.
                val refocused = kotlinx.coroutines.withTimeoutOrNull(1000L) {
                    while (deviceReadHosts.initiating(host) && !deviceReadHosts.initiating(host, requireFocus = true))
                        kotlinx.coroutines.delay(20L)
                    deviceReadHosts.initiating(host, requireFocus = true)
                } == true
                if (confirmed && refocused) broker.executeImage(prompt, deviceReadHosts, images)
                else broker.cancel(prompt.effectKey)
                if (tool != null) deviceReadHosts.whileVisible(host, requireFocus = true, requireOrigin = true) { active ->
                    engine.deliverForegroundEffectResults(tool, active)
                }
            } finally {
                store.rejectForegroundEffectDelivery(effect.owner, effect.revision, effect.stateVersion)
                store.releaseRejectedResources(effect.owner)
            }
            refresh(deliverResults = false)
        }
    }

    internal fun finishShare(prompt: CapabilityPrompt.Share, confirmed: Boolean) {
        if (capabilityPrompt !== prompt || busy || closing) return
        capabilityPrompt = null
        run {
            if (confirmed) {
                val result = broker.executeNative(prompt.effectKey,
                    { !closing && deviceReadHosts.initiating(prompt.origin) }) { _, intent -> prompt.launch(intent) }
                if (result.status == "awaitingUser") broker.cancel(prompt.effectKey)
            } else broker.cancel(prompt.effectKey)
            refresh()
        }
    }
    internal fun selectCalendar(key: String,calendar: NativeOwnedCalendar,foreground: ()->Boolean) {
        if(capabilityPrompt!=CapabilityPrompt.Calendar(key) || busy || closing) return
        capabilityPrompt=null
        run {
            val result=broker.executeOwned(key) {effect -> withContext(Dispatchers.Main.immediate) {
                OwnedCalendarCapabilities(app,store).select(effect,calendar,foreground)
            }}
            if(result.status=="awaitingUser") broker.cancel(key)
            if(result.status=="denied" && !store.permissionGranted("calendar.select")) showDevicePermissionIssue(result.message)
            refresh()
        }
    }
    internal fun cancelCalendar(key: String, denied: Boolean = false) {
        if(capabilityPrompt!=CapabilityPrompt.Calendar(key) || closing) return
        if(selectionJob?.isActive==true) return
        selectionJob=scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if(denied) {
                        val result=CapabilityResult("denied","Android не разрешил доступ к календарю. Разрешите чтение и запись в настройках Android и повторите выбор.")
                        store.rejectPendingEffect(key,result)
                        showDevicePermissionIssue(result.message)
                    } else broker.cancel(key)
                    refresh()
                }
            } finally {
                if(capabilityPrompt==CapabilityPrompt.Calendar(key)) capabilityPrompt=null
            }
        }
    }
    internal suspend fun remindersNeedReactivation(owner: String): Boolean = withContext(Dispatchers.IO) {
        MedicineStore(app,MedicineRuntime.database(store)).use { medicine ->
            medicine.reminders().any {
                it.getString("owner")==owner && (it.getString("status") in setOf("reactivationRequired","grantRevoked") ||
                    (it.getString("status")=="scheduled" && !medicine.activated(it)))
            }
        }
    }
    internal suspend fun revisionHistory(toolId: String): List<ToolRevision> =
        withContext(Dispatchers.IO) { store.revisions(toolId) }
    internal suspend fun wasDeleted(toolId: String): Boolean =
        withContext(Dispatchers.IO) { store.isDeleted(toolId) }
    suspend fun restoreAvailability(tool: ToolRecord,revision: Int): ToolRestoreAvailability =
        withContext(Dispatchers.IO) {engine.restoreAvailability(tool,revision)}
    fun rollback(t: ToolRecord, revision: Int) { run {
        engine.rollback(t.id,t.revision,t.stateVersion,revision)
        if(store.primaryLibrary) AutomationRuntime.refresh(app)
        refresh()
    } }
    fun setDesktopPlacement(tool: ToolRecord, placed: Boolean) = run {
        engine.setDesktopPlacement(tool, placed)
        loadSnapshots()
        withContext(Dispatchers.Main) {
            val message = if (placed) R.string.tool_desktop_added else R.string.tool_desktop_removed
            android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    suspend fun recordLastConversation(tool: ToolRecord,conversationId: String): ToolRecord =
        withContext(Dispatchers.IO) {engine.recordLastConversation(tool,conversationId)}
    suspend fun prepareDeletion(tool: ToolRecord): ToolDeletionSnapshot = withContext(Dispatchers.IO) {
        AutomationStore(app,automationDatabase).use { AutomationRuntime.deletionSnapshot(store,it,tool) }
    }
    fun delete(snapshot: ToolDeletionSnapshot) { run {
        val complete=AutomationStore(app,automationDatabase).use { AutomationRuntime.deleteTool(store,it,snapshot) }
        withContext(Dispatchers.Main) {
            if(selectedId==snapshot.tool.id) {selectedId=null;selectedDraftId=null}
        }
        if(store.primaryLibrary) AutomationRuntime.refresh(app)
        refresh()
        if(!complete) throw CloudFailure("tool_delete_cleanup","Мини-приложение удалено. Очистка завершится при повторном открытии.")
    } }
    fun archive(tool: ToolRecord) { run {
        com.alpha.assistant.automations.AutomationStore(app,automationDatabase).use {
            com.alpha.assistant.automations.AutomationRuntime.archiveTool(store,it,tool)
        }
        if(store.primaryLibrary) {
            val shortcut=com.alpha.assistant.surfaces.ToolWidgets.shortcutId(tool.id,store.library.generation)
            app.getSystemService(ShortcutManager::class.java).disableShortcuts(listOf(shortcut),"Инструмент в архиве. Восстанови его в Alpha.")
            com.alpha.assistant.automations.AutomationRuntime.refresh(app)
        }
        refresh()
    } }
    fun recover(tool: ToolRecord) { run {
        ArtifactCoordinator.get(store,tool.id).ordered {store.setArchived(tool,false)}
        if(store.primaryLibrary) app.getSystemService(ShortcutManager::class.java)
            .enableShortcuts(listOf(com.alpha.assistant.surfaces.ToolWidgets.shortcutId(tool.id,store.library.generation)))
        refresh()
    } }
    fun save(id: String) { run { engine.save(id); refresh() } }
    private suspend fun showDevicePermissionIssue(message: String) = withContext(Dispatchers.Main.immediate) {
        if(!closing) devicePermissionIssue=message
    }
    internal fun dismissDevicePermissionIssue() {devicePermissionIssue=null}

    internal fun receivePickerResult(launch: CapabilitySelectionLaunch, returned: ActivityResult?) {
        if(closing || !selections.owns(launch) || selections.consume(launch.token)!=launch) return
        val origin=(capabilityPrompt as? CapabilityPrompt.Picker)?.takeIf {it.launch==launch}?.origin
        val reading=CapabilityPrompt.Reading(launch,origin)
        capabilityPrompt=reading
        selectionJob=scope.launch {
            job?.join()
            automaticJob?.join()
            try {
                val available=withContext(Dispatchers.IO) {
                    store.effect(launch.effectKey)?.let { it.phase=="waiting" && it.request.capability==launch.capability &&
                        store.effectTargetValid(it) && store.permissionGranted(it.request.capability) }==true
                }
                val result=when {
                    !available -> CapabilityResult("unavailable","Выбранные данные недоступны.")
                    returned==null -> CapabilityResult("unavailable","Android не открыл окно выбора.")
                    launch.capability == VideoContract.CAPTURE && returned.resultCode == Activity.RESULT_OK ->
                        withContext(Dispatchers.IO) { CapabilityResources(app, store).videoCaptureResult(launch) }
                    else -> AndroidCapabilities.selectionResult(returned.resultCode,returned.data)
                }
                finishPickerResult(launch,origin) { result }
            } catch(cancel: CancellationException) {
                throw cancel
            } catch(_: ToolLibraryUnavailable) {
                // The library that issued this launch is no longer selected.
            } finally {
                if(capabilityPrompt===reading) capabilityPrompt=null
            }
        }
    }
    private suspend fun beginPhotoCapture(key: String, host: DeviceReadHost, foreground: () -> Boolean): CapabilityResult {
        fun active() = !closing && foreground() && deviceReadHosts.initiating(host)
        var prompt: CapabilityPrompt.Camera? = null
        val result = broker.executeOwned(key) { claimed ->
            val lens = com.alpha.assistant.capabilities.PhotoLens.requested(claimed.request.args)
            if (com.alpha.assistant.capabilities.PhotoLens.present(app)?.contains(lens) == false)
                return@executeOwned CapabilityResult("unavailable", app.getString(R.string.camera_unavailable))
            if (!active() || capabilityPrompt != null || claimed.owner != host.owner || claimed.revision != host.revision)
                return@executeOwned CapabilityResult("cancelled", app.getString(R.string.camera_cancelled))
            if (!store.permissionGranted(claimed.request.capability))
                return@executeOwned CapabilityResult("denied", app.getString(R.string.capability_permission_required))
            val launch = selections.begin(key, claimed.request.capability)
                ?: return@executeOwned CapabilityResult("unavailable", app.getString(R.string.camera_unavailable))
            val capture = com.alpha.assistant.capabilities.PhotoCapture(lens, {
                active() && selections.active == launch && store.effectTargetValid(claimed)
            }) { status, photo -> receivePhotoCapture(launch, status, photo) }
            prompt = CapabilityPrompt.Camera(launch, capture, host)
            CapabilityResult("awaitingUser", app.getString(R.string.tool_camera_review_title), JSONObject().put("launched", true))
        }
        prompt?.let { ready ->
            if (result.status == "awaitingUser") capabilityPrompt = ready
            else selections.consume(ready.launch.token)
        }
        return result
    }

    private fun receivePhotoCapture(launch: CapabilitySelectionLaunch, status: String, bitmap: Bitmap?) {
        val review = (capabilityPrompt as? CapabilityPrompt.Camera)?.takeIf { it.launch == launch } ?: return
        if (closing || selections.consume(launch.token) != launch) return
        val reading = CapabilityPrompt.Reading(launch, review.origin)
        capabilityPrompt = reading
        selectionJob = scope.launch {
            job?.join()
            automaticJob?.join()
            try {
                if (!deviceReadHosts.initiating(review.origin)) {
                    withContext(Dispatchers.IO) { abandonPicker(launch, review.origin) }
                    return@launch
                }
                finishPickerResult(launch, review.origin) { manager ->
                    if (bitmap != null && status == "succeeded") manager.saveCameraPreview(launch.effectKey, bitmap)
                    else CapabilityResult(status, app.getString(when (status) {
                        "denied" -> R.string.capability_permission_required
                        "unavailable", "failed" -> R.string.camera_unavailable
                        else -> R.string.camera_cancelled
                    }))
                }
            } catch (_: ToolLibraryUnavailable) {
                // The library that issued this capture is no longer selected.
            } finally {
                if (capabilityPrompt === reading) capabilityPrompt = null
            }
        }
    }

    private suspend fun finishPickerResult(launch: CapabilitySelectionLaunch, origin: DeviceReadHost?,
        result: (CapabilityResources) -> CapabilityResult) {
        val manager=CapabilityResources(app,store)
        if(origin==null) {
            withContext(Dispatchers.IO) {
                val callback=currentCoroutineContext()
                manager.finishSelection(launch.effectKey,result(manager)) {!closing && callback.isActive}
                refresh()
            }
            return
        }
        try {
            if(deviceReadHosts.awaitPickerReturn(origin)) deviceReadHosts.whileVisible(origin) { active ->
                withContext(Dispatchers.IO) {
                    val effect=store.effect(launch.effectKey) ?: return@withContext
                    if(!active() || effect.phase!="waiting" || !store.effectTargetValid(effect)) return@withContext
                    val callback=currentCoroutineContext()
                    manager.finishSelection(launch.effectKey,result(manager)) {!closing && callback.isActive && active()}
                    val current=store.tool(effect.owner)?.takeIf {it.revision==effect.revision && it.stateVersion==effect.stateVersion}
                    if(current!=null) engine.deliverForegroundEffectResults(current,active)
                }
            }
        } finally {
            withContext(NonCancellable+Dispatchers.IO) {abandonPicker(launch,origin)}
        }
        withContext(Dispatchers.IO) {refresh(deliverResults=false)}
    }
    fun cancel() {
        automaticCancel?.invoke()
        session.getAndSet(null)?.cancel()
        cancelNativeEdit()
        waitingDiscreteInput?.cancel()
        localButtonJob?.cancel()
        job?.cancel()
        previews.clear()
    }
    fun close() {closeJob()}
    internal suspend fun closeAndJoin() {closeJob().join()}

    @Synchronized private fun closeJob(): Job {
        shutdown?.let {return it}
        closing=true
        networkAuthority?.closeSessions(networkSessionOwner)
        val mountedVideos=videoHosts.toList()
        videoHosts.clear()
        val closingOnMain=Looper.myLooper()==Looper.getMainLooper()
        if(closingOnMain) mountedVideos.forEach(ToolVideoHost::close)
        videoJobs.cancel()
        tiltJobs.cancel()
        if (closingOnMain) stopTilt()
        val selection=selectionJob
        val abandoned=capabilityPrompt
        selection?.cancel()
        locationPermissionAnswer?.cancel()
        capabilityPrompt=null
        val hosts=liveInputHosts.toList()
        hosts.forEach(LiveArtifactUiHost::cancelLiveInput)
        val operation=automaticJob ?: job
        val localOperation = localButtonJob
        watchStore.cancel();cancel()
        return CoroutineScope(Dispatchers.IO).launch {
            if(!closingOnMain) withContext(Dispatchers.Main.immediate) {mountedVideos.forEach(ToolVideoHost::close)}
            if (!closingOnMain) withContext(Dispatchers.Main.immediate) { stopTilt() }
            videoJobs.join()
            tiltJobs.join()
            operation?.join();localOperation?.join();selection?.join();watchStore.join()
            runCatching {
                when(abandoned) {
                    is CapabilityPrompt.Share -> broker.cancel(abandoned.effectKey)
                    is CapabilityPrompt.Image -> broker.cancel(abandoned.effectKey)
                    is CapabilityPrompt.Calendar -> broker.cancel(abandoned.effectKey)
                    is CapabilityPrompt.Picker -> abandonPicker(abandoned.launch,abandoned.origin)
                    is CapabilityPrompt.Reading -> abandonPicker(abandoned.launch,abandoned.origin)
                    is CapabilityPrompt.Camera -> abandonPicker(abandoned.launch,abandoned.origin)
                    is CapabilityPrompt.LocationPermission -> broker.cancel(abandoned.effectKey)
                    null -> Unit
                }
            }
            hosts.forEach {host ->
                try {host.close()} catch(failure: Exception) {android.util.Log.e("ArtifactRuntime","Live host close failed",failure)}
            }
            try {engine.closeArtifacts()} catch(failure: Exception) {android.util.Log.e("ArtifactRuntime","Local snapshot close failed",failure)}
            finally {store.close()}
        }.also {shutdown=it}
    }
    private fun abandonPicker(launch: CapabilitySelectionLaunch, origin: DeviceReadHost?) {
        if (launch.capability == VideoContract.CAPTURE) CapabilityResources(app, store).discardVideoCapture(launch)
        val effect=if(origin==null) null else store.effect(launch.effectKey)
        store.finishUserEffect(launch.effectKey,CapabilityResult("cancelled","Окно выбора закрыто. Данные не сохранены."))
        if(effect!=null) {
            store.rejectForegroundEffectDelivery(effect.owner,effect.revision,effect.stateVersion)
            store.releaseRejectedResources(effect.owner)
        }
    }
    private fun run(block: suspend () -> Unit) {
        if(busy || closing) return
        busy = true; error = null; errorCode = null
        job = scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) {
                if(failure is ToolLibraryUnavailable) storageUnavailable=true
                error = when(failure) {
                    is ToolLibraryUnavailable -> app.getString(R.string.tool_library_unavailable)
                    is StandaloneRefinementUnavailable -> app.getString(R.string.tool_standalone_refinement_unavailable)
                    is CloudFailure -> failure.message
                    else -> failure.message?.takeIf { message -> message.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' } }?.take(240) ?: "Не удалось выполнить действие инструмента. Сохранённые данные доступны."
                }
                errorCode = (failure as? CloudFailure)?.code
            }
            finally {
                if (!closing) withContext(NonCancellable) { runCatching { withContext(Dispatchers.IO) { refresh(deliverResults = false) } } }
                busy = false
            }
        }
    }
    private suspend fun refresh(deliverResults: Boolean = true) {
        store.recoverCapabilityWork()
        if(deliverResults) store.resultOwners().forEach { engine.deliverCapabilityResults(it) }
        loadSnapshots()
        if (store.primaryLibrary) com.alpha.assistant.surfaces.ToolWidgets.updateAll(app)
    }
    private suspend fun loadSnapshots() = snapshotMutex.withLock {
        val selected=withContext(Dispatchers.Main) { selectedId }
        val previewRequest=previews.request()
        val change=store.changes.value
        val loaded = store.tools(); val pending = store.drafts()
        val authority=networkAuthority ?: runCatching {ToolNetworkGrants.authority(store)}.getOrNull()?.also {networkAuthority=it}
        val networkAccess=authority?.refreshAccess()
        val dependencies = if(selected == null) emptyList() else com.alpha.assistant.automations.AutomationStore(app,automationDatabase).use { rules ->
            rules.rules().filter { com.alpha.assistant.automations.AutomationRuntime.dependencies(it,selected) }.map { it.definition.name }
        }
        val actions = selected?.let(store::effects).orEmpty()
        withContext(Dispatchers.Main) {
            tools = loaded; drafts = pending
            networkAccessHost = networkAccess
            if(store.changes.value==change) previews.reconcile(previewRequest,pending)
            if(selectedId==selected) {dependentRules = dependencies; effects = actions}
            if(selectedId != null && loaded.none { it.id == selectedId }) { error = "Этот инструмент недоступен. Выбери другой сохранённый инструмент."; errorCode = null }
        }
    }
}

private fun ToolDraftPreview.matchesDraft(draft: ToolDraft,allowPending: Boolean): Boolean =
    identity.draftId==draft.id && draft.acceptedRevision==null &&
        ((allowPending && draft.candidate==null && draft.previewState==null && draft.previewUi==null) ||
            (draft.candidate==candidateJson && draft.previewState==stateJson && draft.previewUi==uiJson))

/** The request owns one candidate. Clearing its atomic state also fences callbacks already in transit to Main. */
internal class ToolControllerDraftPreviews(
    private val scope: CoroutineScope,
    private val readDraft: suspend (String)->ToolDraft?
) {
    data class Request(val draftId: String,val requestId: String)
    private data class Slot(
        val request: Request,
        val snapshot: ToolDraftPreview? = null,
        val clearedThrough: Int? = null,
        val completed: Boolean = false
    )
    private val active=AtomicReference<Slot?>(null)
    private var visibleIdentity by mutableStateOf<ToolDraftPreviewIdentity?>(null)

    fun request(): Request? = active.get()?.request

    fun previewFor(draftId: String): ToolDraftPreview? {
        val visible=visibleIdentity ?: return null
        return active.get()?.snapshot?.takeIf {it.identity==visible && it.identity.draftId==draftId}
    }

    suspend fun begin(draftId: String,requestId: String): ToolDraftPreviewObserver = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        active.set(Slot(Request(draftId,requestId)))
        project()
        ToolDraftPreviewObserver(requestId,::publish,::clearAttempt)
    }

    private fun Slot.belongsTo(identity: ToolDraftPreviewIdentity) =
        request.draftId==identity.draftId && request.requestId==identity.requestId

    private fun canPublish(slot: Slot?,identity: ToolDraftPreviewIdentity): Boolean {
        if(slot==null || !slot.belongsTo(identity) || slot.completed) return false
        val latest=maxOf(slot.clearedThrough ?: Int.MIN_VALUE,slot.snapshot?.identity?.attempt ?: Int.MIN_VALUE)
        return identity.attempt>latest
    }

    private suspend fun publish(snapshot: ToolDraftPreview) {
        val identity=snapshot.identity
        if(!canPublish(active.get(),identity)) return
        val draft=readDraft(identity.draftId)
        if(!canPublish(active.get(),identity)) return
        if(draft==null || !snapshot.matchesDraft(draft,allowPending=true)) {
            clearAttempt(identity)
            return
        }
        withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext().ensureActive()
            active.updateAndGet {slot ->
                if(canPublish(slot,identity)) checkNotNull(slot).copy(snapshot=snapshot) else slot
            }
            project()
        }
    }

    private fun clearAttempt(identity: ToolDraftPreviewIdentity) = change {
        if(it.belongsTo(identity) && !it.completed) {
            val cleared=maxOf(it.clearedThrough ?: identity.attempt,identity.attempt)
            val snapshot=it.snapshot?.takeIf {preview -> preview.identity.attempt>cleared}
            it.copy(snapshot=snapshot,clearedThrough=cleared)
        } else it
    }

    suspend fun complete(requestId: String,draft: ToolDraft) = withContext(Dispatchers.Main.immediate) {
        active.updateAndGet {slot ->
            if(slot?.request?.requestId!=requestId) slot
            else if(slot.snapshot?.matchesDraft(draft,allowPending=false)==true) slot.copy(completed=true)
            else null
        }
        project()
    }

    fun reconcile(request: Request?,drafts: List<ToolDraft>) {
        if(request==null) return
        val draft=drafts.find {it.id==request.draftId}
        change {slot ->
            if(slot.request!=request) slot
            else if(draft==null || draft.acceptedRevision!=null ||
                slot.snapshot?.matchesDraft(draft,allowPending=!slot.completed)==false) null
            else slot
        }
    }

    fun clear(draftId: String? = null) = change {slot ->
        if(draftId==null || slot.request.draftId==draftId) null else slot
    }

    fun clearRequest(requestId: String) = change {slot ->
        if(slot.request.requestId==requestId) null else slot
    }

    private fun change(update: (Slot)->Slot?) {
        val previous=active.getAndUpdate {it?.let(update)}
        if(previous!==active.get()) scope.launch(Dispatchers.Main.immediate) {project()}
    }

    private fun project() {
        visibleIdentity=active.get()?.snapshot?.identity
    }
}
