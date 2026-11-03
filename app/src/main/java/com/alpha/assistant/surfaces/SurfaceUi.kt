package com.alpha.assistant.surfaces

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec

@Composable fun WidgetOptions(prefs: WidgetPreferences, change: (WidgetPreferences) -> Unit) {
    Text("Widget format",style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("adaptive","compact","wide").forEach { format ->
            FilterChip(prefs.format == format,{ change(prefs.copy(format = format)) },{ Text(format.replaceFirstChar { it.uppercase() }) })
        }
    }
    Row { Checkbox(prefs.details,{ change(prefs.copy(details = it)) }); Text("Show text details",Modifier.padding(top = 12.dp)) }
}
@Composable fun WidgetPreview(tool: ToolRecord, prefs: WidgetPreferences) {
    val context = LocalContext.current
    Text("Inert preview · final size depends on the launcher",style = MaterialTheme.typography.labelMedium)
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { android.widget.FrameLayout(it) },
        update = { frame ->
            frame.removeAllViews()
            val view = ToolWidgets.render(context,0,tool,prefs,prefs.format == "wide",false,if(prefs.format == "wide") 360 else 280).apply(context,frame)
            fun disable(node: android.view.View) {
                node.isEnabled = false; node.isClickable = false; node.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if(node is android.view.ViewGroup) repeat(node.childCount) { disable(node.getChildAt(it)) }
            }
            disable(view)
            frame.addView(view)
        },
        modifier = Modifier.widthIn(max = if(prefs.format == "wide") 420.dp else 230.dp).fillMaxWidth().height(if(prefs.format == "wide") 360.dp else 280.dp)
    )
}
@Composable fun PinToolButton(tool: ToolRecord) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }) { Text("Add to home screen") }
    if(show) {
        val context = LocalContext.current
        var widget by remember { mutableStateOf(true) }
        var prefs by remember { mutableStateOf(WidgetPreferences("compact",true)) }
        var token by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf<String?>(null) }
        val storage = remember { context.getSharedPreferences("alpha-pin-requests",0) }
        DisposableEffect(token) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if(key == token) status = token?.let { SurfaceEntry.status(context,it) } }
            storage.registerOnSharedPreferenceChangeListener(listener)
            token?.let { status = SurfaceEntry.status(context,it) }
            onDispose { storage.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        AlertDialog(onDismissRequest = { show = false },title = { Text("On your home screen") },text = {
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(widget,{ widget = true },{ Text("Widget") })
                    FilterChip(!widget,{ widget = false },{ Text("Shortcut") })
                }
                if(widget) { WidgetOptions(prefs) { prefs = it }; WidgetPreview(tool,prefs) }
                else { Text(ToolSpec.storedTitle(tool.spec),style = MaterialTheme.typography.titleLarge); Text("Opens this tool in Alphy.") }
                Text("Shared state, separate appearance. Text input and hidden controls open in Alphy. Only local buttons and toggles run here.")
                status?.let { Text(it) }
            }
        },confirmButton = { TextButton(onClick = { val result = SurfaceEntry.pin(context,tool,widget,prefs); token = result.first; status = result.second }) { Text("Request Android pin") } },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Close") } })
    }
}
@Composable fun SystemEntrySettings() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    val result = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = if(context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)) "Alphy is the system assistant." else "Assistant selection was cancelled or declined."
    }
    Text("Android entry points",style = MaterialTheme.typography.titleLarge)
    Text("Launcher, assistant gesture, and the Alphy Quick Settings tile continue the selected chat. Drafts remain unchanged. Send requests from Alphy. Device actions retain separate access and confirmation.")
    TextButton(onClick = {
        val request = SurfaceEntry.requestAssistant(context)
        if(request == null) status = "This device does not expose the assistant role."
        else runCatching { result.launch(request) }.onFailure { status = "This device could not open assistant selection." }
    }) { Text("Choose Alphy as system assistant") }
    TextButton(onClick = {
        runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }.onFailure { status = "This device does not expose assistant settings." }
    }) { Text("Open Android assistant settings") }
    Text("Add the Alphy tile from Android Quick Settings → Edit. Android and launcher support varies by device.")
    status?.let { Text(it,style = MaterialTheme.typography.bodySmall) }
}
