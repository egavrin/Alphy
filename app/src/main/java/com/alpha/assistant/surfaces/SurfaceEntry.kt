package com.alpha.assistant.surfaces

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.quicksettings.TileService
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import java.util.UUID

object SurfaceEntry {
    const val TOOLS_CHANGED = "com.alpha.assistant.TOOLS_CHANGED"
    fun chatIntent(context: Context) = Intent(context,MainActivity::class.java).apply {
        action = Intent.ACTION_ASSIST
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    private fun statusPreferences(context: Context) = context.getSharedPreferences("alpha-pin-requests",Context.MODE_PRIVATE)
    fun status(context: Context, token: String) = statusPreferences(context).getString(token,"Not requested") ?: "Not requested"
    fun setStatus(context: Context, token: String, status: String) { statusPreferences(context).edit().putString(token,status).apply() }
    private fun callback(context: Context, token: String, widget: Boolean): PendingIntent {
        // Android fills EXTRA_APPWIDGET_ID. Only this explicit, one-shot callback needs mutability.
        val flags = PendingIntent.FLAG_ONE_SHOT or if(widget) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context,0,
            Intent(context,PinResultReceiver::class.java).setAction(if(widget) "alpha.PIN_WIDGET" else "alpha.PIN_SHORTCUT")
                .setData(android.net.Uri.parse("alpha-pin://result/$token")),flags)
    }
    fun pin(context: Context, tool: ToolRecord, widget: Boolean, prefs: WidgetPreferences): Pair<String,String> {
        val token = UUID.randomUUID().toString()
        val spec = ToolSpec.available(tool.spec)
        if(!tool.saved || tool.archived || spec==null)return token to "This mini-app is unavailable for pinning. Its saved data remains intact."
        val supported = if(widget) AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
            else context.getSystemService(ShortcutManager::class.java).isRequestPinShortcutSupported
        if(!supported) return token to "This launcher does not support pin requests. Use its widget or shortcut picker."
        statusPreferences(context).edit().putBoolean("$token-pending",true).commit()
        setStatus(context,token,"Request sent. Android confirms only after addition. Cancellation has no callback.")
        return try {
            val sent = if(widget) {
                statusPreferences(context).edit().putString("$token-proposal",org.json.JSONObject().put("tool",tool.id).put("format",prefs.format).put("details",prefs.details)
                    .put("before",org.json.JSONArray(AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,ToolWidgetProvider::class.java)).toList())).toString()).commit()
                val preview = ToolWidgets.render(context,AppWidgetManager.INVALID_APPWIDGET_ID,tool,prefs,prefs.format == "wide",false)
                AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context,ToolWidgetProvider::class.java),
                    Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,preview) },callback(context,token,true))
            } else {
                val shortcut = ShortcutInfo.Builder(context,"tool-${tool.id}").setShortLabel(spec.title.take(40))
                    .setIcon(Icon.createWithResource(context,R.drawable.ic_alpha)).setIntent(ToolWidgets.openIntent(context,tool.id)).build()
                context.getSystemService(ShortcutManager::class.java).requestPinShortcut(shortcut,callback(context,token,false).intentSender)
            }
            if(!sent) {
                statusPreferences(context).edit().remove("$token-pending").remove("$token-proposal").apply()
                setStatus(context,token,"Launcher declined the request. Nothing is confirmed.")
            }
            token to status(context,token)
        } catch (_: Exception) {
            statusPreferences(context).edit().remove("$token-pending").remove("$token-proposal").apply()
            setStatus(context,token,"Launcher could not accept this request. Nothing is confirmed.")
            token to status(context,token)
        }
    }
}
class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        if(uri.scheme != "alpha-pin" || uri.authority != "result") return
        val token = uri.lastPathSegment ?: return
        val prefs = context.getSharedPreferences("alpha-pin-requests",Context.MODE_PRIVATE)
        if(!prefs.getBoolean("$token-pending",false)) return
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
                    ToolSpec.parse(tool.spec).also { it.validateState(tool.state); it.validateUi(org.json.JSONObject(tool.ui),org.json.JSONObject(tool.state)) }
                    ToolWidgets.bind(context,id,tool.id,WidgetPreferences(data.getString("format"),data.getBoolean("details")))
                    ToolWidgets.update(context,id,store)
                }
            } else check(intent.action == "alpha.PIN_SHORTCUT")
        }
        prefs.edit().remove("$token-pending").remove("$token-proposal").commit()
        SurfaceEntry.setStatus(context,token,if(result.isSuccess) "Android confirmed addition to the home screen."
            else "Android callback did not match this request. Nothing is confirmed. Open widget settings to choose a saved tool.")
    }
}
class AlphaTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val open = {
            val pending = PendingIntent.getActivity(this,0,SurfaceEntry.chatIntent(this),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if(android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pending)
            else {
                // The PendingIntent overload requires API 34; this branch runs only on older Android versions.
                @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                @Suppress("DEPRECATION")
                startActivityAndCollapse(SurfaceEntry.chatIntent(this))
            }
        }
        if(isLocked) unlockAndRun(open) else open()
    }
}
