package com.alpha.assistant.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.alpha.assistant.capabilities.*
import org.json.JSONObject

data class ToolRecord(val id: String, val revision: Int, val stateVersion: Int, val spec: String, val state: String,
    val ui: String, val conversationId: String?, val saved: Boolean)
data class ToolRevision(val revision: Int, val spec: String, val state: String, val ui: String)
data class ToolDraft(val id: String, val toolId: String?, val baseRevision: Int, val baseStateVersion: Int,
    val prompt: String, val candidate: String?, val previewState: String?, val previewUi: String?, val conversationId: String?)

/** Separate storage leaves all conversation and credential data untouched. */
class ToolStore(context: Context, name: String = "alpha-tools.db") : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tools(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, state_version INTEGER NOT NULL, spec TEXT NOT NULL, state TEXT NOT NULL, ui TEXT NOT NULL, conversation_id TEXT, saved INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE revisions(tool_id TEXT NOT NULL, revision INTEGER NOT NULL, spec TEXT NOT NULL, state TEXT NOT NULL, ui TEXT NOT NULL, PRIMARY KEY(tool_id,revision))")
        createCapabilities(db)
        db.execSQL("CREATE TABLE drafts(id TEXT PRIMARY KEY, tool_id TEXT, base_revision INTEGER NOT NULL, base_state_version INTEGER NOT NULL, prompt TEXT NOT NULL, candidate TEXT, preview_state TEXT, preview_ui TEXT, conversation_id TEXT)")
    }
    private fun createCapabilities(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS capability_grants(owner TEXT NOT NULL, capability TEXT NOT NULL, granted INTEGER NOT NULL, PRIMARY KEY(owner,capability))")
        db.execSQL("CREATE TABLE IF NOT EXISTS capability_effects(effect_key TEXT PRIMARY KEY, owner TEXT NOT NULL, revision INTEGER NOT NULL, state_version INTEGER NOT NULL, request TEXT NOT NULL, result TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("База инструментов использует прежнюю схему разработки. Данные сохранены. Автоматический перенос не включён.")
    }
    fun tools(): List<ToolRecord> = readableDatabase.rawQuery("SELECT * FROM tools ORDER BY rowid DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(ToolRecord(c.getString(0), c.getInt(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7) != 0)) }
    }
    fun tool(id: String) = tools().find { it.id == id }
    fun drafts(): List<ToolDraft> = readableDatabase.rawQuery("SELECT * FROM drafts ORDER BY rowid DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(ToolDraft(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8))) }
    }
    fun draft(id: String) = drafts().find { it.id == id }
    fun revisions(id: String): List<ToolRevision> = readableDatabase.rawQuery("SELECT revision,spec,state,ui FROM revisions WHERE tool_id=? ORDER BY revision DESC", arrayOf(id)).use { c ->
        buildList { while(c.moveToNext()) add(ToolRevision(c.getInt(0), c.getString(1), c.getString(2), c.getString(3))) }
    }
    fun saveDraft(d: ToolDraft) { put("drafts", ContentValues().apply {
        put("id", d.id); put("tool_id", d.toolId); put("base_revision", d.baseRevision); put("base_state_version", d.baseStateVersion)
        put("prompt", d.prompt); put("candidate", d.candidate); put("preview_state", d.previewState); put("preview_ui", d.previewUi); put("conversation_id", d.conversationId)
    }) }
    fun deleteDraft(id: String) { writableDatabase.delete("drafts", "id=?", arrayOf(id)) }
    fun saveTool(t: ToolRecord, revision: Boolean) { transaction {
        put("tools", ContentValues().apply {
            put("id", t.id); put("revision", t.revision); put("state_version", t.stateVersion); put("spec", t.spec); put("state", t.state); put("ui", t.ui); put("conversation_id", t.conversationId); put("saved", if(t.saved) 1 else 0)
        })
        if(revision) put("revisions", ContentValues().apply { put("tool_id",t.id); put("revision",t.revision); put("spec",t.spec); put("state",t.state); put("ui",t.ui) })
    } }
    /** SQLite tokens protect writes across foreground and widget engine instances. */
    fun commitTool(t: ToolRecord, old: ToolRecord?, revision: Boolean, draft: ToolDraft? = null, effects: List<CapabilityRequest> = emptyList()) = transaction {
        if (draft != null) check(draft(draft.id) == draft) { "Черновик изменился" }
        val values = ContentValues().apply {
            put("id",t.id); put("revision",t.revision); put("state_version",t.stateVersion)
            put("spec",t.spec); put("state",t.state); put("ui",t.ui)
            put("conversation_id",t.conversationId); put("saved",if(t.saved) 1 else 0)
        }
        if (old == null) check(writableDatabase.insertWithOnConflict("tools",null,values,SQLiteDatabase.CONFLICT_IGNORE) != -1L) { "Инструмент уже существует" }
        else check(writableDatabase.update("tools",values,"id=? AND revision=? AND state_version=? AND saved=?",
            arrayOf(old.id,old.revision.toString(),old.stateVersion.toString(),if(old.saved) "1" else "0")) == 1) { "Инструмент изменился. Открой текущую версию." }
        if (revision) put("revisions",ContentValues().apply {
            put("tool_id",t.id); put("revision",t.revision); put("spec",t.spec); put("state",t.state); put("ui",t.ui)
        })
        effects.forEach { request ->
            val key = "${t.id}:${t.revision}:${t.stateVersion}:${request.id}"
            check(writableDatabase.insertOrThrow("capability_effects",null,ContentValues().apply {
                put("effect_key",key); put("owner",t.id); put("revision",t.revision); put("state_version",t.stateVersion)
                put("request",request.json().toString()); put("created_at",System.currentTimeMillis())
                put("result",CapabilityResult("awaitingUser","Перед выполнением проверь цель и параметры действия.").json().toString())
            }) != -1L)
        }
        if (draft != null) deleteDraft(draft.id)
    }
    fun grant(owner: String, capability: String, enabled: Boolean) {
        require(capability in CapabilityCatalog.ids)
        check(tool(owner) != null) { "Инструмент недоступен" }
        transaction {
            put("capability_grants",ContentValues().apply { put("owner",owner); put("capability",capability); put("granted",if(enabled) 1 else 0) })
            if(!enabled) effects(owner).filter { it.request.capability == capability && it.result.status == "awaitingUser" }.forEach {
                finishEffect(it.key,CapabilityResult("revoked","Доступ этого инструмента к системной функции отозван."))
            }
        }
    }
    fun grantRevoked(owner: String, capability: String): Boolean = readableDatabase.rawQuery(
        "SELECT granted FROM capability_grants WHERE owner=? AND capability=?",arrayOf(owner,capability)).use { it.moveToFirst() && it.getInt(0) == 0 }
    fun granted(owner: String, capability: String): Boolean = readableDatabase.rawQuery(
        "SELECT granted FROM capability_grants WHERE owner=? AND capability=?",arrayOf(owner,capability)).use { it.moveToFirst() && it.getInt(0) == 1 }
    fun effects(owner: String): List<CapabilityEffect> = readableDatabase.rawQuery(
        "SELECT effect_key,owner,revision,state_version,request,result FROM capability_effects WHERE owner=? ORDER BY created_at,rowid",arrayOf(owner)).use { c ->
        buildList { while(c.moveToNext()) add(CapabilityEffect(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),
            CapabilityCatalog.request(JSONObject(c.getString(4))),CapabilityResult.parse(c.getString(5)))) }
    }
    fun effect(key: String): CapabilityEffect? = readableDatabase.rawQuery("SELECT owner FROM capability_effects WHERE effect_key=?",arrayOf(key)).use { c ->
        if(c.moveToFirst()) effects(c.getString(0)).find { it.key == key } else null
    }
    fun finishEffect(key: String, result: CapabilityResult) {
        check(writableDatabase.update("capability_effects",ContentValues().apply { put("result",result.json().toString()) },"effect_key=?",arrayOf(key)) == 1)
    }
    /** Persist unknown outcome before the adapter starts. A crash never authorizes an automatic retry. */
    fun claimEffect(key: String, confirmed: Boolean): CapabilityEffect? {
        var claimed: CapabilityEffect? = null
        transaction {
            val effect = effect(key) ?: error("Системное действие недоступно")
            if(effect.result.status != "awaitingUser" || effect.result.data.optBoolean("launched")) return@transaction
            val tool = tool(effect.owner)
            val result = when {
                tool == null || tool.revision != effect.revision || tool.stateVersion != effect.stateVersion -> CapabilityResult("cancelled","Инструмент изменился. Повтори действие в текущей версии.")
                !confirmed -> CapabilityResult("cancelled","Ты отменил действие.")
                !ToolSpec.parse(tool.spec).capabilities.contains(effect.request.capability) -> CapabilityResult("denied","Инструмент не объявил эту системную функцию.")
                !granted(effect.owner,effect.request.capability) -> if(grantRevoked(effect.owner,effect.request.capability)) CapabilityResult("revoked","Доступ этого инструмента к системной функции отозван.")
                    else CapabilityResult("denied","Перед новым действием выдай инструменту доступ к этой функции.")
                else -> null
            }
            if(result != null) finishEffect(key,result)
            else {
                finishEffect(key,CapabilityResult("unknownOutcome","Выполнение началось. Проверь результат перед повторным действием."))
                claimed = effect
            }
        }
        return claimed
    }
    fun finishUserEffect(key: String, result: CapabilityResult) = transaction {
        val effect = effect(key) ?: return@transaction
        if(effect.result.status != "awaitingUser" || !effect.result.data.optBoolean("launched")) return@transaction
        val current = tool(effect.owner)
        finishEffect(key,when {
            !granted(effect.owner,effect.request.capability) -> CapabilityResult("revoked","Доступ инструмента отозван. Выбранные данные не сохранены.")
            current == null || current.revision != effect.revision || current.stateVersion != effect.stateVersion -> CapabilityResult("cancelled","Инструмент изменился до возврата результата Android. Результат не применён.")
            else -> result
        })
    }
    fun markSaved(id: String) {
        check(writableDatabase.update("tools",ContentValues().apply { put("saved",1) },"id=?",arrayOf(id)) == 1) { "Инструмент недоступен" }
    }
    fun transaction(block: () -> Unit) {
        writableDatabase.beginTransaction()
        try { block(); writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
    }
    private fun put(table: String, values: ContentValues) { check(writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Не удалось сохранить инструмент" } }
}
