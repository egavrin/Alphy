package com.alpha.assistant.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.OPENAI_LIVE_MODEL
import com.alpha.assistant.tools.liveCheck
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The GPT-Live half of the device echo check (spec 6.4): the loudspeaker echo captured on a real phone must not become
 * an input transcript. The captures are `voice/echo/<model>-reply.wav` and `<model>-proposal.wav`, one pair for each phone model.
 * `run.py live-check` starts this paid check. It skips when no pair exists.
 */
class LiveEchoTranscriptTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun models(): List<String> {
        val files = assets.list("voice/echo").orEmpty().toSet()
        return files.filter { it.endsWith("-reply.wav") }.map { it.removeSuffix("-reply.wav") }
            .filter { "$it-proposal.wav" in files }.sorted()
    }

    private fun capture(name: String) = wavSamples(assets.open("voice/echo/$name.wav").use { it.readBytes() })

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** One session: the capture at real-time pace, 3 s of silence, then the close. Returns the input transcript. */
    private fun transcribe(key: String, samples: ShortArray): String {
        val started = CompletableFuture<Unit>()
        val closed = CompletableFuture<Unit>()
        val transcript = StringBuilder()
        val listener = object : LiveListener {
            override fun onText(text: String) {
                when (val event = LiveProtocol.parse(text)) {
                    is LiveEvent.Started -> started.complete(Unit)
                    is LiveEvent.InputTranscript -> synchronized(transcript) { transcript.append(event.delta) }
                    is LiveEvent.Failure -> started.completeExceptionally(AssertionError("GPT-Live error: ${event.message}"))
                    is LiveEvent.Closed -> {
                        started.completeExceptionally(AssertionError("GPT-Live closed the session: ${event.reason}"))
                        closed.complete(Unit)
                    }
                    else -> Unit
                }
            }
            override fun onFailure(status: Int?, message: String) {
                val failure = AssertionError("Connection failed (HTTP $status): $message")
                started.completeExceptionally(failure)
                closed.completeExceptionally(failure)
            }
            override fun onClosed() { closed.complete(Unit) }
        }
        val connection = OkHttpLiveTransport().connect(liveUrl(Endpoints.PRODUCTION), key, listener)
        try {
            assertTrue(connection.send(LiveProtocol.start(emptyList())))
            try {
                started.get(10, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                fail("No session.started within 10 s.")
            } catch (e: ExecutionException) {
                throw e.cause!!
            }
            val blocks = (samples.size + LIVE_BLOCK - 1) / LIVE_BLOCK + 3000 / 20
            val begin = System.nanoTime()
            for (i in 0 until blocks) {
                val block = ShortArray(LIVE_BLOCK)
                val from = minOf(i * LIVE_BLOCK, samples.size)
                samples.copyInto(block, 0, from, minOf(from + LIVE_BLOCK, samples.size))
                assertTrue("The socket refused audio.", connection.send(LiveProtocol.audio(block)))
                val wait = begin + (i + 1) * 20_000_000L - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            connection.send(LiveProtocol.close())
            // The transcript deltas may still arrive until the server closes. A late close only shortens the wait.
            try { closed.get(10, TimeUnit.SECONDS) } catch (_: TimeoutException) { } catch (_: ExecutionException) { }
        } finally {
            connection.close()
        }
        return synchronized(transcript) { transcript.toString() }
    }

    @Test fun echoIsNotTranscribed() = liveCheck("LiveEchoTranscriptTest#echoIsNotTranscribed",
        missing = { account ->
            when {
                account.provider != AiProvider.OPENAI -> "Only OpenAI has GPT-Live."
                models().isEmpty() -> "No loudspeaker captures in voice/echo."
                else -> null
            }
        },
        model = { OPENAI_LIVE_MODEL }) { account, _ ->
        val replyTriples = words(REPLY_TEXT).windowed(3).toSet()
        val lines = ArrayList<String>()
        val problems = ArrayList<String>()
        for (model in models()) {
            val replyText = transcribe(account.key, capture("$model-reply"))
            val proposalText = transcribe(account.key, capture("$model-proposal"))
            val copied = words(replyText).windowed(3).count { it in replyTriples }
            val said = "да" in words(proposalText)
            if (copied > 0) problems += "$model: the reply echo gave $copied reply word triples."
            if (said) problems += "$model: the proposal echo gave «да»."
            lines += "$model: reply transcript «$replyText»; proposal transcript «$proposalText»; " +
                if (copied == 0 && !said) "PASS" else "FAIL"
        }
        val result = lines.joinToString("\n")
        assertTrue("$result\n${problems.joinToString("\n")}", problems.isEmpty())
        result
    }

    private companion object {
        /** The words of `voice/live-reply.wav` (Task 1). */
        const val REPLY_TEXT = "Солнечная система состоит из Солнца и восьми планет. Ближе всего к Солнцу Меркурий, затем Венера, Земля и Марс. " +
            "Это каменные планеты земной группы. За поясом астероидов находятся газовые гиганты Юпитер и Сатурн, а дальше ледяные гиганты Уран и Нептун. " +
            "За Нептуном лежит пояс Койпера с карликовыми планетами, включая Плутон."
    }
}
