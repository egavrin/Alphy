package com.alpha.assistant.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.sp
import com.alpha.assistant.R
import com.alpha.assistant.data.Draft

internal class CourseComposerState(
    val maxText: Int,
    val send: () -> Unit,
    val cancel: () -> Unit,
) {
    var draft by mutableStateOf(Draft())
    var busy by mutableStateOf(false)
    var active by mutableStateOf(true)
    var cancellable by mutableStateOf(true)
    var contextError by mutableStateOf<String?>(null)

    fun deactivate() {
        active = false
        draft = draft.copy(id = Draft().id)
        contextError = null
    }
}

@Composable internal fun CourseComposer(
    model: ShellViewModel,
    destination: CourseComposerState,
    @StringRes placeholder: Int,
) {
    var contextOpen by remember(destination) { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<ContextMenuAnchor?>(null) }
    Composer(model, { contextOpen = true }, destination = destination, placeholder = placeholder,
        label = placeholder, onContextAnchorChanged = { anchor = it })
    if (contextOpen) ReviewedContextDialog(model, anchor, destination = destination) { contextOpen = false }
}

/** The message composer of the shell, the mini-app split, and the assistant half sheet.
 * [compact] puts the field and the round actions in one row and hides the other lines, while the keyboard takes most of a landscape window. */
@Composable internal fun Composer(
    model: ShellViewModel,
    context: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    @StringRes placeholder: Int = R.string.composer_placeholder,
    onContextAnchorChanged: ((ContextMenuAnchor) -> Unit)? = null,
    destination: CourseComposerState? = null,
    @StringRes label: Int = R.string.composer_label,
) {
    val draft = model.composerDraft(destination)
    val busy = destination?.busy ?: (model.activeConversationId != null)
    val request = model.activeRequestMessageId.takeIf { model.activeConversationId == model.snapshot.selectedId }
    val cancel = if (destination != null) destination.cancel.takeIf { destination.busy && destination.cancellable }
        else request?.let { { model.cancelRequest(it) } }
    val send = { model.sendComposer(destination) }
    val hasInput = draft.text.isNotBlank() || draft.attachments.isNotEmpty()
    val startVoice = rememberVoiceStart(model, oneShot = true, destination = destination)
    val ownsVoice = model.voiceBelongsTo(destination)
    val editable = destination == null || destination.active && !destination.busy
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (!compact) {
            if (ownsVoice && model.voice.active && !model.voice.oneShot) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall) { StatusLine(Glyph.Mic, MaterialTheme.colorScheme.onSurfaceVariant, model.voice.status) }
                if (model.voice.partial.isNotBlank()) Text(model.voice.partial, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.voice.stop() }) { Text(stringResource(R.string.voice_stop)) }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                draft.attachments.forEach { attachment ->
                    ImagePreview(attachment)
                    val remove = stringResource(R.string.composer_remove_file, attachment.name)
                    InputChip(selected = true, enabled = editable, onClick = { model.editComposerDraft(destination, draft.copy(attachments = draft.attachments - attachment)) }, label = { Text(attachment.name, maxLines = 1) }, leadingIcon = { AlphaIcon(Glyph.File) }, trailingIcon = { AlphaIcon(Glyph.Close) }, modifier = Modifier.semantics { contentDescription = remove })
                }
            }
            if (ownsVoice && model.voice.permissionRequired) MicrophonePermissionGuidance(model.voice, startVoice)
            else if (ownsVoice && !model.voice.active && model.voice.hasAttempted) ProvideTextStyle(MaterialTheme.typography.bodySmall) { StatusLine(Glyph.Mic, MaterialTheme.colorScheme.onSurfaceVariant, model.voice.status) }
            if (model.attachmentBusy) ProvideTextStyle(MaterialTheme.typography.labelSmall) { StatusLine(Glyph.File, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.composer_image_check)) }
        }
        val colors = MaterialTheme.colorScheme
        Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerLow,
            border = BorderStroke(1.dp, colors.outline)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(3.dp)) {
                val singleRow = compact || maxWidth >= 270.dp && LocalDensity.current.fontScale < 1.4f
                Column {
                    if (ownsVoice && model.voice.active && model.voice.oneShot) InlineVoiceRecorder(model)
                    else {
                        @Composable fun AddContext() {
                            val anchorModifier = if (onContextAnchorChanged == null) Modifier else Modifier.onGloballyPositioned { button ->
                                val row = checkNotNull(button.parentLayoutCoordinates)
                                onContextAnchorChanged(ContextMenuAnchor(
                                    button.boundsInWindow().roundToIntRect(),
                                    row.boundsInWindow().roundToIntRect(),
                                ))
                            }
                            Box(anchorModifier) {
                                RoundAction(Glyph.Plus, stringResource(R.string.composer_add_context), context, enabled = editable)
                            }
                        }
                        @Composable fun VoiceAndSend() {
                            if (cancel != null) {
                                RoundAction(Glyph.Stop, stringResource(R.string.composer_stop), cancel, primary = destination != null || !hasInput)
                            } else {
                                RoundAction(Glyph.Mic, stringResource(R.string.composer_voice), startVoice, enabled = editable && !busy && !model.attachmentBusy && !model.voice.active)
                            }
                            if (cancel == null || destination == null && hasInput) {
                                Spacer(Modifier.width(5.dp))
                                RoundAction(Glyph.Arrow, stringResource(R.string.composer_send), send, primary = true,
                                    enabled = editable && !model.attachmentBusy && hasInput)
                            }
                        }
                        // The field keeps one call place in both layouts, so a change of compact keeps its focus and the keyboard.
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (singleRow) AddContext()
                            val composerLabel = stringResource(label)
                            // The vertical space is inside the decoration, so the field node keeps the 48 dp minimum height.
                            // In compact, the shell column above the keyboard limits the height, and the field scrolls inside it.
                            BasicTextField(value = draft.text, enabled = editable, onValueChange = { model.editComposerDraft(destination, draft.copy(text = it)) }, modifier = Modifier.weight(1f).heightIn(max = 120.dp).padding(horizontal = 6.dp).semantics { contentDescription = composerLabel }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner ->
                                Box(Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
                                    if (draft.text.isEmpty()) Text(stringResource(placeholder), color = colors.onSurfaceVariant,
                                        fontSize = 16.sp, maxLines = if (singleRow) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                                    inner()
                                }
                            })
                            if (singleRow) VoiceAndSend()
                        }
                        if (!singleRow) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AddContext()
                            Spacer(Modifier.weight(1f))
                            VoiceAndSend()
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun RoundAction(glyph: Glyph, label: String, action: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    FilledIconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (primary) colors.primary else colors.secondaryContainer,
            contentColor = if (primary) colors.onPrimary else colors.onSecondaryContainer)) {
        AlphaIcon(glyph, color = LocalContentColor.current)
    }
}
