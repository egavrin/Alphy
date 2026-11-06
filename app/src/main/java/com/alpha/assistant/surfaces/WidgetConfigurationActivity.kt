package com.alpha.assistant.surfaces

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.tools.*
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.AlphyActivity

class WidgetConfigurationActivity : AlphyActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if(id == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider != ComponentName(this,ToolWidgetProvider::class.java)) { finish(); return }
        setContent {
            AlphaTheme {
                var tools by remember { mutableStateOf<List<ToolRecord>>(emptyList()) }
                var loaded by remember { mutableStateOf(false) }
                var selected by remember { mutableStateOf(runCatching {ToolWidgets.toolId(this,id)}.getOrNull()) }
                var prefs by remember { mutableStateOf(runCatching {ToolWidgets.preferences(this,id)}.getOrDefault(WidgetPreferences())) }
                var generation by remember {mutableStateOf<String?>(null)}
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ToolStore(this@WidgetConfigurationActivity).use {it.library.generation to it.tools().filter {tool -> tool.saved && !tool.archived}}
                    } }.onSuccess {(owner,available) -> generation=owner;tools=available}.onFailure {error=getString(R.string.widget_read_failed)}
                    loaded = true
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.widget_configure_title),style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.widget_configure_detail))
                        tools.forEach { tool ->
                            Row { RadioButton(selected == tool.id,{ selected = tool.id }); TextButton(onClick = { selected = tool.id }) { Text(ToolSpec.storedTitle(tool.spec)) } }
                        }
                        if(loaded && tools.isEmpty()) Text(stringResource(R.string.widget_no_apps))
                        WidgetOptions(prefs) { prefs = it }
                        tools.find { it.id == selected }?.let { WidgetPreview(it,prefs) }
                        error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            runCatching {
                                ToolStore(this@WidgetConfigurationActivity).use { store ->
                                    check(generation==store.library.generation) {"The widget library changed."}
                                    check(selected?.let(store::tool)?.let { it.saved && !it.archived && ToolSpec.availablePersisted(it.spec)!=null } == true) { "Choose a saved tool" }
                                    ToolWidgets.bind(this@WidgetConfigurationActivity,id,selected!!,prefs,store)
                                    ToolWidgets.update(this@WidgetConfigurationActivity,id)
                                }
                            }.onSuccess {
                                setResult(Activity.RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)); finish()
                            }.onFailure { error = getString(R.string.widget_save_failed) }
                        },enabled = tools.any { it.id == selected }) { Text(stringResource(R.string.widget_save)) }
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.widget_cancel)) }
                    }
                }
            }
        }
    }
}
