package com.alpha.assistant.cloud

/**
 * Maps a failed HTTP status to a [CloudFailure] that names the provider (spec 13.1, 13.2).
 * [body] is the error body, at most 16 KiB, or null. It is read for the quota cases only and never logged or shown.
 * With [search], the message starts with «Поиск, <provider>: ».
 */
internal fun providerFailure(provider: AiProvider, status: Int, body: String?, search: Boolean): CloudFailure {
    val prefix = failurePrefix(provider, search)
    // OpenAI: 429 with insufficient_quota. Anthropic: 400 with a low credit balance message.
    val lowCredit = status == 400 && provider == AiProvider.ANTHROPIC &&
        body?.contains("credit balance is too low", ignoreCase = true) == true
    val noQuota = status == 429 && body?.contains("insufficient_quota") == true
    return when {
        // OpenRouter documents 403 as "input flagged by moderation", not a key problem.
        status == 403 && provider == AiProvider.OPENROUTER -> refusalFailure(provider, search)
        // OpenAI documents error.code moderation_blocked for a request that its image safety check refuses.
        status == 400 && provider == AiProvider.OPENAI && body?.contains("\"moderation_blocked\"") == true -> refusalFailure(provider, search)
        status == 401 || status == 403 -> CloudFailure("auth", prefix + "ключ отклонён. Замените API-ключ в настройках.")
        status == 402 || noQuota || lowCredit -> CloudFailure("quota", prefix + "недостаточно средств. Проверьте баланс аккаунта.")
        status == 429 -> CloudFailure("rate_limit", prefix + "лимит запросов. Повторите позже.")
        status in 500..599 -> CloudFailure("unavailable", prefix + "временно недоступен. Повторите позже.")
        else -> CloudFailure("http_$status", prefix + "отклонил запрос (HTTP $status). Проверьте запрос и повторите.")
    }
}

/** The failure of a refused reply: a content filter or a refusal part. With [search], the message starts with «Поиск, <provider>: ». */
internal fun refusalFailure(provider: AiProvider, search: Boolean = false) =
    CloudFailure("refusal", failurePrefix(provider, search) + "отказ отвечать на этот запрос. Измените запрос.")

private fun failurePrefix(provider: AiProvider, search: Boolean) =
    (if (search) "Поиск, ${provider.displayName}" else provider.displayName) + ": "
