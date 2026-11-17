package com.alpha.assistant

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.ui.AlphyActivity
import com.alpha.assistant.ui.VoiceVisibility
import com.alpha.assistant.voice.CloudSpeechInput
import com.alpha.assistant.voice.Microphone
import com.alpha.assistant.voice.PcmCapture
import com.alpha.assistant.voice.VoiceSession
import com.alpha.assistant.voice.VoiceSettings
import kotlinx.coroutines.*

/** Isolated lifecycle fixture. It never constructs chat storage or reads credentials. */
class VoiceTestActivity : AlphyActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var voice: VoiceSession; private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The real microphone with a fake key. An upload would reach only a closed loopback port, never a provider.
        // A voice settings file that the app never uses. This fixture never reads aloud.
        voice = VoiceSession(applicationContext, scope, transcript = {}, settings = VoiceSettings(applicationContext, "test-voice")) {
            CloudSpeechInput(AiAccount(AiProvider.OPENAI, "alpha-test-only"), Endpoints { "http://127.0.0.1:9" }, PcmCapture(Microphone))
        }
        setContent { VoiceVisibility(voice); Text(voice.status) }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
    }
    override fun onDestroy() { voice.close(); scope.cancel(); super.onDestroy() }
}
