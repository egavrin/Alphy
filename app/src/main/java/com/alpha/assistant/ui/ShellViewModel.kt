package com.alpha.assistant.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alpha.assistant.data.*
import com.alpha.assistant.cloud.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ShellViewModel @JvmOverloads constructor(application: Application,
    private val provider: ChatProvider = DeepSeekProvider(),
    private val store: ShellStore = ShellStore(application),
    private val keys: SecureKeyStore = SecureKeyStore(application)
) : AndroidViewModel(application) {
    val voice = com.alpha.assistant.voice.VoiceSession(application, viewModelScope) { text ->
        if (snapshot.draft.text.isBlank() && snapshot.draft.attachments.isEmpty()) editDraft(snapshot.draft.copy(text = text))
        else voiceDraftConflict()
    }
    private fun voiceDraftConflict() { voice.stop("Draft changed. Voice stopped without replacing your text.") }
    val tools = com.alpha.assistant.tools.ToolController(application, viewModelScope, provider, keys,
        com.alpha.assistant.tools.ToolStore(application, if (store.databaseName == "alpha.db") "alpha-tools.db" else "${store.databaseName}-tools.db"))
    var surfaceSequence by mutableStateOf(0); private set
    var surfaceToolId by mutableStateOf<String?>(null); private set
    fun openSurface(toolId: String?) {
        surfaceToolId = toolId
        surfaceSequence++
        tools.select(toolId)
    }
    var snapshot by mutableStateOf(ShellSnapshot()); private set
    var loading by mutableStateOf(true); private set
    var loadFailed by mutableStateOf(false); private set
    var saveFailed by mutableStateOf(false); private set
    var keyStatus by mutableStateOf("Проверка ключа…"); private set
    var keyBusy by mutableStateOf(false); private set
    var requestError by mutableStateOf<String?>(null); private set
    var attachmentBusy by mutableStateOf(false); private set
    var searchEnabled by mutableStateOf(false); private set
    var activeConversationId by mutableStateOf<String?>(null); private set
    private data class Active(val conversationId: String, val assistantId: String, val session: CloudSession = CloudSession(), var job: Job? = null)
    private var active: Active? = null
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (write in writes) {
                val result = withContext(Dispatchers.IO) { runCatching(write) }
                if (result.isFailure) saveFailed = true
            }
        }
        load()
    }
    fun load() {
        loading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.load() } }
            result.onSuccess { loaded ->
                snapshot = loaded.copy(conversations = loaded.conversations.map { conversation ->
                    val messages = conversation.messages.map { message ->
                        if (message.role == "assistant" && message.status in listOf("pending", "streaming"))
                            message.copy(status = "interrupted", error = "Приложение закрылось до завершения ответа. Можно повторить запрос.", errorCode = "interrupted", searchStatus = if (message.searchStatus == "searching") "error" else message.searchStatus)
                        else message
                    }
                    conversation.copy(messages = messages).also { if (messages != conversation.messages) writes.trySend { store.saveConversation(it) } }
                })
            }
            loadFailed = result.isFailure
            loading = false
            refreshKey()
        }
    }
    fun retrySave() {
        val current = snapshot
        writes.trySend {
            store.writableDatabase.beginTransaction()
            try {
                current.conversations.forEach(store::saveConversation)
                store.saveNewDraft(current.newDraft)
                store.saveSetting("selected", current.selectedId.orEmpty())
                store.saveSetting("theme", current.theme)
                store.saveSetting("mode", current.mode)
                store.writableDatabase.setTransactionSuccessful()
            } finally { store.writableDatabase.endTransaction() }
        }
        saveFailed = false
    }
    fun select(id: String?) {
        voice.stop()
        snapshot = snapshot.copy(selectedId = id)
        writes.trySend { store.saveSetting("selected", id.orEmpty()) }
    }
    fun editDraft(draft: Draft) {
        val selected = snapshot.selected
        if (selected == null) {
            snapshot = snapshot.copy(newDraft = draft)
            writes.trySend { store.saveNewDraft(draft) }
        } else {
            val updated = selected.copy(draft = draft)
            replace(updated)
            writes.trySend { store.saveConversation(updated) }
        }
    }
    fun setSearch(enabled: Boolean) { searchEnabled = enabled }
    fun clearRequestError() { requestError = null }
    fun addAttachment(attachment: Attachment) {
        if (attachmentBusy) return
        val conversationId = snapshot.selectedId
        if (snapshot.draft.attachments.size >= ImageInput.MAX_IMAGES) { requestError = "Можно выбрать не более 4 изображений"; return }
        attachmentBusy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ImageInput.read(getApplication<Application>().contentResolver, attachment) } }
            if (result.isSuccess) {
                // A picker result belongs to the draft that was active when validation started.
                if (conversationId == snapshot.selectedId) editDraft(snapshot.draft.copy(attachments = snapshot.draft.attachments.filterNot { it.uri == attachment.uri } + attachment))
                else requestError = "Чат изменился. Выберите изображение в нужном чате ещё раз."
            } else requestError = (result.exceptionOrNull() as? CloudFailure)?.message ?: "Не удалось прочитать изображение"
            attachmentBusy = false
        }
    }
    fun send() {
        val draft = snapshot.draft
        if (loading || loadFailed || attachmentBusy || (draft.text.isBlank() && draft.attachments.isEmpty())) return
        if (draft.text.length > 16000) { requestError = "Сообщение превышает 16 000 символов. Сократите текст."; return }
        voice.submitted()
        cancelRequest()
        requestError = null
        val now = System.currentTimeMillis()
        val user = Message(UUID.randomUUID().toString(), "user", draft.text.trim(), now, "pending", draft.attachments, search = searchEnabled)
        val assistant = Message(UUID.randomUUID().toString(), "assistant", "", now, "pending", replyTo = user.id, search = user.search)
        val selected = snapshot.selected
        val updated = if (selected == null) Conversation(UUID.randomUUID().toString(),
            draft.text.trim().take(70).ifBlank { draft.attachments.first().name }, now, now, messages = listOf(user, assistant))
        else selected.copy(updatedAt = now, draft = Draft(), messages = selected.messages + listOf(user, assistant))
        replace(updated)
        snapshot = snapshot.copy(selectedId = updated.id, newDraft = if (selected == null) Draft() else snapshot.newDraft)
        val saved = CompletableDeferred<Boolean>()
        writes.trySend {
            val result = runCatching { if (selected == null) store.createFromNewDraft(updated) else store.saveConversation(updated) }
            saved.complete(result.isSuccess)
            result.getOrThrow()
        }
        startRequest(updated.id, user, assistant, saved)
    }
    fun retry(messageId: String) {
        if (active != null) return
        val conversation = snapshot.selected ?: return
        val failed = conversation.messages.lastOrNull() ?: return
        if (failed.id != messageId || failed.status !in listOf("error", "cancelled", "interrupted")) return
        val user = conversation.messages.find { it.id == failed.replyTo } ?: return
        val assistant = Message(UUID.randomUUID().toString(), "assistant", "", System.currentTimeMillis(), "pending", replyTo = user.id, search = user.search)
        val updated = conversation.copy(messages = conversation.messages + assistant)
        replace(updated)
        val saved = CompletableDeferred<Boolean>()
        writes.trySend { val result = runCatching { store.saveConversation(updated) }; saved.complete(result.isSuccess); result.getOrThrow() }
        startRequest(updated.id, user, assistant, saved)
    }
    private fun startRequest(conversationId: String, user: Message, assistant: Message, saved: CompletableDeferred<Boolean>) {
        val request = Active(conversationId, assistant.id)
        active = request; activeConversationId = conversationId
        request.job = viewModelScope.launch {
            try {
                if (!saved.await()) throw CloudFailure("storage", "Запрос не отправлен: не удалось сохранить историю. Повторите сохранение.")
                val key = withContext(Dispatchers.IO) {
                    val result = keys.read()
                    if (result.isFailure) throw CloudFailure("key_read", "Ключ недоступен. Введите новый ключ в настройках. Сохранённые данные не удалены.")
                    (result.getOrNull() ?: throw CloudFailure("key_missing", "Добавьте API-ключ DeepSeek в настройках и повторите запрос.")).also {
                        if (it.any { char -> char.isWhitespace() || char.isISOControl() }) throw CloudFailure("key_format", "API-ключ содержит пробелы или переносы строк. Замените ключ в настройках.")
                    }
                }
                val images = withContext(Dispatchers.IO) {
                    if (user.attachments.size > ImageInput.MAX_IMAGES) throw CloudFailure("image_count", "Можно отправить не более 4 изображений")
                    user.attachments.map { ImageInput.dataUrl(getApplication<Application>().contentResolver, it) }
                }
                val conversation = snapshot.conversations.find { it.id == conversationId } ?: return@launch
                val before = conversation.messages.takeWhile { it.id != user.id }
                val complete = before.filter { it.role == "assistant" && it.status == "complete" }.mapNotNull { answer ->
                    before.find { it.id == answer.replyTo }?.let { question -> listOf(CloudTurn("user", question.text.ifBlank { "[Изображение из предыдущего запроса не передаётся]" }), CloudTurn("assistant", answer.text)) }
                }
                var size = user.text.length
                val history = complete.asReversed().take(20).takeWhile { pair -> size += pair.sumOf { it.text.length }; size <= 48000 }.asReversed().flatten()
                updateMessage(request, user.id) { it.copy(status = "sent") }
                updateMessage(request, assistant.id) { it.copy(status = "streaming") }
                var lastSaved = 0L
                withContext(Dispatchers.IO) {
                    provider.respond(key, CloudRequest(history + CloudTurn("user", user.text, images), user.search), request.session) { event ->
                        withContext(Dispatchers.Main) {
                            if (active === request) {
                                val now = System.currentTimeMillis()
                                when (event) {
                                    is CloudEvent.Text -> updateMessage(request, assistant.id, persist = now - lastSaved >= 500) { it.copy(text = it.text + event.value) }
                                    is CloudEvent.Search -> updateMessage(request, assistant.id) { it.copy(searchStatus = if (event.status == "empty" && it.sources.isNotEmpty()) "found" else event.status, sources = (it.sources + event.sources).distinctBy { source -> source.url }) }
                                }
                                if (now - lastSaved >= 500) lastSaved = now
                            }
                        }
                    }
                }
                updateMessage(request, assistant.id) { it.copy(status = "complete") }
                snapshot.conversations.find { it.id == conversationId }?.messages?.find { it.id == assistant.id }?.let { voice.reply(it.text) }
            } catch (_: CancellationException) {
                // cancelRequest persists the terminal state before it permits another request.
            } catch (failure: Exception) {
                voice.requestFailed()
                val known = failure as? CloudFailure
                updateMessage(request, user.id) { if (it.status == "pending") it.copy(status = "not_sent") else it }
                updateMessage(request, assistant.id) { it.copy(status = "error", errorCode = known?.code ?: "network", error = known?.message ?: "Нет соединения с DeepSeek. Проверьте сеть и повторите запрос.", searchStatus = if (it.searchStatus == "searching") "error" else it.searchStatus) }
            } finally {
                if (active === request) { active = null; activeConversationId = null }
            }
        }
    }
    private fun updateMessage(request: Active, messageId: String, persist: Boolean = true, transform: (Message) -> Message) {
        if (active !== request) return
        val conversation = snapshot.conversations.find { it.id == request.conversationId } ?: return
        val updated = conversation.copy(messages = conversation.messages.map { if (it.id == messageId) transform(it) else it })
        replace(updated)
        if (persist) writes.trySend { store.saveConversation(updated) }
    }
    fun cancelRequest() {
        val request = active ?: return
        voice.requestFailed()
        updateMessage(request, request.assistantId) { it.copy(status = "cancelled", errorCode = "cancelled", error = "Запрос остановлен. Полученная часть ответа сохранена.", searchStatus = if (it.searchStatus == "searching") "error" else it.searchStatus) }
        val userId = snapshot.conversations.find { it.id == request.conversationId }?.messages?.find { it.id == request.assistantId }?.replyTo
        if (userId != null) updateMessage(request, userId) { if (it.status == "pending") it.copy(status = "not_sent") else it }
        active = null; activeConversationId = null
        request.job?.cancel()
        viewModelScope.launch(Dispatchers.IO) { request.session.cancel() }
    }
    override fun onCleared() {
        voice.close()
        tools.close()
        active?.session?.let { session -> Thread { session.cancel() }.start() }
        writes.close()
        super.onCleared()
    }
    fun setting(name: String, value: String) {
        snapshot = when (name) { "theme" -> snapshot.copy(theme = value); "mode" -> snapshot.copy(mode = value); else -> return }
        writes.trySend { store.saveSetting(name, value) }
    }
    private fun replace(conversation: Conversation) {
        snapshot = snapshot.copy(conversations = (snapshot.conversations.filterNot { it.id == conversation.id } + conversation).sortedByDescending { it.updatedAt })
    }
    fun saveKey(value: String, done: (Boolean) -> Unit) {
        if (keyBusy) return
        keyBusy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { keys.save(value) }
            keyStatus = if (result.isSuccess) "Ключ сохранён на устройстве" else "Не удалось сохранить ключ. Сохранённые данные не удалены."
            keyBusy = false
            done(result.isSuccess)
        }
    }
    fun clearKey() {
        if (keyBusy) return
        keyBusy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { keys.clear() }
            keyStatus = if (result.isSuccess) "Ключ не задан" else "Не удалось удалить ключ"
            keyBusy = false
        }
    }
    private suspend fun refreshKey() {
        val result = withContext(Dispatchers.IO) { keys.read() }
        keyStatus = when { result.isFailure -> "Ключ недоступен. Зашифрованные данные сохранены."; result.getOrNull() == null -> "Ключ не задан"; else -> "Ключ сохранён на устройстве" }
    }
}
