package com.alpha.assistant.voice

import android.util.Base64
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.providerFailure
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Server events that a test plays on [FakeLiveTransport]. Times are session milliseconds. */
internal const val LIVE_STARTED = """{"type":"session.started","session":{"id":"live_1"}}"""
internal fun inputDelta(delta: String, startMs: Long, endMs: Long, eventId: String? = null) = liveTranscript("session.input_transcript.delta", delta, startMs, endMs, eventId)
internal fun outputDelta(delta: String, startMs: Long, endMs: Long, eventId: String? = null) = liveTranscript("session.output_transcript.delta", delta, startMs, endMs, eventId)
/** A classifier answer of [liveDecision]: [name] chose [choice] with the probability [confidence]. */
internal fun classified(name: String, choice: String, confidence: Double) = Triple(name, choice, confidence)
/** A completed decision of the triggers for the transcript piece [sourceId]. [input] tells an input piece from an output piece. */
internal fun liveDecision(sourceId: String, input: Boolean, vararg answers: Triple<String, String, Double>, inputTokens: Int = 120): String =
    JSONObject().put("type", "decision.event").put("source_event_id", sourceId)
        .put("source_event_type", if (input) "session.input_transcript.delta" else "session.output_transcript.delta")
        .put("event", JSONObject().put("type", "decision.completed").put("decision", JSONObject()
            .put("answers", org.json.JSONArray(answers.map { (name, choice, confidence) ->
                JSONObject().put("name", name).put("choice", choice).put("confidence", 0.0)
                    .put("probabilities", org.json.JSONArray().put(JSONObject().put("value", choice).put("probability", confidence)))
            }))
            .put("usage", JSONObject().put("input_tokens", inputTokens)))).toString()
/** The server answer to the item create [clientId]: the new item is [itemId]. */
internal fun liveItemCreated(clientId: String, itemId: String) =
    JSONObject().put("type", "decision.item.created").put("client_event_id", clientId).put("item", JSONObject().put("id", itemId)).toString()
internal fun liveDelegation(id: String, offsetMs: Long) =
    JSONObject().put("type", "session.delegation.created").put("offset_ms", offsetMs).put("delegation", JSONObject().put("id", id)).toString()
/** Voiced output audio: [samples] samples of [marker] × 1,000, so a test can tell the chunks apart. */
internal fun outputAudio(marker: Int, samples: Int = 4) = outputChunk(ShortArray(samples) { (marker * 1_000).toShort() })
/** The sample value of [silentAudio]. Its level is far below the voiced level of the output stream. */
internal const val SILENT_SAMPLE: Short = 100
/** 100 ms of the near-silent audio that GPT-Live streams between replies (ruling R24). */
internal fun silentAudio() = outputChunk(ShortArray(LIVE_RATE / 10) { (if (it % 2 == 0) SILENT_SAMPLE else -SILENT_SAMPLE).toShort() })
/** An output chunk has the PCM16 encoding of an input append, which `audioIsBase64LittleEndian` pins. */
private fun outputChunk(samples: ShortArray) = JSONObject().put("type", "session.output_audio.delta")
    .put("delta", JSONObject(LiveProtocol.audio(samples)).getString("audio")).toString()
private fun liveTranscript(type: String, delta: String, startMs: Long, endMs: Long, eventId: String?) =
    JSONObject().put("type", type).put("delta", delta).put("start_ms", startMs).put("end_ms", endMs).apply { eventId?.let { put("event_id", it) } }.toString()

/** A scripted GPT-Live socket: it records what the session sends, and the test plays the server side. */
internal class FakeLiveTransport : LiveTransport {
    val sent = CopyOnWriteArrayList<String>()
    val closes = AtomicInteger()
    @Volatile var url = ""; private set
    @Volatile var key = ""; private set
    @Volatile private var listener: LiveListener? = null

    val connected get() = listener != null
    /** The `type` of each sent text, in order. */
    fun types() = sent.map { JSONObject(it).getString("type") }
    /** The sent commentary appends as (delegation id, text), in order. */
    fun commentary() = sent.map(::JSONObject).filter { it.getString("type") == "session.commentary.append" }
        .map { it.getString("delegation_id") to it.getString("content") }
    /** The sent texts of [type], in order. */
    fun sentOf(type: String) = sent.map(::JSONObject).filter { it.getString("type") == type }
    /** The triggers of the last `session.start`, or null when it sent none. */
    fun triggers() = sentOf("session.start").lastOrNull()?.getJSONObject("session")?.optJSONArray("triggers")
    /** The text of each sent item create, as (event id, text). */
    fun itemCreates() = sentOf("decision.item.create").map { it.getString("event_id") to it.getJSONObject("item").getJSONArray("content").getJSONObject(0).getString("text") }
    /** The item id of each sent item delete. */
    fun itemDeletes() = sentOf("decision.item.delete").map { it.getString("item_id") }
    /** The sent thinking appends as (delegation id or empty, text), in order. */
    fun thinking() = sentOf("session.thinking.append").map { (if (it.isNull("delegation_id")) "" else it.getString("delegation_id")) to it.getString("content") }
    /** For each sent input audio append, in order: whether it holds a sample that is not zero. */
    fun inputSounds() = sent.map(::JSONObject).filter { it.getString("type") == "session.input_audio.append" }
        .map { append -> Base64.decode(append.getString("audio"), Base64.DEFAULT).any { it != 0.toByte() } }

    override fun connect(url: String, key: String, listener: LiveListener): LiveConnection {
        this.url = url; this.key = key; this.listener = listener
        return object : LiveConnection {
            override fun send(text: String): Boolean { sent += text; return true }
            override fun close() { closes.incrementAndGet() }
        }
    }

    fun emit(json: String) = checkNotNull(listener).onText(json)
    /** As the real transport: a refused upgrade gives the provider text, a socket failure a fixed text. */
    fun fail(status: Int?) = checkNotNull(listener).onFailure(status,
        if (status != null) providerFailure(AiProvider.OPENAI, status, null, false).message else "Нет соединения с голосовым режимом.")
    fun closeRemote() = checkNotNull(listener).onClosed()
    /** The user says [text] at [atMs] of the session timeline, and GPT-Live hands it to Alphy as [id]. */
    fun say(text: String, id: String, atMs: Long) {
        emit(inputDelta(text, atMs, atMs + 500))
        emit(liveDelegation(id, atMs + 600))
    }
}
