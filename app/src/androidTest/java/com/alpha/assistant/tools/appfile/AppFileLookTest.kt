package com.alpha.assistant.tools.appfile

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.AppLanguage
import com.alpha.assistant.tools.GenerationLanguage
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.agent.AppAgent
import com.alpha.assistant.tools.agent.AppAgentRequest
import com.alpha.assistant.tools.removeWithModel
import com.alpha.assistant.tools.writeAppReply
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** The fixtures of kit/build.js LOOK_FIXTURES with the language of each app. */
private val LOOK_FIXTURES=mapOf("water-ko" to "ko","units-pl" to "pl","shopping" to "en","habits" to "en","price-filter" to "en",
    "look-water-ko" to "ko","look-shopping-it" to "it","mock-water-ko" to "ko","mock-units-pl" to "pl","mock-shopping-it" to "it","mock-habits-uk" to "uk",
    "mock-price-filter" to "en","look-deck-es" to "es","look-guide-es" to "es")
/** The apps that the model of the app wrote for the five requests of the mock plans (final-30d43aa): each spec, saved as an app of the same host, with no kit. */
private val BASELINES=listOf("showcase-lang-water-ko","showcase-lang-units-pl","showcase-lang-shopping-it","showcase-lang-habits-uk","showcase-ui-price-filter")

/**
 * Each compiled plan and each baseline app as a saved app in the real shell: its pictures in the light and the dark theme, the host's words in the language of the app,
 * a real tap on a row control, the host's own timer loop of a drawn app, the steps to add a row in the baseline list and in the kit list, a live slider, and a second live field.
 * The plans that a model wrote go through the whole device path, with the checks of AppAgent.validate and a picture in each theme.
 */
@RunWith(AndroidJUnit4::class)
class AppFileLookTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()

    /** The compiled apps saved in the tool store of a fresh shell, by their ids. The block runs while the shell is open, and the shell makes no model call. */
    private fun inShell(prefix: String,apps: Map<String,ToolSpec>,block: (ShellRobot,ShellViewModel,ToolStore,ActivityScenario<CloudTestActivity>)->Unit)=
        inShell(prefix,{store -> for((id,spec) in apps) save(store,id,spec)},block)
    /** A compiled app saved as a saved app of the tool store with a state. */
    private fun save(store: ToolStore,id: String,spec: ToolSpec,state: String=spec.initialState)=
        store.saveTool(ToolRecord(id,1,1,spec.json,state,spec.ui.toString(),null,true),revision=true)
    /** A fresh shell whose tool store holds what save wrote before the shell starts. */
    private fun inShell(prefix: String,save: (ToolStore)->Unit,block: (ShellRobot,ShellViewModel,ToolStore,ActivityScenario<CloudTestActivity>)->Unit) {
        val fixture=ShellFixture(prefix)
        val robot=ShellRobot(fixture.name)
        try {
            ToolStore(fixture.context,"${fixture.name}.db-tools.db").use {store ->
                save(store)
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
    /**
     * Waits until an action of the screen carries the state version of the last live value: the shell model publishes the stored version,
     * and two frames compose it. The host refuses an action with an older version ("The mini-app data changed. Reopen it."), and its
     * finish button goes before that publication (Task 11b-2b, second round).
     */
    private fun published(robot: ShellRobot,model: ShellViewModel,store: ToolStore,id: String) {
        robot.waitUntil {model.tools.tools.find {it.id == id}?.stateVersion == checkNotNull(store.tool(id)).stateVersion}
        robot.awaitFrames(2)
    }
    /** A string of the host in the resources of a language. */
    private fun word(tag: String,resource: Int,vararg args: Any)=instrumentation.targetContext.let {context ->
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {setLocale(Locale.forLanguageTag(tag))}).getString(resource,*args)}

    @Test fun picturesOfEachPlan() {
        val sandbox=SandboxClient(instrumentation.targetContext)
        val compiled=runBlocking {(SAVED_PLANS+LOOK_FIXTURES).mapValues {(name,language) -> parsedAndSmoked(sandbox,name,language)}}
        val specs=compiled+BASELINES.associateWith(::baseline)
        inEachTheme("appfile-look") {dark,directory ->
            inShell("appfile-look",specs.mapKeys {"appfile-${it.key}"}) {robot,model,store,scenario ->
                robot.waitUntil {nightOf(scenario) == dark}
                for((name,spec) in specs) {
                    open(robot,model,"appfile-$name",spec)
                    // A plan with a surface shows its drawing: the host drew its first frame.
                    assertEquals(name,spec.surfaces.isNotEmpty(),canvasTexts(spec.ui).isNotEmpty())
                    canvasTexts(spec.ui).forEach {text -> robot.waitUntil {drawn(robot,text)}}
                    val picture=File(directory,"$name.png")
                    picture(robot,picture)
                    assertTrue("$picture ${picture.length()} bytes",picture.isFile && picture.length()>10_000)
                    // The shopping list starts empty: one more picture after a person adds two rows through the screen.
                    if(name == "mock-shopping-it") added(robot,model,store,scenario,"appfile-$name",File(directory,"$name-added.png"))
                    // The deck and the question change after their main action: one more picture of the back of the card and of the notice of the answer.
                    if(name == "look-deck-es") pressed(robot,"Ver traducción","the apple",File(directory,"$name-shown.png"))
                    if(name == "look-guide-es") pressed(robot,"92–96 °C","¡Correcto! Así el café no se quema.",File(directory,"$name-answered.png"))
                    robot.back()
                    robot.waitGone(robot.text(R.string.tool_refine))
                }
            }
        }
    }
    /**
     * The block in the light theme, then in the dark theme. Each theme gets a new directory light or dark in the new folder folderName of the
     * external files. After the block, the night mode of the device is as before.
     */
    private fun inEachTheme(folderName: String,block: (dark: Boolean,directory: File)->Unit) {
        val folder=File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),folderName)
        if(folder.exists()) check(folder.deleteRecursively())
        val shell=ShellRobot(folderName)
        val night=shell.shell("cmd uimode night").substringAfterLast(": ")
        try {
            for(dark in listOf(false,true)) {
                shell.shell("cmd uimode night ${if(dark) "yes" else "no"}")
                block(dark,File(folder,if(dark) "dark" else "light").apply {check(mkdirs())})
            }
        } finally {
            if(night in setOf("auto","yes","no","custom")) shell.shell("cmd uimode night $night")
        }
    }

    /** The plans that a model wrote (kit/test/fixtures/model), as kit/build.js copies them to appfile/model, by their ids. */
    private fun modelPlans()=checkNotNull(instrumentation.context.assets.list("appfile/model")).filter {it.endsWith(".plan")}.map {it.removeSuffix(".plan")}.sorted()
    /** e-bbq-pt starts with no row: a copy of it with the rows of three people that the agent step checker adds through its add form. */
    private val BBQ_ROWS=JSONArray(listOf("Ana" to 60,"Bruno" to 25,"Carla" to 0).flatMap {(name,paid) ->
        listOf(JSONObject().put("set","pessoas_new_nome").put("value",name),JSONObject().put("set","pessoas_new_pago").put("value",paid),JSONObject().put("tap","pessoas_add"))})
    /**
     * The action ids of the smoke step whose events changed the state, those whose events never did, and the preview of the first screen.
     * checkUi gets the reply of an event before observeReplay gets the events of its branch, and each branch starts from the start state of the spec.
     */
    private suspend fun smokedControls(sandbox: SandboxClient,spec: ToolSpec,name: String): Triple<Set<String>,Set<String>,ToolPreview> {
        var before=spec.initialState
        var after=spec.initialState
        val changed=sortedSetOf<String>()
        val all=sortedSetOf<String>()
        val preview=smoked(sandbox,spec,name,spec.initialState,observeReplay={events ->
            if(events.size == 1) before=spec.initialState
            val event=JSONObject(events.last())
            if(event.getString("type") in setOf("action",SandboxContract.LIVE_INPUT_EVENT)) {
                all+=event.getString("actionId")
                if(after != before) changed+=event.getString("actionId")
            }
            before=after
        },checkUi={after=it.state})
        return Triple(changed,all-changed,preview)
    }
    /**
     * The host's reply to one write_app of a compiled definition in a new app of the agent loop: AppAgent.validate runs all its checks in its order.
     * presentationProblem is private (AppAgent.kt line 767), so this is the only path to it from a test. A scripted model writes the definition,
     * and its second call ends the run. language null writes the definition without a language, so validate skips its language check.
     */
    private suspend fun written(sandbox: SandboxClient,definition: String,language: String?,request: String,id: String): String {
        class Stop: Exception()
        var reply: String?=null
        val app=JSONObject(definition).apply {if(language == null) remove("language") else put("language",language)}
        val agent=AppAgent(ToolGenerationService(AgentScriptProvider(),sandbox),sandbox) {turns,_ ->
            if(turns.size == 1) writeAppReply(app) else {reply=turns.last().text;throw Stop()}
        }
        try {agent.run(AppAgentRequest(request,null,emptyList(),id,privateState=false),CloudSession())} catch(_: Stop) {}
        return checkNotNull(reply).substringBefore("\n")
    }
    /** A picture of the screen when it holds still, else a picture of the screen as it is. True when the screen held still. */
    private fun snapshot(robot: ShellRobot,file: File): Boolean {
        if(runCatching {picture(robot,file)}.isSuccess) return true
        val screen=checkNotNull(robot.automation.takeScreenshot())
        try {file.outputStream().use {assertTrue(screen.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {screen.recycle()}
        return false
    }

    /**
     * The device path of each plan that a model wrote (appfile/model), as parsedAndSmoked and picturesOfEachPlan take a saved plan: the compile in
     * the sandbox, ToolSpec.parse with the language of the compile, the smoke step of the host, the save in the tool store, and the open in the shell,
     * with a picture in the light and in the dark theme. After the smoke step, the language check of AppAgent.validate runs on the first screen, and
     * one write_app of the definition with the request of the index runs all the checks of validate (written). Each step of each plan is caught, so
     * one bad plan hides no other plan.
     * The folder appfile-model of the external files gets results.txt (one line for each plan), and the picture and the screen texts of each open.
     * The test asserts after the last picture that no step failed.
     */
    @Test fun eachModelPlanPassesTheDevicePath() {
        val sandbox=SandboxClient(instrumentation.targetContext)
        val compiler=AppFileCompiler(instrumentation.targetContext,sandbox)
        val ids=modelPlans()
        assertEquals(52,ids.size)
        // The index of the plans (kit/test/fixtures/model/index.json): the request of each plan is the request of its write.
        val requests=JSONObject(instrumentation.context.assets.open("appfile/model/index.json").use {it.readBytes().decodeToString()})
        val lines=ids.associateWith {mutableListOf<String>()}
        val failures=mutableListOf<String>()
        /** One step of a plan: its status in the line of the plan, and its message in the failures. */
        fun <T> step(id: String,name: String,block: ()->T): T? =runCatching(block).onSuccess {lines.getValue(id)+="$name=ok"}.onFailure {
            lines.getValue(id)+="$name=FAIL"
            failures+="$id $name: ${it.message?.take(600)}"
        }.getOrNull()
        // The first compile loads the kit into the sandbox, so no plan pays it.
        runBlocking {compiler.compile(plan("calc"))}
        val specs=linkedMapOf<String,ToolSpec>()
        for(id in ids) {
            val started=SystemClock.elapsedRealtime()
            val result=step(id,"compile") {runBlocking {compiler.compile(plan("model/$id"))}.also {check(it.status == "ok" && it.faults.isEmpty()) {"${it.status} ${it.faults}"}}}
            lines.getValue(id)+="compileMs=${SystemClock.elapsedRealtime()-started}"
            if(result == null) continue
            val spec=step(id,"parse") {ToolSpec.parse(checkNotNull(result.definition)).withAppLanguage(result.language)} ?: continue
            lines.getValue(id)+="lang=${result.language}"
            val smokeStarted=SystemClock.elapsedRealtime()
            val controls=step(id,"smoke") {runBlocking {smokedControls(sandbox,spec,"model-$id")}}
            lines.getValue(id)+="smokeMs=${SystemClock.elapsedRealtime()-smokeStarted}"
            if(controls == null) continue
            val (changed,unchanged,preview)=controls
            lines.getValue(id)+="changed=$changed unchanged=$unchanged"
            specs[id]=spec
            // The two last checks of AppAgent.validate for a new app, in its order: the language of the visible text, then presentationProblem.
            result.language?.let {tag -> step(id,"language") {check(!runBlocking {GenerationLanguage.Check(AppLanguage(tag)).mismatches(JSONObject(preview.ui),spec.title,spec.description)}) {
                "The visible text is not in ${GenerationLanguage.name(tag)}."}}}
            val request=requests.getJSONObject(id).getString("request")
            val reply=step(id,"write") {runBlocking {written(sandbox,checkNotNull(result.definition),result.language,request,"appfile-validate-$id")}} ?: continue
            step(id,"validate") {check(reply.startsWith("write_app: OK")) {reply}}
            // A stop at the language check hides presentationProblem: a second write without the language reaches it.
            if("The visible text is not in" in reply) step(id,"presentation") {
                runBlocking {written(sandbox,checkNotNull(result.definition),null,request,"appfile-validate-$id")}.also {check(it.startsWith("write_app: OK")) {it}}}
            // A control of the two language paths: the Russian tip calculator as a Japanese app must stop at the language check, so a pass is not a dead check.
            if(id == "holdout-tip-ru") step(id,"control") {
                val direct=runBlocking {GenerationLanguage.Check(AppLanguage("ja")).mismatches(JSONObject(preview.ui),spec.title,spec.description)}
                val japanese=runBlocking {written(sandbox,checkNotNull(result.definition),"ja",request,"appfile-validate-$id")}
                check(direct && japanese.startsWith("write_app: REJECTED. The visible text is not in Japanese")) {"direct=$direct $japanese"}
            }
        }
        val rows=specs["e-bbq-pt"]?.let {spec -> step("e-bbq-pt","rows") {runBlocking {checked(sandbox,spec,"appfile-model-e-bbq-pt",BBQ_ROWS)}.let {(outcome,state) ->
            check(outcome.failure == null) {"${outcome.failure}"}
            state.toString()
        }}}
        val folder=File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),"appfile-model")
        val saved=mutableSetOf<String>()
        try {
            inEachTheme("appfile-model") {dark,directory ->
                val theme=if(dark) "dark" else "light"
                inShell("appfile-model",{store ->
                    for((id,spec) in specs) if(dark) {if(id in saved) save(store,"appfile-model-$id",spec)} else if(step(id,"save") {save(store,"appfile-model-$id",spec)} != null) saved+=id
                    rows?.let {save(store,"appfile-model-e-bbq-pt-rows",specs.getValue("e-bbq-pt"),it)}
                }) {robot,model,_,scenario ->
                    robot.waitUntil {nightOf(scenario) == dark}
                    val refine=robot.text(R.string.tool_refine)
                    val shown=specs.filterKeys {it in saved}.map {(id,spec) -> Triple(id,"appfile-model-$id",spec)}+
                        listOfNotNull(rows?.let {Triple("e-bbq-pt","appfile-model-e-bbq-pt-rows",specs.getValue("e-bbq-pt"))})
                    for((id,appId,spec) in shown) {
                        val name=appId.removePrefix("appfile-model-")
                        val opened=step(id,"open-$theme${if(name == id) "" else "-rows"}") {open(robot,model,appId,spec)} != null
                        if(opened) {
                            // A canvas that is not whole on the first screen is never drawn in this check: the line says so, and the picture shows it.
                            val canvases=canvasTexts(spec.ui).map {text -> "$text:${if(runCatching {robot.waitUntil(5_000) {drawn(robot,text)}}.isSuccess) "drawn" else "not drawn"}"}
                            val still=snapshot(robot,File(directory,"$name.png"))
                            File(directory,"$name.txt").writeText(robot.texts().joinToString("\n"))
                            lines.getValue(id)+="$theme${if(name == id) "" else "-rows"}=[still=$still canvas=$canvases]"
                        } else instrumentation.runOnMainSync {model.tools.clearError()}
                        // After a failed open the app can be shut: back only from an open app, so the loop stays in the shell.
                        if(robot.find(refine) != null) runCatching {robot.back();robot.waitGone(refine)}.onFailure {failures+="$id back-$theme: ${it.message}"}
                    }
                }
            }
        } finally {
            val report=lines.map {(id,parts) -> "$id ${parts.joinToString(" ")}"}
            report.forEach {Log.i("AppFileLookTest","model $it")}
            folder.mkdirs()
            File(folder,"results.txt").writeText((report+"failures ${failures.size}"+failures).joinToString("\n")+"\n")
        }
        assertEquals(emptyList<String>(),failures)
    }
    /** Adds two products with the steps of the baseline list (addTwoRows), and takes the picture when the rows show. */
    private fun added(robot: ShellRobot,model: ShellViewModel,store: ToolStore,scenario: ActivityScenario<CloudTestActivity>,id: String,picture: File) {
        addTwoRows(robot,model,store,id,scenario,KIT_LIST)
        picture(robot,picture)
    }
    /** Presses a control through the UI layer, and takes the picture when the text of its reply shows. */
    private fun pressed(robot: ShellRobot,control: String,reply: String,picture: File) {
        robot.reveal(control,forward=null)
        robot.click(control)
        robot.reveal(reply,forward=null)
        picture(robot,picture)
    }
    /** Where a list app keeps its rows and the text of its field "Prodotto": the baseline app and the kit plan name them differently. */
    private class ListFields(val rows: (JSONObject)->Int,val draft: (JSONObject)->String)
    private val BASELINE_LIST=ListFields({it.getJSONArray("items").length()},{it.getString("draftName")})
    private val KIT_LIST=ListFields({it.getJSONArray("prodotti").length()},{it.getJSONObject("prodotti_s").getString("new_nome")})
    /**
     * The steps of a person who adds two products to a list app through the UI layer, the same for the baseline app and for the kit plan.
     * The field "Prodotto" is a live input of the host, and "Aggiungi" adds the row.
     * 1. Type "Latte" and press "Aggiungi" directly: no row, the host's notice to finish the edit, and the typed text as the stored draft.
     * 2. Finish the edit with the host's finish button and press "Aggiungi": one row, its check mark "Preso", and an empty field.
     * 3. Tap the field, type "Pane", press the action key of the keyboard, and press "Aggiungi": two rows in the state and on the screen.
     * Each press after a finish waits for the condition of published. step gets the label of each step when it is done.
     */
    private fun addTwoRows(robot: ShellRobot,model: ShellViewModel,store: ToolStore,id: String,scenario: ActivityScenario<CloudTestActivity>,
        list: ListFields,step: (String)->Unit={}) {
        val finish=word("it",R.string.tool_live_input_finish_en,"Prodotto")
        fun rows()=list.rows(state(store,id))
        fun draft()=list.draft(state(store,id))
        fun empty()=robot.field("Prodotto").text.isNullOrEmpty()
        robot.enter("Prodotto","Latte")
        robot.click("Aggiungi")
        robot.waitFor(word("it",R.string.tool_live_input_finish_first_en))
        robot.waitUntil {draft() == "Latte"}
        assertEquals("the press directly after the typing adds no row",0,rows())
        step("1-pressed-after-typing")
        // The host shows its finish button under the field, not an apply button.
        robot.waitFor(finish)
        assertNull(robot.find(word("it",R.string.tool_generated_apply_en,"Prodotto")))
        robot.click(finish)
        robot.waitGone(finish)
        step("2-finish-button")
        published(robot,model,store,id)
        robot.click("Aggiungi")
        robot.waitUntil {rows() == 1}
        robot.waitUntil {empty()}
        robot.reveal("Preso",forward=null)
        step("3-added-after-finish-button")
        // The action key of the keyboard is the other step that finishes the edit.
        robot.reveal("Prodotto",forward=null)
        val editor=Rect().also {robot.field("Prodotto").getBoundsInScreen(it)}
        robot.shell("input tap ${editor.centerX()} ${editor.centerY()}")
        robot.waitUntil {robot.keyboardTop(scenario) != null}
        robot.enter("Prodotto","Pane")
        robot.waitFor(finish)
        assertTrue(robot.field("Prodotto").performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
        robot.waitGone(finish)
        if(robot.keyboardTop(scenario) != null) robot.back()
        step("4-keyboard-action")
        published(robot,model,store,id)
        robot.click("Aggiungi")
        robot.waitUntil {rows() == 2}
        // With the field empty, each product name on the screen is a node of its row.
        robot.waitUntil {empty()}
        for(product in listOf("Latte","Pane")) robot.reveal(product,forward=null)
        step("5-added-after-keyboard-action")
    }
    /** A saved baseline app: its spec keeps the appLanguage field of the host. */
    private fun baseline(name: String)=ToolSpec.parsePersisted(instrumentation.context.assets.open("appfile/baseline/$name.json").use {it.readBytes().decodeToString()})
    /** A picture of the screen when it holds still. */
    private fun picture(robot: ShellRobot,file: File) {
        val screen=stillScreen(robot)
        try {file.outputStream().use {assertTrue(screen.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {screen.recycle()}
    }

    /**
     * The steps of addTwoRows on one list app, through the UI layer. Each step leaves the stored rows and draft, the texts of the screen,
     * and a picture in the folder. The typing is the accessibility action that sets the text of the field, as each test of this class types.
     */
    private fun stepsOf(id: String,spec: ToolSpec,list: ListFields,folderName: String) {
        val folder=File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),folderName)
        if(folder.exists()) check(folder.deleteRecursively())
        check(folder.mkdirs())
        inShell(folderName,mapOf(id to spec)) {robot,model,store,scenario ->
            open(robot,model,id,spec)
            addTwoRows(robot,model,store,id,scenario,list) {label ->
                val now=state(store,id)
                File(folder,"$label.txt").writeText("draft=${list.draft(now)} rows=${list.rows(now)}\n"+robot.texts().joinToString("\n"))
                picture(robot,File(folder,"$label.png"))
            }
        }
    }
    /** Step zero of Task 11c: the baseline list app of the current agent loop needs a finish step between the typing of a product and its row. */
    @Test fun theBaselineListNeedsAFinishStepBetweenTheTypingAndTheRow()=
        stepsOf("appfile-showcase-lang-shopping-it",baseline("showcase-lang-shopping-it"),BASELINE_LIST,"appfile-step-zero")
    /** The kit plan of the same request takes the same steps as the baseline list app. */
    @Test fun theKitListTakesTheStepsOfTheBaselineList()=
        stepsOf("appfile-mock-shopping-it",runBlocking {parsedAndSmoked(SandboxClient(instrumentation.targetContext),"mock-shopping-it","it")},KIT_LIST,"appfile-steps-kit")
    /**
     * The tip of calc.plan is a live slider: the accessibility action that sets its value, and a drag on the screen, each change the total
     * with no apply step and no finish button. Then an ordinary action of the number field goes through, so no live edit is open.
     */
    @Test fun theTipSliderOfCalcChangesTheTotalWithNoStepByHand() {
        val spec=runBlocking {parsedAndSmoked(SandboxClient(instrumentation.targetContext),"calc")}
        val id="appfile-calc"
        inShell("appfile-slider",mapOf(id to spec)) {robot,model,store,_ ->
            open(robot,model,id,spec)
            fun tip()=state(store,id).getDouble("tip")
            fun total()=state(store,id).getJSONObject("d").getString("total_text")
            val first=total()
            robot.waitFor(first)
            // The slider is the one node of calc that takes the accessibility action that sets a progress.
            fun slider(): AccessibilityNodeInfo {
                robot.reveal("Tip %",forward=null)
                val found=progressNodes(robot.diagnostics.freshRoot())
                assertEquals(found.map {"${it.className} ${it.contentDescription} ${it.text}"}.toString(),1,found.size)
                return found.single()
            }
            // The new total shows, and the slider has no apply button and no finish button.
            fun shownWithNoStep() {
                robot.waitFor(total())
                for(resource in listOf(R.string.tool_generated_apply_en,R.string.tool_live_input_finish_en)) assertNull(robot.find(word("en",resource,"Tip %")))
            }
            // The host's slider takes a share from 0 to 1 of its range (nativeSliderValue): 0.75 of 0 to 30 is a tip of 22.5.
            assertTrue(slider().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,0.75f)}))
            robot.waitUntil {tip() == 22.5 && total() != first}
            shownWithNoStep()
            // A drag from the thumb past the left end of the slider: the host sends values on the way, and the last one is the min, 0.
            val bounds=Rect().also {slider().getBoundsInScreen(it)}
            val thumb=bounds.left+bounds.width()*3/4
            val moved=total()
            robot.shell("input swipe $thumb ${bounds.centerY()} 1 ${bounds.centerY()} 600")
            // The stored tip holds still for 300 ms after the last value.
            var last=-1.0
            robot.waitUntil {SystemClock.sleep(300); tip().let {now -> (now<22.5 && now == last).also {last=now}}}
            assertEquals(0.0,tip(),0.0)
            assertNotEquals(moved,total())
            shownWithNoStep()
            // The step buttons and the apply button of a number field send an ordinary action, which the host refuses while a live edit is open.
            published(robot,model,store,id)
            robot.click(word("en",R.string.tool_number_increase_en,"People"))
            robot.click(word("en",R.string.tool_generated_apply_en,"People"))
            robot.waitUntil {state(store,id).getInt("people") == 5}
            assertNull(robot.find(word("en",R.string.tool_live_input_finish_first_en)))
        }
    }
    /** A typed text that shows a second live text field: a person types into the first, then into the second, and both texts reach the state. */
    @Test fun aTypedTextShowsASecondLiveFieldThatTakesItsText() {
        val text="app(\"Two\", lang=\"en\")\n\$/first = \"\"\n\$/second = \"\"\nroot = Column([one, two])\none = TextField(\"First\", \$/first)\n" +
            "two = TextField(\"Second\", \$/second, visible=len(\$/first) > 0)\n"
        val spec=runBlocking {parsedAndSmoked(SandboxClient(instrumentation.targetContext),"two","en",text)}
        val id="appfile-two"
        inShell("appfile-two",mapOf(id to spec)) {robot,model,store,_ ->
            open(robot,model,id,spec)
            assertNull(robot.find("Second"))
            robot.enter("First","Ann")
            robot.waitUntil {state(store,id).getString("first") == "Ann"}
            robot.enter("Second","Bo")
            robot.waitUntil {state(store,id).getString("second") == "Bo"}
            assertEquals("Ann",state(store,id).getString("first"))
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
     * is one color. The inside leaves out 16 dp at the left and right edges, where the clip of the rounded corners shows the color behind the canvas,
     * and 16 dp or a third of the height at the top and bottom: the bar of a timer is about 20 dp high (Task 11b-2b).
     */
    private fun drawn(robot: ShellRobot,text: String): Boolean {
        val canvas=described(robot.diagnostics.freshRoot(),"$text.")?.takeIf {it.isVisibleToUser} ?: return false
        val bounds=Rect().also(canvas::getBoundsInScreen)
        val edge=(16*instrumentation.targetContext.resources.displayMetrics.density).toInt()
        val inside=Rect(bounds).apply {inset(edge,minOf(edge,bounds.height()/3))}
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
    private fun progressNodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =if(node == null) emptyList() else
        listOfNotNull(node.takeIf {AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS in it.actionList})+(0 until node.childCount).flatMap {progressNodes(node.getChild(it))}
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
            // The end of each timed change of the app: each one must stop, not only the change of the angle.
            fun ends()=state(store,id).getJSONObject("anim").let {anim -> anim.keys().asSequence().map {anim.getJSONObject(it).getLong("end")}.toList()}
            assertEquals(40.0,angle(),0.0)
            assertTrue(ends().toString(),ends().isNotEmpty() && ends().all {it == 0L})
            // lap_button sets the angle to 0 and animates it to 360 in 5 s. Alphy's own timer loop sends each anim event.
            robot.reveal("Один круг")
            robot.click("Один круг")
            robot.waitUntil(10_000) {ends().any {it>0}}
            robot.waitUntil(10_000) {angle() == 360.0 && ends().all {it == 0L}}
        }
    }

    @Test fun aLongTextOfAPlanShowsInFull() {
        // Item 11 of the final review: the host takes at most 300 characters in a literal text, and a bound text has no such limit.
        // The kit binds a text of 427 characters, and the screen holds all of it.
        val long="Read each line with care. ".repeat(16)+"Then begin."
        val spec=runBlocking {parsedAndSmoked(SandboxClient(instrumentation.targetContext),"long",text="app(\"Long\", lang=\"en\")\nneed intro = content(\"$long\")\n")}
        inShell("appfile-long",mapOf("appfile-long" to spec)) {robot,model,_,_ ->
            open(robot,model,"appfile-long",spec)
            robot.reveal(long,forward=null)
        }
    }
}
