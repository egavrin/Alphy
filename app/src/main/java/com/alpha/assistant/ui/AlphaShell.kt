package com.alpha.assistant.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalUriHandler
import com.alpha.assistant.cloud.ImageInput
import com.alpha.assistant.cloud.validSourceUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.Conversation
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AlphaShell(model: ShellViewModel) {
    var page by rememberSaveable { mutableStateOf("chat") }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var consumedSurface by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(model.surfaceSequence) {
        if (model.surfaceSequence > 0 && model.surfaceSequence != consumedSurface) {
            page = if (model.surfaceToolId != null) "tools" else "chat"
            sheet = null
            consumedSurface = model.surfaceSequence
        }
    }
    val snapshot = model.snapshot
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun notice(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun navigate(destination: String) { sheet = null; page = destination; focus.clearFocus() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Файл"
                val attachment = Attachment(uri.toString(), name, context.contentResolver.getType(uri) ?: "application/octet-stream")
                model.addAttachment(attachment)
            }.onFailure { notice("Не удалось сохранить доступ к файлу. Выберите файл ещё раз.") }
        }
    }
    BackHandler(sheet != null || page != "chat") { if (sheet != null) sheet = null else page = "chat" }
    val activity = context as? Activity
    VoiceVisibility(model.voice)
    DisposableEffect(page) {
        if (page == "settings") activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (model.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (model.loadFailed) {
            Column(Modifier.safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Не удалось открыть данные", style = MaterialTheme.typography.headlineSmall)
                Text("Сохранённые данные не удалены. Повторите попытку.")
                Button(onClick = model::load) { Text("Повторить") }
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page != "chat") IconButton(onClick = { page = "chat" }) { AlphaIcon(Glyph.Back) }
                    Text("Alpha", fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
                    Box(Modifier.padding(start = 7.dp, top = 5.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.weight(1f))
                    Text("Личный ассистент", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (model.saveFailed) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Изменения не сохранены", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = model::retrySave) { Text("Повторить") }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    val split = maxWidth >= 640.dp && page == "tools"
                    if (split) {
                        Row(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f).fillMaxHeight()) { ChatPane(model, { navigate("tools") }, { sheet = "context" }, { sheet = "cloud" }, { sheet = "voice" }) }
                            VerticalDivider()
                            Column(Modifier.weight(1f)) { com.alpha.assistant.tools.ToolsPane(model.tools, snapshot.selectedId, snapshot.draft.text, snapshot.draft.attachments) { navigate("chat") } }
                        }
                    } else when (page) {
                        "chat" -> ChatPane(model, { navigate("tools") }, { sheet = "context" }, { sheet = "cloud" }, { sheet = "voice" })
                        "tools" -> com.alpha.assistant.tools.ToolsPane(model.tools, snapshot.selectedId, snapshot.draft.text, snapshot.draft.attachments) { navigate("chat") }
                        "automation" -> com.alpha.assistant.automations.AutomationsPane(model.tools)
                        "capabilities" -> CapabilitiesPane(model.tools)
                        "history" -> HistoryPane(snapshot.conversations, search, { search = it }, { model.select(it); navigate("chat") }, { model.select(null); navigate("chat") })
                        "settings" -> SettingsPane(model)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Surface(onClick = { sheet = "menu"; focus.clearFocus() }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { AlphaIcon(Glyph.Menu); Text("Меню", fontSize = 13.sp) }
                    }
                    TextButton(onClick = { navigate("history") }) { AlphaIcon(Glyph.History); Spacer(Modifier.width(7.dp)); Text("История", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
                }
            }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar, Modifier.padding(bottom = 64.dp)) }
    }
    model.requestError?.let { error ->
        AlertDialog(onDismissRequest = model::clearRequestError, title = { Text("Не удалось отправить") }, text = { Text(error) }, confirmButton = { TextButton(onClick = model::clearRequestError) { Text("Понятно") } })
    }
    if (sheet != null && !model.loadFailed && !model.loading) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).navigationBarsPadding()) {
                val title = when (sheet) { "voice" -> "Local voice"; "context" -> "Добавить к запросу"; "cloud" -> "Облачная обработка"; else -> "Alpha" }
                Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    when (sheet) {
                        "voice" -> VoiceControls(model)

                        "menu" -> {
                            MenuRow("Новый чат", "Отдельный черновик", Glyph.Plus) { model.select(null); navigate("chat") }
                            MenuRow("Мои инструменты", "Сохранённое из разговоров", Glyph.Grid) { navigate("tools") }
                            MenuRow("Автоматизации", "Расписания и журнал", Glyph.Repeat) { navigate("automation") }
                            MenuRow("Контекст и функции", "Источники и доступ", Glyph.Settings) { navigate("capabilities") }
                            Text("ИСТОРИЯ БЕСЕД", Modifier.padding(top = 28.dp, bottom = 10.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (snapshot.conversations.isEmpty()) Text("Пока нет разговоров", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            snapshot.conversations.take(5).forEach { c -> MenuRow(c.title, if (c.draft.text.isNotEmpty() || c.draft.attachments.isNotEmpty()) "Есть черновик" else "Сохранено на устройстве", Glyph.History) { model.select(c.id); navigate("chat") } }
                            TextButton(onClick = { navigate("history") }) { Text("Вся история") }
                        }
                        "context" -> {
                            MenuRow("Изображения", "JPEG, PNG, GIF, WebP · до 5 МиБ · до 4 файлов", Glyph.File) { sheet = null; picker.launch(arrayOf("image/jpeg", "image/png", "image/gif", "image/webp")) }
                            MenuRow("Камера и фото", "Ещё не подключены", Glyph.Camera) { notice("Камера и фото ещё не подключены") }
                            MenuRow("Текущий экран", "Ещё не подключён", Glyph.Screen) { notice("Доступ к экрану ещё не подключён") }
                            MenuRow("Календарь", "Доступ не запрашивался", Glyph.Calendar) { notice("Календарь ещё не подключён") }
                            Text("Изображения остаются в черновике до отправки. Alpha передаёт выбранные изображения в DeepSeek только с этим запросом. Следующие запросы не используют эти изображения автоматически.", Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        "cloud" -> {
                            Notice("Диалог обрабатывает DeepSeek с вашим API-ключом. Кнопка отправки передаёт текст запроса и до 40 сообщений завершённого диалога в выбранном чате. Изображения передаются только с выбранным запросом. История хранится на устройстве. Облачный провайдер обрабатывает переданные данные по своим правилам и тарифам.")
                        }
                    }
                }
                if (sheet == "menu") {
                    HorizontalDivider(Modifier.padding(top = 10.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Локальный профиль", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { navigate("settings") }) { AlphaIcon(Glyph.Settings); Spacer(Modifier.width(7.dp)); Text("Настройки") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable private fun ChatPane(model: ShellViewModel, tools: () -> Unit, context: () -> Unit, mode: () -> Unit, voice: () -> Unit) {
    val snapshot = model.snapshot
    var confirmSend by remember { mutableStateOf(false) }
    var retryId by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    val busy = model.activeConversationId != null
    if (confirmSend || retryId != null) {
        val retry = snapshot.selected?.messages?.find { it.id == retryId }?.replyTo?.let { id -> snapshot.selected?.messages?.find { it.id == id } }
        val images = retry?.attachments ?: snapshot.draft.attachments
        val search = retry?.search ?: model.searchEnabled
        AlertDialog(onDismissRequest = { confirmSend = false; retryId = null }, title = { Text("Отправить в DeepSeek?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Будут переданы текст этого запроса и до 40 сообщений завершённого диалога в этом чате. Используется ваш API-ключ и тариф DeepSeek.")
                if (images.isNotEmpty()) Text("Только для этого запроса: " + images.joinToString { it.name } + ". Изображения не передаются повторно в следующих сообщениях. Повтор запроса отправит их снова.")
                if (search) Text("Разрешён веб-поиск DeepSeek: до двух поисковых запросов. Поисковые фразы формирует модель из текста и изображений этого запроса и контекста диалога.")
            } }, confirmButton = { TextButton(onClick = { val id = retryId; confirmSend = false; retryId = null; if (id == null) model.send() else model.retry(id) }) { Text("Отправить") } },
            dismissButton = { TextButton(onClick = { confirmSend = false; retryId = null }) { Text("Отмена") } })
    }
    if (linkError) AlertDialog(onDismissRequest = { linkError = false }, title = { Text("Не удалось открыть ссылку") }, text = { Text("Проверьте наличие браузера на устройстве.") }, confirmButton = { TextButton(onClick = { linkError = false }) { Text("Понятно") } })
    Column(Modifier.fillMaxHeight().widthIn(max = 620.dp).fillMaxWidth()) {
        if (snapshot.selected == null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 27.dp)) {
                Spacer(Modifier.height(65.dp))
                Text("Привет.", fontSize = 34.sp, lineHeight = 43.sp, letterSpacing = (-1).sp)
                Text("Чем займёмся?", fontSize = 34.sp, lineHeight = 43.sp, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Row(Modifier.padding(top = 42.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StartCard("Создать", "Свой инструмент\nили виджет", Glyph.Spark, Modifier.weight(1f), tools)
                    StartCard("Мой день", "Подключить календарь\nи составить план", Glyph.Calendar, Modifier.weight(1f), tools)
                }
            }
        } else {
            val chat = snapshot.selected!!
            val listState = rememberLazyListState()
            LaunchedEffect(chat.id, chat.messages.size, chat.messages.lastOrNull()?.text?.length) {
                val last = chat.messages.size
                if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= last - 1 } != false) listState.scrollToItem(last)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                item { Text(chat.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(model.tools.tools.filter { it.conversationId == chat.id }, key = { "tool-${it.id}" }) { artifact ->
                    com.alpha.assistant.tools.ToolCard(artifact) { model.tools.select(artifact.id); tools() }
                }
                items(chat.messages, key = { it.id }) { message ->
                    Column(Modifier.fillMaxWidth().padding(start = if (message.role == "user") 25.dp else 0.dp)) {
                        Surface(shape = RoundedCornerShape(23.dp), color = if (message.role == "user") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                if (message.text.isNotEmpty()) Text(message.text, fontSize = 15.sp, lineHeight = 23.sp)
                                if (message.role == "user" && message.text.isNotBlank()) TextButton(onClick = { model.tools.preparePrompt(message.text); tools() }) { Text("Create tool from message") }
                                message.attachments.forEach { Text("Изображение · ${it.name} · только в исходном запросе", style = MaterialTheme.typography.bodySmall) }
                                if (message.searchStatus.isNotEmpty()) Text(when (message.searchStatus) {
                                    "searching" -> "Поиск в интернете…"; "found" -> "Веб-поиск завершён"; "empty" -> "Поиск не нашёл результатов"; else -> "Ошибка веб-поиска"
                                }, style = MaterialTheme.typography.labelSmall)
                                message.sources.forEachIndexed { index, source ->
                                    TextButton(onClick = { if (validSourceUrl(source.url)) runCatching { uriHandler.openUri(source.url) }.onFailure { linkError = true } else linkError = true }) { Text("[${index + 1}] ${source.title.ifBlank { source.url }}\n${source.url}", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (message.error.isNotBlank()) Text(message.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                if (message.id == chat.messages.lastOrNull()?.id && message.status in listOf("error", "cancelled", "interrupted")) TextButton(onClick = { retryId = message.id }, enabled = !busy) { Text("Повторить запрос") }
                                if (message.role == "assistant" && message.status in listOf("pending", "streaming")) Text(if (message.status == "pending") "Подготовка запроса…" else "DeepSeek отвечает…", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (message.status == "not_sent") Text("Не отправлено · сохранённое локальное сообщение", Modifier.padding(start = 5.dp, top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
            if (model.voice.active) {
                Text(model.voice.status, style = MaterialTheme.typography.bodySmall)
                if (model.voice.partial.isNotBlank()) Text(model.voice.partial, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.voice.stop() }) { Text("Stop voice session") }
            }
            snapshot.draft.attachments.forEach { attachment ->
                ImagePreview(attachment)
                InputChip(selected = true, onClick = { model.editDraft(snapshot.draft.copy(attachments = snapshot.draft.attachments - attachment)) }, label = { Text(attachment.name, maxLines = 1) }, leadingIcon = { AlphaIcon(Glyph.File) }, trailingIcon = { AlphaIcon(Glyph.Close) }, modifier = Modifier.semantics { contentDescription = "Убрать файл ${attachment.name}" })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = model.searchEnabled, onCheckedChange = model::setSearch)
                Text("Веб-поиск DeepSeek", style = MaterialTheme.typography.bodySmall)
                if (model.attachmentBusy) Text(" · проверка изображения…", style = MaterialTheme.typography.labelSmall)
            }
            Surface(shape = RoundedCornerShape(27.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(12.dp)) {
                    BasicTextField(value = snapshot.draft.text, onValueChange = { model.editDraft(snapshot.draft.copy(text = it)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 43.dp, max = 150.dp).padding(horizontal = 7.dp, vertical = 5.dp).semantics { contentDescription = "Сообщение ассистенту" }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner ->
                        if (snapshot.draft.text.isEmpty()) Text("Спроси о чём угодно", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                        inner()
                    })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RoundAction(Glyph.Plus, "Добавить контекст", context)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = mode) { AlphaIcon(Glyph.Cloud); Spacer(Modifier.width(5.dp)); Text("Облако", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
                        RoundAction(Glyph.Mic, "Голосовой запрос", voice)
                        Spacer(Modifier.width(5.dp))
                        if (busy) RoundAction(Glyph.Close, "Остановить ответ", model::cancelRequest)
                        else RoundAction(Glyph.Arrow, "Отправить сообщение", { confirmSend = true }, primary = true, enabled = !model.attachmentBusy && (snapshot.draft.text.isNotBlank() || snapshot.draft.attachments.isNotEmpty()))
                    }
                }
            }
            Text("DeepSeek · облачная обработка · история на устройстве", Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun StartCard(title: String, subtitle: String, glyph: Glyph, modifier: Modifier, action: () -> Unit) {
    Surface(onClick = action, modifier = modifier, shape = RoundedCornerShape(23.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            AlphaIcon(glyph, accent = true)
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun RoundAction(glyph: Glyph, label: String, action: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    FilledIconButton(onClick = action, enabled = enabled, modifier = Modifier.size(46.dp).semantics { contentDescription = label }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        if (primary) Text("↑", color = MaterialTheme.colorScheme.onPrimary, fontSize = 26.sp) else AlphaIcon(glyph)
    }
}

@Composable private fun MenuRow(title: String, detail: String, glyph: Glyph, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        AlphaIcon(glyph)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, fontSize = 15.sp); if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        AlphaIcon(Glyph.Chevron, Modifier.size(15.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun EmptyPane(kicker: String, title: String, glyph: Glyph, heading: String, text: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(kicker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        AlphaIcon(glyph, Modifier.size(38.dp), accent = true)
        Text(heading, style = MaterialTheme.typography.titleLarge)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 25.sp)
    }
}

@Composable private fun HistoryPane(conversations: List<Conversation>, search: String, edit: (String) -> Unit, select: (String) -> Unit, newChat: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Разговоры", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium); TextButton(onClick = newChat) { AlphaIcon(Glyph.Plus); Text("Новый чат") } }
        OutlinedTextField(value = search, onValueChange = edit, placeholder = { Text("Поиск в истории") }, leadingIcon = { AlphaIcon(Glyph.Search) }, modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp).semantics { contentDescription = "Поиск разговоров" }, singleLine = true, shape = RoundedCornerShape(18.dp))
        val results = conversations.filter { c -> search.isBlank() || c.title.contains(search, true) || c.messages.any { it.text.contains(search, true) } || c.draft.text.contains(search, true) }
        LazyColumn(Modifier.weight(1f)) {
            if (results.isEmpty()) item { Text(if (search.isBlank()) "Здесь появятся твои разговоры." else "Ничего не найдено. Попробуй другой запрос.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(results, key = { it.id }) { c -> MenuRow(c.title, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.updatedAt)) + if (c.draft.text.isNotEmpty() || c.draft.attachments.isNotEmpty()) " · черновик" else " · на устройстве", Glyph.History) { select(c.id) } }
        }
    }
}

@Composable private fun CapabilitiesPane(controller: com.alpha.assistant.tools.ToolController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(25.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Контекст и функции", style = MaterialTheme.typography.headlineMedium)
        Text("Только выбранные источники", color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.alpha.assistant.capabilities.CapabilitySettings(controller)
    }
}

@Composable private fun SettingsPane(model: ShellViewModel) {
    var key by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(25.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Text("Оформление", style = MaterialTheme.typography.titleMedium)
        listOf("system" to "Как в системе", "light" to "Светлая тема", "dark" to "Тёмная тема").forEach { (value, title) ->
            Row(Modifier.fillMaxWidth().clickable { model.setting("theme", value) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = model.snapshot.theme == value, onClick = { model.setting("theme", value) }); Text(title) }
        }
        HorizontalDivider()
        Text("DeepSeek", style = MaterialTheme.typography.titleLarge)
        Text(model.keyStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API-ключ DeepSeek") }, placeholder = { Text("Введи новый ключ") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), supportingText = { Text("Ключ хранится на устройстве в зашифрованном виде.") })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { model.saveKey(key) { if (it) key = "" } }, enabled = key.isNotBlank() && !model.keyBusy) { Text("Сохранить ключ") }
            TextButton(onClick = { confirmClear = true }, enabled = !model.keyBusy) { Text("Удалить ключ") }
        }
        Notice("Текст и выбранные изображения обрабатывает DeepSeek после подтверждения отправки. Веб-поиск выполняется только при выборе этой функции. Сохранение ключа не отправляет данные и не проверяет баланс.")
        com.alpha.assistant.surfaces.SystemEntrySettings()
        Text("Alpha · 0.1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Удалить API-ключ?") }, text = { Text("Разговоры и черновики сохранятся.") }, confirmButton = { TextButton(onClick = { model.clearKey(); confirmClear = false }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } })
}

@Composable private fun Notice(text: String) {
    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable private fun ImagePreview(attachment: Attachment) {
    val resolver = LocalContext.current.contentResolver
    val preview by produceState<ImageBitmap?>(null, attachment.uri) {
        value = withContext(Dispatchers.IO) { runCatching {
            val bytes = ImageInput.read(resolver, attachment)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 200)
            })?.asImageBitmap()
        }.getOrNull() }
    }
    if (preview != null) Image(preview!!, contentDescription = "Предпросмотр ${attachment.name}", modifier = Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)))
    else Text("Предпросмотр недоступен: ${attachment.name}. При отправке доступ будет проверен снова.", style = MaterialTheme.typography.bodySmall)
}
