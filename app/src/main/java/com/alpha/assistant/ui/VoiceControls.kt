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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
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

@Composable internal fun rememberVoiceStart(model: ShellViewModel, oneShot: Boolean): () -> Unit {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    var pending by remember { mutableStateOf(false) }
    fun startIfVisible() {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (pending && power.isInteractive && !keyguard.isDeviceLocked && owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            pending = false
            model.startVoice(oneShot)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) { pending = true; startIfVisible() } else { pending = false; model.voice.permissionDenied() }
    }
    DisposableEffect(owner, model, oneShot) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) startIfVisible() }
        owner?.lifecycle?.addObserver(observer)
        onDispose { pending = false; owner?.lifecycle?.removeObserver(observer) }
    }
    return {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = true
            startIfVisible()
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProvideTextStyle(MaterialTheme.typography.labelSmall) { StatusLine(Glyph.Mic, color, voice.status) }
        if (voice.phase == "preparing" || voice.phase == "recognizing") LinearProgressIndicator(Modifier.fillMaxWidth())
        if (voice.phase == "review") {
            val reviewLabel = stringResource(R.string.voice_review_label)
            BasicTextField(voice.partial, voice::editTranscript,
                modifier = Modifier.fillMaxWidth().heightIn(min = 43.dp, max = 150.dp).padding(7.dp).semantics { contentDescription = reviewLabel },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
            Text(stringResource(R.string.voice_send_hint), style = MaterialTheme.typography.labelSmall)
        } else if (voice.partial.isNotBlank()) Text(voice.partial, maxLines = 2, style = MaterialTheme.typography.bodySmall)
        val cancelLabel = stringResource(R.string.voice_cancel)
        val levelLabel = stringResource(R.string.voice_level)
        val finishLabel = stringResource(R.string.voice_finish)
        val sendLabel = stringResource(R.string.voice_send)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { voice.stop() }, modifier = Modifier.size(48.dp).semantics { contentDescription = cancelLabel }) { AlphaIcon(Glyph.Close) }
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
            // The button content color dims a disabled glyph and keeps the contrast on the primary container.
            IconButton(onClick = voice::finishRecording, enabled = voice.phase == "listening", modifier = Modifier.size(48.dp).semantics { contentDescription = finishLabel }) { AlphaIcon(Glyph.Stop, color = LocalContentColor.current) }
            FilledIconButton(onClick = model::sendVoice, enabled = voice.phase == "review" && voice.partial.isNotBlank() && !model.attachmentBusy,
                modifier = Modifier.size(48.dp).semantics { contentDescription = sendLabel }) { AlphaIcon(Glyph.Arrow, color = LocalContentColor.current) }
        }
    }
}

@Composable fun VoiceControls(model: ShellViewModel) {
    val voice = model.voice
    val start = rememberVoiceStart(model, oneShot = false)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Text(stringResource(R.string.voice_session), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.voice_session_text))
        Text(stringResource(R.string.voice_models))
        Text(stringResource(R.string.voice_licenses))
        if (voice.permissionRequired) MicrophonePermissionGuidance(voice, start)
        else if (voice.status.isNotBlank()) StatusLine(Glyph.Mic, MaterialTheme.colorScheme.onSurfaceVariant, voice.status)
        if (voice.partial.isNotBlank()) Text(voice.partial)
        // A tonal button keeps the key action of «Настройки» as the one primary action.
        if (voice.active) FilledTonalButton(onClick = { voice.stop() }) { Text(stringResource(R.string.voice_stop)) }
        else FilledTonalButton(onClick = start) { Text(stringResource(R.string.voice_start)) }
    }
}
