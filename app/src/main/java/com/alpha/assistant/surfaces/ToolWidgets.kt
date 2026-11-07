package com.alpha.assistant.surfaces

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.Uri
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.alpha.assistant.MainActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.surfaces.widgetauthoring.WidgetLayoutStore
import com.alpha.assistant.surfaces.widgetauthoring.projectRows
import com.alpha.assistant.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class WidgetPreferences(val format: String = "adaptive", val details: Boolean = true)

object ToolWidgets {
    private const val ACTION = "com.alpha.assistant.WIDGET_ACTION"
    internal const val REFRESH = "com.alpha.assistant.WIDGET_REFRESH"
    internal val refreshes = MutableStateFlow(0L)
    internal fun settings(context: Context) = settings(context, ToolLibraryStorage.current(context).generation)
    private fun settings(context: Context, generation: String) = context.getSharedPreferences(
        libraryPreferences("alpha-widgets",generation),Context.MODE_PRIVATE)
    internal fun libraryPreferences(prefix: String,generation: String)=if(generation==ToolLibraryStorage.ORIGINAL) prefix else "$prefix-$generation"
    internal fun shortcutId(owner: String,generation: String)=libraryPreferences("tool",generation)+"-$owner"
    fun preferences(context: Context, id: Int) = settings(context).widgetPreferences(id)
    private fun SharedPreferences.widgetPreferences(id: Int) =
        WidgetPreferences(getString("$id-format","adaptive") ?: "adaptive",getBoolean("$id-details",true))
    fun toolId(context: Context, id: Int) = settings(context).getString("$id-tool",null)
    private val bindingLock=Any()
    internal fun <T> withBindings(block: ()->T): T = synchronized(bindingLock,block)
    internal fun bindings(context: Context,owner: String): Set<Int> = settings(context).all
        .mapNotNull { (key,value) -> if(value==owner && key.endsWith("-tool")) key.removeSuffix("-tool").toIntOrNull() else null }.toSet()
    fun deleted(context: Context,id: Int) = settings(context).getBoolean("$id-deleted",false)
    fun bind(context: Context, id: Int, tool: String, prefs: WidgetPreferences, store: ToolStore? = null) = withBindings {
        requireBinding(id, tool, prefs, store)
        check(settings(context).edit().putBinding(id, tool, prefs).commit()) { "Widget settings could not be saved" }
    }
    private fun requireBinding(id: Int, tool: String, prefs: WidgetPreferences, store: ToolStore?) {
        require(id != AppWidgetManager.INVALID_APPWIDGET_ID && prefs.format in listOf("adaptive","compact","wide"))
        if(store!=null) check(store.tool(tool)?.let { it.saved && !it.archived } == true) { "The mini-app is unavailable." }
    }
    private fun SharedPreferences.Editor.putBinding(id: Int, tool: String, prefs: WidgetPreferences) =
        putString("$id-tool",tool).putString("$id-format",prefs.format).putBoolean("$id-details",prefs.details).remove("$id-deleted")

    private fun SharedPreferences.Editor.removeBinding(id: Int) =
        remove("$id-tool").remove("$id-format").remove("$id-details").remove("$id-deleted")

    fun remove(context: Context, id: Int) = withBindings {
        settings(context).edit().removeBinding(id).apply()
    }

    internal fun restoreBindings(context: Context, store: ToolStore, oldIds: IntArray, newIds: IntArray) = withBindings {
        data class Move(val from: Int, val to: Int, val owner: String, val preferences: WidgetPreferences)

        val settings = settings(context, store.library.generation)
        // Read every source before writing, since a destination can also be a source.
        val moves = oldIds.zip(newIds).mapNotNull { (from, to) ->
            val owner = settings.getString("$from-tool", null) ?: return@mapNotNull null
            runCatching {
                val preferences = settings.widgetPreferences(from)
                requireBinding(to, owner, preferences, store)
                Move(from, to, owner, preferences)
            }.getOrNull()
        }
        if (moves.isEmpty()) return@withBindings
        val destinations = moves.mapTo(mutableSetOf()) { it.to }
        val edit = settings.edit()
        for ((from, to, owner, preferences) in moves) {
            if (from !in destinations) edit.removeBinding(from)
            edit.putBinding(to, owner, preferences)
        }
        check(edit.commit()) { "Widget settings could not be saved" }
    }
    internal fun removeDeletedBindings(context: Context,owner: String,ids: Set<Int>,store: ToolStore) = withBindings {
        val preferences=settings(context)
        val edit=preferences.edit()
        ids.filter {toolId(context,it)==owner}.forEach { id ->
            edit.remove("$id-tool").remove("$id-format").remove("$id-details").putBoolean("$id-deleted",true)
        }
        check(edit.commit()) { "Widget cleanup could not be saved." }
        if(store.primaryLibrary) updateAll(context)
    }
    fun openIntent(context: Context,tool: String?=null,generation: String=runCatching {ToolLibraryStorage.current(context).generation}
        .getOrDefault(ToolLibraryStorage.ORIGINAL)) = Intent(context,MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.Builder().scheme("alpha").authority("tool").apply {
            if(generation!=ToolLibraryStorage.ORIGINAL) appendPath(generation)
        }.appendPath(tool.orEmpty()).build()
        putExtra("tool_id",tool)
        putExtra(ToolLibraryStorage.GENERATION_EXTRA,generation)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    fun actionIntent(context: Context,widgetId: Int,tool: ToolRecord,actionId: String,value: Boolean?,
        generation: String=ToolLibraryStorage.current(context).generation) = Intent(context,ToolWidgetProvider::class.java).apply {
        action = ACTION
        data = Uri.Builder().scheme("alpha-widget").authority(widgetId.toString()).apply {
            if(generation!=ToolLibraryStorage.ORIGINAL) appendPath(generation)
        }.appendPath(tool.id).appendPath(tool.revision.toString()).appendPath(tool.stateVersion.toString()).appendPath(actionId).build()
        putExtra(ToolLibraryStorage.GENERATION_EXTRA,generation)
        putExtra("widget_id",widgetId); putExtra("tool_id",tool.id); putExtra("revision",tool.revision); putExtra("state_version",tool.stateVersion); putExtra("action_id",actionId)
        if (value != null) putExtra("value",value)
    }
    /** The revision token makes repeated PendingIntent delivery a no-op after the first commit. */
    suspend fun perform(context: Context, intent: Intent, store: ToolStore, engine: ToolEngine,
        locked: Boolean = isLocked(context)): Boolean {
        if (locked || intent.action != ACTION) return false
        if(ToolLibraryStorage.intentGeneration(intent)!=store.library.generation) return false
        val id = intent.getStringExtra("tool_id") ?: return false
        if (toolId(context,intent.getIntExtra("widget_id",0)) != id) return false
        val tool = runCatching {store.tool(id)}.getOrNull() ?: return false
        if (!tool.saved || tool.archived) return false
        return runCatching {
            val actionId = intent.getStringExtra("action_id") ?: return false
            val projection = projectWidget(tool)
            val layouts = WidgetLayoutStore(store)
            val layout = layouts.read(id)
            val nodes = layout?.takeIf { it.revision == tool.revision }?.let { projectRows(projection,it.presentation) } ?: projection.nodes
            val node = nodes.firstOrNull { it.widgetActionId() == actionId } ?: return false
            val value = if (node.getString("type") == "toggle") {
                if (!intent.hasExtra("value")) return false
                intent.getBooleanExtra("value",false)
            } else null
            engine.interact(id,intent.getIntExtra("revision",-1),intent.getIntExtra("state_version",-1),actionId,value,allowDeviceEffects=false,
                onWidgetEvaluated = { check(layouts.read(id)?.version == layout?.version) { "The widget layout changed." } })
            true
        }.getOrDefault(false)
    }
    private fun update(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        context.sendBroadcast(Intent(context,ToolWidgetProvider::class.java).setAction(REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,ids))
    }

    fun update(context: Context, id: Int) = update(context,intArrayOf(id))

    fun updateAll(context: Context) {
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,ToolWidgetProvider::class.java))
        update(context,ids)
    }

    fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return keyguard.isDeviceLocked || keyguard.isKeyguardLocked
    }
    fun isAction(intent: Intent) = intent.action == ACTION
}

class ToolWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ToolGlanceWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ToolWidgets.refreshes.update { it + 1 }
        super.onUpdate(context,appWidgetManager,appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        runCatching {ToolLibraryStorage.production(context);appWidgetIds.forEach {ToolWidgets.remove(context,it)}}
        super.onDeleted(context,appWidgetIds)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        runCatching {ToolStore(context).use { store ->
            store.readableDatabase
            ToolWidgets.restoreBindings(context, store, oldWidgetIds, newWidgetIds)
        }}
        super.onRestored(context,oldWidgetIds,newWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ToolWidgets.REFRESH) {
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: return
            onUpdate(context,AppWidgetManager.getInstance(context),ids)
            return
        }
        if (!ToolWidgets.isAction(intent)) {
            super.onReceive(context,intent)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    ToolStore(context).use { store ->
                        val offline = object : ChatProvider {
                            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                                error("Widgets only execute local actions")
                            }
                        }
                        ToolWidgets.perform(context,intent,store,ToolEngine(store,ToolGenerationService(offline,SandboxClient(context))))
                    }
                }
                ToolWidgets.updateAll(context)
                context.sendBroadcast(Intent(SurfaceEntry.TOOLS_CHANGED).setPackage(context.packageName))
            } finally {
                pending.finish()
            }
        }
    }
}
