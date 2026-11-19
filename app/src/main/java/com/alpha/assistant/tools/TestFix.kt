package com.alpha.assistant.tools

internal class TestFixNotSaved(message: String) : IllegalStateException(message)

internal enum class TestFixStatus { PASSED, UNVERIFIED, UNREPAIRED, VERIFIED }

/**
 * The outcome of a chat test-and-fix request. [candidate] is the verified repair that the host can save.
 * [checkedByRun] is false for a fix by lines: only the compile and the host checks ran, and no run reproduced the report.
 */
internal data class TestFixResult(
    val status: TestFixStatus,
    val detail: String,
    val candidate: ToolPreview? = null,
    val checkedByRun: Boolean = true,
)
