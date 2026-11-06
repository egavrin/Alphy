package com.alpha.assistant.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R

/** The host supplies the retained review when it presents an offer. */
@Composable
internal fun MemorySuggestionDialog(review: MemorySuggestionReview) {
    val status by review.status.collectAsState()
    if (status == MemorySuggestionStatus.ACCEPTED || status == MemorySuggestionStatus.DECLINED) return
    val enabled = status != MemorySuggestionStatus.SAVING

    AlertDialog(
        onDismissRequest = review::decline,
        title = { Text(stringResource(R.string.memory_suggestion_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(review.text, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                if (status == MemorySuggestionStatus.FAILED) {
                    Text(
                        stringResource(R.string.memory_save_failed),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = review::accept, enabled = enabled) { Text(stringResource(R.string.memory_save)) }
        },
        dismissButton = {
            TextButton(onClick = review::decline, enabled = enabled) { Text(stringResource(R.string.memory_cancel)) }
        },
    )
}
