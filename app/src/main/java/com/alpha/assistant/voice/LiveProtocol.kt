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
    data class InputTranscript(val delta: String, val startMs: Long, val endMs: Long, val eventId: String? = null) : LiveEvent
    data class OutputTranscript(val delta: String, val startMs: Long, val endMs: Long, val eventId: String? = null) : LiveEvent
    data class Delegation(val id: String, val offsetMs: Long) : LiveEvent
    data class Appended(val clientEventId: String) : LiveEvent
    data class Closed(val reason: String?) : LiveEvent
    /** [clientEventId]: the id of the sent event that failed. [code] and [param] name what failed, also with no id (plan "one scene", K8). */
    data class Failure(val message: String, val clientEventId: String?, val code: String = "", val param: String = "") : LiveEvent
    /** A classifier answer for the caption piece [sourceEventId] of the side [input] (plan "one scene", K1). */
    data class Decision(val sourceEventId: String, val input: Boolean, val answers: List<ClassifierAnswer>, val inputTokens: Int) : LiveEvent
    data class ItemCreated(val clientEventId: String, val itemId: String) : LiveEvent
    data object Other : LiveEvent
}

/** One answer of a classifier question: the chosen value and its probability. */
internal data class ClassifierAnswer(val name: String, val choice: String, val confidence: Double)

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
После этого текста замолчи и жди: не объясняй и не продолжай сам, даже если ответ кажется неполным.
Слово «Дословно» — служебная метка. Никогда не произноси его вслух и не начинай с него свои фразы.
Текст без метки скажи коротко на языке разговора. Не добавляй кнопок, действий, предложений и вопросов, которых в нём нет.
Никогда не говори, что действие выполнено, пока это не подтверждено.
Когда тебе предлагают действие, прочитай предложение и попроси сказать «да» или «нет». Никогда не говори, что действие одобрено или запущено.
Если нужно подтверждение на экране, назови кнопку, как в тексте, и никогда не проси сказать «да» или «нет».
Ты не видишь экран. Любой вопрос об экране передавай через делегирование."""

    fun newId() = "event_" + UUID.randomUUID().toString().replace("-", "")

    /** [triggers]: the classifier triggers of plan "one scene", K1, or null for a start with no classifier (K8). */
    fun start(history: List<Message>, triggers: JSONArray? = null): String = JSONObject()
        .put("type", "session.start").put("event_id", newId())
        .put("session", JSONObject()
            .put("model", OPENAI_LIVE_MODEL)
            .put("instructions", INSTRUCTIONS)
            .put("input", history(history))
            .put("audio", JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", LIVE_RATE))
                .put("output", JSONObject().put("voice", OPENAI_LIVE_VOICE)))
            .apply { if (triggers != null) put("triggers", triggers) })
        .toString()

    /** The largest step count of a steps control of the kit (BUDGET.options of kit.js), so the step choices are 1 to this and none. */
    const val MAX_STEPS = 6
    const val USER_COMMAND = "user_command"
    const val USER_STEP = "user_step"
    const val SPOKEN_STEP = "spoken_step"
    private const val INPUT_DELTA = "session.input_transcript.delta"
    private const val OUTPUT_DELTA = "session.output_transcript.delta"

    /** The two classifier triggers of plan "one scene", K1, with the questions of the decisions probe of 2026-11-22. */
    fun triggers(): JSONArray {
        val steps = JSONArray().apply { (1..MAX_STEPS).forEach { put(JSONObject().put("value", "$it")) }; put(JSONObject().put("value", "none")) }
        fun question(name: String, instructions: String, choices: JSONArray) =
            JSONObject().put("type", "choice").put("name", name).put("instructions", instructions).put("choices", choices)
        fun trigger(event: String, vararg questions: JSONObject) = JSONObject().put("type", "decisions").put("event_type", event)
            .put("decisions", JSONObject().put("questions", JSONArray(questions.toList())))
        val commands = JSONArray().apply { listOf("next", "back", "repeat", "go_to", "none").forEach { put(JSONObject().put("value", it)) } }
        return JSONArray()
            .put(trigger(INPUT_DELTA,
                question(USER_COMMAND, "The user speaks to the assistant about the scene. Choose what the user's latest words ask the screen to do. " +
                    "next: show the next step. back: show the previous step. repeat: say the present step again. " +
                    "go_to: show a step that the user names by its number, its label, or its place (the first, the last, the start). " +
                    "none: everything else, such as a question about the subject, a new topic, small talk, or unfinished words.", commands),
                question(USER_STEP, "Choose the number of the scene step that the user's latest words are about: a step that the user names, " +
                    "or the step that the user's question concerns. Choose none when no step fits.", steps)))
            .put(trigger(OUTPUT_DELTA,
                question(SPOKEN_STEP, "The assistant speaks about the scene. Choose the number of the scene step that the assistant's latest words describe. " +
                    "Choose none when the latest words describe no step of the scene, or when the words are too few to tell.", steps)))
    }

    /** The scene item of the classifier (K2): a user message with [text]. */
    fun itemCreate(eventId: String, text: String): String = JSONObject().put("type", "decision.item.create").put("event_id", eventId)
        .put("item", JSONObject().put("type", "message").put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))).toString()

    fun itemDelete(eventId: String, itemId: String): String =
        JSONObject().put("type", "decision.item.delete").put("event_id", eventId).put("item_id", itemId).toString()

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

    /** Quiet context without an open delegation (spec 5.7.2), or for [delegationId] (an empty append for a turn that the host answered, K3). */
    fun thinking(eventId: String, text: String, delegationId: String? = null): String = JSONObject()
        .put("type", "session.thinking.append").put("event_id", eventId)
        .put("delegation_id", delegationId ?: JSONObject.NULL).put("content", text).toString()

    fun mute(eventId: String) = simple("session.input_audio.mute", eventId)
    fun unmute(eventId: String) = simple("session.input_audio.unmute", eventId)
    fun close() = simple("session.close", newId())

    private fun simple(type: String, eventId: String) = JSONObject().put("type", type).put("event_id", eventId).toString()

    fun parse(text: String): LiveEvent = try {
        val e = JSONObject(text)
        when (e.optString("type")) {
            "session.started" -> LiveEvent.Started(e.getJSONObject("session").getString("id"))
            "session.output_audio.delta" -> LiveEvent.OutputAudio(pcm(e.getString("delta")))
            INPUT_DELTA -> LiveEvent.InputTranscript(e.getString("delta"), e.optLong("start_ms"), e.optLong("end_ms"), e.optString("event_id").ifEmpty { null })
            OUTPUT_DELTA -> LiveEvent.OutputTranscript(e.getString("delta"), e.optLong("start_ms"), e.optLong("end_ms"), e.optString("event_id").ifEmpty { null })
            "decision.event" -> decision(e)
            "decision.item.created" -> LiveEvent.ItemCreated(e.getString("client_event_id"), e.getJSONObject("item").getString("id"))
            "session.delegation.created" ->
                LiveEvent.Delegation(e.getJSONObject("delegation").getString("id"), e.optLong("offset_ms"))
            "session.commentary.appended" -> LiveEvent.Appended(e.getString("client_event_id"))
            "session.closed" -> LiveEvent.Closed(e.optString("reason").ifEmpty { null })
            // The reference names the failed client event in client_event_id. An older shape put it in error.event_id.
            "error" -> e.optJSONObject("error").let {
                val id = listOf(it?.optString("client_event_id"), e.optString("client_event_id"), it?.optString("event_id")).firstOrNull { id -> !id.isNullOrEmpty() }
                LiveEvent.Failure(it?.optString("message")?.ifEmpty { null } ?: "Ошибка голосового режима", id,
                    it?.optString("code").orEmpty(), it?.optString("param").orEmpty())
            }
            else -> LiveEvent.Other
        }
    } catch (_: Exception) {
        LiveEvent.Other
    }

    /** A completed decision of a trigger. Confidence is the probability of the chosen value. Any other decision event is [LiveEvent.Other]. */
    private fun decision(e: JSONObject): LiveEvent {
        val event = e.getJSONObject("event")
        if (event.optString("type") != "decision.completed") return LiveEvent.Other
        val decision = event.getJSONObject("decision")
        // An answer with no choice (a refusal) is skipped, and the other answers stay.
        val answers = decision.getJSONArray("answers").let { list -> List(list.length()) { list.getJSONObject(it) } }.filter { !it.isNull("choice") }.map { answer ->
            val choice = answer.get("choice").toString()
            val probabilities = answer.optJSONArray("probabilities")
            val confidence = (0 until (probabilities?.length() ?: 0)).map { probabilities!!.getJSONObject(it) }
                .firstOrNull { it.get("value").toString() == choice }?.optDouble("probability") ?: answer.optDouble("confidence", 0.0)
            ClassifierAnswer(answer.getString("name"), choice, confidence)
        }
        return LiveEvent.Decision(e.getString("source_event_id"), e.optString("source_event_type") == INPUT_DELTA, answers,
            decision.optJSONObject("usage")?.optInt("input_tokens") ?: 0)
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
