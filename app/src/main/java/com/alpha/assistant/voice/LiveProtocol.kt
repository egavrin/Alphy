package com.alpha.assistant.voice

import android.util.Base64
import com.alpha.assistant.cloud.OPENAI_LIVE_MODEL
import com.alpha.assistant.cloud.OPENAI_LIVE_VOICE
import com.alpha.assistant.data.Message
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** A server event of the GPT-Live socket (spec 5.3 and 5.5). */
internal sealed interface LiveEvent {
    data class Started(val sessionId: String) : LiveEvent
    class OutputAudio(val samples: ShortArray) : LiveEvent
    data class InputTranscript(val delta: String, val startMs: Long, val endMs: Long) : LiveEvent
    data class OutputTranscript(val delta: String, val startMs: Long, val endMs: Long) : LiveEvent
    data class Delegation(val id: String, val offsetMs: Long) : LiveEvent
    data class Appended(val clientEventId: String) : LiveEvent
    data class Closed(val reason: String?) : LiveEvent
    data class Failure(val message: String, val clientEventId: String?) : LiveEvent
    data object Other : LiveEvent
}

/** Pure builders and parser for the GPT-Live wire protocol. No network. */
internal object LiveProtocol {
    private const val HISTORY_MESSAGES = 128
    /** 8,192 tokens at 3 characters each, with a margin because Cyrillic tokenizes worse (spec 5.3.4). */
    private const val TOKEN_MARGIN = 0.75
    private val HISTORY_CHARS = (8192 * 3 * TOKEN_MARGIN).toInt()
    private val SENTENCE_ENDS = setOf('.', '!', '?', '…')

    /** The mark before a text of Alphy: the voice says the text after it word for word (plan of 2026-11-22, decision 7). */
    const val MARK = "Дословно: "

    const val INSTRUCTIONS = """Ты голосовой режим помощника. Говори коротко и естественно, на языке последней просьбы человека. Обращайся к человеку на «ты».
Ты сам не выполняешь действия и не знаешь свежих фактов. Любую просьбу о действии на телефоне, поиске, свежих фактах, погоде, мини-приложении, памяти, файлах или о том, что на экране, сразу передавай через делегирование.
Если ответ станет понятнее с картинкой, схемой или моделью, которую человек может менять (как что-то устроено, как что-то движется, урок с проверкой), передай просьбу через делегирование. Примеры: «объясни, как образуется радуга», «как устроен атом», «научи меня таблице умножения на семь».
Простой вопрос с коротким ответом (факт, определение, совет, разговор) отвечай сам сразу и ничего не передавай. Примеры: «сколько планет в Солнечной системе», «кто написал „Войну и мир“», «как дела».
Просьбу изменить экран, продолжить урок или проверить себя передавай через делегирование.
Ответ человека на вопрос проверки, который ты только что произнёс, тоже передавай через делегирование.
Не задавай уточняющих вопросов о задаче сам.
Пока ответ готовится, скажи одну короткую фразу ожидания. Не говори, кто работает. Не называй делегирование, запрос или мини-приложение.
Если в тексте есть метка «Дословно:», произнеси только текст после метки, слово в слово и один раз: без вступления, без своих слов, без повторов, без вопросов и без предложений.
Текст без метки скажи коротко на языке разговора. Не добавляй кнопок, действий, предложений и вопросов, которых в нём нет.
Никогда не говори, что действие выполнено, пока это не подтверждено.
Когда тебе предлагают действие, прочитай предложение и попроси сказать «да» или «нет». Никогда не говори, что действие одобрено или запущено.
Если нужно подтверждение на экране, назови кнопку, как в тексте, и никогда не проси сказать «да» или «нет».
Ты не видишь экран. Любой вопрос об экране передавай через делегирование."""

    fun newId() = "event_" + UUID.randomUUID().toString().replace("-", "")

    fun start(history: List<Message>): String = JSONObject()
        .put("type", "session.start").put("event_id", newId())
        .put("session", JSONObject()
            .put("model", OPENAI_LIVE_MODEL)
            .put("instructions", INSTRUCTIONS)
            .put("input", history(history))
            .put("audio", JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", LIVE_RATE))
                .put("output", JSONObject().put("voice", OPENAI_LIVE_VOICE))))
        .toString()

    /**
     * The newest finished text messages inside the documented limits, oldest first. The chat saves a sent request as `sent`, so a
     * user message counts at `sent` or `complete` (ruling R13). The item format is unverified until the live check.
     */
    fun history(messages: List<Message>): JSONArray {
        val kept = ArrayList<Message>()
        var chars = INSTRUCTIONS.length
        for (m in messages.asReversed()) {
            val finished = when (m.role) {
                "user" -> m.status == "sent" || m.status == "complete"
                "assistant" -> m.status == "complete"
                else -> false
            }
            if (!finished || m.text.isBlank()) continue
            if (kept.size >= HISTORY_MESSAGES) break
            if (chars + m.text.length > HISTORY_CHARS) continue
            chars += m.text.length
            kept += m
        }
        return JSONArray().also { out ->
            kept.asReversed().forEach { m ->
                val part = if (m.role == "user") "input_text" else "output_text"
                out.put(JSONObject().put("type", "message").put("role", m.role)
                    .put("content", JSONArray().put(JSONObject().put("type", part).put("text", m.text))))
            }
        }
    }

    fun audio(samples: ShortArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        return JSONObject().put("type", "session.input_audio.append")
            .put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString()
    }

    fun commentary(eventId: String, delegationId: String, text: String): String = JSONObject()
        .put("type", "session.commentary.append").put("event_id", eventId)
        .put("delegation_id", delegationId).put("content", text).toString()

    /** Quiet context without an open delegation (spec 5.7.2). */
    fun thinking(eventId: String, text: String): String = JSONObject()
        .put("type", "session.thinking.append").put("event_id", eventId)
        .put("delegation_id", JSONObject.NULL).put("content", text).toString()

    fun mute(eventId: String) = simple("session.input_audio.mute", eventId)
    fun unmute(eventId: String) = simple("session.input_audio.unmute", eventId)
    fun close() = simple("session.close", newId())

    private fun simple(type: String, eventId: String) = JSONObject().put("type", type).put("event_id", eventId).toString()

    fun parse(text: String): LiveEvent = try {
        val e = JSONObject(text)
        when (e.optString("type")) {
            "session.started" -> LiveEvent.Started(e.getJSONObject("session").getString("id"))
            "session.output_audio.delta" -> LiveEvent.OutputAudio(pcm(e.getString("delta")))
            "session.input_transcript.delta" ->
                LiveEvent.InputTranscript(e.getString("delta"), e.optLong("start_ms"), e.optLong("end_ms"))
            "session.output_transcript.delta" ->
                LiveEvent.OutputTranscript(e.getString("delta"), e.optLong("start_ms"), e.optLong("end_ms"))
            "session.delegation.created" ->
                LiveEvent.Delegation(e.getJSONObject("delegation").getString("id"), e.optLong("offset_ms"))
            "session.commentary.appended" -> LiveEvent.Appended(e.getString("client_event_id"))
            "session.closed" -> LiveEvent.Closed(e.optString("reason").ifEmpty { null })
            "error" -> e.optJSONObject("error").let {
                LiveEvent.Failure(it?.optString("message")?.ifEmpty { null } ?: "Ошибка голосового режима", it?.optString("event_id")?.ifEmpty { null })
            }
            else -> LiveEvent.Other
        }
    } catch (_: Exception) {
        LiveEvent.Other
    }

    private fun pcm(base64: String): ShortArray {
        val buffer = ByteBuffer.wrap(Base64.decode(base64, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(buffer.remaining() / 2) { buffer.short }
    }

    /** Speakable text cut at a sentence end at or below [max]. Without one, it cuts at a word boundary and adds a period. */
    fun spoken(markdown: String, max: Int = 1200): String {
        val text = speakableText(markdown).trim()
        if (text.length <= max && text.lastOrNull() in SENTENCE_ENDS) return text
        if (text.length > max) {
            val end = sentenceEnd.findAll(text.take(max + 1)).lastOrNull { it.range.first > 0 }
            if (end != null) return text.substring(0, end.range.first)
        }
        if (text.isEmpty()) return ""
        val room = text.take(max - 1)
        val cut = if (text.length > max - 1) room.substringBeforeLast(' ', room) else room
        return cut.trimEnd(',', ';', ':', ' ') + "."
    }
}
