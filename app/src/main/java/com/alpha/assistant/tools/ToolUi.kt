package com.alpha.assistant.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.DeviceReadHost
import com.alpha.assistant.capabilities.ForegroundCapabilityEffects
import com.alpha.assistant.capabilities.VideoContract
import com.alpha.assistant.cloud.KEY_ERRORS
import com.alpha.assistant.surfaces.PinToolDialog
import com.alpha.assistant.surfaces.PinToolKind
import com.alpha.assistant.surfaces.ToolWidgets
import com.alpha.assistant.tools.generation.NativePresentation
import com.alpha.assistant.tools.generation.PartClockSample
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal enum class MiniAppAction { Versions, Shortcut, Widget, Delete, Export, ManualEdit, RecurringSearch }

internal val LocalMiniAppLiveAllowed = staticCompositionLocalOf { true }

@Composable internal fun miniAppDeviceUnlocked(): Boolean {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    fun unlocked() = power.isInteractive && !ToolWidgets.isLocked(context)
    var available by remember(context) { mutableStateOf(unlocked()) }
    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                available = intent.action != Intent.ACTION_SCREEN_OFF && unlocked()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) available = unlocked() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return available
}

internal class MiniAppActions {
    data class Restore(val tool: ToolRecord, val revision: Int)
    private data class Surface(val toolId: String, val revision: Int, val paused: ArtifactSnapshot? = null)

    var request by mutableStateOf<Pair<String, MiniAppAction>?>(null)
    var restore by mutableStateOf<Restore?>(null)
        private set
    private val surfaces = mutableStateMapOf<Any, Surface>()

    fun covers(toolId: String) = request?.first == toolId || restore?.tool?.id == toolId

    fun requestRestore(tool: ToolRecord, revision: Int) {
        if (restore == null) restore = Restore(tool, revision)
    }

    fun finishRestore() { restore = null }

    fun registerSurface(handle: Any, toolId: String, revision: Int) {
        surfaces[handle] = Surface(toolId, revision)
    }

    fun releaseSurface(handle: Any) { surfaces.remove(handle) }

    fun surfacePaused(handle: Any, snapshot: ArtifactSnapshot?) {
        surfaces[handle]?.let { surfaces[handle] = it.copy(paused = snapshot) }
    }

    fun restoreSnapshot(tool: ToolRecord): ToolRecord? {
        val currentHosts = surfaces.values.filter { it.toolId == tool.id && it.revision == tool.revision }
        if (currentHosts.any { it.paused == null }) return null
        // An older host can finish its last input after the frame owner pauses.
        return currentHosts.mapNotNull { it.paused }
            .maxWithOrNull(compareBy<ArtifactSnapshot> { it.tool.stateVersion }.thenBy { it.sequence })
            ?.tool ?: tool
    }
}

@Composable internal fun toolErrorMessage(code: String?, message: String): String {
    val label = when (code) {
        "widget_spec" -> R.string.tool_generation_invalid
        "widget_state" -> R.string.tool_generation_state
        "widget_preview" -> R.string.tool_generation_preview_failed
        "widget_preview_state" -> R.string.tool_generation_preview_state
        "widget_preview_effects" -> R.string.tool_generation_preview_effects
        "widget_response_size" -> R.string.tool_generation_size
        "widget_generation" -> R.string.tool_generation_connection
        "javascript_unavailable" -> R.string.tool_javascript_unavailable
        "javascript_timeout" -> R.string.tool_javascript_timeout
        "javascript_busy" -> R.string.tool_javascript_busy
        else -> if (code?.startsWith("javascript_") == true) R.string.tool_javascript_stopped else return message
    }
    return stringResource(label)
}

@Composable internal fun ArtifactCard(controller: ToolController, toolId: String, actions: MiniAppActions,
    open: (String) -> Unit, openAutomation: (String) -> Unit, compact: Boolean = false) {
    val tool = controller.tools.find { it.id == toolId }
    if (tool == null) {
        val deleted by produceState(false, toolId, controller.busy) { value = readToolDetails { controller.wasDeleted(toolId) } == true }
        Text(stringResource(if (deleted) R.string.tool_deleted else R.string.tool_missing),
            style = MaterialTheme.typography.labelMedium)
        return
    }
    val spec = remember(tool.spec) { ToolSpec.availablePersisted(tool.spec) }
    val history by produceState<List<ToolRevision>?>(null, tool.id, tool.revision) {
        value = readToolDetails { controller.revisionHistory(tool.id) }
    }
    val previous = history?.firstOrNull { it.revision < tool.revision }
    val stopped = history?.firstOrNull { it.revision == tool.revision }?.stoppedAutomations.orEmpty()
    val title = ToolSpec.storedTitle(tool.spec)
    val covered = actions.covers(tool.id)
    var menu by remember(tool.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            val version = if (compact) stringResource(R.string.tool_details_versions, tool.revision, tool.stateVersion)
                else stringResource(R.string.tool_version, tool.revision)
            Text(version, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tool.archived) Text(stringResource(R.string.tool_placed_archive))
            if (spec == null) Text(stringResource(R.string.tool_closed_format))
            else if (compact) {
                if (spec.description.isNotBlank()) Text(spec.description, style = MaterialTheme.typography.bodySmall)
            } else if (!tool.archived) {
                Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    val graphicsHost = if (spec.surfaces.isNotEmpty()) remember(controller, tool.id, tool.revision) {
                        controller.artifactHost(tool)
                    } else null
                    var graphicsSnapshot by remember(graphicsHost) { mutableStateOf<ArtifactSnapshot?>(null) }
                    if (graphicsHost != null) {
                        CoveredToolSurface(tool, spec, true, 180.dp, graphicsHost, actions) { graphicsSnapshot = it }
                    }
                    MiniAppContent(controller, tool, tool, spec.title, enabled = !controller.busy && !covered, embedded = true,
                        presentation = spec.nativePresentation, graphicsHost = graphicsHost, graphicsSnapshot = graphicsSnapshot,
                        liveAllowed = !covered && !menu, allowVideo = true)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { open(tool.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.tool_open))
                }
                Box {
                    val label = stringResource(R.string.tool_card_actions, title)
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
                        AlphaIcon(Glyph.More)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        MiniAppMenuItems(controller, tool, actions) { menu = false }
                    }
                }
            }
            if (previous != null && !tool.archived && spec != null) {
                TextButton(onClick = { actions.requestRestore(tool, previous.revision) },
                    enabled = !controller.busy && actions.restore == null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.tool_restore_version, previous.revision))
                }
            }
            if (stopped.isNotEmpty()) {
                Text(stringResource(R.string.tool_stopped_automations), style = MaterialTheme.typography.labelMedium)
                stopped.forEach { automation ->
                    TextButton(onClick = { openAutomation(automation.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.tool_open_automation, automation.name))
                    }
                }
            }
        }
    }
}

@Composable internal fun CoveredToolSurface(
    tool: ToolRecord, spec: ToolSpec, enabled: Boolean, maxCanvasHeight: Dp, host: ArtifactUiHost,
    actions: MiniAppActions, immersive: Boolean = false, covered: Boolean = false,
    onCoverReady: (Boolean) -> Unit = {}, topControls: @Composable () -> Unit = {},
    onSnapshot: (ArtifactSnapshot) -> Unit = {},
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val handle = remember(tool.id, tool.revision, host) { Any() }
    var latestSnapshot by remember(handle) { mutableStateOf<ArtifactSnapshot?>(null) }
    DisposableEffect(host, enabled) {
        onDispose { if (enabled) (host as? LiveArtifactUiHost)?.cancelLiveInput() }
    }
    if (enabled && spec.surfaces.isNotEmpty()) {
        DisposableEffect(actions, lifecycle, handle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> actions.registerSurface(handle, tool.id, tool.revision)
                    Lifecycle.Event.ON_PAUSE -> actions.releaseSurface(handle)
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                actions.releaseSurface(handle)
            }
        }
    }
    key(host) {
        NativeToolSurface(tool, spec, enabled, maxCanvasHeight, host, immersive,
            covered = covered || actions.covers(tool.id),
            onCoverReady = { ready ->
                actions.surfacePaused(handle, latestSnapshot.takeIf { ready })
                onCoverReady(ready)
            },
            topControls = topControls,
            onSnapshot = {
                latestSnapshot = it
                onSnapshot(it)
            })
    }
}

private data class DraftPreviewContent(val spec: ToolSpec, val ui: String, val state: String, val drawing: String?)

@Composable private fun rememberDraftPreview(draft: ToolDraft?, snapshot: ToolDraftPreview?): DraftPreviewContent? =
    remember(draft?.candidate, draft?.previewUi, draft?.previewState, snapshot) {
        if (snapshot != null) {
            ToolSpec.availablePersisted(snapshot.candidateJson)?.let { spec ->
                DraftPreviewContent(spec, snapshot.uiJson, snapshot.stateJson, snapshot.surfacesJson)
            }
        } else {
            val savedDraft = draft ?: return@remember null
            val spec = savedDraft.candidate?.let(ToolSpec::availablePersisted) ?: return@remember null
            val ui = savedDraft.previewUi ?: return@remember null
            val state = savedDraft.previewState ?: return@remember null
            DraftPreviewContent(spec, ui, state, drawing = null)
        }
    }

@Composable private fun DraftPreviewContent.Render(drawingHeight: Dp) {
    val projected = if(spec.isSealedStandalone) clockedToolState(spec,ui,state,LocalMiniAppLiveAllowed.current) else null
    if(spec.isSealedStandalone && projected?.isSuccess != true) {
        if(projected?.isFailure == true) Text(stringResource(R.string.tool_closed_format))
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    StaticDraftDrawing(spec, drawing, drawingHeight)
    ToolContent(ui, projected?.getOrNull() ?: state, false, heading = spec.title, embedded = true,
        presentation = spec.nativePresentation, appLanguage = spec.appLanguage) { _, _ -> }
}

@Composable internal fun GeneratedToolPreview(draft: ToolDraft?, snapshot: ToolDraftPreview?) {
    val preview = rememberDraftPreview(draft, snapshot) ?: return
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(preview.spec.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tool_preview_provisional), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                preview.Render(150.dp)
            }
        }
    }
}

@Composable private fun MiniAppMenuItems(
    controller: ToolController,
    tool: ToolRecord,
    actions: MiniAppActions,
    item: @Composable (Int, Glyph, Boolean, () -> Unit) -> Unit = { label, _, enabled, action ->
        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = action, enabled = enabled)
    },
    archive: (() -> Unit)? = null,
    archiveEnabled: Boolean = false,
    dismiss: () -> Unit,
) {
    val available = remember(tool.spec) { ToolSpec.availablePersisted(tool.spec) != null }
    val enabled = !controller.busy
    item(R.string.tool_versions, Glyph.History, true) { dismiss(); actions.request = tool.id to MiniAppAction.Versions }
    if (tool.saved && !tool.archived && available) {
        item(R.string.tool_export, Glyph.Arrow, enabled) { dismiss(); actions.request = tool.id to MiniAppAction.Export }
        item(R.string.tool_manual_edit, Glyph.Compose, enabled) { dismiss(); actions.request = tool.id to MiniAppAction.ManualEdit }
        item(if (tool.onDesktop) R.string.tool_desktop_remove else R.string.tool_desktop_add, Glyph.Grid, enabled) {
            dismiss()
            controller.setDesktopPlacement(tool, !tool.onDesktop)
        }
        item(R.string.tool_shortcut_add, Glyph.Link, enabled) { dismiss(); actions.request = tool.id to MiniAppAction.Shortcut }
        item(R.string.tool_widget_add, Glyph.Screen, enabled) { dismiss(); actions.request = tool.id to MiniAppAction.Widget }
        item(R.string.recurring_search_title, Glyph.Repeat, enabled) { dismiss(); actions.request = tool.id to MiniAppAction.RecurringSearch }
    }
    if (archive != null && tool.saved && !tool.archived) {
        item(R.string.tool_archive_action, Glyph.File, archiveEnabled && enabled) { dismiss(); archive() }
    }
    if (tool.saved) item(R.string.delete, Glyph.Close, enabled) { dismiss(); actions.request = tool.id to MiniAppAction.Delete }
}

@Composable private fun MiniAppActionRow(label: Int, glyph: Glyph, enabled: Boolean = true, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val color = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        label == R.string.delete -> colors.error
        else -> colors.onSurface
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = action)
            .heightIn(min = 52.dp).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(36.dp), shape = RoundedCornerShape(10.dp), color = colors.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center) { AlphaIcon(glyph, color = color) }
        }
        Text(stringResource(label), Modifier.weight(1f), color = color, style = MaterialTheme.typography.bodyLarge)
        AlphaIcon(Glyph.Chevron, color = color)
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
}

@Composable internal fun MiniAppActionDialogs(controller: ToolController, actions: MiniAppActions) {
    com.alpha.assistant.capabilities.CapabilityPromptHost(controller)
    val restore = actions.restore
    var restoreUnavailable by remember { mutableStateOf(false) }
    if (restore != null) LaunchedEffect(restore) {
        val paused = withTimeoutOrNull(5000) {
            snapshotFlow { if (controller.busy) null else actions.restoreSnapshot(restore.tool) }
                .filterNotNull().first()
        }
        if (paused == null) restoreUnavailable = true
        else {
            controller.rollback(paused, restore.revision)
            snapshotFlow { controller.busy }.first { !it }
        }
        actions.finishRestore()
    }
    if (restoreUnavailable) AlertDialog(
        onDismissRequest = { restoreUnavailable = false },
        title = { Text(stringResource(R.string.tool_versions)) },
        text = { Text(stringResource(R.string.tool_restore_pause_failed)) },
        confirmButton = { TextButton(onClick = { restoreUnavailable = false }) { Text(stringResource(R.string.tool_dialog_close)) } },
    )
    val (id, action) = actions.request ?: return
    val tool = controller.tools.find { it.id == id }
    if (tool == null) {
        LaunchedEffect(id) { actions.request = null }
        return
    }
    val dismiss = { actions.request = null }
    key(id, action) {
        when (action) {
            MiniAppAction.Widget -> PinToolDialog(tool, only = PinToolKind.Widget, controller = controller, dismiss = dismiss)
            MiniAppAction.Shortcut -> PinToolDialog(tool, only = PinToolKind.Shortcut, dismiss = dismiss)
            MiniAppAction.Versions -> VersionsDialog(controller, tool, actions, dismiss)
            MiniAppAction.Delete -> DeleteMiniAppDialog(controller, tool, dismiss)
            MiniAppAction.Export, MiniAppAction.ManualEdit ->
                ToolPortabilityDialog(controller, tool, export = action == MiniAppAction.Export, dismiss)
            MiniAppAction.RecurringSearch -> RecurringSearchDialog(controller, tool, dismiss)
        }
    }
}

@Composable private fun VersionsDialog(controller: ToolController, tool: ToolRecord, actions: MiniAppActions, dismiss: () -> Unit) {
    val versions by produceState<List<ToolRevision>?>(null, tool.id, tool.revision) {
        value = null
        value = readToolDetails { controller.revisionHistory(tool.id) }.orEmpty()
    }
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    val unavailable = stringResource(R.string.tool_versions_unavailable)
    val availability by produceState(emptyMap<Int, ToolRestoreAvailability>(), tool, versions, controller.busy) {
        value = emptyMap()
        if (!tool.archived && !controller.busy) {
            for (revision in versions.orEmpty().filter { it.revision != tool.revision }) {
                val checked = readToolDetails { controller.restoreAvailability(tool, revision.revision) }
                    ?: ToolRestoreAvailability(unavailable)
                value = value + (revision.revision to checked)
            }
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.tool_versions)) },
        text = {
            val loaded = versions
            when {
                loaded == null -> CircularProgressIndicator(Modifier.size(24.dp))
                loaded.isEmpty() -> Text(unavailable)
                else -> LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(loaded, key = { it.revision }) { revision ->
                        val checked = availability[revision.revision]
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider()
                            val title = if (revision.label.isBlank()) stringResource(R.string.tool_version, revision.revision)
                                else stringResource(R.string.tool_version_label, revision.revision, revision.label)
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            if (revision.createdAt > 0) {
                                Text(dateFormat.format(Date(revision.createdAt)), style = MaterialTheme.typography.bodySmall)
                            }
                            when {
                                revision.revision == tool.revision -> Text(stringResource(R.string.tool_version_current))
                                tool.archived -> Text(stringResource(R.string.tool_restore_archived))
                                checked == null -> Text(stringResource(R.string.tool_version_checking))
                                checked.reason != null -> Text(toolErrorMessage(checked.errorCode, checked.reason))
                                else -> TextButton(onClick = { actions.requestRestore(tool, revision.revision); dismiss() },
                                    enabled = !controller.busy && actions.restore == null, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.tool_restore_version, revision.revision))
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.tool_dialog_close)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeleteMiniAppDialog(controller: ToolController, tool: ToolRecord, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmedScope by remember { mutableStateOf<ToolDeletionSnapshot?>(null) }
    var checking by remember { mutableStateOf(true) }
    var changed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val fallback = stringResource(R.string.tool_delete_unavailable)
    suspend fun prepare(current: ToolRecord): ToolDeletionSnapshot? {
        return try { controller.prepareDeletion(current) }
        catch (cancel: CancellationException) { throw cancel }
        catch (problem: Exception) {
            failure = problem.message?.takeIf { it.any { char -> char in 'А'..'я' || char == 'ё' } } ?: fallback
            null
        }
    }
    LaunchedEffect(tool, controller.busy) {
        if (confirmedScope == null && !controller.busy) {
            checking = true
            failure = null
            confirmedScope = prepare(tool)
            checking = false
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val captured = confirmedScope
            Text(stringResource(R.string.tool_delete_title, ToolSpec.storedTitle((captured?.tool ?: tool).spec)),
                Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (checking) Text(stringResource(R.string.tool_delete_loading))
                if (changed) Text(stringResource(R.string.tool_delete_changed), color = MaterialTheme.colorScheme.error)
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (captured != null) {
                    Text(stringResource(R.string.tool_delete_local, captured.revisionCount))
                    Text(stringResource(if (captured.tool.onDesktop) R.string.tool_delete_desktop else R.string.tool_delete_no_desktop))
                    Text(stringResource(R.string.tool_delete_widgets, captured.widgetIds.size))
                    if (captured.widgetIds.isNotEmpty()) Text(stringResource(R.string.tool_delete_widget_placeholders))
                    if (captured.automations.isEmpty()) Text(stringResource(R.string.tool_delete_no_automations))
                    else {
                        Text(stringResource(R.string.tool_delete_automations))
                        captured.automations.forEach { Text(it.name) }
                    }
                    Text(stringResource(R.string.tool_delete_external))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    checking = true
                    failure = null
                    scope.launch {
                        val current = prepare(tool)
                        if (current != null && current == captured && !controller.busy) {
                            controller.delete(current)
                            snapshotFlow { controller.busy }.first { !it }
                            val remaining = controller.tools.find { it.id == tool.id }
                            if (remaining == null) {
                                dismiss()
                            } else {
                                failure = controller.error
                                val latest = prepare(remaining)
                                if (latest != null) {
                                    confirmedScope = latest
                                    changed = latest != current
                                }
                            }
                        } else if (current != null) {
                            confirmedScope = current
                            changed = current != captured
                        }
                        checking = false
                    }
                }, enabled = !checking && !controller.busy && captured != null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.delete), color = if (!checking && !controller.busy && captured != null)
                        MaterialTheme.colorScheme.error else LocalContentColor.current)
                }
            }
        }
    }
}

private suspend fun <T> readToolDetails(load: suspend () -> T): T? = try { load() }
catch (cancel: CancellationException) { throw cancel }
catch (_: Exception) { null }

@Composable internal fun ToolsPane(controller: ToolController, conversationId: String?, actions: MiniAppActions,
    requests: MutableState<Map<String, String>>, refine: (String) -> Unit, immersive: Boolean=false, exitImmersive: () -> Unit = {}, settings: () -> Unit, close: () -> Unit) {
    val draft=controller.drafts.find {it.id == controller.selectedDraftId}
    val tool=controller.tools.find {it.id == (controller.selectedId ?: draft?.toolId)}
    val contextId=tool?.id ?: draft?.toolId ?: "new"
    val prompt=requests.value[contextId] ?: draft?.prompt.orEmpty()
    val edit: (String) -> Unit = {requests.value=requests.value + (contextId to it)}
    LaunchedEffect(contextId,draft?.id) {
        if(draft != null && requests.value[contextId].isNullOrEmpty()) edit(draft.prompt)
    }
    var archiveConfirm by remember(controller.selectedId) {mutableStateOf(false)}
    val focus=LocalFocusManager.current
    val submit={
        focus.clearFocus()
        controller.generate(prompt,tool?.id,conversationId,emptyList())
    }
    if (tool != null || draft != null) key(tool?.id, draft?.id) {
        FocusedToolPane(controller,tool,draft,prompt,edit,submit,{archiveConfirm=true},actions,refine,immersive,exitImmersive,settings,close,
            hostCovered = archiveConfirm)
    }
    if(archiveConfirm && tool != null && !tool.archived) AlertDialog(onDismissRequest = { archiveConfirm = false },
        title = { Text(stringResource(R.string.tool_archive_title, ToolSpec.storedTitle(tool.spec))) },text = {
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tool_archive_data))
                Text(stringResource(R.string.tool_archive_automations))
                if(controller.dependentRules.isEmpty()) Text(stringResource(R.string.tool_delete_no_automations))
                else { Text(stringResource(R.string.tool_archive_dependencies)); controller.dependentRules.forEach { Text(it) } }
                Text(stringResource(R.string.tool_archive_effects))
                Text(stringResource(R.string.tool_archive_widgets))
            }
        },confirmButton = { TextButton(onClick = { archiveConfirm = false; controller.archive(tool) },enabled = !controller.busy) { Text(stringResource(R.string.tool_archive_action)) } },
        dismissButton = { TextButton(onClick = { archiveConfirm = false }) { Text(stringResource(R.string.cancel)) } })

}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable private fun FocusedToolPane(controller: ToolController, tool: ToolRecord?, draft: ToolDraft?,
    prompt: String, edit: (String) -> Unit, submit: () -> Unit, archive: () -> Unit, actions: MiniAppActions, refine: (String) -> Unit,
    immersive: Boolean, exitImmersive: () -> Unit, settings: () -> Unit, close: () -> Unit, hostCovered: Boolean) {
    val spec=remember(tool?.spec) {tool?.let {ToolSpec.availablePersisted(it.spec)}}
    val graphicsHost = if (tool != null && spec != null && (spec.surfaces.isNotEmpty() || immersive)) {
        remember(controller, tool.id, tool.revision) {
            if (spec.surfaces.isEmpty()) controller.nonGraphicsLiveInputHost(tool) else controller.artifactHost(tool)
        }
    } else null
    var artifactSnapshot by remember(graphicsHost) {mutableStateOf<ArtifactSnapshot?>(null)}
    val displayed=artifactSnapshot?.tool?.takeIf {it.id == tool?.id && it.revision == tool.revision && it.stateVersion >= tool.stateVersion} ?: tool
    val preview = rememberDraftPreview(draft, draft?.id?.let(controller::previewFor))
    val readyDraft = draft?.takeIf { preview != null && it.candidate != null && it.previewUi != null && it.previewState != null }
    val candidate = preview?.spec
    var menu by remember(tool?.id, draft?.id) {mutableStateOf(false)}
    var details by rememberSaveable(tool?.id) {mutableStateOf<String?>(null)}
    var appControls by remember(tool?.id,tool?.revision) {mutableStateOf(false)}
    var controlsReady by remember(tool?.id,tool?.revision) {mutableStateOf(false)}
    var liveInputUnfinished by remember(tool?.id, tool?.revision) { mutableStateOf(false) }
    var liveInputUnsaved by rememberSaveable(tool?.id, tool?.revision) { mutableStateOf(false) }
    if(immersive && tool!=null && spec!=null && graphicsHost != null && preview == null) {
        var finishInputNotice by remember { mutableStateOf(false) }
        var priorUnsavedNotice by remember { mutableStateOf(false) }
        fun mayCloseControls(): Boolean {
            if (liveInputUnfinished) finishInputNotice = true
            return !liveInputUnfinished
        }
        BackHandler { if (!appControls) exitImmersive() else if (mayCloseControls()) appControls = false }
        CoveredToolSurface(tool,spec,!tool.archived,0.dp,graphicsHost,actions,immersive=true,covered=appControls,onCoverReady={controlsReady=it},topControls={
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick=exitImmersive,modifier=Modifier.heightIn(min=48.dp)) {Text(stringResource(R.string.tool_full_screen_exit))}
                TextButton(onClick={controlsReady=false;priorUnsavedNotice=liveInputUnsaved;appControls=true},modifier=Modifier.heightIn(min=48.dp)) {Text(stringResource(R.string.tool_controls))}
            }
        }) {artifactSnapshot=it}
        if(appControls) {
            val sheet = rememberModalBottomSheetState(confirmValueChange = { it != SheetValue.Hidden || !liveInputUnfinished })
            val scope = rememberCoroutineScope()
            ModalBottomSheet(onDismissRequest={if(mayCloseControls()) appControls=false}, sheetState=sheet,
                sheetGesturesEnabled = !liveInputUnfinished,
                properties=ModalBottomSheetProperties(shouldDismissOnBackPress=false)) {
                // The sheet inherits the shell's dispatcher, but system Back goes to the dialog window.
                val dialogOwner = checkNotNull(LocalView.current.findViewTreeNavigationEventDispatcherOwner())
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dialogOwner) {
                    BackHandler {
                        if (mayCloseControls()) scope.launch {
                            sheet.hide()
                            if (!sheet.isVisible) appControls = false
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (finishInputNotice) Text(stringResource(R.string.tool_live_input_finish_first))
                    if (priorUnsavedNotice) Text(stringResource(R.string.tool_live_input_unsaved))
                    TextButton(onClick = { if (mayCloseControls()) refine(tool.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.tool_refine)) }
                    MiniAppContent(controller,tool,displayed,spec.title,enabled=controlsReady && !controller.busy,
                        presentation = spec.nativePresentation, graphicsHost = graphicsHost, graphicsSnapshot = artifactSnapshot,
                        liveAllowed = controlsReady && !actions.covers(tool.id), allowVideo = true,
                        onLiveInputState = { unfinished, unsaved ->
                            liveInputUnfinished = unfinished
                            if (!unfinished) finishInputNotice = false
                            if (unsaved || unfinished) liveInputUnsaved = unsaved
                            priorUnsavedNotice = false
                        })
                }
            }
        }
        return
    }
    val libraryBackLabel = stringResource(R.string.tool_library_back)
    val optionsLabel = stringResource(R.string.tool_options)
    val contentLabel = stringResource(R.string.tool_content)
    val refinementLabel = stringResource(R.string.tool_refinement)
    val savedDraft = draft?.takeIf { tool?.saved == true }
    val canEditRequest = tool == null || (!tool.saved && !tool.archived && spec != null)
    val scroll=rememberScrollState()
    LaunchedEffect(details,candidate?.json) {scroll.scrollTo(0)}
    BackHandler {if(details != null) details=null else controller.select(null)}
    if (menu) {
        val dismiss = { menu = false }
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = dismiss,
            sheetState = sheet,
            containerColor = MaterialTheme.colorScheme.surface,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        ) {
            // Android Back is sent to the sheet window, which has its own dispatcher.
            val dialogOwner = checkNotNull(LocalView.current.findViewTreeNavigationEventDispatcherOwner())
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dialogOwner) {
                BackHandler {
                    scope.launch {
                        sheet.hide()
                        if (!sheet.isVisible) dismiss()
                    }
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                Text(optionsLabel, Modifier.padding(bottom = 12.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                if (tool != null) {
                    MiniAppActionRow(R.string.tool_details_desktop, Glyph.Screen) { details = "details"; dismiss() }
                    MiniAppActionRow(R.string.tool_access_history, Glyph.Shield) { details = "access"; dismiss() }
                    MiniAppMenuItems(controller, tool, actions, item = { label, glyph, enabled, action ->
                        MiniAppActionRow(label, glyph, enabled, action)
                    }, archive = archive, archiveEnabled = draft == null, dismiss = dismiss)
                }
                MiniAppActionRow(R.string.tool_chat, Glyph.Back) { dismiss(); close() }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    val refinementHeight=minOf(240.dp,maxHeight*0.45f)
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={controller.select(null)},modifier=Modifier.semantics {contentDescription=libraryBackLabel}) {AlphaIcon(Glyph.Back)}
            val header = if (preview != null) R.string.tool_preview_changes else R.string.mini_apps
            Text(stringResource(header), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick={menu=true},modifier=Modifier.semantics {contentDescription=optionsLabel}) {AlphaIcon(Glyph.More)}
        }
        if(controller.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(if(draft != null && readyDraft == null) R.string.tool_creating_preview else R.string.tool_working),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=controller::cancel) {Text(stringResource(R.string.cancel))}
            }
        }
        controller.error?.let {message ->
            Surface(color=MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(toolErrorMessage(controller.errorCode, message),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick=controller::clearError) {Text(stringResource(R.string.tool_dismiss))}
                    }
                    // A row with both buttons leaves too little width for the message on a phone.
                    if(controller.errorCode in KEY_ERRORS) TextButton(onClick=settings) {Text(stringResource(R.string.open_settings))}
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        // Reserve the metadata, status, two native control rows, saved summary, and pane spacing.
        val canvasHeight=(maxHeight-330.dp).coerceAtLeast(120.dp)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).semantics {contentDescription=contentLabel}.padding(horizontal = 20.dp, vertical = 16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if (preview != null) Text(stringResource(if (readyDraft == null) R.string.tool_preview_provisional else R.string.tool_preview_label),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(candidate?.title ?: spec?.title ?: tool?.let { ToolSpec.storedTitle(it.spec) } ?: stringResource(R.string.tool_new),
                    Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                if (tool != null && spec != null && tool.saved && !tool.archived && draft == null) {
                    TextButton(onClick = { refine(tool.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.tool_refine))
                    }
                }
            }
            val description = candidate?.description ?: spec?.description
            val descriptionAfterContent = preview == null && tool?.saved == true && !tool.archived
            if (!descriptionAfterContent && !description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(preview != null && spec != null) Text(stringResource(R.string.tool_preview_changes_label, spec.title),style=MaterialTheme.typography.bodySmall)
            if(details != null) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(if(details == "access") R.string.tool_access_history else R.string.tool_details),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick={details=null}) {Text(stringResource(R.string.tool_details_close))}
                }
                if(tool != null && details == "details") {
                    Text(stringResource(R.string.tool_details_versions, tool.revision, tool.stateVersion),style=MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.tool_changes_privacy),style=MaterialTheme.typography.bodySmall)
                    if(tool.saved && !tool.archived && spec!=null) com.alpha.assistant.surfaces.PinToolButton(tool)
                    if(!tool.saved && !tool.archived) Button(onClick={controller.save(tool.id)},enabled=!controller.busy) {Text(stringResource(R.string.tool_save_library))}
                }
            }
            if(tool != null && spec != null && draft == null) {
                controller.effects.lastOrNull()?.let {effect ->
                    Text(effect.result.message,style=MaterialTheme.typography.bodySmall,color=if(effect.result.status in setOf("failed","denied","revoked","unavailable")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                com.alpha.assistant.capabilities.ToolCapabilityPanel(controller,tool,showDetails=details == "access")
            }
            if(preview != null) preview.Render(canvasHeight)
            else if(tool != null) {
                if(tool.archived) {
                    Text(stringResource(R.string.tool_archived_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick={controller.recover(tool)}, enabled=!controller.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.tool_archive_restore))
                    }
                }
                if(spec != null && draft == null && graphicsHost != null) CoveredToolSurface(tool, spec, !tool.archived,
                    canvasHeight, graphicsHost, actions, covered = details != null || menu || hostCovered) { artifactSnapshot = it }
                if(spec == null) Text(stringResource(R.string.tool_closed_format_detail))
                if(spec != null) MiniAppContent(controller,tool,displayed,spec.title,
                    enabled=!controller.busy && draft == null && !tool.archived && !menu && !hostCovered && !actions.covers(tool.id),
                    routeSystemBackEnabled = details == null, presentation = spec.nativePresentation,
                    graphicsHost = graphicsHost, graphicsSnapshot = artifactSnapshot,
                    openNonGraphics = draft == null, allowVideo = true,
                    liveAllowed = draft == null && details == null && !menu && !hostCovered && !actions.covers(tool.id))
            } else Text(draft?.prompt.orEmpty(),style=MaterialTheme.typography.bodyLarge)
            if (tool != null && spec != null && draft == null && tool.saved && !tool.archived) {
                SourceRefreshControl(controller, tool)
            }
            if (descriptionAfterContent && !description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
        if (readyDraft != null || savedDraft != null || canEditRequest) Surface(
            color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp,shadowElevation=4.dp
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max=refinementHeight).verticalScroll(rememberScrollState())
                .semantics {contentDescription=refinementLabel}.padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(readyDraft != null) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={controller.reject(readyDraft.id)},enabled=!controller.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text(stringResource(R.string.tool_discard_preview))}
                        Button(onClick={controller.accept(readyDraft.id)},enabled=!controller.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text(stringResource(R.string.tool_apply_changes))}
                    }
                } else if (savedDraft != null) {
                    Text(savedDraft.prompt, style=MaterialTheme.typography.bodyLarge)
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={controller.reject(savedDraft.id)},enabled=!controller.busy,
                            modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.tool_discard_draft)) }
                        if (tool?.archived == false && spec != null) {
                            Button(onClick={controller.retry(savedDraft.id)},enabled=!controller.busy,
                                modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.tool_retry_preview)) }
                        }
                    }
                } else if(canEditRequest) {
                    OutlinedTextField(prompt,edit,enabled=!controller.busy,label={Text(stringResource(if(tool == null) R.string.tool_request else R.string.tool_refine_request))},
                        modifier=Modifier.fillMaxWidth().heightIn(max=140.dp),maxLines=4,shape=RoundedCornerShape(20.dp))
                    Text(stringResource(R.string.tool_changes_privacy_short),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(draft != null && !controller.busy) TextButton(onClick={controller.reject(draft.id)}, modifier=Modifier.heightIn(min=48.dp)) {Text(stringResource(R.string.tool_discard_draft))}
                        val retry=draft != null && prompt == draft.prompt
                        Button(onClick=if(retry) {{controller.retry(checkNotNull(draft).id)}} else submit,
                            enabled=!controller.busy && prompt.isNotBlank() && prompt.length <= 16000,modifier=Modifier.heightIn(min=48.dp)) {Text(stringResource(if(retry) R.string.tool_retry_preview else R.string.tool_preview_changes))}
                    }
                }
            }
        }
    }
    }
}

private fun hasLiveControl(node: JSONObject): Boolean {
    if (ToolUiCatalog.isLiveInput(node)) return true
    if (node.optJSONObject("rowTemplate")?.let(::hasLiveControl) == true) return true
    val children = node.optJSONArray("children") ?: return false
    return (0 until children.length()).any { hasLiveControl(children.getJSONObject(it)) }
}

@Composable private fun nonGraphicsSnapshot(host: LiveArtifactUiHost?, inWindow: Boolean, stateVersion: Int): Result<ArtifactSnapshot>? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentVisibility by rememberUpdatedState(inWindow)
    val currentVersion by rememberUpdatedState(stateVersion)
    var lease by remember(host) { mutableStateOf<Result<ArtifactSnapshot>?>(null) }
    LaunchedEffect(host, lifecycle) {
        if (host == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { currentVisibility }.collectLatest { showing ->
                if (!showing) return@collectLatest
                lease = null
                var opened = false
                try {
                    var current = host.open()
                    opened = true
                    lease = Result.success(current)
                    snapshotFlow { currentVersion }.collect { version ->
                        if (version > current.tool.stateVersion) {
                            current = host.snapshot()
                            lease = Result.success(current)
                        }
                    }
                } catch (cancel: CancellationException) {
                    lease = null
                    throw cancel
                } catch (failure: Exception) {
                    lease = Result.failure(failure)
                } finally {
                    host.cancelLiveInput()
                    if (opened) withContext(NonCancellable) { runCatching { host.close() } }
                }
            }
        }
    }
    return lease
}

@Composable private fun clockedToolState(spec: ToolSpec, ui: String, state: String, visible: Boolean): Result<String>? {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sample by produceState<PartClockSample?>(null, context, lifecycle, visible) {
        if (!visible) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            callbackFlow {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { trySend(Unit) }
                }
                val timeChanges = IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
                ContextCompat.registerReceiver(context, receiver, timeChanges, ContextCompat.RECEIVER_NOT_EXPORTED)
                trySend(Unit)
                awaitClose { context.unregisterReceiver(receiver) }
            }.conflate().collect { value = localPartClock() }
        }
    }
    return remember(spec, ui, state, sample) {
        sample?.let { clock -> runCatching {
            val view = projectToolState(spec, spec.validateState(state), clock)
            spec.validateUi(JSONObject(ui), view)
            view.toString()
        } }
    }
}

@Composable internal fun MiniAppContent(controller: ToolController, tool: ToolRecord, displayed: ToolRecord?, heading: String,
    enabled: Boolean, embedded: Boolean = false, routeSystemBackEnabled: Boolean = true, presentation: NativePresentation? = null,
    graphicsHost: ArtifactUiHost? = null, graphicsSnapshot: ArtifactSnapshot? = null, openNonGraphics: Boolean = false,
    liveAllowed: Boolean = true, allowVideo: Boolean = false, onLiveInputState: (Boolean, Boolean) -> Unit = { _, _ -> }) {
    val navigationContext = LocalContext.current
    val navigationLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val navigationForeground = {
        navigationLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(navigationContext)
    }
    val initial = checkNotNull(displayed)
    val spec = remember(tool.spec) { ToolSpec.availablePersisted(tool.spec) }
    val hasLive = remember(initial.ui) { hasLiveControl(JSONObject(initial.ui)) }
    val localHost = if (graphicsHost == null && (openNonGraphics || hasLive) && !tool.archived) {
        if (spec?.surfaces?.isEmpty() == true) remember(controller, tool.id, tool.revision) { controller.nonGraphicsLiveInputHost(tool) }
        else null
    } else null
    var inWindow by remember { mutableStateOf(false) }
    val visible = inWindow && !tool.archived && liveAllowed && LocalMiniAppLiveAllowed.current
    val deviceHost = if (visible && spec?.capabilities?.any { it in ForegroundCapabilityEffects.deliveryIds } == true)
        remember(controller,tool.id,tool.revision) { DeviceReadHost(tool.id,tool.revision) } else null
    if (deviceHost != null) {
        DisposableEffect(controller,navigationLifecycle,deviceHost) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START, Lifecycle.Event.ON_PAUSE -> controller.updateDeviceReadHost(deviceHost,false)
                    Lifecycle.Event.ON_RESUME -> controller.updateDeviceReadHost(deviceHost,true)
                    Lifecycle.Event.ON_STOP -> controller.stopDeviceReadHost(deviceHost)
                    Lifecycle.Event.ON_DESTROY -> controller.updateDeviceReadHost(deviceHost,null)
                    else -> Unit
                }
            }
            navigationLifecycle.addObserver(observer)
            onDispose {
                navigationLifecycle.removeObserver(observer)
                controller.updateDeviceReadHost(deviceHost,null)
            }
        }
        if (com.alpha.assistant.capabilities.MiniAppAi.ID in spec?.capabilities.orEmpty()) {
            val view = LocalView.current
            DisposableEffect(controller, deviceHost, view) {
                fun refresh() = controller.updateDeviceReadFocus(deviceHost, view.hasWindowFocus())
                val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
                view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
                refresh()
                onDispose {
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
                    controller.updateDeviceReadFocus(deviceHost, false)
                }
            }
        }
    }
    // A modal cover cancels editing without reopening a mini-app that remains on screen.
    val localLease = nonGraphicsSnapshot(localHost, inWindow, initial.stateVersion)
    val channel = (graphicsHost as? LiveArtifactUiHost) ?: localHost
    val snapshot = graphicsSnapshot ?: localLease?.getOrNull()
    val current = snapshot?.tool?.takeIf { it.stateVersion >= initial.stateVersion } ?: initial
    val needsProjection = spec?.composition?.hasReadProjections == true
    val projected = if (needsProjection) clockedToolState(checkNotNull(spec), current.ui, current.state, visible) else null
    val displayedVideo by rememberUpdatedState(current)
    val videoHost = if (allowVideo && deviceHost != null && tool.saved && VideoContract.PLAY in spec?.capabilities.orEmpty()) {
        remember(controller,deviceHost) {controller.nativeVideoHost(deviceHost) {displayedVideo}}
    } else null
    if (videoHost != null) DisposableEffect(controller,videoHost) {
        controller.attachVideoHost(videoHost)
        onDispose {controller.detachVideoHost(videoHost)}
    }
    val liveInput = when {
        channel != null && snapshot != null -> NativeLiveInput.automatic(channel, snapshot, visible)
        hasLive && (channel == null || localLease?.isFailure == true) ->
            NativeLiveInput.apply(current, visible, controller::applyLiveInput)
        else -> null
    }
    val calendarHost=remember(current.id,current.revision,current.stateVersion,controller.effects) {
        controller.calendarUiHost(current,navigationForeground) {intent ->check(navigationForeground());navigationContext.startActivity(intent)}
    }
    val timerHost = if (!embedded && tool.saved && !tool.archived) {
        com.alpha.assistant.timer.ForegroundTimerUiHost(controller, tool, visible) {
            navigationForeground() && navigationContext.getSystemService(PowerManager::class.java).isInteractive
        }
    } else null
    val localButtons = spec?.canQueueLocalButtons == true
    val buttonHost = remember(controller, tool.id, tool.revision) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val currentVisibility by rememberUpdatedState(visible)
    DisposableEffect(buttonHost) { onDispose { buttonHost.set(false); controller.releaseLocalButtonHost(buttonHost) } }
    val visibleUi = remember(current.ui, localButtons) {
        if (localButtons) JSONObject(current.ui).takeUnless(::hasLiveControl) else null
    }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { inWindow = !it.boundsInWindow().isEmpty }) {
        if (projected?.isFailure == true) Text(stringResource(R.string.tool_closed_format))
        else ToolContent(current.ui,projected?.getOrNull() ?: current.state,enabled && (!needsProjection || projected != null),
            heading=heading,calendarHost=calendarHost,embedded=embedded,
            background=if(embedded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            imageVersion=(tool.id to current.revision),imageLoader={controller.nativeImage(tool.id,it)},imageAvailable={controller.nativeImageAvailable(tool.id,it)},
            routeSystemBackEnabled = routeSystemBackEnabled, presentation = presentation, liveInput = liveInput, videoHost = videoHost, timerHost = timerHost,
            onLiveInputState = onLiveInputState, appLanguage = spec?.appLanguage) {id,value ->
            if (value == null && visibleUi?.let { ToolUiCatalog.action(it, id)?.optString("type") } == "button") {
                controller.queueLocalButton(current, id, buttonHost) { buttonHost.get() && currentVisibility && navigationForeground() }
            } else controller.interact(current,id,value,deviceHost,navigationForeground) {intent ->check(navigationForeground());navigationContext.startActivity(intent)}
        }
    }
}

@Composable internal fun ImmersiveSystemBars(enabled: Boolean) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val view=androidx.compose.ui.platform.LocalView.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val activity=generateSequence(context) {(it as? android.content.ContextWrapper)?.baseContext}.filterIsInstance<android.app.Activity>().firstOrNull()
    DisposableEffect(activity,view,lifecycle,enabled) {
        val window=activity?.window
        val controller=window?.let {androidx.core.view.WindowCompat.getInsetsController(it,view)}
        val insets=androidx.core.view.ViewCompat.getRootWindowInsets(view)
        val bars=androidx.core.view.WindowInsetsCompat.Type.systemBars()
        val status=androidx.core.view.WindowInsetsCompat.Type.statusBars()
        val navigation=androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        val behavior=controller?.systemBarsBehavior
        fun restore() {
            if(enabled && controller!=null) {
                controller.systemBarsBehavior=checkNotNull(behavior)
                for(type in listOf(status,navigation)) {
                    if(insets?.isVisible(type)!=false)controller.show(type) else controller.hide(type)
                }
            }
        }
        fun hide() {
            if(enabled && controller!=null) {
                controller.systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(bars)
            }
        }
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)hide()
            if(event==androidx.lifecycle.Lifecycle.Event.ON_PAUSE)restore()
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))hide()
        onDispose {lifecycle.removeObserver(observer);restore()}
    }
}
