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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alpha.assistant.capabilities.AndroidCapabilities
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private val triggers = linkedMapOf("manual" to "Вручную","schedule" to "По расписанию","charging" to "Питание","geofence" to "Геозона","notification" to "Уведомления приложений")
private val actions = linkedMapOf("notification.post" to "Уведомление Alpha","calendar.read" to "Прочитать календарь","tool.state.set" to "Изменить поле инструмента","app.open" to "Открыть приложение","link.open" to "Открыть ссылку","maps.open" to "Открыть карту")
private fun date(at: Long?) = at?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it)) } ?: "Нет"
private fun status(status: String) = when(status) {
    "succeeded" -> "Выполнено"; "unknownOutcome" -> "Результат неизвестен"; "awaitingUser" -> "Нужно открыть Alpha и выполнить действие вручную"
    "cancelled" -> "Отменено"; "skipped" -> "Условия не выполнены"; "dryRun" -> "Проверка без действий"; "missed" -> "Пропущено"
    "launched" -> "Открыт интерфейс приложения"; "denied","revoked" -> "Нужен доступ"; "unavailable" -> "Недоступно"; "failed" -> "Ошибка"; else -> status
}
private fun triggerText(d: AutomationDefinition, appLabels: Map<String,String> = emptyMap()): String {
    val t = d.trigger
    return when(d.type) {
        "manual" -> "Только по кнопке «Запустить»"
        "charging" -> if(t.getString("connection") == "connected") "При подключении зарядки" else "При отключении зарядки"
        "notification" -> "Новое уведомление от: ${d.notificationPackages().joinToString { "${appLabels[it] ?: it} ($it)" }}. Текст уведомления не читается."
        "geofence" -> "${if(t.getString("transition") == "enter") "Вход" else "Выход"}: ${t.getDouble("latitude")}, ${t.getDouble("longitude")}, радиус ${t.getDouble("radius").toInt()} м"
        else -> "${when(t.getString("repeat")) { "once" -> t.getString("date"); "daily" -> "Ежедневно"; else -> "Дни недели: ${t.getJSONArray("days")}" }} в ${t.getString("time")}, ${if(t.getString("zone") == "device") "часовой пояс устройства" else t.getString("zone")}. ${if(t.getBoolean("exact")) "Точное напоминание Android" else "Приблизительное фоновое выполнение Android"}."
    }
}
private fun actionText(d: AutomationDefinition, tools: ToolController): String = d.actions.joinToString("\n") { action ->
    val a = action.args
    when(action.capability) {
        "notification.post" -> "Уведомление Alpha: «${a.getString("title") }» — ${a.getString("text") }"
        "calendar.read" -> "Календарь ${a.getLong("calendarId")}: ${date(a.getLong("from"))} — ${date(a.getLong("to"))}. До 50 событий, только на устройстве."
        "tool.state.set" -> "Инструмент ${tools.tools.find { it.id == a.getString("toolId") }?.let { ToolSpec.parse(it.spec).title } ?: a.getString("toolId")}, версия ${a.getInt("revision")}: ${a.getString("field")} = ${a.get("value")}"
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

@Composable fun AutomationsPane(tools: ToolController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appLabels=remember { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
        .associate { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() } }
    var rules by remember { mutableStateOf(emptyList<AutomationRule>()) }
    var histories by remember { mutableStateOf(emptyMap<String,List<AutomationRun>>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editRule by remember { mutableStateOf<AutomationRule?>(null) }
    var approval by remember { mutableStateOf<AutomationRule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var monitor by remember { mutableStateOf(ChargingMonitor.active) }
    fun reload() { scope.launch {
        try { val loaded = withContext(Dispatchers.IO) { AutomationStore(context).use { store -> store.prune(); store.rules().let { r -> r to r.associate { it.id to store.history(it.id) } } } }
            rules=loaded.first; histories=loaded.second; monitor=ChargingMonitor.active
        } catch(_: Exception) { error="Не удалось открыть сценарии. Данные не удалены." }
    } }
    fun operation(work: suspend (AutomationStore) -> Unit) {
        if(busy) return
        busy=true; error=null
        scope.launch {
            try { withContext(Dispatchers.IO) { AutomationStore(context).use { work(it) } }; reload() }
            catch(failure: Exception) { error=failure.message?.takeIf { it.any { c -> c in 'А'..'я' || c == 'ё' } } ?: "Не удалось выполнить действие. Сохранённые данные доступны." }
            finally { busy=false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { AutomationRuntime.refresh(context); reload() }
    fun settings(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { error="Android не открыл настройки доступа." } }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { reload() } }
        val filter=IntentFilter(AutomationRuntime.CHANGED)
        if(Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") context.registerReceiver(receiver,filter) }
        val activity=context as? ComponentActivity
        val observer=LifecycleEventObserver { _,event -> if(event == Lifecycle.Event.ON_RESUME) { AutomationRuntime.refresh(context); reload() } }
        activity?.lifecycle?.addObserver(observer)
        reload(); AutomationRuntime.refresh(context)
        onDispose { context.unregisterReceiver(receiver); activity?.lifecycle?.removeObserver(observer) }
    }
    if(approval != null) {
        val rule=approval!!
        AlertDialog(onDismissRequest={approval=null},title={Text("Разрешить версию ${rule.revision}?")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(rule.definition.name); Text(triggerText(rule.definition,appLabels)); Text(conditionText(rule.definition)); Text(actionText(rule.definition,tools))
            Text("Разрешение действует только для этой версии, этих целей и параметров. Изменение сценария или целевого инструмента требует проверки. Данные остаются на устройстве. Облачных действий нет. Повторных запросов перед каждым запуском не будет.")
            Text("При неизвестном результате повтора нет. Только недоступное чтение календаря повторяется один раз. Внешние приложения открываются по кнопке в журнале. Проверка без действий ничего не изменяет.")
        }},confirmButton={TextButton(enabled=!busy,onClick={ approval=null; operation { store ->
            val current=checkNotNull(store.rule(rule.id)); check(current.revision == rule.revision) { "Сценарий изменился" }
            val failure=AutomationRuntime.triggerFailure(context,current) ?: current.definition.actions.firstNotNullOfOrNull { AutomationRuntime.actionFailure(context,it) }
            check(failure == null) { failure.orEmpty() }
            if(current.definition.type == "schedule") check(current.definition.nextAfter(System.currentTimeMillis()) != null) { "Выбери будущее время расписания" }
            store.approve(rule.id,rule.revision); AutomationRuntime.reconcile(context,store)
        } }) {Text("Разрешить и включить")}},dismissButton={TextButton(onClick={approval=null}) {Text("Отмена")}})
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Автоматизации",style=MaterialTheme.typography.headlineSmall)
        Text("Локальные сценарии. Android может задерживать фоновые события. После принудительной остановки открой Alpha. Пропущенные события питания и уведомления не воспроизводятся.",style=MaterialTheme.typography.bodySmall)
        if(error != null) Text(error!!,color=MaterialTheme.colorScheme.error)
        if(editing) {
            AutomationEditor(editRule,tools,{editing=false}) { definition -> operation { store ->
                val saved=store.save(editRule?.id,definition,editRule?.revision)
                AutomationRuntime.reconcile(context,store); selected=saved.id; editing=false
            } }
        } else {
            Button(onClick={editRule=null; editing=true},enabled=!busy) {Text("Создать сценарий")}
            Text("Доступ Android",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick={permission.launch(if(Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.READ_CALENDAR) else arrayOf(Manifest.permission.READ_CALENDAR))}) {Text("Уведомления / календарь")}
            }
            TextButton(onClick={settings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}")))}) {Text("Точные напоминания: ${if(AutomationRuntime.exactAllowed(context)) "доступ есть" else "нужен доступ"}")}
            TextButton(onClick={settings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}) {Text("Уведомления приложений: ${if(AutomationRuntime.listenerAllowed(context)) "доступ есть" else "нужен доступ"}")}
            Text("Android даёт слушателю системный доступ ко всем уведомлениям. Alpha отбирает только выбранные приложения и не читает текст. Содержимое не передаётся в облако.",style=MaterialTheme.typography.bodySmall)
            if(AutomationRuntime.googleAvailable(context)) {
                TextButton(onClick={permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}) {Text("Геозоны: точная геопозиция")}
                TextButton(onClick={settings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) {Text("Геозоны: разрешить доступ «Всегда»")}
                Text("Геозоны обрабатывают Сервисы Google Play по их правилам. В фоне события могут задерживаться на несколько минут. Начальное нахождение внутри не вызывает запуск.",style=MaterialTheme.typography.bodySmall)
            } else Text("Геозоны недоступны: нет рабочих Сервисов Google Play.")
            TextButton(onClick={
                runCatching { if(ChargingMonitor.active) context.stopService(Intent(context,ChargingMonitor::class.java)) else context.startForegroundService(Intent(context,ChargingMonitor::class.java)) }
                    .onFailure { error="Android запретил наблюдение питания. Проверь уведомления и повтори из открытой Alpha." }
                monitor=ChargingMonitor.active
            }) {Text(if(monitor) "Остановить наблюдение питания" else "Начать наблюдение питания")}
            Text("Наблюдение питания использует постоянное уведомление. После остановки процесса или перезагрузки запусти его и включи сценарий снова. Пока наблюдение выключено, события не фиксируются.",style=MaterialTheme.typography.bodySmall)
            if(rules.isEmpty()) Text("Сценариев пока нет")
            rules.forEach { rule ->
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(rule.definition.name,style=MaterialTheme.typography.titleMedium)
                    Text("Версия ${rule.revision} · ${if(rule.enabled) "Включён" else "На паузе"}")
                    Text(triggerText(rule.definition,appLabels)); Text(conditionText(rule.definition)); Text(actionText(rule.definition,tools))
                    if(rule.attention.isNotEmpty()) Text(rule.attention,color=MaterialTheme.colorScheme.error)
                    Text("Следующий запуск: ${date(rule.nextAt)}")
                    val last=histories[rule.id]?.firstOrNull()
                    Text("Последний: ${last?.let { "${date(it.time)} · ${status(it.status)}" } ?: "Ещё не было"}")
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled=!busy,onClick={if(rule.enabled) operation { store -> store.pause(rule.id); AutomationRuntime.reconcile(context,store) } else approval=rule}) {Text(if(rule.enabled) "Пауза" else "Проверить и включить")}
                        TextButton(enabled=!busy,onClick={editRule=rule; editing=true}) {Text("Изменить")}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled=!busy,onClick={operation { store -> AutomationRuntime.execute(context,store,rule.id,rule.revision,AutomationEvent("manual","dry:${UUID.randomUUID()}"),true) }}) {Text("Проверка без действий")}
                        TextButton(enabled=!busy && rule.enabled,onClick={operation { store -> AutomationRuntime.execute(context,store,rule.id,rule.revision,AutomationEvent("manual","manual:${UUID.randomUUID()}")) }}) {Text("Запустить")}
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
                                result.optJSONObject("data")?.optJSONArray("events")?.let { events -> repeat(events.length()) { i ->
                                    val event=events.getJSONObject(i); Text("${event.optString("title")} · ${date(event.optLong("start"))}",style=MaterialTheme.typography.bodySmall)
                                } }
                            }
                            if(run.status == "awaitingUser") {
                                Text("Alpha откроет только указанную цель. Действие внутри приложения выполняешь ты.",style=MaterialTheme.typography.bodySmall)
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

@Composable private fun Choices(value: String, options: Map<String,String>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={expanded=true}) {Text(options[value] ?: value)}
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) { options.forEach { (id,label) -> DropdownMenuItem(text={Text(label)},onClick={expanded=false; choose(id)}) } }
    }
}
@Composable private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value=value,onValueChange=change,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true)
}
@Composable private fun AutomationEditor(rule: AutomationRule?, tools: ToolController, cancel: () -> Unit, save: (JSONObject) -> Unit) {
    val context=LocalContext.current
    val old=rule?.definition
    var name by remember(rule?.id) { mutableStateOf(old?.name ?: "Новый сценарий") }
    var type by remember(rule?.id) { mutableStateOf(old?.type ?: "manual") }
    var connection by remember { mutableStateOf(old?.trigger?.optString("connection")?.ifEmpty { null } ?: "connected") }
    var repeat by remember { mutableStateOf(old?.trigger?.optString("repeat")?.ifEmpty { null } ?: "once") }
    var time by remember { mutableStateOf(old?.trigger?.optString("time")?.ifEmpty { null } ?: LocalTime.now().plusMinutes(5).withSecond(0).withNano(0).toString()) }
    var scheduleDate by remember { mutableStateOf(old?.trigger?.optString("date")?.ifEmpty { null } ?: LocalDate.now().toString()) }
    var zone by remember { mutableStateOf(old?.trigger?.optString("zone")?.ifEmpty { null } ?: "device") }
    var exact by remember { mutableStateOf(old?.trigger?.optBoolean("exact") ?: false) }
    var weekdays by remember { mutableStateOf(old?.trigger?.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: setOf(1,2,3,4,5)) }
    var latitude by remember { mutableStateOf(old?.trigger?.opt("latitude")?.toString() ?: "") }
    var longitude by remember { mutableStateOf(old?.trigger?.opt("longitude")?.toString() ?: "") }
    var radius by remember { mutableStateOf(old?.trigger?.opt("radius")?.toString() ?: "200") }
    var transition by remember { mutableStateOf(old?.trigger?.optString("transition")?.ifEmpty { null } ?: "enter") }
    var packages by remember { mutableStateOf(old?.notificationPackages() ?: emptySet()) }
    val installed=remember { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
        .filter { it.activityInfo.packageName != context.packageName }.distinctBy { it.activityInfo.packageName }
        .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }.sortedBy { it.second } }
    var powerCondition by remember { mutableStateOf(if(old?.conditions?.has("charging") == true) if(old.conditions.getBoolean("charging")) "on" else "off" else "any") }
    fun conditionTime(key: String) = old?.conditions?.optInt(key,-1)?.takeIf { it >= 0 }?.let { "%02d:%02d".format(it/60,it%60) } ?: ""
    var fromTime by remember { mutableStateOf(conditionTime("fromMinute")) }
    var toTime by remember { mutableStateOf(conditionTime("toMinute")) }
    val oldAction=old?.actions?.firstOrNull()
    var action by remember { mutableStateOf(oldAction?.capability ?: "notification.post") }
    val a=oldAction?.args
    var title by remember { mutableStateOf(a?.optString("title") ?: "Напоминание Alpha") }
    var text by remember { mutableStateOf(a?.optString("text") ?: "Пора выполнить запланированное") }
    var url by remember { mutableStateOf(a?.optString("url") ?: "https://") }
    var query by remember { mutableStateOf(a?.optString("query") ?: "") }
    var app by remember { mutableStateOf(a?.optString("app")?.ifEmpty { null } ?: "settings") }
    var calendar by remember { mutableStateOf(a?.opt("calendarId")?.toString() ?: "") }
    var calendarFrom by remember { mutableStateOf(a?.optLong("from")?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().toString()) }
    var calendarTo by remember { mutableStateOf(a?.optLong("to")?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().plusDays(1).toString()) }
    var toolId by remember { mutableStateOf(a?.optString("toolId") ?: "") }
    var field by remember { mutableStateOf(a?.optString("field") ?: "") }
    var value by remember { mutableStateOf(a?.opt("value")?.toString() ?: "") }
    var failure by remember { mutableStateOf<String?>(null) }
    Text("${if(rule == null) "Создание" else "Изменение"} сценария",style=MaterialTheme.typography.titleLarge)
    Field("Название",name) {name=it}
    Text("Триггер"); Choices(type,triggers) {type=it}
    when(type) {
        "charging" -> Choices(connection,mapOf("connected" to "Зарядка подключена","disconnected" to "Зарядка отключена")) {connection=it}
        "schedule" -> {
            Choices(repeat,mapOf("once" to "Один раз","daily" to "Каждый день","weekly" to "По дням недели")) {repeat=it}
            Field("Время, ЧЧ:ММ",time) {time=it}
            if(repeat == "once") Field("Дата, ГГГГ-ММ-ДД",scheduleDate) {scheduleDate=it}
            Field("Часовой пояс: device или Europe/Moscow",zone) {zone=it}
            if(repeat == "weekly") listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEachIndexed { i,day ->
                Row { Checkbox(checked=i+1 in weekdays,onCheckedChange={ weekdays=if(it) weekdays+(i+1) else weekdays-(i+1) }); Text(day,Modifier.padding(top=12.dp)) }
            }
            Row { Checkbox(checked=exact,onCheckedChange={exact=it}); Text("Точное напоминание Alpha",Modifier.padding(top=12.dp)) }
            Text("Точное время — только для уведомления Alpha. Фоновая обработка приблизительна. При переводе часов несуществующее время переносится вперёд, повторяющееся время используется один раз.",style=MaterialTheme.typography.bodySmall)
        }
        "geofence" -> {
            Field("Широта",latitude) {latitude=it}; Field("Долгота",longitude) {longitude=it}; Field("Радиус, 100–10000 м",radius) {radius=it}
            Choices(transition,mapOf("enter" to "Вход в геозону","exit" to "Выход из геозоны")) {transition=it}
        }
        "notification" -> {
            Text("Выбери до 10 приложений. Текст уведомлений не используется.")
            installed.forEach { (id,label) -> Row { Checkbox(checked=id in packages,onCheckedChange={packages=if(it) packages+id else packages-id}); Text(label,Modifier.padding(top=12.dp)) } }
        }
    }
    Text("Условия"); Choices(powerCondition,mapOf("any" to "Любое питание","on" to "Только на зарядке","off" to "Только без зарядки")) {powerCondition=it}
    Field("С какого времени, ЧЧ:ММ (необязательно)",fromTime) {fromTime=it}
    Field("До какого времени, ЧЧ:ММ (необязательно)",toTime) {toTime=it}
    Text("Разрешённое действие"); Choices(action,actions) {action=it}
    when(action) {
        "notification.post" -> {Field("Заголовок",title) {title=it}; Field("Текст",text) {text=it}}
        "calendar.read" -> {
            val calendars=remember { AndroidCapabilities(context).calendars() }
            Choices(calendar,calendars.associate { it.first.toString() to it.second }) {calendar=it}
            if(calendars.isEmpty()) Text("Сначала разреши доступ к календарю на основном экране автоматизаций.")
            Field("Начало периода, ГГГГ-ММ-ДД",calendarFrom) {calendarFrom=it}; Field("Конец периода, ГГГГ-ММ-ДД",calendarTo) {calendarTo=it}
            Text("Выбранный период фиксирован, до 31 дня. События остаются в журнале на устройстве.")
        }
        "tool.state.set" -> {
            Choices(toolId,tools.tools.associate { it.id to ToolSpec.parse(it.spec).title }) {toolId=it; field=""}
            val tool=tools.tools.find { it.id == toolId }
            val schema=tool?.let { ToolSpec.parse(it.spec).schema }
            Choices(field,schema?.keys()?.asSequence()?.filter { schema.get(it) is String }?.associateWith { it }.orEmpty()) {field=it}
            Field("Новое значение поля",value) {value=it}
            Text("Изменяется только выбранное скалярное поле. Для логического поля: true или false. После изменения версии инструмента сценарий требует проверки.")
        }
        "app.open" -> Choices(app,mapOf("settings" to "Настройки Android","browser" to "Браузер","calendar" to "Календарь","camera" to "Камера")) {app=it}
        "link.open" -> Field("Публичная ссылка HTTPS",url) {url=it}
        "maps.open" -> Field("Место для поиска",query) {query=it}
    }
    if(failure != null) Text(failure!!,color=MaterialTheme.colorScheme.error)
    Button(onClick={ runCatching {
        val trigger=JSONObject().put("type",type)
        when(type) {
            "charging" -> trigger.put("connection",connection)
            "notification" -> trigger.put("packages",JSONArray(packages.toList()))
            "geofence" -> trigger.put("latitude",latitude.toDouble()).put("longitude",longitude.toDouble()).put("radius",radius.toDouble()).put("transition",transition)
            "schedule" -> {
                trigger.put("repeat",repeat).put("time",time).put("zone",zone).put("exact",exact)
                if(repeat == "once") trigger.put("date",scheduleDate)
                if(repeat == "weekly") trigger.put("days",JSONArray(weekdays.sorted()))
            }
        }
        val conditions=JSONObject()
        if(powerCondition != "any") conditions.put("charging",powerCondition == "on")
        if(fromTime.isNotBlank() || toTime.isNotBlank()) {
            fun minutes(s: String) = LocalTime.parse(s).let {it.hour*60+it.minute}
            conditions.put("fromMinute",minutes(fromTime)).put("toMinute",minutes(toTime))
        }
        val args=when(action) {
            "notification.post" -> JSONObject().put("title",title).put("text",text)
            "calendar.read" -> {
                fun epoch(s: String) = LocalDate.parse(s).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                JSONObject().put("calendarId",calendar.toLong()).put("from",epoch(calendarFrom)).put("to",epoch(calendarTo))
            }
            "tool.state.set" -> {
                val tool=checkNotNull(tools.tools.find { it.id == toolId }); val schema=ToolSpec.parse(tool.spec).schema
                val v: Any=when(schema.getString(field)) {"boolean" -> {require(value in setOf("true","false")); value.toBoolean()}; "number" -> value.toDouble(); else -> value}
                ToolSpec.parse(tool.spec).validateState(JSONObject(tool.state).put(field,v).toString())
                JSONObject().put("toolId",toolId).put("revision",tool.revision).put("field",field).put("value",v)
            }
            "app.open" -> JSONObject().put("app",app)
            "link.open" -> JSONObject().put("url",url)
            else -> JSONObject().put("query",query)
        }
        val input=JSONObject().put("name",name).put("trigger",trigger).put("conditions",conditions)
            .put("actions",JSONArray().put(JSONObject().put("id","action").put("capability",action).put("args",args)))
        AutomationDefinition.parse(input); save(input)
    }.onFailure { failure=it.message?.takeIf { message -> message.any { c -> c in 'А'..'я' } } ?: "Проверь даты, время, числа и обязательные поля." } }) {Text("Сохранить для проверки")}
    TextButton(onClick=cancel) {Text("Отмена")}
}
