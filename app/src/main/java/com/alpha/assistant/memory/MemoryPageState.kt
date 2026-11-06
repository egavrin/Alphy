package com.alpha.assistant.memory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alpha.assistant.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class MemoryDraft(val original: MemoryFact? = null, val text: String = original?.text.orEmpty()) {
    val hasChanges: Boolean get() = text != original?.text.orEmpty()
}

/** The shell retains this state and owns the store and the main-thread scope. */
internal class MemoryPageState(private val store: MemoryStore, private val scope: CoroutineScope) {
    var facts by mutableStateOf<List<MemoryFact>?>(null)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var draft by mutableStateOf<MemoryDraft?>(null)
        private set
    var discardDraft by mutableStateOf(false)
        private set
    var deleteTarget by mutableStateOf<MemoryFact?>(null)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var saveFailed by mutableStateOf(false)
        private set
    private var editRejection by mutableStateOf<MemoryRejection?>(null)
    private var onPrefillSaved: (() -> Unit)? = null

    private val canOpenEditor: Boolean get() = !busy && draft == null && deleteTarget == null

    val currentRevision: MemoryFact?
        get() = facts?.find { it.id == draft?.original?.id }

    val draftIssue: MemoryRejection?
        get() = if (editRejection == MemoryRejection.STALE_REVISION && facts != null && currentRevision == null) {
            MemoryRejection.FACT_NOT_FOUND
        } else {
            editRejection
        }

    fun refresh() = run { readFacts() }

    fun openEditor(fact: MemoryFact? = null) {
        if (!canOpenEditor) return
        notice = null
        draft = MemoryDraft(fact)
    }

    internal val reviewingChatFact get() = draft != null && onPrefillSaved != null

    /** Open the exact user-authored text for review. Notify the retained host after a successful save. */
    fun tryOpenPrefilledEditor(text: String, onSaved: () -> Unit): Boolean {
        if (!canOpenEditor || text.isBlank()) return false
        notice = null
        onPrefillSaved = onSaved
        draft = MemoryDraft(text = text)
        return true
    }

    fun changeDraft(text: String) {
        if (!busy) draft = draft?.copy(text = text)
    }

    fun cancelEditor() {
        if (busy) return
        if (draft?.hasChanges == true) discardDraft = true else closeEditor()
    }

    fun cancelDiscard() {
        discardDraft = false
    }

    fun closeEditor() {
        draft = null
        discardDraft = false
        editRejection = null
        saveFailed = false
        onPrefillSaved = null
    }

    fun useCurrentRevision() {
        val current = currentRevision ?: return
        if (!busy && draftIssue == MemoryRejection.STALE_REVISION) {
            draft = draft?.copy(original = current)
            editRejection = null
        }
    }

    fun convertDraftToNewFact() {
        if (!busy && draftIssue == MemoryRejection.FACT_NOT_FOUND) {
            draft = draft?.copy(original = null)
            editRejection = null
        }
    }

    fun saveDraft() {
        val editing = draft ?: return
        if (!editing.hasChanges || editing.text.isBlank() || draftIssue != null) return
        run {
            saveFailed = false
            val result = storage {
                val original = editing.original
                if (original == null) store.saveExplicitFact(editing.text)
                else store.edit(original.id, original.revision, editing.text)
            }
            when (val error = result.exceptionOrNull()) {
                null -> {
                    val onSaved = onPrefillSaved
                    closeEditor()
                    onSaved?.invoke()
                }
                is MemoryStoreException -> editRejection = error.reason
                else -> {
                    saveFailed = true
                    return@run
                }
            }
            readFacts()
        }
    }

    fun requestDelete(fact: MemoryFact) {
        if (!busy) {
            notice = null
            deleteTarget = fact
        }
    }

    fun cancelDelete() {
        if (!busy) deleteTarget = null
    }

    fun confirmDelete() {
        val target = deleteTarget ?: return
        run {
            val result = storage { store.delete(target.id, target.revision) }
            deleteTarget = null
            notice = when (val error = result.exceptionOrNull()) {
                null -> null
                is MemoryStoreException -> when (error.reason) {
                    MemoryRejection.FACT_NOT_FOUND -> R.string.memory_missing_delete
                    MemoryRejection.STALE_REVISION -> R.string.memory_stale_delete
                }
                else -> R.string.memory_delete_failed
            }
            readFacts()
        }
    }

    private suspend fun readFacts() {
        val result = storage(store::list)
        loadFailed = result.isFailure
        facts = result.getOrNull()
    }

    private fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } finally {
                busy = false
            }
        }
    }

    private suspend fun <T> storage(action: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(action())
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            Result.failure(error)
        }
    }
}
