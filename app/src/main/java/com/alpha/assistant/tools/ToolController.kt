package com.alpha.assistant.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.content.pm.ShortcutManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.*
import com.alpha.assistant.capabilities.*
import java.util.concurrent.atomic.AtomicReference

class ToolController(private val app: Application, private val scope: CoroutineScope, provider: ChatProvider,
    private val keys: SecureKeyStore, private val store: ToolStore,
    private val readImage: (Attachment) -> String = { ImageInput.dataUrl(app.contentResolver,it) }) {
    private val engine = ToolEngine(store, ToolGenerationService(provider,SandboxClient(app)))
    private val broker = CapabilityBroker(app,store)
    var dependentRules by mutableStateOf<List<String>>(emptyList()); private set
    private val automationDatabase = if(store.databaseName == "alpha-tools.db") "alpha-automations.db" else "${store.databaseName}-automations.db"
    var effects by mutableStateOf<List<CapabilityEffect>>(emptyList()); private set
    var grants by mutableStateOf<Set<String>>(emptySet()); private set
    var tools by mutableStateOf<List<ToolRecord>>(emptyList()); private set
    var drafts by mutableStateOf<List<ToolDraft>>(emptyList()); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var selectedDraftId by mutableStateOf<String?>(null); private set
    var revisions by mutableStateOf<List<ToolRevision>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var errorCode by mutableStateOf<String?>(null); private set // the CloudFailure code of error
    var requestPrompt by mutableStateOf(""); private set
    fun preparePrompt(text: String) { requestPrompt = text; select(null) }
    @Volatile private var closing = false
    private var job: Job? = null
    private val session = AtomicReference<CloudSession?>(null)
    init { run { refresh() } }
    fun select(id: String?) { selectedId = id; selectedDraftId = null; run { refresh() } }
    fun reload() { run { refresh() } }
    fun selectDraft(id: String) { selectedDraftId = id; selectedId = drafts.find { it.id == id }?.toolId; run { refresh() } }
    fun clearError() { error = null; errorCode = null }
    fun generate(prompt: String, toolId: String?, conversationId: String?, images: List<Attachment>) {
        run {
            val draft = engine.createDraft(prompt,toolId,conversationId)
            withContext(Dispatchers.Main) { selectedDraftId = draft.id; selectedId = draft.toolId }
            refresh()
            generateDraft(draft.id,images)
        }
    }
    suspend fun conversationTools(conversationId: String): List<ToolRecord> = withContext(Dispatchers.IO) {
        store.tools().filter { it.conversationId == conversationId && !it.archived }
    }
    suspend fun recoverAutomatic(operationId: String, conversationId: String): Pair<String,Int>? = withContext(Dispatchers.IO) {
        val receipt = store.draft(operationId) ?: return@withContext null
        val revision = receipt.acceptedRevision ?: return@withContext null
        val id = receipt.toolId ?: return@withContext null
        if(receipt.conversationId != conversationId) return@withContext null
        val tool = store.tool(id) ?: return@withContext null
        val accepted = store.revisions(id).find { it.revision == revision } ?: return@withContext null
        if(tool.conversationId != conversationId || accepted.spec != receipt.candidate || accepted.state != receipt.previewState || accepted.ui != receipt.previewUi) return@withContext null
        id to revision
    }
    /** Shell owns cancellation. The final guarded commit and message update run on Main without a suspension. */
    suspend fun generateAutomatic(prompt: String, target: ToolRecord?, conversationId: String, operationId: String,
        key: String, images: List<String>, call: CloudSession, cancelRequest: () -> Unit,
        prepared: suspend () -> Unit, canCommit: () -> Boolean, committed: (ToolRecord) -> Unit) {
        if(busy || closing) throw CloudFailure("widget_busy","Another tool operation is active. Retry after it completes.")
        busy = true; error = null; errorCode = null; automaticCancel = cancelRequest; automaticJob = currentCoroutineContext()[Job]
        var accepted = false
        var created = false
        try {
            withContext(Dispatchers.IO) { engine.createDraft(prompt,target?.id,conversationId,operationId,target); created = true }
            prepared()
            withContext(Dispatchers.IO) { engine.generate(operationId,key,images,call) }
            currentCoroutineContext().ensureActive(); call.check()
            val result = engine.accept(operationId,retainReceipt = true,canCommit = canCommit)
            accepted = true
            committed(result)
        } finally {
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
            } finally { busy = false; automaticJob = null }
        }
    }
    private var automaticCancel: (() -> Unit)? = null
    private var automaticJob: Job? = null
    fun retry(id: String) { run { generateDraft(id,emptyList()) } }
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
            engine.generate(id,key,data,call)
        } finally { session.compareAndSet(call,null) }
        refresh()
    }
    fun accept(id: String) { run { val result = engine.accept(id); withContext(Dispatchers.Main) { selectedId = result.id; selectedDraftId = null }; refresh() } }
    fun reject(id: String) { run { val owner = drafts.find { it.id == id }?.toolId; engine.reject(id); withContext(Dispatchers.Main) { selectedDraftId = null; selectedId = owner }; refresh() } }
    /** Metadata-only recheck for visible cached images, including expiry, removal, and Android URI access. */
    suspend fun nativeImageAvailable(owner: String, handle: String): Boolean = withContext(Dispatchers.IO) {
        try {CapabilityResources(app,store).resolve(owner,handle).mimeType.startsWith("image/")}
        catch(cancel: CancellationException) {throw cancel} catch(_: Exception) {false}
    }
    /** Only validated, owner-scoped image handles enter the native decoder. */
    suspend fun nativeImage(owner: String, handle: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            require(handle.matches(Regex("[0-9a-f]{64}")))
            val selected=CapabilityResources(app,store).resolve(owner,handle)
            require(selected.mimeType.startsWith("image/"))
            val bytes=(if(selected.kind == "cameraPhoto") java.io.File(selected.locator).inputStream()
                else checkNotNull(app.contentResolver.openInputStream(Uri.parse(selected.locator)))).use { input ->
                val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                while(true) {val read=input.read(buffer); if(read < 0) break
                    require(output.size()+read <= CapabilityResources.MAX_FILE_BYTES); output.write(buffer,0,read)}
                output.toByteArray()
            }
            val options=BitmapFactory.Options().apply {inJustDecodeBounds=true; inSampleSize=1; inPreferredConfig=Bitmap.Config.ARGB_8888; inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            require(options.outWidth > 0 && options.outHeight > 0)
            while(options.outWidth/options.inSampleSize > ToolUiCatalog.MAX_IMAGE_EDGE || options.outHeight/options.inSampleSize > ToolUiCatalog.MAX_IMAGE_EDGE) options.inSampleSize*=2
            options.inJustDecodeBounds=false
            val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options))
            require(image.width <= ToolUiCatalog.MAX_IMAGE_EDGE && image.height <= ToolUiCatalog.MAX_IMAGE_EDGE && image.allocationByteCount <= ToolUiCatalog.MAX_IMAGE_BYTES) {"Decoded image exceeds the native memory limit."}
            // Recheck access after provider IO, before the image enters the UI.
            CapabilityResources(app,store).resolve(owner,handle)
            image
        } catch(cancel: CancellationException) {throw cancel} catch(_: Exception) {null}
    }
    fun artifactHost(t: ToolRecord)=object: ArtifactUiHost {
        private var generation: Long?=null
        private suspend fun publish(snapshot: ArtifactSnapshot): ArtifactSnapshot {
            withContext(Dispatchers.Main) {tools=tools.map {if(it.id==snapshot.tool.id) snapshot.tool else it}}
            return snapshot
        }
        override suspend fun open(): ArtifactSnapshot {
            var acquired: Long?=null
            try {
                return withContext(Dispatchers.IO) {
                    check(!closing) {"The artifact owner is closed."}
                    val snapshot=engine.openArtifact(t)
                    acquired=snapshot.sessionGeneration;generation=snapshot.sessionGeneration
                    publish(snapshot)
                }
            } catch(failure: Throwable) {
                withContext(NonCancellable+Dispatchers.IO) {
                    try {acquired?.let {engine.closeArtifact(t.id,t.revision,it)}}
                    catch(cleanup: Exception) {failure.addSuppressed(cleanup)}
                }
                throw failure
            }
        }
        override suspend fun snapshot(): ArtifactSnapshot=withContext(Dispatchers.IO) {publish(engine.artifactSnapshot(t.id,t.revision,checkNotNull(generation)))}
        override suspend fun flush(): ArtifactSnapshot=withContext(Dispatchers.IO) {publish(engine.flushArtifact(t.id,t.revision,checkNotNull(generation)))}
        override suspend fun event(event: org.json.JSONObject): ArtifactSnapshot=withContext(Dispatchers.IO) {publish(engine.artifactEvent(t.id,t.revision,event,checkNotNull(generation)))}
        override suspend fun close()=withContext(Dispatchers.IO) {generation?.let {engine.closeArtifact(t.id,t.revision,it)};Unit}
    }
    fun interact(t: ToolRecord, action: String, value: Any?, foreground: ()->Boolean = { false }, launch: ((android.content.Intent)->Unit)? = null) {
        interactNative(t,action,value,foreground,launch,null)
    }
    internal fun calendarUiHost(t: ToolRecord,foreground: ()->Boolean,launch: (android.content.Intent)->Unit)=object: OwnedCalendarUiHost {
        override suspend fun snapshot(): OwnedCalendarSnapshot?=withContext(Dispatchers.IO) {runCatching {OwnedCalendarLedger(store).snapshot(t)}.getOrNull()}
        override fun deleteAll(action: String,snapshot: OwnedCalendarSnapshot) {interactNative(t,action,null,foreground,launch,snapshot)}
    }
    private fun interactNative(t: ToolRecord,action: String,value: Any?,foreground: ()->Boolean,launch: ((android.content.Intent)->Unit)?,calendarSnapshot: OwnedCalendarSnapshot?) {run {
        // Freeze the actual native selection before generated code can change state or controls.
        val selected=NavigationInteraction.from(t,action,value)
        val calendarSelection=OwnedCalendarInteraction.from(t,action,value)
        val medicineSelection=MedicineInteraction.from(t,action,value)
        if(calendarSnapshot!=null)check(OwnedCalendarLedger(store).snapshotCurrent(checkNotNull(store.tool(t.id)),calendarSnapshot)) {
            "The displayed owned-event snapshot changed. Review the current native control."
        }
        val changed=engine.interact(t.id,t.revision,t.stateVersion,action,value)
        val navigation=store.effects(t.id).filter { it.revision == changed.revision && it.stateVersion == changed.stateVersion &&
            it.phase == "pending" && it.request.capability in NavigationContract.ids }
        if(launch != null && selected != null && navigation.size == 1) navigation.single().takeIf(selected::matches)?.let { effect ->
            if(broker.granted(t.id,effect.request.capability)) broker.executeNative(effect.key,true,foreground,launch)
        }
        val medicineEffects=store.effects(t.id).filter {it.revision==changed.revision && it.stateVersion==changed.stateVersion && it.phase=="pending" && it.request.capability in MedicineContract.ids}
        if(launch!=null && medicineSelection!=null && medicineEffects.size==1 && store.effects(t.id).count {it.revision==changed.revision && it.stateVersion==changed.stateVersion}==1) {
            medicineEffects.single().takeIf(medicineSelection::matches)?.let {effect ->
                if(broker.granted(t.id,effect.request.capability)) broker.executeNative(effect.key,true,foreground,launch)
            }
        }
        val calendarEffects=store.effects(t.id).filter {it.revision==changed.revision && it.stateVersion==changed.stateVersion && it.phase=="pending" && it.request.capability in OwnedCalendarContract.ids}
        if(launch!=null && calendarSelection!=null && calendarEffects.size==1 && store.effects(t.id).count {it.revision==changed.revision && it.stateVersion==changed.stateVersion}==1) {
            calendarEffects.single().takeIf(calendarSelection::matches)?.let {effect ->
                if(broker.granted(t.id,effect.request.capability)) {
                    if(effect.request.capability=="calendar.deleteAllOwned") {
                        if(calendarSnapshot!=null)broker.executeCalendarDeleteAll(effect.key,calendarSnapshot,foreground)
                    } else broker.executeNative(effect.key,true,foreground,launch)
                }
            }
        }
        refresh()
    } }
    internal suspend fun writableCalendars(): List<NativeOwnedCalendar> = withContext(Dispatchers.IO) {OwnedCalendarCapabilities(app,store).writableCalendars()}
    internal fun selectCalendar(key: String,calendar: NativeOwnedCalendar,foreground: ()->Boolean) {run {
        broker.executeOwned(key,true) {effect -> withContext(Dispatchers.Main) {OwnedCalendarCapabilities(app,store).select(effect,calendar,foreground)}}
        refresh()
    }}
    fun rollback(t: ToolRecord, revision: Int) { run { engine.rollback(t.id,t.revision,t.stateVersion,revision); refresh() } }
    fun archive(tool: ToolRecord) { run {
        com.alpha.assistant.automations.AutomationStore(app,automationDatabase).use {
            com.alpha.assistant.automations.AutomationRuntime.archiveTool(store,it,tool)
        }
        if(store.databaseName == "alpha-tools.db") {
            app.getSystemService(ShortcutManager::class.java).disableShortcuts(listOf("tool-${tool.id}"),"Инструмент в архиве. Восстанови его в Alpha.")
            com.alpha.assistant.automations.AutomationRuntime.refresh(app)
        }
        refresh()
    } }
    fun recover(tool: ToolRecord) { run {
        store.setArchived(tool,false)
        if(store.databaseName == "alpha-tools.db") app.getSystemService(ShortcutManager::class.java).enableShortcuts(listOf("tool-${tool.id}"))
        refresh()
    } }
    fun save(id: String) { run { engine.save(id); refresh() } }
    fun grant(tool: ToolRecord, capability: String, enabled: Boolean) { run { broker.grant(tool.id,capability,enabled); if(capability in MedicineContract.ids) com.alpha.assistant.automations.AutomationRuntime.refresh(app); refresh() } }
    fun executeEffect(key: String, confirmed: Boolean, foreground: ()->Boolean, launch: (android.content.Intent) -> Unit) {
        run {
            broker.executeNative(key,confirmed,foreground,launch)
            refresh()
        }
    }
    suspend fun saveCameraPreview(key: String, bitmap: android.graphics.Bitmap): CapabilityResult = withContext(Dispatchers.IO) {
        CapabilityResources(app,store).saveCameraPreview(key,bitmap)
    }
    fun finishSelection(key: String, result: CapabilityResult) {
        scope.launch {
            job?.join()
            automaticJob?.join()
            // A callback must persist even when another controller operation becomes busy after the join.
            withContext(Dispatchers.IO) { CapabilityResources(app,store).finishSelection(key,result) }
            run { refresh() }
        }
    }
    fun cancel() { automaticCancel?.invoke(); session.getAndSet(null)?.cancel(); job?.cancel() }
    fun close() {
        closing=true;cancel()
        CoroutineScope(Dispatchers.IO).launch {
            (automaticJob ?: job)?.join()
            try {engine.closeArtifacts()} catch(failure: Exception) {android.util.Log.e("ArtifactRuntime","Local snapshot close failed",failure)}
            finally {store.close()}
        }
    }
    private fun run(block: suspend () -> Unit) {
        if(busy || closing) return
        busy = true; error = null; errorCode = null
        job = scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) {
                error = (failure as? CloudFailure)?.message ?: failure.message?.takeIf { message -> message.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' } }?.take(240) ?: "Не удалось выполнить действие инструмента. Сохранённые данные доступны."
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
        val loaded = store.tools(); val pending = store.drafts()
        val dependencies = if(selectedId == null) emptyList() else com.alpha.assistant.automations.AutomationStore(app,automationDatabase).use { rules ->
            rules.rules().filter { com.alpha.assistant.automations.AutomationRuntime.dependencies(it,selectedId!!) }.map { it.definition.name }
        }
        val history = selectedId?.let(store::revisions).orEmpty()
        val actions = selectedId?.let(store::effects).orEmpty()
        val allowed = loaded.flatMap { tool -> ToolSpec.available(tool.spec)?.capabilities.orEmpty().filter { broker.granted(tool.id,it) }.map { tool.id + ":" + it } }.toSet()
        if (store.databaseName == "alpha-tools.db") com.alpha.assistant.surfaces.ToolWidgets.updateAll(app)
        withContext(Dispatchers.Main) {
            tools = loaded; drafts = pending; dependentRules = dependencies; revisions = history; effects = actions; grants = allowed
            if(selectedId != null && loaded.none { it.id == selectedId }) { error = "Этот инструмент недоступен. Выбери другой сохранённый инструмент."; errorCode = null }
        }
    }
}
