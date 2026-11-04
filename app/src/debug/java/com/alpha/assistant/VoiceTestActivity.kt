package com.alpha.assistant

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.alpha.assistant.ui.AlphyActivity
import com.alpha.assistant.ui.VoiceVisibility
import com.alpha.assistant.voice.VoiceSession
import kotlinx.coroutines.*

/** Isolated lifecycle fixture. It never constructs chat storage or reads credentials. */
class VoiceTestActivity : AlphyActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var voice: VoiceSession; private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = VoiceSession(applicationContext, scope, transcript = {})
        setContent { VoiceVisibility(voice); Text(voice.status) }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
    }
    override fun onDestroy() { voice.close(); scope.cancel(); super.onDestroy() }
}
