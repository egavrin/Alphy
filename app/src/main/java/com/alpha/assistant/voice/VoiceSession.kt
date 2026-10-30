package com.alpha.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.compose.runtime.*
import com.offlineassistant.app.voice.AudioTranscriber
import com.offlineassistant.app.speech.SileroSpeechSynthesizer
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** An explicit foreground session. Review never opens the microphone or sends data. */
class VoiceSession(private val context: Context, private val scope: CoroutineScope, private val transcript: (String) -> Unit) {
    var engine by mutableStateOf(VoiceEngine.TONE); private set
    var active by mutableStateOf(false); private set
    var phase by mutableStateOf("idle"); private set
    var status by mutableStateOf("Local audio · review each transcript before DeepSeek"); private set
    var partial by mutableStateOf(""); private set
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val releaseScope = CoroutineScope(SupervisorJob() + worker)
    private val models = VoiceModels(context)
    private val playback = LocalPcmPlayback()
    private var recognizer: AudioTranscriber? = null
    private var synthesizer: SileroSpeechSynthesizer? = null
    private var job: Job? = null
    @Volatile private var generation = 0
    @Volatile private var recorder: AudioRecord? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    fun choose(value: VoiceEngine) { stop(); engine = value }
    fun permissionDenied() { stop("Microphone permission is required. Enable it in Android settings.") }
    fun start(draftEmpty: Boolean, busy: Boolean) {
        if (active) return
        if (!draftEmpty || busy) { status = "Send or clear the current draft and wait for the reply first."; return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionDenied(); return }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) scope.launch { stop("Voice stopped: audio interruption") } }.build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status = "Audio is busy. Try again."; return }
        focus = request
        active = true
        phase = "preparing"
        status = "Verify and install packaged models · 341 MB · ARM64 · Silero personal noncommercial use"
        val token = ++generation
        job = scope.launch {
            try {
                withContext(worker) { models.prepare(); recognizer = models.recognizer(engine); recognizer!!.warmUp(); synthesizer = models.synthesizer() }
                if (active && token == generation) listen(token)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (token == generation) stop("Local voice unavailable: ${error.message ?: error.javaClass.simpleName}") }
        }
    }
    private suspend fun listen(token: Int) {
        if (!active || token != generation) return
        phase = "listening"; status = "Microphone on · speak, then pause · Stop ends the session"; partial = ""
        val result = withContext(worker) { record(token) }
        if (!active || token != generation) return
        val text = result.text
        if (text.isNullOrBlank()) { stop("No transcript: ${result.error ?: "no speech detected"}"); return }
        partial = text
        phase = "review"; status = "Microphone off · review the transcript, then press Send and confirm DeepSeek. Audio remains local."
        transcript(text)
    }
    fun submitted() { if (active && phase == "review") { phase = "waiting"; status = "Microphone off · wait for DeepSeek" } else if (active) stop() }
    fun reply(text: String) {
        if (!active || phase != "waiting") return
        val token = generation
        phase = "speaking"; status = "Microphone off · local spoken reply"
        job = scope.launch {
            try {
                // Bound each synthesis chunk so long replies do not allocate one large alignment matrix.
                val chunks = text.split(Regex("(?<=[.!?])\\s+|\\n+")).filter { it.isNotBlank() }.flatMap { it.chunked(240) }
                for (chunk in chunks) {
                    val audio = withContext(worker) { synthesizer!!.synthesize(chunk) }
                    if (!active || token != generation) return@launch
                    withContext(worker) { if (active && token == generation) playback.play(audio) }
                }
                delay(350)
                listen(token)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (token == generation) stop("Local speech failed: ${error.message ?: error.javaClass.simpleName}") }
        }
    }
    fun requestFailed() { if (active) stop("Voice stopped: request did not complete. The transcript stays in history.") }
    fun stop(message: String = "Voice session stopped · microphone off") {
        generation++; active = false; phase = "idle"; status = message; partial = ""
        job?.cancel(); job = null
        runCatching { recorder?.stop() }
        playback.stop()
        synthesizer?.cancel()
        focus?.let { audioManager.abandonAudioFocusRequest(it) }; focus = null
        releaseScope.launch { recognizer?.release(); recognizer = null; synthesizer?.close(); synthesizer = null }
    }
    fun close() { stop(); releaseScope.launch { worker.close(); releaseScope.cancel() } }

    @Suppress("MissingPermission")
    private fun record(token: Int): com.offlineassistant.app.voice.AudioTranscription {
        val sampleRate = 16000
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM16 microphone is unsupported" }
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
        var endpoint = false
        var streaming: com.offlineassistant.app.voice.StreamingTranscriptionSession? = null
        val wav = File.createTempFile("alpha-utterance-", ".wav", context.cacheDir)
        var completed = false
        try {
            streaming = recognizer!!.startStreaming({ text -> scope.launch { if (active && token == generation) partial = text } }, { endpoint = true })
            recorder = input
            check(input.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            check(active && token == generation) { "Voice stopped" }
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone recording failed" }
            val block = ShortArray(1600)
            var total = 0; var speech = false; var silence = 0
            FileOutputStream(wav).use { output ->
                output.write(ByteArray(44))
                while (active && token == generation && total < sampleRate * 25 && !endpoint) {
                    val count = input.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                    check(count > 0) { "Microphone read failed: $count" }
                    val samples = block.copyOf(count)
                    val rms = sqrt(samples.sumOf { val v = it / 32768.0; v * v } / count)
                    if (rms > 0.004) { speech = true; silence = 0 } else if (speech) silence += count
                    val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                    samples.forEach { bytes.putShort(it) }; output.write(bytes.array()); total += count
                    streaming?.acceptPcm16(samples, sampleRate)
                    if (speech && silence >= sampleRate * 1.2 && streaming == null) break
                    if (!speech && total >= sampleRate * 8) break
                }
            }
            input.stop(); recorder = null
            check(active && token == generation) { "Voice stopped" }
            scope.launch { if (active && token == generation) { phase = "recognizing"; status = "Microphone off · local recognition" } }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(total * 2 + 36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(total * 2)
            java.io.RandomAccessFile(wav, "rw").use { it.write(header.array()) }
            if (!speech) return com.offlineassistant.app.voice.AudioTranscription(null, "No speech detected", 0)
            val result = streaming?.finish() ?: recognizer!!.transcribe(wav)
            completed = true
            return result
        } finally {
            recorder = null; runCatching { input.stop() }; input.release()
            if (!completed) streaming?.cancel()
            wav.delete()
        }
    }
}
