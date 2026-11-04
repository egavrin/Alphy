package com.alpha.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class Attachment(val uri: String, val name: String, val mime: String, val contextText: String? = null)
data class Draft(val text: String = "", val attachments: List<Attachment> = emptyList(), val id: String = java.util.UUID.randomUUID().toString())
data class Source(val title: String, val url: String, val summary: String = "")
data class Message(val id: String, val role: String, val text: String, val createdAt: Long, val status: String,
    val attachments: List<Attachment> = emptyList(), val replyTo: String? = null, val search: Boolean = false,
    val searchStatus: String = "", val sources: List<Source> = emptyList(), val error: String = "", val errorCode: String = "",
    val toolId: String? = null, val toolRevision: Int? = null, val toolOperationId: String? = null)
data class Conversation(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val draft: Draft = Draft(), val messages: List<Message> = emptyList())
data class ShellSnapshot(val conversations: List<Conversation> = emptyList(), val selectedId: String? = null, val newDraft: Draft = Draft()) {
    val selected: Conversation? get() = conversations.find { it.id == selectedId }
    val draft: Draft get() = selected?.draft ?: newDraft
}

/** Version 1 stores each conversation atomically. Credentials never enter this database. */
class ShellStore(context: Context, name: String = "alpha.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, updated_at INTEGER NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("A migration is required for Alphy database version $newVersion")
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
        return ShellSnapshot(conversations, settings["selected"]?.takeIf { id -> conversations.any { it.id == id } }, newDraft)
    }
    fun saveConversation(conversation: Conversation) {
        writableDatabase.insertWithOnConflict("conversations", null, ContentValues().apply {
            put("id", conversation.id); put("updated_at", conversation.updatedAt); put("body", encodeConversation(conversation).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Conversation write failed" } }
    }
    fun saveNewDraft(draft: Draft) = saveSetting("newDraft", encodeDraft(draft).toString())
    fun saveSetting(name: String, value: String) {
        writableDatabase.insertWithOnConflict("settings", null, ContentValues().apply { put("name", name); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
            .also { check(it != -1L) { "Setting write failed" } }
    }
    fun createFromNewDraft(conversation: Conversation, nextDraft: Draft = Draft()) {
        writableDatabase.beginTransaction()
        try {
            saveConversation(conversation)
            saveNewDraft(nextDraft)
            saveSetting("selected", conversation.id)
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
    fun deleteConversation(id: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("conversations", "id=?", arrayOf(id))
            writableDatabase.delete("settings", "name=? AND value=?", arrayOf("selected", id))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun clearHistory() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("conversations", null, null)
            writableDatabase.delete("settings", "name=?", arrayOf("selected"))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    private fun encodeAttachments(items: List<Attachment>) = JSONArray().apply { items.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name).put("mime", it.mime).put("contextText",it.contextText ?: JSONObject.NULL)) } }
    private fun decodeAttachments(array: JSONArray) = List(array.length()) { i -> array.getJSONObject(i).let { Attachment(it.getString("uri"), it.getString("name"), it.getString("mime"),if(it.isNull("contextText")) null else it.getString("contextText")) } }
    private fun encodeDraft(draft: Draft) = JSONObject().put("id", draft.id).put("text", draft.text).put("attachments", encodeAttachments(draft.attachments))
    private fun decodeDraft(json: JSONObject): Draft {
        if (json.optString("id").isBlank()) json.put("id", java.util.UUID.randomUUID().toString())
        return Draft(json.getString("text"), decodeAttachments(json.getJSONArray("attachments")), json.getString("id"))
    }
    private fun encodeConversation(c: Conversation) = JSONObject().put("id", c.id).put("title", c.title).put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)
        .put("draft", encodeDraft(c.draft)).put("messages", JSONArray().apply { c.messages.forEach { m -> put(JSONObject().put("id", m.id).put("role", m.role).put("text", m.text).put("createdAt", m.createdAt).put("status", m.status).put("attachments", encodeAttachments(m.attachments)).put("replyTo", m.replyTo).put("search", m.search)
            .put("toolId",m.toolId).put("toolRevision",m.toolRevision).put("toolOperationId",m.toolOperationId).put("searchStatus", m.searchStatus).put("error", m.error).put("errorCode", m.errorCode).put("sources", JSONArray().apply { m.sources.forEach { put(JSONObject().put("title", it.title).put("url", it.url).put("summary", it.summary)) } })) } })
    private fun decodeConversation(j: JSONObject) = Conversation(j.getString("id"), j.getString("title"), j.getLong("createdAt"), j.getLong("updatedAt"), decodeDraft(j.getJSONObject("draft")),
        j.getJSONArray("messages").let { a -> List(a.length()) { i -> a.getJSONObject(i).let { Message(it.getString("id"), it.getString("role"), it.getString("text"), it.getLong("createdAt"), it.getString("status"), decodeAttachments(it.getJSONArray("attachments")),
            it.optString("replyTo").takeIf { value -> value.isNotEmpty() && value != "null" }, it.optBoolean("search"), it.optString("searchStatus"),
            it.optJSONArray("sources")?.let { sources -> List(sources.length()) { index -> sources.getJSONObject(index).let { source -> Source(source.getString("title"), source.getString("url"), source.optString("summary")) } } } ?: emptyList(), it.optString("error"), it.optString("errorCode"), it.optString("toolId").takeIf { value -> value.isNotEmpty() && value != "null" },
            if(it.isNull("toolRevision")) null else it.getInt("toolRevision"), it.optString("toolOperationId").takeIf { value -> value.isNotEmpty() && value != "null" }) } } })
}
