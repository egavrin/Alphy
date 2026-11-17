package com.alpha.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.compose.runtime.*
import com.alpha.assistant.R
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.KEY_ERRORS
import kotlinx.coroutines.*

/** An explicit foreground session. Review never opens the microphone or sends data. */
class VoiceSession internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transcript: (String) -> Unit,
    // No default, so no test reaches the app file by omission.
    internal val settings: VoiceSettings,
    // Null never reads aloud. Only the app passes the real voices (CloudSpeech with the phone engine), so no test reaches them by omission.
    private val output: SpeechOutput? = null,
    // Null without an account, so voice input does not start (spec 12A.6). A failure of the account read gets the
    // normal failure status. It can read the key store, so it runs on IO.
    private val inputs: () -> SpeechInput?,
) {
    var oneShot by mutableStateOf(true); private set
    var levels by mutableStateOf<List<Float>>(emptyList()); private set
    var hasAttempted by mutableStateOf(false); private set
    var permissionRequired by mutableStateOf(false); private set
    var active by mutableStateOf(false); private set
    var phase by mutableStateOf("idle"); private set
    var status by mutableStateOf(""); private set
    var partial by mutableStateOf(""); private set
    /** The last stop needs «Сервис ИИ»: no account, or the provider refused the key. */
    var settingsRequired by mutableStateOf(false); private set
    /**
     * The last reply was not read: the phone has no Russian voice (spec 12.4), or the engine failed. Or the cloud voice failed
     * and the phone read it (spec 12.5). The session still listens.
     */
    var speechStatus by mutableStateOf(""); private set
    /** The phone has no Russian voice, so the system speech settings can fix [speechStatus]. */
    var speechVoiceMissing by mutableStateOf(false); private set
    /** «Скачать русский язык» of the phone path (spec 12A.5). */
    private var download by mutableStateOf<(() -> Unit)?>(null)
    val downloadOffered get() = download != null
    private var input: SpeechInput? = null
    private var job: Job? = null
    @Volatile private var generation = 0
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    fun permissionDenied() { hasAttempted = true; stop(context.getString(R.string.voice_mic_permission)); permissionRequired = true }
    /** True from the tap until the session stops, including the account read before the capture. */
    val running get() = active || job?.isActive == true
    fun start(draftEmpty: Boolean, busy: Boolean, oneShot: Boolean = false) {
        if (running) return
        hasAttempted = true
        settingsRequired = false
        download = null
        speechStatus = ""; speechVoiceMissing = false
        if ((!oneShot && !draftEmpty) || busy) { status = context.getString(R.string.voice_draft_busy); return }
        status = context.getString(R.string.voice_preparing)
        val token = ++generation
        job = scope.launch {
            val speech = try { withContext(Dispatchers.IO) { inputs() } }
                catch (error: CancellationException) { throw error }
                catch (error: Throwable) { if (token == generation) fail(error); return@launch }
            if (token != generation) return@launch
            if (speech == null) { stop(context.getString(R.string.voice_no_account)); settingsRequired = true; return@launch }
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionDenied(); return@launch }
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(ASSISTANT_SPEECH)
                .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) scope.launch { stop(context.getString(R.string.voice_interrupted)) } }.build()
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status = context.getString(R.string.voice_mic_busy); return@launch }
            focus = request
            input = speech
            this@VoiceSession.oneShot = oneShot
            permissionRequired = false
            levels = emptyList()
            partial = ""
            active = true
            phase = "preparing"
            status = context.getString(R.string.voice_preparing)
            capture(token)
        }
    }
    /** Reads [reply] aloud first when it is not blank. */
    private suspend fun capture(token: Int, reply: String = "") {
        try {
            if (reply.isNotBlank()) speak(token, reply)
            listen(token)
        }
        catch (error: CancellationException) { throw error }
        catch (error: Throwable) { if (token == generation) fail(error) }
    }
    private fun fail(error: Throwable) = when (error) {
        is SpeechUnavailable -> { stop(error.message.orEmpty()); download = error.download; hasAttempted = true }
        is CloudFailure -> { stop(context.getString(R.string.voice_transcription_failed, error.message)); settingsRequired = error.code in KEY_ERRORS }
        else -> stop(context.getString(R.string.voice_start_failed))
    }
    /**
     * The speech runs under the one audio focus request of the session, which it holds from the start to the stop. A second
     * request of this app would send a focus loss to that request and stop the session (spec 12.6).
     */
    private suspend fun speak(token: Int, reply: String) {
        phase = "speaking"; status = context.getString(R.string.voice_speaking); speechStatus = ""; speechVoiceMissing = false
        // A failed speech does not end the session: it shows the status and listens (spec 12.4). The reply is in the chat.
        try { output?.speak(reply) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (token != generation) return
            speechVoiceMissing = error is SpeechUnavailable
            // CloudVoiceFailed: the phone read the reply after the cloud voice failed (spec 12.5).
            speechStatus = if (speechVoiceMissing || error is CloudVoiceFailed) error.message.orEmpty() else context.getString(R.string.voice_speech_failed)
        }
    }
    private suspend fun listen(token: Int) {
        val speech = input
        if (!active || token != generation || speech == null) return
        phase = "listening"; status = context.getString(if (oneShot) R.string.voice_listening else R.string.voice_listening_session); partial = ""
        val text = speech.capture(oneShot,
            onLevel = { level -> scope.launch { if (active && token == generation) levels = levels.takeLast(47) + level } },
            onRecognizing = { scope.launch { if (active && token == generation) recognizing() } })
        if (!active || token != generation) return
        if (text.isNullOrBlank()) { stop(context.getString(R.string.voice_not_recognized)); return }
        // Only the final text: no partial text shows while the user speaks (spec 12A.1).
        partial = text
        if (oneShot) releaseFocus()
        phase = "review"; status = context.getString(R.string.voice_review)
        transcript(text)
    }
    private fun recognizing() {
        phase = "recognizing"
        val provider = (input as? CloudSpeechInput)?.provider
        status = if (provider == null) context.getString(R.string.voice_recognizing_phone)
            else context.getString(R.string.voice_recognizing_cloud, provider.displayName)
    }
    fun finishRecording() {
        if (!active || !oneShot || phase != "listening") return
        recognizing()
        input?.finish()
    }
    /** «Скачать русский язык»: one tap starts the download, and the user records again after it (spec 12A.5). */
    fun downloadLanguage() {
        val start = download ?: return
        download = null
        start()
        status = context.getString(R.string.voice_phone_downloading)
        hasAttempted = true
    }
    fun submitted() { if (oneShot && active) stop() else if (active && phase == "review") { phase = "waiting"; status = context.getString(R.string.voice_waiting) } else if (active) stop() }
    /** The continuous session reads the accepted [reply] aloud when «Читать ответы вслух» is on, then listens (spec 12.1). */
    fun replyCompleted(reply: String) {
        if (!active || oneShot || phase != "waiting") return
        val token = generation
        val speech = if (output != null && settings.readAloud) speakableText(reply) else ""
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) { capture(token, speech) }
    }
    fun requestFailed() { if (active) stop(context.getString(R.string.voice_request_failed)) }
    fun stop(message: String = "") {
        generation++; active = false; phase = "idle"; status = message; hasAttempted = message.isNotBlank(); partial = ""; levels = emptyList()
        settingsRequired = false; download = null; speechStatus = ""; speechVoiceMissing = false
        // The input ends its recording or its upload. The recording is not kept (spec 12A.7).
        input?.cancel(); input = null
        job?.cancel(); job = null
        output?.stop()
        releaseFocus()
    }
    private fun releaseFocus() { focus?.let { audioManager.abandonAudioFocusRequest(it) }; focus = null }
    fun close() = stop()
}
