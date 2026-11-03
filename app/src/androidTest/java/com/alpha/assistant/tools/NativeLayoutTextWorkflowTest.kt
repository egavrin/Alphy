package com.alpha.assistant.tools

import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.ui.ShellViewModel
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Failure cases: invalid dimensions/constraints, misplaced weight/alignment, unsafe links, invalid span ranges,
 * duplicate IDs, stale scroll/selection keys, hidden/disabled template controls, unbounded work, and widget disclosure.
 * Boundary checks isolate rejection before effects. The rendered workflow covers selection, scroll, and reopen.
 */
@RunWith(AndroidJUnit4::class)
class NativeLayoutTextWorkflowTest {
    private fun spec(ui: JSONObject=JSONObject(NativeLayoutTextFixture.ui))=ToolSpec.parse(NativeLayoutTextFixture.definition(ui))
    private fun rejects(block: () -> Unit) {assertTrue(runCatching(block).isFailure)}
    @Test fun layoutDimensionsAndParentSlotsAreFinite() {
        spec()
        val child=JSONObject("""{"type":"text","text":"Layout","layout":{"width":120,"minHeight":48,"maxHeight":160,"aspectRatio":2,"align":"center"}}""")
        spec(JSONObject().put("type","box").put("children",JSONArray().put(child)))
        for(layout in listOf("{\"width\":-1}","{\"width\":1025}","{\"height\":1.5}","{\"aspectRatio\":0}","{\"minWidth\":100,\"maxWidth\":90}","{\"width\":120,\"maxWidth\":100}","{\"insets\":\"arbitrary\"}","{\"modifier\":\"exec\"}","{\"weight\":1}"))
            rejects {spec(JSONObject(child.toString()).put("layout",JSONObject(layout)))}
        val row=JSONObject("""{"type":"row","wrap":false,"style":{"arrangement":"between","crossAlign":"center"},"children":[{"type":"text","text":"Weighted","layout":{"weight":1}},{"type":"button","text":"Save","action":"save"}]}""")
        spec(row)
        rejects {spec(JSONObject(row.toString()).put("wrap",true))}
        rejects {spec(JSONObject(row.toString()).put("layout",JSONObject("{\"weight\":1}")))}
    }
    @Test fun readableTextSpansAndLinkActionsUseCurrentAuthority() {
        val ui=JSONObject("""{"type":"text","text":"Read guide","maxLines":3,"overflow":"ellipsis","softWrap":true,"lineHeight":24,"selectable":true,"spans":[{"start":0,"end":4,"emphasis":"bold"},{"start":5,"end":10,"action":"guide","url":"https://example.com/guide"}]}""")
        val selected=spec(ui)
        ToolUiCatalog.validateAction(ui,"guide","https://example.com/guide",JSONObject(selected.initialState))
        assertNotNull(ToolUiCatalog.action(ui,"guide"))
        for(value in listOf(null,"https://example.com/forged",7)) rejects {ToolUiCatalog.validateAction(ui,"guide",value,JSONObject(selected.initialState))}
        for(url in listOf("javascript:alert(1)","http://example.com","https://localhost/a","https://user:pass@example.com/a","https://127.0.0.1/a"))
            rejects {spec(JSONObject(ui.toString()).apply {getJSONArray("spans").getJSONObject(1).put("url",url)})}
        for(span in listOf("{\"start\":-1,\"end\":1}","{\"start\":1,\"end\":11}","{\"start\":2,\"end\":2}","{\"start\":0,\"end\":1,\"action\":\"guide\"}"))
            rejects {spec(JSONObject(ui.toString()).put("spans",JSONArray().put(JSONObject(span))))}
        for(flag in listOf("visible","enabled")) rejects {ToolUiCatalog.validateAction(JSONObject(ui.toString()).put(flag,false),"guide","https://example.com/guide",JSONObject(selected.initialState))}
        rejects {spec(JSONObject(ui.toString()).put("bind","selected"))}
        rejects {spec(JSONObject(ui.toString()).put("maxLines",0))}
        val duplicate=JSONObject().put("type","column").put("children",JSONArray().put(ui).put(JSONObject("""{"type":"button","text":"Duplicate","action":"guide"}""")))
        rejects {spec(duplicate)}
        assertTrue(ToolUiCatalog.widgetLabel(ToolUiCatalog.widgetNodes(ui).single(),JSONObject(selected.initialState)).contains("Open in Alphy"))
    }
    @Test fun lazyCollectionsRetainTypedCurrentKeysAndBoundEveryExpandedRow() {
        val ui=JSONObject("""{"type":"lazyGrid","text":"Cards","bind":"rows","columns":3,"minCellWidth":144,"viewportHeight":320,"selectedKeyBind":"selected","scrollToKeyBind":"target","rowTemplate":{"type":"button","text":"Choose card","action":"choose","enabledBind":"available"}}""")
        val selected=spec(ui); val values=JSONObject(selected.initialState)
        val payload=JSONObject("{\"key\":\"r0\",\"value\":null}")
        ToolUiCatalog.validateAction(ui,"choose",payload,values)
        rejects {ToolUiCatalog.validateAction(ui,"choose",JSONObject("{\"key\":0,\"value\":null}"),values)}
        values.getJSONArray("rows").getJSONObject(0).put("available",false)
        rejects {ToolUiCatalog.validateAction(ui,"choose",payload,values)}
        values.put("target","missing"); rejects {selected.validateUi(ui,values)}
        values.put("target","").put("selected","missing"); rejects {selected.validateUi(ui,values)}
        values.put("selected","").put("rows",JSONArray()); selected.validateUi(ui,values)
        val rows=JSONArray().apply {repeat(512) {put(JSONObject().put("id","r$it").put("title","Row $it").put("available",true))}}
        values.put("rows",rows)
        val expensive=JSONObject(ui.toString()).put("rowTemplate",JSONObject("""{"type":"column","children":[{"type":"text","text":"One"},{"type":"text","text":"Two"},{"type":"text","text":"Three"},{"type":"text","text":"Four"}]}"""))
        rejects {selected.validateUi(expensive,values)}
        val projection=ToolUiCatalog.widgetNodes(ui).single()
        assertEquals(ToolUiCatalog.Projection.SUMMARY,ToolUiCatalog.component(projection.getString("type")).projection)
        assertFalse(ToolUiCatalog.widgetLabel(projection,JSONObject(selected.initialState)).contains("Row 0"))
    }
    @Test fun themeRejectsUnsafePaletteAndUnboundedTypeScale() {
        val ui=JSONObject("""{"type":"theme","preset":"clean","mode":"dark","typeScale":1.1,"cornerRadius":16,"palette":{"primary":"#FFFFFF","onPrimary":"#000000"},"children":[{"type":"text","text":"Theme"}]}""")
        spec(ui)
        for(palette in listOf("{\"primary\":\"#FFFFFF\"}","{\"primary\":\"#FFFFFF\",\"onPrimary\":\"#EEEEEE\"}","{\"surface\":\"javascript:red\",\"onSurface\":\"#000000\"}"))
            rejects {spec(JSONObject(ui.toString()).put("palette",JSONObject(palette)))}
        rejects {spec(JSONObject(ui.toString()).put("typeScale",3))}
        rejects {spec(JSONObject(ui.toString()).put("cornerRadius",100))}
    }
    @Test fun generationSmokeExercisesLazySelectionsTemplatesAndInlineLinks(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cases=listOf(
            """{"type":"lazyList","text":"Rows","bind":"rows","action":"select"}""" to "input.event.actionId==='select' && input.event.value==='r23'",
            """{"type":"lazyGrid","text":"Cards","bind":"rows","columns":2,"rowTemplate":{"type":"button","text":"Choose","action":"choose"}}""" to "input.event.actionId==='choose' && input.event.value.key==='r23'",
            """{"type":"text","text":"Guide","spans":[{"start":0,"end":5,"action":"guide","url":"https://example.com/guide"}]}""" to "input.event.actionId==='guide' && input.event.value==='https://example.com/guide'"
        )
        for((ui,predicate) in cases) {
            val good=JSONObject(NativeLayoutTextFixture.definition(JSONObject(ui))).put("source","return {state:input.state};").toString()
            val bad=JSONObject(good).put("source","if(input.event.type==='action' && ($predicate))throw Error('Representative event reached');return {state:input.state};").toString()
            var requests=0
            val provider=object: ChatProvider {
                override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                    requests++;emit(CloudEvent.Text(if(requests == 1) bad else good))
                }
            }
            ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local fixture",null,emptyList(),CloudSession(),"layout-text")
            assertEquals("Smoke omitted $predicate",2,requests)
        }
    }
    @Test fun nativeCollectionSelectionScrollAndReopenAtLargeFont() {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val app=instrumentation.targetContext
        val name="layout-text-${UUID.randomUUID()}"; val shell=ShellStore(app,"$name.db"); val tools=ToolStore(app,"$name.db-tools.db")
        val keys=SecureKeyStore(app,name,name); val selected=spec()
        tools.saveTool(ToolRecord("layout",1,1,selected.json,selected.initialState,selected.ui.toString(),"chat",true),true)
        shell.saveConversation(Conversation("chat","Native layout",1,1,messages=listOf(Message("artifact","assistant","Native layout is ready.",1,"complete",toolId="layout",toolRevision=1))))
        shell.saveSetting("selected","chat"); keys.save("offline-fixture-only").getOrThrow()
        lateinit var model: ShellViewModel
        val provider=object: ChatProvider {override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {error("Offline workflow must not call a provider")}}
        CloudTestActivity.factory={ShellViewModel(it,provider,shell,keys).also {created -> model=created}}
        val diagnostics=UiFailureDiagnostics("layout-presentation").apply {state={"state="+tools.tool("layout")?.state}}
        fun find(label: String,node: AccessibilityNodeInfo?=diagnostics.freshRoot()): AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node
            repeat(node.childCount) {find(label,node.getChild(it))?.let {return it}}
            return null
        }
        fun await(message: String,condition: () -> Boolean) {val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end) {if(condition())return;SystemClock.sleep(50)};diagnostics.capture("wait-timeout");fail(message)}
        fun shown(label: String): AccessibilityNodeInfo {
            diagnostics.target(label)
            var node: AccessibilityNodeInfo?=null
            var direction=AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            var refused=false
            await("Missing visible $label") {
                node=find(label)
                if(node?.isVisibleToUser==true)return@await true
                if(node!=null)node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                else find("Mini-app content")?.let {viewport ->
                    if(viewport.actionList.none {it.id==direction})direction=if(direction==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    // A refused scroll is not a failure: a running scroll animation can move the range after the read.
                    if(viewport.actionList.any {it.id==direction} && !viewport.performAction(direction) && !refused) {refused=true;diagnostics.capture("scroll-refused",viewport)}
                }
                instrumentation.uiAutomation.waitForIdle(100,2000)
                false
            }
            return checkNotNull(node)
        }
        fun click(label: String) {shown(label);var node: AccessibilityNodeInfo?=null;await("Missing action $label") {node=find(label);while(node?.isClickable==false)node=node?.parent;node?.isVisibleToUser==true && node?.isEnabled==true};assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
        fun capture(label: String) {val directory=File(app.getExternalFilesDir(null),"native-layout-text-evidence").apply {mkdirs()};instrumentation.uiAutomation.takeScreenshot()?.let {bitmap -> File(directory,"$label.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
        try {
            ActivityScenario.launch(CloudTestActivity::class.java).use {scenario ->
                await("Fixture did not load") {!model.loading && !model.tools.busy};click("Open")
                await("Readable instructions missing") {find("Read guide")?.isVisibleToUser==true};capture("compact")
                var disabled=find("Disabled action")
                while(disabled?.isEnabled==true && disabled?.isClickable==false) disabled=disabled?.parent
                assertFalse("Disabled controls must expose their disabled state",disabled?.isEnabled ?: true)
                click("Show final row");shown("Row 23")
                click("Row 23");await("Selection did not persist") {JSONObject(tools.tool("layout")!!.state).getString("selected")=="r23" && !model.tools.busy}
                capture("selected")
                scenario.recreate()
                shown("Row 23");capture("reopened")
                scenario.onActivity {it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
                shown("Row 23");capture("wide")
                scenario.onActivity {activity -> activity.setContentView(androidx.compose.ui.platform.ComposeView(activity).apply {setContent {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f,1.5f)) {
                        androidx.compose.material3.MaterialTheme {
                        val scroll=androidx.compose.foundation.rememberScrollState()
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().verticalScroll(scroll)) {ToolContent(selected.ui.toString(),tools.tool("layout")!!.state,true) {_,_ -> }}
                    }
                    }
                }})}
                shown("Row 23");capture("font-1_5")
            }
            ToolStore(app,"$name.db-tools.db").use {reopened -> assertEquals("r23",JSONObject(reopened.tool("layout")!!.state).getString("selected"))}
            assertTrue(tools.effects("layout").isEmpty())
            println("NATIVE_LAYOUT_TEXT selection=PASS scrollTarget=PASS reopen=PASS wide=PASS disabled=PASS fontScale1.5=PASS effects=0")
        } finally {
            CloudTestActivity.factory=null;tools.close();shell.close();keys.clear();app.deleteDatabase("$name.db");app.deleteDatabase("$name.db-tools.db");app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(name)}
        }
    }
}

internal object NativeLayoutTextFixture {
    val ui="""{"type":"theme","preset":"clean","typeScale":1,"cornerRadius":12,"children":[
        {"type":"box","layout":{"minHeight":48},"children":[{"type":"text","text":"Read guide","maxLines":3,"selectable":true,"spans":[{"start":0,"end":4,"emphasis":"bold"}]},{"type":"badge","text":"Native","layout":{"align":"end"}}]},
        {"type":"row","wrap":false,"style":{"crossAlign":"center"},"children":[{"type":"button","text":"Show final row","action":"last","layout":{"weight":1}},{"type":"button","text":"Disabled action","action":"disabled","enabled":false}]},
        {"type":"lazyList","text":"Native records","bind":"rows","viewportHeight":220,"selectedKeyBind":"selected","scrollToKeyBind":"target","columns":[{"text":"Title","field":"title"}],"action":"choose"}
    ]}"""
    fun definition(ui: JSONObject): String=JSONObject().put("formatVersion",3).put("title","Layout and text").put("description","Offline native fixture").put("capabilities",JSONArray())
        .put("stateSchema",JSONObject("""{"selected":"string","target":"string","rows":{"type":"array","maxItems":512,"items":{"type":"object","fields":{"id":"string","title":"string","available":"boolean"}}}}"""))
        .put("initialState",JSONObject().put("selected","").put("target","").put("rows",JSONArray().apply {repeat(24) {put(JSONObject().put("id","r$it").put("title","Row $it").put("available",true))}}))
        .put("ui",ui).put("source","""if(input.event.type==='action'){if(input.event.actionId==='last')input.state.target='r23';if(input.event.actionId==='choose')input.state.selected=input.event.value;}return {state:input.state};""").toString()
}
