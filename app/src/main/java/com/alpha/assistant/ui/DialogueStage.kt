package com.alpha.assistant.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.agent.AgentPlanCard
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.tools.ArtifactSnapshot
import com.alpha.assistant.tools.CoveredToolSurface
import com.alpha.assistant.tools.MiniAppActions
import com.alpha.assistant.tools.MiniAppContent
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.three.ThreeScenePane
import com.alpha.assistant.voice.LivePhase

/**
 * A card of the voice mode that waits for a tap: an error or an unsent request with its retry, or the refinement choices. The voice
 * speaks each other result, and the stage shows the app of a hand-off, so the stage shows no other card (plan D, Da1, Step 0).
 */
internal fun waitsForATap(message: Message): Boolean =
    message.status !in unfinishedStatuses && (message.status != "complete" || message.refinementChoices.isNotEmpty())

/** What the voice mode does now (spec 5.2.4). A mute has no status of its own, because the microphone state of the strip shows it. */
@StringRes private fun liveStatus(model: ShellViewModel): Int? = when {
    model.liveVoice.phase == LivePhase.CONNECTING -> R.string.live_voice_connecting
    model.liveVoice.phase == LivePhase.SPEAKING -> R.string.live_voice_speaking
    model.liveVoiceWorking -> R.string.live_voice_working
    model.liveVoice.phase == LivePhase.MUTED -> null
    else -> R.string.live_voice_listening
}

/**
 * The stage of the voice mode (plan D, Da1): all the space above the strip. It shows a card that waits for a tap and the plan of the
 * chat, then the app of the latest hand-off. Before the first app, it shows the Alphy character and the status.
 */
@Composable internal fun DialogueStage(model: ShellViewModel, chat: Conversation?, cardActions: MiniAppActions, openTool: (String) -> Unit,
    openAutomation: (String) -> Unit, settings: () -> Unit, modifier: Modifier) {
    var shareText by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf(false) }
    val tool = model.stageToolId?.let { id -> model.tools.tools.find { it.id == id } }
    BoxWithConstraints(modifier.fillMaxWidth().testTag("dialogue_stage")) {
        // The gutter of an open mini-app (FocusedToolPane).
        val gutter = if (maxWidth >= 600.dp) 28.dp else 20.dp
        // In a short space, such as landscape with the keyboard, the decorative character goes.
        val character = minOf(160.dp, maxHeight / 4).takeIf { it >= 48.dp }
        // The canvas height of an open mini-app (FocusedToolPane).
        val canvasHeight = (maxHeight * 0.65f).coerceIn(120.dp, 560.dp)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(horizontal = gutter, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp, if (tool == null) Alignment.CenterVertically else Alignment.Top)) {
            if (chat != null) {
                chat.messages.find { it.id == model.liveVoiceCardId }?.takeIf(::waitsForATap)?.let { message ->
                    ChatMessageItem(model, chat, message, cardActions, openTool, openAutomation, showLivePreviews = true,
                        pairedToolId = null, pairedDraftId = null, share = { shareText = it }, settings = settings,
                        linkError = { linkError = true })
                }
                // The plan of the chat, as the chat list shows it. A later hand-off keeps it while it waits for a tap.
                model.agent.plan?.takeIf { it.conversationId == chat.id }?.let { AgentPlanCard(model.agent, it) }
            }
            if (tool != null) StageApp(model.tools, tool, cardActions, canvasHeight)
            else {
                character?.let { AlphyCharacter(model.liveVoice, it, Modifier.testTag("live_voice_character")) }
                // The strip reads the same status to a screen reader.
                liveStatus(model)?.let {
                    Text(stringResource(it), Modifier.semantics { hideFromAccessibility() }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
        }
    }
    shareText?.let { ShareTextToNotes(it) { shareText = null } }
    if (linkError) ChatLinkErrorDialog { linkError = false }
}

/** The app of the stage with its title, drawn as an open mini-app draws it (FocusedToolPane): a 3D scene, a drawing with its host, or the app. */
@Composable private fun StageApp(controller: ToolController, tool: ToolRecord, actions: MiniAppActions, canvasHeight: Dp) {
    val spec = remember(tool.spec, tool.ui, tool.state) { ToolSpec.availablePersisted(tool) }
    Text(spec?.title ?: ToolSpec.storedTitle(tool.spec), Modifier.fillMaxWidth().semantics { heading() },
        style = MaterialTheme.typography.headlineSmall)
    if (spec == null) { Text(stringResource(R.string.tool_closed_format)); return }
    val enabled = !controller.busy && !actions.covers(tool.id)
    val host = if (!spec.threeScene && spec.surfaces.isNotEmpty()) remember(controller, tool.id, tool.revision) { controller.artifactHost(tool) }
        else null
    var snapshot by remember(host) { mutableStateOf<ArtifactSnapshot?>(null) }
    val content: @Composable () -> Unit = {
        MiniAppContent(controller, tool, tool, spec.title, enabled, graphicsHost = host, graphicsSnapshot = snapshot,
            liveAllowed = !actions.covers(tool.id), allowVideo = true)
    }
    when {
        spec.threeScene -> ThreeScenePane(controller, tool, spec, canvasHeight, null, enabled)
        host != null -> CoveredToolSurface(tool, spec, true, canvasHeight, host, actions, content = content) { snapshot = it }
        else -> content()
    }
}

/**
 * The strip of the voice mode (plan D, Da1): the status with the voice indicator and the microphone state, then the composer row with
 * «+», the field, send, mute, and end (spec 5.7.1). [compact] makes the composer one row for the assistant sheet (spec 5.11.3).
 */
@Composable internal fun DialogueStrip(model: ShellViewModel, compact: Boolean, onEnd: () -> Unit, openContext: () -> Unit,
    settings: () -> Unit, onContextAnchorChanged: (ContextMenuAnchor?) -> Unit) {
    val voice = model.liveVoice
    Column(Modifier.fillMaxWidth().testTag("dialogue_strip")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AlphyCharacter(voice, 28.dp)
            // Each text is a polite live region, so a screen reader hears a new status and a new microphone state (spec 5.2.4).
            Text(liveStatus(model)?.let { stringResource(it) }.orEmpty(), Modifier.weight(1f).then(PoliteLiveRegion),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            // While the mode connects, the phase is CONNECTING even with the microphone button off.
            Text(stringResource(if (voice.userMuted || voice.phase == LivePhase.MUTED) R.string.live_voice_mic_off else R.string.live_voice_mic_on),
                Modifier.then(PoliteLiveRegion), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        Composer(model, openContext, compact = compact, onContextAnchorChanged = onContextAnchorChanged, settings = settings) { hasInput ->
            // Implementation choice: typed text goes as a normal message with its own send button (spec 5.7.1).
            if (hasInput) {
                RoundAction(Glyph.Arrow, stringResource(R.string.composer_send), model::sendLiveTyped, primary = true,
                    enabled = !model.attachmentBusy)
                Spacer(Modifier.width(5.dp))
            }
            RoundAction(if (voice.userMuted) Glyph.MicOff else Glyph.Mic,
                stringResource(if (voice.userMuted) R.string.live_voice_unmute else R.string.live_voice_mute),
                { voice.mute(!voice.userMuted) })
            Spacer(Modifier.width(5.dp))
            RoundAction(Glyph.Close, stringResource(R.string.live_voice_end), onEnd)
        }
    }
}
