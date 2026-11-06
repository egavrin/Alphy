package com.alpha.assistant.tools

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
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
import com.alpha.assistant.capabilities.OwnedCalendarUiHost
import com.alpha.assistant.capabilities.OwnedCalendarSnapshot
import com.alpha.assistant.tools.generation.NativePresentation
import com.alpha.assistant.tools.generation.NativePresentationProfile
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.round

private val LocalEmbeddedTool = staticCompositionLocalOf { false }
private val LocalToolBackground = staticCompositionLocalOf { Color.Unspecified }
private val LocalRouteSystemBack = staticCompositionLocalOf { true }
private val LocalToolMetricFont = staticCompositionLocalOf<FontFamily?> { null }
private val LocalPresentationFocus = staticCompositionLocalOf { false }
private val LocalPresentationPrimary = staticCompositionLocalOf<PresentationPrimary?> { null }
private val LocalPresentationScreen = staticCompositionLocalOf<JSONObject?> { null }
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

private data class PresentationPrimary(val action: String, val screen: JSONObject?)

private fun presentationPrimary(node: JSONObject, action: String, parentScreen: JSONObject? = null): PresentationPrimary? {
    val type = node.getString("type")
    val screen = if (type in ToolUiCatalog.modalTypes || type == "routeStack" || type == "pager") node else parentScreen
    if (type == "button" && node.optString("action") == action) return PresentationPrimary(action, screen)
    val children = node.optJSONArray("children") ?: return null
    repeat(children.length()) { index ->
        presentationPrimary(children.getJSONObject(index), action, screen)?.let { return it }
    }
    return null
}

private data class ToolRole(val ink: Color, val background: Color, val foreground: Color)

private fun ColorScheme.toolRole(name: String?) = when (name) {
    "muted" -> ToolRole(onSurfaceVariant, surfaceVariant, onSurfaceVariant)
    "accent" -> ToolRole(primary, primaryContainer, onPrimaryContainer)
    "success" -> ToolRole(secondary, secondaryContainer, onSecondaryContainer)
    "warning" -> ToolRole(tertiary, tertiaryContainer, onTertiaryContainer)
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

@Composable private fun toolInk(name: String?): Color = when (name) {
    null, "", "default" -> LocalContentColor.current
    else -> MaterialTheme.colorScheme.toolInk(name, LocalToolBackground.current)
}

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

private val LocalMiniAppControlsLanguage = staticCompositionLocalOf<String?> { null }

@Composable private fun miniAppEditorAction(resource: Int, label: String): String {
    val tag = LocalMiniAppControlsLanguage.current
    if (tag == null || tag == "ru") return stringResource(resource, label)
    if (tag == "en") return stringResource(when (resource) {
        R.string.tool_generated_apply -> R.string.tool_generated_apply_en
        R.string.tool_generated_search -> R.string.tool_generated_search_en
        R.string.tool_generated_remove -> R.string.tool_generated_remove_en
        R.string.tool_live_input_finish -> R.string.tool_live_input_finish_en
        R.string.tool_number_increase -> R.string.tool_number_increase_en
        R.string.tool_number_decrease -> R.string.tool_number_decrease_en
        else -> resource
    }, label)

    // The platform supplies a confirmation and search label for additional locales.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(context, configuration, tag) {
        context.createConfigurationContext(android.content.res.Configuration(configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(tag))
        })
    }
    val action = when (resource) {
        R.string.tool_generated_search -> localized.getString(android.R.string.search_go)
        R.string.tool_generated_remove -> "×"
        R.string.tool_number_increase -> "+"
        R.string.tool_number_decrease -> "−"
        else -> localized.getString(android.R.string.ok)
    }
    return "$action: $label"
}

@Composable internal fun ToolContent(ui: String, state: String, enabled: Boolean,
    heading: String? = null, imageVersion: Any? = null, imageLoader: suspend (String) -> Bitmap? = {null}, imageAvailable: suspend (String) -> Boolean = {false}, calendarHost: OwnedCalendarUiHost? = null, embedded: Boolean = false, background: Color = MaterialTheme.colorScheme.surface,
    routeSystemBackEnabled: Boolean = true, presentation: NativePresentation? = null,
    liveInput: NativeLiveInput? = null,
    onLiveInputState: (unfinished: Boolean, unsaved: Boolean) -> Unit = { _, _ -> },
    videoHost: NativeVideoUiHost? = null,
    timerHost: com.alpha.assistant.timer.ForegroundTimerUiHost? = null,
    appLanguage: String? = null,
    event: (String,Any?) -> Unit) {
    val root=remember(ui) {JSONObject(ui)}
    val values=remember(state) {JSONObject(state)}
    val withinLimits=remember(ui,state) {runCatching {ToolUiCatalog.validateRenderedWork(root,values)}.isSuccess}
    if(!withinLimits) {Text(stringResource(R.string.tool_render_limit)); return}
    val recipe = remember(root, presentation) {
        presentation?.takeIf { runCatching { it.focalNode(root) }.isSuccess }
    }
    val selectedPrimary = remember(root, recipe) {
        recipe?.primaryActionId?.let { presentationPrimary(root, it) }
    }
    val primary = remember(root, values, enabled, selectedPrimary) {
        selectedPrimary?.takeIf { enabled && runCatching { ToolUiCatalog.validateAction(root, it.action, null, values) }.isSuccess }
    }
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
            LocalRouteSystemBack provides (routeSystemBackEnabled && !embedded), LocalPresentationPrimary provides primary,
            LocalLiveUi provides NativeLiveUi(liveInput, access, edits, root, values), LocalTimerClock provides timerClock,
            LocalVideoUi provides videoUi, LocalForegroundTimer provides timerHost,
            LocalMiniAppControlsLanguage provides appLanguage) {
            val render: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth()) {
                    edits.notice?.let { NativeLiveMessage(it) }
                    NativeNode(root,values,enabled,imageVersion,imageLoader,imageAvailable,guardedCalendar,false,heading,presentation=recipe) { id,payload ->
                        if(enabled && runCatching {ToolUiCatalog.validateAction(root,id,payload,values)}.isSuccess && edits.allowAction()) event(id,payload)
                    }
                }
            }
            if (recipe == null) render() else NativePreset(recipe.preset, content = render)
        }
    }
}
private fun space(value: String?, default: Int = 0) = when(value) {"none" -> 0; "small" -> 6; "medium" -> 12; "large" -> 20; else -> default}.dp
private fun alignment(style: JSONObject?) = when(style?.optString("align")) {"center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start}
private fun showsToolHeading(text: String, hostHeading: String?) =
    text.isNotBlank() && !text.trim().equals(hostHeading?.trim(), ignoreCase = true)

@Composable private fun textStyle(style: JSONObject?, metric: Boolean = false) = when(style?.optString("emphasis")) {
    "title" -> MaterialTheme.typography.titleLarge
    "headline" -> MaterialTheme.typography.headlineMedium
    "label" -> MaterialTheme.typography.labelLarge
    "number" -> MaterialTheme.typography.displaySmall.copy(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily)
    else -> if(metric) MaterialTheme.typography.displaySmall else MaterialTheme.typography.bodyLarge
}
@Composable private fun metricStyle(style: JSONObject?): androidx.compose.ui.text.TextStyle {
    val value = if (LocalPresentationFocus.current && style?.has("emphasis") != true) MaterialTheme.typography.displayMedium
        else textStyle(style, metric = true)
    return value.copy(fontFamily = LocalToolMetricFont.current ?: MaterialTheme.typography.bodyLarge.fontFamily)
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

private val compactWorkspaceTypes = setOf("column", "section", "card", "form", "facts", "fact", "row", "flowRow",
    "metric", "text", "badge", "input", "numberInput", "button", "toggle", "checkbox", "progress", "progressRing", "timer",
    "spacer", "divider")

private fun fitsWorkspaceColumn(node: JSONObject, state: JSONObject): Boolean {
    if (!ToolUiCatalog.visible(node, state)) return true
    val type = node.getString("type")
    val layout = node.optJSONObject("layout")
    if (type !in compactWorkspaceTypes || type == "row" && !node.optBoolean("wrap", true) ||
        layout?.has("width") == true || layout?.has("minWidth") == true) return false
    val children = node.optJSONArray("children") ?: return true
    return (0 until children.length()).all { fitsWorkspaceColumn(children.getJSONObject(it), state) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun WorkspaceSlots(
    modifier: Modifier, items: JSONArray, state: JSONObject, style: JSONObject?, gap: Dp, render: @Composable (Int, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val visible = (0 until items.length()).filter { ToolUiCatalog.visible(items.getJSONObject(it), state) }
        val paired = if (maxWidth >= 560.dp && density.fontScale < 1.5f) buildSet {
            var waiting: Int? = null
            for (index in visible) {
                if (fitsWorkspaceColumn(items.getJSONObject(index), state)) {
                    val previous = waiting
                    if (previous == null) waiting = index
                    else {
                        add(previous)
                        add(index)
                        waiting = null
                    }
                } else waiting = null
            }
        } else emptySet()
        val halfWidth = if (paired.isEmpty()) 0.dp else with(density) { ((constraints.maxWidth - gap.roundToPx()) / 2).toDp() }
        val slotAlignment = when (style?.optString("align")) {
            "center" -> Alignment.TopCenter
            "end" -> Alignment.TopEnd
            else -> Alignment.TopStart
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, alignment(style)),
            verticalArrangement = verticalArrangement(style, gap), maxItemsInEachRow = 2) {
            for (index in visible) key(index) {
                val slot = if (index in paired) Modifier.width(halfWidth) else Modifier.fillMaxWidth()
                Box(slot, contentAlignment = slotAlignment) { render(index, Modifier) }
            }
        }
    }
}

private fun Modifier.focalAccent(color: Color): Modifier = drawBehind {
    val length = minOf(size.width, 48.dp.toPx())
    val start = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - length
    drawLine(color, Offset(start, 1.5.dp.toPx()), Offset(start + length, 1.5.dp.toPx()), strokeWidth = 3.dp.toPx())
}.padding(top = 12.dp)

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
    val content: @Composable () -> Unit = {
        Text(annotated.toAnnotatedString(),if(!enabled && spans != null) textModifier.semantics {disabled()} else textModifier,style=textStyle(style),color=toolInk(style?.optString("tone")),
            textAlign=when(style?.optString("align")) {"center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start},
            maxLines=node.optInt("maxLines",Int.MAX_VALUE),softWrap=node.optBoolean("softWrap",true),
            overflow=if(node.optString("overflow") == "ellipsis") TextOverflow.Ellipsis else TextOverflow.Clip,
            lineHeight=if(node.has("lineHeight")) node.getDouble("lineHeight").sp else androidx.compose.ui.unit.TextUnit.Unspecified)
    }
    if(selectable) SelectionContainer(modifier=modifier) {content()} else content()
}
@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable private fun NativeNode(node: JSONObject, state: JSONObject, parentEnabled: Boolean, imageVersion: Any?,
    imageLoader: suspend (String) -> Bitmap?, imageAvailable: suspend (String) -> Boolean, calendarHost: OwnedCalendarUiHost?, compact: Boolean, heading: String?, placement: Modifier = Modifier,
    secondaryButton: Boolean = false, presentation: NativePresentation? = null, event: (String,Any?) -> Unit) {
    if(!ToolUiCatalog.visible(node,state)) return
    val type=node.getString("type")
    val definition=ToolUiCatalog.component(type)
    val enabled=parentEnabled && ToolUiCatalog.enabled(node,state)
    val text=node.optString("text")
    val style=node.optJSONObject("style")
    val tone=style?.optString("tone").orEmpty()
    val binding=node.optString("bind")
    val modifier=nativeInteractions(node,enabled,event,nativeLayout(node,placement,compact || type in setOf("spacer","icon")).padding(space(style?.optString("padding"))))
    val gap=space(style?.optString("gap"),12)
    val action=node.optString("action")
    val children=node.optJSONArray("children")
    val renderChildren: @Composable () -> Unit = {
        if(children != null) repeat(children.length()) { NativeNode(children.getJSONObject(it),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event) }
    }
    val content: @Composable () -> Unit = {
        if (type in ToolUiCatalog.modalTypes) {
            CompositionLocalProvider(LocalPresentationScreen provides node, LocalPresentationFocus provides false, content = renderChildren)
        } else renderChildren()
    }
    if (LocalEmbeddedTool.current && type in ToolUiCatalog.modalTypes) {
        val scheme = MaterialTheme.colorScheme
        ToolRoleSurface(modifier, ToolRole(scheme.onSurface, scheme.surfaceContainerHigh, scheme.onSurface)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.titleMedium)
                content()
                TextButton(onClick = { event(node.getString("dismissAction"), null) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.tool_generated_dialog_close, text)) }
            }
        }
        return
    }
    when(definition.type) {
        "appBar" -> TopAppBar(title={if (showsToolHeading(text, heading)) Text(text)},modifier=modifier,actions={content()})
        "bottomBar" -> BottomAppBar(modifier=modifier) {content()}
        "routeStack" -> NativeRouteStack(node, state, enabled, modifier, event) { route ->
            CompositionLocalProvider(LocalPresentationScreen provides node) {
                NativeNode(route,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)
            }
        }
        "dialog" -> AlertDialog(onDismissRequest={if(enabled)event(node.getString("dismissAction"),null)},title={Text(text)},
            text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                CompositionLocalProvider(LocalToolBackground provides MaterialTheme.colorScheme.surfaceContainerHigh, content = content)
            }},
            confirmButton={TextButton(onClick={event(node.getString("dismissAction"),null)},enabled=enabled) {Text(stringResource(R.string.tool_generated_dialog_close, text))}})
        "bottomSheet" -> ModalBottomSheet(onDismissRequest={if(enabled)event(node.getString("dismissAction"),null)},sheetGesturesEnabled=enabled) {
            Column(modifier.heightIn(max=640.dp).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(text,style=MaterialTheme.typography.titleLarge)
                content()
                TextButton(onClick={event(node.getString("dismissAction"),null)},enabled=enabled) {Text(stringResource(R.string.tool_generated_dialog_close, text))}
            }
        }
        "transition" -> NativeTransition(node,state,modifier) {content()}
        "pager" -> NativePager(node,state,enabled,modifier,event) {page ->
            CompositionLocalProvider(LocalPresentationScreen provides node) {
                NativeNode(checkNotNull(children).getJSONObject(page),state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)
            }
        }
        "theme" -> NativePreset(node.getString("preset"), node.optInt("cornerRadius", -1).takeIf { it >= 0 }, node.optDouble("typeScale",1.0)) {
            Column(modifier,verticalArrangement=verticalArrangement(style,gap),horizontalAlignment=alignment(style),content={content()})
        }
        "motion" -> NativeMotion(node,state,modifier) {content()}
        "transform" -> NativeTransform(node,state,modifier) {content()}
        "stage" -> NativeStage(node,state,modifier) {leaf -> NativeNode(leaf,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=event)}
        "column","form" -> TonedContainer(modifier, tone) { containerModifier ->
            val items = checkNotNull(children)
            val renderChild: @Composable (Int, Modifier) -> Unit = { index, slot ->
                val child = items.getJSONObject(index)
                val focal = presentation?.profile == NativePresentationProfile.FOCUS && index == presentation.focalPosition
                if (focal) CompositionLocalProvider(LocalPresentationFocus provides true) {
                    NativeNode(child,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,
                        placement=slot.focalAccent(MaterialTheme.colorScheme.primary),event=event)
                } else NativeNode(child,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,placement=slot,event=event)
            }
            val column: @Composable (Modifier) -> Unit = { position ->
                Column(position,verticalArrangement=verticalArrangement(style,gap),horizontalAlignment=alignment(style)) {
                    if(type == "form" && showsToolHeading(text, heading)) Text(text,Modifier.semantics {heading()},style=MaterialTheme.typography.titleMedium)
                    repeat(items.length()) { index ->
                        val layout = items.getJSONObject(index).optJSONObject("layout")
                        var slot: Modifier=Modifier
                        if(type == "column") {
                            if(layout?.has("weight") == true) slot=slot.weight(layout.getDouble("weight").toFloat())
                            if(layout?.has("align") == true) slot=slot.align(alignment(JSONObject().put("align",layout.getString("align"))))
                        }
                        renderChild(index, slot)
                    }
                }
            }
            when (presentation?.profile) {
                NativePresentationProfile.READING -> Box(containerModifier, contentAlignment = Alignment.TopCenter) {
                    column(Modifier.widthIn(max = 520.dp).fillMaxWidth())
                }
                NativePresentationProfile.WORKSPACE -> {
                    val usesColumnPlacement = (0 until items.length()).any { index ->
                        val layout = items.getJSONObject(index).optJSONObject("layout")
                        layout?.has("weight") == true || layout?.has("align") == true
                    }
                    if (usesColumnPlacement) column(containerModifier)
                    else WorkspaceSlots(containerModifier, items, state, style, gap, renderChild)
                }
                else -> column(containerModifier)
            }
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
                Row(modifier,horizontalArrangement=horizontalArrangement(style,gap),verticalAlignment=verticalAlignment(style?.optString("crossAlign"))) {
                    repeat(items.length()) {index ->
                        val child=items.getJSONObject(index); val layout=child.optJSONObject("layout")
                        var slot: Modifier=Modifier
                        if(layout?.has("weight") == true) slot=slot.weight(layout.getDouble("weight").toFloat())
                        if(layout?.has("align") == true) slot=slot.align(verticalAlignment(layout.getString("align")))
                        NativeNode(child,state,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,true,heading,placement=slot,
                            secondaryButton = index in secondary,event=event)
                    }
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
            val minWidth = maxOf(scaledButtonWidth, node.optInt("minCellWidth", if (shortButtons) 48 else 144).toFloat())
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
            val value=if(binding.isNotEmpty()) ToolUiCatalog.display(node,state).removePrefix("$text: ") else ""
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
            val message: @Composable (Modifier) -> Unit = { box ->
                Column(box.padding(horizontal = if (neutral) 0.dp else 16.dp, vertical = if (empty) 20.dp else 16.dp),
                    horizontalAlignment = if (empty) Alignment.CenterHorizontally else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(gap)) {
                    if (text.isNotBlank()) {
                        val titleStyle = MaterialTheme.typography.titleMedium.copy(textAlign = if (empty) TextAlign.Center else TextAlign.Start)
                        ToolStatusText(text, if (explicitIcon) "" else tone, titleStyle, Modifier.semantics { heading() })
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
        "card" -> ToolRoleSurface(modifier, MaterialTheme.colorScheme.toolRole(tone)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = alignment(style)) {
                if (showsToolHeading(text, heading)) Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                content()
            }
        }
        "listItem" -> Column(modifier) {
            val click = if (action.isNotEmpty()) Modifier.clickable(enabled = enabled, role = Role.Button) { event(action, null) } else Modifier
            Column(click.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = alignment(style)) {
                if (showsToolHeading(text, heading)) Text(text, style = MaterialTheme.typography.titleMedium, color = toolInk(tone))
                content()
            }
            HorizontalDivider()
        }
        "spacer" -> Spacer(modifier.size(node.optInt("size",12).dp))
        "text","metric","badge" -> {
            val label=ToolUiCatalog.display(node,state)
            val align=when(style?.optString("align")) {"center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start}
            if(type == "badge") {
                val role = MaterialTheme.colorScheme.toolRole(tone)
                Surface(modifier,shape=MaterialTheme.shapes.small,color=role.background,contentColor=role.foreground) {
                    ToolStatusText(label, tone, MaterialTheme.typography.labelLarge.copy(textAlign = align), Modifier.padding(horizontal=10.dp,vertical=6.dp))
                }
            } else if(type == "metric" && node.has("bind")) Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp), horizontalAlignment = alignment(style)) {
                if (showsToolHeading(text, heading)) Text(text,style=MaterialTheme.typography.labelLarge,color=toolInk("muted"))
                Text(label.removePrefix("$text: "),style=metricStyle(style),color=toolInk(tone),textAlign=align)
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
                if(node.has("icon")) {AlphaIcon(Glyph.valueOf(node.getString("icon")), color = LocalContentColor.current);Spacer(Modifier.width(8.dp))}
                Text(text)
            }
            val click={event(action,null)}
            val buttonModifier=modifier.heightIn(min=48.dp)
            val primary = LocalPresentationPrimary.current?.takeIf { it.screen === LocalPresentationScreen.current }?.action
            val fallback = if (primary != null) {
                if (action == primary) "filled" else "outlined"
            } else if (secondaryButton) "outlined" else "filled"
            when(node.optString("variant", fallback)) {
                "outlined" -> OutlinedButton(click,buttonModifier,enabled=enabled) {label()}
                "text" -> TextButton(click,buttonModifier,enabled=enabled) {label()}
                "tonal" -> FilledTonalButton(click,buttonModifier,enabled=enabled) {label()}
                "elevated" -> ElevatedButton(click,buttonModifier,enabled=enabled) {label()}
                "fab","extendedFab" -> if(enabled) {
                    if(node.getString("variant")=="fab") FloatingActionButton(click,modifier.semantics {contentDescription=text}) {AlphaIcon(Glyph.valueOf(node.getString("icon")))}
                    else ExtendedFloatingActionButton(click,buttonModifier) {label()}
                } else FilledTonalButton(click,buttonModifier,enabled=false) {label()}
                else -> Button(click,buttonModifier,enabled=enabled) {label()}
            }
        }
        "actionChip" -> if(node.optString("variant")=="suggestion") SuggestionChip(onClick={event(action,null)},label={Text(text)},enabled=enabled,modifier=modifier.heightIn(min=48.dp))
            else AssistChip(onClick={event(action,null)},label={Text(text)},enabled=enabled,modifier=modifier.heightIn(min=48.dp))
        "icon" -> {
            val icon: @Composable () -> Unit = {AlphaIcon(Glyph.valueOf(node.getString("name")),Modifier.semantics {contentDescription=text})}
            if(action.isNotEmpty()) IconButton(onClick={event(action,null)},enabled=enabled,modifier=modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) {icon()}
            else Box(modifier) {icon()}
        }
        "toggle","checkbox" -> Row(modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            val checked=ToolUiCatalog.value(state,binding) as Boolean
            val label=Modifier.semantics {contentDescription=text}
            if(type == "toggle") Switch(checked,{event(action,it)},label,enabled=enabled)
            else Checkbox(checked,{event(action,it)},label,enabled=enabled)
            Text(text,Modifier.padding(start=10.dp).weight(1f))
        }
        "chip" -> {
            val checked=ToolUiCatalog.value(state,binding) as Boolean
            val removeLabel = miniAppEditorAction(R.string.tool_generated_remove, text)
            val dismiss: (@Composable () -> Unit)?=node.optString("dismissAction").takeIf {it.isNotEmpty()}?.let {id -> {IconButton(onClick={event(id,null)},enabled=enabled,modifier=Modifier.size(48.dp)) {AlphaIcon(Glyph.Close,Modifier.semantics {contentDescription=removeLabel})}}}
            if(node.optString("variant")=="input") InputChip(selected=checked,onClick={event(action,!checked)},label={Text(text)},trailingIcon=dismiss,enabled=enabled,modifier=modifier.heightIn(min=48.dp))
            else FilterChip(selected=checked,onClick={event(action,!checked)},label={Text(text)},trailingIcon=dismiss,enabled=enabled,modifier=modifier.heightIn(min=48.dp))
        }
        "rangeSlider" -> {
            val original=ToolUiCatalog.value(state,binding) as JSONArray;val (low,high)=ToolUiCatalog.bounds(node)
            var range by remember(original.toString(),binding) {mutableStateOf(original.getDouble(0)..original.getDouble(1))}
            Column(modifier) {
                Text("$text: ${range.start} – ${range.endInclusive}")
                RangeSlider(value=((range.start-low)/(high-low)).toFloat().coerceIn(0f,1f)..((range.endInclusive-low)/(high-low)).toFloat().coerceIn(0f,1f),
                    onValueChange={range=nativeSliderValue(node,it.start)..nativeSliderValue(node,it.endInclusive)},enabled=enabled,modifier=Modifier.semantics {contentDescription=text})
                TextButton(onClick={event(action,JSONArray().put(range.start).put(range.endInclusive))},enabled=enabled && (range.start!=original.getDouble(0) || range.endInclusive!=original.getDouble(1))) {Text(miniAppEditorAction(R.string.tool_generated_apply, text))}
            }
        }
        "dateRangeInput" -> NativeDateRange(node,state,enabled,modifier,event)
        "singleChoice","multiChoice","segmented","navigationBar","navigationRail","menu" -> NativeChoices(node,state,enabled,modifier,event)
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
            },enabled=enabled,modifier=modifier.heightIn(min=48.dp)) {Text("$text: $label")}
        }
        "slider" -> {
            val (low,high)=ToolUiCatalog.bounds(node)
            val original=(ToolUiCatalog.value(state,binding) as Number).toDouble()
            val editor = if (ToolUiCatalog.isLiveInput(node)) liveEditor(node, enabled) else null
            var draft by remember(original,binding) {mutableDoubleStateOf(original)}
            val value = (editor?.shown(original) as? Number)?.toDouble() ?: draft
            Column(modifier) {
                Text("$text: $value")
                Slider(value=((value-low)/(high-low)).toFloat().coerceIn(0f,1f),onValueChange={ fraction ->
                    val next = nativeSliderValue(node,fraction)
                    if (editor == null) draft = next else editor.change(next)
                },onValueChangeFinished=editor?.takeIf { it.automatic }?.let { live -> { live.finish(explicit = false) } },
                    enabled=editor?.canEdit ?: enabled,modifier=Modifier.semantics {contentDescription=text})
                if (editor == null) {
                    TextButton(onClick={event(action,value)},enabled=enabled && value != original) {Text(miniAppEditorAction(R.string.tool_generated_apply, text))}
                } else NativeLiveActions(editor, text, finishButton = false)
            }
        }
        "select","tabs" -> {
            val options=node.getJSONArray("options"); val selected=ToolUiCatalog.value(state,binding)
            Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(text,style=MaterialTheme.typography.labelLarge)
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
                            Text(option.getString("text"))
                        }
                        DropdownMenu(expanded && enabled,onDismissRequest={expanded=false}) {
                            repeat(options.length()) {index -> val option=options.getJSONObject(index)
                                DropdownMenuItem(text={Text(option.getString("text"))},onClick={expanded=false; event(action,option.get("value"))})
                            }
                        }
                    }
                }
            }
        }
        "list","table","groupedList","lazyList","lazyGrid" -> NativeRecords(node,state,enabled,modifier,heading,event=event) {template,row,dispatch ->
            NativeNode(template,row,enabled,imageVersion,imageLoader,imageAvailable,calendarHost,false,heading,event=dispatch)
        }
        "progress","progressRing" -> {
            val (low,high)=ToolUiCatalog.bounds(node)
            val progress=if(binding.isNotEmpty()) (((ToolUiCatalog.value(state,binding) as Number).toDouble()-low)/(high-low)).toFloat().coerceIn(0f,1f) else 0f
            Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(ToolUiCatalog.display(node,state))
                if(node.optBoolean("indeterminate")) {
                    if(type == "progress") LinearProgressIndicator(modifier=Modifier.fillMaxWidth().semantics {contentDescription=text})
                    else CircularProgressIndicator(modifier=Modifier.size(56.dp).semantics {contentDescription=text})
                } else if(type == "progress") LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().semantics {contentDescription=text})
                else CircularProgressIndicator(progress={progress},modifier=Modifier.size(56.dp).semantics {contentDescription=text})
            }
        }
        "timer" -> if (node.has("foreground")) {
            val timer = remember(node) { com.alpha.assistant.timer.ForegroundTimerSpec.parse(node) }
            com.alpha.assistant.timer.ForegroundTimerUi(text, timer, enabled, LocalForegroundTimer.current, modifier)
        } else NativeTimer(node, state, modifier)
        "chart" -> NativeChart(node,state,modifier,heading)
        "video" -> NativeVideo(node, state, enabled, modifier)
        "image" -> {
            val handle=if(binding.isNotEmpty()) ToolUiCatalog.value(state,binding).toString() else node.getString("resourceId")
            val loader by rememberUpdatedState(imageLoader)
            val available by rememberUpdatedState(imageAvailable)
            val lifecycle=LocalLifecycleOwner.current.lifecycle
            key(handle,imageVersion,lifecycle) {
                val image by produceState<Bitmap?>(null) {
                    observeNativeImage(lifecycle,available={available(handle)},load={loader(handle)},display={value=it})
                }
                if(image == null) Surface(modifier.height(160.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment=Alignment.Center) {Text(stringResource(R.string.tool_image_unavailable, text),Modifier.padding(16.dp))}
                } else Image(checkNotNull(image).asImageBitmap(),text,modifier.heightIn(min=80.dp,max=280.dp),contentScale=if(node.optString("fit") == "crop") ContentScale.Crop else ContentScale.Fit)
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
        "backHandler" -> BackHandler(enabled=enabled) {event(action,null)}
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
    val status = when {
        timer == null -> stringResource(R.string.tool_timer_unavailable)
        seconds == null -> stringResource(R.string.tool_timer_idle)
        timer.countdown && seconds == 0L -> stringResource(R.string.tool_timer_expired)
        else -> {
            val days = seconds / 86_400
            val hours = seconds / 3600 % 24
            val minutes = seconds / 60 % 60
            val remainder = seconds % 60
            val duration = if (days > 0) stringResource(R.string.tool_timer_clock_days, days, hours, minutes, remainder)
                else stringResource(R.string.tool_timer_clock, hours, minutes, remainder)
            stringResource(if (timer.countdown) R.string.tool_timer_remaining else R.string.tool_timer_elapsed, duration)
        }
    }
    val style = node.optJSONObject("style")
    val align = when (style?.optString("align")) {
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> TextAlign.Start
    }
    Text(stringResource(R.string.tool_timer_value, node.getString("text"), status),
        modifier.onGloballyPositioned { inWindow = !it.boundsInWindow().isEmpty },
        color = toolInk(style?.optString("tone")), style = textStyle(style), textAlign = align)
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
    val message = if (LocalMiniAppControlsLanguage.current == "en") when (resource) {
        R.string.tool_live_input_pending -> R.string.tool_live_input_pending_en
        R.string.tool_live_input_unsaved -> R.string.tool_live_input_unsaved_en
        R.string.tool_live_input_unavailable -> R.string.tool_live_input_unavailable_en
        R.string.tool_live_input_finish_first -> R.string.tool_live_input_finish_first_en
        else -> resource
    } else resource
    Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
            Text(miniAppEditorAction(if (apply) R.string.tool_generated_apply else R.string.tool_live_input_finish, label))
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
    @Composable fun ApplyDraft() {
        TextButton(onClick = apply, enabled = enabled && valid && (changed || search)) {
            Text(miniAppEditorAction(if (search) R.string.tool_generated_search else R.string.tool_generated_apply, label))
        }
    }
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(draft,{if(it.length <= if(numeric) 40 else node.optInt("maxLength",2048)) {
                if (editor == null) ordinaryDraft = it else editor.change(it)
            }},
            enabled=editor?.canEdit ?: enabled,label={Text(label)},modifier=fieldModifier,singleLine=numeric || node.optInt("maxLines",if(search) 1 else 8) == 1,maxLines=if(numeric) 1 else node.optInt("maxLines",if(search) 1 else 8),
            visualTransformation=if(node.optString("keyboard") == "password") PasswordVisualTransformation() else VisualTransformation.None,
            isError=draft.isNotEmpty() && !valid,keyboardOptions=KeyboardOptions(keyboardType=keyboard,imeAction=ime),
            keyboardActions=keyboardActions)
    }
    Column(modifier) {
        if (numeric && node.has("step")) {
            val current = payload as? Double
            @Composable fun StepButton(increase: Boolean) {
                val description = miniAppEditorAction(if (increase) R.string.tool_number_increase else R.string.tool_number_decrease, label)
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
                val inline = maxWidth >= 280.dp && LocalDensity.current.fontScale < 1.5f
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
@Composable private fun NativeRecords(node: JSONObject, state: JSONObject, enabled: Boolean, modifier: Modifier, heading: String?, event: (String,Any?) -> Unit, renderTemplate: @Composable (JSONObject,JSONObject,(String,Any?) -> Unit) -> Unit) {
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
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(showsToolHeading(node.getString("text"), heading)) Text(node.getString("text"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics {heading()})
        if(rows.length() == 0) Text(node.optString("emptyText",stringResource(R.string.tool_records_empty)),
            Modifier.padding(vertical = 12.dp), color = toolInk("muted"))
        if(type == "table") {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {repeat(checkNotNull(columns).length()) {Text(columns.getJSONObject(it).getString("text"),Modifier.width(160.dp).padding(10.dp),style=MaterialTheme.typography.labelLarge)}}
                repeat(rows.length()) {index -> val record=rows.getJSONObject(index)
                    HorizontalDivider()
                    val rowModifier=if(action.isNotEmpty()) Modifier.clickable(enabled=enabled,role=Role.Button) {event(action,ToolUiCatalog.stableKey(record))} else Modifier
                    Row(rowModifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                        repeat(checkNotNull(columns).length()) {Text(ToolUiCatalog.value(record,columns.getJSONObject(it).getString("field")).toString(),Modifier.width(160.dp).padding(10.dp))}
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
        CompositionLocalProvider(LocalLiveRow provides key) {
            renderTemplate(template,record) {id,value -> event(id,JSONObject().put("key",key).put("value",value ?: JSONObject.NULL))}
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
            val empty = stringResource(R.string.tool_chart_empty)
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
@Composable private fun NativePreset(preset: String, cornerRadius: Int? = null, typeScale: Double = 1.0, content: @Composable () -> Unit) {
    val radius=(cornerRadius ?: when(preset) {"clean" -> 12; "soft" -> 24; "expressive" -> 28; "editorial" -> 4; "technical" -> 0; else -> 32}).dp
    val typography=MaterialTheme.typography
    val shell = MaterialTheme.colorScheme
    val colors = when (preset) {
        "soft", "editorial" -> shell.copy(primary = shell.secondary, onPrimary = shell.onSecondary,
            primaryContainer = shell.secondaryContainer, onPrimaryContainer = shell.onSecondaryContainer)
        "expressive", "playful" -> shell.copy(primary = shell.tertiary, onPrimary = shell.onTertiary,
            primaryContainer = shell.tertiaryContainer, onPrimaryContainer = shell.onTertiaryContainer)
        else -> shell
    }
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
        val metricFont = if (preset == "technical") FontFamily.Monospace else null
        CompositionLocalProvider(LocalToolMetricFont provides metricFont, content = content)
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
@Composable private fun NativeChoices(node:JSONObject,state:JSONObject,enabled:Boolean,modifier:Modifier,event:(String,Any?)->Unit) {
    val type=node.getString("type");val options=node.getJSONArray("options");val current=ToolUiCatalog.value(state,node.getString("bind"));val action=node.getString("action")
    val values=(0 until options.length()).map {options.getJSONObject(it)}
    fun selected(value:Any)=if(current is JSONArray)(0 until current.length()).any {scalarEqual(current.get(it),value)} else scalarEqual(current,value)
    fun choose(value:Any) {
        if(type != "multiChoice")event(action,value)
        else {val array=current as JSONArray;val next=JSONArray();repeat(array.length()){if(!scalarEqual(array.get(it),value))next.put(array.get(it))};if(!selected(value))next.put(value);event(action,next)}
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(node.getString("text"),style=MaterialTheme.typography.labelLarge)
        when(type) {
            "segmented" -> SingleChoiceSegmentedButtonRow(Modifier.horizontalScroll(rememberScrollState())) {
                values.forEachIndexed {index,option -> SegmentedButton(selected(option.get("value")),{choose(option.get("value"))},SegmentedButtonDefaults.itemShape(index,values.size),enabled=enabled) {Text(option.getString("text"))} }
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
                    OutlinedButton(onClick={expanded=true},enabled=enabled) {Text(values.first {selected(it.get("value"))}.getString("text"))}
                    DropdownMenu(expanded && enabled,{expanded=false}) {values.forEach {option->DropdownMenuItem(text={Text(option.getString("text"))},onClick={expanded=false;choose(option.get("value"))})}}
                }
            }
            "multiChoice" -> FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                values.forEach {option->FilterChip(selected(option.get("value")),{choose(option.get("value"))},label={Text(option.getString("text"))},enabled=enabled)}
            }
            else -> values.forEach {option->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=enabled,role=Role.RadioButton){choose(option.get("value"))},verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected(option.get("value")),onClick=null,enabled=enabled);Text(option.getString("text"),Modifier.padding(start=8.dp))
            }}
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
        val initialStart = LocalDate.parse(dates.getString(0)).toEpochDay() * 86400000
        val initialEnd = LocalDate.parse(dates.getString(1)).toEpochDay() * 86400000
        val cancel: @Composable () -> Unit = {
            TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
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
                        open = false
                    }
                },
                enabled = valid,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(miniAppEditorAction(R.string.tool_generated_apply, text)) }
        }
        if (narrowWindow || shortWindow) {
            val start = rememberDatePickerState(initialSelectedDateMillis = initialStart, yearRange = 1..9999, initialDisplayMode = DisplayMode.Input)
            val end = rememberDatePickerState(initialSelectedDateMillis = initialEnd, yearRange = 1..9999, initialDisplayMode = DisplayMode.Input)
            val startDate = start.selectedDateMillis
            val endDate = end.selectedDateMillis
            DateRangeInputDialog(text, { open = false }, cancel, { apply(startDate, endDate) }) {
                val rangeError: @Composable () -> Unit = {
                    if (startDate != null && endDate != null && startDate > endDate) Text(
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
                if (narrowWindow) {
                    startInput()
                    endInput()
                    rangeError()
                } else {
                    rangeError()
                    Row(Modifier.fillMaxWidth()) {
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
            androidx.compose.material3.DatePickerDialog(onDismissRequest = { open = false }, confirmButton = confirm, dismissButton = cancel) {
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
            Text(stringResource(R.string.tool_route_back))
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
        val pageLabel = stringResource(R.string.tool_page, current + 1, count)
        Text("${node.getString("text")}: ${current+1} / $count",Modifier.semantics {stateDescription=pageLabel})
        HorizontalPager(pages,Modifier.fillMaxWidth().height(node.optInt("viewportHeight",320).dp),userScrollEnabled=enabled,key={it}) {page->Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {render(page)}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={event(node.getString("action"),current-1)},enabled=enabled && current>0) {Text(stringResource(R.string.tool_page_previous, node.getString("text")))}
            TextButton(onClick={event(node.getString("action"),current+1)},enabled=enabled && current<count-1) {Text(stringResource(R.string.tool_page_next, node.getString("text")))}
        }
    }
}
