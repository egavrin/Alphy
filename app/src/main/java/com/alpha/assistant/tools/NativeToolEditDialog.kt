package com.alpha.assistant.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.cloud.CloudFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun NativeToolEditDialog(controller: ToolController, target: ToolRecord, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val requestId = remember { UUID.randomUUID().toString() }
    val failureMessage = stringResource(R.string.tool_native_edit_failed)
    var instruction by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<NativeToolEditPreview?>(null) }
    var showAfter by remember { mutableStateOf(true) }
    var work by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller, requestId) {
        onDispose { controller.cancelNativeEdit(requestId) }
    }

    fun close() {
        if (saving) return
        controller.cancelNativeEdit(requestId)
        work?.cancel()
        dismiss()
    }

    fun runEdit(operation: suspend () -> Unit) {
        if (work != null) return
        error = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                operation()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                controller.cancelNativeEdit(requestId)
                review = null
                error = (failure as? CloudFailure)?.message ?: failureMessage
            } finally {
                saving = false
                work = null
            }
        }
        work = job
        job.start()
    }

    val preview = review
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(stringResource(R.string.tool_native_edit_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (preview == null) {
                    Text(ToolSpec.storedTitle(target.spec), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(instruction, { instruction = it.take(16000) },
                        label = { Text(stringResource(R.string.tool_native_edit_instruction)) },
                        enabled = work == null, modifier = Modifier.fillMaxWidth())
                } else {
                    if (preview.stoppedAutomationNames.isNotEmpty()) {
                        Text(stringResource(R.string.tool_native_edit_stopped), style = MaterialTheme.typography.titleSmall)
                        preview.stoppedAutomationNames.forEach { Text(it) }
                    }
                    if (preview.automationReviewNames.isNotEmpty()) {
                        Text(stringResource(R.string.tool_native_edit_review_rules), style = MaterialTheme.typography.titleSmall)
                        preview.automationReviewNames.forEach { Text(it) }
                    }
                    PrimaryTabRow(selectedTabIndex = if (showAfter) 1 else 0) {
                        Tab(selected = !showAfter, onClick = { showAfter = false },
                            text = { Text(stringResource(R.string.tool_native_edit_before)) })
                        Tab(selected = showAfter, onClick = { showAfter = true },
                            text = { Text(stringResource(R.string.tool_native_edit_after)) })
                    }
                    val view = if (showAfter) preview.after else preview.before
                    ToolContent(view.uiJson, view.stateJson, enabled = false, heading = view.title,
                        embedded = true, routeSystemBackEnabled = false, appLanguage = view.appLanguage) { _, _ -> }
                }
                if (work != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = work == null && (preview != null || instruction.isNotBlank()), onClick = {
                runEdit {
                    if (preview == null) review = controller.prepareNativeEdit(target, requestId, instruction.trim())
                    else {
                        saving = true
                        controller.confirmNativeEdit(requestId)
                        dismiss()
                    }
                }
            }) {
                Text(stringResource(if (preview == null) R.string.tool_native_edit_prepare else R.string.tool_native_edit_confirm))
            }
        },
        dismissButton = { TextButton(onClick = ::close, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}
