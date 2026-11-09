package com.alpha.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.alpha.assistant.chat.RefinementChoice
import org.json.JSONArray
import org.json.JSONObject

data class Attachment(val uri: String, val name: String, val mime: String, val contextText: String? = null,
    val automaticScreen: Boolean = false)
data class Draft(val text: String = "", val attachments: List<Attachment> = emptyList(), val id: String = java.util.UUID.randomUUID().toString())
data class Source(val title: String, val url: String, val summary: String = "", val siteName: String? = null, val siteIcon: String? = null,
    val publishedAt: String? = null)

/** The host assigns provenance independently of message text. */
enum class MessageProvenance { CLEAN, MEMORY_DERIVED, UNKNOWN }

data class Message(val id: String, val role: String, val text: String, val createdAt: Long, val status: String,
    val attachments: List<Attachment> = emptyList(), val replyTo: String? = null, val search: Boolean = false,
    val searchStatus: String = "", val sources: List<Source> = emptyList(), val error: String = "", val errorCode: String = "",
    val toolId: String? = null, val toolRevision: Int? = null, val toolOperationId: String? = null,
    val queueOrder: Long? = null, val hostTargetId: String? = null, val requiresRefinement: Boolean = false,
    val refinementChoices: List<RefinementChoice> = emptyList(),
    val provenance: MessageProvenance = MessageProvenance.UNKNOWN,
    val automationResult: Boolean = false,
    val screenContext: ScreenContext? = null)
data class Conversation(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val draft: Draft = Draft(), val messages: List<Message> = emptyList(),
    val refinementTargetId: String? = null, val refinementDraft: Draft? = null) {
    val cardIds: List<String> get() = messages.mapNotNull { it.toolId }.distinct()
    val composerDraft: Draft get() = refinementDraft ?: draft
}
data class ShellSnapshot(val conversations: List<Conversation> = emptyList(), val selectedId: String? = null, val newDraft: Draft = Draft(), val userName: String = "") {
    val selected: Conversation? get() = conversations.find { it.id == selectedId }
    val draft: Draft get() = selected?.composerDraft ?: newDraft
}

/** The chat body and background results have independent writers. */
class ShellStore(context: Context, name: String = "alpha.db") : SQLiteOpenHelper(context, name, null, 2) {
    companion object {
        internal const val AUTOMATION_RESULT_CHANGED = "com.alpha.assistant.AUTOMATION_RESULT_CHANGED"
        internal val deliveryLock = Any()
    }

    private fun createAutomationMessages(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE automation_messages(id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, delivered_at INTEGER NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE INDEX automation_messages_conversation ON automation_messages(conversation_id, delivered_at)")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, updated_at INTEGER NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createAutomationMessages(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "A migration is required for Alphy database version $newVersion" }
        createAutomationMessages(db)
    }

    internal fun hasConversation(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    internal fun automationMessages(): Map<String, List<Message>> = automationMessages(null)

    private fun automationMessages(conversationId: String?): Map<String, List<Message>> {
        val selection = if (conversationId == null) "" else " WHERE conversation_id=?"
        return readableDatabase.rawQuery(
            "SELECT id, conversation_id, delivered_at, body FROM automation_messages$selection ORDER BY delivered_at,id",
            conversationId?.let { arrayOf(it) },
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val result = JSONObject(cursor.getString(3))
                    add(cursor.getString(1) to Message(cursor.getString(0), "assistant", result.getString("text"),
                        cursor.getLong(2), "complete", sources = decodeSources(result.getJSONArray("sources")), automationResult = true))
                }
            }.groupBy({ it.first }, { it.second })
        }
    }

    internal fun hasAutomationMessage(messageId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM automation_messages WHERE id=?", arrayOf(messageId)).use { it.moveToFirst() }

    internal fun appendAutomationMessage(conversationId: String, messageId: String, now: Long, body: JSONObject): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            if (!hasConversation(conversationId)) false
            else {
                db.insertWithOnConflict("automation_messages", null, ContentValues().apply {
                    put("id", messageId); put("conversation_id", conversationId); put("delivered_at", now); put("body", body.toString())
                }, SQLiteDatabase.CONFLICT_IGNORE)
                val exists = db.rawQuery("SELECT 1 FROM automation_messages WHERE id=? AND conversation_id=?",
                    arrayOf(messageId, conversationId)).use { it.moveToFirst() }
                check(exists) { "Automation message write failed" }
                db.setTransactionSuccessful()
                true
            }
        } finally { db.endTransaction() }
    }
    internal fun searchConversations(id: String? = null): List<Conversation> {
        val results = automationMessages(id)
        return readableDatabase.query("conversations", arrayOf("body"), id?.let { "id=?" },
            id?.let { arrayOf(it) }, null, null, "updated_at DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val conversation = decodeConversation(JSONObject(cursor.getString(0)))
                    add(conversation.withAutomationMessages(results[conversation.id].orEmpty()))
                }
            }
        }
    }

    fun load(): ShellSnapshot {
        val settings = mutableMapOf<String, String>()
        readableDatabase.query("settings", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) settings[c.getString(0)] = c.getString(1)
        }
        val conversations = mutableListOf<Conversation>()
        val draftIdentities = mutableListOf<Pair<String, String>>()
        readableDatabase.query("conversations", arrayOf("body"), null, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) {
                val body = JSONObject(c.getString(0))
                val needsIdentity = body.getJSONObject("draft").optString("id").isBlank()
                val conversation = decodeConversation(body)
                conversations += conversation
                if (needsIdentity) draftIdentities += conversation.id to body.toString()
            }
        }
        // Persist identities before exposing drafts, including existing drafts with no identity.
        draftIdentities.forEach { (id, body) ->
            check(writableDatabase.update("conversations", ContentValues().apply { put("body", body) }, "id=?", arrayOf(id)) == 1)
        }
        val newDraftJson = settings["newDraft"]?.let(::JSONObject)
        val needsIdentity = newDraftJson?.optString("id").isNullOrBlank()
        val newDraft = newDraftJson?.let(::decodeDraft) ?: Draft()
        if (needsIdentity) {
            if (newDraftJson == null) saveNewDraft(newDraft) else saveSetting("newDraft", newDraftJson.toString())
        }
        val results = automationMessages()
        val withResults = conversations.map { it.withAutomationMessages(results[it.id].orEmpty()) }.sortedByDescending { it.updatedAt }
        return ShellSnapshot(conversations = withResults, newDraft = newDraft, userName = settings["userName"] ?: "")
    }
    fun saveConversation(conversation: Conversation) {
        writableDatabase.insertWithOnConflict("conversations", null, ContentValues().apply {
            put("id", conversation.id); put("updated_at", conversation.updatedAt); put("body", encodeConversation(conversation).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Conversation write failed" } }
    }
    internal fun updateConversation(conversation: Conversation) {
        writableDatabase.update("conversations", ContentValues().apply {
            put("updated_at", conversation.updatedAt); put("body", encodeConversation(conversation).toString())
        }, "id=?", arrayOf(conversation.id))
    }
    fun saveNewDraft(draft: Draft) = saveSetting("newDraft", encodeDraft(draft).toString())
    fun saveSetting(name: String, value: String) {
        writableDatabase.insertWithOnConflict("settings", null, ContentValues().apply { put("name", name); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
            .also { check(it != -1L) { "Setting write failed" } }
    }
    internal fun hasSetting(name: String): Boolean =
        readableDatabase.query("settings",arrayOf("name"),"name=?",arrayOf(name),null,null,null).use { it.moveToFirst() }

    internal fun completeToolCardUpgrade(marker: String, chats: List<Conversation>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!hasSetting(marker)) {
                chats.forEach(::updateConversation)
                saveSetting(marker,"1")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    fun createFromNewDraft(conversation: Conversation, nextDraft: Draft = Draft()) {
        writableDatabase.beginTransaction()
        try {
            saveConversation(conversation)
            saveNewDraft(nextDraft)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun renameConversation(id: String, title: String) {
        require(title.isNotBlank()) { "Conversation title cannot be blank" }
        writableDatabase.beginTransaction()
        try {
            val conversation = readableDatabase.query("conversations", arrayOf("body"), "id=?", arrayOf(id), null, null, null).use { c ->
                if (c.moveToFirst()) decodeConversation(JSONObject(c.getString(0))) else null
            }
            if (conversation != null) saveConversation(conversation.copy(title = title.trim()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun deleteConversation(id: String) = synchronized(deliveryLock) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("automation_messages", "conversation_id=?", arrayOf(id))
            writableDatabase.delete("conversations", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    internal fun retryHistoryClear(keep: Set<String>) {
        val remove = readableDatabase.rawQuery("SELECT id FROM conversations", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) cursor.getString(0).let { if (it !in keep) add(it) }
            }
        }
        remove.forEach(::deleteConversation)
    }
    fun clearHistory() = synchronized(deliveryLock) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("automation_messages", null, null)
            writableDatabase.delete("conversations", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    private fun encodeAttachments(items: List<Attachment>) = JSONArray().apply {
        items.forEach {
            put(JSONObject().put("uri", it.uri).put("name", it.name).put("mime", it.mime)
                .put("contextText", it.contextText ?: JSONObject.NULL).apply {
                    if (it.automaticScreen) put("automaticScreen", true)
                })
        }
    }

    private fun decodeAttachments(array: JSONArray) = List(array.length()) { index ->
        array.getJSONObject(index).let {
            Attachment(it.getString("uri"), it.getString("name"), it.getString("mime"),
                it.opt("contextText") as? String, it.optBoolean("automaticScreen"))
        }
    }
    private fun encodeDraft(draft: Draft) = JSONObject().put("id", draft.id).put("text", draft.text).put("attachments", encodeAttachments(draft.attachments))
    private fun decodeDraft(json: JSONObject): Draft {
        if (json.optString("id").isBlank()) json.put("id", java.util.UUID.randomUUID().toString())
        return Draft(json.getString("text"), decodeAttachments(json.getJSONArray("attachments")), json.getString("id"))
    }
    private fun encodeConversation(c: Conversation) = JSONObject().put("id", c.id).put("title", c.title).put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)
        .put("draft", encodeDraft(c.draft)).put("refinementTargetId",c.refinementTargetId)
        .put("refinementDraft",c.refinementDraft?.let(::encodeDraft)).put("messages", JSONArray().apply { c.messages.filterNot { it.automationResult }.forEach { m -> put(JSONObject().put("id", m.id).put("role", m.role).put("text", m.text).put("createdAt", m.createdAt).put("status", m.status).put("attachments", encodeAttachments(m.attachments)).put("replyTo", m.replyTo).put("search", m.search)
            .put("provenance", m.provenance.name).put("screenContext", m.screenContext?.json())
            .put("toolId",m.toolId).put("toolRevision",m.toolRevision).put("toolOperationId",m.toolOperationId)
            .put("queueOrder",m.queueOrder).put("hostTargetId",m.hostTargetId).put("requiresRefinement",m.requiresRefinement)
            .put("refinementChoices",JSONArray().apply { m.refinementChoices.forEach { choice ->
                put(JSONObject().put("toolId",choice.toolId).put("title",choice.title))
            } }).put("searchStatus", m.searchStatus).put("error", m.error).put("errorCode", m.errorCode).put("sources", encodeSources(m.sources))) } })
    private fun decodeConversation(j: JSONObject) = Conversation(j.getString("id"), j.getString("title"), j.getLong("createdAt"), j.getLong("updatedAt"), decodeDraft(j.getJSONObject("draft")),
        j.getJSONArray("messages").let { a -> List(a.length()) { i -> a.getJSONObject(i).let { Message(it.getString("id"), it.getString("role"), it.getString("text"), it.getLong("createdAt"), it.getString("status"), decodeAttachments(it.getJSONArray("attachments")),
            it.optString("replyTo").takeIf { value -> value.isNotEmpty() && value != "null" }, it.optBoolean("search"), it.optString("searchStatus"),
            it.optJSONArray("sources")?.let(::decodeSources).orEmpty(), it.optString("error"), it.optString("errorCode"), it.optString("toolId").takeIf { value -> value.isNotEmpty() && value != "null" },
            if(it.isNull("toolRevision")) null else it.getInt("toolRevision"), it.opt("toolOperationId") as? String,
            if(it.isNull("queueOrder")) null else it.getLong("queueOrder"), it.opt("hostTargetId") as? String, it.optBoolean("requiresRefinement"),
            it.optJSONArray("refinementChoices")?.let { choices ->
                List(choices.length()) { index -> choices.getJSONObject(index).let { choice ->
                    RefinementChoice(choice.getString("toolId"),choice.getString("title"))
                } }
            }.orEmpty(),
            provenance = (it.opt("provenance") as? String)?.let { name ->
                MessageProvenance.entries.firstOrNull { value -> value.name == name }
            } ?: MessageProvenance.UNKNOWN,
            screenContext = it.optJSONObject("screenContext")?.let(ScreenContext::fromJson)) } } }, j.opt("refinementTargetId") as? String, j.optJSONObject("refinementDraft")?.let(::decodeDraft))
}

internal fun Conversation.withAutomationMessages(messages: List<Message>): Conversation {
    if (messages.isEmpty()) return this
    return copy(
        messages = (this.messages + messages).distinctBy { it.id }.sortedBy { it.createdAt },
        updatedAt = maxOf(updatedAt, messages.last().createdAt),
    )
}

internal fun encodeSources(sources: List<Source>) = JSONArray().apply {
    sources.forEach { source ->
        put(JSONObject().put("title", source.title).put("url", source.url).put("summary", source.summary)
            .put("siteName", source.siteName).put("siteIcon", source.siteIcon).put("publishedAt", source.publishedAt))
    }
}

private fun decodeSources(sources: JSONArray): List<Source> = List(sources.length()) { index ->
    val source = sources.getJSONObject(index)
    Source(source.getString("title"), source.getString("url"), source.optString("summary"),
        source.opt("siteName") as? String, source.opt("siteIcon") as? String, source.opt("publishedAt") as? String)
}
