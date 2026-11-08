package com.alpha.assistant.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import com.alpha.assistant.ui.SettingsDetailWidth
import com.alpha.assistant.ui.SettingsHeading
import com.alpha.assistant.ui.SettingsPanel

/** Show local facts. Supply the same state when the activity is recreated. */
@Composable
internal fun MemoryScreen(
    state: MemoryPageState,
    onExit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    settingsPage: Boolean = false,
    profile: (@Composable () -> Unit)? = null,
) {
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    val scroll = rememberLazyListState()
    val draft = state.draft
    BackHandler(enabled = draft != null || state.deleteTarget != null || state.busy) {
        if (state.deleteTarget != null) state.cancelDelete() else state.cancelEditor()
    }

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val shortPane = maxHeight < 240.dp
        val content = if (settingsPage) Modifier.widthIn(max = SettingsDetailWidth + 40.dp) else Modifier
        if (draft != null) {
            Column(content.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                if (settingsPage) {
                    SettingsHeading(R.string.memory_title, MaterialTheme.typography.headlineMedium)
                    Box(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 480.dp).padding(top = 14.dp)) {
                        SettingsPanel { MemoryEditor(draft, state) }
                    }
                } else MemoryEditor(draft, state)
            }
        } else {
            val facts = state.facts
            LazyColumn(
                content.fillMaxSize(), state = scroll, contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SettingsHeading(R.string.memory_title, MaterialTheme.typography.headlineMedium)
                    onExit?.let { exit ->
                        TextButton(onClick = exit, enabled = !state.busy) { Text(stringResource(R.string.memory_exit)) }
                    }
                }
                state.notice?.let { notice -> item { MemoryNotice(notice) } }
                when {
                    state.loadFailed -> item { MemoryLoadFailure(state) }
                    facts == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    facts.isEmpty() -> {
                        if (shortPane) item { AddFactButton(state) }
                        item {
                            SettingsPanel {
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AlphaIcon(Glyph.Notebook, Modifier.size(28.dp), accent = true)
                                    Text(stringResource(R.string.memory_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!shortPane) AddFactButton(state)
                                }
                            }
                        }
                    }
                    else -> {
                        item { AddFactButton(state) }
                        itemsIndexed(facts, key = { _, fact -> fact.id }) { index, fact ->
                            MemoryFactCard(fact, index + 1, state)
                        }
                    }
                }
                profile?.let { field -> item { SettingsPanel { field() } } }
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

@Composable
private fun AddFactButton(state: MemoryPageState) {
    Button(onClick = { state.openEditor() }, enabled = !state.busy, shape = MaterialTheme.shapes.small) {
        Text(stringResource(R.string.memory_add))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryFactCard(fact: MemoryFact, number: Int, state: MemoryPageState) {
    SettingsPanel {
        Text(fact.text, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MemoryFactAction(R.string.memory_edit, R.string.memory_edit_fact, number, !state.busy) { state.openEditor(fact) }
            MemoryFactAction(R.string.memory_delete, R.string.memory_delete_fact, number, !state.busy) { state.requestDelete(fact) }
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
private fun MemoryEditor(draft: MemoryDraft, state: MemoryPageState) {
    val issue = state.draftIssue
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsHeading(if (draft.original == null) R.string.memory_add else R.string.memory_edit_title, MaterialTheme.typography.titleLarge)
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
                    SettingsHeading(R.string.memory_current_text, MaterialTheme.typography.titleLarge)
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
