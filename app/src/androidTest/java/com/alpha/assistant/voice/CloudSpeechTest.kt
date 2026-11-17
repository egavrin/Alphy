package com.alpha.assistant.voice

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.cloud.*
import com.alpha.assistant.ui.FixtureProvider
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A different fake key for each provider, so a test sees which key goes where. */
private fun keyOf(provider: AiProvider) = "alpha-test-${provider.id}"
private const val QUESTION = "Голосовой вопрос"
/** Two sentences of 2100 characters: two OpenRouter requests of at most 4000 characters. */
private val LONG = "${"а".repeat(2099)}. ${"б".repeat(2099)}."
/** One probe each 50 ms: one second of writes that only a closed socket refuses. */
private const val PROBES = 20

/** Silent MPEG-1 Layer III audio: [frames] mono frames of 26 ms at 128 kbit/s and 44.1 kHz. Zero side information decodes to silence. */
internal fun silentMp3(frames: Int): ByteArray {
    val frame = ByteArray(417).apply { this[0] = 0xFF.toByte(); this[1] = 0xFB.toByte(); this[2] = 0x90.toByte(); this[3] = 0xC4.toByte() }
    return ByteArray(frame.size * frames).also { audio -> repeat(frames) { frame.copyInto(audio, it * frame.size) } }
}

internal fun OutputStream.audioResponse(type: String, bytes: ByteArray) {
    write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
    write(bytes)
    flush()
}

/** The OpenRouter audio: an MP3 file of about 0.2 s. */
private fun audio(@Suppress("UNUSED_PARAMETER") request: LoopbackRequest, output: OutputStream) = output.audioResponse("audio/mpeg", silentMp3(8))

/**
 * Failure cases: a wrong path, key, body field, voice, format, or chunk limit; an audio file left in `cacheDir/speech`; a
 * failure that the phone does not read or that shows no status; speech or a request after Stop; cloud voice without the
 * cloud source or for a provider without cloud voice; «Облако» for a provider without cloud voice, or a consent for it.
 */
class CloudSpeechTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val speechFiles = File(context.cacheDir, "speech")
    private val name = "cloud-speech-${UUID.randomUUID()}"
    private val models = AiModels(context, "$name-models")
    private val settings = VoiceSettings(context, "$name-voice").apply { source = SpeechSource.CLOUD }
    private val phone = FakeSpeechOutput()
    private val modelReads = AtomicInteger()

    @After fun remove() {
        context.deleteSharedPreferences("$name-models")
        context.deleteSharedPreferences("$name-voice")
    }

    private fun serving(handler: (LoopbackRequest, OutputStream) -> Unit = ::audio, block: (LoopbackServer) -> Unit): Unit =
        LoopbackServer(handler).use(block)

    private fun cloud(server: LoopbackServer, account: () -> AiAccount?) =
        CloudSpeech(context, settings, account, { modelReads.incrementAndGet(); models.speech(it) }, Endpoints { server.url }, phone)

    private fun speak(server: LoopbackServer, provider: AiProvider, text: String) =
        runBlocking { cloud(server) { AiAccount(provider, keyOf(provider)) }.speak(text) }

    private fun LoopbackRequest.json() = JSONObject(body)
    private fun JSONObject.fields() = keys().asSequence().toSet()

    @Test fun openRouterRequestShape() = serving { server ->
        val selected = AiProvider.OPENROUTER.profile.speechModels.single()
        speak(server, AiProvider.OPENROUTER, LONG)
        assertEquals(speechChunks(LONG, 4000), server.requests.map { it.json().getString("input") })
        assertEquals(2, server.requests.size)
        for (request in server.requests) {
            assertEquals("POST /api/v1/audio/speech", "${request.method} ${request.path}")
            assertEquals("Bearer ${keyOf(AiProvider.OPENROUTER)}", request.headers["authorization"])
            assertEquals("application/json", request.headers["content-type"])
            val body = request.json()
            assertEquals(setOf("model", "input", "voice", "response_format"), body.fields())
            assertEquals(selected.wire, body.getString("model"))
            assertEquals(selected.voice, body.getString("voice"))
            assertEquals("mp3", body.getString("response_format"))
        }
        // One model for the whole reply, from the snapshot of the account.
        assertEquals(1, modelReads.get())
        assertEquals(0, phone.calls.get())
    }

    @Test fun fileDeletedAfterPlayback() = serving({ _, output -> output.audioResponse("audio/mpeg", silentMp3(40)) }) { server ->
        // A crash can leave a file: the next speech deletes it.
        speechFiles.mkdirs()
        val leftover = File(speechFiles, "leftover.mp3").apply { writeBytes(byteArrayOf(1)) }
        val seen = CopyOnWriteArraySet<String>()
        runBlocking {
            val speech = async(Dispatchers.Default) { cloud(server) { AiAccount(AiProvider.OPENROUTER, keyOf(AiProvider.OPENROUTER)) }.speak("Привет.") }
            while (speech.isActive) { speechFiles.list()?.let { seen.addAll(it) }; delay(10) }
            speech.await()
        }
        assertFalse(leftover.exists())
        // The audio played from a file of its own.
        assertTrue("$seen", seen.any { Regex("[0-9a-f-]{36}\\.mp3").matches(it) })
        assertTrue(speechFiles.list().isNullOrEmpty())
        assertEquals(0, phone.calls.get())
    }

    /** The first chunk plays from the cloud. The second request fails, so the phone reads the rest of the reply (decision 9). */
    @Test fun failureFallsBackToPhone() {
        val requests = AtomicInteger()
        serving({ request, output ->
            if (requests.incrementAndGet() == 1) audio(request, output)
            else output.jsonResponse(JSONObject().put("error", JSONObject().put("message", "Insufficient credits")), status = 402)
        }) { server ->
            voiceReply(server, LONG) { robot, model, captures ->
                robot.waitFor(robot.text(R.string.voice_cloud_failed))
                robot.waitUntil { model.voice.phase == "listening" && captures.get() == 2 }
                assertEquals(2, server.requests.size)
                assertEquals(speechChunks(LONG, 4000).drop(1), phone.spoken)
                assertTrue(speechFiles.list().isNullOrEmpty())
            }
        }
    }

    /** A provider error, a playback error, or audio over 10 MiB: the phone reads the reply, and the status follows. */
    @Test fun badAudioFallsBackToPhone() {
        val replies = listOf<(OutputStream) -> Unit>(
            { it.jsonResponse(JSONObject().put("error", JSONObject().put("message", "Busy")), status = 503) },
            { it.audioResponse("audio/mpeg", "not audio".toByteArray()) },
            { it.audioResponse("audio/mpeg", ByteArray(10 * 1024 * 1024 + 1)) },
        )
        for (reply in replies) serving({ _, output -> reply(output) }) { server ->
            phone.spoken.clear()
            val failure = assertThrows(CloudVoiceFailed::class.java) { speak(server, AiProvider.OPENROUTER, "Ответ.") }
            assertEquals(context.getString(R.string.voice_cloud_failed), failure.message)
            assertEquals(listOf("Ответ."), phone.spoken)
            assertTrue(speechFiles.list().isNullOrEmpty())
        }
    }

    /** Spec 12.2 and 2.11: cloud voice needs «Облако» and a provider with cloud voice, and each reply reads the account again. */
    @Test fun sourceAndAccountChooseTheVoice() = serving { server ->
        var account: AiAccount? = AiAccount(AiProvider.OPENROUTER, keyOf(AiProvider.OPENROUTER))
        val speech = cloud(server) { account }
        settings.source = SpeechSource.PHONE
        runBlocking { speech.speak("Телефон.") }
        settings.source = SpeechSource.CLOUD
        for (provider in listOf(AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.DEEPSEEK, null)) {
            account = provider?.let { AiAccount(it, keyOf(it)) }
            runBlocking { speech.speak("Без облака.") }
        }
        assertEquals(listOf("Телефон.") + List(4) { "Без облака." }, phone.spoken)
        assertTrue(server.requests.isEmpty())
        account = AiAccount(AiProvider.OPENROUTER, keyOf(AiProvider.OPENROUTER))
        runBlocking { speech.speak("Облако.") }
        // The reply sends the key of its own account to the provider of that account.
        assertEquals(listOf("/api/v1/audio/speech" to "Bearer ${keyOf(AiProvider.OPENROUTER)}"), server.requests.map { it.path to it.headers["authorization"] })
        assertEquals(5, phone.calls.get())
    }

    /**
     * The account read of the app (spec 14.7, 12.5): with consent the reply goes to the provider. Without consent there is no
     * account, so the phone reads the reply with no status. A key that does not read falls back to the phone with the status.
     */
    @Test fun accountReadUsesTheConsentGate() = serving { server ->
        val fixture = ShellFixture("cloud-voice-account")
        try {
            val speech = cloud(server) { fixture.keys.voiceAccount(context) }
            check(fixture.keys.save(keyOf(AiProvider.OPENROUTER), AiProvider.OPENROUTER).isSuccess)
            runBlocking { speech.speak("С согласием.") }
            assertEquals(1, server.requests.size)
            val stored = context.getSharedPreferences(fixture.name, Context.MODE_PRIVATE)
            check(stored.edit().remove("consent").commit())
            runBlocking { speech.speak("Без согласия.") }
            // A stored key without its provider does not read (key_read).
            check(stored.edit().putString("consent", "1").remove("provider").commit())
            val failure = assertThrows(CloudVoiceFailed::class.java) { runBlocking { speech.speak("Ключ не читается.") } }
            assertEquals(context.getString(R.string.voice_cloud_failed), failure.message)
            assertEquals(listOf("Без согласия.", "Ключ не читается."), phone.spoken)
            assertEquals(1, server.requests.size)
        } finally { fixture.remove() }
    }

    /** Decision 7: Stop ends the playback, deletes the file, and requests or plays nothing more, not even on the phone. */
    @Test fun stopDuringPlaybackEndsTheSpeech() = serving({ _, output -> output.audioResponse("audio/mpeg", silentMp3(200)) }) { server ->
        voiceReply(server, LONG) { robot, model, captures ->
            robot.waitUntil { model.voice.phase == "speaking" && !speechFiles.list().isNullOrEmpty() }
            robot.click(robot.text(R.string.voice_stop))
            robot.waitUntil { !model.voice.active && speechFiles.list().isNullOrEmpty() }
            assertNothingAfterStop(server, model, captures)
        }
    }

    /** After Stop: one request, no phone speech, no audio file, no second capture, and an idle session (decision 7). */
    private fun assertNothingAfterStop(server: LoopbackServer, model: ShellViewModel, captures: AtomicInteger) {
        SystemClock.sleep(1000)
        assertEquals(1, server.requests.size)
        assertEquals(0, phone.calls.get())
        assertTrue(phone.stopped)
        assertTrue(speechFiles.list().isNullOrEmpty())
        assertEquals(1, captures.get())
        assertEquals("idle", model.voice.phase)
    }

    /** Spec 3.4 and decision 7: Stop during the speech request disconnects it. No audio plays, and nothing reads the reply. */
    @Test fun stopDuringRequestDisconnects() {
        val requested = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val closed = CountDownLatch(1)
        serving({ _, output ->
            requested.countDown()
            stopped.await(15, TimeUnit.SECONDS)
            // Only a closed socket refuses the probes. A connection that is still open after them gets valid audio.
            val audio = silentMp3(8)
            try {
                output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${PROBES + audio.size}\r\nConnection: close\r\n\r\n".toByteArray())
                repeat(PROBES) { output.write(0); output.flush(); Thread.sleep(50) }
                output.write(audio)
                output.flush()
            } catch (_: IOException) { closed.countDown() }
        }) { server ->
            try {
                voiceReply(server, LONG) { robot, model, captures ->
                    assertTrue(requested.await(10, TimeUnit.SECONDS))
                    robot.click(robot.text(R.string.voice_stop))
                    robot.waitUntil { !model.voice.active }
                    stopped.countDown()
                    assertTrue("The speech request stayed open after Stop", closed.await(2, TimeUnit.SECONDS))
                    assertNothingAfterStop(server, model, captures)
                }
            } finally { stopped.countDown() }
        }
    }

    @Test fun cloudSourceHiddenWithoutCapability() {
        val fixture = ShellFixture("cloud-voice-source")
        val robot = ShellRobot("cloud-voice-source")
        val voice = VoiceSettings(fixture.context, fixture.voiceStore)
        val cloudLabel = robot.text(R.string.voice_source_cloud)
        fun cloudChecked() = generateSequence(robot.waitFor(cloudLabel)) { it.parent }.first { it.isCheckable }
            .checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        /** Opens «Голос» with an account of [provider]. */
        fun voiceSettings(provider: AiProvider, then: () -> Unit) {
            check(fixture.keys.save(keyOf(provider), provider).isSuccess)
            fixture.launch().use { scenario ->
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading && model.accountProvider == provider }
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.settings))
                robot.awaitDrawerClosed(scenario)
                robot.click(robot.text(R.string.settings_voice))
                robot.waitFor(robot.text(R.string.voice_source))
                robot.waitFor(robot.text(R.string.voice_source_phone))
                then()
            }
        }
        try {
            // The session has no output and no input, so this test sends nothing to a provider.
            fixture.install(saveKey = false, voice = { app, scope, transcript -> VoiceSession(app, scope, transcript, voice) { null } })
            voice.source = SpeechSource.CLOUD
            for (provider in listOf(AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.DEEPSEEK))
                voiceSettings(provider) { assertNull(robot.find(cloudLabel)) }
            // A provider without cloud voice does not change the stored choice (decision 1).
            assertEquals(SpeechSource.CLOUD, voice.source)
            voice.source = SpeechSource.PHONE
            voiceSettings(AiProvider.OPENROUTER) {
                val consent = fixture.keys.consentIdentity()
                assertFalse(cloudChecked())
                robot.click(cloudLabel)
                robot.waitUntil { voice.source == SpeechSource.CLOUD && cloudChecked() }
                // «Облако» asks for no second consent and writes no key (decision 4, spec 3.5).
                assertNotNull(consent)
                assertEquals(consent, fixture.keys.consentIdentity())
            }
        } finally { fixture.remove() }
    }

    /**
     * A continuous session with an OpenRouter account, cloud voice, and the loopback. It hears [QUESTION], sends it, and gets
     * [reply]. A later capture listens until the session stops. [then] gets the counter of captures.
     */
    private fun voiceReply(server: LoopbackServer, reply: String, then: (ShellRobot, ShellViewModel, AtomicInteger) -> Unit) {
        val fixture = ShellFixture("cloud-speech")
        val robot = ShellRobot("cloud-speech")
        val captures = AtomicInteger()
        try {
            fixture.install(saveKey = false, chat = FixtureProvider(reply), voice = { app, scope, transcript ->
                val voice = VoiceSettings(app, fixture.voiceStore).apply { readAloud = true; source = SpeechSource.CLOUD }
                // The consent-gated account read of the app.
                val output = CloudSpeech(app, voice, { fixture.keys.voiceAccount(app) }, fixture.models::speech, Endpoints { server.url }, phone)
                VoiceSession(app, scope, transcript, voice, output) {
                    FakeSpeechInput { if (captures.incrementAndGet() == 1) QUESTION else awaitCancellation() }
                }
            })
            check(fixture.keys.save(keyOf(AiProvider.OPENROUTER), AiProvider.OPENROUTER).isSuccess)
            fixture.launch().use { scenario ->
                robot.allowMicrophone(scenario)
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading && robot.composed(scenario, "desktop") }
                scenario.onActivity { model.startVoice(oneShot = false) }
                robot.waitUntil { model.voice.phase == "review" && model.snapshot.draft.text == QUESTION }
                scenario.onActivity { model.send() }
                then(robot, model, captures)
            }
        } finally { fixture.remove() }
    }
}
