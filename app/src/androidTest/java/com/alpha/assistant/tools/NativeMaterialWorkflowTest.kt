package com.alpha.assistant.tools

import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Failure cases encoded before implementation: forged/mistyped/duplicate choices, invalid ranges and dates,
 * inactive pager actions, hidden modal dismissals, duplicate interaction IDs, unsupported keys/semantics,
 * invalid spans/swipe references, and widget disclosure. Rendered E2E requires no provider or native effects.
 */
@RunWith(AndroidJUnit4::class)
class NativeMaterialWorkflowTest {
    private fun spec(ui: JSONObject=JSONObject(NativeMaterialFixture.ui))=ToolSpec.parse(NativeMaterialFixture.definition(ui))
    private fun rejects(block: ()->Unit) {assertTrue(runCatching(block).isFailure)}
    @Test fun choicesRangesAndNavigationHaveTypedFiniteEvents() {
        val selected=spec();val values=JSONObject(selected.initialState)
        for((id,value) in listOf("mode" to "b","tags" to JSONArray("[\"a\",\"b\"]"),"range" to JSONArray("[2,8]"),"dates" to JSONArray("[\"2028-02-28\",\"2028-02-29\"]"),"page" to 1,"chip" to true))
            ToolUiCatalog.validateAction(selected.ui,id,value,values)
        for((id,value) in listOf("mode" to 1,"mode" to "forged","tags" to JSONArray("[\"a\",\"a\"]"),"tags" to JSONArray("[1]"),"range" to JSONArray("[9,2]"),"range" to JSONArray("[0,11]"),"dates" to JSONArray("[\"2027-02-29\",\"2027-03-01\"]"),"page" to 1.5,"page" to 2,"chip" to "true"))
            rejects {ToolUiCatalog.validateAction(selected.ui,id,value,values)}
        rejects {ToolUiCatalog.validateAction(selected.ui,"inactive",null,values)}
        values.put("page",1);ToolUiCatalog.validateAction(selected.ui,"inactive",null,values)
        rejects {ToolUiCatalog.validateAction(selected.ui,"dismiss",null,values)}
        values.put("dialog",true);ToolUiCatalog.validateAction(selected.ui,"dismiss",null,values)
        for(type in listOf("singleChoice","segmented","navigationBar","navigationRail","menu")) {
            val node=JSONObject("""{"type":"$type","text":"Mode","bind":"mode","action":"choose","options":[{"text":"A","value":"a"},{"text":"B","value":"b"}]}""")
            spec(node);rejects {spec(JSONObject(node.toString()).apply {getJSONArray("options").getJSONObject(1).put("value","a")})}
        }
    }
    @Test fun materialShellSearchStatusAndButtonVariantsRemainClosed() {
        for(type in listOf("appBar","bottomBar","dialog","bottomSheet")) {
            val node=JSONObject().put("type",type).put("text","Shell").put("children",JSONArray().put(JSONObject("{\"type\":\"button\",\"text\":\"Action\",\"action\":\"child\"}")))
            if(type in ToolUiCatalog.modalTypes)node.put("visibleBind","dialog").put("dismissAction","dismiss")
            spec(node);assertTrue(ToolUiCatalog.widgetNodes(node,JSONObject(spec().initialState)).none {it.optString("text")=="Action" && type in ToolUiCatalog.modalTypes})
        }
        for(variant in listOf("filter","input"))spec(JSONObject("""{"type":"chip","text":"Selected tag","bind":"pinned","action":"toggleTag","variant":"$variant","dismissAction":"removeTag"}"""))
        for(variant in listOf("assist","suggestion"))spec(JSONObject("""{"type":"actionChip","text":"Suggestion","action":"suggest","variant":"$variant"}"""))
        for(variant in listOf("filled","outlined","text","tonal","elevated","fab","extendedFab"))spec(JSONObject("""{"type":"button","text":"Action","action":"action","variant":"$variant","icon":"Plus"}"""))
        rejects {spec(JSONObject("""{"type":"button","text":"Action","action":"action","variant":"arbitrary"}"""))}
        spec(JSONObject("""{"type":"search","text":"Search","bind":"mode","action":"search","keyboard":"text","imeAction":"search","maxLines":1}"""))
        for(type in listOf("progress","progressRing"))spec(JSONObject("""{"type":"$type","text":"Loading","indeterminate":true}"""))
        rejects {spec(JSONObject("""{"type":"progress","text":"Loading","indeterminate":"true"}"""))}
    }
    @Test fun interactionsAndSemanticsKeepCurrentAuthority() {
        val node=JSONObject("""{"type":"text","text":"Interactive","interactions":{"click":"click","longClick":"hold","doubleClick":"twice","focus":"focus","keys":{"enter":"enter","escape":"escape"}},"semantics":{"label":"Interactive card","stateDescription":"Ready","heading":true,"liveRegion":"polite"}}""")
        val selected=spec(node); val values=JSONObject(selected.initialState)
        for(id in listOf("click","hold","twice","enter","escape"))ToolUiCatalog.validateAction(node,id,null,values)
        ToolUiCatalog.validateAction(node,"focus",true,values);rejects {ToolUiCatalog.validateAction(node,"focus",null,values)}
        for(flag in listOf("enabled","visible"))rejects {ToolUiCatalog.validateAction(JSONObject(node.toString()).put(flag,false),"hold",null,values)}
        rejects {spec(JSONObject(node.toString()).apply {getJSONObject("interactions").put("click","hold")})}
        rejects {spec(JSONObject(node.toString()).apply {getJSONObject("interactions").getJSONObject("keys").put("arbitrary","arbitrary")})}
        rejects {spec(JSONObject(node.toString()).apply {getJSONObject("semantics").put("execute","code")})}
        assertTrue(ToolUiCatalog.widgetLabel(ToolUiCatalog.widgetNodes(node,values).single(),values).contains("Open in Alphy"))
    }
    @Test fun collectionAffordancesAndMotionStayBounded() {
        val node=JSONObject("""{"type":"lazyGrid","text":"Records","bind":"rows","columns":2,"spanField":"span","groupBy":"group","rowActions":[{"action":"archive","text":"Archive"}],"swipeAction":"archive"}""")
        val selected=spec(node);val values=JSONObject(selected.initialState)
        ToolUiCatalog.validateAction(node,"archive","r1",values)
        rejects {spec(JSONObject(node.toString()).put("swipeAction","missing"))}
        rejects {selected.validateUi(node,JSONObject(values.toString()).apply {getJSONArray("rows").getJSONObject(0).put("span",5)})}
        val sticky=JSONObject("""{"type":"lazyList","text":"Groups","bind":"rows","groupBy":"group","stickyHeaders":true}""");spec(sticky)
        rejects {spec(JSONObject(sticky.toString()).apply {remove("groupBy")})}
        val transition=JSONObject("""{"type":"transition","text":"Panel","bind":"dialog","preset":"fadeSlide","durationMs":200,"children":[{"type":"text","text":"Panel content"}]}""");spec(transition)
        rejects {spec(JSONObject(transition.toString()).put("durationMs",5001))}
        rejects {spec(JSONObject(transition.toString()).put("preset","arbitrary"))}
    }
    /** Isolated boundary check: native slider fractions cross Float/Double precision before the action validator.
     * Cases: decimal endpoints, stepped interior values, negative bounds, collapsed Float bounds,
     * and cancellation near a large bound.
     */
    @Test fun nativeRangeFractionsPreserveDeclaredBoundsAndSteps() {
        val cases=listOf(
            "{\"min\":0.1,\"max\":0.3}" to listOf(0f to 0.1,1f to 0.3),
            "{\"min\":0,\"max\":0.3,\"step\":0.1}" to listOf(0f to 0.0,1f to 0.3,0.34f to 0.1,0.67f to 0.2),
            "{\"min\":-0.3,\"max\":-0.1,\"step\":0.1}" to listOf(0f to -0.3,1f to -0.1,0.51f to -0.2),
            "{\"min\":100000000,\"max\":100000001}" to listOf(0f to 100000000.0,1f to 100000001.0),
            "{\"min\":-9007199254740991,\"max\":0.3}" to listOf(1f to 0.3)
        )
        for((properties,samples) in cases) {
            val node=JSONObject(properties).put("type","rangeSlider").put("text","Decimal range").put("bind","range").put("action","range")
            val definition=JSONObject(NativeMaterialFixture.definition(node)).put("initialState",JSONObject(spec().initialState).put("range",JSONArray().put(node.getDouble("min")).put(node.getDouble("max"))))
            val selected=ToolSpec.parse(definition.toString());val state=JSONObject(selected.initialState)
            for((fraction,expected) in samples) {
                val actual=nativeSliderValue(node,fraction)
                assertEquals("Fraction $fraction for $properties",expected,actual,if(fraction == 0f || fraction == 1f) 0.0 else 1e-15)
                ToolUiCatalog.validateAction(node,"range",JSONArray().put(node.getDouble("min")).put(actual),state)
            }
        }
    }
    @Test fun generationSmokeSamplesNewEvents(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val predicates=listOf("input.event.actionId==='tags' && input.event.value.length===0","input.event.actionId==='range' && input.event.value[1]===10","input.event.actionId==='page' && input.event.value===1","input.event.actionId==='focus' && input.event.value===true","input.event.actionId==='confirm'","input.event.actionId==='inactive'")
        for(predicate in predicates) {
            val good=NativeMaterialFixture.definition(JSONObject(NativeMaterialFixture.ui))
            val bad=JSONObject(good).put("source","if(input.event.type==='action' && ($predicate))throw Error('Representative event reached');"+JSONObject(good).getString("source")).toString()
            var requests=0
            val provider=object:ChatProvider {override suspend fun respond(key:String,request:CloudRequest,session:CloudSession,emit:suspend(CloudEvent)->Unit) {requests++;emit(CloudEvent.Text(if(requests==1)bad else good))}}
            ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local Material fixture",null,emptyList(),CloudSession(),"material")
            assertEquals("Smoke omitted $predicate",2,requests)
        }
    }
    @Test fun nativeMaterialChoiceModalPagerAndReopenWorkflow() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val app=instrumentation.targetContext
        val name="native-material-${UUID.randomUUID()}";val shell=ShellStore(app,"$name.db");val toolDatabase="${shell.databaseName}-tools.db";val tools=ToolStore(app,toolDatabase);val keys=SecureKeyStore(app,name,name)
        val selected=spec(JSONObject(NativeMaterialFixture.ui).apply {
            val all=getJSONArray("children");put("children",JSONArray().apply {repeat(all.length()){val child=all.getJSONObject(it);if(child.getString("type") in setOf("segmented","button","dialog","pager"))put(child)}})
        });tools.saveTool(ToolRecord("material",1,1,selected.json,selected.initialState,selected.ui.toString(),"chat",true),true)
        shell.saveConversation(Conversation("chat","Native Material",1,1,messages=listOf(Message("artifact","assistant","Native controls are ready.",1,"complete",toolId="material",toolRevision=1))))
        shell.saveSetting("selected","chat");keys.save("offline-fixture-only").getOrThrow()
        lateinit var model:ShellViewModel
        val provider=object:ChatProvider {override suspend fun respond(key:String,request:CloudRequest,session:CloudSession,emit:suspend(CloudEvent)->Unit) {error("Offline workflow must not call a provider")}}
        CloudTestActivity.factory={ShellViewModel(it,provider,shell,keys).also {created->model=created}}
        val diagnostics=UiFailureDiagnostics("material-presentation").apply {state={"state="+tools.tool("material")?.state}}
        fun find(label:String,node:AccessibilityNodeInfo?=diagnostics.freshRoot()):AccessibilityNodeInfo? {if(node==null)return null;if(node.text?.toString()==label||node.contentDescription?.toString()==label)return node;repeat(node.childCount){find(label,node.getChild(it))?.let{return it}};return null}
        fun await(message:String,condition:()->Boolean) {val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(50)};diagnostics.capture("wait-timeout");fail(message)}
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
        fun click(label:String) {shown(label);var node:AccessibilityNodeInfo?=null;await("Missing $label"){node=find(label);while(node?.isClickable==false)node=node?.parent;node?.isVisibleToUser==true&&node?.isEnabled==true};assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
        fun capture(label:String) {val directory=File(app.getExternalFilesDir(null),"native-material-evidence").apply{mkdirs()};instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(directory,"$label.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
        try {ActivityScenario.launch(CloudTestActivity::class.java).use {scenario->
            await("Fixture did not load"){!model.loading&&!model.tools.busy};click("Open");click("Mode B")
            await("Choice did not persist"){JSONObject(tools.tool("material")!!.state).getString("mode")=="b"&&!model.tools.busy}
            click("Show dialog");await("Dialog missing"){find("Confirm choice")?.isVisibleToUser==true};capture("dialog");click("Confirm choice")
            await("Dialog did not close"){!JSONObject(tools.tool("material")!!.state).getBoolean("dialog")&&!model.tools.busy}
            click("Next: Pages");await("Pager did not change"){JSONObject(tools.tool("material")!!.state).getInt("page")==1&&!model.tools.busy};capture("page-2")
            scenario.recreate();shown("Second page");assertEquals("b",JSONObject(tools.tool("material")!!.state).getString("mode"));capture("reopened")
        };assertTrue(tools.effects("material").isEmpty());println("NATIVE_MATERIAL choice=PASS dialog=PASS pager=PASS reopen=PASS effects=0")}
        finally {CloudTestActivity.factory=null;tools.close();shell.close();keys.clear();app.deleteDatabase("$name.db");app.deleteDatabase(toolDatabase);app.deleteSharedPreferences(name);java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(name)}}
    }
}
internal object NativeMaterialFixture {
    val ui="""{"type":"column","children":[
      {"type":"segmented","text":"Mode","bind":"mode","action":"mode","options":[{"text":"Mode A","value":"a"},{"text":"Mode B","value":"b"}]},
      {"type":"multiChoice","text":"Tags","bind":"tags","action":"tags","options":[{"text":"Tag A","value":"a"},{"text":"Tag B","value":"b"}]},
      {"type":"chip","text":"Pinned","bind":"pinned","action":"chip"},
      {"type":"rangeSlider","text":"Range","bind":"range","action":"range","min":0,"max":10},
      {"type":"dateRangeInput","text":"Dates","bind":"dates","action":"dates"},
      {"type":"button","text":"Show dialog","action":"show"},
      {"type":"dialog","text":"Review choice","visibleBind":"dialog","dismissAction":"dismiss","children":[{"type":"button","text":"Confirm choice","action":"confirm"}]},
      {"type":"pager","text":"Pages","bind":"page","action":"page","viewportHeight":140,"children":[{"type":"text","text":"First page"},{"type":"button","text":"Second page","action":"inactive"}]},
      {"type":"text","text":"Focus target","interactions":{"focus":"focus","keys":{"enter":"enter"}}}
    ]}"""
    fun definition(ui:JSONObject)=JSONObject().put("formatVersion",3).put("title","Material fixture").put("description","Offline Material workflow").put("capabilities",JSONArray())
        .put("stateSchema",JSONObject("""{"mode":"string","tags":{"type":"array","maxItems":30,"items":"string"},"pinned":"boolean","range":{"type":"array","maxItems":2,"items":"number"},"dates":{"type":"array","maxItems":2,"items":"string"},"page":"number","dialog":"boolean","rows":{"type":"array","maxItems":30,"items":{"type":"object","fields":{"id":"string","group":"string","span":"number"}}}}"""))
        .put("initialState",JSONObject("""{"mode":"a","tags":["a"],"pinned":false,"range":[2,8],"dates":["2028-02-28","2028-02-29"],"page":0,"dialog":false,"rows":[{"id":"r1","group":"First","span":2}]}"""))
        .put("ui",ui).put("source","""if(input.event.type==='action'){var id=input.event.actionId;if(['mode','tags','range','dates','page'].includes(id))input.state[id]=input.event.value;if(id==='chip')input.state.pinned=input.event.value;if(id==='show')input.state.dialog=true;if(id==='confirm'||id==='dismiss')input.state.dialog=false;}return {state:input.state};""").toString()
}
