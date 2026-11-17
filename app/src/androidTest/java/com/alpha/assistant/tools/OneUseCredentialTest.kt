package com.alpha.assistant.tools

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Failure cases precede the channel implementation: untrusted UID, absent peer, leaked listener, raw error text,
 * a malformed or unknown account envelope, and a chat model outside the curated list. */
class OneUseCredentialTest {
    @Test fun untrustedPeerAndTimeoutCloseTheAbstractListener() {
        val name="test-${UUID.randomUUID()}"
        val ready=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val thread=Thread {
            try {OneUseCredential.receive(name,1000) {ready.countDown()}}
            catch(problem: Exception) {failure.set(problem)}
        }
        thread.start()
        assertTrue(ready.await(2,TimeUnit.SECONDS))
        LocalSocket().use {it.connect(LocalSocketAddress("compose-credential-$name"))}
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertNotNull(failure.get())
        LocalServerSocket("compose-credential-$name").close()
        val timeout="timeout-${UUID.randomUUID()}"
        assertTrue(runCatching {OneUseCredential.receive(timeout,20) {}}.isFailure)
        LocalServerSocket("compose-credential-$timeout").close()
    }

    @Test fun envelopeGivesTheAccountOfEachProvider() {
        for (provider in AiProvider.entries) {
            val key = "synthetic-${provider.id}-K3y_!~"
            assertEquals(AiAccount(provider, key), OneUseCredential.account("${provider.id}\n$key".toByteArray(Charsets.US_ASCII)))
        }
    }

    // A malformed envelope can hold the key in any part, so no failure text shows a part of it.
    @Test fun malformedEnvelopeFailsWithoutItsText() {
        val envelopes = listOf("synthetic-secret", "\nsynthetic-secret", "openai\n", "synthetic-secret\nsynthetic-secret",
            "OpenAI\nsynthetic-secret", "openai \nsynthetic-secret", "openai\nsynthetic-secret\nsynthetic-secret",
            "openai\nsynthetic secret", "openai\nsynthetic-secret\r", "openai\nsynthetic-ключ", "openai\r\nsynthetic-secret")
        for (envelope in envelopes) {
            val failure = runCatching { OneUseCredential.account(envelope.toByteArray(Charsets.UTF_8)) }.exceptionOrNull()
            assertTrue(envelope, failure is IllegalStateException)
            assertFalse(envelope, failure!!.message.orEmpty().contains("synthetic"))
        }
    }

    @Test fun chatModelIsCuratedOrTheFirstOfTheProvider() {
        val account = AiAccount(AiProvider.OPENROUTER, "synthetic-key")
        assertEquals("openai/gpt-6.1-terra", account.liveChatModel(null))
        assertEquals("claude-opus-5-5", AiAccount(AiProvider.ANTHROPIC, "synthetic-key").liveChatModel("claude-opus-5-5"))
        for (model in listOf("deepseek-flash", "gpt-6.1-terra", "google/gemini-4-carbon", "")) {
            assertTrue(model, runCatching { account.liveChatModel(model) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
