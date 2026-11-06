package com.alpha.assistant.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.alpha.assistant.scheduling.SourceOffer
import com.alpha.assistant.scheduling.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SourceRefreshControl(controller: ToolController, tool: ToolRecord) {
    val current by produceState<Pair<SourceStatus, Boolean>?>(null, tool.id, tool.revision, tool.createdAt) {
        value = null
        controller.sourceChanges.collectLatest { value = controller.readSource(tool) }
    }
    val proposed = controller.sourceOffer?.takeIf { it.matches(tool) && it != current?.first?.offer }
    val (status, scheduled) = current ?: (null to false)
    if (proposed == null && status == null) return
    val scope = rememberCoroutineScope()
    var review by remember(tool.id, tool.revision) { mutableStateOf<SourceOffer?>(null) }
    var working by remember(tool.id, tool.revision) { mutableStateOf(false) }
    var failed by remember(tool.id, tool.revision) { mutableStateOf(false) }
    fun submit(action: suspend () -> Boolean) {
        working = true
        failed = false
        scope.launch {
            try {
                failed = !action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                working = false
            }
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.live_source_title), style = MaterialTheme.typography.titleSmall)
            if (status != null) {
                val sample = status.sample
                if (sample != null) {
                    Text(sample.get("value").toString(), style = MaterialTheme.typography.titleMedium)
                    val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(sample.getLong("dataTimeMs")))
                    Text(stringResource(if (status.failed) R.string.live_source_stale else R.string.live_source_fresh, timestamp),
                        style = MaterialTheme.typography.bodySmall)
                } else if (status.failed) {
                    Text(stringResource(R.string.live_source_unavailable), style = MaterialTheme.typography.bodySmall)
                }
                Text(status.offer.endpoint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (scheduled) Text(stringResource(R.string.live_source_hourly), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow {
                if (proposed != null) TextButton(onClick = { review = proposed }, enabled = !working && !controller.busy) {
                    Text(stringResource(R.string.live_source_review))
                }
                if (status != null) {
                    TextButton(onClick = { submit { controller.refreshSource(status.offer) } }, enabled = !working && !controller.busy) {
                        Text(stringResource(R.string.live_source_refresh))
                    }
                    TextButton(onClick = {
                        if (scheduled) submit { controller.scheduleSource(status.offer, false) } else review = status.offer
                    }, enabled = !working && !controller.busy) {
                        Text(stringResource(if (scheduled) R.string.live_source_stop else R.string.live_source_start))
                    }
                }
            }
            if (failed) Text(stringResource(R.string.live_source_error), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }

    val selected = review ?: return
    val startSchedule = status?.offer == selected
    AlertDialog(
        onDismissRequest = { review = null },
        title = { Text(stringResource(if (startSchedule) R.string.live_source_start else R.string.live_source_review)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected.endpoint)
                val selector = selected.selector
                val kind = when (selector.getString("kind")) {
                    "number" -> R.string.live_source_type_number
                    "boolean" -> R.string.live_source_type_boolean
                    else -> R.string.live_source_type_text
                }
                Text(stringResource(R.string.live_source_field, selector.getJSONArray("path").toString(), stringResource(kind)))
                Text(stringResource(if (startSchedule) R.string.live_source_schedule_consent else R.string.live_source_consent))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                review = null
                submit {
                    if (startSchedule) controller.scheduleSource(selected, true)
                    else controller.approveSource(selected)
                }
            }) { Text(stringResource(if (startSchedule) R.string.live_source_start else R.string.live_source_approve)) }
        },
        dismissButton = { TextButton(onClick = { review = null }) { Text(stringResource(R.string.cancel)) } },
    )
}
