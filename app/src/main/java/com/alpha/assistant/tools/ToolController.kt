package com.alpha.assistant.tools

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.*
import com.alpha.assistant.capabilities.*

class ToolController(private val app: Application, private val scope: CoroutineScope, provider: ChatProvider,
    private val keys: SecureKeyStore, private val store: ToolStore) {
    private val engine = ToolEngine(store, ToolGenerationService(provider,SandboxClient(app)))
    private val broker = CapabilityBroker(app,store)
    private val android = AndroidCapabilities(app)
    var effects by mutableStateOf<List<CapabilityEffect>>(emptyList()); private set
    var grants by mutableStateOf<Set<String>>(emptySet()); private set
    var tools by mutableStateOf<List<ToolRecord>>(emptyList()); private set
    var drafts by mutableStateOf<List<ToolDraft>>(emptyList()); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var selectedDraftId by mutableStateOf<String?>(null); private set
    var revisions by mutableStateOf<List<ToolRevision>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var requestPrompt by mutableStateOf(""); private set
    fun preparePrompt(text: String) { requestPrompt = text; select(null) }
    private var closing = false
    private var job: Job? = null
    private var session: CloudSession? = null
    init { run { refresh() } }
    fun select(id: String?) { selectedId = id; selectedDraftId = null; run { refresh() } }
    fun reload() { run { refresh() } }
    fun selectDraft(id: String) { selectedDraftId = id; selectedId = null }
    fun clearError() { error = null }
    fun generate(prompt: String, toolId: String?, conversationId: String?, images: List<Attachment>) {
        run {
            val draft = engine.createDraft(prompt,toolId,conversationId)
            withContext(Dispatchers.Main) { selectedDraftId = draft.id; selectedId = null }
            refresh()
            generateDraft(draft.id,images)
        }
    }
    fun retry(id: String) { run { generateDraft(id,emptyList()) } }
    private suspend fun generateDraft(id: String, images: List<Attachment>) {
        val key = keys.read().getOrThrow() ?: error("Добавь API-ключ DeepSeek в настройках")
        val data = images.map { ImageInput.dataUrl(app.contentResolver,it) }
        val call = CloudSession()
        session = call
        try { engine.generate(id,key,data,call) } finally { session = null }
        refresh()
    }
    fun accept(id: String) { run { val result = engine.accept(id); withContext(Dispatchers.Main) { selectedId = result.id; selectedDraftId = null }; refresh() } }
    fun reject(id: String) { run { engine.reject(id); withContext(Dispatchers.Main) { selectedDraftId = null }; refresh() } }
    fun interact(t: ToolRecord, action: String, value: Any?) { run { engine.interact(t.id,t.revision,t.stateVersion,action,value); refresh() } }
    fun rollback(t: ToolRecord, revision: Int) { run { engine.rollback(t.id,t.revision,t.stateVersion,revision); refresh() } }
    fun save(id: String) { run { engine.save(id); refresh() } }
    fun grant(tool: ToolRecord, capability: String, enabled: Boolean) { run { broker.grant(tool.id,capability,enabled); refresh() } }
    fun executeEffect(key: String, confirmed: Boolean, foreground: Boolean, launch: (android.content.Intent) -> Unit) {
        run {
            broker.execute(key,confirmed) { request -> android.execute(request,foreground) { intent -> withContext(Dispatchers.Main) { launch(intent) } } }
            refresh()
        }
    }
    fun finishSelection(key: String, result: CapabilityResult, discard: () -> Unit = {}) {
        scope.launch {
            job?.join()
            run {
                store.finishUserEffect(key,result)
                if(store.effect(key)?.result?.status != "succeeded") discard()
                refresh()
            }
        }
    }
    fun cancel() { job?.cancel(); session?.let { call -> Thread { call.cancel() }.start() }; session = null }
    fun close() { closing = true; cancel(); job?.invokeOnCompletion { store.close() } ?: store.close() }
    private fun run(block: suspend () -> Unit) {
        if(busy || closing) return
        busy = true; error = null
        job = scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { error = (failure as? CloudFailure)?.message ?: failure.message?.takeIf { message -> message.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' } }?.take(240) ?: "Не удалось выполнить действие инструмента. Сохранённые данные доступны." }
            finally {
                if (!closing) withContext(NonCancellable) { runCatching { withContext(Dispatchers.IO) { refresh() } } }
                busy = false
            }
        }
    }
    private suspend fun refresh() {
        val loaded = store.tools(); val pending = store.drafts()
        val history = selectedId?.let(store::revisions).orEmpty()
        val actions = selectedId?.let(store::effects).orEmpty()
        val allowed = loaded.flatMap { tool -> ToolSpec.parse(tool.spec).capabilities.filter { broker.granted(tool.id,it) }.map { tool.id + ":" + it } }.toSet()
        if (store.databaseName == "alpha-tools.db") com.alpha.assistant.surfaces.ToolWidgets.updateAll(app)
        withContext(Dispatchers.Main) {
            tools = loaded; drafts = pending; revisions = history; effects = actions; grants = allowed
            if(selectedId != null && loaded.none { it.id == selectedId }) error = "Этот инструмент недоступен. Выбери другой сохранённый инструмент."
        }
    }
}
