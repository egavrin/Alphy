package com.alpha.assistant.surfaces.widgetauthoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R

@Composable
internal fun WidgetAuthoringPreview(
    review: WidgetDraftReview,
    onProposal: ((WidgetSaveProposal) -> Unit)?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var submitting by remember(review) { mutableStateOf(false) }
    var closed by remember(review) { mutableStateOf(false) }
    var failure by remember(review) { mutableStateOf<WidgetAuthoringResult.Rejected?>(null) }
    val proposalHandler by rememberUpdatedState(onProposal)
    LaunchedEffect(review, submitting) {
        if (!submitting) return@LaunchedEffect
        if (proposalHandler != null) {
            when (val result = review.proposeSave()) {
                is WidgetAuthoringResult.Ready -> {
                    closed = true
                    proposalHandler?.invoke(result.value)
                }
                is WidgetAuthoringResult.Rejected -> failure = result
            }
        }
        submitting = false
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.widget_preview), style = MaterialTheme.typography.labelMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(review.content.title, style = MaterialTheme.typography.titleMedium)
                for (row in review.content.rows) {
                    when (row) {
                        is WidgetPreviewRow.Text -> Text(row.label)
                        is WidgetPreviewRow.Button -> Button(onClick = {}, enabled = false) { Text(row.label) }
                        is WidgetPreviewRow.Toggle -> Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(checked = row.checked, onCheckedChange = null, enabled = false)
                            val label = if (row.checked) R.string.widget_toggle_on else R.string.widget_toggle_off
                            Text(stringResource(label, row.label))
                        }
                    }
                }
            }
        }
        val error = when {
            onProposal == null -> R.string.widget_unavailable
            failure != null -> R.string.widget_save_failed
            else -> null
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { failure = null; submitting = true },
                enabled = onProposal != null && !closed && !submitting && failure != WidgetAuthoringResult.Rejected.STALE,
            ) { Text(stringResource(R.string.widget_save)) }
            TextButton(
                onClick = { review.cancel(); closed = true; onCancel() },
                enabled = !closed,
            ) { Text(stringResource(R.string.widget_cancel)) }
        }
    }
}
