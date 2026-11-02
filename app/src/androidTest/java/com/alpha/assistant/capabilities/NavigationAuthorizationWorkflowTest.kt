package com.alpha.assistant.capabilities

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.tools.*
import com.alpha.assistant.ui.ShellViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Repair failure cases, before production: unrelated/input controls cannot authorize navigation; semantic
 * Mark A cannot substitute B or coordinates; route cannot substitute destination/mode/ref; valid clicks need
 * no second dialog; widget/preview actions cannot execute navigation. UI automation drives actual ToolContent,
 * ToolController, and isolated JavaScript. Offline and private fixture databases only, without a provider/key.
 */
class NavigationAuthorizationWorkflowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val automation get()=instrumentation.uiAutomation
    private fun stop(id: String) = JSONObject().put("version",1).put("stopId",id).put("label","Stop $id")
        .put("latE6",31230000+(id.first().code-65)*10000).put("lonE6",121473000+(id.first().code-65)*20000).put("coordinateSystem","wgs84")
    private fun navigation(capability: String="maps.markReached",args: JSONObject=stop("A")) = JSONObject()
        .put("type","navigation").put("action","run").put("capability",capability).put("args",args)
    private fun waitUntil(test: ()->Boolean) {
        val end=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<end) {if(test())return;SystemClock.sleep(30)}
        fail("Navigation authorization workflow timed out")
    }
    private fun find(label: String,node: AccessibilityNodeInfo?=automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node
        repeat(node.childCount) {find(label,node.getChild(it))?.let {found -> return found}}
        return null
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo?=null
        waitUntil {node=find(label); if(node?.isVisibleToUser != true) node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); node?.isVisibleToUser==true}
        while(node != null && node?.isClickable != true)node=node?.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    private fun fixture(node: JSONObject, output: JSONObject?, block: (ShellViewModel,ToolStore,ToolRecord)->Unit) {
        val name="navigation-authorization-${UUID.randomUUID()}"
        val shell=ShellStore(context,"$name.db"); val store=ToolStore(context,"$name.db-tools.db")
        val keys=SecureKeyStore(context,name,name)
        val capability=node.optString("capability","maps.markReached")
        val source="if(input.event.type==='action')return {state:input.state,effects:[{id:'navigation',capability:'$capability',args:${(output ?: stop("A"))}}]}; return {state:input.state};"
        val spec=ToolSpec.parse(JSONObject().put("formatVersion",2).put("title","Authorization fixture").put("description","")
            .put("capabilities",JSONArray().put("maps.markReached").put("maps.route")).put("capabilityResults",true)
            .put("stateSchema",JSONObject("{\"name\":\"string\"}")).put("initialState",JSONObject("{\"name\":\"\"}"))
            .put("ui",node).put("source",source).toString())
        val tool=ToolRecord("navigation",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
        store.saveTool(tool,true); store.grant(tool.id,"maps.markReached",true);store.grant(tool.id,"maps.route",true)
        lateinit var model: ShellViewModel
        val offline=object: ChatProvider {override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend(CloudEvent)->Unit)=error("Offline UI fixture")}
        CloudTestActivity.factory={ShellViewModel(it,offline,shell,keys).also {created -> model=created}}
        try {
            ActivityScenario.launch(CloudTestActivity::class.java).use {
                waitUntil {!model.loading && !model.tools.busy}
                instrumentation.runOnMainSync {model.openSurface(tool.id)}
                waitUntil {!model.tools.busy}
                block(model,store,tool)
                val dir=File(context.getExternalFilesDir(null),"navigation-evidence").apply {mkdirs()}
                automation.takeScreenshot()?.let {image -> File(dir,"${node.getString("type")}-${output?.getString("stopId") ?: "valid"}.png").outputStream().use {out -> image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)};image.recycle()}
            }
        } finally {CloudTestActivity.factory=null;SystemClock.sleep(100);store.close();shell.close();context.deleteDatabase("$name.db");context.deleteDatabase("$name.db-tools.db");context.deleteSharedPreferences(name)}
    }
    @Test fun genericDetailsCannotMarkB() = fixture(JSONObject("{\"type\":\"button\",\"text\":\"Show details\",\"action\":\"run\"}"),stop("B")) { model,store,tool ->
        click("Show details");waitUntil {!model.tools.busy && store.effects(tool.id).isNotEmpty()}
        assertEquals("pending",store.effects(tool.id).single().phase)
        assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM maps_reached",null).use {it.moveToFirst();it.getInt(0)})
    }
    @Test fun inputCannotMarkB() = fixture(JSONObject("{\"type\":\"input\",\"text\":\"Name\",\"bind\":\"name\",\"action\":\"run\"}"),stop("B")) { model,store,tool ->
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.isEditable)return node
            repeat(node.childCount) {editable(node.getChild(it))?.let {found -> return found}}
            return null
        }
        var field: AccessibilityNodeInfo?=null
        waitUntil {field=editable(automation.rootInActiveWindow);field!=null}
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Changed")
        }))
        click("Apply: Name");waitUntil {!model.tools.busy && store.effects(tool.id).isNotEmpty()}
        assertEquals("pending",store.effects(tool.id).single().phase)
        assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM maps_reached",null).use {it.moveToFirst();it.getInt(0)})
    }
    @Test fun semanticMarkACannotSubstituteB() = fixture(navigation(),stop("B")) { model,store,tool ->
        click("Mark reached: Stop A [A] · 31.230000,121.473000 wgs84")
        waitUntil {!model.tools.busy && store.effects(tool.id).isNotEmpty()}
        assertEquals("pending",store.effects(tool.id).single().phase)
        assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM maps_reached",null).use {it.moveToFirst();it.getInt(0)})
    }
    @Test fun legitimateSemanticClickMarksAWithoutAnotherDialog() = fixture(navigation(),null) { model,store,tool ->
        click("Mark reached: Stop A [A] · 31.230000,121.473000 wgs84")
        waitUntil {!model.tools.busy && store.effects(tool.id).firstOrNull()?.result?.status=="succeeded"}
        assertEquals("A",store.effects(tool.id).single().result.data.getJSONObject("stop").getString("id"))
        assertNull(find("Подтвердить системное действие"))
    }
    @Test fun semanticRouteCCannotSubstituteB() = fixture(navigation("maps.route",stop("C").put("mode","walking").put("reachedRef","a".repeat(64))),stop("B").put("mode","walking").put("reachedRef","a".repeat(64))) { model,store,tool ->
        click("Route to: Stop C [C] · 31.250000,121.513000 wgs84 · walking")
        waitUntil {!model.tools.busy && store.effects(tool.id).isNotEmpty()}
        assertEquals("pending",store.effects(tool.id).single().phase)
    }
    @Test fun selectedDataAndCapabilityMustMatchExactly() {
        val mark=navigation()
        val binding=NavigationInteraction.from(ToolRecord("native",1,1,"","{}",mark.toString(),null,true),"run",null)!!
        fun effect(capability: String,args: JSONObject)=CapabilityEffect("native:1:2:run","native",1,2,CapabilityRequest("run",capability,args),CapabilityResult("awaitingUser","Pending"),"pending")
        assertTrue(binding.matches(effect("maps.markReached",stop("A"))))
        for(args in listOf(stop("B"),stop("A").put("latE6",30000000),stop("A").put("label","Other")))assertFalse(binding.matches(effect("maps.markReached",args)))
        val ref="a".repeat(64)
        val route=navigation("maps.route",stop("C").put("mode","walking").put("reachedRef",ref))
        val routeBinding=NavigationInteraction.from(ToolRecord("native",1,1,"","{}",route.toString(),null,true),"run",null)!!
        assertTrue(routeBinding.matches(effect("maps.route",stop("C").put("mode","walking").put("reachedRef",ref))))
        for(args in listOf(stop("B").put("mode","walking").put("reachedRef",ref),stop("C").put("mode","driving").put("reachedRef",ref),stop("C").put("mode","walking").put("reachedRef","b".repeat(64))))assertFalse(routeBinding.matches(effect("maps.route",args)))
        assertFalse(routeBinding.matches(effect("maps.markReached",stop("C"))))
        for(node in listOf(JSONObject("{\"type\":\"button\",\"text\":\"Mark A\",\"action\":\"run\"}"),JSONObject("{\"type\":\"input\",\"text\":\"Name\",\"bind\":\"name\",\"action\":\"run\"}")))assertNull(NavigationInteraction.from(ToolRecord("native",1,1,"","{\"name\":\"\"}",node.toString(),null,true),"run",if(node.getString("type")=="input") "B" else null))
        assertFalse(com.alpha.assistant.automations.AutomationDefinition.actionIds.any {it in NavigationContract.ids})
        assertEquals(ToolUiCatalog.Projection.SUMMARY,ToolUiCatalog.component("navigation").projection)
        assertTrue(runCatching {CapabilityCatalog.parseEffects(JSONArray().put(effect("maps.markReached",stop("A")).request.json()),setOf("maps.markReached"),"preview")}.isFailure)
    }
}
