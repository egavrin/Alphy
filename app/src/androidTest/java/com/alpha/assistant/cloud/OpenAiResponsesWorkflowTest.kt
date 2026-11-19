package com.alpha.assistant.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Responses stream fixtures. CloudWorkflowTest also uses them to re-encode its recorded replays (spec 16.5).

internal fun OutputStream.responsesHeaders() {
    write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); flush()
}

/** Writes one Responses event as an `event:` line and a `data:` line. */
internal fun OutputStream.responsesEvent(type: String, fields: JSONObject = JSONObject()) {
    write("event: $type\ndata: ${fields.put("type", type)}\n\n".toByteArray()); flush()
}

private fun OutputStream.responsesMessage(part: JSONObject) {
    responsesEvent("response.output_item.added", JSONObject().put("output_index", 0)
        .put("item", JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray())))
    responsesEvent("response.content_part.added", JSONObject().put("output_index", 0).put("content_index", 0).put("part", part))
}

private fun OutputStream.responsesCompleted() =
    responsesEvent("response.completed", JSONObject().put("response", JSONObject().put("status", "completed")))

private fun textDelta(delta: String) = JSONObject().put("output_index", 0).put("content_index", 0).put("delta", delta)

/** An unfinished Responses stream: one assistant message with the text [deltas]. */
internal fun OutputStream.responsesStart(vararg deltas: String) {
    responsesHeaders()
    responsesMessage(JSONObject().put("type", "output_text").put("text", ""))
    for (delta in deltas) responsesEvent("response.output_text.delta", textDelta(delta))
}

/** A finished Responses stream with one assistant message made of [deltas]. */
internal fun OutputStream.responsesText(vararg deltas: String) {
    responsesStart(*deltas)
    responsesCompleted()
}

/** A Responses server that stalls the first request after [start] until [release]. Each later request gets the text [later]. */
internal fun stallingResponses(release: CountDownLatch, later: String, start: OutputStream.() -> Unit): LoopbackServer {
    val calls = AtomicInteger()
    return LoopbackServer { _, output ->
        if (calls.getAndIncrement() == 0) {
            output.start()
            release.await(15, TimeUnit.SECONDS)
        } else output.responsesText(later)
    }
}

/** A finished Responses stream with the [reasoning] items, then one function call with streamed arguments. */
internal fun OutputStream.responsesCall(callId: String, name: String, arguments: String, vararg reasoning: JSONObject) {
    responsesHeaders()
    reasoning.forEachIndexed { index, item ->
        responsesEvent("response.output_item.added", JSONObject().put("output_index", index)
            .put("item", JSONObject().put("type", "reasoning").put("summary", JSONArray())))
        responsesEvent("response.output_item.done", JSONObject().put("output_index", index).put("item", item))
    }
    val index = reasoning.size
    responsesEvent("response.output_item.added", JSONObject().put("output_index", index).put("item", JSONObject()
        .put("type", "function_call").put("call_id", callId).put("name", name).put("arguments", "")))
    for (part in arguments.chunked(16))
        responsesEvent("response.function_call_arguments.delta", JSONObject().put("output_index", index).put("delta", part))
    responsesCompleted()
}

/**
 * Spec 3.4: cancellation disconnects the request while its read waits for the next event. [start] writes an unfinished
 * stream with text. The cancelled call must stop within [cancelMillis].
 */
internal fun assertCancelClosesStream(provider: AiProvider, cancelMillis: Long, start: OutputStream.() -> Unit) {
    val key = "alpha-test-only"
    val session = CloudSession()
    val streaming = CountDownLatch(1)
    val clientDone = CountDownLatch(1)
    val closed = CountDownLatch(1)
    LoopbackServer { _, output ->
        output.start()
        // Stay silent so that the client waits in a read. Then write until the closed socket refuses a write.
        clientDone.await(15, TimeUnit.SECONDS)
        try { repeat(40) { output.write(": keep-alive\n\n".toByteArray()); output.flush(); Thread.sleep(50) } }
        catch (_: IOException) { closed.countDown() }
    }.use { server ->
        val chat = CloudChatProvider({ AiAccount(provider, key) }, Endpoints { server.url })
        val request = CloudRequest(listOf(CloudTurn("user", "Hi")), false, "Reply in one line.")
        val failure = try {
            runBlocking {
                val call = async(Dispatchers.IO) {
                    try { chat.respond(key, request, session) { if (it is CloudEvent.Text) streaming.countDown() }; null }
                    catch (failure: CloudFailure) { failure }
                }
                assertTrue(streaming.await(10, TimeUnit.SECONDS))
                Thread.sleep(200) // The client returns from the text callback to its read.
                session.cancel()
                withTimeout(cancelMillis) { call.await() }
            }
        } finally { clientDone.countDown() }
        assertEquals("cancelled", failure?.code)
        assertTrue("The canceled stream stayed open.", closed.await(10, TimeUnit.SECONDS))
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }
}

/** Each test checks one rule of the OpenAI Responses wire (spec 8.9) against a loopback Responses fixture. */
@RunWith(AndroidJUnit4::class)
class OpenAiResponsesWorkflowTest {
    private val key = "alpha-test-only"

    private data class Request(val path: String, val json: JSONObject) {
        val input: List<JSONObject> get() = json.getJSONArray("input").let { items -> List(items.length(), items::getJSONObject) }
    }

    private class Exchange(val requests: List<Request>, val events: List<CloudEvent>, val failure: CloudFailure?)

    /** Sends [request] through an OpenAI account. [reply] answers each HTTP call with its index and body. */
    private fun exchange(request: CloudRequest, session: CloudSession = CloudSession(),
        reply: (Int, JSONObject, OutputStream) -> Unit = { _, body, output -> answer(body, output) }): Exchange {
        val calls = AtomicInteger()
        val events = CopyOnWriteArrayList<CloudEvent>()
        return LoopbackServer { raw, output ->
            assertEquals("Bearer $key", raw.headers["authorization"])
            reply(calls.getAndIncrement(), JSONObject(raw.body), output)
        }.use { server ->
            val provider = CloudChatProvider({ AiAccount(AiProvider.OPENAI, key) }, Endpoints { server.url }, recordEmptyCompletion = {})
            val failure = try {
                runBlocking { provider.respond(key, request, session) { events += it } }
                null
            } catch (failure: CloudFailure) { failure }
            assertTrue(server.failures.toString(), server.failures.isEmpty())
            Exchange(server.requests.map { Request(it.path, JSONObject(it.body)) }, events, failure)
        }
    }

    private fun firstRequest(request: CloudRequest) = exchange(request).requests.first()

    private fun decision(text: String) =
        JSONObject().put("kind", "answer").put("text", text).put("brief", "").put("search", "none").toString()

    /** The decision step answers with kind answer. Every other request gets one JSON text reply. */
    private fun answer(body: JSONObject, output: OutputStream) =
        if (body.optJSONArray("tools")?.getJSONObject(0)?.getString("name") == "submit_chat_decision")
            output.responsesCall("call_decision", "submit_chat_decision", decision("Hello"))
        else output.responsesText("{\"a\":1}")

    private fun textRequest(text: String, images: List<String> = emptyList()) =
        CloudRequest(listOf(CloudTurn("user", text, images)), false, "Reply in one line.")

    @Test fun usesResponsesPathWithoutStorage() {
        val request = firstRequest(textRequest("Hi"))
        assertEquals("/v1/responses", request.path)
        assertFalse(request.json.getBoolean("store"))
        assertTrue(request.json.getBoolean("stream"))
        assertEquals("gpt-6.1-terra", request.json.getString("model"))
        assertFalse(request.json.has("messages"))
    }

    @Test fun systemBecomesInstructions() {
        val request = firstRequest(textRequest("Hi"))
        val instructions = request.json.getString("instructions")
        assertTrue(instructions, instructions.startsWith("You are Alphy"))
        assertTrue(instructions, instructions.endsWith("Reply in one line."))
        assertTrue(request.input.none { it.optString("role") == "system" })
        val user = request.input.single()
        assertEquals("user", user.getString("role"))
        assertEquals("input_text", user.getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("Hi", user.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun strictToolsAreFlat() {
        val tool = firstRequest(decisionRequest()).json.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("submit_chat_decision", tool.getString("name"))
        assertEquals("object", tool.getJSONObject("parameters").getString("type"))
        assertTrue(tool.getBoolean("strict"))
        assertFalse(tool.has("function"))
    }

    @Test fun decisionForcesNamedTool() {
        val json = firstRequest(decisionRequest()).json
        val choice = json.getJSONObject("tool_choice")
        assertEquals(setOf("type", "name"), choice.keys().asSequence().toSet())
        assertEquals("function", choice.getString("type"))
        assertEquals("submit_chat_decision", choice.getString("name"))
        assertEquals("low", json.getJSONObject("reasoning").getString("effort"))
    }

    @Test fun agentStepUsesReasoningOnAndKeepsTheTurns() {
        val request = firstRequest(agentStepRequest())
        val json = request.json
        assertEquals("medium", json.getJSONObject("reasoning").getString("effort"))
        assertEquals("json_object", json.getJSONObject("text").getJSONObject("format").getString("type"))
        assertFalse(json.has("response_format"))
        assertFalse(json.has("thinking"))
        assertFalse(json.has("tools"))
        assertEquals(OpenAiProfile.maxOutputTokens, json.getInt("max_output_tokens"))
        val messages = request.input.filter { it.optString("type") == "message" && it.getString("role") != "developer" }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.getString("role") })
        assertEquals("output_text", messages[1].getJSONArray("content").getJSONObject(0).getString("type"))
        val image = messages[2].getJSONArray("content").getJSONObject(1)
        assertEquals("input_image", image.getString("type"))
        assertEquals(AGENT_SCREENSHOT, image.getString("image_url"))
    }

    @Test fun jsonModeAddsDeveloperJsonInstructionFirst() {
        val request = firstRequest(agentStepRequest())
        assertTrue(request.json.getString("instructions").endsWith("Agent rules."))
        assertFalse(request.input.toString().contains("Agent rules."))
        val first = request.input.first()
        assertEquals("message", first.getString("type"))
        assertEquals("developer", first.getString("role"))
        assertEquals("Return one JSON object.", first.getString("content"))
        val plain = firstRequest(textRequest("Hi")).input
        assertTrue(plain.none { it.optString("role") == "developer" })
    }

    @Test fun toolRoundReplaysReasoningItems() = discoveryRoundReplaysReasoning(generationRequest(), "medium")

    /** Spec 4.5: a tool round with thinking off (the device plan) sends its reasoning back too. OpenAI reasons at effort low. */
    @Test fun devicePlanDiscoveryReplaysReasoningItems() = discoveryRoundReplaysReasoning(
        CloudRequest(listOf(CloudTurn("user", "Set a timer.")), false, jsonObject = true, agentPlan = true), "low")

    private fun discoveryRoundReplaysReasoning(base: CloudRequest, effort: String) {
        val reasoning = JSONObject().put("type", "reasoning").put("id", "rs_fixture")
            .put("summary", JSONArray()).put("encrypted_content", "gAAAA-fixture-ciphertext")
        val request = base.copy(capabilityDiscovery = { JSONObject().put("caller", "miniApp") })
        val exchange = exchange(request) { index, _, output ->
            if (index == 0) output.responsesCall("call_discovery", "discover_capabilities", "{}", reasoning)
            else output.responsesText("{\"a\":1}")
        }
        assertNull(exchange.failure)
        assertEquals(2, exchange.requests.size)
        assertEquals(effort, exchange.requests[0].json.getJSONObject("reasoning").getString("effort"))
        val include = exchange.requests[0].json.getJSONArray("include")
        assertTrue(include.toString(), (0 until include.length()).any { include.getString(it) == "reasoning.encrypted_content" })
        val input = exchange.requests[1].input
        val replayed = input.indexOfFirst { it.optString("type") == "reasoning" }
        assertTrue("The second request has no reasoning item.", replayed >= 0)
        assertEquals(reasoning.toString(), input[replayed].toString())
        val call = input[replayed + 1]
        assertEquals("function_call", call.getString("type"))
        assertEquals("call_discovery", call.getString("call_id"))
        assertEquals("discover_capabilities", call.getString("name"))
        assertEquals("{}", call.getString("arguments"))
        val output = input[replayed + 2]
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_discovery", output.getString("call_id"))
        assertEquals("miniApp", JSONObject(output.getString("output")).getString("caller"))
    }

    /** Spec 4.5 with search: the round after a scheduled-agent search sends its reasoning item back unchanged. */
    @Test fun searchRoundReplaysReasoningItems() {
        val reasoning = JSONObject().put("type", "reasoning").put("id", "rs_search")
            .put("summary", JSONArray()).put("encrypted_content", "gAAAA-search-ciphertext")
        val source = "https://example.com/news"
        val request = CloudRequest(listOf(CloudTurn("user", "News?")), true, scheduledAgent = true)
        val exchange = exchange(request) { index, _, output ->
            when (index) {
                0 -> output.responsesCall("call_search", "web_search", "{\"query\":\"news\"}", reasoning)
                1 -> output.jsonResponse(openAiSearchReply(JSONArray().put(sourceEntry(source, "News")).toString(), listOf(source)))
                else -> output.responsesText("News [1]")
            }
        }
        assertNull(exchange.failure)
        assertEquals(3, exchange.requests.size)
        assertEquals("web_search", exchange.requests[1].json.getJSONArray("tools").getJSONObject(0).getString("type"))
        val input = exchange.requests[2].input
        val replayed = input.indexOfFirst { it.optString("type") == "reasoning" }
        assertTrue("The round after the search has no reasoning item.", replayed >= 0)
        assertEquals(reasoning.toString(), input[replayed].toString())
        val call = input[replayed + 1]
        assertEquals("function_call", call.getString("type"))
        assertEquals("call_search", call.getString("call_id"))
        assertEquals("web_search", call.getString("name"))
        assertEquals("{\"query\":\"news\"}", call.getString("arguments"))
        val output = input[replayed + 2]
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_search", output.getString("call_id"))
        assertEquals(source, JSONObject(output.getString("output")).getJSONArray("results").getJSONObject(0).getString("url"))
    }

    @Test fun imagesBecomeInputImage() {
        val image = "data:image/png;base64,iVBORw0KGgo="
        val content = firstRequest(textRequest("Describe.", listOf(image))).input.single().getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
        assertEquals(image, content.getJSONObject(1).getString("image_url"))
    }

    @Test fun answerStreamsText() {
        val exchange = exchange(decisionRequest()) { index, body, output ->
            if (index == 0) answer(body, output) else output.responsesText("Hello", ", ", "world")
        }
        assertNull(exchange.failure)
        assertEquals(listOf("Hello", ", ", "world"),
            exchange.events.filterIsInstance<CloudEvent.AnswerFragment>().map { it.value })
        val round = exchange.events.last() as CloudEvent.RoundComplete
        assertEquals("Hello, world", JSONObject(round.text).getString("text"))
    }

    @Test fun incompleteIsLength() {
        fun incomplete(output: OutputStream) {
            output.responsesStart("{\"cut\":")
            output.responsesEvent("response.incomplete", JSONObject().put("response", JSONObject()
                .put("status", "incomplete").put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))))
        }
        val chat = exchange(textRequest("Hi")) { _, _, output -> incomplete(output) }.failure
        assertEquals("incomplete", chat?.code)
        assertEquals("Достигнут лимит ответа. Уточните запрос.", chat?.message)
        val plan = exchange(generationRequest()) { _, _, output -> incomplete(output) }.failure
        assertEquals("response_length", plan?.code)
    }

    @Test fun refusalStopsWithRefusalCode() {
        val exchange = exchange(decisionRequest()) { index, body, output ->
            if (index == 0) answer(body, output) else {
                output.responsesHeaders()
                output.responsesMessage(JSONObject().put("type", "refusal").put("refusal", ""))
                output.responsesEvent("response.refusal.delta", textDelta("I cannot help with that."))
                output.responsesCompleted()
            }
        }
        assertEquals("refusal", exchange.failure?.code)
        assertTrue(exchange.events.none { it is CloudEvent.AnswerFragment })
    }

    @Test fun missingCompletedIsStreamInvalid() {
        val failure = exchange(textRequest("Hi")) { _, _, output -> output.responsesStart("Hello") }.failure
        assertEquals("stream_invalid", failure?.code)
    }

    /** A stream error maps as the HTTP error would, and names OpenAI (spec 13.1). The field names follow the Responses streaming reference. */
    @Test fun streamErrorEventsMapToProviderFailures() {
        fun detail(code: String) = JSONObject().put("code", code).put("message", "Fixture message")
        fun failed(code: String) = JSONObject().put("response", JSONObject().put("status", "failed").put("error", detail(code)))
        // The ResponseErrorEvent shape: code, message, param, and sequence_number at the top level.
        fun error(code: String) = detail(code).put("param", JSONObject.NULL).put("sequence_number", 3)
        val events = mapOf("response.failed" to ::failed, "error" to ::error)
        for ((type, build) in events) for ((code, expected) in mapOf("rate_limit_exceeded" to "rate_limit", "server_error" to "unavailable")) {
            val failure = exchange(textRequest("Hi")) { _, _, output ->
                output.responsesStart("Partial")
                output.responsesEvent(type, build(code))
            }.failure
            assertEquals("$type $code", expected, failure?.code)
            assertTrue("$type $code: ${failure?.message}", failure?.message?.startsWith("OpenAI: ") == true)
        }
    }

    @Test fun maxTokensIsClamped() {
        val json = firstRequest(generationRequest(expanded = true)).json
        assertEquals(OpenAiProfile.maxOutputTokens, json.getInt("max_output_tokens"))
        assertFalse(json.has("max_tokens"))
    }

    @Test fun cancelClosesResponsesStream() = assertCancelClosesStream(AiProvider.OPENAI, 5_000) { responsesStart("Hel") }

    // Failure case: the change call reasoned at medium with no output limit, so a change took longer than a new plan.
    @Test fun appFileStageSendsTheInstructionsAloneAtEffortLow() {
        for (stage in listOf(CloudGenerationStage.APP_FILE, CloudGenerationStage.APP_FILE_CHANGE)) {
            val request = firstRequest(appFileRequest(stage))
            assertEquals("PLAN SYSTEM", request.json.getString("instructions"))
            assertEquals("$stage", "low", request.json.getJSONObject("reasoning").getString("effort"))
            assertEquals("$stage", 4096, request.json.getInt("max_output_tokens"))
            assertFalse(request.json.optJSONObject("text")?.has("format") == true)
            assertTrue(request.input.none { it.optString("role") == "developer" })
        }
    }

    @Test fun appFileStageMakesNoTransportRetry() {
        val session = CloudSession()
        // The server closes each connection before the response headers.
        val exchange = exchange(appFileRequest(CloudGenerationStage.APP_FILE), session) { _, _, _ -> }
        assertEquals("pre_headers_io", exchange.failure?.code)
        assertEquals(1, exchange.requests.size)
        assertTrue("The plan call used the transport retry of the loop.", session.claimGenerationTransportRetry())
    }

    @Test fun abortCallEndsTheStreamAndKeepsTheSession() {
        val streaming = CountDownLatch(1)
        val release = CountDownLatch(1)
        stallingResponses(release, "Done") { responsesStart("Hel") }.use { server ->
            val provider = CloudChatProvider({ AiAccount(AiProvider.OPENAI, key) }, Endpoints { server.url }, recordEmptyCompletion = {})
            val session = CloudSession()
            val request = appFileRequest(CloudGenerationStage.APP_FILE)
            try {
                runBlocking {
                    val first = async(Dispatchers.IO) {
                        try { provider.respond(key, request, session) { if (it is CloudEvent.Text) streaming.countDown() }; null }
                        catch (failure: CloudFailure) { failure }
                    }
                    assertTrue(streaming.await(10, TimeUnit.SECONDS))
                    Thread.sleep(200) // The client returns from the text callback to its read.
                    session.abortCall()
                    val failure = withTimeout(5_000) { first.await() }
                    assertTrue("$failure ${failure?.code}", failure != null && failure.code != "cancelled")
                    val text = StringBuilder()
                    provider.respond(key, request, session) { if (it is CloudEvent.Text) text.append(it.value) }
                    assertEquals("Done", text.toString())
                }
            } finally { release.countDown() }
            assertEquals(2, server.requests.size)
            assertTrue(server.failures.toString(), server.failures.isEmpty())
        }
    }
}
