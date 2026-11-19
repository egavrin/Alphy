package com.alpha.assistant.tools

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.widget.NumberPicker
import androidx.core.view.allViews
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.alpha.assistant.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import com.alpha.assistant.ui.StatusLine
import com.alpha.assistant.ui.StrokeIcon
import com.alpha.assistant.capabilities.OwnedCalendarUiHost
import com.alpha.assistant.capabilities.OwnedCalendarSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.round

private val LocalEmbeddedTool = staticCompositionLocalOf { false }
internal val LocalToolBackground = staticCompositionLocalOf { Color.Unspecified }
private val LocalRouteSystemBack = staticCompositionLocalOf { true }
/** The technical preset aligns digits in columns with tabular figures. */
private val LocalToolTabularDigits = staticCompositionLocalOf { false }
private val LocalLiveUi = staticCompositionLocalOf<NativeLiveUi?> { null }
private val LocalLiveRow = staticCompositionLocalOf<String?> { null }
private val LocalTimerClock = staticCompositionLocalOf<NativeTimerClock?> { null }
private val LocalForegroundTimer = staticCompositionLocalOf<com.alpha.assistant.timer.ForegroundTimerUiHost?> { null }
private val LocalVideoUi = staticCompositionLocalOf<NativeVideoUi?> { null }

private class NativeVideoUi(val host: NativeVideoUiHost, val root: JSONObject, val allowAction: () -> Boolean)

private class NativeTimerClock {
    var visibleTimers by mutableIntStateOf(0)
    var nowMillis by mutableLongStateOf(System.currentTimeMillis())
}

internal class NativeLiveInput private constructor(
    val record: ToolRecord,
    val active: Boolean,
    val channel: LiveArtifactUiHost?,
    private val snapshot: ArtifactSnapshot?,
    private val fallback: (suspend (ToolRecord, String, Any?, () -> Boolean) -> LiveInputResult)?,
) {
    val identity get() = Triple(record.id, record.revision, snapshot?.sessionGeneration)

    suspend fun submit(action: String, value: Any?, finish: Boolean, active: () -> Boolean): LiveInputSubmission {
        if (!active()) return LiveInputSubmission.Rejected(LiveInputRejection.Inactive)
        if (channel != null) {
            val shown = checkNotNull(snapshot)
            return if (finish) channel.finishLiveInput(shown, action, value, active)
                else channel.offerLiveInput(shown, action, value, active)
        }
        check(finish)
        return LiveInputSubmission.Pending(CompletableDeferred(checkNotNull(fallback)(record, action, value, active)))
    }

    companion object {
        fun automatic(channel: LiveArtifactUiHost, snapshot: ArtifactSnapshot, active: Boolean) =
            NativeLiveInput(snapshot.tool, active, channel, snapshot, null)

        fun apply(record: ToolRecord, active: Boolean, submit: suspend (ToolRecord, String, Any?, () -> Boolean) -> LiveInputResult) =
            NativeLiveInput(record, active, null, null, submit)
    }
}

private class NativeLiveAccess {
    private val active = AtomicBoolean()
    var available by mutableStateOf(false)
        private set

    fun set(value: Boolean): Boolean = (active.getAndSet(value) != value).also { available = value }
    fun isActive(): Boolean = active.get()
}

private enum class NativeLiveStatus { Pending, Unsaved }

private class NativeLiveEdits(noticeState: MutableState<Int?>,
    private val report: (unfinished: Boolean, unsaved: Boolean) -> Unit) {
    private val unfinished = mutableStateListOf<NativeLiveDraft>()
    var notice by noticeState
    val waiting get() = unfinished.any { it.status == NativeLiveStatus.Pending }

    fun isOpen(draft: NativeLiveDraft) = draft in unfinished

    fun start(draft: NativeLiveDraft) {
        unfinished.add(draft)
        if (unfinished.size == 1) report(true, false)
    }

    fun allowAction(): Boolean {
        if (unfinished.isEmpty()) return true
        notice = R.string.tool_live_input_finish_first
        return false
    }

    fun remove(draft: NativeLiveDraft, unsaved: Boolean = false) {
        val removed = unfinished.remove(draft)
        val idle = unfinished.isEmpty()
        if (idle && notice == R.string.tool_live_input_finish_first) notice = null
        if (unsaved || removed && idle) report(!idle, unsaved)
    }

    fun interrupt(channel: LiveArtifactUiHost?) {
        if (unfinished.isEmpty()) return
        val unsaved = unfinished.any { it.uncommitted }
        if (unsaved) notice = R.string.tool_live_input_unsaved
        else if (notice == R.string.tool_live_input_finish_first) notice = null
        unfinished.forEach { it.interrupt() }
        unfinished.clear()
        channel?.cancelLiveInput()
        report(false, unsaved)
    }
}

private class NativeLiveUi(val adapter: NativeLiveInput?, val access: NativeLiveAccess, val edits: NativeLiveEdits,
    private val root: JSONObject, private val state: JSONObject) {
    fun allows(action: String, payload: Any?): Boolean =
        runCatching { ToolUiCatalog.validateLiveInput(root, action, payload, state) }.isSuccess
}

private class NativeLiveDraft(private val edits: NativeLiveEdits, private val scope: CoroutineScope) {
    private class Request(val sequence: Long, val adapter: NativeLiveInput, val action: String, val payload: Any?,
        val finish: Boolean, val active: () -> Boolean)

    var value by mutableStateOf<Any?>(null)
        private set
    var status by mutableStateOf<NativeLiveStatus?>(null)
        private set
    var finishing by mutableStateOf(false)
        private set
    private var acceptedVersion by mutableStateOf<Int?>(null)
    private var sequence = 0L
    private var guard = AtomicBoolean(true)
    private var waiting: Request? = null
    private var worker: Job? = null
    private var fallbackBase: NativeLiveInput? = null
    val open get() = edits.isOpen(this)
    val uncommitted get() = value != null && acceptedVersion == null

    fun shown(original: Any, shownVersion: Int?): Any {
        if (!open && acceptedVersion?.let { shownVersion != null && shownVersion >= it } == true) return original
        return value ?: original
    }

    fun edit(value: Any, action: String, row: String?, ui: NativeLiveUi, finish: Boolean) {
        val adapter = ui.adapter ?: return
        if (!ui.access.isActive() || finishing) return
        val payload = if (row == null) value else JSONObject().put("key", row).put("value", value)
        this.value = value
        acceptedVersion = null
        if (!ui.allows(action, payload)) {
            reject()
            return
        }
        if (status == NativeLiveStatus.Unsaved && !finish) return
        if (!open) edits.start(this)
        edits.notice = null
        if (adapter.channel == null && !finish) {
            if (fallbackBase == null) fallbackBase = adapter
            return
        }
        finishing = finish
        status = NativeLiveStatus.Pending
        val currentGuard = guard
        val access = ui.access
        waiting = Request(++sequence, fallbackBase ?: adapter, action, payload, finish) {
            currentGuard.get() && access.isActive()
        }
        if (worker?.isActive != true) worker = scope.launch {
            while (true) {
                val request = waiting ?: break
                waiting = null
                val submission = try {
                    request.adapter.submit(request.action, request.payload, request.finish, request.active)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    LiveInputSubmission.Rejected(LiveInputRejection.Failed)
                }
                when (submission) {
                    is LiveInputSubmission.Pending -> scope.launch { complete(request, submission.receipt.await()) }
                    is LiveInputSubmission.Rejected -> complete(request, LiveInputResult.Rejected(submission.reason))
                }
            }
        }
    }

    private fun complete(request: Request, result: LiveInputResult) {
        if (request.sequence != sequence) return
        if (result is LiveInputResult.Accepted && request.active()) {
            acceptedVersion = result.snapshot.tool.stateVersion
            status = null
            finishing = false
            if (request.finish) {
                fallbackBase = null
                edits.remove(this)
            }
        } else reject()
    }

    private fun reject() {
        interrupt()
        edits.remove(this, unsaved = status == NativeLiveStatus.Unsaved)
    }

    fun interrupt() {
        guard.set(false)
        guard = AtomicBoolean(true)
        sequence++
        waiting = null
        fallbackBase = null
        finishing = false
        status = if (uncommitted) NativeLiveStatus.Unsaved else null
        if (status == null) value = null
    }
}

private data class ToolRole(val ink: Color, val background: Color, val foreground: Color)

private fun ColorScheme.toolRole(name: String?) = when (name) {
    "muted" -> ToolRole(onSurfaceVariant, surfaceVariant, onSurfaceVariant)
    "accent" -> ToolRole(primary, primaryContainer, onPrimaryContainer)
    // Success and warning keep green and amber in every palette, so a red palette does not turn success red.
    "success", "warning" -> checkNotNull(ToolPalettes.status(name, background.luminance() < 0.5f)).let { (ink, back, fore) -> ToolRole(ink, back, fore) }
    "error" -> ToolRole(error, errorContainer, onErrorContainer)
    else -> ToolRole(onSurface, surfaceContainer, onSurface)
}

internal fun ColorScheme.toolInk(name: String, background: Color): Color {
    val role = toolRole(name)
    val base = background.luminance()
    fun readable(color: Color): Boolean {
        val luminance = color.luminance()
        return (maxOf(luminance, base) + 0.05f) / (minOf(luminance, base) + 0.05f) >= 4.5f
    }
    return when {
        readable(role.ink) -> role.ink
        readable(role.foreground) -> role.foreground
        readable(onSurface) -> onSurface
        else -> inverseOnSurface
    }
}

/**
 * The colors of the roles of a drawing on a canvas of this background: each tone as its readable ink, and surface as the lowest
 * container of the theme. That is white in a light theme and near black in a dark one, so a shape in surface follows the theme, and a
 * text in surface reads on a shape in an ink (L1 item 6).
 */
internal fun ColorScheme.drawingRoles(background: Color): Map<String, Int> =
    ToolUiCatalog.tones.associateWith { toolInk(it, background).toArgb() } + ("surface" to surfaceContainerLowest.toArgb())

@Composable private fun toolInk(name: String?): Color = when (name) {
    null, "", "default" -> LocalContentColor.current
    else -> MaterialTheme.colorScheme.toolInk(name, LocalToolBackground.current)
}

/** A progress track on a hero card uses the surface color, because the usual tracks are close to the hero container. */
@Composable private fun progressTrack(usual: Color) =
    MaterialTheme.colorScheme.let { if (LocalToolBackground.current == it.secondaryContainer) it.surface else usual }

@Composable private fun ToolRoleSurface(modifier: Modifier, role: ToolRole, content: @Composable () -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = role.background, contentColor = role.foreground) {
        CompositionLocalProvider(LocalToolBackground provides role.background, content = content)
    }
}

@Composable private fun TonedContainer(modifier: Modifier, tone: String, content: @Composable (Modifier) -> Unit) {
    if (tone.isEmpty()) content(modifier)
    else ToolRoleSurface(modifier, MaterialTheme.colorScheme.toolRole(tone)) {
        content(Modifier.fillMaxWidth().padding(12.dp))
    }
}

private val rowTextTypes = setOf("text", "metric", "badge", "fact")

/** A metric value or a text up to this length is a number with a unit, a name, or a short phrase. */
private const val COMPACT_METRIC_LENGTH = 24

/**
 * Shrinks a label until it fits without a line break inside a word. StepBased accepts a break inside a word.
 * With [oneLineFirst], the label first shrinks to fit one line. It breaks between words only when one line cannot fit at [min].
 */
internal data class WholeWordAutoSize(val min: TextUnit, val max: TextUnit, val oneLineFirst: Boolean = false) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val sizes = generateSequence(if (max.isSpecified) max.value else 14f) { it - 1f }.takeWhile { it >= min.value }.map { it.sp }
        fun fits(size: TextUnit, oneLine: Boolean): Boolean {
            val layout = performLayout(constraints, text, size)
            if (layout.hasVisualOverflow) return false
            return if (oneLine) layout.lineCount == 1 else (0 until layout.lineCount - 1).all { line ->
                val end = layout.getLineEnd(line)
                end >= text.length || text[end - 1].isWhitespace() || text[end - 1] == '-' || text[end].isWhitespace()
            }
        }
        if (oneLineFirst) sizes.firstOrNull { fits(it, true) }?.let { return it }
        return sizes.firstOrNull { fits(it, false) } ?: min
    }
}

internal val LocalMiniAppControlsLanguage = staticCompositionLocalOf<String?> { null }

// True inside a row without wrap. Its children share a fixed width, so a short text there shrinks before a word breaks.
private val LocalFixedRow = staticCompositionLocalOf { false }
/** Text inside a ring or a level gauge is centered unless its style sets an alignment. */
private val LocalCenteredContent = staticCompositionLocalOf { false }
@Composable private fun textAlignOf(style: JSONObject?) = when(style?.optString("align")) {
    "center" -> TextAlign.Center; "end" -> TextAlign.End; "start" -> TextAlign.Start
    else -> if (LocalCenteredContent.current) TextAlign.Center else TextAlign.Start
}

// The text for an app in another language than Russian. values/ holds English, and values-<language>/ holds translations.
private val miniAppOtherLanguage = mapOf(
    R.string.tool_generated_apply to R.string.tool_generated_apply_en,
    R.string.tool_generated_search to R.string.tool_generated_search_en,
    R.string.tool_generated_remove to R.string.tool_generated_remove_en,
    R.string.tool_live_input_finish to R.string.tool_live_input_finish_en,
    R.string.tool_number_increase to R.string.tool_number_increase_en,
    R.string.tool_number_decrease to R.string.tool_number_decrease_en,
    R.string.tool_live_input_pending to R.string.tool_live_input_pending_en,
    R.string.tool_live_input_unsaved to R.string.tool_live_input_unsaved_en,
    R.string.tool_live_input_unavailable to R.string.tool_live_input_unavailable_en,
    R.string.tool_live_input_finish_first to R.string.tool_live_input_finish_first_en,
    R.string.tool_timer_idle to R.string.tool_timer_idle_en,
    R.string.tool_timer_expired to R.string.tool_timer_expired_en,
    R.string.tool_timer_unavailable to R.string.tool_timer_unavailable_en,
    R.string.tool_timer_remaining to R.string.tool_timer_remaining_en,
    R.string.tool_timer_elapsed to R.string.tool_timer_elapsed_en,
    R.string.tool_timer_clock_days to R.string.tool_timer_clock_days_en,
    R.string.foreground_timer_start to R.string.foreground_timer_start_en,
    R.string.foreground_timer_resume to R.string.foreground_timer_resume_en,
    R.string.foreground_timer_reset to R.string.foreground_timer_reset_en,
    R.string.foreground_timer_ready to R.string.foreground_timer_ready_en,
    R.string.foreground_timer_paused to R.string.foreground_timer_paused_en,
    R.string.foreground_timer_vibration to R.string.foreground_timer_vibration_en,
    R.string.foreground_timer_notification to R.string.foreground_timer_notification_en,
    R.string.foreground_timer_denied to R.string.foreground_timer_denied_en,
    R.string.foreground_timer_unavailable to R.string.foreground_timer_unavailable_en,
    R.string.foreground_timer_uncertain to R.string.foreground_timer_uncertain_en,
    R.string.foreground_timer_suppressed to R.string.foreground_timer_suppressed_en,
    R.string.foreground_timer_failed to R.string.foreground_timer_failed_en,
    R.string.tool_records_empty to R.string.tool_records_empty_en,
    R.string.tool_chart_empty to R.string.tool_chart_empty_en,
    R.string.tool_generated_dialog_close to R.string.tool_generated_dialog_close_en,
    R.string.tool_route_back to R.string.tool_route_back_en,
    R.string.tool_page to R.string.tool_page_en,
    R.string.tool_page_previous to R.string.tool_page_previous_en,
    R.string.tool_page_next to R.string.tool_page_next_en,
    R.string.tool_reorder_handle to R.string.tool_reorder_handle_en,
    R.string.tool_reorder_move_up to R.string.tool_reorder_move_up_en,
    R.string.tool_reorder_move_down to R.string.tool_reorder_move_down_en,
    R.string.tool_image_unavailable to R.string.tool_image_unavailable_en,
    R.string.tool_game_start to R.string.tool_game_start_en,
    R.string.tool_game_pause to R.string.tool_game_pause_en,
    R.string.tool_game_resume to R.string.tool_game_resume_en,
    R.string.tool_game_restart to R.string.tool_game_restart_en,
    R.string.tool_scene_paused to R.string.tool_scene_paused_en,
)

/** Host text inside a mini-app in the app language. A language without a translation gets English. */
@Composable internal fun miniAppString(resource: Int, vararg args: Any): String {
    val tag = LocalMiniAppControlsLanguage.current
    val other = miniAppOtherLanguage[resource]
    if (tag == null || tag == "ru" || other == null) return stringResource(resource, *args)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = remember(context, configuration, tag) {
        context.createConfigurationContext(android.content.res.Configuration(configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(tag))
        }).resources
    }
    return resources.getString(other, *args)
}

@Composable internal fun ToolContent(ui: String, state: String, enabled: Boolean,
    heading: String? = null, imageVersion: Any? = null, imageLoader: suspend (String) -> Bitmap? = {null}, imageAvailable: suspend (String) -> Boolean = {false}, calendarHost: OwnedCalendarUiHost? = null, embedded: Boolean = false, background: Color = MaterialTheme.colorScheme.surface,
    routeSystemBackEnabled: Boolean = true,
    liveInput: NativeLiveInput? = null,
    onLiveInputState: (unfinished: Boolean, unsaved: Boolean) -> Unit = { _, _ -> },
    videoHost: NativeVideoUiHost? = null,
    timerHost: com.alpha.assistant.timer.ForegroundTimerUiHost? = null,
    appLanguage: String? = null,
    courseSceneLayout: Boolean = false,
    event: (String,Any?) -> Unit) {
    val root=remember(ui) {JSONObject(ui)}
    val values=remember(state) {JSONObject(state)}
    val withinLimits=remember(ui,state) {runCatching {ToolUiCatalog.validateRenderedWork(root,values)}.isSuccess}
    if(!withinLimits) {Text(stringResource(R.string.tool_render_limit)); return}
    val sceneSlot = remember(root, courseSceneLayout) { if (courseSceneLayout) courseSceneSlot(root) else null }
    // A recreation can report interrupted input without restoring or sending any draft value.
    val notice = rememberSaveable { mutableStateOf<Int?>(null) }
    val reportLiveInput by rememberUpdatedState(onLiveInputState)
    val edits = remember(notice) { NativeLiveEdits(notice) { unfinished, unsaved -> reportLiveInput(unfinished, unsaved) } }
    val channel = liveInput?.channel
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val timerClock = remember { NativeTimerClock() }
    val timerActive = timerClock.visibleTimers > 0 && LocalMiniAppLiveAllowed.current
    LaunchedEffect(lifecycle, timerActive) {
        if (timerActive) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = System.currentTimeMillis()
                timerClock.nowMillis = now
                delay(1000L - Math.floorMod(now, 1000L))
            }
        }
    }
    val allowLive = enabled && liveInput?.active == true
    val access = remember(lifecycle, channel, liveInput?.identity, allowLive) { NativeLiveAccess() }
    DisposableEffect(access) {
        fun refresh() {
            val active = allowLive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (access.set(active) && !active) edits.interrupt(channel)
        }
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            access.set(false)
            edits.interrupt(channel)
            lifecycle.removeObserver(observer)
        }
    }
    val waiting = edits.waiting
    LaunchedEffect(channel, access, waiting) {
        if (channel != null) while (access.isActive() && edits.waiting) withFrameNanos(channel::liveInputFrame)
    }
    val guardedCalendar = remember(calendarHost, edits) {
        calendarHost?.let { host -> object : OwnedCalendarUiHost by host {
            override fun deleteAll(action: String, snapshot: OwnedCalendarSnapshot) {
                if (edits.allowAction()) host.deleteAll(action, snapshot)
            }
        } }
    }
    val videoUi = remember(videoHost, root, edits) { videoHost?.let { NativeVideoUi(it, root, edits::allowAction) } }
    Surface(Modifier.fillMaxWidth(), color = background) {
        CompositionLocalProvider(LocalEmbeddedTool provides embedded, LocalToolBackground provides background,
            LocalRouteSystemBack provides (routeSystemBackEnabled && !embedded),
            LocalLiveUi provides NativeLiveUi(liveInput, access, edits, root, values), LocalTimerClock provides timerClock,
            LocalVideoUi provides videoUi, LocalForegroundTimer provides timerHost,
            LocalMiniAppControlsLanguage provides appLanguage) {
            Column(Modifier.fillMaxWidth()) {
                edits.notice?.let { NativeLiveMessage(it) }
                NativeNode(root,values,enabled,imageVersion,imageLoader,imageAvailable,guardedCalendar,false,heading,courseSceneSlot=sceneSlot) { id,payload ->
                    if(enabled && runCatching {ToolUiCatalog.validateAction(root,id,payload,values)}.isSuccess && edits.allowAction()) event(id,payload)
                }
            }
        }
    }
}
private fun space(value: String?, default: Int = 0) = when(value) {"none" -> 0; "small" -> 6; "medium" -> 12; "large" -> 20; else -> default}.dp
private fun alignment(style: JSONObject?) = when(style?.optString("align")) {"center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start}
private fun showsToolHeading(text: String, hostHeading: String?) =
    text.isNotBlank() && !text.trim().equals(hostHeading?.trim(), ignoreCase = true)
private val titledChoices = setOf("singleChoice","multiChoice","segmented","select")
/** The end of the texts after a leading large emoji in a hero card, or 0 when the card does not start with one. */
private fun heroEmojiSide(children: JSONArray): Int {
    val first = children.optJSONObject(0) ?: return 0
    if (first.getString("type") != "emoji" || first.optString("size") != "large") return 0
    return (1 until children.length()).firstOrNull { children.getJSONObject(it).getString("type") !in setOf("text","metric","badge") } ?: children.length()
}

@Composable private fun textStyle(style: JSONObject?, metric: Boolean = false) = when(style?.optString("emphasis")) {
    "title" -> MaterialTheme.typography.titleLarge
    "headline" -> MaterialTheme.typography.headlineMedium
    "label" -> MaterialTheme.typography.labelLarge
    "number" -> MaterialTheme.typography.displaySmall.copy(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily)
    "display" -> MaterialTheme.typography.displayMedium.copy(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily)
    // A plain metric is a secondary value, so the number and display emphases stand out above it.
    else -> if(metric) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
}
@Composable private fun metricStyle(style: JSONObject?): androidx.compose.ui.text.TextStyle {
    val value = textStyle(style, metric = true)
    return value.copy(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
        fontFeatureSettings = if (LocalToolTabularDigits.current) "tnum" else null)
}

@Composable private fun ToolStatusText(text: String, tone: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val glyph = when (tone) {
        "success" -> Glyph.Check
        "warning" -> Glyph.Bell
        "error" -> Glyph.Close
        else -> null
    }
    if (glyph == null) Text(text, modifier, style = style)
    else ProvideTextStyle(style) { StatusLine(glyph, LocalContentColor.current, text, modifier) }
}

/**
 * A large progress ring of about 200 dp with its children centered inside. It grows when the children need more room.
 * When its cell is too narrow to grow, the children shrink to fit inside the circle.
 */
@Composable private fun NativeRing(progress: Float, description: String, amount: String, content: @Composable () -> Unit) {
    val stroke = 14.dp
    Layout(content = {
        CircularProgressIndicator(progress = { progress }, strokeWidth = stroke, strokeCap = StrokeCap.Round,
            trackColor = progressTrack(MaterialTheme.colorScheme.surfaceContainerHighest),
            modifier = Modifier.semantics { contentDescription = description; stateDescription = amount })
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }) { measurables, constraints ->
        val base = 200.dp.roundToPx()
        val inset = (stroke + 12.dp).roundToPx()
        val inner = measurables[1].measure(Constraints(maxWidth = (minOf(constraints.maxWidth, base) - 2 * inset).coerceAtLeast(0)))
        val diagonal = kotlin.math.hypot(inner.width.toFloat(), inner.height.toFloat())
        val size = maxOf(base, (diagonal + 2 * inset).toInt()).coerceAtMost(constraints.maxWidth)
        val scale = if (diagonal > 0f) minOf(1f, (size - 2 * inset).coerceAtLeast(0) / diagonal) else 1f
        val ring = measurables[0].measure(Constraints.fixed(size, size))
        layout(size, size) {
            ring.place(0, 0)
            inner.placeWithLayer((size - inner.width) / 2, (size - inner.height) / 2) { scaleX = scale; scaleY = scale }
        }
    }
}

/** A level gauge: a tank that fills from the bottom, with its children centered inside. */
@Composable private fun NativeLevel(progress: Float, description: String, amount: String, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.widthIn(max = 180.dp).fillMaxWidth().heightIn(min = 220.dp).clip(MaterialTheme.shapes.large).background(scheme.surfaceContainerHigh)
        .semantics { contentDescription = description; stateDescription = amount }) {
        Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(progress).align(Alignment.BottomCenter).background(scheme.primary.copy(alpha = 0.45f)))
        }
        Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** The leading icon of a field-like button, such as a select or a date input. */
@Composable private fun RowScope.NativeButtonIcon(node: JSONObject) {
    val icon = node.optString("icon")
    if (icon.isNotEmpty()) { ToolGlyph(icon, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
}

/** Value pills above slider thumbs. The track insets its ends by about 10 dp. */
@Composable private fun SliderValueLabels(values: List<Double>, low: Double, high: Double) {
    Layout(content = {
        values.forEach { value ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val pills = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val inset = 10.dp.roundToPx()
        val width = constraints.maxWidth
        layout(width, pills.maxOf { it.height }) {
            pills.forEachIndexed { index, pill ->
                val fraction = ((values[index] - low) / (high - low)).coerceIn(0.0, 1.0)
                val center = inset + ((width - 2 * inset) * fraction).toInt()
                pill.place((center - pill.width / 2).coerceIn(0, maxOf(0, width - pill.width)), 0)
            }
        }
    }
}

/** Draws a catalog icon: a shell glyph or a domain icon. */
@Composable private fun ToolGlyph(name: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) =
    StrokeIcon(Glyph.entries.firstOrNull { it.name == name }?.path ?: checkNotNull(ToolIcons.paths[name]), modifier, color)

/** A decorative icon in a tinted circle, at the start of a card, a row, or an empty state. The text beside it is the label. */
@Composable private fun IconBadge(name: String, color: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(color.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
        ToolGlyph(name, Modifier.size(size * 0.55f), color)
    }
}

private fun secondaryButtons(children: JSONArray, state: JSONObject, groupEnabled: Boolean): Set<Int> {
    val buttons = (0 until children.length()).filter { index ->
        val node = children.getJSONObject(index)
        node.getString("type") == "button" && ToolUiCatalog.visible(node, state)
    }
    if (buttons.size < 2) return emptySet()
    val unstyled = buttons.filterNot { children.getJSONObject(it).has("variant") }
    val available = buttons.filter { groupEnabled && ToolUiCatalog.enabled(children.getJSONObject(it), state) }
    val hasPrimary = available.any { children.getJSONObject(it).optString("variant") in setOf("filled", "fab", "extendedFab") }
    val primary = if (hasPrimary) null else available.firstOrNull { it in unstyled }
    return unstyled.filterNot { it == primary }.toSet()
}

@Composable private fun nativeLayout(node: JSONObject,placement: Modifier,compact: Boolean): Modifier {
    val layout=node.optJSONObject("layout") ?: JSONObject()
    var result=placement
    result=result.widthIn(min=if(layout.has("minWidth")) layout.getInt("minWidth").dp else Dp.Unspecified,max=if(layout.has("maxWidth")) layout.getInt("maxWidth").dp else Dp.Unspecified)
        .heightIn(min=if(layout.has("minHeight")) layout.getInt("minHeight").dp else Dp.Unspecified,max=if(layout.has("maxHeight")) layout.getInt("maxHeight").dp else Dp.Unspecified)
    if(layout.has("width")) result=result.width(layout.getInt("width").dp)
    else if(layout.optBoolean("fillWidth",!compact)) result=result.fillMaxWidth()
    else if(compact) result=result.widthIn(max=300.dp)
    if(layout.has("height")) result=result.height(layout.getInt("height").dp)
    if(layout.has("aspectRatio")) result=result.aspectRatio(layout.getDouble("aspectRatio").toFloat())
    result=when(layout.optString("insets")) {
        "safeDrawing" -> result.windowInsetsPadding(WindowInsets.safeDrawing)
        "ime" -> result.imePadding()
        "safeDrawingAndIme" -> result.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
        else -> result
    }
    return result
}
private fun boxAlignment(value: String?)=when(value) {"center" -> Alignment.Center; "end" -> Alignment.BottomEnd; else -> Alignment.TopStart}
private fun verticalAlignment(value: String?)=when(value) {"center" -> Alignment.CenterVertically; "end" -> Alignment.Bottom; else -> Alignment.Top}
private fun horizontalArrangement(style: JSONObject?,gap: Dp): Arrangement.Horizontal=when(style?.optString("arrangement")) {
    "center" -> Arrangement.spacedBy(gap,Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(gap,Alignment.End)
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap)
}
private fun verticalArrangement(style: JSONObject?,gap: Dp): Arrangement.Vertical=when(style?.optString("arrangement")) {
    "center" -> Arrangement.spacedBy(gap,Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(gap,Alignment.Bottom)
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap)
}
@Composable private fun NativeReadableText(node: JSONObject,label: String,enabled: Boolean,modifier: Modifier,event: (String,Any?) -> Unit) {
    val style=node.optJSONObject("style")
    val spans=node.optJSONArray("spans")
    val annotated=AnnotatedString.Builder(label)
    if(spans != null) repeat(spans.length()) {index ->
        val span=spans.getJSONObject(index); val start=span.getInt("start"); val end=span.getInt("end")
        annotated.addStyle(SpanStyle(color=if(span.has("tone")) toolInk(span.getString("tone")) else Color.Unspecified,
            fontWeight=if(span.optString("emphasis") == "bold") FontWeight.Bold else null,
            fontStyle=if(span.optString("emphasis") == "italic") FontStyle.Italic else null,
            fontFamily=if(span.optString("emphasis") == "code") FontFamily.Monospace else null),start,end)
        if(span.has("action")) {
            val linkStyle=SpanStyle(color=toolInk(if(enabled) "accent" else "muted"),textDecoration=TextDecoration.Underline)
            if (enabled) annotated.addLink(
                LinkAnnotation.Clickable(span.getString("action"),TextLinkStyles(style=linkStyle)) { event(span.getString("action"),span.getString("url")) },start,end)
            else annotated.addStyle(linkStyle,start,end)
        }
    }
    val selectable=enabled && node.optBoolean("selectable")
    val textModifier=if(selectable) Modifier else modifier
    // A short text in a row, such as a name beside a stepper, shrinks so that no word breaks. Other text keeps its size and wraps.
    val short=LocalFixedRow.current && label.length <= COMPACT_METRIC_LENGTH
    // A short display value, such as a time in a ring, shrinks to fit one line.
    val display=style?.optString("emphasis") == "display" && label.length <= COMPACT_METRIC_LENGTH
    val base=textStyle(style)
    val shortStyle=if((short || display) && base.lineHeight.isSp && base.fontSize.isSp) base.copy(lineHeight=(base.lineHeight.value / base.fontSize.value).em) else base
    val content: @Composable () -> Unit = {
        Text(annotated.toAnnotatedString(),if(!enabled && spans != null) textModifier.semantics {disabled()} else textModifier,style=shortStyle,color=toolInk(style?.optString("tone")),
            textAlign=textAlignOf(style),
            maxLines=node.optInt("maxLines",Int.MAX_VALUE),softWrap=node.optBoolean("softWrap",true),
            overflow=if(node.optString("overflow") == "ellipsis") TextOverflow.Ellipsis else TextOverflow.Clip,
            lineHeight=if(node.has("lineHeight")) node.getDouble("lineHeight").sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            autoSize=if(display) WholeWordAutoSize(12.sp,base.fontSize,oneLineFirst=true) else if(short) WholeWordAutoSize(10.sp,base.fontSize) else null)
    }
    if(selectable) SelectionContainer(modifier=modifier) {content()} else content()
}
private val courseSceneControls = setOf("slider", "numberInput", "toggle", "checkbox", "button", "select", "segmented")

private fun courseSceneSlot(root: JSONObject): Int? {
    if (root.optString("type") != "column" || root.has("layout") ||
        root.optJSONObject("style")?.keys()?.asSequence()?.any { it in setOf("align", "arrangement") } == true
    ) return null
    val children = root.optJSONArray("children") ?: return null
    val items = List(children.length(), children::getJSONObject)
    val index = items.indices.singleOrNull { canvasSurfaceIds(items[it]).isNotEmpty() } ?: return null

    fun canvasOnly(node: JSONObject): Boolean {
        if (node.has("action")) return false
        if (node.getString("type") == "canvas") return true
        if (node.getString("type") !in setOf("column", "row", "box")) return false
        val nested = node.optJSONArray("children") ?: return false
        return (0 until nested.length()).all { canvasOnly(nested.getJSONObject(it)) }
    }

    val trailing = items.drop(index + 1)
    return index.takeIf {
        canvasOnly(items[index]) &&
            items.none { it.has("layout") } &&
            items.take(index).none { it.getString("type") in courseSceneControls } &&
            trailing.isNotEmpty() && trailing.all { it.getString("type") in courseSceneControls }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CourseSceneSlots(
    modifier: Modifier, sceneSlot: Int, count: Int, gap: Dp,
    renderChild: @Composable (Int, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val pair = maxWidth >= 640.dp && density.fontScale <= 1.3f &&
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val panel = if (pair) Modifier.width(with(density) { ((constraints.maxWidth - gap.roundToPx()) / 2).toDp() })
            else Modifier.fillMaxWidth()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            if (sceneSlot > 0) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(sceneSlot) { renderChild(it, Modifier) }
                }
            }
            Box(panel) { renderChild(sceneSlot, Modifier) }
            Column(panel, verticalArrangement = Arrangement.spacedBy(gap)) {
                for (index in sceneSlot + 1 until count) renderChild(index, Modifier)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable private fun NativeNode(node: JSONObject, state: JSONObject, parentEnabled: Boolean, imageVersion: Any?,
    imageLoader: suspend (String) -> Bitmap?, imageAvailable: suspend (String) -> Boolean, calendarHost: OwnedCalendarUiHost?, compact: Boolean, heading: String?, placement: Modifier = Modifier,
    secondaryButton: Boolean = false, namedByTitle: Boolean = false, courseSceneSlot: Int? = null, event: (String,Any?) -> Unit) {
    if(!ToolUiCatalog.visible(node,state)) return
    val type=node.getString("type")
    val definition=ToolUiCatalog.component(type)
    val enabled=parentEnabled && ToolUiCatalog.enabled(node,state)
    val text=node.optString("text")
    val style=node.optJSONObject("style")
    val tone=style?.optString("tone").orEmpty()
    val binding=node.optString("bind")
    // A card draws a background, so its padding is inside it. For other nodes, padding is outside.
    val outerPadding=if(type == "card") 0.dp else space(style?.optString("padding"))
    val modifier=nativeInteractions(node,enabled,event,nativeLayout(node,placement,compact || type in setOf("spacer","icon","emoji")).padding(outerPadding))
    val gap=space(style?.optString("gap"),12)
    val action=node.optString("action")
    val children=node.optJSONArray("children")
    val group = type == "section" || type == "card"
    val secondary = if (children != null && group) secondaryButtons(children, state, enabled) else emptySet()
    // The only list under a shown title, and a choice with the same name as the title, take their name from that title.
    // So the page does not name one thing twice.
    val named = if (children != null && group && showsToolHeading(text, heading)) buildSet {
        val nodes = (0 until children.length()).map { children.getJSONObject(it) }
        nodes.indices.singleOrNull { nodes[it].getString("type") in ToolUiCatalog.recordTypes }?.let(::add)
        addAll(nodes.indices.filter { nodes[it].getString("type") in titledChoices && !showsToolHeading(text, nodes[it].optString("text")) })
    } else emptySet()
    val renderChildren: @Composable (IntRange) -> Unit = { range ->
        for (index in range) NativeNode(checkNotNull(children).getJSONObject(index),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,
            secondaryButton = index in secondary, namedByTitle = index in named, event=event)
    }
    val content: @Composable () -> Unit = { if (children != null) renderChildren(0 until children.length()) }
    if (LocalEmbeddedTool.current && type in ToolUiCatalog.modalTypes) {
        val scheme = MaterialTheme.colorScheme
        ToolRoleSurface(modifier, ToolRole(scheme.onSurface, scheme.surfaceContainerHigh, scheme.onSurface)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.titleMedium)
                content()
                TextButton(onClick = { event(node.getString("dismissAction"), null) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(miniAppString(R.string.tool_generated_dialog_close, text)) }
            }
        }
        return
    }
    when(definition.type) {
        "canvas" -> LocalToolCanvas.current?.let { render ->
            val ids = node.getJSONArray("surfaces")
            render(List(ids.length()) { ids.getString(it) }, text, modifier, enabled)
        } ?: Text(text, modifier)
        "appBar" -> TopAppBar(title={if (showsToolHeading(text, heading)) Text(text)},modifier=modifier,actions={content()})
        "bottomBar" -> BottomAppBar(modifier=modifier) {content()}
        "routeStack" -> NativeRouteStack(node, state, enabled, modifier, event) { route ->
            NativeNode(route,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)
        }
        "dialog" -> AlertDialog(onDismissRequest={if(enabled)event(node.getString("dismissAction"),null)},title={Text(text)},
            text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                CompositionLocalProvider(LocalToolBackground provides MaterialTheme.colorScheme.surfaceContainerHigh, content = content)
            }},
            confirmButton={TextButton(onClick={event(node.getString("dismissAction"),null)},enabled=enabled) {Text(miniAppString(R.string.tool_generated_dialog_close, text))}})
        "bottomSheet" -> ModalBottomSheet(onDismissRequest={if(enabled)event(node.getString("dismissAction"),null)},sheetGesturesEnabled=enabled) {
            Column(modifier.heightIn(max=640.dp).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(text,style=MaterialTheme.typography.titleLarge)
                content()
                TextButton(onClick={event(node.getString("dismissAction"),null)},enabled=enabled) {Text(miniAppString(R.string.tool_generated_dialog_close, text))}
            }
        }
        "transition" -> NativeTransition(node,state,modifier) {content()}
        "pager" -> NativePager(node,state,enabled,modifier,event) {page ->
            NativeNode(checkNotNull(children).getJSONObject(page),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)
        }
        "theme" -> NativePreset(node.getString("preset"), node.optInt("cornerRadius", -1).takeIf { it >= 0 }, node.optDouble("typeScale",1.0),
            node.optString("palette").ifEmpty { null }, node.optBoolean("dark")) {
            Column(modifier,verticalArrangement=verticalArrangement(style,gap),horizontalAlignment=alignment(style),content={content()})
        }
        // Motion animates its children as one group. A Box would draw them on top of each other.
        "motion" -> NativeMotion(node,state,modifier) {
            Column(verticalArrangement=verticalArrangement(style,gap),horizontalAlignment=alignment(style),content={content()})
        }
        "transform" -> NativeTransform(node,state,modifier) {content()}
        "stage" -> NativeStage(node,state,modifier) {leaf -> NativeNode(leaf,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)}
        "column","form" -> TonedContainer(modifier, tone) { containerModifier ->
            val items = checkNotNull(children)
            val secondary = secondaryButtons(items, state, enabled)
            val renderChild: @Composable (Int, Modifier) -> Unit = { index, slot ->
                NativeNode(items.getJSONObject(index),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,
                    placement=slot,secondaryButton = index in secondary,event=event)
            }
            val weighted = type == "column" && (0 until items.length()).any { items.getJSONObject(it).optJSONObject("layout")?.has("weight") == true }
            val column: @Composable (Modifier) -> Unit = { position ->
                @Composable fun body(modifier: Modifier, useWeights: Boolean) =
                    Column(modifier,verticalArrangement=verticalArrangement(style,gap),horizontalAlignment=alignment(style)) {
                        if(type == "form" && showsToolHeading(text, heading)) Text(text,Modifier.semantics {heading()},style=MaterialTheme.typography.titleMedium)
                        repeat(items.length()) { index ->
                            val layout = items.getJSONObject(index).optJSONObject("layout")
                            var slot: Modifier=Modifier
                            if(type == "column") {
                                if(useWeights && layout?.has("weight") == true) slot=slot.weight(layout.getDouble("weight").toFloat())
                                if(layout?.has("align") == true) slot=slot.align(alignment(JSONObject().put("align",layout.getString("align"))))
                            }
                            renderChild(index, slot)
                        }
                    }
                // A weight shares a bounded height. In a scrolling screen the height is unbounded, and a weight would give its child 0 dp.
                if (weighted) BoxWithConstraints(position, propagateMinConstraints = true) { body(Modifier, constraints.hasBoundedHeight) }
                else body(position, false)
            }
            if (courseSceneSlot == null) column(containerModifier)
            else CourseSceneSlots(containerModifier, courseSceneSlot, items.length(), gap, renderChild)
        }
        "box" -> Box(modifier,contentAlignment=boxAlignment(style?.optString("align"))) {
            repeat(checkNotNull(children).length()) {index ->
                val child=children.getJSONObject(index)
                val slot=child.optJSONObject("layout")?.optString("align")?.takeIf {it.isNotEmpty()}?.let {Modifier.align(boxAlignment(it))} ?: Modifier
                NativeNode(child,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,true,heading,placement=slot,event=event)
            }
        }
        "row","flowRow" -> {
            val items = checkNotNull(children)
            val secondary = secondaryButtons(items, state, enabled)
            if(type == "row" && !node.optBoolean("wrap",true)) {
                // A divider between row items is a vertical line across the row. The row draws it from the place of a thin spacer,
                // because items such as facts cannot report the intrinsic height that a full-height divider needs.
                val dividerX = remember(node) { mutableStateMapOf<Int, Float>() }
                val dividerColor = DividerDefaults.color
                Row(modifier.drawBehind {
                    val thickness = DividerDefaults.Thickness.toPx()
                    dividerX.values.forEach { x -> drawLine(dividerColor, Offset(x + thickness / 2, 0f), Offset(x + thickness / 2, size.height), thickness) }
                },horizontalArrangement=horizontalArrangement(style,gap),verticalAlignment=verticalAlignment(style?.optString("crossAlign"))) {
                    CompositionLocalProvider(LocalFixedRow provides true) { repeat(items.length()) {index ->
                        val child=items.getJSONObject(index); val layout=child.optJSONObject("layout")
                        if (child.optString("type") == "divider") {
                            Spacer(Modifier.width(DividerDefaults.Thickness).onPlaced { dividerX[index] = it.positionInParent().x })
                            return@repeat
                        }
                        var slot: Modifier=Modifier
                        if(layout?.has("weight") == true) slot=slot.weight(layout.getDouble("weight").toFloat())
                        // Text without a weight shares the free width, so one long text cannot squeeze the next to one letter per line.
                        else if(child.optString("type") in rowTextTypes) slot=slot.weight(1f, fill = false)
                        if(layout?.has("align") == true) slot=slot.align(verticalAlignment(layout.getString("align")))
                        NativeNode(child,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,true,heading,placement=slot,
                            secondaryButton = index in secondary,event=event)
                    } }
                }
            } else FlowRow(modifier,horizontalArrangement=horizontalArrangement(style,gap),verticalArrangement=Arrangement.spacedBy(gap)) {
                repeat(items.length()) {index ->
                    NativeNode(items.getJSONObject(index),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,true,heading,
                        placement=Modifier.align(verticalAlignment(style?.optString("crossAlign"))),secondaryButton = index in secondary,event=event)
                }
            }
        }
        "grid" -> BoxWithConstraints(modifier) {
            val items = checkNotNull(children)
            val shortButtons = (0 until items.length()).all { index ->
                val child = items.getJSONObject(index)
                child.optString("type") == "button" && child.optString("text").length in 1..3 && !child.has("icon")
            }
            val scaledButtonWidth = if (shortButtons) 48 * LocalDensity.current.fontScale.coerceAtLeast(1f) else 0f
            // An explicit column count, for example a 3 by 3 board, keeps its columns while each cell keeps 80 dp.
            val minWidth = maxOf(scaledButtonWidth, node.optInt("minCellWidth", if (shortButtons) 48 else if (node.has("columns")) 80 else 144).toFloat())
            val fittingColumns = ((maxWidth.value + gap.value) / (minWidth + gap.value)).toInt().coerceAtLeast(1)
            val columns = minOf(node.optInt("columns", 2), fittingColumns)
            Column(verticalArrangement=Arrangement.spacedBy(gap)) {
                val count=items.length()
                for(start in 0 until count step columns) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap)) {
                    repeat(columns) {offset -> Box(Modifier.weight(1f)) {
                        if(start+offset < count) NativeNode(items.getJSONObject(start+offset),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)
                    }}
                }
            }
        }
        "scroll" -> Column(modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(gap)) {content()}
        "facts" -> TonedContainer(modifier, tone) { containerModifier ->
            BoxWithConstraints(containerModifier) {
                val columns=if(maxWidth >= 480.dp) 2 else 1
                Column(verticalArrangement=Arrangement.spacedBy(gap)) {
                    if(showsToolHeading(text, heading)) Text(text,Modifier.semantics {heading()},style=MaterialTheme.typography.titleMedium)
                    val items=checkNotNull(children)
                    for(start in 0 until items.length() step columns) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap)) {
                        repeat(columns) {offset -> Box(Modifier.weight(1f)) {if(start+offset < items.length()) NativeNode(items.getJSONObject(start+offset),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)}}
                    }
                }
            }
        }
        "timeline","listGroup" -> TonedContainer(modifier, tone) { containerModifier ->
            Column(containerModifier,verticalArrangement=Arrangement.spacedBy(if(type == "listGroup") 0.dp else gap)) {
                if(showsToolHeading(text, heading)) Text(text,Modifier.semantics {heading()},style=MaterialTheme.typography.titleMedium)
                var previousVisible: JSONObject? = null
                repeat(checkNotNull(children).length()) {index ->
                    val child = children.getJSONObject(index)
                    if (ToolUiCatalog.visible(child, state)) {
                        if (type == "listGroup" && previousVisible?.optString("type")?.let { it != "listItem" } == true) HorizontalDivider()
                        previousVisible = child
                    }
                    key(index) { NativeNode(child,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event) }
                }
            }
        }
        "timelineItem" -> Row(modifier,verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(if(node.has("timeBind")) ToolUiCatalog.value(state,node.getString("timeBind")).toString() else node.optString("time"),style=MaterialTheme.typography.labelMedium)
                Box(Modifier.padding(top=8.dp).size(10.dp).background(MaterialTheme.colorScheme.primary,RoundedCornerShape(50)))
                Box(Modifier.width(2.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
            val selection=if(action.isNotEmpty()) Modifier.clickable(enabled=enabled,role=Role.Button) {event(action,null)} else Modifier
            Column(selection.weight(1f).heightIn(min=48.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(ToolUiCatalog.display(node,state),style=MaterialTheme.typography.titleMedium)
                if(node.has("detail") || node.has("detailBind")) Text(if(node.has("detailBind")) ToolUiCatalog.value(state,node.getString("detailBind")).toString() else node.getString("detail"),color=toolInk("muted"))
            }
        }
        "fact" -> BoxWithConstraints(modifier) {
            val value=if(binding.isNotEmpty()) ToolUiCatalog.boundText(node,state) else ""
            if(maxWidth < 220.dp) Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(text,style=MaterialTheme.typography.labelLarge,color=toolInk("muted"))
                Text(value,style=MaterialTheme.typography.bodyLarge)
            } else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(text,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=toolInk("muted"))
                Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,textAlign=TextAlign.End)
            }
        }
        "banner","emptyState" -> {
            val empty = type == "emptyState"
            val neutral = empty && tone in setOf("", "default", "muted")
            val explicitIcon = (0 until (children?.length() ?: 0)).any { index ->
                val child = checkNotNull(children).getJSONObject(index)
                child.getString("type") == "icon" && !child.has("action") && ToolUiCatalog.visible(child, state)
            }
            val ownIcon = node.optString("icon")
            val message: @Composable (Modifier) -> Unit = { box ->
                Column(box.padding(horizontal = if (neutral) 0.dp else 16.dp, vertical = if (empty) 20.dp else 16.dp),
                    horizontalAlignment = if (empty) Alignment.CenterHorizontally else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(gap)) {
                    if (ownIcon.isNotEmpty()) IconBadge(ownIcon, if (neutral) MaterialTheme.colorScheme.primary else LocalContentColor.current, if (empty) 64.dp else 40.dp)
                    if (text.isNotBlank()) {
                        val titleStyle = MaterialTheme.typography.titleMedium.copy(textAlign = if (empty) TextAlign.Center else TextAlign.Start)
                        ToolStatusText(text, if (explicitIcon || ownIcon.isNotEmpty()) "" else tone, titleStyle, Modifier.semantics { heading() })
                    }
                    content()
                }
            }
            if (neutral) message(modifier)
            else ToolRoleSurface(modifier, MaterialTheme.colorScheme.toolRole(tone)) { message(Modifier.fillMaxWidth()) }
        }
        "section" -> Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = alignment(style)) {
            if (showsToolHeading(text, heading)) Text(text, Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge, color = toolInk(tone))
            content()
        }
        "card" -> {
          val hero = node.optString("variant") == "hero"
          val centered = style?.optString("align") == "center"
          val scheme = MaterialTheme.colorScheme
          // A hero card holds the main result in the palette's secondary container. The primary container is neon in some palettes.
          ToolRoleSurface(modifier, if (hero) ToolRole(scheme.secondary, scheme.secondaryContainer, scheme.onSecondaryContainer) else scheme.toolRole(tone)) {
           // Text in a centered card is centered too, so it lines up with a centered emoji or icon.
           CompositionLocalProvider(LocalCenteredContent provides (centered || LocalCenteredContent.current)) {
            Column(Modifier.padding(space(style?.optString("padding"), if (hero) 20 else 16)), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = alignment(style)) {
                val icon = node.optString("icon")
                val title = showsToolHeading(text, heading)
                if (icon.isNotEmpty() || title) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (icon.isNotEmpty()) IconBadge(icon, if (tone in setOf("", "default", "muted")) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    if (title) Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                }
                val beside = if (hero && !centered && children != null) heroEmojiSide(children) else 0
                if (beside > 1) BoxWithConstraints {
                    // A large emoji stands at the end of the texts that follow it. Below 300 dp it stays above them.
                    val wide = maxWidth >= 300.dp
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        if (!wide) content() else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) { renderChildren(1 until beside) }
                                renderChildren(0..0)
                            }
                            renderChildren(beside until checkNotNull(children).length())
                        }
                    }
                } else content()
            }
           }
          }
        }
        "listItem" -> Column(modifier) {
            val click = if (action.isNotEmpty()) Modifier.clickable(enabled = enabled, role = Role.Button) { event(action, null) } else Modifier
            Row(click.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                node.optString("icon").takeIf { it.isNotEmpty() }?.let { IconBadge(it, MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = alignment(style)) {
                    if (showsToolHeading(text, heading)) Text(text, style = MaterialTheme.typography.titleMedium, color = toolInk(tone))
                    content()
                }
            }
            HorizontalDivider()
        }
        "spacer" -> Spacer(modifier.size(node.optInt("size",12).dp))
        "text","metric","badge" -> {
            val label=ToolUiCatalog.display(node,state)
            val align=textAlignOf(style)
            if(type == "badge") {
                val role = MaterialTheme.colorScheme.toolRole(tone)
                Surface(modifier,shape=MaterialTheme.shapes.small,color=role.background,contentColor=role.foreground) {
                    val icon = node.optString("icon")
                    if (icon.isEmpty()) ToolStatusText(label, tone, MaterialTheme.typography.labelLarge.copy(textAlign = align), Modifier.padding(horizontal=10.dp,vertical=6.dp))
                    // An own icon replaces the tone glyph.
                    else Row(Modifier.padding(horizontal=10.dp,vertical=6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolGlyph(icon, Modifier.size(16.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge.copy(textAlign = align))
                    }
                }
            } else if(type == "metric" && node.has("bind")) Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp),
                horizontalAlignment = if (LocalCenteredContent.current && style?.has("align") != true) Alignment.CenterHorizontally else alignment(style)) {
                val icon = node.optString("icon")
                if (icon.isNotEmpty() || showsToolHeading(text, heading)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (icon.isNotEmpty()) ToolGlyph(icon, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                    if (showsToolHeading(text, heading)) Text(text,style=MaterialTheme.typography.labelLarge,color=toolInk("muted"))
                }
                val value=ToolUiCatalog.boundText(node,state)
                // A short value, such as 56.7–76.3 or 10,000 steps, shrinks to fit one line instead of breaking between a number and its unit.
                // At a large font scale it can break between words. The line height follows the shrunk font, so no empty space stays.
                val compact = value.length <= COMPACT_METRIC_LENGTH
                val base=metricStyle(style)
                val metric=if (compact && base.lineHeight.isSp && base.fontSize.isSp) base.copy(lineHeight = (base.lineHeight.value / base.fontSize.value).em) else base
                Text(value,style=metric,color=toolInk(tone),textAlign=align,maxLines=if(compact) 2 else Int.MAX_VALUE,
                    overflow=TextOverflow.Ellipsis,autoSize=if(compact) WholeWordAutoSize(12.sp,base.fontSize,oneLineFirst=true) else null)
            } else if(node.has("bind") || showsToolHeading(text, heading)) {
                if(type == "text") NativeReadableText(node,label,enabled,modifier,event)
                else Text(label,modifier,style=textStyle(style),color=toolInk(style?.optString("tone")),textAlign=align)
            }
        }
        "calendar" -> {
            if(node.getString("capability")=="calendar.deleteAllOwned") {
                val snapshot by produceState<OwnedCalendarSnapshot?>(null,calendarHost,imageVersion) {value=null;value=calendarHost?.snapshot()}
                Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tool_calendar_delete_all_title),style=MaterialTheme.typography.titleSmall)
                    if(snapshot==null)Text(stringResource(R.string.tool_calendar_open_snapshot))
                    snapshot?.let {selected ->
                        Text(stringResource(R.string.tool_calendar_snapshot, selected.entries.size))
                        if(selected.entries.isNotEmpty())Column(Modifier.fillMaxWidth().heightIn(max=240.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            selected.entries.forEach {entry ->
                                val args=JSONObject(entry.details.toString()).put("version",1).put("eventRef",entry.eventRef)
                                Text(com.alpha.assistant.capabilities.OwnedCalendarContract.label(com.alpha.assistant.capabilities.CapabilityRequest("selected","calendar.deleteOwned",args)))
                            }
                        }
                        Button(onClick={calendarHost?.deleteAll(action,selected)},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                            Text(stringResource(R.string.tool_calendar_delete_all, selected.entries.size))
                        }
                    }
                }
            } else Button(onClick={event(action,null)},enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {
                Text(com.alpha.assistant.capabilities.OwnedCalendarContract.label(com.alpha.assistant.capabilities.OwnedCalendarContract.request(node)))
            }
        }
        "medicine" -> Button(onClick={event(action,null)},enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {
            Text(com.alpha.assistant.capabilities.MedicineContract.label(com.alpha.assistant.capabilities.MedicineContract.request(node)))
        }
        "navigation" -> Button(onClick={event(action,null)},enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {
            Text(com.alpha.assistant.capabilities.NavigationContract.label(com.alpha.assistant.capabilities.NavigationContract.request(node)))
        }
        "button" -> {
            val label: @Composable () -> Unit = {
                if(node.has("icon")) {ToolGlyph(node.getString("icon"));Spacer(Modifier.width(8.dp))}
                // A narrow button shrinks its label, so a word such as "Down" does not break.
                Text(text,textAlign=TextAlign.Center,autoSize=WholeWordAutoSize(10.sp,LocalTextStyle.current.fontSize))
            }
            val click={event(action,null)}
            val buttonModifier=modifier.heightIn(min=48.dp)
            val fallback = if (secondaryButton) "outlined" else "filled"
            when(node.optString("variant", fallback)) {
                "outlined" -> OutlinedButton(click,buttonModifier,enabled=enabled) {label()}
                "text" -> TextButton(click,buttonModifier,enabled=enabled) {label()}
                "tonal" -> FilledTonalButton(click,buttonModifier,enabled=enabled) {label()}
                "elevated" -> ElevatedButton(click,buttonModifier,enabled=enabled) {label()}
                "fab","extendedFab" -> if(enabled) {
                    if(node.getString("variant")=="fab") FloatingActionButton(click,modifier.semantics {contentDescription=text}) {ToolGlyph(node.getString("icon"))}
                    else ExtendedFloatingActionButton(click,buttonModifier) {label()}
                } else FilledTonalButton(click,buttonModifier,enabled=false) {label()}
                else -> Button(click,buttonModifier,enabled=enabled) {label()}
            }
        }
        "actionChip" -> if(node.optString("variant")=="suggestion") SuggestionChip(onClick={event(action,null)},label={Text(text)},enabled=enabled,modifier=modifier.heightIn(min=48.dp))
            else AssistChip(onClick={event(action,null)},label={Text(text)},enabled=enabled,modifier=modifier.heightIn(min=48.dp))
        "icon" -> {
            val icon: @Composable () -> Unit = {ToolGlyph(node.getString("name"),Modifier.semantics {contentDescription=text},
                if(style?.has("tone") == true) toolInk(style.optString("tone")) else MaterialTheme.colorScheme.onSurfaceVariant)}
            if(action.isNotEmpty()) IconButton(onClick={event(action,null)},enabled=enabled,modifier=modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) {icon()}
            else Box(modifier) {icon()}
        }
        "toggle","checkbox" -> if(node.optString("variant") == "circle") {
            val checked=ToolUiCatalog.value(state,binding) as Boolean
            val scheme=MaterialTheme.colorScheme
            // A day of a week row: the label above a round check, as in habit trackers.
            Column(modifier.widthIn(min=48.dp).toggleable(checked,enabled=enabled,role=Role.Checkbox) {event(action,it)}.semantics {contentDescription=text},
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(text,Modifier.clearAndSetSemantics {},style=MaterialTheme.typography.labelMedium,maxLines=1)
                Box(Modifier.size(36.dp).clip(CircleShape).background(if(checked) scheme.primary else Color.Transparent)
                    .border(2.dp,if(checked) scheme.primary else scheme.outline,CircleShape),contentAlignment=Alignment.Center) {
                    if(checked) AlphaIcon(Glyph.Check,Modifier.size(20.dp),color=scheme.onPrimary)
                }
            }
        } else Row(modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            val checked=ToolUiCatalog.value(state,binding) as Boolean
            val label=Modifier.semantics {contentDescription=text}
            if(type == "toggle") Switch(checked,{event(action,it)},label,enabled=enabled)
            else Checkbox(checked,{event(action,it)},label,enabled=enabled)
            // The label wraps inside the free width but does not fill it, so a row keeps width for its other children.
            Text(text,Modifier.padding(start=10.dp).weight(1f, fill = false))
        }
        "chip" -> {
            val checked=ToolUiCatalog.value(state,binding) as Boolean
            val removeLabel = miniAppString(R.string.tool_generated_remove, text)
            val dismiss: (@Composable () -> Unit)?=node.optString("dismissAction").takeIf {it.isNotEmpty()}?.let {id -> {IconButton(onClick={event(id,null)},enabled=enabled,modifier=Modifier.size(48.dp)) {AlphaIcon(Glyph.Close,Modifier.semantics {contentDescription=removeLabel})}}}
            val leading: (@Composable () -> Unit)? = node.optString("icon").takeIf { it.isNotEmpty() }?.let { icon -> { ToolGlyph(icon, Modifier.size(18.dp)) } }
            if(node.optString("variant")=="input") InputChip(selected=checked,onClick={event(action,!checked)},label={Text(text)},leadingIcon=leading,trailingIcon=dismiss,enabled=enabled,modifier=modifier.heightIn(min=48.dp))
            else FilterChip(selected=checked,onClick={event(action,!checked)},label={Text(text)},leadingIcon=leading,trailingIcon=dismiss,enabled=enabled,modifier=modifier.heightIn(min=48.dp))
        }
        "rangeSlider" -> {
            val original=ToolUiCatalog.value(state,binding) as JSONArray;val (low,high)=ToolUiCatalog.bounds(node)
            var range by remember(original.toString(),binding) {mutableStateOf(original.getDouble(0)..original.getDouble(1))}
            Column(modifier) {
                Text("$text: ${BigDecimal.valueOf(range.start).stripTrailingZeros().toPlainString()} – ${BigDecimal.valueOf(range.endInclusive).stripTrailingZeros().toPlainString()}")
                SliderValueLabels(listOf(range.start, range.endInclusive), low, high)
                RangeSlider(value=((range.start-low)/(high-low)).toFloat().coerceIn(0f,1f)..((range.endInclusive-low)/(high-low)).toFloat().coerceIn(0f,1f),
                    onValueChange={range=nativeSliderValue(node,it.start)..nativeSliderValue(node,it.endInclusive)},enabled=enabled,modifier=Modifier.semantics {contentDescription=text})
                if (range.start!=original.getDouble(0) || range.endInclusive!=original.getDouble(1))
                    TextButton(onClick={event(action,JSONArray().put(range.start).put(range.endInclusive))},
                        modifier=Modifier.heightIn(min=48.dp),enabled=enabled) {Text(miniAppString(R.string.tool_generated_apply, text))}
            }
        }
        "dateRangeInput" -> NativeDateRange(node,state,enabled,modifier,event)
        "singleChoice","multiChoice","segmented","navigationBar","navigationRail","menu" -> NativeChoices(node,state,enabled,modifier,namedByTitle,event)
        "input","numberInput","search" -> NativeInput(node,state,enabled,modifier,event)
        "timeInput","dateInput" -> {
            val context=LocalContext.current
            val compactTimePicker=LocalConfiguration.current.screenHeightDp in 1..399
            val current=ToolUiCatalog.value(state,binding)
            val label=if(type == "timeInput") (current as Number).toInt().let {"%02d:%02d".format(java.util.Locale.ROOT,it/60,it%60)} else current.toString()
            OutlinedButton(onClick={
                if(type == "timeInput") {
                    val minutes=(current as Number).toInt()
                    val theme=if(compactTimePicker) R.style.Theme_Alpha_CompactTimePicker else 0
                    val picker=TimePickerDialog(context,theme,{_,hour,minute -> event(action,hour*60+minute)},
                        minutes/60,minutes%60,android.text.format.DateFormat.is24HourFormat(context))
                    picker.show()
                    if(compactTimePicker) picker.window?.decorView?.allViews?.filterIsInstance<NumberPicker>()?.forEach { numberPicker ->
                        // Android reapplies the fade on layout. It lowers the contrast of enabled values.
                        numberPicker.isVerticalFadingEdgeEnabled = false
                        numberPicker.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                            view.isVerticalFadingEdgeEnabled = false
                        }
                    }
                } else {
                    val date=LocalDate.parse(current.toString())
                    DatePickerDialog(context,{_,year,month,day -> event(action,LocalDate.of(year,month+1,day).toString())},date.year,date.monthValue-1,date.dayOfMonth).show()
                }
            },enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {NativeButtonIcon(node); Text("$text: $label")}
        }
        "slider" -> {
            val (low,high)=ToolUiCatalog.bounds(node)
            val original=(ToolUiCatalog.value(state,binding) as Number).toDouble()
            val editor = if (ToolUiCatalog.isLiveInput(node)) liveEditor(node, enabled) else null
            var draft by remember(original,binding) {mutableDoubleStateOf(original)}
            val value = (editor?.shown(original) as? Number)?.toDouble() ?: draft
            Column(modifier) {
                Text("$text: ${BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()}")
                SliderValueLabels(listOf(value), low, high)
                Slider(value=((value-low)/(high-low)).toFloat().coerceIn(0f,1f),onValueChange={ fraction ->
                    val next = nativeSliderValue(node,fraction)
                    if (editor == null) draft = next else editor.change(next)
                },onValueChangeFinished=editor?.takeIf { it.automatic }?.let { live -> { live.finish(explicit = false) } },
                    enabled=editor?.canEdit ?: enabled,modifier=Modifier.semantics {contentDescription=text})
                if (editor == null) {
                    if (value != original) TextButton(onClick={event(action,value)},modifier=Modifier.heightIn(min=48.dp),enabled=enabled) {
                        Text(miniAppString(R.string.tool_generated_apply, text))
                    }
                } else NativeLiveActions(editor, text, finishButton = false)
            }
        }
        "select","tabs" -> {
            val options=node.getJSONArray("options"); val selected=ToolUiCatalog.value(state,binding)
            // A tab row shows its options instead of its name, and so does a select that the title above names.
            // The name stays for screen readers.
            val unlabeled=type == "tabs" || namedByTitle
            Column(if(unlabeled) modifier.semantics {contentDescription=text} else modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(!unlabeled) Text(text,style=MaterialTheme.typography.labelLarge)
                if(type == "tabs") ScrollableTabRow(
                    selectedTabIndex=(0 until options.length()).first {scalarEqual(selected,options.getJSONObject(it).get("value"))},
                    modifier=Modifier.fillMaxWidth(),edgePadding=0.dp) {
                    repeat(options.length()) {index -> val option=options.getJSONObject(index); val value=option.get("value")
                        Tab(selected=scalarEqual(selected,value),onClick={event(action,value)},text={Text(option.getString("text"))},enabled=enabled,
                            modifier=Modifier.heightIn(min=48.dp).semantics(mergeDescendants=true) {contentDescription=option.getString("text");role=Role.Tab;this.selected=scalarEqual(selected,value)})
                    }
                } else {
                    var expanded by remember {mutableStateOf(false)}
                    Box {
                        OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.heightIn(min=48.dp)) {
                            val option=(0 until options.length()).map {options.getJSONObject(it)}.first {scalarEqual(it.get("value"),selected)}
                            NativeButtonIcon(node)
                            Text(option.getString("text"))
                        }
                        DropdownMenu(expanded && enabled,onDismissRequest={expanded=false}) {
                            repeat(options.length()) {index -> val option=options.getJSONObject(index)
                                DropdownMenuItem(text={Text(option.getString("text"))},onClick={expanded=false; event(action,option.get("value"))},
                                    modifier=Modifier.heightIn(min=48.dp))
                            }
                        }
                    }
                }
            }
        }
        "list","table","groupedList","lazyList","lazyGrid" -> NativeRecords(node,state,enabled,modifier,heading,namedByTitle,event=event) {template,row,dispatch ->
            NativeNode(template,row,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=dispatch)
        }
        "progress","progressRing" -> {
            val (low,high)=ToolUiCatalog.bounds(node)
            val progress=if(binding.isNotEmpty()) (((ToolUiCatalog.value(state,binding) as Number).toDouble()-low)/(high-low)).toFloat().coerceIn(0f,1f) else 0f
            val amount=if(binding.isNotEmpty()) ToolUiCatalog.boundText(node,state) else ""
            if(ToolUiCatalog.holdsChildren(node)) Column(modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                CompositionLocalProvider(LocalCenteredContent provides true) {
                    if(type == "progressRing") NativeRing(progress,text,amount,content) else NativeLevel(progress,text,amount,content)
                }
                // The frame already announces the label, so the caption is visible only.
                if(showsToolHeading(text, heading)) Text(text,Modifier.clearAndSetSemantics {},style=MaterialTheme.typography.titleMedium,color=toolInk("muted"))
            } else if(node.optString("variant") == "dots") Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(ToolUiCatalog.display(node,state))
                val filled=(ToolUiCatalog.value(state,binding) as Number).toInt()
                // A dot is 20 dp. In a narrow row, all dots shrink by the same amount, so every step stays visible.
                Row(Modifier.fillMaxWidth().semantics {contentDescription=text; stateDescription=amount},horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    repeat(high.toInt()) {index ->
                        Box(Modifier.weight(1f, fill = false).widthIn(max = 20.dp).aspectRatio(1f).clip(CircleShape)
                            .background(if(index < filled) MaterialTheme.colorScheme.primary else progressTrack(MaterialTheme.colorScheme.surfaceContainerHighest)))
                    }
                }
            } else Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(ToolUiCatalog.display(node,state))
                if(node.optBoolean("indeterminate")) {
                    if(type == "progress") LinearProgressIndicator(modifier=Modifier.fillMaxWidth().height(10.dp).semantics {contentDescription=text},
                        trackColor=progressTrack(ProgressIndicatorDefaults.linearTrackColor))
                    else CircularProgressIndicator(modifier=Modifier.size(56.dp).semantics {contentDescription=text})
                } else if(type == "progress") LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(10.dp).semantics {contentDescription=text},
                    trackColor=progressTrack(ProgressIndicatorDefaults.linearTrackColor))
                else CircularProgressIndicator(progress={progress},modifier=Modifier.size(56.dp).semantics {contentDescription=text},
                    trackColor=progressTrack(ProgressIndicatorDefaults.circularDeterminateTrackColor))
            }
        }
        "timer" -> if (node.has("foreground")) {
            val timer = remember(node) { com.alpha.assistant.timer.ForegroundTimerSpec.parse(node) }
            com.alpha.assistant.timer.ForegroundTimerUi(text, timer, enabled, LocalForegroundTimer.current, modifier)
        } else NativeTimer(node, state, modifier)
        "chart" -> NativeChart(node,state,modifier,heading)
        "video" -> NativeVideo(node, state, enabled, modifier)
        "image" -> {
            val handle = when {
                node.has("assetSlot") -> "slot:" + node.getString("assetSlot")
                binding.isNotEmpty() -> ToolUiCatalog.value(state,binding).toString()
                else -> node.getString("resourceId")
            }
            val loader by rememberUpdatedState(imageLoader)
            val available by rememberUpdatedState(imageAvailable)
            val lifecycle=LocalLifecycleOwner.current.lifecycle
            key(handle,imageVersion,lifecycle) {
                val image by produceState<Bitmap?>(null) {
                    observeNativeImage(lifecycle,available={available(handle)},load={loader(handle)},display={value=it})
                }
                if(image == null) Surface(modifier.height(160.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment=Alignment.Center) {Text(miniAppString(R.string.tool_image_unavailable, text),Modifier.padding(16.dp))}
                } else Image(checkNotNull(image).asImageBitmap(),text,modifier.heightIn(min=80.dp,max=280.dp),contentScale=if(node.optString("fit") == "crop") ContentScale.Crop else ContentScale.Fit)
            }
        }
        "emoji" -> {
            val value=if(binding.isNotEmpty()) ToolUiCatalog.value(state,binding).toString() else node.optString("value")
            // An empty value, or one that the fonts cannot draw as one glyph, leaves the tile empty instead of drawing a box.
            val glyph=remember(value) { value.takeIf { it.isNotEmpty() && android.graphics.Paint().hasGlyph(it) } }
            val size=node.optString("size")
            val tile=when(size) {"small" -> 40.dp; "large" -> 112.dp; else -> 64.dp}
            // A large emoji is an illustration. Smaller ones, and a large one with nothing to draw, sit on a tile like an icon badge.
            val back=if(size == "large" && glyph != null) Modifier else Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            Box(modifier.size(tile).then(back).clearAndSetSemantics {contentDescription=text},contentAlignment=Alignment.Center) {
                if(glyph != null) Text(glyph,fontSize=with(LocalDensity.current) {(tile*0.6f).toSp()},maxLines=1)
            }
        }
        "divider" -> HorizontalDivider(modifier)
        "skeleton","spinner" -> {
            val loading = text.ifEmpty { stringResource(R.string.tool_loading) }
            if(type == "skeleton") Box(modifier.height(24.dp).background(MaterialTheme.colorScheme.surfaceVariant,MaterialTheme.shapes.small).semantics {contentDescription=loading})
            else Box(modifier) {CircularProgressIndicator(modifier=Modifier.size(32.dp).semantics {contentDescription=loading})}
        }
        "snackbar" -> Snackbar(
            modifier = modifier,
            actionOnNewLine = action.isNotEmpty(),
            action = if (action.isNotEmpty()) {{
                TextButton(
                    onClick = { event(action, null) },
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionContentColor),
                ) { Text(stringResource(R.string.tool_dismiss)) }
            }} else null,
        ) { Text(text) }
    }
}

@Composable private fun NativeVideo(node: JSONObject, state: JSONObject, enabled: Boolean, modifier: Modifier) {
    val title = node.getString("text")
    val video = LocalVideoUi.current
    val handle = ToolUiCatalog.value(state, node.getString("bind")) as String
    val action = node.getString("action")
    val canPlay = remember(node, state, video, enabled) {
        enabled && video != null && runCatching { ToolUiCatalog.validateVideoAction(video.root, action, handle, state) }.isSuccess
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (video == null) {
            Text(stringResource(R.string.tool_video_open_app), style = MaterialTheme.typography.bodySmall)
        } else if (!enabled) {
            Text(stringResource(R.string.tool_video_unavailable), style = MaterialTheme.typography.bodySmall)
        } else if (handle.isEmpty()) {
            Text(stringResource(R.string.tool_video_no_selection), style = MaterialTheme.typography.bodySmall)
        } else if (canPlay) {
            val host = video.host
            val control = remember(host, action, handle) { NativeVideoControl(action, handle) }
            key(control) {
                var surfaceLease by remember { mutableStateOf<NativeVideoSurfaceLease?>(null) }
                var inWindow by remember { mutableStateOf(false) }
                val surfaceHost = remember {
                    object : NativeVideoSurfaceHost {
                        override fun onSurfaceAvailable(lease: NativeVideoSurfaceLease) {
                            surfaceLease = lease
                            host.onSurfaceAvailable(control, lease)
                        }
                        override fun onSurfaceLost(lease: NativeVideoSurfaceLease) {
                            surfaceLease = null
                            host.onSurfaceLost(control, lease)
                        }
                    }
                }
                val active = inWindow && LocalMiniAppLiveAllowed.current
                val status = surfaceLease?.let { host.status(control, it) } ?: NativeVideoStatus.Preparing
                NativeVideoSurface(surfaceHost, active, status,
                    Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 240.dp).aspectRatio(16f / 9f)
                        .onGloballyPositioned { inWindow = !it.boundsInWindow().isEmpty })
                val ready = status == NativeVideoStatus.Idle || status == NativeVideoStatus.Failed
                Button(onClick = {
                    val current = surfaceLease
                    if (active && current?.isCurrent == true && video.allowAction()) host.play(control, current)
                }, enabled = active && ready && surfaceLease != null, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Text(stringResource(R.string.tool_video_play, title))
                }
            }
        }
    }
}

@Composable private fun NativeTimer(node: JSONObject, state: JSONObject, modifier: Modifier) {
    val timer = remember(node, state) { ToolUiCatalog.timerBinding(node, state) }
    val clock = checkNotNull(LocalTimerClock.current)
    var inWindow by remember { mutableStateOf(false) }
    val active = inWindow && LocalMiniAppLiveAllowed.current && timer != null && timer.timestampMillis != 0L
    DisposableEffect(clock, active) {
        if (active) clock.visibleTimers++
        onDispose { if (active) clock.visibleTimers-- }
    }
    var lastShownMillis by remember(timer) { mutableLongStateOf(System.currentTimeMillis()) }
    val now = if (active) clock.nowMillis else lastShownMillis
    SideEffect { lastShownMillis = now }
    val seconds = timer?.secondsAt(now)
    val duration = seconds?.let {
        val days = it / 86_400
        val hours = it / 3600 % 24
        val minutes = it / 60 % 60
        val remainder = it % 60
        if (days > 0) miniAppString(R.string.tool_timer_clock_days, days, hours, minutes, remainder)
            else stringResource(R.string.tool_timer_clock, hours, minutes, remainder)
    }
    val note = when {
        timer == null -> miniAppString(R.string.tool_timer_unavailable)
        seconds == null -> miniAppString(R.string.tool_timer_idle)
        timer.countdown && seconds == 0L -> miniAppString(R.string.tool_timer_expired)
        else -> null
    }
    val status = note ?: miniAppString(if (checkNotNull(timer).countdown) R.string.tool_timer_remaining else R.string.tool_timer_elapsed,
        checkNotNull(duration))
    val style = node.optJSONObject("style")
    TimerDisplay(node.getString("text"), duration, note, stringResource(R.string.tool_timer_value, node.getString("text"), status),
        modifier.onGloballyPositioned { inWindow = !it.boundsInWindow().isEmpty }, style?.optString("tone"), alignment(style))
}

/** A timer shows its label, a large clock, and a short status. Assistive technology reads the whole state as one sentence. */
@Composable internal fun TimerDisplay(label: String, clock: String?, status: String?, spoken: String, modifier: Modifier = Modifier,
    tone: String? = null, align: Alignment.Horizontal = Alignment.Start) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = align) {
        if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelLarge, color = toolInk("muted"))
        if (clock != null) Text(clock, style = metricStyle(null), color = toolInk(tone), maxLines = 1)
        if (status != null) Text(status, style = MaterialTheme.typography.bodyMedium, color = toolInk("muted"))
    }
}

/** Cache pixels only while resumed. Access metadata is checked each second without decoding again. */
internal suspend fun observeNativeImage(lifecycle: Lifecycle, available: suspend () -> Boolean,
    load: suspend () -> Bitmap?, display: (Bitmap?) -> Unit) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        var image: Bitmap?=null
        var attempted=false
        display(null)
        try {
            while(true) {
                if(!available()) {image=null; attempted=false; display(null)}
                else if(!attempted) {
                    attempted=true
                    val decoded=load()
                    currentCoroutineContext().ensureActive()
                    val stillAvailable=available()
                    attempted=stillAvailable
                    image=if(stillAvailable) decoded else null
                    display(image)
                }
                delay(1000)
            }
        } finally {display(null)}
    }
}
/** Native sliders use unit fractions. Keep declared endpoints and snapped state in Double precision. */
internal fun nativeSliderValue(node:JSONObject,fraction:Float):Double {
    val (low,high)=ToolUiCatalog.bounds(node)
    if(fraction <= 0f)return low
    if(fraction >= 1f)return high
    val proposed=low+fraction.toDouble()*(high-low)
    return (if(node.has("step")) low+round((proposed-low)/node.getDouble("step"))*node.getDouble("step") else proposed).coerceIn(low,high)
}

internal fun nativeNumberStep(node: JSONObject, current: Double?, increase: Boolean): Double? {
    if (!node.has("step") || current == null) return null
    val (low, high) = ToolUiCatalog.bounds(node)
    if (current !in low..high) return null
    val step = node.getDouble("step")
    // The validated values fit in a Double. Decimal arithmetic avoids drift between steps.
    val delta = BigDecimal.valueOf(if (increase) step else -step)
    val adjusted = BigDecimal.valueOf(current).add(delta)
        .coerceIn(BigDecimal.valueOf(low), BigDecimal.valueOf(high)).toDouble()
    return adjusted.takeIf { it != current }
}

private fun scalarEqual(a: Any,b: Any) = if(a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

private class NativeLiveEditor(private val ui: NativeLiveUi, val draft: NativeLiveDraft,
    private val action: String, private val row: String?, private val enabled: Boolean) {
    val canEdit get() = enabled && ui.access.available && !draft.finishing
    val automatic get() = ui.adapter?.channel != null
    val unavailable get() = ui.adapter == null || !ui.adapter.active
    fun shown(original: Any) = draft.shown(original, ui.adapter?.record?.stateVersion)
    fun change(value: Any) {
        if (canEdit) draft.edit(value, action, row, ui, finish = false)
    }
    fun finish(explicit: Boolean) {
        if (canEdit && (draft.open || explicit && draft.status == NativeLiveStatus.Unsaved)) {
            draft.value?.let { draft.edit(it, action, row, ui, finish = true) }
        }
    }
}

@Composable private fun liveEditor(node: JSONObject, enabled: Boolean): NativeLiveEditor {
    val ui = checkNotNull(LocalLiveUi.current)
    val row = LocalLiveRow.current
    val action = node.getString("action")
    val scope = rememberCoroutineScope()
    val draft = remember(ui.adapter?.identity, action, row, node.getString("bind")) { NativeLiveDraft(ui.edits, scope) }
    DisposableEffect(draft, enabled) {
        onDispose { if (draft.open) ui.edits.interrupt(ui.adapter?.channel) }
    }
    return NativeLiveEditor(ui, draft, action, row, enabled)
}

@Composable private fun NativeLiveMessage(resource: Int) {
    Text(miniAppString(resource), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = toolInk(if (resource == R.string.tool_live_input_pending) "muted" else "warning"),
        style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun NativeLiveActions(editor: NativeLiveEditor, label: String, finishButton: Boolean) {
    when (editor.draft.status) {
        NativeLiveStatus.Pending -> NativeLiveMessage(R.string.tool_live_input_pending)
        NativeLiveStatus.Unsaved -> NativeLiveMessage(R.string.tool_live_input_unsaved)
        null -> if (editor.unavailable) NativeLiveMessage(R.string.tool_live_input_unavailable)
    }
    val apply = !editor.automatic || editor.draft.status == NativeLiveStatus.Unsaved
    if (apply || finishButton && editor.draft.open) {
        TextButton(onClick = { editor.finish(explicit = true) }, modifier = Modifier.heightIn(min = 48.dp),
            enabled = editor.canEdit && (editor.draft.open || editor.draft.status == NativeLiveStatus.Unsaved)) {
            Text(miniAppString(if (apply) R.string.tool_generated_apply else R.string.tool_live_input_finish, label))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NativeInput(node: JSONObject, state: JSONObject, enabled: Boolean, modifier: Modifier, event: (String,Any?) -> Unit) {
    val binding=node.getString("bind"); val original=ToolUiCatalog.value(state,binding)
    val numeric=node.getString("type") == "numberInput"
    val search=node.getString("type") == "search"
    val keyboard=if(numeric) KeyboardType.Decimal else when(node.optString("keyboard")) {"email" -> KeyboardType.Email; "uri" -> KeyboardType.Uri; "phone" -> KeyboardType.Phone; "number" -> KeyboardType.Number; "password" -> KeyboardType.Password; else -> KeyboardType.Text}
    val ime=when(node.optString("imeAction",if(search) "search" else "done")) {"next" -> ImeAction.Next; "search" -> ImeAction.Search; else -> ImeAction.Done}
    val editor = if (ToolUiCatalog.isLiveInput(node)) liveEditor(node, enabled) else null
    var ordinaryDraft by remember(original,binding) {mutableStateOf(original.toString())}
    val draft = editor?.shown(original)?.toString() ?: ordinaryDraft
    val payload=if(numeric) draft.toDoubleOrNull() else draft
    val valid=editor != null || runCatching {ToolUiCatalog.validateAction(node,node.getString("action"),payload,state)}.isSuccess
    val changed=if(numeric) payload != (original as Number).toDouble() else payload != original
    val apply={if(enabled && valid && (changed || search)) event(node.getString("action"),payload)}
    val keyboardActions = if (editor == null) KeyboardActions(onDone = { apply() }, onSearch = { apply() })
        else KeyboardActions(
            onDone = { editor.finish(explicit = true); defaultKeyboardAction(ImeAction.Done) },
            onNext = { editor.finish(explicit = true); defaultKeyboardAction(ImeAction.Next) },
            onSearch = { editor.finish(explicit = true); defaultKeyboardAction(ImeAction.Search) },
        )
    val label = node.getString("text")
    // Apply shows only for an unsaved edit, so a form of unchanged fields has no disabled buttons.
    @Composable fun ApplyDraft() {
        if (!changed && !search) return
        TextButton(onClick = apply, modifier = Modifier.heightIn(min = 48.dp), enabled = enabled && valid && (changed || search)) {
            Text(miniAppString(if (search) R.string.tool_generated_search else R.string.tool_generated_apply, label))
        }
    }
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(draft,{if(it.length <= if(numeric) 40 else node.optInt("maxLength",2048)) {
                if (editor == null) ordinaryDraft = it else editor.change(it)
            }},
            enabled=editor?.canEdit ?: enabled,label={Text(label)},modifier=fieldModifier,
            leadingIcon=node.optString("icon").takeIf { it.isNotEmpty() }?.let { icon -> { ToolGlyph(icon, Modifier.size(20.dp)) } },singleLine=numeric || node.optInt("maxLines",if(search) 1 else 8) == 1,maxLines=if(numeric) 1 else node.optInt("maxLines",if(search) 1 else 8),
            visualTransformation=if(node.optString("keyboard") == "password") PasswordVisualTransformation() else VisualTransformation.None,
            isError=draft.isNotEmpty() && !valid,keyboardOptions=KeyboardOptions(keyboardType=keyboard,imeAction=ime),
            keyboardActions=keyboardActions)
    }
    Column(modifier) {
        if (numeric && node.has("step")) {
            val current = payload as? Double
            @Composable fun StepButton(increase: Boolean) {
                val description = miniAppString(if (increase) R.string.tool_number_increase else R.string.tool_number_decrease, label)
                val next = nativeNumberStep(node, current, increase)
                FilledTonalIconButton(onClick = {
                    nativeNumberStep(node, ordinaryDraft.toDoubleOrNull(), increase)?.let {
                        val adjusted = BigDecimal.valueOf(it).stripTrailingZeros()
                        ordinaryDraft = adjusted.toPlainString().takeIf { value -> value.length <= 40 } ?: adjusted.toString()
                    }
                }, enabled = enabled && next != null,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
                    if (increase) AlphaIcon(Glyph.Plus, color = LocalContentColor.current)
                    else HorizontalDivider(Modifier.width(16.dp), thickness = 2.dp, color = LocalContentColor.current)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // A half-width cell still fits the field and both buttons on one line.
                val inline = maxWidth >= 168.dp && LocalDensity.current.fontScale < 1.5f
                // Leave room for the two 48 dp buttons and the gaps before measuring the field.
                val fieldSize = if (inline) Modifier.width(maxWidth - 110.dp) else Modifier.fillMaxWidth()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    maxItemsInEachRow = if (inline) 3 else Int.MAX_VALUE) {
                    field(fieldSize.align(Alignment.CenterVertically))
                    StepButton(increase = false)
                    StepButton(increase = true)
                    ApplyDraft()
                }
            }
        } else {
            field(Modifier.fillMaxWidth())
            if (editor == null) ApplyDraft() else NativeLiveActions(editor, label, finishButton = true)
        }
    }
}
private data class NativeRecordEntry(val key: String,val group: String? = null,val record: JSONObject? = null,val divider: Boolean = false)
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun NativeRecords(node: JSONObject, state: JSONObject, enabled: Boolean, modifier: Modifier, heading: String?, namedByTitle: Boolean,
    event: (String,Any?) -> Unit, renderTemplate: @Composable (JSONObject,JSONObject,(String,Any?) -> Unit) -> Unit) {
    val rows=ToolUiCatalog.records(node,state); val type=node.getString("type")
    val columns=if(type == "lazyGrid") null else node.optJSONArray("columns"); val action=node.optString("action")
    val selected=if(node.has("selectedKeyBind")) ToolUiCatalog.value(state,node.getString("selectedKeyBind")) as String else ""
    val dividerRows = type != "lazyGrid" && !node.has("rowTemplate")
    val entries=if(type == "table") emptyList() else buildList {
        val groups=(0 until rows.length()).map {rows.getJSONObject(it)}.groupBy {if(node.has("groupBy")) ToolUiCatalog.value(it,node.getString("groupBy")).toString() else ""}
        groups.forEach {(group,records) ->
            if(node.has("groupBy")) add(NativeRecordEntry("group:$group",group=group))
            records.forEachIndexed { index, record ->
                add(NativeRecordEntry("row:${ToolUiCatalog.stableKey(record)}", record = record,
                    divider = dividerRows && index < records.lastIndex))
            }
        }
    }
    val reorder = node.optJSONObject("reorder")
    val reorderTracker = reorder?.let { remember(it.getString("action")) { NativeRecordReorder() } }
    val eligibleKeys = if (reorder == null) emptyList() else entries.mapNotNull { entry ->
        entry.record?.takeIf { ToolUiCatalog.rowReorderAvailable(reorder, it) }?.let(ToolUiCatalog::stableKey)
    }
    val rowContent: @Composable (NativeRecordEntry) -> Unit = {entry ->
        if(entry.group != null) Text(entry.group,Modifier.padding(top=8.dp).semantics {heading()},style=MaterialTheme.typography.titleMedium)
        else entry.record?.let {record ->
            val selection=if(node.has("selectedKeyBind")) Modifier.semantics {this.selected=selected == ToolUiCatalog.stableKey(record)} else Modifier
            Column(Modifier.fillMaxWidth()) {
                Box(selection.fillMaxWidth()) {
                    if (reorder != null && reorderTracker != null && ToolUiCatalog.rowReorderVisible(reorder, record)) {
                        NativeReorderRow(reorder, record, enabled, eligibleKeys, reorderTracker, event) {
                            NativeRecordRow(node, record, columns, enabled, event, renderTemplate)
                        }
                    } else NativeRecordRow(node, record, columns, enabled, event, renderTemplate)
                }
                if (entry.divider) HorizontalDivider()
            }
        }
    }
    val name=node.getString("text")
    // A list named by the title above it keeps its name for screen readers only.
    Column(if(namedByTitle) modifier.semantics {contentDescription=name} else modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(!namedByTitle && showsToolHeading(name, heading)) Text(name,style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics {heading()})
        if(rows.length() == 0) Text(node.optString("emptyText",miniAppString(R.string.tool_records_empty)),
            Modifier.padding(vertical = 12.dp), color = toolInk("muted"))
        if(type == "table") BoxWithConstraints {
            val count=checkNotNull(columns).length()
            // Columns share the width when each keeps 80 dp. A wider table scrolls sideways.
            val cell=if(constraints.hasBoundedWidth && maxWidth/count >= 80.dp) maxWidth/count else 140.dp
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {repeat(count) {Text(columns.getJSONObject(it).getString("text"),Modifier.width(cell).padding(10.dp),style=MaterialTheme.typography.labelLarge)}}
                repeat(rows.length()) {index -> val record=rows.getJSONObject(index)
                    HorizontalDivider()
                    val rowModifier=if(action.isNotEmpty()) Modifier.clickable(enabled=enabled,role=Role.Button) {event(action,ToolUiCatalog.stableKey(record))} else Modifier
                    Row(rowModifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                        repeat(count) {Text(ToolUiCatalog.value(record,columns.getJSONObject(it).getString("field")).toString(),Modifier.width(cell).padding(10.dp))}
                    }
                }
            }
        } else if(type == "lazyList" || type == "lazyGrid") {
            val target=if(node.has("scrollToKeyBind")) ToolUiCatalog.value(state,node.getString("scrollToKeyBind")) as String else ""
            val keys=entries.map {it.key}
            val targetIndex=if(target.isEmpty()) -1 else keys.indexOf("row:$target")
            val viewport=Modifier.fillMaxWidth().height(node.optInt("viewportHeight",360).dp)
            if(type == "lazyList") {
                val scroll=rememberLazyListState()
                LaunchedEffect(target,keys) {if(targetIndex >= 0) scroll.scrollToItem(targetIndex)}
                LazyColumn(viewport,state=scroll,verticalArrangement=Arrangement.spacedBy(if (dividerRows) 0.dp else 8.dp)) {
                    entries.forEach {entry ->
                        if(entry.group != null && node.optBoolean("stickyHeaders")) stickyHeader(key=entry.key) {
                            Surface(Modifier.fillMaxWidth()) {rowContent(entry)}
                        } else item(key=entry.key) {rowContent(entry)}
                    }
                }
            } else BoxWithConstraints(viewport) {
                val count=minOf(node.optInt("columns",2),((maxWidth.value+8)/(node.optInt("minCellWidth",144)+8)).toInt().coerceAtLeast(1))
                val scroll=rememberLazyGridState()
                LaunchedEffect(target,keys) {if(targetIndex >= 0) scroll.scrollToItem(targetIndex)}
                LazyVerticalGrid(columns=GridCells.Fixed(count),state=scroll,horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(entries.size,key={entries[it].key},span={index ->
                        val entry=entries[index]
                        GridItemSpan(if(entry.group != null) maxLineSpan else if(node.has("spanField")) (ToolUiCatalog.value(checkNotNull(entry.record),node.getString("spanField")) as Number).toInt().coerceIn(1,maxLineSpan) else 1)
                    }) {index -> rowContent(entries[index])}
                }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(if (dividerRows) 0.dp else 8.dp)) {
            entries.forEach {entry -> key(entry.key) {rowContent(entry)}}
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NativeRecordRow(node: JSONObject,record: JSONObject,columns: JSONArray?,enabled: Boolean,event: (String,Any?) -> Unit,renderTemplate: @Composable (JSONObject,JSONObject,(String,Any?) -> Unit) -> Unit) {
    val template=node.optJSONObject("rowTemplate")
    if(template != null) {
        val key = ToolUiCatalog.stableKey(record)
        // Each template row is a card on the page, as in phone list apps.
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                CompositionLocalProvider(LocalLiveRow provides key) {
                    renderTemplate(template,record) {id,value -> event(id,JSONObject().put("key",key).put("value",value ?: JSONObject.NULL))}
                }
            }
        }
        return
    }
    val action=node.optString("action")
    val selection=if(action.isNotEmpty()) Modifier.clickable(enabled=enabled,role=Role.Button) {event(action,ToolUiCatalog.stableKey(record))} else Modifier
    val swipe=node.optString("swipeAction")
    val latestEvent by rememberUpdatedState(event)
    val swipeEnabled=enabled && swipe.isNotEmpty() && node.optJSONArray("rowActions")?.let {actions -> (0 until actions.length()).map {actions.getJSONObject(it)}.firstOrNull {it.getString("action") == swipe}?.let {ToolUiCatalog.rowActionEnabled(it,record)}} == true
    val swipeModifier=if(swipeEnabled) Modifier.pointerInput(ToolUiCatalog.stableKey(record),swipe,swipeEnabled) {
        var distance=0f
        detectHorizontalDragGestures(onDragStart={distance=0f},onDragCancel={distance=0f},onDragEnd={if(abs(distance) >= 72.dp.toPx()) latestEvent(swipe,ToolUiCatalog.stableKey(record));distance=0f}) {change,delta -> change.consume();distance+=delta}
    } else Modifier
    val tile = node.getString("type") == "lazyGrid"
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        Column(rowModifier.fillMaxWidth().padding(horizontal = if (tile) 16.dp else 8.dp, vertical = if (tile) 16.dp else 8.dp),
            verticalArrangement=Arrangement.spacedBy(if (tile) 12.dp else 6.dp)) {
            Column(selection.fillMaxWidth().heightIn(min=48.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(columns != null) repeat(columns.length()) {column ->
                    val field=columns.getJSONObject(column)
                    Text(ToolUiCatalog.value(record,field.getString("field")).toString(),
                        style=if(column == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        color=if (column == 0 && tile) MaterialTheme.colorScheme.onSurface
                            else if (column == 0) LocalContentColor.current else toolInk("muted"))
                } else {
                    val fields=if(record.has("key") && record.has("token") && record.has("value")) record.getJSONObject("value") else record
                    Text(fields.keys().asSequence().filter {it != "id"}.joinToString(" · ") {"$it: ${fields.get(it)}"},style=MaterialTheme.typography.bodyLarge)
                }
            }
            node.optJSONArray("rowActions")?.let {actions ->
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    repeat(actions.length()) {index ->
                        val rowAction=actions.getJSONObject(index)
                        val available=enabled && ToolUiCatalog.rowActionEnabled(rowAction,record)
                        val dispatch={event(rowAction.getString("action"),ToolUiCatalog.stableKey(record))}
                        if(rowAction.optString("emphasis") == "primary") Button(onClick=dispatch,enabled=available,modifier=Modifier.heightIn(min=48.dp)) {Text(rowAction.getString("text"))}
                        else OutlinedButton(onClick=dispatch,enabled=available,modifier=Modifier.heightIn(min=48.dp)) {Text(rowAction.getString("text"))}
                    }
                }
            }
        }
    }
    if (tile) {
        val scheme = MaterialTheme.colorScheme
        ToolRoleSurface(swipeModifier.fillMaxWidth(), ToolRole(scheme.onSurfaceVariant, scheme.surfaceVariant, scheme.onSurfaceVariant)) {
            row(Modifier)
        }
    } else row(swipeModifier)
}
@Composable private fun NativeChart(node: JSONObject, state: JSONObject, modifier: Modifier, heading: String?) {
    val categories=ToolUiCatalog.value(state,node.getString("categories")) as JSONArray
    val series=node.getJSONArray("series")
    val values = List(series.length()) { index ->
        ToolUiCatalog.value(state, series.getJSONObject(index).getString("bind")) as JSONArray
    }
    val kind = node.getString("kind")
    val pie = kind == "pie"
    val pieTotal = if (pie) (0 until categories.length()).sumOf { values.single().getDouble(it) } else 0.0
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary)
    val axis=scheme.outlineVariant
    val description=buildString {
        append(node.getString("text")); append(". ")
        values.forEachIndexed { index, points ->
            append(series.getJSONObject(index).getString("text")); append(": ")
            repeat(points.length()) {append(categories.get(it)); append(' '); append(points.get(it)); append("; ")}
        }
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(showsToolHeading(node.getString("text"), heading)) Text(node.getString("text"),style=MaterialTheme.typography.titleMedium)
        if (categories.length() == 0 || pie && pieTotal == 0.0) {
            val empty = miniAppString(R.string.tool_chart_empty)
            Text(empty, if (categories.length() == 0) Modifier else Modifier.semantics { contentDescription = "$empty. $description" })
        } else if (pie) {
            Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
                val diameter = minOf(size.width, size.height)
                val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                val circle = Size(diameter, diameter)
                val points = values.single()
                var cumulative = 0.0
                var start = -90f
                repeat(points.length()) { index ->
                    cumulative += points.getDouble(index)
                    val end = (360.0 * (cumulative / pieTotal) - 90.0).toFloat()
                    if (end > start) drawArc(colors[index % colors.size], start, end - start, useCenter = true, topLeft = topLeft, size = circle)
                    start = end
                }
            }
        } else {
            val range=ToolUiCatalog.chartRange(node,state)
            Text("${range.first} — ${range.second}",style=MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(180.dp).semantics {contentDescription=description}) {
                fun y(value: Double) = ((1.0-(value-range.first)/(range.second-range.first))*size.height).toFloat().coerceIn(0f,size.height)
                val baseline=y(0.0)
                drawLine(axis,Offset(0f,baseline),Offset(size.width,baseline),strokeWidth=1.dp.toPx())
                values.forEachIndexed { s, points ->
                    fun x(index: Int) = if(points.length() == 1) size.width/2 else index.toFloat()/(points.length()-1)*size.width
                    if (kind == "area") {
                        val area = Path().apply {
                            moveTo(0f, baseline)
                            if (points.length() == 1) {
                                val top = y(points.getDouble(0))
                                lineTo(0f, top)
                                lineTo(size.width, top)
                            } else repeat(points.length()) { index -> lineTo(x(index), y(points.getDouble(index))) }
                            lineTo(size.width, baseline)
                            close()
                        }
                        drawPath(area, colors[s], alpha = .28f)
                    }
                    repeat(points.length()) {i ->
                        val current=y(points.getDouble(i))
                        if(kind == "bar") {
                            val group=size.width/points.length(); val width=group/(series.length()+1)
                            val x=i*group+(s+0.5f)*width
                            drawRect(colors[s],Offset(x,minOf(current,baseline)),Size(width*0.85f,maxOf(abs(baseline-current),1f)))
                        } else {
                            val x=x(i)
                            if(i > 0) drawLine(colors[s],Offset(x(i-1),y(points.getDouble(i-1))),Offset(x,current),strokeWidth=2.dp.toPx())
                            drawCircle(colors[s],3.dp.toPx(),Offset(x,current))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(categories.get(0).toString(),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall); if(categories.length()>1) Text(categories.get(categories.length()-1).toString(),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.End)}
            repeat(series.length()) {Text(series.getJSONObject(it).getString("text"),color=colors[it],style=MaterialTheme.typography.labelMedium)}
        }
    }
}
/** The palette of a preset without its own palette. */
internal fun presetPalette(preset: String) = when (preset) {
    "clean" -> "blue"; "soft" -> "teal"; "expressive" -> "purple"; "editorial" -> "brown"; "technical" -> "indigo"; else -> "orange"
}

private fun themeColors(preset: String, palette: String?, dark: Boolean, shellDark: Boolean): ColorScheme {
    val darkColors = dark || shellDark
    // Graphite has no scheme: it keeps the shell's gray colors.
    return ToolPalettes.scheme(palette ?: presetPalette(preset), darkColors)
        ?: if (darkColors) com.alpha.assistant.ui.AlphaDarkColors else com.alpha.assistant.ui.AlphaLightColors
}

/** The colors of an app whose root node is a theme, so the host around the app can use them. Null for other apps. */
internal fun rootThemeColors(ui: String, shellDark: Boolean): ColorScheme? =
    runCatching { JSONObject(ui) }.getOrNull()?.takeIf { it.optString("type") == "theme" && it.has("preset") }?.let {
        themeColors(it.getString("preset"), it.optString("palette").ifEmpty { null }, it.optBoolean("dark"), shellDark)
    }

@Composable private fun NativePreset(preset: String, cornerRadius: Int? = null, typeScale: Double = 1.0, palette: String? = null,
    dark: Boolean = false, content: @Composable () -> Unit) {
    val radius=(cornerRadius ?: when(preset) {"clean" -> 12; "soft" -> 24; "expressive" -> 28; "editorial" -> 4; "technical" -> 0; else -> 32}).dp
    val typography=MaterialTheme.typography
    val colors = themeColors(preset, palette, dark, MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val scale=typeScale.toFloat()
    fun scaled(style: androidx.compose.ui.text.TextStyle) = style.copy(fontSize=style.fontSize*scale,lineHeight=style.lineHeight*scale)
    fun heading(style: androidx.compose.ui.text.TextStyle) =
        scaled(style.copy(fontFamily = if (preset == "editorial") FontFamily.Serif else typography.bodyLarge.fontFamily))
    MaterialTheme(colorScheme=colors,
        shapes=Shapes(RoundedCornerShape(radius),RoundedCornerShape(radius),RoundedCornerShape(radius),RoundedCornerShape(radius),RoundedCornerShape(radius)),
        typography=typography.copy(
            displayLarge=heading(typography.displayLarge),displayMedium=heading(typography.displayMedium),displaySmall=heading(typography.displaySmall),
            headlineLarge=heading(typography.headlineLarge),headlineMedium=heading(typography.headlineMedium),headlineSmall=heading(typography.headlineSmall),
            titleLarge=heading(typography.titleLarge),titleMedium=heading(typography.titleMedium),titleSmall=heading(typography.titleSmall),
            bodyLarge=scaled(typography.bodyLarge),bodyMedium=scaled(typography.bodyMedium),bodySmall=scaled(typography.bodySmall),
            labelLarge=scaled(typography.labelLarge),labelMedium=scaled(typography.labelMedium),labelSmall=scaled(typography.labelSmall),
        )) {
        // The app draws its own background, so its palette shows, not the gray shell card around it.
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(radius), color = colors.background, contentColor = colors.onBackground) {
            CompositionLocalProvider(LocalToolTabularDigits provides (preset == "technical"), LocalToolBackground provides colors.background, content = content)
        }
    }
}

@Composable private fun nativeInteractions(node:JSONObject,enabled:Boolean,event:(String,Any?)->Unit,base:Modifier):Modifier {
    val interactions=ToolUiCatalog.interactionActions(node)
    val latestEvent by rememberUpdatedState(event)
    var focused by remember {mutableStateOf(false)}
    val semantics=node.optJSONObject("semantics")
    val label = node.optString("text", semantics?.optString("label") ?: stringResource(R.string.tool_control))
    val longPress = stringResource(R.string.tool_long_press, label)
    val doubleTap = stringResource(R.string.tool_double_tap, label)
    var result=base.semantics {
        semantics?.let {
            if(it.has("label"))contentDescription=it.getString("label")
            if(it.has("stateDescription"))stateDescription=it.getString("stateDescription")
            if(it.optBoolean("heading"))heading()
            if(it.has("liveRegion"))liveRegion=if(it.getString("liveRegion")=="assertive")LiveRegionMode.Assertive else LiveRegionMode.Polite
        }
        if(interactions.isNotEmpty()) {
            if(!enabled)disabled()
            customActions=listOfNotNull(interactions["longClick"]?.let {id->CustomAccessibilityAction(longPress) {if(enabled)latestEvent(id,null);enabled}},
                interactions["doubleClick"]?.let {id->CustomAccessibilityAction(doubleTap) {if(enabled)latestEvent(id,null);enabled}})
        }
    }
    if(interactions.keys.any {it in setOf("click","longClick","doubleClick")}) result=result.combinedClickable(enabled=enabled,
        onClick={interactions["click"]?.let{latestEvent(it,null)}},onLongClick=interactions["longClick"]?.let{id->{latestEvent(id,null)}},
        onDoubleClick=interactions["doubleClick"]?.let{id->{latestEvent(id,null)}})
    if(interactions.isNotEmpty()) result=result.onFocusChanged {state ->
        if(focused != state.isFocused) {focused=state.isFocused;if(enabled)interactions["focus"]?.let{latestEvent(it,focused)}}
    }.onKeyEvent {key ->
        val name=when(key.key) {Key.Enter,Key.NumPadEnter->"enter";Key.Spacebar->"space";Key.Escape,Key.Back->"escape";Key.DirectionUp->"up";Key.DirectionDown->"down";Key.DirectionLeft->"left";Key.DirectionRight->"right";else->null}
        val id=interactions["key:$name"]
        if(enabled && id != null && key.type==KeyEventType.KeyUp) {latestEvent(id,null);true} else false
    }.focusable(enabled)
    return result
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable private fun NativeChoices(node:JSONObject,state:JSONObject,enabled:Boolean,modifier:Modifier,namedByTitle:Boolean,event:(String,Any?)->Unit) {
    val type=node.getString("type");val options=node.getJSONArray("options");val current=ToolUiCatalog.value(state,node.getString("bind"));val action=node.getString("action")
    val values=(0 until options.length()).map {options.getJSONObject(it)}
    fun selected(value:Any)=if(current is JSONArray)(0 until current.length()).any {scalarEqual(current.get(it),value)} else scalarEqual(current,value)
    fun choose(value:Any) {
        if(type != "multiChoice")event(action,value)
        else {val array=current as JSONArray;val next=JSONArray();repeat(array.length()){if(!scalarEqual(array.get(it),value))next.put(array.get(it))};if(!selected(value))next.put(value);event(action,next)}
    }
    if (ToolUiCatalog.isSceneChoice(node)) {
        NativeSceneChoices(node.getString("text"), values, enabled, modifier, ::selected, ::choose)
        return
    }
    Column(if(namedByTitle) modifier.semantics {contentDescription=node.getString("text")} else modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(!namedByTitle) Text(node.getString("text"),style=MaterialTheme.typography.labelLarge)
        when(type) {
            // The options share the width. A scrolling row looked cut off at the screen edge.
            // All options take the height of the tallest one, so a two-line label does not stand out.
            "segmented" -> {
                val widest = widestLabel(values, MaterialTheme.typography.labelLarge)
                val cellColors = SegmentedButtonDefaults.colors()
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // A selected segment shows its label on one line beside the check (its size and a gap of 8 dp). The segments share the row, and
                    // their borders of 1 dp overlap. When a label does not fit its share, the options are cells that wrap, each label whole (L1 item 4: "Dokumen te").
                    val (check, border) = with(LocalDensity.current) { (SegmentedButtonDefaults.IconSize + 8.dp).roundToPx() to 1.dp.roundToPx() }
                    if (constraints.hasBoundedWidth && values.size * (widest + check) > constraints.maxWidth + (values.size - 1) * border)
                        NativeSceneChoices(null, values, enabled, Modifier, ::selected, ::choose, cellColors.activeContainerColor, cellColors.activeContentColor)
                    else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        values.forEachIndexed {index,option -> SegmentedButton(selected(option.get("value")),{choose(option.get("value"))},
                            SegmentedButtonDefaults.itemShape(index,values.size),modifier=Modifier.weight(1f).fillMaxHeight().sizeIn(minHeight=48.dp),
                            enabled=enabled) {
                                // A narrow option shrinks its label, so a word such as "Quarterly" does not break.
                                Text(option.getString("text"),maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,
                                    autoSize=WholeWordAutoSize(10.sp,LocalTextStyle.current.fontSize))
                            } }
                    }
                }
            }
            "navigationBar" -> NavigationBar(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                values.forEach {option -> NavigationBarItem(selected(option.get("value")),{choose(option.get("value"))},icon={AlphaIcon(Glyph.Grid, color = LocalContentColor.current)},label={Text(option.getString("text"))},enabled=enabled)}
            }
            "navigationRail" -> NavigationRail {
                values.forEach {option -> NavigationRailItem(selected(option.get("value")),{choose(option.get("value"))},icon={AlphaIcon(Glyph.Grid, color = LocalContentColor.current)},label={Text(option.getString("text"))},enabled=enabled)}
            }
            "menu" -> {
                var expanded by remember {mutableStateOf(false)}
                Box {
                    OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.heightIn(min=48.dp)) {
                        Text(values.first {selected(it.get("value"))}.getString("text"))
                    }
                    DropdownMenu(expanded && enabled,{expanded=false}) {
                        values.forEach { option ->
                            DropdownMenuItem(text={Text(option.getString("text"))},onClick={expanded=false;choose(option.get("value"))},
                                modifier=Modifier.heightIn(min=48.dp))
                        }
                    }
                }
            }
            // Five to seven short options, such as weekdays, are one row of circles.
            "multiChoice" -> if (values.size in 5..7 && values.all { it.getString("text").length <= 3 }) Row(Modifier.fillMaxWidth()) {
                values.forEach { option ->
                    val on = selected(option.get("value"))
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).toggleable(on, enabled = enabled, role = Role.Checkbox) { choose(option.get("value")) },
                        contentAlignment = Alignment.Center) {
                        Surface(Modifier.size(40.dp), shape = CircleShape,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(option.getString("text"), maxLines = 1, style = MaterialTheme.typography.labelLarge,
                                    autoSize = WholeWordAutoSize(8.sp, MaterialTheme.typography.labelLarge.fontSize))
                            }
                        }
                    }
                }
            } else FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                values.forEach {option->FilterChip(selected(option.get("value")),{choose(option.get("value"))},label={Text(option.getString("text"))},
                    modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp),enabled=enabled)}
            }
            else -> values.forEach {option->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=enabled,role=Role.RadioButton){choose(option.get("value"))},verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected(option.get("value")),onClick=null,enabled=enabled);Text(option.getString("text"),Modifier.padding(start=8.dp))
            }}
        }
    }
}

/** The width in px of the widest option label on one line in [style]. */
@Composable
private fun widestLabel(options: List<JSONObject>, style: androidx.compose.ui.text.TextStyle): Int {
    val measurer = rememberTextMeasurer(cacheSize = options.size)
    return remember(options, style, measurer) { options.maxOf { measurer.measure(it.getString("text"), style = style, softWrap = false).size.width } }
}

/**
 * Cells of a single choice that wrap. A scene choice of a course keeps its fixed blue. A segmented control whose labels do not fit
 * passes the colors of its selected segment and no label, because its title stands over it.
 */
@Composable
private fun NativeSceneChoices(
    label: String?,
    options: List<JSONObject>,
    enabled: Boolean,
    modifier: Modifier,
    selected: (Any) -> Boolean,
    choose: (Any) -> Unit,
    selectedContainer: Color = Color(0xFFC7DFFF),
    selectedContent: Color = Color(0xFF17283E),
) {
    val style = MaterialTheme.typography.labelLarge
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val cellGap = 8.dp
    val horizontalPadding = 8.dp
    val checkSize = 16.dp
    val labelGap = 4.dp
    val textWidth = widestLabel(options, style)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.selectableGroup().semantics {
        if (label != null) contentDescription = label
        isTraversalGroup = true
    }) {
        val columns = with(density) {
            val minWidth = maxOf(48.dp.roundToPx(),
                textWidth + horizontalPadding.roundToPx() * 2 + checkSize.roundToPx() + labelGap.roundToPx())
            val gap = cellGap.roundToPx()
            if (constraints.hasBoundedWidth) ((constraints.maxWidth + gap) / (minWidth + gap)).coerceIn(1, options.size)
            else 1
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(cellGap)) {
            for (row in options.chunked(columns)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(cellGap)) {
                    for (option in row) {
                        val value = option.get("value")
                        val active = selected(value)
                        val foreground = if (active) selectedContent else colors.onSurface
                        val textColor = if (enabled) foreground else foreground.copy(alpha = 0.38f)
                        val decoration = if (active) Modifier.background(selectedContainer)
                            else Modifier.border(1.dp, colors.outline, shape)
                        Row(
                            Modifier.weight(1f).heightIn(min = 48.dp).clip(shape).then(decoration)
                                .selectable(active, enabled = enabled, role = Role.RadioButton) { choose(value) }
                                .padding(horizontal = horizontalPadding, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(labelGap, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(checkSize)) {
                                if (active) AlphaIcon(Glyph.Check, Modifier.fillMaxSize(), color = textColor)
                            }
                            Text(option.getString("text"), color = textColor, style = style, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NativeDateRange(node:JSONObject,state:JSONObject,enabled:Boolean,modifier:Modifier,event:(String,Any?)->Unit) {
    val dates=ToolUiCatalog.value(state,node.getString("bind")) as JSONArray
    var open by remember {mutableStateOf(false)}
    val text=node.getString("text")
    OutlinedButton(onClick={open=true},enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {Text("$text: ${dates.getString(0)} – ${dates.getString(1)}")}
    if(open && enabled) {
        val configuration = LocalConfiguration.current
        val shortWindow = configuration.screenHeightDp in 1..399
        val narrowWindow = configuration.screenWidthDp in 1..399
        val largeText = LocalDensity.current.fontScale >= 2f
        val initialStart = LocalDate.parse(dates.getString(0)).toEpochDay() * 86400000
        val initialEnd = LocalDate.parse(dates.getString(1)).toEpochDay() * 86400000
        val dismiss = { open = false }
        val cancel: @Composable () -> Unit = {
            TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        }
        val apply: @Composable (Long?, Long?) -> Unit = { start, end ->
            val valid = start != null && end != null && start <= end
            TextButton(
                onClick = {
                    if (start != null && end != null && start <= end) {
                        val range = JSONArray()
                            .put(LocalDate.ofEpochDay(Math.floorDiv(start, 86400000L)).toString())
                            .put(LocalDate.ofEpochDay(Math.floorDiv(end, 86400000L)).toString())
                        event(node.getString("action"), range)
                        dismiss()
                    }
                },
                enabled = valid,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(miniAppString(R.string.tool_generated_apply, text)) }
        }
        if (narrowWindow || shortWindow || largeText) {
            val start = rememberDatePickerState(initialSelectedDateMillis = initialStart, yearRange = 1..9999, initialDisplayMode = DisplayMode.Input)
            val end = rememberDatePickerState(initialSelectedDateMillis = initialEnd, yearRange = 1..9999, initialDisplayMode = DisplayMode.Input)
            val startDate = start.selectedDateMillis
            val endDate = end.selectedDateMillis
            val invalid = startDate != null && endDate != null && startDate > endDate
            val fields = remember { BringIntoViewRequester() }
            // In landscape the error line appears above the fields and pushes them down, under the keyboard. Show them again.
            LaunchedEffect(invalid) { if (invalid) { withFrameNanos {}; fields.bringIntoView() } }
            DateRangeInputDialog(text, dismiss, cancel, { apply(startDate, endDate) }) {
                val rangeError: @Composable () -> Unit = {
                    if (invalid) Text(
                        stringResource(R.string.tool_date_range_invalid), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val startInput: @Composable () -> Unit = {
                    Text(stringResource(R.string.tool_date_range_start), Modifier.padding(horizontal = 24.dp).semantics { heading() })
                    DatePicker(start, title = null, headline = null, showModeToggle = false, focusRequester = null)
                }
                val endInput: @Composable () -> Unit = {
                    Text(stringResource(R.string.tool_date_range_end), Modifier.padding(horizontal = 24.dp).semantics { heading() })
                    DatePicker(end, title = null, headline = null, showModeToggle = false, focusRequester = null)
                }
                if (narrowWindow || largeText && configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    startInput()
                    endInput()
                    rangeError()
                } else {
                    rangeError()
                    Row(Modifier.fillMaxWidth().bringIntoViewRequester(fields)) {
                        Column(Modifier.weight(1f)) { startInput() }
                        Column(Modifier.weight(1f)) { endInput() }
                    }
                }
            }
        } else {
            val picker = rememberDateRangePickerState(
                initialSelectedStartDateMillis = initialStart,
                initialSelectedEndDateMillis = initialEnd,
                yearRange = 1..9999,
            )
            val confirm: @Composable () -> Unit = { apply(picker.selectedStartDateMillis, picker.selectedEndDateMillis) }
            androidx.compose.material3.DatePickerDialog(onDismissRequest = dismiss, confirmButton = confirm, dismissButton = cancel) {
                DateRangePicker(picker, modifier = Modifier.heightIn(max = 500.dp), title = { Text(text, Modifier.padding(16.dp)) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DateRangeInputDialog(
    title: String,
    dismiss: () -> Unit,
    cancel: @Composable () -> Unit,
    apply: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = DatePickerDefaults.colors().containerColor) {
            Column {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(title, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                    content()
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    cancel()
                    apply()
                }
            }
        }
    }
}

@Composable private fun NativeRouteStack(node: JSONObject, state: JSONObject, enabled: Boolean, modifier: Modifier,
    event: (String, Any?) -> Unit, render: @Composable (JSONObject) -> Unit) {
    val stack = ToolUiCatalog.routeStack(node, state)
    val canPop = stack.size > 1
    val inModalWindow = generateSequence(LocalView.current.parent) { it.parent }.any { it is DialogWindowProvider }
    val back = { event(node.getString("action"), JSONArray(stack)) }
    BackHandler(enabled = enabled && canPop && LocalRouteSystemBack.current && !inModalWindow, onBack = back)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canPop) TextButton(onClick = back, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(miniAppString(R.string.tool_route_back))
        }
        key(stack) { render(ToolUiCatalog.routeChild(node, stack.last())) }
    }
}

@Composable private fun NativePager(node:JSONObject,state:JSONObject,enabled:Boolean,modifier:Modifier,event:(String,Any?)->Unit,render:@Composable (Int) -> Unit) {
    val count=node.getJSONArray("children").length();val current=(ToolUiCatalog.value(state,node.getString("bind")) as Number).toInt()
    val pages=rememberPagerState(initialPage=current,pageCount={count})
    val target by rememberUpdatedState(current);val active by rememberUpdatedState(enabled);val dispatch by rememberUpdatedState(event)
    var programmatic by remember {mutableStateOf(false)}
    LaunchedEffect(current,count) {programmatic=true;try {pages.scrollToPage(current)} finally {programmatic=false}}
    LaunchedEffect(pages) {snapshotFlow {pages.settledPage}.distinctUntilChanged().collect {if(active && !programmatic && it!=target)dispatch(node.getString("action"),it)}}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        val pageLabel = miniAppString(R.string.tool_page, current + 1, count)
        Text("${node.getString("text")}: ${current+1} / $count",Modifier.semantics {stateDescription=pageLabel})
        HorizontalPager(pages,Modifier.fillMaxWidth().height(node.optInt("viewportHeight",320).dp),userScrollEnabled=enabled,key={it}) {page->Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {render(page)}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={event(node.getString("action"),current-1)},enabled=enabled && current>0) {Text(miniAppString(R.string.tool_page_previous, node.getString("text")))}
            TextButton(onClick={event(node.getString("action"),current+1)},enabled=enabled && current<count-1) {Text(miniAppString(R.string.tool_page_next, node.getString("text")))}
        }
    }
}
