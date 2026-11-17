package com.alpha.assistant.cloud

internal enum class AiProvider(val id: String, val displayName: String, val profile: ProviderProfile) {
    OPENAI("openai", "OpenAI", OpenAiProfile),
    ANTHROPIC("anthropic", "Anthropic", AnthropicProfile),
    OPENROUTER("openrouter", "OpenRouter", OpenRouterProfile),
    DEEPSEEK("deepseek", "DeepSeek", DeepSeekProfile),
}

internal data class AiAccount(val provider: AiProvider, val key: String) {
    // A log line or a test failure message must never show the key.
    override fun toString() = "AiAccount(provider=$provider, key=<hidden>)"
}

private val DEEPSEEK_KEY = Regex("^sk-[0-9a-f]{32}$")

/** Guesses the provider from the key prefix. The user can override the guess. Order matters. */
internal fun detectProvider(key: String): AiProvider? {
    val trimmed = key.trim()
    return when {
        trimmed.startsWith("sk-ant-") -> AiProvider.ANTHROPIC
        trimmed.startsWith("sk-or-") -> AiProvider.OPENROUTER
        trimmed.startsWith("sk-proj-") || trimmed.startsWith("sk-svcacct-") -> AiProvider.OPENAI
        DEEPSEEK_KEY.matches(trimmed) -> AiProvider.DEEPSEEK
        trimmed.startsWith("sk-") -> AiProvider.OPENAI
        else -> null
    }
}

/** Checks only the local format. The key check against the provider decides validity. */
internal fun AiProvider.accepts(key: String): Boolean {
    val trimmed = key.trim()
    // A key goes into an HTTP header, which takes printable ASCII only, so spaces and other letters are refused here.
    if (trimmed.isEmpty() || trimmed.any { it !in '!'..'~' }) return false
    // Spec 6.5 refuses an Anthropic admin key whatever provider the user selected.
    return !trimmed.startsWith("sk-ant-admin")
}
