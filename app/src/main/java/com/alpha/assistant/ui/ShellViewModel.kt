package com.alpha.assistant.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alpha.assistant.R
import com.alpha.assistant.data.*
import com.alpha.assistant.cloud.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.alpha.assistant.weather.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class ShellViewModel @JvmOverloads constructor(application: Application,
    private val provider: ChatProvider = DeepSeekProvider(),
    private val store: ShellStore = ShellStore(application),
    private val keys: SecureKeyStore = SecureKeyStore(application),
    voiceFactory: ((Application, kotlinx.coroutines.CoroutineScope, (String) -> Unit) -> com.alpha.assistant.voice.VoiceSession)? = null,
    private val weatherLocator: WeatherLocator = AndroidWeatherLocator(application)
) : AndroidViewModel(application) {
    private var voiceOrigin: Pair<String?, Draft>? = null
    val voice = voiceFactory?.invoke(application, viewModelScope, ::receiveTranscript)
        ?: com.alpha.assistant.voice.VoiceSession(application, viewModelScope, ::receiveTranscript)
    private fun receiveTranscript(text: String) {
        if (voice.oneShot) {
            if (voiceOrigin != (snapshot.selectedId to snapshot.draft)) voiceDraftConflict()
        } else if (snapshot.draft.text.isBlank() && snapshot.draft.attachments.isEmpty()) editDraft(snapshot.draft.copy(text = text))
        else voiceDraftConflict()
    }
    private fun voiceDraftConflict() { voice.stop(getApplication<Application>().getString(R.string.voice_draft_changed)) }
    fun startVoice(oneShot: Boolean = true) {
        if (loading || loadFailed || attachmentBusy) return
        voiceOrigin = snapshot.selectedId to snapshot.draft
        voice.start(snapshot.draft.text.isBlank() && snapshot.draft.attachments.isEmpty(), activeConversationId != null, oneShot)
    }
    fun sendVoice() {
        if (!voice.active || !voice.oneShot || voice.phase != "review" || voice.partial.isBlank()) return
        if (voiceOrigin != (snapshot.selectedId to snapshot.draft)) { voiceDraftConflict(); return }
        val text = listOf(snapshot.draft.text, voice.partial.trim()).filter { it.isNotBlank() }.joinToString("\n")
        if (text.length > 16000) { requestError = "Сообщение превышает 16 000 символов. Сократите текст."; return }
        if (loading || loadFailed || attachmentBusy || activeConversationId != null) return
        voice.stop()
        editDraft(snapshot.draft.copy(text = text))
        send()
    }
    val tools = com.alpha.assistant.tools.ToolController(application, viewModelScope, provider, keys,
        com.alpha.assistant.tools.ToolStore(application, if (store.databaseName == "alpha.db") "alpha-tools.db" else "${store.databaseName}-tools.db"))
    var surfaceSequence by mutableStateOf(0); private set
    internal var surfaceDestination by mutableStateOf<ShellDestination?>(null); private set
    internal var surfaceAutomationRequest by mutableStateOf<String?>(null); private set
    /** The model outlives each activity, so it holds the entry until a shell applies it. */
    var surfacePending by mutableStateOf(false); private set
    fun openSurface(toolId: String?, settings: Boolean = false) {
        tools.select(toolId)
        queueSurface(when {
            settings -> ShellDestination.Settings
            toolId != null -> ShellDestination.MiniApps
            else -> null
        })
    }
    internal fun openCreationSurface(destination: ShellDestination, request: String) {
        if (destination == ShellDestination.MiniApps) tools.preparePrompt(request)
        queueSurface(destination, request.takeIf { destination == ShellDestination.Automations && it.isNotBlank() })
    }
    private fun queueSurface(destination: ShellDestination?, automationRequest: String? = null) {
        surfaceDestination = destination
        surfaceAutomationRequest = automationRequest
        surfacePending = true
        surfaceSequence++
    }
    fun consumeSurface() {
        surfacePending = false
        surfaceAutomationRequest = null
    }
    var snapshot by mutableStateOf(ShellSnapshot()); private set
    var loading by mutableStateOf(true); private set
    var loadFailed by mutableStateOf(false); private set
    var saveFailed by mutableStateOf(false); private set
    var keyStatus by mutableStateOf(KeyStatus.Checking); private set
    @get:StringRes var keyFailure by mutableStateOf<Int?>(null); private set
    var keyBusy by mutableStateOf(false); private set
    var requestError by mutableStateOf<String?>(null); private set
    var attachmentBusy by mutableStateOf(false); private set
    var activeConversationId by mutableStateOf<String?>(null); private set
    private data class Active(val conversationId: String, val assistantId: String, val originDraft: Draft, val session: CloudSession = CloudSession(), var job: Job? = null, var weatherDraft: Draft? = null)
    private var active: Active? = null
    private var answerFragment by mutableStateOf("")
    fun activeAnswer(messageId: String): String? = answerFragment.takeIf { active?.assistantId == messageId && it.isNotEmpty() }
    var weatherPermissionId by mutableStateOf<String?>(null); private set
    var weatherLocationBusy by mutableStateOf(false); private set
    private var weatherPermission: CompletableDeferred<Boolean>? = null
    private val weatherForegroundOwners = mutableSetOf<Any>()
    private val weatherForeground get() = weatherForegroundOwners.isNotEmpty()
    fun weatherVisibilityChanged(visible: Boolean, owner: Any) {
        if(visible) weatherForegroundOwners.add(owner) else weatherForegroundOwners.remove(owner)
    }
    fun weatherPermissionResult(id: String, allowed: Boolean) {
        if(weatherPermissionId == id && active?.assistantId == id) {
            val permission = weatherPermission
            weatherPermissionId = null; weatherPermission = null
            permission?.complete(allowed)
        }
    }
    fun weatherHidden(owner: Any) {
        weatherVisibilityChanged(false, owner)
        if(!weatherForeground && active?.weatherDraft != null) cancelRequest()
    }
    private suspend fun resolveWeatherPlace(request: Active, userText: String): WeatherPlace {
        withContext(Dispatchers.Main) {
            currentCoroutineContext().ensureActive()
            if(active !== request || snapshot.selectedId != request.conversationId || snapshot.draft != request.originDraft) {
                if(active === request) cancelRequest()
                throw CancellationException()
            }
            if(!weatherForeground) throw CloudFailure("weather_location", "Откройте Alphy для определения примерной геопозиции или укажите город для прогноза.")
            if(!WeatherRequest.canLocate(userText)) throw CloudFailure("weather_location", WeatherRequest.MANUAL_CITY)
            request.weatherDraft = snapshot.draft
            weatherLocationBusy = true
        }
        try {
            if(!weatherLocator.hasPermission()) {
                val permission = CompletableDeferred<Boolean>()
                withContext(Dispatchers.Main) {
                    currentCoroutineContext().ensureActive()
                    if(active !== request) throw CancellationException()
                    weatherPermission = permission
                    weatherPermissionId = request.assistantId
                }
                val allowed = try { withTimeout(60000) { permission.await() } }
                    catch(timeout: TimeoutCancellationException) { throw CloudFailure("weather_location", "Время ожидания разрешения на геопозицию истекло. Укажите город для прогноза.") }
                if(!allowed || !weatherLocator.hasPermission()) throw CloudFailure("weather_location", "Нет разрешения на примерную геопозицию. Укажите город для прогноза.")
            }
            withContext(Dispatchers.Main) {
                currentCoroutineContext().ensureActive()
                if(!weatherForeground) throw CloudFailure("weather_location", "Откройте Alphy для определения примерной геопозиции или укажите город для прогноза.")
            }
            val place = weatherLocator.locate()
            withContext(Dispatchers.Main) {
                currentCoroutineContext().ensureActive()
                if(active !== request || snapshot.selectedId != request.conversationId || snapshot.draft != request.originDraft) {
                    if(active === request) cancelRequest()
                    throw CancellationException()
                }
                if(!weatherForeground) throw CloudFailure("weather_location", "Откройте Alphy для определения примерной геопозиции или укажите город для прогноза.")
            }
            if(!place.isFresh()) throw CloudFailure("weather_location", WeatherRequest.MANUAL_CITY)
            return place
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                if(weatherPermissionId == request.assistantId) { weatherPermissionId = null; weatherPermission = null }
                if(active === request) weatherLocationBusy = false
            }
        }
    }
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)
    private val deletedConversationIds = mutableSetOf<String>()
    private var historyCleared = false

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
            val result = runCatching {
                val loaded = withContext(Dispatchers.IO) { store.load() }
                loaded.copy(conversations = loaded.conversations.map { conversation ->
                    val messages = conversation.messages.map { message ->
                        var recoveryUnavailable = false
                        val recover = message.status == "generating" || (message.status == "interrupted" && message.errorCode == "tool_storage")
                        val recovered = if(recover && message.toolOperationId != null) {
                            try { tools.recoverAutomatic(message.toolOperationId,conversation.id) }
                            catch(cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { recoveryUnavailable = true; null }
                        } else null
                        if(recoveryUnavailable) message.copy(status = "interrupted",errorCode = "tool_storage",
                            error = "Tool storage is unavailable. The previous widget result could not be checked. Saved data has not been changed.")
                        else if(recovered != null) message.copy(status = "complete",toolId = recovered.first,toolRevision = recovered.second,error = "",errorCode = "")
                        else if (message.role == "assistant" && (message.status in listOf("pending", "streaming", "generating") || recover))
                            message.copy(status = "interrupted", error = "Приложение закрылось до завершения ответа. Можно повторить запрос.", errorCode = "interrupted", searchStatus = if (message.searchStatus == "searching") "error" else message.searchStatus)
                        else message
                    }
                    conversation.copy(messages = messages).also { if (messages != conversation.messages) writes.trySend { store.saveConversation(it) } }
                })
            }
            result.onSuccess { snapshot = it }
            loadFailed = result.isFailure
            loading = false
            refreshKey()
        }
    }
    fun retrySave() {
        val current = snapshot
        val deletedIds = deletedConversationIds.toSet()
        val clearHistory = historyCleared
        writes.trySend {
            store.writableDatabase.beginTransaction()
            try {
                if (clearHistory) store.clearHistory() else deletedIds.forEach(store::deleteConversation)
                current.conversations.forEach(store::saveConversation)
                store.saveNewDraft(current.newDraft)
                store.saveSetting("selected", current.selectedId.orEmpty())
                store.saveSetting("userName", current.userName)
                store.writableDatabase.setTransactionSuccessful()
            } finally { store.writableDatabase.endTransaction() }
        }
        saveFailed = false
    }
    fun select(id: String?) {
        if(active?.weatherDraft != null && id != snapshot.selectedId) cancelRequest()
        voice.stop()
        snapshot = snapshot.copy(selectedId = id)
        writes.trySend { store.saveSetting("selected", id.orEmpty()) }
    }
    fun setUserName(value: String) {
        // A cut between the two halves of a surrogate pair would save half of an emoji.
        val name = value.take(40).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        snapshot = snapshot.copy(userName = name)
        writes.trySend { store.saveSetting("userName", name) }
    }
    fun editDraft(draft: Draft) {
        if(active?.weatherDraft != null && draft != snapshot.draft) cancelRequest()
        if (voice.active && (voice.oneShot || voice.phase in listOf("preparing", "listening", "recognizing")) && draft != snapshot.draft) voiceDraftConflict()
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
    fun renameConversation(id: String, title: String) {
        if (title.isBlank()) return
        val conversation = snapshot.conversations.find { it.id == id } ?: return
        replace(conversation.copy(title = title.trim()))
        writes.trySend { store.renameConversation(id, title) }
    }
    fun deleteConversation(id: String) {
        if (snapshot.conversations.none { it.id == id }) return
        if (activeConversationId == id) cancelRequest()
        if (snapshot.selectedId == id) voice.stop()
        deletedConversationIds += id
        snapshot = snapshot.copy(conversations = snapshot.conversations.filterNot { it.id == id },
            selectedId = snapshot.selectedId.takeUnless { it == id })
        writes.trySend { store.deleteConversation(id) }
    }
    fun clearHistory() {
        cancelRequest()
        voice.stop()
        historyCleared = true
        snapshot = snapshot.copy(conversations = emptyList(), selectedId = null)
        writes.trySend { store.clearHistory() }
    }
    fun refineTool(id: String) {
        val tool = tools.tools.find { it.id == id && !it.archived && it.conversationId == snapshot.selectedId } ?: return
        editDraft(snapshot.draft.copy(text = "Update tool [${tool.id}]: "))
    }
    fun clearRequestError() { requestError = null }
    fun contextDraftKey(): String = snapshot.draft.id
    fun acceptsContext(origin: String): Boolean {
        if (!loading && !loadFailed && origin == contextDraftKey()) return true
        requestError = getApplication<Application>().getString(R.string.context_draft_changed)
        return false
    }
    fun addScreenText(text: String): Boolean = addReviewedText(text, "Текст экрана", contextDraftKey())
    fun addReviewedText(text: String, source: String, origin: String): Boolean {
        if (!acceptsContext(origin)) return false
        if (attachmentBusy) { requestError = getApplication<Application>().getString(R.string.context_wait_for_image_check); return false }
        val attachments = snapshot.draft.attachments
        if (attachments.size >= ImageInput.MAX_IMAGES || attachments.any { it.contextText != null }) {
            requestError = getApplication<Application>().getString(R.string.context_text_attachment_limit)
            return false
        }
        val attachment = runCatching { com.alpha.assistant.surfaces.AssistContext.textAttachment(text, source) }.getOrElse {
            requestError = getApplication<Application>().getString(R.string.context_text_invalid)
            return false
        }
        editDraft(snapshot.draft.copy(attachments = attachments + attachment))
        return true
    }
    fun addAttachment(attachment: Attachment, origin: String = contextDraftKey(), completed: (Boolean) -> Unit = {}) {
        viewModelScope.launch { completed(attachImage(attachment, origin)) }
    }
    internal suspend fun attachImage(attachment: Attachment, origin: String, persistReadAccess: Boolean = false): Boolean {
        if (attachmentBusy || !acceptsContext(origin)) return false
        if (snapshot.draft.attachments.size >= ImageInput.MAX_IMAGES) {
            requestError = getApplication<Application>().getString(R.string.context_attachment_count_limit)
            return false
        }
        val resolver = getApplication<Application>().contentResolver
        attachmentBusy = true
        try {
            val result = withContext(Dispatchers.IO) { runCatching { ImageInput.read(resolver, attachment) } }
            if (!acceptsContext(origin)) return false
            val failure = result.exceptionOrNull()
            if (failure != null) {
                requestError = (failure as? CloudFailure)?.message ?: getApplication<Application>().getString(R.string.context_image_read_failed)
                return false
            }
            if (persistReadAccess) {
                try {
                    resolver.takePersistableUriPermission(Uri.parse(attachment.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    requestError = getApplication<Application>().getString(R.string.context_image_access)
                    return false
                }
            }
            editDraft(snapshot.draft.copy(attachments = snapshot.draft.attachments.filterNot { it.uri == attachment.uri } + attachment))
            return true
        } finally {
            attachmentBusy = false
        }
    }
    fun send(search: Boolean = true) {
        val draft = snapshot.draft
        if (loading || loadFailed || attachmentBusy || (draft.text.isBlank() && draft.attachments.isEmpty())) return
        if (draft.text.length > 16000) { requestError = "Сообщение превышает 16 000 символов. Сократите текст."; return }
        voice.submitted()
        cancelRequest()
        requestError = null
        val now = System.currentTimeMillis()
        val user = Message(UUID.randomUUID().toString(), "user", draft.text.trim(), now, "pending", draft.attachments, search = search)
        val assistant = Message(UUID.randomUUID().toString(), "assistant", "", now, "pending", replyTo = user.id, search = user.search)
        val selected = snapshot.selected
        val updated = if (selected == null) Conversation(UUID.randomUUID().toString(),
            draft.text.trim().take(70).ifBlank { draft.attachments.first().name }, now, now, messages = listOf(user, assistant))
        else selected.copy(updatedAt = now, draft = Draft(), messages = selected.messages + listOf(user, assistant))
        replace(updated)
        val nextNewDraft = if (selected == null) Draft() else snapshot.newDraft
        snapshot = snapshot.copy(selectedId = updated.id, newDraft = nextNewDraft)
        val saved = CompletableDeferred<Boolean>()
        writes.trySend {
            val result = runCatching { if (selected == null) store.createFromNewDraft(updated, nextNewDraft) else store.saveConversation(updated) }
            saved.complete(result.isSuccess)
            result.getOrThrow()
        }
        startRequest(updated.id, user, assistant, saved)
    }
    fun retry(messageId: String, search: Boolean = true) {
        if (active != null) return
        val conversation = snapshot.selected ?: return
        val failed = conversation.messages.lastOrNull() ?: return
        if (failed.id != messageId || failed.status !in listOf("error", "cancelled", "interrupted")) return
        if(failed.toolOperationId != null && failed.errorCode == "tool_storage") {
            requestError = "The previous widget result is unavailable. Restart after tool storage is available to check it before retrying."
            return
        }
        val user = conversation.messages.find { it.id == failed.replyTo } ?: return
        val assistant = Message(UUID.randomUUID().toString(), "assistant", "", System.currentTimeMillis(), "pending", replyTo = user.id, search = search)
        val updated = conversation.copy(messages = conversation.messages + assistant)
        replace(updated)
        val saved = CompletableDeferred<Boolean>()
        writes.trySend { val result = runCatching { store.saveConversation(updated) }; saved.complete(result.isSuccess); result.getOrThrow() }
        startRequest(updated.id, user, assistant, saved)
    }
    private fun startRequest(conversationId: String, user: Message, assistant: Message, saved: CompletableDeferred<Boolean>) {
        val request = Active(conversationId, assistant.id, snapshot.draft)
        active = request; activeConversationId = conversationId
        request.job = viewModelScope.launch {
            try {
                if (!saved.await()) throw CloudFailure("storage", "Запрос не отправлен: не удалось сохранить историю. Повторите сохранение.")
                val key = withContext(Dispatchers.IO) { keys.cloudKey(getApplication()) }
                val images = withContext(Dispatchers.IO) {
                    if (user.attachments.size > ImageInput.MAX_IMAGES) throw CloudFailure("image_count", "Можно отправить не более 4 изображений")
                    user.attachments.filter { it.contextText == null }.map { ImageInput.dataUrl(getApplication<Application>().contentResolver, it) }
                }
                val selectedText = try {
                    com.alpha.assistant.surfaces.AssistContext.requestText("",user.attachments)
                    user.attachments.singleOrNull { it.contextText != null }?.contextText.orEmpty()
                } catch (_: Exception) { throw CloudFailure("context_text","Selected text must be one text attachment within 8,000 characters.") }
                val conversation = snapshot.conversations.find { it.id == conversationId } ?: return@launch
                val toolContext = try {
                    val known = tools.conversationTools(conversationId)
                    known to ChatToolReply.instructions(known,user.text)
                } catch(cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                val knownTools = toolContext?.first
                val toolStorageWarning = "Tool storage is unavailable. Automatic widgets are disabled for this request. Saved data has not been changed."
                val toolInstructions = toolContext?.second ?: ChatToolReply.instructions(emptyList(),user.text,available = false)
                fun parseDecision(decision: ChatToolReply.Decision): ChatToolReply.Reply {
                    if(knownTools == null && decision.kind in listOf("create","refine")) throw CloudFailure("tool_storage",toolStorageWarning)
                    return ChatToolReply.parse(decision,knownTools.orEmpty(),user.text,conversationId)
                }
                val before = conversation.messages.takeWhile { it.id != user.id }
                val complete = before.filter { it.role == "assistant" && it.status == "complete" }.mapNotNull { answer ->
                    before.find { it.id == answer.replyTo }?.let { question -> listOf(CloudTurn("user", question.text.ifBlank { "[Изображение из предыдущего запроса не передаётся]" }), CloudTurn("assistant", answer.text)) }
                }
                var size = user.text.length
                val history = complete.asReversed().take(20).takeWhile { pair -> size += pair.sumOf { it.text.length }; size <= 48000 }.asReversed().flatten()
                updateMessage(request, user.id) { it.copy(status = "sent") }
                updateMessage(request, assistant.id) { it.copy(status = "streaming",
                    error = if(knownTools == null) toolStorageWarning else "",errorCode = if(knownTools == null) "tool_storage" else "") }
                val raw = StringBuilder()
                var terminal: String? = null
                withContext(Dispatchers.IO) {
                    val cloudRequest = CloudRequest(history + CloudTurn("user",user.text,images),assistant.search,toolInstructions,
                        chatDecision = true,selectedText = selectedText,weatherLocation = { resolveWeatherPlace(request,user.text) },
                        validateOperation = { parseDecision(it).takeIf { reply -> reply.kind == "clarify" }?.text })
                    provider.respond(key, cloudRequest, request.session) { event ->
                        withContext(Dispatchers.Main) {
                            if (active === request) {
                                when (event) {
                                    is CloudEvent.Text -> {
                                        terminal = null
                                        if(raw.length + event.value.length > ChatToolReply.MAX_REPLY) throw CloudFailure("response_size","The reply exceeds the allowed size.")
                                        raw.append(event.value)
                                        // Structured decisions remain buffered until the completed round validates.
                                    }
                                    is CloudEvent.AnswerFragment -> {
                                        terminal = null
                                        if(answerFragment.length + event.value.length > ChatToolReply.MAX_TEXT)
                                            throw CloudFailure("response_size","The reply exceeds the allowed size.")
                                        answerFragment += event.value
                                    }
                                    is CloudEvent.RoundComplete -> {
                                        if(event.text.length > ChatToolReply.MAX_REPLY) throw CloudFailure("response_size","The reply exceeds the allowed size.")
                                        terminal = event.text.takeUnless { event.hasToolCalls }
                                        raw.clear()
                                    }
                                    is CloudEvent.Search -> updateMessage(request, assistant.id) { it.copy(searchStatus = if (event.status == "empty" && it.sources.isNotEmpty()) "found" else event.status, sources = (it.sources + event.sources).distinctBy { source -> source.url }) }
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive(); request.session.check()
                if(active !== request) return@launch
                val completed = terminal ?: throw CloudFailure("incomplete","The reply has no completed answer. Retry the request.")
                val decoded = ChatToolReply.decode(completed)
                if(answerFragment.isNotEmpty() && decoded.kind != "answer")
                    throw CloudFailure("decision_changed","The assistant changed the requested operation after streaming an answer.")
                val parsed = parseDecision(decoded)
                val sources = snapshot.conversations.find { it.id == conversationId }?.messages?.find { it.id == assistant.id }?.sources.orEmpty()
                if(parsed.search == "required" && !assistant.search) throw CloudFailure("search_disabled","This request requires web search, but search is disabled.")
                if(parsed.search == "required" && sources.isEmpty()) throw CloudFailure("search_required","No validated search sources were returned. The requested facts could not be verified.")
                if(parsed.intent == null) updateMessage(request, assistant.id) { it.copy(text = parsed.text,status = "complete") }
                else {
                    val prompt = ChatToolReply.generationPrompt(
                        user.text,parsed.intent.brief,parsed.text,sources,selectedText)
                    tools.generateAutomatic(prompt,parsed.intent.target,conversationId,assistant.id,key,images,request.session,
                        cancelRequest = ::cancelRequest,
                        prepared = {
                            val persisted = CompletableDeferred<Boolean>()
                            val progress = if (parsed.kind == "refine") R.string.chat_app_update_progress else R.string.chat_app_create_progress
                            updateMessage(request, assistant.id, persist = false) {
                                it.copy(text = getApplication<Application>().getString(progress), status = "generating", toolOperationId = assistant.id)
                            }
                            val pending = snapshot.conversations.find { it.id == conversationId } ?: throw CancellationException()
                            writes.trySend {
                                val result = runCatching { store.saveConversation(pending) }
                                persisted.complete(result.isSuccess); result.getOrThrow()
                            }
                            if(!persisted.await()) throw CloudFailure("storage","The widget was not created because its request could not be saved.")
                        },
                        canCommit = { active === request && snapshot.conversations.any { c -> c.id == conversationId && c.messages.any { it.id == assistant.id && it.status == "generating" } } },
                        committed = { tool -> updateMessage(request,assistant.id) { it.copy(text = parsed.text,status = "complete",toolId = tool.id,toolRevision = tool.revision) } })
                }
                if(active === request) snapshot.conversations.find { it.id == conversationId }?.messages?.find { it.id == assistant.id }?.let { voice.reply(it.text) }
            } catch (_: CancellationException) {
                // cancelRequest persists the terminal state before it permits another request.
            } catch (failure: Exception) {
                if(active !== request) return@launch
                voice.requestFailed()
                val known = failure as? CloudFailure
                updateMessage(request, user.id) { if (it.status == "pending") it.copy(status = "not_sent") else it }
                updateMessage(request, assistant.id) { it.copy(status = "error", errorCode = known?.code ?: "network", error = known?.message ?: if(it.toolOperationId != null) "The widget could not be created safely. Existing tools and data are unchanged. Retry the request." else "Нет соединения с DeepSeek. Проверьте сеть и повторите запрос.", searchStatus = if (it.searchStatus == "searching") "error" else it.searchStatus) }
            } finally {
                if (active === request) clearActiveRequest()
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
        weatherLocationBusy = false
        if(weatherPermissionId == request.assistantId) {
            weatherPermission?.cancel(); weatherPermission = null; weatherPermissionId = null
        }
        val answer = snapshot.conversations.find { it.id == request.conversationId }?.messages?.find { it.id == request.assistantId }
        // A completed commit stays complete while its tool list refreshes.
        if(answer?.status == "complete") {
            clearActiveRequest()
            request.job?.cancel()
            return
        }
        voice.requestFailed()
        val partial = answerFragment.takeIf { it.isNotBlank() }.orEmpty()
        updateMessage(request, request.assistantId) {
            it.copy(text = partial, status = "cancelled", errorCode = "cancelled",
                error = if(partial.isEmpty()) "Запрос остановлен." else "Запрос остановлен. Ответ не завершён.",
                searchStatus = if (it.searchStatus == "searching") "error" else it.searchStatus)
        }
        val userId = snapshot.conversations.find { it.id == request.conversationId }?.messages?.find { it.id == request.assistantId }?.replyTo
        if (userId != null) updateMessage(request, userId) { if (it.status == "pending") it.copy(status = "not_sent") else it }
        clearActiveRequest()
        request.job?.cancel()
        request.session.cancel()
    }
    private fun clearActiveRequest() {
        answerFragment = ""
        active = null
        activeConversationId = null
    }
    override fun onCleared() {
        weatherPermission?.cancel()
        voice.close()
        tools.close()
        active?.session?.let { session -> Thread { session.cancel() }.start() }
        writes.close()
        super.onCleared()
    }
    private fun replace(conversation: Conversation) {
        snapshot = snapshot.copy(conversations = (snapshot.conversations.filterNot { it.id == conversation.id } + conversation).sortedByDescending { it.updatedAt })
    }
    fun saveKey(value: String, done: (Boolean) -> Unit) = keyAction(R.string.key_save_failed, { keys.save(value) }, done)
    fun acceptConsent() = keyAction(R.string.consent_failed, keys::recordConsent)
    // A running request keeps its key in memory, so the cloud work stops before the key goes.
    fun clearKey() {
        if (keyBusy) return
        cancelRequest()
        tools.cancel()
        keyAction(R.string.key_delete_failed, keys::clear)
    }
    private fun keyAction(@StringRes failure: Int, action: () -> Result<Unit>, done: (Boolean) -> Unit = {}) {
        if (keyBusy) return
        keyBusy = true
        keyFailure = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            if (result.isSuccess) refreshKey() else keyFailure = failure
            keyBusy = false
            done(result.isSuccess)
        }
    }
    private suspend fun refreshKey() {
        keyStatus = withContext(Dispatchers.IO) {
            val result = keys.read()
            when {
                result.isFailure -> KeyStatus.Unreadable
                result.getOrNull() == null -> KeyStatus.Missing
                !keys.consented() -> KeyStatus.NeedsConsent
                else -> KeyStatus.Ready
            }
        }
    }
}

enum class KeyStatus { Checking, Missing, NeedsConsent, Ready, Unreadable }
