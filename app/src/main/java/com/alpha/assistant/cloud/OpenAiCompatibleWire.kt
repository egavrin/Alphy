package com.alpha.assistant.cloud

import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject

/** Chat Completions for OpenRouter and DeepSeek. The profile supplies the path, reasoning control, and output limit. */
internal class OpenAiCompatibleWire(
    private val provider: AiProvider,
    private val baseUrl: String,
    private val model: String,
    private val http: CloudHttp,
    private val recordEmptyCompletion: (String) -> Unit,
) : ChatWire {
    private val profile = provider.profile
    private val openRouter = provider == AiProvider.OPENROUTER

    override suspend fun complete(key: String, body: JSONObject, limits: StreamLimits, session: CloudSession,
        onText: suspend (String) -> Unit): Completion {
        val path = if (limits.stage.strictDecision) profile.strictChatPath else profile.chatPath
        val thinking = body.optJSONObject("thinking")?.optString("type") == "enabled"
        return stream(key, path, wireBody(body).utf8(), session, onText, limits, thinking)
    }

    /** Maps the canonical body to a new object. The shared profile objects are copied, never changed. */
    private fun wireBody(body: JSONObject): JSONObject {
        val wire = JSONObject()
        for (name in body.keys()) if (name != "thinking") wire.put(name, body.get(name))
        val enabled = body.optJSONObject("thinking")?.optString("type") == "enabled"
        val reasoning = JSONObject((if (enabled) profile.reasoningOn else profile.reasoningOff).toString())
        for (name in reasoning.keys()) wire.put(name, reasoning.get(name))
        wire.put("model", model)
        if (wire.has("max_tokens")) wire.put("max_tokens", minOf(wire.getInt("max_tokens"), profile.maxOutputTokens))
        if (openRouter) wire.put("provider", openRouterProviderPreferences())
        val messages = body.optJSONArray("messages") ?: return wire
        wire.put("messages", JSONArray().apply {
            for (index in 0 until messages.length()) {
                val message = messages.getJSONObject(index)
                val replay = message.optJSONArray("reasoning_replay")
                if (replay == null) { put(message); continue }
                put(JSONObject().apply {
                    for (name in message.keys()) if (name != "reasoning_replay") put(name, message.get(name))
                    // OpenRouter takes its reasoning_details array back. DeepSeek takes the reasoning text (spec 4.3).
                    if (openRouter) put("reasoning_details", replay) else put("reasoning_content", replay.getString(0))
                })
            }
        })
        return wire
    }

    private suspend fun stream(key: String, path: String, body: ByteArray, session: CloudSession, onText: suspend (String) -> Unit,
        limits: StreamLimits, thinking: Boolean): Completion = http.streamLines(provider, baseUrl + path, bearerHeaders(key), body, session, limits) { nextLine ->
        val (maxText, allowTools, reportLength, stage, maxWireChars, retainReasoning) = limits
        fun toolFragment(json: JSONObject, key: String): String {
            if (!stage.strictDecision) return json.optString(key)
            if (json.isNull(key)) return ""
            return json.opt(key) as? String
                ?: throw streamInvalid()
        }
        val text = StringBuilder()
        val ids = mutableMapOf<Int, StringBuilder>(); val names = mutableMapOf<Int, StringBuilder>(); val arguments = mutableMapOf<Int, StringBuilder>()
        var finish: String? = null
        var done = false
        var contentSeen = false
        var reasoning = false
        // Spec 4.3: DeepSeek takes reasoning_content back from every thinking round, even when it is empty, and none without thinking.
        val retainedReasoning = if (retainReasoning && thinking && !openRouter) StringBuilder() else null
        val retainedDetails = if (retainReasoning && openRouter) JSONArray() else null
        var alternateText = false
        var bomData = false
        while (true) {
            val line = nextLine() ?: break
            if (!line.startsWith("data:")) {
                bomData = bomData || line.startsWith("\uFEFFdata:")
                continue
            }
            val data = line.substring(5).trim()
            if (data == "[DONE]") { done = true; break }
            if (data.isEmpty()) continue
            val json = try { JSONObject(data) } catch (_: Exception) { throw streamInvalid() }
            if (json.has("error")) throw CloudFailure("provider", "${provider.displayName} прервал ответ. Повторите попытку.")
            val choices = json.optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val choice = choices.getJSONObject(0)
            alternateText = alternateText || choice.optJSONObject("message").hasText("content") ||
                (1 until choices.length()).any { index ->
                    val other = choices.optJSONObject(index)
                    other?.optJSONObject("delta").hasText("content") || other?.optJSONObject("message").hasText("content")
                }
            if (!choice.isNull("finish_reason")) finish = choice.getString("finish_reason")
            if(!allowTools && finish == "tool_calls") throw toolsRefused()
            val delta = choice.optJSONObject("delta") ?: continue
            // An OpenAI-family model behind OpenRouter streams a refusal in delta.refusal, with finish_reason stop.
            if (delta.hasText("refusal")) throw refusalFailure(provider)
            reasoning = reasoning || delta.hasText("reasoning_content") || (delta.optJSONArray("reasoning_details")?.length() ?: 0) > 0
            if (retainedReasoning != null && !delta.isNull("reasoning_content")) {
                val fragment = delta.get("reasoning_content") as? String
                    ?: throw CloudFailure("stream_invalid", "The provider returned invalid reasoning content.")
                if (retainedReasoning.length + fragment.length > maxWireChars)
                    throw CloudFailure("response_size", "The reasoning response exceeds the allowed size.")
                retainedReasoning.append(fragment)
            }
            // OpenRouter streams reasoning_details in order. The complete sequence is their concatenation.
            if (retainedDetails != null && !delta.isNull("reasoning_details")) {
                val details = delta.get("reasoning_details") as? JSONArray
                    ?: throw CloudFailure("stream_invalid", "The provider returned invalid reasoning content.")
                for (index in 0 until details.length()) retainedDetails.put(details.optJSONObject(index)
                    ?: throw CloudFailure("stream_invalid", "The provider returned invalid reasoning content."))
            }
            val toolCalls = delta.optJSONArray("tool_calls")
            if(!allowTools && !delta.isNull("tool_calls") && toolCalls?.length() != 0)
                throw toolsRefused()
            if (!delta.isNull("content")) {
                val chunk = delta.get("content") as? String ?: throw streamInvalid()
                if(text.length + chunk.length > maxText) throw responseTooLarge()
                contentSeen = true
                text.append(chunk)
                onText(chunk)
            }
            toolCalls?.let { calls -> repeat(calls.length()) { i ->
                val call = calls.getJSONObject(i); val index = call.getInt("index")
                if(call.has("type") && call.getString("type") != "function") throw CloudFailure("tool_invalid","The assistant returned an unsupported tool type.")
                if (index !in 0 until MAX_TOOL_CALLS) throw toolLimit()
                ids.getOrPut(index) { StringBuilder() }.append(toolFragment(call, "id"))
                call.optJSONObject("function")?.let { function ->
                    names.getOrPut(index) { StringBuilder() }.append(toolFragment(function, "name"))
                    arguments.getOrPut(index) { StringBuilder() }.append(toolFragment(function, "arguments"))
                }
            } }
        }
        session.checkActive(currentCoroutineContext())
        if (finish == "content_filter") throw refusalFailure(provider)
        requireFinish(finish, done, reportLength)
        val calls = ids.keys.sorted().map { ToolCall(ids[it].toString(), names[it]?.toString().orEmpty(), arguments[it]?.toString().orEmpty()) }
        if (finish == "tool_calls" && calls.isEmpty() || finish == "stop" && calls.isNotEmpty()) throw CloudFailure("stream_invalid", "Неполный вызов функции")
        if (calls.isEmpty() && text.isBlank()) throw emptyResponse(provider, recordEmptyCompletion, stage, contentSeen, text,
            "reasoning=$reasoning alternateText=$alternateText bomData=$bomData")
        Completion(text.toString(), calls, retainedDetails ?: retainedReasoning?.let { JSONArray().put(it.toString()) })
    }
}

private fun JSONObject?.hasText(field: String): Boolean = (this?.opt(field) as? String)?.isNotBlank() == true
