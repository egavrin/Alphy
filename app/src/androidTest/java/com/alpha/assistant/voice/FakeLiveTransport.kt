package com.alpha.assistant.voice

import android.util.Base64
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.providerFailure
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Server events that a test plays on [FakeLiveTransport]. Times are session milliseconds. */
internal const val LIVE_STARTED = """{"type":"session.started","session":{"id":"live_1"}}"""
internal fun inputDelta(delta: String, startMs: Long, endMs: Long) = liveTranscript("session.input_transcript.delta", delta, startMs, endMs)
internal fun outputDelta(delta: String, startMs: Long, endMs: Long) = liveTranscript("session.output_transcript.delta", delta, startMs, endMs)
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
private fun liveTranscript(type: String, delta: String, startMs: Long, endMs: Long) =
    JSONObject().put("type", type).put("delta", delta).put("start_ms", startMs).put("end_ms", endMs).toString()

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
