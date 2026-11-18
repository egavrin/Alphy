package com.alpha.assistant.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Message
import com.alpha.assistant.surfaces.AssistCapture
import com.alpha.assistant.voice.LiveProtocol
import com.alpha.assistant.voice.LiveVoiceHost
import com.alpha.assistant.voice.LiveVoiceSession
import com.alpha.assistant.voice.UserTurn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The chat side of the voice mode (spec 5.5 to 5.8). Each hand-off goes to the chat agent as a normal message, and its result goes
 * to GPT-Live once, by its delegation id. A request outlives the mode (spec 5.5.9), so an end stops only the watch.
 */
internal class LiveVoiceBridge(
    private val model: ShellViewModel,
    private val session: LiveVoiceSession,
    // The first hand-off of an assistant button press carries the screen context of this invocation (spec 5.11.4, Task 9).
    private val assistantInvocation: String?,
    private val capture: AssistCapture?,
) : LiveVoiceHost {
    /** The delegation id to the assistant message id of each hand-off (spec 5.5.4). */
    val handOffs = LinkedHashMap<String, String>()
    /** The assistant message id to the user text of each typed turn (spec 5.7). */
    private val typed = LinkedHashMap<String, String>()
    private val progressed = HashSet<String>()
    private val done = HashSet<String>()
    /** The assistant message of the latest hand-off or typed turn (spec 5.2.3). */
    var cardId by mutableStateOf<String?>(null); private set
    private var watch: Job? = null

    val working: Boolean get() = (handOffs.values + typed.keys).any { message(it)?.status in unfinishedStatuses }

    fun start(firstSpeech: Boolean) {
        session.start(this, firstSpeech)
        watch = model.viewModelScope.launch {
            // The session reads the account only at its start, so a later provider change or key removal ends it here (spec 5.8).
            launch {
                snapshotFlow { model.accountProvider }.drop(1).collect {
                    if (it != AiProvider.OPENAI) session.stop(model.getApplication<Application>().getString(R.string.live_voice_openai_only))
                }
            }
            snapshotFlow { model.snapshot }.collect { report() }
        }
    }

    fun typed(messageId: String, text: String) {
        typed[messageId] = text
        cardId = messageId
    }

    override fun history(): List<Message> = model.snapshot.selected?.messages.orEmpty()

    override fun delegation(id: String, request: String) {
        if (request.isBlank()) { session.commentary(id, NOT_HEARD); return }
        // shortcut: every refusal gets the busy text. A spoken request reaches only the full queue or a busy «+» attachment.
        val messageId = model.submitDraft(Draft(request), search = true) ?: run { session.commentary(id, BUSY); return }
        handOffs[id] = messageId
        cardId = messageId
    }

    override fun turn(turn: UserTurn) = Unit
    override fun handOffRunning() = working
    override fun appended(eventId: String) = Unit
    override fun outputEnded(endMs: Long) = Unit
    override fun ended(status: String, settingsRequired: Boolean) { watch?.cancel(); watch = null }

    /** Sends the mini-app progress and the result of each tracked message once (spec 5.5.5 to 5.5.8, 5.7.2). */
    private fun report() {
        for ((delegation, id) in handOffs) {
            val message = changed(id) ?: continue
            session.commentary(delegation, when {
                message.status == "generating" -> MINI_APP_STARTED
                message.status != "complete" -> FAILED + message.error.take(MAX - FAILED.length)
                message.refinementChoices.isNotEmpty() -> ON_SCREEN
                message.toolId != null -> spoken(MINI_APP_READY, message.text)
                else -> spoken(ANSWER, message.text)
            })
        }
        for ((id, text) in typed) {
            val message = changed(id)?.takeIf { it.status == "complete" } ?: continue
            session.thinking(spoken("Пользователь написал: ${LiveProtocol.spoken(text, TYPED_MAX)} Ответ Alphy на экране: ", message.text))
        }
    }

    /** The message when it enters `generating` or ends, once for each run. A deleted message ends without a result. */
    private fun changed(id: String): Message? {
        val message = message(id)
        if (message == null || message.status !in unfinishedStatuses) return message.takeIf { done.add(id) }
        // A refinement choice or a retry runs the same message again, and its new result goes to the same delegation (ruling R14).
        if (done.remove(id)) progressed -= id
        return message.takeIf { it.status == "generating" && progressed.add(id) }
    }

    private fun message(id: String) = model.snapshot.conversations.firstNotNullOfOrNull { c -> c.messages.find { it.id == id } }

    /** [prefix] and the speakable [reply] within the limit of one append (spec 5.5.5). */
    private fun spoken(prefix: String, reply: String) = prefix + LiveProtocol.spoken(reply, MAX - prefix.length)

    private companion object {
        const val MAX = 1200
        /** The typed text of a thinking append. The rest of the limit is for the reply. */
        const val TYPED_MAX = 400
        const val NOT_HEARD = "Alphy не расслышал запрос."
        const val BUSY = "Alphy ещё занят предыдущими запросами."
        const val ANSWER = "Ответ Alphy: "
        const val MINI_APP_STARTED = "Alphy готовит мини-приложение, оно появится на экране."
        const val MINI_APP_READY = "Мини-приложение готово. Ответ Alphy: "
        const val FAILED = "Alphy не смог выполнить запрос: "
        const val ON_SCREEN = "Нужно подтверждение на экране."
    }
}
