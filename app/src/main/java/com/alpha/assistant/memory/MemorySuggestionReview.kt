package com.alpha.assistant.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class MemorySuggestionStatus { PENDING, SAVING, FAILED, ACCEPTED, DECLINED }

/**
 * Retain one review and acceptance ID for each offer. Only the native accept action calls [saveAccepted].
 * The host must save atomically and use the ID to prevent duplicate writes across retries or replay.
 * A repeated ID with the same text must succeed without writing again. Reject a conflicting text.
 */
internal class MemorySuggestionReview(
    private val acceptanceId: String,
    val text: String,
    private val scope: CoroutineScope,
    private val saveAccepted: (acceptanceId: String, text: String) -> Unit,
) {
    init {
        require(acceptanceId.isNotBlank()) { "The memory suggestion requires an acceptance ID." }
        require(text.isNotBlank()) { "The memory suggestion requires text." }
    }

    private val mutableStatus = MutableStateFlow(MemorySuggestionStatus.PENDING)
    val status = mutableStatus.asStateFlow()

    fun accept() {
        if (!transitionFromReview(MemorySuggestionStatus.SAVING)) return
        scope.launch(Dispatchers.IO) {
            try {
                saveAccepted(acceptanceId, text)
                mutableStatus.value = MemorySuggestionStatus.ACCEPTED
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The completion handler reports the failure without storage details.
            }
        }.invokeOnCompletion {
            mutableStatus.compareAndSet(MemorySuggestionStatus.SAVING, MemorySuggestionStatus.FAILED)
        }
    }

    fun decline() {
        transitionFromReview(MemorySuggestionStatus.DECLINED)
    }

    private fun transitionFromReview(next: MemorySuggestionStatus): Boolean {
        val current = mutableStatus.value
        return (current == MemorySuggestionStatus.PENDING || current == MemorySuggestionStatus.FAILED) &&
            mutableStatus.compareAndSet(current, next)
    }
}
