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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.alpha.assistant.R
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
 * The voice mode screen (spec 5.2, plan D): the stage with the app of the talk, and the strip with the status and a composer row with
 * «+», the field, the microphone, and ✕. The full chat shows it over the chat. [compact] makes the composer one row for the assistant
 * sheet (spec 5.11.3). [onEnd] ends the mode, and ✕ and Back call it. [aboveComposer] is the screen context of the sheet.
 */
@Composable internal fun LiveVoiceLayer(model: ShellViewModel, chat: Conversation?, compact: Boolean, openTool: (String) -> Unit,
    openAutomation: (String) -> Unit, settings: () -> Unit, onEnd: () -> Unit, aboveComposer: @Composable () -> Unit = {}) {
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
    val title = stringResource(R.string.live_voice)
    // The pane covers the chat, so the chat under it is out of the accessibility tree.
    Surface(Modifier.fillMaxSize().testTag("live_voice_layer").semantics { paneTitle = title }) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AgentRunBar(model.agent)
            DialogueStage(model, chat, cardActions, openTool, openAutomation, settings, Modifier.weight(1f))
            aboveComposer()
            DialogueStrip(model, compact, onEnd, { contextOpen = true }, settings) { anchor = it }
        }
    }
    MiniAppActionDialogs(model.tools, cardActions)
    if (contextOpen) ReviewedContextDialog(model, anchor) { contextOpen = false }
}

/**
 * The Alphy character (spec 5.2.2). Its scale and glow follow the microphone level, and they pulse while Alphy speaks.
 * Implementation choice: one image for both poses. The status line and the motion show the phase. With reduced motion it is still.
 */
@Composable internal fun AlphyCharacter(voice: LiveVoiceSession, size: Dp, modifier: Modifier = Modifier) {
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
    Image(painterResource(R.drawable.alphy_welcome), contentDescription = null, modifier = modifier.size(size)
        .drawBehind { drawCircle(glow.copy(alpha = 0.12f + 0.4f * level.value), radius = this.size.minDimension * (0.45f + 0.15f * level.value)) }
        .graphicsLayer { val scale = 1f + 0.1f * level.value; scaleX = scale; scaleY = scale })
}
