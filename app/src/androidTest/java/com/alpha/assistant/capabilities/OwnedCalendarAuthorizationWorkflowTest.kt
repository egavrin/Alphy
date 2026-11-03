package com.alpha.assistant.capabilities

import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import android.os.SystemClock
import android.provider.CalendarContract
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

/** Actual native clicks, isolated JavaScript, native grants and ordinary Calendar Provider writes. */
class OwnedCalendarAuthorizationWorkflowTest {
    private val diagnostics = UiFailureDiagnostics("calendar")
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val automation get()=instrumentation.uiAutomation
    private fun waitUntil(check: ()->Boolean) {
        val end=SystemClock.uptimeMillis()+20000
        while(SystemClock.uptimeMillis()<end) {if(check())return;SystemClock.sleep(30)}
        diagnostics.capture("wait-timeout")
        fail("Owned calendar UI workflow timed out")
    }
    private fun find(label: String,node: AccessibilityNodeInfo?=diagnostics.freshRoot()): AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node
        repeat(node.childCount) {find(label,node.getChild(it))?.let {found->return found}}
        return null
    }
    private fun shown(label: String): AccessibilityNodeInfo {
        diagnostics.target(label)
        var node: AccessibilityNodeInfo?=null
        var direction=AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var refused=false
        waitUntil {
            node=find(label)
            if(node?.isVisibleToUser==true)return@waitUntil true
            if(node!=null)node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            else find("Mini-app content")?.let { viewport ->
                if(viewport.actionList.none {it.id==direction})direction=if(direction==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                // A refused scroll is not a failure: a running scroll animation can move the range after the read.
                if(viewport.actionList.any {it.id==direction} && !viewport.performAction(direction) && !refused) {refused=true;diagnostics.capture("scroll-refused",viewport)}
            }
            automation.waitForIdle(100,2000)
            false
        }
        return checkNotNull(node)
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo?=shown(label)
        while(node!=null && node?.isClickable!=true)node=node?.parent
        assertTrue(diagnostics.click(label,node))
    }
    private fun workflow(semantic: Boolean,substitute: Boolean,grant: Boolean,bulk: Boolean=false,lateCreate: Boolean=false) {
        val name="owned-calendar-ui-${UUID.randomUUID()}"
        val shell=ShellStore(context,"$name.db");val store=ToolStore(context,"$name.db-tools.db")
        val keys=SecureKeyStore(context,name,name)
        try {SyntheticOwnedCalendar(context).use {calendar ->
            val calendarRef=OwnedCalendarLedger(store).select("calendar-ui",calendar.native())
            val args=JSONObject().put("version",1).put("calendarRef",calendarRef).put("eventKey","explicit-visit")
                .put("title","Synthetic explicit visit").put("start",1893456000000L).put("end",1893459600000L)
                .put("timeZone","UTC").put("deviceTimeZone",java.time.ZoneId.systemDefault().id).put("location","Synthetic room").put("reminderMinutes",15)
            val capability=if(bulk)"calendar.deleteAllOwned" else "calendar.createOwned"
            val selectedArgs=if(bulk)JSONObject().put("version",1) else args
            val node=if(semantic)JSONObject().put("type","calendar").put("action","run").put("capability",capability).put("args",selectedArgs)
                else JSONObject("{\"type\":\"button\",\"text\":\"Show details\",\"action\":\"run\"}")
            val output=JSONObject(selectedArgs.toString()).apply {if(substitute)put("title","Substituted event")}
            val source="if(input.event.type==='action')return {state:input.state,effects:[{id:'calendar',capability:'$capability',args:$output}]};return {state:input.state};"
            val spec=ToolSpec.parse(JSONObject().put("formatVersion",3).put("title","Owned calendar UI fixture").put("description","")
                .put("capabilities",JSONArray().put("calendar.createOwned").put("calendar.deleteAllOwned")).put("stateSchema",JSONObject("{\"ready\":\"boolean\"}")).put("initialState",JSONObject("{\"ready\":true}"))
                .put("ui",node).put("source",source).toString())
            var tool=ToolRecord("calendar-ui",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            store.saveTool(tool,true);if(grant)store.grant(tool.id,capability,true)
            if(bulk)kotlinx.coroutines.runBlocking {
                store.grant(tool.id,"calendar.createOwned",true)
                val native=OwnedCalendarCapabilities(context,store,unlocked={true})
                repeat(if(lateCreate)1 else 5) {index ->
                    val old=store.tool(tool.id)!!
                    val createArgs=JSONObject(args.toString()).put("eventKey","bulk-$index").put("title","Synthetic bulk visit $index")
                    store.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false,effects=listOf(CapabilityRequest("seed$index","calendar.createOwned",createArgs)))
                    if(!lateCreate) {
                        val result=CapabilityBroker(context,store).executeOwned(store.effects(tool.id).last().key,true) {native.execute(it,{true}){}}
                        assertEquals(result.message,"succeeded",result.status)
                    }
                }
                tool=store.tool(tool.id)!!
            }
            lateinit var model: ShellViewModel
            val offline=object:ChatProvider {override suspend fun respond(key:String,request:CloudRequest,session:CloudSession,emit:suspend(CloudEvent)->Unit)=error("Offline UI fixture")}
            CloudTestActivity.factory={ShellViewModel(it,offline,shell,keys).also {created->model=created}}
            ActivityScenario.launch(CloudTestActivity::class.java).use {
                waitUntil {!model.loading && !model.tools.busy}
                instrumentation.runOnMainSync {model.openSurface(tool.id)}
                waitUntil {!model.tools.busy}
                diagnostics.state = {
                    val snapshot = runCatching { OwnedCalendarLedger(store).snapshot(store.tool(tool.id)!!) }
                    "controllerBusy=${model.tools.busy} ledgerSnapshotPresent=${snapshot.isSuccess} " +
                        "ledgerSnapshotCount=${snapshot.getOrNull()?.entries?.size} " +
                        "ledgerSnapshotException=${snapshot.exceptionOrNull()?.javaClass?.name ?: "none"} " +
                        "uiSnapshotException=unknown uiSnapshotPresence=unknown"
                }
                if(lateCreate) {
                    diagnostics.target("Delete all 0 owned events")
                    shown("Delete all 0 owned events")
                    click("Просмотреть действие");click("Выполнить это действие")
                    waitUntil {!model.tools.busy && store.effects(tool.id).last().result.status=="succeeded"}
                    assertEquals(tool.stateVersion,store.tool(tool.id)!!.stateVersion)
                    diagnostics.target("Delete all 1 owned events")
                    shown("Delete all 1 owned events")
                }
                val label=if(bulk)"Delete all ${if(lateCreate)1 else 5} owned events" else if(semantic)OwnedCalendarContract.label(OwnedCalendarContract.request(node)) else "Show details"
                click(label)
                waitUntil {!model.tools.busy && store.effects(tool.id).lastOrNull()?.request?.capability==capability}
                val effect=store.effects(tool.id).last()
                val count=context.contentResolver.query(CalendarContract.Events.CONTENT_URI,arrayOf(CalendarContract.Events._ID),
                    "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DELETED}=0",arrayOf(calendar.id.toString()),null)!!.use {cursor->cursor.count}
                if(semantic && !substitute && grant) {
                    assertEquals(effect.result.message,"succeeded",effect.result.status);assertEquals(if(bulk)0 else 1,count)
                    if(bulk)assertEquals(if(lateCreate)1 else 5,effect.result.data.getInt("deleted"))
                    assertNull(find("Подтвердить системное действие"))
                } else {assertEquals("pending",effect.phase);assertEquals(0,count)}
                val dir=File(context.getExternalFilesDir(null),"owned-calendar-evidence").apply {mkdirs()}
                val stem="ui-$semantic-$substitute-$grant-$bulk-$lateCreate"
                automation.takeScreenshot()?.let {image->File(dir,"$stem.png").outputStream().use {out->image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)};image.recycle()}
                File(dir,"$stem.json").writeText(JSONObject().put("input",JSONObject().put("semantic",semantic).put("substitute",substitute).put("grant",grant).put("bulk",bulk).put("lateCreate",lateCreate))
                    .put("expectedRows",if(semantic && !substitute && grant && !bulk)1 else 0).put("observedRows",count).put("effectStatus",effect.result.status).put("effectPhase",effect.phase).toString(2))
            }
        }} finally {CloudTestActivity.factory=null;SystemClock.sleep(100);store.close();shell.close();context.deleteDatabase("$name.db");context.deleteDatabase("$name.db-tools.db");context.deleteSharedPreferences(name)}
    }
    @Test fun approvedPendingCreateRefreshesTheDisplayedDeleteAllSnapshot()=workflow(semantic=true,substitute=false,grant=true,bulk=true,lateCreate=true)
    @Test fun oneSemanticClickDeletesAllFiveOwnedEventsWithoutAnotherDialog()=workflow(semantic=true,substitute=false,grant=true,bulk=true)
    @Test fun genericButtonCannotCreate()=workflow(semantic=false,substitute=false,grant=true)
    @Test fun semanticSelectionCannotSubstituteAnotherEvent()=workflow(semantic=true,substitute=true,grant=true)
    @Test fun semanticClickCreatesExactlyOneEventWithoutAnotherDialog()=workflow(semantic=true,substitute=false,grant=true)
    @Test fun semanticClickNeedsExplicitScopedGrant()=workflow(semantic=true,substitute=false,grant=false)
}
