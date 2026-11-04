package com.alpha.assistant.capabilities

import android.app.*
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alpha.assistant.automations.AutomationDefinition
import com.alpha.assistant.automations.AutomationRuntime
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.ui.AlphyActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.UUID
import kotlinx.coroutines.sync.withLock

/** This closed contract records user statements. A notification never records a meal or intake. */
internal object MedicineContract {
    private fun text(max: Int=80,optional: Boolean=false)=CapabilityArgument("string",optional,maxLength=max)
    private fun number(min: Long=0,max: Long=4102444800000L)=CapabilityArgument("integer",minimum=min,maximum=max)
    val reminderIds=setOf("medicine.remindDaily","medicine.remindMeal")
    private val version=mapOf("version" to number(1,1))
    private val record=version+mapOf("recordKey" to text(),"at" to number(1),"timeZone" to text())
    private val reminder=version+mapOf("reminderKey" to text(),"medicine" to text(),"timeZone" to text())
    val range=version+mapOf("from" to number(1),"to" to number(1))
    private const val recordSchema="""{"type":"object","additionalProperties":false,"required":["kind","key","at","timeZone","medicine","mealKey","mealWindow","status"],"properties":{"kind":{"type":"string","enum":["meal","intake","reminder"]},"key":{"type":"string","maxLength":160},"at":{"type":"integer"},"timeZone":{"type":"string","maxLength":80},"medicine":{"type":"string","maxLength":80},"mealKey":{"type":"string","maxLength":80},"mealWindow":{"type":"string","enum":["notApplicable","noMealAnchor","within30Minutes","outside30Minutes"]},"status":{"type":"string","maxLength":80}}}"""
    val resultSchema="""{"version":{"type":"integer","const":1},"record":$recordSchema,"records":{"type":"array","maxItems":5,"items":$recordSchema},"nextCursor":{"type":"integer","minimum":0},"reminder":{"type":"object","additionalProperties":false,"required":["reminderKey","mode","status","nextAt"],"properties":{"reminderKey":{"type":"string","maxLength":80},"mode":{"type":"string","enum":["daily","meal"]},"status":{"type":"string","maxLength":80},"nextAt":{"type":["integer","null"]},"selectedAt":{"type":"integer"},"requestedAt":{"type":"integer"}}}}"""
    private fun definition(id: String,title: String,args: Map<String,CapabilityArgument>,description: String)=CapabilityDefinition(id,title,CapabilityAction.MEDICINE,args,
        "Version 1. $description Never invent a dose, meal, intake, time, offset, or medical schedule. Explicit native medicine controls and a scoped grant are required.",
        if(id=="medicine.report") "launched" else "succeeded","Only explicit local records and native request states are confirmed. Reminder delivery and medicine intake are separate.",
        permission=if(id in reminderIds && android.os.Build.VERSION.SDK_INT>=33) android.Manifest.permission.POST_NOTIFICATIONS else null,
        foreground=true,resultData=resultSchema,scopedActionGrant=true)
    val definitions=listOf(
        definition("medicine.recordMeal","Record meal",record,"Save the user's explicit past meal time and IANA timeZone with a stable recordKey. Same-key retries preserve the original record."),
        definition("medicine.recordIntake","Record actual intake",record+mapOf("medicine" to text(),"mealKey" to text(optional=true)),"Save actual intake only after the user explicitly records it. Optional mealKey must reference this app's explicit meal. Native history calculates the inclusive 0–30-minute window."),
        definition("medicine.remindMeal","Remind after recorded meal",reminder+mapOf("mealKey" to text(),"offsetMinutes" to number(0,30)),"Schedule one exact reminder from this app's explicit meal plus the user's selected 0–30-minute offset. The timeZone must match the meal. An explicitly selected zero offset requests delivery now if the meal time has passed but the 30-minute window remains open. The selected time remains the meal time, and the result discloses the actual request time. Nonzero past targets, expired meal windows, and missing Android exact-alarm or notification access are unavailable. No inexact fallback."),
        definition("medicine.remindDaily","Daily medicine reminder",reminder+mapOf("minuteOfDay" to number(0,1439)),"Schedule at the user's explicit minuteOfDay in a fixed IANA timeZone. Skip a DST gap and use the earlier overlap once. Device zone changes do not change this fixed zone. Android cannot guarantee timely display. No inexact fallback."),
        definition("medicine.cancelReminder","Cancel medicine reminder",version+mapOf("reminderKey" to text()),"Cancel only this app's reminderKey. Never alter intake history."),
        definition("medicine.history","View medicine history",range+mapOf("cursor" to number()),"Read this app's explicit records and reminder attempts over at most 31 days, with 5 records per page in saved sequence order. Use nextCursor until it is zero. No adherence is inferred."),
        definition("medicine.report","Review medicine report",range,"Open a native frozen report for an explicit range of at most 31 days, 1000 records, and 200000 UTF-8 bytes. The user reviews the complete report and then chooses Share. Opening the preview does not export or prove delivery.")
    )
    val ids=definitions.map {it.id}.toSet()
    fun validate(id: String,args: JSONObject) {
        if(args.has("timeZone")) require(args.getString("timeZone") in ZoneId.getAvailableZoneIds()) {"Select an explicit IANA time zone."}
        if(id in setOf("medicine.history","medicine.report")) require(args.getLong("to")-args.getLong("from") in 1..2678400000L)
    }
    fun request(node: JSONObject): CapabilityRequest {
        require(node.getString("type")=="medicine" && node.getString("capability") in ids)
        return CapabilityCatalog.request(JSONObject().put("id","selected").put("capability",node.getString("capability")).put("args",node.getJSONObject("args")))
    }
    fun label(request: CapabilityRequest): String = CapabilityCatalog.definition(request.capability).title+"\n"+request.args.keys().asSequence().filter {it!="version"}.joinToString("\n") {key ->
        val value=request.args.get(key)
        "$key: "+when {
            key=="minuteOfDay" -> LocalTime.of((value as Number).toInt()/60,value.toInt()%60).toString()
            key in setOf("at","from","to") -> Instant.ofEpochMilli((value as Number).toLong()).atZone(if(request.args.has("timeZone")) ZoneId.of(request.args.getString("timeZone")) else ZoneOffset.UTC).toString()
            else -> value.toString()
        }
    } + if(request.capability=="medicine.remindMeal" && request.args.getInt("offsetMinutes")==0) "\nZero offset: if the selected meal time has passed, request delivery now within its 30-minute window. This does not record delivery at the meal time." else ""
    fun validateResult(id: String,result: CapabilityResult) {
        val data=result.data
        if(result.status!="succeeded") {require(data.length()==0);return}
        require(data.getInt("version")==1)
        val field=when(id) {"medicine.recordMeal","medicine.recordIntake" -> "record";"medicine.history" -> "records";else -> "reminder"}
        require(data.keys().asSequence().toSet()==setOf("version",field)+if(id=="medicine.history") setOf("nextCursor") else emptySet())
        if(field=="records") {require(data.getJSONArray(field).length()<=5);require(data.getLong("nextCursor")>=0)}
        fun record(d: JSONObject) {
            require(d.keys().asSequence().toSet()==setOf("kind","key","at","timeZone","medicine","mealKey","mealWindow","status"))
            require(d.getString("kind") in setOf("meal","intake","reminder"))
            require(d.get("at") is Number && d.getLong("at") in 1..4102444800000L && d.getDouble("at")==d.getLong("at").toDouble())
            for(key in listOf("key","timeZone","medicine","mealKey","mealWindow","status")) require(d.get(key) is String && d.getString(key).length<=if(key=="key")160 else 80)
            require(d.getString("mealWindow") in setOf("notApplicable","noMealAnchor","within30Minutes","outside30Minutes"))
        }
        if(field=="record")record(data.getJSONObject(field))
        if(field=="records")repeat(data.getJSONArray(field).length()) {record(data.getJSONArray(field).getJSONObject(it))}
        if(field=="reminder") {
            val r=data.getJSONObject(field)
            val required=setOf("reminderKey","mode","status","nextAt")
            val actual=r.keys().asSequence().toSet()
            require(actual.containsAll(required) && actual.all {it in required+setOf("selectedAt","requestedAt")})
            require(r.has("selectedAt")==r.has("requestedAt"))
            if(r.has("selectedAt")) for(key in listOf("selectedAt","requestedAt")) require(r.get(key) is Number && r.getLong(key) in 1..4102444800000L && r.getDouble(key)==r.getLong(key).toDouble())
            require(r.getString("mode") in setOf("daily","meal"))
            require(r.get("reminderKey") is String && r.getString("reminderKey").length<=80)
            require(r.get("status") is String && r.getString("status").length<=80)
            require(r.isNull("nextAt") || r.get("nextAt") is Number && r.getLong("nextAt") in 1..4102444800000L)
        }
    }
}

internal class MedicineInteraction private constructor(private val owner: String,private val revision: Int,private val version: Int,private val request: CapabilityRequest) {
    companion object {
        fun from(tool: com.alpha.assistant.tools.ToolRecord,action: String,value: Any?): MedicineInteraction? {
            val node=com.alpha.assistant.tools.ToolUiCatalog.validateAction(JSONObject(tool.ui),action,value,JSONObject(tool.state))
            return if(node.getString("type")=="medicine") MedicineInteraction(tool.id,tool.revision,tool.stateVersion,MedicineContract.request(node)) else null
        }
    }
    fun matches(effect: CapabilityEffect)=effect.owner==owner && effect.revision==revision && effect.stateVersion==version+1 && effect.request.capability==request.capability &&
        effect.request.args.keys().asSequence().toSet()==request.args.keys().asSequence().toSet() && request.args.keys().asSequence().all {key -> effect.request.args.get(key).toString()==request.args.get(key).toString()}
}

internal object MedicineSchedule {
    fun nextDaily(args: JSONObject,now: Long): Long {
        val minute=args.getInt("minuteOfDay")
        val time=LocalTime.of(minute/60,minute%60)
        val trigger=JSONObject().put("type","schedule").put("repeat","daily").put("time",time.toString()).put("zone",args.getString("timeZone")).put("exact",true)
        val definition=AutomationDefinition.parse(JSONObject().put("name","Medicine reminder").put("trigger",trigger).put("conditions",JSONObject()).put("actions",JSONArray().put(
            JSONObject().put("id","remind").put("capability","notification.post").put("args",JSONObject().put("title","Medicine reminder").put("text",args.getString("medicine"))))))
        var after=now
        repeat(8) {
            val at=checkNotNull(definition.nextAfter(after))
            if(Instant.ofEpochMilli(at).atZone(ZoneId.of(args.getString("timeZone"))).toLocalTime()==time) return at
            after=at
        }
        error("No valid daily time is available.")
    }
}

/** New private storage preserves all existing tool and automation databases. Stable keys prevent repeated writes. */
internal class MedicineStore(context: Context,name: String="alpha-medicine.db"): SQLiteOpenHelper(context,name,null,1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(seq INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,kind TEXT NOT NULL,key TEXT NOT NULL,at INTEGER NOT NULL,data TEXT NOT NULL,UNIQUE(owner,kind,key))")
        db.execSQL("CREATE TABLE reminders(owner TEXT NOT NULL,key TEXT NOT NULL,revision INTEGER NOT NULL,capability TEXT NOT NULL,args TEXT NOT NULL,mode TEXT NOT NULL,status TEXT NOT NULL,next_at INTEGER,PRIMARY KEY(owner,key))")
        db.execSQL("CREATE TABLE reports(token TEXT PRIMARY KEY,owner TEXT NOT NULL,body TEXT NOT NULL,expires INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int)=error("Unknown medicine schema. Records remain saved.")
    fun entry(owner: String,kind: String,key: String): JSONObject?=readableDatabase.rawQuery("SELECT data FROM entries WHERE owner=? AND kind=? AND key=?",arrayOf(owner,kind,key)).use {if(it.moveToFirst()) JSONObject(it.getString(0)) else null}
    fun append(owner: String,data: JSONObject) {writableDatabase.insertOrThrow("entries",null,ContentValues().apply {put("owner",owner);put("kind",data.getString("kind"));put("key",data.getString("key"));put("at",data.getLong("at"));put("data",data.toString())})}
    fun record(owner: String,id: String,args: JSONObject,now: Long): CapabilityResult {
        CapabilityCatalog.definition(id).validate(args)
        if(args.getLong("at")>now) return CapabilityResult("denied","A future time cannot record an actual meal or intake.")
        val kind=if(id=="medicine.recordMeal") "meal" else "intake"
        val mealKey=args.optString("mealKey")
        val meal=if(mealKey.isNotEmpty()) entry(owner,"meal",mealKey) ?: return CapabilityResult("denied","This app has no explicit meal with that key.") else null
        val data=JSONObject().put("kind",kind).put("key",args.getString("recordKey")).put("at",args.getLong("at")).put("timeZone",args.getString("timeZone"))
            .put("medicine",args.optString("medicine")).put("mealKey",mealKey).put("mealWindow",if(kind=="meal") "notApplicable" else if(meal==null) "noMealAnchor" else if(args.getLong("at")-meal.getLong("at") in 0..1800000L) "within30Minutes" else "outside30Minutes").put("status","userRecorded")
        val old=entry(owner,kind,args.getString("recordKey"))
        if(old!=null && old.toString()!=data.toString()) return CapabilityResult("denied","This key already has another record. Existing history remains saved.")
        if(old==null) append(owner,data)
        return CapabilityResult("succeeded","The user's explicit record is saved.",JSONObject().put("version",1).put("record",old ?: data))
    }
    fun history(owner: String,from: Long,to: Long,cursor: Long): JSONObject {
        val rows=JSONArray();var next=0L
        readableDatabase.rawQuery("SELECT seq,data FROM entries WHERE owner=? AND at>=? AND at<? AND seq>? ORDER BY seq LIMIT 6",arrayOf(owner,from.toString(),to.toString(),cursor.toString())).use {c ->
            while(c.moveToNext()) {if(rows.length()==5) break;next=c.getLong(0);rows.put(JSONObject(c.getString(1)))}
            if(c.isAfterLast) next=0
        }
        return JSONObject().put("version",1).put("records",rows).put("nextCursor",next)
    }
    fun reminders(): List<JSONObject> = readableDatabase.rawQuery("SELECT owner,key,revision,capability,args,mode,status,next_at FROM reminders",null).use {c -> buildList {while(c.moveToNext())add(JSONObject().put("owner",c.getString(0)).put("reminderKey",c.getString(1)).put("revision",c.getInt(2)).put("capability",c.getString(3)).put("args",JSONObject(c.getString(4))).put("mode",c.getString(5)).put("status",c.getString(6)).put("nextAt",if(c.isNull(7)) JSONObject.NULL else c.getLong(7)))}}
    fun state(owner: String,key: String,status: String,next: Long?) {writableDatabase.update("reminders",ContentValues().apply {put("status",status);if(next==null)putNull("next_at") else put("next_at",next)},"owner=? AND key=?",arrayOf(owner,key))}
    fun report(owner: String,from: Long,to: Long,now: Long): String? {
        val lines=mutableListOf("Medicine history. User records and reminder attempts only.","Range: ${Instant.ofEpochMilli(from)} inclusive to ${Instant.ofEpochMilli(to)} exclusive.","Missing records do not prove missed intake. Notifications do not prove intake or reading.")
        readableDatabase.rawQuery("SELECT data FROM entries WHERE owner=? AND at>=? AND at<? ORDER BY at,seq LIMIT 1001",arrayOf(owner,from.toString(),to.toString())).use {c ->
            while(c.moveToNext()) {if(lines.size>=1003)return null
                val d=JSONObject(c.getString(0));lines.add(JSONObject(d.toString()).put("time",Instant.ofEpochMilli(d.getLong("at")).atZone(ZoneId.of(d.getString("timeZone"))).toString()).toString())}
        }
        val body=lines.joinToString("\n")
        if(body.toByteArray().size>200000)return null
        val token=UUID.randomUUID().toString()
        writableDatabase.delete("reports","expires<?",arrayOf(now.toString()))
        writableDatabase.insertOrThrow("reports",null,ContentValues().apply {put("token",token);put("owner",owner);put("body",body);put("expires",now+3600000)})
        return token
    }
}

internal class MedicineCapabilities(private val context: Context,private val tools: ToolStore,
    private val clock: ()->Long={System.currentTimeMillis()},private val platform: MedicinePlatform=MedicineRuntime.android(context),
    private val unlocked: ()->Boolean={!com.alpha.assistant.surfaces.ToolWidgets.isLocked(context)}) {
    fun execute(effect: CapabilityEffect,foreground: ()->Boolean,launch: (Intent)->Unit): CapabilityResult {
        val native=tools.effect(effect.key)
        if(native?.phase!="running" || native.owner!=effect.owner || native.request.json().toString()!=effect.request.json().toString() || !tools.effectTargetValid(native)) return CapabilityResult("denied","A current native medicine action is required.")
        if(!foreground() || !unlocked()) return CapabilityResult("awaitingUser","Open and unlock Alphy for this medicine action.")
        val id=effect.request.capability;val args=effect.request.args;val now=clock()
        MedicineStore(context,MedicineRuntime.database(tools)).use {store ->
            if(id in setOf("medicine.recordMeal","medicine.recordIntake")) return store.record(effect.owner,id,args,now)
            if(id=="medicine.history") return CapabilityResult("succeeded","Explicit local history. No intake is inferred.",store.history(effect.owner,args.getLong("from"),args.getLong("to"),args.getLong("cursor")))
            if(id=="medicine.report") {
                val token=store.report(effect.owner,args.getLong("from"),args.getLong("to"),now) ?: return CapabilityResult("unavailable","The complete report exceeds 1000 records or 200000 UTF-8 bytes. Select a shorter range.")
                launch(Intent(context,MedicineReportActivity::class.java).putExtra("token",token).putExtra("toolsDatabase",tools.databaseName))
                return CapabilityResult("launched","The native report preview opened. Nothing was shared.")
            }
            val key=args.getString("reminderKey")
            val old=store.reminders().find {it.getString("owner")==effect.owner && it.getString("reminderKey")==key}
            if(id=="medicine.cancelReminder") {
                if(old==null)return CapabilityResult("unavailable","This app has no reminder with that key.")
                store.state(effect.owner,key,"cancelled",null);platform.cancel(old)
                val notifications=context.getSystemService(NotificationManager::class.java)
                notifications.activeNotifications.filter {it.tag?.startsWith(MedicineRuntime.tagPrefix(effect.owner,key))==true}.forEach {notifications.cancel(it.tag,it.id)}
                return reminderResult(old.put("status","cancelled").put("nextAt",JSONObject.NULL))
            }
            if(old!=null) {
                if(old.getString("args")!=args.toString() || old.getString("capability")!=id) return CapabilityResult("denied","This key already identifies another reminder. Cancel it and use a new key.")
                MedicineRuntime.reconcile(context,tools,store,now,platform)
                return reminderResult(store.reminders().first {it.getString("owner")==effect.owner && it.getString("reminderKey")==key})
            }
            platform.accessFailure()?.let {return CapabilityResult("unavailable",it)}
            val daily=id=="medicine.remindDaily"
            val meal=if(!daily) store.entry(effect.owner,"meal",args.getString("mealKey")) ?: return CapabilityResult("denied","Record the meal explicitly before a meal reminder.") else null
            if(meal!=null && meal.getString("timeZone")!=args.getString("timeZone")) return CapabilityResult("denied","The reminder time zone must match its explicit meal.")
            val selectedAt=if(daily) MedicineSchedule.nextDaily(args,now) else meal!!.getLong("at")+args.getLong("offsetMinutes")*60000
            val immediate=!daily && args.getInt("offsetMinutes")==0 && selectedAt<=now
            if(!daily && now>meal!!.getLong("at")+1800000) return CapabilityResult("unavailable","The meal's 30-minute window has passed. No notification was requested.")
            if(selectedAt<now && !immediate) return CapabilityResult("unavailable","The selected nonzero offset time already passed. No delayed reminder was scheduled.")
            val at=if(immediate) now else selectedAt
            store.writableDatabase.insertOrThrow("reminders",null,ContentValues().apply {put("owner",effect.owner);put("key",key);put("revision",effect.revision);put("capability",id);put("args",args.toString());put("mode",if(daily) "daily" else "meal");put("status","scheduled");put("next_at",at)})
            if(immediate) MedicineRuntime.deliver(context,tools,store,Intent().putExtra("owner",effect.owner).putExtra("key",key).putExtra("at",at),clock(),platform)
            else MedicineRuntime.reconcile(context,tools,store,now,platform)
            return reminderResult(store.reminders().first {it.getString("owner")==effect.owner && it.getString("reminderKey")==key},if(immediate) selectedAt else null,if(immediate) now else null)
        }
    }
    private fun reminderResult(r: JSONObject,selectedAt: Long?=null,requestedAt: Long?=null): CapabilityResult {
        val message=if(selectedAt!=null && requestedAt!=null) {
            (if(selectedAt<requestedAt) "The selected time has passed. " else "")+"The selected zero offset remains unchanged. Immediate delivery was requested at ${Instant.ofEpochMilli(requestedAt)}, for the original selected time ${Instant.ofEpochMilli(selectedAt)}. Actual intake remains unverified."
        } else "The native reminder state is recorded. Delivery and actual intake remain unverified."
        return CapabilityResult("succeeded",message,JSONObject().put("version",1).put("reminder",JSONObject().put("reminderKey",r.getString("reminderKey")).put("mode",r.getString("mode")).put("status",r.getString("status")).put("nextAt",r.get("nextAt")).apply {
            if(selectedAt!=null && requestedAt!=null) {put("selectedAt",selectedAt);put("requestedAt",requestedAt)}
        }))
    }
}

/** One snapshot keeps special alarm access separate from notification permission and channel state. */
internal data class MedicineReminderAccess(val exactAlarms: Boolean,val notificationPermission: Boolean,val notificationsEnabled: Boolean,val channelEnabled: Boolean) {
    val permissionGranted get()=exactAlarms && notificationPermission
    val state get()=if(!permissionGranted) "permissionRequired" else if(!notificationsEnabled || !channelEnabled) "disabled" else "available"
    fun json()=JSONObject().put("exactAlarms",exactAlarms).put("notificationPermission",notificationPermission)
        .put("notificationsEnabled",notificationsEnabled).put("medicineChannelEnabled",channelEnabled)
    fun failure(): String?=when {
        !exactAlarms -> "Android exact alarms are unavailable. No inexact reminder was substituted."
        !notificationPermission || !notificationsEnabled || !channelEnabled -> "Android notification access or the medicine channel is disabled."
        else -> null
    }
}

/** The native side-effect boundary permits deterministic lifecycle tests without real alarms or notifications. */
internal interface MedicinePlatform {
    fun accessFailure(): String?
    fun arm(reminder: JSONObject)
    fun cancel(reminder: JSONObject)
    fun post(reminder: JSONObject,at: Long,now: Long): String
}

/** This scheduler reuses the automation restore gate and schedule calculator. Claims survive process death. */
internal object MedicineRuntime {
    const val ALARM="com.alpha.assistant.MEDICINE_ALARM"
    fun tagPrefix(owner: String,key: String)="medicine:"+java.security.MessageDigest.getInstance("SHA-256").digest(JSONArray().put(owner).put(key).toString().toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}+":"
    fun database(tools: ToolStore)=if(tools.databaseName=="alpha-tools.db") "alpha-medicine.db" else "${tools.databaseName}-medicine.db"
    fun access(context: Context): MedicineReminderAccess {
        val manager=context.getSystemService(NotificationManager::class.java)
        return MedicineReminderAccess(AutomationRuntime.exactAllowed(context),AndroidCapabilities(context).permissionGranted("notification.post"),
            manager.areNotificationsEnabled(),manager.getNotificationChannel("alpha-medicine")?.importance!=NotificationManager.IMPORTANCE_NONE)
    }
    fun android(context: Context): MedicinePlatform=AndroidPlatform(context)
    private class AndroidPlatform(private val context: Context): MedicinePlatform {
        override fun accessFailure(): String? {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("alpha-medicine","Medicine reminders",NotificationManager.IMPORTANCE_HIGH))
            return access(context).failure()
        }
        override fun arm(reminder: JSONObject) {context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,reminder.getLong("nextAt"),pending(context,reminder))}
        override fun cancel(reminder: JSONObject) {context.getSystemService(AlarmManager::class.java).cancel(pending(context,reminder))}
        override fun post(reminder: JSONObject,at: Long,now: Long): String {
            val manager=context.getSystemService(NotificationManager::class.java)
            val tag=tagPrefix(reminder.getString("owner"),reminder.getString("reminderKey"))+at
            val args=reminder.getJSONObject("args")
            val immediate=reminder.getString("mode")=="meal" && args.getInt("offsetMinutes")==0 && reminder.getLong("selectedAt")<at
            val timing=if(immediate) " · Selected meal time passed. Requested now at ${Instant.ofEpochMilli(now)}." else ""
            val open=PendingIntent.getActivity(context,0,Intent(context,com.alpha.assistant.MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(tag,0,Notification.Builder(context,"alpha-medicine").setSmallIcon(com.alpha.assistant.R.drawable.ic_alpha).setContentTitle("Medicine reminder")
                .setContentText(args.getString("medicine")+timing+" · Record actual intake separately.").setStyle(Notification.BigTextStyle().bigText(args.getString("medicine")+timing+" · Record actual intake separately."))
                .setContentIntent(open).setAutoCancel(true).build())
            return if(manager.activeNotifications.any {it.tag==tag}) "activeNotification" else "unknownOutcome"
        }
    }
    private fun pending(context: Context,r: JSONObject): PendingIntent = PendingIntent.getBroadcast(context,0,Intent(context,MedicineReceiver::class.java).setAction(ALARM)
        .setData(Uri.Builder().scheme("alpha-medicine").authority("alarm").appendPath(r.getString("owner")).appendPath(r.getString("reminderKey")).build())
        .putExtra("owner",r.getString("owner")).putExtra("key",r.getString("reminderKey")).putExtra("at",r.optLong("nextAt")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun deadline(store: MedicineStore,r: JSONObject): Long = if(r.getString("mode")=="meal")
        (store.entry(r.getString("owner"),"meal",r.getJSONObject("args").getString("mealKey"))?.getLong("at") ?: 0)+1800000 else r.getLong("nextAt")+60000
    fun reconcile(context: Context,tools: ToolStore,store: MedicineStore,now: Long=System.currentTimeMillis(),platform: MedicinePlatform=android(context)) {
        for(r in store.reminders()) {
            if(r.getString("status")!="scheduled") {platform.cancel(r);continue}
            val owner=r.getString("owner");val key=r.getString("reminderKey");val tool=tools.tool(owner)
            val failure=when {tool==null || tool.archived || tool.revision!=r.getInt("revision") -> "ownerChanged";!tools.granted(owner,r.getString("capability")) -> "grantRevoked";platform.accessFailure()!=null -> "permissionUnavailable";else -> null}
            if(failure!=null) {store.state(owner,key,failure,null);platform.cancel(r);continue}
            if(now>deadline(store,r)) {complete(store,r,"missed",now,platform);continue}
            // Keep the original occurrence and claim key. A past valid target requests prompt Android delivery.
            try {platform.arm(r)}
            catch(_: SecurityException) {store.state(owner,key,"permissionUnavailable",null);platform.cancel(r)}
        }
    }
    private fun complete(store: MedicineStore,r: JSONObject,status: String,now: Long,platform: MedicinePlatform) {
        val at=r.getLong("nextAt");val key=r.getString("reminderKey");val owner=r.getString("owner");val args=r.getJSONObject("args")
        val eventKey="$key:$at"
        if(store.entry(owner,"reminder",eventKey)==null)store.append(owner,JSONObject().put("kind","reminder").put("key",eventKey).put("at",at).put("timeZone",args.getString("timeZone")).put("medicine",args.getString("medicine")).put("mealKey",args.optString("mealKey")).put("mealWindow","notApplicable").put("status",status))
        val next=if(r.getString("mode")=="daily") MedicineSchedule.nextDaily(args,now) else null
        store.state(owner,key,if(next==null) status else "scheduled",next)
        platform.cancel(r)
        if(next!=null)try {platform.arm(r.put("nextAt",next))}
        catch(_: SecurityException) {store.state(owner,key,"permissionUnavailable",null);platform.cancel(r)}
    }
    fun deliver(context: Context,tools: ToolStore,store: MedicineStore,intent: Intent,now: Long=System.currentTimeMillis(),platform: MedicinePlatform=android(context)) {
        val r=store.reminders().find {it.getString("owner")==intent.getStringExtra("owner") && it.getString("reminderKey")==intent.getStringExtra("key")} ?: return
        if(r.getString("status")!="scheduled" || r.isNull("nextAt") || r.getLong("nextAt")!=intent.getLongExtra("at",-1)) return
        val at=r.getLong("nextAt");if(now<at)return
        val tool=tools.tool(r.getString("owner"))
        if(tool==null || tool.archived || tool.revision!=r.getInt("revision") || !tools.granted(tool.id,r.getString("capability")) || platform.accessFailure()!=null) {reconcile(context,tools,store,now,platform);return}
        val args=r.getJSONObject("args")
        if(now>deadline(store,r)) {complete(store,r,"missed",now,platform);return}
        val eventKey="${r.getString("reminderKey")}:$at"
        if(store.entry(tool.id,"reminder",eventKey)!=null) {complete(store,r,"unknownOutcome",now,platform);return}
        if(r.getString("mode")=="meal")r.put("selectedAt",store.entry(tool.id,"meal",args.getString("mealKey"))!!.getLong("at")+args.getLong("offsetMinutes")*60000)
        // Claim before Android notify. A crash never retries the notification or records intake.
        complete(store,r,"unknownOutcome",now,platform)
        try {
            val status=platform.post(r,at,now)
            val data=store.entry(tool.id,"reminder",eventKey)!!.put("status",status)
            store.writableDatabase.update("entries",ContentValues().apply {put("data",data.toString())},"owner=? AND kind='reminder' AND key=?",arrayOf(tool.id,eventKey))
            if(r.getString("mode")=="meal")store.state(tool.id,r.getString("reminderKey"),status,null)
        } catch(_: SecurityException) { /* The durable claim remains an unknown outcome. */ }
    }
}

class MedicineReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=MedicineRuntime.ALARM)return
        val pending=goAsync()
        AutomationRuntime.launch(context,pending::finish) {
            ToolStore.effectMutex.withLock {
                ToolStore(context).use {tools ->MedicineStore(context).use {store ->MedicineRuntime.deliver(context,tools,store,intent)}}
            }
        }
    }
}

/** A frozen native report never enters generated UI or the model. Only a separate review click opens the chooser. */
class MedicineReportActivity: AlphyActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token=intent.getStringExtra("token") ?: return finish()
        val database=intent.getStringExtra("toolsDatabase") ?: return finish()
        fun snapshot(): String?=ToolStore(this,database).use {tools ->MedicineStore(this,MedicineRuntime.database(tools)).use {store ->
            store.readableDatabase.rawQuery("SELECT owner,body,expires FROM reports WHERE token=?",arrayOf(token)).use {c ->
                if(!c.moveToFirst() || c.getLong(2)<System.currentTimeMillis() || tools.tool(c.getString(0))?.archived!=false || !tools.granted(c.getString(0),"medicine.report")) null else c.getString(1)
            }
        }}
        val body=snapshot() ?: return finish()
        setContent {MaterialTheme {Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Review medicine report",style=MaterialTheme.typography.titleLarge)
            Text("Only explicit records appear. A missing record does not prove missed intake. Select Share after review.")
            Text(body,Modifier.weight(1f).verticalScroll(rememberScrollState()))
            Button(onClick={if(!com.alpha.assistant.surfaces.ToolWidgets.isLocked(this@MedicineReportActivity) && snapshot()==body) {
                try {startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,body),"Share reviewed medicine report"))} catch(_: ActivityNotFoundException) {android.widget.Toast.makeText(this@MedicineReportActivity,"No share app is available.",android.widget.Toast.LENGTH_LONG).show()}
            }}) {Text("Share reviewed report")}
            TextButton(onClick={finish()}) {Text("Close")}
        }}}
    }
}
