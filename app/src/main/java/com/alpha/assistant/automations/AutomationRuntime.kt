package com.alpha.assistant.automations

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
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
import java.util.concurrent.ConcurrentHashMap

data class AutomationApproval(val rule: AutomationRule, val archiveToken: String?)
enum class AutomationResumeResult { RESUMED, REVIEW_REQUIRED, MISSING }

object AutomationRuntime {
    const val CHANGED = "com.alpha.assistant.AUTOMATIONS_CHANGED"
    const val ALARM = "com.alpha.assistant.AUTOMATION_ALARM"
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex = Mutex()
    private val alarmLock = Any()
    private val geofenceMutex = Mutex()
    private val deleting = ConcurrentHashMap.newKeySet<String>()
    fun launch(context: Context, done: (() -> Unit)? = null, work: suspend (AutomationStore) -> Unit) {
        val app = context.applicationContext
        val library=runCatching {ToolLibraryStorage.current(app)}
        scope.launch {
            mutex.withLock {
                try {
                    val selected=library.getOrThrow()
                    ToolLibraryStorage.requireUsable(app,selected)
                    AutomationStore(app,selected.automation).use {work(it)}
                }
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
                ToolSpec.availablePersisted(target.spec)==null -> "The target artifact protocol is unavailable. Its saved data remains intact."
                target.revision != action.args.getInt("revision") -> "Версия целевого инструмента изменилась. Проверь сценарий."
                else -> null
            }
        }
    suspend fun prepareApproval(store: AutomationStore, id: String, revision: Int): AutomationApproval = mutex.withLock {
        val rule=checkNotNull(store.rule(id))
        check(rule.revision == revision && id !in deleting) { "Сценарий изменился. Проверь текущую версию." }
        AutomationApproval(rule,store.approvalToken(id))
    }

    suspend fun approve(store: AutomationStore, approval: AutomationApproval, validate: (AutomationRule) -> Unit) = mutex.withLock {
        val expected=approval.rule
        fun checkApproval() {
            check(store.approvalToken(expected.id)==approval.archiveToken && expected.id !in deleting) {
                "Разрешение на запуск изменилось. Проверь сценарий и включи его заново."
            }
        }
        checkApproval()
        val current=checkNotNull(store.rule(expected.id))
        check(current.revision == expected.revision) { "Сценарий изменился. Проверь текущую версию." }
        validate(current)
        store.transaction {
            checkApproval()
            store.approve(expected.id,expected.revision)
        }
    }

    private fun eligibilityFailure(rule: AutomationRule,validate: (AutomationRule)->String?): String? =
        validate(rule) ?: if(rule.definition.scheduled && rule.definition.nextAfter(System.currentTimeMillis())==null) {
            "Выбери будущее время расписания."
        } else null

    internal suspend fun activateApproved(store: AutomationStore,approval: AutomationApproval,validate: (AutomationRule)->String?) {
        approve(store,approval) { current ->
            eligibilityFailure(current,validate)?.let { failure ->
                store.attention(current.id,current.revision,failure)
                error(failure)
            }
        }
    }

    internal suspend fun resumeApproved(store: AutomationStore,expected: AutomationRule,validate: (AutomationRule)->String?): AutomationResumeResult = mutex.withLock {
        val current=store.rule(expected.id) ?: return@withLock AutomationResumeResult.MISSING
        if(current.revision!=expected.revision || current.approvedRevision!=current.revision ||
            current.attention.isNotEmpty() || current.id in deleting) return@withLock AutomationResumeResult.REVIEW_REQUIRED
        val token=store.approvalToken(current.id)
        val failure=eligibilityFailure(current,validate)
        if(failure!=null) {
            store.attention(current.id,current.revision,failure)
            return@withLock AutomationResumeResult.REVIEW_REQUIRED
        }
        if(store.resumeApproved(current.id,current.revision,token)) AutomationResumeResult.RESUMED
        else AutomationResumeResult.REVIEW_REQUIRED
    }

    fun pause(context: Context,store: AutomationStore,expected: AutomationRule): Boolean {
        synchronized(alarmLock) {
            if(!store.pause(expected.id,expected.revision)) return false
            cancelAlarm(context,store,expected)
        }
        notifyChanged(context)
        if(expected.definition.type=="geofence") refreshDatabase(context,store.databaseName)
        return true
    }

    suspend fun resume(context: Context,store: AutomationStore,expected: AutomationRule): AutomationResumeResult {
        val result=resumeApproved(store,expected) {accessFailure(context,store,it)}
        notifyChanged(context)
        if(result==AutomationResumeResult.RESUMED) refreshDatabase(context,store.databaseName)
        return result
    }

    internal suspend fun activate(context: Context,store: AutomationStore,approval: AutomationApproval) {
        try { activateApproved(store,approval) {accessFailure(context,store,it)} }
        finally {notifyChanged(context)}
        refreshDatabase(context,store.databaseName)
    }

    internal suspend fun deleteRule(context: Context,store: AutomationStore,expected: AutomationRule) {
        check(deleting.add(expected.id)) { "Сценарий уже удаляется." }
        try {
            store.stopForDeletion(expected.id,expected.revision)
            cancelAlarm(context,store,expected)
            mutex.withLock {
                geofenceMutex.withLock {
                    check(store.rule(expected.id)?.revision==expected.revision) {
                        "Сценарий изменился. Проверь текущую версию перед удалением."
                    }
                    val registered=store.geofenceRegistrations(expected.id)
                    if(registered.isNotEmpty()) {
                        check(googleAvailable(context)) { "Сервисы Google Play не отключили геозону. Сценарий остановлен." }
                        val client=LocationServices.getGeofencingClient(context)
                        for(batch in registered.chunked(100)) {
                            try {
                                val generation=rulesGeneration(context,store)
                                withTimeout(4000) {client.removeGeofences(batch.map {geofenceId(generation,it)}).awaitTask()}
                            } catch(failure: TimeoutCancellationException) {
                                error("Сервисы Google Play не отключили геозону. Сценарий остановлен.")
                            }
                        }
                    }
                    store.removeStoppedRule(expected.id,expected.revision)
                }
            }
        } finally {
            deleting.remove(expected.id)
            notifyChanged(context)
        }
    }

    private fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
    }

    private fun refreshDatabase(context: Context,database: String) {
        val app=context.applicationContext
        scope.launch {
            mutex.withLock {
                try {AutomationStore(app,database).use {reconcile(app,it)}}
                catch(cancel: CancellationException) {throw cancel}
                catch(_: Exception) { /* A disabled rule remains fenced if scheduling fails. */ }
                finally {notifyChanged(app)}
            }
        }
    }

    internal fun activationPermissions(context: Context,rule: AutomationRule): Array<String> = buildSet {
        if(rule.definition.type=="geofence" && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if(rule.definition.type=="geofence" && Build.VERSION.SDK_INT==29 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        addAll(oneTimePermissions(context,rule.definition))
    }.toTypedArray()

    internal fun oneTimePermissions(context: Context,definition: AutomationDefinition): Array<String> = buildSet {
        definition.actions.forEach { action ->
            val capability=if(action.capability=="calendar.read.rolling") "calendar.read" else action.capability
            if(capability in CapabilityCatalog.ids) CapabilityCatalog.definition(capability).permission?.let { permission ->
                if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) add(permission)
            }
        }
    }.toTypedArray()

    internal fun oneTimeSettings(context: Context,definition: AutomationDefinition): Intent? =
        if(definition.actions.any {it.capability=="notification.post"} && notificationUnavailable(context))
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
        else null

    internal fun activationSettings(context: Context,rule: AutomationRule): Intent? {
        val appUri=Uri.parse("package:${context.packageName}")
        return when {
            rule.definition.type=="schedule" && rule.definition.trigger.optBoolean("exact") && !exactAllowed(context) ->
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,appUri)
            rule.definition.type=="notification" && !listenerAllowed(context) ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            rule.definition.type=="geofence" && !locationAllowed(context) ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,appUri)
            rule.definition.type=="geofence" && !context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled ->
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            rule.definition.actions.any {it.capability=="notification.post"} &&
                !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
            else -> null
        }
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
            }
        }
        ToolStore.effectMutex.withLock { tools.setArchived(saved,true) }
      }
    }
    private const val TOOL_REVISION_CHANGED="Версия целевого инструмента изменилась. Проверь сценарий."
    private const val TOOL_DELETED="Целевой инструмент удалён. Все действия сценария остановлены."

    internal suspend fun <T> withToolRevisionChange(tools: ToolStore,owner: String,change: suspend ArtifactCoordinator.(AutomationStore)->T): T =
        mutex.withLock {
            ArtifactCoordinator.get(tools,owner).ordered {
                ToolStore.effectMutex.withLock { AutomationStore(tools.context,tools.automationDatabase).use { change(it) } }
            }
        }

    private const val TOOL_CHANGE_PAUSE="Сценарий остановлен перед операцией с мини-приложением. Проверь его и включи сценарий вручную."
    private data class PausedDependency(val rule: AutomationRule,val token: String,val log: AutomationRun?)
    internal data class ToolRevisionImpact(val id: String,val revision: Int,val name: String,val enabled: Boolean,
        val approvalToken: String?)

    private fun toolRevisionImpact(rules: AutomationStore,dependent: List<AutomationRule>) =
        dependent.sortedBy { it.id }.map {
            ToolRevisionImpact(it.id,it.revision,it.definition.name,it.enabled,rules.approvalToken(it.id))
        }

    /** The caller holds the revision ordering. The transaction also fences direct rule pause and edit. */
    internal fun toolRevisionImpact(rules: AutomationStore,owner: String): List<ToolRevisionImpact> {
        lateinit var impact: List<ToolRevisionImpact>
        rules.transaction { impact=toolRevisionImpact(rules,rules.rules().filter { dependencies(it,owner) }) }
        return impact
    }

    internal fun commitToolRevision(tools: ToolStore,rules: AutomationStore,owner: String,
        validateImpact: ((List<ToolRevisionImpact>)->Unit)?=null,commit: (List<StoppedAutomation>)->Unit) {
        commitToolChange(tools,rules,owner,TOOL_REVISION_CHANGED) { dependent ->
            validateImpact?.invoke(toolRevisionImpact(rules,dependent))
            commit(dependent.filter { it.enabled }.map { StoppedAutomation(it.id,it.definition.name) })
        }
    }

    private fun commitToolChange(tools: ToolStore,rules: AutomationStore,owner: String,reason: String,
        mutate: (List<AutomationRule>)->Unit) {
        lateinit var paused: List<PausedDependency>
        tools.transaction {
            rules.transaction {
                val dependent=rules.rules().filter { dependencies(it,owner) }
                mutate(dependent)
                paused=dependent.map { rule ->
                    val token=java.util.UUID.randomUUID().toString()
                    val log=if(rule.enabled) {
                        val event=AutomationEvent("manual","tool-change:$token")
                        rules.begin(rule,event,System.currentTimeMillis(),false)?.let { rules.finish(it,"revoked",TOOL_CHANGE_PAUSE) }
                    } else null
                    rules.attention(rule.id,rule.revision,TOOL_CHANGE_PAUSE)
                    rules.setState("approval-archive:${rule.id}",token)
                    PausedDependency(rule,token,log)
                }
            }
        }
        if(paused.isEmpty()) return
        // The two databases can commit separately. Record the outcome only after the tool commit.
        try {
            rules.transaction {
                paused.forEach { (rule,token,log) ->
                    if(rules.state("approval-archive:${rule.id}")==token) rules.attention(rule.id,rule.revision,reason)
                    if(log!=null) rules.finish(log,"revoked",reason)
                }
            }
        } catch(failure: Exception) {
            android.util.Log.e("ToolAutomation","The tool change committed. The pause remains recorded.",failure)
        }
    }

    suspend fun deletionSnapshot(tools: ToolStore,rules: AutomationStore,expected: ToolRecord): ToolDeletionSnapshot = mutex.withLock {
        ArtifactCoordinator.get(tools,expected.id).ordered {
            check(expected.saved && tools.tool(expected.id)==expected && (expected.archived || current(tools,expected.id)==expected)) {
                "Инструмент изменился. Открой текущую версию."
            }
            ToolStore.effectMutex.withLock {
                deletionScope(tools,expected,rules.rules().filter { dependencies(it,expected.id) })
            }
        }
    }

    private fun deletionScope(tools: ToolStore,tool: ToolRecord,dependent: List<AutomationRule>): ToolDeletionSnapshot {
        val owner=tool.id
        val medicine=MedicineStore(tools.context,MedicineRuntime.database(tools)).use { it.deletionFingerprint(owner) }
        return ToolDeletionSnapshot(tool,tools.revisions(owner).size,ToolWidgets.bindings(tools.context,owner),
            dependent.map { StoppedAutomation(it.id,it.definition.name) },tools.deletionFingerprint(owner),medicine,
            dependent.associate { it.id to it.revision })
    }

    suspend fun deleteTool(tools: ToolStore,rules: AutomationStore,snapshot: ToolDeletionSnapshot): Boolean = mutex.withLock {
        val expected=snapshot.tool
        val owner=expected.id
        ArtifactCoordinator.get(tools,owner).ordered {
            check(expected.archived || current(tools,owner)==expected) { "Инструмент изменился. Подтверди удаление ещё раз." }
            ToolStore.effectMutex.withLock {
                CapabilityResources(tools.context,tools).ordered {
                    ToolWidgets.withBindings {
                        commitToolChange(tools,rules,owner,TOOL_DELETED) { dependent ->
                            check(tools.tool(owner)==expected && deletionScope(tools,expected,dependent)==snapshot) {
                                "Состав данных изменился. Подтверди удаление ещё раз."
                            }
                            tools.beginDeletion(snapshot)
                        }
                    }
                }
                invalidate()
                finishToolDeletion(tools,owner,snapshot.widgetIds)
            }
        }
    }

    suspend fun recoverToolDeletions(tools: ToolStore): Boolean = mutex.withLock {
        ToolStore.effectMutex.withLock {
            val results=tools.pendingDeletions().map { (owner,widgets) -> finishToolDeletion(tools,owner,widgets) }
            results.all { it }
        }
    }

    private fun finishToolDeletion(tools: ToolStore,owner: String,widgets: Set<Int>): Boolean {
        val context=tools.context
        return try {
            val resourcesReleased=CapabilityResources(context,tools).releaseOwner(owner)
            ToolWidgets.removeDeletedBindings(context,owner,widgets,tools)
            SurfaceEntry.removeToolPins(context,owner,tools.primaryLibrary)
            MedicineStore(context,MedicineRuntime.database(tools)).use { it.deleteOwner(owner,context) }
            if(resourcesReleased) tools.completeDeletion(owner)
            resourcesReleased
        } catch(failure: Exception) {
            android.util.Log.e("ToolDeletion","Cleanup remains pending.",failure)
            false
        }
    }

    private fun dependencyFailure(context: Context,store: AutomationStore,rule: AutomationRule) = toolsForRules(context,store).use {dependencyFailure(it,rule)}
    private fun actionFailure(context: Context,store: AutomationStore,action: CapabilityRequest): String? {
        if(action.capability in setOf("tool.state.set","tool.records.calendar")) return toolsForRules(context,store).use { tools ->
            val tool = tools.tool(action.args.getString("toolId"))
            when {
                tool == null || tool.archived || tool.revision != action.args.getInt("revision") -> "Целевой инструмент изменился или недоступен. Проверь и одобри новую версию сценария."
                else -> runCatching {
                    val spec=ToolSpec.parsePersisted(tool.spec)
                    if(action.capability == "tool.records.calendar") AutomationDataflow.validateCollection(spec,action.args.getString("field"))
                    else {
                        require(spec.schema.get(action.args.getString("field")) is String)
                        spec.validateState(JSONObject(tool.state).put(action.args.getString("field"),action.args.get("value")).toString())
                    }
                    null
                }.getOrElse { "Новое значение не соответствует полю инструмента." }
            }
        }
        nativeActionFailure(context,action)?.let {return it}
        val nativeId=if(action.capability == "calendar.read.rolling") "calendar.read" else action.capability
        if(nativeId == "calendar.read" && AndroidCapabilities(context).calendars().none { it.first == action.args.getLong("calendarId") })
            return "Выбранный календарь недоступен. Проверь источник и включи сценарий снова."
        return null
    }

    private fun notificationUnavailable(context: Context): Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return !manager.areNotificationsEnabled() || manager.getNotificationChannel("alpha-tools")?.importance==NotificationManager.IMPORTANCE_NONE
    }

    private fun nativeActionFailure(context: Context,action: CapabilityRequest): String? {
        if(action.capability in setOf("tool.state.set","tool.records.calendar")) return null
        val id=if(action.capability=="calendar.read.rolling") "calendar.read" else action.capability
        return when {
            !AndroidCapabilities(context).permissionGranted(id) -> "Доступ Android к действию отозван: ${CapabilityCatalog.definition(id).title}."
            id=="notification.post" && notificationUnavailable(context) -> "Уведомления Alphy выключены в Android."
            else -> null
        }
    }
    private fun accessFailure(context: Context,store: AutomationStore,rule: AutomationRule): String? = try {
        triggerFailure(context,rule) ?: dependencyFailure(context,store,rule) ?:
            rule.definition.actions.firstNotNullOfOrNull {actionFailure(context,store,it)}
    } catch(_: SecurityException) {
        "Доступ Android отозван. Проверь разрешения и включи сценарий снова."
    }

    fun executor(context: Context, store: AutomationStore) = AutomationExecutor(store,{ charging(context) },{ rule,action ->
        triggerFailure(context,rule) ?: dependencyFailure(context,store,rule) ?: actionFailure(context,store,action)
    },preview={ rule,action,step -> perform(context,store,rule,action,step) }) { rule,action,step -> perform(context,store,rule,action,step) }

    private fun toolsForRules(context: Context,store: AutomationStore): ToolStore =
        ToolStore(context,ToolLibraryStorage.relatedSelection(context,store.databaseName,"automations")?.physical ?: ToolLibraryStorage.DEFAULT)

    private fun oneTime(context: Context,tools: ToolStore,launch: (Intent)->Unit = {error("Preview cannot open an app.")}): AutomationOneTime {
        val android=AndroidCapabilities(context)
        val renderer by lazy {AutomationTools.isolatedRenderer(context)}
        return AutomationOneTime(tools,{charging(context)},{nativeActionFailure(context,it)},readCalendar={ request ->
            check(android.calendars().any {it.first==request.args.getLong("calendarId")}) {"Выбранный календарь недоступен."}
            android.readAutomationCalendar(request)
        },render={tool,state -> renderer(tool,state)},perform={action,authority ->
            authority.commit {}
            android.execute(action,true) {intent -> withContext(Dispatchers.Main.immediate) {authority.commit {launch(intent)}}}
        })
    }

    internal suspend fun requestOneTime(store: AutomationStore,rule: AutomationRule): AutomationOneTime.Request = mutex.withLock {
        check(rule.id !in deleting) {"Сценарий удаляется."}
        AutomationOneTime.request(store,rule)
    }

    internal suspend fun prepareOneTime(context: Context,store: AutomationStore,request: AutomationOneTime.Request): AutomationOneTime.Proposal = mutex.withLock {
        check(request.ruleId !in deleting) {"Сценарий удаляется."}
        toolsForRules(context,store).use {oneTime(context,it).prepare(store,request)}
    }

    internal suspend fun confirmOneTime(context: Context,store: AutomationStore,proposal: AutomationOneTime.Proposal,
        foreground: ()->Boolean,launch: (Intent)->Unit): AutomationRun? = mutex.withLock {
        toolsForRules(context,store).use {tools ->
            val before=tools.changes.value
            try {
                oneTime(context,tools,launch).confirm(store,proposal) {proposal.request.ruleId !in deleting && foreground()}
            } finally {
                if(tools.changes.value!=before) {
                    if(tools.primaryLibrary) ToolWidgets.updateAll(context)
                    context.sendBroadcast(Intent(SurfaceEntry.TOOLS_CHANGED).setPackage(context.packageName))
                }
                notifyChanged(context)
            }
        }
    }

    private suspend fun perform(context: Context, store: AutomationStore, rule: AutomationRule, action: CapabilityRequest, step: AutomationStep): CapabilityResult {
        if(!step.dry && !store.active(rule.id,rule.revision)) return CapabilityResult("cancelled","Сценарий остановлен.")
        if(action.capability in setOf("tool.state.set","tool.records.calendar")) {
            val result=toolsForRules(context,store).use { tools -> AutomationTools.execute(tools,rule.definition,action,step,AutomationTools.renderer(context,tools)) {store.active(rule.id,rule.revision)} }
            if(!step.dry) {
                ToolWidgets.updateAll(context)
                context.sendBroadcast(Intent(SurfaceEntry.TOOLS_CHANGED).setPackage(context.packageName))
            }
            return result
        }
        if(action.capability in setOf("calendar.read","calendar.read.rolling")) {
            val resolved=AutomationDataflow.resolveRead(action,step.now)
            val result=AndroidCapabilities(context).readAutomationCalendar(resolved)
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
        val denied=triggerFailure(context,rule) ?: dependencyFailure(context,store,rule) ?: actionFailure(context,store,action)
        if(denied != null) { store.attention(rule.id,rule.revision,denied); return store.finish(run,"revoked",denied,run.details) }
        if(!store.active(rule.id,rule.revision)) return store.finish(run,"cancelled","Сценарий выключен или изменён.",run.details)
        val result=try { AndroidCapabilities(context).execute(action,true,launch) }
            catch(_: Exception) { CapabilityResult("unknownOutcome","Результат открытия неизвестен. Автоматического повтора нет.") }
        store.effect(run.id,action.id,result.json().toString())
        details.put(result.json().put("action",action.id))
        return store.finish(run,result.status,result.message,details.toString())
    }
    fun refresh(context: Context, resetGeofences: Boolean = false) = launch(context) { reconcile(context,it,resetGeofences) }
    private fun rulesGeneration(context: Context,store: AutomationStore) =
        ToolLibraryStorage.relatedSelection(context,store.databaseName,"automations")?.generation ?: ToolLibraryStorage.ORIGINAL
    private fun alarmIntent(context: Context,store: AutomationStore,rule: AutomationRule): Intent {
        val generation=rulesGeneration(context,store)
        val prefix=if(generation==ToolLibraryStorage.ORIGINAL) "" else "$generation/"
        return Intent(context,AutomationReceiver::class.java).setAction(ALARM)
            .setData(Uri.parse("alpha-automation://alarm/$prefix${rule.id}"))
            .putExtra(ToolLibraryStorage.GENERATION_EXTRA,generation)
            .putExtra("id",rule.id).putExtra("revision",rule.revision).putExtra("at",rule.nextAt ?: 0)
    }
    private fun alarm(context: Context,store: AutomationStore,rule: AutomationRule) = PendingIntent.getBroadcast(context,0,alarmIntent(context,store,rule),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelAlarm(context: Context,store: AutomationStore,rule: AutomationRule) = synchronized(alarmLock) {
        val pending=PendingIntent.getBroadcast(context,0,alarmIntent(context,store,rule),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if(pending!=null) {
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }

    private fun cancelInactiveAlarm(context: Context,store: AutomationStore,rule: AutomationRule) = synchronized(alarmLock) {
        val current=store.rule(rule.id)
        if(current==null || !current.enabled || current.approvedRevision!=current.revision || current.attention.isNotEmpty()) {
            cancelAlarm(context,store,rule)
        }
    }

    suspend fun reconcile(context: Context, store: AutomationStore, resetGeofences: Boolean = false) {
        store.prune()
        val manager = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        for(rule in store.rules()) {
            if(rule.approvedRevision!=rule.revision || rule.attention.isNotEmpty()) {cancelInactiveAlarm(context,store,rule);continue}
            val failure=accessFailure(context,store,rule)
            if(failure!=null) {store.attention(rule.id,rule.revision,failure);cancelInactiveAlarm(context,store,rule);continue}
            if(!rule.enabled) {cancelInactiveAlarm(context,store,rule);continue}
            if(!rule.definition.scheduled) continue
            val token=store.approvalToken(rule.id)
            var at=rule.nextAt
            if(at!=null && at<now-AutomationSchedule.MAX_LATENESS) {
                val missed=AutomationEvent("schedule",rule.definition.occurrenceToken(at))
                store.begin(rule,missed,now,false)?.let { store.finish(it,"missed","Время прошло, пока расписание было недоступно. Просроченное действие не воспроизводилось.") }
                at=null
            }
            if(at==null || resetGeofences && at>now && rule.definition.type!="interval") at=rule.definition.nextAfter(now)
            synchronized(alarmLock) {
                val current=store.rule(rule.id)
                if(current==null || !current.enabled || current.revision!=rule.revision ||
                    current.approvedRevision!=current.revision || current.attention.isNotEmpty() ||
                    current.nextAt!=rule.nextAt || store.approvalToken(rule.id)!=token) return@synchronized
                store.next(rule.id,rule.revision,at)
                if(at==null) {cancelAlarm(context,store,rule);return@synchronized}
                val pending=alarm(context,store,rule.copy(nextAt=at))
                try {
                    if(rule.definition.trigger.optBoolean("exact")) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending)
                    else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending)
                } catch(_: SecurityException) {
                    store.attention(rule.id,rule.revision,"Android запретил расписание. Проверь доступ к точным напоминаниям.")
                    cancelAlarm(context,store,rule)
                }
            }
        }
        com.alpha.assistant.tools.ToolStore.effectMutex.withLock {
            toolsForRules(context,store).use {tools -> MedicineStore(context,MedicineRuntime.database(tools)).use {medicine -> MedicineRuntime.reconcile(context,tools,medicine)}}
        }
        geofenceMutex.withLock {syncGeofences(context,store,resetGeofences)}
    }
    fun alarmReceived(context: Context, intent: Intent, done: () -> Unit) {
        launch(context,done) { store ->
            if(ToolLibraryStorage.intentGeneration(intent)!=rulesGeneration(context,store)) return@launch
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
    private fun geoBroadcast(context: Context,generation: String): Intent =
        Intent(context,AutomationGeofenceReceiver::class.java).setAction("com.alpha.assistant.GEOFENCE")
            .putExtra(ToolLibraryStorage.GENERATION_EXTRA,generation)
            .apply {if(generation!=ToolLibraryStorage.ORIGINAL) data=Uri.parse("alpha-automation://geofence/$generation")}

    private fun geoIntent(context: Context,store: AutomationStore): PendingIntent = PendingIntent.getBroadcast(
        context,0,geoBroadcast(context,rulesGeneration(context,store)),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    private fun geofenceId(generation: String,id: String)=if(generation==ToolLibraryStorage.ORIGINAL) id else "$generation:$id"

    private suspend fun syncGeofences(context: Context, store: AutomationStore, force: Boolean) {
        if(!googleAvailable(context)) return
        val rules = store.rules().filter { store.active(it.id,it.revision) && it.definition.type == "geofence" }
        val fingerprint = rules.joinToString { "${it.id}:${it.revision}" }
        val client = LocationServices.getGeofencingClient(context)
        try {
            registerGeofences(store,fingerprint,force,
                {withTimeout(4000) { client.removeGeofences(geoIntent(context,store)).awaitTask() }}) {
                if(rules.isNotEmpty()) {
                    val fences = rules.map { rule ->
                        store.setState("geo:${rule.id}:${rule.revision}","")
                        if(force) store.setState("geo-clock:${rule.id}:${rule.revision}","-1")
                        val t = rule.definition.trigger
                        Geofence.Builder().setRequestId(geofenceId(rulesGeneration(context,store),"${rule.id}:${rule.revision}"))
                            .setCircularRegion(t.getDouble("latitude"),t.getDouble("longitude"),t.getDouble("radius").toFloat())
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).setExpirationDuration(Geofence.NEVER_EXPIRE).build()
                    }
                    withTimeout(4000) {
                        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
                            throw SecurityException("Доступ к геопозиции отозван")
                        client.addGeofences(GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build(),geoIntent(context,store)).awaitTask()
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
            val generation=rulesGeneration(context,store)
            if(ToolLibraryStorage.intentGeneration(intent)!=generation) return@launch
            val event = GeofencingEvent.fromIntent(intent) ?: return@launch
            if(event.hasError()) {
                store.setState("geofences","!unregistered")
                store.rules().filter { it.definition.type == "geofence" && it.enabled }.forEach { store.attention(it.id,it.revision,"Сервисы Google Play сообщили ошибку геозоны: ${event.errorCode}. Проверь доступ и включи сценарий снова.") }
                return@launch
            }
            val transition = when(event.geofenceTransition) { Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"; Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"; else -> return@launch }
            for(fence in event.triggeringGeofences.orEmpty()) {
                val id=if(generation==ToolLibraryStorage.ORIGINAL) fence.requestId
                    else fence.requestId.takeIf {it.startsWith("$generation:")}?.removePrefix("$generation:") ?: continue
                val pieces = id.split(':'); if(pieces.size != 2) continue
                val rule = store.rule(pieces[0]) ?: continue
                if(rule.revision != pieces[1].toIntOrNull() || !store.active(rule.id,rule.revision)) continue
                val timestamp = event.triggeringLocation?.elapsedRealtimeNanos ?: continue
                val clockKey = "geo-clock:$id"
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
