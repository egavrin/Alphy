package com.alpha.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alpha.assistant.R

/** The message composer of the shell, the mini-app split, and the assistant half sheet.
 * [compact] puts the field and the round actions in one row and hides the other lines, while the keyboard takes most of a landscape window. */
@Composable internal fun Composer(model: ShellViewModel, context: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val snapshot = model.snapshot
    val busy = model.activeConversationId != null
    val startVoice = rememberVoiceStart(model, oneShot = true)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 7.dp)) {
        if (!compact) {
            if (model.voice.active && !model.voice.oneShot) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall) { StatusLine(Glyph.Mic, MaterialTheme.colorScheme.onSurfaceVariant, model.voice.status) }
                if (model.voice.partial.isNotBlank()) Text(model.voice.partial, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.voice.stop() }) { Text(stringResource(R.string.voice_stop)) }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                snapshot.draft.attachments.forEach { attachment ->
                    ImagePreview(attachment)
                    val remove = stringResource(R.string.composer_remove_file, attachment.name)
                    InputChip(selected = true, onClick = { model.editDraft(snapshot.draft.copy(attachments = snapshot.draft.attachments - attachment)) }, label = { Text(attachment.name, maxLines = 1) }, leadingIcon = { AlphaIcon(Glyph.File) }, trailingIcon = { AlphaIcon(Glyph.Close) }, modifier = Modifier.semantics { contentDescription = remove })
                }
            }
            if (model.voice.permissionRequired) MicrophonePermissionGuidance(model.voice, startVoice)
            else if (!model.voice.active && model.voice.hasAttempted) ProvideTextStyle(MaterialTheme.typography.bodySmall) { StatusLine(Glyph.Mic, MaterialTheme.colorScheme.onSurfaceVariant, model.voice.status) }
            if (model.attachmentBusy) ProvideTextStyle(MaterialTheme.typography.labelSmall) { StatusLine(Glyph.File, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.composer_image_check)) }
        }
        // A dark Material scheme gives outlineVariant the tone of surfaceVariant, so the round actions need a container of another tone.
        Surface(shape = RoundedCornerShape(27.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(Modifier.padding(if (compact) 4.dp else 12.dp)) {
                if (model.voice.active && model.voice.oneShot) InlineVoiceRecorder(model)
                else {
                    @Composable fun AddContext() = RoundAction(Glyph.Plus, stringResource(R.string.composer_add_context), context)
                    @Composable fun VoiceAndSend() {
                        RoundAction(Glyph.Mic, stringResource(R.string.composer_voice), startVoice, enabled = !busy && !model.attachmentBusy && !model.voice.active)
                        Spacer(Modifier.width(5.dp))
                        if (busy) RoundAction(Glyph.Close, stringResource(R.string.composer_stop), model::cancelRequest)
                        else RoundAction(Glyph.Arrow, stringResource(R.string.composer_send), { model.send() }, primary = true, enabled = !model.attachmentBusy && (snapshot.draft.text.isNotBlank() || snapshot.draft.attachments.isNotEmpty()))
                    }
                    // The field keeps one call place in both layouts, so a change of compact keeps its focus and the keyboard.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (compact) AddContext()
                        val composerLabel = stringResource(R.string.composer_label)
                        // The vertical space is inside the decoration, so the field node keeps the 48 dp minimum height.
                        // In compact, the shell column above the keyboard limits the height, and the field scrolls inside it.
                        BasicTextField(value = snapshot.draft.text, onValueChange = { model.editDraft(snapshot.draft.copy(text = it)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 150.dp).padding(horizontal = 7.dp).semantics { contentDescription = composerLabel }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner ->
                            Box(Modifier.padding(vertical = 5.dp)) {
                                if (snapshot.draft.text.isEmpty()) Text(stringResource(R.string.composer_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                                inner()
                            }
                        })
                        if (compact) VoiceAndSend()
                    }
                    if (!compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AddContext()
                        Spacer(Modifier.weight(1f))
                        VoiceAndSend()
                    }
                }
            }
        }
    }
}

@Composable private fun RoundAction(glyph: Glyph, label: String, action: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    FilledIconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        // The button content color keeps the contrast on the primary container and dims a disabled glyph.
        AlphaIcon(glyph, color = LocalContentColor.current)
    }
}
