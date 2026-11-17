package com.alpha.assistant.voice

import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudHttp
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.bearerHeaders
import com.alpha.assistant.cloud.checked
import com.alpha.assistant.cloud.reply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private val transcriptionHttp = CloudHttp(null, 60_000)
private const val MAX_TRANSCRIPT_RESPONSE = 64 * 1024

/**
 * Voice input through the provider of the account (spec 12A.2). One utterance goes as one WAV file in a multipart
 * upload with the key of the user. The audio stays in memory, and nothing keeps it after the request.
 */
internal class CloudSpeechInput(private val account: AiAccount, private val endpoints: Endpoints, private val pcm: PcmCapture) : SpeechInput {
    val provider get() = account.provider
    @Volatile private var upload: CloudSession? = null

    override suspend fun capture(oneShot: Boolean, onLevel: (Float) -> Unit, onRecognizing: () -> Unit): String? = withContext(Dispatchers.IO) {
        val samples = pcm.record(oneShot, onLevel) ?: return@withContext null
        onRecognizing()
        transcribe(wav(samples))
    }
    override fun finish() = pcm.finish()
    override fun cancel() { pcm.cancel(); upload?.cancel() }

    private suspend fun transcribe(audio: ByteArray): String? {
        val transcription = checkNotNull(provider.profile.transcription) { "${provider.displayName} has no transcription endpoint" }
        val boundary = "alphy-${UUID.randomUUID()}"
        val body = multipart(boundary, transcription.model, audio)
        val session = CloudSession().also { upload = it }
        val reply = try {
            transcriptionHttp.reply(provider, endpoints.checked(provider) + transcription.path, bearerHeaders(account.key) + ("Accept" to "application/json"),
                "multipart/form-data; boundary=$boundary", body, MAX_TRANSCRIPT_RESPONSE, session) { bytes, _ -> bytes }
        } catch (failure: IOException) {
            if (failure is CloudFailure) throw failure
            if (failure is SocketTimeoutException) throw CloudFailure("timeout", "${provider.displayName}: нет ответа. Повторите запись.")
            throw CloudFailure("network", "${provider.displayName}: нет соединения. Проверьте интернет и повторите запись.")
        } finally { upload = null }
        val bytes = reply ?: throw CloudFailure("response_size", "${provider.displayName}: слишком большой ответ. Повторите запись.")
        // A null or missing text is an invalid reply, not the text "null" (org.json getString would give it).
        val text = try { JSONObject(String(bytes, Charsets.UTF_8)).opt("text") as? String } catch (_: JSONException) { null }
            ?: throw CloudFailure("invalid_response", "${provider.displayName}: неверный ответ. Повторите запись.")
        return text.trim().ifEmpty { null }
    }
}

/** A `multipart/form-data` body: the optional `model` part, then the `file` part (RFC 7578). */
private fun multipart(boundary: String, model: String?, audio: ByteArray): ByteArray = ByteArrayOutputStream(audio.size + 512).apply {
    fun text(value: String) = write(value.toByteArray(Charsets.UTF_8))
    if (model != null) text("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n")
    text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
    write(audio)
    text("\r\n--$boundary--\r\n")
}.toByteArray()

/** A WAV file of 16-bit mono PCM: the 44-byte header, then the little-endian samples. */
internal fun wav(samples: ShortArray): ByteArray {
    val data = samples.size * 2
    val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data).put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
        .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
    buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
    buffer.asShortBuffer().put(samples)
    return buffer.array()
}
