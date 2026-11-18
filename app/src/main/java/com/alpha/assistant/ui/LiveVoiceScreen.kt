package com.alpha.assistant.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.alpha.assistant.R
import com.alpha.assistant.agent.AgentPlanCard
import com.alpha.assistant.agent.AgentRunBar
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.tools.MiniAppActionDialogs
import com.alpha.assistant.tools.MiniAppActions
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveVoiceSession
import kotlinx.coroutines.flow.collectLatest

/** Implementation choice: a launch can return without a pause (a granted permission, or a failed startActivity), so it lapses. */
private const val EXPECTATION_MS = 5_000L

/**
 * A screen or an app that Alphy opens next keeps the voice mode (spec 5.8.1). Call it right before the launch. A source link that
 * opens in a browser does not call it, so the browser ends the mode.
 */
internal fun ShellViewModel.expectAlphyScreen() { alphyScreenUntil = SystemClock.uptimeMillis() + EXPECTATION_MS }

/**
 * The shell and the assistant sheet share the model, so only the activity that started the voice mode applies its lifecycle rule
 * and shows the voice view. A start without an owner belongs to every activity.
 */
internal fun ShellViewModel.ownsLiveVoice(activity: Activity?): Boolean = liveVoiceOwner.let { it == null || it == activity?.javaClass }

/**
 * The lifecycle rule of the voice mode (spec 5.8.1). [VoiceVisibility] stops at a pause, so the voice mode has its own rule:
 * a pause mutes, a resume unmutes, and a stop ends the mode. A configuration change does none of these. A stop also keeps the
 * mode while Alphy expects its own screen, and a finish ends it anyway (spec 5.11.7).
 * The screen off always ends it.
 */
@Composable internal fun LiveVoiceLifecycle(model: ShellViewModel) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, model) {
        val voice = model.liveVoice
        val observer = LifecycleEventObserver { _, event ->
            if (!model.ownsLiveVoice(activity)) return@LifecycleEventObserver
            when (event) {
                // A configuration change keeps Alphy's reply and the microphone (ruling R20).
                Lifecycle.Event.ON_PAUSE -> if (activity?.isChangingConfigurations != true) voice.away(true)
                Lifecycle.Event.ON_RESUME -> { model.alphyScreenUntil = 0; voice.away(false) }
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true &&
                    SystemClock.uptimeMillis() >= model.alphyScreenUntil) model.endLiveVoice()
                Lifecycle.Event.ON_DESTROY -> if (activity?.isFinishing == true) model.endLiveVoice()
                else -> Unit
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { model.endLiveVoice() }
        }
        activity?.lifecycle?.addObserver(observer)
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        onDispose { activity?.lifecycle?.removeObserver(observer); context.unregisterReceiver(receiver) }
    }
    // A start clears the away mute, so a mode that starts while Alphy is not in front mutes again.
    LaunchedEffect(model.liveVoice.active) {
        if (model.liveVoice.active && model.ownsLiveVoice(activity) &&
            activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == false) model.liveVoice.away(true)
    }
}

/**
 * The voice mode screen (spec 5.2): the card of the latest hand-off, the Alphy character, the status line, and a composer row with
 * «+», the field, the microphone, and ✕. The full chat shows it over the chat. [compact] makes the character smaller and the
 * composer one row for the assistant sheet (spec 5.11.3). [onEnd] ends the mode, and ✕ and Back call it. [aboveComposer] is the
 * screen context of the sheet.
 */
@Composable internal fun LiveVoiceLayer(model: ShellViewModel, chat: Conversation?, compact: Boolean, openTool: (String) -> Unit,
    openAutomation: (String) -> Unit, settings: () -> Unit, onEnd: () -> Unit, aboveComposer: @Composable () -> Unit = {}) {
    val voice = model.liveVoice
    val end by rememberUpdatedState(onEnd)
    // Back on the layer is ✕ (the 5.8 table). An overlay handler comes before the Back of the chat, which selects no chat.
    val dispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
    val back = remember {
        object : NavigationEventHandler<NavigationEventInfo.None>(NavigationEventInfo.None, true) { override fun onBackCompleted() = end() }
    }
    DisposableEffect(dispatcher) { dispatcher.addHandler(back, NavigationEventDispatcher.PRIORITY_OVERLAY); onDispose { back.remove() } }
    // The mode ends at a screen lock, so the screen stays on (spec 5.2.6).
    val view = LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val cardActions = remember { MiniAppActions() }
    var contextOpen by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<ContextMenuAnchor?>(null) }
    var shareText by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf(false) }
    val title = stringResource(R.string.live_voice)
    // The pane covers the chat, so the chat under it is out of the accessibility tree.
    Surface(Modifier.fillMaxSize().testTag("live_voice_layer").semantics { paneTitle = title }) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            // In a short space, such as landscape with the keyboard, the decorative character goes, so the composer row stays in view.
            val character = minOf(if (compact) 96.dp else 160.dp, maxHeight / 4).takeIf { it >= 48.dp }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                AgentRunBar(model.agent)
                // Empty before the first hand-off. The card scrolls when it is taller than its space (spec 5.2.3).
                Column(Modifier.weight(1f).widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    val message = chat?.messages?.find { it.id == model.liveVoiceCardId }
                    if (chat != null && message != null) {
                        ChatMessageItem(model, chat, message, cardActions, openTool, openAutomation, showLivePreviews = true,
                            pairedToolId = null, pairedDraftId = null, share = { shareText = it }, settings = settings,
                            linkError = { linkError = true })
                        // The plan of the chat, as the chat list shows it. A later hand-off keeps it while it waits for a tap.
                        model.agent.plan?.takeIf { it.conversationId == chat.id }?.let { AgentPlanCard(model.agent, it) }
                    }
                }
                character?.let { AlphyCharacter(voice, it) }
                Text(stringResource(when {
                    voice.phase == LivePhase.CONNECTING -> R.string.live_voice_connecting
                    voice.phase == LivePhase.MUTED -> R.string.live_voice_muted
                    voice.phase == LivePhase.SPEAKING -> R.string.live_voice_speaking
                    model.liveVoiceWorking -> R.string.live_voice_working
                    else -> R.string.live_voice_listening
                }), Modifier.padding(16.dp).then(PoliteLiveRegion), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                aboveComposer()
                Composer(model, { contextOpen = true }, Modifier.widthIn(max = 620.dp), compact = compact,
                    onContextAnchorChanged = { anchor = it }, settings = settings) { hasInput ->
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
    }
    MiniAppActionDialogs(model.tools, cardActions)
    if (contextOpen) ReviewedContextDialog(model, anchor) { contextOpen = false }
    shareText?.let { ShareTextToNotes(it) { shareText = null } }
    if (linkError) ChatLinkErrorDialog { linkError = false }
}

/**
 * The Alphy character (spec 5.2.2). Its scale and glow follow the microphone level, and they pulse while Alphy speaks.
 * Implementation choice: one image for both poses. The status line and the motion show the phase. With reduced motion it is still.
 */
@Composable private fun AlphyCharacter(voice: LiveVoiceSession, size: Dp) {
    val level = remember { Animatable(0f) }
    val glow = MaterialTheme.colorScheme.primary
    LaunchedEffect(voice) {
        if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
        // shortcut: the playback level is not measured (LiveAudio has none), so a fixed pulse stands for Alphy's voice.
        snapshotFlow { voice.level.takeUnless { voice.phase == LivePhase.SPEAKING } }.collectLatest { target ->
            if (target != null) level.animateTo(target, tween(120))
            else while (true) { level.animateTo(0.6f, tween(600)); level.animateTo(0.2f, tween(600)) }
        }
    }
    // The level is read in the draw and layer blocks, so a new level redraws without a recomposition.
    Image(painterResource(R.drawable.alphy_welcome), contentDescription = null, modifier = Modifier.size(size)
        .drawBehind { drawCircle(glow.copy(alpha = 0.12f + 0.4f * level.value), radius = this.size.minDimension * (0.45f + 0.15f * level.value)) }
        .graphicsLayer { val scale = 1f + 0.1f * level.value; scaleX = scale; scaleY = scale })
}
