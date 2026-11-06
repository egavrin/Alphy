package com.alpha.assistant.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.scheduling.RecurringSearchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date

@Composable
internal fun RecurringSearchDialog(controller: ToolController, tool: ToolRecord, dismiss: () -> Unit) {
    val scheduled by produceState<RecurringSearchStatus?>(null, tool.id, tool.revision) {
        controller.sourceChanges.collectLatest { value = controller.readRecurringSearch(tool) }
    }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val active = scheduled
    AlertDialog(
        onDismissRequest = { if (!working) dismiss() },
        title = { Text(stringResource(R.string.recurring_search_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recurring_search_consent))
                if (active == null) {
                    OutlinedTextField(input, { if (it.codePointCount(0, it.length) <= 128) input = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.recurring_search_query)) }, enabled = !working, maxLines = 3)
                } else {
                    Text(active.query, style = MaterialTheme.typography.titleSmall)
                    if (active.observedAt > 0L) {
                        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(active.observedAt))
                        Text(stringResource(if (active.failed) R.string.live_source_stale else R.string.live_source_fresh, time))
                    } else if (active.failed) {
                        Text(stringResource(R.string.live_source_unavailable))
                    }
                    val hits = active.result?.let(::JSONArray)
                    if (hits != null) for (index in 0 until hits.length()) {
                        val hit = hits.getJSONObject(index)
                        Text(hit.getString("title"), style = MaterialTheme.typography.titleSmall)
                        Text(hit.getString("url"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (failed) Text(stringResource(R.string.recurring_search_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && (active != null || input.isNotBlank()),
                onClick = {
                    working = true
                    failed = false
                    scope.launch {
                        try {
                            failed = !controller.scheduleRecurringSearch(tool, input.trim(), active?.ticket)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            working = false
                        }
                    }
                }
            ) {
                Text(stringResource(if (active == null) R.string.recurring_search_start else R.string.live_source_stop))
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !working) { Text(stringResource(R.string.tool_dialog_close)) } },
    )
}
