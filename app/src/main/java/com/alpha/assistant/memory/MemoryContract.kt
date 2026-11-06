package com.alpha.assistant.memory

enum class MemorySource {
    EXPLICIT_SAVE_COMMAND,
    ACCEPTED_SUGGESTION,
}

data class MemoryFact(
    val id: String,
    val text: String,
    val source: MemorySource,
    val revision: Long,
)

enum class MemoryPurpose {
    CHAT_DECISION,
    CHAT_ANSWER,
    AGENT_PLAN,
    MINI_APP_GENERATION,
    LESSON,
    AUTOMATION,
}

enum class MemoryRejection {
    FACT_NOT_FOUND,
    STALE_REVISION,
}

class MemoryStoreException(val reason: MemoryRejection) : IllegalStateException(reason.name)

internal class MemorySuggestionConflictException :
    IllegalStateException("This memory suggestion was accepted with different text.")
