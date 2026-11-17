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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.surfaces.AssistCapture
import com.alpha.assistant.voice.SpeechSource
import com.alpha.assistant.voice.VoiceSession
import com.alpha.assistant.voice.VoiceSettings
import com.alpha.assistant.voice.speechSources

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

/** [settings] opens «Сервис ИИ». */
@Composable fun VoiceControls(model: ShellViewModel, settings: () -> Unit, inSettings: Boolean = false) {
    val voice = model.voice
    val start = rememberVoiceStart(model, oneShot = false)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Text(stringResource(R.string.voice_session), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.voice_session_text))
        VoiceNote(model.accountProvider)
        if (inSettings) {
            ReadAloudSwitch(voice.settings)
            SpeechSourceChoice(voice.settings, model.accountProvider)
        }
        if (voice.permissionRequired) MicrophonePermissionGuidance(voice, start)
        else VoiceStatus(voice, settings)
        if (voice.partial.isNotBlank()) Text(voice.partial)
        if (voice.active) FilledTonalButton(onClick = { voice.stop() }) { Text(stringResource(R.string.voice_stop)) }
        else if (inSettings) Button(onClick = start, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.voice_start)) }
        else FilledTonalButton(onClick = start) { Text(stringResource(R.string.voice_start)) }
    }
}

/** The microphone note names the path of the account (spec 12A.8). Without an account, voice input does not start, so no note shows. */
@Composable
private fun VoiceNote(provider: AiProvider?) {
    if (provider == null) return
    Text(if (provider.profile.transcription == null) stringResource(R.string.voice_note_phone)
        else stringResource(R.string.voice_note_cloud, provider.displayName))
}

/** «Читать ответы вслух» (spec 12.2). The row is the switch, so its text labels the switch. */
@Composable
private fun ReadAloudSwitch(settings: VoiceSettings) {
    var on by remember(settings) { mutableStateOf(settings.readAloud) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch) { on = it; settings.readAloud = it },
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.voice_read_aloud), Modifier.weight(1f))
        Switch(on, onCheckedChange = null)
    }
}

/**
 * «Источник голоса» (spec 12.2). «Облако» shows only when [provider] has cloud voice. Without it, «Телефон» shows, and the
 * stored choice stays. The choice asks for no consent: the consent of the account already names the reply text (spec 14.7).
 */
@Composable
private fun SpeechSourceChoice(settings: VoiceSettings, provider: AiProvider?) {
    var source by remember(settings, provider) { mutableStateOf(settings.sourceFor(provider)) }
    val phone = stringResource(R.string.voice_source_phone)
    val cloud = stringResource(R.string.voice_source_cloud)
    SettingsChoice(R.string.voice_source, speechSources(provider), source, { if (it == SpeechSource.CLOUD) cloud else phone }) {
        settings.source = it
        source = it
    }
}

/**
 * The last reply was not read (spec 12.4): the status. Only for a missing Russian voice, also «Открыть настройки» for the
 * system speech settings when the phone has them.
 */
@Composable internal fun SpeechStatus(voice: VoiceSession) {
    if (voice.speechStatus.isBlank()) return
    val context = LocalContext.current
    StatusLine(Glyph.Mic, MaterialTheme.colorScheme.onSurfaceVariant, voice.speechStatus, PoliteLiveRegion)
    val speechSettings = remember(context) {
        Intent("com.android.settings.TTS_SETTINGS").takeIf { it.resolveActivity(context.packageManager) != null }
    }
    if (voice.speechVoiceMissing && speechSettings != null) TextButton(onClick = { runCatching { context.startActivity(speechSettings) } },
        Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.open_settings))
    }
}

/** The status of the last voice input and its fix: «Открыть настройки» (spec 12A.6) or «Скачать русский язык» (spec 12A.5). */
@Composable internal fun VoiceStatus(voice: VoiceSession, settings: () -> Unit) {
    if (voice.status.isNotBlank()) StatusLine(Glyph.Mic, MaterialTheme.colorScheme.onSurfaceVariant, voice.status, PoliteLiveRegion)
    SpeechStatus(voice)
    if (voice.settingsRequired) TextButton(onClick = settings, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_settings)) }
    if (voice.downloadOffered) TextButton(onClick = voice::downloadLanguage, Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.voice_phone_download))
    }
}
