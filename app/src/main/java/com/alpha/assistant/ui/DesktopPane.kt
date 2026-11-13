package com.alpha.assistant.ui

import android.content.ActivityNotFoundException
import android.content.Intent

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alpha.assistant.R
import com.alpha.assistant.automations.AutomationResumeResult
import com.alpha.assistant.courses.CourseSnapshot
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.toolErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalTime
import java.util.Date
import java.util.TimeZone

@StringRes internal fun greetingRes(hour: Int): Int = when (hour) {
    in 5..11 -> R.string.greeting_morning
    in 12..17 -> R.string.greeting_day
    in 18..22 -> R.string.greeting_evening
    else -> R.string.greeting_night
}

@Composable internal fun DesktopPane(
    model: ShellViewModel,
    open: (ShellDestination) -> Unit,
    isDesktopVisible: Boolean,
) {
    val context = LocalContext.current
    val tools = model.tools
    val layout = remember(context, tools) { DesktopLayout(context, tools) }
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var choosePlacement by remember { mutableStateOf<DesktopRef.Kind?>(null) }
    var pendingAutomations by remember { mutableStateOf(emptySet<String>()) }
    var automationError by remember { mutableStateOf<Int?>(null) }
    var calendarUnavailable by remember { mutableStateOf(false) }
    var retryLayout by remember { mutableIntStateOf(0) }
    val automationsResult by desktopAutomations(tools.automationDatabase)
    val automations = automationsResult?.getOrNull().orEmpty()
    val calendar by desktopCalendarEvent()
    val event = calendar?.takeIf { canReadDesktopCalendar(context) }
    var coursesResult by remember(model.courseStore) { mutableStateOf<Result<List<CourseSnapshot>>?>(null) }
    var refreshCourses by remember { mutableIntStateOf(0) }
    LaunchedEffect(model.courseStore, isDesktopVisible, refreshCourses) {
        if (isDesktopVisible) coursesResult = withContext(Dispatchers.IO) { runCatching { model.courseStore.courses() } }
    }
    val courses = coursesResult?.getOrNull().orEmpty()
    val courseById = courses.associateBy { it.id }
    fun openCourse(courseId: String) {
        editing = false
        val lookup = model.resumeDesktopCourse(courseId) ?: return
        scope.launch {
            lookup.join()
            refreshCourses++
        }
    }

    val placed = tools.tools.filter { it.saved && it.onDesktop }.sortedWith(compareBy<ToolRecord> { it.createdAt }.thenBy { it.id })
        .map { DesktopRef(DesktopRef.Kind.Tool, it.id) }
    val unplaced = tools.tools.filterNot { it.saved && it.onDesktop }.map { DesktopRef(DesktopRef.Kind.Tool, it.id) }.toSet()
    val layoutReady = layout.order != null || !tools.busy
    LaunchedEffect(layout, layoutReady, placed, unplaced, retryLayout) {
        if (layoutReady) layout.sync(placed, unplaced)
    }
    val visibleTools = tools.tools.filter { it.saved && it.onDesktop && !it.archived }.associateBy { it.id }
    val automationById = automations.associateBy { it.rule.id }
    val order = layout.order.orEmpty()
    val visible = (order + placed).distinct().filter { ref ->
        when (ref.kind) {
            DesktopRef.Kind.Tool -> ref.id in visibleTools
            DesktopRef.Kind.Automation -> ref.id in automationById
            DesktopRef.Kind.Course -> ref.id in courseById
        }
    }
    val automationChoices = automations.filter { DesktopRef(DesktopRef.Kind.Automation, it.rule.id) !in order }
    val courseChoices = courses.filter { DesktopRef(DesktopRef.Kind.Course, it.id) !in order }
    val canEditLayout = layout.order != null && !layout.saving
    val empty = visible.isEmpty()
    val desktopLoaded = canEditLayout && !layout.failed && !tools.busy && !tools.storageUnavailable &&
        automationsResult?.isSuccess == true && coursesResult?.isSuccess == true
    LaunchedEffect(empty, desktopLoaded) {
        if (empty && desktopLoaded) editing = false
    }
    val openLibrary = {
        editing = false
        tools.select(null)
        open(ShellDestination.MiniApps)
    }
    BackHandler(editing && !empty) { editing = false }

    val placementKind = choosePlacement
    if (placementKind != null) {
        val pickingCourse = placementKind == DesktopRef.Kind.Course
        val options = if (pickingCourse) courseChoices.map { it.id to it.setup.topic }
            else automationChoices.map { it.rule.id to it.rule.definition.name }
        val title = if (pickingCourse) R.string.desktop_choose_course else R.string.desktop_choose_automation
        val label = if (pickingCourse) R.string.desktop_place_course else R.string.desktop_place_automation
        AlertDialog(onDismissRequest = { choosePlacement = null },
            title = { Text(stringResource(title)) },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { (id, name) ->
                        TextButton(onClick = {
                            choosePlacement = null
                            scope.launch { layout.setPlacement(DesktopRef(placementKind, id), true) }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canEditLayout) {
                            Text(stringResource(label, name))
                        }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { choosePlacement = null }) { Text(stringResource(R.string.cancel)) }
            })
    }

    val greeting = stringResource(greetingRes(LocalTime.now().hour))
    val name = model.snapshot.userName.trim()
    val slotsWithoutCalendar = if (event == null) 2 else 1
    val attention = automations.filter { it.needsAttention }.take(slotsWithoutCalendar)
    val unfinishedCourse = if (attention.size < slotsWithoutCalendar) courses.firstOrNull { !it.progress.isComplete } else null
    BoxWithConstraints(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
        val compactText = LocalDensity.current.fontScale < 1.5f
        val columns = if (maxWidth >= 480.dp && compactText) 2 else 1
        val showMascot = maxWidth >= 320.dp && compactText
        LazyVerticalGrid(GridCells.Fixed(columns), modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "desktop-header", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (showMascot) {
                                Image(painterResource(R.drawable.alphy_welcome), contentDescription = null,
                                    modifier = Modifier.size(76.dp).clip(MaterialTheme.shapes.large))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (name.isEmpty()) greeting else stringResource(R.string.greeting_named, greeting, name),
                                    Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall.merge(RussianHyphenation))
                                Text(stringResource(R.string.desktop_prompt), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (event != null || attention.isNotEmpty() || unfinishedCourse != null) {
                        Text(stringResource(R.string.now_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            attention.forEach { automationItem ->
                item(key = "attention:${automationItem.rule.id}", span = { GridItemSpan(maxLineSpan) }) {
                    NowCard(Glyph.Repeat, stringResource(R.string.desktop_now_automation_attention),
                        stringResource(R.string.desktop_now_automation_detail, automationItem.rule.definition.name,
                            automationItem.rule.attention.ifBlank { automationItem.lastRun?.message.orEmpty() })) {
                        model.openAutomationSurface(automationItem.rule.id)
                    }
                }
            }
            if (event != null) item(key = "now-calendar", span = { GridItemSpan(maxLineSpan) }) {
                NowCalendarCard(event) {
                    if (!canReadDesktopCalendar(context)) return@NowCalendarCard
                    calendarUnavailable = try {
                        context.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
                        false
                    } catch (_: ActivityNotFoundException) { true }
                }
                if (calendarUnavailable) DesktopError(stringResource(R.string.desktop_calendar_open_failed))
            }
            if (unfinishedCourse != null) item(key = "now-course", span = { GridItemSpan(maxLineSpan) }) {
                NowCard(Glyph.File, stringResource(R.string.desktop_now_course_title),
                    stringResource(R.string.desktop_now_course_detail, unfinishedCourse.setup.topic,
                        unfinishedCourse.lessonPosition, unfinishedCourse.outline.lessons.size)) {
                    openCourse(unfinishedCourse.id)
                }
            }
            item(key = "grid-header", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.desktop_grid_title), Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge)
                        if (!empty) TextButton(onClick = { editing = !editing }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (editing) R.string.desktop_done else R.string.desktop_edit))
                        }
                    }
                    if ((editing || empty) && automationChoices.isNotEmpty()) {
                        TextButton(onClick = { choosePlacement = DesktopRef.Kind.Automation }, enabled = canEditLayout,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.desktop_add_automation))
                        }
                    }
                    if ((editing || empty) && courseChoices.isNotEmpty()) {
                        TextButton(onClick = { choosePlacement = DesktopRef.Kind.Course }, enabled = canEditLayout,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.desktop_add_course))
                        }
                    }
                    if (layout.failed) {
                        DesktopError(stringResource(R.string.desktop_layout_failed))
                        TextButton(onClick = { retryLayout++ }, enabled = !layout.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                    if (automationsResult?.isFailure == true) DesktopError(stringResource(R.string.desktop_automation_read_failed))
                    if (coursesResult?.isFailure == true) {
                        DesktopError(stringResource(R.string.desktop_courses_load_failed))
                        TextButton(onClick = { refreshCourses++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.course_reload))
                        }
                    }
                    automationError?.let { DesktopError(stringResource(it)) }
                    tools.error?.let { message ->
                        DesktopError(toolErrorMessage(tools.errorCode, message))
                        TextButton(onClick = tools::clearError, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.tool_dismiss))
                        }
                    }
                    if (empty && desktopLoaded && tools.error == null) {
                        Text(stringResource(R.string.desktop_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ExampleRequests(model::addExample)
                    }
                    if (empty && tools.tools.any { it.saved && !it.archived }) {
                        TextButton(onClick = openLibrary, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.desktop_open_library))
                        }
                    }
                }
            }
            itemsIndexed(visible, key = { _, ref -> ref.key }) { index, ref ->
                val tool = visibleTools[ref.id].takeIf { ref.kind == DesktopRef.Kind.Tool }
                val item = automationById[ref.id].takeIf { ref.kind == DesktopRef.Kind.Automation }
                val course = courseById[ref.id].takeIf { ref.kind == DesktopRef.Kind.Course }
                val remove: (() -> Unit)? = when {
                    tool != null && !tools.busy -> ({ tools.setDesktopPlacement(tool, false) })
                    (item != null || course != null) && canEditLayout -> ({ scope.launch { layout.setPlacement(ref, false) } })
                    else -> null
                }
                fun move(neighbor: DesktopRef?): (() -> Unit)? {
                    if (!canEditLayout || ref !in order || neighbor == null || neighbor !in order) return null
                    return { scope.launch { layout.move(ref, neighbor) } }
                }
                val actions = DesktopTileActions(open = {
                    if (tool != null) model.openSurface(tool.id)
                    else if (item != null) model.openAutomationSurface(item.rule.id)
                    else if (course != null) openCourse(course.id)
                }, remove = remove, moveEarlier = move(visible.getOrNull(index - 1)), moveLater = move(visible.getOrNull(index + 1)))
                if (tool != null) DesktopToolTile(tool, editing, actions, visible = isDesktopVisible,
                    onAction = if (!tools.busy && !tools.storageUnavailable) { action, value, active ->
                        tools.interactFromDesktop(tool, action, value) { active() && lifecycle.currentState == Lifecycle.State.RESUMED }
                    } else null)
                else if (course != null) DesktopCourseTile(course, editing, actions)
                else if (item != null) DesktopAutomationTile(item, editing, actions, switchEnabled = ref.id !in pendingAutomations) { enabled ->
                    if (ref.id !in pendingAutomations) {
                        automationError = null
                        pendingAutomations = pendingAutomations + ref.id
                        scope.launch {
                            try {
                                if (enabled) {
                                    when (resumeDesktopAutomation(context, tools.automationDatabase, item.rule)) {
                                        AutomationResumeResult.RESUMED -> Unit
                                        AutomationResumeResult.REVIEW_REQUIRED -> model.openAutomationSurface(item.rule.id)
                                        AutomationResumeResult.MISSING -> automationError = R.string.automation_missing
                                    }
                                } else pauseDesktopAutomation(context, tools.automationDatabase, item.rule)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                automationError = if (enabled) R.string.desktop_automation_resume_failed else R.string.desktop_automation_pause_failed
                            } finally {
                                pendingAutomations = pendingAutomations - ref.id
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CourseSnapshot.lessonPosition: Int
    get() = if (progress.isComplete) outline.lessons.size else outline.lessons.indexOfFirst { it.id == progress.currentLessonId } + 1

@Composable private fun DesktopCourseTile(course: CourseSnapshot, editing: Boolean, actions: DesktopTileActions) {
    DesktopTile(course.setup.topic, editing, actions) {
        Text(stringResource(R.string.desktop_course_lesson_progress, course.lessonPosition, course.outline.lessons.size),
            style = MaterialTheme.typography.bodyMedium)
        if (course.progress.isComplete) Text(stringResource(R.string.course_complete), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun DesktopError(message: String) {
    Text(message, PoliteLiveRegion, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun NowCalendarCard(event: DesktopCalendarEvent, open: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val format = if (event.allDay) DateFormat.getDateInstance(DateFormat.SHORT, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
    NowCard(Glyph.Calendar, stringResource(R.string.desktop_now_calendar),
        stringResource(R.string.desktop_now_calendar_detail, event.title ?: stringResource(R.string.desktop_now_calendar_untitled),
            format.format(Date(event.start))), open)
}

@Composable private fun NowCard(glyph: Glyph, title: String, text: String, onClick: (() -> Unit)? = null) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                AlphaIcon(glyph, Modifier.padding(9.dp), color = LocalContentColor.current)
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium.merge(RussianHyphenation))
                Text(text, style = MaterialTheme.typography.bodyMedium.merge(RussianHyphenation))
            }
        }
    }
    if (onClick == null) Card(modifier, shape = MaterialTheme.shapes.medium, colors = colors) { content() }
    else Card(onClick, modifier, shape = MaterialTheme.shapes.medium, colors = colors) { content() }
}
