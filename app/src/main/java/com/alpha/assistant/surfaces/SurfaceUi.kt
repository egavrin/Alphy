package com.alpha.assistant.surfaces

import android.content.SharedPreferences
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alpha.assistant.R
import com.alpha.assistant.surfaces.widgetauthoring.WidgetAuthoringPreview
import com.alpha.assistant.surfaces.widgetauthoring.WidgetAuthoringResult
import com.alpha.assistant.surfaces.widgetauthoring.WidgetDraftReview
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun WidgetOptions(prefs: WidgetPreferences, change: (WidgetPreferences) -> Unit) {
    Text(stringResource(R.string.widget_format),style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("adaptive" to R.string.widget_format_adaptive,"compact" to R.string.widget_format_compact,
            "wide" to R.string.widget_format_wide).forEach { (format,label) ->
            FilterChip(prefs.format == format,{ change(prefs.copy(format = format)) },{ Text(stringResource(label)) })
        }
    }
    Row { Checkbox(prefs.details,{ change(prefs.copy(details = it)) }); Text(stringResource(R.string.widget_details),Modifier.padding(top = 12.dp)) }
}

@Composable fun WidgetPreview(tool: ToolRecord, prefs: WidgetPreferences) {
    val context = LocalContext.current
    val size = ToolGlanceWidget.previewSize(prefs)
    val preview by produceState<Result<RemoteViews>?>(null,context,tool,prefs) {
        value = null
        value = try {
            Result.success(ToolGlanceWidget.preview(context,tool,prefs,size))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    Text(stringResource(if (preview?.isFailure == true) R.string.widget_preview_failed else R.string.widget_preview),
        style = MaterialTheme.typography.labelMedium)
    AndroidView(
        factory = ::FrameLayout,
        update = { frame ->
            frame.removeAllViews()
            preview?.getOrNull()?.apply(context,frame)?.let(frame::addView)
        },
        modifier = Modifier.widthIn(max = size.width).fillMaxWidth().height(size.height)
    )
}

@Composable fun PinToolButton(tool: ToolRecord) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }) { Text(stringResource(R.string.widget_add_to_home)) }
    if (show) PinToolDialog(tool) { show = false }
}

internal enum class PinToolKind { Widget, Shortcut }

@Composable internal fun PinToolDialog(tool: ToolRecord, only: PinToolKind? = null, controller: ToolController? = null, dismiss: () -> Unit) {
    val context = LocalContext.current
    val layoutSaved = stringResource(R.string.widget_layout_saved)
    val scope = rememberCoroutineScope()
    var requesting by remember { mutableStateOf(false) }
    var widget by remember(only) { mutableStateOf(only != PinToolKind.Shortcut) }
    var prefs by remember { mutableStateOf(WidgetPreferences("compact",true)) }
    var token by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var editingLayout by remember { mutableStateOf(false) }
    var layoutVersion by remember { mutableIntStateOf(0) }
    val storage = remember(context) { context.getSharedPreferences("alpha-pin-requests",0) }
    DisposableEffect(storage,token) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if(key == token) status = token?.let { SurfaceEntry.status(context,it) } }
        storage.registerOnSharedPreferenceChangeListener(listener)
        token?.let { status = SurfaceEntry.status(context,it) }
        onDispose { storage.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    AlertDialog(onDismissRequest = dismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(when (only) {
                    PinToolKind.Widget -> R.string.tool_widget_add
                    PinToolKind.Shortcut -> R.string.tool_shortcut_add
                    null -> R.string.widget_home_title
                }))
                if (!editingLayout) status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editingLayout && controller != null) {
                    WidgetLayoutEditor(controller, tool, onCancel = { editingLayout = false }) {
                        editingLayout = false
                        layoutVersion++
                        status = layoutSaved
                    }
                    return@Column
                }
                if(only == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(widget,{ widget = true },{ Text(stringResource(R.string.widget_kind)) })
                    FilterChip(!widget,{ widget = false },{ Text(stringResource(R.string.widget_shortcut)) })
                }
                if(widget) {
                    WidgetOptions(prefs) { prefs = it }
                    key(layoutVersion) { WidgetPreview(tool,prefs) }
                    if (controller != null) TextButton(onClick = { editingLayout = true }) {
                        Text(stringResource(R.string.widget_layout_edit))
                    }
                }
                else { Text(ToolSpec.storedTitle(tool.spec),style = MaterialTheme.typography.titleLarge); Text(stringResource(R.string.widget_shortcut_detail)) }
                Text(stringResource(R.string.widget_pin_explanation))
            }
        },confirmButton = {
            TextButton(onClick = {
                requesting = true
                scope.launch {
                    try {
                        val result = SurfaceEntry.pin(context,tool,widget,prefs)
                        token = result.first
                        status = result.second
                    } finally {
                        requesting = false
                    }
                }
            },enabled = !requesting && !editingLayout) { Text(stringResource(R.string.tool_widget_pin)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.tool_dialog_close)) } })
}

@Composable private fun WidgetLayoutEditor(controller: ToolController, tool: ToolRecord, onCancel: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var instruction by remember(tool.id,tool.revision) { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<WidgetDraftReview?>(null) }
    val current = review
    if (current != null) {
        DisposableEffect(current) { onDispose { current.cancel() } }
        WidgetAuthoringPreview(current, onProposal = { proposal ->
            working = true
            scope.launch {
                val saved = try { controller.saveWidgetLayout(proposal) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { false }
                finally { working = false }
                if (saved) onSaved() else { review = null; failed = true }
            }
        }, onCancel = onCancel)
    } else {
        OutlinedTextField(instruction, { instruction = it.take(1_000) },
            label = { Text(stringResource(R.string.widget_layout_instruction)) }, enabled = !working,
            modifier = Modifier.fillMaxWidth(), maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                failed = false
                working = true
                scope.launch {
                    try {
                        when (val result = controller.prepareWidgetLayout(tool,instruction)) {
                            is WidgetAuthoringResult.Ready -> review = result.value
                            is WidgetAuthoringResult.Rejected -> failed = true
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failed = true }
                    finally { working = false }
                }
            }, enabled = !working && instruction.isNotBlank()) { Text(stringResource(R.string.widget_layout_generate)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.widget_cancel)) }
        }
    }
    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Text(stringResource(R.string.widget_layout_failed), color = MaterialTheme.colorScheme.error)
}
