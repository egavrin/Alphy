package com.alpha.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.alpha.assistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** The on-device recognizer of the phone. Every call runs on the main thread. Tests use a fake, because the emulator can lack one. */
internal interface PhoneRecognizer {
    fun available(): Boolean
    fun checkSupport(intent: Intent, callback: RecognitionSupportCallback)
    fun start(intent: Intent, listener: RecognitionListener)
    fun stop()
    fun cancel()
    fun download(intent: Intent)
    fun destroy()
}

/** The on-device [SpeechRecognizer]: the speech stays on the phone (spec 12A.4). One instance serves one capture. */
internal class AndroidPhoneRecognizer(private val context: Context) : PhoneRecognizer {
    private var recognizer: SpeechRecognizer? = null
    private fun recognizer() = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }

    override fun available() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    override fun checkSupport(intent: Intent, callback: RecognitionSupportCallback) =
        recognizer().checkRecognitionSupport(intent, context.mainExecutor, callback)
    override fun start(intent: Intent, listener: RecognitionListener) {
        val recognizer = recognizer()
        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent)
    }
    override fun stop() { recognizer?.stopListening() }
    override fun cancel() { recognizer?.cancel() }
    override fun destroy() { recognizer?.destroy(); recognizer = null }

    /** A separate instance asks for the download: destroy() of an instance drops a request that is not yet sent. */
    override fun download(intent: Intent) {
        val downloader = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val release = { Handler(Looper.getMainLooper()).post(downloader::destroy); Unit }
        downloader.triggerModelDownload(intent, context.mainExecutor, object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) = Unit
            override fun onSuccess() = release()
            override fun onScheduled() = release()
            override fun onError(error: Int) {
                // A recognizer that sends no download events can still take the request without a listener.
                if (error == SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS) downloader.triggerModelDownload(intent)
                release()
            }
        })
    }
}

/** Voice input of an account whose provider has no transcription endpoint: the phone recognizes Russian speech (spec 12A.4). */
internal class PhoneSpeechInput(private val recognizer: PhoneRecognizer, private val context: Context) : SpeechInput {
    @Volatile private var finished = false

    override suspend fun capture(oneShot: Boolean, onLevel: (Float) -> Unit, onRecognizing: () -> Unit): String? =
        withContext(Dispatchers.Main.immediate) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            try {
                if (!recognizer.available()) throw unavailable()
                val support = suspendCancellableCoroutine { continuation ->
                    recognizer.checkSupport(intent, object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) { if (continuation.isActive) continuation.resume(support) }
                        override fun onError(error: Int) { if (continuation.isActive) continuation.resume(null) }
                    })
                }
                when {
                    support == null -> throw unavailable()
                    support.installedOnDeviceLanguages.russian() -> Unit
                    support.pendingOnDeviceLanguages.russian() -> throw SpeechUnavailable(context.getString(R.string.voice_phone_downloading))
                    support.supportedOnDeviceLanguages.russian() -> throw SpeechUnavailable("") { recognizer.download(intent) }
                    else -> throw unavailable()
                }
                if (finished) null else listen(intent, onLevel, onRecognizing)
            } finally {
                finished = false
                recognizer.destroy()
            }
        }

    private suspend fun listen(intent: Intent, onLevel: (Float) -> Unit, onRecognizing: () -> Unit): String? =
        suspendCancellableCoroutine { continuation ->
            fun end(result: Result<String?>) { if (continuation.isActive) continuation.resumeWith(result) }
            recognizer.start(intent, object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                // shortcut: the 0 to 1 range assumes -2 to 10 dB from the recognizer, measure it on a real phone.
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = onRecognizing()
                override fun onError(error: Int) = end(failure(error))
                override fun onResults(results: Bundle?) =
                    end(Result.success(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()))
                // Only the final text reaches the draft (spec 12A.1).
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }

    private fun failure(error: Int): Result<String?> = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Result.success(null)
        else -> Result.failure(SpeechUnavailable(context.getString(when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_mic_permission
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> R.string.voice_mic_busy
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_phone_unavailable
            else -> R.string.voice_start_failed
        })))
    }

    private fun unavailable() = SpeechUnavailable(context.getString(R.string.voice_phone_unavailable))

    /** «Завершить запись»: the recognizer stops listening and gives the result of the speech so far. */
    override fun finish() { finished = true; recognizer.stop() }
    override fun cancel() { recognizer.cancel(); recognizer.destroy() }

    private companion object {
        const val LANGUAGE = "ru-RU"
        fun List<String>.russian() = any { Locale.forLanguageTag(it).language == "ru" }
    }
}
