package com.alpha.assistant.tools

import kotlinx.coroutines.CancellationException

data class ToolDraftPreviewIdentity(val draftId: String, val requestId: String, val attempt: Int)

/** A provisional drawing from the initial smoke pair. The draft transaction governs Apply. */
data class ToolDraftPreview(
    val identity: ToolDraftPreviewIdentity,
    val candidateJson: String,
    val stateJson: String,
    val uiJson: String,
    val surfacesJson: String,
)

/** Check the request identity before applying either callback, including after a suspension. */
class ToolDraftPreviewObserver(
    val requestId: String,
    val onPreview: suspend (ToolDraftPreview) -> Unit,
    val onClear: (ToolDraftPreviewIdentity) -> Unit,
) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 128)
    }
}

internal class ToolDraftPreviewPublisher(private val draftId: String, private val observer: ToolDraftPreviewObserver) {
    private var offered: ToolDraftPreviewIdentity? = null

    suspend fun publish(attempt: Int, preview: ToolPreview, surfacesJson: String) {
        val candidate = preview.spec.json
        if (draftId.isBlank() || draftId.length > 128 || candidate.toByteArray(Charsets.UTF_8).size > ToolSpec.MAX_DEFINITION_BYTES) return
        val identity = ToolDraftPreviewIdentity(draftId, observer.requestId, attempt)
        offered = identity
        try {
            observer.onPreview(ToolDraftPreview(identity, candidate, preview.state, preview.ui, surfacesJson))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            clear()
        }
    }

    fun clear() {
        val identity = offered ?: return
        offered = null
        try { observer.onClear(identity) } catch (_: Throwable) {}
    }
}
