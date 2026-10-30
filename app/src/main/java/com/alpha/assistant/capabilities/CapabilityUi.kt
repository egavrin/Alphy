package com.alpha.assistant.capabilities

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
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
import java.util.UUID

@Composable fun ToolCapabilityGrants(controller: ToolController, tool: ToolRecord) {
    val spec = remember(tool.spec) { ToolSpec.parse(tool.spec) }
    if(spec.capabilities.isNotEmpty()) {
        Text("Доступы инструмента",style = MaterialTheme.typography.titleMedium)
        Text("Каждый доступ действует только для этого инструмента. Каждое внешнее действие требует подтверждения. Результаты остаются на устройстве.")
        spec.capabilities.forEach { id ->
            Row(Modifier.fillMaxWidth()) {
                Checkbox(tool.id + ":" + id in controller.grants,{ controller.grant(tool,id,it) },enabled = !controller.busy)
                Column(Modifier.weight(1f)) { Text(CapabilityCatalog.definition(id).title); Text(id,style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable fun ToolCapabilityPanel(controller: ToolController, tool: ToolRecord) {
    ToolCapabilityGrants(controller,tool)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var activeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var activeCapability by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPreview by remember { mutableStateOf<Bitmap?>(null) }
    var confirmKey by remember { mutableStateOf<String?>(null) }
    DisposableEffect(cameraPreview) {
        val bitmap = cameraPreview
        onDispose { if(bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returned ->
        val key = activeKey
        if(key != null) {
            if(activeCapability == "camera.capture" && returned.resultCode == Activity.RESULT_OK) {
                @Suppress("DEPRECATION")
                val bitmap = returned.data?.extras?.get("data") as? Bitmap
                if(bitmap != null && bitmap.width <= 2048 && bitmap.height <= 2048) cameraPreview = bitmap
                else { controller.finishSelection(key,CapabilityResult("unknownOutcome","Камера не вернула подходящее изображение для предпросмотра.")); activeKey = null }
            } else {
                val result = AndroidCapabilities.selectionResult(returned.resultCode,returned.data)
                if(result.status == "succeeded") {
                    val uri = Uri.parse(result.data.getString("uri"))
                    runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                controller.finishSelection(key,result) {
                    if(result.data.has("uri")) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(result.data.getString("uri")),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                activeKey = null
            }
        }
    }
    if(controller.effects.isNotEmpty()) Text("История системных действий",style = MaterialTheme.typography.titleMedium)
    controller.effects.takeLast(30).reversed().forEach { effect ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp),verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(CapabilityCatalog.definition(effect.request.capability).title)
                Text("${resultLabel(effect.result.status)}: ${effect.result.message}",style = MaterialTheme.typography.bodySmall)
                if(effect.result.status == "awaitingUser" && !effect.result.data.optBoolean("launched")) {
                    Text(actionDetails(effect.request),style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { confirmKey = effect.key },enabled = !controller.busy) { Text("Просмотреть действие") }
                        TextButton(onClick = { controller.executeEffect(effect.key,false,false) {} },enabled = !controller.busy) { Text("Отмена action") }
                    }
                }
                if(effect.result.status == "awaitingUser" && effect.result.data.optBoolean("launched")) {
                    TextButton(onClick = { controller.finishSelection(effect.key,CapabilityResult("unknownOutcome","Окно выбора не вернуло подтверждённый результат.")) },enabled = !controller.busy && cameraPreview == null) { Text("Закрыть без подтверждённого результата") }
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
        Column { Text(CapabilityCatalog.definition(effect.request.capability).title); Text(actionDetails(effect.request)); Text("Alpha проверит доступ инструмента и разрешения Android. Открытие другого приложения не подтверждает выполнение действия в нём.") }
    },confirmButton = { TextButton(onClick = {
        confirmKey = null
        val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(context)
        controller.executeEffect(effect.key,true,foreground) { intent ->
            check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(context)) { "Открой Alpha, чтобы продолжить это действие" }
            if(effect.request.capability in setOf("photo.pick","file.pick","camera.capture")) {
                activeKey = effect.key; activeCapability = effect.request.capability; launcher.launch(intent)
            } else context.startActivity(intent)
        }
    },enabled = !controller.busy) { Text("Выполнить это действие") } },dismissButton = { TextButton(onClick = { confirmKey = null }) { Text("Назад") } })
    cameraPreview?.let { bitmap ->
        AlertDialog(onDismissRequest = {
            activeKey?.let { controller.finishSelection(it,CapabilityResult("cancelled","Снимок предпросмотра удалён.")) }
            cameraPreview = null; activeKey = null
        },title = { Text("Сохранить снимок на устройстве?") },text = {
            Column { Image(bitmap.asImageBitmap(),"Предпросмотр снимка",Modifier.fillMaxWidth().heightIn(max = 240.dp)); Text("Это уменьшенный снимок камеры. Он не отправляется в DeepSeek.") }
        },confirmButton = { TextButton(onClick = {
            val key = activeKey
            if(key != null) scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = java.io.File(context.filesDir,"capture-${UUID.randomUUID()}.jpg")
                        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)) }
                        CapabilityResult("succeeded","Уменьшенный снимок сохранён в закрытом хранилище Alpha на устройстве.",JSONObject().put("localPhoto",file.name))
                    }.getOrElse { CapabilityResult("failed","Не удалось сохранить снимок предпросмотра.") }
                }
                controller.finishSelection(key,result) {
                    if(result.data.has("localPhoto")) java.io.File(context.filesDir,result.data.getString("localPhoto")).delete()
                }; cameraPreview = null; activeKey = null
            }
        },enabled = !controller.busy) { Text("Сохранить на устройстве") } },dismissButton = { TextButton(onClick = {
            activeKey?.let { controller.finishSelection(it,CapabilityResult("cancelled","Снимок предпросмотра удалён.")) }
            cameraPreview = null; activeKey = null
        }) { Text("Удалить") } })
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
    Text("Разрешения и системные функции",style = MaterialTheme.typography.titleLarge)
    Text("Контекст остаётся на устройстве. Системный доступ не разрешает отправку контекста в DeepSeek.")
    if(status.isNotEmpty()) Text(status)
    CapabilityCatalog.definitions.forEach { definition ->
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(definition.title,style = MaterialTheme.typography.titleMedium)
            Text(capabilityExplanation(definition.id),style = MaterialTheme.typography.bodySmall)
            Text(remember(refresh) { native.status(definition.id) },style = MaterialTheme.typography.bodySmall)
            if(definition.permission != null) TextButton(onClick = { permission.launch(definition.permission) }) { Text("Запросить разрешение Android") }
        } }
    }
    TextButton(onClick = { scope.launch { calendars = withContext(Dispatchers.IO) { runCatching { native.calendars() }.getOrDefault(emptyList()) }; status = if(calendars.isEmpty()) "Доступных календарей нет. Проверь разрешение календаря в Android и настройки аккаунта." else "Укажи идентификатор выбранного календаря в запросе для инструмента." } }) { Text("Показать календари на устройстве") }
    calendars.forEach { (id,title) -> Text("$title · идентификатор календаря: $id") }
    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))); refresh++ }) { Text("Открыть настройки Alpha в Android") }
    Text("Доступы для каждого инструмента",style = MaterialTheme.typography.titleLarge)
    controller.tools.forEach { tool -> if(ToolSpec.parse(tool.spec).capabilities.isNotEmpty()) { Text(ToolSpec.parse(tool.spec).title); ToolCapabilityGrants(controller,tool) } }
    Text("Недоступные функции",style = MaterialTheme.typography.titleMedium)
    CapabilityCatalog.unavailable.forEach { (id,reason) -> Text("$id: $reason",style = MaterialTheme.typography.bodySmall) }
    Text("Микрофон: включи голосовой ввод в чате. Распознавание работает на устройстве. Alpha не слушает микрофон в фоне.")
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

private fun capabilityExplanation(id: String) = when(id) {
    "notification.post" -> "Уведомление Alpha с указанным заголовком и текстом. На Android 13 и новее требуется разрешение на уведомления."
    "calendar.read" -> "Только выбранный календарь и указанный период: до 31 дня и 50 событий. Требуется разрешение на чтение календаря. Результаты остаются на устройстве."
    "calendar.create" -> "Открывает системную форму с названием, временем и местом события. Событие сохраняешь ты в приложении календаря."
    "link.open" -> "Открывает указанную ссылку HTTPS на публичный сайт. Параметры ссылки видны перед подтверждением."
    "app.open" -> "Открывает настройки, браузер, календарь или камеру через поддерживаемое системное действие."
    "maps.open" -> "Открывает поиск указанного места на карте. Alpha не получает твою геопозицию."
    "vibration.play" -> "Запрашивает вибрацию от 1 до 2000 миллисекунд, если устройство её поддерживает. Фактическое выполнение не подтверждается."
    "flashlight.set" -> "Запрашивает включение или выключение фонарика. Требуется разрешение на камеру. Фактическое состояние не подтверждается."
    "photo.pick" -> "Открывает системный выбор одной фотографии. Доступ действует только для выбранного изображения."
    "file.pick" -> "Открывает системный выбор одного файла указанного типа. Доступ действует только для выбранного файла."
    "camera.capture" -> "Открывает камеру для уменьшенного снимка. Перед сохранением ты увидишь предпросмотр. Требуется разрешение на камеру."
    else -> "Функция недоступна."
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
        "maps.open" -> "Место для поиска: ${args.getString("query")}"
        "vibration.play" -> "Длительность: ${args.getLong("milliseconds")} мс"
        "flashlight.set" -> if(args.getBoolean("enabled")) "Включить фонарик" else "Выключить фонарик"
        "file.pick" -> "Тип файла: ${args.getString("mime")}"
        "photo.pick" -> "Выбрать одну фотографию в системном окне"
        "camera.capture" -> "Открыть камеру и просмотреть уменьшенный снимок"
        else -> "Функция недоступна"
    }
}
