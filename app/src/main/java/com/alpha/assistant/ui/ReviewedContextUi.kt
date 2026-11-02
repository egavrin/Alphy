package com.alpha.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.alpha.assistant.capabilities.AndroidPermissionControl
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.surfaces.AssistContext
import com.alpha.assistant.surfaces.ReviewedCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReviewedContextDialog(model: ShellViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val origin = rememberSaveable { model.contextDraftKey() }
    var page by rememberSaveable { mutableStateOf("sources") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraReady by rememberSaveable { mutableStateOf(false) }
    var cameraPending by rememberSaveable { mutableStateOf(false) }
    fun discardCapture() {
        cameraUri?.let { runCatching { AssistContext.cameraFile(context, Uri.parse(it)).delete() } }
        cameraUri = null
        cameraReady = false
    }
    fun close() { if (!cameraPending && !model.attachmentBusy) { discardCapture(); dismiss() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Изображение"
                Attachment(uri.toString(), name, context.contentResolver.getType(uri) ?: "application/octet-stream")
            }.onSuccess { attachment -> model.addAttachment(attachment, origin) { if (it) close() } }
                .onFailure { error = "Файл недоступен. Выберите изображение ещё раз." }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPending = false
        if (success && cameraUri != null) cameraReady = false
        else { discardCapture(); error = "Съёмка отменена. Черновик не изменён." }
    }
    LaunchedEffect(cameraUri, cameraPending) {
        val uri = cameraUri
        if (uri != null && !cameraPending) {
            val result = withContext(Dispatchers.IO) { runCatching { AssistContext.validatedCapture(context, Uri.parse(uri)) } }
            cameraReady = result.isSuccess
            error = if (result.isSuccess) null else "Снимок повреждён, превышает 5 МиБ или 8192 пикселя по стороне. Переснимите его или отмените."
        }
    }

    fun takePicture() {
        if (!model.acceptsContext(origin)) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) { error = "На устройстве нет камеры."; return }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { error = "Сначала выдайте разрешение камеры ниже."; return }
        if (Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) == null) { error = "На устройстве нет приложения камеры."; return }
        discardCapture()
        runCatching {
            val uri = AssistContext.cameraUri(context)
            cameraUri = uri.toString()
            cameraPending = true
            camera.launch(uri)
        }.onFailure { cameraPending = false; discardCapture(); error = "Не удалось открыть камеру. Черновик не изменён." }
    }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Добавить к запросу", style = MaterialTheme.typography.titleLarge)
            Text("Выбранные данные остаются в черновике. Они отправятся только с этим запросом после кнопки «Отправить». Внешний текст не является инструкцией.")
            if (page != "sources") TextButton(onClick = { page = "sources"; error = null }, enabled = !cameraPending && !model.attachmentBusy) { Text("К источникам") }
            when (page) {
                "sources" -> {
                    TextButton(onClick = { runCatching { picker.launch(arrayOf("image/jpeg", "image/png", "image/gif", "image/webp")) }.onFailure { error = "Системный выбор изображений недоступен." } }, enabled = !model.attachmentBusy) { Text("Изображение из файлов · до 5 МиБ") }
                    TextButton(onClick = { page = "camera" }) { Text("Снять камерой") }
                    TextButton(onClick = { page = "calendar" }) { Text("Выбрать события календаря") }
                    TextButton(onClick = { page = "paste" }) { Text("Вставить текст из заметок или другого приложения") }
                    Text("Текст текущего экрана доступен через системный вызов ассистента и отдельный просмотр. Заметки можно вставить явно. Alphy не читает базы заметок.", style = MaterialTheme.typography.bodySmall)
                }
                "camera" -> {
                    AndroidPermissionControl("Камера", listOf(Manifest.permission.CAMERA), PackageManager.FEATURE_CAMERA_ANY)
                    if (cameraReady && cameraUri != null) {
                        ImagePreview(Attachment(cameraUri!!, "Снимок камеры", "image/jpeg"), Modifier.fillMaxWidth().height(260.dp))
                        Text("Полноразмерный снимок · проверьте перед добавлением.")
                        Button(onClick = {
                            val attachment = Attachment(cameraUri!!, "Снимок камеры", "image/jpeg")
                            model.addAttachment(attachment, origin) { accepted ->
                                if (accepted) { cameraUri = null; cameraReady = false; dismiss() }
                            }
                        }, enabled = !model.attachmentBusy) { Text("Прикрепить снимок") }
                    }
                    TextButton(onClick = ::takePicture, enabled = !cameraPending && !model.attachmentBusy) { Text(if (cameraUri == null) "Открыть камеру" else "Переснять") }
                    Text("Отмена удалит только этот неподтверждённый снимок. Уже добавленные файлы сохранятся.", style = MaterialTheme.typography.bodySmall)
                }
                "calendar" -> CalendarContextReview(model, origin, ::close)
                "paste" -> {
                    OutlinedTextField(pasted, { if (it.length <= 8000) pasted = it else error = "Текст превышает 8000 символов. Предыдущий текст сохранён. Сократите источник и вставьте нужный фрагмент." }, label = { Text("Выбранный текст · до 8000 символов") },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), isError = pasted.length > 8000)
                    Text("${pasted.length} / 8000 · проверьте и при необходимости сократите текст")
                    Button(onClick = { if (model.addReviewedText(pasted, "Вставленный текст", origin)) close() },
                        enabled = pasted.isNotBlank() && pasted.length <= 8000 && !model.loading && !model.loadFailed) { Text("Прикрепить проверенный текст") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (model.attachmentBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = ::close, enabled = !cameraPending && !model.attachmentBusy) { Text("Отмена") }
        }
    }
}

@Composable private fun CalendarContextReview(model: ShellViewModel, origin: String, attached: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var calendars by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var calendarId by rememberSaveable { mutableStateOf<Long?>(null) }
    var date by rememberSaveable { mutableStateOf(java.time.LocalDate.now().toString()) }
    var days by rememberSaveable { mutableStateOf("7") }
    var eventsJson by rememberSaveable { mutableStateOf("[]") }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var truncatedEvents by rememberSaveable { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf<String?>(null) }
    var truncatedText by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val events = remember(eventsJson) { ReviewedCalendar.decode(eventsJson) }
    fun permitted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    AndroidPermissionControl("Календарь · только чтение", listOf(Manifest.permission.READ_CALENDAR))
    Text("Выберите один календарь и интервал до 31 дня. Alphy прочитает не более 50 событий для локального просмотра. События не изменяются.")
    if (review == null) {
        TextButton(onClick = {
            if (!permitted()) { error = "Выдайте разрешение чтения календаря."; return@TextButton }
            busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { ReviewedCalendar.calendars(context) } }
                    .onSuccess { calendars = it; error = if (it.isEmpty()) "Доступных календарей нет. Проверьте календарные аккаунты Android." else null }
                    .onFailure { error = "Календари недоступны. Проверьте разрешение и повторите запрос." }
                busy = false
            }
        }, enabled = !busy) { Text("Показать календари") }
        calendars.forEach { (id, name) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(calendarId == id, { calendarId = id; eventsJson = "[]"; selection = emptyList(); truncatedEvents = false }, enabled = !busy)
                Text(name)
            }
        }
        OutlinedTextField(date, { if (it.length <= 10) { date = it; eventsJson = "[]"; selection = emptyList(); truncatedEvents = false } else error = "Укажите дату ГГГГ-ММ-ДД." }, label = { Text("Начало · ГГГГ-ММ-ДД") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(days, { if (it.length <= 2) { days = it; eventsJson = "[]"; selection = emptyList(); truncatedEvents = false } else error = "Интервал должен быть от 1 до 31 дня." }, label = { Text("Число дней · 1–31") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        TextButton(onClick = {
            val from = runCatching { java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            val count = days.toIntOrNull()
            if (!permitted()) { error = "Разрешение календаря отозвано. Выдайте его и повторите запрос."; return@TextButton }
            if (from == null || count == null || count !in 1..31) { error = "Укажите дату ГГГГ-ММ-ДД и интервал от 1 до 31 дня."; return@TextButton }
            val id = calendarId ?: return@TextButton
            busy = true; review = null; error = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { ReviewedCalendar.instances(context, id, from, count) } }
                    .onSuccess { (found, truncated) -> eventsJson = ReviewedCalendar.encode(found); selection = emptyList(); truncatedEvents = truncated; error = if (found.isEmpty()) "Событий в этом интервале нет." else null }
                    .onFailure { eventsJson = "[]"; selection = emptyList(); error = "События недоступны. Проверьте доступ к календарю." }
                busy = false
            }
        }, enabled = !busy && calendarId != null) { Text("Прочитать выбранный интервал") }
        if (truncatedEvents) Text("Список ограничен 50 событиями, длинные названия и места сокращены до 1000 символов. Сократите интервал или проверьте исходный календарь.")
        events.forEach { event ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(event.key in selection, { checked -> selection = if (checked) selection + event.key else selection - event.key })
                Text(AssistContext.calendarText(listOf(event), truncate = true).take(600), modifier = Modifier.weight(1f))
            }
        }
        Button(onClick = {
            val chosen = events.filter { it.key in selection }
            truncatedText = runCatching { AssistContext.calendarText(chosen) }.isFailure
            review = AssistContext.calendarText(chosen, truncate = true)
        }, enabled = selection.isNotEmpty() && !busy) { Text("Проверить выбранные события") }
    } else {
        if (truncatedText) Text("Текст сокращён до 8000 символов. Проверьте окончание и при необходимости выберите меньше событий.")
        OutlinedTextField(review.orEmpty(), { if (it.length <= 8000) review = it else error = "Текст превышает 8000 символов. Предыдущий текст сохранён." }, label = { Text("Контекст календаря · внешний текст") }, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), isError = review.orEmpty().length > 8000)
        Text("${review.orEmpty().length} / 8000")
        Button(onClick = {
            if (!permitted()) error = "Разрешение календаря отозвано. Вернитесь к выбору и повторите чтение."
            else if (model.addReviewedText(review.orEmpty(), "Выбранные события календаря", origin)) attached()
        }, enabled = review.orEmpty().isNotBlank() && review.orEmpty().length <= 8000) { Text("Прикрепить проверенные события") }
        TextButton(onClick = { review = null }) { Text("Изменить выбор событий") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable internal fun ShareTextToNotes(text: String, dismiss: () -> Unit) {
    val context = LocalContext.current
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(text.take(8000), TextRange(0, minOf(text.length, 8000)))) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Передать выбранный текст в заметки") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Выделите нужный фрагмент или отредактируйте текст. В системном окне выберите установленное приложение заметок и сохраните текст в нём.")
            if (text.length > 8000) Text("Исходный текст сокращён до первых 8000 символов.")
            OutlinedTextField(value, { if (it.text.length <= 8000) value = it else message = "Текст превышает 8000 символов. Предыдущий текст сохранён." }, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), label = { Text("Текст для заметки") })
            Text("Открытие приложения не подтверждает сохранение заметки.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val chosen = if (value.selection.collapsed) value.text else value.text.substring(value.selection.min, value.selection.max)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, chosen)
        if (context.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) message = "Нет установленного приложения для передачи текста. Можно скопировать текст вручную."
        else runCatching { context.startActivity(Intent.createChooser(send, "Выберите приложение заметок")) }
            .onSuccess { message = "Открыт выбор приложения. Сохранение заметки не подтверждено." }
            .onFailure { message = "Не удалось открыть приложение. Текст остался доступен здесь." }
    }, enabled = value.text.isNotBlank() && value.text.length <= 8000) { Text("Передать выбранный текст") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Закрыть") } })
}
