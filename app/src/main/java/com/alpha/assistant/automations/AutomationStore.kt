package com.alpha.assistant.automations

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.alpha.assistant.tools.ToolLibraryStorage
import org.json.JSONObject
import java.util.UUID

/** Separate local storage never opens conversations or credentials. */
class AutomationStore(private val context: Context, name: String = ToolLibraryStorage.current(context).automation) :
    SQLiteOpenHelper(context,name,null,2,ToolLibraryStorage.preserveOnCorruption) {
    override fun getReadableDatabase(): SQLiteDatabase {
        ToolLibraryStorage.requireRelated(context,databaseName,"automations")
        return super.getReadableDatabase()
    }
    override fun getWritableDatabase(): SQLiteDatabase {
        ToolLibraryStorage.requireRelated(context,databaseName,"automations")
        return super.getWritableDatabase()
    }
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
    internal fun approvalToken(id: String): String? = state("approval-archive:$id")

    private fun invalidateApproval(id: String) {
        setState("approval-archive:$id",UUID.randomUUID().toString())
    }

    fun pause(id: String, expectedRevision: Int? = null): Boolean {
        var paused=false
        transaction {
            val where=if(expectedRevision==null) "id=? AND enabled=1" else "id=? AND revision=? AND enabled=1"
            val args=if(expectedRevision==null) arrayOf(id) else arrayOf(id,expectedRevision.toString())
            paused=writableDatabase.update("rules",ContentValues().apply {
                put("enabled",0); putNull("next_at")
            },where,args)==1
            if(paused) invalidateApproval(id)
        }
        return paused
    }

    fun attention(id: String, revision: Int, message: String) = transaction {
        val changed=writableDatabase.update("rules",ContentValues().apply {
            put("enabled",0); put("attention",message); putNull("next_at")
        },"id=? AND revision=?",arrayOf(id,revision.toString()))
        if(changed==1) invalidateApproval(id)
    }

    internal fun resumeApproved(id: String,revision: Int,token: String?): Boolean {
        var resumed=false
        transaction {
            if(approvalToken(id)!=token) return@transaction
            resumed=writableDatabase.update("rules",ContentValues().apply {put("enabled",1)},
                "id=? AND revision=? AND approved=? AND attention=''",arrayOf(id,revision.toString(),revision.toString()))==1
        }
        return resumed
    }

    internal fun stopForDeletion(id: String,revision: Int) = transaction {
        check(rule(id)?.revision==revision) { "Сценарий изменился. Проверь текущую версию перед удалением." }
        attention(id,revision,"Сценарий остановлен перед удалением.")
    }

    internal fun geofenceRegistrations(id: String): List<String> = readableDatabase.rawQuery(
        "SELECT substr(key,5) FROM trigger_state WHERE key GLOB ?",arrayOf("geo:$id:*")
    ).use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.getString(0)) } }

    internal fun removeStoppedRule(id: String,revision: Int) = transaction {
        check(writableDatabase.delete("rules","id=? AND revision=? AND enabled=0",arrayOf(id,revision.toString()))==1) {
            "Сценарий изменился. Проверь текущую версию перед удалением."
        }
        writableDatabase.delete("trigger_state","key=? OR key GLOB ? OR key GLOB ?",
            arrayOf("approval-archive:$id","geo:$id:*","geo-clock:$id:*"))
    }

    fun next(id: String, revision: Int, at: Long?) {
        writableDatabase.update("rules",ContentValues().apply { if(at==null) putNull("next_at") else put("next_at",at) },
            "id=? AND revision=? AND enabled=1 AND approved=revision AND attention=''",arrayOf(id,revision.toString()))
    }

    internal fun clearDeliveredOccurrence(id: String, revision: Int, due: Long) {
        writableDatabase.update("rules", ContentValues().apply { putNull("next_at") },
            "id=? AND revision=? AND enabled=1 AND approved=revision AND attention='' AND next_at=?",
            arrayOf(id, revision.toString(), due.toString()))
    }
    fun history(id: String): List<AutomationRun> = readableDatabase.rawQuery("SELECT * FROM runs WHERE rule_id=? ORDER BY time DESC,rowid DESC",arrayOf(id)).use { c ->
        buildList { while(c.moveToNext()) add(c.runRecord()) }
    }
    fun latestRun(rule: AutomationRule): AutomationRun? = readableDatabase.rawQuery(
        "SELECT * FROM runs WHERE rule_id=? AND revision=? AND token NOT GLOB 'dry:*' AND status<>'dryRun' ORDER BY time DESC,rowid DESC LIMIT 1",
        arrayOf(rule.id,rule.revision.toString())
    ).use { c ->
        if(c.moveToFirst()) c.runRecord() else null
    }
    fun occurrence(id: String, token: String): AutomationRun? = readableDatabase.rawQuery("SELECT * FROM runs WHERE rule_id=? AND token=?",arrayOf(id,token)).use { c ->
        if(c.moveToFirst()) c.runRecord() else null
    }
    private fun Cursor.runRecord() = AutomationRun(getString(0),getString(1),getInt(2),getString(3),getLong(4),getString(5),getString(6),getString(7))
    /** The claim survives process death. Existing claims never start another execution. */
    fun begin(rule: AutomationRule, event: AutomationEvent, now: Long, dry: Boolean): AutomationRun? {
        if(event.token.startsWith(AutomationOneTime.TOKEN_PREFIX)) return null
        var result: AutomationRun? = null
        transaction {
            if(!dry && !active(rule.id,rule.revision)) return@transaction
            result=insertClaim(rule,event.token,now)
        }
        return result
    }

    internal fun oneTimeRule(request: AutomationOneTime.Request): AutomationRule? {
        if(request.database!=databaseName) return null
        val rule=rule(request.ruleId) ?: return null
        return rule.takeIf {!it.enabled && it.revision==request.revision && it.definition.json==request.definition && approvalToken(it.id)==request.invalidation}
    }

    internal fun claimOneTime(proposal: AutomationOneTime.Proposal,now: Long): AutomationRun? = proposal.claim {
        var result: AutomationRun?=null
        transaction {
            val rule=oneTimeRule(proposal.request) ?: return@transaction
            result=insertClaim(rule,proposal.token,now)
        }
        result
    }

    private fun insertClaim(rule: AutomationRule,token: String,now: Long): AutomationRun? {
        if(occurrence(rule.id,token)!=null) return null
        val run=AutomationRun(UUID.randomUUID().toString(),rule.id,rule.revision,token,now,"unknownOutcome","Запуск начат. После прерывания автоматического повтора нет.","[]")
        writableDatabase.insertOrThrow("runs",null,ContentValues().apply {
            put("id",run.id);put("rule_id",rule.id);put("revision",rule.revision);put("token",run.token);put("time",now)
            put("status",run.status);put("message",run.message);put("details",run.details)
        })
        return run
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
