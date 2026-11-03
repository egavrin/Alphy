package com.alpha.assistant.surfaces

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alpha.assistant.tools.*
import com.alpha.assistant.ui.AlphaTheme

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if(id == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider != ComponentName(this,ToolWidgetProvider::class.java)) { finish(); return }
        setContent {
            AlphaTheme("system") {
                var tools by remember { mutableStateOf<List<ToolRecord>>(emptyList()) }
                var loaded by remember { mutableStateOf(false) }
                var selected by remember { mutableStateOf(ToolWidgets.toolId(this,id)) }
                var prefs by remember { mutableStateOf(ToolWidgets.preferences(this,id)) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ToolStore(this@WidgetConfigurationActivity).use { it.tools().filter { tool -> tool.saved && !tool.archived } } } }
                        .onSuccess { tools = it }.onFailure { error = "Saved tools could not be read. Data remains available." }
                    loaded = true
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Alphy widget",style = MaterialTheme.typography.headlineMedium)
                        Text("Choose a saved tool. Each widget has its own appearance and shares the tool state.")
                        tools.forEach { tool ->
                            Row { RadioButton(selected == tool.id,{ selected = tool.id }); TextButton(onClick = { selected = tool.id }) { Text(ToolSpec.storedTitle(tool.spec)) } }
                        }
                        if(loaded && tools.isEmpty()) Text("No saved tools. Save a tool in Alphy first.")
                        WidgetOptions(prefs) { prefs = it }
                        tools.find { it.id == selected }?.let { WidgetPreview(it,prefs) }
                        error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            runCatching {
                                ToolStore(this@WidgetConfigurationActivity).use { store ->
                                    check(selected?.let(store::tool)?.let { it.saved && !it.archived && ToolSpec.available(it.spec)!=null } == true) { "Choose a saved tool" }
                                    ToolWidgets.bind(this@WidgetConfigurationActivity,id,selected!!,prefs)
                                    ToolWidgets.update(this@WidgetConfigurationActivity,id,store)
                                }
                            }.onSuccess {
                                setResult(Activity.RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)); finish()
                            }.onFailure { error = "Widget could not be saved. Choose a saved tool and retry." }
                        },enabled = tools.any { it.id == selected }) { Text("Save widget") }
                        TextButton(onClick = { finish() }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
