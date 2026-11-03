package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeComposeContractTest {
    private val fields=JSONObject("""{"tick":"number","show":"boolean","rows":{"type":"array","maxItems":512,"items":{"type":"object","fields":{"id":"string","title":"string","available":"boolean","shown":"boolean","amount":"number"}}}}""")
    private fun state()=JSONObject("""{"tick":0,"show":true,"rows":[{"id":"a","title":"First","available":true,"shown":true,"amount":2}]}""")
    private fun template()=JSONObject("""{"type":"list","text":"Rows","bind":"rows","rowTemplate":{"type":"column","visibleBind":"shown","enabledBind":"available","children":[{"type":"text","text":"Title","bind":"title"},{"type":"numberInput","text":"Amount","bind":"amount","min":0,"max":10,"action":"amount"},{"type":"button","text":"Choose","action":"choose"}]}}""")
    private fun validate(ui: JSONObject, values: JSONObject=state())=ToolUiCatalog.validate(ui,fields,values)
    private fun rejects(block: () -> Unit) {assertTrue(runCatching(block).isFailure)}
    private fun payload(key: String="a",value: Any?=null)=JSONObject().put("key",key).put("value",value ?: JSONObject.NULL)

    @Test fun templatesValidateRelativeControlsAndCurrentRowAvailability() {
        val ui=validate(template()); val values=state()
        ToolUiCatalog.validateAction(ui,"choose",payload(),values)
        ToolUiCatalog.validateAction(ui,"amount",payload(value=4),values)
        for(value in listOf(payload("forged"),payload(value=1),JSONObject().put("key","a"),payload().put("extra",true)))
            rejects {ToolUiCatalog.validateAction(ui,"choose",value,values)}
        rejects {ToolUiCatalog.validateAction(ui,"amount",payload(value="4"),values)}
        rejects {ToolUiCatalog.validateAction(ui,"amount",payload(value=11),values)}
        for(flag in listOf("available","shown")) {
            val changed=state(); changed.getJSONArray("rows").getJSONObject(0).put(flag,false)
            rejects {ToolUiCatalog.validateAction(ui,"choose",payload(),changed)}
        }
        rejects {ToolUiCatalog.validateAction(ui,"choose",payload(),state().put("rows",JSONArray()))}
        rejects {ToolUiCatalog.validateAction(JSONObject().put("type","column").put("visibleBind","show").put("children",JSONArray().put(ui)),"choose",payload(),state().put("show",false))}
    }
    @Test fun emptyTemplatesStillValidateSchemaAndUniqueActions() {
        val values=state().put("rows",JSONArray())
        validate(template(),values)
        val missing=template().apply {getJSONObject("rowTemplate").getJSONArray("children").getJSONObject(0).put("bind","missing")}
        rejects {validate(missing,values)}
        val duplicate=template().apply {getJSONObject("rowTemplate").getJSONArray("children").getJSONObject(2).put("action","amount")}
        rejects {validate(duplicate,values)}
        val nested=template().apply {put("rowTemplate",JSONObject("""{"type":"list","text":"Nested","bind":"rows"}"""))}
        rejects {validate(nested,values)}
        val privileged=template().put("rowTemplate",JSONObject("""{"type":"navigation","action":"route","capability":"maps.route","args":{}}"""))
        rejects {validate(privileged,values)}
    }
    @Test fun expandedTemplateWorkAndTypedVisibilityAreBounded() {
        val ui=template(); val values=state(); val row=values.getJSONArray("rows").getJSONObject(0)
        values.put("rows",JSONArray().apply {repeat(512) {put(JSONObject(row.toString()).put("id","r$it"))}})
        rejects {validate(ui,values)}
        rejects {validate(JSONObject("""{"type":"text","text":"Hidden","visibleBind":"tick"}"""))}
        rejects {validate(JSONObject("""{"type":"text","text":"Hidden","visibleBind":"missing"}"""))}
    }
    @Test fun semanticFamiliesHaveClosedContracts() {
        for(type in listOf("form","listGroup","facts","timeline","banner","emptyState"))
            validate(JSONObject().put("type",type).put("text","Section").put("children",JSONArray().put(JSONObject("""{"type":"text","text":"Content"}"""))))
        validate(JSONObject("""{"type":"fact","text":"Count","bind":"tick"}"""))
        validate(JSONObject("""{"type":"timelineItem","text":"First","detail":"Description","time":"09:00"}"""))
        val grouped=template().put("type","groupedList").put("groupBy","title")
        validate(grouped)
        rejects {validate(grouped.put("groupBy","missing"))}
    }
    @Test fun motionAndPoseContractsRejectUnknownOrUnboundedValues() {
        val text=JSONObject("""{"type":"text","text":"Result"}""")
        val motion=JSONObject().put("type","motion").put("preset","pop").put("trigger","tick").put("children",JSONArray().put(text))
        validate(motion)
        rejects {validate(JSONObject(motion.toString()).put("preset","unknown"))}
        rejects {validate(JSONObject(motion.toString()).put("trigger","show"))}
        rejects {validate(JSONObject(motion.toString()).put("children",JSONArray().put(motion)))}
        val transform=JSONObject().put("type","transform").put("pose",JSONArray("[0,30,0,0,0,20,1,1]")).put("durationMs",400).put("size",120).put("children",JSONArray().put(text))
        validate(transform)
        rejects {validate(JSONObject(transform.toString()).put("pose",JSONArray("[0,0,0,0,0,0,-1,1]")))}
        rejects {validate(JSONObject(transform.toString()).put("durationMs",10001))}
        val stage=JSONObject().put("type","stage").put("height",240).put("perspective",900).put("children",JSONArray().put(transform))
        validate(stage)
        rejects {validate(JSONObject(stage.toString()).put("children",JSONArray().put(text)))}
        transform.put("children",JSONArray().put(JSONObject("""{"type":"button","text":"Unsafe projected target","action":"tap"}""")))
        rejects {validate(stage)}
    }
    @Test fun sharedProjectionComposesParentsAndDepth() {
        val parent=NativeLayerMath.pose(doubleArrayOf(0.0,90.0,0.0,10.0,0.0,0.0,1.0,1.0))
        val child=NativeLayerMath.pose(doubleArrayOf(0.0,0.0,0.0,0.0,0.0,60.0,1.0,1.0))
        val matrix=NativeLayerMath.multiply(parent,child)
        val p=NativeLayerMath.project(matrix,900.0,0.0,0.0)
        assertEquals(70.0,p[0],0.00001); assertEquals(0.0,p[2],0.00001)
        val near=NativeLayerMath.project(NativeLayerMath.pose(doubleArrayOf(0.0,0.0,0.0,0.0,0.0,450.0,1.0,1.0)),900.0,10.0,0.0)
        assertEquals(20.0,near[0],0.00001)
    }
    @Test fun mixedWidgetProjectionRetainsNativeSummaryWithoutRows() {
        val ui=JSONObject().put("type","column").put("children",JSONArray().put(JSONObject("""{"type":"text","text":"Score","bind":"tick"}""")).put(template()))
        validate(ui)
        val nodes=ToolUiCatalog.widgetNodes(ui)
        assertTrue(nodes.any {it.optString("bind")=="tick"})
        assertFalse(nodes.any {it.optString("text")=="First"})
    }
}
