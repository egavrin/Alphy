package com.alpha.assistant.courses

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alpha.assistant.R
import com.alpha.assistant.tools.ToolContent
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.ui.AdaptiveShellLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
) {
    val presentation = remember(store, controller) { CoursePresentation(store, controller) }
    val scope = rememberCoroutineScope()
    var courseId by rememberSaveable { mutableStateOf<String?>(null) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var returnToHost by rememberSaveable { mutableStateOf(false) }
    var prepareOnEntry by rememberSaveable { mutableStateOf(true) }
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
    val tutorSession = remember(slide?.course?.id, slide?.step?.slide?.id, tutor) { CourseTutorSession(scope) }
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

    fun navigate(id: String?, play: Boolean = false, exitToHost: Boolean = false, autoPrepare: Boolean = true) {
        stopGeneration()
        courseId = id
        playing = play
        returnToHost = exitToHost
        prepareOnEntry = autoPrepare
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

    fun update(answerId: String? = null, generateOnReach: Boolean = false, work: suspend () -> CoursePlayerUpdate) {
        if (busy) return
        busy = true
        heldAnswerId = answerId
        notice = 0
        scope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { work() }) {
                    is CoursePlayerUpdate.Open -> {
                        show(result.page, generateOnReach && !result.reloaded)
                        if (result.reloaded || (result.page as? CoursePage.Slide)?.feedback?.correct != true) {
                            heldAnswerId = null
                        }
                        if (result.reloaded) notice = R.string.course_reloaded
                    }
                    CoursePlayerUpdate.SaveFailed -> {
                        heldAnswerId = null
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
                onApplied = { page = CoursePage.Outline(it) },
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
                val text = if (draftSlideId == slideId) draftText else when (savedAnswer) {
                    is CourseAnswer.Choice -> savedAnswer.optionId
                    is CourseAnswer.FillIn -> savedAnswer.text
                    else -> ""
                }
                val order = if (draftSlideId == slideId) draftOrder else (savedAnswer as? CourseAnswer.Order)?.optionIds.orEmpty()
                CourseSlidePlayer(
                    slide = current,
                    busy = busy,
                    text = text,
                    order = order,
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
                    onSubmit = { answer ->
                        val requestId = UUID.randomUUID().toString()
                        update(requestId) { presentation.submit(current, requestId, answer) }
                    },
                    onNext = { update(generateOnReach = true) { presentation.next(current) } },
                    onOpenTutor = tutorSession::show,
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
                    tutorAvailable = tutor != null,
                    slidePane = {
                        CoursePageFrame(Modifier.fillMaxSize(), backLabel, busy, notice, ::back) { PageContent() }
                    },
                    onAsk = {
                        if (tutor != null && slide != null) {
                            tutorSession.send(tutor) { question, history ->
                                withContext(Dispatchers.IO) { store.tutorRequest(slide, question, history) }
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
    tutorAvailable: Boolean,
    slidePane: @Composable () -> Unit,
    onAsk: () -> Unit,
) {
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    AdaptiveShellLayout(immersive = false, pairApp = session.open, systemInsets = insets) { layout ->
        val safe = Modifier.fillMaxSize().windowInsetsPadding(insets)
        val tabletopGap = layout.tabletopGap
        if (layout.wide && session.open) {
            SupportingPaneScaffold(
                directive = PaneScaffoldDirective(
                    maxHorizontalPartitions = 2,
                    horizontalPartitionSpacerSize = 16.dp,
                    maxVerticalPartitions = 1,
                    verticalPartitionSpacerSize = 0.dp,
                    defaultPanePreferredWidth = 360.dp,
                    defaultPanePreferredHeight = 320.dp,
                    excludedBounds = listOfNotNull(layout.verticalHinge),
                    shouldAutoFocusCurrentDestination = false,
                ),
                value = ThreePaneScaffoldValue(PaneAdaptedValue.Expanded, PaneAdaptedValue.Expanded, PaneAdaptedValue.Hidden),
                mainPane = { slidePane() },
                supportingPane = { CourseTutorPanel(session, tutorAvailable, onAsk, Modifier.fillMaxSize()) },
                modifier = safe.imePadding(),
            )
        } else if (tabletopGap != null && session.open) {
            val density = LocalDensity.current
            Column(safe) {
                Box(Modifier.height(with(density) { tabletopGap.first.toDp() })) { slidePane() }
                Spacer(Modifier.height(with(density) { (tabletopGap.last - tabletopGap.first).toDp() }))
                CourseTutorPanel(session, tutorAvailable, onAsk, Modifier.weight(1f).imePadding())
            }
        } else {
            Box(safe.imePadding()) { slidePane() }
            if (session.open) {
                ModalBottomSheet(
                    onDismissRequest = session::close,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    CourseTutorPanel(session, tutorAvailable, onAsk, Modifier.fillMaxHeight(0.85f))
                }
            }
        }
    }
}

@Composable
private fun CourseTutorPanel(session: CourseTutorSession, available: Boolean, onAsk: () -> Unit, modifier: Modifier) {
    val scroll = rememberLazyListState()
    val history = session.history
    LaunchedEffect(history) {
        if (history.isNotEmpty()) scroll.scrollToItem(history.size)
    }
    LazyColumn(modifier.fillMaxWidth().padding(16.dp), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            CourseHeading(stringResource(R.string.course_tutor_title))
            TextButton(onClick = session::close) { Text(stringResource(R.string.course_tutor_close)) }
        }
        if (!available) {
            item { Text(stringResource(R.string.course_tutor_unavailable)) }
        } else {
            items(history) { exchange ->
                Text(stringResource(R.string.course_tutor_learner_label), style = MaterialTheme.typography.labelLarge)
                Text(exchange.question)
                Text(stringResource(R.string.course_tutor_title), style = MaterialTheme.typography.labelLarge)
                Text(exchange.answer, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            item {
                val status = session.status
                val busy = status == CourseTutorStatus.WAITING
                val statusLabel = when (status) {
                    CourseTutorStatus.WAITING -> R.string.course_tutor_waiting
                    CourseTutorStatus.FAILED -> R.string.course_tutor_failed
                    CourseTutorStatus.CANCELED -> R.string.course_tutor_canceled
                    CourseTutorStatus.IDLE -> null
                }
                statusLabel?.let { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                OutlinedTextField(
                    value = session.draft,
                    onValueChange = session::edit,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.course_tutor_question)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                if (busy) {
                    TextButton(onClick = session::cancel) { Text(stringResource(R.string.course_tutor_cancel)) }
                } else {
                    Button(onClick = onAsk, enabled = session.draft.isNotBlank()) {
                        Text(stringResource(R.string.course_tutor_send))
                    }
                }
            }
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
    onApplied: (CourseSnapshot) -> Unit,
    onResume: () -> Unit,
    sourceAction: @Composable () -> Unit,
) {
    CourseHeading(course.setup.topic)
    CourseProgressText(course.progress)
    Button(onClick = onResume) {
        Text(stringResource(when {
            course.progress.isComplete -> R.string.course_view_completion
            course.progress.completedSlides > 0 -> R.string.course_resume
            else -> R.string.course_open
        }))
    }
    sourceAction()
    CourseOutlineRefinementAction(course, refinement, onApplied)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(course.setup.level)
            Text(course.setup.goal)
            CourseHeading(stringResource(R.string.course_outline))
        }
        courseOutlineItems(course.outline)
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
    val propose: suspend (courseId: String, expectedRevision: Long, instruction: String) -> CourseOutlineRefinement,
    val apply: suspend (CourseOutlineRefinement) -> CourseSnapshot,
)

@Composable
internal fun CourseOutlineRefinementAction(
    course: CourseSnapshot,
    host: CourseOutlineRefinementHost?,
    onApplied: (CourseSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    key(course.id, course.revision, host != null) {
        var open by remember { mutableStateOf(false) }
        Column(modifier) {
            TextButton(onClick = { open = true }, enabled = host != null) {
                Text(stringResource(R.string.course_refinement_open))
            }
            if (host == null) Text(stringResource(R.string.course_refinement_unavailable))
        }
        if (open && host != null) CourseOutlineRefinementDialog(course, host, onApplied) { open = false }
    }
}

private enum class CourseRefinementWork(val waiting: Int, val failed: Int) {
    PROPOSE(R.string.course_refinement_waiting, R.string.course_refinement_request_failed),
    APPLY(R.string.course_refinement_saving, R.string.course_refinement_apply_failed),
}

@Composable
private fun CourseOutlineRefinementDialog(
    course: CourseSnapshot,
    host: CourseOutlineRefinementHost,
    onApplied: (CourseSnapshot) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var instruction by remember { mutableStateOf("") }
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
            }
            ensureActive()
            onSuccess(result)
        }
    }

    val review = proposal
    AlertDialog(
        onDismissRequest = ::close,
        title = { CourseHeading(stringResource(if (review == null) R.string.course_refinement_open else R.string.course_refinement_review)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { message ->
                    Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                pending?.let { work ->
                    Text(stringResource(work.waiting), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (review == null) {
                        item {
                            OutlinedTextField(
                                value = instruction,
                                onValueChange = { instruction = it.take(4096) },
                                label = { Text(stringResource(R.string.course_refinement_instruction)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = pending == null,
                                maxLines = 4,
                            )
                        }
                    } else {
                        courseOutlineItems(review.outline)
                    }
                }
            }
        },
        confirmButton = {
            if (review == null) {
                Button(
                    enabled = pending == null && instruction.isNotBlank() && error != R.string.course_refinement_stale,
                    onClick = {
                        val requested = instruction.trim()
                        run(CourseRefinementWork.PROPOSE, {
                            host.propose(course.id, course.revision, requested).also {
                                check(it.courseId == course.id && it.expectedRevision == course.revision)
                            }
                        }) { proposal = it }
                    },
                ) { Text(stringResource(R.string.course_refinement_request)) }
            } else {
                Button(
                    enabled = pending == null,
                    onClick = {
                        run(CourseRefinementWork.APPLY, {
                            host.apply(review).also { check(it.id == course.id) }
                        }) {
                            onDismiss()
                            onApplied(it)
                        }
                    },
                ) { Text(stringResource(R.string.course_refinement_apply)) }
            }
        },
        dismissButton = {
            TextButton(onClick = ::close, enabled = pending != CourseRefinementWork.APPLY) {
                Text(stringResource(R.string.course_refinement_cancel))
            }
        },
    )
}

@Composable
private fun CourseSlidePlayer(
    slide: CoursePage.Slide,
    busy: Boolean,
    text: String,
    order: List<String>,
    onText: (String) -> Unit,
    onOrder: (List<String>) -> Unit,
    onSubmit: (CourseAnswer) -> Unit,
    onNext: () -> Unit,
    onOpenTutor: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CourseHeading(slide.step.lessonTitle)
        Text(stringResource(R.string.course_slide_position, slide.number, slide.course.progress.totalSlides))
        LinearProgressIndicator(
            progress = { slide.number.toFloat() / slide.course.progress.totalSlides },
            modifier = Modifier.fillMaxWidth(),
        )
        val correct = slide.feedback?.correct
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolContent(ui = slide.ui, state = slide.state, enabled = false, embedded = true, event = { _, _ -> })
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
                CourseExerciseControls(it, !busy && correct != true, text, order, onText, onOrder, onSubmit)
            }
            if (correct != null) {
                Text(stringResource(if (correct) R.string.course_answer_correct else R.string.course_answer_wrong),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        TextButton(onClick = onOpenTutor, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.course_tutor_open))
        }
        if (slide.prompt == null || correct == true) {
            Button(onClick = onNext, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.course_next))
            }
        }
    }
}

@Composable
private fun CourseExerciseControls(
    prompt: CourseExercisePrompt,
    enabled: Boolean,
    text: String,
    order: List<String>,
    onText: (String) -> Unit,
    onOrder: (List<String>) -> Unit,
    onSubmit: (CourseAnswer) -> Unit,
) {
    CourseHeading(prompt.question)
    val answer = when (prompt.kind) {
        CourseSlideKind.CHOICE -> {
            Column(Modifier.selectableGroup()) {
                prompt.options.forEach { option ->
                    val selected = text == option.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected, enabled = enabled, role = Role.RadioButton) { onText(option.id) },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = null, enabled = enabled)
                        Text(option.label)
                    }
                }
            }
            text.takeIf { value -> prompt.options.any { it.id == value } }?.let { CourseAnswer.Choice(it) }
        }
        CourseSlideKind.FILL_IN -> {
            OutlinedTextField(value = text, onValueChange = { onText(it.take(4096)) }, enabled = enabled,
                label = { Text(stringResource(R.string.course_answer_label)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            text.takeIf(String::isNotBlank)?.let { CourseAnswer.FillIn(it) }
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
            selected.takeIf { it.size == prompt.options.size }?.let { CourseAnswer.Order(it) }
        }
        else -> error("The slide does not accept an answer.")
    }
    Button(onClick = { answer?.let(onSubmit) }, enabled = enabled && answer != null) {
        Text(stringResource(R.string.course_check))
    }
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
        Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
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

private class CourseTutorSession(private val scope: CoroutineScope) {
    var open by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")
        private set
    var history by mutableStateOf(emptyList<CourseTutorExchange>())
        private set
    var status by mutableStateOf(CourseTutorStatus.IDLE)
        private set

    private var request: Job? = null

    fun show() { open = true }

    fun close() {
        cancel()
        open = false
    }

    fun edit(text: String) {
        if (status != CourseTutorStatus.WAITING) draft = text.take(2000)
    }

    fun send(host: CourseTutorHost, prepare: suspend (String, List<CourseTutorExchange>) -> CourseTutorRequest) {
        val question = draft.trim()
        if (!open || status == CourseTutorStatus.WAITING || question.isEmpty()) return
        val earlier = history
        status = CourseTutorStatus.WAITING
        request = scope.launch {
            try {
                val input = prepare(question, earlier)
                ensureActive()
                val answer = host.answer(input).trim()
                ensureActive()
                check(answer.isNotEmpty())
                history = (earlier + CourseTutorExchange(question, answer.take(8000))).takeLast(12)
                draft = ""
                status = CourseTutorStatus.IDLE
            } catch (cancel: CancellationException) {
                if (isActive) status = CourseTutorStatus.CANCELED
                throw cancel
            } catch (_: Exception) {
                ensureActive()
                status = CourseTutorStatus.FAILED
            }
        }
    }

    fun cancel() {
        if (status != CourseTutorStatus.WAITING) return
        request?.cancel()
        request = null
        status = CourseTutorStatus.CANCELED
    }
}

private fun CourseStore.tutorRequest(
    slide: CoursePage.Slide,
    question: String,
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
        question = question,
        slide = CourseTutorSlide(course.setup.topic, slide.step.lessonTitle, slide.number, slide.step.slide.goal,
            slide.tutorText(), slide.prompt, slide.adaptationNote, slide.sources),
        earlierAnswers = answers.toList(),
        history = history,
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
