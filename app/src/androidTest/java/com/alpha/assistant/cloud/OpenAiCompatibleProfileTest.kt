package com.alpha.assistant.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.data.MessageProvenance
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Request fixtures of the wire tests. OpenAiResponsesWorkflowTest uses them too.

internal fun decisionRequest(): CloudRequest {
    val turn = CloudTurn("user", "Say hello.")
    val instructions = ChatToolReply.instructions()
    return CloudRequest(listOf(turn), false, instructions, chatDecision = true,
        decisionContext = ChatDecisionContext(turn, emptyList(), instructions, MessageProvenance.CLEAN))
}

internal fun generationRequest(expanded: Boolean = false) = CloudRequest(listOf(CloudTurn("user", "Build an app.")),
    false, jsonObject = true, generationStage = CloudGenerationStage.AGENT, expandedWriterOutput = expanded)

/** The screenshot of [agentStepRequest]. Each wire sends it as an image of the latest user turn. */
internal const val AGENT_SCREENSHOT = "data:image/jpeg;base64,/9j/4AAQ"

/** One app agent step as ToolGenerationService sends it: alternating turns, a screenshot, and no tools. */
internal fun agentStepRequest() = CloudRequest(listOf(CloudTurn("user", "Build an app."),
    CloudTurn("assistant", "{\"tool\":\"write_app\"}"), CloudTurn("user", "write_app: OK.", listOf(AGENT_SCREENSHOT))),
    false, "Agent rules.", jsonObject = true, generationStage = CloudGenerationStage.AGENT, expandedWriterOutput = true)

/** One call of an app-file stage as ToolGenerationService.appFileCall sends it: plain text, no JSON mode. */
internal fun appFileRequest(stage: CloudGenerationStage) =
    CloudRequest(listOf(CloudTurn("user", "A tip calculator.")), false, "PLAN SYSTEM", generationStage = stage)

/** Each test checks one difference between the OpenRouter and DeepSeek profiles of the OpenAI-compatible wire. */
@RunWith(AndroidJUnit4::class)
class OpenAiCompatibleProfileTest {
    private val key = "alpha-test-only"

    private fun chunk(delta: JSONObject, finish: String? = null) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", delta).apply { if (finish != null) put("finish_reason", finish) }))

    private fun OutputStream.stream(vararg deltas: JSONObject, finish: String) {
        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
        for (delta in deltas) write("data: ${chunk(delta)}\n\n".toByteArray())
        write("data: ${chunk(JSONObject(), finish)}\n\ndata: [DONE]\n\n".toByteArray())
        flush()
    }

    private fun text(value: String) = JSONObject().put("content", value)

    private fun call(id: String, name: String, arguments: String) = JSONObject().put("tool_calls", JSONArray().put(
        JSONObject().put("index", 0).put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments))))

    /** The decision step answers with kind answer, so the answer step follows it. */
    private fun decide(body: JSONObject, output: OutputStream) = if (body.has("tools")) output.stream(
        call("decision-0", "submit_chat_decision",
            JSONObject().put("kind", "answer").put("text", "Hello").put("brief", "").put("search", "none").toString()),
        finish = "tool_calls") else output.stream(text("Hello"), finish = "stop")

    private fun json(output: OutputStream) = output.stream(text("{\"a\":1}"), finish = "stop")

    private fun respond(provider: AiProvider, request: CloudRequest, reply: (JSONObject, OutputStream) -> Unit): List<LoopbackRequest> =
        LoopbackServer { raw, output -> reply(JSONObject(raw.body), output) }.use { server ->
            runBlocking { CloudChatProvider({ AiAccount(provider, key) }, Endpoints { server.url }).respond(key, request, CloudSession()) {} }
            assertTrue(server.failures.toString(), server.failures.isEmpty())
            server.requests.toList()
        }

    private fun assertHas(fields: JSONObject, body: JSONObject) =
        fields.keys().forEach { assertEquals(it, fields.get(it).toString(), body.opt(it)?.toString()) }

    @Test fun openRouterUsesStandardPathAndReasoningEffort() {
        val requests = respond(AiProvider.OPENROUTER, decisionRequest(), ::decide) +
            respond(AiProvider.OPENROUTER, generationRequest()) { _, output -> json(output) }
        assertEquals(3, requests.size)
        for (request in requests) {
            assertEquals("/api/v1/chat/completions", request.path)
            val body = JSONObject(request.body)
            assertEquals(OpenRouterProfile.chatModels.first(), body.getString("model"))
            assertFalse(body.has("thinking"))
            assertFalse(body.has("reasoning_effort"))
        }
        assertHas(OpenRouterProfile.reasoningOff, JSONObject(requests[0].body))
        assertHas(OpenRouterProfile.reasoningOn, JSONObject(requests[2].body))
    }

    @Test fun openRouterReplaysReasoningDetails() {
        val details = listOf(
            JSONObject().put("type", "reasoning.text").put("text", "a ").put("format", "anthropic-claude-v1").put("index", 0),
            JSONObject().put("type", "reasoning.text").put("text", "b").put("signature", "sig").put("index", 0))
        val request = generationRequest().copy(capabilityDiscovery = { JSONObject().put("caller", "miniApp") })
        val requests = respond(AiProvider.OPENROUTER, request) { body, output ->
            if (body.getJSONArray("messages").length() == 2) output.stream(
                JSONObject().put("reasoning_details", JSONArray().put(details[0])),
                JSONObject().put("reasoning_details", JSONArray().put(details[1])),
                call("discovery-id", "discover_capabilities", "{}"), finish = "tool_calls")
            else json(output)
        }
        assertEquals(2, requests.size)
        val messages = JSONObject(requests[1].body).getJSONArray("messages")
        val assistant = messages.getJSONObject(messages.length() - 2)
        assertEquals("discovery-id", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals(JSONArray(details).toString(), assistant.getJSONArray("reasoning_details").toString())
        assertFalse(assistant.has("reasoning_replay"))
        assertFalse(assistant.has("reasoning_content"))
    }

    @Test fun openRouterRequestsRequireParametersAndNoDataCollection() {
        val requests = respond(AiProvider.OPENROUTER, decisionRequest(), ::decide)
        assertEquals(2, requests.size)
        for (request in requests) {
            val provider = JSONObject(request.body).getJSONObject("provider")
            assertTrue(provider.getBoolean("require_parameters"))
            assertEquals("deny", provider.getString("data_collection"))
        }
        respond(AiProvider.DEEPSEEK, decisionRequest(), ::decide).forEach { assertFalse(JSONObject(it.body).has("provider")) }
    }

    @Test fun maxTokensIsLimitedByProfile() {
        val request = generationRequest(expanded = true)
        val openRouter = respond(AiProvider.OPENROUTER, request) { _, output -> json(output) }.single()
        assertEquals(OpenRouterProfile.maxOutputTokens, JSONObject(openRouter.body).getInt("max_tokens"))
        val deepSeek = respond(AiProvider.DEEPSEEK, request) { _, output -> json(output) }.single()
        assertEquals(131_072, JSONObject(deepSeek.body).getInt("max_tokens"))
    }

    @Test fun agentStepKeepsTheTurnsAndTheScreenshot() {
        for ((provider, reasoning) in listOf(AiProvider.OPENROUTER to OpenRouterProfile.reasoningOn,
            AiProvider.DEEPSEEK to DeepSeekProfile.reasoningOn)) {
            val body = JSONObject(respond(provider, agentStepRequest()) { _, output -> json(output) }.single().body)
            val messages = body.getJSONArray("messages")
            assertEquals(listOf("system", "user", "assistant", "user"), List(messages.length()) { messages.getJSONObject(it).getString("role") })
            assertEquals("{\"tool\":\"write_app\"}", messages.getJSONObject(2).getString("content"))
            val image = messages.getJSONObject(3).getJSONArray("content").getJSONObject(1)
            assertEquals(AGENT_SCREENSHOT, image.getJSONObject("image_url").getString("url"))
            assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
            assertFalse(body.has("tools"))
            assertHas(reasoning, body)
        }
    }

    @Test fun appFileStageTurnsThinkingOff() {
        fun body(provider: AiProvider, stage: CloudGenerationStage) =
            JSONObject(respond(provider, appFileRequest(stage)) { _, output -> json(output) }.single().body)
        // The change stage turns thinking off too: a change call is as short as a new plan.
        for (stage in listOf(CloudGenerationStage.APP_FILE, CloudGenerationStage.APP_FILE_CHANGE, CloudGenerationStage.APP_FILE_TEMPLATE)) {
            val deepSeek = body(AiProvider.DEEPSEEK, stage)
            assertEquals("$stage", "disabled", deepSeek.getJSONObject("thinking").getString("type"))
            val openRouter = body(AiProvider.OPENROUTER, stage)
            assertEquals("$stage", "low", openRouter.getJSONObject("reasoning").getString("effort"))
            for (sent in listOf(deepSeek, openRouter)) {
                assertEquals("PLAN SYSTEM", sent.getJSONArray("messages").getJSONObject(0).getString("content"))
                assertEquals("$stage", 4096, sent.getInt("max_tokens"))
                assertFalse(sent.has("response_format"))
            }
        }
    }
}
