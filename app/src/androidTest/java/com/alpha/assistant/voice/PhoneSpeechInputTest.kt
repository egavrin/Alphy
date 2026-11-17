package com.alpha.assistant.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A phone recognizer under test control. It answers the support check at once and records each call. */
internal class FakePhoneRecognizer : PhoneRecognizer {
    @Volatile var available = true
    @Volatile var installed = true
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    val downloads = AtomicInteger()
    val destroys = AtomicInteger()
    val cancels = AtomicInteger()
    @Volatile var intent: Intent? = null
    @Volatile var listener: RecognitionListener? = null
    override fun available() = available
    override fun checkSupport(intent: Intent, callback: RecognitionSupportCallback) = callback.onSupportResult(
        RecognitionSupport.Builder().setSupportedOnDeviceLanguages(listOf("ru-RU"))
            .setInstalledOnDeviceLanguages(if (installed) listOf("ru-RU") else emptyList()).build())
    override fun start(intent: Intent, listener: RecognitionListener) { starts.incrementAndGet(); this.intent = intent; this.listener = listener }
    override fun stop() { stops.incrementAndGet() }
    override fun cancel() { cancels.incrementAndGet() }
    override fun download(intent: Intent) { downloads.incrementAndGet() }
    override fun destroy() { destroys.incrementAndGet() }
}

internal fun recognitionResults(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }

/** Failure cases: partial text in the draft, a «Завершить запись» that does not stop the recognizer, a missing language without a way out. */
class PhoneSpeechInputTest {
    private val fixture = ShellFixture("phone-voice")
    private val robot = ShellRobot("phone-voice")
    private val recognizer = FakePhoneRecognizer()
    @After fun remove() = fixture.remove()

    /** Starts one-shot voice input from the composer with the fake recognizer. */
    private fun startVoice(block: (ShellViewModel) -> Unit) {
        fixture.install(voice = { app, scope, transcript -> VoiceSession(app, scope, transcript, VoiceSettings(app, fixture.voiceStore)) { PhoneSpeechInput(recognizer, app) } })
        fixture.launch().use { scenario ->
            robot.allowMicrophone(scenario)
            val model = checkNotNull(fixture.model)
            robot.waitUntil { !model.loading }
            robot.click(robot.text(R.string.composer_voice))
            block(model)
        }
    }
    private fun listener(): RecognitionListener {
        robot.waitUntil { recognizer.listener != null }
        return recognizer.listener!!
    }
    private fun onMain(action: () -> Unit) = robot.instrumentation.runOnMainSync(action)

    @Test fun resultArrivesAfterEndOfSpeech() = startVoice { model ->
        val listener = listener()
        val intent = checkNotNull(recognizer.intent)
        assertEquals("ru-RU", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        onMain { listener.onPartialResults(recognitionResults("Поставь")) }
        robot.automation.waitForIdle(100, 2000)
        assertEquals("listening", model.voice.phase)
        assertEquals("", model.voice.partial)
        assertTrue(model.snapshot.draft.text.isEmpty())
        assertNull(robot.find("Поставь"))
        onMain { listener.onEndOfSpeech() }
        robot.waitUntil { model.voice.phase == "recognizing" && model.voice.status == robot.text(R.string.voice_recognizing_phone) }
        onMain { listener.onResults(recognitionResults(TEXT)) }
        robot.waitUntil { model.snapshot.draft.text == TEXT }
        // The recognizer ends with its capture.
        robot.waitUntil { recognizer.destroys.get() >= 1 }
        assertEquals(1, recognizer.starts.get())
    }

    @Test fun finishCallsStopListening() = startVoice { model ->
        val listener = listener()
        robot.click(robot.text(R.string.voice_finish))
        robot.waitUntil { recognizer.stops.get() == 1 }
        assertEquals("recognizing", model.voice.phase)
        onMain { listener.onResults(recognitionResults(TEXT)) }
        robot.waitUntil { model.snapshot.draft.text == TEXT }
    }

    @Test fun stopWhileListeningCancelsTheRecognizer() = startVoice { model ->
        val listener = listener()
        onMain { model.voice.stop() }
        robot.waitUntil { recognizer.cancels.get() == 1 && recognizer.destroys.get() >= 1 }
        // A result after the stop must not reach the draft.
        onMain { listener.onResults(recognitionResults(TEXT)) }
        robot.automation.waitForIdle(100, 2000)
        assertEquals("idle", model.voice.phase)
        assertTrue(model.snapshot.draft.text.isEmpty())
    }

    @Test fun unavailableShowsStatus() {
        recognizer.available = false
        startVoice { model ->
            robot.waitFor(robot.text(R.string.voice_phone_unavailable))
            assertFalse(model.voice.active)
            assertEquals(0, recognizer.starts.get())
        }
    }

    @Test fun missingRussianOffersDownload() {
        recognizer.installed = false
        startVoice { model ->
            robot.waitFor(robot.text(R.string.voice_phone_download))
            assertFalse(model.voice.active)
            robot.assertTarget(robot.text(R.string.voice_phone_download))
            robot.click(robot.text(R.string.voice_phone_download))
            robot.waitFor(robot.text(R.string.voice_phone_downloading))
            assertEquals(1, recognizer.downloads.get())
            // One tap starts one download. The user records again after it.
            assertNull(robot.find(robot.text(R.string.voice_phone_download)))
            assertEquals(0, recognizer.starts.get())
        }
    }

    @Test fun noMatchIsNotRecognized() = startVoice { model ->
        val listener = listener()
        onMain { listener.onError(SpeechRecognizer.ERROR_NO_MATCH) }
        robot.waitFor(robot.text(R.string.voice_not_recognized))
        assertFalse(model.voice.active)
        robot.waitUntil { recognizer.destroys.get() >= 1 }
    }

    private companion object { const val TEXT = "Поставь таймер на пять минут" }
}
