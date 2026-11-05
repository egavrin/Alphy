package com.alpha.assistant.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalUriHandler
import com.alpha.assistant.cloud.ImageInput
import com.alpha.assistant.cloud.KEY_ERRORS
import com.alpha.assistant.cloud.validSourceUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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

@Composable fun AlphaShell(model: ShellViewModel) {
    var destination by rememberSaveable { mutableStateOf<ShellDestination?>(null) }
    var contextSheet by rememberSaveable { mutableStateOf(false) }
    var automationRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(model.surfaceSequence) {
        // The model applies each entry once. So a recreation or a new activity applies no entry again, and a rotation keeps an open drawer open.
        if (model.surfacePending) {
            model.consumeSurface()
            destination = when {
                model.surfaceSettings -> ShellDestination.Settings
                model.surfaceToolId != null -> ShellDestination.MiniApps
                else -> null
            }
            contextSheet = false
            drawerState.snapTo(DrawerValue.Closed)
        }
    }
    val snapshot = model.snapshot
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val window = LocalWindowInfo.current
    // Implementation choice: a window below 480 dp high is a phone in landscape, so the keyboard leaves room for one composer row only.
    // derivedStateOf keeps the keyboard animation from recomposing the shell in each frame.
    val compact by remember(ime, density, window) { derivedStateOf { ime.getBottom(density) > 0 && window.containerSize.height < with(density) { 480.dp.roundToPx() } } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun notice(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun navigate(target: ShellDestination?) { contextSheet = false; destination = target; focus.clearFocus() }
    fun automate(request: String) { automationRequest = request; navigate(ShellDestination.Automations) }
    fun closeDrawer() { scope.launch { drawerState.close() } }
    // An overlay handler comes before NavDisplay and the entry handlers, so the drawer takes Back while it targets Open.
    val handler = remember { object : NavigationEventHandler<NavigationEventInfo.None>(NavigationEventInfo.None, false) { override fun onBackCompleted() = closeDrawer() } }
    // The composition reads targetValue, because a read inside SideEffect causes no recomposition.
    val drawerTargetsOpen = drawerState.targetValue == DrawerValue.Open
    SideEffect { handler.isBackEnabled = drawerTargetsOpen }
    // A swipe opens the drawer without «Меню», so the drawer also clears the focus, and the keyboard closes.
    LaunchedEffect(drawerTargetsOpen) { if (drawerTargetsOpen) focus.clearFocus() }
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
            ShellDrawerSheet(drawerState, snapshot.conversations, snapshot.selectedId, destination, snapshot.userName.ifBlank { stringResource(R.string.profile) },
                { navigate(it); closeDrawer() }, { model.select(it); navigate(null); closeDrawer() }) { model.select(null); navigate(null); closeDrawer() }
        }, drawerState = drawerState, gesturesEnabled = drawerState.isOpen || backStack.last() !is ShellDestination) {
            val focusedMiniApp=destination == ShellDestination.MiniApps && (model.tools.selectedId != null || model.tools.selectedDraftId != null)
            val selectedTool=model.tools.tools.find {it.id==model.tools.selectedId}
            val selectedSpec=remember(selectedTool?.spec) {selectedTool?.let {com.alpha.assistant.tools.ToolSpec.available(it.spec)}}
            var immersiveExited by rememberSaveable(selectedTool?.id,selectedTool?.revision,destination == ShellDestination.MiniApps) {mutableStateOf(false)}
            val immersive=focusedMiniApp && model.tools.selectedDraftId==null && selectedTool?.archived==false && selectedSpec?.presentation=="immersive" && !immersiveExited
            Column(Modifier.fillMaxSize().then(if(immersive)Modifier else Modifier.safeDrawingPadding())) {
                if (!focusedMiniApp && !compact) ShellTopBar(stringResource(destination?.title ?: R.string.app_name),
                    { focus.clearFocus(); scope.launch { drawerState.open() } }) { model.select(null); navigate(null) }
                if (model.saveFailed && !immersive && !compact) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                                StatusLine(Glyph.Close, MaterialTheme.colorScheme.onErrorContainer, stringResource(R.string.save_failed), Modifier.weight(1f).then(PoliteLiveRegion))
                            }
                            TextButton(onClick = model::retrySave) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    val split = maxWidth >= 640.dp && destination == ShellDestination.MiniApps && !immersive
                    // A session continues while the shell shows a composer or VoiceControls.
                    val voiceShown = backStack.last() !is ShellDestination || split || backStack.last() == ShellDestination.Settings
                    LaunchedEffect(voiceShown) { if (!voiceShown) model.voice.stop() }
                    @Composable fun ChatEntry(conversation: Conversation?, modifier: Modifier = Modifier) = ChatPane(model, conversation, { navigate(ShellDestination.MiniApps) },
                        automation = ::automate, settings = { navigate(ShellDestination.Settings) }, modifier = modifier)
                    @Composable fun ToolsEntry() = com.alpha.assistant.tools.ToolsPane(model.tools, snapshot.selectedId, snapshot.draft.text, snapshot.draft.attachments.filter { it.contextText == null },immersive=immersive,exitImmersive={immersiveExited=true},
                        settings = { navigate(ShellDestination.Settings) }) { navigate(null) }
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
                        entry<ShellRoute.Desktop>(clazzContentKey = { it.toString() }) { Box(Modifier.testTag("desktop")) { DesktopPane(model, ::navigate, ::automate) } }
                        // The route gives the conversation, so a chat keeps its messages during its exit animation.
                        entry<ShellRoute.Chat>(clazzContentKey = { it.toString() }) { route -> Box(Modifier.testTag("chat")) { ChatEntry(snapshot.conversations.find { it.id == route.id }) } }
                        entry<ShellDestination>(clazzContentKey = { it.toString() }) { route ->
                            when (route) {
                                ShellDestination.Search -> SearchPane(snapshot.conversations, search, { search = it }, { model.select(it); navigate(null) }, model::renameConversation, model::deleteConversation, model::clearHistory)
                                ShellDestination.MiniApps -> if (split) {
                                    Row(Modifier.fillMaxSize()) {
                                        Column(Modifier.weight(1f).fillMaxHeight()) {
                                            ChatEntry(snapshot.selected, Modifier.weight(1f))
                                            Composer(model, { contextSheet = true }, ::automate, Modifier.align(Alignment.CenterHorizontally).widthIn(max = 620.dp))
                                        }
                                        VerticalDivider()
                                        Column(Modifier.weight(1f)) { ToolsEntry() }
                                    }
                                } else ToolsEntry()
                                ShellDestination.Automations -> com.alpha.assistant.automations.AutomationsPane(model.tools,automationRequest) {automationRequest=null}
                                ShellDestination.Access -> com.alpha.assistant.capabilities.AccessPane()
                                ShellDestination.Settings -> SecureWindow { SettingsPane(model) }
                            }
                        }
                    })
                }
                // One call place keeps the voice start state when the Chat route replaces the Desktop route.
                if (backStack.last() !is ShellDestination) Composer(model, { contextSheet = true }, ::automate,
                    Modifier.align(Alignment.CenterHorizontally).widthIn(max = 620.dp).fillMaxWidth().testTag("composer"), compact = compact)
            }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar, Modifier.padding(16.dp)) }
    }
    model.requestError?.let { error ->
        AlertDialog(onDismissRequest = model::clearRequestError, title = { Text(stringResource(R.string.send_failed)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(error) } }, confirmButton = { TextButton(onClick = model::clearRequestError) { Text(stringResource(R.string.understood)) } })
    }
    if (contextSheet && !model.loadFailed && !model.loading) ReviewedContextDialog(model) { contextSheet = false }
}

@Composable internal fun ChatPane(model: ShellViewModel, conversation: Conversation?, tools: () -> Unit, automation: (String) -> Unit,
    settings: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    var shareText by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf(false) }
    val busy = model.activeConversationId != null
    if (linkError) AlertDialog(onDismissRequest = { linkError = false }, title = { Text("Не удалось открыть ссылку") }, text = { Text("Проверьте наличие браузера на устройстве.") }, confirmButton = { TextButton(onClick = { linkError = false }) { Text("Понятно") } })
    Column(modifier.fillMaxHeight().widthIn(max = 620.dp).fillMaxWidth()) {
        if (conversation != null) {
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
                                if (message.role == "assistant" && message.errorCode in KEY_ERRORS) TextButton(onClick = settings) { Text(stringResource(R.string.open_settings)) }
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
    }
    shareText?.let { ShareTextToNotes(it) { shareText = null } }
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

@Composable private fun SecureWindow(content: @Composable () -> Unit) {
    val window = LocalActivity.current?.window
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Box(Modifier.testTag("secure")) { content() }
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
    if (preview != null) Image(preview!!, contentDescription = stringResource(R.string.image_preview, attachment.name), modifier = modifier.clip(RoundedCornerShape(12.dp)))
    else Text(stringResource(R.string.image_preview_unavailable, attachment.name), style = MaterialTheme.typography.bodySmall)
}
