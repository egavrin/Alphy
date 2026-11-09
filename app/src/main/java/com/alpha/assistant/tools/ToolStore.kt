package com.alpha.assistant.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import java.io.Closeable
import com.alpha.assistant.capabilities.*
import com.alpha.assistant.scheduling.SourceRefresh
import com.alpha.assistant.scheduling.RecurringSearch
import com.alpha.assistant.surfaces.widgetauthoring.WidgetLayoutStore
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class ToolRecord(val id: String, val revision: Int, val stateVersion: Int, val spec: String, val state: String,
    val ui: String, val conversationId: String?, val saved: Boolean, val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(), val lastConversationId: String? = conversationId, val onDesktop: Boolean = false)
data class StoppedAutomation(val id: String, val name: String)
data class ToolRevision(val revision: Int, val spec: String, val state: String, val ui: String,
    val label: String, val createdAt: Long, val stoppedAutomations: List<StoppedAutomation>)
data class ToolRestoreAvailability(val reason: String? = null, val errorCode: String? = null) {
    val available get() = reason == null
}
data class ToolDeletionSnapshot internal constructor(
    val tool: ToolRecord,
    val revisionCount: Int,
    val widgetIds: Set<Int>,
    val automations: List<StoppedAutomation>,
    internal val localFingerprint: String,
    internal val medicineFingerprint: String,
    internal val automationVersions: Map<String,Int>
)
data class ToolDraft(val id: String, val toolId: String?, val baseRevision: Int, val baseStateVersion: Int,
    val prompt: String, val candidate: String?, val previewState: String?, val previewUi: String?, val conversationId: String?) {
    // Automatic chat receipts use a negative base revision and never appear as editable drafts.
    val acceptedRevision: Int? get() = baseRevision.takeIf { it < 0 }?.let { -it }
}

/** Separate storage leaves all conversation and credential data untouched. */
class ToolStore private constructor(internal val context: Context, internal val logicalDatabase: String,
    private val binding: Result<ToolLibrarySelection>,
    private val permissionCheck: (String) -> Boolean = AndroidCapabilities(context)::permissionGranted
) : Closeable {
    constructor(context: Context, name: String = ToolLibraryStorage.DEFAULT) :
        this(context, ToolLibraryStorage.logicalName(name), ToolLibraryStorage.bind(context, name))
    internal constructor(context: Context, name: String, permissionCheck: (String) -> Boolean) :
        this(context, ToolLibraryStorage.logicalName(name), ToolLibraryStorage.bind(context, name), permissionCheck)

    val databaseName = binding.getOrNull()?.physical ?: logicalDatabase
    private var database: SQLiteDatabase? = null
    val readableDatabase get() = writableDatabase
    val writableDatabase: SQLiteDatabase
        @Synchronized get() {
            database?.let { cached ->
                ToolLibraryStorage.requireCurrent(context, library)
                return cached
            }
            var retries = 0
            while (true) {
                try {
                    return ToolLibraryStorage.open(context, library) {
                        val opened = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null, ToolLibraryStorage.preserveOnCorruption)
                        try {
                            initialize(opened)
                            val obsolete = "capability<>?"
                            val network = arrayOf(NETWORK_GRANT)
                            val hasObsoleteGrants = opened.rawQuery(
                                "SELECT 1 FROM capability_grants WHERE $obsolete LIMIT 1",network,
                            ).use { it.moveToFirst() }
                            if (hasObsoleteGrants) opened.delete("capability_grants",obsolete,network)
                        } catch (failure: Exception) {
                            opened.close()
                            if (failure is SQLiteDatabaseLockedException) throw failure
                            throw ToolLibraryUnavailable(failure)
                        }
                        database = opened
                        opened
                    }
                } catch (failure: SQLiteDatabaseLockedException) {
                    if (retries == 4) throw ToolLibraryUnavailable(failure)
                    // Opening can collide with a write from another store. Let it finish outside the library gate.
                    SystemClock.sleep(20L shl retries)
                    retries++
                } catch (failure: SQLiteException) {
                    throw ToolLibraryUnavailable(failure)
                }
            }
        }

    @Synchronized override fun close() {
        database?.close()
        database = null
    }

    internal val library get()=binding.getOrThrow()
    internal val primaryLibrary get()=logicalDatabase==ToolLibraryStorage.DEFAULT
    internal val automationDatabase = binding.getOrNull()?.automation ?: ToolLibrarySelection(logicalDatabase,logicalDatabase).automation
    internal val artifactIdentity=context.getDatabasePath(databaseName).absolutePath
    private val changeCounter = changeCounters.getOrPut(artifactIdentity) { MutableStateFlow(0L) }
    internal val changes = changeCounter.asStateFlow()
    internal fun artifactWorkPending(owner: String): Boolean =
        effects(owner).any {it.phase!="terminal"} || deliveries(owner).any {it.phase in setOf("pending","claimed")} || ToolRecords(this).pending(owner)!=null
    companion object {
        private const val TOOL_COLUMNS = "id,revision,state_version,spec,state,ui,conversation_id,saved,archived,created_at,last_conversation_id,on_desktop"
        private const val NETWORK_GRANT = "host.network"
        private val requiredTables = setOf("tools", "revisions", "drafts", "deleted_tools", "widget_layouts",
            "capability_grants", "capability_effects", "capability_deliveries", "capability_private_owners",
            "capability_resources", "capability_acquisitions", "maps_stops", "maps_reached", "owned_calendar_selection",
            "owned_calendar_events", "owned_calendar_delete_batches", "owned_calendar_delete_items", "artifact_collections",
            "artifact_records", "artifact_cursors", "artifact_record_deliveries", "live_sources", "source_schedules",
            "recurring_searches", "recurring_search_runs")
        private val changeCounters = ConcurrentHashMap<String, MutableStateFlow<Long>>()
        internal val effectMutex = kotlinx.coroutines.sync.Mutex()
        internal val deliveryMutex = kotlinx.coroutines.sync.Mutex()
        internal val processSession = java.util.UUID.randomUUID().toString()
    }
    private fun initialize(db: SQLiteDatabase) {
        if (currentStructure(db)) return
        db.beginTransaction()
        try {
            val tables = tables(db)
            check(tables.isEmpty() || tables.containsAll(listOf("tools", "revisions", "drafts")))
            if ("capability_effects" in tables) {
                check(columns(db, "capability_effects").containsAll(listOf("phase", "session")))
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS tools(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, state_version INTEGER NOT NULL, spec TEXT NOT NULL, state TEXT NOT NULL, ui TEXT NOT NULL, conversation_id TEXT, saved INTEGER NOT NULL, archived INTEGER NOT NULL, created_at INTEGER NOT NULL, last_conversation_id TEXT, on_desktop INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS revisions(tool_id TEXT NOT NULL, revision INTEGER NOT NULL, spec TEXT NOT NULL, state TEXT NOT NULL, ui TEXT NOT NULL, label TEXT NOT NULL, created_at INTEGER NOT NULL, stopped_automations TEXT NOT NULL, PRIMARY KEY(tool_id,revision))")
            db.execSQL("CREATE TABLE IF NOT EXISTS deleted_tools(id TEXT PRIMARY KEY, cleanup_pending INTEGER NOT NULL, widget_ids TEXT NOT NULL)")
            createCapabilities(db)
            createResultTables(db)
            NavigationLedger.createTables(db)
            OwnedCalendarLedger.createTables(db)
            ToolRecords.createTables(db)
            WidgetLayoutStore.createTable(db)
            SourceRefresh.createTables(db)
            RecurringSearch.createTables(db)
            db.execSQL("CREATE TABLE IF NOT EXISTS drafts(id TEXT PRIMARY KEY, tool_id TEXT, base_revision INTEGER NOT NULL, base_state_version INTEGER NOT NULL, prompt TEXT NOT NULL, candidate TEXT, preview_state TEXT, preview_ui TEXT, conversation_id TEXT)")
            if (addColumn(db, "tools", "last_conversation_id", "TEXT")) {
                db.execSQL("UPDATE tools SET last_conversation_id=conversation_id")
            }
            addColumn(db, "tools", "on_desktop", "INTEGER NOT NULL DEFAULT 0")
            addColumn(db, "revisions", "label", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "revisions", "created_at", "INTEGER NOT NULL DEFAULT 0")
            addColumn(db, "revisions", "stopped_automations", "TEXT NOT NULL DEFAULT '[]'")
            check(currentStructure(db))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun currentStructure(db: SQLiteDatabase): Boolean = tables(db).containsAll(requiredTables) &&
        columns(db, "tools").containsAll(TOOL_COLUMNS.split(',')) &&
        columns(db, "revisions").containsAll(listOf("tool_id", "revision", "spec", "state", "ui", "label", "created_at", "stopped_automations")) &&
        columns(db, "capability_effects").containsAll(listOf("phase", "session")) &&
        "write_token" in columns(db, "live_sources")

    private fun tables(db: SQLiteDatabase): Set<String> = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'", null
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    private fun addColumn(db: SQLiteDatabase, table: String, column: String, declaration: String): Boolean {
        if (column in columns(db, table)) return false
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $declaration")
        return true
    }

    private fun createCapabilities(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS capability_grants(owner TEXT NOT NULL, capability TEXT NOT NULL, granted INTEGER NOT NULL, PRIMARY KEY(owner,capability))")
        db.execSQL("CREATE TABLE IF NOT EXISTS capability_effects(effect_key TEXT PRIMARY KEY, owner TEXT NOT NULL, revision INTEGER NOT NULL, state_version INTEGER NOT NULL, request TEXT NOT NULL, result TEXT NOT NULL, created_at INTEGER NOT NULL, phase TEXT NOT NULL DEFAULT 'pending', session TEXT)")
    }
    private fun createResultTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS capability_deliveries(delivery_id TEXT PRIMARY KEY,owner TEXT NOT NULL,revision INTEGER NOT NULL,state_version INTEGER NOT NULL,phase TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,event TEXT,session TEXT,UNIQUE(owner,revision,state_version))")
        db.execSQL("CREATE TABLE IF NOT EXISTS capability_private_owners(owner TEXT PRIMARY KEY)")
        for(table in listOf("capability_resources","capability_acquisitions")) db.execSQL("CREATE TABLE IF NOT EXISTS $table(resource_id TEXT PRIMARY KEY,owner TEXT NOT NULL,kind TEXT NOT NULL,locator TEXT NOT NULL,mime_type TEXT NOT NULL,size_bytes INTEGER NOT NULL,persistent INTEGER NOT NULL,owns_grant INTEGER NOT NULL,session TEXT NOT NULL,expires_at INTEGER NOT NULL)")
    }
    fun permissionGranted(capability: String) = permissionCheck(capability)
    internal fun networkGrantedOwners(): Set<String> = readableDatabase.rawQuery(
        "SELECT g.owner FROM capability_grants g JOIN tools t ON t.id=g.owner WHERE g.capability=? AND g.granted=1 AND t.saved=1",
        arrayOf(NETWORK_GRANT)).use { rows -> buildSet { while(rows.moveToNext()) add(rows.getString(0)) } }

    internal fun networkGrantEntries(): List<ToolNetworkGrantEntry> = readableDatabase.rawQuery(
        "SELECT t.id,t.spec,COALESCE(g.granted,0) FROM tools t LEFT JOIN capability_grants g ON g.owner=t.id AND g.capability=? WHERE t.saved=1 ORDER BY t.rowid DESC",
        arrayOf(NETWORK_GRANT)).use { rows ->
        buildList { while(rows.moveToNext()) add(ToolNetworkGrantEntry(rows.getString(0),rows.getString(1),rows.getInt(2)==1)) }
    }

    internal fun networkGrantEnabled(owner: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM capability_grants WHERE owner=? AND capability=? AND granted=1",arrayOf(owner,NETWORK_GRANT)).use {it.moveToFirst()}

    internal fun setNetworkGrant(owner: String, enabled: Boolean) = transaction {
        check(tool(owner)?.saved == true && !isDeleted(owner)) { "The saved mini-app is unavailable." }
        if(enabled) put("capability_grants",ContentValues().apply {
            put("owner",owner); put("capability",NETWORK_GRANT); put("granted",1)
        }) else {
            writableDatabase.delete("capability_grants","owner=? AND capability=?",arrayOf(owner,NETWORK_GRANT))
            SourceRefresh.stop(this,owner)
        }
    }

    fun privateResults(owner: String) = readableDatabase.rawQuery("SELECT 1 FROM capability_private_owners WHERE owner=?",arrayOf(owner)).use { it.moveToFirst() }
    private fun retainResultPrivacy(tool: ToolRecord) {
        if(ToolSpec.availablePersisted(tool.spec)?.let { it.capabilityResults || it.collections.length() > 0 || it.capabilities.any { id -> id in NavigationContract.ids || id in OwnedCalendarContract.ids } }==true) writableDatabase.insertWithOnConflict("capability_private_owners",null,
            ContentValues().apply { put("owner",tool.id) },SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun android.database.Cursor.toolRecord() = ToolRecord(getString(0),getInt(1),getInt(2),getString(3),
        getString(4),getString(5),getString(6),getInt(7)!=0,getInt(8)!=0,getLong(9),getString(10),getInt(11)!=0)
    fun tools(): List<ToolRecord> = readableDatabase.rawQuery("SELECT $TOOL_COLUMNS FROM tools ORDER BY rowid DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(c.toolRecord()) }
    }
    fun tool(id: String): ToolRecord? = readableDatabase.rawQuery("SELECT $TOOL_COLUMNS FROM tools WHERE id=?",arrayOf(id)).use {
        if(it.moveToFirst()) it.toolRecord() else null
    }
    private fun android.database.Cursor.toolDraft() = ToolDraft(getString(0), getString(1), getInt(2),
        getInt(3), getString(4), getString(5), getString(6), getString(7), getString(8))
    fun drafts(): List<ToolDraft> = readableDatabase.rawQuery("SELECT * FROM drafts WHERE base_revision>=0 ORDER BY rowid DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(c.toolDraft()) }
    }
    fun draft(id: String): ToolDraft? = readableDatabase.rawQuery("SELECT * FROM drafts WHERE id=?",arrayOf(id)).use { c ->
        if(c.moveToFirst()) c.toolDraft() else null
    }
    fun revisions(id: String): List<ToolRevision> = readableDatabase.rawQuery(
        "SELECT revision,spec,state,ui,label,created_at,stopped_automations FROM revisions WHERE tool_id=? ORDER BY revision DESC",arrayOf(id)
    ).use { c -> buildList { while(c.moveToNext()) {
        val stopped=JSONArray(c.getString(6))
        add(ToolRevision(c.getInt(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),
            (0 until stopped.length()).map { stopped.getJSONObject(it).let { row -> StoppedAutomation(row.getString("id"),row.getString("name")) } }))
    } } }
    fun saveDraft(d: ToolDraft) = transaction {
        check(d.toolId?.let { tool(it)?.archived == false } ?: !isDeleted(d.id)) { "The mini-app is unavailable." }
        put("drafts", ContentValues().apply {
            put("id", d.id); put("tool_id", d.toolId); put("base_revision", d.baseRevision); put("base_state_version", d.baseStateVersion)
            put("prompt", d.prompt); put("candidate", d.candidate); put("preview_state", d.previewState); put("preview_ui", d.previewUi); put("conversation_id", d.conversationId)
        })
    }
    fun deleteDraft(id: String) = transaction { writableDatabase.delete("drafts","id=?",arrayOf(id)) }
    private fun toolValues(t: ToolRecord) = ContentValues().apply {
        put("id",t.id); put("revision",t.revision); put("state_version",t.stateVersion)
        put("spec",t.spec); put("state",t.state); put("ui",t.ui)
        put("conversation_id",t.conversationId); put("saved",if(t.saved) 1 else 0)
        put("archived",if(t.archived) 1 else 0); put("created_at",t.createdAt)
        put("last_conversation_id",t.lastConversationId); put("on_desktop",if(t.onDesktop) 1 else 0)
    }
    private fun revisionValues(t: ToolRecord,label: String,stopped: List<StoppedAutomation>) = ContentValues().apply {
        put("tool_id",t.id); put("revision",t.revision); put("spec",t.spec); put("state",t.state); put("ui",t.ui)
        put("label",label); put("created_at",if(t.revision==1) t.createdAt else System.currentTimeMillis())
        put("stopped_automations",JSONArray(stopped.map { JSONObject().put("id",it.id).put("name",it.name) }).toString())
    }
    fun saveTool(t: ToolRecord, revision: Boolean) = transaction {
        check(!isDeleted(t.id)) { "The mini-app was deleted." }
        val old=tool(t.id)
        ToolRecords(this).beforeCommit(t,old,null)
        retainResultPrivacy(t)
        put("tools",toolValues(t))
        if(revision) put("revisions",revisionValues(t,if(t.revision==1) "Создание" else "Изменение",emptyList()))
        if(old!=null && (old.revision!=t.revision || old.archived!=t.archived || old.saved!=t.saved)) {
            invalidateAppSchedulesAndGrants(t.id)
        }
    }
    /** SQLite tokens protect writes across foreground and widget engine instances. */
    fun commitTool(t: ToolRecord, old: ToolRecord?, revision: Boolean, draft: ToolDraft? = null, effects: List<CapabilityRequest> = emptyList(), retainReceipt: Boolean = false, recordDeliveryId: String? = null, capabilityDeliveryId: String? = null, autonomous: Boolean = false,
        revisionLabel: String = if(t.revision==1) "Создание" else "Изменение",
        stoppedAutomations: List<StoppedAutomation> = emptyList()) = transaction {
        check(!isDeleted(t.id) && (old == null || tool(old.id) == old)) { "Инструмент изменился. Открой текущую версию." }
        check(!t.archived && old?.archived != true) { "Инструмент в архиве. Сначала восстанови его." }
        check(effects.none {it.capability==NetworkEffectContract.ID} || effects.size==1)
        if(autonomous) {
            check(this.effects(t.id).none {it.phase!="terminal"}) {"A native action must finish before another state change."}
            check(deliveries(t.id).none {it.phase in setOf("pending","claimed") && it.id!=capabilityDeliveryId}) {"A native result must finish before another state change."}
        }
        ToolRecords(this).beforeCommit(t,old,recordDeliveryId)
        retainResultPrivacy(t)
        if (draft != null) check(draft(draft.id) == draft) { "Черновик изменился" }
        val values=toolValues(t)
        if (old == null) writableDatabase.insertOrThrow("tools",null,values)
        else check(writableDatabase.update("tools",values,"id=?",arrayOf(old.id)) == 1)
        if(revision) writableDatabase.insertOrThrow("revisions",null,revisionValues(t,revisionLabel,stoppedAutomations))
        if(effects.isNotEmpty() && ToolSpec.parsePersisted(t.spec).capabilityResults) {
            check(effects.size <= 3)
            writableDatabase.insertOrThrow("capability_deliveries",null,ContentValues().apply {
                put("delivery_id",java.util.UUID.randomUUID().toString()); put("owner",t.id); put("revision",t.revision)
                put("state_version",t.stateVersion); put("phase","pending"); put("attempts",0)
            })
        }
        effects.forEach { request ->
            val receipt=if(request.capability==NetworkEffectContract.ID) NetworkEffectContract.receipt(request) else request
            val key = "${t.id}:${t.revision}:${t.stateVersion}:${receipt.id}"
            check(writableDatabase.insertOrThrow("capability_effects",null,ContentValues().apply {
                put("effect_key",key); put("owner",t.id); put("revision",t.revision); put("state_version",t.stateVersion)
                put("request",receipt.json().toString()); put("created_at",System.currentTimeMillis()); put("phase","pending"); put("session",processSession)
                put("result",CapabilityResult("awaitingUser","Системное действие ещё не запущено.").json().toString())
            }) != -1L)
        }
        if (draft != null) {
            if(retainReceipt) saveDraft(draft.copy(toolId = t.id,baseRevision = -t.revision,baseStateVersion = t.stateVersion))
            else deleteDraft(draft.id)
        }
        if(old!=null && (old.revision!=t.revision || old.saved!=t.saved)) {
            invalidateAppSchedulesAndGrants(t.id)
        }
    }
    fun effects(owner: String): List<CapabilityEffect> = readableDatabase.rawQuery(
        "SELECT effect_key,owner,revision,state_version,request,result,phase FROM capability_effects WHERE owner=? AND phase<>'native_timer' ORDER BY created_at,rowid",arrayOf(owner)).use { c ->
        buildList { while(c.moveToNext()) {
            val request=JSONObject(c.getString(4))
            val effect=CapabilityEffect(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),
                if(request.optString("capability")==NetworkEffectContract.ID) NetworkEffectContract.readReceipt(request) else CapabilityCatalog.request(request),
                CapabilityResult.parse(c.getString(5)),c.getString(6))
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
    fun finishEffect(key: String, result: CapabilityResult, canFinish: () -> Boolean = { true }) {
        val permission=effect(key)?.let { it.request.capability in DeviceReadContract.ids || permissionGranted(it.request.capability) } ?: false
        transaction {
        val effect=effect(key) ?: return@transaction
        if(effect.phase != "running" || !effectSessionCurrent(key)) return@transaction
        val phase=when {
            result.status == "awaitingUser" && result.data.optBoolean("launched") -> "waiting"
            result.status == "awaitingUser" -> "pending"
            else -> "terminal"
        }
        val calendarWrite=effect.phase=="running" && effect.request.capability in setOf("calendar.createOwned","calendar.deleteOwned","calendar.deleteAllOwned")
        val current=effectTargetMatches(effect)
        val capability=effect.request.capability
        val safe=when {
            capability==NetworkEffectContract.ID -> when {
                result.status!="unknownOutcome" && (!current || !canFinish()) -> NetworkEffectContract.receiptResult("cancelled")
                result.status !in NetworkEffectContract.statuses -> NetworkEffectContract.receiptResult("failed")
                else -> result
            }
            calendarWrite && result.status in setOf("succeeded","unknownOutcome") && (!current || !permission) ->
                CapabilityResult("unknownOutcome","Calendar access changed after native execution started. The provider outcome remains uncertain. No insert will repeat automatically.")
            !current -> CapabilityResult("cancelled","The tool changed before the result returned.")
            capability in ForegroundCapabilityEffects.executionIds && (!canFinish() || result.status=="cancelled") ->
                ForegroundCapabilityEffects.cancelled(capability)
            capability in DeviceReadContract.ids && !permissionGranted(capability) -> DeviceReadContract.denied(capability)
            !permission -> CapabilityResult("denied","Android не разрешил действие. Разрешите доступ в настройках Android и повторите.")
            capability in selectedMediaPlayback && result.status=="succeeded" && !mediaSourceAvailable(effect) ->
                if(capability==VideoContract.PLAY) VideoContract.unavailable() else AudioContract.unavailable()
            phase == "terminal" -> boundedResult(effect,result)
            else -> result
        }
        updateEffect(key,safe,if(safe.status == "awaitingUser") phase else "terminal")
        }
    }
    private fun boundedResult(effect: CapabilityEffect, result: CapabilityResult): CapabilityResult {
        if(effect.request.capability !in ForegroundCapabilityEffects.deliveryIds && tool(effect.owner)?.let { ToolSpec.availablePersisted(it.spec)?.capabilityResults } != true) return result
        return try {
            CapabilityResultEvent.data(effect.request.capability,result)
            if(effect.request.capability==DeviceReadContract.SENSOR && result.status=="succeeded")
                require(result.data.getString("sensor")==effect.request.args.getString("sensor"))
            result
        } catch(_: Exception) { CapabilityResult("failed","The native result did not satisfy its bounded data contract.") }
    }
    private fun updateEffect(key: String, result: CapabilityResult, phase: String) {
        val receipt=if(phase=="terminal") effect(key) else null
        val saved=when {
            phase=="terminal" && receipt?.request?.capability==NetworkEffectContract.ID -> NetworkEffectContract.durableResult(result)
            phase=="terminal" && result.status!="succeeded" && receipt!=null -> OwnedCalendarLedger(this).interruptedResult(receipt,result.message) ?: result
            else -> result
        }
        check(writableDatabase.update("capability_effects",ContentValues().apply {
            put("result",saved.json().toString()); put("phase",phase); put("session",processSession)
        },"effect_key=?",arrayOf(key)) == 1)
    }
    private fun effectSessionCurrent(key: String): Boolean = readableDatabase.rawQuery(
        "SELECT session FROM capability_effects WHERE effect_key=?",arrayOf(key)).use { it.moveToFirst() && it.getString(0)==processSession }

    fun effectTargetValid(effect: CapabilityEffect) = effectTargetMatches(effect) && effectSessionCurrent(effect.key)

    private fun effectTargetMatches(effect: CapabilityEffect): Boolean {
        val target=tool(effect.owner) ?: return false
        return !target.archived && target.revision == effect.revision && target.stateVersion == effect.stateVersion &&
            effect.request.capability in ToolSpec.availablePersisted(target.spec)?.capabilities.orEmpty()
    }

    internal fun rejectPendingEffect(key: String, result: CapabilityResult): CapabilityResult? {
        require(result.status != "awaitingUser")
        var final: CapabilityResult? = null
        transaction {
            val effect = effect(key) ?: return@transaction
            if (effect.phase == "pending") {
                if(effectSessionCurrent(key)) updateEffect(key,result,"terminal") else retireOldPending(effect)
            }
            final = effect(key)?.result
        }
        return final
    }

    private fun retireOldPending(effect: CapabilityEffect) {
        updateEffect(effect.key,CapabilityResult("cancelled","Действие из предыдущего сеанса отменено. При необходимости повторите его в мини-приложении."),"terminal")
        deliveries(effect.owner).filter { it.revision==effect.revision && it.stateVersion==effect.stateVersion && it.phase in setOf("pending","claimed") }
            .forEach { rejectDelivery(it.id) }
    }
    /** Persist the running phase before the adapter starts. A crash never authorizes an automatic retry. */
    fun claimEffect(key: String): CapabilityEffect? {
        var claimed: CapabilityEffect? = null
        transaction {
            val effect = effect(key) ?: return@transaction
            if(effect.phase != "pending") return@transaction
            if(!effectSessionCurrent(key)) {
                retireOldPending(effect)
                return@transaction
            }
            val tool = tool(effect.owner)
            val result = when {
                tool == null || tool.archived || tool.revision != effect.revision || tool.stateVersion != effect.stateVersion -> CapabilityResult("cancelled","Инструмент изменился. Повтори действие в текущей версии.")
                ToolSpec.availablePersisted(tool.spec)?.capabilities?.contains(effect.request.capability)!=true -> CapabilityResult("denied","Инструмент не объявил эту системную функцию.")
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
            if(effect.phase != "waiting" || !effectSessionCurrent(key)) return@transaction
            val safe=when {
                !effectTargetMatches(effect) -> CapabilityResult("cancelled","Инструмент изменился до возврата результата Android. Результат не применён.")
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
        val interrupted=readableDatabase.rawQuery("SELECT effect_key,phase FROM capability_effects WHERE phase IN ('pending','running','waiting')"+where,args).use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        interrupted.forEach { (key,phase) ->
            if(phase=="pending") effect(key)?.let(::retireOldPending)
            else {
                val interruptedEffect=effect(key)
                if(interruptedEffect!=null && interruptedEffect.request.capability in ForegroundCapabilityEffects.deliveryIds) {
                    val capability=interruptedEffect.request.capability
                    val result=if(capability==NetworkEffectContract.ID && phase=="running" &&
                        !com.alpha.assistant.network.NetworkMethod.valueOf(interruptedEffect.request.args.getString("method")).safe)
                        NetworkEffectContract.receiptResult("unknownOutcome") else ForegroundCapabilityEffects.cancelled(capability)
                    updateEffect(key,result,"terminal")
                    rejectForegroundEffectDelivery(interruptedEffect.owner,interruptedEffect.revision,interruptedEffect.stateVersion)
                } else updateEffect(key,CapabilityResult(if(phase == "running") "unknownOutcome" else "unavailable",
                    if(phase == "running") "The interrupted native action has an unknown outcome. It will not repeat." else "The interrupted selection is unavailable. Select it again."),"terminal")
            }
        }
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
    // An empty failure can tell the app that access failed. Private readings still require Android access at delivery.
    private fun deviceReadFailure(effect: CapabilityEffect) = effect.request.capability in DeviceReadContract.ids &&
        effect.result.status != "succeeded" && effect.result.data.length() == 0
    private val selectedMediaPlayback = setOf(AudioContract.PLAY,VideoContract.PLAY)
    private fun mediaSourceAvailable(effect: CapabilityEffect) = runCatching {
        val resources=CapabilityResources(context,this)
        val handle=effect.request.args.getString("resourceId")
        if(effect.request.capability==VideoContract.PLAY) resources.resolveVideo(effect.owner,handle) else resources.resolveAudio(effect.owner,handle)
    }.isSuccess
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
        val spec=ToolSpec.availablePersisted(target.spec) ?: return false
        val batch=effects(delivery.owner).filter { it.revision == delivery.revision && it.stateVersion == delivery.stateVersion }
        return spec.capabilityResults && batch.size in 1..3 && batch.all {
            it.phase == "terminal" && it.request.capability in spec.capabilities && (deviceReadFailure(it) || it.request.capability in access.capabilities) &&
                (it.request.capability !in selectedMediaPlayback || it.result.status!="succeeded" || it.request.args.getString("resourceId") in access.resources) &&
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
            (deviceReadFailure(effect) || permissionCheck(effect.request.capability)) &&
                (effect.request.capability !in selectedMediaPlayback || effect.result.status!="succeeded" || mediaSourceAvailable(effect)) && (!effect.result.data.has("resourceId") ||
                resource(effect.result.data.getString("resourceId"))?.let(manager::permissionAvailable) == true)
        }
    }
    fun claimDelivery(owner: String, permissionCheck: (String)->Boolean): CapabilityDelivery? = claimDelivery(owner,null,permissionCheck=permissionCheck)

    internal fun claimDelivery(owner: String, foregroundEffect: ToolRecord?, networkEffectKey: String? = null,
        permissionCheck: (String)->Boolean): CapabilityDelivery? {
        var claimed: CapabilityDelivery?=null
        val access=resultAccess(owner,permissionCheck)
        transaction {
            for(delivery in deliveries(owner).filter { it.phase == "pending" }) {
                val target=tool(owner)
                if(target == null || target.archived || target.revision != delivery.revision || target.stateVersion != delivery.stateVersion || ToolSpec.availablePersisted(target.spec)?.capabilityResults!=true) {
                    rejectDelivery(delivery.id); continue
                }
                val batch=effects(owner).filter { it.revision == delivery.revision && it.stateVersion == delivery.stateVersion }
                val foreground=batch.filter { it.request.capability in ForegroundCapabilityEffects.deliveryIds }
                if(foreground.isNotEmpty()) {
                    if(foreground.any { !effectSessionCurrent(it.key) }) { rejectDelivery(delivery.id); continue }
                    if(foregroundEffect?.revision != delivery.revision || foregroundEffect.stateVersion != delivery.stateVersion) continue
                } else if(foregroundEffect != null) continue
                val networkEffect=batch.singleOrNull()?.takeIf {it.request.capability==NetworkEffectContract.ID}
                if(networkEffect?.key!=networkEffectKey) continue
                if(batch.any { it.phase != "terminal" }) continue
                if(delivery.attempts >= 2 || !deliveryValid(delivery,access)) { rejectDelivery(delivery.id); continue }
                val event=if(networkEffect!=null) null else try { CapabilityResultEvent.event(delivery.id,batch).toString() }
                    catch(_: Exception) { rejectDelivery(delivery.id); continue }
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
    internal fun rejectForegroundEffectDelivery(owner: String, revision: Int, stateVersion: Int) {
        writableDatabase.update("capability_deliveries",ContentValues().apply { put("phase","rejected") },
            "owner=? AND revision=? AND state_version=? AND phase IN ('pending','claimed')",arrayOf(owner,revision.toString(),stateVersion.toString()))
    }
    fun commitDelivery(delivery: CapabilityDelivery, next: ToolRecord, canCommit: ()->Boolean = { true },
        authorizePublication: ()->Boolean = { true }, permissionCheck: (String)->Boolean): Boolean {
        var committed=false
        val access=resultAccess(delivery.owner,permissionCheck)
        transaction {
            val saved=deliveries(delivery.owner).find { it.id == delivery.id } ?: return@transaction
            if(saved != delivery || saved.phase != "claimed") return@transaction
            if(!canCommit() || !deliveryValid(delivery,access) || !immediateResultAccess(delivery,permissionCheck)) { rejectDelivery(delivery.id); return@transaction }
            val old=checkNotNull(tool(delivery.owner))
            require(next == old.copy(stateVersion=old.stateVersion+1,state=next.state,ui=next.ui))
            val spec=ToolSpec.parsePersisted(old.spec); spec.validateState(next.state); spec.validateUi(JSONObject(next.ui),JSONObject(next.state))
            // Android permission changes cannot be atomic with SQLite. Check immediately after the final cancellation guard.
            if(!canCommit() || !deliveryValid(delivery,access) || !immediateResultAccess(delivery,permissionCheck)) { rejectDelivery(delivery.id); return@transaction }
            if(!authorizePublication()) { rejectDelivery(delivery.id); return@transaction }
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
        check(!isDeleted(resource.owner)) { "The mini-app was deleted." }
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
    private fun invalidateAppSchedulesAndGrants(owner: String) {
        ToolNetworkGrants.invalidateApp(this, owner)
        SourceRefresh.stop(this, owner)
        RecurringSearch.stop(this, owner)
    }

    private val ownedTables = listOf(
        "revisions" to "tool_id=?", "drafts" to "(tool_id=? OR (tool_id IS NULL AND id=?))",
        "capability_grants" to "owner=?", "capability_effects" to "owner=?", "capability_deliveries" to "owner=?",
        "capability_private_owners" to "owner=?", "artifact_collections" to "owner=?", "artifact_records" to "owner=?",
        "artifact_cursors" to "owner=?", "artifact_record_deliveries" to "owner=?", "maps_stops" to "owner=?",
        "maps_reached" to "owner=?", "owned_calendar_selection" to "owner=?", "owned_calendar_events" to "owner=?",
        "owned_calendar_delete_items" to "effect_key IN (SELECT effect_key FROM owned_calendar_delete_batches WHERE owner=?)",
        "owned_calendar_delete_batches" to "owner=?"
    )
    private fun ownerArgs(table: String,owner: String) = if(table=="drafts") arrayOf(owner,owner) else arrayOf(owner)
    private fun ownershipTables(db: SQLiteDatabase) = if (WidgetLayoutStore.exists(db)) ownedTables + ("widget_layouts" to "owner=?") else ownedTables
    internal fun deletionFingerprint(owner: String): String = ownerFingerprint(readableDatabase,owner,
        ownershipTables(readableDatabase) + listOf("capability_resources" to "owner=?","capability_acquisitions" to "owner=?"),::ownerArgs)
    internal fun isDeleted(owner: String) = readableDatabase.rawQuery("SELECT 1 FROM deleted_tools WHERE id=?",arrayOf(owner)).use { it.moveToFirst() }
    internal fun beginDeletion(snapshot: ToolDeletionSnapshot) {
        val owner=snapshot.tool.id
        invalidateAppSchedulesAndGrants(owner)
        writableDatabase.delete("live_sources","owner=?",arrayOf(owner))
        writableDatabase.insertOrThrow("deleted_tools",null,ContentValues().apply {
            put("id",owner);put("cleanup_pending",1);put("widget_ids",JSONArray(snapshot.widgetIds.toList()).toString())
        })
        ownershipTables(writableDatabase).forEach { (table,where) -> writableDatabase.delete(table,where,ownerArgs(table,owner)) }
        check(writableDatabase.delete("tools","id=?",arrayOf(owner)) == 1)
    }
    internal fun pendingDeletions() = readableDatabase.rawQuery("SELECT id,widget_ids FROM deleted_tools WHERE cleanup_pending=1",null).use { c ->
        buildMap { while(c.moveToNext()) {
            val widgets=JSONArray(c.getString(1))
            put(c.getString(0),(0 until widgets.length()).map(widgets::getInt).toSet())
        } }
    }
    internal fun completeDeletion(owner: String) = transaction {
        writableDatabase.update("deleted_tools",ContentValues().apply { put("cleanup_pending",0);put("widget_ids","[]") },"id=?",arrayOf(owner))
    }
    fun setDesktopPlacement(expected: ToolRecord,placed: Boolean): ToolRecord = updatePlacement(expected,"on_desktop",if(placed) 1 else 0)
    fun recordLastConversation(expected: ToolRecord,conversationId: String): ToolRecord = updatePlacement(expected,"last_conversation_id",conversationId)
    private fun updatePlacement(expected: ToolRecord,column: String,value: Any): ToolRecord {
        transaction {
            check(tool(expected.id)==expected && expected.saved && !expected.archived) { "Инструмент изменился. Открой текущую версию." }
            writableDatabase.execSQL("UPDATE tools SET $column=? WHERE id=?",arrayOf(value,expected.id))
        }
        return checkNotNull(tool(expected.id))
    }
    fun markSaved(id: String) = transaction {
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
        invalidateAppSchedulesAndGrants(expected.id)
        if(archived) NavigationLedger(this).revoke(expected.id)
        if(archived) ToolRecords(this).pending(expected.id)?.let { ToolRecords(this).reject(it) }
        if(archived) effects(expected.id).filter {it.phase != "terminal"}.forEach {
            val uncertain=it.phase=="running" && it.request.capability in setOf("calendar.createOwned","calendar.deleteOwned","calendar.deleteAllOwned")
            updateEffect(it.key,if(uncertain) CapabilityResult("unknownOutcome","The app was archived during calendar execution. The event ledger remains saved and the provider outcome is uncertain.") else CapabilityResult("cancelled","Инструмент в архиве. Ожидающее действие отменено."),"terminal")
        }
    }
    fun transaction(block: () -> Unit) = inTransaction(block)

    internal fun <T> inTransaction(block: () -> T): T {
        val db=writableDatabase
        val outer=!db.inTransaction()
        db.beginTransaction()
        val result = try {
            val value = block()
            db.setTransactionSuccessful()
            value
        } finally {
            try {
                db.endTransaction()
            } catch(failure: SQLiteException) {
                // A failed outer COMMIT can leave a write lock on the connection.
                if(outer) close()
                throw failure
            }
        }
        if(outer) changeCounter.update { it+1 }
        return result
    }
    private fun put(table: String, values: ContentValues) { check(writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Не удалось сохранить инструмент" } }
}

internal fun ownerFingerprint(db: SQLiteDatabase,owner: String,tables: List<Pair<String,String>>,
    args: (String,String)->Array<String> = { _,id -> arrayOf(id) }): String {
    val digest=java.security.MessageDigest.getInstance("SHA-256")
    tables.forEach { (table,where) ->
        digest.update(table.toByteArray())
        db.rawQuery("SELECT * FROM $table WHERE $where ORDER BY rowid",args(table,owner)).use { c ->
            while(c.moveToNext()) {
                val row=JSONArray()
                for(index in 0 until c.columnCount) row.put(if(c.isNull(index)) JSONObject.NULL else c.getString(index))
                digest.update(row.toString().toByteArray())
            }
        }
    }
    return android.util.Base64.encodeToString(digest.digest(),android.util.Base64.NO_WRAP)
}
