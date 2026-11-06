package com.alpha.assistant.chat

data class RefinementChoice(val toolId: String, val title: String)

internal const val MAX_QUEUED_CHAT_REQUESTS = 32
internal const val MAX_CHAT_INPUT = 16000

internal fun refinementTarget(choices: List<RefinementChoice>, text: String): String? =
    choices.singleOrNull()?.toolId ?: choices.filter { choice ->
        val title = Regex.escape(choice.title)
        Regex("(?<![\\p{L}\\p{N}])$title(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }.singleOrNull()?.toolId
