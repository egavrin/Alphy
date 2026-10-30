package com.alpha.assistant.surfaces

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.*
import kotlinx.coroutines.*
import org.json.JSONObject

data class WidgetPreferences(val format: String = "adaptive", val details: Boolean = true)

object ToolWidgets {
    private const val ACTION = "com.alpha.assistant.WIDGET_ACTION"
    private fun settings(context: Context) = context.getSharedPreferences("alpha-widgets",Context.MODE_PRIVATE)
    fun preferences(context: Context, id: Int) = WidgetPreferences(settings(context).getString("$id-format","adaptive") ?: "adaptive",settings(context).getBoolean("$id-details",true))
    fun toolId(context: Context, id: Int) = settings(context).getString("$id-tool",null)
    fun bind(context: Context, id: Int, tool: String, prefs: WidgetPreferences) {
        require(id != AppWidgetManager.INVALID_APPWIDGET_ID && prefs.format in listOf("adaptive","compact","wide"))
        check(settings(context).edit().putString("$id-tool",tool).putString("$id-format",prefs.format).putBoolean("$id-details",prefs.details).commit()) { "Widget settings could not be saved" }
    }
    fun remove(context: Context, id: Int) { settings(context).edit().remove("$id-tool").remove("$id-format").remove("$id-details").apply() }
    fun openIntent(context: Context, tool: String? = null) = Intent(context,MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.Builder().scheme("alpha").authority("tool").appendPath(tool.orEmpty()).build()
        putExtra("tool_id",tool)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    private fun activity(context: Context, intent: Intent) = PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun actionIntent(context: Context, widgetId: Int, tool: ToolRecord, actionId: String, value: Boolean?) = Intent(context,ToolWidgetProvider::class.java).apply {
        action = ACTION
        data = Uri.Builder().scheme("alpha-widget").authority(widgetId.toString()).appendPath(tool.id).appendPath(tool.revision.toString()).appendPath(tool.stateVersion.toString()).appendPath(actionId).build()
        putExtra("widget_id",widgetId); putExtra("tool_id",tool.id); putExtra("revision",tool.revision); putExtra("state_version",tool.stateVersion); putExtra("action_id",actionId)
        if (value != null) putExtra("value",value)
    }
    /** The revision token makes repeated PendingIntent delivery a no-op after the first commit. */
    suspend fun perform(context: Context, intent: Intent, store: ToolStore, engine: ToolEngine,
        locked: Boolean = isLocked(context)): Boolean {
        if (locked || intent.action != ACTION) return false
        val id = intent.getStringExtra("tool_id") ?: return false
        if (toolId(context,intent.getIntExtra("widget_id",0)) != id) return false
        val tool = store.tool(id) ?: return false
        if (!tool.saved) return false
        return runCatching {
            val actionId = intent.getStringExtra("action_id") ?: return false
            val spec = ToolSpec.parse(tool.spec)
            val ui = spec.validateUi(JSONObject(tool.ui))
            val node = spec.action(ui,actionId) ?: return false
            if (node.getString("type") !in listOf("button","toggle")) return false
            val value = if (node.getString("type") == "toggle") {
                if (!intent.hasExtra("value")) return false
                intent.getBooleanExtra("value",false)
            } else null
            engine.interact(id,intent.getIntExtra("revision",-1),intent.getIntExtra("state_version",-1),actionId,value)
            true
        }.getOrDefault(false)
    }
    fun render(context: Context, widgetId: Int, tool: ToolRecord?, prefs: WidgetPreferences, wide: Boolean, locked: Boolean, height: Int = 320): RemoteViews {
        val views = RemoteViews(context.packageName,R.layout.tool_widget)
        views.removeAllViews(R.id.widget_content)
        val validated = if (locked) null else tool?.takeIf { it.saved }?.let { record -> runCatching {
            val spec = ToolSpec.parse(record.spec)
            Triple(spec,spec.validateState(record.state),spec.validateUi(JSONObject(record.ui)))
        }.getOrNull() }
        val open = activity(context,openIntent(context,tool?.id))
        views.setOnClickPendingIntent(R.id.widget_open,open)
        views.setOnClickPendingIntent(R.id.widget_title,open)
        val configure = Intent(context,WidgetConfigurationActivity::class.java).apply {
            data = Uri.parse("alpha-widget://configure/$widgetId")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
        }
        views.setOnClickPendingIntent(R.id.widget_settings,activity(context,configure))
        views.setTextViewText(R.id.widget_title,validated?.first?.title ?: "Alpha")
        views.setTextViewText(R.id.widget_subtitle,when {
            locked -> "Unlock to view this tool"
            validated == null -> "Choose a saved tool in widget settings"
            else -> "Local tool · revision ${tool!!.revision}"
        })
        if (validated != null) {
            val (_,state,ui) = validated
            val nodes = mutableListOf<JSONObject>()
            fun flatten(node: JSONObject) {
                if(node.getString("type") in listOf("row","column")) {
                    val children = node.getJSONArray("children")
                    repeat(children.length()) { flatten(children.getJSONObject(it)) }
                } else nodes += node
            }
            flatten(ui)
            val capacity = ((height-132)/48).coerceIn(1,if(wide) 6 else 3)
            nodes.filter { prefs.details || it.getString("type") != "text" || it.has("bind") }.take(capacity).forEach { node ->
                val type = node.getString("type")
                val text = node.getString("text")
                val label = if(type == "list") "$text: ${state.getJSONArray(node.getString("bind")).length()} записей · открыть в Alpha" else if(node.has("bind")) "$text: ${state.get(node.getString("bind"))}" else text
                if(type in listOf("button","toggle")) {
                    val child = RemoteViews(context.packageName,R.layout.tool_widget_action)
                    child.setTextViewText(R.id.widget_action,if(type == "toggle") "${if(state.getBoolean(node.getString("bind"))) "✓" else "○"} $text" else text)
                    val intent = actionIntent(context,widgetId,tool!!,node.getString("action"),if(type == "toggle") !state.getBoolean(node.getString("bind")) else null)
                    child.setOnClickPendingIntent(R.id.widget_action,PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    views.addView(R.id.widget_content,child)
                } else {
                    val child = RemoteViews(context.packageName,R.layout.tool_widget_text)
                    child.setTextViewText(R.id.widget_text,if(type == "input") "$label · edit in Alpha" else label)
                    child.setOnClickPendingIntent(R.id.widget_text,open)
                    views.addView(R.id.widget_content,child)
                }
            }
            if(nodes.size > capacity) views.setTextViewText(R.id.widget_open,"Open all ${nodes.size} controls")
        }
        return views
    }
    fun update(context: Context, id: Int, store: ToolStore) {
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(id)
        val prefs = preferences(context,id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,260)
        val locked = isLocked(context)
        val record = toolId(context,id)?.let(store::tool)
        manager.updateAppWidget(id,render(context,id,record,prefs,prefs.format == "wide" || (prefs.format == "adaptive" && width >= 250),locked,height))
    }
    fun updateAll(context: Context) {
        ToolStore(context).use { store ->
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,ToolWidgetProvider::class.java)).forEach { update(context,it,store) }
        }
    }
    fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return keyguard.isDeviceLocked || keyguard.isKeyguardLocked
    }
    fun isAction(intent: Intent) = intent.action == ACTION
}

class ToolWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ToolStore(context).use { store -> ids.forEach { ToolWidgets.update(context,it,store) } }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { onUpdate(context,manager,intArrayOf(id)) }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { ToolWidgets.remove(context,it) } }
    override fun onRestored(context: Context, old: IntArray, new: IntArray) {
        old.zip(new).forEach { (from,to) -> ToolWidgets.toolId(context,from)?.let { ToolWidgets.bind(context,to,it,ToolWidgets.preferences(context,from)); ToolWidgets.remove(context,from) } }
        onUpdate(context,AppWidgetManager.getInstance(context),new)
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (!ToolWidgets.isAction(intent)) { super.onReceive(context,intent); return }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { ToolStore(context).use { store ->
                    val offline = object : ChatProvider {
                        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { error("Widgets only execute local actions") }
                    }
                    ToolWidgets.perform(context,intent,store,ToolEngine(store,ToolGenerationService(offline,SandboxClient(context))))
                }
                ToolWidgets.updateAll(context)
                context.sendBroadcast(Intent(SurfaceEntry.TOOLS_CHANGED).setPackage(context.packageName))
                }
            } finally { pending.finish() }
        }
    }
}
