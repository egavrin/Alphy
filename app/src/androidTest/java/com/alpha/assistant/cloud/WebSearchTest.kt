package com.alpha.assistant.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.data.Source
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Search reply fixtures. OpenAiResponsesWorkflowTest uses them too.

/** A Responses search reply: one search call with its [sources], then one answer [text] with its url_citation [marks]. */
internal fun openAiSearchReply(text: String, sources: List<String>, vararg marks: JSONObject): JSONObject = JSONObject().put("output", JSONArray()
    .put(JSONObject().put("type", "web_search_call").put("status", "completed").put("action", JSONObject().put("type", "search")
        .put("sources", JSONArray(sources.map { JSONObject().put("type", "url").put("url", it) }))))
    .put(JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(JSONObject()
        .put("type", "output_text").put("text", text).put("annotations", JSONArray(marks.toList()))))))

/** The model JSON entry of one source (spec 9.4). */
internal fun sourceEntry(url: String, title: String, publishedAt: Any? = null): JSONObject =
    JSONObject().put("url", url).put("title", title).put("publishedAt", publishedAt ?: JSONObject.NULL)

/** A Messages search reply: [results] in one web_search_tool_result block, then the [cited] text blocks, then the model JSON [entries]. */
internal fun anthropicSearchReply(results: List<JSONObject>, cited: List<JSONObject>, entries: String, stopReason: String = "end_turn"): JSONObject =
    JSONObject().put("type", "message").put("role", "assistant").put("stop_reason", stopReason).put("content", JSONArray()
        .put(JSONObject().put("type", "server_tool_use").put("id", "srvtoolu_1").put("name", "web_search")
            .put("input", JSONObject().put("query", "Fact")))
        .put(JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "srvtoolu_1").put("content", JSONArray(results)))
        .apply { for (block in cited) put(block) }
        .put(JSONObject().put("type", "text").put("text", entries)))

/** One `web_search_result` of a Messages reply. */
internal fun anthropicResult(url: String, title: String, pageAge: Any? = null): JSONObject =
    JSONObject().put("type", "web_search_result").put("url", url).put("title", title).put("page_age", pageAge ?: JSONObject.NULL)

/** One text block with one citation for each pair of a URL and its `cited_text`. */
internal fun anthropicCited(text: String, vararg citations: Pair<String, String>): JSONObject = JSONObject().put("type", "text").put("text", text)
    .put("citations", JSONArray(citations.map { (url, cited) ->
        JSONObject().put("type", "web_search_result_location").put("url", url).put("title", "Page").put("cited_text", cited)
    }))

/** Spec 9: each provider's search maps its own citations to the shared source contract. */
@RunWith(AndroidJUnit4::class)
class WebSearchTest {
    private val key = "alpha-test-only"
    private val first = "https://example.com/first"
    private val second = "https://example.org/second"
    private val third = "https://example.net/third"
    private var account = AiProvider.OPENROUTER
    private var reply = JSONObject()
    private var request: LoopbackRequest? = null

    /** Runs one search through an [account] account. The loopback answers with [reply]. */
    private fun search(query: String): List<Source> = LoopbackServer { _, output -> output.jsonResponse(reply) }.use { server ->
        val provider = CloudChatProvider({ AiAccount(account, key) }, Endpoints { server.url })
        try {
            runBlocking { provider.search(key, query, CloudSession()) }
        } finally {
            assertTrue(server.failures.toString(), server.failures.isEmpty())
            request = server.requests.singleOrNull()
        }
    }

    private fun failure(query: String = "Fact") = assertThrows(CloudFailure::class.java) { search(query) }

    /** An OpenRouter reply: the answer [content] and one url_citation annotation with page content for each citation. */
    private fun openRouter(content: String, vararg citations: Pair<String, String>, finish: String = "stop") =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish).put("message", JSONObject()
            .put("role", "assistant").put("content", content).put("annotations", JSONArray(citations.map { (url, text) ->
                JSONObject().put("type", "url_citation").put("url_citation", JSONObject().put("url", url).put("title", "Page")
                    .put("content", text).put("start_index", 0).put("end_index", 1))
            })))))

    private fun entries(vararg entries: JSONObject) = JSONArray(entries.toList()).toString()

    @Test fun openRouterSearchMapsAnnotations() {
        reply = openRouter(entries(sourceEntry(first, "First"), sourceEntry(second, "Second")),
            first to "First page text", second to "Second page text")
        val sources = search("Fact")
        assertEquals(listOf(first, second), sources.map { it.url })
        assertEquals(listOf("First", "Second"), sources.map { it.title })
        assertEquals(listOf("First page text", "Second page text"), sources.map { it.summary })
        val request = checkNotNull(request)
        assertEquals("/api/v1/chat/completions", request.path)
        assertEquals("Bearer $key", request.headers["authorization"])
        val json = JSONObject(request.body)
        assertFalse(json.getBoolean("stream"))
        assertEquals("openai/gpt-6.1-terra", json.getString("model"))
        val tool = json.getJSONArray("tools").getJSONObject(0)
        assertEquals("openrouter:web_search", tool.getString("type"))
        assertEquals("exa", tool.getJSONObject("parameters").getString("engine"))
        assertEquals(5, tool.getJSONObject("parameters").getInt("max_results"))
        assertTrue(json.getJSONObject("provider").getBoolean("require_parameters"))
        assertEquals("deny", json.getJSONObject("provider").getString("data_collection"))
        val messages = json.getJSONArray("messages")
        assertEquals("Fact", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    @Test fun openAiSearchUsesResponsesApi() {
        account = AiProvider.OPENAI
        val prose = "The first page states the fact. "
        val mark = "([example.com]($first))"
        val text = prose + mark + "\n" + entries(sourceEntry(first, "First"), sourceEntry(second, "Second"), sourceEntry(third, "Third"))
        reply = openAiSearchReply(text, listOf(second), JSONObject().put("type", "url_citation")
            .put("start_index", prose.length).put("end_index", prose.length + mark.length).put("url", first).put("title", "First"))
        val sources = search("Fact")
        // The answer cites the first URL, the search call lists the second, and nothing cites the third.
        assertEquals(listOf(first, second), sources.map { it.url })
        assertEquals(listOf("The first page states the fact.", ""), sources.map { it.summary })
        val request = checkNotNull(request)
        assertEquals("/v1/responses", request.path)
        val json = JSONObject(request.body)
        assertFalse(json.getBoolean("store"))
        assertEquals("gpt-6.1-terra", json.getString("model"))
        assertEquals("web_search", json.getJSONArray("tools").getJSONObject(0).getString("type"))
        val include = json.getJSONArray("include")
        assertTrue(include.toString(), (0 until include.length()).any { include.getString(it) == "web_search_call.action.sources" })
        assertEquals("Fact", json.getString("input"))
    }

    @Test fun dropsUrlsThatProviderDidNotCite() {
        reply = openRouter(entries(sourceEntry(first, "First"), sourceEntry(third, "Invented")), first to "First page text")
        assertEquals(listOf(first), search("Fact").map { it.url })
        // Without citations, the search has no sources.
        reply = openRouter(entries(sourceEntry(first, "First")))
        assertEquals(emptyList<Source>(), search("Fact"))
    }

    @Test fun unusableCitationsFail() {
        reply = openRouter(entries(sourceEntry("javascript:alert(1)", "Script"), sourceEntry("ftp://x", "File")),
            "javascript:alert(1)" to "Script text", "ftp://x" to "File text")
        assertEquals("search_no_usable_results", failure().code)
    }

    @Test fun publishedAtFromModelJson() {
        reply = openRouter(entries(sourceEntry(first, "First", "2026-10-07T08:30:00Z"), sourceEntry(second, "Second", "yesterday")),
            first to "First page text", second to "Second page text")
        val sources = search("Fact")
        assertEquals("2026-10-07T08:30:00Z", sources[0].publishedAt)
        assertNull(sources[1].publishedAt)
        // Spec 9.4: the prompt shows no date that a model can copy for an undated page.
        val prompt = JSONObject(checkNotNull(request).body).getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse(prompt, Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(prompt))
    }

    /** The model JSON is untrusted: an entry of a wrong shape is dropped, and no usable entry gives the contract code. */
    @Test fun malformedModelJsonIsDropped() {
        val malformed = JSONArray().put("text").put(JSONObject().put("url", 5).put("title", "Number"))
            .put(sourceEntry(second, "Second", 20261101)).put(JSONObject().put("url", third).put("title", JSONArray().put("Third")))
            .put(sourceEntry(first, "First"))
        reply = openRouter(malformed.toString(), first to "a", second to "b", third to "c")
        assertEquals(listOf(first), search("Fact").map { it.url })
        for (content in listOf("not json", JSONObject().put("url", first).toString(), "")) {
            reply = openRouter(content, first to "a")
            assertEquals(content, "search_no_usable_results", failure().code)
        }
    }

    @Test fun outputContractLimits() {
        val urls = List(7) { "https://example.com/page-$it" }
        val title = "T".repeat(400)
        reply = openRouter(entries(*urls.map { sourceEntry(it, title) }.toTypedArray()), *urls.map { it to "x".repeat(7000) }.toTypedArray())
        val sources = search("Fact")
        assertEquals(urls.take(5), sources.map { it.url })
        assertTrue(sources.all { it.title.length == 300 && it.summary.length == 6000 })
    }

    @Test fun responseOverOneMebibyteFails() {
        reply = openRouter("x".repeat(1024 * 1024), first to "a")
        assertEquals("search_response", failure().code)
    }

    @Test fun refusedSearchIsRefusal() {
        reply = openRouter("", finish = "content_filter")
        val openRouter = failure()
        assertEquals("refusal", openRouter.code)
        assertTrue(openRouter.message, openRouter.message.startsWith("Поиск, OpenRouter: "))
        account = AiProvider.OPENAI
        reply = JSONObject().put("output", JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "I cannot help with that.")))))
        val openAi = failure()
        assertEquals("refusal", openAi.code)
        assertTrue(openAi.message, openAi.message.startsWith("Поиск, OpenAI: "))
    }

    private fun anthropic(results: List<JSONObject>, cited: List<JSONObject>, entries: String, stopReason: String = "end_turn") {
        account = AiProvider.ANTHROPIC
        reply = anthropicSearchReply(results, cited, entries, stopReason)
    }

    @Test fun anthropicSearchMapsResultsAndCitations() {
        anthropic(listOf(anthropicResult(first, "First"), anthropicResult(second, "Second")),
            listOf(anthropicCited("First fact.", first to "First cited text"), anthropicCited("Second fact.", second to "Second cited text",
                third to "Cited but in no result")), entries(sourceEntry(first, "First"), sourceEntry(second, "Second"), sourceEntry(third, "Third")))
        val sources = search("Fact")
        assertEquals(listOf(first, second), sources.map { it.url })
        assertEquals(listOf("First cited text", "Second cited text"), sources.map { it.summary })
        val request = checkNotNull(request)
        assertEquals("/v1/messages", request.path)
        assertEquals(key, request.headers["x-api-key"])
        assertEquals("2026-10-21", request.headers["anthropic-version"])
        assertNull(request.headers["authorization"])
        assertNull(request.headers["anthropic-beta"])
        val json = JSONObject(request.body)
        assertFalse(json.getBoolean("stream"))
        assertEquals("claude-sonnet-5-5", json.getString("model"))
        assertEquals(JSONObject().put("type", "web_search_20260209").put("name", "web_search").put("max_uses", 1).toString(),
            json.getJSONArray("tools").getJSONObject(0).toString())
        assertEquals(JSONObject().put("type", "adaptive").toString(), json.getJSONObject("thinking").toString())
        assertEquals("low", json.getJSONObject("output_config").getString("effort"))
        for (name in listOf("temperature", "top_p", "top_k", "tool_choice")) assertFalse(name, json.has(name))
        assertEquals("Fact", json.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun anthropicSearchToolError() {
        account = AiProvider.ANTHROPIC
        reply = anthropicSearchReply(emptyList(), emptyList(), "[]")
        reply.getJSONArray("content").getJSONObject(1).put("content", JSONObject()
            .put("type", "web_search_tool_result_error").put("error_code", "max_uses_exceeded"))
        assertEquals("search_unavailable", failure().code)
    }

    @Test fun anthropicErrorAfterResultsKeepsTheResults() {
        anthropic(listOf(anthropicResult(first, "First")), listOf(anthropicCited("Fact.", first to "Cited")), entries(sourceEntry(first, "First")))
        val content = reply.getJSONArray("content")
        content.put(JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "srvtoolu_2").put("content",
            JSONObject().put("type", "web_search_tool_result_error").put("error_code", "unavailable")))
        assertEquals(listOf(first), search("Fact").map { it.url })
    }

    @Test fun anthropicPausedAndRefusedTurns() {
        anthropic(listOf(anthropicResult(first, "First")), listOf(anthropicCited("Fact.", first to "Cited")), entries(sourceEntry(first, "First")), "pause_turn")
        assertEquals(listOf(first), search("Fact").map { it.url })
        // Without a usable result, a paused turn follows the contract code.
        anthropic(listOf(anthropicResult("javascript:alert(1)", "Script")), emptyList(), "[]", "pause_turn")
        assertEquals("search_no_usable_results", failure().code)
        anthropic(emptyList(), emptyList(), "", "refusal")
        val refusal = failure()
        assertEquals("refusal", refusal.code)
        assertTrue(refusal.message, refusal.message.startsWith("Поиск, Anthropic: "))
    }

    @Test fun pageAgeBecomesPublishedAt() {
        anthropic(listOf(anthropicResult(first, "First", "2026-11-01"), anthropicResult(second, "Second", "2026-11-02T10:30:00Z"),
            anthropicResult(third, "Third", "2 days ago")), emptyList(),
            entries(sourceEntry(first, "First", "2026-10-01T00:00:00Z"), sourceEntry(second, "Second"), sourceEntry(third, "Third", "2026-10-07T08:30:00Z")))
        // The page age wins over the model JSON. Text that is no date leaves the model JSON in force.
        assertEquals(listOf("2026-11-01T00:00:00Z", "2026-11-02T10:30:00Z", "2026-10-07T08:30:00Z"), search("Fact").map { it.publishedAt })
    }

    /** Without cited prose a result has no citation text. As in the OpenAI search, the source stays and its excerpt is empty. */
    @Test fun anthropicSearchWithoutCitedProseKeepsEmptyExcerpts() {
        anthropic(listOf(anthropicResult(first, "First"), anthropicResult(second, "Second")), emptyList(),
            entries(sourceEntry(first, "First"), sourceEntry(second, "Second")))
        val sources = search("Fact")
        assertEquals(listOf(first, second), sources.map { it.url })
        assertEquals(listOf("", ""), sources.map { it.summary })
        val system = JSONObject(checkNotNull(request).body).getString("system")
        assertTrue(system, system.contains("cite the source"))
        assertFalse(system, system.contains("Write no other text"))
    }
}
