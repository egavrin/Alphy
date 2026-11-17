package com.alpha.assistant.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 13: each provider failure keeps its code and names the active provider. */
@RunWith(AndroidJUnit4::class)
class ProviderErrorsTest {
    private val key = "alpha-test-only"
    private val lowCredit = """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."}}"""

    @Test fun mapsQuotaForEachProvider() {
        assertEquals("quota", providerFailure(AiProvider.OPENROUTER, 402, null, false).code)
        assertEquals("quota", providerFailure(AiProvider.OPENAI, 429, """{"error":{"code":"insufficient_quota"}}""", false).code)
        assertEquals("rate_limit", providerFailure(AiProvider.OPENAI, 429, """{"error":{"code":"rate_limit_exceeded"}}""", false).code)
        assertEquals("unavailable", providerFailure(AiProvider.ANTHROPIC, 529, null, false).code)
        assertEquals("quota", providerFailure(AiProvider.DEEPSEEK, 402, null, false).code)
        assertTrue(providerFailure(AiProvider.OPENROUTER, 402, null, false).message.startsWith("OpenRouter"))
    }

    @Test fun openRouterModerationIsRefusalAndOthersKeepAuth() {
        val moderation = providerFailure(AiProvider.OPENROUTER, 403, null, false)
        assertEquals("refusal", moderation.code)
        assertTrue(moderation.message, moderation.message.startsWith("OpenRouter: "))
        assertTrue(providerFailure(AiProvider.OPENROUTER, 403, null, true).message.startsWith("Поиск, OpenRouter: "))
        assertEquals("auth", providerFailure(AiProvider.OPENROUTER, 401, null, false).code)
        for (provider in listOf(AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.DEEPSEEK))
            assertEquals(provider.name, "auth", providerFailure(provider, 403, null, false).code)
    }

    @Test fun anthropicLowCreditIsQuota() {
        assertEquals("quota", providerFailure(AiProvider.ANTHROPIC, 400, lowCredit, false).code)
        assertEquals("quota", providerFailure(AiProvider.ANTHROPIC, 400, lowCredit.replace("too low to access", "TOO LOW for"), false).code)
        assertEquals("http_400", providerFailure(AiProvider.ANTHROPIC, 400, """{"error":{"message":"max_tokens is required"}}""", false).code)
        assertEquals("http_400", providerFailure(AiProvider.ANTHROPIC, 400, null, false).code)
    }

    @Test fun authErrorsOpenSettings() {
        for (status in listOf(401, 403)) assertTrue(providerFailure(AiProvider.OPENAI, status, null, false).code in KEY_ERRORS)
    }

    @Test fun everyMessageNamesTheProviderAndSearchAddsItsPrefix() {
        for (provider in AiProvider.entries) for (status in listOf(401, 402, 429, 503, 418)) {
            assertTrue(providerFailure(provider, status, null, false).message.startsWith("${provider.displayName}: "))
            assertTrue(providerFailure(provider, status, null, true).message.startsWith("Поиск, ${provider.displayName}: "))
        }
        assertEquals("http_418", providerFailure(AiProvider.OPENAI, 418, null, false).code)
    }

    @Test fun httpErrorBodyIsReadFromTheWire() {
        val body = """{"error":{"code":"insufficient_quota"}}"""
        val failure = failure(AiProvider.OPENAI) { _, output ->
            output.write("HTTP/1.1 429 Too Many\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
            output.flush()
        }
        assertEquals("quota", failure.code)
        assertTrue(failure.message, failure.message.startsWith("OpenAI: "))
    }

    @Test fun anErrorBodyOverTheLimitDoesNotChangeTheMapping() {
        val padding = "x".repeat(40_000)
        val failure = failure(AiProvider.OPENROUTER) { _, output ->
            output.write("HTTP/1.1 402 Payment Required\r\nContent-Length: ${padding.length}\r\nConnection: close\r\n\r\n$padding".toByteArray())
            output.flush()
        }
        assertEquals("quota", failure.code)
    }

    @Test fun contentFilterIsRefusalOnOpenRouterAndDeepSeek() {
        for (provider in listOf(AiProvider.OPENROUTER, AiProvider.DEEPSEEK)) {
            val exchange = exchange(provider, plainRequest()) { _, output ->
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                output.write("data: ${chunk(JSONObject().put("content", "Partial"))}\n\n".toByteArray())
                output.write("data: ${chunk(JSONObject(), "content_filter")}\n\ndata: [DONE]\n\n".toByteArray())
                output.flush()
            }
            assertEquals(provider.name, "refusal", exchange.failure?.code)
            assertTrue(exchange.failure?.message, exchange.failure!!.message.startsWith("${provider.displayName}: "))
            assertTrue(exchange.events.none { it is CloudEvent.RoundComplete })
        }
    }

    @Test fun openRouterDeltaRefusalWithStopIsRefusal() {
        val exchange = exchange(AiProvider.OPENROUTER, plainRequest()) { _, output ->
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            output.write("data: ${chunk(JSONObject().put("refusal", "I cannot help with that."))}\n\n".toByteArray())
            output.write("data: ${chunk(JSONObject(), "stop")}\n\ndata: [DONE]\n\n".toByteArray())
            output.flush()
        }
        assertEquals("refusal", exchange.failure?.code)
        assertTrue(exchange.events.none { it is CloudEvent.RoundComplete })
    }

    @Test fun openAiRefusalPartAfterTextIsRefusal() {
        val exchange = exchange(AiProvider.OPENAI, plainRequest()) { _, output ->
            output.responsesHeaders()
            output.responsesEvent("response.output_text.delta", JSONObject().put("output_index", 0).put("content_index", 0).put("delta", "Partial"))
            output.responsesEvent("response.content_part.added", JSONObject().put("output_index", 0).put("content_index", 1)
                .put("part", JSONObject().put("type", "refusal").put("refusal", "")))
        }
        assertEquals("refusal", exchange.failure?.code)
        assertTrue(exchange.events.none { it is CloudEvent.RoundComplete })
    }

    @Test fun openAiIncompleteContentFilterIsRefusal() {
        val exchange = exchange(AiProvider.OPENAI, plainRequest()) { _, output ->
            output.responsesHeaders()
            output.responsesEvent("response.incomplete", JSONObject().put("response", JSONObject()
                .put("status", "incomplete").put("incomplete_details", JSONObject().put("reason", "content_filter"))))
        }
        assertEquals("refusal", exchange.failure?.code)
    }

    @Test fun emptyReplyReportsReasoningFromOpenRouterDetails() {
        val recorded = CopyOnWriteArrayList<String>()
        val exchange = exchange(AiProvider.OPENROUTER, plainRequest(), record = { recorded += it }) { _, output ->
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            val details = JSONArray().put(JSONObject().put("type", "reasoning.text").put("text", "thinking").put("index", 0))
            output.write("data: ${chunk(JSONObject().put("reasoning_details", details))}\n\n".toByteArray())
            output.write("data: ${chunk(JSONObject(), "stop")}\n\ndata: [DONE]\n\n".toByteArray())
            output.flush()
        }
        assertEquals("empty_response", exchange.failure?.code)
        assertTrue(exchange.failure?.message, exchange.failure!!.message.startsWith("OpenRouter "))
        assertTrue(recorded.toString(), recorded.single().contains("reasoning=true"))
    }

    private fun chunk(delta: JSONObject, finish: String? = null) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", delta).apply { if (finish != null) put("finish_reason", finish) }))

    private fun plainRequest() = CloudRequest(listOf(CloudTurn("user", "Hi")), false, "Reply in one line.")

    private class Exchange(val events: List<CloudEvent>, val failure: CloudFailure?)

    private fun exchange(provider: AiProvider, request: CloudRequest, record: (String) -> Unit = {},
        reply: (JSONObject, OutputStream) -> Unit): Exchange = LoopbackServer { raw, output -> reply(JSONObject(raw.body), output) }.use { server ->
        val events = CopyOnWriteArrayList<CloudEvent>()
        val failure = try {
            runBlocking {
                CloudChatProvider({ AiAccount(provider, key) }, Endpoints { server.url }, recordEmptyCompletion = record)
                    .respond(key, request, CloudSession()) { events += it }
            }
            null
        } catch (failure: CloudFailure) { failure }
        assertTrue(server.failures.toString(), server.failures.isEmpty())
        Exchange(events, failure)
    }

    private fun failure(provider: AiProvider, reply: (JSONObject, OutputStream) -> Unit): CloudFailure =
        checkNotNull(exchange(provider, plainRequest(), reply = reply).failure)
}
