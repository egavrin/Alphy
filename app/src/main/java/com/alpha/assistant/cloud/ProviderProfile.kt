package com.alpha.assistant.cloud

import org.json.JSONObject

internal enum class Capability { CHAT, SEARCH, VISION, IMAGES, CLOUD_VOICE }

internal data class SpeechModel(val wire: String, val title: String, val voice: String)

/** The file transcription request of voice input (spec 12A.2). [model] is null when the endpoint takes no `model` part. */
internal data class Transcription(val path: String, val model: String?)

/** Pinned data for one provider. Models and reasoning rules are listed in spec 7.4. */
internal data class ProviderProfile(
    val baseUrl: String,
    val chatPath: String,
    val strictChatPath: String,
    val capabilities: Set<Capability>,
    val chatModels: List<String>,
    val speechModels: List<SpeechModel>,
    // Null: the phone recognizes the speech (spec 12A.4).
    val transcription: Transcription?,
    val maxOutputTokens: Int,
    val reasoningOn: JSONObject,
    val reasoningOff: JSONObject,
    val keyPage: String,
)

private fun effort(level: String) = JSONObject().put("reasoning", JSONObject().put("effort", level))

private fun thinking(type: String) = JSONObject().put("thinking", JSONObject().put("type", type))

private fun outputEffort(level: String) = JSONObject().put("output_config", JSONObject().put("effort", level))

/** The pinned GPT-Live model and voice (spec 5.3.5). */
internal const val OPENAI_LIVE_MODEL = "gpt-live-1"
internal const val OPENAI_LIVE_VOICE = "marin"

internal val OpenAiProfile = ProviderProfile(
    baseUrl = "https://api.openai.com",
    chatPath = "/v1/responses",
    strictChatPath = "/v1/responses",
    capabilities = Capability.entries.toSet() - Capability.CLOUD_VOICE,
    chatModels = listOf("gpt-6.1-terra", "gpt-6.1-sol"),
    speechModels = emptyList(),
    transcription = Transcription("/v1/audio/transcriptions", "gpt-transcribe"),
    maxOutputTokens = 128000,
    reasoningOn = effort("medium"),
    reasoningOff = effort("low"),
    keyPage = "https://platform.openai.com/api-keys",
)

internal val AnthropicProfile = ProviderProfile(
    baseUrl = "https://api.anthropic.com",
    chatPath = "/v1/messages",
    strictChatPath = "/v1/messages",
    capabilities = setOf(Capability.CHAT, Capability.SEARCH, Capability.VISION),
    chatModels = listOf("claude-sonnet-5-5", "claude-opus-5-5"),
    speechModels = emptyList(),
    transcription = null,
    maxOutputTokens = 128000,
    reasoningOn = outputEffort("high"),
    reasoningOff = outputEffort("low"),
    keyPage = "https://platform.claude.com/settings/keys",
)

internal val OpenRouterProfile = ProviderProfile(
    baseUrl = "https://openrouter.ai",
    chatPath = "/api/v1/chat/completions",
    strictChatPath = "/api/v1/chat/completions",
    capabilities = Capability.entries.toSet(),
    chatModels = listOf("openai/gpt-6.1-terra"),
    speechModels = listOf(SpeechModel("elevenlabs/eleven-v4-turbo", "Eleven v4 Turbo", "george")),
    transcription = Transcription("/api/v1/audio/transcriptions", "openai/whisper-large-v3"),
    maxOutputTokens = 128000,
    reasoningOn = effort("medium"),
    reasoningOff = effort("low"),
    keyPage = "https://openrouter.ai/settings/keys",
)

/** The OpenRouter `provider` object of every request: providers that support every parameter and keep no data. A new object each call. */
internal fun openRouterProviderPreferences() = JSONObject().put("require_parameters", true).put("data_collection", "deny")

internal val DeepSeekProfile = ProviderProfile(
    baseUrl = "https://api.deepseek.com",
    chatPath = "/chat/completions",
    strictChatPath = "/beta/chat/completions",
    // User decision 2026-11-17: DeepSeek has no cloud voice (spec 12.7).
    capabilities = Capability.entries.toSet() - Capability.IMAGES - Capability.CLOUD_VOICE,
    chatModels = listOf("deepseek-flash"),
    speechModels = emptyList(),
    transcription = Transcription("/v1/tools/asr", null),
    maxOutputTokens = 393216,
    // Today's DeepSeek request: thinking on runs at effort low.
    reasoningOn = thinking("enabled").put("reasoning_effort", "low"),
    reasoningOff = thinking("disabled"),
    keyPage = "https://platform.deepseek.com/api_keys",
)
