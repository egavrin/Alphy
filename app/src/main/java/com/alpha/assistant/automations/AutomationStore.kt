package com.alpha.assistant.automations

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/** Separate local storage never opens conversations or credentials. */
class AutomationStore(context: Context, name: String = "alpha-automations.db") : SQLiteOpenHelper(context,name,null,2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rules(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, approved INTEGER NOT NULL, enabled INTEGER NOT NULL, definition TEXT NOT NULL, next_at INTEGER, attention TEXT NOT NULL)")
        db.execSQL("CREATE TABLE runs(id TEXT PRIMARY KEY, rule_id TEXT NOT NULL, revision INTEGER NOT NULL, token TEXT NOT NULL, time INTEGER NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, details TEXT NOT NULL, UNIQUE(rule_id,token))")
        db.execSQL("CREATE TABLE effects(run_id TEXT NOT NULL, action_id TEXT NOT NULL, result TEXT NOT NULL, PRIMARY KEY(run_id,action_id))")
        db.execSQL("CREATE TABLE trigger_state(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Неизвестная схема автоматизаций. Данные сохранены.") }
    fun rules(): List<AutomationRule> = readableDatabase.rawQuery("SELECT * FROM rules ORDER BY rowid DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(AutomationRule(c.getString(0),c.getInt(1),c.getInt(2),c.getInt(3) == 1,AutomationDefinition.parse(JSONObject(c.getString(4))),if(c.isNull(5)) null else c.getLong(5),c.getString(6))) }
    }
    fun rule(id: String) = rules().find { it.id == id }
    fun save(id: String?, input: JSONObject, expectedRevision: Int? = null): AutomationRule {
        val definition = AutomationDefinition.parse(input)
        val key = id ?: UUID.randomUUID().toString()
        transaction {
            val old = id?.let { checkNotNull(rule(it)) }
            check(old == null || old.revision == expectedRevision) { "Сценарий изменился. Открой текущую версию." }
            check(old != null || rules().size < 50) { "Можно сохранить не более 50 сценариев" }
            writableDatabase.insertWithOnConflict("rules",null,ContentValues().apply {
                put("id",key); put("revision",(old?.revision ?: 0)+1); put("approved",0); put("enabled",0)
                put("definition",definition.json); putNull("next_at"); put("attention","Проверь и одобри эту версию сценария.")
            },SQLiteDatabase.CONFLICT_REPLACE)
        }
        return checkNotNull(rule(key))
    }
    fun active(id: String, revision: Int) = rule(id)?.let { it.enabled && it.revision == revision && it.approvedRevision == revision && it.attention.isEmpty() } == true
    fun approve(id: String, revision: Int) {
        check(writableDatabase.update("rules",ContentValues().apply { put("approved",revision); put("enabled",1); put("attention","") },"id=? AND revision=?",arrayOf(id,revision.toString())) == 1) { "Сценарий изменился" }
    }
    fun pause(id: String) { writableDatabase.update("rules",ContentValues().apply { put("enabled",0); putNull("next_at") },"id=?",arrayOf(id)) }
    fun attention(id: String, revision: Int, message: String) {
        writableDatabase.update("rules",ContentValues().apply { put("enabled",0); put("attention",message); putNull("next_at") },"id=? AND revision=?",arrayOf(id,revision.toString()))
    }
    fun next(id: String, revision: Int, at: Long?) { writableDatabase.update("rules",ContentValues().apply { if(at == null) putNull("next_at") else put("next_at",at) },"id=? AND revision=?",arrayOf(id,revision.toString())) }
    fun history(id: String): List<AutomationRun> = readableDatabase.rawQuery("SELECT * FROM runs WHERE rule_id=? ORDER BY time DESC,rowid DESC",arrayOf(id)).use { c ->
        buildList { while(c.moveToNext()) add(AutomationRun(c.getString(0),c.getString(1),c.getInt(2),c.getString(3),c.getLong(4),c.getString(5),c.getString(6),c.getString(7))) }
    }
    fun occurrence(id: String, token: String): AutomationRun? = readableDatabase.rawQuery("SELECT * FROM runs WHERE rule_id=? AND token=?",arrayOf(id,token)).use { c ->
        if(c.moveToFirst()) AutomationRun(c.getString(0),c.getString(1),c.getInt(2),c.getString(3),c.getLong(4),c.getString(5),c.getString(6),c.getString(7)) else null
    }
    /** The claim survives process death. Existing claims never start another execution. */
    fun begin(rule: AutomationRule, event: AutomationEvent, now: Long, dry: Boolean): AutomationRun? {
        var result: AutomationRun? = null
        transaction {
            if(!dry && !active(rule.id,rule.revision)) return@transaction
            if(occurrence(rule.id,event.token) != null) return@transaction
            val run = AutomationRun(UUID.randomUUID().toString(),rule.id,rule.revision,event.token,now,"unknownOutcome","Запуск начат. После прерывания автоматического повтора нет.","[]")
            writableDatabase.insertOrThrow("runs",null,ContentValues().apply {
                put("id",run.id); put("rule_id",rule.id); put("revision",rule.revision); put("token",event.token); put("time",now)
                put("status",run.status); put("message",run.message); put("details",run.details)
            }); result = run
        }
        return result
    }
    fun finish(run: AutomationRun, status: String, message: String, details: String = "[]"): AutomationRun {
        writableDatabase.update("runs",ContentValues().apply { put("status",status); put("message",message); put("details",details) },"id=?",arrayOf(run.id))
        return run.copy(status=status,message=message,details=details)
    }
    fun claimUserRun(run: AutomationRun): Boolean {
        var claimed=false
        transaction {
            if(!active(run.ruleId,run.revision)) return@transaction
            claimed=writableDatabase.update("runs",ContentValues().apply {
                put("status","unknownOutcome"); put("message","Запуск интерфейса начат. Проверь результат перед новым действием.")
            },"id=? AND status='awaitingUser'",arrayOf(run.id)) == 1
        }
        return claimed
    }
    fun effect(runId: String, actionId: String, result: String) {
        writableDatabase.insertWithOnConflict("effects",null,ContentValues().apply { put("run_id",runId); put("action_id",actionId); put("result",result) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun state(key: String): String? = readableDatabase.rawQuery("SELECT value FROM trigger_state WHERE key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else null }
    fun setState(key: String, value: String) { writableDatabase.insertWithOnConflict("trigger_state",null,ContentValues().apply { put("key",key); put("value",value) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun transition(key: String, value: String): Boolean {
        var changed = false
        transaction { if(state(key) != value) { setState(key,value); changed = true } }
        return changed
    }
    fun prune(now: Long = System.currentTimeMillis()) = transaction {
        writableDatabase.delete("effects","run_id IN (SELECT id FROM runs WHERE time<?)",arrayOf((now-30L*86400000).toString()))
        writableDatabase.delete("runs","time<?",arrayOf((now-30L*86400000).toString()))
    }
    fun transaction(block: () -> Unit) {
        writableDatabase.beginTransaction()
        try { block(); writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
    }
}
