package com.alpha.assistant.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alpha.assistant.R
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.Draft
import com.alpha.assistant.tools.ArtifactSnapshot
import com.alpha.assistant.tools.NativeLiveInput
import com.alpha.assistant.tools.NativeToolSurface
import com.alpha.assistant.tools.ToolContent
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.ui.AdaptiveShellLayout
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.CourseComposer
import com.alpha.assistant.ui.CourseComposerState
import com.alpha.assistant.ui.Glyph
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

internal enum class CourseEntryDestination { OUTLINE, REACHED_SLIDE }

/** Use a new request ID for each host selection. A host overlay can exit to its underlying route. */
internal data class CourseEntryRequest(
    val requestId: String,
    val courseId: String,
    val destination: CourseEntryDestination,
    val exitToHostOnBack: Boolean = false,
    val refineOutline: Boolean = false,
)

/** The caller owns the injected store and controller. */
@Composable
internal fun CourseLibraryScreen(
    store: CourseStore,
    controller: CourseController,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    tutor: CourseTutorHost? = null,
    generation: CourseSlideGenerationHost? = null,
    onStartCourse: (() -> Unit)? = null,
    entryRequest: CourseEntryRequest? = null,
    onEntryHandled: (String) -> Unit = {},
    refinement: CourseOutlineRefinementHost? = null,
    sourcedGeneration: CourseSourcedSlideGenerationHost? = null,
    composerModel: ShellViewModel? = null,
    onOutlineApplied: (CourseSnapshot) -> Unit = {},
    nextGeneration: CourseNextGenerationHost? = null,
) {
    val presentation = remember(store, controller) { CoursePresentation(store, controller) }
    val scope = rememberCoroutineScope()
    var courseId by rememberSaveable { mutableStateOf<String?>(null) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var returnToHost by rememberSaveable { mutableStateOf(false) }
    var prepareOnEntry by rememberSaveable { mutableStateOf(true) }
    var refining by rememberSaveable { mutableStateOf(false) }
    var heldAnswerId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftSlideId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftText by rememberSaveable { mutableStateOf("") }
    var draftOrder by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<CoursePage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableIntStateOf(0) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var generationAttempts by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    var handledEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val handleEntry by rememberUpdatedState(onEntryHandled)
    val slide = page as? CoursePage.Slide
    val nextRequest = slide?.nextRequest
    var nextRetry by remember(presentation, nextRequest, nextGeneration) { mutableIntStateOf(0) }
    var nextPreparation by remember(presentation, nextRequest, nextGeneration, nextRetry) {
        mutableStateOf<CourseNextPreparation?>(null)
    }
    LaunchedEffect(presentation, nextRequest, nextGeneration, nextRetry) {
        if (nextRequest != null) {
            val result = withContext(Dispatchers.IO) { presentation.prepareNext(nextRequest, nextGeneration) }
            ensureActive()
            nextPreparation = result
        }
    }
    val currentSlide by rememberUpdatedState(slide)
    val tutorSession = remember(slide?.course?.id, slide?.step?.slide?.id, tutor, composerModel) {
        CourseTutorSession(scope, composerModel) { draft, history ->
            val reached = checkNotNull(currentSlide)
            val host = checkNotNull(tutor)
            val request = withContext(Dispatchers.IO) { store.tutorRequest(reached, draft, history) }
            currentCoroutineContext().ensureActive()
            host.answer(request)
        }
    }
    val lessonScroll = key(slide?.course?.id, slide?.step?.slide?.id) { rememberScrollState() }
    DisposableEffect(tutorSession) {
        onDispose { tutorSession.close() }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    fun startGeneration(pending: CoursePage.Unavailable, retry: Boolean) {
        val host = generation ?: return
        val request = pending.generationRequest ?: return
        if (generationJob != null) return
        generationAttempts = HashMap(generationAttempts).apply { put(request.courseId, pending.generationReach) }
        notice = 0
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) { presentation.generate(pending, host, retry) }
                ensureActive()
                page = result.page
                if (result.page is CoursePage.Slide) {
                    generationAttempts = HashMap(generationAttempts).apply { remove(request.courseId) }
                }
                if (result.reloaded) notice = R.string.course_reloaded
            } finally {
                if (generationJob === coroutineContext[Job]) generationJob = null
            }
        }
        generationJob = task
        task.start()
    }

    fun show(loaded: CoursePage, generateOnReach: Boolean) {
        val pending = loaded as? CoursePage.Unavailable
        if (pending?.issue == CourseSlideIssue.MISSING &&
            generationAttempts[pending.course.id] == pending.generationReach
        ) {
            page = pending.copy(issue = CourseSlideIssue.GENERATION_INTERRUPTED)
            return
        }
        page = loaded
        if (generateOnReach && pending?.issue == CourseSlideIssue.MISSING) startGeneration(pending, retry = false)
    }

    fun navigate(id: String?, play: Boolean = false, exitToHost: Boolean = false, autoPrepare: Boolean = true,
        refine: Boolean = false,
    ) {
        stopGeneration()
        courseId = id
        playing = play
        returnToHost = exitToHost
        prepareOnEntry = autoPrepare
        refining = refine
        heldAnswerId = null
        draftSlideId = null
        draftText = ""
        draftOrder = arrayListOf()
        notice = 0
        page = null
        refresh++
    }

    fun back() {
        if (busy) return
        when {
            refining -> refining = false
            returnToHost -> {
                tutorSession.close()
                navigate(null)
                onExit()
            }
            playing -> navigate(courseId)
            courseId != null -> navigate(null)
            else -> onExit()
        }
    }

    fun update(answerId: String? = null, work: suspend () -> CoursePlayerUpdate) {
        if (busy) return
        busy = true
        if (answerId != null) heldAnswerId = answerId
        notice = 0
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ensureActive()
                    work()
                }
                when (result) {
                    is CoursePlayerUpdate.Open -> {
                        if (result.reloaded && result.page is CoursePage.Unavailable && page is CoursePage.Slide) {
                            nextPreparation = CourseNextPreparation.Stale
                        } else {
                            show(result.page, generateOnReach = false)
                            if (result.reloaded || (result.page as? CoursePage.Slide)?.feedback?.correct != true) {
                                heldAnswerId = null
                            }
                            if (result.reloaded) notice = R.string.course_reloaded
                        }
                    }
                    CoursePlayerUpdate.SaveFailed -> {
                        if (answerId != null) heldAnswerId = null
                        notice = R.string.course_save_failed
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(presentation, entryRequest) {
        val request = entryRequest ?: return@LaunchedEffect
        if (handledEntryId == request.requestId) return@LaunchedEffect
        val available = withContext(Dispatchers.IO) { presentation.outline(request.courseId) is CoursePage.Outline }
        snapshotFlow { busy }.first { !it }
        val dismissMissingOverlay = !available && request.exitToHostOnBack && courseId == null
        if (available) {
            navigate(
                request.courseId,
                play = request.destination == CourseEntryDestination.REACHED_SLIDE,
                exitToHost = request.exitToHostOnBack,
                refine = request.refineOutline,
            )
        } else if (!dismissMissingOverlay) {
            notice = R.string.course_entry_unavailable
        }
        handledEntryId = request.requestId
        handleEntry(request.requestId)
        if (dismissMissingOverlay) onExit()
    }
    LaunchedEffect(presentation, courseId, playing, refresh) {
        val id = courseId
        val play = playing
        val held = heldAnswerId
        val prepare = prepareOnEntry
        val loaded = withContext(Dispatchers.IO) {
            when {
                id == null -> presentation.library()
                play -> presentation.player(id, held)
                else -> presentation.outline(id)
            }
        }
        show(loaded, generateOnReach = play && prepare)
    }
    val fullWindow = playing || returnToHost
    val hostedEntryPending = entryRequest?.let { it.exitToHostOnBack && it.requestId != handledEntryId } == true
    BackHandler(enabled = !fullWindow && (courseId != null || hostedEntryPending)) { back() }

    val backLabel = when {
        returnToHost -> R.string.course_exit
        playing -> R.string.course_back_to_outline
        courseId != null -> R.string.course_back_to_library
        else -> R.string.course_exit
    }
    @Composable
    fun PageContent() {
        when (val current = page) {
            null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            CoursePage.LoadFailed -> CourseRecovery(R.string.course_load_failed) { refresh++ }
            is CoursePage.Library -> SavedCourses(current.courses, onStartCourse) { navigate(it) }
            is CoursePage.Outline -> SavedOutline(
                current.course,
                refinement,
                composerModel,
                refining,
                onRefining = { refining = it },
                onApplying = { busy = it },
                onApplied = {
                    page = CoursePage.Outline(it)
                    onOutlineApplied(it)
                },
                onResume = { navigate(current.course.id, play = true, exitToHost = returnToHost) },
                sourceAction = {
                    if (sourcedGeneration != null) {
                        CourseOutlineSourceAction(
                            current.course, presentation, sourcedGeneration, generation,
                            onPrepared = {
                                navigate(current.course.id, play = true, exitToHost = returnToHost, autoPrepare = false)
                            },
                            onReload = { refresh++ },
                        )
                    }
                },
            )
            is CoursePage.Complete -> {
                CourseHeading(current.course.setup.topic)
                Text(stringResource(R.string.course_complete))
                if (!returnToHost) {
                    TextButton(onClick = { navigate(null) }) { Text(stringResource(R.string.course_back_to_library)) }
                }
            }
            is CoursePage.Unavailable -> {
                CourseGenerationPage(
                    current,
                    waiting = generationJob != null,
                    hostAvailable = generation != null,
                    onCancel = {
                        stopGeneration()
                        page = current.copy(issue = CourseSlideIssue.GENERATION_INTERRUPTED)
                    },
                    onRetry = { startGeneration(current, retry = true) },
                    onReload = { refresh++ },
                )
            }
            is CoursePage.Slide -> key(current.course.id, current.step.slide.id) {
                val slideId = current.step.slide.id
                val savedAnswer = current.feedback?.answer
                fun currentText() = if (draftSlideId == slideId) draftText else when (savedAnswer) {
                    is CourseAnswer.Choice -> savedAnswer.optionId
                    is CourseAnswer.FillIn -> savedAnswer.text
                    else -> ""
                }
                fun currentOrder() = if (draftSlideId == slideId) draftOrder else (savedAnswer as? CourseAnswer.Order)?.optionIds.orEmpty()
                CourseSlidePlayer(
                    slide = current,
                    sceneHost = remember(controller, current.course.id, current.course.revision, current.step.slide.id, current.scene?.json) {
                        current.scene?.let { controller.sceneHost(current) }
                    },
                    busy = busy,
                    text = currentText(),
                    order = currentOrder(),
                    onText = {
                        draftSlideId = slideId
                        draftText = it
                        draftOrder = arrayListOf()
                    },
                    onOrder = {
                        draftSlideId = slideId
                        draftText = ""
                        draftOrder = ArrayList(it)
                    },
                    onSubmit = {
                        // A selection can change before the button recomposes.
                        if (page === current) current.prompt?.answerFor(currentText(), currentOrder())?.let { answer ->
                            val requestId = UUID.randomUUID().toString()
                            update(requestId) { presentation.submit(current, requestId, answer) }
                        }
                    },
                    nextPreparation = nextPreparation,
                    onRetryNext = {
                        when (nextPreparation) {
                            CourseNextPreparation.Stale -> refresh++
                            CourseNextPreparation.Failed, is CourseNextPreparation.SceneFailed -> {
                                nextPreparation = null
                                nextRetry++
                            }
                            else -> Unit
                        }
                    },
                    onNext = {
                        val prepared = nextPreparation as? CourseNextPreparation.Ready
                        update { presentation.next(current, prepared) }
                    },
                    scroll = lessonScroll,
                )
            }
        }
    }
    if (fullWindow) {
        Dialog(onDismissRequest = { if (tutorSession.open) tutorSession.close() else back() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                CoursePlayerLayout(
                    session = tutorSession,
                    slide = slide,
                    composerModel = composerModel.takeIf { tutor != null },
                    backLabel = backLabel,
                    busy = busy,
                    onBack = ::back,
                    slidePane = {
                        if (slide == null) {
                            CoursePageFrame(Modifier.fillMaxSize(), backLabel, busy, notice, ::back) { PageContent() }
                        } else {
                            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                if (notice != 0) Text(stringResource(notice),
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                PageContent()
                            }
                        }
                    },
                )
            }
        }
    } else {
        CoursePageFrame(modifier.fillMaxWidth(), backLabel, busy, notice, ::back) { PageContent() }
    }
}

@Composable
private fun CoursePageFrame(
    modifier: Modifier,
    backLabel: Int,
    busy: Boolean,
    notice: Int,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(backLabel)) }
        if (notice != 0) Text(stringResource(notice), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        content()
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CoursePlayerLayout(
    session: CourseTutorSession,
    slide: CoursePage.Slide?,
    composerModel: ShellViewModel?,
    backLabel: Int,
    busy: Boolean,
    onBack: () -> Unit,
    slidePane: @Composable () -> Unit,
) {
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    AdaptiveShellLayout(immersive = false, pairApp = slide != null, systemInsets = insets, minimumPairWidth = 640.dp) { layout ->
        val safe = Modifier.fillMaxSize().windowInsetsPadding(insets)
        if (slide == null) {
            Box(safe.imePadding()) { slidePane() }
            return@AdaptiveShellLayout
        }

        @Composable fun Toolbar() {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val back = stringResource(backLabel)
                IconButton(onClick = onBack, enabled = !busy, modifier = Modifier.semantics { contentDescription = back }) {
                    AlphaIcon(Glyph.Back)
                }
                Text(slide.course.setup.topic, Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                FilledTonalButton(
                    onClick = { if (session.open) session.close() else session.show() },
                    enabled = !busy || session.open,
                ) {
                    AlphaIcon(Glyph.Chat)
                    Text(stringResource(R.string.course_tutor_open), Modifier.padding(start = 6.dp))
                }
            }
            HorizontalDivider()
        }

        @Composable fun Chat(modifier: Modifier) {
            CourseTutorPanel(session, composerModel, slide.step.lessonTitle, modifier)
        }

        val tabletopGap = layout.tabletopGap
        if (tabletopGap != null) {
            val density = LocalDensity.current
            Column(safe) {
                Column(Modifier.height(with(density) { tabletopGap.first.toDp() })) {
                    Toolbar()
                    slidePane()
                }
                if (session.open) {
                    Spacer(Modifier.height(with(density) { (tabletopGap.last - tabletopGap.first + 1).toDp() }))
                    Chat(Modifier.weight(1f).imePadding())
                }
            }
        } else {
            Column(safe.imePadding()) {
                Toolbar()
                if (layout.wide) {
                    val directive = PaneScaffoldDirective(
                        maxHorizontalPartitions = 2,
                        horizontalPartitionSpacerSize = 16.dp,
                        maxVerticalPartitions = 1,
                        verticalPartitionSpacerSize = 0.dp,
                        defaultPanePreferredWidth = 280.dp,
                        defaultPanePreferredHeight = 320.dp,
                        excludedBounds = listOfNotNull(layout.verticalHinge),
                        shouldAutoFocusCurrentDestination = false,
                    )
                    val value = ThreePaneScaffoldValue(PaneAdaptedValue.Expanded, PaneAdaptedValue.Expanded, PaneAdaptedValue.Hidden)
                    if (session.open) {
                        SupportingPaneScaffold(directive = directive, value = value, mainPane = { slidePane() },
                            supportingPane = { Chat(Modifier.fillMaxSize()) }, modifier = Modifier.fillMaxSize())
                    } else {
                        ListDetailPaneScaffold(directive = directive, value = value,
                            listPane = { CourseLessonIndex(slide) }, detailPane = { slidePane() },
                            modifier = Modifier.fillMaxSize())
                    }
                } else {
                    slidePane()
                    if (session.open) {
                        ModalBottomSheet(
                            onDismissRequest = session::close,
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        ) {
                            Chat(Modifier.fillMaxHeight(0.85f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseLessonIndex(slide: CoursePage.Slide) {
    val colors = MaterialTheme.colorScheme
    val course = slide.course
    val currentIndex = course.outline.lessons.indexOfFirst { it.id == slide.step.lessonId }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { CourseHeading(stringResource(R.string.course_lessons)) }
        itemsIndexed(course.outline.lessons, key = { _, lesson -> lesson.id }) { index, lesson ->
            val current = index == currentIndex
            Surface(shape = MaterialTheme.shapes.medium,
                color = if (current) colors.primaryContainer else colors.surfaceContainerLow,
                contentColor = if (current) colors.onPrimaryContainer else colors.onSurface) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("${index + 1}. ${lesson.title}", style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(when {
                        current -> R.string.course_lesson_current
                        index < currentIndex -> R.string.course_lesson_completed
                        else -> R.string.course_lesson_pending
                    }), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CourseTutorPanel(session: CourseTutorSession, model: ShellViewModel?, lesson: String, modifier: Modifier) {
    val scroll = rememberLazyListState()
    val history = session.history
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(history) {
        if (history.isNotEmpty()) scroll.scrollToItem(history.lastIndex)
    }
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AlphaIcon(Glyph.Chat)
            Text(stringResource(R.string.course_tutor_title), Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.titleLarge)
            val close = stringResource(R.string.course_tutor_close)
            IconButton(onClick = session::close, modifier = Modifier.semantics { contentDescription = close }) {
                AlphaIcon(Glyph.Close)
            }
        }
        Surface(shape = MaterialTheme.shapes.large, color = colors.secondaryContainer) {
            Text(stringResource(R.string.course_tutor_context, lesson), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge)
        }
        if (model == null) {
            Text(stringResource(R.string.course_tutor_unavailable))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history) { exchange ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(Modifier.align(Alignment.End), shape = MaterialTheme.shapes.large, color = colors.secondaryContainer) {
                            Text(exchange.question.ifBlank { stringResource(R.string.course_tutor_attachment) },
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        Surface(Modifier.align(Alignment.Start), shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
                            Text(exchange.answer, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                }
            }
            val statusLabel = when (session.status) {
                CourseTutorStatus.WAITING -> R.string.course_tutor_waiting
                CourseTutorStatus.FAILED -> R.string.course_tutor_failed
                CourseTutorStatus.CANCELED -> R.string.course_tutor_canceled
                CourseTutorStatus.IDLE -> null
            }
            statusLabel?.let { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            CourseComposer(model, session.composer, R.string.course_tutor_question)
        }
    }
}

@Composable
private fun SavedCourses(courses: List<CourseSnapshot>, onStartCourse: (() -> Unit)?, onOpen: (String) -> Unit) {
    CourseHeading(stringResource(R.string.course_library))
    if (onStartCourse != null) Button(onClick = onStartCourse) { Text(stringResource(R.string.course_new)) }
    if (courses.isEmpty()) Text(stringResource(R.string.course_empty))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(courses, key = { it.id }) { course ->
            TextButton(onClick = { onOpen(course.id) }) { Text(course.setup.topic) }
            CourseProgressText(course.progress)
        }
    }
}

@Composable
private fun SavedOutline(
    course: CourseSnapshot,
    refinement: CourseOutlineRefinementHost?,
    model: ShellViewModel?,
    refining: Boolean,
    onRefining: (Boolean) -> Unit,
    onApplying: (Boolean) -> Unit,
    onApplied: (CourseSnapshot) -> Unit,
    onResume: () -> Unit,
    sourceAction: @Composable () -> Unit,
) {
    CourseHeading(course.setup.topic)
    CourseProgressText(course.progress)
    if (refining && refinement != null && model != null) {
        key(course.id, course.revision) {
            CourseOutlineRefinementPanel(course, refinement, model, onApplying, onApplied) { onRefining(false) }
        }
    } else {
        Button(onClick = onResume) {
            Text(stringResource(when {
                course.progress.isComplete -> R.string.course_view_completion
                course.progress.completedSlides > 0 -> R.string.course_resume
                else -> R.string.course_open
            }))
        }
        sourceAction()
        TextButton(onClick = { onRefining(true) }, enabled = refinement != null && model != null) {
            Text(stringResource(R.string.course_refinement_open))
        }
        if (refinement == null || model == null) Text(stringResource(R.string.course_refinement_unavailable))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(course.setup.level)
                Text(course.setup.goal)
                CourseHeading(stringResource(R.string.course_outline))
            }
            courseOutlineItems(course.outline)
        }
    }
}

private fun LazyListScope.courseOutlineItems(outline: CourseOutline) {
    outline.lessons.forEach { lesson ->
        item(key = "lesson:${lesson.id}") { CourseHeading(lesson.title) }
        items(lesson.slides, key = { "slide:${it.id}" }) { Text(it.goal) }
    }
}

@Composable
private fun CourseOutlineSourceAction(
    course: CourseSnapshot,
    presentation: CoursePresentation,
    sourcedHost: CourseSourcedSlideGenerationHost,
    plainHost: CourseSlideGenerationHost?,
    onPrepared: () -> Unit,
    onReload: () -> Unit,
) {
    var reach by remember(course.id, course.revision) { mutableStateOf<CoursePage.Unavailable?>(null) }
    var open by remember(course.id, course.revision) { mutableStateOf(false) }
    LaunchedEffect(presentation, course.id, course.revision) {
        reach = withContext(Dispatchers.IO) { presentation.unsavedReach(course) }
    }
    val pending = reach ?: return
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.course_sourced_open)) }
    if (open) {
        CourseSourcedPreparationDialog(pending, presentation, sourcedHost, plainHost, onPrepared) {
            open = false
            onReload()
        }
    }
}

@Composable
private fun CourseSourcedPreparationDialog(
    pending: CoursePage.Unavailable,
    presentation: CoursePresentation,
    sourcedHost: CourseSourcedSlideGenerationHost,
    plainHost: CourseSlideGenerationHost?,
    onPrepared: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(pending) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var waiting by remember { mutableStateOf<Int?>(null) }
    var stale by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun close() {
        job?.cancel()
        onDismiss()
    }

    fun prepare(sourced: Boolean) {
        if (waiting != null || stale) return
        val submitted = query.trim()
        val host = if (sourced) CourseSlideGenerationHost { sourcedHost.generate(it, submitted) } else plainHost ?: return
        val reached = target
        error = null
        waiting = if (sourced) R.string.course_sourced_waiting else R.string.course_generation_waiting
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { presentation.generateFromOutline(reached, host, sourced) }
                ensureActive()
                when (val saved = result.page) {
                    is CoursePage.Slide -> {
                        when {
                            result.reloaded -> {
                                stale = true
                                error = R.string.course_sourced_stale
                            }
                            sourced && saved.sources.isEmpty() -> {
                                stale = true
                                error = R.string.course_sourced_missing_attribution
                            }
                            else -> onPrepared()
                        }
                    }
                    else -> {
                        stale = result.reloaded || saved !is CoursePage.Unavailable || saved.generationRequest == null
                        if (!stale && saved is CoursePage.Unavailable) target = saved
                        error = when {
                            result.reloaded -> R.string.course_sourced_stale
                            result.searchIssue == CourseSourceSearchIssue.QUERY -> R.string.course_sourced_invalid_query
                            result.searchIssue == CourseSourceSearchIssue.UNAVAILABLE -> R.string.course_sourced_unavailable
                            result.searchIssue == CourseSourceSearchIssue.EMPTY -> R.string.course_sourced_empty
                            result.searchIssue == CourseSourceSearchIssue.FAILED -> R.string.course_sourced_failed
                            saved is CoursePage.Unavailable && saved.issue == CourseSlideIssue.GENERATION_INTERRUPTED ->
                                R.string.course_generation_interrupted
                            saved is CoursePage.Unavailable && saved.issue == CourseSlideIssue.GENERATION_BUSY ->
                                R.string.course_generation_busy
                            else -> R.string.course_slide_generation_failed
                        }
                    }
                }
            } finally {
                waiting = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { CourseHeading(stringResource(R.string.course_sourced_open)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pending.step.slide.goal)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.course_sourced_query)) },
                    enabled = waiting == null && !stale,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                (waiting ?: error)?.let {
                    Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (waiting != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null && waiting == null && !stale && plainHost != null) {
                    TextButton(onClick = { prepare(sourced = false) }) { Text(stringResource(R.string.course_sourced_continue)) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { prepare(sourced = true) }, enabled = query.isNotBlank() && waiting == null && !stale) {
                Text(stringResource(R.string.course_sourced_submit))
            }
        },
        dismissButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.course_sourced_cancel)) } },
    )
}

/** The host owns consent and the revision-checked proposal and apply operations. */
internal class CourseOutlineRefinementHost(
    val propose: suspend (courseId: String, expectedRevision: Long, input: Draft) -> CourseOutlineRefinement,
    val apply: suspend (CourseOutlineRefinement) -> CourseSnapshot,
)

private enum class CourseRefinementWork(val waiting: Int, val failed: Int) {
    PROPOSE(R.string.course_refinement_waiting, R.string.course_refinement_request_failed),
    APPLY(R.string.course_refinement_saving, R.string.course_refinement_apply_failed),
}

@Composable
private fun CourseOutlineRefinementPanel(
    course: CourseSnapshot,
    host: CourseOutlineRefinementHost,
    model: ShellViewModel,
    onApplying: (Boolean) -> Unit,
    onApplied: (CourseSnapshot) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var proposal by remember { mutableStateOf<CourseOutlineRefinement?>(null) }
    var pending by remember { mutableStateOf<CourseRefinementWork?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun close() {
        if (pending == CourseRefinementWork.APPLY) return
        job?.cancel()
        onDismiss()
    }

    fun <T> run(work: CourseRefinementWork, operation: suspend () -> T, onSuccess: (T) -> Unit) {
        if (pending != null) return
        pending = work
        if (work == CourseRefinementWork.APPLY) onApplying(true)
        error = null
        job = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { operation() }
            } catch (cancel: CancellationException) {
                if (isActive) error = work.failed
                throw cancel
            } catch (failure: CourseStoreException) {
                error = when (failure.reason) {
                    CourseRejection.STALE_REVISION, CourseRejection.COURSE_NOT_FOUND -> {
                        proposal = null
                        R.string.course_refinement_stale
                    }
                    CourseRejection.PROTECTED_PROGRESS -> R.string.course_refinement_protected
                    else -> work.failed
                }
                return@launch
            } catch (_: Exception) {
                error = work.failed
                return@launch
            } finally {
                pending = null
                if (work == CourseRefinementWork.APPLY) onApplying(false)
            }
            ensureActive()
            onSuccess(result)
        }
    }

    val composer = remember {
        lateinit var destination: CourseComposerState
        destination = CourseComposerState(4096, send = {
            val draft = destination.draft
            if (error != R.string.course_refinement_stale && (draft.text.isNotBlank() || draft.attachments.isNotEmpty())) {
                run(CourseRefinementWork.PROPOSE, {
                    host.propose(course.id, course.revision, draft.copy(text = draft.text.trim())).also {
                        check(it.courseId == course.id && it.expectedRevision == course.revision)
                    }
                }) {
                    proposal = it
                    destination.draft = Draft()
                }
            }
        }, cancel = {
            if (pending == CourseRefinementWork.PROPOSE) {
                job?.cancel()
                error = null
            }
        })
        destination
    }
    SideEffect { composer.busy = pending != null }
    DisposableEffect(composer) { onDispose { model.closeCourseComposer(composer) } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val review = proposal
        CourseHeading(stringResource(if (review == null) R.string.course_refinement_open else R.string.course_refinement_review))
        error?.let { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        pending?.let { work ->
            Text(stringResource(work.waiting), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            courseOutlineItems(review?.outline ?: course.outline)
        }
        Row {
            if (review != null) {
                Button(enabled = pending == null && !model.attachmentBusy, onClick = {
                    run(CourseRefinementWork.APPLY, {
                        host.apply(review).also { check(it.id == course.id) }
                    }) {
                        onDismiss()
                        onApplied(it)
                    }
                }) { Text(stringResource(R.string.course_refinement_apply)) }
            }
            TextButton(onClick = ::close, enabled = pending != CourseRefinementWork.APPLY) {
                Text(stringResource(R.string.course_refinement_cancel))
            }
        }
        if (review == null && error != R.string.course_refinement_stale) {
            CourseComposer(model, composer, R.string.course_refinement_instruction)
        }
    }
}

@Composable
private fun CourseSlidePlayer(
    slide: CoursePage.Slide,
    sceneHost: CourseSceneHost?,
    busy: Boolean,
    text: String,
    order: List<String>,
    onText: (String) -> Unit,
    onOrder: (List<String>) -> Unit,
    onSubmit: () -> Unit,
    nextPreparation: CourseNextPreparation?,
    onRetryNext: () -> Unit,
    onNext: () -> Unit,
    scroll: ScrollState,
) {
    val colors = MaterialTheme.colorScheme
    var sceneEditing by remember(sceneHost) { mutableStateOf(false) }
    val lessonIndex = slide.course.outline.lessons.indexOfFirst { it.id == slide.step.lessonId }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.course_lesson_position, lessonIndex + 1, slide.course.outline.lessons.size),
                    style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.course_slide_position, slide.number, slide.course.progress.totalSlides),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(
                progress = { slide.number.toFloat() / slide.course.progress.totalSlides },
                modifier = Modifier.fillMaxWidth(),
            )
            val correct = slide.feedback?.correct
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(slide.step.lessonTitle, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                if (sceneHost == null) ToolContent(ui = slide.ui, state = slide.state, enabled = false, embedded = true, event = { _, _ -> })
                else CourseScene(sceneHost, !busy, onEditing = { sceneEditing = it })
                slide.adaptationNote?.let {
                    CourseHeading(stringResource(R.string.course_adaptation))
                    Text(it)
                }
                if (slide.sources.isNotEmpty()) {
                    CourseHeading(stringResource(R.string.course_sources))
                    slide.sources.forEach {
                        Text(it.title)
                        Text(it.url, style = MaterialTheme.typography.bodySmall)
                    }
                }
                slide.prompt?.let {
                    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CourseExerciseControls(it, !busy && correct != true, text, order, onText, onOrder, onSubmit)
                        }
                    }
                }
                if (correct != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = if (correct) colors.secondaryContainer else colors.errorContainer) {
                        Text(stringResource(if (correct) R.string.course_answer_correct else R.string.course_answer_wrong),
                            Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
            if (slide.prompt == null || correct == true) {
                when {
                    slide.nextRequest == null || nextPreparation is CourseNextPreparation.Ready ->
                        Button(onClick = onNext, enabled = !busy && !sceneEditing, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.course_next))
                        }
                    nextPreparation == null -> CourseNextNotice(R.string.course_next_preparing)
                    nextPreparation == CourseNextPreparation.Unavailable -> CourseNextNotice(R.string.course_next_unavailable)
                    else -> {
                        val stale = nextPreparation == CourseNextPreparation.Stale
                        if (nextPreparation is CourseNextPreparation.SceneFailed) {
                            Text(nextPreparation.detail, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        } else CourseNextNotice(if (stale) R.string.course_next_changed else R.string.course_next_failed)
                        TextButton(onClick = onRetryNext, enabled = !busy) {
                            Text(stringResource(if (stale) R.string.course_reload else R.string.course_generation_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseScene(host: CourseSceneHost, enabled: Boolean, onEditing: (Boolean) -> Unit) {
    var snapshot by remember(host) { mutableStateOf<ArtifactSnapshot?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(host) {
        onDispose { host.dispose() }
    }
    NativeToolSurface(host.initial, host.spec, true, 360.dp, host, covered = !enabled, content = {
        val accepted = snapshot
        val record = accepted?.tool ?: host.initial
        ToolContent(record.ui, record.state, enabled && accepted != null, embedded = true,
            liveInput = accepted?.let { NativeLiveInput.automatic(host, it, enabled) },
            onLiveInputState = { unfinished, _ -> onEditing(unfinished) }) { action, value ->
            scope.launch {
                try {
                    snapshot = host.event(JSONObject().put("type", "action").put("actionId", action)
                        .put("value", value ?: JSONObject.NULL))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // The shared surface driver observes the stopped session and offers recovery.
                }
            }
        }
    }) { snapshot = it }
}

@Composable
private fun CourseNextNotice(label: Int) {
    Text(stringResource(label), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun CourseExerciseControls(
    prompt: CourseExercisePrompt,
    enabled: Boolean,
    text: String,
    order: List<String>,
    onText: (String) -> Unit,
    onOrder: (List<String>) -> Unit,
    onSubmit: () -> Unit,
) {
    CourseHeading(prompt.question)
    when (prompt.kind) {
        CourseSlideKind.CHOICE -> {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                prompt.options.forEach { option ->
                    val selected = text == option.id
                    val colors = MaterialTheme.colorScheme
                    Surface(shape = MaterialTheme.shapes.medium,
                        color = if (selected) colors.primaryContainer else colors.surface,
                        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected, enabled = enabled, role = Role.RadioButton) { onText(option.id) }
                            .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null, enabled = enabled)
                            Text(option.label)
                        }
                    }
                }
            }
        }
        CourseSlideKind.FILL_IN -> {
            OutlinedTextField(value = text, onValueChange = { onText(it.take(4096)) }, enabled = enabled,
                label = { Text(stringResource(R.string.course_answer_label)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
        }
        CourseSlideKind.ORDER -> {
            Text(stringResource(R.string.course_order_instructions))
            val options = prompt.options.associateBy { it.id }
            val selected = order.filter(options::containsKey)
            selected.forEachIndexed { index, id ->
                Text(stringResource(R.string.course_order_position, index + 1, options.getValue(id).label), Modifier.padding(8.dp))
            }
            prompt.options.filterNot { it.id in selected }.forEach { option ->
                TextButton(onClick = { onOrder(selected + option.id) }, enabled = enabled) { Text(option.label) }
            }
            if (selected.isNotEmpty()) {
                TextButton(onClick = { onOrder(emptyList()) }, enabled = enabled) { Text(stringResource(R.string.course_order_reset)) }
            }
        }
        else -> error("The slide does not accept an answer.")
    }
    Button(onClick = onSubmit, enabled = enabled && prompt.answerFor(text, order) != null, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.course_check))
    }
}

private fun CourseExercisePrompt.answerFor(text: String, order: List<String>): CourseAnswer? = when (kind) {
    CourseSlideKind.CHOICE -> text.takeIf { value -> options.any { it.id == value } }?.let { CourseAnswer.Choice(it) }
    CourseSlideKind.FILL_IN -> text.takeIf(String::isNotBlank)?.let { CourseAnswer.FillIn(it) }
    CourseSlideKind.ORDER -> order.filter { id -> options.any { it.id == id } }
        .takeIf { it.size == options.size }?.let { CourseAnswer.Order(it) }
    else -> error("The slide does not accept an answer.")
}

@Composable
private fun CourseRecovery(message: Int, onReload: () -> Unit) {
    Text(stringResource(message))
    TextButton(onClick = onReload) { Text(stringResource(R.string.course_reload)) }
}

private val CoursePage.Unavailable.generationReach: String
    get() = "${course.outlineRevision}:${step.slide.id}"

@Composable
private fun CourseGenerationPage(
    page: CoursePage.Unavailable,
    waiting: Boolean,
    hostAvailable: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onReload: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CourseHeading(page.course.setup.topic)
        CourseProgressText(page.course.progress)
        Text(page.step.slide.goal)
        val message = when {
            waiting -> R.string.course_generation_waiting
            page.issue == CourseSlideIssue.INVALID -> R.string.course_slide_invalid
            page.generationRequest != null && !hostAvailable -> R.string.course_generation_unavailable
            page.issue == CourseSlideIssue.GENERATION_FAILED -> R.string.course_slide_generation_failed
            page.issue == CourseSlideIssue.GENERATION_INTERRUPTED -> R.string.course_generation_interrupted
            page.issue == CourseSlideIssue.GENERATION_BUSY -> R.string.course_generation_busy
            else -> R.string.course_slide_missing
        }
        Text(page.sceneFailure?.takeUnless { waiting } ?: stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when {
            waiting -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text(stringResource(R.string.course_generation_cancel)) }
            }
            hostAvailable && page.generationRequest != null -> {
                Button(onClick = onRetry) { Text(stringResource(R.string.course_generation_retry)) }
            }
            else -> TextButton(onClick = onReload) { Text(stringResource(R.string.course_reload)) }
        }
    }
}

@Composable
private fun CourseProgressText(progress: CourseProgress) {
    Text(stringResource(R.string.course_progress, progress.completedSlides, progress.totalSlides))
}

@Composable
private fun CourseHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

/** The host receives a request only after the learner selects the send action. */
internal fun interface CourseTutorHost {
    suspend fun answer(request: CourseTutorRequest): String
}

/** The host uses the course ID, revision, and slide ID only for local validation. */
internal data class CourseTutorRequest(
    val courseId: String,
    val expectedRevision: Long,
    val slideId: String,
    val question: String,
    val slide: CourseTutorSlide,
    val earlierAnswers: List<CourseTutorAnswer>,
    val history: List<CourseTutorExchange>,
    val attachments: List<Attachment> = emptyList(),
)

internal data class CourseTutorSlide(
    val topic: String,
    val lessonTitle: String,
    val number: Int,
    val goal: String,
    val text: List<String>,
    val exercise: CourseExercisePrompt?,
    val adaptationNote: String?,
    val sources: List<CourseSourceReference>,
)

internal data class CourseTutorAnswer(val slideGoal: String, val answer: CourseAnswer, val correct: Boolean)

internal data class CourseTutorExchange(val question: String, val answer: String)

private enum class CourseTutorStatus { IDLE, WAITING, FAILED, CANCELED }

private class CourseTutorSession(
    private val scope: CoroutineScope,
    private val model: ShellViewModel?,
    private val answer: suspend (Draft, List<CourseTutorExchange>) -> String,
) {
    var open by mutableStateOf(false)
        private set
    val composer = CourseComposerState(2000, ::send, ::cancel).apply { active = false }
    var history by mutableStateOf(emptyList<CourseTutorExchange>())
        private set
    var status by mutableStateOf(CourseTutorStatus.IDLE)
        private set

    private var request: Job? = null

    fun show() {
        composer.active = true
        open = true
    }

    fun close() {
        cancel()
        if (model != null) model.closeCourseComposer(composer) else composer.deactivate()
        open = false
    }

    private fun send() {
        val draft = composer.draft
        val question = draft.text.trim()
        if (!open || composer.busy || question.isEmpty() && draft.attachments.isEmpty()) return
        val earlier = history
        status = CourseTutorStatus.WAITING
        composer.busy = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val reply = answer(draft.copy(text = question), earlier).trim()
                ensureActive()
                check(reply.isNotEmpty())
                history = (earlier + CourseTutorExchange(question, reply.take(8000))).takeLast(12)
                composer.draft = Draft()
                status = CourseTutorStatus.IDLE
            } catch (cancel: CancellationException) {
                if (isActive) status = CourseTutorStatus.CANCELED
                throw cancel
            } catch (_: Exception) {
                ensureActive()
                status = CourseTutorStatus.FAILED
            } finally {
                if (request === coroutineContext[Job]) {
                    request = null
                    composer.busy = false
                }
            }
        }
        request = job
        job.start()
    }

    private fun cancel() {
        val job = request ?: return
        request = null
        job.cancel()
        composer.busy = false
        status = CourseTutorStatus.CANCELED
    }
}

private fun CourseStore.tutorRequest(
    slide: CoursePage.Slide,
    draft: Draft,
    history: List<CourseTutorExchange>,
): CourseTutorRequest {
    val course = slide.course
    check(course(course.id)?.revision == course.revision)
    val reached = course.outline.steps().take(slide.number).associateBy { it.slide.id }
    val answers = ArrayDeque<CourseTutorAnswer>()
    for (attempt in answerAttempts(course.id)) {
        val step = reached[attempt.slideId] ?: continue
        if (answers.size == 20) answers.removeFirst()
        answers.addLast(CourseTutorAnswer(step.slide.goal, attempt.answer, attempt.correct))
    }
    check(course(course.id)?.revision == course.revision)
    return CourseTutorRequest(
        courseId = course.id,
        expectedRevision = course.revision,
        slideId = slide.step.slide.id,
        question = draft.text,
        slide = CourseTutorSlide(course.setup.topic, slide.step.lessonTitle, slide.number, slide.step.slide.goal,
            slide.tutorText(), slide.prompt, slide.adaptationNote, slide.sources),
        earlierAnswers = answers.toList(),
        history = history,
        attachments = draft.attachments,
    )
}

private fun CoursePage.Slide.tutorText(): List<String> = buildList {
    val values = JSONObject(state)
    fun visit(node: JSONObject) {
        if (!ToolUiCatalog.visible(node, values)) return
        when (node.getString("type")) {
            "text", "metric", "badge" -> add(ToolUiCatalog.display(node, values))
            "pager", "routeStack", "transition", "dialog", "bottomSheet" -> return
        }
        node.optJSONArray("children")?.let { children ->
            repeat(children.length()) { visit(children.getJSONObject(it)) }
        }
    }
    visit(JSONObject(ui))
}
