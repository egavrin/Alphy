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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alpha.assistant.voice.VoiceSession

@Composable fun VoiceVisibility(voice: VoiceSession) {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    DisposableEffect(owner, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) voice.stop()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { voice.stop("Экран заблокирован · микрофон выключен") }
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
    Text("Разрешите микрофон в настройках Android и повторите запись.", style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = retry) { Text("Разрешить микрофон") }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                .onFailure { voice.permissionDenied() }
        }) { Text("Настройки Android") }
    }
}

@Composable internal fun InlineVoiceRecorder(model: ShellViewModel) {
    val voice = model.voice
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(voice.status, style = MaterialTheme.typography.labelSmall)
        if (voice.phase == "preparing" || voice.phase == "recognizing") LinearProgressIndicator(Modifier.fillMaxWidth())
        if (voice.phase == "review") {
            BasicTextField(voice.partial, voice::editTranscript,
                modifier = Modifier.fillMaxWidth().heightIn(min = 43.dp, max = 150.dp).padding(7.dp).semantics { contentDescription = "Проверить голосовой текст" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
            Text("Только текст и выбранные вложения отправятся после нажатия ↑.", style = MaterialTheme.typography.labelSmall)
        } else if (voice.partial.isNotBlank()) Text(voice.partial, maxLines = 2, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { voice.stop() }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Отменить запись" }) { AlphaIcon(Glyph.Close) }
            Canvas(Modifier.weight(1f).height(40.dp).semantics {
                contentDescription = "Уровень микрофона"
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
            IconButton(onClick = voice::finishRecording, enabled = voice.phase == "listening", modifier = Modifier.size(48.dp).semantics { contentDescription = "Завершить запись" }) { Text("■") }
            FilledIconButton(onClick = model::sendVoice, enabled = voice.phase == "review" && voice.partial.isNotBlank() && !model.attachmentBusy,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Отправить голосовой текст" }) { Text("↑", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@Composable fun VoiceControls(model: ShellViewModel) {
    val voice = model.voice
    val start = rememberVoiceStart(model, oneShot = false)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Text("Непрерывный голосовой сеанс", style = MaterialTheme.typography.titleMedium)
        Text("T-one распознаёт русскую речь на устройстве. Проверьте текст и нажмите «Отправить». Silero озвучит ответ, затем микрофон включится снова. Кнопка «Остановить сеанс» завершает работу.")
        Text("Модели в приложении: T-one — 144 МБ, Silero Xenia — 137 МБ. Перед установкой проверяются контрольные суммы SHA-256. Требуется ARM64.")
        Text("Silero v5.5 Xenia разрешён только для личного некоммерческого использования. T-one: Apache-2.0. Лицензии моделей и библиотек включены в приложение.")
        if (voice.permissionRequired) MicrophonePermissionGuidance(voice, start) else if (voice.status.isNotBlank()) Text(voice.status)
        if (voice.partial.isNotBlank()) Text(voice.partial)
        if (voice.active) Button(onClick = { voice.stop() }) { Text("Остановить сеанс") }
        else Button(onClick = start) { Text("Начать непрерывный сеанс") }
    }
}
