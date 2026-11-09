package com.alpha.assistant.tools

import com.alpha.assistant.ui.openSeededChat
import com.alpha.assistant.ui.seedOpenChat
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Black-box failure cases: stale or inactive chat cards, lost app context/draft, scrolling composer,
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
            if(node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) != true) {
                if(label in setOf("Выйти из полноэкранного режима","Управление мини-приложением","Начать","Пауза","Продолжить","Начать заново","Влево","Вправо")) {
                    var handle=find("Показать управление игрой")
                    if (handle?.isVisibleToUser == true) {
                        while (handle?.isClickable == false) handle = handle.parent
                        if (handle?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@waitUntil false
                    }
                }
                val viewport=find("Управление игрой") ?: find("Содержимое мини-приложения") ?: find("Содержимое библиотеки мини-приложений")
                    ?: find("Сообщения разговора")
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
    private fun fixture(candidate: Boolean=false, provider: ChatProvider?=null, specJson: String=ItineraryFixture.spec(), secondReference: Boolean=false, candidateJson: String=ItineraryFixture.spec("Revised lake day"),
        repeatInChat: Boolean=false, previousJson: String?=null, showChat: Boolean=true, pendingPrompt: String?=null,
        block: (ShellViewModel,ToolStore,ActivityScenario<CloudTestActivity>) -> Unit) {
        val app=instrumentation.targetContext
        val name="presentation-${UUID.randomUUID()}"
        val shell=ShellStore(app,"$name.db"); val tools=ToolStore(app,"$name.db-tools.db")
        val keys=SecureKeyStore(app,name,name)
        val spec=ToolSpec.parse(specJson)
        val seed=ToolRecord("lake",1,1,spec.json,spec.initialState,spec.ui.toString(),"chat",true)
        val previous=previousJson?.let(ToolSpec::parse)
        val tool=if(previous==null) seed else seed.copy(revision=2,stateVersion=2)
        if(previous!=null) tools.saveTool(seed.copy(spec=previous.json,state=previous.initialState,ui=previous.ui.toString()),true)
        tools.saveTool(tool,true)
        if(candidate || pendingPrompt != null) {
            val next=if(candidate) ToolSpec.parsePersisted(candidateJson) else null
            tools.saveDraft(ToolDraft("candidate",tool.id,1,1,pendingPrompt ?: "Make the day shorter",
                next?.json,next?.initialState,next?.ui?.toString(),"chat"))
        }
        val artifact=Message("artifact","assistant","Your day plan is ready.",1,"complete",toolId=tool.id,toolRevision=1)
        val cards=if(repeatInChat) listOf(artifact,artifact.copy(id="duplicate-artifact")) else listOf(artifact)
        shell.saveConversation(Conversation("chat","Plan discussion",1,1,messages=cards))
        if(secondReference) shell.saveConversation(Conversation("other","Second discussion",2,2,
            messages=listOf(Message("second-artifact","assistant","Second card.",2,"complete",toolId=tool.id,toolRevision=1))))
        if(showChat) shell.seedOpenChat("chat")
        keys.save("presentation-fixture-only").getOrThrow()
        lateinit var model: ShellViewModel
        val responder=provider ?: object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                check(request.jsonObject) {"Unexpected chat execution"}
                emit(CloudEvent.Text(ItineraryFixture.spec("Revised lake day")))
            }
        }
        CloudTestActivity.factory={ShellViewModel(it,responder,shell,keys).openSeededChat(shell).also {created -> model=created}}
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
            click("Открыть");shown("Выйти из полноэкранного режима")
            assertNull(find("Изменение мини-приложения"));assertNull(find("Содержимое мини-приложения"))
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
                if(find("Скрыть управление игрой")!=null)click("Скрыть управление игрой")
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
            click("Пауза");waitUntil {state().getInt("cancels")>0};touch(android.view.MotionEvent.ACTION_UP,0.5f)
            click("Продолжить")
            val previousWidth=state().getInt("width")
            scenario.onActivity {it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            waitUntil {state().getInt("width")!=previousWidth}
            waitUntil {canvas()?.let {it.getBoundsInScreen(bounds);bounds.width()>bounds.height()}==true}
            fullWindow()
            assertNull("Wide view must suppress the embedded app",find("Содержимое мини-приложения"))
            click("Управление мини-приложением");shown("Probe state")
            settled("Probe state")
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            shown("Выйти из полноэкранного режима")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            shown("Выйти из полноэкранного режима");screenshot("immersive-viewport")
            click("Выйти из полноэкранного режима");shown("Содержимое мини-приложения")
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
                if(a==='start')s.starts++;
                if(a==='start'||a==='resume'||a==='restart')s.mode='playing';
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
            val gameMenu = instrumentation.targetContext.getString(R.string.tool_game_controls_open)
            fun openGameMenu() = click(gameMenu)
            fun dismiss() {
                settled("Playback controls")
                assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                try {
                    shown(instrumentation.targetContext.getString(R.string.tool_full_screen_exit))
                } catch (failure: AssertionError) {
                    val sheet = find("Playback controls")?.isVisibleToUser == true
                    val gameMenu = find(instrumentation.targetContext.getString(R.string.tool_game_controls_open))?.isVisibleToUser == true
                    val panel = find(instrumentation.targetContext.getString(R.string.tool_game_controls))?.isVisibleToUser == true
                    throw AssertionError("Back left sheet=$sheet gameMenu=$gameMenu panel=$panel animatorEnabled=${android.animation.ValueAnimator.areAnimatorsEnabled()}", failure)
                }
            }
            fun controls() {
                click("Управление мини-приложением");shown("Playback controls")
                waitUntil {
                    var node=find("Generated pause")
                    while(node?.isClickable==false)node=node.parent
                    node?.isEnabled==true
                }
            }
            var heldDownTime=0L
            fun touch(action: Int?=null,fraction: Float=0.5f) {
                if(find("Скрыть управление игрой")!=null)click("Скрыть управление игрой")
                fun canvas(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(node.isVisibleToUser && node.contentDescription?.startsWith("Playback probe.")==true)return node
                    repeat(node.childCount) {canvas(node.getChild(it))?.let {found -> return found}}
                    return null
                }
                val bounds=Rect()
                waitUntil("The playback canvas did not return after closing the controls") {
                    val visibleCanvas = canvas(diagnostics.freshRoot()) ?: return@waitUntil false
                    visibleCanvas.getBoundsInScreen(bounds)
                    !bounds.isEmpty
                }
                if(action==null || action==android.view.MotionEvent.ACTION_DOWN)heldDownTime=SystemClock.uptimeMillis()
                for(phase in action?.let {listOf(it)} ?: listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                    val event=android.view.MotionEvent.obtain(heldDownTime,SystemClock.uptimeMillis(),phase,bounds.left+bounds.width()*fraction,bounds.exactCenterY(),0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try {assertTrue(injectTouch(event))}finally {event.recycle()}
                }
            }
            waitUntil("The chat canvas did not initialize in ready mode") {
                state().getString("mode")=="ready" && state().getInt("starts")==0
            }
            click("Открыть");shown(gameMenu);mode("ready");still()
            assertEquals("Opening the full view must preserve the initialized ready state",0,state().getInt("starts"))
            touch(android.view.MotionEvent.ACTION_DOWN,0.25f)
            waitUntil {state().getInt("downs")==1}
            openGameMenu()
            click("Начать");mode("playing");advances()
            assertEquals("Only the user's Start advances ready playback",1,state().getInt("starts"))
            touch(android.view.MotionEvent.ACTION_MOVE,0.75f)
            waitUntil("Ready-to-playing transition lost pointer ownership") {kotlin.math.abs(state().getDouble("pointerX")-240.0)<3.0}
            touch(android.view.MotionEvent.ACTION_UP,0.75f)
            click("Пауза");shown("Продолжить");mode("paused");still()
            click("Начать заново");mode("playing");shown("Пауза");advances()
            touch();waitUntil {state().getInt("downs")==2}
            openGameMenu()
            controls();mode("playing");still()
            click("Generated pause");mode("paused");dismiss();shown("Продолжить");still()
            controls();click("Generated resume");mode("playing")
            dismiss();shown("Пауза");advances()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            mode("playing");still()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            shown("Пауза");mode("playing");advances()
            assertEquals("Resuming the activity must not send Start",1,state().getInt("starts"))
            click("Пауза");mode("paused");shown("Продолжить")
            click("Выйти из полноэкранного режима");shown("Содержимое мини-приложения");shown("Продолжить");mode("paused");still()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            shown("Продолжить");mode("paused");still()
            assertEquals("Reopening paused playback must not send Start",1,state().getInt("starts"))
            screenshot("playback-saved-pause")
            println("PLAYBACK_NATIVE ready=PASS restart=PASS sheetActions=PASS suspension=PASS savedPause=PASS")
        }
    }
    private fun activeCardSpec(title: String, advance: Boolean, note: String) = JSONObject(ArkanoidFixture.spec())
        .put("title",title)
        .put("stateSchema",JSONObject("""{"ticks":"number","touches":"number","note":"string"}"""))
        .put("initialState",JSONObject().put("ticks",0).put("touches",0).put("note",note))
        .put("ui",JSONObject("""{"type":"text","text":"Saved note","bind":"note"}"""))
        .put("source","""
            const s=input.state,e=input.event;
            if($advance && e.type==='frame')s.ticks++;
            if(e.type==='pointer' && (e.phase==='down'||e.phase==='cancel'))s.touches++;
            return {state:s,playback:'playing',frames:['main'],surfaces:{main:{commands:[
                {type:'rect',x:0,y:0,width:320,height:420,color:'#123456'},
                {type:'text',x:12,y:24,size:16,text:'Ticks '+s.ticks,color:'#FFFFFF'}]}}};
        """.trimIndent()).toString()

    @Test fun liveCanvasResumesOnTheSameActivityWithTheLatestState() = fixture(
        specJson=activeCardSpec("Live canvas",true,"Resume data")
    ) {_,store,scenario ->
        val metrics=com.alpha.assistant.sandbox.SurfaceFrameMetrics
        fun state()=JSONObject(checkNotNull(store.tool("lake")).state)
        diagnostics.state={"state="+store.tool("lake")?.state+" metrics="+metrics.snapshot()?.status}
        waitUntil("The live chat canvas did not commit a frame") {state().getInt("ticks")>0}
        lateinit var activity: CloudTestActivity
        scenario.onActivity {activity=it}
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        waitUntil("The original canvas session did not close") {metrics.snapshot()?.status=="STOPPED"}
        val savedTicks=state().getInt("ticks")
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        scenario.onActivity {assertSame("Resume must use the same activity",activity,it)}
        waitUntil("The resumed chat canvas did not advance its saved state") {state().getInt("ticks")>savedTicks}
        assertEquals("Resume data",state().getString("note"))
        screenshot("live-canvas-same-activity-resume")
    }

    @Test fun versionHistoryRestoreQuiescesActiveCanvasAndKeepsCurrentData() = fixture(
        specJson=activeCardSpec("Current live canvas",true,"Current data"),
        previousJson=activeCardSpec("Earlier live canvas",false,"Earlier data")
    ) {_,store,_ ->
        fun state()=JSONObject(checkNotNull(store.tool("lake")).state)
        diagnostics.state={"state="+store.tool("lake")?.state+" revision="+store.tool("lake")?.revision}
        shown("Current live canvas")
        assertNull(find("v2"))
        assertNull(find("Вернуть v1"))
        waitUntil("The current canvas did not commit a frame") {state().getInt("ticks")>0}
        val ticks=state().getInt("ticks")
        click("Действия мини-приложения Current live canvas")
        click("Версии")
        click("Вернуть v1")
        waitUntil("Version history restore did not commit a new revision") {store.tool("lake")?.revision==3}
        assertTrue("Restore must keep canvas progress",state().getInt("ticks")>=ticks)
        assertEquals("Current data",state().getString("note"))
        assertEquals("Восстановление v1",store.revisions("lake").first().label)
        shown("Earlier live canvas")
        assertNull(find("v3"))
        screenshot("version-history-restore-live-canvas")
    }

    @Test fun restoreUsesTheFinalPauseFromEitherVisibleHost() {
        restoreAcrossVisibleHosts(conflict = false)
    }

    @Test fun restoreRejectsStateChangedAfterBothVisibleHostsPause() {
        restoreAcrossVisibleHosts(conflict = true)
    }

    // A display tick does not guarantee host order. Gate the real cancel until the newer host
    // acknowledges its pause, then let the same controller and store process the restore.
    private fun restoreAcrossVisibleHosts(conflict: Boolean) = fixture(
        specJson = activeCardSpec("Shared pause canvas", false, "Current data"),
        previousJson = activeCardSpec("Earlier canvas", false, "Earlier data"),
        showChat = false,
    ) { model, store, scenario ->
        val tool = checkNotNull(store.tool("lake"))
        val spec = ToolSpec.parsePersisted(tool.spec)
        val actions = MiniAppActions()
        val olderHost = model.tools.artifactHost(tool)
        val newerHost = model.tools.artifactHost(tool)
        val olderOpened = CompletableDeferred<Unit>()
        val newerOpened = AtomicReference<ArtifactSnapshot>()
        val ownerPaused = CompletableDeferred<Unit>()
        val ownerPauseSnapshot = AtomicReference<ArtifactSnapshot>()
        val ownerOnlyBoundary = AtomicReference<ToolRecord?>()
        val finalCancel = AtomicReference<ArtifactSnapshot>()
        val olderPauseSnapshot = AtomicReference<ArtifactSnapshot>()
        val boundaryAtConflict = AtomicReference<ToolRecord>()
        val externalWrite = AtomicReference<ToolRecord>()
        diagnostics.state = {
            val first = ownerPauseSnapshot.get()
            val last = olderPauseSnapshot.get()
            "ownerPause=${first?.sequence}/${first?.tool?.stateVersion} " +
                "olderPause=${last?.sequence}/${last?.tool?.stateVersion} " +
                "revision=${store.tool(tool.id)?.revision} error=${model.tools.error}"
        }
        val restoreLabel = instrumentation.targetContext.getString(R.string.tool_restore_version, 1)
        val older = object : ArtifactUiHost by olderHost {
            override suspend fun open() = olderHost.open().also { olderOpened.complete(Unit) }

            override suspend fun event(event: JSONObject): ArtifactSnapshot {
                if (event.optString("type") == "pointer" && event.optString("phase") == "cancel") {
                    ownerPaused.await()
                    return olderHost.event(event).also(finalCancel::set)
                }
                return olderHost.event(event)
            }
        }
        val newer = object : ArtifactUiHost by newerHost {
            override suspend fun open(): ArtifactSnapshot {
                olderOpened.await()
                return newerHost.open().also(newerOpened::set)
            }
        }
        scenario.onActivity { activity ->
            activity.setContentView(ComposeView(activity).apply {
                setContent {
                    AlphaTheme {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            val current = model.tools.tools.single()
                            Text("v${current.revision}")
                            if (current.revision == tool.revision) {
                                Row(Modifier.fillMaxWidth()) {
                                    var olderLatest: ArtifactSnapshot? = null
                                    var newerLatest: ArtifactSnapshot? = null
                                    Column(Modifier.weight(1f).semantics { contentDescription = "Older canvas host" }) {
                                        CoveredToolSurface(tool, spec, true, 120.dp, older, actions,
                                            onSnapshot = { olderLatest = it }, onCoverReady = { ready ->
                                                if (ready) {
                                                    val paused = checkNotNull(olderLatest)
                                                    olderPauseSnapshot.set(paused)
                                                    if (conflict) {
                                                        boundaryAtConflict.set(checkNotNull(actions.restoreSnapshot(tool)))
                                                        val next = paused.tool.copy(stateVersion = paused.tool.stateVersion + 1,
                                                            state = JSONObject(paused.tool.state).put("note", "Post-boundary data").toString())
                                                        store.commitTool(next, paused.tool, false)
                                                        externalWrite.set(next)
                                                    }
                                                }
                                            })
                                    }
                                    Column(Modifier.weight(1f).semantics { contentDescription = "Newer canvas host" }) {
                                        CoveredToolSurface(tool, spec, true, 120.dp, newer, actions,
                                            onSnapshot = { newerLatest = it }, onCoverReady = { ready ->
                                                if (ready) {
                                                    ownerPauseSnapshot.set(checkNotNull(newerLatest))
                                                    ownerOnlyBoundary.set(actions.restoreSnapshot(tool))
                                                    ownerPaused.complete(Unit)
                                                }
                                            })
                                    }
                                }
                                TextButton(onClick = { actions.requestRestore(tool, 1) }) { Text(restoreLabel) }
                            }
                            model.tools.error?.let { Text(it) }
                            MiniAppActionDialogs(model.tools, actions)
                        }
                    }
                }
            })
        }
        waitUntil("The second canvas did not acquire frame ownership") { newerOpened.get()?.frameOwner == true }
        shown("Newer canvas host")
        fun canvas(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.startsWith("Shared pause canvas.") == true) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(::canvas)?.let { return it } }
            return null
        }
        val bounds = Rect().also { checkNotNull(canvas(shown("Older canvas host"))).getBoundsInScreen(it) }
        val now = SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0)
        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(injectTouch(down)) } finally { down.recycle() }
        waitUntil("The older host did not save the initial pointer input") {
            JSONObject(checkNotNull(store.tool(tool.id)).state).getInt("touches") == 1
        }
        click(restoreLabel)
        waitUntil("Both visible hosts did not acknowledge their pauses") { olderPauseSnapshot.get() != null }
        val first = checkNotNull(ownerPauseSnapshot.get())
        val last = checkNotNull(olderPauseSnapshot.get())
        val cancel = checkNotNull(finalCancel.get())
        assertTrue(first.frameOwner)
        assertFalse(last.frameOwner)
        assertNull("Restore must wait for the older host to pause", ownerOnlyBoundary.get())
        assertTrue(last.sessionGeneration < first.sessionGeneration)
        assertTrue(last.sequence > first.sequence)
        assertTrue(last.tool.stateVersion > first.tool.stateVersion)
        assertEquals(cancel.tool, last.tool)
        assertEquals(1, JSONObject(first.tool.state).getInt("touches"))
        assertEquals(2, JSONObject(last.tool.state).getInt("touches"))
        if (conflict) {
            shown("Инструмент изменился. Открой текущую версию.")
            waitUntil { !model.tools.busy }
            assertEquals(last.tool, boundaryAtConflict.get())
            assertEquals(checkNotNull(externalWrite.get()), store.tool(tool.id))
            assertEquals(listOf(2, 1), store.revisions(tool.id).map { it.revision })
        } else {
            waitUntil("Restore did not finish") { !model.tools.busy && (store.tool(tool.id)?.revision == 3 || model.tools.error != null) }
            assertNull("Restore rejected the latest acknowledged state", model.tools.error)
            shown("v3")
            val restored = checkNotNull(store.tool(tool.id))
            assertEquals(last.tool.state, restored.state)
            assertEquals("Earlier canvas", ToolSpec.storedTitle(restored.spec))
            assertEquals("Восстановление v1", store.revisions(tool.id).first().label)
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
            val ids=definition.getJSONArray("surfaces")
            val movement=JSONArray().put(ids.getJSONObject(0).getString("id"))
            definition.put("hostControls",JSONObject().put("playback",true).put("movement",movement))
            fixture(specJson=definition.toString()) {_,store,scenario ->
                diagnostics.state={"replay=$file state="+store.tool("lake")?.state}
                fun state()=JSONObject(store.tool("lake")!!.state)
                fun canvas(node: AccessibilityNodeInfo?=diagnostics.freshRoot()): AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(node.contentDescription?.startsWith(definition.getString("title")+".")==true)return node
                    repeat(node.childCount) {canvas(node.getChild(it))?.let {return it}}
                    return null
                }
                click("Открыть");shown("Начать")
                assertEquals("$file waits for the user to start", "ready", state().getString("status"))
                click("Начать");shown("Пауза")
                val bounds=Rect()
                waitUntil {canvas()?.let {it.getBoundsInScreen(bounds);bounds.width()>0 && bounds.height()>0}==true}
                val window=Rect().also {checkNotNull(diagnostics.freshRoot()).getBoundsInScreen(it)}
                assertEquals("$file full-window canvas",window,bounds)
                for(orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                scenario.onActivity {it.requestedOrientation=orientation}
                waitUntil {canvas()?.let {it.getBoundsInScreen(bounds);(bounds.width()>bounds.height())==(orientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)}==true}
                val window=Rect().also {checkNotNull(diagnostics.freshRoot()).getBoundsInScreen(it)}
                assertEquals("$file orientation=$orientation full-window canvas",window,bounds)
                click("Начать заново");shown("Пауза")
                click("Пауза");shown("Продолжить")
                click("Влево");waitUntil {state().getDouble("paddle")<0.49}
                click("Вправо");waitUntil {kotlin.math.abs(state().getDouble("paddle")-0.5)<0.03}
                click("Управление мини-приложением")
                shown(definition.getJSONObject("ui").getJSONArray("children").getJSONObject(0).getString("text")+": "+state().getString("message"))
                waitUntil("App controls sheet did not settle") {offersExpand()}
                screenshot(file.removeSuffix(".json")+"-$orientation-app-controls")
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                shown("Продолжить")
                click("Скрыть управление игрой")
                shown("Показать управление игрой")
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
                click("Продолжить");shown("Пауза");click("Скрыть управление игрой")
                shown("Показать управление игрой")
                assertNull("Playback panel must retract during play",find("Скрыть управление игрой"))
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
                click("Пауза");shown("Продолжить")
                click("Начать заново");shown("Пауза")
                waitUntil {state().getInt("score")==0 && state().getInt("lives")==3}
                screenshot("$stem-controls-expanded")
                println("SAVED_GAME_TOUCH file=$file orientation=$orientation density=${instrumentation.targetContext.resources.displayMetrics.density} fontScale=${instrumentation.targetContext.resources.configuration.fontScale} beforePx=$before downPx=$down movePx=$moved fullWindow=PASS down=PASS move=PASS upRelease=PASS cancelRelease=PASS nativeControls=PASS restart=PASS")
                }
                click("Выйти из полноэкранного режима");shown("Содержимое мини-приложения")
            }
        }
    }
    @Test fun inlineDrawingAppearsBetweenNativeControls() {
        val spec = JSONObject(RequestGameplayExamples.orbitSpec())
        val old = spec.getJSONObject("ui").getJSONArray("children")
        val children = JSONArray()
            .put(JSONObject("""{"type":"text","text":"Before the scene"}"""))
            .put(JSONObject("""{"type":"canvas","text":"Illustration","surfaces":["orbit"]}"""))
        repeat(old.length()) { children.put(old.getJSONObject(it)) }
        spec.getJSONObject("ui").put("children", children)
        fixture(specJson = spec.toString()) { _, _, _ ->
            click("Открыть")
            val before = shown("Before the scene")
            fun findVisible(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isVisibleToUser && matches(node)) return node
                repeat(node.childCount) { findVisible(node.getChild(it), matches)?.let { found -> return found } }
                return null
            }
            val drawing = checkNotNull(findVisible(diagnostics.freshRoot()) {
                it.contentDescription?.startsWith("Illustration.") == true
            })
            val slider = checkNotNull(findVisible(diagnostics.freshRoot()) { it.className == "android.widget.SeekBar" })
            fun top(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen).top
            assertTrue(top(before) < top(drawing))
            assertTrue(top(drawing) < top(slider))
        }
    }

    /** Regression: a duplicate board or an implicit host control must never be needed to play. */
    @Test fun generatedBoardUsesDirectTouchAndOneGameStatus() {
        val request = RequestGameplayExamples.TIC_TAC_TOE
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                val result = if (request.generationStage == CloudGenerationStage.PLAN)
                    JSONObject("""{"wholeScreen":true,"language":"en"}""").put("scene", fixtureScene()).toString()
                else JSONObject(RequestGameplayExamples.ticTacToeSpec()).withFixtureDrawingBinding("board").toString()
                emit(CloudEvent.Text(result))
            }
        }
        val generated = kotlinx.coroutines.runBlocking {
            ToolGenerationService(provider, com.alpha.assistant.sandbox.SandboxClient(instrumentation.targetContext))
                .generate("offline", request, null, emptyList(), CloudSession(), "board-fixture")
        }
        fixture(specJson = generated.spec.providerDefinition()) { _, store, _ ->
            fun canvases(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
                if (node == null) return emptyList()
                if (node.contentDescription?.startsWith("Крестики-нолики.") == true) return listOf(node)
                return (0 until node.childCount).flatMap { canvases(node.getChild(it)) }
            }
            fun board() = JSONObject(checkNotNull(store.tool("lake")).state).getJSONArray("board")
            fun tap(column: Int, row: Int) {
                val bounds = Rect().also { canvases(diagnostics.freshRoot()).single().getBoundsInScreen(it) }
                val now = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                        bounds.left + bounds.width() * (column + 0.5f) / 3,
                        bounds.top + bounds.height() * (row + 0.5f) / 3, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(injectTouch(event)) } finally { event.recycle() }
                }
            }
            click("Открыть"); shown("Статус: Ваш ход")
            waitUntil { canvases(diagnostics.freshRoot()).size == 1 }
            for (label in listOf("Влево", "Вправо", "Игра идёт", "Пауза", "Начать заново")) {
                assertNull("Unexpected host control: $label", find(label))
            }
            assertEquals(1, canvases(diagnostics.freshRoot()).size)
            tap(1, 1)
            waitUntil { board().getString(4) == "X" }
            assertEquals("O", board().getString(0))
            assertEquals(2, (0..8).count { board().getString(it).isNotEmpty() })
            screenshot("direct-board-first-move")
            val beforeOccupied = board().toString()
            tap(0, 0)
            tap(0, 1)
            waitUntil { board().getString(3) == "X" }
            assertEquals(JSONArray(beforeOccupied).getString(0), board().getString(0))
            assertEquals(4, (0..8).count { board().getString(it).isNotEmpty() })
            tap(2, 1); shown("Статус: Вы победили")
            waitUntil { board().getString(5) == "X" }
            val terminal = board().toString()
            tap(2, 2)
            SystemClock.sleep(350)
            assertEquals(terminal, board().toString())
            click("Новая партия"); shown("Статус: Ваш ход")
            waitUntil { (0..8).all { board().getString(it).isEmpty() } }
            println("DIRECT_BOARD request=$request firstTap=PASS occupied=PASS terminal=PASS restart=PASS")
        }
    }

    @Test fun graphicsRetryWorksWithoutGameplayControls() {
        val definition = JSONObject(RequestGameplayExamples.ticTacToeSpec())
        definition.put("source", "if(input.event.type==='pointer')throw new Error('fixture');\n" + definition.getString("source"))
        fixture(specJson = definition.toString()) { _, store, _ ->
            click("Открыть"); shown("Статус: Ваш ход")
            val canvas = shown("Крестики-нолики. ")
            val bounds = Rect().also(canvas::getBoundsInScreen)
            val original = checkNotNull(store.tool("lake")).state
            val now = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(injectTouch(event)) } finally { event.recycle() }
            }
            click(instrumentation.targetContext.getString(R.string.retry))
            waitUntil { find(instrumentation.targetContext.getString(R.string.retry)) == null }
            shown("Крестики-нолики. ")
            assertEquals(original, checkNotNull(store.tool("lake")).state)
            assertNull(find("Начать заново"))
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
        click("Открыть")
        shown("Игра идёт")
        click("Пауза")
        shown("Игра на паузе")
        click("Вправо");paddle(156)
        val before=store.tool("lake")!!;assertTrue(before.stateVersion>=1)
        ToolSpec.parsePersisted(before.spec).validateState(before.state)
        assertNull("Protocol 3 saves validated state without a checkpoint button",find("Save score"))
        click("Влево");paddle(128)
        click("Продолжить");shown("Игра идёт")
        val node=checkNotNull(surface());node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        automation.waitForIdle(100,3000)
        val bounds=Rect().also {checkNotNull(surface()).getBoundsInScreen(it)}
        val now=SystemClock.uptimeMillis()
        val x=bounds.left+bounds.width()*0.25f;val y=bounds.exactCenterY()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0)
            try {assertTrue(injectTouch(event))}finally {event.recycle()}
        }
        paddle(48);click("Пауза");shown("Игра на паузе");screenshot("graphics-touch-saved")
        val saved=store.tool("lake")!!
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Thread.sleep(150)
        kotlinx.coroutines.runBlocking {
            val probe=com.alpha.assistant.sandbox.GraphicsSession(instrumentation.targetContext)
            try {assertEquals("Background must release the retained slot","OK",probe.open("return {state:input.state,surfaces:{main:{commands:[]}}};","{}").status)}finally {probe.close()}
        }
        assertEquals(saved.state,store.tool("lake")!!.state)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        shown("Игра идёт")
        paddle(128)
        click("Начать заново")
        paddle(128)
        assertEquals(0,JSONObject(store.tool("lake")!!.state).getInt("score"))
        assertEquals(3,JSONObject(store.tool("lake")!!.state).getInt("lives"))
        screenshot("graphics-resumed")
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitUntil {surface()==null}
        shown(instrumentation.targetContext.getString(R.string.tool_library_content))
        assertTrue(store.effects("lake").isEmpty())
        println("GRAPHICS_NATIVE touch=PASS durableState=PASS backgroundRelease=PASS back=PASS stateVersion=${saved.stateVersion}")
    }
    @Test fun graphicsLockReleasesSessionWithoutSavingFrames() = fixture(specJson=ArkanoidFixture.spec()) {model,store,scenario ->
        click("Открыть");shown("Игра идёт")
        click("Пауза");shown("Игра на паузе")
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
        click("Открыть")
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
            .put("hostControls", JSONObject("""{"playback":true,"movement":["front"]}"""))
            .put("source","""
                const s=input.state,e=input.event;
                if(e.type==='pointer') {
                    s.last=e.surfaceId;s.phase=e.phase;s.x=e.x;
                    if(e.phase==='down')s.downs++;
                    if(e.phase==='cancel')s.cancels++;
                }
                if(e.type==='control')s.last=e.surfaceId;
                if(e.type==='resume')s.resumed=true;
                if(e.type==='frame') {s.ticks++;if(s.resumed){s.firstDt=e.dt;s.resumed=false;}}
                return {state:s,frames:['back'],surfaces:{
                    back:{commands:[{type:'rect',x:0,y:0,width:320,height:240,color:'#203040'}]},
                    front:{commands:[{type:'path',segments:[{type:'move',x:0,y:0},{type:'line',x:160,y:0},{type:'line',x:160,y:120},{type:'line',x:0,y:120},{type:'close'}],gradient:{type:'linear',x:0,y:0,x2:160,y2:120,stops:[{offset:0,color:'#AA3300'},{offset:1,color:'#FFAA55'}]}}]}
                }};
            """.trimIndent())
        fixture(specJson=definition.toString()) {_,store,_ ->
            click("Открыть");shown("Игра идёт")
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
            click("Пауза")
            waitUntil {state().getInt("cancels")==1}
            touch(android.view.MotionEvent.ACTION_UP,10f,10f)
            shown("Игра на паузе");screenshot("positioned-surfaces-paused")
            click("Влево");waitUntil { state().getString("last") == "front" }
            click("Продолжить");shown("Игра идёт")
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
            click("Открыть"); shown("Turns"); shown("0"); screenshot("compose-families-initial")
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
            edit("Row amount","4"); click("Применить: Row amount")
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

    @Test fun liveChatCardSharesStateWithFullViewAndAnotherChat() {
        val definition=JSONObject(ItineraryFixture.spec())
            .put("stateSchema",JSONObject("""{"packed":"boolean","count":"number"}"""))
            .put("initialState",JSONObject("""{"packed":false,"count":0}"""))
            .put("ui",JSONObject("""{"type":"column","children":[
                {"type":"checkbox","text":"Bag packed","bind":"packed","action":"pack"},
                {"type":"button","text":"Add a stop","action":"add"},
                {"type":"text","text":"Stops","bind":"count"}
            ]}"""))
            .put("source","""
                if(input.event.actionId==='pack') input.state.packed=input.event.value;
                if(input.event.actionId==='add') input.state.count++;
                return {state:input.state};
            """.trimIndent())
        fixture(specJson=definition.toString(),secondReference=true) {model,store,scenario ->
            val robot=ShellRobot("presentation")
            fun state()=JSONObject(checkNotNull(store.tool("lake")).state)
            val app = instrumentation.targetContext
            shown("Lake day")
            val record = checkNotNull(store.tool("lake"))
            val versions = app.getString(R.string.tool_details_versions, record.revision, record.stateVersion)
            assertNull(find(versions))
            click(app.getString(R.string.tool_card_actions, "Lake day"))
            click(app.getString(R.string.tool_versions))
            shown(versions)
            click(app.getString(R.string.tool_dialog_close))
            click("Bag packed")
            waitUntil {state().getBoolean("packed")}
            click("Add a stop"); waitUntil {state().getInt("count")==1}
            shown("Stops: 1"); screenshot("live-chat-card")
            val composer=instrumentation.targetContext.getString(R.string.composer_label)
            edit(composer,"Keep my ordinary draft")
            click("Открыть")
            shown("Содержимое мини-приложения"); shown("Stops: 1")
            click("Add a stop"); waitUntil {state().getInt("count")==2}
            click("Изменить")
            waitUntil {model.snapshot.selected?.refinementTargetId == "lake" && model.snapshot.draft.text.isEmpty()}
            shown(composer)
            assertEquals("Keep my ordinary draft",model.snapshot.selected?.draft?.text)
            shown("Stops: 2")
            robot.openDrawer(scenario); click("Second discussion"); robot.awaitDrawerClosed(scenario)
            waitUntil {model.snapshot.selectedId == "other"}; shown("Stops: 2")
            click("Add a stop"); waitUntil {state().getInt("count")==3}
            scenario.recreate()
            waitUntil {model.snapshot.selectedId == "other"}; shown("Stops: 3")
            click("Открыть"); shown("Содержимое мини-приложения"); shown("Stops: 3")
            assertTrue(state().getBoolean("packed"))
            screenshot("shared-live-card-reopened")
        }
    }
    @Test fun embeddedDialogsKeepChatLibraryAndDraftControlsAvailable() {
        val definition=JSONObject(ItineraryFixture.spec())
            .put("stateSchema",JSONObject("""{"dialog":"boolean"}"""))
            .put("initialState",JSONObject("""{"dialog":true}"""))
            .put("ui",JSONObject("""{"type":"column","children":[
                {"type":"dialog","text":"Review embedded choice","visibleBind":"dialog","dismissAction":"dismiss",
                 "children":[{"type":"text","text":"The preview keeps the host controls available."}]}
            ]}"""))
            .put("source","if(input.event.actionId==='dismiss') input.state.dialog=false; return {state:input.state};")
            .toString()
        fixture(candidate=true,specJson=definition,candidateJson=definition,repeatInChat=true) {model,store,scenario ->
            val robot=ShellRobot("presentation")
            val app=instrumentation.targetContext
            shown("Review embedded choice")
            shown("Закрыть: Review embedded choice")
            shown(app.getString(R.string.composer_label))
            robot.openDrawer(scenario);click(app.getString(R.string.mini_apps));robot.awaitDrawerClosed(scenario)
            shown("Lake day")
            assertNull("Thumbnail contents are absent from accessibility",find("Review embedded choice"))
            click("Make the day shorter")
            shown("Предпросмотр · управление станет доступно после сохранения")
            shown("Применить изменения");screenshot("embedded-dialog-preview")
            click("Удалить предпросмотр")
            click("Закрыть: Review embedded choice")
            waitUntil { !JSONObject(checkNotNull(store.tool("lake")).state).getBoolean("dialog") && !model.tools.busy }
        }
    }
    @Test fun composerQueuesWhileBusyAndCancelsTheSelectedRequest() {
        val calls=AtomicInteger()
        val provider=object : ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                calls.incrementAndGet()
                awaitCancellation()
            }
        }
        fixture(provider=provider) { model,_,_ ->
            val app=instrumentation.targetContext
            val composer=app.getString(R.string.composer_label)
            val send=app.getString(R.string.composer_send)
            fun status()=model.snapshot.selected!!.messages.filter { it.replyTo != null }.map { it.status }
            edit(composer,"Hold first request");click(send)
            waitUntil {calls.get()==1 && status()==listOf("streaming")}
            edit(composer,"Queue second request");click(send)
            waitUntil {status()==listOf("streaming","queued")}
            shown(app.getString(R.string.chat_request_queued))
            click(app.getString(R.string.chat_cancel_queued))
            waitUntil {status()==listOf("streaming","cancelled")}
            assertEquals(1,calls.get())
            click(app.getString(R.string.composer_stop))
            waitUntil {status()==listOf("cancelled","cancelled") && model.activeConversationId==null}
            assertEquals(1,calls.get())
        }
    }
    @Test fun quickBackAfterChatMenuLeavesTheChat() = fixture {model,_,scenario ->
        val robot=ShellRobot("presentation")
        instrumentation.runOnMainSync {model.openSurface("lake")}
        waitUntil {!model.tools.busy}
        shown("Содержимое мини-приложения")
        // Only the Chat entry shows this message. The push fade to MiniApps ends first.
        robot.waitUntil {!robot.composedText(scenario,"Your day plan is ready.")}
        click("Действия мини-приложения")
        val chatLabel = robot.text(R.string.tool_chat)
        var chat: AccessibilityNodeInfo?=shown(chatLabel)
        while(chat?.isClickable == false) chat=chat.parent
        // No idle wait, so the pop fade still runs at the Back.
        assertTrue("Click Chat",diagnostics.click(chatLabel,chat))
        robot.waitUntil {robot.composedText(scenario,"Your day plan is ready.")}
        // The closing menu window gets a Back while it has the window focus.
        waitUntil { var focus = false; scenario.onActivity { focus = it.window.decorView.hasWindowFocus() }; focus }
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        robot.waitUntil {model.snapshot.selectedId == null}
    }
    @Test fun candidateIsExplicitAndDiscardReturnsToSavedApp() = fixture(true) {model,store,_ ->
        instrumentation.runOnMainSync {model.tools.selectDraft("candidate"); model.openSurface("lake")}
        waitUntil {!model.tools.busy}
        instrumentation.runOnMainSync {model.tools.selectDraft("candidate")}
        shown("Предпросмотр · управление станет доступно после сохранения"); shown("Применить изменения"); shown("Удалить предпросмотр")
        screenshot("candidate-preview")
        assertEquals(1,store.tool("lake")!!.revision)
        click("Удалить предпросмотр")
        shown("Lake day"); shown("Изменить")
        assertNull(find("Опишите изменения"))
        assertEquals("lake",model.tools.selectedId); assertNull(store.draft("candidate"))
        assertEquals(1,store.tool("lake")!!.revision)
    }
    @Test fun applyChangesUsesExistingGuardedCommit() = fixture(true) {model,store,_ ->
        instrumentation.runOnMainSync {model.openSurface("lake")}
        waitUntil {!model.tools.busy}
        instrumentation.runOnMainSync {model.tools.selectDraft("candidate")}
        click("Применить изменения")
        waitUntil {!model.tools.busy && store.tool("lake")!!.revision == 2}
        shown("Revised lake day"); shown("Изменить")
        assertNull(find("Опишите изменения"))
        assertNull(store.draft("candidate"))
        screenshot("accepted-refinement")
    }
    @Test fun cancelThenErrorThenRetryPreservesAppAndPendingDraft() {
        val calls=AtomicInteger()
        val provider=object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                check(request.jsonObject)
                if (request.replyWithFixturePlan(emit)) return
                when(calls.incrementAndGet()) {
                    1 -> awaitCancellation()
                    2 -> throw CloudFailure("fixture_failure","Offline refinement fixture")
                    else -> emit(CloudEvent.Text(ItineraryFixture.spec("Recovered lake day")))
                }
            }
        }
        val prompt = "Keep the lake walk"
        fixture(provider=provider, pendingPrompt=prompt) {model,store,_ ->
            click("Открыть")
            val original=store.tool("lake")!!
            click(instrumentation.targetContext.getString(R.string.tool_library_back))
            click(prompt)
            shown(prompt)
            assertNull(find("Опишите изменения"))
            click("Повторить предпросмотр")
            waitUntil {calls.get() == 1}
            shown("Lake day"); click(instrumentation.targetContext.getString(R.string.cancel))
            waitUntil {!model.tools.busy}
            assertEquals(original,store.tool("lake")); assertEquals("lake",model.tools.selectedId)
            shown(prompt); shown("Повторить предпросмотр")
            assertEquals(prompt,store.draft("candidate")?.prompt)
            screenshot("cancel-preserves-app")
            click("Повторить предпросмотр")
            waitUntil {!model.tools.busy && model.tools.error != null}
            shown("Offline refinement fixture"); shown(prompt)
            assertEquals(original,store.tool("lake"))
            screenshot("refinement-error")
            click("Повторить предпросмотр")
            shown("Предпросмотр · управление станет доступно после сохранения"); shown("Recovered lake day")
            assertEquals(original,store.tool("lake")); assertEquals(3,calls.get())
            click("Удалить предпросмотр")
            shown("Lake day")
            assertNull(store.draft("candidate"))
            assertEquals(original,store.tool("lake"))
        }
    }
    @Test fun concurrentStateChangeRejectsStaleApplyWithoutDataLoss() = fixture(true) {model,store,_ ->
        instrumentation.runOnMainSync {model.openSurface("lake")}
        waitUntil {!model.tools.busy}
        instrumentation.runOnMainSync {model.tools.selectDraft("candidate")}
        shown("Применить изменения")
        val old=store.tool("lake")!!
        val nextState=JSONObject(old.state).apply {getJSONArray("stops").getJSONObject(0).put("detail","Concurrent saved edit")}
        val changed=old.copy(stateVersion=old.stateVersion+1,state=nextState.toString())
        store.commitTool(changed,old,false)
        click("Применить изменения")
        waitUntil {!model.tools.busy && model.tools.error != null}
        shown("Инструмент изменился. Открой текущую версию.")
        assertEquals(changed,store.tool("lake")); assertNotNull(store.draft("candidate"))
        shown("Применить изменения"); shown("Удалить предпросмотр")
        screenshot("stale-apply-rejected")
        click("Удалить предпросмотр")
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
        val launches = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                val map = intent.action == Intent.ACTION_VIEW && intent.scheme == "geo"
                val calendar = intent.action == Intent.ACTION_INSERT && intent.data == CalendarContract.Events.CONTENT_URI
                if (!map && !calendar) return null
                launches.add(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            fixture(specJson=definition.toString()) {model,store,_ ->
                click("Открыть")
                click("Search maps")
                waitUntil {store.effects("lake").size == 1 && !model.tools.busy}
                val map=store.effects("lake").single()
                assertEquals("maps.open",map.request.capability)
                assertEquals("Stop 1",map.request.args.getString("query"))
                assertEquals("launched",map.result.status)
                assertEquals("geo:0,0?q=Stop%201", launches.single().dataString)
                click("Open calendar form")
                waitUntil {store.effects("lake").size == 2 && !model.tools.busy}
                val calendar=store.effects("lake").last()
                assertEquals("calendar.create",calendar.request.capability)
                assertEquals("Stop 1",calendar.request.args.getString("title"))
                assertEquals("Walk by the lake for 20 minutes",calendar.request.args.getString("location"))
                assertEquals(1800000000000,calendar.request.args.getLong("start"))
                assertNotEquals(map.key,calendar.key)
                assertEquals("launched",calendar.result.status)
                assertEquals(2, launches.size)
                val calendarIntent = launches.last()
                assertEquals("Stop 1", calendarIntent.getStringExtra(CalendarContract.Events.TITLE))
                assertEquals(1800000000000L, calendarIntent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
                assertEquals(1,store.tool("lake")!!.revision)
                screenshot("distinct-stop-requests")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

}
