package com.alpha.assistant.surfaces

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.SizeF
import android.view.View
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
    // 14dp top + 14dp bottom + 48dp footer in tool_widget.xml.
    private const val FIXED_HEIGHT_DP = 76
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
        if (!tool.saved || tool.archived) return false
        return runCatching {
            val actionId = intent.getStringExtra("action_id") ?: return false
            val spec = ToolSpec.parse(tool.spec)
            if(spec.surface != null)return false
            val ui = spec.validateUi(JSONObject(tool.ui),JSONObject(tool.state))
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
    private fun rowHeight(context: Context) = maxOf(48,kotlin.math.ceil(20*context.resources.configuration.fontScale + 12.0).toInt())
    private fun titleHeight(context: Context) = kotlin.math.ceil(24*context.resources.configuration.fontScale.toDouble()).toInt()
    fun render(context: Context, widgetId: Int, tool: ToolRecord?, prefs: WidgetPreferences, wide: Boolean, locked: Boolean, height: Int = 320, width: Int = if(wide) 320 else 180): RemoteViews {
        val views = RemoteViews(context.packageName,R.layout.tool_widget)
        views.removeAllViews(R.id.widget_content)
        val validated = if (locked) null else tool?.takeIf { it.saved && !it.archived }?.let { record -> runCatching {
            val spec = ToolSpec.parse(record.spec)
            Triple(spec,spec.validateState(record.state),spec.validateUi(JSONObject(record.ui),JSONObject(record.state)))
        }.getOrNull() }
        val open = activity(context,openIntent(context,tool?.id))
        views.setOnClickPendingIntent(R.id.widget_open,open)
        views.setOnClickPendingIntent(R.id.widget_title,open)
        val configure = Intent(context,WidgetConfigurationActivity::class.java).apply {
            data = Uri.parse("alpha-widget://configure/$widgetId")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
        }
        views.setOnClickPendingIntent(R.id.widget_settings,activity(context,configure))
        views.setTextViewText(R.id.widget_title,validated?.first?.title?.let(ToolUiCatalog::widgetText) ?: "Alphy")
        views.setTextViewText(R.id.widget_subtitle,when {
            locked -> "Разблокируй устройство"
            tool?.archived == true -> "Инструмент в архиве · восстанови в Alpha"
            validated == null -> "Инструмент недоступен · выбери сохранённый инструмент"
            else -> "Local tool · revision ${tool!!.revision}"
        })
        if (validated != null) {
            val (_,state,ui) = validated
            val rowHeight = rowHeight(context)
            val titleHeight = titleHeight(context)
            // Reserve space for state and the primary action before the decorative header.
            val showTitle = height >= FIXED_HEIGHT_DP + titleHeight + 2*rowHeight
            views.setViewVisibility(R.id.widget_title,if(showTitle) View.VISIBLE else View.GONE)
            views.setInt(R.id.widget_title,"setHeight",(titleHeight*context.resources.displayMetrics.density).toInt())
            views.setViewVisibility(R.id.widget_subtitle,View.GONE)
            views.setViewVisibility(R.id.widget_settings,if(width >= 220) View.VISIBLE else View.GONE)
            val nodes = if(validated.first.surface != null) listOf(JSONObject().put("type","text").put("text","Interactive surface · Open in Alphy")) else ToolUiCatalog.widgetNodes(ui)
            val capacity = ((height-FIXED_HEIGHT_DP-if(showTitle) titleHeight else 0)/rowHeight).coerceIn(0,if(wide) 6 else 3)
            val candidates = nodes.filter { node ->
                node.getString("type") != "text" || node.has("bind") ||
                    (prefs.details && !node.getString("text").equals(validated.first.title,ignoreCase = true))
            }
            val selected = (listOfNotNull(candidates.firstOrNull { it.has("bind") },
                candidates.firstOrNull { it.getString("type") in listOf("button","toggle") }) +
                candidates.filter { it.has("bind") } + candidates).distinct().take(capacity)
            selected.forEach { node ->
                val type = node.getString("type")
                val text = ToolUiCatalog.widgetText(node.getString("text"))
                val label = ToolUiCatalog.widgetLabel(node,state)
                if(type in listOf("button","toggle")) {
                    val child = RemoteViews(context.packageName,R.layout.tool_widget_action)
                    child.setInt(R.id.widget_action,"setHeight",(rowHeight*context.resources.displayMetrics.density).toInt())
                    child.setBoolean(R.id.widget_action,"setEnabled",ToolUiCatalog.enabled(node))
                    child.setTextViewText(R.id.widget_action,if(type == "toggle") "${if((ToolUiCatalog.value(state,node.getString("bind")) as Boolean)) "✓" else "○"} $text" else text)
                    val intent = actionIntent(context,widgetId,tool!!,node.getString("action"),if(type == "toggle") !(ToolUiCatalog.value(state,node.getString("bind")) as Boolean) else null)
                    child.setOnClickPendingIntent(R.id.widget_action,PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    views.addView(R.id.widget_content,child)
                } else {
                    val child = RemoteViews(context.packageName,R.layout.tool_widget_text)
                    child.setInt(R.id.widget_text,"setHeight",(rowHeight*context.resources.displayMetrics.density).toInt())
                    child.setTextViewText(R.id.widget_text,label)
                    child.setOnClickPendingIntent(R.id.widget_text,open)
                    views.addView(R.id.widget_content,child)
                }
            }
            if(candidates.size > selected.size) views.setTextViewText(R.id.widget_open,"Open ${candidates.size-selected.size} more")
        }
        return views
    }
    fun renderForHost(context: Context, id: Int, tool: ToolRecord?, prefs: WidgetPreferences, locked: Boolean, options: Bundle): RemoteViews {
        fun layout(width: Int, height: Int) = render(context,id,tool,prefs,
            prefs.format == "wide" || (prefs.format == "adaptive" && width >= 250),locked,height,width)
        return if(Build.VERSION.SDK_INT >= 31) {
            val row = rowHeight(context)
            val title = titleHeight(context)
            val sizes = listOf(140 to 140,140 to (FIXED_HEIGHT_DP+2*row),140 to (FIXED_HEIGHT_DP+title+2*row),140 to (FIXED_HEIGHT_DP+title+3*row),
                220 to 140,220 to (FIXED_HEIGHT_DP+2*row),220 to (FIXED_HEIGHT_DP+title+2*row),220 to (FIXED_HEIGHT_DP+title+3*row)) +
                (2..6).map { 250 to (FIXED_HEIGHT_DP+title+it*row) }
            RemoteViews(sizes.distinct().associate { (width,height) -> SizeF(width.toFloat(),height.toFloat()) to layout(width,height) })
        } else {
            // Legacy hosts supply portrait width/height and landscape width/height as separate range bounds.
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180)
            val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,minWidth)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,minHeight)
            RemoteViews(layout(maxWidth,minHeight),layout(minWidth,maxHeight))
        }
    }
    fun update(context: Context, id: Int, store: ToolStore) {
        val manager = AppWidgetManager.getInstance(context)
        manager.updateAppWidget(id,renderForHost(context,id,toolId(context,id)?.let(store::tool),preferences(context,id),
            isLocked(context),manager.getAppWidgetOptions(id)))
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
