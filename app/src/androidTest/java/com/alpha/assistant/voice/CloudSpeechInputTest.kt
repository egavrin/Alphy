package com.alpha.assistant.voice

import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.*
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** A 440 Hz tone at amplitude 0.2: speech for the end-of-speech detector. */
internal fun toneSample(index: Int): Short = (sin(2 * PI * 440 * index / SAMPLE_RATE) * 0.2 * Short.MAX_VALUE).roundToInt().toShort()

/** One part of a multipart/form-data body. */
internal class FormPart(val headers: String, val bytes: ByteArray)

/** The parts of a multipart upload by name. ISO-8859-1 keeps one character for each byte, so the file bytes stay exact. */
internal fun LoopbackRequest.parts(): Map<String, FormPart> {
    val type = headers.getValue("content-type")
    assertTrue(type, type.startsWith("multipart/form-data; boundary="))
    val boundary = type.substringAfter("boundary=")
    val text = String(bytes, Charsets.ISO_8859_1)
    assertTrue(text.startsWith("--$boundary\r\n") && "\r\n--$boundary--" in text)
    return text.removePrefix("--$boundary\r\n").substringBeforeLast("\r\n--$boundary--").split("\r\n--$boundary\r\n").associate { part ->
        val head = part.substringBefore("\r\n\r\n")
        val name = checkNotNull(Regex("; name=\"([^\"]*)\"").find(head)) { head }.groupValues[1]
        name to FormPart(head, part.substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
    }
}

/** The samples of the uploaded WAV file. */
internal fun LoopbackRequest.wavSamples(): ShortArray {
    val file = parts().getValue("file").bytes
    val buffer = ByteBuffer.wrap(file, 44, file.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    return ShortArray(buffer.remaining()).also { buffer.get(it) }
}

/** A synthetic microphone. It gives [sample] for each sample index and counts the samples that it gave. */
private class SyntheticMicrophone(private val sample: (Int) -> Short) : PcmSource {
    val delivered = AtomicInteger()
    override fun open() = object : PcmStream {
        override fun read(buffer: ShortArray): Int {
            val start = delivered.get()
            // The fixture ends after 70 s, so a capture that never stops fails.
            if (start >= SAMPLE_RATE * 70) return 0
            for (index in buffer.indices) buffer[index] = sample(start + index)
            delivered.addAndGet(buffer.size)
            return buffer.size
        }
        override fun close() = Unit
    }
}

/** Background noise at amplitude 0.01. Its level is above the fixed floor, so only the adaptive threshold keeps it out of speech. */
private fun noise(random: Random): Short = ((random.nextDouble() * 2 - 1) * 0.01 * Short.MAX_VALUE).roundToInt().toShort()

private const val TONE_START = SAMPLE_RATE / 2
private const val TONE_END = TONE_START + SAMPLE_RATE

/** 0.5 s of noise, 1 s of speech, then noise. */
private fun utterance(): SyntheticMicrophone {
    val random = Random(7)
    return SyntheticMicrophone { index -> if (index in TONE_START until TONE_END) toneSample(index) else noise(random) }
}

private const val KEY = "alpha-test-only"
private const val TEXT = "Поставь таймер на пять минут"
/** One probe each 50 ms: one second of writes that only a closed socket refuses. */
private const val PROBES = 20

/** Failure cases: a wrong endpoint, model, or file part; a recording without a limit or an end; an upload that stop() leaves open. */
class CloudSpeechInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun serving(handler: (LoopbackRequest, OutputStream) -> Unit = { _, output -> output.jsonResponse(JSONObject().put("text", TEXT)) },
        block: (LoopbackServer) -> Unit): Unit = LoopbackServer(handler).use(block)

    private fun capture(server: LoopbackServer, provider: AiProvider, microphone: PcmSource = utterance(), oneShot: Boolean = false,
        recognizing: AtomicInteger = AtomicInteger()): String? = runBlocking {
        CloudSpeechInput(AiAccount(provider, KEY), Endpoints { server.url }, PcmCapture(microphone))
            .capture(oneShot, onLevel = {}, onRecognizing = { recognizing.incrementAndGet() })
    }

    @Test fun openAiRequestShape() = serving { server ->
        val recognizing = AtomicInteger()
        assertEquals(TEXT, capture(server, AiProvider.OPENAI, recognizing = recognizing))
        assertEquals(1, recognizing.get())
        val request = server.requests.single()
        assertEquals("POST /v1/audio/transcriptions", "${request.method} ${request.path}")
        assertEquals("Bearer $KEY", request.headers["authorization"])
        val parts = request.parts()
        // No language part: the provider detects the language.
        assertEquals(setOf("model", "file"), parts.keys)
        assertEquals("gpt-transcribe", String(parts.getValue("model").bytes))
        val file = parts.getValue("file")
        assertTrue(file.headers, file.headers.contains("filename=\"voice.wav\""))
        assertTrue(file.headers, file.headers.contains("Content-Type: audio/wav"))
    }

    @Test fun openRouterRequestShape() = serving { server ->
        assertEquals(TEXT, capture(server, AiProvider.OPENROUTER))
        val request = server.requests.single()
        assertEquals("POST /api/v1/audio/transcriptions", "${request.method} ${request.path}")
        assertEquals("Bearer $KEY", request.headers["authorization"])
        assertEquals("openai/whisper-large-v3", String(request.parts().getValue("model").bytes))
    }

    @Test fun deepSeekRequestShape() = serving { server ->
        assertEquals(TEXT, capture(server, AiProvider.DEEPSEEK))
        val request = server.requests.single()
        assertEquals("POST /v1/tools/asr", "${request.method} ${request.path}")
        assertEquals("Bearer $KEY", request.headers["authorization"])
        assertEquals(setOf("file"), request.parts().keys)
    }

    @Test fun wavHeaderIsPcm16Mono16k() = serving { server ->
        val microphone = utterance()
        capture(server, AiProvider.OPENAI, microphone)
        val file = server.requests.single().parts().getValue("file").bytes
        val header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(file, 0, 4, Charsets.US_ASCII))
        assertEquals(file.size - 8, header.getInt(4))
        assertEquals("WAVE", String(file, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(file, 12, 4, Charsets.US_ASCII))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(1, header.getShort(22).toInt()) // mono
        assertEquals(16000, header.getInt(24))
        assertEquals(32000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(file, 36, 4, Charsets.US_ASCII))
        assertEquals(2 * microphone.delivered.get(), header.getInt(40))
        assertEquals(44 + 2 * microphone.delivered.get(), file.size)
        val samples = server.requests.single().wavSamples()
        assertTrue((TONE_START until TONE_END).all { samples[it] == toneSample(it) })
    }

    @Test fun recordingStopsAtSixtySeconds() = serving { server ->
        val microphone = SyntheticMicrophone(::toneSample)
        assertEquals(TEXT, capture(server, AiProvider.OPENAI, microphone, oneShot = true))
        assertEquals(1_920_044, server.requests.single().parts().getValue("file").bytes.size)
        assertEquals(SAMPLE_RATE * 60, microphone.delivered.get())
    }

    @Test fun endOfSpeechAfterSilence() = serving { server ->
        val microphone = utterance()
        assertEquals(TEXT, capture(server, AiProvider.OPENAI, microphone))
        val after = microphone.delivered.get() - TONE_END
        assertTrue("$after samples after the speech", after in SAMPLE_RATE * 11 / 10..SAMPLE_RATE * 16 / 10)
    }

    // A microphone can start with digital silence, and steady speech can last longer than the noise estimate window.
    @Test fun longSpeechAfterSilentStartIsKept() = serving { server ->
        val random = Random(13)
        val speechStart = SAMPLE_RATE * 8 / 10
        val speechEnd = speechStart + SAMPLE_RATE * 5
        val microphone = SyntheticMicrophone { index ->
            when {
                index < SAMPLE_RATE * 3 / 10 -> 0.toShort()
                index in speechStart until speechEnd -> toneSample(index)
                else -> noise(random)
            }
        }
        assertEquals(TEXT, capture(server, AiProvider.OPENAI, microphone))
        val after = microphone.delivered.get() - speechEnd
        assertTrue("$after samples after the speech", after in SAMPLE_RATE * 11 / 10..SAMPLE_RATE * 16 / 10)
    }

    @Test fun noSpeechReturnsNullAfterEightSeconds() = serving { server ->
        val random = Random(11)
        val microphone = SyntheticMicrophone { noise(random) }
        val recognizing = AtomicInteger()
        assertNull(capture(server, AiProvider.OPENAI, microphone, recognizing = recognizing))
        assertEquals(SAMPLE_RATE * 8, microphone.delivered.get())
        assertEquals(0, recognizing.get())
        assertTrue(server.requests.isEmpty())
    }

    @Test fun failureNamesTheProvider() = serving({ _, output ->
        output.jsonResponse(JSONObject().put("error", JSONObject().put("message", "Insufficient Balance")), status = 402)
    }) { server ->
        val failure = assertThrows(CloudFailure::class.java) { capture(server, AiProvider.DEEPSEEK) }
        assertEquals("quota", failure.code)
        assertTrue(failure.message, failure.message.startsWith("DeepSeek: "))
    }

    @Test fun nullTextIsAnInvalidResponse() = serving({ _, output -> output.jsonResponse("""{"text":null}""") }) { server ->
        val failure = assertThrows(CloudFailure::class.java) { capture(server, AiProvider.OPENAI) }
        assertEquals("invalid_response", failure.code)
        assertTrue(failure.message, failure.message.startsWith("OpenAI: "))
    }

    @Test fun redirectIsRefused() = serving({ _, output ->
        output.write("HTTP/1.1 302 Found\r\nLocation: /v1/other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        output.flush()
    }) { server ->
        val failure = assertThrows(CloudFailure::class.java) { capture(server, AiProvider.OPENAI) }
        assertEquals("http_302", failure.code)
        assertEquals(1, server.requests.size)
    }

    @Test fun stopDuringUploadDisconnects() {
        val uploading = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val transcripts = CopyOnWriteArrayList<String>()
        val server = LoopbackServer { _, output ->
            uploading.countDown()
            stopped.await(15, TimeUnit.SECONDS)
            // JSON white space keeps an open connection valid, so only a closed socket refuses a write.
            // A connection that is still open after the probes gets a valid transcript.
            val reply = JSONObject().put("text", TEXT).toString().toByteArray()
            try {
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${PROBES + reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                repeat(PROBES) { output.write(' '.code); output.flush(); Thread.sleep(50) }
                output.write(reply)
                output.flush()
            } catch (_: IOException) { closed.countDown() }
        }
        val fixture = ShellFixture("voice-upload")
        val robot = ShellRobot("voice-upload")
        try {
            fixture.install(voice = { app, scope, transcript ->
                VoiceSession(app, scope, { text -> transcripts += text; transcript(text) }, VoiceSettings(app, fixture.voiceStore)) {
                    CloudSpeechInput(AiAccount(AiProvider.OPENAI, KEY), Endpoints { server.url }, PcmCapture(utterance()))
                }
            })
            fixture.launch().use { scenario ->
                robot.allowMicrophone(scenario)
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading }
                scenario.onActivity { model.startVoice(oneShot = false) }
                assertTrue(uploading.await(10, TimeUnit.SECONDS))
                robot.waitUntil { model.voice.phase == "recognizing" }
                scenario.onActivity { model.voice.stop() }
                stopped.countDown()
                val disconnected = closed.await(2, TimeUnit.SECONDS)
                SystemClock.sleep(300)
                assertTrue("$transcripts", transcripts.isEmpty())
                assertTrue("The upload stayed open after stop()", disconnected)
                assertEquals("idle", model.voice.phase)
                assertTrue(model.snapshot.draft.text.isEmpty())
            }
        } finally {
            stopped.countDown()
            server.close()
            fixture.remove()
        }
    }

    @Test fun noAccountShowsSettingsStatus() = voiceNeedsSettings("voice-no-account", saveKey = false,
        context.getString(R.string.voice_no_account)) { robot, scenario ->
        robot.click(robot.text(R.string.open_settings))
        robot.waitUntil { robot.composed(scenario, "settings-pane") }
        robot.waitFor(robot.text(R.string.key_missing))
    }

    @Test fun unreadableKeyShowsTheReadFailure() = voiceNeedsSettings("voice-key-read", saveKey = true,
        context.getString(R.string.voice_transcription_failed, context.getString(R.string.cloud_key_read)),
        // A stored key without its provider does not read (key_read), so an account exists but is unusable.
        breakAccount = { fixture ->
            check(context.getSharedPreferences(fixture.name, android.content.Context.MODE_PRIVATE).edit().remove("provider").commit())
        }) { robot, _ -> assertNull(robot.find(robot.text(R.string.voice_no_account))) }

    /**
     * Taps «Голосовой ввод» after [breakAccount] runs on the loaded shell. The microphone stays off, [status] shows, and
     * «Открыть настройки» is a target. Then [then] continues on the same screen.
     */
    private fun voiceNeedsSettings(prefix: String, saveKey: Boolean, status: String, breakAccount: (ShellFixture) -> Unit = {},
        then: (ShellRobot, ActivityScenario<CloudTestActivity>) -> Unit) {
        val fixture = ShellFixture(prefix)
        val robot = ShellRobot(prefix)
        try {
            fixture.install(saveKey = saveKey)
            fixture.launch().use { scenario ->
                robot.allowMicrophone(scenario)
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading }
                breakAccount(fixture)
                assertNull(robot.find(robot.text(R.string.open_settings)))
                robot.click(robot.text(R.string.composer_voice))
                robot.waitFor(status)
                assertFalse(model.voice.active)
                assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
                robot.assertTarget(robot.text(R.string.open_settings))
                then(robot, scenario)
            }
        } finally { fixture.remove() }
    }

    @Test fun anthropicUsesThePhonePath() = serving { server ->
        val endpoints = Endpoints { server.url }
        for (provider in listOf(AiProvider.OPENAI, AiProvider.OPENROUTER, AiProvider.DEEPSEEK))
            assertTrue(provider.name, speechInputFor(AiAccount(provider, KEY), context, endpoints) is CloudSpeechInput)
        assertTrue(speechInputFor(AiAccount(AiProvider.ANTHROPIC, KEY), context, endpoints) is PhoneSpeechInput)
        // The phone recognizer gives the text, and no request leaves the phone.
        val recognizer = FakePhoneRecognizer()
        val text = runBlocking {
            val result = async(Dispatchers.Default) { PhoneSpeechInput(recognizer, context).capture(true, {}, {}) }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (recognizer.listener == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            val listener = checkNotNull(recognizer.listener) { "The phone recognizer did not start" }
            instrumentation.runOnMainSync { listener.onResults(recognitionResults(TEXT)) }
            result.await()
        }
        assertEquals(TEXT, text)
        assertTrue(server.requests.isEmpty())
    }
}
