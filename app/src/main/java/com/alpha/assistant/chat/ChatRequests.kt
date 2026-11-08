package com.alpha.assistant.chat

data class RefinementChoice(val toolId: String, val title: String)

internal const val MAX_QUEUED_CHAT_REQUESTS = 32
internal const val MAX_CHAT_INPUT = 16000
