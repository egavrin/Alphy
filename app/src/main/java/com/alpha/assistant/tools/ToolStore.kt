package com.alpha.assistant.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.alpha.assistant.capabilities.*
import org.json.JSONObject

data class ToolRecord(val id: String, val revision: Int, val stateVersion: Int, val spec: String, val state: String,
    val ui: String, val conversationId: String?, val saved: Boolean, val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis())
data class ToolRevision(val revision: Int, val spec: String, val state: String, val ui: String)
data class ToolDraft(val id: String, val toolId: String?, val baseRevision: Int, val baseStateVersion: Int,
    val prompt: String, val candidate: String?, val previewState: String?, val previewUi: String?, val conversationId: String?) {
    // Automatic chat receipts use a negative base revision and never appear as editable drafts.
    val acceptedRevision: Int? get() = baseRevision.takeIf { it < 0 }?.let { -it }
}

/** Separate storage leaves all conversation and credential data untouched. */
class ToolStore(private val context: Context, name: String = "alpha-tools.db") : SQLiteOpenHelper(context, name, null, 7) {
    internal val artifactIdentity=context.getDatabasePath(name).absolutePath
    internal fun artifactWorkPending(owner: String): Boolean =
        effects(owner).any {it.phase!="terminal"} || deliveries(owner).any {it.phase in setOf("pending","claimed")} || ToolRecords(this).pending(owner)!=null
    companion object {
        internal val effectMutex = kotlinx.coroutines.sync.Mutex()
        internal val deliveryMutex = kotlinx.coroutines.sync.Mutex()
        internal val processSession = java.util.UUID.randomUUID().toString()
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tools(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, state_version INTEGER NOT NULL, spec TEXT NOT NULL, state TEXT NOT NULL, ui TEXT NOT NULL, conversation_id TEXT, saved INTEGER NOT NULL, archived INTEGER NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE revisions(tool_id TEXT NOT NULL, revision INTEGER NOT NULL, spec TEXT NOT NULL, state TEXT NOT NULL, ui TEXT NOT NULL, PRIMARY KEY(tool_id,revision))")
        createCapabilities(db)
        createResultTables(db)
        NavigationLedger.createTables(db)
        OwnedCalendarLedger.createTables(db)
        ToolRecords.createTables(db)
        db.execSQL("CREATE TABLE drafts(id TEXT PRIMARY KEY, tool_id TEXT, base_revision INTEGER NOT NULL, base_state_version INTEGER NOT NULL, prompt TEXT NOT NULL, candidate TEXT, preview_state TEXT, preview_ui TEXT, conversation_id TEXT)")
    }
    private fun createCapabilities(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS capability_grants(owner TEXT NOT NULL, capability TEXT NOT NULL, granted INTEGER NOT NULL, PRIMARY KEY(owner,capability))")
        db.execSQL("CREATE TABLE IF NOT EXISTS capability_effects(effect_key TEXT PRIMARY KEY, owner TEXT NOT NULL, revision INTEGER NOT NULL, state_version INTEGER NOT NULL, request TEXT NOT NULL, result TEXT NOT NULL, created_at INTEGER NOT NULL, phase TEXT NOT NULL DEFAULT 'pending', session TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 3..6 && newVersion == 7) { "Unsupported tool database version. Existing data is unchanged." }
        if(oldVersion == 3) {
        db.execSQL("ALTER TABLE capability_effects ADD COLUMN phase TEXT NOT NULL DEFAULT 'terminal'")
        db.execSQL("ALTER TABLE capability_effects ADD COLUMN session TEXT")
        // A prior unknown outcome never authorizes replay. Old unlaunched requests still require confirmation.
        val pending=db.rawQuery("SELECT effect_key,result FROM capability_effects",null).use { c ->
            buildList { while(c.moveToNext()) {
                val result=CapabilityResult.parse(c.getString(1))
                if(result.status == "awaitingUser" && !result.data.optBoolean("launched")) add(c.getString(0))
            } }
        }
        pending.forEach { key -> db.execSQL("UPDATE capability_effects SET phase='pending' WHERE effect_key=?",arrayOf(key)) }
        createResultTables(db)
        }
        if(oldVersion < 5) ToolRecords.createTables(db)
        if(oldVersion < 6) NavigationLedger.createTables(db)
        OwnedCalendarLedger.createTables(db)
    }
    private fun createResultTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE capability_deliveries(delivery_id TEXT PRIMARY KEY,owner TEXT NOT NULL,revision INTEGER NOT NULL,state_version INTEGER NOT NULL,phase TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,event TEXT,session TEXT,UNIQUE(owner,revision,state_version))")
        db.execSQL("CREATE TABLE capability_private_owners(owner TEXT PRIMARY KEY)")
        for(table in listOf("capability_resources","capability_acquisitions")) db.execSQL("CREATE TABLE $table(resource_id TEXT PRIMARY KEY,owner TEXT NOT NULL,kind TEXT NOT NULL,locator TEXT NOT NULL,mime_type TEXT NOT NULL,size_bytes INTEGER NOT NULL,persistent INTEGER NOT NULL,owns_grant INTEGER NOT NULL,session TEXT NOT NULL,expires_at INTEGER NOT NULL)")
    }
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported tool database version. Existing data is unchanged.")
    }
    fun permissionGranted(capability: String) = AndroidCapabilities(context).permissionGranted(capability)
    fun privateResults(owner: String) = readableDatabase.rawQuery("SELECT 1 FROM capability_private_owners WHERE owner=?",arrayOf(owner)).use { it.moveToFirst() }
    private fun retainResultPrivacy(tool: ToolRecord) {
        if(ToolSpec.available(tool.spec)?.let { it.capabilityResults || it.collections.length() > 0 || it.capabilities.any { id -> id in NavigationContract.ids || id in OwnedCalendarContract.ids } }==true) writableDatabase.insertWithOnConflict("capability_private_owners",null,
            ContentValues().apply { put("owner",tool.id) },SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun tools(): List<ToolRecord> = readableDatabase.rawQuery("SELECT * FROM tools ORDER BY rowid DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(ToolRecord(c.getString(0), c.getInt(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7) != 0, c.getInt(8) != 0, c.getLong(9))) }
    }
    fun tool(id: String) = tools().find { it.id == id }
    fun drafts(): List<ToolDraft> = readableDatabase.rawQuery("SELECT * FROM drafts WHERE base_revision>=0 ORDER BY rowid DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(ToolDraft(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8))) }
    }
    fun draft(id: String): ToolDraft? = readableDatabase.rawQuery("SELECT * FROM drafts WHERE id=?",arrayOf(id)).use { c ->
        if(c.moveToFirst()) ToolDraft(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8)) else null
    }
    fun revisions(id: String): List<ToolRevision> = readableDatabase.rawQuery("SELECT revision,spec,state,ui FROM revisions WHERE tool_id=? ORDER BY revision DESC", arrayOf(id)).use { c ->
        buildList { while(c.moveToNext()) add(ToolRevision(c.getInt(0), c.getString(1), c.getString(2), c.getString(3))) }
    }
    fun saveDraft(d: ToolDraft) { put("drafts", ContentValues().apply {
        put("id", d.id); put("tool_id", d.toolId); put("base_revision", d.baseRevision); put("base_state_version", d.baseStateVersion)
        put("prompt", d.prompt); put("candidate", d.candidate); put("preview_state", d.previewState); put("preview_ui", d.previewUi); put("conversation_id", d.conversationId)
    }) }
    fun deleteDraft(id: String) { writableDatabase.delete("drafts", "id=?", arrayOf(id)) }
    fun saveTool(t: ToolRecord, revision: Boolean) { transaction {
        ToolRecords(this).beforeCommit(t,tool(t.id),null)
        retainResultPrivacy(t)
        put("tools", ContentValues().apply {
            put("id", t.id); put("revision", t.revision); put("state_version", t.stateVersion); put("spec", t.spec); put("state", t.state); put("ui", t.ui); put("conversation_id", t.conversationId); put("saved", if(t.saved) 1 else 0); put("archived",if(t.archived) 1 else 0); put("created_at",t.createdAt)
        })
        if(revision) put("revisions", ContentValues().apply { put("tool_id",t.id); put("revision",t.revision); put("spec",t.spec); put("state",t.state); put("ui",t.ui) })
    } }
    /** SQLite tokens protect writes across foreground and widget engine instances. */
    fun commitTool(t: ToolRecord, old: ToolRecord?, revision: Boolean, draft: ToolDraft? = null, effects: List<CapabilityRequest> = emptyList(), retainReceipt: Boolean = false, recordDeliveryId: String? = null, capabilityDeliveryId: String? = null, autonomous: Boolean = false) = transaction {
        check(!t.archived && old?.archived != true) { "Инструмент в архиве. Сначала восстанови его." }
        if(autonomous) {
            check(this.effects(t.id).none {it.phase!="terminal"}) {"A native action must finish before another state change."}
            check(deliveries(t.id).none {it.phase in setOf("pending","claimed") && it.id!=capabilityDeliveryId}) {"A native result must finish before another state change."}
        }
        ToolRecords(this).beforeCommit(t,old,recordDeliveryId)
        retainResultPrivacy(t)
        if (draft != null) check(draft(draft.id) == draft) { "Черновик изменился" }
        val values = ContentValues().apply {
            put("id",t.id); put("revision",t.revision); put("state_version",t.stateVersion)
            put("spec",t.spec); put("state",t.state); put("ui",t.ui)
            put("conversation_id",t.conversationId); put("saved",if(t.saved) 1 else 0); put("archived",if(t.archived) 1 else 0); put("created_at",t.createdAt)
        }
        if (old == null) check(writableDatabase.insertWithOnConflict("tools",null,values,SQLiteDatabase.CONFLICT_IGNORE) != -1L) { "Инструмент уже существует" }
        else check(writableDatabase.update("tools",values,"id=? AND revision=? AND state_version=? AND saved=? AND archived=0",
            arrayOf(old.id,old.revision.toString(),old.stateVersion.toString(),if(old.saved) "1" else "0")) == 1) { "Инструмент изменился. Открой текущую версию." }
        if (revision) put("revisions",ContentValues().apply {
            put("tool_id",t.id); put("revision",t.revision); put("spec",t.spec); put("state",t.state); put("ui",t.ui)
        })
        if(effects.isNotEmpty() && ToolSpec.parse(t.spec).capabilityResults) {
            check(effects.size <= 3)
            writableDatabase.insertOrThrow("capability_deliveries",null,ContentValues().apply {
                put("delivery_id",java.util.UUID.randomUUID().toString()); put("owner",t.id); put("revision",t.revision)
                put("state_version",t.stateVersion); put("phase","pending"); put("attempts",0)
            })
        }
        effects.forEach { request ->
            val key = "${t.id}:${t.revision}:${t.stateVersion}:${request.id}"
            check(writableDatabase.insertOrThrow("capability_effects",null,ContentValues().apply {
                put("effect_key",key); put("owner",t.id); put("revision",t.revision); put("state_version",t.stateVersion)
                put("request",request.json().toString()); put("created_at",System.currentTimeMillis()); put("phase","pending"); put("session",processSession)
                put("result",CapabilityResult("awaitingUser","Перед выполнением проверь цель и параметры действия.").json().toString())
            }) != -1L)
        }
        if (draft != null) {
            if(retainReceipt) saveDraft(draft.copy(toolId = t.id,baseRevision = -t.revision,baseStateVersion = t.stateVersion))
            else deleteDraft(draft.id)
        }
    }
    fun grant(owner: String, capability: String, enabled: Boolean) {
        require(capability in CapabilityCatalog.ids)
        check(tool(owner)?.archived == false) { "Инструмент недоступен" }
        transaction {
            put("capability_grants",ContentValues().apply { put("owner",owner); put("capability",capability); put("granted",if(enabled) 1 else 0) })
            if(!enabled && capability in NavigationContract.ids) NavigationLedger(this).revoke(owner)
            if(!enabled) deliveries(owner).filter { delivery ->
                delivery.phase in setOf("pending","claimed") && effects(owner).any { it.revision == delivery.revision && it.stateVersion == delivery.stateVersion && it.request.capability == capability }
            }.forEach { rejectDelivery(it.id) }
            if(!enabled) effects(owner).filter { it.request.capability == capability && it.phase != "terminal" }.forEach {
                val uncertain=it.phase=="running" && it.request.capability in setOf("calendar.createOwned","calendar.deleteOwned","calendar.deleteAllOwned")
                updateEffect(it.key,if(uncertain) CapabilityResult("unknownOutcome","Calendar access was revoked during native execution. Check the provider outcome before another action.") else CapabilityResult("revoked","Доступ этого инструмента к системной функции отозван."),"terminal")
            }
        }
    }
    fun grantRevoked(owner: String, capability: String): Boolean = readableDatabase.rawQuery(
        "SELECT granted FROM capability_grants WHERE owner=? AND capability=?",arrayOf(owner,capability)).use { it.moveToFirst() && it.getInt(0) == 0 }
    fun granted(owner: String, capability: String): Boolean = readableDatabase.rawQuery(
        "SELECT granted FROM capability_grants WHERE owner=? AND capability=?",arrayOf(owner,capability)).use { it.moveToFirst() && it.getInt(0) == 1 }
    fun effects(owner: String): List<CapabilityEffect> = readableDatabase.rawQuery(
        "SELECT effect_key,owner,revision,state_version,request,result,phase FROM capability_effects WHERE owner=? ORDER BY created_at,rowid",arrayOf(owner)).use { c ->
        buildList { while(c.moveToNext()) {
            val effect=CapabilityEffect(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),
                CapabilityCatalog.request(JSONObject(c.getString(4))),CapabilityResult.parse(c.getString(5)),c.getString(6))
            // Journal counts remain authoritative if progress committed immediately before interruption.
            val result=if(effect.phase=="terminal" && effect.result.status=="unknownOutcome")
                OwnedCalendarLedger(this@ToolStore).interruptedResult(effect,effect.result.message) ?: effect.result else effect.result
            add(effect.copy(result=result))
        } }
    }
    fun effect(key: String): CapabilityEffect? = readableDatabase.rawQuery("SELECT owner FROM capability_effects WHERE effect_key=?",arrayOf(key)).use { c ->
        if(c.moveToFirst()) effects(c.getString(0)).find { it.key == key } else null
    }
    /** Only the claimed native execution can publish its initial result. Terminal callbacks are immutable. */
    fun finishEffect(key: String, result: CapabilityResult) {
        val permission=effect(key)?.let { permissionGranted(it.request.capability) } ?: false
        transaction {
        val effect=effect(key) ?: return@transaction
        if(effect.phase !in setOf("pending","running")) return@transaction
        val phase=when {
            result.status == "awaitingUser" && result.data.optBoolean("launched") -> "waiting"
            result.status == "awaitingUser" -> "pending"
            else -> "terminal"
        }
        val calendarWrite=effect.phase=="running" && effect.request.capability in setOf("calendar.createOwned","calendar.deleteOwned","calendar.deleteAllOwned")
        val safe=when {
            calendarWrite && (!granted(effect.owner,effect.request.capability) || !effectTargetValid(effect) || !permission) ->
                CapabilityResult("unknownOutcome","Calendar access changed after native execution started. The provider outcome remains uncertain. No insert will repeat automatically.")
            !granted(effect.owner,effect.request.capability) -> CapabilityResult("revoked","Tool access was revoked before the result returned.")
            !effectTargetValid(effect) -> CapabilityResult("cancelled","The tool changed before the result returned.")
            !permission -> CapabilityResult("denied","Android permission is no longer available.")
            phase == "terminal" -> boundedResult(effect,result)
            else -> result
        }
        updateEffect(key,safe,if(safe.status == "awaitingUser") phase else "terminal")
        }
    }
    private fun boundedResult(effect: CapabilityEffect, result: CapabilityResult): CapabilityResult {
        if(tool(effect.owner)?.let { ToolSpec.available(it.spec)?.capabilityResults } != true) return result
        return try {
            CapabilityResultEvent.data(effect.request.capability,result)
            result
        } catch(_: Exception) { CapabilityResult("failed","The native result did not satisfy its bounded data contract.") }
    }
    private fun updateEffect(key: String, result: CapabilityResult, phase: String) {
        val saved=if(phase=="terminal" && result.status!="succeeded") effect(key)?.let {
            OwnedCalendarLedger(this).interruptedResult(it,result.message)
        } ?: result else result
        check(writableDatabase.update("capability_effects",ContentValues().apply {
            put("result",saved.json().toString()); put("phase",phase); put("session",processSession)
        },"effect_key=?",arrayOf(key)) == 1)
    }
    fun effectTargetValid(effect: CapabilityEffect): Boolean {
        val target=tool(effect.owner) ?: return false
        return !target.archived && target.revision == effect.revision && target.stateVersion == effect.stateVersion &&
            effect.request.capability in ToolSpec.available(target.spec)?.capabilities.orEmpty() && granted(effect.owner,effect.request.capability)
    }
    /** Persist the running phase before the adapter starts. A crash never authorizes an automatic retry. */
    fun claimEffect(key: String, confirmed: Boolean): CapabilityEffect? {
        var claimed: CapabilityEffect? = null
        transaction {
            val effect = effect(key) ?: error("Системное действие недоступно")
            if(effect.phase != "pending") return@transaction
            val tool = tool(effect.owner)
            val result = when {
                tool == null || tool.archived || tool.revision != effect.revision || tool.stateVersion != effect.stateVersion -> CapabilityResult("cancelled","Инструмент изменился. Повтори действие в текущей версии.")
                !confirmed -> CapabilityResult("cancelled","Ты отменил действие.")
                ToolSpec.available(tool.spec)?.capabilities?.contains(effect.request.capability)!=true -> CapabilityResult("denied","Инструмент не объявил эту системную функцию.")
                !granted(effect.owner,effect.request.capability) -> if(grantRevoked(effect.owner,effect.request.capability)) CapabilityResult("revoked","Доступ этого инструмента к системной функции отозван.")
                    else CapabilityResult("denied","Перед новым действием выдай инструменту доступ к этой функции.")
                else -> null
            }
            if(result != null) updateEffect(key,result,"terminal")
            else {
                updateEffect(key,CapabilityResult("unknownOutcome","Выполнение началось. Проверь результат перед повторным действием."),"running")
                claimed = effect
            }
        }
        return claimed
    }
    fun finishUserEffect(key: String, result: CapabilityResult, resource: CapabilityResource? = null): Boolean {
        var accepted=false
        val permission=effect(key)?.let { permissionGranted(it.request.capability) } ?: false
        transaction {
            val effect = effect(key) ?: return@transaction
            if(effect.phase != "waiting") return@transaction
            val safe=when {
                !granted(effect.owner,effect.request.capability) -> CapabilityResult("revoked","Доступ инструмента отозван. Выбранные данные не сохранены.")
                !effectTargetValid(effect) -> CapabilityResult("cancelled","Инструмент изменился до возврата результата Android. Результат не применён.")
                !permission -> CapabilityResult("denied","Android permission is no longer available.")
                else -> boundedResult(effect,result)
            }
            if(resource != null && safe.status == "succeeded") {
                require(resource.owner == effect.owner && safe.data.optString("resourceId") == resource.id)
                insertResource(resource)
            }
            updateEffect(key,safe,"terminal")
            accepted=safe.status == "succeeded"
        }
        return accepted
    }
    /** Recover only work from another process. The host never relaunches Android activities on recovery. */
    fun recoverCapabilityWork(force: Boolean = false) {
        CapabilityResources(context,this).recoverAcquisitions(force)
        transaction {
        val args=if(force) emptyArray() else arrayOf(processSession)
        val where=if(force) "" else " AND (session IS NULL OR session<>?)"
        val interrupted=readableDatabase.rawQuery("SELECT effect_key,phase FROM capability_effects WHERE phase IN ('running','waiting')"+where,args).use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        interrupted.forEach { (key,phase) -> updateEffect(key,CapabilityResult(if(phase == "running") "unknownOutcome" else "unavailable",
            if(phase == "running") "The interrupted native action has an unknown outcome. It will not repeat." else "The interrupted selection is unavailable. Select it again."),"terminal") }
        writableDatabase.execSQL("UPDATE capability_deliveries SET phase=CASE WHEN attempts<2 THEN 'pending' ELSE 'rejected' END WHERE phase='claimed'"+where,args)
        }
    }
    fun resultOwners(): List<String> = (readableDatabase.rawQuery("SELECT DISTINCT owner FROM capability_deliveries WHERE phase<>'committed'",null).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0)) }
    } + ToolRecords(this).pendingOwners()).distinct()
    fun deliveries(owner: String): List<CapabilityDelivery> = readableDatabase.rawQuery(
        "SELECT delivery_id,owner,revision,state_version,phase,attempts,event FROM capability_deliveries WHERE owner=? ORDER BY rowid",arrayOf(owner)).use { c ->
        buildList { while(c.moveToNext()) add(CapabilityDelivery(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),c.getString(4),c.getInt(5),c.getString(6))) }
    }
    private data class ResultAccess(val capabilities: Set<String>, val resources: Set<String>)
    /** Full access checks run outside SQLite. A short permission check also guards the final commit. */
    private fun resultAccess(owner: String, permissionCheck: (String)->Boolean): ResultAccess {
        val capabilities=effects(owner).map { it.request.capability }.toSet().filter(permissionCheck).toSet()
        val manager=CapabilityResources(context,this)
        val available=resources(owner).filter { runCatching { manager.resolve(owner,it.id) }.isSuccess }.map { it.id }.toSet()
        return ResultAccess(capabilities,available)
    }
    private fun deliveryValid(delivery: CapabilityDelivery, access: ResultAccess): Boolean {
        val target=tool(delivery.owner) ?: return false
        if(target.archived || target.revision != delivery.revision || target.stateVersion != delivery.stateVersion) return false
        val spec=ToolSpec.available(target.spec) ?: return false
        val batch=effects(delivery.owner).filter { it.revision == delivery.revision && it.stateVersion == delivery.stateVersion }
        return spec.capabilityResults && batch.size in 1..3 && batch.all {
            it.phase == "terminal" && it.request.capability in spec.capabilities && granted(delivery.owner,it.request.capability) && it.request.capability in access.capabilities &&
                (!it.result.data.has("resourceId") || resource(it.result.data.getString("resourceId"))?.let { resource ->
                    resource.id in access.resources && resource.owner == delivery.owner && resource.expiresAt > System.currentTimeMillis() &&
                        resource.kind == it.result.data.optString("kind") && resource.mimeType == it.result.data.optString("mimeType") && resource.sizeBytes == it.result.data.optLong("sizeBytes",-1)
                } == true)
        }
    }
    /** Only quick Android permission metadata is checked under SQLite. Provider and file checks stay outside. */
    private fun immediateResultAccess(delivery: CapabilityDelivery, permissionCheck: (String)->Boolean): Boolean {
        val manager=CapabilityResources(context,this)
        return effects(delivery.owner).filter { it.revision == delivery.revision && it.stateVersion == delivery.stateVersion }.all { effect ->
            permissionCheck(effect.request.capability) && (!effect.result.data.has("resourceId") ||
                resource(effect.result.data.getString("resourceId"))?.let(manager::permissionAvailable) == true)
        }
    }
    fun claimDelivery(owner: String, permissionCheck: (String)->Boolean): CapabilityDelivery? {
        var claimed: CapabilityDelivery?=null
        val access=resultAccess(owner,permissionCheck)
        transaction {
            for(delivery in deliveries(owner).filter { it.phase == "pending" }) {
                val target=tool(owner)
                if(target == null || target.archived || target.revision != delivery.revision || target.stateVersion != delivery.stateVersion || ToolSpec.available(target.spec)?.capabilityResults!=true) {
                    rejectDelivery(delivery.id); continue
                }
                val batch=effects(owner).filter { it.revision == delivery.revision && it.stateVersion == delivery.stateVersion }
                if(batch.any { it.phase != "terminal" }) continue
                if(delivery.attempts >= 2 || !deliveryValid(delivery,access)) { rejectDelivery(delivery.id); continue }
                val event=try { CapabilityResultEvent.event(delivery.id,batch).toString() } catch(_: Exception) { rejectDelivery(delivery.id); continue }
                val changed=writableDatabase.update("capability_deliveries",ContentValues().apply {
                    put("phase","claimed"); put("attempts",delivery.attempts+1); put("event",event); put("session",processSession)
                },"delivery_id=? AND phase='pending' AND attempts=?",arrayOf(delivery.id,delivery.attempts.toString()))
                if(changed == 1) { claimed=delivery.copy(phase="claimed",attempts=delivery.attempts+1,event=event); break }
            }
        }
        return claimed
    }
    fun retryDelivery(id: String) {
        writableDatabase.execSQL("UPDATE capability_deliveries SET phase=CASE WHEN attempts<2 THEN 'pending' ELSE 'rejected' END WHERE delivery_id=? AND phase='claimed'",arrayOf(id))
    }
    fun releaseRejectedResources(owner: String) {
        val resources=CapabilityResources(context,this)
        deliveries(owner).filter { it.phase == "rejected" }.forEach { delivery ->
            effects(owner).filter { it.revision == delivery.revision && it.stateVersion == delivery.stateVersion }.forEach { effect ->
                effect.result.data.optString("resourceId").takeIf { it.isNotEmpty() }?.let { id -> resource(id)?.let(resources::release) }
            }
        }
    }
    fun rejectDelivery(id: String) { writableDatabase.update("capability_deliveries",ContentValues().apply { put("phase","rejected") },"delivery_id=? AND phase IN ('pending','claimed')",arrayOf(id)) }
    fun commitDelivery(delivery: CapabilityDelivery, next: ToolRecord, canCommit: ()->Boolean = { true }, permissionCheck: (String)->Boolean): Boolean {
        var committed=false
        val access=resultAccess(delivery.owner,permissionCheck)
        transaction {
            val saved=deliveries(delivery.owner).find { it.id == delivery.id } ?: return@transaction
            if(saved != delivery || saved.phase != "claimed") return@transaction
            if(!canCommit() || !deliveryValid(delivery,access) || !immediateResultAccess(delivery,permissionCheck)) { rejectDelivery(delivery.id); return@transaction }
            val old=checkNotNull(tool(delivery.owner))
            require(next == old.copy(stateVersion=old.stateVersion+1,state=next.state,ui=next.ui))
            val spec=ToolSpec.parse(old.spec); spec.validateState(next.state); spec.validateUi(JSONObject(next.ui),JSONObject(next.state))
            // Android permission changes cannot be atomic with SQLite. Check immediately after the final cancellation guard.
            if(!canCommit() || !deliveryValid(delivery,access) || !immediateResultAccess(delivery,permissionCheck)) { rejectDelivery(delivery.id); return@transaction }
            commitTool(next,old,false,capabilityDeliveryId=delivery.id)
            check(writableDatabase.update("capability_deliveries",ContentValues().apply { put("phase","committed") },
                "delivery_id=? AND phase='claimed' AND attempts=?",arrayOf(delivery.id,delivery.attempts.toString())) == 1)
            committed=true
        }
        return committed
    }
    fun resources(owner: String) = resourceQuery("owner=?",arrayOf(owner))
    fun resourcesForLocator(locator: String) = resourceQuery("locator=?",arrayOf(locator))
    fun resource(id: String) = resourceQuery("resource_id=?",arrayOf(id)).singleOrNull()
    fun acquisitions() = resourceQuery("1=1",emptyArray(),"capability_acquisitions")
    fun journalResource(resource: CapabilityResource) {
        if(acquisitions().any { it.id == resource.id }) return
        check(resources(resource.owner).size + acquisitions().count { it.owner == resource.owner } < CapabilityResources.MAX_RESOURCES)
        insertResourceRow("capability_acquisitions",resource)
    }
    fun deleteAcquisition(id: String) { writableDatabase.delete("capability_acquisitions","resource_id=?",arrayOf(id)) }
    private fun resourceQuery(where: String, args: Array<String>, table: String = "capability_resources"): List<CapabilityResource> = readableDatabase.rawQuery("SELECT * FROM $table WHERE $where",args).use { c ->
        buildList { while(c.moveToNext()) add(CapabilityResource(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),c.getInt(6)!=0,c.getInt(7)!=0,c.getString(8),c.getLong(9))) }
    }
    fun insertResource(resource: CapabilityResource) = transaction {
        check(resources(resource.owner).size < CapabilityResources.MAX_RESOURCES)
        insertResourceRow("capability_resources",resource)
        deleteAcquisition(resource.id)
    }
    private fun insertResourceRow(table: String, resource: CapabilityResource) {
        writableDatabase.insertOrThrow(table,null,ContentValues().apply {
            put("resource_id",resource.id); put("owner",resource.owner); put("kind",resource.kind); put("locator",resource.locator)
            put("mime_type",resource.mimeType); put("size_bytes",resource.sizeBytes); put("persistent",if(resource.persistent) 1 else 0)
            put("owns_grant",if(resource.ownsGrant) 1 else 0); put("session",resource.session); put("expires_at",resource.expiresAt)
        })
    }
    fun deleteResource(id: String) { writableDatabase.delete("capability_resources","resource_id=?",arrayOf(id)) }
    fun transferResourceGrant(id: String) { writableDatabase.update("capability_resources",ContentValues().apply { put("owns_grant",1) },"resource_id=?",arrayOf(id)) }
    fun markSaved(id: String) {
        check(writableDatabase.update("tools",ContentValues().apply { put("saved",1) },"id=? AND archived=0",arrayOf(id)) == 1) { "Инструмент недоступен или в архиве" }
    }
    /** Archive changes only availability. Tokens invalidate all previously issued tool commands. */
    fun setArchived(expected: ToolRecord, archived: Boolean) = transaction {
        check(expected.saved && expected.archived != archived) { "Состояние библиотеки изменилось" }
        check(writableDatabase.update("tools",ContentValues().apply {
            put("archived",if(archived) 1 else 0); put("state_version",expected.stateVersion+1)
        },"id=? AND revision=? AND state_version=? AND archived=? AND saved=1",
            arrayOf(expected.id,expected.revision.toString(),expected.stateVersion.toString(),if(expected.archived) "1" else "0")) == 1) {
            "Инструмент изменился. Открой текущую версию."
        }
        if(archived) NavigationLedger(this).revoke(expected.id)
        if(archived) ToolRecords(this).pending(expected.id)?.let { ToolRecords(this).reject(it) }
        if(archived) effects(expected.id).filter {it.phase != "terminal"}.forEach {
            val uncertain=it.phase=="running" && it.request.capability in setOf("calendar.createOwned","calendar.deleteOwned","calendar.deleteAllOwned")
            updateEffect(it.key,if(uncertain) CapabilityResult("unknownOutcome","The app was archived during calendar execution. The event ledger remains saved and the provider outcome is uncertain.") else CapabilityResult("cancelled","Инструмент в архиве. Ожидающее действие отменено."),"terminal")
        }
    }
    fun transaction(block: () -> Unit) {
        writableDatabase.beginTransaction()
        try { block(); writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
    }
    private fun put(table: String, values: ContentValues) { check(writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Не удалось сохранить инструмент" } }
}
