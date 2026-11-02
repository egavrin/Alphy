package com.alpha.assistant.tools

import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.ui.ShellViewModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import org.json.JSONArray
import org.json.JSONObject

/** Black-box failure cases: executable chat cards, lost app context/draft, scrolling composer,
 * active candidate controls, accidental apply, and lost apply/discard controls with long content.
 * The root runs this fixture and retains screenshots with its tested revision and command.
 */
class MiniAppPresentationWorkflowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private fun waitUntil(message: String="Mini-app presentation workflow timed out",condition: () -> Boolean) {
        val end=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<end) {if(condition()) return; SystemClock.sleep(40)}
        val name="failure-${SystemClock.uptimeMillis()}"
        screenshot(name)
        val text=StringBuilder()
        fun dump(node: AccessibilityNodeInfo?,depth: Int=0) {
            if(node==null || depth>30)return
            text.append(" ".repeat(depth)).append(node.className).append(" text=").append(node.text).append(" description=").append(node.contentDescription)
                .append(" visible=").append(node.isVisibleToUser).append(" enabled=").append(node.isEnabled).append('\n')
            repeat(node.childCount) {dump(node.getChild(it),depth+1)}
        }
        dump(automation.rootInActiveWindow)
        File(instrumentation.targetContext.getExternalFilesDir(null),"mini-app-evidence/$name-ui.txt").writeText(text.toString())
        fail(message)
    }
    private fun find(label: String, node: AccessibilityNodeInfo?=automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if(node == null) return null
        if(node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) {find(label,node.getChild(it))?.let {return it}}
        return null
    }
    private fun shown(label: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo?=null
        waitUntil("Missing visible label: $label") {
            node=find(label)
            if(node?.isVisibleToUser != true) node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            node?.isVisibleToUser == true
        }
        return node!!
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo?=shown(label)
        while(node != null && !node.isClickable) node=node.parent
        assertTrue("Click $label",node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        automation.waitForIdle(100,3000)
    }
    private fun edit(label: String, text: String) {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isEditable) return node
            repeat(node.childCount) {node.getChild(it)?.let {editable(it)?.let {found -> return found}}}
            return null
        }
        var node: AccessibilityNodeInfo?=shown(label)
        while(node != null && editable(node) == null) node=node.parent
        val field=editable(checkNotNull(node))!!
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)
        }))
    }
    private fun screenshot(name: String) {
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"mini-app-evidence").apply {mkdirs()}
        automation.takeScreenshot()?.let {bitmap -> File(directory,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}; bitmap.recycle()}
    }
    private fun fixture(candidate: Boolean=false, provider: ChatProvider?=null, specJson: String=ItineraryFixture.spec(), block: (ShellViewModel,ToolStore,ActivityScenario<CloudTestActivity>) -> Unit) {
        val app=instrumentation.targetContext
        val name="presentation-${UUID.randomUUID()}"
        val shell=ShellStore(app,"$name.db"); val tools=ToolStore(app,"$name.db-tools.db")
        val keys=SecureKeyStore(app,name,name)
        val spec=ToolSpec.parse(specJson)
        val tool=ToolRecord("lake",1,1,spec.json,spec.initialState,spec.ui.toString(),"chat",true)
        tools.saveTool(tool,true)
        spec.capabilities.forEach {tools.grant(tool.id,it,true)}
        if(candidate) {
            val next=ToolSpec.parse(ItineraryFixture.spec("Revised lake day"))
            tools.saveDraft(ToolDraft("candidate",tool.id,1,1,"Make the day shorter",next.json,next.initialState,next.ui.toString(),"chat"))
        }
        shell.saveConversation(Conversation("chat","Plan discussion",1,1,messages=listOf(Message("artifact","assistant","Your day plan is ready.",1,"complete",toolId=tool.id,toolRevision=1))))
        shell.saveSetting("selected","chat")
        keys.save("presentation-fixture-only").getOrThrow()
        lateinit var model: ShellViewModel
        val responder=provider ?: object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                check(request.jsonObject) {"Unexpected chat execution"}
                emit(CloudEvent.Text(ItineraryFixture.spec("Revised lake day")))
            }
        }
        CloudTestActivity.factory={ShellViewModel(it,responder,shell,keys).also {created -> model=created}}
        try {
            ActivityScenario.launch(CloudTestActivity::class.java).use {scenario ->
                waitUntil {!model.loading && !model.tools.busy}
                block(model,tools,scenario)
            }
        } finally {
            CloudTestActivity.factory=null; SystemClock.sleep(150); tools.close(); shell.close()
            app.deleteDatabase("$name.db"); app.deleteDatabase("$name.db-tools.db")
            keys.clear(); app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null); deleteEntry(name)}
        }
    }
    /** Offline native graphics acceptance. No provider call or system capability is required. */
    @Test fun graphicsTouchCheckpointAndLifecyclePreserveSavedData() = fixture(specJson=ArkanoidFixture.spec()) {model,store,scenario ->
        fun surface(node: AccessibilityNodeInfo?=automation.rootInActiveWindow): AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.contentDescription?.toString()?.startsWith("Arkanoid.")==true)return node
            repeat(node.childCount) {surface(node.getChild(it))?.let {return it}}
            return null
        }
        fun paddle(value: Int) {waitUntil {surface()?.contentDescription?.contains("Paddle $value ")==true}}
        click("Open")
        shown("Playing")
        click("Pause")
        shown("Paused")
        click("Right");paddle(156)
        val before=store.tool("lake")!!;assertEquals(1,before.stateVersion)
        click("Save score");waitUntil {store.tool("lake")!!.stateVersion==2};paddle(156)
        click("Left");paddle(128)
        val node=checkNotNull(surface());node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        automation.waitForIdle(100,3000)
        val bounds=Rect().also {checkNotNull(surface()).getBoundsInScreen(it)}
        val now=SystemClock.uptimeMillis()
        val x=bounds.left+bounds.width()*0.25f;val y=bounds.exactCenterY()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0)
            try {assertTrue(automation.injectInputEvent(event,true))}finally {event.recycle()}
        }
        paddle(48);screenshot("graphics-touch-saved")
        val saved=store.tool("lake")!!
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Thread.sleep(150)
        kotlinx.coroutines.runBlocking {
            val probe=com.alpha.assistant.sandbox.GraphicsSession(instrumentation.targetContext)
            try {assertEquals("Background must release the retained slot","OK",probe.open("return ()=>({commands:[]});","{}").status)}finally {probe.close()}
        }
        assertEquals(saved,store.tool("lake"))
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        shown("Playing")
        paddle(128)
        click("Restart")
        paddle(128)
        assertEquals(saved,store.tool("lake"))
        screenshot("graphics-resumed")
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitUntil {surface()==null}
        assertEquals(saved,store.tool("lake"))
        println("GRAPHICS_NATIVE touch=PASS checkpointContinuity=PASS backgroundRelease=PASS back=PASS stateVersion=${saved.stateVersion}")
    }
    @Test fun graphicsLockReleasesSessionWithoutSavingFrames() = fixture(specJson=ArkanoidFixture.spec()) {model,store,scenario ->
        click("Open");shown("Playing")
        val saved=store.tool("lake")!!
        val power=instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java)
        try {
            automation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            waitUntil {!power.isInteractive};Thread.sleep(200)
            kotlinx.coroutines.runBlocking {
                val probe=com.alpha.assistant.sandbox.GraphicsSession(instrumentation.targetContext)
                try {assertEquals("Lock must release the retained slot","OK",probe.open("return ()=>({commands:[]});","{}").status)}finally {probe.close()}
            }
            assertEquals(saved,store.tool("lake"))
        } finally {
            automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            automation.executeShellCommand("wm dismiss-keyguard").close()
        }
        waitUntil {power.isInteractive};shown("Playing")
        assertEquals(saved,store.tool("lake"));screenshot("graphics-lock-resume")
        println("GRAPHICS_LOCK retainedSlotReleased=PASS statePreserved=PASS")
    }
    @Test fun graphicsForegroundSixtySecondEvidence() = fixture(specJson=ArkanoidFixture.spec()) {model,store,scenario ->
        val metrics=com.alpha.assistant.sandbox.SurfaceFrameMetrics
        val previous=metrics.snapshot()?.generation
        click("Open")
        waitUntil("Missing active native frame session") {
            metrics.snapshot()?.let {it.generation!=previous && it.status=="RUNNING" && it.acceptedFrames>0}==true
        }
        val start=checkNotNull(metrics.snapshot())
        Thread.sleep(60000)
        val end=checkNotNull(metrics.snapshot())
        assertEquals("The session must not reset",start.generation,end.generation)
        assertEquals(start.sessionId,end.sessionId);assertEquals(start.engineGeneration,end.engineGeneration)
        assertEquals("The session must remain active","RUNNING",end.status)
        val elapsedNs=end.observedAtNs-start.observedAtNs
        val elapsedMs=elapsedNs/1_000_000
        assertTrue("Measurement must cover 60 seconds",elapsedMs>=60000)
        val frames=end.acceptedFrames-start.acceptedFrames
        val fps=frames*1_000_000_000.0/elapsedNs
        assertTrue("Tested emulator must sustain at least 10fps: $fps",fps>=10.0)
        assertTrue("30fps ceiling: $frames in $elapsedMs ms",frames<=elapsedNs*30/1_000_000_000+2)
        val samples=end.samples.filter {it.sequence>start.acceptedFrames}
        assertEquals("Every accepted frame needs a measurement",frames,samples.size.toLong())
        assertEquals(start.acceptedFrames+1,samples.first().sequence)
        assertEquals(end.acceptedFrames,samples.last().sequence)
        samples.zipWithNext().forEach {(a,b) -> assertEquals(a.sequence+1,b.sequence);assertTrue(b.acceptedAtNs>=a.acceptedAtNs)}
        val latency=samples.map {it.latencyNs/1_000_000.0}.sorted()
        fun percentile(p: Double)=latency[(kotlin.math.ceil(latency.size*p).toInt()-1).coerceAtLeast(0)]
        val distribution=listOf(1.0,2.0,4.0,8.0,16.0,25.0,50.0,100.0).map {limit -> "le${limit}ms="+latency.count {it<=limit}}
        waitUntil("Native accepted frames stopped after measurement") {
            metrics.snapshot()?.let {it.generation==end.generation && it.status=="RUNNING" && it.acceptedFrames>end.acceptedFrames}==true
        }
        assertEquals("Frames must not save state",1,store.tool("lake")!!.stateVersion)
        screenshot("graphics-60-seconds")
        println("GRAPHICS_60S session=${end.sessionId} generation=${end.generation} engineGeneration=${end.engineGeneration} startNs=${start.observedAtNs} endNs=${end.observedAtNs} sessionStartedNs=${end.startedAtNs} startFrames=${start.acceptedFrames} endFrames=${end.acceptedFrames} elapsedMs=$elapsedMs frames=$frames fps=$fps latencyMinMs=${latency.first()} latencyMeanMs=${latency.average()} latencyP50Ms=${percentile(0.50)} latencyP95Ms=${percentile(0.95)} latencyP99Ms=${percentile(0.99)} latencyMaxMs=${latency.last()} distribution=${distribution.joinToString(",")} status=${end.status} stateVersion=1")
    }

    @Test fun chatSummaryOpensFocusedAppWithPersistentComposer() = fixture {model,store,scenario ->
        shown("Lake day"); shown("Open"); shown("Refine")
        assertNull("Chat must not render rows or image nodes",find("Day plan"))
        assertEquals(1,store.tool("lake")!!.stateVersion)
        screenshot("chat-summary")
        click("Refine")
        shown("Day plan"); shown("Refine this mini-app")
        assertNull("Focused app owns its top bar", find("Меню"))
        assertNull("Focused app has no shell history action", find("История"))
        assertNull(find("История версий"))
        edit("Refine this mini-app","Keep my request")
        val before=Rect().also {shown("Preview changes").getBoundsInScreen(it)}
        screenshot("composer-keyboard")
        scenario.recreate()
        shown("Refine this mini-app")
        val composer=find("Keep my request")
        assertNotNull("Composer text survives recreation",composer)
        // The long itinerary scrolls independently. The composer stays outside that viewport.
        fun scroll(node: AccessibilityNodeInfo?): Boolean {
            if(node == null) return false
            if(node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            repeat(node.childCount) {if(scroll(node.getChild(it))) return true}
            return false
        }
        repeat(5) {scroll(automation.rootInActiveWindow); automation.waitForIdle(100,3000)}
        shown("Preview changes")
        screenshot("long-itinerary")
        assertEquals("lake",model.tools.selectedId)
    }
    @Test fun candidateIsExplicitAndDiscardReturnsToSavedApp() = fixture(true) {model,store,_ ->
        instrumentation.runOnMainSync {model.tools.selectDraft("candidate"); model.openSurface("lake")}
        waitUntil {!model.tools.busy}
        instrumentation.runOnMainSync {model.tools.selectDraft("candidate")}
        shown("Preview · controls are disabled"); shown("Apply changes"); shown("Discard preview")
        screenshot("candidate-preview")
        assertEquals(1,store.tool("lake")!!.revision)
        click("Discard preview")
        shown("Lake day"); shown("Refine this mini-app")
        assertEquals("lake",model.tools.selectedId); assertNull(store.draft("candidate"))
        assertEquals(1,store.tool("lake")!!.revision)
    }
    @Test fun applyChangesUsesExistingGuardedCommit() = fixture(true) {model,store,_ ->
        instrumentation.runOnMainSync {model.openSurface("lake")}
        waitUntil {!model.tools.busy}
        instrumentation.runOnMainSync {model.tools.selectDraft("candidate")}
        click("Apply changes")
        waitUntil {!model.tools.busy && store.tool("lake")!!.revision == 2}
        shown("Revised lake day"); shown("Refine this mini-app")
        assertNull(store.draft("candidate"))
        screenshot("accepted-refinement")
    }
    @Test fun cancelThenErrorThenRetryPreservesAppAndComposer() {
        val calls=AtomicInteger()
        val provider=object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                check(request.jsonObject)
                when(calls.incrementAndGet()) {
                    1 -> awaitCancellation()
                    2 -> throw CloudFailure("fixture_failure","Offline refinement fixture")
                    else -> emit(CloudEvent.Text(ItineraryFixture.spec("Recovered lake day")))
                }
            }
        }
        fixture(provider=provider) {model,store,_ ->
            click("Open")
            val original=store.tool("lake")!!
            edit("Refine this mini-app","Keep the lake walk")
            click("Preview changes")
            waitUntil {calls.get() == 1}
            shown("Lake day"); click("Cancel")
            waitUntil {!model.tools.busy}
            assertEquals(original,store.tool("lake")); assertEquals("lake",model.tools.selectedId)
            shown("Keep the lake walk"); shown("Retry preview")
            screenshot("cancel-preserves-app")
            click("Retry preview")
            waitUntil {!model.tools.busy && model.tools.error != null}
            shown("Offline refinement fixture"); shown("Keep the lake walk")
            assertEquals(original,store.tool("lake"))
            screenshot("refinement-error")
            click("Retry preview")
            shown("Preview · controls are disabled"); shown("Recovered lake day")
            assertEquals(original,store.tool("lake")); assertEquals(3,calls.get())
            click("Discard preview")
            shown("Lake day"); shown("Keep the lake walk")
            assertEquals(original,store.tool("lake"))
        }
    }
    @Test fun concurrentStateChangeRejectsStaleApplyWithoutDataLoss() = fixture(true) {model,store,_ ->
        instrumentation.runOnMainSync {model.openSurface("lake")}
        waitUntil {!model.tools.busy}
        instrumentation.runOnMainSync {model.tools.selectDraft("candidate")}
        shown("Apply changes")
        val old=store.tool("lake")!!
        val nextState=JSONObject(old.state).apply {getJSONArray("stops").getJSONObject(0).put("detail","Concurrent saved edit")}
        val changed=old.copy(stateVersion=old.stateVersion+1,state=nextState.toString())
        store.commitTool(changed,old,false)
        click("Apply changes")
        waitUntil {!model.tools.busy && model.tools.error != null}
        shown("Инструмент изменился. Открой текущую версию.")
        assertEquals(changed,store.tool("lake")); assertNotNull(store.draft("candidate"))
        shown("Apply changes"); shown("Discard preview")
        screenshot("stale-apply-rejected")
        click("Discard preview")
        shown("Lake day"); assertEquals(changed,store.tool("lake"))
    }
    @Test fun twoButtonsOnOneStopCreateDistinctCorrectNativeRequests() {
        val definition=JSONObject(ItineraryFixture.spec())
            .put("initialState",JSONObject().put("stops",JSONArray().put(ItineraryFixture.rows(2).getJSONObject(1))))
            .put("capabilities",JSONArray().put("maps.open").put("calendar.create"))
            .put("source", """
                if(input.event.type==='action') {
                    const row=input.state.stops.find(x=>x.id===input.event.value);
                    if(!row) throw new Error('Unknown stop');
                    if(input.event.actionId==='mapStop') return {state:input.state,effects:[{id:'map_'+row.id,capability:'maps.open',args:{query:row.name}}]};
                    if(input.event.actionId==='calendarStop') return {state:input.state,effects:[{id:'calendar_'+row.id,capability:'calendar.create',args:{title:row.name,start:1800000000000,end:1800001200000,location:row.detail}}]};
                }
                return {state:input.state};
            """.trimIndent())
        fixture(specJson=definition.toString()) {model,store,_ ->
            click("Open")
            click("Search maps")
            waitUntil {store.effects("lake").size == 1 && !model.tools.busy}
            val map=store.effects("lake").single()
            assertEquals("maps.open",map.request.capability)
            assertEquals("Stop 1",map.request.args.getString("query")); assertEquals("pending",map.phase)
            click("Open calendar form")
            waitUntil {store.effects("lake").size == 2 && !model.tools.busy}
            val calendar=store.effects("lake").last()
            assertEquals("calendar.create",calendar.request.capability)
            assertEquals("Stop 1",calendar.request.args.getString("title"))
            assertEquals("Walk by the lake for 20 minutes",calendar.request.args.getString("location"))
            assertEquals(1800000000000,calendar.request.args.getLong("start"))
            assertNotEquals(map.key,calendar.key); assertEquals("pending",calendar.phase)
            assertEquals(1,store.tool("lake")!!.revision)
            screenshot("distinct-stop-requests")
        }
    }

}
