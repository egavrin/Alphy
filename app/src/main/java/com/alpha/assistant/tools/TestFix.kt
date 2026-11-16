package com.alpha.assistant.tools

internal class TestFixNotSaved(message: String) : IllegalStateException(message)

internal enum class TestFixStatus { PASSED, UNVERIFIED, UNREPAIRED, VERIFIED }

/** The outcome of a chat test-and-fix request. [candidate] is the verified repair that the host can save. */
internal data class TestFixResult(
    val status: TestFixStatus,
    val detail: String,
    val candidate: ToolPreview? = null,
)
