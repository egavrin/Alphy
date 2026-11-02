package com.alpha.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.compose.runtime.*
import com.offlineassistant.app.voice.AudioTranscriber
import com.offlineassistant.app.speech.SileroSpeechSynthesizer
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** An explicit foreground session. Review never opens the microphone or sends data. */
class VoiceSession internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transcript: (String) -> Unit,
    private val prepareModels: ((Boolean) -> Unit)? = null,
    private val captureAudio: (suspend () -> com.offlineassistant.app.voice.AudioTranscription)? = null
) {
    var oneShot by mutableStateOf(true); private set
    var levels by mutableStateOf<List<Float>>(emptyList()); private set
    var hasAttempted by mutableStateOf(false); private set
    var permissionRequired by mutableStateOf(false); private set
    var active by mutableStateOf(false); private set
    var phase by mutableStateOf("idle"); private set
    var status by mutableStateOf("Звук остаётся на устройстве · проверяйте текст перед отправкой"); private set
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
    @Volatile private var finishRequested = false
    private val captureLock = Any()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    fun permissionDenied() { hasAttempted = true; stop("Разрешите микрофон в настройках Android и повторите запись."); permissionRequired = true }
    fun start(draftEmpty: Boolean, busy: Boolean, oneShot: Boolean = false) {
        if (active) return
        hasAttempted = true
        if ((!oneShot && !draftEmpty) || busy) { status = "Отправьте или очистите черновик и дождитесь ответа."; return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionDenied(); return }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) scope.launch { stop("Голосовой ввод прерван другим приложением. Повторите запись.") } }.build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status = "Микрофон занят другим приложением. Повторите попытку."; return }
        focus = request
        this.oneShot = oneShot
        permissionRequired = false
        finishRequested = false
        levels = emptyList()
        partial = ""
        active = true
        phase = "preparing"
        status = if (oneShot) "Подготовка T-one · распознавание на устройстве" else "Подготовка T-one и Silero · распознавание и ответы на устройстве"
        val token = ++generation
        job = scope.launch {
            try {
                withContext(worker) {
                    if (prepareModels != null) prepareModels.invoke(!oneShot)
                    else {
                        models.prepare(includeSpeech = !oneShot)
                        recognizer = models.recognizer()
                        recognizer!!.warmUp()
                        if (!oneShot) synthesizer = models.synthesizer()
                    }
                }
                if (active && token == generation) listen(token)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (token == generation) stop("Не удалось включить голосовой ввод. Повторите попытку.") }
        }
    }
    private suspend fun listen(token: Int) {
        if (!active || token != generation) return
        finishRequested = false
        phase = "listening"; status = if (oneShot) "Микрофон включён · завершите запись для проверки текста" else "Микрофон включён · говорите, затем сделайте паузу"; partial = ""
        val result = if (captureAudio != null) captureAudio.invoke() else withContext(worker) { record(token) }
        if (!active || token != generation) return
        val text = result.text
        if (text.isNullOrBlank()) { stop("Не удалось распознать речь. Повторите запись."); return }
        partial = text
        if (oneShot) releaseFocus()
        phase = "review"; status = "Микрофон выключен · проверьте текст перед отправкой"
        transcript(text)
    }
    fun editTranscript(text: String) { if (active && oneShot && phase == "review") partial = text }
    fun finishRecording() {
        if (!active || !oneShot || phase != "listening") return
        finishRequested = true
        phase = "recognizing"
        status = "Микрофон выключен · распознавание на устройстве"
        synchronized(captureLock) { runCatching { recorder?.stop() } }
    }
    fun submitted() { if (oneShot && active) stop() else if (active && phase == "review") { phase = "waiting"; status = "Микрофон выключен · дождитесь ответа DeepSeek" } else if (active) stop() }
    fun reply(text: String) {
        if (!active || oneShot || phase != "waiting") return
        val token = generation
        phase = "speaking"; status = "Микрофон выключен · ответ звучит на устройстве"
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
            catch (error: Throwable) { if (token == generation) stop("Не удалось озвучить ответ. Текст ответа сохранён в чате.") }
        }
    }
    fun requestFailed() { if (active) stop("Голосовой сеанс остановлен: ответ не получен. Ваш текст сохранён в истории.") }
    fun stop(message: String = "") {
        generation++; active = false; phase = "idle"; status = message; hasAttempted = message.isNotBlank(); partial = ""; levels = emptyList()
        job?.cancel(); job = null
        synchronized(captureLock) { runCatching { recorder?.stop() } }
        playback.stop()
        synthesizer?.cancel()
        releaseFocus()
        releaseScope.launch { recognizer?.release(); recognizer = null; synthesizer?.close(); synthesizer = null }
    }
    private fun releaseFocus() { focus?.let { audioManager.abandonAudioFocusRequest(it) }; focus = null }
    fun close() { stop(); releaseScope.launch { worker.close(); releaseScope.cancel() } }

    @Suppress("MissingPermission")
    private fun record(token: Int): com.offlineassistant.app.voice.AudioTranscription {
        val sampleRate = 16000
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM16 microphone is unsupported" }
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
        var endpoint = false
        var streaming: com.offlineassistant.app.voice.StreamingTranscriptionSession? = null
        var completed = false
        try {
            streaming = checkNotNull(recognizer!!.startStreaming({ text -> scope.launch { if (active && token == generation) partial = text } }, { endpoint = true })) { "T-one streaming is unavailable" }
            synchronized(captureLock) {
                check(input.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
                check(active && token == generation) { "Voice stopped" }
                if (finishRequested) return com.offlineassistant.app.voice.AudioTranscription(null, "No speech detected", 0)
                recorder = input
                input.startRecording()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone recording failed" }
            }
            val block = ShortArray(1600)
            var total = 0; var speech = false
            while (active && token == generation && total < sampleRate * 25 && !finishRequested && (oneShot || !endpoint)) {
                val count = input.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                if (count <= 0 && finishRequested) break
                check(count > 0) { "Microphone read failed: $count" }
                val samples = block.copyOf(count)
                val rms = sqrt(samples.sumOf { val v = it / 32768.0; v * v } / count)
                scope.launch { if (active && token == generation) levels = (levels.takeLast(47) + (rms.toFloat() * 12f).coerceIn(0f, 1f)) }
                if (rms > 0.004) speech = true
                total += count
                streaming.acceptPcm16(samples, sampleRate)
                if (!oneShot && !speech && total >= sampleRate * 8) break
            }
            runCatching { input.stop() }; recorder = null
            check(active && token == generation) { "Voice stopped" }
            scope.launch { if (active && token == generation) { phase = "recognizing"; status = "Микрофон выключен · распознавание на устройстве" } }
            if (!speech) return com.offlineassistant.app.voice.AudioTranscription(null, "No speech detected", 0)
            val result = streaming.finish()
            completed = true
            return result
        } finally {
            recorder = null; runCatching { input.stop() }; input.release()
            if (!completed) streaming?.cancel()
        }
    }
}
