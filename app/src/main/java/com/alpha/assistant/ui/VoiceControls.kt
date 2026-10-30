package com.alpha.assistant.ui

import android.Manifest
import android.content.*
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alpha.assistant.voice.VoiceEngine
import com.alpha.assistant.voice.VoiceSession

@Composable fun VoiceVisibility(voice: VoiceSession) {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    DisposableEffect(owner, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) voice.stop()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { voice.stop("Screen locked · microphone off") }
        }
        owner?.lifecycle?.addObserver(observer)
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        onDispose { owner?.lifecycle?.removeObserver(observer); context.unregisterReceiver(receiver); voice.stop() }
    }
}

@Composable fun VoiceControls(model: ShellViewModel) {
    val voice = model.voice
    val context = LocalContext.current
    fun start() {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive && (context as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true)
            voice.start(model.snapshot.draft.text.isBlank() && model.snapshot.draft.attachments.isEmpty(), model.activeConversationId != null)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (allowed) start() else voice.permissionDenied() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Text("Audio stays on this device. Review each transcript and confirm Send before DeepSeek receives text. Stop ends the session.")
        VoiceEngine.entries.forEach { engine ->
            Row { RadioButton(selected = voice.engine == engine, onClick = { voice.choose(engine) }); Text(engine.label) }
        }
        Text("Model bundle alpha-voice-1. Packaged models: T-one 144 MB, Whisper Base Q5_1 60 MB, Silero Xenia 137 MB. SHA-256 verification precedes installation. ARM64 only.")
        Text("Silero v5.5 Xenia: personal noncommercial use only. T-one: Apache-2.0. Whisper: MIT. Model and third-party licenses are included in the APK.")
        Text(voice.status)
        if (voice.partial.isNotBlank()) Text(voice.partial)
        if (voice.active) Button(onClick = { voice.stop() }) { Text("Stop voice session") }
        else Button(onClick = {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) start()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }) { Text("Start continuous voice") }
    }
}
