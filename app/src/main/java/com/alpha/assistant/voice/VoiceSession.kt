package com.alpha.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.compose.runtime.*
import com.alpha.assistant.R
import com.offlineassistant.app.voice.AudioTranscriber
import com.offlineassistant.app.voice.AudioTranscription
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** An explicit foreground session. Review never opens the microphone or sends data. */
class VoiceSession internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transcript: (String) -> Unit,
    private val prepareModels: (() -> Unit)? = null,
    private val captureAudio: (suspend () -> AudioTranscription)? = null,
    private val capturePcmForTest: (suspend (suspend (ShortArray) -> Unit) -> Boolean)? = null,
) {
    var oneShot by mutableStateOf(true); private set
    var levels by mutableStateOf<List<Float>>(emptyList()); private set
    var hasAttempted by mutableStateOf(false); private set
    var permissionRequired by mutableStateOf(false); private set
    var active by mutableStateOf(false); private set
    var phase by mutableStateOf("idle"); private set
    var status by mutableStateOf(context.getString(R.string.voice_local_note)); private set
    var partial by mutableStateOf(""); private set
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val releaseScope = CoroutineScope(SupervisorJob() + worker)
    private val models = VoiceModels(context)
    private var recognizer: AudioTranscriber? = null
    private var job: Job? = null
    @Volatile private var generation = 0
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var finishRequested = false
    private val captureLock = Any()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    fun permissionDenied() { hasAttempted = true; stop(context.getString(R.string.voice_mic_permission)); permissionRequired = true }
    fun start(draftEmpty: Boolean, busy: Boolean, oneShot: Boolean = false) {
        if (active) return
        hasAttempted = true
        if ((!oneShot && !draftEmpty) || busy) { status = context.getString(R.string.voice_draft_busy); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionDenied(); return }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) scope.launch { stop(context.getString(R.string.voice_interrupted)) } }.build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status = context.getString(R.string.voice_mic_busy); return }
        focus = request
        this.oneShot = oneShot
        permissionRequired = false
        finishRequested = false
        levels = emptyList()
        partial = ""
        active = true
        phase = "preparing"
        status = context.getString(R.string.voice_preparing)
        val token = ++generation
        job = scope.launch {
            try {
                if (!oneShot || captureAudio != null) withContext(worker) {
                    if (prepareModels != null) prepareModels.invoke()
                    else {
                        models.prepare()
                        recognizer = models.recognizer()
                        recognizer!!.warmUp()
                    }
                }
                if (active && token == generation) listen(token)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (token == generation) stop(context.getString(R.string.voice_start_failed)) }
        }
    }
    private suspend fun listen(token: Int) {
        if (!active || token != generation) return
        finishRequested = false
        phase = "listening"; status = context.getString(if (oneShot) R.string.voice_listening else R.string.voice_listening_session); partial = ""
        val result = when {
            captureAudio != null -> captureAudio.invoke()
            oneShot -> recordWhilePreparing(token)
            else -> withContext(worker) { recordContinuous(token) }
        }
        if (!active || token != generation) return
        val text = result.text
        if (text.isNullOrBlank()) { stop(context.getString(R.string.voice_not_recognized)); return }
        partial = text
        if (oneShot) releaseFocus()
        phase = "review"; status = context.getString(R.string.voice_review)
        transcript(text)
    }
    fun finishRecording() {
        if (!active || !oneShot || phase != "listening") return
        finishRequested = true
        phase = "recognizing"
        status = context.getString(R.string.voice_recognizing)
        synchronized(captureLock) { runCatching { recorder?.stop() } }
    }
    fun submitted() { if (oneShot && active) stop() else if (active && phase == "review") { phase = "waiting"; status = context.getString(R.string.voice_waiting) } else if (active) stop() }
    fun replyCompleted() {
        if (!active || oneShot || phase != "waiting") return
        val token = generation
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                listen(token)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (token == generation) stop(context.getString(R.string.voice_start_failed)) }
        }
    }
    fun requestFailed() { if (active) stop(context.getString(R.string.voice_request_failed)) }
    fun stop(message: String = "") {
        generation++; active = false; phase = "idle"; status = message; hasAttempted = message.isNotBlank(); partial = ""; levels = emptyList()
        job?.cancel(); job = null
        synchronized(captureLock) { runCatching { recorder?.stop() } }
        releaseFocus()
        releaseScope.launch { recognizer?.release(); recognizer = null }
    }
    private fun releaseFocus() { focus?.let { audioManager.abandonAudioFocusRequest(it) }; focus = null }
    fun close() { stop(); releaseScope.launch { worker.close(); releaseScope.cancel() } }

    private suspend fun recordWhilePreparing(token: Int): AudioTranscription = coroutineScope {
        // At most 25 seconds of 16 kHz mono PCM wait in memory while the model loads.
        val blocks = Channel<ShortArray>(MAX_SAMPLES / BLOCK_SAMPLES)
        val capture = async(Dispatchers.IO) {
            try {
                val offer: suspend (ShortArray) -> Unit = { samples -> blocks.send(samples) }
                capturePcmForTest?.invoke(offer) ?: recordPcm(token, onSamples = offer)
            } finally { blocks.close() }
        }
        try {
            withContext(worker) {
                prepareModels?.invoke()
                models.prepare()
                ensureActive()
                val local = models.recognizer()
                try {
                    val stream = streaming(local, token)
                    try {
                        for (samples in blocks) {
                            ensureActive()
                            stream.acceptPcm16(samples, SAMPLE_RATE)
                        }
                        val speech = capture.await()
                        ensureActive()
                        if (speech) stream.finish() else noSpeech()
                    } finally { stream.cancel() }
                } finally { local.release() }
            }
        } finally {
            if (capture.isActive) {
                capture.cancel()
                synchronized(captureLock) {
                    if (token == generation) runCatching { recorder?.stop() }
                }
            }
        }
    }

    private fun streaming(recognizer: AudioTranscriber, token: Int, endpoint: () -> Unit = {}) =
        checkNotNull(recognizer.startStreaming({ text ->
            scope.launch { if (active && token == generation) partial = text }
        }, endpoint)) { "T-one streaming is unavailable" }

    private suspend fun recordContinuous(token: Int): AudioTranscription {
        var endpoint = false
        val stream = streaming(checkNotNull(recognizer), token) { endpoint = true }
        try {
            val speech = recordPcm(token, untilEndpoint = { endpoint }) { stream.acceptPcm16(it, SAMPLE_RATE) }
            return if (speech) stream.finish() else noSpeech()
        } finally { stream.cancel() }
    }

    @Suppress("MissingPermission")
    private suspend fun recordPcm(token: Int, untilEndpoint: (() -> Boolean)? = null, onSamples: suspend (ShortArray) -> Unit): Boolean {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM16 microphone is unsupported" }
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
        try {
            synchronized(captureLock) {
                check(input.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
                check(active && token == generation) { "Voice stopped" }
                if (finishRequested) return false
                recorder = input
                input.startRecording()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone recording failed" }
            }
            val block = ShortArray(BLOCK_SAMPLES)
            var total = 0; var speech = false
            while (token == generation && total < MAX_SAMPLES && !finishRequested && untilEndpoint?.invoke() != true) {
                val count = input.read(block, 0, minOf(block.size, MAX_SAMPLES - total), AudioRecord.READ_BLOCKING)
                if (count <= 0 && finishRequested) break
                check(count > 0) { "Microphone read failed: $count" }
                currentCoroutineContext().ensureActive()
                val samples = block.copyOf(count)
                val rms = sqrt(samples.sumOf { val v = it / 32768.0; v * v } / count)
                scope.launch { if (active && token == generation) levels = (levels.takeLast(47) + (rms.toFloat() * 12f).coerceIn(0f, 1f)) }
                if (rms > 0.004) speech = true
                total += count
                onSamples(samples)
                if (untilEndpoint != null && !speech && total >= SAMPLE_RATE * 8) break
            }
            scope.launch { if (active && token == generation) { phase = "recognizing"; status = context.getString(R.string.voice_recognizing) } }
            return speech
        } finally {
            synchronized(captureLock) {
                if (recorder === input) recorder = null
                runCatching { input.stop() }
            }
            input.release()
        }
    }

    private fun noSpeech() = AudioTranscription(null, "No speech detected", 0)

    private companion object {
        const val SAMPLE_RATE = 16000
        const val BLOCK_SAMPLES = 1600
        const val MAX_SAMPLES = SAMPLE_RATE * 25
    }
}
