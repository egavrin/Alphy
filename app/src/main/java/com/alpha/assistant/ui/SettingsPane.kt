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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.alpha.assistant.tools.agent.GenerationSettings
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.AccessSettings
import com.alpha.assistant.capabilities.AssistantSettingsRow
import com.alpha.assistant.capabilities.HealthSettings
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Capability
import com.alpha.assistant.cloud.ProviderProfile
import com.alpha.assistant.cloud.SpeechModel
import com.alpha.assistant.cloud.detectProvider
import com.alpha.assistant.images.ImageModel
import com.alpha.assistant.memory.MemoryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

internal val SettingsSplitWidth = 700.dp
private val SettingsRailWidth = 260.dp
internal val SettingsDetailWidth = 740.dp

internal enum class SettingsSection(@StringRes val label: Int, val glyph: Glyph) {
    Ai(R.string.settings_ai, Glyph.Cloud),
    Voice(R.string.settings_voice, Glyph.Mic),
    Appearance(R.string.settings_appearance, Glyph.Screen),
    Access(R.string.access, Glyph.Shield),
    Health(R.string.health_title, Glyph.Bolt),
    Memory(R.string.memory_title, Glyph.Notebook),
    Updates(R.string.update_title, Glyph.Refresh),
}

private val settingsGroups = listOf(
    R.string.settings_group_app to listOf(SettingsSection.Ai, SettingsSection.Voice, SettingsSection.Appearance),
    R.string.settings_group_data to listOf(SettingsSection.Access, SettingsSection.Health, SettingsSection.Memory),
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
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).testTag("settings-pane")) {
        val split = maxWidth >= SettingsSplitWidth
        val section = selected ?: SettingsSection.Ai.takeIf { split }
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
                            SettingsSection.Ai -> AiSettings(model)
                            SettingsSection.Voice -> SettingsDetail(section) {
                                SettingsPanel { VoiceControls(model, settings = { select(SettingsSection.Ai) }, inSettings = true) }
                            }
                            SettingsSection.Appearance -> SettingsDetail(section) { AppearanceSettings() }
                            SettingsSection.Access -> SettingsDetail(section) { AccessSettings(model.tools.networkAccessHost) }
                            SettingsSection.Health -> SettingsDetail(section) { HealthSettings() }
                            SettingsSection.Memory -> MemoryScreen(model.memoryPage, settingsPage = true, profile = { NameSettings(model) })
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
        SettingsPanel { AssistantSettingsRow() }
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
private fun AppearanceSettings() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var mode by remember(context) { mutableStateOf(AppearanceMode.read(context)) }
    SettingsPanel {
        SettingsHeading(R.string.settings_theme)
        Column(Modifier.selectableGroup()) {
            AppearanceMode.entries.forEach { option ->
                val selected = mode == option
                val label = when (option) {
                    AppearanceMode.System -> R.string.settings_theme_system
                    AppearanceMode.Light -> R.string.settings_theme_light
                    AppearanceMode.Dark -> R.string.settings_theme_dark
                }
                SettingsRadioRow(selected, {
                    option.select(context)
                    mode = option
                }) {
                    Text(stringResource(label))
                    if (option == AppearanceMode.System) {
                        Text(stringResource(R.string.settings_theme_system_description),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** One row of a selectableGroup: a 48 dp radio target that TalkBack reads with its text. A tap on the selected row does nothing. */
@Composable
private fun SettingsRadioRow(selected: Boolean, select: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton) { if (!selected) select() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Column(content = content)
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
internal fun SettingsDisclosure(@StringRes label: Int, initiallyOpen: Boolean = false, content: @Composable () -> Unit) =
    SettingsDisclosure(stringResource(label), initiallyOpen, content)

@Composable
internal fun SettingsDisclosure(label: String, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    // A new default replaces the earlier choice, so a saved key closes the consent that was open during entry.
    var expanded by rememberSaveable(initiallyOpen) { mutableStateOf(initiallyOpen) }
    Column {
        HorizontalDivider()
        TextButton({ expanded = !expanded }, Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            AlphaIcon(Glyph.Chevron, Modifier.rotate(if (expanded) -90f else 90f), accent = true)
        }
        if (expanded) content()
    }
}

@Composable
internal fun NameSettings(model: ShellViewModel) {
    val label = stringResource(R.string.settings_name_label)
    OutlinedTextField(value = model.snapshot.userName, onValueChange = model::setUserName, label = { Text(label) },
        supportingText = { Text(stringResource(R.string.settings_name_hint)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}

@Composable
private fun AiSettings(model: ShellViewModel) {
    val scroll = rememberScrollState()
    SettingsDetail(SettingsSection.Ai, scroll) {
        AiKeyPanel(model, scroll)
        ModelSettings(model)
        GenerationPanel(model.accountProvider)
    }
}

/** «Модели»: the curated models of the account provider (spec 14.8). Only «Настройки» shows it, not the first-run flow (spec 14.1). */
@Composable
private fun ModelSettings(model: ShellViewModel) {
    val provider = model.accountProvider ?: return
    val capabilities = provider.profile.capabilities
    val models = model.models
    val images = model.imageGeneration.settings
    SettingsDisclosure(R.string.ai_models) {
        // A key of a different provider resets the choices (spec 6.7), so the rows read the store again for each provider.
        key(provider) {
            // The start padding of the title button, so the groups align under «Модели».
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var chat by remember { mutableStateOf(models.chat(provider)) }
                SettingsChoice(R.string.ai_models_chat, provider.profile.chatModels, chat, { it }) {
                    models.selectChat(provider, it)
                    chat = it
                }
                if (Capability.IMAGES in capabilities) {
                    // From the known provider, so the composition reads no key.
                    var image by remember { mutableStateOf(images.model(provider)) }
                    SettingsChoice(R.string.ai_models_images, images.models(provider), image, ImageModel::title) {
                        images.select(it)
                        image = it
                    }
                }
                if (Capability.CLOUD_VOICE in capabilities) {
                    // One voice model for each provider, so the row is text and never selects (spec 14.8).
                    SettingsChoice(R.string.ai_models_voice, provider.profile.speechModels, models.speech(provider), SpeechModel::title) {}
                }
            }
        }
    }
}

/** One group of radio choices, such as a group of «Модели». A single option is text, because it offers no choice. */
@Composable
internal fun <T> SettingsChoice(@StringRes title: Int, options: List<T>, selected: T?, label: (T) -> String, select: (T) -> Unit) {
    Column {
        SettingsHeading(title, MaterialTheme.typography.titleSmall)
        if (options.size == 1) Text(label(options.single()), Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
        else Column(Modifier.selectableGroup()) {
            for (option in options) SettingsRadioRow(option == selected, { select(option) }) { Text(label(option)) }
        }
    }
}

/**
 * The screenshot review is off by default, because it adds one model step to each new app.
 * The plan step row shows only for an OpenAI account, the provider that the plan step was measured with.
 */
@Composable
private fun GenerationPanel(provider: AiProvider?) {
    val context = LocalContext.current
    val settings = remember(context) { GenerationSettings(context) }
    SettingsPanel {
        SettingsSwitch(R.string.settings_screenshot_review, R.string.settings_screenshot_review_hint, settings.screenshotReview) {
            settings.screenshotReview = it
        }
        if (provider == AiProvider.OPENAI) SettingsSwitch(R.string.settings_plan_step, R.string.settings_plan_step_hint, settings.planStep(AiProvider.OPENAI)) {
            settings.setPlanStep(AiProvider.OPENAI, it)
        }
    }
}

/** A switch row and one line of text under it. The row shows [stored] when it enters the screen, and [store] keeps each change. */
@Composable
private fun SettingsSwitch(@StringRes title: Int, @StringRes hint: Int, stored: Boolean, store: (Boolean) -> Unit) {
    var enabled by remember { mutableStateOf(stored) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(enabled, role = Role.Switch) { value ->
        store(value)
        enabled = value
    }, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The key status, entry, provider, consent text, functions, and actions. «Настройки» and the first-run flow show the same panel. */
@Composable
internal fun AiKeyPanel(model: ShellViewModel, scroll: ScrollState) {
    val status = model.keyStatus
    val colors = MaterialTheme.colorScheme
    // remember, not rememberSaveable, so a recreation clears the typed key.
    var key by remember { mutableStateOf("") }
    // The provider for the typed key: the prefix guess or the user's choice (spec 6.4).
    var typedProvider by remember { mutableStateOf<AiProvider?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var helpFailed by remember { mutableStateOf<String?>(null) }
    var shownStatus by remember { mutableStateOf(status) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(status) {
        // A failed check returns to the same status, so the message under the button stays in view.
        if (status == KeyStatus.Verifying) return@LaunchedEffect
        val previous = shownStatus
        shownStatus = status
        if (previous != KeyStatus.Checking && previous != status) scroll.animateScrollTo(0)
    }
    // «Использовать» on a found-key card gives the old key and the provider of its store one time (decision 2).
    val legacyEntry = model.legacyEntry
    val saveInView = remember { BringIntoViewRequester() }
    var saveSize by remember { mutableStateOf(IntSize.Zero) }
    // The gap under the button comes into view too, so the button does not touch the edge of the scroll area.
    val saveGap = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(legacyEntry) {
        if (legacyEntry == null) return@LaunchedEffect
        key = legacyEntry.key
        typedProvider = legacyEntry.provider
        model.takeLegacyEntry()
        scope.launch {
            // One frame composes the provider field and the open consent, and the next starts after their layout.
            repeat(2) { withFrameNanos {} }
            // The save button comes into view with the end of the consent above it, as a save brings the status line into view.
            saveInView.bringIntoView(Rect(0f, 0f, saveSize.width.toFloat(), saveSize.height + saveGap))
        }
    }
    val verifying = status == KeyStatus.Verifying
    val entry = status == KeyStatus.Missing || status == KeyStatus.Ready || verifying
    val typing = entry && key.isNotBlank()
    val provider = if (typing) typedProvider else model.accountProvider
    LegacyKeyCard(model)
    SettingsPanel {
        when (status) {
            KeyStatus.Ready -> StatusLine(Glyph.Check, colors.primary, stringResource(R.string.key_ready), PoliteLiveRegion)
            KeyStatus.Unreadable -> StatusLine(Glyph.Close, colors.error, stringResource(R.string.key_unreadable), PoliteLiveRegion)
            else -> StatusLine(Glyph.Cloud, colors.onSurfaceVariant, stringResource(when (status) {
                KeyStatus.Missing -> R.string.key_missing
                KeyStatus.Verifying -> R.string.key_verifying
                else -> R.string.key_checking
            }), PoliteLiveRegion)
        }
        model.keyNotice?.let { StatusLine(Glyph.Cloud, colors.onSurfaceVariant, it, PoliteLiveRegion) }
        if (entry) {
            val label = stringResource(R.string.key_label)
            OutlinedTextField(value = key, onValueChange = { value ->
                // Only a changed guess replaces the provider, so a choice that the user made stays through edits (spec 6.4).
                val guess = detectProvider(key)
                val next = detectProvider(value)
                if (next != guess) typedProvider = next ?: typedProvider.takeIf { it != guess }
                key = value
            }, label = { Text(label) }, placeholder = { Text(stringResource(R.string.key_placeholder)) },
                supportingText = { Text(stringResource(R.string.key_supporting)) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !verifying,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
            if (typing) ProviderChoice(typedProvider, enabled = !verifying) { typedProvider = it }
            if (model.accountProvider == null) {
                KeyGuide { helpFailed = it }
                helpFailed?.let { StatusLine(Glyph.Close, colors.error, stringResource(R.string.key_help_failed, it), PoliteLiveRegion) }
            }
        }
        // The consent text is open while the user consents: during key entry and before consent (spec 14.1).
        val consenting = typing || status != KeyStatus.Ready
        if (provider != null) SettingsDisclosure(stringResource(R.string.ai_disclosure, provider.displayName), initiallyOpen = consenting) {
            ConsentText(provider)
        }
        if (status != KeyStatus.Checking) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val idle = !model.keyBusy
            when (status) {
                KeyStatus.Unreadable -> Button(onClick = { confirmDelete = true }, enabled = idle) { Text(stringResource(R.string.key_delete)) }
                else -> {
                    val chosen = typedProvider
                    val save = {
                        if (chosen != null) model.saveKey(key, chosen) { saved ->
                            if (!saved) return@saveKey
                            key = ""
                            typedProvider = null
                            // A replaced key keeps the Ready status, so the save itself shows the status line and its notice.
                            scope.launch { scroll.animateScrollTo(0) }
                        }
                    }
                    // A disabled button names its reason for TalkBack. The same text shows on screen.
                    val reason = when {
                        verifying -> stringResource(R.string.key_verifying)
                        typing && chosen == null -> stringResource(R.string.key_provider_choose)
                        else -> null
                    }
                    val modifier = Modifier.bringIntoViewRequester(saveInView).onSizeChanged { saveSize = it }.semantics { reason?.let { stateDescription = it } }
                    val enabled = idle && typing && chosen != null
                    val text = @Composable { Text(stringResource(R.string.consent_and_save)) }
                    if (model.accountProvider == null) Button(onClick = save, modifier, enabled = enabled, shape = MaterialTheme.shapes.small) { text() }
                    else OutlinedButton(onClick = save, modifier, enabled = enabled, shape = MaterialTheme.shapes.small) { text() }
                }
            }
        }
        model.keyFailure?.let { StatusLine(Glyph.Close, colors.error, it, PoliteLiveRegion) }
        if (provider != null) AvailableFunctions(model, provider)
        if (model.accountProvider != null) TextButton(onClick = { confirmDelete = true }, enabled = !model.keyBusy) { Text(stringResource(R.string.key_delete)) }
    }
    val dismissDelete = { confirmDelete = false }
    if (confirmDelete) AlertDialog(onDismissRequest = dismissDelete, title = { Text(stringResource(R.string.key_delete_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.key_delete_text)) } },
        confirmButton = {
            TextButton(onClick = {
                model.clearKey()
                dismissDelete()
            }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = dismissDelete) { Text(stringResource(R.string.cancel)) } })
}

/** A card for each old key store that holds a key, DeepSeek first (spec 14.6). «Удалить» always shows, so an old key never stays out of sight. */
@Composable
internal fun LegacyKeyCard(model: ShellViewModel) {
    val found = model.legacyKeys
    val texts = mapOf(
        AiProvider.DEEPSEEK to (R.string.legacy_deepseek to R.string.legacy_deepseek_use),
        AiProvider.OPENROUTER to (R.string.legacy_openrouter to R.string.legacy_openrouter_use),
    )
    // An unreadable account also hides the key field, so the old key could not go into it.
    val usable = model.accountProvider == null && model.keyStatus != KeyStatus.Unreadable
    val idle = !model.keyBusy
    for (provider in found) SettingsPanel {
        val (title, use) = texts.getValue(provider)
        SettingsHeading(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (usable) Button(onClick = { model.useLegacyKey(provider) }, enabled = idle) { Text(stringResource(use)) }
            OutlinedButton(onClick = { model.clearLegacyKey(provider) }, enabled = idle) { Text(stringResource(R.string.legacy_delete)) }
        }
    }
    model.legacyFailure?.let { StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, it, PoliteLiveRegion) }
}

@Composable
private fun ProviderChoice(selected: AiProvider?, enabled: Boolean, select: (AiProvider) -> Unit) {
    // Four segments wrap their labels at 360 dp, so the control is a menu (spec 18).
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.key_provider)
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it }) {
        OutlinedTextField(value = selected?.displayName.orEmpty(), onValueChange = {}, readOnly = true, enabled = enabled, singleLine = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            supportingText = if (selected == null) { { Text(stringResource(R.string.key_provider_choose)) } } else null,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth()
                .testTag("ai-provider").semantics { contentDescription = label })
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            AiProvider.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.displayName) }, contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding, onClick = {
                    select(option)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun ConsentText(provider: AiProvider) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val style = MaterialTheme.typography.bodyMedium
        Text(stringResource(R.string.consent_text, provider.displayName), style = style)
        if (provider == AiProvider.OPENROUTER) Text(stringResource(R.string.consent_openrouter), style = style)
        // Cloud voice sends the reply text, so the consent names it before the user selects «Облако» (spec 14.7).
        if (Capability.CLOUD_VOICE in provider.profile.capabilities) Text(stringResource(R.string.consent_voice), style = style)
        // Voice input through the provider sends the recording. The phone path keeps it on the phone (spec 14.7, 12A.4).
        if (provider.profile.transcription != null) Text(stringResource(R.string.consent_voice_input), style = style)
    }
}

/** «Что доступно»: the rows come from the provider capabilities, so they show before the key check (spec 14.3). */
@Composable
private fun AvailableFunctions(model: ShellViewModel, provider: AiProvider) {
    val capabilities = provider.profile.capabilities
    HorizontalDivider()
    SettingsHeading(R.string.ai_available)
    Column {
        if (Capability.CHAT in capabilities) FunctionRow(R.string.ai_feature_chat, available = true)
        if (Capability.VISION in capabilities) FunctionRow(R.string.ai_feature_vision, stringResource(R.string.ai_feature_vision_detail), available = true)
        if (Capability.SEARCH in capabilities) FunctionRow(R.string.ai_feature_search, available = true)
        if (Capability.IMAGES in capabilities) ImageSwitchRow(model, provider)
        else FunctionRow(R.string.ai_feature_images, stringResource(R.string.ai_feature_images_unavailable, provider.displayName))
        FunctionRow(R.string.ai_feature_voice,
            stringResource(if (Capability.CLOUD_VOICE in capabilities) R.string.ai_feature_voice_any else R.string.ai_feature_voice_phone))
        FunctionRow(R.string.capability_voice_input, available = true, detail = if (provider.profile.transcription == null)
            stringResource(R.string.capability_voice_input_phone) else stringResource(R.string.capability_voice_input_cloud, provider.displayName))
    }
}

@Composable
private fun FunctionRow(@StringRes title: Int, detail: String? = null, available: Boolean = false, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title))
            if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (available) AlphaIcon(Glyph.Check, accent = true)
        trailing()
    }
}

/** The image switch of spec 11.4. Only the saved account generates images, so the switch of a typed key stays off. */
@Composable
private fun ImageSwitchRow(model: ShellViewModel, provider: AiProvider) {
    val settings = model.imageGeneration.settings
    val scope = rememberCoroutineScope()
    val account = provider == model.accountProvider
    var enabled by remember(account) { mutableStateOf(account && settings.enabled) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    val label = stringResource(R.string.image_enable)
    FunctionRow(R.string.ai_feature_images, stringResource(R.string.ai_feature_images_paid)) {
        Switch(checked = enabled, enabled = account && !busy, modifier = Modifier.semantics { contentDescription = label }, onCheckedChange = { value ->
            busy = true
            failure = false
            scope.launch {
                try {
                    val (result, on) = withContext(Dispatchers.IO) { settings.setEnabled(value).let { it to (settings.enabled && settings.available) } }
                    enabled = on
                    failure = result.isFailure
                } finally { busy = false }
            }
        })
    }
    if (failure) Text(stringResource(R.string.key_save_failed), color = MaterialTheme.colorScheme.error)
}

private val KEY_GUIDE_ORDER = listOf(AiProvider.OPENROUTER, AiProvider.OPENAI, AiProvider.DEEPSEEK, AiProvider.ANTHROPIC)

/** «Где взять ключ»: each provider name links to its key page and is followed by the functions that it lacks (spec 14.10). */
@Composable
internal fun KeyGuide(onOpenFailed: (url: String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val linkStyles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    Column {
        Text(stringResource(R.string.key_help), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        for (provider in KEY_GUIDE_ORDER) {
            val page = provider.profile.keyPage
            val gaps = keyGuideGaps(provider.profile).map { stringResource(it) }
            val gap = when (gaps.size) {
                0 -> stringResource(R.string.key_guide_all)
                1 -> stringResource(R.string.key_guide_without_one, gaps[0])
                else -> stringResource(R.string.key_guide_without_two, gaps[0], gaps[1])
            }
            val open = { if (runCatching { uriHandler.openUri(page) }.isFailure) onOpenFailed(page) }
            // The whole row opens the link, so its touch target is at least 48 dp (decision 5). TalkBack reads the row with the provider name.
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = open).padding(vertical = 8.dp),
                contentAlignment = Alignment.CenterStart) {
                Row {
                    Text(buildAnnotatedString {
                        withLink(LinkAnnotation.Url(page, linkStyles) { open() }) { append(provider.displayName) }
                        append(" — ")
                    }, Modifier.alignByBaseline())
                    Text(gap, Modifier.weight(1f).alignByBaseline())
                }
            }
        }
    }
}

/** The string IDs of the functions that a provider lacks, in guide order. */
internal fun keyGuideGaps(profile: ProviderProfile): List<Int> = listOfNotNull(
    R.string.key_guide_images.takeIf { Capability.IMAGES !in profile.capabilities },
    R.string.key_guide_voice.takeIf { Capability.CLOUD_VOICE !in profile.capabilities },
)
