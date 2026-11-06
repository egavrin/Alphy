package com.alpha.assistant.capabilities

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.provider.CalendarContract
import android.provider.CalendarContract.Events as Events
import android.provider.CalendarContract.Reminders as Reminders
import android.provider.CalendarContract.Calendars as Calendars
import com.alpha.assistant.surfaces.ToolWidgets
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.ToolUiCatalog
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ensureActive

/** Public data uses opaque references. Provider identities and ownership markers remain native. */
internal object OwnedCalendarContract {
    val ids=setOf("calendar.select","calendar.createOwned","calendar.readOwned","calendar.deleteOwned","calendar.deleteAllOwned","calendar.open")
    val detailKeys=setOf("title","start","end","timeZone","deviceTimeZone","location","reminderMinutes")
    private fun text(max: Int,format: String?=null)=CapabilityArgument("string",maxLength=max,format=format)
    private fun integer(min: Long,max: Long)=CapabilityArgument("integer",minimum=min,maximum=max)
    val version=mapOf("version" to integer(1,1))
    val reference=version+mapOf("eventRef" to text(64,"reached-ref"))
    val details=mapOf("title" to text(200),"start" to integer(0,4102444800000L),"end" to integer(0,4102444800000L),
        "timeZone" to text(100),"deviceTimeZone" to text(100),"location" to CapabilityArgument("string",optional=true,maxLength=300,allowEmpty=true),
        "reminderMinutes" to integer(0,10080))
    val create=version+details+mapOf("calendarRef" to text(64,"reached-ref"),"eventKey" to text(64,"stop-id"))
    val delete=reference+details
    fun validate(capability: String,args: JSONObject) {
        require(args.getLong("version")==1L)
        if(capability in setOf("calendar.createOwned","calendar.deleteOwned")) {
            require(args.getLong("end")-args.getLong("start") in 1..2678400000L)
            for(key in listOf("timeZone","deviceTimeZone")) require(args.getString(key) in ZoneId.getAvailableZoneIds()) {"Use an explicit IANA timezone."}
        }
    }
    fun details(args: JSONObject)=JSONObject().apply {detailKeys.forEach {key ->
        if(key=="location") put(key,args.optString(key,"")) else put(key,args.get(key))
    }}
    fun equal(a: JSONObject,b: JSONObject): Boolean = a.keys().asSequence().toSet()==b.keys().asSequence().toSet() && a.keys().asSequence().all {key ->
        val x=a.get(key);val y=b.get(key);if(x is Number && y is Number)x.toDouble()==y.toDouble() else x==y
    }
    fun fingerprint(args: JSONObject): String {
        val canonical=JSONObject().apply {args.keys().asSequence().sorted().forEach {put(it,args.get(it))}}
        return MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
    }
    fun random()=ByteArray(32).also {SecureRandom().nextBytes(it)}.joinToString("") {"%02x".format(it.toInt() and 255)}
    fun request(node: JSONObject): CapabilityRequest {
        require(node.getString("type")=="calendar" && node.getString("capability") in ids)
        return CapabilityCatalog.request(JSONObject().put("id","selected").put("capability",node.getString("capability")).put("args",node.getJSONObject("args")))
    }
    fun label(request: CapabilityRequest): String {
        val args=request.args
        return when(request.capability) {
            "calendar.select" -> "Select a writable calendar"
            "calendar.deleteAllOwned" -> "Delete all events this app added"
            "calendar.open" -> "Open calendar"
            "calendar.readOwned" -> "Read owned event · ${args.getString("eventRef").take(8)}"
            else -> {
                val zone=ZoneId.of(args.getString("timeZone"))
                val start=Instant.ofEpochMilli(args.getLong("start")).atZone(zone)
                val end=Instant.ofEpochMilli(args.getLong("end")).atZone(zone)
                "${if(request.capability=="calendar.createOwned")"Create" else "Delete"} event: ${args.getString("title")}\n$start — $end\n"+
                    "Device timezone at creation: ${args.getString("deviceTimeZone")} · now: ${ZoneId.systemDefault().id}\nLocation: ${args.optString("location","")}\nReminder: ${args.getInt("reminderMinutes")} minutes before"+
                    if(request.capability=="calendar.createOwned")"\nSelected calendar: ${args.getString("calendarRef").take(8)}" else "\nOwned event: ${args.getString("eventRef").take(8)}"
            }
        }
    }
    val resultSchema="""{"version":{"type":"integer","const":1},"calendarRef":{"type":"string","format":"opaque-owner-handle"},"calendarName":{"type":"string","maxLength":120},"eventRef":{"type":"string","format":"opaque-owner-handle"},"event":{"type":"object"},"deleted":{"type":"boolean"},"reminderDeliveryVerified":{"type":"boolean","const":false}}"""
    fun resultDataSchema(capability: String): JSONObject {
        if(capability=="calendar.deleteAllOwned")return JSONObject().put("type","object").put("additionalProperties",false)
            .put("required",org.json.JSONArray(listOf("version","total","deleted","unknown","remaining")))
            .put("properties",JSONObject().put("version",JSONObject().put("type","integer").put("const",1)).apply {
                listOf("total","deleted","unknown","remaining").forEach {put(it,JSONObject().put("type","integer").put("minimum",0).put("maximum",Int.MAX_VALUE))}
            })
        val all=JSONObject(resultSchema)
        val fields=when(capability) {
            "calendar.select" -> listOf("version","calendarRef")
            "calendar.deleteOwned" -> listOf("version","eventRef","deleted")
            "calendar.open" -> emptyList()
            else -> listOf("version","eventRef","calendarRef","event","reminderDeliveryVerified")
        }
        val properties=JSONObject().apply {fields.forEach {put(it,all.get(it))}}
        if("event" in fields)properties.put("event",JSONObject().put("type","object").put("additionalProperties",false)
            .put("required",org.json.JSONArray(detailKeys.toList())).put("properties",JSONObject().apply {details.forEach {(name,arg)->put(name,arg.schema())}}))
        return JSONObject().put("type","object").put("additionalProperties",false).put("properties",properties)
            .put("required",org.json.JSONArray(fields))
    }
    fun validateResult(capability: String,result: CapabilityResult) {
        val data=result.data
        if(capability=="calendar.deleteAllOwned" && (result.status=="succeeded" || result.status=="unknownOutcome" && data.length()>0)) {
            require(data.keys().asSequence().toSet()==setOf("version","total","deleted","unknown","remaining") && data.get("version")==1)
            listOf("total","deleted","unknown","remaining").forEach {key ->
                val value=data.get(key);require(value is Number && value.toDouble()==value.toLong().toDouble() && value.toLong() in 0..Int.MAX_VALUE.toLong())
            }
            require(data.getLong("total")==data.getLong("deleted")+data.getLong("unknown")+data.getLong("remaining"))
            require(result.status!="succeeded" || data.getLong("unknown")==0L && data.getLong("remaining")==0L)
            return
        }
        if(result.status!="succeeded") {require(data.length()==0);return}
        require(data.get("version")==1)
        fun ref(key: String) {require(data.get(key) is String && data.getString(key).matches(Regex("[0-9a-f]{64}")))}
        val expected=when(capability) {
            "calendar.select" -> {ref("calendarRef");setOf("version","calendarRef")}
            "calendar.deleteOwned" -> {ref("eventRef");require(data.get("deleted")==true);setOf("version","eventRef","deleted")}
            else -> {
                require(capability in setOf("calendar.createOwned","calendar.readOwned"));ref("eventRef");ref("calendarRef")
                require(data.get("reminderDeliveryVerified")==false)
                val event=data.getJSONObject("event");require(event.keys().asSequence().toSet()==detailKeys)
                CapabilityCatalog.definition("calendar.deleteOwned").validate(JSONObject(event.toString()).apply {
                    if(optString("location")=="") remove("location");put("version",1);put("eventRef",data.getString("eventRef"))
                })
                setOf("version","eventRef","calendarRef","event","reminderDeliveryVerified")
            }
        }
        require(data.keys().asSequence().toSet()==expected)
    }
}

/** Only a visible native calendar control can produce the frozen click authorization. */
internal class OwnedCalendarInteraction private constructor(private val owner: String,private val revision: Int,
    private val stateVersion: Int,private val selected: CapabilityRequest) {
    companion object {
        fun from(tool: ToolRecord,action: String,value: Any?): OwnedCalendarInteraction? {
            val node=ToolUiCatalog.validateAction(JSONObject(tool.ui),action,value,JSONObject(tool.state))
            if(node.getString("type")!="calendar")return null
            return OwnedCalendarInteraction(tool.id,tool.revision,tool.stateVersion,OwnedCalendarContract.request(node))
        }
    }
    fun matches(effect: CapabilityEffect)=effect.owner==owner && effect.revision==revision && effect.stateVersion==stateVersion+1 &&
        effect.request.capability==selected.capability && OwnedCalendarContract.equal(effect.request.args,selected.args)
}

internal data class NativeOwnedCalendar(val id: Long,val name: String,val identity: String,val supportsReminder: Boolean)
internal data class NativeOwnedEvent(val id: Long,val calendarId: Long,val app: String,val marker: String,val details: JSONObject,val deleted: Boolean)
internal interface OwnedCalendarProvider {
    fun calendars(): List<NativeOwnedCalendar>
    fun find(calendarId: Long,app: String,marker: String): List<NativeOwnedEvent>
    fun insert(calendarId: Long,app: String,marker: String,details: JSONObject): Long
    fun delete(id: Long,calendarId: Long,app: String,marker: String,details: JSONObject): Int
}

/** Uses ordinary app writes. No sync-adapter query parameter or private provider field is used. */
internal class AndroidOwnedCalendarProvider(private val context: Context): OwnedCalendarProvider {
    override fun calendars(): List<NativeOwnedCalendar> {
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,arrayOf(Calendars._ID,Calendars.CALENDAR_DISPLAY_NAME,Calendars.ACCOUNT_NAME,Calendars.ACCOUNT_TYPE,Calendars.OWNER_ACCOUNT,Calendars.ALLOWED_REMINDERS,Calendars.MAX_REMINDERS),
            "${Calendars.CALENDAR_ACCESS_LEVEL}>=? AND ${Calendars.VISIBLE}=1 AND ${Calendars.SYNC_EVENTS}=1",arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),null)?.use {cursor ->
            buildList {while(cursor.moveToNext() && size<50) {
                val identity=OwnedCalendarContract.fingerprint(JSONObject().put("id",cursor.getLong(0)).put("account",cursor.getString(2)).put("type",cursor.getString(3)).put("owner",cursor.getString(4)))
                val methods=cursor.getString(5).orEmpty().split(',').mapNotNull {it.trim().toIntOrNull()}
                add(NativeOwnedCalendar(cursor.getLong(0),cursor.getString(1).orEmpty().take(120),identity,
                    cursor.getInt(6)>=1 && CalendarContract.Reminders.METHOD_ALERT in methods))
            }}
        } ?: error("Calendar provider unavailable")
    }
    override fun insert(calendarId: Long,app: String,marker: String,details: JSONObject): Long {
        val values=ContentValues().apply {
            put(Events.CALENDAR_ID,calendarId);put(Events.TITLE,details.getString("title"));put(Events.DTSTART,details.getLong("start"));put(Events.DTEND,details.getLong("end"))
            put(Events.EVENT_TIMEZONE,details.getString("timeZone"));put(Events.EVENT_END_TIMEZONE,details.getString("timeZone"));put(Events.EVENT_LOCATION,details.getString("location"))
            put(Events.ALL_DAY,0);put(Events.HAS_ALARM,1);put(Events.CUSTOM_APP_PACKAGE,app);put(Events.CUSTOM_APP_URI,marker)
        }
        val operations=arrayListOf(ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values).build(),
            ContentProviderOperation.newInsert(Reminders.CONTENT_URI).withValueBackReference(Reminders.EVENT_ID,0)
                .withValue(Reminders.MINUTES,details.getInt("reminderMinutes")).withValue(Reminders.METHOD,Reminders.METHOD_ALERT).build())
        val result=context.contentResolver.applyBatch(CalendarContract.AUTHORITY,operations)
        return android.content.ContentUris.parseId(checkNotNull(result[0].uri))
    }
    override fun find(calendarId: Long,app: String,marker: String): List<NativeOwnedEvent> {
        val fields=arrayOf(Events._ID,Events.CALENDAR_ID,Events.CUSTOM_APP_PACKAGE,Events.CUSTOM_APP_URI,Events.TITLE,Events.DTSTART,Events.DTEND,Events.EVENT_TIMEZONE,Events.EVENT_LOCATION,Events.DELETED,Events.ALL_DAY,Events.RRULE,Events.RDATE,Events.DURATION,Events.EVENT_END_TIMEZONE)
        return context.contentResolver.query(Events.CONTENT_URI,fields,"${Events.CALENDAR_ID}=? AND ${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.CUSTOM_APP_URI}=?",
            arrayOf(calendarId.toString(),app,marker),null)?.use {cursor ->buildList {
            while(cursor.moveToNext() && size<2) {
                val id=cursor.getLong(0)
                val reminders=context.contentResolver.query(Reminders.CONTENT_URI,arrayOf(Reminders.MINUTES,Reminders.METHOD),"${Reminders.EVENT_ID}=?",arrayOf(id.toString()),null)?.use {rc ->
                    buildList<Pair<Int,Int>> {while(rc.moveToNext() && size<2)add(rc.getInt(0) to rc.getInt(1))}
                } ?: error("Reminder provider unavailable")
                val details=JSONObject().put("title",cursor.getString(4).orEmpty()).put("start",cursor.getLong(5)).put("end",cursor.getLong(6))
                    .put("timeZone",cursor.getString(7).orEmpty()).put("location",cursor.getString(8).orEmpty())
                if(reminders.size==1 && reminders[0].second==Reminders.METHOD_ALERT)details.put("reminderMinutes",reminders[0].first)
                if(cursor.getInt(10)!=0 || (11..13).any {!cursor.getString(it).isNullOrEmpty()} ||
                    (!cursor.getString(14).isNullOrEmpty() && cursor.getString(14)!=cursor.getString(7))) details.put("unsupported",true)
                add(NativeOwnedEvent(id,cursor.getLong(1),cursor.getString(2).orEmpty(),cursor.getString(3).orEmpty(),details,cursor.getInt(9)!=0))
            }
        }} ?: error("Event provider unavailable")
    }
    override fun delete(id: Long,calendarId: Long,app: String,marker: String,details: JSONObject): Int {
        // Keep ownership and the displayed snapshot on CONTENT_URI. Provider assertions share the delete batch.
        val selection="${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.CUSTOM_APP_URI}=? AND ${Events.DELETED}=0 AND ${Events.TITLE}=? AND ${Events.DTSTART}=? AND ${Events.DTEND}=? AND ${Events.EVENT_TIMEZONE}=? AND COALESCE(${Events.EVENT_LOCATION},'')=? AND ${Events.ALL_DAY}=0 AND COALESCE(${Events.RRULE},'')='' AND COALESCE(${Events.RDATE},'')='' AND COALESCE(${Events.DURATION},'')=''"
        val args=arrayOf(id.toString(),calendarId.toString(),app,marker,details.getString("title"),details.getLong("start").toString(),details.getLong("end").toString(),details.getString("timeZone"),details.getString("location"))
        val operations=arrayListOf(
            ContentProviderOperation.newAssertQuery(Reminders.CONTENT_URI).withSelection("${Reminders.EVENT_ID}=?",arrayOf(id.toString()))
                .withValue(Reminders.MINUTES,details.getInt("reminderMinutes")).withValue(Reminders.METHOD,Reminders.METHOD_ALERT).withExpectedCount(1).build(),
            ContentProviderOperation.newDelete(Events.CONTENT_URI).withSelection(selection,args).withExpectedCount(1).build())
        return context.contentResolver.applyBatch(CalendarContract.AUTHORITY,operations)[1].count ?: 0
    }
}

internal data class OwnedCalendarEntry(val owner: String,val eventRef: String,val eventKey: String,val calendarRef: String,
    val calendarId: Long,val calendarIdentity: String,val effectKey: String,val marker: String,val fingerprint: String,
    val details: JSONObject,val providerId: Long?,val phase: String)
internal data class OwnedCalendarSnapshot(val owner: String,val revision: Int,val stateVersion: Int,val entries: List<OwnedCalendarEntry>)
internal interface OwnedCalendarUiHost {
    suspend fun snapshot(): OwnedCalendarSnapshot?
    fun deleteAll(action: String,snapshot: OwnedCalendarSnapshot)
}
internal class OwnedCalendarLedger(private val store: ToolStore) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS owned_calendar_delete_batches(effect_key TEXT PRIMARY KEY,owner TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS owned_calendar_delete_items(effect_key TEXT NOT NULL,event_ref TEXT NOT NULL,phase TEXT NOT NULL,PRIMARY KEY(effect_key,event_ref))")
            db.execSQL("CREATE TABLE IF NOT EXISTS owned_calendar_selection(owner TEXT PRIMARY KEY,calendar_ref TEXT NOT NULL,calendar_id INTEGER NOT NULL,identity TEXT NOT NULL,name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS owned_calendar_events(owner TEXT NOT NULL,event_ref TEXT NOT NULL,event_key TEXT NOT NULL,calendar_ref TEXT NOT NULL,calendar_id INTEGER NOT NULL,calendar_identity TEXT NOT NULL,effect_key TEXT NOT NULL,marker TEXT NOT NULL,fingerprint TEXT NOT NULL,details TEXT NOT NULL,provider_id INTEGER,phase TEXT NOT NULL,PRIMARY KEY(owner,event_key),UNIQUE(owner,event_ref),UNIQUE(effect_key))")
        }
    }
    fun valid(effect: CapabilityEffect): Boolean {
        val current=store.effect(effect.key) ?: return false
        return current.phase=="running" && current.owner==effect.owner && current.revision==effect.revision && current.stateVersion==effect.stateVersion &&
            current.request.json().toString()==effect.request.json().toString() && store.effectTargetValid(current)
    }
    fun select(owner: String,calendar: NativeOwnedCalendar): String {
        val ref=OwnedCalendarContract.random()
        store.writableDatabase.insertWithOnConflict("owned_calendar_selection",null,ContentValues().apply {
            put("owner",owner);put("calendar_ref",ref);put("calendar_id",calendar.id);put("identity",calendar.identity);put("name",calendar.name)
        },SQLiteDatabase.CONFLICT_REPLACE).also {check(it!=-1L)}
        return ref
    }
    fun selected(owner: String,ref: String): NativeOwnedCalendar?=store.readableDatabase.rawQuery(
        "SELECT calendar_id,name,identity FROM owned_calendar_selection WHERE owner=? AND calendar_ref=?",arrayOf(owner,ref)).use {
        if(it.moveToFirst())NativeOwnedCalendar(it.getLong(0),it.getString(1),it.getString(2),true) else null
    }
    private fun query(owner: String,field: String,value: String): OwnedCalendarEntry?=store.readableDatabase.rawQuery(
        "SELECT * FROM owned_calendar_events WHERE owner=? AND $field=?",arrayOf(owner,value)).use {c ->
        if(!c.moveToFirst())null else OwnedCalendarEntry(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getString(5),
            c.getString(6),c.getString(7),c.getString(8),JSONObject(c.getString(9)),if(c.isNull(10))null else c.getLong(10),c.getString(11))
    }
    private fun activeReferences(owner: String): List<String> = store.readableDatabase.rawQuery(
        "SELECT event_ref FROM owned_calendar_events WHERE owner=? AND phase<>'deleted' ORDER BY rowid",arrayOf(owner)).use {c ->
        buildList {while(c.moveToNext())add(c.getString(0))}
    }
    fun snapshot(tool: ToolRecord): OwnedCalendarSnapshot {
        check(store.tool(tool.id)==tool && !tool.archived)
        return OwnedCalendarSnapshot(tool.id,tool.revision,tool.stateVersion,activeReferences(tool.id).map {checkNotNull(event(tool.id,it))})
    }
    private fun snapshotComplete(snapshot: OwnedCalendarSnapshot): Boolean {
        val refs=snapshot.entries.map {it.eventRef}.toSet()
        return refs.size==snapshot.entries.size && refs==activeReferences(snapshot.owner).toSet() && snapshot.entries.all {frozen ->
            frozen.owner==snapshot.owner && event(snapshot.owner,frozen.eventRef)?.let {current ->
                current.marker==frozen.marker && current.fingerprint==frozen.fingerprint && current.calendarId==frozen.calendarId &&
                    current.calendarIdentity==frozen.calendarIdentity && current.providerId==frozen.providerId && current.phase==frozen.phase
            }==true
        }
    }
    fun snapshotCurrent(tool: ToolRecord,snapshot: OwnedCalendarSnapshot)=!tool.archived && snapshot.owner==tool.id &&
        snapshot.revision==tool.revision && snapshot.stateVersion==tool.stateVersion && snapshotComplete(snapshot)
    fun snapshotValid(effect: CapabilityEffect,snapshot: OwnedCalendarSnapshot)=snapshot.owner==effect.owner && snapshot.revision==effect.revision &&
        snapshot.stateVersion+1==effect.stateVersion && snapshotComplete(snapshot)
    fun beginDeleteAll(effect: CapabilityEffect,snapshot: OwnedCalendarSnapshot): Boolean {
        var inserted=false
        store.transaction {
            if(!valid(effect) || !snapshotValid(effect,snapshot))return@transaction
            inserted=store.writableDatabase.insertWithOnConflict("owned_calendar_delete_batches",null,ContentValues().apply {
                put("effect_key",effect.key);put("owner",effect.owner)
            },SQLiteDatabase.CONFLICT_IGNORE)!=-1L
            if(inserted)snapshot.entries.forEach {entry ->store.writableDatabase.insertOrThrow("owned_calendar_delete_items",null,ContentValues().apply {
                put("effect_key",effect.key);put("event_ref",entry.eventRef);put("phase","pending")
            })}
        }
        return inserted
    }
    fun deleteProgress(effect: CapabilityEffect,entry: OwnedCalendarEntry,phase: String) {
        check(store.writableDatabase.update("owned_calendar_delete_items",ContentValues().apply {put("phase",phase)},
            "effect_key=? AND event_ref=?",arrayOf(effect.key,entry.eventRef))==1)
    }
    fun deleteCounts(effect: CapabilityEffect): JSONObject {
        var total=0;var deleted=0;var unknown=0
        store.readableDatabase.rawQuery("SELECT phase,COUNT(*) FROM owned_calendar_delete_items WHERE effect_key=? GROUP BY phase",arrayOf(effect.key)).use {c ->
            while(c.moveToNext()) {val count=c.getInt(1);total+=count;if(c.getString(0)=="succeeded")deleted+=count;if(c.getString(0) in setOf("running","unknown"))unknown+=count}
        }
        return JSONObject().put("version",1).put("total",total).put("deleted",deleted).put("unknown",unknown).put("remaining",total-deleted-unknown)
    }
    /** Read only the durable native journal. Recovery never queries or mutates the provider. */
    fun interruptedResult(effect: CapabilityEffect,message: String): CapabilityResult? {
        if(effect.request.capability!="calendar.deleteAllOwned")return null
        val started=store.readableDatabase.rawQuery("SELECT 1 FROM owned_calendar_delete_batches WHERE effect_key=? AND owner=?",arrayOf(effect.key,effect.owner)).use {it.moveToFirst()}
        return if(started)CapabilityResult("unknownOutcome",message,deleteCounts(effect)) else null
    }
    fun event(owner: String,ref: String)=query(owner,"event_ref",ref)
    fun keyed(owner: String,key: String)=query(owner,"event_key",key)
    fun prepare(effect: CapabilityEffect,calendar: NativeOwnedCalendar): OwnedCalendarEntry {
        val args=effect.request.args;val ref=OwnedCalendarContract.random()
        val entry=OwnedCalendarEntry(effect.owner,ref,args.getString("eventKey"),args.getString("calendarRef"),calendar.id,calendar.identity,
            effect.key,"alphy-owned:${OwnedCalendarContract.random()}",OwnedCalendarContract.fingerprint(args),OwnedCalendarContract.details(args),null,"prepared")
        store.writableDatabase.insertOrThrow("owned_calendar_events",null,ContentValues().apply {
            put("owner",entry.owner);put("event_ref",ref);put("event_key",entry.eventKey);put("calendar_ref",entry.calendarRef);put("calendar_id",calendar.id)
            put("calendar_identity",calendar.identity);put("effect_key",effect.key);put("marker",entry.marker);put("fingerprint",entry.fingerprint)
            put("details",entry.details.toString());put("phase",entry.phase)
        })
        return entry
    }
    fun phase(entry: OwnedCalendarEntry,phase: String,id: Long?=entry.providerId) {
        check(store.writableDatabase.update("owned_calendar_events",ContentValues().apply {
            put("phase",phase);if(id!=null)put("provider_id",id)
        },"owner=? AND event_ref=? AND marker=?",arrayOf(entry.owner,entry.eventRef,entry.marker))==1)
    }
}

/** Provider and SQLite commits cannot be atomic. A prepared key is consumed even when the outcome is unknown. */
internal class OwnedCalendarCapabilities(private val context: Context,private val store: ToolStore,
    private val provider: OwnedCalendarProvider=AndroidOwnedCalendarProvider(context),
    private val permissions: ()->Boolean={hasPermissions(context)},private val unlocked: ()->Boolean={!ToolWidgets.isLocked(context)}) {
    companion object {
        fun hasPermissions(context: Context)=listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR).all {context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
    }
    private val ledger=OwnedCalendarLedger(store)
    private fun guard(effect: CapabilityEffect,foreground: ()->Boolean): CapabilityResult?=when {
        !permissions() -> CapabilityResult("denied","Both Android calendar permissions are required.")
        !ledger.valid(effect) -> CapabilityResult("denied","The native calendar context is stale or revoked.")
        !foreground() || !unlocked() -> CapabilityResult("awaitingUser","Open and unlock Alphy to use calendar actions.")
        else -> null
    }
    fun writableCalendars()=if(permissions())provider.calendars().filter {it.supportsReminder} else emptyList()
    fun select(effect: CapabilityEffect,calendar: NativeOwnedCalendar,foreground: ()->Boolean): CapabilityResult {
        require(effect.request.capability=="calendar.select")
        guard(effect,foreground)?.let {return it}
        val current=writableCalendars().singleOrNull {it.id==calendar.id && it.identity==calendar.identity}
            ?: return CapabilityResult("unavailable","The selected writable calendar is no longer available.")
        var result=CapabilityResult("denied","Calendar selection changed.")
        store.transaction {
            guard(effect,foreground)?.let {result=it;return@transaction}
            val ref=ledger.select(effect.owner,current)
            result=CapabilityResult("succeeded","The writable calendar was explicitly selected for this app.",JSONObject().put("version",1).put("calendarRef",ref))
        }
        return result
    }
    fun execute(effect: CapabilityEffect,foreground: ()->Boolean,launch: (Intent)->Unit): CapabilityResult {
        CapabilityCatalog.request(effect.request.json());require(effect.request.capability in OwnedCalendarContract.ids)
        guard(effect,foreground)?.let {return it}
        val args=effect.request.args
        if(effect.request.capability=="calendar.deleteAllOwned")return CapabilityResult("denied","Use the native delete-all control with its displayed frozen owned-event snapshot.")
        if(effect.request.capability=="calendar.select")return CapabilityResult("awaitingUser","Select a writable calendar in the native picker.")
        if(effect.request.capability=="calendar.open") {
            guard(effect,foreground)?.let {return it}
            return try {launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,Intent.CATEGORY_APP_CALENDAR));CapabilityResult("launched","The calendar app opened. No event or reminder delivery is claimed.")}
            catch(_: android.content.ActivityNotFoundException) {CapabilityResult("unavailable","No calendar app can open.")}
        }
        val create=effect.request.capability=="calendar.createOwned"
        val entry=if(create)ledger.keyed(effect.owner,args.getString("eventKey")) else ledger.event(effect.owner,args.getString("eventRef"))
        if(create && entry!=null && entry.fingerprint!=OwnedCalendarContract.fingerprint(args))return CapabilityResult("denied","This event key already identifies a different request.")
        if(!create && entry==null)return CapabilityResult("denied","This event reference does not belong to this app.")
        if(effect.request.capability=="calendar.deleteOwned" && !OwnedCalendarContract.equal(checkNotNull(entry).details,OwnedCalendarContract.details(args)))
            return CapabilityResult("denied","The selected event details do not match the owned event.")
        if(create && entry==null && args.has("deviceTimeZone") && args.getString("deviceTimeZone")!=ZoneId.systemDefault().id)
            return CapabilityResult("denied","The device timezone changed. Review the event in the current timezone.")
        if(entry?.phase=="deleted")return if(effect.request.capability=="calendar.deleteOwned")deleted(entry)
            else CapabilityResult("unavailable","This owned event was deleted. Its key will not create another event.")
        val calendar=if(entry!=null)writableCalendars().singleOrNull {it.id==entry.calendarId && it.identity==entry.calendarIdentity}
            else ledger.selected(effect.owner,args.getString("calendarRef"))?.let {selected -> writableCalendars().singleOrNull {it.id==selected.id && it.identity==selected.identity}}
        if(calendar==null)return CapabilityResult("unavailable","Select an available writable calendar that supports an alert reminder.")
        if(create && entry==null) {
            guard(effect,foreground)?.let {return it}
            val prepared=ledger.prepare(effect,calendar) // Durable before the provider batch.
            return try {
                var result=unknown()
                store.transaction {
                    guard(effect,foreground)?.let {result=it;return@transaction}
                    val id=provider.insert(calendar.id,context.packageName,prepared.marker,prepared.details)
                    result=read(prepared,id)
                }
                result
            } catch(_: SecurityException) {unknown()} catch(_: Exception) {unknown()}
        }
        val owned=checkNotNull(entry)
        return try {
            var result=unknown()
            store.transaction {
                guard(effect,foreground)?.let {result=it;return@transaction}
                if(effect.request.capability=="calendar.deleteOwned") {
                    val row=verified(owned) ?: return@transaction
                    if(row.deleted) {ledger.phase(owned,"deleted",row.id);result=deleted(owned);return@transaction}
                    ledger.phase(owned,"deleting",row.id)
                    guard(effect,foreground)?.let {result=it;return@transaction}
                    val count=provider.delete(row.id,owned.calendarId,context.packageName,owned.marker,owned.details)
                    val remaining=provider.find(owned.calendarId,context.packageName,owned.marker)
                    if(count==1 && (remaining.isEmpty() || remaining.singleOrNull()?.let {it.id==row.id && it.deleted}==true)) {
                        ledger.phase(owned,"deleted",row.id);result=deleted(owned)
                    }
                } else result=read(owned)
            }
            result
        } catch(_: SecurityException) {unknown()} catch(_: Exception) {unknown()}
    }
    suspend fun deleteAll(effect: CapabilityEffect,snapshot: OwnedCalendarSnapshot,foreground: ()->Boolean): CapabilityResult {
        require(effect.request.capability=="calendar.deleteAllOwned")
        guard(effect,foreground)?.let {return it}
        if(!ledger.snapshotValid(effect,snapshot))return CapabilityResult("denied","The displayed owned-event snapshot is stale. Review the current native control.")
        if(!ledger.beginDeleteAll(effect,snapshot))return ledger.interruptedResult(effect,"This native delete-all operation already started. It will not replay.")
            ?: CapabilityResult("unknownOutcome","The native delete-all operation could not start. No deletion will replay.")
        for(entry in snapshot.entries) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(guard(effect,foreground)!=null)break
            ledger.deleteProgress(effect,entry,"running")
            val outcome=try {
                val calendar=writableCalendars().singleOrNull {it.id==entry.calendarId && it.identity==entry.calendarIdentity}
                if(calendar==null)false else {
                    val row=verified(entry)
                    if(row==null)false else if(row.deleted) {ledger.phase(entry,"deleted",row.id);true} else {
                        ledger.phase(entry,"deleting",row.id)
                        var deleted=false
                        store.transaction {
                            if(guard(effect,foreground)!=null)return@transaction
                            val count=provider.delete(row.id,entry.calendarId,context.packageName,entry.marker,entry.details)
                            val remaining=provider.find(entry.calendarId,context.packageName,entry.marker)
                            if(count==1 && (remaining.isEmpty() || remaining.singleOrNull()?.let {it.id==row.id && it.deleted}==true)) {
                                ledger.phase(entry,"deleted",row.id);deleted=true
                            }
                        }
                        deleted
                    }
                }
            } catch(_: SecurityException) {false} catch(_: Exception) {false}
            ledger.deleteProgress(effect,entry,if(outcome)"succeeded" else "unknown")
            // Yield between bounded single-event batches so lifecycle changes can stop the remaining work.
            kotlinx.coroutines.yield()
        }
        val counts=ledger.deleteCounts(effect)
        val complete=counts.getInt("unknown")==0 && counts.getInt("remaining")==0
        return CapabilityResult(if(complete)"succeeded" else "unknownOutcome",
            if(complete)"Every event in the explicitly selected native snapshot was deleted. Events added later and unrelated events remain unchanged."
            else "The native delete-all operation has partial or unknown outcomes. Saved progress will not replay automatically. Review the remaining owned events.",counts)
    }
    private fun verified(entry: OwnedCalendarEntry,id: Long?=entry.providerId): NativeOwnedEvent? {
        val row=provider.find(entry.calendarId,context.packageName,entry.marker).singleOrNull() ?: return null
        if(row.calendarId!=entry.calendarId || row.app!=context.packageName || row.marker!=entry.marker || id!=null && row.id!=id)return null
        val details=JSONObject(row.details.toString()).put("deviceTimeZone",entry.details.getString("deviceTimeZone"))
        if(row.deleted)details.put("reminderMinutes",entry.details.getInt("reminderMinutes"))
        return if(OwnedCalendarContract.equal(details,entry.details))row else null
    }
    private fun read(entry: OwnedCalendarEntry,id: Long?=entry.providerId): CapabilityResult {
        val row=verified(entry,id) ?: return unknown()
        if(row.deleted) {ledger.phase(entry,"deleted",row.id);return CapabilityResult("unavailable","The owned event is deleted.")}
        ledger.phase(entry,"verified",row.id)
        return CapabilityResult("succeeded","The exact owned event and one alert reminder were read back. Reminder delivery remains unverified.",
            JSONObject().put("version",1).put("eventRef",entry.eventRef).put("calendarRef",entry.calendarRef).put("event",JSONObject(entry.details.toString())).put("reminderDeliveryVerified",false))
    }
    private fun deleted(entry: OwnedCalendarEntry)=CapabilityResult("succeeded","The owned event deletion was verified. Other events remain unchanged.",JSONObject().put("version",1).put("eventRef",entry.eventRef).put("deleted",true))
    private fun unknown()=CapabilityResult("unknownOutcome","The provider outcome is partial or unknown. This event key will only be read back and will never repeat an insert automatically.")
}
