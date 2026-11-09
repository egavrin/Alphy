package com.alpha.assistant.courses

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.media.DeviceMediaPlayback
import com.alpha.assistant.media.MediaPlaybackResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal class ReadingVoice(private val context: Context, namespace: String = "") {
    private val keys = SecureKeyStore(context, CREDENTIALS + namespace, KEY_ALIAS + namespace)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    fun hasCredential(): Boolean = keys.consented()

    suspend fun saveCredential(key: String) {
        val value = key.trim()
        require(value.length in 1..512)
        saveVerifiedCredential(value, lookupVoice(value))
    }

    internal suspend fun saveVerifiedCredential(key: String, voice: String) = withContext(Dispatchers.IO) {
        require(key.length in 1..512 && voice.matches(Regex("[A-Za-z0-9_-]{8,80}")))
        keys.save(JSONObject().put("key", key).put("voice", voice).toString()).getOrThrow()
    }

    fun observeCredential(changed: () -> Unit) = keys.observeConsent(changed)

    suspend fun synthesize(cue: CourseCue): ByteArray {
        val clip = cue.checkedClip
        if (clip != null) withContext(Dispatchers.IO) { readCheckedClip(clip) }?.let { return it }
        val revision = checkNotNull(keys.consentIdentity())
        val credential = withContext(Dispatchers.IO) { JSONObject(checkNotNull(keys.read().getOrThrow())) }
        val key = credential.getString("key")
        val voice = credential.getString("voice")
        check(keys.consentIdentity() == revision)
        return dialogue(key, voice, cue.text).also {
            check(keys.consentIdentity() == revision)
            if (context.packageName.endsWith(".readingtest")) android.util.Log.i("AlphyReading",
                "synthesis model=eleven_v4_turbo language=ru voice=Evgeny_v2 bytes=${it.size}")
        }
    }

    private suspend fun lookupVoice(key: String): String = suspendCancellableCoroutine { continuation ->
        val url = "https://api.elevenlabs.io/v2/voices".toHttpUrl().newBuilder()
            .addQueryParameter("search", "Evgeny v2").addQueryParameter("page_size", "30")
            .addQueryParameter("include_total_count", "false").build()
        val call = client.newCall(Request.Builder().url(url).header("xi-api-key", key).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(IOException("Voice lookup unavailable"))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        check(it.isSuccessful) { "Voice lookup ${responseStatus(it)}" }
                        val source = it.body.source()
                        check(!source.request(MAX_AUDIO_BYTES.toLong() + 1))
                        val voices = JSONObject(source.readUtf8()).getJSONArray("voices")
                        (0 until voices.length()).map(voices::getJSONObject)
                            .filter { row -> row.getString("name") == "Evgeny v2" && row.getString("category") == "cloned" }
                            .single().getString("voice_id").also { id -> check(id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) }
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private suspend fun dialogue(key: String, voice: String, text: String): ByteArray {
        val url = "https://api.elevenlabs.io/v1/text-to-dialogue/stream-input".toHttpUrl().newBuilder()
            .addQueryParameter("model_id", "eleven_v4_turbo").addQueryParameter("language_code", "ru")
            .addQueryParameter("output_format", "mp3_44100_128").build()
        val request = Request.Builder().url(url).header("xi-api-key", key).build()
        val result = CompletableDeferred<ByteArray>()
        val audio = ByteArrayOutputStream()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val voices = JSONObject().put("voices", JSONArray().put(voice))
                val input = JSONObject().put("inputs", JSONArray().put(JSONObject().put("text", text).put("voice_id", voice)))
                if (!webSocket.send(voices.toString()) || !webSocket.send(input.toString()) ||
                    !webSocket.send("""{"close_socket":true}""")) {
                    result.completeExceptionally(IOException("Speech connection closed"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (result.isCompleted) return
                try {
                    check(text.length <= MAX_AUDIO_BYTES * 2)
                    val frame = JSONObject(text)
                    check(!frame.has("error"))
                    val encoded = frame.opt("audio") as? String
                    if (!encoded.isNullOrEmpty()) {
                        val bytes = Base64.getDecoder().decode(encoded)
                        check(bytes.size <= MAX_AUDIO_BYTES - audio.size())
                        audio.write(bytes)
                    }
                    if (frame.optBoolean("is_final")) {
                        check(audio.size() > 0)
                        result.complete(audio.toByteArray())
                    }
                } catch (_: Exception) {
                    result.completeExceptionally(IOException("Invalid speech response"))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                result.completeExceptionally(IOException("Speech connection closed before the final cue"))
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!result.completeExceptionally(IOException("Speech connection unavailable"))) return
                val status = response?.let(::responseStatus) ?: "transport error"
                if (context.packageName.endsWith(".readingtest")) android.util.Log.i("AlphyReading", "synthesis failed: $status")
            }
        })
        return try {
            checkNotNull(withTimeoutOrNull(15_000) { result.await() }) { "Speech response timed out" }
        } finally {
            result.cancel()
            socket.cancel()
        }
    }

    suspend fun play(bytes: ByteArray, started: () -> Unit) {
        require(bytes.size in 1..MAX_AUDIO_BYTES)
        val file = File(context.cacheDir, "reading-${java.util.UUID.randomUUID()}.mp3")
        try {
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            withContext(Dispatchers.Main.immediate) {
                val focusLost = CompletableDeferred<Unit>()
                val manager = context.getSystemService(AudioManager::class.java)
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) focusLost.complete(Unit) }.build()
                check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                try {
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    AssetFileDescriptor(descriptor, 0, bytes.size.toLong()).use { source ->
                        DeviceMediaPlayback().use { playback ->
                            coroutineScope {
                                val completion = async {
                                    playback.playAudio(source) { start ->
                                        if (focusLost.isCompleted) false
                                        else {
                                            start()
                                            started()
                                            if (context.packageName.endsWith(".readingtest")) {
                                                android.util.Log.i("AlphyReading", "playback started bytes=${bytes.size}")
                                            }
                                            true
                                        }
                                    }
                                }
                                try {
                                    val finished = select {
                                        completion.onAwait { it }
                                        focusLost.onAwait { MediaPlaybackResult.CANCELLED }
                                    }
                                    check(finished == MediaPlaybackResult.COMPLETED)
                                    if (context.packageName.endsWith(".readingtest")) android.util.Log.i("AlphyReading", "playback completed")
                                } finally {
                                    completion.cancel()
                                }
                            }
                        }
                    }
                } finally {
                    manager.abandonAudioFocusRequest(request)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    private fun readCheckedClip(id: String): ByteArray? {
        val directory = File(context.filesDir, "reading-sounds")
        val manifest = File(directory, "manifest.json")
        if (!manifest.isFile) return null
        check(manifest.length() in 1..32_768L)
        val expected = JSONObject(manifest.readText()).optString(id).ifEmpty { return null }
        check(expected.matches(Regex("[a-f0-9]{64}")))
        val clip = File(directory, "$id.mp3")
        check(clip.length() in 1..MAX_AUDIO_BYTES.toLong())
        val bytes = clip.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == expected)
        return bytes
    }

    private fun responseStatus(response: Response): String {
        val location = response.header("Location")?.let(response.request.url::resolve)
        val target = when {
            location == null -> ""
            location.host == response.request.url.host -> " same-host redirect"
            location.host == "elevenlabs.io" || location.host.endsWith(".elevenlabs.io") -> " provider redirect"
            else -> " external redirect"
        }
        return "HTTP ${response.code}$target"
    }

    private companion object {
        const val CREDENTIALS = "alphy_reading_speech"
        const val KEY_ALIAS = "alphy.elevenlabs.reading.v1"
        const val MAX_AUDIO_BYTES = 1_048_576
    }
}
