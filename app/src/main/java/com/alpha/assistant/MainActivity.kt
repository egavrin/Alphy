package com.alpha.assistant

import com.alpha.assistant.tools.ToolLibraryStorage
import com.alpha.assistant.data.ShellStore
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
            if (intent.action == ShellStore.AUTOMATION_RESULT_CHANGED) {
                model.refreshAutomationMessages()
                return
            }
            model.tools.reload()
            Thread { runCatching { ToolWidgets.updateAll(context) } }.start()
        }
    }
    private fun receiveEntry(intent: Intent) {
        // A launch from Recents repeats the extras of the old entry.
        if(intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "alpha" && intent.data?.authority == "automation-chat") {
            intent.data?.pathSegments?.singleOrNull()?.let(model::openAutomationConversation)
            return
        }
        if(intent.action in listOf(Intent.ACTION_MAIN,Intent.ACTION_ASSIST,Intent.ACTION_VIEW)) {
            val tool=intent.getStringExtra("tool_id")
            if(tool!=null && intent.data?.scheme=="alpha" && intent.data?.authority=="tool") {
                val library=runCatching {ToolLibraryStorage.current(this)}.getOrNull()
                val expected=ToolLibraryStorage.intentGeneration(intent)
                if(library?.generation!=expected) {
                    model.openCreationSurface(com.alpha.assistant.ui.ShellDestination.MiniApps,"")
                    return
                }
            }
            model.openSurface(tool,intent.getBooleanExtra("settings",false))
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveEntry(intent) }
    override fun onResume() { super.onResume(); model.tools.reload(); model.refreshAutomationMessages(); com.alpha.assistant.automations.AutomationRuntime.refresh(this) }
    override fun onDestroy() { unregisterReceiver(surfaceUpdates); super.onDestroy() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = com.alpha.assistant.surfaces.AssistantChat.model(application)
        val filter = IntentFilter(SurfaceEntry.TOOLS_CHANGED).apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(ShellStore.AUTOMATION_RESULT_CHANGED) }
        androidx.core.content.ContextCompat.registerReceiver(this,surfaceUpdates,filter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        if(savedInstanceState == null) receiveEntry(intent)
        setContent { AlphaTheme { AlphaShell(model) } }
    }
}
