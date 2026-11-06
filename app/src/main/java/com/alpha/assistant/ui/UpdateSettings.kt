package com.alpha.assistant.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alpha.assistant.R
import com.alpha.assistant.updates.AppUpdater
import com.alpha.assistant.updates.UpdateState

@Composable internal fun UpdateNotice(updater: AppUpdater, visible: Boolean, openSettings: () -> Unit) {
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    if (!visible) return
    val state by updater.state.collectAsStateWithLifecycle()
    val tag = when (val update = state) {
        is UpdateState.Available -> update.release.tag
        is UpdateState.Ready -> update.release.tag
        else -> null
    }
    if (tag == null || tag == dismissed) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.update_available, tag),
                Modifier.weight(1f).then(PoliteLiveRegion),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = openSettings) { Text(stringResource(R.string.update_title)) }
            TextButton(onClick = { dismissed = tag }) { Text(stringResource(R.string.update_dismiss_notice)) }
        }
    }
}

@Composable internal fun UpdateSettings(updater: AppUpdater, openInstaller: () -> Unit) {
    val context = LocalContext.current
    val state by updater.state.collectAsStateWithLifecycle()
    var notesTag by rememberSaveable { mutableStateOf<String?>(null) }
    val release = when (val update = state) {
        is UpdateState.Available -> update.release
        is UpdateState.Downloading -> update.release
        is UpdateState.Ready -> update.release
        else -> null
    }
    Text(
        stringResource(R.string.app_version, updater.installedVersion),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (val update = state) {
        is UpdateState.Idle -> update.error?.let { UpdateError(it) }
        UpdateState.Checking -> Text(stringResource(R.string.update_checking), PoliteLiveRegion)
        UpdateState.Current -> Text(stringResource(R.string.update_current), PoliteLiveRegion)
        is UpdateState.Available -> {
            Text(stringResource(R.string.update_available, update.release.tag), PoliteLiveRegion)
            update.error?.let { UpdateError(it) }
        }
        is UpdateState.Downloading -> {
            Text(stringResource(R.string.update_downloading, update.percent))
            LinearProgressIndicator(progress = { update.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.Ready -> {
            Text(stringResource(R.string.update_ready, update.release.tag), PoliteLiveRegion)
            update.error?.let { UpdateError(it) }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val update = state) {
            is UpdateState.Downloading -> {
                TextButton(onClick = updater::cancelDownload) { Text(stringResource(R.string.update_cancel_download)) }
            }
            is UpdateState.Ready -> {
                val label = if (updater.canInstall) R.string.update_install else R.string.update_allow_install
                TextButton(onClick = openInstaller, enabled = !update.handoffPending) {
                    Text(stringResource(label))
                }
                TextButton(onClick = updater::discard, enabled = !update.handoffPending) {
                    Text(stringResource(R.string.update_discard))
                }
            }
            else -> {
                TextButton(onClick = { updater.check(manual = true) }, enabled = update != UpdateState.Checking) {
                    Text(stringResource(R.string.update_check))
                }
                if (update is UpdateState.Available) {
                    TextButton(onClick = updater::download) { Text(stringResource(R.string.update_download)) }
                }
            }
        }
        if (release != null && state !is UpdateState.Downloading) {
            TextButton(onClick = { notesTag = release.tag }) { Text(stringResource(R.string.update_details)) }
        }
    }
    if (release != null && notesTag == release.tag) {
        AlertDialog(
            onDismissRequest = { notesTag = null },
            title = { Text(stringResource(R.string.update_available, release.tag)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.update_size, Formatter.formatFileSize(context, release.size)))
                    Text(release.notes.ifBlank { stringResource(R.string.update_no_notes) })
                }
            },
            confirmButton = {
                TextButton(onClick = { notesTag = null }) { Text(stringResource(R.string.update_close_details)) }
            },
        )
    }
}

@Composable private fun UpdateError(message: Int) {
    Text(stringResource(message), PoliteLiveRegion, color = MaterialTheme.colorScheme.error)
}
