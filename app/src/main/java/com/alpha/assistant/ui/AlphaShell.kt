package com.alpha.assistant.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.alpha.assistant.R
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.Conversation
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable fun AlphaShell(model: ShellViewModel) {
    var destination by rememberSaveable { mutableStateOf<ShellDestination?>(null) }
    var contextSheet by rememberSaveable { mutableStateOf(false) }
    var automationRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var consumedSurface by rememberSaveable { mutableStateOf(0) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(model.surfaceSequence) {
        // A recreation consumes no entry, because consumedSurface is saved, so a rotation keeps an open drawer open.
        if (model.surfaceSequence > 0 && model.surfaceSequence != consumedSurface) {
            destination = if (model.surfaceToolId != null) ShellDestination.MiniApps else null
            contextSheet = false
            consumedSurface = model.surfaceSequence
            drawerState.snapTo(DrawerValue.Closed)
        }
    }
    val snapshot = model.snapshot
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun notice(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun navigate(target: ShellDestination?) { contextSheet = false; destination = target; focus.clearFocus() }
    fun closeDrawer() { scope.launch { drawerState.close() } }
    // An overlay handler comes before NavDisplay and the entry handlers, so the drawer takes Back while it targets Open.
    val handler = remember { object : NavigationEventHandler<NavigationEventInfo.None>(NavigationEventInfo.None, false) { override fun onBackCompleted() = closeDrawer() } }
    // The composition reads targetValue, because a read inside SideEffect causes no recomposition.
    val drawerTargetsOpen = drawerState.targetValue == DrawerValue.Open
    SideEffect { handler.isBackEnabled = drawerTargetsOpen }
    val dispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
    DisposableEffect(dispatcher) { dispatcher.addHandler(handler, NavigationEventDispatcher.PRIORITY_OVERLAY); onDispose { handler.remove() } }
    fun back() { if (destination != null) navigate(null) else model.select(null) }
    val backStack = listOfNotNull(ShellRoute.Desktop, snapshot.selected?.let { ShellRoute.Chat(it.id) }, destination)
    VoiceVisibility(model.voice)
    WeatherLocationAccess(model)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (model.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (model.loadFailed) {
            Column(Modifier.safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.load_failed), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.load_failed_text))
                Button(onClick = model::load) { Text(stringResource(R.string.retry)) }
            }
        } else ModalNavigationDrawer({
            ShellDrawerSheet(drawerState, snapshot.conversations, snapshot.selectedId, destination, stringResource(R.string.profile),
                { navigate(it); closeDrawer() }, { model.select(it); navigate(null); closeDrawer() }) { model.select(null); navigate(null); closeDrawer() }
        }, drawerState = drawerState, gesturesEnabled = drawerState.isOpen || backStack.last() !is ShellDestination) {
            val focusedMiniApp=destination == ShellDestination.MiniApps && (model.tools.selectedId != null || model.tools.selectedDraftId != null)
            val selectedTool=model.tools.tools.find {it.id==model.tools.selectedId}
            val selectedSpec=remember(selectedTool?.spec) {selectedTool?.let {com.alpha.assistant.tools.ToolSpec.available(it.spec)}}
            var immersiveExited by rememberSaveable(selectedTool?.id,selectedTool?.revision,destination == ShellDestination.MiniApps) {mutableStateOf(false)}
            val immersive=focusedMiniApp && model.tools.selectedDraftId==null && selectedTool?.archived==false && selectedSpec?.presentation=="immersive" && !immersiveExited
            Column(Modifier.fillMaxSize().then(if(immersive)Modifier else Modifier.safeDrawingPadding())) {
                if (!focusedMiniApp) ShellTopBar(stringResource(destination?.title ?: R.string.app_name),
                    { focus.clearFocus(); scope.launch { drawerState.open() } }) { model.select(null); navigate(null) }
                if (model.saveFailed && !immersive) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.save_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = model::retrySave) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    val split = maxWidth >= 640.dp && destination == ShellDestination.MiniApps && !immersive
                    // A session continues while the shell shows a composer or VoiceControls.
                    val voiceShown = backStack.last() !is ShellDestination || split || backStack.last() == ShellDestination.Settings
                    LaunchedEffect(voiceShown) { if (!voiceShown) model.voice.stop() }
                    @Composable fun ChatEntry(conversation: Conversation?) = ChatPane(model, conversation, { navigate(ShellDestination.MiniApps) }, { contextSheet = true }) { request -> automationRequest=request; navigate(ShellDestination.Automations) }
                    @Composable fun ToolsEntry() = com.alpha.assistant.tools.ToolsPane(model.tools, snapshot.selectedId, snapshot.draft.text, snapshot.draft.attachments.filter { it.contextText == null },immersive=immersive,exitImmersive={immersiveExited=true}) { navigate(null) }
                    // The tool and capability gates read RESUMED as "Alphy is in the foreground". NavDisplay holds a scene at STARTED
                    // during its transition, so each entry in the back stack keeps the shell lifecycle. A leaving entry is CREATED, as in Navigation 3.
                    val shellLifecycle = LocalLifecycleOwner.current
                    val currentBackStack by rememberUpdatedState(backStack)
                    val keepShellLifecycle = remember(shellLifecycle) { NavEntryDecorator<ShellRoute> { entry ->
                        val maxLifecycle = if (currentBackStack.any { it.toString() == entry.contentKey }) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
                        CompositionLocalProvider(LocalLifecycleOwner provides rememberLifecycleOwner(maxLifecycle, shellLifecycle)) { entry.Content() }
                    } }
                    // NavDisplay gives each entry fixed constraints. The Box loosens them, so a pane keeps its maximum width and stays centered.
                    val centerEntry = remember { NavEntryDecorator<ShellRoute> { entry -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { entry.Content() } } }
                    NavDisplay(backStack, Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter, onBack = ::back, entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), keepShellLifecycle, centerEntry), entryProvider = entryProvider {
                        entry<ShellRoute.Desktop>(clazzContentKey = { it.toString() }) { Box(Modifier.testTag("desktop")) { ChatEntry(null) } }
                        // The route gives the conversation, so a chat keeps its messages during its exit animation.
                        entry<ShellRoute.Chat>(clazzContentKey = { it.toString() }) { route -> Box(Modifier.testTag("chat")) { ChatEntry(snapshot.conversations.find { it.id == route.id }) } }
                        entry<ShellDestination>(clazzContentKey = { it.toString() }) { route ->
                            when (route) {
                                ShellDestination.Search -> HistoryPane(snapshot.conversations, search, { search = it }, { model.select(it); navigate(null) }, { model.select(null); navigate(null) }, model::renameConversation, model::deleteConversation, model::clearHistory)
                                ShellDestination.MiniApps -> if (split) {
                                    Row(Modifier.fillMaxSize()) {
                                        Column(Modifier.weight(1f).fillMaxHeight()) { ChatEntry(snapshot.selected) }
                                        VerticalDivider()
                                        Column(Modifier.weight(1f)) { ToolsEntry() }
                                    }
                                } else ToolsEntry()
                                ShellDestination.Automations -> com.alpha.assistant.automations.AutomationsPane(model.tools,automationRequest) {automationRequest=null}
                                ShellDestination.Access -> CapabilitiesPane(model.tools)
                                ShellDestination.Settings -> SecureWindow { SettingsPane(model) }
                            }
                        }
                    })
                }

            }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar, Modifier.padding(16.dp)) }
    }
    model.requestError?.let { error ->
        AlertDialog(onDismissRequest = model::clearRequestError, title = { Text(stringResource(R.string.send_failed)) }, text = { Text(error) }, confirmButton = { TextButton(onClick = model::clearRequestError) { Text(stringResource(R.string.understood)) } })
    }
    if (contextSheet && !model.loadFailed && !model.loading) ReviewedContextDialog(model) { contextSheet = false }
}

@Composable internal fun ChatPane(model: ShellViewModel, conversation: Conversation?, tools: () -> Unit, context: () -> Unit, automation: (String) -> Unit) {
    val snapshot = model.snapshot
    val uriHandler = LocalUriHandler.current
    var shareText by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf(false) }
    val busy = model.activeConversationId != null
    val startVoice = rememberVoiceStart(model, oneShot = true)
    if (linkError) AlertDialog(onDismissRequest = { linkError = false }, title = { Text("Не удалось открыть ссылку") }, text = { Text("Проверьте наличие браузера на устройстве.") }, confirmButton = { TextButton(onClick = { linkError = false }) { Text("Понятно") } })
    Column(Modifier.fillMaxHeight().widthIn(max = 620.dp).fillMaxWidth()) {
        if (conversation == null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 27.dp)) {
                Spacer(Modifier.height(65.dp))
                Text("Привет.", fontSize = 34.sp, lineHeight = 43.sp, letterSpacing = (-1).sp)
                Text("Чем займёмся?", fontSize = 34.sp, lineHeight = 43.sp, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Row(Modifier.padding(top = 42.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StartCard("Создать", "Свой инструмент\nили виджет", Glyph.Spark, Modifier.weight(1f), tools)
                    StartCard("Мой день", "Подключить календарь\nи составить план", Glyph.Calendar, Modifier.weight(1f)) {automation("Обновляй Мой день из календаря каждый день в 08:00")}
                }
            }
        } else {
            val chat = conversation
            val listState = rememberLazyListState()
            LaunchedEffect(chat.id, chat.messages.size, chat.messages.lastOrNull()?.text?.length) {
                val last = chat.messages.size
                if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= last - 1 } != false) listState.scrollToItem(last)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                item { Text(chat.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(model.tools.tools.filter { tool -> tool.conversationId == chat.id && chat.messages.none { it.toolId == tool.id } }, key = { "tool-${it.id}" }) { artifact ->
                    com.alpha.assistant.tools.ToolCard(artifact) { model.tools.select(artifact.id); tools() }
                }
                items(chat.messages, key = { it.id }) { message ->
                    Column(Modifier.fillMaxWidth().padding(start = if (message.role == "user") 25.dp else 0.dp)) {
                        Surface(shape = RoundedCornerShape(23.dp), color = if (message.role == "user") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                if (message.text.isNotEmpty()) {
                                    MarkdownText(message.text,linkError = { linkError = true })
                                    TextButton(onClick = { shareText = message.text }) { Text("Выбрать текст для заметки") }
                                }
                                if (message.role == "user" && message.text.isNotBlank()) {
                                    TextButton(onClick = { model.tools.preparePrompt(message.text); tools() }) { Text("Create tool from message") }
                                    TextButton(onClick = {automation(message.text)}) {Text("Создать сценарий из запроса")}
                                }
                                message.attachments.forEach { Text("Вложение · ${it.name} · только в исходном запросе", style = MaterialTheme.typography.bodySmall) }
                                if (message.searchStatus.isNotEmpty()) Text(when (message.searchStatus) {
                                    "searching" -> "Поиск в интернете…"; "found" -> "Веб-поиск завершён"; "empty" -> "Поиск не нашёл результатов"; else -> "Ошибка веб-поиска"
                                }, style = MaterialTheme.typography.labelSmall)
                                message.sources.forEachIndexed { index, source ->
                                    TextButton(onClick = { if (validSourceUrl(source.url)) runCatching { uriHandler.openUri(source.url) }.onFailure { linkError = true } else linkError = true }) { Text("[${index + 1}] ${source.title.ifBlank { source.url }}\n${source.url}", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (message.error.isNotBlank()) Text(message.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                if (message.id == chat.messages.lastOrNull()?.id && message.status in listOf("error", "cancelled", "interrupted")) TextButton(onClick = { model.retry(message.id) }, enabled = !busy) { Text("Повторить запрос") }
                                if(message.status == "generating") { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Creating your widget…",style = MaterialTheme.typography.labelSmall) }
                                if (message.role == "assistant" && message.status in listOf("pending", "streaming")) Text(if (model.weatherLocationBusy) "Определяем примерное местоположение…" else if (message.status == "pending") "Подготовка запроса…" else "DeepSeek отвечает…", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if(message.toolId != null && message.toolRevision != null) {
                            val artifact=model.tools.tools.find {it.id == message.toolId}
                            val latest=chat.messages.lastOrNull {it.toolId == message.toolId && it.toolRevision != null}
                            if(artifact != null) {
                                Spacer(Modifier.height(8.dp))
                                com.alpha.assistant.tools.ArtifactCard(artifact,revision=message.toolRevision,
                                    historical=latest?.id != message.id || artifact.revision != message.toolRevision,
                                    open={model.tools.select(artifact.id);tools()},
                                    refine={model.tools.select(artifact.id);tools()})
                            } else Text("Mini-app unavailable · historical revision ${message.toolRevision}",style=MaterialTheme.typography.labelMedium)
                        }
                        if (message.status == "not_sent") Text("Не отправлено · сохранённое локальное сообщение", Modifier.padding(start = 5.dp, top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
            if (model.voice.active && !model.voice.oneShot) {
                Text(model.voice.status, style = MaterialTheme.typography.bodySmall)
                if (model.voice.partial.isNotBlank()) Text(model.voice.partial, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.voice.stop() }) { Text("Остановить сеанс") }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                snapshot.draft.attachments.forEach { attachment ->
                    ImagePreview(attachment)
                    InputChip(selected = true, onClick = { model.editDraft(snapshot.draft.copy(attachments = snapshot.draft.attachments - attachment)) }, label = { Text(attachment.name, maxLines = 1) }, leadingIcon = { AlphaIcon(Glyph.File) }, trailingIcon = { AlphaIcon(Glyph.Close) }, modifier = Modifier.semantics { contentDescription = "Убрать файл ${attachment.name}" })
                }
            }
            if (model.voice.permissionRequired) MicrophonePermissionGuidance(model.voice, startVoice)
            else if (!model.voice.active && model.voice.hasAttempted) Text(model.voice.status, style = MaterialTheme.typography.bodySmall)
            if(snapshot.draft.text.isNotBlank()) TextButton(onClick={automation(snapshot.draft.text)}) {Text("Сценарий из запроса · локальная проверка")}
            if (model.attachmentBusy) Text("Проверка изображения…", style = MaterialTheme.typography.labelSmall)
            Surface(shape = RoundedCornerShape(27.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(12.dp)) {
                    if (model.voice.active && model.voice.oneShot) InlineVoiceRecorder(model)
                    else {
                        BasicTextField(value = snapshot.draft.text, onValueChange = { model.editDraft(snapshot.draft.copy(text = it)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 43.dp, max = 150.dp).padding(horizontal = 7.dp, vertical = 5.dp).semantics { contentDescription = "Сообщение ассистенту" }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner ->
                            if (snapshot.draft.text.isEmpty()) Text("Спроси о чём угодно", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                            inner()
                        })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RoundAction(Glyph.Plus, "Добавить контекст", context)
                            Spacer(Modifier.weight(1f))
                            RoundAction(Glyph.Mic, "Голосовой запрос", startVoice, enabled = !busy && !model.attachmentBusy && !model.voice.active)
                            Spacer(Modifier.width(5.dp))
                            if (busy) RoundAction(Glyph.Close, "Остановить ответ", model::cancelRequest)
                            else RoundAction(Glyph.Arrow, "Отправить сообщение", { model.send() }, primary = true, enabled = !model.attachmentBusy && (snapshot.draft.text.isNotBlank() || snapshot.draft.attachments.isNotEmpty()))
                        }
                    }
                }
            }
        }
    }
    shareText?.let { ShareTextToNotes(it) { shareText = null } }
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

@Composable private fun HistoryPane(conversations: List<Conversation>, search: String, edit: (String) -> Unit,
    select: (String) -> Unit, newChat: () -> Unit, rename: (String, String) -> Unit, delete: (String) -> Unit, clear: () -> Unit) {
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Разговоры", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium); TextButton(onClick = newChat) { AlphaIcon(Glyph.Plus); Text("Новый чат") } }
        OutlinedTextField(value = search, onValueChange = edit, placeholder = { Text("Поиск в истории") }, leadingIcon = { AlphaIcon(Glyph.Search) }, modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp).semantics { contentDescription = "Поиск разговоров" }, singleLine = true, shape = RoundedCornerShape(18.dp))
        val results = conversations.filter { c -> search.isBlank() || c.title.contains(search, true) || c.messages.any { it.text.contains(search, true) } || c.draft.text.contains(search, true) }
        LazyColumn(Modifier.weight(1f)) {
            if (results.isEmpty()) item { Text(if (search.isBlank()) "Здесь появятся твои разговоры." else "Ничего не найдено. Попробуй другой запрос.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(results, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { select(c.id) }.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(c.title, fontSize = 15.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.updatedAt)) + if (c.draft.text.isNotEmpty() || c.draft.attachments.isNotEmpty()) " · черновик" else " · на устройстве", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menuId = c.id }, modifier = Modifier.semantics { contentDescription = "Действия разговора ${c.title}" }) { Text("⋯", fontSize = 25.sp) }
                        DropdownMenu(expanded = menuId == c.id, onDismissRequest = { menuId = null }) {
                            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { renameId = c.id; renameText = c.title; menuId = null })
                            DropdownMenuItem(text = { Text("Удалить разговор", color = MaterialTheme.colorScheme.error) }, onClick = { deleteId = c.id; menuId = null })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        TextButton(onClick = { confirmClear = true }, enabled = conversations.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Очистить историю", color = if (conversations.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (renameId != null) AlertDialog(onDismissRequest = { renameId = null }, title = { Text("Переименовать разговор") }, text = {
        OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("Название разговора") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Название разговора" }, isError = renameText.isBlank(), supportingText = { if (renameText.isBlank()) Text("Введи название") })
    }, confirmButton = { TextButton(onClick = { renameId?.let { rename(it, renameText) }; renameId = null }, enabled = renameText.isNotBlank()) { Text("Сохранить название") } }, dismissButton = { TextButton(onClick = { renameId = null }) { Text("Отмена") } })
    val deleting = conversations.find { it.id == deleteId }
    if (deleting != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Удалить разговор?") }, text = {
        Text("«${deleting.title}»: сообщения и черновик этого разговора будут удалены. Другие разговоры, черновик нового чата, инструменты, автоматизации, API-ключи и исходные файлы сохранятся.")
    }, confirmButton = { TextButton(onClick = { delete(deleting.id); deleteId = null }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Очистить всю историю?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Будут удалены все разговоры, их сообщения и черновики. Активный запрос остановится. Фильтр поиска не ограничивает удаление.")
            Text("Черновик нового чата, инструменты и их данные, автоматизации, API-ключи и исходные файлы сохранятся.")
        }
    }, confirmButton = { TextButton(onClick = { clear(); confirmClear = false }) { Text("Очистить всё") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } })
}

@Composable private fun CapabilitiesPane(controller: com.alpha.assistant.tools.ToolController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(25.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Контекст и функции", style = MaterialTheme.typography.headlineMedium)
        Text("Только выбранные источники", color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.alpha.assistant.capabilities.CapabilitySettings(controller)
    }
}

@Composable private fun SecureWindow(content: @Composable () -> Unit) {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Box(Modifier.testTag("secure")) { content() }
}

@Composable private fun SettingsPane(model: ShellViewModel) {
    var key by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(25.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        HorizontalDivider()
        Text("DeepSeek", style = MaterialTheme.typography.titleLarge)
        Text(model.keyStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API-ключ DeepSeek") }, placeholder = { Text("Введи новый ключ") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), supportingText = { Text("Ключ хранится на устройстве в зашифрованном виде.") })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { model.saveKey(key) { if (it) key = "" } }, enabled = key.isNotBlank() && !model.keyBusy) { Text("Сохранить ключ") }
            TextButton(onClick = { confirmClear = true }, enabled = !model.keyBusy) { Text("Удалить ключ") }
        }
        Notice("Текст и выбранные изображения обрабатывает DeepSeek после подтверждения отправки. Веб-поиск доступен в каждом запросе. Сохранение ключа не отправляет данные и не проверяет баланс.")
        com.alpha.assistant.surfaces.SystemEntrySettings()
        Text("Alphy · 0.1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // NavDisplay places all children of an entry at one origin, so a second child of the Settings entry would draw over this page.
        VoiceControls(model)
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Удалить API-ключ?") }, text = { Text("Разговоры и черновики сохранятся.") }, confirmButton = { TextButton(onClick = { model.clearKey(); confirmClear = false }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } })
}

@Composable private fun Notice(text: String) {
    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable internal fun ImagePreview(attachment: Attachment, modifier: Modifier = Modifier.size(76.dp)) {
    if(attachment.contextText != null) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(attachment.contextText,Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),style = MaterialTheme.typography.bodySmall)
        }
        return
    }
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
    if (preview != null) Image(preview!!, contentDescription = "Предпросмотр ${attachment.name}", modifier = modifier.clip(RoundedCornerShape(12.dp)))
    else Text("Предпросмотр недоступен: ${attachment.name}. При отправке доступ будет проверен снова.", style = MaterialTheme.typography.bodySmall)
}
