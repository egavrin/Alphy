package com.alpha.assistant.surfaces

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import com.alpha.assistant.R
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
    @Test fun hostProjectionFitsAndResizes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Water test","description":"Local water tracker","capabilities":[],"stateSchema":{"count":"number","note":"string"},"initialState":{"count":4,"note":""},"ui":{"type":"column","children":[{"type":"text","text":"Water test"},{"type":"text","text":"Count","bind":"count"},{"type":"button","text":"Add","action":"add"},{"type":"progress","text":"Goal","bind":"count","max":8},{"type":"input","text":"Note","bind":"note","action":"note"}]},"source":"return {state:input.state};"}""")
        val tool = ToolRecord("host-projection-fixture",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
        listOf(140 to 140, 180 to 180, 276 to 319, 420 to 360).forEach { (width,height) ->
            val views = ToolGlanceWidget.preview(context,tool,WidgetPreferences(),DpSize(width.dp,height.dp))
            instrumentation.runOnMainSync {
                val host = android.appwidget.AppWidgetHostView(context)
                host.setPadding(0,0,0,0)
                host.updateAppWidget(views)
                val density = context.resources.displayMetrics.density
                fun exact(dp: Int) = View.MeasureSpec.makeMeasureSpec((dp * density).toInt(), View.MeasureSpec.EXACTLY)
                host.measure(exact(width),exact(height))
                host.layout(0,0,host.measuredWidth,host.measuredHeight)
                val rendered = host.descendants().toList()
                val labels = rendered.filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue("$width x $height: $labels",labels.contains("Count: 4"))
                if(height >= 180) assertTrue("Primary action must remain visible: $labels",labels.contains("Add"))
                if(height >= 319) {
                    assertTrue(labels.contains("Goal: 4"))
                    assertTrue(rendered.filterIsInstance<ProgressBar>().any { it.progress * 2 == it.max })
                    assertTrue(labels.contains(context.getString(R.string.widget_open_content, "Note")))
                }
                assertFalse("Preview must be inert",rendered.any { it.isClickable && it.isEnabled })
            }
        }
    }

    @Test fun focalRecipeLimitsWidgetRenderingAndActionDelivery(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val fixture = "focal-widget-${UUID.randomUUID()}"
        val database = "$fixture.db"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$fixture-$name",mode)
        }
        val raw = JSONObject("""{
            "formatVersion":3,"title":"Focal widget","description":"Local widget fixture","capabilities":[],
            "stateSchema":{"count":"number","privateCount":"number","note":"string","show":"boolean","active":"boolean"},
            "initialState":{"count":4,"privateCount":99,"note":"","show":true,"active":true},
            "ui":{"type":"column","visibleBind":"show","enabledBind":"active","children":[
                {"type":"column","children":[
                    {"type":"text","text":"Private count","bind":"privateCount"},
                    {"type":"button","text":"Private action","action":"outside"}]},
                {"type":"column","children":[
                    {"type":"text","text":"Focal count","bind":"count"},
                    {"type":"button","text":"Add","action":"add"},
                    {"type":"button","text":"Hidden action","action":"hidden","visible":false},
                    {"type":"button","text":"Disabled action","action":"disabled","enabled":false},
                    {"type":"input","text":"Note","bind":"note","action":"note"}]}]},
            "source":"if(input.event.type==='action') input.state.count++; return {state:input.state};"
        }""")
        val plain = ToolSpec.parse(raw.toString())
        val recipe = raw.put("nativePresentation",JSONObject()
            .put("profile","focus").put("preset","clean").put("focalPosition",1)
            .put("compactSummary","focal").put("primaryActionId","add"))
        val spec = ToolSpec.parsePersisted(recipe.toString())
        val tool = ToolRecord(fixture,1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
        val prefs = WidgetPreferences("wide",true)
        suspend fun labels(record: ToolRecord): List<String> {
            val views = ToolGlanceWidget.preview(context,record,prefs,DpSize(360.dp,400.dp))
            var result = emptyList<String>()
            instrumentation.runOnMainSync {
                result = views.apply(base,null).descendants().filterIsInstance<TextView>().map { it.text.toString() }.toList()
            }
            return result
        }
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                error("Widget actions are local")
            }
        }
        val widgetId = 1700000001
        try {
            ToolStore(context,database).use { store ->
                store.saveTool(tool,true)
                ToolWidgets.bind(context,widgetId,tool.id,prefs,store)
                val engine = ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
                suspend fun deliver(action: String, record: ToolRecord = tool, locked: Boolean = false): Boolean =
                    ToolWidgets.perform(context,ToolWidgets.actionIntent(context,widgetId,record,action,null),store,engine,locked)
                val visible = labels(tool)
                assertTrue(visible.containsAll(listOf("Focal count: 4","Add","Disabled action",
                    context.getString(R.string.widget_open_content,"Note"))))
                assertFalse(visible.any { it.startsWith("Private") || it == "Hidden action" })
                val open = ToolWidgets.openIntent(context,tool.id)
                assertEquals(tool.id,open.getStringExtra("tool_id"))
                assertEquals(tool.id,open.data?.lastPathSegment)
                listOf("outside","hidden","disabled","note").forEach { assertFalse(it,deliver(it)) }
                assertFalse(deliver("add",tool.copy(id = "another-app")))
                assertFalse(deliver("add",tool.copy(revision = 0)))
                assertFalse(deliver("add",locked = true))
                assertTrue(deliver("add"))
                assertFalse(deliver("add"))
                val current = checkNotNull(store.tool(tool.id))
                assertTrue(labels(current).contains("Focal count: 5"))
                val noRecipe = current.copy(spec = plain.json)
                assertTrue(labels(noRecipe).contains("Private count: 99"))
                val disabled = current.copy(stateVersion = current.stateVersion + 1,
                    state = JSONObject(current.state).put("active",false).toString())
                store.saveTool(disabled,false)
                assertFalse(deliver("add",disabled))
                val hidden = disabled.copy(stateVersion = disabled.stateVersion + 1,
                    state = JSONObject(disabled.state).put("show",false).toString())
                store.saveTool(hidden,false)
                assertFalse(deliver("add",hidden))
                assertFalse(labels(hidden).any { it.startsWith("Focal count") || it == "Add" })
                val staleUi = JSONObject(current.ui)
                val slots = staleUi.getJSONArray("children")
                val wrongFocal = slots.getJSONObject(0)
                slots.put(0,slots.getJSONObject(1)).put(1,wrongFocal)
                val stale = current.copy(stateVersion = hidden.stateVersion + 1,ui = staleUi.toString())
                assertEquals(1,store.writableDatabase.update("tools",ContentValues().apply {
                    put("ui",stale.ui)
                    put("state",stale.state)
                    put("state_version",stale.stateVersion)
                },"id=?",arrayOf(stale.id)))
                assertFalse(deliver("add",stale))
                assertTrue(labels(stale).contains(context.getString(R.string.widget_unavailable)))
            }
        } finally {
            base.deleteDatabase(database)
            base.deleteSharedPreferences("$fixture-alpha-widgets")
        }
    }

    @Test fun quickSettingsTileOpensAssistantWithoutCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = AlphaTileService.entryIntent(context)
        assertEquals(ComponentName(context,AssistantActivity::class.java),intent.component)
        assertFalse(intent.hasExtra("assist_invocation"))
        assertNull(intent.action)
    }

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
        val raw = """{"formatVersion":3,"title":"Surface fixture","description":"Test-owned local state","capabilities":[],"stateSchema":{"count":"number","done":"boolean"},"initialState":{"count":0,"done":false},"ui":{"type":"column","children":[{"type":"text","text":"Count","bind":"count"},{"type":"button","text":"Add","action":"add"},{"type":"toggle","text":"Done","bind":"done","action":"done"}]},"source":"if(input.event.type==='action'){if(input.event.actionId==='add') input.state.count++; if(input.event.actionId==='done') input.state.done=input.event.value;} return {state:input.state};"}"""
        val mixed=JSONObject(raw).put("surfaces",org.json.JSONArray("[{\"id\":\"main\",\"width\":320,\"height\":240}]")).toString()
        val spec = ToolSpec.parse(mixed)
        val initial = ToolRecord(suffix,1,1,mixed,spec.initialState,spec.ui.toString(),null,true)
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
            val staleRevision = ToolWidgets.actionIntent(context,101,store.tool(initial.id)!!.copy(revision = 0),"add",null)
            assertFalse(ToolWidgets.perform(context,staleRevision,store,engine(store),locked = false))
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
            val effectSpec = ToolSpec.parse(JSONObject(raw)
                .put("capabilities", org.json.JSONArray().put("vibration.play"))
                .put("source", "input.state.count++;return {state:input.state,effects:[{id:'buzz',capability:'vibration.play',args:{milliseconds:1}}]};")
                .toString())
            val effectTool = initial.copy(id = "$suffix-effect", spec = effectSpec.json, ui = effectSpec.ui.toString())
            store.saveTool(effectTool, true)
            ToolWidgets.bind(context,104,effectTool.id,WidgetPreferences())
            val widgetEffect = ToolWidgets.actionIntent(context,104,effectTool,"add",null)
            assertFalse(ToolWidgets.perform(context,widgetEffect,store,engine(store),locked = false))
            assertEquals(effectTool,store.tool(effectTool.id))
            assertTrue(store.effects(effectTool.id).isEmpty())
            ToolWidgets.bind(context,103,"deleted",WidgetPreferences())
            assertFalse(ToolWidgets.perform(context,ToolWidgets.actionIntent(context,103,initial,"add",null),store,engine(store),locked = false))
            val deletedAction = ToolWidgets.actionIntent(context,102,store.tool(initial.id)!!,"add",null)
            ToolWidgets.removeDeletedBindings(context,initial.id,setOf(102),store)
            assertTrue(ToolWidgets.deleted(context,102))
            assertFalse(ToolWidgets.perform(context,deletedAction,store,engine(store),locked = false))
            assertEquals(setOf(101),ToolWidgets.bindings(context,initial.id))
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try {
                hostId = host.allocateAppWidgetId()
                val manager = AppWidgetManager.getInstance(base)
                val bound = manager.bindAppWidgetIdIfAllowed(hostId,ComponentName(base,ToolWidgetProvider::class.java),Bundle())
                assertTrue("Shell BIND_APPWIDGET permission must permit test host",bound)
                assertEquals(ComponentName(base,ToolWidgetProvider::class.java),manager.getAppWidgetInfo(hostId).provider)
                val requests = context.getSharedPreferences("alpha-pin-requests",Context.MODE_PRIVATE)
                fun queuePin(previous: List<Int> = emptyList()): String {
                    val token = UUID.randomUUID().toString()
                    requests.edit().putBoolean("$token-pending",true).putString("$token-proposal",
                        JSONObject().put("tool",initial.id).put("format","wide").put("details",false)
                            .put("before",org.json.JSONArray(previous)).toString()).commit()
                    SurfaceEntry.setStatus(context,token,context.getString(R.string.widget_pin_pending))
                    return token
                }
                val cancelledToken = queuePin()
                val requestToken = queuePin()
                assertNull(ToolWidgets.toolId(context,hostId))
                val callback = android.content.Intent(context,PinResultReceiver::class.java).setAction("alpha.PIN_WIDGET")
                    .setData(android.net.Uri.parse("alpha-pin://result/$requestToken")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,hostId)
                instrumentation.runOnMainSync { PinResultReceiver().onReceive(context,callback) }
                assertEquals(initial.id,ToolWidgets.toolId(context,hostId))
                assertEquals(WidgetPreferences("wide",false),ToolWidgets.preferences(context,hostId))
                assertEquals(context.getString(R.string.widget_pin_confirmed),SurfaceEntry.status(context,requestToken))
                assertEquals("A request without a callback remains unconfirmed",
                    context.getString(R.string.widget_pin_pending),SurfaceEntry.status(context,cancelledToken))
                instrumentation.runOnMainSync { PinResultReceiver().onReceive(context,callback) }
                assertFalse(requests.getBoolean("$requestToken-pending",false))
                val wrongToken = queuePin(listOf(hostId))
                val invalidCallback = android.content.Intent(callback).setData(android.net.Uri.parse("alpha-pin://result/$wrongToken"))
                instrumentation.runOnMainSync { PinResultReceiver().onReceive(context,invalidCallback) }
                assertEquals(context.getString(R.string.widget_pin_mismatch),SurfaceEntry.status(context,wrongToken))
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(hostId)
                val widget = ToolGlanceWidget()
                val secondHostId = host.allocateAppWidgetId()
                try {
                    assertTrue(manager.bindAppWidgetIdIfAllowed(secondHostId,ComponentName(base,ToolWidgetProvider::class.java),Bundle()))
                    ToolWidgets.bind(context,secondHostId,initial.id,WidgetPreferences("compact",true),store)
                    val secondGlanceId = GlanceAppWidgetManager(context).getGlanceIdBy(secondHostId)
                    val compact = DpSize(180.dp,180.dp)
                    val wide = DpSize(320.dp,320.dp)
                    suspend fun assertBothCounts(expected: Int) {
                        val compactViews = widget.compose(context,secondGlanceId,size = compact)
                        val wideViews = widget.compose(context,glanceId,size = wide)
                        instrumentation.runOnMainSync {
                            listOf(compactViews,wideViews).forEach { remote ->
                                val labels = remote.apply(base,null).descendants().filterIsInstance<TextView>().map { it.text.toString() }.toList()
                                assertTrue("Expected count $expected: $labels",labels.contains("Count: $expected"))
                            }
                        }
                    }
                    assertBothCounts(1)
                    val latest = store.tool(initial.id)!!
                    assertTrue(ToolWidgets.perform(context,ToolWidgets.actionIntent(context,secondHostId,latest,"add",null),store,engine(store),locked = false))
                    assertBothCounts(2)
                } finally {
                    ToolWidgets.remove(context,secondHostId)
                    host.deleteAppWidgetId(secondHostId)
                }
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        } finally {
            if(hostId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(hostId)
            store.close(); other.close(); base.deleteDatabase(database)
            base.deleteSharedPreferences("$suffix-alpha-widgets")
            base.deleteSharedPreferences("$suffix-alpha-pin-requests")
        }
    }
}

internal fun View.descendants(): Sequence<View> = sequence {
    yield(this@descendants)
    if (this@descendants is ViewGroup) {
        repeat(childCount) { yieldAll(getChildAt(it).descendants()) }
    }
}
