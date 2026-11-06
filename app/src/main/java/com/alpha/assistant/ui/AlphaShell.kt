package com.alpha.assistant.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import com.alpha.assistant.cloud.ImageInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultTransitionSpec
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.alpha.assistant.R
import com.alpha.assistant.courses.CourseLibraryScreen
import com.alpha.assistant.courses.CourseOutlineRefinementAction
import com.alpha.assistant.courses.CourseOutlineRefinementHost
import com.alpha.assistant.courses.CourseSnapshot
import com.alpha.assistant.courses.CourseSourcedSlideGenerationHost
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.memory.MemoryScreen
import com.alpha.assistant.tools.ArtifactCard
import com.alpha.assistant.tools.GeneratedToolPreview
import com.alpha.assistant.tools.LocalMiniAppLiveAllowed
import com.alpha.assistant.tools.MiniAppActionDialogs
import com.alpha.assistant.tools.MiniAppActions
import com.alpha.assistant.tools.NativeToolEditDialog
import com.alpha.assistant.tools.ToolsPane
import com.alpha.assistant.tools.miniAppDeviceUnlocked
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable fun AlphaShell(model: ShellViewModel) {
    var destination by rememberSaveable { mutableStateOf<ShellDestination?>(null) }
    var openSettingsAtUpdates by rememberSaveable { mutableStateOf(false) }
    val updateHandoff = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.updates.handoffReturned()
    }
    var newChatPage by rememberSaveable { mutableStateOf(false) }
    var contextSheet by rememberSaveable { mutableStateOf(false) }
    var automationRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var automationRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    val miniAppActions = remember { MiniAppActions() }
    var search by rememberSaveable { mutableStateOf("") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val courseChatVisible = destination == null && !contextSheet && !model.loading && !model.loadFailed &&
        model.courseSetup == null && model.courseEntry?.inChat != true
    val courseChatHost = remember(model, courseChatVisible) { Any() }
    val shellLifecycle = LocalLifecycleOwner.current.lifecycle
    val shellState by shellLifecycle.currentStateFlow.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.updates.check() }
    DisposableEffect(model, courseChatHost, shellLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.attachCourseChat(courseChatHost)
                Lifecycle.Event.ON_PAUSE -> model.detachCourseChat(courseChatHost)
                else -> Unit
            }
        }
        if (courseChatVisible) shellLifecycle.addObserver(observer)
        onDispose {
            shellLifecycle.removeObserver(observer)
            model.detachCourseChat(courseChatHost)
        }
    }
    LaunchedEffect(model.surfaceSequence) {
        // The model applies each entry once. So a recreation or a new activity applies no entry again, and a rotation keeps an open drawer open.
        if (model.surfacePending) {
            model.dismissCourseSetup()
            model.closeChatCourse()
            if (model.surfaceDestination != null) model.detachCourseChat(courseChatHost)
            if (destination == ShellDestination.Courses && model.surfaceDestination != ShellDestination.Courses) {
                model.cancelCourseRequests()
            }
            destination = model.surfaceDestination
            openSettingsAtUpdates = false
            automationRequest = model.surfaceAutomationRequest
            automationRuleId = model.surfaceAutomationRuleId
            model.consumeSurface()
            contextSheet = false
            drawerState.snapTo(DrawerValue.Closed)
        }
    }
    val snapshot = model.snapshot
    val chatCourseEntry = model.courseEntry?.takeIf { it.inChat }
    val sourcedCourseGeneration = remember(model) { CourseSourcedSlideGenerationHost(model::generateSourcedCourseSlide) }
    LaunchedEffect(model, snapshot.selectedId, destination, chatCourseEntry) {
        if (destination == null && chatCourseEntry == null) model.refreshChatCourses(snapshot.selectedId)
    }
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val window = LocalWindowInfo.current
    // Implementation choice: a window below 480 dp high is a phone in landscape, so the keyboard leaves room for one composer row only.
    // derivedStateOf keeps the keyboard animation from recomposing the shell in each frame.
    val imeVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val compact = imeVisible && window.containerSize.height < with(density) { 480.dp.roundToPx() }
    val scope = rememberCoroutineScope()
    fun navigate(target: ShellDestination?) {
        model.dismissCourseSetup()
        if (target != null) model.dismissAutomationSetup()
        model.closeChatCourse()
        if (target != null) model.detachCourseChat(courseChatHost)
        if (destination == ShellDestination.Courses && target != ShellDestination.Courses) model.cancelCourseRequests()
        contextSheet = false
        automationRuleId = null
        destination = target
        openSettingsAtUpdates = false
        focus.clearFocus()
    }
    fun newChat() {
        model.select(null)
        model.dismissAutomationSetup()
        newChatPage = true
        navigate(null)
    }
    fun openAutomation(id: String) {
        automationRequest = null
        navigate(ShellDestination.Automations)
        automationRuleId = id
    }
    fun closeDrawer() { scope.launch { drawerState.close() } }
    // An overlay handler comes before NavDisplay and the entry handlers, so the drawer takes Back while it targets Open.
    val handler = remember { object : NavigationEventHandler<NavigationEventInfo.None>(NavigationEventInfo.None, false) { override fun onBackCompleted() = closeDrawer() } }
    // The composition reads targetValue, because a read inside SideEffect causes no recomposition.
    val drawerTargetsOpen = drawerState.targetValue == DrawerValue.Open
    val deviceUnlocked = miniAppDeviceUnlocked()
    SideEffect { handler.isBackEnabled = drawerTargetsOpen }
    // A swipe opens the drawer without «Меню», so the drawer also clears the focus, and the keyboard closes.
    LaunchedEffect(drawerTargetsOpen) { if (drawerTargetsOpen) focus.clearFocus() }
    val dispatcherOwner = checkNotNull(LocalNavigationEventDispatcherOwner.current)
    val dispatcher = dispatcherOwner.navigationEventDispatcher
    DisposableEffect(dispatcher) { dispatcher.addHandler(handler, NavigationEventDispatcher.PRIORITY_OVERLAY); onDispose { handler.remove() } }
    val focusedMiniApp = destination == ShellDestination.MiniApps && (model.tools.selectedId != null || model.tools.selectedDraftId != null)
    fun back() {
        when {
            focusedMiniApp -> model.tools.select(null)
            destination == ShellDestination.Memory -> navigate(ShellDestination.Settings)
            destination != null -> navigate(null)
            snapshot.selectedId == null && newChatPage -> newChatPage = false
            else -> model.select(null)
        }
    }
    val selectedTool = model.tools.tools.find { it.id == model.tools.selectedId }
    val selectedSpec = remember(selectedTool?.spec) { selectedTool?.let { com.alpha.assistant.tools.ToolSpec.availablePersisted(it.spec) } }
    var immersiveExited by rememberSaveable(selectedTool?.id, selectedTool?.revision, destination == ShellDestination.MiniApps) { mutableStateOf(false) }
    val immersive = focusedMiniApp && model.tools.selectedDraftId == null && selectedTool?.archived == false &&
        selectedSpec?.presentation == "immersive" && !immersiveExited
    val chatRoute = snapshot.selected?.let { ShellRoute.Chat(it.id) } ?: ShellRoute.NewChat.takeIf { focusedMiniApp || newChatPage }
    val backStack = listOfNotNull(ShellRoute.Desktop, chatRoute, destination)
    val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
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
        } else AdaptiveShellLayout(immersive, focusedMiniApp && !immersive, systemInsets) { paneLayout ->
            val sceneStrategies = shellSceneStrategies(paneLayout)
            ModalNavigationDrawer({
                ShellDrawerSheet(drawerState, snapshot.conversations, snapshot.selectedId, destination, snapshot.userName.ifBlank { stringResource(R.string.profile) },
                    {
                        if (it == ShellDestination.MiniApps) model.tools.select(null)
                        navigate(it); closeDrawer()
                    }, { model.select(it); newChatPage = false; navigate(null); closeDrawer() }) { newChat(); closeDrawer() }
            }, drawerState = drawerState, gesturesEnabled = drawerState.isOpen || destination == null) {
                val drawingInsets = when {
                    immersive -> Modifier
                    paneLayout.tabletopGap != null -> Modifier.windowInsetsPadding(systemInsets)
                    else -> Modifier.windowInsetsPadding(systemInsets).imePadding()
                }
                Column(Modifier.fillMaxSize().then(drawingInsets)) {
                    if (!focusedMiniApp && !compact) {
                        val title = if (destination == ShellDestination.Memory) null else stringResource(destination?.title ?: R.string.app_name)
                        ShellTopBar(title, onMenu = { focus.clearFocus(); scope.launch { drawerState.open() } },
                            onNewChat = if (destination == null && snapshot.selectedId != null) ::newChat else null)
                    }
                    @Composable fun SaveFailure() {
                        Surface(color = MaterialTheme.colorScheme.errorContainer) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                                    StatusLine(Glyph.Close, MaterialTheme.colorScheme.onErrorContainer, stringResource(R.string.save_failed), Modifier.weight(1f).then(PoliteLiveRegion))
                                }
                                TextButton(onClick = model::retrySave, colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    }
                    UpdateNotice(model.updates, visible = !immersive && !compact && destination != ShellDestination.Settings) {
                        navigate(ShellDestination.Settings)
                        openSettingsAtUpdates = true
                    }
                    if (model.saveFailed && !immersive && !compact && !paneLayout.paired) SaveFailure()
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                        // A session continues while the shell shows a composer or VoiceControls.
                        val voiceShown = destination == null || paneLayout.paired || destination == ShellDestination.Settings
                        LaunchedEffect(voiceShown) { if (!voiceShown) model.voice.stop() }
                        // Navigation 3 retains each entry by content key when the scene changes.
                        val currentLayout by rememberUpdatedState(paneLayout)
                        val currentSnapshot by rememberUpdatedState(snapshot)
                        val currentImmersive by rememberUpdatedState(immersive)
                        val currentCompact by rememberUpdatedState(compact)
                        @Composable fun ChatEntry(conversation: Conversation?) {
                            BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag("chat").then(
                                if (currentLayout.tabletopGap != null) Modifier.imePadding() else Modifier,
                            )) {
                                val paired = currentLayout.paired && destination == ShellDestination.MiniApps
                                val compactComposer = currentCompact || (imeVisible && maxHeight < 250.dp)
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (paired && model.saveFailed && !compactComposer) SaveFailure()
                                    ChatPane(model, conversation, { navigate(ShellDestination.MiniApps) },
                                        settings = { navigate(ShellDestination.Settings) }, modifier = Modifier.weight(1f).testTag("chat-content"),
                                        actions = miniAppActions, openAutomation = ::openAutomation,
                                        pairedToolId = model.tools.selectedId.takeIf { destination == ShellDestination.MiniApps },
                                        pairedDraftId = model.tools.selectedDraftId.takeIf { destination == ShellDestination.MiniApps },
                                        showLivePreviews = paired || destination != ShellDestination.MiniApps,
                                        resumeCourse = if (destination == null) model::resumeChatCourse else null)
                                    if (paired) Composer(model, { contextSheet = true }, Modifier.widthIn(max = 620.dp).testTag("composer"),
                                        compact = compactComposer)
                                }
                            }
                        }
                        // The tool and capability gates read RESUMED as "Alphy is in the foreground". NavDisplay holds a scene at STARTED
                        // during its transition, so each entry in the back stack keeps the shell lifecycle. A leaving entry is CREATED, as in Navigation 3.
                        val shellLifecycle = LocalLifecycleOwner.current
                        val currentBackStack by rememberUpdatedState(backStack)
                        val liveEntries by rememberUpdatedState(backStack.takeLast(if (paneLayout.paired) 2 else 1))
                        val liveUncovered by rememberUpdatedState(deviceUnlocked && !drawerTargetsOpen && !drawerState.isOpen &&
                            !contextSheet && model.requestError == null && model.nativeRefinementTarget == null &&
                            miniAppActions.request == null && miniAppActions.restore == null && model.chatAutomationSetup == null)
                        val desktopVisible by rememberUpdatedState(courseChatVisible && snapshot.selectedId == null && !newChatPage &&
                            model.courseEntry == null && liveUncovered && shellState == Lifecycle.State.RESUMED)
                        // Each entry<…> needs clazzContentKey = { it.toString() }, or it stays CREATED.
                        val keepShellLifecycle = remember(shellLifecycle, dispatcherOwner) { NavEntryDecorator<ShellRoute> { entry ->
                            val maxLifecycle = if (currentBackStack.any { it.toString() == entry.contentKey }) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
                            CompositionLocalProvider(
                                LocalLifecycleOwner provides rememberLifecycleOwner(maxLifecycle, shellLifecycle),
                                LocalNavigationEventDispatcherOwner provides dispatcherOwner,
                                LocalMiniAppLiveAllowed provides (liveUncovered && liveEntries.any { it.toString() == entry.contentKey }),
                            ) { entry.Content() }
                        } }
                        // NavDisplay gives each entry fixed constraints. The Box loosens them, so a pane keeps its maximum width and stays centered.
                        val centerEntry = remember { NavEntryDecorator<ShellRoute> { entry -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { entry.Content() } } }
                        NavDisplay(backStack, Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter, onBack = ::back,
                            sceneStrategies = sceneStrategies,
                            transitionSpec = {
                                if (initialState.entries.last().contentKey == targetState.entries.last().contentKey) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else defaultTransitionSpec<ShellRoute>().invoke(this)
                            },
                            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), keepShellLifecycle, centerEntry), entryProvider = entryProvider {
                            entry<ShellRoute.Desktop>(clazzContentKey = { it.toString() }) {
                                Box(Modifier.testTag("desktop")) {
                                    DesktopPane(model, ::navigate, isDesktopVisible = desktopVisible)
                                }
                            }
                            // The route gives the conversation, so a chat keeps its messages during its exit animation.
                            entry<ShellRoute.Chat>(clazzContentKey = { it.toString() }, metadata = ListDetailSceneStrategy.listPane()) { route ->
                                ChatEntry(currentSnapshot.conversations.find { it.id == route.id })
                            }
                            entry<ShellRoute.NewChat>(clazzContentKey = { it.toString() }, metadata = SupportingPaneSceneStrategy.mainPane()) {
                                if (destination != null) ChatEntry(null)
                                else Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    val greeting = stringResource(greetingRes(java.time.LocalTime.now().hour))
                                    val name = currentSnapshot.userName.trim()
                                    Text(if (name.isEmpty()) greeting else stringResource(R.string.greeting_named, greeting, name),
                                        style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                            entry<ShellDestination>(clazzContentKey = { it.toString() }, metadata = { route ->
                                if (route == ShellDestination.MiniApps) {
                                    ListDetailSceneStrategy.detailPane() + SupportingPaneSceneStrategy.supportingPane()
                                } else emptyMap()
                            }) { route ->
                                when (route) {
                                    ShellDestination.MiniApps -> Box(Modifier.fillMaxSize().clipToBounds().testTag("mini-app")) {
                                        if (model.tools.storageUnavailable) ToolLibraryUnavailablePane()
                                        else ToolsPane(model.tools, currentSnapshot.selectedId, miniAppActions, refine = model::refineTool,
                                            immersive = currentImmersive, exitImmersive = { immersiveExited = true },
                                            settings = { navigate(ShellDestination.Settings) }) { navigate(null) }
                                    }
                                    ShellDestination.Search -> SearchPane(currentSnapshot.conversations, search, { search = it }, { model.select(it); navigate(null) }, model::renameConversation, model::deleteConversation, model::clearHistory)
                                    ShellDestination.Automations -> if (model.tools.storageUnavailable) ToolLibraryUnavailablePane()
                                        else com.alpha.assistant.automations.AutomationsPane(model.tools, automationRequest,
                                            openRuleId = automationRuleId, showAll = { automationRuleId = null }) { automationRequest = null }
                                    ShellDestination.Courses -> CourseLibraryScreen(
                                        model.courseStore, model.courseController, onExit = { navigate(null) },
                                        tutor = model.courseTutor, generation = model.courseGeneration,
                                        refinement = model.courseRefinement, sourcedGeneration = sourcedCourseGeneration,
                                        entryRequest = model.courseEntry?.takeUnless { it.inChat }?.request,
                                        onEntryHandled = model::courseEntryHandled,
                                    )
                                    ShellDestination.Memory -> MemoryScreen(model.memoryPage, onExit = { navigate(ShellDestination.Settings) })
                                    ShellDestination.Settings -> SecureWindow {
                                        SettingsPane(model, openSettingsAtUpdates, { model.updates.handoff { updateHandoff.launch(it) } }) {
                                            navigate(ShellDestination.Memory)
                                        }
                                    }
                                }
                            }
                        })
                    }
                    // One call place keeps the voice start state when the Chat route replaces the Desktop route.
                    if (destination == null) Composer(model, { contextSheet = true },
                        Modifier.align(Alignment.CenterHorizontally).widthIn(max = 620.dp).fillMaxWidth().testTag("composer"), compact = compact)
                }
            }
        }
    }
    if (destination == null && chatCourseEntry != null) key(chatCourseEntry.request.requestId) {
        CourseLibraryScreen(model.courseStore, model.courseController,
            onExit = { model.closeChatCourse(chatCourseEntry.request.requestId) },
            modifier = Modifier.size(0.dp),
            tutor = model.courseTutor, generation = model.courseGeneration,
            refinement = model.courseRefinement, sourcedGeneration = sourcedCourseGeneration,
            entryRequest = chatCourseEntry.request)
    }
    model.courseSetup?.let { CourseSetupDialog(model, it) }
    MiniAppActionDialogs(model.tools, miniAppActions)
    model.nativeRefinementTarget?.let { target ->
        key(model.tools, target) {
            NativeToolEditDialog(model.tools, target, model::dismissNativeRefinement)
        }
    }
    model.requestError?.let { error ->
        AlertDialog(onDismissRequest = model::clearRequestError, title = { Text(stringResource(R.string.send_failed)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(error) } }, confirmButton = { TextButton(onClick = model::clearRequestError) { Text(stringResource(R.string.understood)) } })
    }
    if (contextSheet && !model.loadFailed && !model.loading) ReviewedContextDialog(model) { contextSheet = false }
    if (destination == null) ChatSetupDialogs(model)
}

@Composable internal fun ChatSetupDialogs(model: ShellViewModel) {
    val automationSetup = model.chatAutomationSetup?.takeIf { it.conversationId == model.snapshot.selectedId }
    if (automationSetup != null) key(automationSetup) {
        ChatSetupDialog(model::dismissAutomationSetup) {
            com.alpha.assistant.automations.AutomationsPane(model.tools, chatSetup = automationSetup.request,
                finishSetup = model::dismissAutomationSetup)
        }
    }
    if (model.memoryPage.reviewingChatFact) {
        ChatSetupDialog(model.memoryPage::cancelEditor) {
            MemoryScreen(model.memoryPage, onExit = model.memoryPage::cancelEditor)
        }
    }
}

@Composable private fun ChatSetupDialog(dismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = dismiss,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false,
            securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(12.dp),
            shape = MaterialTheme.shapes.extraLarge, content = content)
    }
}

@Composable private fun CourseSetupDialog(model: ShellViewModel, setup: ShellCourseSetup) {
    val draft = setup.outline
    val input = setup.input
    val working = setup.requesting || setup.saving
    AlertDialog(
        onDismissRequest = { model.cancelCourseSetup(setup.id) },
        title = { Text(stringResource(if (draft == null) R.string.shell_course_setup else R.string.shell_course_review)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft == null) {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(input.topic, { value -> model.editCourseSetup(setup.id) { it.copy(topic = value.take(256)) } },
                            label = { Text(stringResource(R.string.shell_course_topic)) }, enabled = !working,
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(input.level, { value -> model.editCourseSetup(setup.id) { it.copy(level = value.take(128)) } },
                            label = { Text(stringResource(R.string.shell_course_level)) }, enabled = !working,
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(input.goal, { value -> model.editCourseSetup(setup.id) { it.copy(goal = value.take(1024)) } },
                            label = { Text(stringResource(R.string.shell_course_goal)) }, enabled = !working,
                            maxLines = 3, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Text(draft.setup.topic, style = MaterialTheme.typography.titleMedium)
                    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(draft.outline.lessons, key = { it.id }) { lesson ->
                            Text(lesson.title, style = MaterialTheme.typography.titleSmall)
                            lesson.slides.forEach { slide -> Text(slide.goal, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                setup.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (draft == null) Button(onClick = { model.requestCoursePlan(setup.id) }, enabled = !working) {
                Text(stringResource(R.string.shell_course_plan))
            } else Button(onClick = { model.startPreparedCourse(setup.id) }, enabled = !working) {
                Text(stringResource(R.string.shell_course_start))
            }
        },
        dismissButton = {
            Row {
                if (draft != null) TextButton(onClick = { model.changeCoursePlan(setup.id) }, enabled = !working) {
                    Text(stringResource(R.string.shell_course_change_setup))
                }
                TextButton(onClick = { model.cancelCourseSetup(setup.id) }, enabled = !setup.saving) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable private fun ToolLibraryUnavailablePane() {
    Text(stringResource(R.string.tool_library_unavailable), Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable internal fun ChatPane(model: ShellViewModel, conversation: Conversation?, tools: () -> Unit,
    settings: () -> Unit, modifier: Modifier = Modifier, actions: MiniAppActions? = null,
    openAutomation: (String) -> Unit, pairedToolId: String? = null, pairedDraftId: String? = null,
    showLivePreviews: Boolean = true, resumeCourse: ((String) -> Unit)? = null) {
    val cardActions = actions ?: remember { MiniAppActions() }
    val scope = rememberCoroutineScope()
    var shareText by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf(false) }
    if (linkError) AlertDialog(
        onDismissRequest = { linkError = false },
        title = { Text(stringResource(R.string.chat_link_error)) },
        text = { Text(stringResource(R.string.chat_link_error_detail)) },
        confirmButton = { TextButton(onClick = { linkError = false }) { Text(stringResource(R.string.understood)) } },
    )
    Column(modifier.fillMaxHeight().widthIn(max = 620.dp).fillMaxWidth()) {
        if (conversation != null) {
            val chat = conversation
            model.tools.error?.let { error ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (model.tools.errorCode in com.alpha.assistant.cloud.KEY_ERRORS) {
                        TextButton(onClick = settings) { Text(stringResource(R.string.open_settings)) }
                    }
                    TextButton(onClick = model.tools::clearError) { Text(stringResource(R.string.tool_dialog_close)) }
                }
            }
            val legacyCards = model.tools.tools.filter { tool -> tool.conversationId == chat.id && tool.id !in chat.cardIds }
            val courses = if (resumeCourse == null) emptyList() else model.chatCourses.filter { it.setup.originatingConversationId == chat.id }
            val openTool: (String) -> Unit = { id -> model.tools.select(id); tools() }
            val listState = key(chat.id) { rememberLazyListState() }
            val latestUserMessage = chat.messages.lastOrNull { it.role == "user" }?.id
            var observedUserMessage by rememberSaveable(chat.id) { mutableStateOf(latestUserMessage) }
            val openCourse = model.courseEntry?.takeIf { it.inChat && resumeCourse != null }?.request?.courseId
            var returnToCourse by rememberSaveable(chat.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(chat.id, latestUserMessage) {
                if (observedUserMessage != latestUserMessage) {
                    observedUserMessage = latestUserMessage
                    listState.requestScrollToItem(0)
                }
            }
            LaunchedEffect(chat.id, openCourse, courses, chat.messages.size) {
                if (openCourse != null) {
                    returnToCourse = openCourse
                } else if (returnToCourse != null) {
                    val index = courses.asReversed().indexOfFirst { it.id == returnToCourse }
                    if (index >= 0) {
                        returnToCourse = null
                        listState.requestScrollToItem(chat.messages.size + index)
                    }
                }
            }
            val chatMessages = stringResource(R.string.chat_messages)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = chatMessages },
                    state = listState, reverseLayout = true, contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    items(chat.messages.asReversed(), key = { it.id }) { message ->
                        Column(Modifier.fillMaxWidth().padding(start = if (message.role == "user") 25.dp else 0.dp)) {
                            val assistant = message.role == "assistant"
                            val canRetry = assistant && message.status in setOf("error", "cancelled", "interrupted")
                            val canCancelQueued = assistant && message.status == "queued"
                            ChatMessageCard(
                                message,
                                fragment = if (message.status == "streaming") model.activeAnswer(message.id) else null,
                                weatherLocationBusy = model.weatherLocationBusy && model.activeConversationId == chat.id,
                                retry = if (canRetry) ({ model.retry(message.id) }) else null,
                                retryEnabled = model.snapshot.selectedId == chat.id,
                                cancelQueued = if (canCancelQueued) ({ model.cancelRequest(message.id) }) else null,
                                chooseRefinement = { toolId -> model.chooseRefinementTarget(message.id, toolId) },
                                share = { shareText = message.text },
                                settings = settings,
                                linkError = { linkError = true },
                            )
                            if (message.toolId != null && message.toolRevision != null) {
                                Spacer(Modifier.height(8.dp))
                                ArtifactCard(model.tools, message.toolId, cardActions, openTool, openAutomation,
                                    compact = !showLivePreviews || message.toolId == pairedToolId)
                            } else if (message.status == "generating") {
                                message.toolOperationId?.let { draftId ->
                                    if (showLivePreviews && draftId != pairedDraftId) {
                                        GeneratedToolPreview(model.tools.drafts.find { it.id == draftId }, model.tools.previewFor(draftId))
                                    }
                                }
                            }
                            if (message.status == "not_sent") Text(stringResource(R.string.chat_message_not_sent),
                                Modifier.padding(start = 5.dp, top = 6.dp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (resumeCourse != null) items(courses.asReversed(), key = { "course-${it.id}" }) { course ->
                        ChatCourseCard(
                            course, resume = { resumeCourse(course.id) },
                            openOutline = { model.openChatCourseOutline(course.id) },
                            refinement = model.courseRefinement,
                            onRefined = { scope.launch { model.refreshChatCourses(chat.id) } },
                        )
                    }
                    items(legacyCards.asReversed(), key = { "tool-${it.id}" }) { artifact ->
                        ArtifactCard(model.tools, artifact.id, cardActions, openTool, openAutomation,
                            compact = !showLivePreviews || artifact.id == pairedToolId)
                    }
                }
                if (listState.canScrollBackward) {
                    FilledTonalButton(onClick = { scope.launch { listState.scrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.chat_jump_latest))
                    }
                }
            }
        }
    }
    if (actions == null) MiniAppActionDialogs(model.tools, cardActions)
    shareText?.let { ShareTextToNotes(it) { shareText = null } }
}

@Composable private fun ChatCourseCard(
    course: CourseSnapshot,
    resume: () -> Unit,
    openOutline: () -> Unit,
    refinement: CourseOutlineRefinementHost,
    onRefined: (CourseSnapshot) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(course.setup.topic, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.shell_course_progress, course.progress.completedSlides, course.progress.totalSlides))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = resume) { Text(stringResource(R.string.shell_course_continue)) }
                TextButton(onClick = openOutline) { Text(stringResource(R.string.shell_course_open_outline)) }
            }
            CourseOutlineRefinementAction(course, refinement, onApplied = onRefined)
        }
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
