package com.alpha.assistant.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.AccessSettings
import com.alpha.assistant.memory.MemoryScreen

internal val SettingsSplitWidth = 700.dp
private val SettingsRailWidth = 260.dp
internal val SettingsDetailWidth = 740.dp

internal enum class SettingsSection(@StringRes val label: Int, val glyph: Glyph) {
    DeepSeek(R.string.settings_deepseek, Glyph.Cloud),
    Voice(R.string.settings_voice, Glyph.Mic),
    Access(R.string.access, Glyph.Shield),
    Memory(R.string.memory_title, Glyph.Notebook),
    Updates(R.string.update_title, Glyph.Refresh),
}

private val settingsGroups = listOf(
    R.string.settings_group_app to listOf(SettingsSection.DeepSeek, SettingsSection.Voice),
    R.string.settings_group_data to listOf(SettingsSection.Access, SettingsSection.Memory),
    R.string.settings_group_maintenance to listOf(SettingsSection.Updates),
)

@Composable
internal fun SettingsPane(
    model: ShellViewModel,
    selected: SettingsSection?,
    hinge: IntRange?,
    select: (SettingsSection?) -> Unit,
    openUpdateInstaller: () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        SettingsLayout(model, selected, hinge, select, openUpdateInstaller)
    }
}

@Composable
private fun SettingsLayout(
    model: ShellViewModel,
    selected: SettingsSection?,
    hinge: IntRange?,
    select: (SettingsSection?) -> Unit,
    openUpdateInstaller: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val split = maxWidth >= SettingsSplitWidth
        val section = selected ?: SettingsSection.DeepSeek.takeIf { split }
        val density = LocalDensity.current
        val railWidth = if (hinge == null) SettingsRailWidth else with(density) { hinge.first.toDp() }
        val hingeWidth = if (hinge == null) 1.dp else with(density) { (hinge.last - hinge.first).toDp() }
        Row(Modifier.fillMaxSize()) {
            if (split || section == null) {
                SettingsMenu(
                    model, section, select, split,
                    if (split) Modifier.width(railWidth).fillMaxHeight() else Modifier.fillMaxSize(),
                )
            }
            if (split) Spacer(Modifier.width(hingeWidth).fillMaxHeight().background(colors.outlineVariant))
            if (section != null) {
                Column(Modifier.weight(1f).fillMaxHeight().testTag("settings-detail")) {
                    if (!split) TextButton({
                        if (section == SettingsSection.Memory && model.memoryPage.draft != null) model.memoryPage.cancelEditor()
                        else select(null)
                    }, Modifier.padding(start = 12.dp).heightIn(min = 48.dp).testTag("settings-back")) {
                        AlphaIcon(Glyph.Back, Modifier.padding(end = 4.dp), accent = true)
                        Text(stringResource(R.string.settings))
                    }
                    key(section) {
                        when (section) {
                            SettingsSection.DeepSeek -> DeepSeekSettings(model)
                            SettingsSection.Voice -> SettingsDetail(section) { SettingsPanel { VoiceControls(model, settingsDisclosure = true) } }
                            SettingsSection.Access -> SettingsDetail(section) { AccessSettings(model.tools.networkAccessHost) }
                            SettingsSection.Memory -> MemoryScreen(model.memoryPage, settingsPage = true)
                            SettingsSection.Updates -> SettingsDetail(section) { SettingsPanel { UpdateSettings(model.updates, openUpdateInstaller) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsMenu(model: ShellViewModel, selected: SettingsSection?, select: (SettingsSection) -> Unit, split: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.testTag("settings-menu")
            .background(if (split) colors.surfaceContainerLow else colors.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeading(R.string.settings, if (split) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
        NameSettings(model)
        for ((title, sections) in settingsGroups) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), Modifier.padding(start = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(if (split) Color.Transparent else colors.surface)) {
                    for (section in sections) {
                        if (!split && section != sections.first()) HorizontalDivider(color = colors.outlineVariant)
                        val active = selected == section
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = if (split) 54.dp else 59.dp)
                                .clip(shape).background(if (active) colors.primaryContainer else Color.Transparent)
                                .selectable(active, role = Role.Button) { select(section) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val foreground = if (active) colors.onPrimaryContainer else colors.onSurface
                            AlphaIcon(section.glyph, color = foreground)
                            Text(stringResource(section.label), Modifier.weight(1f), color = foreground)
                            AlphaIcon(Glyph.Chevron)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDetail(
    section: SettingsSection,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = SettingsDetailWidth + 40.dp).fillMaxWidth()
                .verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsHeading(section.label, MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}

@Composable
internal fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(), color = colors.surface,
        shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun SettingsHeading(
    @StringRes label: Int,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Text(stringResource(label), Modifier.semantics { heading() }, style = style)
}

@Composable
internal fun SettingsDisclosure(@StringRes label: Int, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column {
        HorizontalDivider()
        TextButton({ expanded = !expanded }, Modifier.fillMaxWidth()) {
            Text(stringResource(label), Modifier.weight(1f))
            AlphaIcon(Glyph.Chevron, Modifier.rotate(if (expanded) -90f else 90f), accent = true)
        }
        if (expanded) content()
    }
}

@Composable
private fun NameSettings(model: ShellViewModel) {
    val label = stringResource(R.string.settings_name_label)
    OutlinedTextField(value = model.snapshot.userName, onValueChange = model::setUserName, label = { Text(label) },
        supportingText = { Text(stringResource(R.string.settings_name_hint)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}

@Composable
private fun DeepSeekSettings(model: ShellViewModel) {
    val status = model.keyStatus
    // remember, not rememberSaveable, so a recreation clears the typed key.
    var key by remember { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var shownStatus by remember { mutableStateOf(status) }
    LaunchedEffect(status) {
        val previous = shownStatus
        shownStatus = status
        if (previous != KeyStatus.Checking && previous != status) scroll.animateScrollTo(0)
    }
    SettingsDetail(SettingsSection.DeepSeek, scroll) {
        SettingsPanel {
            when (status) {
                KeyStatus.Ready -> StatusLine(Glyph.Check, MaterialTheme.colorScheme.primary, stringResource(R.string.key_ready), PoliteLiveRegion)
                KeyStatus.Unreadable -> StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(R.string.key_unreadable), PoliteLiveRegion)
                else -> StatusLine(Glyph.Cloud, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(when (status) {
                    KeyStatus.Missing -> R.string.key_missing
                    KeyStatus.NeedsConsent -> R.string.key_needs_consent
                    else -> R.string.key_checking
                }), PoliteLiveRegion)
            }
            if (status == KeyStatus.Missing || status == KeyStatus.Ready) {
                val label = stringResource(R.string.key_label)
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(label) }, placeholder = { Text(stringResource(R.string.key_placeholder)) },
                    supportingText = { Text(stringResource(R.string.key_supporting)) }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
            }
            SettingsDisclosure(R.string.settings_deepseek_disclosure, initiallyOpen = status != KeyStatus.Ready) {
                Text(stringResource(R.string.consent_text), style = MaterialTheme.typography.bodyMedium)
            }
            if (status != KeyStatus.Checking) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val idle = !model.keyBusy
                val save = { model.saveKey(key) { if (it) key = "" } }
                when (status) {
                    KeyStatus.Missing -> Button(onClick = save, enabled = idle && key.isNotBlank(), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.consent_and_save)) }
                    KeyStatus.Ready -> OutlinedButton(onClick = save, enabled = idle && key.isNotBlank(), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.consent_and_save)) }
                    KeyStatus.NeedsConsent -> Button(onClick = model::acceptConsent, enabled = idle) { Text(stringResource(R.string.consent_accept)) }
                    KeyStatus.Unreadable -> Button(onClick = { confirmDelete = true }, enabled = idle) { Text(stringResource(R.string.key_delete)) }
                    KeyStatus.Checking -> Unit
                }
                if (status == KeyStatus.Ready || status == KeyStatus.NeedsConsent)
                    TextButton(onClick = { confirmDelete = true }, enabled = idle) { Text(stringResource(R.string.key_delete)) }
            }
            model.keyFailure?.let { StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(it), PoliteLiveRegion) }
        }
    }
    if (confirmDelete) AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.key_delete_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.key_delete_text)) } },
        confirmButton = { TextButton(onClick = { model.clearKey(); confirmDelete = false }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
}
