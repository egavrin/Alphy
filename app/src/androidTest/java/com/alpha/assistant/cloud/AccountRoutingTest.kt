package com.alpha.assistant.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The stored account selects the wire and guards every request against a key change. */
@RunWith(AndroidJUnit4::class)
class AccountRoutingTest {
    private val key = "alpha-test-only"

    private fun provider(account: () -> AiAccount?, server: LoopbackServer) =
        CloudChatProvider(account, Endpoints { server.url }, recordEmptyCompletion = {})

    private fun respond(provider: ChatProvider, request: CloudRequest = decisionRequest()) =
        runBlocking { provider.respond(key, request, CloudSession()) {} }

    private fun OutputStream.sse(vararg data: String) {
        responsesHeaders()
        data.forEach { write("data: $it\n\n".toByteArray()) }
        flush()
    }

    private fun toolCall(id: String, name: String, arguments: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
            .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments)))))
            .put("finish_reason", "tool_calls"))).toString()

    private fun OutputStream.searchCall() = sse(toolCall("c0", "web_search", "{\"query\":\"news\"}"), "[DONE]")

    @Test fun staleAccountStopsRequest() {
        var account = AiAccount(AiProvider.OPENAI, key)
        LoopbackServer { _, _ -> throw AssertionError("no HTTP call expected") }.use { server ->
            val provider = provider({ account }, server)
            account = AiAccount(AiProvider.ANTHROPIC, "other-key")
            assertEquals("stale_account", assertThrows(CloudFailure::class.java) { respond(provider) }.code)
            assertEquals("stale_account",
                assertThrows(CloudFailure::class.java) { runBlocking { provider.search(key, "news", CloudSession()) } }.code)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun missingAccountStopsRequest() {
        LoopbackServer { _, _ -> throw AssertionError("no HTTP call expected") }.use { server ->
            assertEquals("stale_account", assertThrows(CloudFailure::class.java) { respond(provider({ null }, server)) }.code)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun accountChangeBeforeAgentSearchIsStale() {
        var account = AiAccount(AiProvider.OPENROUTER, key)
        LoopbackServer { _, output ->
            // The model round asks for a search. The account then changes before the search runs.
            account = AiAccount(AiProvider.DEEPSEEK, "other-key")
            output.searchCall()
        }.use { server ->
            val request = CloudRequest(listOf(CloudTurn("user", "News?")), true, scheduledAgent = true)
            assertEquals("stale_account", assertThrows(CloudFailure::class.java) { respond(provider({ account }, server), request) }.code)
            assertEquals(listOf("/api/v1/chat/completions"), server.requests.map { it.path })
        }
    }

    @Test fun accountChangeBeforeDecisionSearchIsStale() {
        var account = AiAccount(AiProvider.OPENROUTER, key)
        var round = 0
        LoopbackServer { _, output ->
            if (round++ == 0) {
                val arguments = JSONObject().put("kind", "answer").put("text", "x").put("brief", "").put("search", "required").toString()
                output.sse(toolCall("d0", "submit_chat_decision", arguments), "[DONE]")
            } else {
                account = AiAccount(AiProvider.DEEPSEEK, "other-key")
                output.searchCall()
            }
        }.use { server ->
            assertEquals("stale_account",
                assertThrows(CloudFailure::class.java) { respond(provider({ account }, server), decisionRequest().copy(search = true)) }.code)
            assertEquals(2, server.requests.size)
            assertTrue(server.requests.none { it.path.contains("search") })
        }
    }

    private fun pathOf(provider: AiProvider): String = LoopbackServer { _, output -> output.sse("{}") }.use { server ->
        runCatching { respond(provider({ AiAccount(provider, key) }, server)) }
        server.requests.first().path
    }

    @Test fun openRouterAccountUsesOpenRouterPath() = assertEquals("/api/v1/chat/completions", pathOf(AiProvider.OPENROUTER))

    @Test fun openAiAccountUsesResponsesPath() = assertEquals("/v1/responses", pathOf(AiProvider.OPENAI))
}
