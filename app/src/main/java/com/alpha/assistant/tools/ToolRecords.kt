package com.alpha.assistant.tools

import android.content.ContentValues
import com.alpha.assistant.sandbox.SandboxContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RecordDelivery(val id: String, val owner: String, val revision: Int, val stateVersion: Int,
    val event: String, val attempts: Int)

/** SQLite owns record identities, tokens, cursors, and receipts. JavaScript supplies only bounded requests. */
class ToolRecords(private val store: ToolStore) {
    companion object {
        private fun token() = UUID.randomUUID().toString().replace("-","") + UUID.randomUUID().toString().replace("-","")
        private fun keys(json: JSONObject) = ToolValueSchema.keys(json)
        private fun exact(json: JSONObject, fields: Set<String>) = require(keys(json) == fields) { "Invalid record operation fields." }
        private fun text(json: JSONObject, field: String, max: Int): String {
            val value=json.get(field); require(value is String && value.isNotBlank() && value.length <= max)
            return value
        }
        fun validateOperations(operations: JSONArray) {
            require(operations.length() <= 8)
            val ids=mutableSetOf<String>(); var pages=0
            repeat(operations.length()) {
                val op=operations.getJSONObject(it)
                val id=text(op,"id",48); require(ToolValueSchema.name(id) && ids.add(id))
                require(ToolValueSchema.name(text(op,"collection",48)))
                val base=setOf("id","collection","op")
                when(op.getString("op")) {
                    "put" -> { exact(op,base+setOf("key","expectedToken","value")); text(op,"key",128); op.get("value") }
                    "delete" -> { exact(op,base+setOf("key","expectedToken")); text(op,"key",128); require(op.get("expectedToken") is String) }
                    "get" -> { exact(op,base+"key"); text(op,"key",128) }
                    "page" -> {
                        exact(op,base+setOf("order","limit","cursor")); require(++pages <= 1)
                        require(op.get("order") in setOf("asc","desc"))
                        require(op.get("limit") is Int && op.getInt("limit") in 1..50)
                        require(op.isNull("cursor") || op.get("cursor") is String && op.getString("cursor").matches(Regex("[0-9a-f]{64}")))
                    }
                    else -> error("Unknown record operation.")
                }
                if(op.has("expectedToken")) require(op.isNull("expectedToken") || op.get("expectedToken") is String && op.getString("expectedToken").matches(Regex("[0-9a-f]{64}")))
            }
        }
        fun validateEvent(event: JSONObject) {
            exact(event,setOf("type","deliveryId","entries"))
            require(event.getString("type") == "recordResults" && event.getString("deliveryId").matches(Regex("[0-9a-f-]{36}")))
            require(event.toString().toByteArray().size <= 15360)
            val entries=event.getJSONArray("entries"); require(entries.length() in 1..8)
            val ids=mutableSetOf<String>()
            repeat(entries.length()) {
                val entry=entries.getJSONObject(it)
                val base=setOf("requestId","collection","op","status")
                require(ToolValueSchema.name(text(entry,"requestId",48)) && ids.add(entry.getString("requestId")))
                require(ToolValueSchema.name(text(entry,"collection",48)))
                val fields=when(entry.getString("op")) {
                    "put" -> { require(entry.getString("status") == "succeeded"); base+"record" }
                    "delete" -> { require(entry.getString("status") == "succeeded"); base }
                    "get" -> when(entry.getString("status")) { "succeeded" -> base+"record"; "notFound" -> base; else -> error("Invalid record status.") }
                    "page" -> when(entry.getString("status")) { "succeeded" -> base+setOf("records","cursor"); "staleCursor" -> base; else -> error("Invalid page status.") }
                    else -> error("Invalid record result operation.")
                }
                exact(entry,fields)
                fun record(record: JSONObject) {
                    exact(record,setOf("key","token","value")); text(record,"key",128)
                    require(record.getString("token").matches(Regex("[0-9a-f]{64}")))
                    require(record.toString().toByteArray().size <= 8192)
                }
                if(entry.has("record")) record(entry.getJSONObject("record"))
                if(entry.has("records")) {
                    val records=entry.getJSONArray("records"); require(records.length() <= 50)
                    repeat(records.length()) { index -> record(records.getJSONObject(index)) }
                    require(entry.toString().toByteArray().size <= 8192)
                }
                if(entry.has("cursor")) require(entry.isNull("cursor") || entry.getString("cursor").matches(Regex("[0-9a-f]{64}")))
            }
        }
        internal fun createTables(db: android.database.sqlite.SQLiteDatabase) {
            db.execSQL("CREATE TABLE artifact_collections(owner TEXT NOT NULL,name TEXT NOT NULL,generation INTEGER NOT NULL,PRIMARY KEY(owner,name))")
            db.execSQL("CREATE TABLE artifact_records(owner TEXT NOT NULL,collection TEXT NOT NULL,record_key TEXT NOT NULL,token TEXT NOT NULL,value TEXT NOT NULL,size_bytes INTEGER NOT NULL,PRIMARY KEY(owner,collection,record_key))")
            db.execSQL("CREATE TABLE artifact_cursors(cursor TEXT PRIMARY KEY,owner TEXT NOT NULL,collection TEXT NOT NULL,ordering TEXT NOT NULL,position TEXT NOT NULL,generation INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE artifact_record_deliveries(delivery_id TEXT PRIMARY KEY,owner TEXT NOT NULL,revision INTEGER NOT NULL,state_version INTEGER NOT NULL,event TEXT NOT NULL,phase TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,UNIQUE(owner,revision,state_version))")
        }
    }
    private val db get() = store.writableDatabase
    internal fun ensureCollections(tool: ToolRecord) {
        val spec=ToolSpec.parse(tool.spec)
        spec.collections.keys().forEach { name ->
            db.insertWithOnConflict("artifact_collections",null,ContentValues().apply { put("owner",tool.id); put("name",name); put("generation",0) },android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        }
    }
    fun pending(owner: String): RecordDelivery? = db.rawQuery("SELECT delivery_id,owner,revision,state_version,event,attempts FROM artifact_record_deliveries WHERE owner=? AND phase='pending' ORDER BY rowid LIMIT 1",arrayOf(owner)).use { c ->
        if(c.moveToFirst()) RecordDelivery(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),c.getString(4),c.getInt(5)) else null
    }
    fun pendingOwners(): List<String> = db.rawQuery("SELECT DISTINCT owner FROM artifact_record_deliveries WHERE phase='pending'",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun beforeCommit(next: ToolRecord, old: ToolRecord?, deliveryId: String?) {
        val pending=pending(next.id)
        check(pending == null || pending.id == deliveryId) { "A local record result must finish before another change." }
        if(old != null) {
            val previous=ToolSpec.parse(old.spec); val candidate=ToolSpec.parse(next.spec)
            previous.collections.keys().forEach {
                require(candidate.collections.has(it) && ToolValueSchema.same(previous.collections.get(it),candidate.collections.get(it))) { "Existing collections cannot be removed or retyped." }
            }
        }
        ensureCollections(next)
    }
    private fun record(owner: String, collection: String, key: String): JSONObject? = db.rawQuery("SELECT token,value FROM artifact_records WHERE owner=? AND collection=? AND record_key=?",arrayOf(owner,collection,key)).use { c ->
        if(c.moveToFirst()) JSONObject().put("key",key).put("token",c.getString(0)).put("value",org.json.JSONTokener(c.getString(1)).nextValue()) else null
    }
    private fun generation(owner: String, collection: String): Long = db.rawQuery("SELECT generation FROM artifact_collections WHERE owner=? AND name=?",arrayOf(owner,collection)).use { c -> check(c.moveToFirst()); c.getLong(0) }
    /** State, all writes, bounded query results, and one receipt have the same transaction and CAS guard. */
    fun commitAction(old: ToolRecord, next: ToolRecord, operations: JSONArray, canCommit: ()->Boolean = { true }): RecordDelivery {
        validateOperations(operations); require(operations.length() in 1..8)
        require(next == old.copy(stateVersion=old.stateVersion+1,state=next.state,ui=next.ui))
        val spec=ToolSpec.parse(old.spec); spec.validateState(next.state); spec.validateUi(JSONObject(next.ui),JSONObject(next.state))
        var receipt: RecordDelivery?=null
        store.transaction {
            check(canCommit()) { "The action was canceled." }
            check(store.tool(old.id) == old && pending(old.id) == null) { "The tool changed before the record action committed." }
            check(store.deliveries(old.id).none { it.phase in setOf("pending","claimed") }) { "A native result is still pending." }
            ensureCollections(old)
            val entries=JSONArray()
            repeat(operations.length()) {
                val operation=operations.getJSONObject(it); val collection=operation.getString("collection")
                require(spec.collections.has(collection)) { "The collection is not declared by this artifact." }
                val entry=JSONObject().put("requestId",operation.getString("id")).put("collection",collection).put("op",operation.getString("op")).put("status","succeeded")
                when(operation.getString("op")) {
                    "put", "delete" -> {
                        val key=operation.getString("key"); val existing=record(old.id,collection,key)
                        val expected=operation.get("expectedToken")
                        require(if(existing == null) expected == JSONObject.NULL && operation.getString("op") == "put" else expected == existing.getString("token")) { "The record token is stale or belongs to another artifact." }
                        if(operation.getString("op") == "put") {
                            val value=operation.get("value"); ToolValueSchema.validate(spec.collections.get(collection),value)
                            val result=JSONObject().put("key",key).put("token",token()).put("value",value)
                            val size=result.toString().toByteArray().size; require(size <= 8192) { "The record exceeds 8 KiB." }
                            db.insertWithOnConflict("artifact_records",null,ContentValues().apply {
                                put("owner",old.id); put("collection",collection); put("record_key",key); put("token",result.getString("token"))
                                put("value",JSONArray().put(value).toString().let { it.substring(1,it.length-1) }); put("size_bytes",size)
                            },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
                            entry.put("record",result)
                        } else db.delete("artifact_records","owner=? AND collection=? AND record_key=?",arrayOf(old.id,collection,key))
                        db.execSQL("UPDATE artifact_collections SET generation=generation+1 WHERE owner=? AND name=?",arrayOf(old.id,collection))
                        db.delete("artifact_cursors","owner=? AND collection=?",arrayOf(old.id,collection))
                        quotas(old.id,collection)
                    }
                    "get" -> record(old.id,collection,operation.getString("key"))?.let { entry.put("record",it) } ?: entry.put("status","notFound")
                    "page" -> page(old.id,collection,operation,entry)
                }
                entries.put(entry)
            }
            val id=UUID.randomUUID().toString()
            val event=JSONObject().put("type","recordResults").put("deliveryId",id).put("entries",entries)
            validateEvent(event)
            SandboxContract.validateRequest(spec.source,JSONObject().put("artifactId",old.id).put("revision",old.revision).put("state",JSONObject(next.state)).put("event",event).toString())
            check(canCommit()) { "The action was canceled." }
            store.commitTool(next,old,false)
            db.insertOrThrow("artifact_record_deliveries",null,ContentValues().apply {
                put("delivery_id",id); put("owner",old.id); put("revision",old.revision); put("state_version",next.stateVersion)
                put("event",event.toString()); put("phase","pending"); put("attempts",0)
            })
            receipt=RecordDelivery(id,old.id,old.revision,next.stateVersion,event.toString(),0)
        }
        return checkNotNull(receipt)
    }
    private fun quotas(owner: String, collection: String) {
        db.rawQuery("SELECT COUNT(*),COALESCE(SUM(size_bytes),0),SUM(CASE WHEN collection=? THEN 1 ELSE 0 END) FROM artifact_records WHERE owner=?",arrayOf(collection,owner)).use { c ->
            check(c.moveToFirst()); require(c.getLong(0) <= 20000 && c.getLong(1) <= 20L*1024*1024 && c.getLong(2) <= 10000) { "The artifact record quota is full." }
        }
    }
    private fun page(owner: String, collection: String, operation: JSONObject, entry: JSONObject) {
        val order=operation.getString("order"); val generation=generation(owner,collection)
        var position: String?=null
        if(!operation.isNull("cursor")) {
            val valid=db.rawQuery("SELECT position FROM artifact_cursors WHERE cursor=? AND owner=? AND collection=? AND ordering=? AND generation=?",
                arrayOf(operation.getString("cursor"),owner,collection,order,generation.toString())).use { c -> if(c.moveToFirst()) c.getString(0) else null }
            if(valid == null) { entry.put("status","staleCursor"); return }
            position=valid
        }
        val rows=JSONArray(); val limit=operation.getInt("limit")
        val where=if(position == null) "" else " AND record_key${if(order == "asc") ">" else "<"}?"
        val args=mutableListOf(owner,collection).apply { position?.let(::add) }
        val ordering=if(order == "asc") "ASC" else "DESC"
        var more=false
        db.rawQuery("SELECT record_key,token,value FROM artifact_records WHERE owner=? AND collection=?$where ORDER BY record_key $ordering LIMIT ${limit+1}",args.toTypedArray()).use { c ->
            while(c.moveToNext()) {
                if(rows.length() == limit) { more=true; break }
                val row=JSONObject().put("key",c.getString(0)).put("token",c.getString(1)).put("value",org.json.JSONTokener(c.getString(2)).nextValue())
                val candidate=JSONArray(rows.toString()).put(row)
                if(JSONObject(entry.toString()).put("records",candidate).put("cursor","x".repeat(64)).toString().toByteArray().size > 8192) {
                    require(rows.length() > 0) { "The record does not fit inside an 8 KiB page." }; more=true; break
                }
                rows.put(row)
            }
        }
        entry.put("records",rows).put("cursor",JSONObject.NULL)
        if(more) {
            val cursor=token()
            db.insertOrThrow("artifact_cursors",null,ContentValues().apply {
                put("cursor",cursor); put("owner",owner); put("collection",collection); put("ordering",order)
                put("position",rows.getJSONObject(rows.length()-1).getString("key")); put("generation",generation)
            })
            // Cursors contain no user records. Bound native cursor metadata while stale handles fail explicitly.
            db.execSQL("DELETE FROM artifact_cursors WHERE owner=? AND rowid NOT IN (SELECT rowid FROM artifact_cursors WHERE owner=? ORDER BY rowid DESC LIMIT 256)",arrayOf(owner,owner))
            entry.put("cursor",cursor)
        }
    }
    fun claimDelivery(owner: String): RecordDelivery? {
        var claimed: RecordDelivery?=null
        store.transaction {
            val delivery=pending(owner) ?: return@transaction
            val tool=store.tool(owner)
            if(tool == null || tool.archived || tool.revision != delivery.revision || tool.stateVersion != delivery.stateVersion || delivery.attempts >= 2) {
                reject(delivery); return@transaction
            }
            val count=db.update("artifact_record_deliveries",ContentValues().apply { put("attempts",delivery.attempts+1) },"delivery_id=? AND phase='pending' AND attempts=?",arrayOf(delivery.id,delivery.attempts.toString()))
            if(count == 1) claimed=delivery.copy(attempts=delivery.attempts+1)
        }
        return claimed
    }
    private fun pruneReceipts(owner: String) {
        db.update("artifact_record_deliveries",ContentValues().apply { put("event","") },"owner=? AND phase<>'pending' AND event<>''",arrayOf(owner))
        db.execSQL("DELETE FROM artifact_record_deliveries WHERE owner=? AND phase<>'pending' AND rowid NOT IN (SELECT rowid FROM artifact_record_deliveries WHERE owner=? AND phase<>'pending' ORDER BY rowid DESC LIMIT 64)",arrayOf(owner,owner))
    }
    fun reject(delivery: RecordDelivery) = store.transaction {
        db.update("artifact_record_deliveries",ContentValues().apply { put("phase","rejected"); put("event","") },"delivery_id=? AND phase='pending'",arrayOf(delivery.id))
        pruneReceipts(delivery.owner)
    }
    fun commitDelivery(delivery: RecordDelivery, next: ToolRecord, canCommit: ()->Boolean = { true }): Boolean {
        var committed=false
        store.transaction {
            if(pending(delivery.owner) != delivery || !canCommit()) return@transaction
            val old=store.tool(delivery.owner) ?: return@transaction
            if(old.archived || old.revision != delivery.revision || old.stateVersion != delivery.stateVersion) { reject(delivery); return@transaction }
            require(next == old.copy(stateVersion=old.stateVersion+1,state=next.state,ui=next.ui))
            val spec=ToolSpec.parse(old.spec); spec.validateState(next.state); spec.validateUi(JSONObject(next.ui),JSONObject(next.state))
            if(!canCommit()) return@transaction
            store.commitTool(next,old,false,recordDeliveryId=delivery.id)
            check(db.update("artifact_record_deliveries",ContentValues().apply { put("phase","committed"); put("event","") },"delivery_id=? AND phase='pending' AND attempts=?",arrayOf(delivery.id,delivery.attempts.toString())) == 1)
            pruneReceipts(delivery.owner)
            committed=true
        }
        return committed
    }
}
