package com.alpha.assistant.cloud

import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The OpenAI Responses API (spec 8.9). Every request sends `store: false`, so OpenAI keeps no copy of the
 * conversation. A tool round sends the encrypted reasoning items back unchanged.
 */
internal class OpenAiResponsesWire(
    private val baseUrl: String,
    private val model: String,
    private val http: CloudHttp,
    private val recordEmptyCompletion: (String) -> Unit,
) : ChatWire {
    private val profile = OpenAiProfile
    private val provider = AiProvider.OPENAI

    private class Call(val id: String, val name: String) { val arguments = StringBuilder() }

    override suspend fun complete(key: String, body: JSONObject, limits: StreamLimits, session: CloudSession,
        onText: suspend (String) -> Unit): Completion {
        return http.streamLines(provider, baseUrl + profile.chatPath, bearerHeaders(key), wireBody(body).utf8(), session, limits) { nextLine ->
            parse(nextLine, limits, session, onText)
        }
    }

    /** Maps the canonical body to a new object. The shared profile objects are copied, never changed. */
    private fun wireBody(body: JSONObject): JSONObject {
        val enabled = body.optJSONObject("thinking")?.optString("type") == "enabled"
        val wire = JSONObject((if (enabled) profile.reasoningOn else profile.reasoningOff).toString())
            .put("model", model).put("stream", true).put("store", false)
            // With store false, OpenAI returns the encrypted reasoning only on request. A tool round sends it back.
            .put("include", JSONArray().put("reasoning.encrypted_content"))
        if (body.has("max_tokens")) wire.put("max_output_tokens", minOf(body.getInt("max_tokens"), profile.maxOutputTokens))
        body.optJSONObject("response_format")?.let { wire.put("text", JSONObject().put("format", it)) }
        body.optJSONArray("tools")?.let { tools ->
            wire.put("tools", JSONArray().apply {
                for (index in 0 until tools.length()) {
                    val function = tools.getJSONObject(index).getJSONObject("function")
                    // Responses tools are strict by default. A canonical tool without strict stays non-strict.
                    put(JSONObject(function.toString()).put("type", "function").put("strict", function.optBoolean("strict")))
                }
            })
        }
        when (val choice = body.opt("tool_choice")) {
            null -> Unit
            is JSONObject -> wire.put("tool_choice", JSONObject().put("type", "function")
                .put("name", choice.getJSONObject("function").getString("name")))
            else -> wire.put("tool_choice", choice)
        }
        val instructions = mutableListOf<String>()
        val input = JSONArray()
        // The Responses API checks only input messages, not instructions, for the word json in json_object mode.
        if (body.optJSONObject("response_format")?.optString("type") == "json_object")
            input.put(JSONObject().put("type", "message").put("role", "developer").put("content", "Return one JSON object."))
        val messages = body.getJSONArray("messages")
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            val role = message.getString("role")
            if (role == "system") { instructions += message.getString("content"); continue }
            if (role == "tool") {
                input.put(JSONObject().put("type", "function_call_output").put("call_id", message.getString("tool_call_id"))
                    .put("output", message.getString("content")))
                continue
            }
            message.optJSONArray("reasoning_replay")?.let { replay -> for (item in 0 until replay.length()) input.put(replay.get(item)) }
            when (val content = message.opt("content")) {
                is String, is JSONArray -> input.put(JSONObject().put("type", "message").put("role", role).put("content", content(role, content)))
            }
            message.optJSONArray("tool_calls")?.let { calls ->
                for (item in 0 until calls.length()) {
                    val call = calls.getJSONObject(item)
                    val function = call.getJSONObject("function")
                    input.put(JSONObject().put("type", "function_call").put("call_id", call.getString("id"))
                        .put("name", function.getString("name")).put("arguments", function.getString("arguments")))
                }
            }
        }
        if (instructions.isNotEmpty()) wire.put("instructions", instructions.joinToString("\n\n"))
        return wire.put("input", input)
    }

    /** User text becomes `input_text`, assistant text becomes `output_text`, and an `image_url` part becomes `input_image`. */
    private fun content(role: String, content: Any): JSONArray {
        val textType = if (role == "assistant") "output_text" else "input_text"
        if (content is String) return JSONArray().put(JSONObject().put("type", textType).put("text", content))
        val parts = content as JSONArray
        return JSONArray().apply {
            for (index in 0 until parts.length()) {
                val part = parts.getJSONObject(index)
                put(if (part.getString("type") == "image_url") JSONObject().put("type", "input_image")
                    .put("image_url", part.getJSONObject("image_url").getString("url"))
                else JSONObject().put("type", textType).put("text", part.getString("text")))
            }
        }
    }

    private suspend fun parse(nextLine: suspend () -> String?, limits: StreamLimits, session: CloudSession,
        onText: suspend (String) -> Unit): Completion {
        fun JSONObject.string(name: String) = opt(name) as? String ?: throw streamInvalid()
        val text = StringBuilder()
        val calls = LinkedHashMap<Int, Call>()
        val replay = if (limits.retainReasoning) JSONArray() else null
        var contentSeen = false
        var reasoning = false
        var finish: String? = null
        while (finish == null) {
            // A stream that ends before response.completed or response.incomplete is invalid.
            val line = nextLine() ?: throw streamInvalid()
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty()) continue
            val event = JSONObject(data)
            when (event.optString("type")) {
                "response.output_text.delta" -> {
                    val chunk = event.string("delta")
                    if (text.length + chunk.length > limits.maxText) throw responseTooLarge()
                    contentSeen = true
                    text.append(chunk)
                    onText(chunk)
                }
                "response.content_part.added" -> if (event.getJSONObject("part").optString("type") == "refusal")
                    throw refusalFailure(provider)
                "response.output_item.added" -> {
                    val item = event.getJSONObject("item")
                    if (item.optString("type") == "reasoning") reasoning = true
                    if (item.optString("type") == "function_call") {
                        if (!limits.allowTools) throw toolsRefused()
                        if (calls.size == MAX_TOOL_CALLS) throw toolLimit()
                        calls[event.getInt("output_index")] = Call(item.string("call_id"), item.string("name"))
                    }
                }
                "response.function_call_arguments.delta" ->
                    (calls[event.getInt("output_index")] ?: throw streamInvalid()).arguments.append(event.string("delta"))
                "response.output_item.done" -> {
                    val item = event.getJSONObject("item")
                    if (item.optString("type") == "reasoning") { reasoning = true; replay?.put(item) }
                }
                "response.completed" -> finish = if (calls.isEmpty()) "stop" else "tool_calls"
                "response.incomplete" -> finish = event.getJSONObject("response").optJSONObject("incomplete_details")
                    ?.optString("reason").let {
                        if (it == "content_filter") throw refusalFailure(provider)
                        if (it == "max_output_tokens") "length" else "incomplete"
                    }
                // A stream error maps as the HTTP error would (spec 13.2). The `error` event has a top-level `code`. `response.failed` has `response.error.code`.
                "response.failed", "error" -> {
                    val code = if (event.optString("type") == "error") event.optString("code") else event.optJSONObject("response")?.optJSONObject("error")?.optString("code")
                    throw providerFailure(provider, if (code == "rate_limit_exceeded") 429 else 500, data, false)
                }
            }
        }
        session.checkActive(currentCoroutineContext())
        requireFinish(finish, true, limits.reportLength)
        if (calls.isEmpty() && text.isBlank())
            throw emptyResponse(provider, recordEmptyCompletion, limits.stage, contentSeen, text, "reasoning=$reasoning")
        return Completion(text.toString(), calls.values.map { ToolCall(it.id, it.name, it.arguments.toString()) }, replay)
    }
}
