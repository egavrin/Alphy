package com.alpha.assistant

import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.alpha.assistant.surfaces.SurfaceEntry
import com.alpha.assistant.surfaces.ToolWidgets
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alpha.assistant.ui.AlphaShell
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.AlphyActivity
import com.alpha.assistant.ui.ShellViewModel

class MainActivity : AlphyActivity() {
    private lateinit var model: ShellViewModel
    private val surfaceUpdates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            model.tools.reload()
            Thread { runCatching { ToolWidgets.updateAll(context) } }.start()
        }
    }
    private fun receiveEntry(intent: Intent) {
        if(intent.action in listOf(Intent.ACTION_MAIN,Intent.ACTION_ASSIST,Intent.ACTION_VIEW)) model.openSurface(intent.getStringExtra("tool_id"),intent.getBooleanExtra("settings",false))
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveEntry(intent) }
    override fun onResume() { super.onResume(); model.tools.reload(); com.alpha.assistant.automations.AutomationRuntime.refresh(this) }
    override fun onDestroy() { unregisterReceiver(surfaceUpdates); super.onDestroy() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = com.alpha.assistant.surfaces.AssistantChat.model(application)
        val filter = IntentFilter(SurfaceEntry.TOOLS_CHANGED).apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
        androidx.core.content.ContextCompat.registerReceiver(this,surfaceUpdates,filter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        if(savedInstanceState == null) receiveEntry(intent)
        setContent { AlphaTheme { AlphaShell(model) } }
    }
}
