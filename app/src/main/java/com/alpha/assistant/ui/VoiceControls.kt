package com.alpha.assistant.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alpha.assistant.R
import com.alpha.assistant.surfaces.AssistCapture
import com.alpha.assistant.voice.VoiceSession

@Composable fun VoiceVisibility(voice: VoiceSession) {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    val locked = stringResource(R.string.voice_screen_locked)
    DisposableEffect(owner, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) voice.stop()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { voice.stop(locked) }
        }
        owner?.lifecycle?.addObserver(observer)
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        onDispose { owner?.lifecycle?.removeObserver(observer); context.unregisterReceiver(receiver); voice.stop() }
    }
}

@Composable internal fun rememberVoiceStart(
    model: ShellViewModel,
    oneShot: Boolean,
    destination: CourseComposerState? = null,
    assistantInvocation: String? = null,
    displayedCapture: AssistCapture? = null,
): () -> Unit {
    val context = LocalContext.current
    val owner = androidx.activity.compose.LocalActivity.current as? ComponentActivity
    var origin by remember(destination, assistantInvocation) { mutableStateOf<String?>(null) }
    var pending by remember(destination, assistantInvocation) { mutableStateOf(false) }
    val visibleCapture by rememberUpdatedState(displayedCapture)
    fun startIfVisible() {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (pending && power.isInteractive && !keyguard.isDeviceLocked && owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            pending = false
            if (origin != null && origin == model.composerContextKey(destination)) {
                model.startComposerVoice(oneShot, destination, assistantInvocation, visibleCapture)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) { pending = true; startIfVisible() }
        else {
            pending = false
            if (origin != null && origin == model.composerContextKey(destination)) model.denyVoicePermission(destination)
        }
    }
    DisposableEffect(owner, model, oneShot, destination, assistantInvocation) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) startIfVisible() }
        owner?.lifecycle?.addObserver(observer)
        onDispose { pending = false; origin = null; owner?.lifecycle?.removeObserver(observer) }
    }
    return {
        origin = model.composerContextKey(destination)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = true
            startIfVisible()
        } else if (origin != null) permission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable internal fun MicrophonePermissionGuidance(voice: VoiceSession, retry: () -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.voice_mic_permission), style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = retry) { Text(stringResource(R.string.voice_mic_allow)) }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                .onFailure { voice.permissionDenied() }
        }) { Text(stringResource(R.string.voice_android_settings)) }
    }
}

@Composable internal fun InlineVoiceRecorder(model: ShellViewModel) {
    val voice = model.voice
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val recording = voice.phase == "listening"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (voice.partial.isNotBlank()) Text(voice.partial, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            maxLines = 3, style = MaterialTheme.typography.bodyLarge)
        val levelLabel = stringResource(R.string.voice_level)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction(Glyph.Close, stringResource(R.string.voice_cancel), { voice.stop() })
            if (!recording) {
                Text(stringResource(R.string.voice_transcribing), Modifier.weight(1f).then(PoliteLiveRegion),
                    color = color, style = MaterialTheme.typography.bodySmall)
                CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
            } else {
                Canvas(Modifier.weight(1f).height(40.dp).semantics {
                contentDescription = levelLabel
                progressBarRangeInfo = ProgressBarRangeInfo(voice.levels.lastOrNull() ?: 0f, 0f..1f)
                }) {
                    val spacing = size.width / 48
                    repeat(48) { index ->
                        val level = voice.levels.getOrNull(index - (48 - voice.levels.size)) ?: 0f
                        val height = 2.dp.toPx() + level * (size.height - 2.dp.toPx())
                        val x = spacing * (index + 0.5f)
                        drawLine(color, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), minOf(3.dp.toPx(), spacing * 0.6f), StrokeCap.Round)
                    }
                }
                RoundAction(Glyph.Stop, stringResource(R.string.voice_finish), voice::finishRecording)
                RoundAction(Glyph.Arrow, stringResource(R.string.voice_send), model::sendVoice, primary = true)
            }
        }
    }
}

@Composable fun VoiceControls(model: ShellViewModel, settingsDisclosure: Boolean = false) {
    val voice = model.voice
    val start = rememberVoiceStart(model, oneShot = false)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Text(stringResource(R.string.voice_session), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.voice_session_text))
        if (!settingsDisclosure) VoiceModelDetails()
        if (voice.permissionRequired) MicrophonePermissionGuidance(voice, start)
        else if (voice.status.isNotBlank()) StatusLine(Glyph.Mic, MaterialTheme.colorScheme.onSurfaceVariant, voice.status)
        if (voice.partial.isNotBlank()) Text(voice.partial)
        if (voice.active) FilledTonalButton(onClick = { voice.stop() }) { Text(stringResource(R.string.voice_stop)) }
        else if (settingsDisclosure) Button(onClick = start, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.voice_start)) }
        else FilledTonalButton(onClick = start) { Text(stringResource(R.string.voice_start)) }
        if (settingsDisclosure) SettingsDisclosure(R.string.settings_voice_disclosure) { VoiceModelDetails() }
    }
}

@Composable
private fun VoiceModelDetails() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.voice_models))
        Text(stringResource(R.string.voice_licenses))
    }
}
