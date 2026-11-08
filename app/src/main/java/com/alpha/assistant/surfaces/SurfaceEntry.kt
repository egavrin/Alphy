package com.alpha.assistant.surfaces

import com.alpha.assistant.tools.ToolLibraryStorage
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Build
import android.service.quicksettings.TileService
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import java.util.UUID

object SurfaceEntry {
    const val TOOLS_CHANGED = "com.alpha.assistant.TOOLS_CHANGED"
    fun chatIntent(context: Context) = Intent(context,MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    private fun statusPreferences(context: Context) = context.getSharedPreferences(
        ToolWidgets.libraryPreferences("alpha-pin-requests",ToolLibraryStorage.current(context).generation),Context.MODE_PRIVATE)
    fun status(context: Context, token: String) =
        statusPreferences(context).getString(token,null) ?: context.getString(R.string.widget_pin_not_requested)
    fun setStatus(context: Context, token: String, status: String) { statusPreferences(context).edit().putString(token,status).apply() }
    private fun callback(context: Context, token: String, widget: Boolean, owner: String,generation: String): PendingIntent {
        // Android fills EXTRA_APPWIDGET_ID. Only this explicit, one-shot callback needs mutability.
        val flags = PendingIntent.FLAG_ONE_SHOT or if(widget) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context,0,
            Intent(context,PinResultReceiver::class.java).setAction(if(widget) "alpha.PIN_WIDGET" else "alpha.PIN_SHORTCUT")
                .setData(android.net.Uri.parse("alpha-pin://result/$token")).putExtra("owner",owner)
                .putExtra(ToolLibraryStorage.GENERATION_EXTRA,generation),flags)
    }
    internal fun removeToolPins(context: Context,owner: String,launcher: Boolean) = ToolWidgets.withBindings {
        val preferences=statusPreferences(context)
        val edit=preferences.edit()
        preferences.all.forEach { (key,value) ->
            if(key.endsWith("-proposal") && value is String &&
                runCatching {org.json.JSONObject(value).optString("tool")==owner}.getOrDefault(false)) {
                val token=key.removeSuffix("-proposal")
                edit.remove(key).remove("$token-pending").putString(token,context.getString(R.string.widget_deleted))
            }
        }
        check(edit.commit()) { "Pin cleanup could not be saved." }
        if(launcher) {
            val manager=context.getSystemService(ShortcutManager::class.java)
            val id=ToolWidgets.shortcutId(owner,ToolLibraryStorage.current(context).generation)
            manager.removeDynamicShortcuts(listOf(id))
            if(manager.pinnedShortcuts.any {it.id==id && it.isEnabled}) manager.disableShortcuts(listOf(id),context.getString(R.string.widget_deleted))
        }
    }
    suspend fun pin(context: Context, tool: ToolRecord, widget: Boolean, prefs: WidgetPreferences): Pair<String,String> {
        val token = UUID.randomUUID().toString()
        val spec = ToolSpec.availablePersisted(tool.spec)
        if (!tool.saved || tool.archived || spec == null) return token to context.getString(R.string.widget_pin_unavailable)
        val manager = AppWidgetManager.getInstance(context)
        val shortcuts = context.getSystemService(ShortcutManager::class.java)
        val supported = if (widget) manager.isRequestPinAppWidgetSupported else shortcuts.isRequestPinShortcutSupported
        if (!supported) return token to context.getString(R.string.widget_pin_unsupported)
        val preview = if (widget) {
            try {
                ToolGlanceWidget.preview(context,tool,prefs)
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (_: Exception) {
                return token to context.getString(R.string.widget_pin_failed)
            }
        } else null
        return ToolWidgets.withBindings {
            val generation=runCatching {
                    com.alpha.assistant.tools.ToolStore(context).use { store ->
                        check(store.tool(tool.id)?.let {it.saved && !it.archived && it.revision==tool.revision}==true)
                        store.library.generation
                    }
                }.getOrNull() ?: return@withBindings token to context.getString(R.string.widget_pin_unavailable)
            val storage = statusPreferences(context)
            val provider = ComponentName(context,ToolWidgetProvider::class.java)
            val proposal = org.json.JSONObject().put("tool",tool.id)
            if (widget) {
                proposal.put("format",prefs.format).put("details",prefs.details)
                    .put("before",org.json.JSONArray(manager.getAppWidgetIds(provider).toList()))
            }
            try {
                check(storage.edit().putBoolean("$token-pending",true).putString("$token-proposal",proposal.toString())
                    .putString(token,context.getString(R.string.widget_pin_pending)).commit())
                val sent = if (widget) {
                    manager.requestPinAppWidget(provider,
                        Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,preview) },
                        callback(context,token,true,tool.id,generation))
                } else {
                    val shortcut = ShortcutInfo.Builder(context,ToolWidgets.shortcutId(tool.id,generation)).setShortLabel(spec.title.take(40))
                        .setIcon(Icon.createWithResource(context,R.drawable.ic_alpha)).setIntent(ToolWidgets.openIntent(context,tool.id)).build()
                    shortcuts.requestPinShortcut(shortcut,callback(context,token,false,tool.id,generation).intentSender)
                }
                if (!sent) {
                    storage.edit().remove("$token-pending").remove("$token-proposal").apply()
                    setStatus(context,token,context.getString(R.string.widget_pin_declined))
                }
            } catch (_: Exception) {
                storage.edit().remove("$token-pending").remove("$token-proposal").apply()
                setStatus(context,token,context.getString(R.string.widget_pin_failed))
            }
            token to status(context,token)
        }
    }
}
class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        if(uri.scheme != "alpha-pin" || uri.authority != "result") return
        val token = uri.lastPathSegment ?: return
        val library=runCatching {ToolLibraryStorage.production(context)}.getOrNull() ?: return
        if(ToolLibraryStorage.intentGeneration(intent)!=library.generation) return
        val prefs=context.getSharedPreferences(ToolWidgets.libraryPreferences("alpha-pin-requests",library.generation),Context.MODE_PRIVATE)
        if(!prefs.getBoolean("$token-pending",false)) {
            val owner=intent.getStringExtra("owner") ?: return
            if(intent.action=="alpha.PIN_SHORTCUT") runCatching {
                com.alpha.assistant.tools.ToolStore(context).use { store ->
                    if(store.isDeleted(owner)) SurfaceEntry.removeToolPins(context,owner,true)
                }
            }
            return
        }
        val result = runCatching {
            if(intent.action == "alpha.PIN_WIDGET") {
                val data = org.json.JSONObject(checkNotNull(prefs.getString("$token-proposal",null)))
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
                val manager = AppWidgetManager.getInstance(context)
                check(id > 0 && manager.getAppWidgetInfo(id)?.provider == ComponentName(context,ToolWidgetProvider::class.java))
                val before = data.getJSONArray("before")
                check((0 until before.length()).none { before.getInt(it) == id }) { "Stale widget ID" }
                check(ToolWidgets.toolId(context,id) == null) { "Widget already configured" }
                com.alpha.assistant.tools.ToolStore(context).use { store ->
                    val tool = checkNotNull(store.tool(data.getString("tool")))
                    check(tool.saved && !tool.archived)
                    ToolSpec.parsePersisted(tool.spec).also { it.validateState(tool.state); it.validateUi(org.json.JSONObject(tool.ui),org.json.JSONObject(tool.state)) }
                    ToolWidgets.bind(context,id,tool.id,WidgetPreferences(data.getString("format"),data.getBoolean("details")),store)
                    ToolWidgets.update(context,id)
                }
            } else {
                check(intent.action == "alpha.PIN_SHORTCUT")
                val owner=org.json.JSONObject(checkNotNull(prefs.getString("$token-proposal",null))).getString("tool")
                com.alpha.assistant.tools.ToolStore(context).use { store ->
                    if(store.isDeleted(owner)) {
                        SurfaceEntry.removeToolPins(context,owner,true)
                        error("The mini-app was deleted.")
                    }
                }
            }
        }
        prefs.edit().remove("$token-pending").remove("$token-proposal").commit()
        SurfaceEntry.setStatus(context,token,context.getString(
            if (result.isSuccess) R.string.widget_pin_confirmed else R.string.widget_pin_mismatch))
    }
}
class AlphaTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val open = {
            val intent = entryIntent(this)
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pending)
            } else {
                @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(open) else open()
    }

    internal companion object {
        fun entryIntent(context: Context) = Intent(context,AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
