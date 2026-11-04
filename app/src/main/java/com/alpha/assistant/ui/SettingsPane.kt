package com.alpha.assistant.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.AssistantSettingsRow

/** «Настройки». The top bar shows the title, so the page has none. */
@Composable internal fun SettingsPane(model: ShellViewModel) {
    val context = LocalContext.current
    val status = model.keyStatus
    // remember, not rememberSaveable, so a recreation clears the typed key.
    var key by remember { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val version = remember(context) { @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val scroll = rememberScrollState()
    // The key buttons are below the consent text, so a key action that changes the status scrolls the page up to the new status.
    var shownStatus by remember { mutableStateOf(status) }
    LaunchedEffect(status) {
        val previous = shownStatus
        shownStatus = status
        if (previous != KeyStatus.Checking && previous != status) scroll.animateScrollTo(0)
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading(R.string.settings_deepseek)
        when (status) {
            KeyStatus.Ready -> StatusLine(Glyph.Check, MaterialTheme.colorScheme.primary, stringResource(R.string.key_ready))
            KeyStatus.Unreadable -> StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(R.string.key_unreadable))
            else -> StatusLine(Glyph.Cloud, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(when (status) {
                KeyStatus.Missing -> R.string.key_missing
                KeyStatus.NeedsConsent -> R.string.key_needs_consent
                else -> R.string.key_checking
            }))
        }
        Text(stringResource(R.string.consent_text), style = MaterialTheme.typography.bodyMedium)
        if (status == KeyStatus.Missing || status == KeyStatus.Ready) {
            val label = stringResource(R.string.key_label)
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(label) }, placeholder = { Text(stringResource(R.string.key_placeholder)) },
                supportingText = { Text(stringResource(R.string.key_supporting)) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
        if (status != KeyStatus.Checking) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val idle = !model.keyBusy
            val save = { model.saveKey(key) { if (it) key = "" } }
            when (status) {
                KeyStatus.Missing -> Button(onClick = save, enabled = idle && key.isNotBlank()) { Text(stringResource(R.string.consent_and_save)) }
                KeyStatus.Ready -> OutlinedButton(onClick = save, enabled = idle && key.isNotBlank()) { Text(stringResource(R.string.consent_and_save)) }
                KeyStatus.NeedsConsent -> Button(onClick = model::acceptConsent, enabled = idle) { Text(stringResource(R.string.consent_accept)) }
                KeyStatus.Unreadable -> Button(onClick = { confirmDelete = true }, enabled = idle) { Text(stringResource(R.string.key_delete)) }
                KeyStatus.Checking -> Unit
            }
            if (status == KeyStatus.Ready || status == KeyStatus.NeedsConsent)
                TextButton(onClick = { confirmDelete = true }, enabled = idle) { Text(stringResource(R.string.key_delete)) }
        }
        model.keyFailure?.let { StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(it)) }
        HorizontalDivider()
        val nameLabel = stringResource(R.string.settings_name_label)
        OutlinedTextField(value = model.snapshot.userName, onValueChange = model::setUserName, label = { Text(nameLabel) },
            supportingText = { Text(stringResource(R.string.settings_name_hint)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = nameLabel })
        HorizontalDivider()
        Heading(R.string.settings_voice)
        VoiceControls(model)
        HorizontalDivider()
        Heading(R.string.settings_assistant)
        AssistantSettingsRow()
        Text(stringResource(R.string.app_version, version), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.key_delete_title)) },
        text = { Text(stringResource(R.string.key_delete_text)) },
        confirmButton = { TextButton(onClick = { model.clearKey(); confirmDelete = false }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun Heading(@StringRes id: Int) =
    Text(stringResource(id), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
