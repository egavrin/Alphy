package com.alpha.assistant.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.LoopbackServer
import com.alpha.assistant.cloud.jsonResponse
import com.alpha.assistant.tools.liveCheck
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The live voice input check of spec 12A.10: `voice/russian.wav` goes once to the provider of the received account.
 * Failure cases: a provider without cloud transcription, a fixture that the end-of-speech detector cuts or never ends,
 * and a key in the evidence. `run.py live-check` starts the live test.
 */
class LiveVoiceInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun fixtureSamples() = wavSamples(instrumentation.context.assets.open("voice/russian.wav").use { it.readBytes() })

    /** The fixture samples, then digital silence without end, so the end-of-speech detector ends the utterance. */
    private fun fixture(samples: ShortArray) = PcmSource {
        object : PcmStream {
            private var position = 0
            override fun read(buffer: ShortArray): Int {
                buffer.fill(0)
                val count = minOf(buffer.size, samples.size - position)
                samples.copyInto(buffer, 0, position, position + count)
                position += count
                return buffer.size
            }
            override fun close() = Unit
        }
    }

    /** One continuous utterance of the fixture through the cloud speech input. The loopback test runs this same call. */
    private fun transcribe(account: AiAccount, endpoints: Endpoints, samples: ShortArray) = runBlocking {
        CloudSpeechInput(account, endpoints, PcmCapture(fixture(samples))).capture(false, onLevel = {}, onRecognizing = {})
    }

    @Test fun fixtureReachesTheUploadWhole() {
        val samples = fixtureSamples()
        val text = "Поставь таймер на пять минут"
        LoopbackServer { _, output -> output.jsonResponse(JSONObject().put("text", text)) }.use { server ->
            assertEquals(text, transcribe(AiAccount(AiProvider.OPENAI, "alpha-test-only"), Endpoints { server.url }, samples))
            val uploaded = server.requests.single().wavSamples()
            assertArrayEquals(samples, uploaded.copyOf(samples.size))
            // The detector ends the utterance about 1.2 s after the speech, far before the 60 s limit.
            assertTrue("${uploaded.size} samples", uploaded.size in samples.size..samples.size + SAMPLE_RATE * 2)
        }
    }

    @Test fun transcribeRussianFixture() = liveCheck("LiveVoiceInputTest#transcribeRussianFixture",
        // The phone recognizer is not a provider check, so a provider without transcription skips (spec 12A.10).
        missing = { account -> "${account.provider.displayName} has no cloud transcription.".takeIf { account.provider.profile.transcription == null } },
        model = { it.provider.profile.transcription?.model }) { account, _ ->
        assertTrue(speechInputFor(account, instrumentation.targetContext, Endpoints.PRODUCTION) is CloudSpeechInput)
        val text = transcribe(account, Endpoints.PRODUCTION, fixtureSamples()).orEmpty()
        val lower = text.lowercase()
        assertTrue("The transcript lacks «таймер» and «пять» or «5»: $text", "таймер" in lower && ("пять" in lower || "5" in lower))
        text
    }
}
