package com.alpha.assistant.surfaces

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Failure cases: stale/repeated delivery, two engines, independent settings, missing tool,
 * invalid action, locked action, persisted reopen, compact/wide layout and host binding. */
@RunWith(AndroidJUnit4::class)
class SurfaceWorkflowTest {
    @Test fun widgetBindingActionConcurrencyAndReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val suffix = "surfaces-${UUID.randomUUID()}"
        val database = "$suffix.db"
        val context = object : ContextWrapper(base) {
            private fun databaseName(name: String) = if(name == "alpha-tools.db") database else name
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$suffix-$name", mode)
            override fun getDatabasePath(name: String) = base.getDatabasePath(databaseName(name))
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = base.openOrCreateDatabase(databaseName(name),mode,factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?) = base.openOrCreateDatabase(databaseName(name),mode,factory,handler)
        }
        val store = ToolStore(context, database)
        val other = ToolStore(context, database)
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { error("Surface must never call cloud") }
        }
        fun engine(s: ToolStore) = ToolEngine(s, ToolGenerationService(provider, SandboxClient(context)))
        val raw = """{"formatVersion":2,"title":"Surface fixture","description":"Test-owned local state","capabilities":[],"stateSchema":{"count":"number","done":"boolean"},"initialState":{"count":0,"done":false},"ui":{"type":"column","children":[{"type":"text","text":"Count","bind":"count"},{"type":"button","text":"Add","action":"add"},{"type":"toggle","text":"Done","bind":"done","action":"done"}]},"source":"if(input.event.type==='action'){if(input.event.actionId==='add') input.state.count++; if(input.event.actionId==='done') input.state.done=input.event.value;} return {state:input.state};"}"""
        val spec = ToolSpec.parse(raw)
        val initial = ToolRecord(suffix,1,1,raw,spec.initialState,spec.ui.toString(),null,true)
        val host = AppWidgetHost(base, 78123)
        var hostId = AppWidgetManager.INVALID_APPWIDGET_ID
        try {
            store.saveTool(initial,true)
            ToolWidgets.bind(context,101,initial.id,WidgetPreferences("compact",true))
            ToolWidgets.bind(context,102,initial.id,WidgetPreferences("wide",false))
            assertEquals("compact",ToolWidgets.preferences(context,101).format)
            assertEquals("wide",ToolWidgets.preferences(context,102).format)
            assertFalse(ToolWidgets.preferences(context,102).details)
            val action = ToolWidgets.actionIntent(context,101,initial,"done",true)
            assertTrue(ToolWidgets.perform(context,action,store,engine(store),locked = false))
            assertFalse(ToolWidgets.perform(context,action,store,engine(store),locked = false))
            assertTrue(JSONObject(store.tool(initial.id)!!.state).getBoolean("done"))
            val locked = ToolWidgets.actionIntent(context,101,store.tool(initial.id)!!,"add",null)
            assertFalse(ToolWidgets.perform(context,locked,store,engine(store),locked = true))
            val before = store.tool(initial.id)!!
            val results = withContext(Dispatchers.Default) {
                listOf(async { runCatching { engine(store).interact(before.id,before.revision,before.stateVersion,"add",null) } },
                    async { runCatching { engine(other).interact(before.id,before.revision,before.stateVersion,"add",null) } }).awaitAll()
            }
            assertEquals(1,results.count { it.isSuccess })
            assertEquals(1,JSONObject(store.tool(initial.id)!!.state).getInt("count"))
            ToolStore(context,database).use { reopened -> assertEquals(store.tool(initial.id),reopened.tool(initial.id)) }
            val invalid = ToolWidgets.actionIntent(context,101,store.tool(initial.id)!!,"missing",null)
            assertFalse(ToolWidgets.perform(context,invalid,store,engine(store),locked = false))
            ToolWidgets.bind(context,103,"deleted",WidgetPreferences())
            assertFalse(ToolWidgets.perform(context,ToolWidgets.actionIntent(context,103,initial,"add",null),store,engine(store),locked = false))
            instrumentation.runOnMainSync {
                listOf(false,true).forEach { wide ->
                    val views = ToolWidgets.render(context,101,store.tool(initial.id),WidgetPreferences(),wide,false)
                    val view = views.apply(base,null)
                    assertNotNull(view.findViewById<android.view.View>(com.alpha.assistant.R.id.widget_title))
                }
                ToolWidgets.render(context,103,null,WidgetPreferences(),false,false).apply(base,null)
                ToolWidgets.render(context,101,store.tool(initial.id),WidgetPreferences(),true,true).apply(base,null)
            }
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try {
                hostId = host.allocateAppWidgetId()
                val manager = AppWidgetManager.getInstance(base)
                val bound = manager.bindAppWidgetIdIfAllowed(hostId,ComponentName(base,ToolWidgetProvider::class.java),Bundle())
                assertTrue("Shell BIND_APPWIDGET permission must permit test host",bound)
                manager.updateAppWidget(hostId,ToolWidgets.render(context,101,store.tool(initial.id),WidgetPreferences(),true,false))
                assertEquals(ComponentName(base,ToolWidgetProvider::class.java),manager.getAppWidgetInfo(hostId).provider)
                val requestToken = UUID.randomUUID().toString()
                val requests = context.getSharedPreferences("alpha-pin-requests",Context.MODE_PRIVATE)
                requests.edit().putBoolean("$requestToken-pending",true).putString("$requestToken-proposal",
                    JSONObject().put("tool",initial.id).put("format","wide").put("details",false).put("before",org.json.JSONArray()).toString()).commit()
                val callback = android.content.Intent(context,PinResultReceiver::class.java).setAction("alpha.PIN_WIDGET")
                    .setData(android.net.Uri.parse("alpha-pin://result/$requestToken")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,hostId)
                instrumentation.runOnMainSync { PinResultReceiver().onReceive(context,callback) }
                assertEquals(initial.id,ToolWidgets.toolId(context,hostId))
                assertEquals(WidgetPreferences("wide",false),ToolWidgets.preferences(context,hostId))
                assertEquals("Android confirmed addition to the home screen.",SurfaceEntry.status(context,requestToken))
                instrumentation.runOnMainSync { PinResultReceiver().onReceive(context,callback) }
                assertFalse(requests.getBoolean("$requestToken-pending",false))
                val wrongToken = UUID.randomUUID().toString()
                requests.edit().putBoolean("$wrongToken-pending",true).putString("$wrongToken-proposal",
                    JSONObject().put("tool",initial.id).put("format","wide").put("details",false).put("before",org.json.JSONArray()).toString()).commit()
                val invalidCallback = android.content.Intent(callback).setData(android.net.Uri.parse("alpha-pin://result/$wrongToken"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1)
                instrumentation.runOnMainSync { PinResultReceiver().onReceive(context,invalidCallback) }
                assertTrue(SurfaceEntry.status(context,wrongToken).startsWith("Android callback did not match"))
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        } finally {
            if(hostId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(hostId)
            store.close(); other.close(); base.deleteDatabase(database)
            base.deleteSharedPreferences("$suffix-alpha-widgets")
            base.deleteSharedPreferences("$suffix-alpha-pin-requests")
        }
    }
}
