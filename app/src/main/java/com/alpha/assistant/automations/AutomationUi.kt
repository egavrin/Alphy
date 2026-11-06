package com.alpha.assistant.automations

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import com.alpha.assistant.R
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private fun date(at: Long?) = at?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it)) } ?: "Нет"
private val testTimeFormat=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS XXX")
private fun testTime(at: Long)=testTimeFormat.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
private fun status(status: String) = when(status) {
    "succeeded" -> "Выполнено"; "unknownOutcome" -> "Результат неизвестен"; "awaitingUser" -> "Нужно открыть Alphy и выполнить действие вручную"
    "cancelled" -> "Отменено"; "skipped" -> "Условия не выполнены"; "dryRun" -> "Проверка без действий"; "missed" -> "Пропущено"
    "launched" -> "Открыт интерфейс приложения"; "denied","revoked" -> "Нужен доступ"; "unavailable" -> "Недоступно"; "failed" -> "Ошибка"; else -> status
}
private fun triggerText(d: AutomationDefinition, appLabels: Map<String,String> = emptyMap()): String {
    val t = d.trigger
    return when(d.type) {
        "manual" -> "Только по кнопке «Запустить»"
        "interval" -> "Каждые ${t.getInt("minutes")} минут. Приблизительное фоновое выполнение, начиная с ${date(t.getLong("anchor"))}."
        "charging" -> if(t.getString("connection") == "connected") "При подключении зарядки" else "При отключении зарядки"
        "notification" -> "Новое уведомление от: ${d.notificationPackages().joinToString { "${appLabels[it] ?: it} ($it)" }}. Текст уведомления не читается."
        "geofence" -> "${if(t.getString("transition") == "enter") "Вход" else "Выход"}: ${t.getDouble("latitude")}, ${t.getDouble("longitude")}, радиус ${t.getDouble("radius").toInt()} м"
        else -> "${when(t.getString("repeat")) { "once" -> t.getString("date"); "daily" -> "Ежедневно"; else -> "Дни недели: ${t.getJSONArray("days")}" }} в ${t.getString("time")}, ${if(t.getString("zone") == "device") "часовой пояс устройства (${ZoneId.systemDefault().id})" else t.getString("zone")}. ${if(t.getBoolean("exact")) "Точное напоминание Android" else "Приблизительное фоновое выполнение Android"}."
    }
}
private fun actionText(d: AutomationDefinition, tools: ToolController): String = d.actions.joinToString("\n") { action ->
    val a = action.args
    when(action.capability) {
        "notification.post" -> "Уведомление Alphy: «${a.getString("title") }» — ${a.getString("text") }"
        "calendar.read" -> "Календарь ${a.getLong("calendarId")}: ${date(a.getLong("from"))} — ${date(a.getLong("to"))}. До 50 событий, только на устройстве."
        "calendar.read.rolling" -> "Каждый запуск: календарь ${a.getLong("calendarId")}, от начала текущего дня на ${a.getInt("days")} дн., ${if(a.getString("zone") == "device") "часовой пояс устройства (${ZoneId.systemDefault().id})" else a.getString("zone")}. До 50 событий, только на устройстве."
        "tool.records.calendar" -> "Результат «${a.getString("source") }» → список «${a.getString("field") }» инструмента ${tools.tools.find { it.id == a.getString("toolId") }?.let { ToolSpec.storedTitle(it.spec) } ?: a.getString("toolId")}, версия ${a.getInt("revision")}. Импортированные записи этого календаря заменяются текущими. Ручные записи сохраняются."
        "tool.state.set" -> "Инструмент ${tools.tools.find { it.id == a.getString("toolId") }?.let { ToolSpec.storedTitle(it.spec) } ?: a.getString("toolId")}, версия ${a.getInt("revision")}: ${a.getString("field")} = ${a.get("value")}"
        "calendar.create" -> "Форма события «${a.getString("title")}»: ${date(a.getLong("start"))} — ${date(a.getLong("end"))}"
        "app.open" -> "Открыть: ${a.getString("app")}. В фоне ожидает пользователя."
        "link.open" -> "Ссылка: ${a.getString("url")}. В фоне ожидает пользователя."
        "maps.open" -> "Карта: ${a.getString("query")}. В фоне ожидает пользователя."
        else -> "${action.capability}: ${a}"
    }
}
private fun conditionText(d: AutomationDefinition): String = buildList {
    if(d.conditions.has("charging")) add(if(d.conditions.getBoolean("charging")) "Зарядка подключена" else "Зарядка отключена")
    if(d.conditions.has("fromMinute")) {
        fun time(key: String) = d.conditions.getInt(key).let { "%02d:%02d".format(it/60,it%60) }
        add("Время устройства: ${time("fromMinute")} — ${time("toMinute")}")
    }
}.joinToString("; ").ifEmpty { "Без дополнительных условий" }

@Composable private fun OneTimeStep(index: Int,step: AutomationOneTime.Step) {
    val target=when(step.capability) {
        "notification.post" -> stringResource(R.string.automation_test_notification,step.target)
        "calendar.read","calendar.read.rolling" -> stringResource(R.string.automation_test_calendar,step.target)
        "calendar.create" -> stringResource(R.string.automation_test_calendar_form,step.target)
        "link.open" -> stringResource(R.string.automation_test_link,step.target)
        "maps.open" -> stringResource(R.string.automation_test_maps,step.target)
        "app.open" -> stringResource(R.string.automation_test_app,stringResource(when(step.target) {
            "settings" -> R.string.automation_test_app_settings
            "camera" -> R.string.automation_test_app_camera
            "browser" -> R.string.automation_test_app_browser
            else -> R.string.automation_test_app_calendar
        }))
        else -> step.target
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.automation_test_step,index+1,target),style=MaterialTheme.typography.titleSmall)
        if(step.from!=null && step.to!=null) Text(stringResource(R.string.automation_test_range,testTime(step.from),testTime(step.to)))
        val readAt=step.readAt
        if(readAt!=null) {
            Text(stringResource(R.string.automation_test_calendar_source,testTime(readAt)))
            val events=JSONArray(checkNotNull(step.after))
            if(events.length()==0) Text(stringResource(R.string.automation_test_calendar_empty))
            if(events.length()==50) Text(stringResource(R.string.automation_test_calendar_limit))
            repeat(events.length()) {eventIndex ->
                val event=events.getJSONObject(eventIndex)
                Text(stringResource(R.string.automation_test_event,event.getString("title"),testTime(event.getLong("start")),testTime(event.getLong("end"))))
            }
        } else {
            when(step.capability) {
                "tool.state.set","tool.records.calendar" -> {
                    Text(stringResource(R.string.automation_test_tool_target,checkNotNull(step.toolId),checkNotNull(step.toolRevision)))
                    Text(stringResource(R.string.automation_test_before,checkNotNull(step.before)))
                    Text(stringResource(R.string.automation_test_after,checkNotNull(step.after)))
                    if(step.uiChanged) Text(stringResource(R.string.automation_test_ui_changed))
                }
                "notification.post" -> step.after?.let {Text(it)}
                "calendar.create" -> step.after?.let {Text(stringResource(R.string.automation_test_calendar_location,it))}
            }
            if(step.capability in setOf("app.open","link.open","maps.open","calendar.create")) Text(stringResource(R.string.automation_test_external))
        }
    }
}

@Composable fun AutomationsPane(tools: ToolController, chatRequest: String? = null,
    openRuleId: String? = null, showAll: () -> Unit = {}, chatSetup: String? = null,
    finishSetup: () -> Unit = {}, consumeRequest: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appLabels=remember { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
        .associate { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() } }
    var rules by remember { mutableStateOf(emptyList<AutomationRule>()) }
    var loadedRules by remember { mutableStateOf(false) }
    var histories by remember { mutableStateOf(emptyMap<String,List<AutomationRun>>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var editing by remember(chatSetup) { mutableStateOf(chatSetup != null) }
    var editRule by remember { mutableStateOf<AutomationRule?>(null) }
    var editSection by remember { mutableStateOf(AutomationEditorSection.WHEN) }
    var proposal by remember(chatSetup) { mutableStateOf(chatSetup?.let { runCatching { AutomationDrafts.fromChat(it) }.getOrNull() }) }
    var approval by remember { mutableStateOf<AutomationApproval?>(null) }
    var activating by remember { mutableStateOf<AutomationApproval?>(null) }
    var requestingAccess by remember { mutableStateOf(false) }
    var requestedPermissions by remember { mutableStateOf(emptySet<String>()) }
    var visitedSettings by remember { mutableStateOf(emptySet<String>()) }
    var deleteConfirmation by remember { mutableStateOf<AutomationRule?>(null) }
    var pausing by remember { mutableStateOf(emptySet<String>()) }
    var errorRuleId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingTest by remember { mutableStateOf<AutomationOneTime.Request?>(null) }
    var testProposal by remember { mutableStateOf<AutomationOneTime.Proposal?>(null) }
    var testAccessInProgress by remember { mutableStateOf(false) }
    var testPermissionAsked by remember { mutableStateOf(false) }
    var testSettingsVisited by remember { mutableStateOf(false) }
    var testRunning by remember { mutableStateOf(false) }
    fun reload() { scope.launch {
        try { val loaded = withContext(Dispatchers.IO) { AutomationStore(context,tools.automationDatabase).use { store -> store.prune(); store.rules().let { r -> r to r.associate { it.id to store.history(it.id) } } } }
            rules=loaded.first; histories=loaded.second; loadedRules=true
        } catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { error="Не удалось открыть сценарии. Данные не удалены." }
    } }
    fun operation(ruleId: String? = null,work: suspend (AutomationStore) -> Unit) {
        if(busy) return
        busy=true; error=null;errorRuleId=ruleId
        scope.launch {
            try { withContext(Dispatchers.IO) { AutomationStore(context,tools.automationDatabase).use { work(it) } }; reload() }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) {
                error=failure.message?.takeIf { it.any { c -> c in 'А'..'я' || c == 'ё' } } ?: "Не удалось выполнить действие. Сохранённые данные доступны."
                if(ruleId!=null) reload()
            }
            finally { busy=false }
        }
    }
    fun edit(rule: AutomationRule?,section: AutomationEditorSection = AutomationEditorSection.WHEN) {
        editRule=rule; editSection=section; proposal=null; error=null; errorRuleId=null; editing=true
    }
    LaunchedEffect(chatRequest) {
        if(chatRequest != null) {
            runCatching {AutomationDrafts.fromChat(chatRequest)}.onSuccess {edit(null); proposal=it}.onFailure {error=it.message}
            consumeRequest()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { AutomationRuntime.refresh(context); reload() }
    val activationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requestingAccess=false }
    val activationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { requestingAccess=false }
    val testPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {testAccessInProgress=false}
    val testSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {testAccessInProgress=false}
    val testPermissionDenied by rememberUpdatedState(stringResource(R.string.automation_test_permission_denied))
    LaunchedEffect(pendingTest,testAccessInProgress,busy) {
        val request=pendingTest ?: return@LaunchedEffect
        if(testAccessInProgress || busy) return@LaunchedEffect
        val definition=AutomationDefinition.parse(JSONObject(request.definition))
        val missing=AutomationRuntime.oneTimePermissions(context,definition)
        val settingsIntent=if(missing.isEmpty()) AutomationRuntime.oneTimeSettings(context,definition) else null
        if(missing.isNotEmpty() && !testPermissionAsked) {
            testPermissionAsked=true
            testAccessInProgress=true
            try {testPermission.launch(missing);return@LaunchedEffect}
            catch(_: IllegalStateException) {testAccessInProgress=false}
        }
        if(settingsIntent!=null && !testSettingsVisited) {
            testSettingsVisited=true
            testAccessInProgress=true
            try {testSettings.launch(settingsIntent);return@LaunchedEffect}
            catch(_: ActivityNotFoundException) {testAccessInProgress=false}
            catch(_: SecurityException) {testAccessInProgress=false}
        }
        pendingTest=null
        if(missing.isNotEmpty() || settingsIntent!=null) {
            error=testPermissionDenied
            errorRuleId=request.ruleId
        } else operation(request.ruleId) {store ->
            val prepared=AutomationRuntime.prepareOneTime(context,store,request)
            withContext(Dispatchers.Main) {testProposal=prepared}
        }
    }
    LaunchedEffect(activating,requestingAccess) {
        val pending=activating ?: return@LaunchedEffect
        if(requestingAccess) return@LaunchedEffect
        val rule=pending.rule
        val missing=AutomationRuntime.activationPermissions(context,rule)
        val nextSettings=if(missing.isEmpty()) AutomationRuntime.activationSettings(context,rule) else null
        if(missing.isNotEmpty() && missing.none {it in requestedPermissions}) {
            requestedPermissions=requestedPermissions+missing
            requestingAccess=true
            try {
                activationPermission.launch(missing)
                return@LaunchedEffect
            } catch(_: IllegalStateException) {requestingAccess=false}
        }
        if(nextSettings?.action!=null && nextSettings.action !in visitedSettings) {
            visitedSettings=visitedSettings+checkNotNull(nextSettings.action)
            requestingAccess=true
            try {
                activationSettings.launch(nextSettings)
                return@LaunchedEffect
            } catch(_: ActivityNotFoundException) {requestingAccess=false}
              catch(_: SecurityException) {requestingAccess=false}
        }
        activating=null
        approval=null
        operation(rule.id) { store -> AutomationRuntime.activate(context,store,pending) }
    }
    fun pauseRule(rule: AutomationRule) {
        if(rule.id in pausing) return
        pausing=pausing+rule.id
        error=null
        errorRuleId=rule.id
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AutomationStore(context,tools.automationDatabase).use { store ->
                        check(AutomationRuntime.pause(context,store,rule)) { "Сценарий изменился. Проверь текущую версию." }
                    }
                }
                reload()
            } catch(cancel: CancellationException) {
                throw cancel
            } catch(failure: Exception) {
                error=failure.message ?: "Не удалось остановить сценарий."
            } finally {
                pausing=pausing-rule.id
            }
        }
    }
    fun resumeRule(rule: AutomationRule) {
        operation(rule.id) { store ->
            val next=when(AutomationRuntime.resume(context,store,rule)) {
                AutomationResumeResult.RESUMED -> null
                AutomationResumeResult.MISSING -> error("Сценарий удалён.")
                AutomationResumeResult.REVIEW_REQUIRED -> {
                    val current=checkNotNull(store.rule(rule.id)) { "Сценарий удалён." }
                    AutomationRuntime.prepareApproval(store,current.id,current.revision)
                }
            }
            withContext(Dispatchers.Main) {approval=next}
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { reload() } }
        val filter=IntentFilter(AutomationRuntime.CHANGED)
        ContextCompat.registerReceiver(context,receiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED)
        val activity=context as? ComponentActivity
        val observer=LifecycleEventObserver { _,event -> if(event == Lifecycle.Event.ON_RESUME) { AutomationRuntime.refresh(context); reload() } }
        activity?.lifecycle?.addObserver(observer)
        reload(); AutomationRuntime.refresh(context)
        onDispose {
            testProposal?.cancel()
            context.unregisterReceiver(receiver)
            activity?.lifecycle?.removeObserver(observer)
        }
    }
    testProposal?.let {proposal ->
        fun cancel() {proposal.cancel();testProposal=null}
        AlertDialog(onDismissRequest=::cancel,title={Text(stringResource(R.string.automation_test_title))},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.automation_test_rule,proposal.name,proposal.request.revision))
                Text(stringResource(R.string.automation_test_explanation))
                proposal.steps.forEachIndexed {index,step -> OneTimeStep(index,step)}
                if(testRunning) Text(stringResource(R.string.automation_test_running))
            }},confirmButton={TextButton(enabled=!busy && !testRunning,onClick={
                testRunning=true
                operation(proposal.request.ruleId) {store ->
                    try {
                        val activity=context as? ComponentActivity
                        AutomationRuntime.confirmOneTime(context,store,proposal,foreground={
                            activity!=null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                !activity.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                        }) {intent -> checkNotNull(activity).startActivity(intent)}
                    } finally {
                        withContext(Dispatchers.Main) {testProposal=null;testRunning=false;selected=proposal.request.ruleId}
                    }
                }
            }) {Text(stringResource(R.string.automation_test_run))}},dismissButton={TextButton(onClick=::cancel) {
                Text(stringResource(if(testRunning) R.string.automation_test_cancel_remaining else R.string.automation_test_cancel))
            }})
    }
    approval?.let { pending ->
        val rule=pending.rule
        AlertDialog(onDismissRequest={if(activating==null) approval=null},title={Text("Включить версию ${rule.revision}?")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(rule.definition.name); Text(triggerText(rule.definition,appLabels)); Text(conditionText(rule.definition)); Text(actionText(rule.definition,tools))
                Text("Разрешение действует для этой версии, целей и параметров. Android запросит нужный доступ при включении.")
                Text("При неизвестном результате повтора нет. Внешние приложения открываются по кнопке в журнале. Проверка без действий ничего не изменяет.")
            }},
            confirmButton={TextButton(enabled=!busy && activating==null,onClick={
                requestedPermissions=emptySet()
                visitedSettings=emptySet()
                activating=pending
            }) {Text("Включить")}},
            dismissButton={TextButton(enabled=activating==null,onClick={approval=null}) {Text("Отмена")}})
    }
    deleteConfirmation?.let { rule ->
        AlertDialog(onDismissRequest={deleteConfirmation=null},title={Text("Удалить сценарий?")},text={Column {
            Text(rule.definition.name)
            Text("Запуски остановятся.")
            Text("Журнал запусков хранится 30 дней.")
        }},confirmButton={TextButton(enabled=!busy,onClick={
            deleteConfirmation=null
            operation(rule.id) { store -> AutomationRuntime.deleteRule(context,store,rule) }
        }) {Text("Удалить")}},dismissButton={TextButton(onClick={deleteConfirmation=null}) {Text("Отмена")}})
    }
    val scroll=key(editing) { rememberScrollState() }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(editing) {
            val captured=editRule
            val notificationApps=appLabels.filterKeys {it!=context.packageName}
            key(captured?.id,proposal?.request) {
                AutomationEditor(captured,tools,proposal,editSection,notificationApps,busy,error,
                    cancel={editing=false;proposal=null;error=null; if (chatSetup != null) finishSetup()}) { definition ->
                    operation { store ->
                        val saved=store.save(captured?.id,definition,captured?.revision)
                        AutomationRuntime.reconcile(context,store)
                        withContext(Dispatchers.Main) {selected=saved.id;editing=false;proposal=null; if (chatSetup != null) finishSetup()}
                    }
                }
            }
        } else {
            if(errorRuleId==null || rules.none {it.id==errorRuleId}) {
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            }
            if (openRuleId != null) {
                TextButton(onClick = showAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.automation_all)) }
                if (loadedRules && rules.none { it.id == openRuleId }) Text(stringResource(R.string.automation_missing))
            } else {
                if (loadedRules && rules.isEmpty()) Text(stringResource(R.string.automation_empty_chat))
            }
            rules.filter { openRuleId == null || it.id == openRuleId }.forEach { rule ->
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(rule.definition.name,style=MaterialTheme.typography.titleMedium)
                    Text("Версия ${rule.revision} · ${if(rule.enabled) "Включён" else "На паузе"}")
                    val sections=mapOf(
                        AutomationEditorSection.WHEN to triggerText(rule.definition,appLabels),
                        AutomationEditorSection.IF to conditionText(rule.definition),
                        AutomationEditorSection.ACTIONS to actionText(rule.definition,tools),
                    )
                    sections.forEach { (section,summary) ->
                        TextButton(enabled=!busy,onClick={edit(rule,section)},modifier=Modifier.heightIn(min=48.dp)) {
                            Text(stringResource(section.title),style=MaterialTheme.typography.titleSmall)
                        }
                        Text(summary)
                    }
                    if(rule.attention.isNotEmpty()) Text(rule.attention,color=MaterialTheme.colorScheme.error)
                    if(errorRuleId==rule.id && error!=rule.attention) {
                        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    }
                    Text("Следующий запуск: ${date(rule.nextAt)}")
                    val last=histories[rule.id]?.firstOrNull()
                    Text("Последний: ${last?.let { "${date(it.time)} · ${status(it.status)}" } ?: "Ещё не было"}")
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled=if(rule.enabled) rule.id !in pausing else !busy && activating==null && rule.id !in pausing,
                            onClick={if(rule.enabled) pauseRule(rule) else resumeRule(rule)}) {
                            Text(when {
                                rule.enabled -> "Пауза"
                                rule.approvedRevision!=rule.revision || rule.attention.isNotEmpty() -> "Проверить и включить"
                                else -> "Включить"
                            })
                        }
                        TextButton(enabled=!busy,onClick={edit(rule)}) {Text("Изменить")}
                        TextButton(enabled=!busy,onClick={deleteConfirmation=rule}) {Text("Удалить")}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(!rule.enabled) TextButton(enabled=!busy && pendingTest==null && testProposal==null,onClick={
                            testPermissionAsked=false
                            testSettingsVisited=false
                            operation(rule.id) {store ->
                                val request=AutomationRuntime.requestOneTime(store,rule)
                                withContext(Dispatchers.Main) {pendingTest=request}
                            }
                        }) {Text(stringResource(R.string.automation_test_once))}
                        TextButton(enabled=!busy,onClick={operation { store -> AutomationRuntime.execute(context,store,rule.id,rule.revision,AutomationEvent("manual","dry:${UUID.randomUUID()}"),true); selected=rule.id }}) {Text("Проверка без действий")}
                        if(rule.enabled) TextButton(enabled=!busy,onClick={operation { store -> AutomationRuntime.execute(context,store,rule.id,rule.revision,AutomationEvent("manual","manual:${UUID.randomUUID()}")) }}) {Text("Запустить")}
                    }
                    TextButton(onClick={selected=if(selected == rule.id) null else rule.id}) {Text("Журнал за 30 дней")}
                    if(selected == rule.id) {
                        Text("Срок хранения — 30 дней.",style=MaterialTheme.typography.bodySmall)
                        histories[rule.id].orEmpty().forEach { run ->
                            HorizontalDivider(); Text("${date(run.time)} · версия ${run.revision} · ${status(run.status)}")
                            Text(run.message,style=MaterialTheme.typography.bodySmall)
                            val details=JSONArray(run.details)
                            repeat(details.length()) { index ->
                                val result=details.getJSONObject(index)
                                result.optJSONObject("data")?.optString("preview")?.takeIf { it.isNotEmpty() }?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                                result.optJSONObject("data")?.optJSONArray("events")?.let { events -> repeat(events.length()) { i ->
                                    val event=events.getJSONObject(i); Text("${event.optString("title")} · ${date(event.optLong("start"))}",style=MaterialTheme.typography.bodySmall)
                                } }
                            }
                            if(run.status == "awaitingUser") {
                                Text("Alphy откроет только указанную цель. Действие внутри приложения выполняешь ты.",style=MaterialTheme.typography.bodySmall)
                                TextButton(enabled=!busy && rule.enabled && rule.revision == run.revision,onClick={ operation { store ->
                                    AutomationRuntime.continueUserRun(context,store,run) { intent -> withContext(Dispatchers.Main) {
                                        val activity=context as? ComponentActivity
                                        check(activity != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !activity.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                                        activity.startActivity(intent)
                                    } }
                                } }) {Text("Продолжить в приложении")}
                            }
                        }
                    }
                } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}


@Composable internal fun AutomationDeviceSettings() {
    val context = LocalContext.current
    var monitor by remember { mutableStateOf(ChargingMonitor.active) }
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick = {
        failed = runCatching {
            val intent = Intent(context, ChargingMonitor::class.java)
            if (ChargingMonitor.active) context.stopService(intent) else context.startForegroundService(intent)
        }.isFailure
        monitor = ChargingMonitor.active
    }) { Text(stringResource(if (monitor) R.string.automation_power_stop else R.string.automation_power_start)) }
    Text(stringResource(if (failed) R.string.automation_power_failed else R.string.automation_power_help),
        style = MaterialTheme.typography.bodySmall)
}
