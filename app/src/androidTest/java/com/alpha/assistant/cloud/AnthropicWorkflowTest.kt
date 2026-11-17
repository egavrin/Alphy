package com.alpha.assistant.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Messages stream fixtures. They use the SSE framing of the Responses fixtures (an `event:` line and a `data:` line).
// CloudWorkflowTest also uses them to re-encode its recorded replays (spec 16.5).

private fun OutputStream.messageStart() = responsesEvent("message_start", JSONObject().put("message", JSONObject()
    .put("id", "msg_fixture").put("type", "message").put("role", "assistant").put("content", JSONArray())))

private fun OutputStream.messageEnd(stopReason: String) {
    responsesEvent("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", stopReason).put("stop_sequence", JSONObject.NULL)))
    responsesEvent("message_stop")
}

private fun OutputStream.blockStart(index: Int, block: JSONObject) =
    responsesEvent("content_block_start", JSONObject().put("index", index).put("content_block", block))

private fun OutputStream.blockDelta(index: Int, delta: JSONObject) =
    responsesEvent("content_block_delta", JSONObject().put("index", index).put("delta", delta))

private fun OutputStream.blockStop(index: Int) = responsesEvent("content_block_stop", JSONObject().put("index", index))

/** An unfinished Messages stream: one text block made of [deltas]. */
private fun OutputStream.anthropicStart(vararg deltas: String) {
    responsesHeaders()
    messageStart()
    blockStart(0, JSONObject().put("type", "text").put("text", ""))
    for (delta in deltas) blockDelta(0, JSONObject().put("type", "text_delta").put("text", delta))
}

/** A finished Messages stream with one text block made of [deltas]. */
internal fun OutputStream.anthropicText(vararg deltas: String) {
    anthropicStart(*deltas)
    blockStop(0)
    messageEnd("end_turn")
}

/** The structured-output reply of forced tools (spec 8.7): one `calls` item for each pair of a name and its arguments. */
internal fun structuredCalls(vararg calls: Pair<String, JSONObject>): String = JSONObject().put("calls",
    JSONArray(calls.map { (name, arguments) -> JSONObject().put("name", name).put("arguments", arguments) })).toString()

/**
 * A finished Messages stream with the [thinking] blocks, then one tool_use block with streamed input.
 * A thinking block streams its text and its signature as deltas. A redacted_thinking block arrives whole.
 */
internal fun OutputStream.anthropicToolUse(id: String, name: String, input: String, vararg thinking: JSONObject) {
    responsesHeaders()
    messageStart()
    thinking.forEachIndexed { index, block ->
        if (block.getString("type") == "redacted_thinking") blockStart(index, block) else {
            blockStart(index, JSONObject().put("type", "thinking").put("thinking", "").put("signature", ""))
            blockDelta(index, JSONObject().put("type", "thinking_delta").put("thinking", block.getString("thinking")))
            blockDelta(index, JSONObject().put("type", "signature_delta").put("signature", block.getString("signature")))
        }
        blockStop(index)
    }
    val index = thinking.size
    blockStart(index, JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", JSONObject()))
    for (part in input.chunked(16)) blockDelta(index, JSONObject().put("type", "input_json_delta").put("partial_json", part))
    blockStop(index)
    messageEnd("tool_use")
}

/** Each test checks one rule of the Anthropic Messages wire (spec 8.3, 8.7, 8.8) against a loopback Messages fixture. */
@RunWith(AndroidJUnit4::class)
class AnthropicWorkflowTest {
    private val key = "alpha-test-only"

    private data class Request(val headers: Map<String, String>, val path: String, val json: JSONObject) {
        val messages: List<JSONObject> get() = json.getJSONArray("messages").let { items -> List(items.length(), items::getJSONObject) }
        val format: JSONObject? get() = json.optJSONObject("output_config")?.optJSONObject("format")
    }

    private class Exchange(val requests: List<Request>, val events: List<CloudEvent>, val failure: CloudFailure?)

    private fun request(raw: LoopbackRequest) = Request(raw.headers, raw.path, JSONObject(raw.body))

    /** Sends [request] through an Anthropic account. [reply] answers each HTTP call with its index and body. */
    private fun exchange(request: CloudRequest, reply: (Int, JSONObject, OutputStream) -> Unit = { _, body, output -> answer(body, output) }): Exchange {
        val calls = AtomicInteger()
        val events = CopyOnWriteArrayList<CloudEvent>()
        return LoopbackServer { raw, output -> reply(calls.getAndIncrement(), JSONObject(raw.body), output) }.use { server ->
            val provider = CloudChatProvider({ AiAccount(AiProvider.ANTHROPIC, key) }, Endpoints { server.url }, recordEmptyCompletion = {})
            val failure = try {
                runBlocking { provider.respond(key, request, CloudSession()) { events += it } }
                null
            } catch (failure: CloudFailure) { failure }
            assertTrue(server.failures.toString(), server.failures.isEmpty())
            Exchange(server.requests.map(::request), events, failure)
        }
    }

    private fun firstRequest(request: CloudRequest) = exchange(request).requests.first()

    /** Sends [body] through the wire alone and returns its completion and its one request. */
    private fun complete(body: JSONObject, stage: CompletionStage, reply: (OutputStream) -> Unit): Pair<Completion, Request> =
        LoopbackServer { _, output -> reply(output) }.use { server ->
            val wire = AnthropicWire(server.url, AnthropicProfile.chatModels.first(), CloudHttp(null, 60_000)) {}
            val completion = runBlocking { wire.complete(key, body, StreamLimits(stage = stage), CloudSession()) {} }
            assertTrue(server.failures.toString(), server.failures.isEmpty())
            completion to request(server.requests.single())
        }

    private fun decision(text: String) = JSONObject().put("kind", "answer").put("text", text).put("brief", "").put("search", "none")

    /** The decision step answers with kind answer in its structured form. Every other request gets one JSON text reply. */
    private fun answer(body: JSONObject, output: OutputStream) =
        if (body.optJSONObject("output_config")?.optJSONObject("format")?.toString()?.contains("submit_chat_decision") == true)
            output.anthropicText(structuredCalls("submit_chat_decision" to decision("Hello")))
        else output.anthropicText("{\"a\":1}")

    private fun textRequest(text: String, images: List<String> = emptyList()) =
        CloudRequest(listOf(CloudTurn("user", text, images)), false, "Reply in one line.")

    private fun JSONObject.branches(): List<JSONObject> = getJSONObject("schema").getJSONObject("properties")
        .getJSONObject("calls").getJSONObject("items").getJSONArray("anyOf").let { items -> List(items.length(), items::getJSONObject) }

    private fun JSONObject.toolName() = getJSONObject("properties").getJSONObject("name").getJSONArray("enum").getString(0)

    /** The canonical search plan body (CloudProvider.kt): a forced web_search tool and no tool history. */
    private fun searchPlanBody(): JSONObject {
        val parameters = JSONObject().put("type", "object").put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("query")).put("additionalProperties", false)
        val tool = JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search")
            .put("description", "Search the web.").put("parameters", parameters))
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "Return structured web_search calls."))
            .put(JSONObject().put("role", "user").put("content", "Compare two cities."))
        return JSONObject().put("messages", messages).put("stream", true).put("thinking", JSONObject().put("type", "disabled"))
            .put("max_tokens", 8192).put("tools", JSONArray().put(tool))
            .put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search")))
    }

    @Test fun decisionUsesStructuredOutputNotForcedTool() {
        val exchange = exchange(decisionRequest()) { index, body, output -> if (index == 0) answer(body, output) else output.anthropicText("Hello") }
        assertNull(exchange.failure)
        val json = exchange.requests[0].json
        assertFalse(json.has("tools"))
        assertFalse(json.has("tool_choice"))
        val format = checkNotNull(exchange.requests[0].format)
        assertEquals("json_schema", format.getString("type"))
        assertEquals(listOf("submit_chat_decision"), format.branches().map { it.toolName() })
        // Decision 3: a canonical request with thinking off keeps adaptive thinking at the profile effort low.
        assertEquals("adaptive", json.getJSONObject("thinking").getString("type"))
        assertEquals("low", json.getJSONObject("output_config").getString("effort"))
        // The structured reply became a submit_chat_decision call with kind answer, so the answer step ran.
        assertEquals(2, exchange.requests.size)
        val round = exchange.events.last() as CloudEvent.RoundComplete
        assertEquals("Hello", JSONObject(round.text).getString("text"))
    }

    /** This test reads the wire result directly. searchPlanOfTwoCallsRunsTwoAnthropicSearches covers the provider path. */
    @Test fun searchPlanUsesStructuredOutput() {
        val reply = structuredCalls("web_search" to JSONObject().put("query", "Paris weather"),
            "web_search" to JSONObject().put("query", "Rome weather"))
        val (completion, request) = complete(searchPlanBody(), CompletionStage.SEARCH_PLAN) { it.anthropicText(reply) }
        assertFalse(request.json.has("tools"))
        assertFalse(request.json.has("tool_choice"))
        val format = checkNotNull(request.format)
        assertEquals("json_schema", format.getString("type"))
        assertEquals(listOf("web_search"), format.branches().map { it.toolName() })
        assertEquals("", completion.text)
        assertNull(completion.replay)
        assertEquals(listOf("web_search", "web_search"), completion.calls.map { it.name })
        assertEquals(listOf("Paris weather", "Rome weather"), completion.calls.map { JSONObject(it.arguments).getString("query") })
        assertEquals(2, completion.calls.map { it.id }.toSet().size)
        assertTrue(completion.calls.toString(), completion.calls.all { it.id.startsWith("call_") })
    }

    /** The canonical tool descriptions of [request]. The OpenAI wire sends each tool function unchanged. */
    private fun canonicalDescriptions(request: CloudRequest): Map<String, String> =
        LoopbackServer { _, output -> output.responsesHeaders() }.use { server ->
            runCatching { runBlocking { CloudChatProvider({ AiAccount(AiProvider.OPENAI, key) }, Endpoints { server.url }).respond(key, request, CloudSession()) {} } }
            val tools = JSONObject(server.requests.first().body).getJSONArray("tools")
            List(tools.length(), tools::getJSONObject).associate { it.getString("name") to it.getString("description") }
        }

    /** Spec 4.4: no maxItems, minItems 0 or 1, every object closed, and at most 16 properties with anyOf or type arrays. */
    private fun assertFitsAnthropicLimits(schema: JSONObject) {
        var unions = 0
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("anyOf") || node.opt("type") is JSONArray) unions++
                    assertFalse("maxItems is not supported.", node.has("maxItems"))
                    if (node.has("minItems")) assertTrue(node.getInt("minItems") in 0..1)
                    if (node.optString("type") == "object") assertFalse(node.toString(), node.getBoolean("additionalProperties"))
                    for (name in node.keys()) walk(node.get(name))
                }
                is JSONArray -> for (index in 0 until node.length()) walk(node.get(index))
            }
        }
        walk(schema)
        assertTrue("$unions properties use anyOf or type arrays.", unions <= 16)
    }

    @Test fun requiredChoiceBecomesUnion() {
        val request = decisionRequest().copy(allowMemoryFact = true, allowCourseDecision = true)
        val format = checkNotNull(firstRequest(request).format)
        val branches = format.branches()
        assertEquals(listOf("submit_chat_decision", "prepare_memory_fact", "prepare_course"), branches.map { it.toolName() })
        // The forced tools on the other providers keep their descriptions. The structured calls keep them too.
        assertEquals(canonicalDescriptions(request), branches.associate { it.toolName() to it.optString("description") })
        for (branch in branches) {
            assertFalse(branch.getBoolean("additionalProperties"))
            assertEquals("[\"name\",\"arguments\"]", branch.getJSONArray("required").toString())
        }
        val root = format.getJSONObject("schema")
        assertFalse(root.getBoolean("additionalProperties"))
        assertEquals("[\"calls\"]", root.getJSONArray("required").toString())
    }

    @Test fun badStructuredReplyIsToolInvalid() {
        for (reply in listOf("not json", structuredCalls("unknown_tool" to JSONObject()))) {
            val failure = exchange(decisionRequest()) { _, _, output -> output.anthropicText(reply) }.failure
            assertEquals(reply, "tool_invalid", failure?.code)
        }
    }

    @Test fun decisionSchemaFitsAnthropicLimits() {
        val request = decisionRequest().copy(search = true, weatherLocation = { error("No location in this test.") },
            allowMemoryFact = true, allowCourseDecision = true, allowTestFix = true, allowDevicePlan = true,
            allowChatImage = true, automationDraftChatId = "chat", capabilityDiscovery = { JSONObject() })
        val format = checkNotNull(firstRequest(request).format)
        assertEquals(6, format.branches().size)
        assertFitsAnthropicLimits(format.getJSONObject("schema"))
    }

    @Test fun searchPlanSchemaFitsAnthropicLimits() {
        val provider = CloudChatProvider({ AiAccount(AiProvider.ANTHROPIC, key) }, Endpoints { "http://127.0.0.1:9" })
        for (dated in listOf(false, true)) assertFitsAnthropicLimits(StructuredCalls.schema(provider.searchTool(dated)))
    }

    /** One app agent step: the JSON object rule is a system line, and the screenshot is an image block. */
    @Test fun jsonObjectBecomesSystemLine() {
        val request = firstRequest(agentStepRequest())
        val json = request.json
        assertTrue(json.getString("system"), json.getString("system").endsWith("\n\nReturn one JSON object."))
        assertFalse(json.getJSONObject("output_config").has("format"))
        assertEquals("high", json.getJSONObject("output_config").getString("effort"))
        assertFalse(json.has("tools"))
        assertEquals(128_000, json.getInt("max_tokens"))
        assertEquals(listOf("user", "assistant", "user"), request.messages.map { it.getString("role") })
        val image = request.messages[2].getJSONArray("content").getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals(AGENT_SCREENSHOT.substringAfter(","), image.getJSONObject("source").getString("data"))
    }

    @Test fun imagesBecomeBase64Blocks() {
        val content = firstRequest(textRequest("Describe.", listOf("data:image/png;base64,iVBORw0KGgo="))).messages.single()
            .getJSONArray("content")
        assertEquals(JSONObject().put("type", "text").put("text", "Describe.").toString(), content.getJSONObject(0).toString())
        val image = JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64")
            .put("media_type", "image/png").put("data", "iVBORw0KGgo="))
        assertEquals(image.toString(), content.getJSONObject(1).toString())
    }

    /** Decision 12: the thinking signature binds the system text, the tools, and every earlier message. */
    private fun assertReplayPrefix(first: Request, second: Request) {
        assertEquals(first.json.getString("system"), second.json.getString("system"))
        assertEquals(first.json.getJSONArray("tools").toString(), second.json.getJSONArray("tools").toString())
        assertEquals(first.messages.map { it.toString() }, second.messages.take(first.messages.size).map { it.toString() })
    }

    @Test fun toolRoundReplaysThinkingBlocks() {
        // The default display is omitted: a thinking block arrives with empty text and only a signature.
        val thinking = JSONObject().put("type", "thinking").put("thinking", "").put("signature", "sig-fixture")
        val redacted = JSONObject().put("type", "redacted_thinking").put("data", "redacted-fixture")
        val request = generationRequest().copy(capabilityDiscovery = { JSONObject().put("caller", "miniApp") })
        val exchange = exchange(request) { index, _, output ->
            if (index == 0) output.anthropicToolUse("toolu_discovery", "discover_capabilities", "{}", thinking, redacted)
            else output.anthropicText("{\"a\":1}")
        }
        assertNull(exchange.failure)
        assertEquals(2, exchange.requests.size)
        val (first, second) = exchange.requests
        for (sent in exchange.requests) {
            assertEquals("thinking-binding-controls-2026-08-01", sent.headers["anthropic-beta"])
            assertEquals("drop_block", sent.json.getJSONObject("thinking").getJSONObject("block_binding").getString("prefix_mismatch_behavior"))
        }
        assertEquals("high", first.json.getJSONObject("output_config").getString("effort"))
        assertReplayPrefix(first, second)
        assertEquals(JSONObject().put("type", "none").toString(), second.json.getJSONObject("tool_choice").toString())
        val assistant = second.messages[first.messages.size]
        assertEquals("assistant", assistant.getString("role"))
        val use = JSONObject().put("type", "tool_use").put("id", "toolu_discovery").put("name", "discover_capabilities").put("input", JSONObject())
        assertEquals(listOf(thinking, redacted, use).map { it.toString() },
            assistant.getJSONArray("content").let { blocks -> List(blocks.length()) { blocks.getJSONObject(it).toString() } })
        val results = second.messages[first.messages.size + 1]
        assertEquals("user", results.getString("role"))
        val result = results.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("toolu_discovery", result.getString("tool_use_id"))
        assertEquals("miniApp", JSONObject(result.getString("content")).getString("caller"))
        assertEquals(first.messages.size + 2, second.messages.size)
    }

    @Test fun refusalStopsWithRefusalCode() {
        val exchange = exchange(decisionRequest()) { index, body, output ->
            if (index == 0) answer(body, output) else {
                // A classifier refusal before any output: no content block, and stop_reason refusal.
                output.responsesHeaders()
                output.messageStart()
                output.messageEnd("refusal")
            }
        }
        assertEquals("refusal", exchange.failure?.code)
        assertTrue(exchange.failure?.message.orEmpty().startsWith("Anthropic: "))
        assertTrue(exchange.events.none { it is CloudEvent.AnswerFragment || it is CloudEvent.RoundComplete })
    }

    @Test fun maxTokensIsClamped() {
        assertEquals(128_000, firstRequest(generationRequest(expanded = true)).json.getInt("max_tokens"))
        // Anthropic requires max_tokens. A canonical body without it gets the profile limit.
        assertEquals(AnthropicProfile.maxOutputTokens, firstRequest(generationRequest()).json.getInt("max_tokens"))
    }

    @Test fun answerStreamsText() {
        val exchange = exchange(decisionRequest()) { index, body, output ->
            if (index == 0) answer(body, output) else output.anthropicText("Hello", ", ", "world")
        }
        assertNull(exchange.failure)
        assertEquals(listOf("Hello", ", ", "world"), exchange.events.filterIsInstance<CloudEvent.AnswerFragment>().map { it.value })
        val round = exchange.events.last() as CloudEvent.RoundComplete
        assertEquals("Hello, world", JSONObject(round.text).getString("text"))
    }

    @Test fun overloadedIsUnavailable() {
        val error = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        val failure = exchange(textRequest("Hi")) { _, _, output ->
            output.write(("HTTP/1.1 529 Overloaded\r\nContent-Type: application/json\r\nContent-Length: ${error.length}\r\n" +
                "Connection: close\r\n\r\n$error").toByteArray()); output.flush()
        }.failure
        assertEquals("unavailable", failure?.code)
        assertTrue(failure?.message.orEmpty().startsWith("Anthropic: "))
    }

    @Test fun eventsSplitAcrossReadsParse() {
        val body = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Hi")))
            .put("stream", true).put("thinking", JSONObject().put("type", "disabled")).put("max_tokens", 4096)
        val whole = complete(body, CompletionStage.GENERAL) { it.anthropicText("Hello", " world") }.first
        val split = complete(body, CompletionStage.GENERAL) { output ->
            output.anthropicStart("Hello")
            val event = ByteArrayOutputStream().apply {
                responsesEvent("content_block_delta", JSONObject().put("index", 0)
                    .put("delta", JSONObject().put("type", "text_delta").put("text", " world")))
            }.toByteArray()
            val third = event.size / 3
            for (part in listOf(event.copyOfRange(0, third), event.copyOfRange(third, 2 * third), event.copyOfRange(2 * third, event.size))) {
                output.write(part); output.flush(); Thread.sleep(100)
            }
            output.blockStop(0)
            output.messageEnd("end_turn")
        }.first
        assertEquals(Completion("Hello world", emptyList()), whole)
        assertEquals(whole, split)
    }

    @Test fun pingAndUnknownEventsAreIgnored() {
        val body = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Hi")))
            .put("stream", true).put("thinking", JSONObject().put("type", "disabled")).put("max_tokens", 4096)
        val completion = complete(body, CompletionStage.GENERAL) { output ->
            output.responsesHeaders()
            output.responsesEvent("ping")
            output.messageStart()
            output.responsesEvent("future_event", JSONObject().put("index", 0).put("note", "unknown"))
            output.blockStart(0, JSONObject().put("type", "text").put("text", ""))
            output.responsesEvent("ping")
            output.blockDelta(0, JSONObject().put("type", "text_delta").put("text", "Hello"))
            output.blockDelta(0, JSONObject().put("type", "future_delta").put("value", "unknown"))
            output.blockStop(0)
            output.responsesEvent("ping")
            output.messageEnd("end_turn")
        }.first
        assertEquals(Completion("Hello", emptyList()), completion)
    }

    /** The answer step streams "Partial", then [end]. The failure has [code], and the partial text never becomes a completed round. */
    private fun answerFailure(code: String, end: OutputStream.() -> Unit): CloudFailure {
        val exchange = exchange(decisionRequest()) { index, body, output ->
            if (index == 0) answer(body, output) else { output.anthropicStart("Partial"); output.end() }
        }
        assertEquals(code, exchange.failure?.code)
        assertTrue(exchange.events.none { it is CloudEvent.RoundComplete })
        return checkNotNull(exchange.failure)
    }

    @Test fun errorEventMidStreamIsFailure() {
        val failure = answerFailure("unavailable") {
            responsesEvent("error", JSONObject().put("error", JSONObject().put("type", "overloaded_error").put("message", "Overloaded")))
        }
        assertTrue(failure.message.orEmpty().startsWith("Anthropic: "))
    }

    // Decision 9: a stream without message_stop, or with an unknown stop_reason, follows the requireFinish rule of the other wires.
    @Test fun missingMessageStopIsIncomplete() {
        answerFailure("incomplete") {
            blockStop(0)
            responsesEvent("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "end_turn")))
        }
    }

    @Test fun unknownStopReasonIsIncomplete() {
        answerFailure("incomplete") {
            blockStop(0)
            messageEnd("future_stop_reason")
        }
    }

    @Test fun cancelClosesTheConnection() = assertCancelClosesStream(AiProvider.ANTHROPIC, 2_000) { anthropicStart("Hel") }

    @Test fun redirectIsRefused() {
        val exchange = exchange(textRequest("Hi")) { _, _, output ->
            output.write("HTTP/1.1 302 Found\r\nLocation: /v1/other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }
        assertNotNull(exchange.failure)
        assertEquals("http_302", exchange.failure?.code)
        assertEquals(1, exchange.requests.size)
    }

    @Test fun sendsApiKeyHeaderOnly() {
        val request = firstRequest(textRequest("Hi"))
        assertEquals("/v1/messages", request.path)
        assertEquals(key, request.headers["x-api-key"])
        assertEquals("2026-10-21", request.headers["anthropic-version"])
        assertNull(request.headers["authorization"])
        assertEquals("application/json", request.headers["content-type"])
        assertEquals(AnthropicProfile.chatModels.first(), request.json.getString("model"))
        assertTrue(request.json.getBoolean("stream"))
        // API version 2026-10-21 rejects the sampling parameters on every model.
        for (name in listOf("temperature", "top_p", "top_k")) assertFalse(name, request.json.has(name))
    }

    private val searchTool = "web_search_20260209"

    private val Request.isSearch get() = json.optJSONArray("tools")?.getJSONObject(0)?.optString("type") == searchTool

    private fun searchReply(url: String) = anthropicSearchReply(listOf(anthropicResult(url, "Page")), listOf(anthropicCited("Fact.", url to "Cited")),
        JSONArray().put(sourceEntry(url, "Page")).toString())

    @Test fun searchRoundsReplayThinkingAroundAnthropicSearch() {
        val thinking = JSONObject().put("type", "thinking").put("thinking", "").put("signature", "sig-search")
        val url = "https://example.com/page"
        val request = CloudRequest(listOf(CloudTurn("user", "Find the fact.")), true, "Reply in one line.")
        val exchange = exchange(request) { index, _, output ->
            when (index) {
                0 -> output.anthropicToolUse("toolu_search", "web_search", "{\"query\":\"Fact\"}", thinking)
                1 -> output.jsonResponse(searchReply(url))
                else -> output.anthropicText("Done.")
            }
        }
        assertNull(exchange.failure)
        // Round 1, the search call, and round 2.
        assertEquals(3, exchange.requests.size)
        val (first, search, second) = exchange.requests
        assertTrue(search.isSearch)
        assertFalse(search.json.getBoolean("stream"))
        assertReplayPrefix(first, second)
        val assistant = second.messages[first.messages.size]
        assertEquals(thinking.toString(), assistant.getJSONArray("content").getJSONObject(0).toString())
        assertEquals("tool_use", assistant.getJSONArray("content").getJSONObject(1).getString("type"))
        val found = exchange.events.filterIsInstance<CloudEvent.Search>().last()
        assertEquals(listOf(url), found.sources.map { it.url })
    }

    @Test fun searchPlanOfTwoCallsRunsTwoAnthropicSearches() {
        val request = decisionRequest().copy(search = true, weatherLocation = { error("No location in this test.") })
        val urls = listOf("https://example.com/paris", "https://example.org/rome")
        var searches = 0
        val exchange = exchange(request) { index, body, output ->
            val format = body.optJSONObject("output_config")?.optJSONObject("format")?.toString().orEmpty()
            when {
                body.optJSONArray("tools")?.getJSONObject(0)?.optString("type") == searchTool -> output.jsonResponse(searchReply(urls[searches++]))
                index == 0 -> output.anthropicText(structuredCalls("submit_chat_decision" to decision("Hello").put("search", "required")))
                format.contains("\"web_search\"") -> output.anthropicText(structuredCalls("web_search" to JSONObject().put("query", "Paris weather"),
                    "web_search" to JSONObject().put("query", "Rome weather")))
                else -> output.anthropicText("Fact [1] [2]")
            }
        }
        assertEquals(2, exchange.requests.count { it.isSearch })
        assertEquals(2, searches)
        assertEquals(listOf("searching", "found", "searching", "found"), exchange.events.filterIsInstance<CloudEvent.Search>().map { it.status })
    }
}
