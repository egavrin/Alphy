package com.alpha.assistant.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.alpha.assistant.R

/** Show local facts. Supply the same state when the activity is recreated. */
@Composable
internal fun MemoryScreen(state: MemoryPageState, onExit: () -> Unit, modifier: Modifier = Modifier) {
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    val scroll = rememberLazyListState()
    val draft = state.draft
    BackHandler(enabled = draft != null || state.deleteTarget != null || state.busy) {
        if (state.deleteTarget != null) state.cancelDelete() else state.cancelEditor()
    }

    if (draft != null) {
        MemoryEditor(draft, state, modifier)
    } else {
        val facts = state.facts
        LazyColumn(
            modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MemoryHeading(R.string.memory_title)
                TextButton(onClick = onExit, enabled = !state.busy) { Text(stringResource(R.string.memory_exit)) }
            }
            state.notice?.let { notice -> item { MemoryNotice(notice) } }
            when {
                state.loadFailed -> item { MemoryLoadFailure(state) }
                facts == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else -> {
                    item {
                        Button(onClick = { state.openEditor() }, enabled = !state.busy) {
                            Text(stringResource(R.string.memory_add))
                        }
                    }
                    if (facts.isEmpty()) item { Text(stringResource(R.string.memory_empty)) }
                    itemsIndexed(facts, key = { _, fact -> fact.id }) { index, fact ->
                        MemoryFactCard(fact, index + 1, state)
                    }
                }
            }
        }
    }

    if (state.discardDraft) {
        AlertDialog(
            onDismissRequest = state::cancelDiscard,
            title = { Text(stringResource(R.string.memory_discard_title)) },
            confirmButton = { TextButton(onClick = state::closeEditor) { Text(stringResource(R.string.memory_discard)) } },
            dismissButton = { TextButton(onClick = state::cancelDiscard) { Text(stringResource(R.string.memory_keep_editing)) } },
        )
    }
    state.deleteTarget?.let { fact ->
        AlertDialog(
            onDismissRequest = state::cancelDelete,
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(fact.text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = state::confirmDelete, enabled = !state.busy) {
                    Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = state::cancelDelete, enabled = !state.busy) { Text(stringResource(R.string.memory_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryFactCard(fact: MemoryFact, number: Int, state: MemoryPageState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(fact.text, style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryFactAction(R.string.memory_edit, R.string.memory_edit_fact, number, !state.busy) { state.openEditor(fact) }
                MemoryFactAction(R.string.memory_delete, R.string.memory_delete_fact, number, !state.busy) { state.requestDelete(fact) }
            }
        }
    }
}

@Composable
private fun MemoryFactAction(label: Int, accessibleLabel: Int, number: Int, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(accessibleLabel, number)
    TextButton(onClick, Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }, enabled = enabled) {
        Text(stringResource(label), Modifier.clearAndSetSemantics { })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditor(draft: MemoryDraft, state: MemoryPageState, modifier: Modifier) {
    val issue = state.draftIssue
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MemoryHeading(if (draft.original == null) R.string.memory_add else R.string.memory_edit_title)
        OutlinedTextField(
            value = draft.text,
            onValueChange = state::changeDraft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.memory_text)) },
            enabled = !state.busy,
            minLines = 3,
            maxLines = 6,
        )
        if (state.saveFailed) MemoryNotice(R.string.memory_save_failed)
        when (issue) {
            MemoryRejection.STALE_REVISION -> {
                MemoryNotice(R.string.memory_stale_edit)
                val latest = state.currentRevision
                if (latest != null) {
                    MemoryHeading(R.string.memory_current_text)
                    Text(latest.text)
                    TextButton(onClick = state::useCurrentRevision, enabled = !state.busy) {
                        Text(stringResource(R.string.memory_use_latest))
                    }
                } else if (state.loadFailed) MemoryLoadFailure(state)
            }
            MemoryRejection.FACT_NOT_FOUND -> {
                MemoryNotice(R.string.memory_missing_edit)
                TextButton(onClick = state::convertDraftToNewFact, enabled = !state.busy) {
                    Text(stringResource(R.string.memory_convert_draft))
                }
            }
            null -> Unit
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = state::saveDraft, enabled = !state.busy && draft.hasChanges && draft.text.isNotBlank() && issue == null) {
                Text(stringResource(R.string.memory_save))
            }
            TextButton(onClick = state::cancelEditor, enabled = !state.busy) { Text(stringResource(R.string.memory_cancel)) }
        }
    }
}

@Composable
private fun MemoryHeading(label: Int) {
    Text(stringResource(label), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun MemoryNotice(label: Int) {
    Text(stringResource(label), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun MemoryLoadFailure(state: MemoryPageState) {
    Column {
        MemoryNotice(R.string.memory_load_failed)
        TextButton(onClick = state::refresh, enabled = !state.busy) { Text(stringResource(R.string.memory_retry)) }
    }
}
