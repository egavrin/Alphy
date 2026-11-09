package com.alpha.assistant.ui

import android.content.Context
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.semantic.SemanticIndex
import com.alpha.assistant.semantic.SemanticRecord
import com.alpha.assistant.semantic.semanticRevision
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HistoryResult(
    val conversationId: String,
    val messageId: String? = null,
    val draft: Boolean = false,
    val preview: String? = null,
    val draftId: String? = null,
    val revision: String? = null,
) {
    fun matches(chat: Conversation): Boolean {
        val expected = revision ?: return true
        val text = when {
            draft -> chat.composerDraft.takeIf { it.id == draftId }?.text
            messageId != null -> chat.messages.firstOrNull { it.id == messageId }?.text
            else -> chat.title
        }
        return text != null && semanticRevision(text) == expected
    }
}

internal class HistorySearch(context: Context, private val store: ShellStore) {
    private val index = SemanticIndex.get(context.applicationContext, "history:${store.databaseName}")
    suspend fun update() {
        index.update(::records, ::current)
    }

    suspend fun search(query: String): List<HistoryResult> = withContext(Dispatchers.IO) {
        index.search(query, 0.70f, 50, ::records, ::current).mapNotNull { hit ->
            val key = JSONArray(hit.id)
            val record = resolve(key)?.takeIf { it.revision == hit.revision } ?: return@mapNotNull null
            val excerpt = record.text.substring(hit.offset, hit.end).replace(Regex("\\s+"), " ").trim()
            HistoryResult(key.getString(0), key.getString(2).takeIf { key.getString(1) == "message" },
                draft = key.getString(1) == "draft", preview = excerpt.takeUnless { key.getString(1) == "title" },
                draftId = key.getString(2).takeIf { key.getString(1) == "draft" }, revision = hit.revision)
        }
    }

    private fun records(): List<SemanticRecord> = store.searchConversations().flatMap { chat ->
        buildList {
            add(record(chat.id, "title", "", chat.title))
            val draft = chat.composerDraft
            if (draft.text.isNotBlank()) add(record(chat.id, "draft", draft.id, draft.text))
            for (message in chat.messages) {
                if (message.text.isNotBlank()) add(record(chat.id, "message", message.id, message.text))
            }
        }
    }

    private fun current(id: String, revision: String) = resolve(JSONArray(id))?.revision == revision

    private fun resolve(key: JSONArray): SemanticRecord? {
        val chat = store.searchConversations(key.getString(0)).singleOrNull() ?: return null
        val kind = key.getString(1)
        val id = key.getString(2)
        val text = when (kind) {
            "title" -> chat.title
            "draft" -> chat.composerDraft.takeIf { it.id == id }?.text
            "message" -> chat.messages.firstOrNull { it.id == id }?.text
            else -> null
        } ?: return null
        return record(chat.id, kind, id, text)
    }

    private fun record(chat: String, kind: String, id: String, text: String) =
        SemanticRecord(JSONArray(listOf(chat, kind, id)).toString(), semanticRevision(text), text)
}
