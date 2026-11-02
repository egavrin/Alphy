package com.alpha.assistant.capabilities

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.surfaces.ToolWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun ToolCapabilityGrants(controller: ToolController, tool: ToolRecord) {
    val spec = remember(tool.spec) { ToolSpec.parse(tool.spec) }
    if(spec.capabilities.isNotEmpty()) {
        Text("Доступы инструмента",style = MaterialTheme.typography.titleMedium)
        Text("Каждый доступ действует только для этого инструмента. Внешние действия требуют подтверждения. Scoped calendar grants permit explicit native calendar buttons without repeated confirmation. Доступы maps.markReached и maps.route разрешают прямые кнопки навигации в открытом Alphy без повторного подтверждения. Результаты остаются на устройстве.")
        spec.capabilities.forEach { id ->
            Row(Modifier.fillMaxWidth()) {
                Checkbox(tool.id + ":" + id in controller.grants,{ controller.grant(tool,id,it) },enabled = !controller.busy)
                Column(Modifier.weight(1f)) { Text(CapabilityCatalog.definition(id).title); Text(id,style = MaterialTheme.typography.bodySmall)
                    if(id in MedicineContract.ids) Text("Allow this app to execute explicitly selected native medicine actions without another confirmation. Meals and intake require separate user records. Exact alarm and notification permissions remain separate. Reports require native review before Share.",style=MaterialTheme.typography.bodySmall)
                    if(id in OwnedCalendarContract.ids) Text("Allow explicit native calendar buttons in this app without another confirmation. Creation and deletion show exact event details. Android permissions and calendar selection remain required. Reminder delivery is not verified.",style=MaterialTheme.typography.bodySmall)
                    if(id in NavigationContract.ids) Text(if(id == "maps.markReached") "Разрешить кнопкам этого приложения отмечать достигнутые точки. Отмечай только места, куда ты уже пришёл." else "Разрешить кнопкам этого приложения открывать маршрут от последней отмеченной точки. Координаты передаются установленному приложению карт.",style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable fun ToolCapabilityPanel(controller: ToolController, tool: ToolRecord, showDetails: Boolean = true) {
    if(showDetails && !tool.archived) ToolCapabilityGrants(controller,tool)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val selections=rememberSaveable(saver=CapabilitySelectionState.Saver) { CapabilitySelectionState() }
    var selectionIntent by rememberSaveable { mutableStateOf<Intent?>(null) }
    var cameraPreview by remember { mutableStateOf<CapabilityCameraPreview?>(null) }
    var confirmKey by remember { mutableStateOf<String?>(null) }
    var calendarKey by remember { mutableStateOf<String?>(null) }
    var calendarChoices by remember { mutableStateOf<List<NativeOwnedCalendar>>(emptyList()) }
    var calendarStatus by remember { mutableStateOf("") }
    fun loadCalendarChoices() {
        scope.launch {
            calendarChoices=runCatching {controller.writableCalendars()}.getOrDefault(emptyList())
            calendarStatus=if(calendarChoices.isEmpty()) "No writable calendar supports one alert reminder. Configure a calendar in Android first." else "Select one calendar for this app. Account details stay native."
        }
    }
    val calendarPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(OwnedCalendarCapabilities.hasPermissions(context)) loadCalendarChoices()
        else calendarStatus="Both Android calendar permissions are required. No event was changed."
    }
    if(calendarKey!=null) AlertDialog(onDismissRequest={calendarKey=null;calendarChoices=emptyList()},title={Text("Select writable calendar")},text={
        Column {Text(calendarStatus);calendarChoices.forEach {calendar ->
            TextButton(onClick={
                val key=calendarKey ?: return@TextButton
                calendarKey=null;calendarChoices=emptyList()
                controller.selectCalendar(key,calendar,{lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(context)})
            },enabled=!controller.busy) {Text(calendar.name.ifBlank {"Unnamed calendar"})}
        }}
    },confirmButton={},dismissButton={TextButton(onClick={calendarKey=null;calendarChoices=emptyList()}) {Text("Cancel")}})

    DisposableEffect(cameraPreview) {
        val bitmap = cameraPreview?.bitmap
        onDispose { if(bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
    }
    selections.active?.let { selected -> selectionIntent?.let { intent ->
        key(selected.token) {
            CapabilitySelectionActivity(selected,intent,
                canLaunch={ lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(context) },
                onResult={ launch,returned ->
                    val completed=selections.consume(launch.token)
                    if(completed != null) {
                        selectionIntent=null
                        if(completed.capability == "camera.capture" && returned.resultCode == Activity.RESULT_OK) {
                            @Suppress("DEPRECATION")
                            val bitmap=returned.data?.extras?.get("data") as? Bitmap
                            if(bitmap != null && bitmap.width in 1..2048 && bitmap.height in 1..2048) cameraPreview=CapabilityCameraPreview(completed,bitmap)
                            else controller.finishSelection(completed.effectKey,CapabilityResult("unknownOutcome","Камера не вернула подходящее изображение для предпросмотра."))
                        } else controller.finishSelection(completed.effectKey,AndroidCapabilities.selectionResult(returned.resultCode,returned.data))
                    }
                },
                onFailure={ launch ->
                    selections.consume(launch.token)?.let { completed ->
                        selectionIntent=null
                        controller.finishSelection(completed.effectKey,CapabilityResult("unavailable","The system selection could not start."))
                    }
                })
        }
    } }
    if(showDetails && controller.effects.isNotEmpty()) Text("История системных действий",style = MaterialTheme.typography.titleMedium)
    controller.effects.filter {showDetails || it.phase == "pending" || it.result.status == "awaitingUser"}.takeLast(30).reversed().forEach { effect ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp),verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(CapabilityCatalog.definition(effect.request.capability).title)
                Text("${resultLabel(effect.result.status)}: ${effect.result.message}",style = MaterialTheme.typography.bodySmall)
                if(effect.phase == "pending") {
                    Text(actionDetails(effect.request),style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = {
                            if(effect.request.capability=="calendar.select") {
                                calendarKey=effect.key;calendarChoices=emptyList();calendarStatus="Calendar permission and selection are required."
                                if(OwnedCalendarCapabilities.hasPermissions(context)) loadCalendarChoices()
                                else calendarPermission.launch(arrayOf(android.Manifest.permission.READ_CALENDAR,android.Manifest.permission.WRITE_CALENDAR))
                            } else if(effect.request.capability in NavigationContract.ids) {
                                controller.executeEffect(effect.key,true,{ lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(context) }) { intent ->
                                    check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(context)); context.startActivity(intent)
                                }
                            } else confirmKey = effect.key
                        },enabled = !controller.busy && (!CapabilityCatalog.definition(effect.request.capability).selection || (selections.active == null && cameraPreview == null))) { Text(when(effect.request.capability) { "calendar.select" -> "Select writable calendar"; "maps.markReached" -> "Я уже здесь: ${effect.request.args.getString("label")}"; "maps.route" -> "Маршрут к ${effect.request.args.getString("label")}"; else -> "Просмотреть действие" }) }
                        TextButton(onClick = { controller.executeEffect(effect.key,false,{ false }) {} },enabled = !controller.busy) { Text("Отмена action") }
                    }
                }
                if(effect.result.status == "awaitingUser" && effect.result.data.optBoolean("launched")) {
                    TextButton(onClick = {
                        selections.active?.takeIf { it.effectKey == effect.key }?.let { selections.consume(it.token); selectionIntent=null }
                        controller.finishSelection(effect.key,CapabilityResult("unknownOutcome","Окно выбора не вернуло подтверждённый результат."))
                    },enabled = !controller.busy && cameraPreview == null) { Text("Закрыть без подтверждённого результата") }
                }
                if(effect.result.data.has("uri")) Text("Выбрано на устройстве: ${effect.result.data.getString("uri")}",style = MaterialTheme.typography.bodySmall)
                if(effect.result.data.has("localPhoto")) Text("Фото на устройстве: ${effect.result.data.getString("localPhoto")}",style = MaterialTheme.typography.bodySmall)
                if(effect.result.data.has("events")) {
                    val events = effect.result.data.getJSONArray("events")
                    if(events.length() == 0) Text("За этот период событий нет",style = MaterialTheme.typography.bodySmall)
                    repeat(events.length()) { index ->
                        val event = events.getJSONObject(index)
                        Text("${event.getString("title").ifBlank { "Без названия" }} · ${eventTime(event.getLong("start"))} — ${eventTime(event.getLong("end"))}",style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    val effect = controller.effects.find { it.key == confirmKey }
    if(effect != null) AlertDialog(onDismissRequest = { confirmKey = null },title = { Text("Подтвердить системное действие") },text = {
        Column { Text(CapabilityCatalog.definition(effect.request.capability).title); Text(actionDetails(effect.request)); Text("Alphy проверит доступ инструмента и разрешения Android. Открытие другого приложения не подтверждает выполнение действия в нём.") }
    },confirmButton = { TextButton(onClick = {
        confirmKey = null
        val foreground = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(context) }
        controller.executeEffect(effect.key,true,foreground) { intent ->
            check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(context)) { "Открой Alphy, чтобы продолжить это действие" }
            if(CapabilityCatalog.definition(effect.request.capability).selection) {
                check(cameraPreview == null && selections.begin(effect.key,effect.request.capability) != null) { "Another system selection is active." }
                selectionIntent=intent
            } else context.startActivity(intent)
        }
    },enabled = !controller.busy && (!CapabilityCatalog.definition(effect.request.capability).selection || (selections.active == null && cameraPreview == null))) { Text("Выполнить это действие") } },dismissButton = { TextButton(onClick = { confirmKey = null }) { Text("Назад") } })
    cameraPreview?.let { preview ->
        val bitmap=preview.bitmap
        AlertDialog(onDismissRequest = {
            controller.finishSelection(preview.launch.effectKey,CapabilityResult("cancelled","Снимок предпросмотра удалён."))
            cameraPreview = null
        },title = { Text("Сохранить снимок на устройстве?") },text = {
            Column { Image(bitmap.asImageBitmap(),"Предпросмотр снимка",Modifier.fillMaxWidth().heightIn(max = 240.dp)); Text("Это уменьшенный снимок камеры. Он не отправляется в DeepSeek.") }
        },confirmButton = { TextButton(onClick = {
            scope.launch {
                val result = controller.saveCameraPreview(preview.launch.effectKey,bitmap)
                controller.finishSelection(preview.launch.effectKey,result)
                if(cameraPreview?.launch?.token == preview.launch.token) cameraPreview=null
            }
        },enabled = !controller.busy) { Text("Сохранить на устройстве") } },dismissButton = { TextButton(onClick = {
            controller.finishSelection(preview.launch.effectKey,CapabilityResult("cancelled","Снимок предпросмотра удалён."))
            cameraPreview = null
        }) { Text("Удалить") } })
    }
}

private data class CapabilityCameraPreview(val launch: CapabilitySelectionLaunch, val bitmap: Bitmap)

/** A new token creates a separate registry entry. Saved launch state prevents rotation from relaunching Android. */
@Composable private fun CapabilitySelectionActivity(launch: CapabilitySelectionLaunch, intent: Intent, canLaunch: ()->Boolean,
    onResult: (CapabilitySelectionLaunch,ActivityResult)->Unit, onFailure: (CapabilitySelectionLaunch)->Unit) {
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returned -> onResult(launch,returned) }
    var launched by rememberSaveable(launch.token) { mutableStateOf(false) }
    LaunchedEffect(launch.token) {
        if(!launched) {
            launched=true
            try { check(canLaunch()); launcher.launch(intent) } catch(_: Exception) { onFailure(launch) }
        }
    }
}

@Composable fun CapabilitySettings(controller: ToolController) {
    val context = LocalContext.current
    val native = remember(context) { AndroidCapabilities(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var status by remember { mutableStateOf("") }
    var calendars by remember { mutableStateOf<List<Pair<Long,String>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        status = if(allowed) "Разрешение Android выдано. Доступ для каждого инструмента включается отдельно." else "Разрешение Android не выдано. Остальные инструменты и данные доступны."
        refresh++
    }
    val calendarPermissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        status=if(OwnedCalendarCapabilities.hasPermissions(context)) "Both calendar permissions are available. Select a calendar separately in the app." else "Both calendar permissions are required."
        refresh++
    }
    AndroidAccessOverview()
    Text("Каталог функций Android для Alphy",style = MaterialTheme.typography.titleLarge)
    Text("Системные разрешения не включают доступ для мини-приложений. Выбранный контекст чата отправляется с обычным запросом.")
    if(status.isNotEmpty()) Text(status)
    CapabilityCatalog.definitions.forEach { definition ->
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(definition.title,style = MaterialTheme.typography.titleMedium)
            Text(definition.id,style = MaterialTheme.typography.labelSmall)
            Text("Реализовано · Android API ${definition.minApi}+",style = MaterialTheme.typography.labelSmall)
            Text(definition.resultDescription,style = MaterialTheme.typography.bodySmall)
            Text(definition.explanation,style = MaterialTheme.typography.bodySmall)
            Text(remember(refresh) { native.status(definition.id) },style = MaterialTheme.typography.bodySmall)
            if(definition.permission != null) TextButton(onClick = { if(definition.id in OwnedCalendarContract.ids) calendarPermissions.launch(arrayOf(android.Manifest.permission.READ_CALENDAR,android.Manifest.permission.WRITE_CALENDAR)) else permission.launch(definition.permission) }) { Text("Запросить разрешение Android") }
        } }
    }
    TextButton(onClick = { scope.launch { calendars = withContext(Dispatchers.IO) { runCatching { native.calendars() }.getOrDefault(emptyList()) }; status = if(calendars.isEmpty()) "Доступных календарей нет. Проверь разрешение календаря в Android и настройки аккаунта." else "Укажи идентификатор выбранного календаря в запросе для инструмента." } }) { Text("Показать календари на устройстве") }
    calendars.forEach { (id,title) -> Text("$title · идентификатор календаря: $id") }
    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))); refresh++ }) { Text("Открыть настройки Alphy в Android") }
    Text("Medicine reminders",style=MaterialTheme.typography.titleMedium)
    Text("Exact reminders require Android alarm access and the medicine notification channel. No notification confirms intake.")
    if(android.os.Build.VERSION.SDK_INT>=31) TextButton(onClick={context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}")))}) {Text("Exact alarm access")}
    TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}) {Text("Medicine notification settings")}
    Text("Доступы для каждого инструмента",style = MaterialTheme.typography.titleLarge)
    controller.tools.forEach { tool -> if(ToolSpec.parse(tool.spec).capabilities.isNotEmpty()) { Text(ToolSpec.parse(tool.spec).title); ToolCapabilityGrants(controller,tool) } }
    Text("Запланировано · недоступно приложениям",style = MaterialTheme.typography.titleMedium)
    CapabilityCatalog.planned.forEach { definition ->
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(definition.title,style = MaterialTheme.typography.titleMedium)
            Text(definition.id,style = MaterialTheme.typography.labelSmall)
            Text(definition.constraint,style = MaterialTheme.typography.bodySmall)
        } }
    }
    Text("Другие недоступные функции",style = MaterialTheme.typography.titleMedium)
    CapabilityCatalog.unavailable.filterKeys { key -> CapabilityCatalog.planned.none { it.id == key } }.forEach { (id,reason) -> Text("$id: $reason",style = MaterialTheme.typography.bodySmall) }
    Text("Микрофон: включи голосовой ввод в чате. Распознавание работает на устройстве. Alphy не слушает микрофон в фоне.")
}


private fun resultLabel(status: String) = when(status) {
    "succeeded" -> "Выполнено"
    "denied" -> "Доступ не разрешён"
    "revoked" -> "Доступ отозван"
    "unavailable" -> "Недоступно"
    "failed" -> "Ошибка"
    "cancelled" -> "Отменено"
    "awaitingUser" -> "Ожидает твоего действия"
    "unknownOutcome" -> "Результат не подтверждён"
    "launched" -> "Открыто"
    else -> "Неизвестное состояние"
}

private fun eventTime(value: Long) = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(value))

private fun actionDetails(request: CapabilityRequest): String {
    val args = request.args
    return when(request.capability) {
        "notification.post" -> "Заголовок: ${args.getString("title")}\nТекст: ${args.getString("text")}"
        "calendar.read" -> "Календарь: ${args.getLong("calendarId")}\nС: ${eventTime(args.getLong("from"))}\nДо: ${eventTime(args.getLong("to"))}"
        "calendar.create" -> "Событие: ${args.getString("title")}\nНачало: ${eventTime(args.getLong("start"))}\nОкончание: ${eventTime(args.getLong("end"))}" + if(args.has("location")) "\nМесто: ${args.getString("location")}" else ""
        "link.open" -> "Ссылка: ${args.getString("url")}"
        "app.open" -> "Приложение: " + when(args.getString("app")) { "settings" -> "Настройки"; "browser" -> "Браузер"; "calendar" -> "Календарь"; else -> "Камера" }
        in MedicineContract.ids -> MedicineContract.label(request)
        in OwnedCalendarContract.ids -> OwnedCalendarContract.label(request)
        "maps.markReached", "maps.route" -> NavigationContract.label(request)
        "maps.open" -> "Место для поиска: ${args.getString("query")}"
        "vibration.play" -> "Длительность: ${args.getLong("milliseconds")} мс"
        "flashlight.set" -> if(args.getBoolean("enabled")) "Включить фонарик" else "Выключить фонарик"
        "file.pick" -> "Тип файла: ${args.getString("mime")}"
        "photo.pick" -> "Выбрать одну фотографию в системном окне"
        "camera.capture" -> "Открыть камеру и просмотреть уменьшенный снимок"
        else -> "Функция недоступна"
    }
}
