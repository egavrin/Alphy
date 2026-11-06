package com.alpha.assistant.ui

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.AndroidPermissionControl
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.surfaces.AssistContext
import com.alpha.assistant.surfaces.ReviewedCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReviewedContextDialog(
    model: ShellViewModel,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val origin = rememberSaveable { model.contextDraftKey() }
    var page by rememberSaveable { mutableStateOf("sources") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var pickedText by rememberSaveable { mutableStateOf("") }
    var pickerSource by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraReady by rememberSaveable { mutableStateOf(false) }
    var cameraPending by rememberSaveable { mutableStateOf(false) }
    val cameraCaptureName = stringResource(R.string.context_camera_capture_name)
    val pastedTextName = stringResource(R.string.context_pasted_text_name)
    val imageName = stringResource(R.string.context_image_name)
    val textFileName = stringResource(R.string.context_text_file_name)
    fun checkOrigin(): Boolean {
        if (origin == model.contextDraftKey()) return true
        error = R.string.context_draft_changed
        return false
    }
    fun discardCapture() {
        cameraUri?.let { runCatching { AssistContext.cameraFile(context, Uri.parse(it)).delete() } }
        cameraUri = null
        cameraReady = false
    }
    fun close() { if (!cameraPending && !model.attachmentBusy) { discardCapture(); dismiss() } }
    fun receivePick(uri: Uri?) {
        if (pickerSource == null) return
        if (uri == null || !checkOrigin()) pickerSource = null
        else pickedUri = uri.toString()
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::receivePick)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::receivePick)
    fun launchPicker(source: String, launch: () -> Unit) {
        if (pickerSource != null || !checkOrigin()) return
        error = null
        pickerSource = source
        runCatching(launch).onFailure { pickerSource = null; error = R.string.context_picker_unavailable }
    }
    LaunchedEffect(pickedUri) {
        val uri = pickedUri?.let(Uri::parse) ?: return@LaunchedEffect
        val resolver = context.contentResolver
        if (checkOrigin()) {
            val mime = withContext(Dispatchers.IO) {
                runCatching { if (uri.scheme == "content") resolver.getType(uri) else null }.getOrNull()
            }
            when (mime) {
                "image/jpeg", "image/png", "image/gif", "image/webp" -> {
                    val name = withContext(Dispatchers.IO) {
                        runCatching {
                            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            }
                        }.getOrNull() ?: imageName
                    }
                    if (checkOrigin() && model.attachImage(Attachment(uri.toString(), name, mime), origin, persistReadAccess = true)) close()
                }
                "text/plain" -> if (pickerSource == "file") {
                    val text = withContext(Dispatchers.IO) { runCatching { readTextContext(resolver, uri) }.getOrNull() }
                    if (checkOrigin()) {
                        if (text == null) error = R.string.context_file_limit
                        else { pickedText = text; page = "file-text" }
                    }
                } else error = R.string.context_file_limit
                else -> error = R.string.context_file_limit
            }
        }
        pickedUri = null
        pickerSource = null
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPending = false
        if (!checkOrigin()) discardCapture()
        else if (!success) { discardCapture(); error = R.string.context_camera_cancelled }
    }
    LaunchedEffect(cameraUri, cameraPending) {
        val uri = cameraUri
        if (uri != null && !cameraPending) {
            val result = withContext(Dispatchers.IO) { runCatching { AssistContext.validatedCapture(context, Uri.parse(uri)) } }
            if (checkOrigin()) {
                cameraReady = result.isSuccess
                error = if (result.isSuccess) null else R.string.context_camera_invalid_capture
            } else discardCapture()
        }
    }

    fun takePicture() {
        if (!checkOrigin()) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) { error = R.string.context_camera_missing; return }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { error = R.string.context_camera_permission; return }
        if (Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) == null) { error = R.string.context_camera_app_missing; return }
        discardCapture()
        runCatching {
            val uri = AssistContext.cameraUri(context)
            cameraUri = uri.toString()
            cameraPending = true
            camera.launch(uri)
        }.onFailure { cameraPending = false; discardCapture(); error = R.string.context_camera_open_failed }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = ::close, sheetState = sheet,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)) {
        // System Back goes to the sheet window, which has its own dispatcher.
        val dialogOwner = checkNotNull(LocalView.current.findViewTreeNavigationEventDispatcherOwner())
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dialogOwner) {
            BackHandler {
                if (!cameraPending && !model.attachmentBusy) scope.launch {
                    sheet.hide()
                    if (!sheet.isVisible) close()
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.context_title), style = MaterialTheme.typography.titleLarge)
                if (page != "sources") Text(stringResource(R.string.context_privacy))
                if (page != "sources") TextButton(onClick = { page = "sources"; error = null }, enabled = !cameraPending && !model.attachmentBusy) { Text(stringResource(R.string.context_back_to_sources)) }
                when (page) {
                    "sources" -> {
                        fun canChooseSource() = pickerSource == null && !model.attachmentBusy
                        val enabled = canChooseSource()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { if (checkOrigin()) page = "camera" }, label = { Text(stringResource(R.string.context_camera)) }, enabled = enabled)
                            AssistChip(onClick = { launchPicker("photo") { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                                label = { Text(stringResource(R.string.context_photo)) }, enabled = enabled)
                            AssistChip(onClick = { launchPicker("file") { files.launch(arrayOf("*/*")) } }, label = { Text(stringResource(R.string.context_file)) }, enabled = enabled)
                            AssistChip(onClick = { if (checkOrigin()) page = "calendar" }, label = { Text(stringResource(R.string.context_calendar)) }, enabled = enabled)
                        }
                        TextButton(onClick = { if (checkOrigin()) page = "paste" }, enabled = enabled) { Text(stringResource(R.string.context_paste)) }
                    }
                    "camera" -> {
                        AndroidPermissionControl(stringResource(R.string.context_camera), listOf(Manifest.permission.CAMERA), PackageManager.FEATURE_CAMERA_ANY)
                        if (cameraReady && cameraUri != null) {
                            ImagePreview(Attachment(cameraUri!!, cameraCaptureName, "image/jpeg"), Modifier.fillMaxWidth().height(260.dp))
                            Text(stringResource(R.string.context_camera_review))
                            Button(onClick = {
                                val attachment = Attachment(cameraUri!!, cameraCaptureName, "image/jpeg")
                                scope.launch {
                                    if (checkOrigin() && model.attachImage(attachment, origin)) {
                                        cameraUri = null
                                        cameraReady = false
                                        dismiss()
                                    }
                                }
                            }, enabled = !model.attachmentBusy) { Text(stringResource(R.string.context_camera_attach)) }
                        }
                        TextButton(onClick = ::takePicture, enabled = !cameraPending && !model.attachmentBusy) { Text(if (cameraUri == null) stringResource(R.string.context_camera_open) else stringResource(R.string.context_camera_retake)) }
                        Text(stringResource(R.string.context_camera_cancel_info), style = MaterialTheme.typography.bodySmall)
                    }
                    "calendar" -> CalendarContextReview(model, origin, ::close)
                    "paste", "file-text" -> {
                        val fromFile = page == "file-text"
                        val text = if (fromFile) pickedText else pasted
                        OutlinedTextField(text, {
                            if (it.length > TEXT_CONTEXT_LIMIT) error = R.string.context_text_paste_limit
                            else if (fromFile) pickedText = it else pasted = it
                        }, label = { Text(stringResource(R.string.context_text_label)) }, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
                        Text(stringResource(R.string.context_text_count_review, text.length))
                        Button(onClick = {
                            if (checkOrigin() && model.addReviewedText(text, if (fromFile) textFileName else pastedTextName, origin)) close()
                        }, enabled = text.isNotBlank() && !model.loading && !model.loadFailed && !model.attachmentBusy) { Text(stringResource(R.string.context_text_attach)) }
                    }
                }
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (model.attachmentBusy || pickedUri != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = ::close, enabled = !cameraPending && !model.attachmentBusy) { Text(stringResource(R.string.cancel)) }
        }
    }
}

private const val TEXT_CONTEXT_LIMIT = 8000

private fun readTextContext(resolver: ContentResolver, uri: Uri): String {
    val input = checkNotNull(resolver.openInputStream(uri))
    return InputStreamReader(input, Charsets.UTF_8.newDecoder()).use { reader ->
        val chars = CharArray(TEXT_CONTEXT_LIMIT + 1)
        var length = 0
        while (length < chars.size) {
            val count = reader.read(chars, length, chars.size - length)
            if (count < 0) break
            length += count
        }
        require(length <= TEXT_CONTEXT_LIMIT)
        String(chars, 0, length).also { require(it.isNotBlank()) }
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
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    val calendarSelectionName = stringResource(R.string.context_calendar_selection_name)
    val events = remember(eventsJson) { ReviewedCalendar.decode(eventsJson) }
    fun permitted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    AndroidPermissionControl(stringResource(R.string.context_calendar_read_only), listOf(Manifest.permission.READ_CALENDAR))
    Text(stringResource(R.string.context_calendar_privacy))
    if (review == null) {
        TextButton(onClick = {
            if (!permitted()) { error = R.string.context_calendar_permission; return@TextButton }
            busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { ReviewedCalendar.calendars(context) } }
                    .onSuccess { calendars = it; error = if (it.isEmpty()) R.string.context_calendar_missing else null }
                    .onFailure { error = R.string.context_calendar_unavailable }
                busy = false
            }
        }, enabled = !busy) { Text(stringResource(R.string.context_calendar_list)) }
        calendars.forEach { (id, name) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(calendarId == id, { calendarId = id; eventsJson = "[]"; selection = emptyList(); truncatedEvents = false }, enabled = !busy)
                Text(name)
            }
        }
        OutlinedTextField(date, { if (it.length <= 10) { date = it; eventsJson = "[]"; selection = emptyList(); truncatedEvents = false } else error = R.string.context_calendar_invalid_date }, label = { Text(stringResource(R.string.context_calendar_start_label)) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(days, { if (it.length <= 2) { days = it; eventsJson = "[]"; selection = emptyList(); truncatedEvents = false } else error = R.string.context_calendar_invalid_days }, label = { Text(stringResource(R.string.context_calendar_days_label)) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        TextButton(onClick = {
            val from = runCatching { java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            val count = days.toIntOrNull()
            if (!permitted()) { error = R.string.context_calendar_permission_lost; return@TextButton }
            if (from == null || count == null || count !in 1..31) { error = R.string.context_calendar_invalid_window; return@TextButton }
            val id = calendarId ?: return@TextButton
            busy = true; review = null; error = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { ReviewedCalendar.instances(context, id, from, count) } }
                    .onSuccess { (found, truncated) -> eventsJson = ReviewedCalendar.encode(found); selection = emptyList(); truncatedEvents = truncated; error = if (found.isEmpty()) R.string.context_calendar_empty else null }
                    .onFailure { eventsJson = "[]"; selection = emptyList(); error = R.string.context_calendar_events_unavailable }
                busy = false
            }
        }, enabled = !busy && calendarId != null) { Text(stringResource(R.string.context_calendar_read)) }
        if (truncatedEvents) Text(stringResource(R.string.context_calendar_event_limit))
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
        }, enabled = selection.isNotEmpty() && !busy) { Text(stringResource(R.string.context_calendar_review)) }
    } else {
        if (truncatedText) Text(stringResource(R.string.context_calendar_text_limit))
        OutlinedTextField(review.orEmpty(), { if (it.length <= 8000) review = it else error = R.string.context_calendar_text_edit_limit }, label = { Text(stringResource(R.string.context_calendar_text_label)) }, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), isError = review.orEmpty().length > 8000)
        Text(stringResource(R.string.context_text_count, review.orEmpty().length))
        Button(onClick = {
            if (!permitted()) error = R.string.context_calendar_review_permission_lost
            else if (model.addReviewedText(review.orEmpty(), calendarSelectionName, origin)) attached()
        }, enabled = review.orEmpty().isNotBlank() && review.orEmpty().length <= 8000) { Text(stringResource(R.string.context_calendar_attach)) }
        TextButton(onClick = { review = null }) { Text(stringResource(R.string.context_calendar_change_selection)) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
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
