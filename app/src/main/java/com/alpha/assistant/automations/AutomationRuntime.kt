package com.alpha.assistant.automations

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.alpha.assistant.capabilities.*
import com.alpha.assistant.tools.*
import com.alpha.assistant.surfaces.SurfaceEntry
import com.alpha.assistant.surfaces.ToolWidgets
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

object AutomationRuntime {
    const val CHANGED = "com.alpha.assistant.AUTOMATIONS_CHANGED"
    const val ALARM = "com.alpha.assistant.AUTOMATION_ALARM"
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex = Mutex()
    fun launch(context: Context, done: (() -> Unit)? = null, work: suspend (AutomationStore) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                try { AutomationStore(app).use { work(it) } }
                catch(_: Exception) { /* Persisted claims retain unknown outcomes after interrupted execution. */ }
                finally { app.sendBroadcast(Intent(CHANGED).setPackage(app.packageName)); done?.invoke() }
            }
        }
    }
    fun charging(context: Context): Boolean? = context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
        it.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1).takeIf { plugged -> plugged >= 0 }?.let { plugged -> plugged != 0 }
    }
    fun googleAvailable(context: Context) = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    fun listenerAllowed(context: Context) = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context,AutomationNotificationListener::class.java))
    fun exactAllowed(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun locationAllowed(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun triggerFailure(context: Context, rule: AutomationRule, requireMonitor: Boolean = true): String? = when(rule.definition.type) {
        "geofence" -> when {
            !googleAvailable(context) -> "Геозоны недоступны: нужны рабочие Сервисы Google Play."
            !locationAllowed(context) -> "Для геозон разреши точную геопозицию и доступ «Всегда» в Android."
            !context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled -> "Геопозиция устройства выключена."
            else -> null
        }
        "notification" -> when {
            !listenerAllowed(context) -> "Разреши Alpha доступ к уведомлениям в Android."
            rule.definition.notificationPackages().any { source -> runCatching { context.packageManager.getApplicationInfo(source,0) }.isFailure } -> "Одно из выбранных приложений удалено или недоступно."
            else -> null
        }
        "charging" -> if(requireMonitor && !ChargingMonitor.active) "Наблюдение питания остановлено. Запусти его из Alpha и включи сценарий снова." else null
        "schedule" -> if(rule.definition.trigger.getBoolean("exact") && !exactAllowed(context)) "Точные напоминания запрещены Android. Разреши будильники и напоминания, затем включи сценарий." else null
        else -> null
    }
    fun actionFailure(context: Context, action: CapabilityRequest): String? {
        if(action.capability == "tool.state.set") return ToolStore(context).use { tools ->
            val tool = tools.tool(action.args.getString("toolId"))
            when {
                tool == null || tool.revision != action.args.getInt("revision") -> "Целевой инструмент изменился или недоступен. Проверь и одобри новую версию сценария."
                ToolSpec.parse(tool.spec).schema.opt(action.args.getString("field")) !is String -> "Целевое поле инструмента недоступно."
                else -> runCatching {
                    val state = JSONObject(tool.state).put(action.args.getString("field"),action.args.get("value"))
                    ToolSpec.parse(tool.spec).validateState(state.toString()); null
                }.getOrElse { "Новое значение не соответствует полю инструмента." }
            }
        }
        val android = AndroidCapabilities(context)
        if(!android.permissionGranted(action.capability)) return "Доступ Android к действию отозван: ${CapabilityCatalog.definition(action.capability).title}."
        if(action.capability == "notification.post") {
            val manager = context.getSystemService(NotificationManager::class.java)
            if(!manager.areNotificationsEnabled() || manager.getNotificationChannel("alpha-tools")?.importance == NotificationManager.IMPORTANCE_NONE) return "Уведомления Alpha выключены в Android."
        }
        return null
    }
    fun executor(context: Context, store: AutomationStore) = AutomationExecutor(store,{ charging(context) },{ rule,action ->
        triggerFailure(context,rule) ?: actionFailure(context,action)
    }) { rule,action,_ ->
        if(!store.active(rule.id,rule.revision)) CapabilityResult("cancelled","Сценарий остановлен.")
        else if(action.capability == "tool.state.set") {
            ToolStore(context).use { tools ->
                tools.transaction {
                    check(store.active(rule.id,rule.revision))
                    val old = checkNotNull(tools.tool(action.args.getString("toolId")))
                    check(old.revision == action.args.getInt("revision"))
                    val state = ToolSpec.parse(old.spec).validateState(JSONObject(old.state).put(action.args.getString("field"),action.args.get("value")).toString()).toString()
                    tools.commitTool(old.copy(state=state,stateVersion=old.stateVersion+1),old,false)
                }
            }
            ToolWidgets.updateAll(context)
            context.sendBroadcast(Intent(SurfaceEntry.TOOLS_CHANGED).setPackage(context.packageName))
            CapabilityResult("succeeded","Поле инструмента изменено на устройстве.")
        } else AndroidCapabilities(context).execute(action,false) { error("Фоновый запуск интерфейса запрещён") }
    }
    suspend fun execute(context: Context, store: AutomationStore, id: String, revision: Int, event: AutomationEvent, dry: Boolean = false): AutomationRun? = executor(context,store).run(id,revision,event,dry)
    suspend fun continueUserRun(context: Context, store: AutomationStore, run: AutomationRun, launch: suspend (Intent) -> Unit): AutomationRun? {
        val rule=store.rule(run.ruleId) ?: return null
        if(rule.revision != run.revision || !store.active(rule.id,rule.revision)) return null
        val details=org.json.JSONArray(run.details)
        val pending=(0 until details.length()).map { details.getJSONObject(it) }.lastOrNull { it.getString("status") == "awaitingUser" } ?: return null
        val action=rule.definition.actions.find { it.id == pending.getString("action") } ?: return null
        if(action.capability !in setOf("app.open","link.open","maps.open","calendar.create")) return null
        if(!store.claimUserRun(run)) return null
        val denied=triggerFailure(context,rule) ?: actionFailure(context,action)
        if(denied != null) { store.attention(rule.id,rule.revision,denied); return store.finish(run,"revoked",denied,run.details) }
        if(!store.active(rule.id,rule.revision)) return store.finish(run,"cancelled","Сценарий выключен или изменён.",run.details)
        val result=try { AndroidCapabilities(context).execute(action,true,launch) }
            catch(_: Exception) { CapabilityResult("unknownOutcome","Результат открытия неизвестен. Автоматического повтора нет.") }
        store.effect(run.id,action.id,result.json().toString())
        details.put(result.json().put("action",action.id))
        return store.finish(run,result.status,result.message,details.toString())
    }
    fun refresh(context: Context, resetGeofences: Boolean = false) = launch(context) { reconcile(context,it,resetGeofences) }
    private fun alarmIntent(context: Context, rule: AutomationRule) = Intent(context,AutomationReceiver::class.java).setAction(ALARM)
        .setData(Uri.parse("alpha-automation://alarm/${rule.id}"))
        .putExtra("id",rule.id).putExtra("revision",rule.revision).putExtra("at",rule.nextAt ?: 0)
    private fun alarm(context: Context, rule: AutomationRule) = PendingIntent.getBroadcast(context,0,alarmIntent(context,rule),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    suspend fun reconcile(context: Context, store: AutomationStore, resetGeofences: Boolean = false) {
        store.prune()
        val manager = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        for(rule in store.rules()) {
            if(!rule.enabled || rule.approvedRevision != rule.revision) { manager.cancel(alarm(context,rule)); continue }
            val failure = triggerFailure(context,rule) ?: rule.definition.actions.firstNotNullOfOrNull { actionFailure(context,it) }
            if(failure != null) { store.attention(rule.id,rule.revision,failure); manager.cancel(alarm(context,rule)); continue }
            if(rule.definition.type != "schedule") continue
            var at = rule.nextAt
            if(at != null && (resetGeofences && at < now || at < now - if(rule.definition.trigger.getBoolean("exact")) 900000L else 86400000L)) {
                val missed = AutomationEvent("schedule",rule.definition.occurrenceToken(at))
                store.begin(rule,missed,now,false)?.let { store.finish(it,"missed","Время прошло, пока расписание было недоступно. Просроченное действие не воспроизводилось.") }
                at = null
            }
            if(at == null || resetGeofences) at = rule.definition.nextAfter(now)
            store.next(rule.id,rule.revision,at)
            if(at == null) { manager.cancel(alarm(context,rule)); continue }
            val pending = alarm(context,rule.copy(nextAt=at))
            try {
                if(rule.definition.trigger.getBoolean("exact")) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending)
            } catch(_: SecurityException) { store.attention(rule.id,rule.revision,"Android запретил расписание. Проверь доступ к точным напоминаниям.") }
        }
        syncGeofences(context,store,resetGeofences)
    }
    fun alarmReceived(context: Context, intent: Intent, done: () -> Unit) {
        launch(context,done) { store ->
            val rule = intent.getStringExtra("id")?.let(store::rule) ?: return@launch
            val at = intent.getLongExtra("at",0)
            if(rule.revision != intent.getIntExtra("revision",-1) || rule.nextAt != at || at > System.currentTimeMillis()+1000) return@launch
            execute(context,store,rule.id,rule.revision,AutomationEvent("schedule",rule.definition.occurrenceToken(at)))
            store.next(rule.id,rule.revision,null)
            reconcile(context,store)
        }
    }
    fun powerChanged(context: Context, connected: Boolean) = launch(context) { store ->
        val value = if(connected) "connected" else "disconnected"
        if(store.transition("power",value)) {
            val token = "power:${android.os.SystemClock.elapsedRealtimeNanos()}"
            store.rules().filter { it.definition.type == "charging" }.forEach { execute(context,store,it.id,it.revision,AutomationEvent("charging",token,value)) }
        }
    }
    fun notification(context: Context, source: String, key: String, postTime: Long) = launch(context) { store ->
        if(source == context.packageName || !listenerAllowed(context)) return@launch
        val token = "notification:"+java.security.MessageDigest.getInstance("SHA-256").digest("$key:$postTime".toByteArray()).joinToString("") { "%02x".format(it) }
        store.rules().filter { it.definition.type == "notification" && source in it.definition.notificationPackages() }.forEach {
            execute(context,store,it.id,it.revision,AutomationEvent("notification",token,source))
        }
    }
    private fun geoIntent(context: Context) = PendingIntent.getBroadcast(context,0,Intent(context,AutomationGeofenceReceiver::class.java)
        .setAction("com.alpha.assistant.GEOFENCE"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    private suspend fun syncGeofences(context: Context, store: AutomationStore, force: Boolean) {
        if(!googleAvailable(context)) return
        val rules = store.rules().filter { store.active(it.id,it.revision) && it.definition.type == "geofence" }
        val fingerprint = rules.joinToString { "${it.id}:${it.revision}" }
        if(!force && store.state("geofences") == fingerprint) return
        val client = LocationServices.getGeofencingClient(context)
        try {
            withTimeout(4000) { client.removeGeofences(geoIntent(context)).awaitTask() }
            if(rules.isNotEmpty()) {
                val fences = rules.map { rule ->
                    store.setState("geo:${rule.id}:${rule.revision}","")
                    if(force) store.setState("geo-clock:${rule.id}:${rule.revision}","-1")
                    val t = rule.definition.trigger
                    Geofence.Builder().setRequestId("${rule.id}:${rule.revision}").setCircularRegion(t.getDouble("latitude"),t.getDouble("longitude"),t.getDouble("radius").toFloat())
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).setExpirationDuration(Geofence.NEVER_EXPIRE).build()
                }
                withTimeout(4000) { client.addGeofences(GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build(),geoIntent(context)).awaitTask() }
            }
            store.setState("geofences",fingerprint)
        } catch(_: Exception) {
            rules.forEach { store.attention(it.id,it.revision,"Сервисы Google Play не зарегистрировали геозону. Проверь разрешения и повтори включение.") }
        }
    }
    fun geofence(context: Context, intent: Intent, done: () -> Unit) = launch(context,done) { store ->
            val event = GeofencingEvent.fromIntent(intent) ?: return@launch
            if(event.hasError()) {
                store.setState("geofences","")
                store.rules().filter { it.definition.type == "geofence" && it.enabled }.forEach { store.attention(it.id,it.revision,"Сервисы Google Play сообщили ошибку геозоны: ${event.errorCode}. Проверь доступ и включи сценарий снова.") }
                return@launch
            }
            val transition = when(event.geofenceTransition) { Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"; Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"; else -> return@launch }
            for(fence in event.triggeringGeofences.orEmpty()) {
                val pieces = fence.requestId.split(':'); if(pieces.size != 2) continue
                val rule = store.rule(pieces[0]) ?: continue
                if(rule.revision != pieces[1].toIntOrNull() || !store.active(rule.id,rule.revision)) continue
                val timestamp = event.triggeringLocation?.elapsedRealtimeNanos ?: continue
                val clockKey = "geo-clock:${fence.requestId}"
                if(timestamp <= (store.state(clockKey)?.toLongOrNull() ?: -1)) continue
                store.setState(clockKey,timestamp.toString())
                if(store.transition("geo:${fence.requestId}",transition)) execute(context,store,rule.id,rule.revision,AutomationEvent("geofence","geo:$timestamp:$transition",transition))
            }
    }
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if(continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if(continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
