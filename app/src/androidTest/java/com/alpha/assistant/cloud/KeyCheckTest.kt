package com.alpha.assistant.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyCheckTest {
    private val key = "alpha-test-only"

    private fun reply(output: OutputStream, status: Int, body: String = "{}", extra: String = "") {
        val bytes = body.toByteArray()
        output.write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun serving(status: Int, body: String = "{}", block: (LoopbackServer) -> Unit) {
        LoopbackServer { _, output -> reply(output, status, body) }.use { block(it) }
    }

    private fun check(server: LoopbackServer, provider: AiProvider, secret: String = key) =
        runBlocking { checkKey(AiAccount(provider, secret), Endpoints { server.url }) }

    @Test fun openRouterCheckUsesKeyEndpoint() = serving(200) {
        assertEquals(KeyCheck.ACCEPTED, check(it, AiProvider.OPENROUTER))
        assertEquals("GET /api/v1/key", it.requests.single().let { r -> "${r.method} ${r.path}" })
        assertEquals("Bearer alpha-test-only", it.requests.single().headers["authorization"])
        // The key check is a plain GET: it sends the auth pair and no stream Accept value.
        assertFalse(it.requests.single().headers.containsKey("accept"))
    }

    @Test fun openAiAndDeepSeekRequests() {
        serving(200, """{"is_available": true, "balance_infos": []}""") {
            assertEquals(KeyCheck.ACCEPTED, check(it, AiProvider.OPENAI))
            assertEquals(KeyCheck.ACCEPTED, check(it, AiProvider.DEEPSEEK))
            assertEquals(listOf("GET /v1/models", "GET /user/balance"), it.requests.map { r -> "${r.method} ${r.path}" })
            assertTrue(it.requests.all { r -> r.headers["authorization"] == "Bearer alpha-test-only" })
        }
    }

    @Test fun rejectedAndLimitedKeys() {
        for (provider in AiProvider.entries) {
            serving(401) { assertEquals(provider.name, KeyCheck.REJECTED, check(it, provider)) }
            serving(403) { assertEquals(provider.name, KeyCheck.LIMITED, check(it, provider)) }
            serving(503) { assertEquals(provider.name, KeyCheck.UNREACHABLE, check(it, provider)) }
        }
    }

    @Test fun paymentRequiredIsNoCredit() {
        for (provider in AiProvider.entries) serving(402) { assertEquals(provider.name, KeyCheck.NO_CREDIT, check(it, provider)) }
    }

    @Test fun deepSeekEmptyBalanceIsNoCredit() = serving(200, """{"is_available": false, "balance_infos": []}""") {
        assertEquals(KeyCheck.NO_CREDIT, check(it, AiProvider.DEEPSEEK))
        assertEquals("/user/balance", it.requests.single().path)
    }

    @Test fun checkSendsOneRequestToSelectedProvider() = serving(401) {
        val only = Endpoints { provider -> if (provider == AiProvider.DEEPSEEK) it.url else error("No request to $provider") }
        val deepSeekKey = "sk-" + "0123456789abcdef".repeat(2)
        assertEquals(KeyCheck.REJECTED, runBlocking { checkKey(AiAccount(AiProvider.DEEPSEEK, deepSeekKey), only) })
        assertEquals("/user/balance", it.requests.single().path)
    }

    @Test fun unreachableWhenServerClosed() {
        val url = LoopbackServer { _, _ -> }.use { it.url }
        assertEquals(KeyCheck.UNREACHABLE, runBlocking { checkKey(AiAccount(AiProvider.OPENAI, key), Endpoints { url }) })
    }

    @Test fun redirectIsNotFollowed() {
        LoopbackServer { _, output -> reply(output, 302, "", "Location: /elsewhere\r\n") }.use {
            assertEquals(KeyCheck.UNREACHABLE, check(it, AiProvider.OPENAI))
            assertEquals(1, it.requests.size)
        }
    }

    @Test fun anthropicCheckUsesApiKeyHeader() = serving(200) {
        assertEquals(KeyCheck.ACCEPTED, check(it, AiProvider.ANTHROPIC))
        val request = it.requests.single()
        assertEquals("GET /v1/models", "${request.method} ${request.path}")
        assertEquals(key, request.headers["x-api-key"])
        assertEquals("2026-10-21", request.headers["anthropic-version"])
        assertFalse(request.headers.containsKey("authorization"))
    }

    @Test fun endpointsAcceptOnlyProductionOrLoopback() {
        for (provider in AiProvider.entries) assertEquals(provider.profile.baseUrl, Endpoints.PRODUCTION.checked(provider))
        for (bad in listOf("http://example.com", "https://127.0.0.1", "http://user@127.0.0.1", "http://127.0.0.1?x=1", "http://127.0.0.1#x", "https://api.openai.com.evil.test")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Endpoints { bad }.checked(AiProvider.OPENAI) }
        }
        assertEquals("http://localhost:8080", Endpoints { "http://localhost:8080" }.checked(AiProvider.OPENAI))
    }
}
