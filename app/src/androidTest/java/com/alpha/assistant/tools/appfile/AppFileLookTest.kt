package com.alpha.assistant.tools.appfile

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.removeWithModel
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** The fixtures of kit/build.js LOOK_FIXTURES with the language of each app. */
private val LOOK_FIXTURES=mapOf("water-ko" to "ko","units-pl" to "pl","shopping" to "en","habits" to "en","price-filter" to "en",
    "look-water-ko" to "ko","look-shopping-it" to "it")

/** Each compiled plan as a saved app in the real shell: its pictures in the light and the dark theme, the host's words in the language of the app, a real tap on a row control, and the host's own timer loop of a drawn app. */
@RunWith(AndroidJUnit4::class)
class AppFileLookTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()

    /** The compiled apps saved in the tool store of a fresh shell, by their ids. The block runs while the shell is open, and the shell makes no model call. */
    private fun inShell(prefix: String,apps: Map<String,ToolSpec>,block: (ShellRobot,ShellViewModel,ToolStore,ActivityScenario<CloudTestActivity>)->Unit) {
        val fixture=ShellFixture(prefix)
        val robot=ShellRobot(fixture.name)
        try {
            ToolStore(fixture.context,"${fixture.name}.db-tools.db").use {store ->
                for((id,spec) in apps) store.saveTool(ToolRecord(id,1,1,spec.json,spec.initialState,spec.ui.toString(),null,true),revision=true)
                fixture.install()
                fixture.launch().use {scenario ->
                    val model=checkNotNull(fixture.model)
                    robot.waitUntil {!model.loading && !model.tools.busy}
                    block(robot,model,store,scenario)
                    assertEquals(0,fixture.provider.calls)
                }
            }
        } finally {fixture.removeWithModel()}
    }
    /** Opens a saved app, and waits for the refine action of the open app, the title of the app, and the reply to its open event. */
    private fun open(robot: ShellRobot,model: ShellViewModel,id: String,spec: ToolSpec) {
        instrumentation.runOnMainSync {model.openSurface(id)}
        robot.waitFor(robot.text(R.string.tool_refine))
        robot.waitFor(spec.title)
        robot.waitUntil {!model.tools.busy}
        assertNull(spec.title,model.tools.error)
    }
    private fun state(store: ToolStore,id: String)=JSONObject(checkNotNull(store.tool(id)).state)

    @Test fun picturesOfEachPlan() {
        val sandbox=SandboxClient(instrumentation.targetContext)
        val specs=runBlocking {(SAVED_PLANS+LOOK_FIXTURES).mapValues {(name,language) -> parsedAndSmoked(sandbox,name,language)}}
        val folder=File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),"appfile-look")
        if(folder.exists()) check(folder.deleteRecursively())
        val shell=ShellRobot("appfile-look")
        val night=shell.shell("cmd uimode night").substringAfterLast(": ")
        try {
            for(dark in listOf(false,true)) {
                shell.shell("cmd uimode night ${if(dark) "yes" else "no"}")
                val directory=File(folder,if(dark) "dark" else "light").apply {check(mkdirs())}
                inShell("appfile-look",specs.mapKeys {"appfile-${it.key}"}) {robot,model,_,scenario ->
                    robot.waitUntil {nightOf(scenario) == dark}
                    for((name,spec) in specs) {
                        open(robot,model,"appfile-$name",spec)
                        // A plan with a surface shows its drawing: the host drew its first frame.
                        assertEquals(name,spec.surfaces.isNotEmpty(),canvasTexts(spec.ui).isNotEmpty())
                        canvasTexts(spec.ui).forEach {text -> robot.waitUntil {drawn(robot,text)}}
                        val picture=File(directory,"$name.png")
                        val screen=stillScreen(robot)
                        try {picture.outputStream().use {assertTrue(screen.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {screen.recycle()}
                        assertTrue("$picture ${picture.length()} bytes",picture.isFile && picture.length()>10_000)
                        robot.back()
                        robot.waitGone(robot.text(R.string.tool_refine))
                    }
                }
            }
        } finally {
            if(night in setOf("auto","yes","no","custom")) shell.shell("cmd uimode night $night")
        }
    }
    private fun nightOf(scenario: ActivityScenario<CloudTestActivity>): Boolean {
        var night=false
        scenario.onActivity {night=it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES}
        return night
    }
    /** The text of each canvas node of a ui. */
    private fun canvasTexts(node: JSONObject): List<String> =(if(node.optString("type") == "canvas") listOf(node.getString("text")) else emptyList())+
        (node.optJSONArray("children")?.let {children -> List(children.length()) {canvasTexts(children.getJSONObject(it))}.flatten()} ?: emptyList())
    /** The node whose description starts with prefix: a canvas describes itself as its text, a full stop, and the texts of its drawing. */
    private fun described(node: AccessibilityNodeInfo?,prefix: String): AccessibilityNodeInfo? =when {
        node == null -> null
        node.contentDescription?.startsWith(prefix) == true -> node
        else -> (0 until node.childCount).firstNotNullOfOrNull {described(node.getChild(it),prefix)}
    }
    /**
     * True when the canvas with this text is whole on the screen and its inside holds more than one color: before its first frame the canvas
     * is one color. The inside leaves out 16 dp at each edge, where the clip of the rounded corners shows the color behind the canvas.
     */
    private fun drawn(robot: ShellRobot,text: String): Boolean {
        val canvas=described(robot.diagnostics.freshRoot(),"$text.")?.takeIf {it.isVisibleToUser} ?: return false
        val bounds=Rect().also(canvas::getBoundsInScreen)
        val edge=(16*instrumentation.targetContext.resources.displayMetrics.density).toInt()
        val inside=Rect(bounds).apply {inset(edge,edge)}
        val screen=checkNotNull(robot.automation.takeScreenshot())
        try {
            if(inside.isEmpty || !Rect(0,0,screen.width,screen.height).contains(bounds)) return false
            val pixels=IntArray(inside.width()*inside.height())
            screen.getPixels(pixels,0,inside.width(),inside.left,inside.top,inside.width(),inside.height())
            return pixels.any {it != pixels[0]}
        } finally {screen.recycle()}
    }
    /**
     * A screenshot of a screen that holds still: two screenshots in turn are the same, with frames between them.
     * A main thread that draws no frame also gives two same screenshots, in the middle of a change of the screen.
     */
    private fun stillScreen(robot: ShellRobot): Bitmap {
        var last: Bitmap?=null
        robot.waitUntil {
            robot.awaitFrames(3)
            val next=checkNotNull(robot.automation.takeScreenshot())
            val same=last?.sameAs(next) == true
            last?.recycle()
            last=next
            same
        }
        return checkNotNull(last)
    }

    @Test fun theHostWordsOfAnAppAreInTheLanguageOfTheApp() {
        val spec=runBlocking {parsedAndSmoked(SandboxClient(instrumentation.targetContext),"tea","tr")}
        val context=instrumentation.targetContext
        fun word(tag: String,resource: Int)=context.createConfigurationContext(Configuration(context.resources.configuration).apply {setLocale(Locale.forLanguageTag(tag))}).getString(resource)
        // The host's own Russian word, and the word of the other language that miniAppString takes in Turkish.
        val russian=word("ru",R.string.foreground_timer_start)
        val turkish=word("tr",R.string.foreground_timer_start_en)
        assertNotEquals(russian,turkish)
        inShell("appfile-language",mapOf("appfile-tea" to spec)) {robot,model,_,_ ->
            open(robot,model,"appfile-tea",spec)
            robot.waitFor(turkish)
            assertNull(russian,robot.find(russian))
        }
    }

    @Test fun aTapOnARowControlChangesTheRow() {
        val spec=runBlocking {parsedAndSmoked(SandboxClient(instrumentation.targetContext),"list","it")}
        val id="appfile-list"
        inShell("appfile-row",mapOf(id to spec)) {robot,model,store,_ ->
            open(robot,model,id,spec)
            fun preso()=state(store,id).getJSONArray("prodotti").let {rows -> List(rows.length()) {rows.getJSONObject(it)}.associate {it.getString("id") to it.getBoolean("preso")}}
            fun shown()=checkboxes(robot.diagnostics.freshRoot()).map {it.isChecked}
            // The screen shows prodotti_list, sorted by corsia: Pane (r2), Mele (r3), Latte (r1).
            assertEquals(listOf("r2","r3","r1"),state(store,id).getJSONArray("prodotti_list").let {rows -> List(rows.length()) {rows.getJSONObject(it).getString("id")}})
            assertEquals(listOf(false,false,false),shown())
            robot.waitFor("3")
            val first=checkboxes(robot.diagnostics.freshRoot()).first()
            assertTrue(first.isEnabled && first.isVisibleToUser)
            assertTrue(first.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            robot.waitUntil {preso() == mapOf("r1" to false,"r2" to true,"r3" to false)}
            assertEquals("2",state(store,id).getJSONObject("d").getString("daComprare_text"))
            robot.waitUntil {shown() == listOf(true,false,false)}
            robot.waitFor("2")
        }
    }
    private fun checkboxes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =when {
        node == null -> emptyList()
        node.isCheckable -> listOf(node)
        else -> (0 until node.childCount).flatMap {checkboxes(node.getChild(it))}
    }

    @Test fun theLapButtonMovesTheHandInTheDrawnApp() {
        val spec=runBlocking {parsedAndSmoked(SandboxClient(instrumentation.targetContext),"lesson","ru")}
        val id="appfile-lesson"
        inShell("appfile-lap",mapOf(id to spec)) {robot,model,store,_ ->
            open(robot,model,id,spec)
            fun angle()=state(store,id).getDouble("angle")
            fun end()=state(store,id).getJSONObject("anim").getJSONObject("angle").getLong("end")
            assertEquals(40.0 to 0L,angle() to end())
            // lap_button sets the angle to 0 and animates it to 360 in 5 s. Alphy's own timer loop sends each anim event.
            robot.reveal("Один круг")
            robot.click("Один круг")
            robot.waitUntil(10_000) {end()>0}
            robot.waitUntil(10_000) {angle() == 360.0 && end() == 0L}
        }
    }
}
