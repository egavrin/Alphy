package com.alpha.assistant.tools

import android.os.Bundle
import com.alpha.assistant.diagnostics.UiFailureDiagnostics
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
import kotlin.math.roundToInt

/** Black-box failure cases: executable chat cards, lost app context/draft, scrolling composer,
 * active candidate controls, accidental apply, and lost apply/discard controls with long content.
 * The root runs this fixture and retains screenshots with its tested revision and command.
 */
class MiniAppPresentationWorkflowTest {
    private val diagnostics = UiFailureDiagnostics("miniapp")
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private var injectedDownTime: Long?=null
    private fun injectTouch(event: android.view.MotionEvent): Boolean {
        val accepted=automation.injectInputEvent(event,true)
        if(accepted)when(event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> injectedDownTime=event.downTime
            android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL -> injectedDownTime=null
        }
        return accepted
    }
    private fun cancelInjectedTouch() {
        val down=injectedDownTime ?: return
        val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_CANCEL,0f,0f,0)
        event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {injectTouch(event)}finally {event.recycle();injectedDownTime=null}
    }
    private fun waitUntil(message: String="Mini-app presentation workflow timed out",condition: () -> Boolean) {
        val end=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<end) {
            // Android presents this tutorial on a new device before the app can receive touch.
            if(find("Viewing full screen")?.isVisibleToUser==true) {
                var confirm=find("Got it")
                while(confirm?.isClickable==false)confirm=confirm.parent
                if(confirm?.isVisibleToUser==true)assertTrue("Acknowledge Android full-screen tutorial",confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            if(condition()) return
            SystemClock.sleep(40)
        }
        diagnostics.capture("wait-timeout")
        fail(message)
    }
    private fun find(label: String, node: AccessibilityNodeInfo?=diagnostics.freshRoot()): AccessibilityNodeInfo? {
        if(node == null) return null
        if(node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) {find(label,node.getChild(it))?.let {return it}}
        return null
    }
    // material3 1.5.0-beta02 ignores Back until a modal sheet passes half of its enter animation. A tall sheet offers Expand only after it settles.
    private fun offersExpand(node: AccessibilityNodeInfo?=diagnostics.freshRoot()): Boolean {
        if(node == null) return false
        if(node.actionList.any {it.id == AccessibilityNodeInfo.ACTION_EXPAND}) return true
        repeat(node.childCount) {if(offersExpand(node.getChild(it))) return true}
        return false
    }
    // A modal sheet ignores Back until it passes half of its enter animation, so wait until it rests.
    private fun settled(label: String) {
        fun bounds()=find(label,diagnostics.freshRoot())?.let {node -> Rect().also {node.getBoundsInScreen(it)}}
        waitUntil("Sheet did not settle: $label") {
            val before=bounds()
            SystemClock.sleep(100)
            before != null && before == bounds()
        }
    }
    private fun shown(label: String): AccessibilityNodeInfo {
        diagnostics.target(label)
        var node: AccessibilityNodeInfo?=null
        var direction=AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var refused=false
        waitUntil("Missing visible label: $label") {
            node=find(label)
            if(node?.isVisibleToUser == true)return@waitUntil true
            if(node != null)node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            else {
                if(label in setOf("Exit full screen","App controls","Start","Pause","Resume","Restart","Left","Right") && find("Show game controls")?.isVisibleToUser==true) {
                    var handle=find("Show game controls")
                    while(handle?.isClickable==false)handle=handle.parent
                    assertTrue("Expand native game controls",checkNotNull(handle).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    return@waitUntil false
                }
                fun scrollable(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if(current==null)return null
                    if(current.isScrollable)return current
                    repeat(current.childCount) {scrollable(current.getChild(it))?.let {return it}}
                    return null
                }
                val viewport=find("Native game controls") ?: find("Mini-app content") ?: find("Mini-app library content") ?: if(label=="Open")scrollable(diagnostics.freshRoot()) else null
                if(viewport != null) {
                    if(viewport.actionList.none {it.id == direction}) direction=if(direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    // A refused scroll is not a failure: a running scroll animation can move the range after the read.
                    if(viewport.actionList.any {it.id == direction} && !viewport.performAction(direction) && !refused) {refused=true;diagnostics.capture("scroll-refused",viewport)}
                }
            }
            automation.waitForIdle(100,2000)
            false
        }
        return checkNotNull(node)
    }
    private fun click(label: String) {
        shown(label)
        var node: AccessibilityNodeInfo?=null
        waitUntil("Missing ready action: $label") {
            node=find(label)
            while(node?.isClickable == false) node=node?.parent
            node?.let {it.isVisibleToUser && it.isEnabled && it.actionList.any {action -> action.id == AccessibilityNodeInfo.ACTION_CLICK}} == true
        }
        assertTrue("Click $label",diagnostics.click(label,node))
        automation.waitForIdle(100,3000)
    }
    private fun edit(label: String, text: String) {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isEditable) return node
            repeat(node.childCount) {node.getChild(it)?.let {editable(it)?.let {found -> return found}}}
            return null
        }
        shown(label)
        var found: AccessibilityNodeInfo?=null
        // Compose UI 1.12.1 reports a disabled text field as not editable, and the field is disabled while the controller is busy.
        waitUntil("Missing enabled field: $label") {
            var node=find(label)
            while(node != null && editable(node) == null) node=node.parent
            found=node?.let {editable(it)}
            found?.isEnabled == true
        }
        val field=checkNotNull(found)
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
                try {block(model,tools,scenario)}finally {cancelInjectedTouch()}
            }
        } finally {
            CloudTestActivity.factory=null; SystemClock.sleep(150); tools.close(); shell.close()
            app.deleteDatabase("$name.db"); app.deleteDatabase("$name.db-tools.db")
            keys.clear(); app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null); deleteEntry(name)}
        }
    }
    /** Failure cases: wide split/chrome in immersive play, wrong touch transform, missing cancel,
     * stale dimensions after rotation, inaccessible controls, lost state on exit, and hidden bars after exit.
     */
    @Test fun immersiveViewportTouchResizeAndExitPreserveState() {
        val definition=JSONObject(ArkanoidFixture.spec()).put("title","Viewport probe")
            .put("presentation",JSONObject("{\"mode\":\"immersive\"}"))
            .put("stateSchema",JSONObject("{\"width\":\"number\",\"height\":\"number\",\"x\":\"number\",\"phase\":\"string\",\"cancels\":\"number\"}"))
            .put("initialState",JSONObject("{\"width\":320,\"height\":420,\"x\":0,\"phase\":\"none\",\"cancels\":0}"))
            .put("ui",JSONObject("{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"text\":\"Probe state\"},{\"type\":\"text\",\"text\":\"Pointer phase\",\"bind\":\"phase\"}]}"))
            .put("source","""
                const s=input.state,e=input.event;
                if(e.type==='viewport'){s.width=e.width;s.height=e.height;}
                if(e.type==='pointer'){s.x=e.x;s.phase=e.phase;if(e.phase==='cancel')s.cancels++;}
                return {state:s,surfaces:{main:{commands:[{type:'rect',x:0,y:0,width:s.width,height:s.height,color:'#123456'},
                {type:'text',x:12,y:24,size:16,text:'Viewport '+s.width+' '+s.height,color:'#FFFFFF'}]}},frames:['main']};
            """.trimIndent())
        definition.getJSONArray("surfaces").getJSONObject(0).put("sizing","viewport")
        fixture(specJson=definition.toString()) {_,store,scenario ->
            click("Open");shown("Exit full screen")
            assertNull(find("Mini-app refinement"));assertNull(find("Mini-app content"))
            fun state()=JSONObject(store.tool("lake")!!.state)
            fun canvas(node: AccessibilityNodeInfo?=automation.rootInActiveWindow): AccessibilityNodeInfo? {
                if(node==null)return null
                if(node.contentDescription?.startsWith("Viewport probe.")==true)return node
                repeat(node.childCount) {canvas(node.getChild(it))?.let {found -> return found}}
                return null
            }
            val bounds=Rect()
            fun logical(pixels: Int): Int=(pixels/instrumentation.targetContext.resources.displayMetrics.density).roundToInt().coerceIn(64,2048)
            waitUntil {canvas()?.let {it.getBoundsInScreen(bounds);bounds.width()>0 && bounds.height()>0}==true && state().getInt("height")==logical(bounds.height())}
            fun fullWindow() {
                val window=Rect().also {checkNotNull(diagnostics.freshRoot()).getBoundsInScreen(it)}
                checkNotNull(canvas()).getBoundsInScreen(bounds)
                assertEquals("Viewport canvas must fill the entire app window",window,bounds)
                assertEquals("Logical viewport width",logical(bounds.width()),state().getInt("width"))
                assertEquals("Logical viewport height",logical(bounds.height()),state().getInt("height"))
            }
            fullWindow()
            val down=SystemClock.uptimeMillis()
            fun touch(action: Int,fraction: Float) {
                if(find("Hide game controls")!=null)click("Hide game controls")
                val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.left+bounds.width()*fraction,bounds.exactCenterY(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {assertTrue(injectTouch(event))}finally {event.recycle()}
            }
            touch(android.view.MotionEvent.ACTION_DOWN,0.25f)
            touch(android.view.MotionEvent.ACTION_MOVE,0.75f)
            touch(android.view.MotionEvent.ACTION_UP,0.75f)
            waitUntil {state().getString("phase")=="up"}
            assertEquals(state().getInt("width")*0.75,state().getDouble("x"),3.0)
            touch(android.view.MotionEvent.ACTION_DOWN,0.5f)
            click("Pause");waitUntil {state().getInt("cancels")>0};touch(android.view.MotionEvent.ACTION_UP,0.5f)
            click("Resume")
            val previousWidth=state().getInt("width")
            scenario.onActivity {it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            waitUntil {state().getInt("width")!=previousWidth}
            waitUntil {canvas()?.let {it.getBoundsInScreen(bounds);bounds.width()>bounds.height()}==true}
            fullWindow()
            assertNull("Wide view must suppress shell split",find("Mini-app refinement"))
            click("App controls");shown("Probe state")
            settled("Probe state")
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            shown("Exit full screen")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            shown("Exit full screen");screenshot("immersive-viewport")
            click("Exit full screen");shown("Mini-app refinement")
            assertTrue(store.effects("lake").isEmpty())
            scenario.onActivity {activity ->
                val insets=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                assertTrue("System bars must return after exit",insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())==true)
            }
            println("IMMERSIVE_VIEWPORT density=${instrumentation.targetContext.resources.displayMetrics.density} touch=PASS cancel=PASS rotation=PASS controls=PASS lifecycle=PASS exit=PASS")
        }
    }
    /** Playback failure cases: ready cannot start, restart remains host-paused, sheet actions are
     * overwritten, saved pause disappears on reopen, or temporary suspension changes saved playback.
     */
    @Test fun playbackControlsSheetAndReopenUseValidatedOutput() {
        val definition=JSONObject(ArkanoidFixture.spec()).put("title","Playback probe")
            .put("presentation",JSONObject("{\"mode\":\"immersive\"}"))
            .put("stateSchema",JSONObject("{\"mode\":\"string\",\"ticks\":\"number\",\"downs\":\"number\",\"starts\":\"number\",\"pointerX\":\"number\"}"))
            .put("initialState",JSONObject("{\"mode\":\"ready\",\"ticks\":0,\"downs\":0,\"starts\":0,\"pointerX\":0}"))
            .put("ui",JSONObject("""{"type":"column","children":[{"type":"text","text":"Playback controls"},{"type":"button","text":"Generated pause","action":"pause"},{"type":"button","text":"Generated resume","action":"resume"}]}"""))
            .put("source","""
                const s=input.state,e=input.event,a=e.type==='action'?e.actionId:e.type;
                if(a==='start'&&s.mode==='ready'&&s.starts++>0||a==='resume'||a==='restart')s.mode='playing';
                if(a==='pause')s.mode='paused';
                if(e.type==='frame')s.ticks++;
                if(e.type==='pointer'){s.pointerX=e.x;if(e.phase==='down')s.downs++;}
                return {state:s,playback:s.mode,frames:['main'],surfaces:{main:{commands:[
                    {type:'rect',x:0,y:0,width:320,height:420,color:'#123456'},
                    {type:'text',x:12,y:24,size:16,text:'Playback '+s.mode,color:'#FFFFFF'}]}}};
            """.trimIndent())
        fixture(specJson=definition.toString()) {_,store,scenario ->
            diagnostics.state={"state="+store.tool("lake")?.state}
            fun state()=JSONObject(store.tool("lake")!!.state)
            fun mode(value: String)=waitUntil {state().getString("mode")==value}
            fun still() {val ticks=state().getInt("ticks");SystemClock.sleep(350);assertEquals(ticks,state().getInt("ticks"))}
            fun advances() {val ticks=state().getInt("ticks");waitUntil {state().getInt("ticks")>ticks}}
            fun dismiss() {settled("Playback controls");automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);shown("Exit full screen")}
            fun controls() {
                click("App controls");shown("Playback controls")
                waitUntil {
                    var node=find("Generated pause")
                    while(node?.isClickable==false)node=node.parent
                    node?.isEnabled==true
                }
            }
            var heldDownTime=0L
            fun touch(action: Int?=null,fraction: Float=0.5f) {
                if(find("Hide game controls")!=null)click("Hide game controls")
                fun canvas(node: AccessibilityNodeInfo?=automation.rootInActiveWindow): AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(node.contentDescription?.startsWith("Playback probe.")==true)return node
                    repeat(node.childCount) {canvas(node.getChild(it))?.let {found -> return found}}
                    return null
                }
                val bounds=Rect().also {checkNotNull(canvas()).getBoundsInScreen(it)}
                if(action==null || action==android.view.MotionEvent.ACTION_DOWN)heldDownTime=SystemClock.uptimeMillis()
                for(phase in action?.let {listOf(it)} ?: listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                    val event=android.view.MotionEvent.obtain(heldDownTime,SystemClock.uptimeMillis(),phase,bounds.left+bounds.width()*fraction,bounds.exactCenterY(),0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try {assertTrue(injectTouch(event))}finally {event.recycle()}
                }
            }
            click("Open");shown("Start");mode("ready");still()
            touch(android.view.MotionEvent.ACTION_DOWN,0.25f)
            waitUntil {state().getInt("downs")==1}
            click("Start");mode("playing");advances()
            touch(android.view.MotionEvent.ACTION_MOVE,0.75f)
            waitUntil("Ready-to-playing transition lost pointer ownership") {kotlin.math.abs(state().getDouble("pointerX")-240.0)<3.0}
            touch(android.view.MotionEvent.ACTION_UP,0.75f)
            click("Pause");shown("Resume");mode("paused");still()
            click("Restart");mode("playing");shown("Pause");advances()
            touch();waitUntil {state().getInt("downs")==2}
            controls();mode("playing");still()
            click("Generated pause");mode("paused");dismiss();shown("Resume");still()
            controls();click("Generated resume");mode("playing")
            dismiss();shown("Pause");advances()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            mode("playing");still()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            shown("Pause");mode("playing");advances()
            click("Pause");mode("paused");shown("Resume")
            click("Exit full screen");shown("Mini-app refinement");shown("Resume");mode("paused");still()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            shown("Resume");mode("paused");still()
            screenshot("playback-saved-pause")
            println("PLAYBACK_NATIVE ready=PASS restart=PASS sheetActions=PASS suspension=PASS savedPause=PASS")
        }
    }
    /** Saved provider regressions: down/move must change state and visible paddle pixels.
     * The original JSON stays unchanged. The probe above covers observable up/cancel delivery.
     */
    @Test fun savedGeneratedArkanoidTouchMovesVisiblePaddle() {
        val assets=instrumentation.context.assets
        for(file in checkNotNull(assets.list("runtime-replay")).sorted()) {
            val raw=assets.open("runtime-replay/$file").bufferedReader().use {it.readText()}
            val definition=JSONObject(raw)
            fixture(specJson=raw) {_,store,scenario ->
                diagnostics.state={"replay=$file state="+store.tool("lake")?.state}
                fun state()=JSONObject(store.tool("lake")!!.state)
                fun canvas(node: AccessibilityNodeInfo?=diagnostics.freshRoot()): AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(node.contentDescription?.startsWith(definition.getString("title")+".")==true)return node
                    repeat(node.childCount) {canvas(node.getChild(it))?.let {return it}}
                    return null
                }
                click("Open");shown("Pause")
                val bounds=Rect()
                waitUntil {canvas()?.let {it.getBoundsInScreen(bounds);bounds.width()>0 && bounds.height()>0}==true}
                val window=Rect().also {checkNotNull(diagnostics.freshRoot()).getBoundsInScreen(it)}
                assertEquals("$file full-window canvas",window,bounds)
                for(orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                scenario.onActivity {it.requestedOrientation=orientation}
                waitUntil {canvas()?.let {it.getBoundsInScreen(bounds);(bounds.width()>bounds.height())==(orientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)}==true}
                val window=Rect().also {checkNotNull(diagnostics.freshRoot()).getBoundsInScreen(it)}
                assertEquals("$file orientation=$orientation full-window canvas",window,bounds)
                click("Restart");shown("Pause")
                click("Pause");shown("Resume")
                click("Left");waitUntil {state().getDouble("paddle")<0.49}
                click("Right");waitUntil {kotlin.math.abs(state().getDouble("paddle")-0.5)<0.03}
                click("App controls")
                shown(definition.getJSONObject("ui").getJSONArray("children").getJSONObject(0).getString("text")+": "+state().getString("message"))
                waitUntil("App controls sheet did not settle") {offersExpand()}
                screenshot(file.removeSuffix(".json")+"-$orientation-app-controls")
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                shown("Resume")
                click("Hide game controls")
                shown("Show game controls")
                waitUntil("$file orientation=$orientation first-row bricks must remain unobstructed") {
                    val board=automation.takeScreenshot() ?: return@waitUntil false
                    try {
                        val u=minOf(bounds.width(),bounds.height()).toDouble()
                        val brickWidth=(bounds.width()*0.89-u*0.016*5)/6
                        val y=(bounds.top+bounds.height()*0.16+u*0.023).toInt()
                        (0 until 6).all {index ->
                            val x=(bounds.left+bounds.width()*0.055+index*(brickWidth+u*0.016)+brickWidth/2).toInt()
                            val color=board.getPixel(x,y)
                            android.graphics.Color.red(color)>230 && android.graphics.Color.green(color) in 70..150 && android.graphics.Color.blue(color) in 100..180
                        }
                    }finally {board.recycle()}
                }
                screenshot(file.removeSuffix(".json")+"-$orientation-unobstructed-board")
                click("Resume");shown("Pause");click("Hide game controls")
                shown("Show game controls")
                assertNull("Playback panel must retract during play",find("Hide game controls"))
                val stem=file.removeSuffix(".json")+"-"+if(orientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)"landscape" else "portrait"
                fun visiblePaddle(label: String,expected: Double?=null): Double {
                    var center=Double.NaN
                    waitUntil("$file visible paddle missing") {
                        val bitmap=automation.takeScreenshot() ?: return@waitUntil false
                        try {
                            val y=(bounds.top+bounds.height()*0.9+minOf(bounds.width(),bounds.height())*0.013).toInt()
                            val pixels=(bounds.left until bounds.right).filter {x ->
                                val color=bitmap.getPixel(x,y)
                                android.graphics.Color.red(color)>40 && android.graphics.Color.green(color)>170 && android.graphics.Color.blue(color)>170
                            }
                            if(pixels.size<bounds.width()*0.05)false else {
                                center=pixels.average()
                                if(expected!=null && kotlin.math.abs(center-expected)>5.0)return@waitUntil false
                                val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"mini-app-evidence").apply {mkdirs()}
                                File(directory,"$stem-$label.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                                true
                            }
                        } finally {bitmap.recycle()}
                    }
                    return center
                }
                val before=visiblePaddle("before")
                var downTime=0L
                fun touch(action: Int,fraction: Float) {
                    val now=SystemClock.uptimeMillis()
                    if(action==android.view.MotionEvent.ACTION_DOWN)downTime=now
                    val event=android.view.MotionEvent.obtain(downTime,now,action,bounds.left+bounds.width()*fraction,bounds.top+bounds.height()*0.65f,0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try {assertTrue(injectTouch(event))}finally {event.recycle()}
                }
                touch(android.view.MotionEvent.ACTION_DOWN,0.25f)
                waitUntil {kotlin.math.abs(state().getDouble("paddle")-0.25)<0.03}
                val down=visiblePaddle("down",bounds.left+bounds.width()*0.25)
                assertEquals("$file visible down position",bounds.left+bounds.width()*0.25,down,5.0)
                touch(android.view.MotionEvent.ACTION_MOVE,0.75f)
                waitUntil {kotlin.math.abs(state().getDouble("paddle")-0.75)<0.03}
                val moved=visiblePaddle("move",bounds.left+bounds.width()*0.75)
                assertEquals("$file visible move position",bounds.left+bounds.width()*0.75,moved,5.0)
                assertTrue("$file visible displacement",moved-down>bounds.width()*0.4)
                touch(android.view.MotionEvent.ACTION_UP,0.75f)
                touch(android.view.MotionEvent.ACTION_DOWN,0.4f)
                waitUntil("UP must release the consumed pointer") {kotlin.math.abs(state().getDouble("paddle")-0.4)<0.03}
                touch(android.view.MotionEvent.ACTION_CANCEL,0.4f)
                touch(android.view.MotionEvent.ACTION_DOWN,0.6f)
                waitUntil("CANCEL must release the consumed pointer") {kotlin.math.abs(state().getDouble("paddle")-0.6)<0.03}
                touch(android.view.MotionEvent.ACTION_UP,0.6f)
                click("Pause");shown("Resume")
                click("Restart");shown("Pause")
                waitUntil {state().getInt("score")==0 && state().getInt("lives")==3}
                screenshot("$stem-controls-expanded")
                println("SAVED_GAME_TOUCH file=$file orientation=$orientation density=${instrumentation.targetContext.resources.displayMetrics.density} fontScale=${instrumentation.targetContext.resources.configuration.fontScale} beforePx=$before downPx=$down movePx=$moved fullWindow=PASS down=PASS move=PASS upRelease=PASS cancelRelease=PASS nativeControls=PASS restart=PASS")
                }
                click("Exit full screen");shown("Mini-app refinement")
            }
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
        val before=store.tool("lake")!!;assertTrue(before.stateVersion>=1)
        ToolSpec.parse(before.spec).validateState(before.state)
        assertNull("Protocol 3 saves validated state without a checkpoint button",find("Save score"))
        click("Left");paddle(128)
        click("Resume");shown("Playing")
        val node=checkNotNull(surface());node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        automation.waitForIdle(100,3000)
        val bounds=Rect().also {checkNotNull(surface()).getBoundsInScreen(it)}
        val now=SystemClock.uptimeMillis()
        val x=bounds.left+bounds.width()*0.25f;val y=bounds.exactCenterY()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0)
            try {assertTrue(injectTouch(event))}finally {event.recycle()}
        }
        paddle(48);click("Pause");shown("Paused");screenshot("graphics-touch-saved")
        val saved=store.tool("lake")!!
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Thread.sleep(150)
        kotlinx.coroutines.runBlocking {
            val probe=com.alpha.assistant.sandbox.GraphicsSession(instrumentation.targetContext)
            try {assertEquals("Background must release the retained slot","OK",probe.open("return {state:input.state,surfaces:{main:{commands:[]}}};","{}").status)}finally {probe.close()}
        }
        assertEquals(saved.state,store.tool("lake")!!.state)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        shown("Playing")
        paddle(128)
        click("Restart")
        paddle(128)
        assertEquals(0,JSONObject(store.tool("lake")!!.state).getInt("score"))
        assertEquals(3,JSONObject(store.tool("lake")!!.state).getInt("lives"))
        screenshot("graphics-resumed")
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitUntil {surface()==null}
        shown("Mini-app library content")
        assertTrue(store.effects("lake").isEmpty())
        println("GRAPHICS_NATIVE touch=PASS durableState=PASS backgroundRelease=PASS back=PASS stateVersion=${saved.stateVersion}")
    }
    @Test fun graphicsLockReleasesSessionWithoutSavingFrames() = fixture(specJson=ArkanoidFixture.spec()) {model,store,scenario ->
        click("Open");shown("Playing")
        click("Pause");shown("Paused")
        val saved=store.tool("lake")!!
        val power=instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java)
        try {
            automation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            waitUntil {!power.isInteractive};Thread.sleep(200)
            kotlinx.coroutines.runBlocking {
                val probe=com.alpha.assistant.sandbox.GraphicsSession(instrumentation.targetContext)
                try {assertEquals("Lock must release the retained slot","OK",probe.open("return {state:input.state,surfaces:{main:{commands:[]}}};","{}").status)}finally {probe.close()}
            }
            assertEquals(saved.state,store.tool("lake")!!.state)
        } finally {
            automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            automation.executeShellCommand("wm dismiss-keyguard").close()
        }
        waitUntil {power.isInteractive}
        assertEquals(saved.state,store.tool("lake")!!.state);screenshot("graphics-lock-resume")
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
        val snapshotRate=frames*1_000_000_000.0/elapsedNs
        assertTrue("Validated snapshots must advance",frames>0)
        assertTrue("Display requests must advance",end.frameRequests>start.frameRequests)
        assertTrue("Host completions must advance",end.evaluations>start.evaluations)
        assertTrue("Native submissions must advance",end.submissions>start.submissions)
        assertTrue("Platform commits must advance",end.frameCommits>start.frameCommits)
        assertEquals("UNAVAILABLE",end.presentationEvidence)
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
        assertTrue("Validated frame state must persist",store.tool("lake")!!.stateVersion>1)
        assertTrue("Frames must not request effects",store.effects("lake").isEmpty())
        screenshot("graphics-60-seconds")
        println("GRAPHICS_60S session=${end.sessionId} generation=${end.generation} elapsedMs=$elapsedMs validatedSnapshots=$frames snapshotRate=$snapshotRate frameRequests=${end.frameRequests-start.frameRequests} hostCompletions=${end.evaluations-start.evaluations} submissions=${end.submissions-start.submissions} bufferCommits=${end.frameCommits-start.frameCommits} panelPresentation=${end.presentationEvidence} hostCallLatencyP50Ms=${percentile(0.50)} hostCallLatencyP95Ms=${percentile(0.95)} distribution=${distribution.joinToString(",")} status=${end.status} stateVersion=${store.tool("lake")!!.stateVersion}")
    }

    /** Black-box graphics workflow. Touch injection targets only the disposable activity fixture. */
    @Test fun positionedSurfacesRouteOwnedPointersAndReportBufferCommits() {
        val definition=JSONObject().put("formatVersion",3).put("title","Positioned surfaces").put("description","Offline graphics workflow")
            .put("capabilities",JSONArray()).put("stateSchema",JSONObject("""{"last":"string","phase":"string","x":"number","downs":"number","cancels":"number","ticks":"number","firstDt":"number","resumed":"boolean"}"""))
            .put("initialState",JSONObject("""{"last":"none","phase":"none","x":0,"downs":0,"cancels":0,"ticks":0,"firstDt":0,"resumed":false}"""))
            .put("ui",JSONObject("""{"type":"text","text":"Last surface","bind":"last"}"""))
            .put("surfaces",JSONArray("""[{"id":"back","width":320,"height":240,"x":0,"y":0},{"id":"front","width":160,"height":120,"x":80,"y":60}]"""))
            .put("source","""
                const s=input.state,e=input.event;
                if(e.type==='pointer') {
                    s.last=e.surfaceId;s.phase=e.phase;s.x=e.x;
                    if(e.phase==='down')s.downs++;
                    if(e.phase==='cancel')s.cancels++;
                }
                if(e.type==='resume')s.resumed=true;
                if(e.type==='frame') {s.ticks++;if(s.resumed){s.firstDt=e.dt;s.resumed=false;}}
                return {state:s,frames:['back'],surfaces:{
                    back:{commands:[{type:'rect',x:0,y:0,width:320,height:240,color:'#203040'}]},
                    front:{commands:[{type:'path',segments:[{type:'move',x:0,y:0},{type:'line',x:160,y:0},{type:'line',x:160,y:120},{type:'line',x:0,y:120},{type:'close'}],gradient:{type:'linear',x:0,y:0,x2:160,y2:120,stops:[{offset:0,color:'#AA3300'},{offset:1,color:'#FFAA55'}]}}]}
                }};
            """.trimIndent())
        fixture(specJson=definition.toString()) {_,store,_ ->
            click("Open");shown("Playing")
            fun state()=JSONObject(store.tool("lake")!!.state)
            fun canvas(node: AccessibilityNodeInfo?=automation.rootInActiveWindow): AccessibilityNodeInfo? {
                if(node==null)return null
                if(node.contentDescription?.startsWith("Positioned surfaces.")==true)return node
                repeat(node.childCount) {canvas(node.getChild(it))?.let {found -> return found}}
                return null
            }
            var bounds=Rect()
            waitUntil {canvas()?.let {it.getBoundsInScreen(bounds);bounds.width()>0 && bounds.height()>0}==true}
            var downTime=0L
            fun touch(action: Int,x: Float,y: Float) {
                val now=SystemClock.uptimeMillis()
                if(action==android.view.MotionEvent.ACTION_DOWN)downTime=now
                val event=android.view.MotionEvent.obtain(downTime,now,action,bounds.left+x/320*bounds.width(),bounds.top+y/240*bounds.height(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {assertTrue(injectTouch(event))}finally {event.recycle()}
            }
            touch(android.view.MotionEvent.ACTION_DOWN,100f,80f)
            waitUntil {state().getInt("downs")==1}
            assertEquals("front",state().getString("last"));assertEquals(20.0,state().getDouble("x"),2.0)
            touch(android.view.MotionEvent.ACTION_MOVE,300f,210f)
            touch(android.view.MotionEvent.ACTION_UP,300f,210f)
            waitUntil {state().getString("phase")=="up"}
            assertEquals("front",state().getString("last"));assertEquals(160.0,state().getDouble("x"),0.0)
            touch(android.view.MotionEvent.ACTION_DOWN,10f,10f)
            waitUntil {state().getInt("downs")==2}
            assertEquals("back",state().getString("last"))
            click("Pause")
            waitUntil {state().getInt("cancels")==1}
            touch(android.view.MotionEvent.ACTION_UP,10f,10f)
            shown("Paused");screenshot("positioned-surfaces-paused")
            click("Resume");shown("Playing")
            waitUntil {state().getInt("firstDt")==0 && !state().getBoolean("resumed")}
            waitUntil {com.alpha.assistant.sandbox.SurfaceFrameMetrics.snapshot()?.frameCommits?.let {it>0}==true}
            val measured=checkNotNull(com.alpha.assistant.sandbox.SurfaceFrameMetrics.snapshot())
            assertTrue(measured.frameRequests>0);assertTrue(measured.evaluations>0)
            assertTrue(measured.validatedSnapshots>0);assertTrue(measured.submissions>0);assertTrue(measured.frameCommits>0)
            assertEquals("UNAVAILABLE",measured.presentationEvidence)
            assertTrue(store.effects("lake").isEmpty());screenshot("positioned-surfaces-resumed")
            println("POSITIONED_SURFACES overlap=front ownership=PASS clamp=PASS backgroundHit=PASS cancel=PASS resumeDt=0 requests=${measured.frameRequests} eventCompletions=${measured.evaluations} validated=${measured.validatedSnapshots} submissions=${measured.submissions} bufferCommits=${measured.frameCommits} panelPresentation=${measured.presentationEvidence}")
        }
    }

    /** Rendered workflow: template controls, shared layers, tabs, empty state, rotation, and large text. */
    @Test fun nativeComposeFamiliesRenderAndInteractAcrossConfigurations() {
        val schema="""{"tick":"number","tab":"string","details":"boolean","empty":"boolean","pose":{"type":"array","maxItems":8,"items":"number"},"rows":{"type":"array","maxItems":8,"items":{"type":"object","fields":{"id":"string","title":"string","group":"string","shown":"boolean","available":"boolean","selected":"boolean","amount":"number"}}}}"""
        val state="""{"tick":0,"tab":"overview","details":false,"empty":false,"pose":[0,0,0,0,0,0,1,1],"rows":[{"id":"r1","title":"First row","group":"Today","shown":true,"available":true,"selected":false,"amount":2}]}"""
        val ui="""{"type":"column","children":[
            {"type":"row","children":[{"type":"button","text":"Turn layer","action":"turn"},{"type":"button","text":"Clear rows","action":"clear"}]},
            {"type":"motion","preset":"pop","trigger":"tick","children":[{"type":"metric","text":"Turns","bind":"tick"}]},
            {"type":"tabs","text":"View","bind":"tab","action":"tab","options":[{"text":"Overview","value":"overview"},{"text":"Details","value":"details"}]},
            {"type":"banner","text":"Details selected","visibleBind":"details","children":[{"type":"text","text":"The selected page is native."}]},
            {"type":"stage","height":180,"perspective":900,"floor":true,"children":[{"type":"transform","poseBind":"pose","durationMs":300,"children":[
                {"type":"transform","pose":[0,0,0,0,0,30,1,1],"solid":true,"size":100,"children":[{"type":"card","children":[{"type":"text","text":"Front face"}]}]},
                {"type":"transform","pose":[0,180,0,0,0,-30,1,1],"solid":true,"size":100,"children":[{"type":"card","children":[{"type":"text","text":"Back face"}]}]}
            ]}]},
            {"type":"groupedList","text":"Template rows","bind":"rows","groupBy":"group","emptyText":"No sample rows","rowTemplate":{"type":"form","text":"Row form","visibleBind":"shown","enabledBind":"available","children":[
                {"type":"text","text":"Name","bind":"title"},{"type":"toggle","text":"Select row","bind":"selected","action":"selectRow"},{"type":"numberInput","text":"Row amount","bind":"amount","min":0,"max":10,"action":"amount"}
            ]}},
            {"type":"facts","text":"Native facts","children":[{"type":"fact","text":"Turns recorded","bind":"tick"},{"type":"fact","text":"Current view","bind":"tab"}]},
            {"type":"timeline","text":"Native timeline","children":[{"type":"timelineItem","text":"Start","time":"09:00","detail":"The first native entry"},{"type":"timelineItem","text":"Finish","time":"10:00","detail":"The second native entry"}]},
            {"type":"listGroup","text":"Native group","children":[{"type":"text","text":"Grouped content"},{"type":"text","text":"Another grouped item"}]},
            {"type":"emptyState","text":"Sample is empty","visibleBind":"empty","children":[{"type":"text","text":"All rows were removed."}]}
        ]}"""
        val definition=JSONObject().put("formatVersion",3).put("title","Compose family fixture").put("description","Offline native family workflow")
            .put("capabilities",JSONArray()).put("stateSchema",JSONObject(schema)).put("initialState",JSONObject(state)).put("ui",JSONObject(ui))
            .put("source","""
                if(input.event.type==='action') {
                    const event=input.event;
                    if(event.actionId==='turn') {input.state.tick++;input.state.pose[1]+=45;}
                    if(event.actionId==='tab') {input.state.tab=event.value;input.state.details=event.value==='details';}
                    if(event.actionId==='clear') {input.state.rows=[];input.state.empty=true;}
                    if(event.actionId==='selectRow'||event.actionId==='amount') {
                        const row=input.state.rows.find(x=>x.id===event.value.key);
                        if(!row) throw new Error('Missing row');
                        if(event.actionId==='amount') row.amount=event.value.value;
                        else row.selected=event.value.value;
                    }
                }
                return {state:input.state};
            """.trimIndent())
        fixture(specJson=definition.toString()) {model,store,scenario ->
            click("Open"); shown("Turns"); shown("0"); screenshot("compose-families-initial")
            click("Turn layer")
            waitUntil {JSONObject(store.tool("lake")!!.state).getInt("tick")==1 && !model.tools.busy}
            shown("Turns"); shown("1"); screenshot("compose-families-turned")
            click("Details")
            waitUntil("Tab selection did not reach durable state") {
                val current=JSONObject(store.tool("lake")!!.state)
                current.getString("tab")=="details" && current.getBoolean("details") && !model.tools.busy
            }
            shown("Details")
            waitUntil("The selected tab remains labeled and selected") {
                var tab=find("Details")
                while(tab != null && !tab.isSelected && !tab.isClickable) tab=tab.parent
                tab?.let {it.isVisibleToUser && it.isSelected} == true
            }
            shown("Details selected"); screenshot("compose-families-details")
            click("Select row")
            waitUntil {JSONObject(store.tool("lake")!!.state).getJSONArray("rows").getJSONObject(0).getBoolean("selected") && !model.tools.busy}
            edit("Row amount","4"); click("Apply: Row amount")
            waitUntil {JSONObject(store.tool("lake")!!.state).getJSONArray("rows").getJSONObject(0).getInt("amount")==4 && !model.tools.busy}
            shown("Native timeline"); shown("10:00"); screenshot("compose-families-timeline")
            scenario.onActivity {it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            shown("Native facts"); screenshot("compose-families-wide")
            val original=automation.executeShellCommand("settings get system font_scale").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()}
            try {
                automation.executeShellCommand("settings put system font_scale 1.5").close()
                SystemClock.sleep(500)
                shown("Native facts"); screenshot("compose-families-large-font")
            } finally {
                val restore=original.toDoubleOrNull()?.toString() ?: "1.0"
                automation.executeShellCommand("settings put system font_scale $restore").close()
            }
            scenario.onActivity {it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            click("Clear rows"); shown("Sample is empty"); shown("No sample rows")
            screenshot("compose-families-empty")
            assertEquals(0,JSONObject(store.tool("lake")!!.state).getJSONArray("rows").length())
            assertTrue(store.effects("lake").isEmpty())
            println("NATIVE_COMPOSE families=PASS templateToggle=PASS templateNumber=PASS tabs=PASS empty=PASS wide=PASS fontScale1.5=PASS effects=0")
        }
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
