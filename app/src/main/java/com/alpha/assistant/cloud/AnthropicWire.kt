package com.alpha.assistant.cloud

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** With this beta, a changed conversation prefix drops the replayed thinking blocks instead of failing the round. */
private const val THINKING_BINDING = "thinking-binding-controls-2026-08-01"

/** The HTTP status of each stream `error.type`. A stream error then maps to the same failure as the HTTP error. */
private val ERROR_STATUS = mapOf(
    "invalid_request_error" to 400, "authentication_error" to 401, "billing_error" to 402, "permission_error" to 403,
    "not_found_error" to 404, "request_too_large" to 413, "rate_limit_error" to 429, "api_error" to 500, "overloaded_error" to 529,
)

/**
 * Forced tools as structured output (spec 8.7). Claude chat models reject a forced `tool_choice` (spec 4.2), so the
 * wire asks for one JSON object that lists the calls. Alphy logic checks the calls as before.
 */
internal object StructuredCalls {
    /** The canonical tools that `tool_choice` forces: one named function, or every tool for `"required"`. Otherwise null. */
    fun forced(body: JSONObject): JSONArray? {
        val tools = body.optJSONArray("tools") ?: return null
        val choice = body.opt("tool_choice")
        if (choice == "required") return tools
        val name = (choice as? JSONObject)?.getJSONObject("function")?.getString("name") ?: return null
        return JSONArray().put((0 until tools.length()).map(tools::getJSONObject).single { it.toolName() == name })
    }

    /**
     * A root object with one `calls` array. Each item is `anyOf` the tools, with the name fixed by a one-value `enum`.
     * Each branch keeps the description of its tool, as a forced tool does on the other providers.
     */
    fun schema(tools: JSONArray): JSONObject {
        val branches = JSONArray()
        for (index in 0 until tools.length()) {
            val function = tools.getJSONObject(index).getJSONObject("function")
            val properties = JSONObject()
                .put("name", JSONObject().put("type", "string").put("enum", JSONArray().put(function.getString("name"))))
                .put("arguments", function.getJSONObject("parameters"))
            // A null description puts no key.
            branches.put(strictObject(properties, "name", "arguments").put("description", function.opt("description")))
        }
        val calls = JSONObject().put("type", "array").put("items", JSONObject().put("anyOf", branches))
        return strictObject(JSONObject().put("calls", calls), "calls")
    }

    /** Turns the JSON reply into tool calls with new IDs. Invalid JSON or an unknown tool name gives `tool_invalid`. */
    fun calls(json: String, tools: JSONArray): List<ToolCall> {
        fun invalid() = CloudFailure("tool_invalid", "The assistant returned invalid structured tool calls.")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).toolName() }
        val calls = try { JSONObject(json).getJSONArray("calls") } catch (_: JSONException) { throw invalid() }
        return List(calls.length()) { index ->
            val call = calls.optJSONObject(index) ?: throw invalid()
            val name = call.opt("name") as? String
            val arguments = call.optJSONObject("arguments")
            if (name == null || name !in names || arguments == null) throw invalid()
            ToolCall("call_${UUID.randomUUID()}", name, arguments.toString())
        }
    }

    private fun JSONObject.toolName() = getJSONObject("function").getString("name")

    private fun strictObject(properties: JSONObject, vararg required: String) = JSONObject().put("type", "object")
        .put("properties", properties).put("required", JSONArray(required.toList())).put("additionalProperties", false)
}

/**
 * The Anthropic Messages API (spec 8.3). Chat models always use adaptive thinking, and the profile effort is the only
 * control. A tool round sends the thinking blocks back unchanged.
 */
internal class AnthropicWire(
    private val baseUrl: String,
    private val model: String,
    private val http: CloudHttp,
    private val recordEmptyCompletion: (String) -> Unit,
) : ChatWire {
    private val profile = AnthropicProfile
    private val provider = AiProvider.ANTHROPIC

    private class Call(val id: String, val name: String, val input: String) { val arguments = StringBuilder() }

    /** A streamed thinking block. [block] is already in the replay, and the stop of the block writes the text and signature. */
    private class Thinking(val block: JSONObject) {
        val text = StringBuilder(block.optString("thinking"))
        val signature = StringBuilder(block.optString("signature"))
    }

    override suspend fun complete(key: String, body: JSONObject, limits: StreamLimits, session: CloudSession,
        onText: suspend (String) -> Unit): Completion {
        val forced = StructuredCalls.forced(body)
        val headers = anthropicHeaders(key) + ("anthropic-beta" to THINKING_BINDING)
        // The JSON of structured calls is not answer text.
        val textSink: suspend (String) -> Unit = if (forced == null) onText else { _ -> }
        val completion = http.streamLines(provider, baseUrl + profile.chatPath, headers, wireBody(body, forced).utf8(),
            session, limits) { nextLine -> parse(nextLine, limits, session, textSink) }
        // The request of these calls had no tools, so their thinking cannot be replayed (decision 5b).
        return if (forced == null) completion else Completion("", StructuredCalls.calls(completion.text, forced))
    }

    /** Maps the canonical body to a new object from known fields only. The shared profile objects are copied, never changed. */
    private fun wireBody(body: JSONObject, forced: JSONArray?): JSONObject {
        val messages = body.getJSONArray("messages")
        // Anthropic rejects tool_use and tool_result blocks in a request without tools. Forced bodies are fresh conversations.
        require(forced == null || (0 until messages.length()).map(messages::getJSONObject).none { it.has("tool_calls") || it.getString("role") == "tool" })
        // Anthropic requires max_tokens. Spec 8.4: the profile output limit caps it.
        val wire = JSONObject().put("model", model).put("stream", true)
            .put("max_tokens", minOf(body.optInt("max_tokens", profile.maxOutputTokens), profile.maxOutputTokens))
        val enabled = body.optJSONObject("thinking")?.optString("type") == "enabled"
        val reasoning = JSONObject((if (enabled) profile.reasoningOn else profile.reasoningOff).toString())
        for (name in reasoning.keys()) wire.put(name, reasoning.get(name))
        // The chat models reject disabled thinking. A canonical request with thinking off gets the low effort of reasoningOff.
        wire.put("thinking", JSONObject().put("type", "adaptive")
            .put("block_binding", JSONObject().put("prefix_mismatch_behavior", "drop_block")))
        if (forced != null) wire.put("output_config", (wire.optJSONObject("output_config") ?: JSONObject())
            .put("format", JSONObject().put("type", "json_schema").put("schema", StructuredCalls.schema(forced))))
        val tools = body.optJSONArray("tools")
        if (forced == null && tools != null) {
            wire.put("tools", JSONArray().apply {
                for (index in 0 until tools.length()) {
                    val function = tools.getJSONObject(index).getJSONObject("function")
                    put(JSONObject().put("name", function.getString("name")).put("description", function.opt("description"))
                        .put("input_schema", function.getJSONObject("parameters")).apply { if (function.optBoolean("strict")) put("strict", true) })
                }
            })
            // A forced choice never gets here, so the canonical choice is "auto" or "none".
            (body.opt("tool_choice") as? String)?.let { wire.put("tool_choice", JSONObject().put("type", it)) }
        }
        val system = mutableListOf<String>()
        val turns = JSONArray()
        // Anthropic needs alternate roles: adjacent messages of one role become one message. Tool results join one user message.
        fun add(role: String, blocks: JSONArray) {
            val last = turns.optJSONObject(turns.length() - 1)
            if (last?.getString("role") != role) turns.put(JSONObject().put("role", role).put("content", blocks))
            else for (index in 0 until blocks.length()) last.getJSONArray("content").put(blocks.get(index))
        }
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            when (val role = message.getString("role")) {
                "system" -> system += message.getString("content")
                "tool" -> add("user", JSONArray().put(JSONObject().put("type", "tool_result")
                    .put("tool_use_id", message.getString("tool_call_id")).put("content", message.getString("content"))))
                else -> add(role, blocks(message))
            }
        }
        // Spec 8.3: JSON without a schema gets one constant system line, never a prefill.
        if (body.optJSONObject("response_format")?.optString("type") == "json_object") system += "Return one JSON object."
        if (system.isNotEmpty()) wire.put("system", system.joinToString("\n\n"))
        return wire.put("messages", turns)
    }

    /** The replayed thinking blocks, the text and images, and the tool calls of one message (decision 5a). */
    private fun blocks(message: JSONObject) = JSONArray().apply {
        message.optJSONArray("reasoning_replay")?.let { replay -> for (index in 0 until replay.length()) put(replay.get(index)) }
        when (val content = message.opt("content")) {
            is String -> if (content.isNotEmpty()) put(text(content))
            is JSONArray -> for (index in 0 until content.length()) {
                val part = content.getJSONObject(index)
                put(if (part.getString("type") == "image_url") image(part.getJSONObject("image_url").getString("url")) else text(part.getString("text")))
            }
        }
        message.optJSONArray("tool_calls")?.let { calls ->
            for (index in 0 until calls.length()) {
                val call = calls.getJSONObject(index)
                val function = call.getJSONObject("function")
                put(JSONObject().put("type", "tool_use").put("id", call.getString("id")).put("name", function.getString("name"))
                    .put("input", JSONObject(function.getString("arguments"))))
            }
        }
    }

    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)

    /** A base64 data URL becomes an `image` block (spec 10). Alphy sends images only as data URLs. */
    private fun image(url: String): JSONObject {
        val header = url.substringBefore(',')
        require(header.startsWith("data:") && header.endsWith(";base64")) { "The image is not a base64 data URL." }
        return JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64")
            .put("media_type", header.removePrefix("data:").removeSuffix(";base64")).put("data", url.substringAfter(',')))
    }

    private suspend fun parse(nextLine: suspend () -> String?, limits: StreamLimits, session: CloudSession,
        onText: suspend (String) -> Unit): Completion {
        fun JSONObject.string(name: String) = opt(name) as? String ?: throw streamInvalid()
        val text = StringBuilder()
        val calls = LinkedHashMap<Int, Call>()
        val thinking = HashMap<Int, Thinking>()
        val replay = if (limits.retainReasoning) JSONArray() else null
        var stopReason: String? = null
        var done = false
        var contentSeen = false
        var reasoning = false
        while (!done) {
            val line = nextLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty()) continue
            val event = JSONObject(data)
            // Ping and unknown event, block, and delta types change nothing. Blocks are read by type, never by position.
            when (event.optString("type")) {
                "content_block_start" -> {
                    val index = event.getInt("index")
                    val block = event.getJSONObject("content_block")
                    when (block.optString("type")) {
                        "thinking", "redacted_thinking" -> {
                            reasoning = true
                            // Every block goes back in its original order, also with empty text (decision 5a).
                            val copy = JSONObject(block.toString())
                            replay?.put(copy)
                            if (copy.getString("type") == "thinking") thinking[index] = Thinking(copy)
                        }
                        "tool_use" -> {
                            if (!limits.allowTools) throw toolsRefused()
                            if (calls.size == MAX_TOOL_CALLS) throw toolLimit()
                            calls[index] = Call(block.string("id"), block.string("name"), block.optJSONObject("input")?.toString() ?: "{}")
                        }
                    }
                }
                "content_block_delta" -> {
                    val index = event.getInt("index")
                    val delta = event.getJSONObject("delta")
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val chunk = delta.string("text")
                            if (text.length + chunk.length > limits.maxText) throw responseTooLarge()
                            contentSeen = true
                            text.append(chunk)
                            onText(chunk)
                        }
                        "input_json_delta" -> (calls[index] ?: throw streamInvalid()).arguments.append(delta.string("partial_json"))
                        "thinking_delta" -> (thinking[index] ?: throw streamInvalid()).text.append(delta.string("thinking"))
                        "signature_delta" -> (thinking[index] ?: throw streamInvalid()).signature.append(delta.string("signature"))
                    }
                }
                "content_block_stop" -> thinking.remove(event.getInt("index"))?.let {
                    it.block.put("thinking", it.text.toString()).put("signature", it.signature.toString())
                }
                "message_delta" -> (event.getJSONObject("delta").opt("stop_reason") as? String)?.let { stopReason = it }
                "message_stop" -> done = true
                // Decision 8: a stream error maps as the HTTP error of its type would. The partial text is dropped.
                "error" -> throw providerFailure(provider, ERROR_STATUS[event.optJSONObject("error")?.optString("type").orEmpty()] ?: 500, data, false)
            }
        }
        session.checkActive(currentCoroutineContext())
        // A refusal can follow partial text. The failure drops that text (spec 13.2).
        if (stopReason == "refusal") throw refusalFailure(provider)
        val finish = when (stopReason) {
            "end_turn" -> "stop"
            "tool_use" -> "tool_calls"
            "max_tokens" -> "length"
            else -> stopReason
        }
        requireFinish(finish, done, limits.reportLength)
        val toolCalls = calls.values.map { ToolCall(it.id, it.name, it.arguments.ifEmpty { it.input }.toString()) }
        if (toolCalls.isEmpty() && text.isBlank())
            throw emptyResponse(provider, recordEmptyCompletion, limits.stage, contentSeen, text, "reasoning=$reasoning")
        return Completion(text.toString(), toolCalls, replay)
    }
}
