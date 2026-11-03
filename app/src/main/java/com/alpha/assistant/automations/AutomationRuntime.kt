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

data class AutomationApproval(val rule: AutomationRule, val archiveToken: String?)

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
            !listenerAllowed(context) -> "Разреши Alphy доступ к уведомлениям в Android."
            rule.definition.notificationPackages().any { source -> runCatching { context.packageManager.getApplicationInfo(source,0) }.isFailure } -> "Одно из выбранных приложений удалено или недоступно."
            else -> null
        }
        "charging" -> if(requireMonitor && !ChargingMonitor.active) "Наблюдение питания остановлено. Запусти его из Alphy и включи сценарий снова." else null
        "schedule" -> if(rule.definition.trigger.optBoolean("exact") && !exactAllowed(context)) "Точные напоминания запрещены Android. Разреши будильники и напоминания, затем включи сценарий." else null
        else -> null
    }
    fun dependencies(rule: AutomationRule, toolId: String) = rule.definition.actions.any {
        it.capability in setOf("tool.state.set","tool.records.calendar") && it.args.getString("toolId") == toolId
    }
    fun dependencyFailure(tools: ToolStore, rule: AutomationRule): String? = rule.definition.actions
        .filter { it.capability in setOf("tool.state.set","tool.records.calendar") }.firstNotNullOfOrNull { action ->
            val target=tools.tool(action.args.getString("toolId"))
            when {
                target == null -> "Целевой инструмент недоступен. Все действия сценария остановлены."
                target.archived -> "Целевой инструмент в архиве. Восстанови его и включи сценарий вручную."
                ToolSpec.available(target.spec)==null -> "The target artifact protocol is unavailable. Its saved data remains intact."
                target.revision != action.args.getInt("revision") -> "Версия целевого инструмента изменилась. Проверь сценарий."
                else -> null
            }
        }
    suspend fun prepareApproval(store: AutomationStore, id: String, revision: Int): AutomationApproval = mutex.withLock {
        val rule=checkNotNull(store.rule(id))
        check(rule.revision == revision) { "Сценарий изменился. Проверь текущую версию." }
        AutomationApproval(rule,store.state("approval-archive:$id"))
    }
    /** Archive invalidates open confirmations even if the target is recovered before approval resumes. */
    suspend fun approve(store: AutomationStore, approval: AutomationApproval, validate: (AutomationRule) -> Unit) = mutex.withLock {
        val expected=approval.rule
        check(store.state("approval-archive:${expected.id}") == approval.archiveToken) {
            "Инструмент был архивирован. Проверь сценарий и включи его заново после восстановления."
        }
        val current=checkNotNull(store.rule(expected.id))
        check(current.revision == expected.revision) { "Сценарий изменился. Проверь текущую версию." }
        validate(current)
        store.approve(expected.id,expected.revision)
    }
    /** Uses the same gate as rule effects. Persist pauses first, so interruption cannot enable background work. */
    suspend fun archiveTool(tools: ToolStore, rules: AutomationStore, expected: ToolRecord) = mutex.withLock {
      ArtifactCoordinator.get(tools,expected.id).ordered {
        check(current(tools,expected.id)==expected && !expected.archived) { "Инструмент изменился. Открой текущую версию." }
        close(tools,expected.revision)
        val saved=checkNotNull(tools.tool(expected.id))
        rules.transaction {
            rules.rules().filter { dependencies(it,expected.id) }.forEach {
                rules.attention(it.id,it.revision,"Инструмент в архиве. После восстановления включи сценарий вручную.")
                rules.setState("approval-archive:${it.id}",java.util.UUID.randomUUID().toString())
            }
        }
        ToolStore.effectMutex.withLock { tools.setArchived(saved,true) }
      }
    }
    private fun dependencyFailure(context: Context, rule: AutomationRule) = ToolStore(context).use { dependencyFailure(it,rule) }
    fun actionFailure(context: Context, action: CapabilityRequest): String? {
        if(action.capability in setOf("tool.state.set","tool.records.calendar")) return ToolStore(context).use { tools ->
            val tool = tools.tool(action.args.getString("toolId"))
            when {
                tool == null || tool.archived || tool.revision != action.args.getInt("revision") -> "Целевой инструмент изменился или недоступен. Проверь и одобри новую версию сценария."
                else -> runCatching {
                    val spec=ToolSpec.parse(tool.spec)
                    if(action.capability == "tool.records.calendar") AutomationDataflow.validateCollection(spec,action.args.getString("field"))
                    else {
                        require(spec.schema.get(action.args.getString("field")) is String)
                        spec.validateState(JSONObject(tool.state).put(action.args.getString("field"),action.args.get("value")).toString())
                    }
                    null
                }.getOrElse { "Новое значение не соответствует полю инструмента." }
            }
        }
        val android = AndroidCapabilities(context)
        val nativeId=if(action.capability == "calendar.read.rolling") "calendar.read" else action.capability
        if(!android.permissionGranted(nativeId)) return "Доступ Android к действию отозван: ${CapabilityCatalog.definition(nativeId).title}."
        if(nativeId == "calendar.read" && android.calendars().none { it.first == action.args.getLong("calendarId") })
            return "Выбранный календарь недоступен. Проверь источник и включи сценарий снова."
        if(action.capability == "notification.post") {
            val manager = context.getSystemService(NotificationManager::class.java)
            if(!manager.areNotificationsEnabled() || manager.getNotificationChannel("alpha-tools")?.importance == NotificationManager.IMPORTANCE_NONE) return "Уведомления Alphy выключены в Android."
        }
        return null
    }
    fun executor(context: Context, store: AutomationStore) = AutomationExecutor(store,{ charging(context) },{ rule,action ->
        triggerFailure(context,rule) ?: dependencyFailure(context,rule) ?: actionFailure(context,action)
    },preview={ rule,action,step -> perform(context,store,rule,action,step) }) { rule,action,step -> perform(context,store,rule,action,step) }
    private suspend fun perform(context: Context, store: AutomationStore, rule: AutomationRule, action: CapabilityRequest, step: AutomationStep): CapabilityResult {
        if(!step.dry && !store.active(rule.id,rule.revision)) return CapabilityResult("cancelled","Сценарий остановлен.")
        if(action.capability in setOf("tool.state.set","tool.records.calendar")) {
            val result=ToolStore(context).use { tools -> AutomationTools.execute(tools,rule.definition,action,step,AutomationTools.renderer(context,tools)) {store.active(rule.id,rule.revision)} }
            if(!step.dry) {
                ToolWidgets.updateAll(context)
                context.sendBroadcast(Intent(SurfaceEntry.TOOLS_CHANGED).setPackage(context.packageName))
            }
            return result
        }
        if(action.capability in setOf("calendar.read","calendar.read.rolling")) {
            val resolved=AutomationDataflow.resolveRead(action,step.now)
            val result=AndroidCapabilities(context).execute(resolved,false) {error("Чтение календаря не открывает интерфейс")}
            return result.copy(data=JSONObject(result.data.toString()).put("range",resolved.args)
                .put("preview","Календарь ${resolved.args.getLong("calendarId")}: ${resolved.args.getLong("from")} — ${resolved.args.getLong("to")}. ${result.data.optJSONArray("events")?.length() ?: 0} событий."))
        }
        if(step.dry) return CapabilityResult("succeeded","Действие не выполнялось.",JSONObject().put("preview",AutomationDataflow.describe(action)))
        return AndroidCapabilities(context).execute(action,false) { error("Фоновый запуск интерфейса запрещён") }
    }
    suspend fun execute(context: Context, store: AutomationStore, id: String, revision: Int, event: AutomationEvent, dry: Boolean = false): AutomationRun? = mutex.withLock { executor(context,store).run(id,revision,event,dry) }
    suspend fun continueUserRun(context: Context, store: AutomationStore, run: AutomationRun, launch: suspend (Intent) -> Unit): AutomationRun? = mutex.withLock {
        continueUserRunLocked(context,store,run,launch)
    }
    private suspend fun continueUserRunLocked(context: Context, store: AutomationStore, run: AutomationRun, launch: suspend (Intent) -> Unit): AutomationRun? {
        val rule=store.rule(run.ruleId) ?: return null
        if(rule.revision != run.revision || !store.active(rule.id,rule.revision)) return null
        val details=org.json.JSONArray(run.details)
        val pending=(0 until details.length()).map { details.getJSONObject(it) }.lastOrNull { it.getString("status") == "awaitingUser" } ?: return null
        val action=rule.definition.actions.find { it.id == pending.getString("action") } ?: return null
        if(action.capability !in setOf("app.open","link.open","maps.open","calendar.create")) return null
        if(!store.claimUserRun(run)) return null
        val denied=triggerFailure(context,rule) ?: dependencyFailure(context,rule) ?: actionFailure(context,action)
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
            val failure = triggerFailure(context,rule) ?: dependencyFailure(context,rule) ?: rule.definition.actions.firstNotNullOfOrNull { actionFailure(context,it) }
            if(failure != null) { store.attention(rule.id,rule.revision,failure); manager.cancel(alarm(context,rule)); continue }
            if(!rule.definition.scheduled) continue
            var at = rule.nextAt
            if(at != null && at < now-AutomationSchedule.MAX_LATENESS) {
                val missed = AutomationEvent("schedule",rule.definition.occurrenceToken(at))
                store.begin(rule,missed,now,false)?.let { store.finish(it,"missed","Время прошло, пока расписание было недоступно. Просроченное действие не воспроизводилось.") }
                at = null
            }
            if(at == null || resetGeofences && at > now && rule.definition.type != "interval") at = rule.definition.nextAfter(now)
            store.next(rule.id,rule.revision,at)
            if(at == null) { manager.cancel(alarm(context,rule)); continue }
            val pending = alarm(context,rule.copy(nextAt=at))
            try {
                if(rule.definition.trigger.optBoolean("exact")) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending)
            } catch(_: SecurityException) { store.attention(rule.id,rule.revision,"Android запретил расписание. Проверь доступ к точным напоминаниям.") }
        }
        com.alpha.assistant.tools.ToolStore.effectMutex.withLock {
            com.alpha.assistant.tools.ToolStore(context).use {tools -> MedicineStore(context).use {medicine -> MedicineRuntime.reconcile(context,tools,medicine) }}
        }
        syncGeofences(context,store,resetGeofences)
    }
    fun alarmReceived(context: Context, intent: Intent, done: () -> Unit) {
        launch(context,done) { store ->
            val rule = intent.getStringExtra("id")?.let(store::rule) ?: return@launch
            val at = intent.getLongExtra("at",0)
            if(rule.revision != intent.getIntExtra("revision",-1)) return@launch
            AutomationSchedule.deliver(store,rule,at,System.currentTimeMillis(),executor(context,store))
            reconcile(context,store)
        }
    }
    fun powerChanged(context: Context, connected: Boolean) = launch(context) { store ->
        val value = if(connected) "connected" else "disconnected"
        if(store.transition("power",value)) {
            val token = "power:${android.os.SystemClock.elapsedRealtimeNanos()}"
            store.rules().filter { it.definition.type == "charging" }.forEach { executor(context,store).run(it.id,it.revision,AutomationEvent("charging",token,value)) }
        }
    }
    fun notification(context: Context, source: String, key: String, postTime: Long) = launch(context) { store ->
        if(source == context.packageName || !listenerAllowed(context)) return@launch
        val token = "notification:"+java.security.MessageDigest.getInstance("SHA-256").digest("$key:$postTime".toByteArray()).joinToString("") { "%02x".format(it) }
        store.rules().filter { it.definition.type == "notification" && source in it.definition.notificationPackages() }.forEach {
            executor(context,store).run(it.id,it.revision,AutomationEvent("notification",token,source))
        }
    }
    private fun geoIntent(context: Context) = PendingIntent.getBroadcast(context,0,Intent(context,AutomationGeofenceReceiver::class.java)
        .setAction("com.alpha.assistant.GEOFENCE"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    private suspend fun syncGeofences(context: Context, store: AutomationStore, force: Boolean) {
        if(!googleAvailable(context)) return
        val rules = store.rules().filter { store.active(it.id,it.revision) && it.definition.type == "geofence" }
        val fingerprint = rules.joinToString { "${it.id}:${it.revision}" }
        val client = LocationServices.getGeofencingClient(context)
        try {
            registerGeofences(store,fingerprint,force,
                {withTimeout(4000) { client.removeGeofences(geoIntent(context)).awaitTask() }}) {
                if(rules.isNotEmpty()) {
                    val fences = rules.map { rule ->
                        store.setState("geo:${rule.id}:${rule.revision}","")
                        if(force) store.setState("geo-clock:${rule.id}:${rule.revision}","-1")
                        val t = rule.definition.trigger
                        Geofence.Builder().setRequestId("${rule.id}:${rule.revision}").setCircularRegion(t.getDouble("latitude"),t.getDouble("longitude"),t.getDouble("radius").toFloat())
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).setExpirationDuration(Geofence.NEVER_EXPIRE).build()
                    }
                    withTimeout(4000) {
                        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
                            throw SecurityException("Доступ к геопозиции отозван")
                        client.addGeofences(GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build(),geoIntent(context)).awaitTask()
                    }
                }
            }
        } catch(_: SecurityException) {
            rules.forEach { store.attention(it.id,it.revision,"Доступ к геопозиции отозван. Разреши точную геопозицию и доступ «Всегда», затем включи сценарий снова.") }
        } catch(_: Exception) {
            rules.forEach { store.attention(it.id,it.revision,"Сервисы Google Play не зарегистрировали геозону. Проверь разрешения и повтори включение.") }
        }
    }
    suspend fun registerGeofences(store: AutomationStore, fingerprint: String, force: Boolean, remove: suspend () -> Unit, add: suspend () -> Unit): Boolean {
        if(!force && store.state("geofences") == fingerprint) return false
        store.setState("geofences","!unregistered")
        remove()
        add()
        store.setState("geofences",fingerprint)
        return true
    }
    fun geofence(context: Context, intent: Intent, done: () -> Unit) = launch(context,done) { store ->
            val event = GeofencingEvent.fromIntent(intent) ?: return@launch
            if(event.hasError()) {
                store.setState("geofences","!unregistered")
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
                if(store.transition("geo:${fence.requestId}",transition)) executor(context,store).run(rule.id,rule.revision,AutomationEvent("geofence","geo:$timestamp:$transition",transition))
            }
    }
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if(continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if(continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
