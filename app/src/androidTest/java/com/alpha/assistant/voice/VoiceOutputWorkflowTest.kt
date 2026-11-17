package com.alpha.assistant.voice

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatToolReply
import com.alpha.assistant.ui.FixtureProvider
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Failure cases: the continuous session does not read a reply or does not listen after it, one-shot or the switched-off
 * setting reads aloud, the voice reads Markdown, code, URLs, or the source note, speech moves the session focus and
 * stops the session, a missing Russian voice or an engine error stops listening or shows no status in the chat, a chunk
 * plays after Stop, a reply that does not parse is read as raw Markdown, or listening starts after Stop. */
class VoiceOutputWorkflowTest {
    private val fixture = ShellFixture("voice-output")
    private val robot = ShellRobot("voice-output")
    private val settings = VoiceSettings(fixture.context, fixture.voiceStore)
    private val fake = FakeSpeechOutput()
    private val captures = AtomicInteger()
    private val question = "Голосовой вопрос"
    private lateinit var scenario: ActivityScenario<CloudTestActivity>
    private lateinit var model: ShellViewModel
    @After fun remove() {
        try { if (::scenario.isInitialized) scenario.close() } finally { fixture.remove() }
    }

    @Test fun continuousSessionSpeaksThenListens() {
        val session = speakingSession("**Привет**. Как дела?")
        robot.waitFor(robot.text(R.string.voice_speaking))
        assertEquals(1, captures.get())
        fake.gate!!.complete(Unit)
        robot.waitUntil { session.phase == "listening" && captures.get() == 2 }
        assertEquals(listOf("Привет. Как дела?"), fake.spoken)
    }

    @Test fun oneShotDoesNotSpeak() {
        launch("**Привет**. Как дела?")
        scenario.onActivity { model.startVoice(oneShot = true) }
        robot.waitUntil { !model.voice.active && model.snapshot.draft.text == question }
        scenario.onActivity { model.send() }
        robot.waitUntil { model.activeConversationId == null && model.snapshot.selected?.messages?.lastOrNull()?.status == "complete" }
        assertTrue(fake.spoken.isEmpty())
        assertEquals("idle", model.voice.phase)
        assertEquals(1, captures.get())
    }

    @Test fun readAloudOffDoesNotSpeak() {
        val session = finishReply("**Привет**. Как дела?", readAloud = false)
        assertTrue(fake.spoken.isEmpty())
        assertTrue(session.active)
    }

    @Test fun longReplySpeaksInOrderedChunks() {
        val reply = List(30) { "Предложение номер $it." }.joinToString(" ")
        val session = finishReply(reply)
        assertEquals(speechChunks(reply, 3000), fake.spoken)
        // A small limit: chunks keep the order and the limit, and a long sentence or word still splits.
        val chunks = speechChunks(reply, 50)
        assertEquals(listOf("Предложение номер 0. Предложение номер 1."), chunks.take(1))
        assertTrue(chunks.all { it.length <= 50 })
        assertEquals(reply, chunks.joinToString(" "))
        val sentence = List(20) { "слово$it" }.joinToString(" ") + "."
        assertTrue(speechChunks(sentence, 30).all { it.length <= 30 })
        assertEquals(sentence, speechChunks(sentence, 30).joinToString(" "))
        assertEquals(listOf("абвгд", "еёжзи", "й"), speechChunks("абвгдеёжзий", 5))
        scenario.onActivity { session.stop() }; assertTrue(fake.stopped)
    }

    @Test fun missingRussianVoiceShowsStatus() {
        fake.failure = SpeechUnavailable(robot.text(R.string.voice_no_russian))
        launch("**Привет**. Как дела?", readAloud = false)
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.click(robot.text(R.string.settings_voice))
        // «Читать ответы вслух» is off by default. The row is the switch, labeled by its text.
        val readAloud = robot.text(R.string.voice_read_aloud)
        robot.assertTarget(readAloud)
        fun switchOn() = generateSequence(robot.waitFor(readAloud)) { it.parent }.first { it.isClickable }
            .let { it.isCheckable && it.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE }
        assertFalse(switchOn())
        robot.click(readAloud)
        robot.waitUntil { settings.readAloud && switchOn() }
        robot.click(robot.text(R.string.voice_start))
        robot.waitUntil { model.voice.phase == "review" && model.snapshot.draft.text == question }
        scenario.onActivity { model.send() }
        robot.waitFor(robot.text(R.string.voice_no_russian))
        // The status shows in «Голос»: only VoiceControls in the settings has the switch.
        assertNotNull(robot.find(readAloud))
        robot.waitUntil { model.voice.phase == "listening" && captures.get() == 2 }
        val ttsSettings = robot.text(R.string.open_settings)
        if (Intent(TTS_SETTINGS).resolveActivity(fixture.context.packageManager) == null) assertNull(robot.find(ttsSettings))
        else {
            // «Источник голоса» puts the button lower in «Голос», so a small screen scrolls to it.
            robot.reveal(ttsSettings)
            robot.assertTarget(ttsSettings)
            robot.click(ttsSettings)
            robot.waitUntil { robot.automation.rootInActiveWindow?.packageName == "com.android.settings" }
            robot.back()
        }
    }

    /** After «Отправить» the user sees the chat, so its composer shows the speech status and the fix (spec 12.4). */
    @Test fun missingRussianVoiceShowsInTheChat() {
        fake.failure = SpeechUnavailable(robot.text(R.string.voice_no_russian))
        finishReply("Ответ.")
        robot.waitFor(robot.text(R.string.voice_no_russian))
        // Not «Голос»: only VoiceControls in the settings has the switch.
        assertNull(robot.find(robot.text(R.string.voice_read_aloud)))
        val ttsSettings = robot.text(R.string.open_settings)
        if (Intent(TTS_SETTINGS).resolveActivity(fixture.context.packageManager) == null) assertNull(robot.find(ttsSettings))
        else robot.assertTarget(ttsSettings)
    }

    @Test fun speechFailureShowsStatusAndListens() {
        fake.failure = IllegalStateException("The engine failed")
        finishReply("Ответ.")
        robot.waitFor(robot.text(R.string.voice_speech_failed))
        // The system speech settings do not fix an engine error.
        assertNull(robot.find(robot.text(R.string.open_settings)))
    }

    @Test fun engineErrorsMapToTheStatus() {
        val missing = speechFailure(fixture.context, TextToSpeech.ERROR_NOT_INSTALLED_YET)
        assertTrue(missing is SpeechUnavailable)
        assertEquals(robot.text(R.string.voice_no_russian), missing.message)
        assertFalse(speechFailure(fixture.context, TextToSpeech.ERROR_SYNTHESIS) is SpeechUnavailable)
    }

    /** Plan Review Focus 5: Stop ends all chunks, not only the chunk that plays. */
    @Test fun stopBetweenChunksEndsTheSpeech() {
        val reply = "Первая фраза ответа. Вторая фраза ответа. Третья фраза."
        fake.limit = 20
        val permits = Channel<Unit>(Channel.UNLIMITED).also { fake.chunkPermits = it }
        val chunks = speechChunks(reply, 20)
        assertEquals(3, chunks.size)
        val session = finishReply(reply, untilListening = false)
        robot.waitUntil { session.phase == "speaking" && fake.spoken.size == 1 }
        robot.click(robot.text(R.string.voice_stop))
        robot.waitUntil { !session.active && session.phase == "idle" }
        repeat(chunks.size) { permits.trySend(Unit) }
        SystemClock.sleep(500)
        assertEquals(chunks.take(1), fake.spoken)
        assertTrue(fake.stopped)
        assertEquals(1, captures.get())
        assertEquals("idle", session.phase)
    }

    @Test fun emptySpeechListensWithoutSpeaking() {
        finishReply("```\nval code = 1\n```")
        assertEquals(0, fake.calls.get())
    }

    @Test fun replyThatDoesNotParseIsNotRead() {
        assertEquals("", speakableText("**Привет**") { error("The parser failed") })
    }

    /** The app wiring gives the session the voice file of the model, so no test changes the app file. */
    @Test fun modelSessionUsesTheModelVoiceFile() {
        val app = VoiceSettings(fixture.context)
        val appBefore = app.readAloud
        fixture.install(chat = FixtureProvider("Ответ."))
        scenario = fixture.launch()
        model = checkNotNull(fixture.model)
        try {
            model.voice.settings.readAloud = true
            assertTrue(settings.readAloud)
            assertEquals(appBefore, app.readAloud)
        } finally {
            // Only a broken wiring changes the app file. Put the value of the user back.
            if (app.readAloud != appBefore) app.readAloud = appBefore
        }
    }

    /** Decision 4: speech runs under the focus request of the session, so moving from speech to listening does not stop it. */
    @Test fun sessionSurvivesSpeakThenListen() {
        val session = speakingSession()
        assertTrue("The session holds audio focus while it speaks", holdsFocus())
        fake.gate!!.complete(Unit)
        robot.waitUntil { session.phase == "listening" && captures.get() == 2 }
        // Focus changes arrive later on the main thread.
        robot.instrumentation.waitForIdleSync()
        SystemClock.sleep(1000)
        robot.instrumentation.waitForIdleSync()
        assertTrue(session.active)
        assertEquals("listening", session.phase)
        assertNotEquals(robot.text(R.string.voice_interrupted), session.status)
        assertFalse(fake.stopped)
        assertTrue(holdsFocus())
    }

    @Test fun stopDuringSpeechEndsTheSession() {
        val session = speakingSession()
        robot.click(robot.text(R.string.voice_stop))
        robot.waitUntil { !session.active && session.phase == "idle" }
        assertTrue(fake.stopped)
        fake.gate!!.complete(Unit)
        robot.waitUntil { !holdsFocus() }
        SystemClock.sleep(500)
        assertEquals(1, captures.get())
        assertEquals("idle", session.phase)
    }

    @Test fun otherFocusDuringSpeechStopsTheSession() {
        val session = speakingSession()
        // A second focus client stands for another app: the audio service gives focus by client, not by app.
        val audio = fixture.context.getSystemService(AudioManager::class.java)
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).build()
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(other))
            robot.waitUntil { !session.active }
            assertEquals(robot.text(R.string.voice_interrupted), session.status)
            assertTrue(fake.stopped)
            fake.gate!!.complete(Unit)
            SystemClock.sleep(500)
            assertEquals(1, captures.get())
        } finally { audio.abandonAudioFocusRequest(other) }
    }

    @Test fun speakableTextKeepsOnlyTheWordsOfTheReply() {
        val reply = ChatToolReply.SOURCE_LIMITATION + "\n\n" + """
            ## Итоги
            Подробнее в [документации](https://example.com/docs) [1]. Адрес https://example.com/raw тоже.

            ```kotlin
            val code = 1
            ```

                indented code

            - Первый `пункт`
            - Второй *пункт*
        """.trimIndent()
        assertEquals("Итоги\nПодробнее в документации. Адрес тоже.\nПервый пункт\nВторой пункт", speakableText(reply))
        assertEquals("Ответ.", speakableText(ChatToolReply.NO_SOURCES + "\n\nОтвет."))
        assertEquals("", speakableText("```\ncode\n```"))
    }

    /** The shell on the Desktop with the microphone permission. Its provider gives [reply], and its session reads aloud with [fake]. */
    private fun launch(reply: String, readAloud: Boolean = true) {
        settings.readAloud = readAloud
        fixture.install(chat = FixtureProvider(reply), voice = { app, scope, transcript ->
            VoiceSession(app, scope, transcript, settings, fake) {
                FakeSpeechInput { if (captures.incrementAndGet() == 1) question else awaitCancellation() }
            }
        })
        scenario = fixture.launch()
        robot.allowMicrophone(scenario)
        model = checkNotNull(fixture.model)
        robot.waitUntil { !model.loading && robot.composed(scenario, "desktop") }
    }

    /** The continuous session hears [question], sends it, and gets [reply]. A later capture listens until the session stops. */
    private fun finishReply(reply: String, readAloud: Boolean = true, untilListening: Boolean = true): VoiceSession {
        launch(reply, readAloud)
        scenario.onActivity { model.startVoice(oneShot = false) }
        robot.waitUntil { model.voice.phase == "review" && model.snapshot.draft.text == question }
        scenario.onActivity { model.send() }
        if (untilListening) robot.waitUntil { model.voice.phase == "listening" && captures.get() == 2 }
        return model.voice
    }

    /** [finishReply] with a held speech: it returns while the session speaks. Completing `fake.gate` ends the speech. */
    private fun speakingSession(reply: String = "Ответ."): VoiceSession {
        fake.gate = CompletableDeferred()
        return finishReply(reply, untilListening = false).also { session -> robot.waitUntil { session.phase == "speaking" } }
    }

    /** True when this app is in the audio focus stack of the device. */
    private fun holdsFocus() = robot.shell("dumpsys audio").substringAfter("Audio Focus stack entries")
        .substringBefore("focus policy").contains("pack: ${fixture.context.packageName} ")

    private companion object { const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS" }
}
