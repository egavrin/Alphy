package com.alpha.assistant.tools.appfile

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.GraphicsSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.GeneratedOutputDetail
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolGenerationSmoke
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolSmokeFailure
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.agent.AppSteps
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Each saved plan of kit/plans with the language of its app. */
internal val SAVED_PLANS=mapOf("calc" to "en","list" to "it","quiz" to "ja","tabs-chart" to "es","timer-rounds" to "de","tea" to "tr","section" to "uk",
    "lesson" to "ru","model3d" to "ru")
/** A plan of the test assets that kit/build.js copies. */
internal fun plan(name: String)=InstrumentationRegistry.getInstrumentation().context.assets.open("appfile/$name.plan").use {it.readBytes().decodeToString()}
/** A saved plan through compile, ToolSpec.parse with the language of the compile result, and the smoke step as AppAgent.validate builds it. */
internal suspend fun parsedAndSmoked(sandbox: SandboxClient,name: String,language: String="en",text: String=plan(name),observeReplay: ((List<String>)->Unit)?=null): ToolSpec {
    val result=AppFileCompiler(InstrumentationRegistry.getInstrumentation().targetContext,sandbox).compile(text)
    assertEquals(result.faults.toString(),"ok",result.status)
    assertEquals(language,result.language)
    val spec=ToolSpec.parse(checkNotNull(result.definition)).withAppLanguage(result.language)
    smoked(sandbox,spec,name,spec.initialState,observeReplay=observeReplay)
    return spec
}
/** The event that the host sends for the control of an action id of a ui, as ToolGenerationSmoke.control picks it: a liveInput for a live input, else an action. */
internal fun controlEvent(root: JSONObject,actionId: String,value: Any?): JSONObject {
    val live=ToolUiCatalog.actionTarget(root,actionId)?.node?.let(ToolUiCatalog::isLiveInput) == true
    return JSONObject().put("type",if(live) SandboxContract.LIVE_INPUT_EVENT else "action").put("actionId",actionId).put("value",value ?: JSONObject.NULL)
}
/** The value of a row control of a list: the id of the row, and the value of the control or null for a press. */
internal fun row(key: String,value: Any?)=JSONObject().put("key",key).put("value",value ?: JSONObject.NULL)
/**
 * The smoke step as AppAgent.validate builds it, from a state: the preview of the first screen.
 * checkUi gets the reply of each event before observeReplay gets the events of its branch.
 */
internal suspend fun smoked(sandbox: SandboxClient,spec: ToolSpec,name: String,state: String,checkUi: (suspend (ToolPreview)->Unit)?=null,
    observeReplay: ((List<String>)->Unit)?): ToolPreview {
    // The script provider is never asked: the smoke run makes no model call.
    try {
        return ToolGenerationSmoke(sandbox,spec,"appfile-$name",1,state,checkActive={},
            generation=ToolGenerationService(AgentScriptProvider(),sandbox),checkUi=checkUi,observeReplay=observeReplay).run()
    } catch(failure: ToolSmokeFailure) {
        // The failure itself has no message: the validator's text is the detail of its cause.
        throw AssertionError("smoke ${failure.event} at ${failure.path}: ${(failure.cause as? GeneratedOutputDetail)?.detail ?: failure.cause} replay=${failure.replay}",failure)
    }
}
/** The steps of the agent step checker (AppSteps) on a disposable copy of a spec from its start state, as AppScenarioCheckTest runs them: the outcome and the state after the steps. */
internal suspend fun checked(sandbox: SandboxClient,spec: ToolSpec,id: String,steps: JSONArray): Pair<AppSteps.Outcome,JSONObject> =
    ToolGenerationSmoke(sandbox,spec,id,1,spec.initialState,checkActive={},generation=ToolGenerationService(AgentScriptProvider(),sandbox))
        .probe {AppSteps.run(this,AppSteps.parse(steps),everyStep=false) to state}

/** The plan of items 5 of the final review: a division, a doubling, and a text that grows. kit/test/run.test.js has the same plan. */
internal val PLAIN_VALUES="app(\"Big\", lang=\"en\")\n\$/r = 0\n\$/n = 1\n\$/note = \"x\"\nneed a = input(\"number\", \"A\", 6, min=0, max=100)\n" +
    "need b = input(\"number\", \"B\", 2, min=0, max=100)\ndivide = Button(\"Divide\", set(\$/r, \$/a / \$/b))\ndouble = Button(\"Double\", set(\$/n, \$/n * 2))\n" +
    "grow = Button(\"Grow\", set(\$/note, \$/note + \"0123456789\"))\nroot = Column([a_field, b_field, Text(text(\$/r)), divide, Text(text(\$/n)), double, Text(\$/note), grow])\n"
/** The plan of item 7: a choice whose options are the names of a list, and a button that sets a choice to a value that is no option. kit/test/run.test.js has the same plan. */
internal val PICK="app(\"Pick\", lang=\"en\")\nneed people = entries(\"People\", fields={name: \"text\"}, rows=[{name: \"Ann\"}, {name: \"Bo\"}])\n\$/who = \"Ann\"\n" +
    "pick = Select(\"Who\", \$/who, map(\$/people, \$name))\nneed k = input(\"choice\", \"K\", \"b\", options=[\"a\", \"b\"])\nreset = Button(\"Reset\", set(\$/k, \"\"))\n" +
    "root = Column([pick, people_view, k_field, reset])\n"

/** The kit bundle in the sandbox: the report of a saved plan, the fault of a line, an overlapping call, the time of a compile, compiled apps, each node of the node table, and each look option in Alphy's checks, the numbers of a figure in pl and de, a range, a map of options, and an icon of a field, a hero figure with an emoji, a bar, and a note, events on lists, the quiz, tabs, and a chart, the cards fixture, lists full to the render work limit, the time of an event, the held needs of tabs under an author's root, the timers, the written section, the free forms, the drawings and timed changes, the time of each saved plan with its lists full, the behavior scenarios of the plans that a model wrote, and the calls of the plan step and the change step: a reply read, merged, and compiled with a stored app, and the prompt texts. */
@RunWith(AndroidJUnit4::class)
class AppFileProofTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val compiler get()=AppFileCompiler(instrumentation.targetContext,SandboxClient(instrumentation.targetContext))

    @Test fun compilesAPlanInTheSandbox(): Unit=runBlocking {
        val result=compiler.compile(plan("calc"))
        assertEquals("ok",result.status)
        assertEquals(emptyList<String>(),result.faults)
    }
    @Test fun reportsAFaultOfALine(): Unit=runBlocking {
        val result=compiler.compile("need x = nothing(1)")
        assertEquals("faults",result.status)
        assertEquals(result.faults.toString(),1,result.faults.count {"nothing" in it})
    }
    @Test fun aCallThatOverlapsIsBusyNotAHang(): Unit=runBlocking {
        val text=plan("calc")
        // A compile and a read of a reply (loop plan, task 5) at one time.
        val results=withTimeout(2*APP_FILE_COMPILE_MS+4_000) {
            listOf(async(Dispatchers.Default) {compiler.compile(text)},async(Dispatchers.Default) {planOf(compiler.compileReply("```\n$text```")).compilation}).awaitAll()
        }
        results.forEach {assertTrue(it.status,it.status in setOf("faults","ok","BUSY"))}
    }
    @Test fun compileTakesUnderOneSecond(): Unit=runBlocking {
        // quiz is a saved plan. cards and rich cost the most of the test plans on Bun, about 30 times quiz (item 14 of the final review).
        val medians=listOf("quiz","cards","rich").associateWith {name ->
            val text=plan(name)
            val times=List(5) {
                val started=SystemClock.elapsedRealtime()
                val result=compiler.compile(text)
                assertEquals(result.toString(),"ok",result.status)
                assertEquals(emptyList<String>(),result.faults)
                SystemClock.elapsedRealtime()-started
            }
            val median=times.sorted()[2]
            Log.i("AppFileProofTest","compile $name times ms=$times median=$median")
            median
        }
        assertTrue("medians $medians",medians.values.all {it<1000})
    }
    /** Each node of a ui, also the nodes of each row template. */
    private fun nodes(node: JSONObject): List<JSONObject> =listOf(node)+
        (node.optJSONArray("children")?.let {children -> List(children.length()) {nodes(children.getJSONObject(it))}.flatten()} ?: emptyList())+
        (node.optJSONObject("rowTemplate")?.let {nodes(it)} ?: emptyList())
    @Test fun eachNodeOfTheTablePassesParseAndSmoke(): Unit=runBlocking {
        // nodes.plan is kit/test/nodes.plan: each component of the emitter's node table, and the kit test reads the same text.
        val spec=parsedAndSmoked(SandboxClient(instrumentation.targetContext),"nodes")
        val state=JSONObject(spec.initialState)
        // A progress bar in each row of a list binds a field of the view: the share of the row in percent.
        val goals=state.getJSONArray("goalList")
        assertEquals(listOf(75.0,20.0),List(goals.length()) {goals.getJSONObject(it).getDouble("goalRow_1_value")})
        // A ring with no text under it has no children, and dots show a value that is not a whole number.
        val ring=nodes(spec.ui).single {it.optString("type") == "progressRing"}
        assertFalse(ring.toString(),ring.has("children"))
        assertEquals(2.5,state.getJSONObject("d").getDouble("half_value"),0.0)
    }
    /** The text of each option of a choice. */
    private fun optionTexts(node: JSONObject)=node.getJSONArray("options").let {options -> List(options.length()) {options.getJSONObject(it).getString("text")}}
    @Test fun eachLookOptionPassesParseAndSmoke(): Unit=runBlocking {
        // looks.plan is kit/test/looks.plan: each look option and a Grid, and the kit test reads the same text.
        val spec=parsedAndSmoked(SandboxClient(instrumentation.targetContext),"looks")
        val all=nodes(spec.ui)
        assertEquals(3,all.single {it.optString("type") == "grid"}.getInt("columns"))
        assertEquals(listOf("Small","Large"),optionTexts(all.first {it.optString("type") == "segmented"}))
        assertEquals("display",all.single {it.optString("type") == "progressRing"}.getJSONArray("children").getJSONObject(0).getJSONObject("style").getString("emphasis"))
    }
    @Test fun calcPassesParseAndSmoke(): Unit=runBlocking {
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"calc")
        // Five events in sequence, each on the state of the reply before it. Alphy's limit for one event is 500 ms.
        // The tip is a live slider, so its events are liveInput events.
        assertEquals(SandboxContract.LIVE_INPUT_EVENT,controlEvent(spec.ui,"tip_field",20).getString("type"))
        var state=JSONObject(spec.initialState)
        val times=listOf(Triple("bill_field",200,"57.50"),Triple("tip_field",20,"60.00"),Triple("people_field",5,"48.00"),
            Triple("bill_field",100,"24.00"),Triple("tip_field",10,"22.00")).map {(actionId,value,perPerson) ->
            val input=JSONObject().put("artifactId","appfile-calc").put("revision",1).put("state",state)
                .put("event",controlEvent(spec.ui,actionId,value))
            val started=SystemClock.elapsedRealtime()
            val reply=sandbox.execute(spec.source,input.toString())
            val took=SystemClock.elapsedRealtime()-started
            assertEquals(reply.status,"OK",reply.status)
            state=JSONObject(checkNotNull(reply.json)).getJSONObject("state")
            assertEquals(actionId,value,state.getInt(actionId.removeSuffix("_field")))
            assertEquals(actionId,perPerson,state.getJSONObject("d").getString("perPerson_text"))
            took
        }
        val median=times.sorted()[2]
        Log.i("AppFileProofTest","event times ms=$times median=$median")
        assertTrue("median $median ms of $times",median<250)
    }
    @Test fun aFigurePrintsTheNumbersOfItsLanguageAtCompileAndInTheSandbox(): Unit=runBlocking {
        // The texts of kit/test/numbers.test.js: the table of the kit prints them, not the language data of the device.
        val sandbox=SandboxClient(instrumentation.targetContext)
        for ((language,start,after) in listOf(Triple("pl",listOf("2469","1234,50"),listOf("40\u00a0000","20\u00a0000,00")),
            Triple("de",listOf("2.469","1.234,50"),listOf("40.000","20.000,00")))) {
            val text="app(\"T\", lang=\"$language\")\nneed x = input(\"number\", \"X\", 1234.5)\n" +
                "need y = figure(\"number\", \"Y\", \$/x * 2, example=[{x: 10}, 20])\nneed m = figure(\"money\", \"M\", \$/x, example=[{x: 10}, 10])"
            val spec=parsedAndSmoked(sandbox,"numbers-$language",language,text)
            fun shown(state: JSONObject)=state.getJSONObject("d").let {listOf(it.getString("y_text"),it.getString("m_text"))}
            assertEquals(language,start,shown(JSONObject(spec.initialState)))
            assertEquals(language,after,shown(Session(sandbox,spec,"appfile-numbers-$language").act("x_field",20000)))
        }
    }
    @Test fun aRangeAMapOfOptionsAndAnIconOfAFieldPassParseAndSmoke(): Unit=runBlocking {
        // The plan of kit/test/inputs.test.js. Alphy's smoke step moves the range to its bounds, and an event sends both numbers.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val text="app(\"Bikes\", lang=\"en\")\nneed price = input(\"range\", \"Price\", [100, 1500], min=0, max=2000, step=50)\n" +
            "need shown = figure(\"count\", \"Shown\", count([300, 900, 1800], \$it >= \$/price[0] && \$it <= \$/price[1]), example=[{price: [0, 2000]}, 3])\n" +
            "need kind = input(\"choice\", \"Type\", \"road\", options={all: \"All\", road: \"Road\", city: \"City\"})\nneed name = input(\"text\", \"Name\", \"\", icon=\"Pencil\")"
        val spec=parsedAndSmoked(sandbox,"inputs","en",text)
        val all=nodes(spec.ui)
        assertEquals(listOf(0.0,2000.0,50.0),all.single {it.optString("type") == "rangeSlider"}.let {node -> listOf("min","max","step").map {node.getDouble(it)}})
        assertEquals(listOf("All","Road","City"),optionTexts(all.single {it.optString("type") == "segmented"}))
        assertEquals("Pencil",all.single {it.optString("type") == "input"}.getString("icon"))
        val session=Session(sandbox,spec,"appfile-inputs")
        val moved=session.act("price_field",JSONArray(listOf(500,1000)))
        assertEquals(listOf(500.0,1000.0),moved.getJSONArray("price").let {range -> List(range.length()) {range.getDouble(it)}})
        assertEquals("1",moved.getJSONObject("d").getString("shown_text"))
        assertEquals("city",session.act("kind_field","city").getString("kind"))
    }
    @Test fun aHeroFigureWithAnEmojiABarAndANotePassesParseAndSmoke(): Unit=runBlocking {
        // The first plan of kit/test/figures.test.js.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val text="app(\"물\", lang=\"ko\")\nneed ml = input(\"number\", \"물 (ml)\", 500)\n" +
            "need today = figure(\"number\", \"오늘 마신 물\", \$/ml, icon=\"💧\", goal=2000, note=\"하루 목표 2,000 ml\", example=[{ml: 750}, 750])"
        val spec=parsedAndSmoked(sandbox,"hero","ko",text)
        val hero=nodes(spec.ui).single {it.optString("variant") == "hero"}
        assertEquals(listOf("emoji","metric","progress","text"),hero.getJSONArray("children").let {children -> List(children.length()) {children.getJSONObject(it).getString("type")}})
        val after=Session(sandbox,spec,"appfile-hero").act("ml_field",1500).getJSONObject("d")
        assertEquals(listOf("1,500","75"),listOf(after.getString("today_text"),after.get(hero.getJSONArray("children").getJSONObject(2).getString("bind").removePrefix("d.")).toString()))
    }
    /** Events in sequence through ToolGenerationService.executeEvent: Alphy checks each event against the screen before it, and each reply. */
    private class Session(private val sandbox: SandboxClient,private val spec: ToolSpec,private val id: String,seed: String=spec.initialState) {
        private val generation=ToolGenerationService(AgentScriptProvider(),sandbox)
        var ui=spec.ui.toString()
            private set
        var state=seed
            private set
        suspend fun act(actionId: String,value: Any?=null): JSONObject =
            send(JSONObject().put("type","action").put("actionId",actionId).put("value",value ?: JSONObject.NULL))
        /** The value of a live input (a text field or a slider), as the host sends it while the person types or drags. */
        suspend fun live(actionId: String,value: Any): JSONObject =
            send(JSONObject().put("type",SandboxContract.LIVE_INPUT_EVENT).put("actionId",actionId).put("value",value))
        /** The event that the host sends for this control on the screen of now (controlEvent). */
        suspend fun control(actionId: String,value: Any?=null): JSONObject=send(controlEvent(JSONObject(ui),actionId,value))
        suspend fun send(event: JSONObject): JSONObject {
            val preview=generation.executeEvent(spec,id,1,state,event,ui) {source,input -> sandbox.execute(source,input.toString())}
            state=preview.state
            ui=preview.ui
            return JSONObject(state)
        }
    }
    private fun ids(rows: JSONArray)=List(rows.length()) {rows.getJSONObject(it).getString("id")}
    @Test fun listPassesParseAndSmoke(): Unit=runBlocking {
        val sandbox=SandboxClient(instrumentation.targetContext)
        val app=Session(sandbox,parsedAndSmoked(sandbox,"list","it"),"appfile-list")
        app.live("prodotti_new_nome","Burro")
        app.live("prodotti_new_corsia","Latticini")
        val added=app.act("prodotti_add").getJSONArray("prodotti")
        assertEquals(listOf("r1","r2","r3","r4"),ids(added))
        assertEquals("Burro",added.getJSONObject(3).getString("nome"))
        val checked=app.act("prodotti_preso",row("r4",true))
        assertTrue(checked.getJSONArray("prodotti").getJSONObject(3).getBoolean("preso"))
        assertEquals("3",checked.getJSONObject("d").getString("daComprare_text"))
        val removed=app.act("prodotti_remove",row("r4",null))
        assertEquals(listOf("r1","r2","r3"),ids(removed.getJSONArray("prodotti")))
        // The sorted view follows the stored list: Forno, Frutta, Latticini.
        assertEquals(listOf("r2","r3","r1"),ids(removed.getJSONArray("prodotti_list")))
    }
    @Test fun eachListAndModalPassesParseAndSmoke(): Unit=runBlocking {
        // lists.plan is kit/test/lists.plan: a grouped list, a list with reorder, row controls of each kind, a dialog, two bottom sheets, and the date, time, and multi-choice inputs.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val app=Session(sandbox,parsedAndSmoked(sandbox,"lists"),"appfile-lists")
        val moved=app.act("steps_list_move",JSONObject().put("sourceKey","r6").put("destinationKey","r8"))
        assertEquals(listOf("r7","r8","r6"),ids(moved.getJSONArray("steps")))
        assertTrue(app.act("acts_open").getJSONObject("acts_s").getBoolean("adding"))
        assertFalse(app.act("acts_addRow_dismiss").getJSONObject("acts_s").getBoolean("adding"))
        // The run-time owns the sheet of the doses form. Alphy refuses the dismiss of a sheet that is not open, so the dismiss shows that the open worked.
        app.act("doses_addRow_open")
        app.act("doses_addRow_dismiss")
        assertEquals(510,app.act("wake_field",510).getInt("wake"))
    }
    @Test fun quizPassesParseAndSmoke(): Unit=runBlocking {
        // The presses of the plan's own check: reveal, learned, and not yet two times.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val app=Session(sandbox,parsedAndSmoked(sandbox,"quiz","ja"),"appfile-quiz")
        assertTrue(app.act("revealButton").getBoolean("shown"))
        val learned=app.act("learnedButton")
        assertEquals(1,learned.getInt("learned"))
        assertFalse(learned.getBoolean("shown"))
        assertEquals("火",learned.getJSONObject("d").getString("card_kanji"))
        app.act("notYetButton")
        val notYet=app.act("notYetButton")
        assertEquals(2,notYet.getInt("notYet"))
        assertEquals("水",notYet.getJSONObject("d").getString("card_kanji"))
    }
    @Test fun twoRichListsFullToTheirCountPassParseAndSmoke(): Unit=runBlocking {
        // rich.plan is kit/test/rich.plan: two lists with a choice of 6 options in each row.
        // Its start rows fill each list to the count that the render work limit leaves.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"rich")
        val state=JSONObject(spec.initialState)
        for(list in listOf("shirts","shoes")) {
            assertEquals(list,91,spec.schema.getJSONObject(list).getInt("maxItems"))
            assertEquals(list,91,state.getJSONArray(list).length())
        }
        val checked=Session(sandbox,spec,"appfile-rich").act("shoes_done",row("r92",true))
        assertTrue(checked.getJSONArray("shoes").getJSONObject(0).getBoolean("done"))
        // The source holds no start rows, so an event on 182 rows reads them from the state only.
        val median=medianEventMs(sandbox,spec,"rich",state,"shoes_done",row("r92",true))
        assertTrue("median $median ms",median<250)
    }
    /** The median time in ms of one action, five times in the bare sandbox, which the log prints with its label. */
    private suspend fun medianEventMs(sandbox: SandboxClient,spec: ToolSpec,label: String,state: JSONObject,actionId: String,value: Any?): Long =
        timedEvent(sandbox,spec,label,state,action(actionId,value)).first
    /** The median time in ms of one event, five times in the bare sandbox, which the log prints with its label, and the state of its reply. */
    private suspend fun timedEvent(sandbox: SandboxClient,spec: ToolSpec,label: String,state: JSONObject,event: JSONObject): Pair<Long,JSONObject> {
        val input=JSONObject().put("artifactId","appfile-$label").put("revision",1).put("state",state).put("event",event).toString()
        var replied=JSONObject()
        val times=List(5) {
            val started=SystemClock.elapsedRealtime()
            val reply=sandbox.execute(spec.source,input)
            val took=SystemClock.elapsedRealtime()-started
            assertEquals(reply.status,"OK",reply.status)
            replied=JSONObject(checkNotNull(reply.json)).getJSONObject("state")
            took
        }
        val median=times.sorted()[2]
        Log.i("AppFileProofTest","$label event times ms=$times median=$median")
        return median to replied
    }
    @Test fun tabsChartPassesParseAndSmoke(): Unit=runBlocking {
        // A tab press and an add that the chart follows, each through Alphy's checks of the event and of its reply.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"tabs-chart","es")
        val app=Session(sandbox,spec,"appfile-tabs-chart")
        val opened=app.act("resumen_view","Añadir")
        assertEquals("Añadir",opened.getJSONObject("resumen_s").getString("tab"))
        assertFalse(opened.getJSONObject("v").getBoolean("resumen_view_tab0"))
        assertTrue(opened.getJSONObject("v").getBoolean("resumen_view_tab1"))
        app.live("gastos_new_concepto","Taxi")
        app.act("gastos_new_categoria","Transporte")
        val ready=app.act("gastos_new_importe",300)
        val added=app.act("gastos_add")
        assertEquals("[\"Transporte\"]",added.getJSONArray("grafico_chart_labels").toString())
        assertEquals("[300]",added.getJSONArray("grafico_chart_values").toString())
        // The add, with the chart and the total that follow it, is the heavier event of the plan.
        val median=medianEventMs(sandbox,spec,"tabs-chart",ready,"gastos_add",null)
        assertTrue("median $median ms",median<250)
    }
    @Test fun eachCardChartAndTabPassesParseAndSmoke(): Unit=runBlocking {
        // cards.plan is kit/test/cards.plan: a ring in the hero card, a dots and a bar progress, a bar chart and a line chart at 256 points,
        // tabs, the steps of a wizard, two lessons, a question, and a deck.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"cards")
        val state=JSONObject(spec.initialState)
        assertEquals(256,state.getJSONArray("trend_chart_labels").length())
        // The progress, ring, chart, and tabs of a part show a caption of the part, never the name of a line.
        assertEquals(listOf("Done","Charts, Tasks","Sizes","Trend","Step","Step"),
            nodes(spec.ui).filter {it.optString("type") in setOf("progress","progressRing","chart","tabs")}.map {it.getString("text")})
        val app=Session(sandbox,spec,"appfile-cards")
        val answered=app.act("ask_opt1")
        assertTrue(answered.getJSONObject("v").getBoolean("ask_result_visible"))
        assertEquals("Right",answered.getJSONObject("d").getString("ask_result_text"))
        assertEquals("Tasks",app.act("pages_view","Tasks").getJSONObject("pages_s").getString("tab"))
        val median=medianEventMs(sandbox,spec,"cards",state,"ask_opt1",null)
        assertTrue("median $median ms",median<250)
        // A row with no weighted child is a flowRow, also as the row template of a list (the kit test of the add sheet has the same plan).
        val picks=parsedAndSmoked(sandbox,"picks",text="app(\"Picks\", lang=\"en\")\nneed picks = entries(\"Picks\", fields={day: [\"Sat\", \"Sun\"]}, sheet=true, rows=[])\n")
        assertTrue(picks.ui.toString(),"\"rowTemplate\":{\"type\":\"flowRow\"" in picks.ui.toString())
    }
    @Test fun anAuthorsRootBesideATabsNeedPassesParseAndSmoke(): Unit=runBlocking {
        // The tabs need holds the two counters, and the author's root gets only the tabs: each control is on the screen once.
        // The kit test of the held needs has the same plan.
        val text="app(\"Held\", lang=\"en\")\nneed a = counter(\"A\")\nneed b = counter(\"B\")\nneed pages = tabs([\"One\", \"Two\"], [[a], [b]])\nnote = Text(\"Hi\")\nroot = Column([note])\n"
        val spec=parsedAndSmoked(SandboxClient(instrumentation.targetContext),"held",text=text)
        assertEquals(listOf("pages_view","a_minus","a_plus","b_minus","b_plus"),nodes(spec.ui).mapNotNull {it.optString("action").ifEmpty {null}})
    }
    @Test fun teaPassesParseAndSmoke(): Unit=runBlocking {
        // The host's own countdown: a choice of tea gives a new ui whose timer has the duration of that tea, and Alphy's checks take it.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"tea","tr")
        assertEquals(setOf("audio.tone","vibration.play"),spec.capabilities)
        val app=Session(sandbox,spec,"appfile-tea")
        fun duration()=ToolUiCatalog.validateForegroundTimer(JSONObject(app.ui),"demleme_timer",JSONObject(app.state)).durationMillis
        assertEquals(300_000L,duration())
        app.act("cay_field","Yeşil çay")
        assertEquals(180_000L,duration())
        app.act("cay_field","Bitki çayı")
        assertEquals(420_000L,duration())
    }
    /** The path of the host for an open app: one retained session, and executeEvent with dispatch for each event, as ArtifactCoordinator sends a timer. */
    private class Host(context: Context,sandbox: SandboxClient,private val spec: ToolSpec,private val id: String) {
        private val generation=ToolGenerationService(AgentScriptProvider(),sandbox)
        private val session=GraphicsSession(context)
        private var ui=spec.ui.toString()
        var state=spec.initialState
            private set
        suspend fun open()=send(JSONObject().put("type","open"),open=true)
        suspend fun send(event: JSONObject,open: Boolean=false): ToolPreview {
            val preview=generation.executeEvent(spec,id,1,state,event,ui) {source,input ->
                if(open) session.open(source,input,waitForRetainedRelease=false) {true} else session.dispatch(input)
            }
            state=preview.state
            ui=preview.ui
            return preview
        }
        fun close()=session.close()
    }
    private fun action(actionId: String,value: Any?)=JSONObject().put("type","action").put("actionId",actionId).put("value",value ?: JSONObject.NULL)
    private fun press(actionId: String)=action(actionId,null)
    private fun timer(timerId: String)=JSONObject().put("type","timer").put("timerId",timerId)
    /** The median time in ms of five events through the host path: event gives the event of each turn, and check asserts each reply. The log prints the times with their label. */
    private suspend fun medianHostMs(app: Host,label: String,event: (Int)->JSONObject,check: (ToolPreview)->Unit): Long {
        val times=List(5) {
            val started=SystemClock.elapsedRealtime()
            val reply=app.send(event(it))
            val took=SystemClock.elapsedRealtime()-started
            check(reply)
            took
        }
        val median=times.sorted()[2]
        Log.i("AppFileProofTest","$label host event times ms=$times median=$median")
        return median
    }
    /** The timer ids that the smoke step sends: it sends each timer of a reply once. */
    private fun timerIds(sent: MutableSet<String>): (List<String>)->Unit={events ->
        events.map {JSONObject(it)}.filter {it.getString("type") == "timer"}.forEach {sent+=it.getString("timerId")}
    }
    @Test fun timerRoundsPassesParseAndSmoke(): Unit=runBlocking {
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        // The smoke sends each timer of a reply once. A source with timers and no declared surface fails with widget_timer_host.
        val sent=mutableSetOf<String>()
        val rounds=parsedAndSmoked(sandbox,"timer-rounds","de",observeReplay=timerIds(sent))
        assertEquals(listOf("timer_bar"),rounds.surfaces.keys.toList())
        assertEquals(setOf("timer_bar"),sent)
        // Real time: two work seconds and two rest seconds in 2 rounds (the kit test of the short plan has the same plan and steps).
        // A phase of 2 s leaves a slow emulator time to send each timer event before the next phase ends.
        val text="app(\"Short rounds\", lang=\"en\")\nneed t = timer(\"Short\", 2, rounds=2, rest=2)\nneed done = progress(\"Rounds done\", \$/t, 2)\n"
        val short=Host(context,sandbox,parsedAndSmoked(sandbox,"short",text=text),"appfile-short")
        try {
            val ticks=mapOf("t_bar" to 1000)
            assertEquals(emptyMap<String,Int>(),short.open().timers)
            assertEquals(ticks,short.send(press("t_start")).timers)
            val seen=List(3) {
                // Each timer event comes 300 ms after the end of the phase in the state of the reply before it.
                val end=JSONObject(short.state).getJSONObject("t_s").getLong("end")
                delay(maxOf(0L,end+300-System.currentTimeMillis()))
                val reply=short.send(timer("t_bar"))
                val state=JSONObject(reply.state)
                val t=state.getJSONObject("t_s")
                listOf(if(t.getBoolean("over")) "over" else if(t.getBoolean("resting")) "rest" else "work",t.getInt("round"),state.getInt("t"),reply.timers)
            }
            // The whole timers of each reply: the id and the interval of each timer that runs. No rest follows the last round.
            assertEquals(listOf(listOf("rest",1,1,ticks),listOf("work",2,1,ticks),listOf("over",2,2,emptyMap<String,Int>())),seen)
        } finally {short.close()}
        // Five timer events of the 40 s work phase of timer-rounds through the same path.
        val app=Host(context,sandbox,rounds,"appfile-timer-rounds")
        try {
            app.open()
            app.send(press("timer_start"))
            val median=medianHostMs(app,"timer-rounds timer",{timer("timer_bar")}) {assertEquals(mapOf("timer_bar" to 1000),it.timers)}
            assertTrue("median $median ms",median<50)
        } finally {app.close()}
    }
    @Test fun anActionOnAListOf100RowsTakesUnder250Ms(): Unit=runBlocking {
        val rows=(1..100).joinToString(", ") {"{name: \"Item $it\", done: false}"}
        // sort= gives the list a view that the run-time writes after each event: the heavier path.
        val text="app(\"Items\", lang=\"en\")\nneed items = entries(\"Items\", fields={name: \"text\", done: \"check\"}, sort=\$name, rows=[$rows])\n"
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"items",text=text)
        // One event through Alphy's checks, then the same event five times in the bare sandbox for its time.
        val checked=Session(sandbox,spec,"appfile-items").act("items_done",row("r50",true))
        assertTrue(checked.getJSONArray("items").getJSONObject(49).getBoolean("done"))
        assertEquals(100,checked.getJSONArray("items_list").length())
        val median=medianEventMs(sandbox,spec,"items",JSONObject(spec.initialState),"items_done",row("r50",true))
        assertTrue("median $median ms",median<250)
    }
    @Test fun sectionPassesParseAndSmoke(): Unit=runBlocking {
        // section.plan is kit/plans/section.plan: an entries need, and the free lines of a hole: a Section, a List with a row of its own,
        // and a Text that each row computes. The kit test of section.plan reads the same plan.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"section","uk")
        val app=Session(sandbox,spec,"appfile-section")
        fun streaks(state: JSONObject)=state.getJSONArray("streak_list").let {rows -> List(rows.length()) {rows.getJSONObject(it).getString("streak_text_text")}}
        // Each kind of action of the plan through Alphy's checks: the field of a row, the add (a list of append and set), and the remove of a row.
        val checked=app.act("habits_mon",row("r1",true))
        assertTrue(checked.getJSONArray("habits").getJSONObject(0).getBoolean("mon"))
        assertEquals(listOf("Серія: 1","Серія: 0","Серія: 0"),streaks(checked))
        app.live("habits_new_name","Біг")
        val added=app.act("habits_add")
        assertEquals(listOf("r1","r2","r3","r4"),ids(added.getJSONArray("habits")))
        assertEquals("Біг",added.getJSONArray("habits").getJSONObject(3).getString("name"))
        assertEquals("",added.getJSONObject("habits_s").getString("new_name"))
        assertEquals(listOf("Серія: 1","Серія: 0","Серія: 0","Серія: 0"),streaks(added))
        val removed=app.act("habits_remove",row("r4",null))
        assertEquals(listOf("r1","r2","r3"),ids(removed.getJSONArray("habits")))
        assertEquals(listOf("r1","r2","r3"),ids(removed.getJSONArray("streak_list")))
        val median=medianEventMs(sandbox,spec,"section",JSONObject(spec.initialState),"habits_mon",row("r1",true))
        assertTrue("median $median ms",median<250)
    }
    @Test fun eachFreeFormPassesParseAndSmoke(): Unit=runBlocking {
        // free.plan is kit/test/free.plan: an inline Row as a List template, a row Button with enabled=, flip, move with a test,
        // and the lines bar_0 and bar_1 beside the inline children of bar. The kit tests of the free fixture read the same plan.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"free")
        // A control by its text: an inline control has a name from its parent and its place.
        fun node(text: String)=nodes(spec.ui).single {it.optString("text") == text}
        val app=Session(sandbox,spec,"appfile-free")
        val marked=app.act(node("Mark").getString("action"),row("r1",null))
        assertTrue(marked.getJSONArray("rows").getJSONObject(0).getBoolean("done"))
        val undone=app.act(node("Undo").getString("action"),row("r2",null))
        assertFalse(undone.getJSONArray("rows").getJSONObject(1).getBoolean("done"))
        // Mark is on in each row that is not done: the test of each row follows its row.
        val items=undone.getJSONArray("items")
        assertEquals(listOf(false,true,true),List(items.length()) {items.getJSONObject(it).getBoolean(node("Mark").getString("enabledBind"))})
        assertEquals(listOf("r3","r1","r2"),ids(app.act(node("Hike first").getString("action")).getJSONArray("rows")))
        assertEquals("[\"home\",\"work\"]",app.act(node("Work").getString("action")).getJSONArray("tags").toString())
        assertEquals("[\"home\"]",app.act(node("Work").getString("action")).getJSONArray("tags").toString())
        // The inline text of bar and the line bar_1 show values of their own.
        val more=app.act(node("One more").getString("action"))
        assertEquals(1,more.getInt("count"))
        val shown=more.getJSONObject("d")
        assertEquals(setOf("home","Count 1","Twice 2"),shown.keys().asSequence().map {shown.getString(it)}.toSet())
        assertEquals(0,app.act(node("Zero").getString("action")).getInt("count"))
        // An author's line with when(...): an odd count goes to the next even count, and an even count stays.
        app.act(node("One more").getString("action"))
        assertEquals(2,app.act(node("Even").getString("action")).getInt("count"))
        assertEquals(2,app.act(node("Even").getString("action")).getInt("count"))
    }
    private fun commands(preview: ToolPreview,surface: String)=checkNotNull(preview.surfaces).getJSONObject(surface).getJSONArray("commands").let {commands ->
        List(commands.length()) {commands.getJSONObject(it)}
    }
    @Test fun lessonPassesParseAndSmoke(): Unit=runBlocking {
        // lesson.plan is kit/plans/lesson.plan: a drawing of the Moon on its orbit, a field of its angle, and a button that animates the angle
        // from 0 to 360 in 5 s. The kit tests of lesson.plan have the same plan and steps.
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        val sent=mutableSetOf<String>()
        val spec=parsedAndSmoked(sandbox,"lesson","ru",observeReplay=timerIds(sent))
        assertEquals(listOf("orbit_view"),spec.surfaces.keys.toList())
        assertEquals(setOf("anim"),sent)
        // The Moon is the circle of sand.
        fun moon(preview: ToolPreview)=commands(preview,"orbit_view").single {it.getString("color") == "#e7d3a8"}.let {it.getDouble("x") to it.getDouble("y")}
        val app=Host(context,sandbox,spec,"appfile-lesson")
        fun angle(value: Int)=JSONObject().put("type","action").put("actionId","angle_field").put("value",value)
        try {
            assertEquals(emptyMap<String,Int>(),app.open().timers)
            assertEquals(160.0 to 50.0,moon(app.send(angle(90))))
            // Five actions of the angle field, from 50 to 90 degrees: each reply draws again and names no timer.
            val action=medianHostMs(app,"lesson action",{angle(50+10*it)}) {assertEquals(emptyMap<String,Int>(),it.timers)}
            assertTrue("median $action ms",action<250)
            val started=app.send(press("lap_button"))
            assertEquals(mapOf("anim" to 50),started.timers)
            assertEquals(270.0 to 160.0,moon(started))
            val tick=medianHostMs(app,"lesson timer",{timer("anim")}) {assertEquals(mapOf("anim" to 50),it.timers)}
            assertTrue("median $tick ms",tick<50)
            // The change runs on the real clock to its end: the last timer event gives 360 and no timer.
            val last=withTimeout(10_000) {
                var reply: ToolPreview
                do {delay(50);reply=app.send(timer("anim"))} while(reply.timers != emptyMap<String,Int>())
                reply
            }
            assertEquals(360.0,JSONObject(last.state).getDouble("angle"),0.0)
            assertEquals(270.0 to 160.0,moon(last))
        } finally {app.close()}
    }
    @Test fun eachShapeAndColorPassesParseAndSmoke(): Unit=runBlocking {
        // shapes.plan is kit/test/shapes.plan: each shape kind, each outline form, each over a list, each kind of color, a text, and animate
        // beside a timer with rounds. The kit test of the two timers has the same plan.
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        val sent=mutableSetOf<String>()
        val spec=parsedAndSmoked(sandbox,"shapes",observeReplay=timerIds(sent))
        assertEquals(listOf("art_view","laps_bar"),spec.surfaces.keys.toList())
        assertEquals(setOf("anim","laps_bar"),sent)
        val app=Host(context,sandbox,spec,"appfile-shapes")
        try {
            // The kit color surface is the host role surface, which follows the theme (L1 item 6).
            assertEquals(listOf("rect" to "surface","roundRect" to "#a16207","polyline" to "default","circle" to "#336699","arc" to "#80ff0000","line" to "muted",
                "polygon" to "success","polyline" to "warning","text" to "accent","rect" to "#facc15","rect" to "#facc15","circle" to "default"),
                commands(app.open(),"art_view").map {it.getString("type") to it.getString("color")})
            app.send(press("laps_start"))
            // One timers list for the bar of the rounds and for the timed change, each by its own id.
            assertEquals(mapOf("laps_bar" to 1000,"anim" to 50),app.send(press("grow_button")).timers)
        } finally {app.close()}
    }
    @Test fun aReplyAtTheByteLimitPassesTheHostPath(): Unit=runBlocking {
        // bytes.plan is kit/test/bytes.plan: 350 lines and a text of the pad on one surface. The kit test of the byte limit sends the same values:
        // a pad of 773 characters gives a reply of exactly 32768 bytes, and one more character makes the run-time cut the text.
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        val app=Host(context,sandbox,parsedAndSmoked(sandbox,"bytes"),"appfile-bytes")
        // The pad is a live text field: the host sends its text as a liveInput event, and the retained session answers it in 100 ms at most.
        fun pad(text: String)=JSONObject().put("type",SandboxContract.LIVE_INPUT_EVENT).put("actionId","pad_field").put("value",text)
        fun bytes(preview: ToolPreview)=checkNotNull(preview.surfaces).toString().toByteArray(Charsets.UTF_8).size
        try {
            app.open()
            app.send(action("n_field",350))
            val exact=app.send(pad("x".repeat(773)))
            assertEquals(32768 to 351,bytes(exact) to commands(exact,"lines_view").size)
            val cut=app.send(pad("x".repeat(774)))
            assertEquals(31889 to 350,bytes(cut) to commands(cut,"lines_view").size)
            // Five texts of the pad at the byte limit, logged with no time bar: a reply at the byte limit costs the host about 120 ms
            // for each event, an action too, and the kit takes 2 to 5 ms of it (Task 11c). The 50 ms bar is for the plans that a person uses.
            medianHostMs(app,"bytes liveInput",{pad("x".repeat(769+it))}) {assertEquals(351,commands(it,"lines_view").size)}
        } finally {app.close()}
    }
    @Test fun aLiveInputBesideASurfaceTimerKeepsTheTimer(): Unit=runBlocking {
        // A text field beside a timer with rounds on a surface: the kit test of the timer case has the same plan. The reply to a liveInput
        // holds no timers (SandboxContract). This test proves that the host's checks take such a reply and that the kit still moves the timer
        // at the next timer event. The helper Host has no coordinator: in the app, the coordinator keeps its timers when a reply has none
        // (ArtifactCoordinator.kt 110), and the clock of the surface sends a timer event for each timer that it keeps (NativeToolSurface.kt 228 to 234).
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        val text="app(\"Note\", lang=\"en\")\nneed t = timer(\"Run\", 60, rounds=2, rest=10)\nneed note = input(\"text\", \"Note\", \"\")\n"
        val app=Host(context,sandbox,parsedAndSmoked(sandbox,"note",text=text),"appfile-note")
        fun note(value: String)=JSONObject().put("type",SandboxContract.LIVE_INPUT_EVENT).put("actionId","note_field").put("value",value)
        try {
            app.open()
            assertEquals(mapOf("t_bar" to 1000),app.send(press("t_start")).timers)
            val typed=JSONObject(app.send(note("Go")).state)
            assertEquals("Go" to true,typed.getString("note") to typed.getJSONObject("t_s").getBoolean("on"))
            assertEquals(mapOf("t_bar" to 1000),app.send(timer("t_bar")).timers)
            val median=medianHostMs(app,"note liveInput",{note("Go $it")}) {assertTrue(checkNotNull(it.surfaces).length()>0)}
            assertTrue("median $median ms",median<=50)
        } finally {app.close()}
    }
    private fun pointer(surfaceId: String,phase: String,x: Int)=JSONObject().put("type","pointer").put("surfaceId",surfaceId).put("x",x).put("y",60).put("phase",phase).put("pointer",0)
    @Test fun model3dPassesParseAndSmoke(): Unit=runBlocking {
        // model3d.plan is kit/plans/model3d.plan: the rover of screen 4, with no sketch. The kit tests of model3d.plan give the same values.
        // The smoke sends each surface a down, a move, an up, a down, and a cancel for pointers 0 and 1.
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        val spec=parsedAndSmoked(sandbox,"model3d","ru")
        assertEquals(listOf("rover_model"),spec.surfaces.keys.toList())
        val app=Host(context,sandbox,spec,"appfile-model3d")
        fun rover()=JSONObject(app.state).getJSONObject("rover").let {listOf(it.getString("selected"),it.getBoolean("exploded"),it.getDouble("turn"))}
        // The faces of a part that is not selected keep 40 % of their alpha: a color of 9 characters.
        fun faces(preview: ToolPreview)=commands(preview,"rover_model").let {it.size to it.count {command -> command.getString("color").length == 9}}
        fun text(preview: ToolPreview,actionId: String)=nodes(JSONObject(preview.ui)).single {it.optString("action") == actionId}.getString("text")
        try {
            val rest=app.open()
            assertEquals(listOf("",false,30.0) to (52 to 0),rover() to faces(rest))
            // The camera chip dims the 41 faces of the other parts, and its second tap ends the selection.
            assertEquals(listOf("camera",false,30.0) to (52 to 41),app.send(press("rover_part_camera")).let {rover() to faces(it)})
            assertEquals(listOf("",false,30.0) to (52 to 0),app.send(press("rover_part_camera")).let {rover() to faces(it)})
            // Taken apart: the same 52 faces at new places, and the button says "Собрать" through a new ui.
            val apart=app.send(press("rover_explode"))
            assertEquals(listOf("",true,30.0) to (52 to 0),rover() to faces(apart))
            assertNotEquals(commands(rest,"rover_model").map {it.toString()},commands(apart,"rover_model").map {it.toString()})
            assertEquals("Собрать",text(apart,"rover_explode"))
            assertEquals("Разобрать",text(app.send(press("rover_explode")),"rover_explode"))
            // A drag of 50 pixels to the right: 0.6 degrees for each pixel turn the rover from 30 to 60 degrees.
            app.send(pointer("rover_model","down",100))
            for(x in listOf(110,130,150)) app.send(pointer("rover_model","move",x))
            assertEquals(listOf("",false,60.0) to 52,app.send(pointer("rover_model","up",150)).let {rover() to commands(it,"rover_model").size})
            // Five moves of a drag through the host path: each one draws the rover again.
            app.send(pointer("rover_model","down",100))
            val median=medianHostMs(app,"model3d pointer",{pointer("rover_model","move",110+10*it)}) {assertTrue(commands(it,"rover_model").isNotEmpty())}
            assertEquals(90.0,rover()[2])
            assertTrue("median $median ms",median<50)
            // The drag still runs. An open ends it (the kit test of an open after a drag has the same rule).
            assertEquals("rover_model",JSONObject(app.state).getJSONObject("drag").getString("surface"))
            val opened=Session(sandbox,spec,"appfile-model3d",app.state).send(JSONObject().put("type","open"))
            assertEquals("",opened.getJSONObject("drag").getString("surface"))
        } finally {app.close()}
    }
    @Test fun aSmallModelBesideADrawingPassesParseAndSmoke(): Unit=runBlocking {
        // model.plan is kit/test/model.plan: a box and a cylinder with a named part, then a drawing of n lines. The kit tests of model.plan give the same values.
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        val spec=parsedAndSmoked(sandbox,"model")
        assertEquals(listOf("toy_model","lines_view"),spec.surfaces.keys.toList())
        val app=Host(context,sandbox,spec,"appfile-model")
        try {
            assertEquals(11 to 1,app.open().let {commands(it,"toy_model").size to commands(it,"lines_view").size})
            // 500 lines pass the byte limit: the reply keeps the whole model and the first lines that fit, and Alphy takes it.
            val full=app.send(JSONObject().put("type","action").put("actionId","n_field").put("value",500))
            val lines=commands(full,"lines_view").size
            assertEquals(11,commands(full,"toy_model").size)
            assertTrue("$lines lines",lines in 1 until 500)
            assertTrue(checkNotNull(full.surfaces).toString().toByteArray(Charsets.UTF_8).size<=32768)
            app.send(pointer("toy_model","down",10))
            app.send(pointer("toy_model","move",300))
            assertEquals(204.0,JSONObject(app.state).getJSONObject("toy").getDouble("turn"),0.0)
        } finally {app.close()}
    }
    /** Each list of rows in the state schema, with its row count. The schema of a plain field is the name of its type. */
    private fun lists(spec: ToolSpec)=spec.schema.keys().asSequence().mapNotNull {field ->
        spec.schema.optJSONObject(field)?.takeIf {it.optString("type") == "list"}?.let {field to it.getInt("maxItems")}
    }.toList()
    /** The state with each list full to its row count less short: the rows of the state first, then copies of them in turn with new ids. */
    private fun full(spec: ToolSpec,state: JSONObject,short: Int): JSONObject {
        for((field,count) in lists(spec)) {
            val rows=state.getJSONArray(field)
            val start=rows.length()
            assertTrue("$field has no row to copy",start>0)
            for(n in start until count-short) rows.put(JSONObject(rows.getJSONObject(n%start).toString()).put("id","r${1000+n}"))
        }
        return state
    }
    /** The action id of each control of a ui: an action, a dismiss action, and the reorder of a list. */
    private fun actionIds(root: JSONObject)=nodes(root).flatMap {node ->
        listOf(node.optString("action"),node.optString("dismissAction"),node.optJSONObject("reorder")?.optString("action").orEmpty())
    }.filter {it.isNotEmpty()}.toSet()
    @Test fun eachPlanAnswersInTime(): Unit=runBlocking {
        // Each saved plan with its lists full: the open event, the add of a list that is one row short, and each action that the smoke step
        // sends from the full state, five times each in the bare sandbox. For a plan with a surface, also its timer or pointer event through
        // the retained session. Alphy's limit for one event is 500 ms. The liveInput events of the live inputs are a set of their own with a limit
        // of 100 ms: a plan with no surface answers them in a fresh sandbox, as it answers an action.
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        // The draft steps and the add of each plan with an add. A list that starts empty gets its first row from the same steps:
        // the steps of tabs-chart are the add steps of tabsChartPassesParseAndSmoke.
        val adds=mapOf("list" to listOf("prodotti_new_nome" to "Burro","prodotti_new_corsia" to "Latticini","prodotti_add" to null),
            "section" to listOf("habits_new_name" to "Біг","habits_add" to null),
            "tabs-chart" to listOf("resumen_view" to "Añadir","gastos_new_concepto" to "Taxi","gastos_new_categoria" to "Transporte","gastos_new_importe" to 300,"gastos_add" to null))
        // The event that starts the surface, and the event of each of five turns.
        val hosted=mapOf("timer-rounds" to (press("timer_start") to {_: Int -> timer("timer_bar")}),
            "lesson" to (press("lap_button") to {_: Int -> timer("anim")}),
            "model3d" to (pointer("rover_model","down",100) to {turn: Int -> pointer("rover_model","move",110+10*turn)}))
        // The actions that show after one earlier event: the pause and the reset after a start, and the back after the next page.
        val later=mapOf("timer-rounds" to ("timer_start" to listOf("timer_pause","timer_reset")),"lesson" to ("lesson_next" to listOf("lesson_back")))
        val open=JSONObject().put("type","open")
        val over=mutableListOf<String>()
        for((name,language) in SAVED_PLANS) {
            val spec=parsedAndSmoked(sandbox,name,language)
            assertEquals(name,name in hosted,spec.surfaces.isNotEmpty())
            val steps=adds[name].orEmpty()
            val start=Session(sandbox,spec,"appfile-$name")
            if(lists(spec).any {(field,_) -> JSONObject(start.state).getJSONArray(field).length() == 0}) steps.forEach {(id,value) -> start.control(id,value)}
            // Each event of a Session goes through Alphy's checks of the state, the event, and the reply.
            val app=Session(sandbox,spec,"appfile-$name",full(spec,JSONObject(start.state),if(steps.isEmpty()) 0 else 1).toString())
            app.send(open)
            val add=steps.lastOrNull()?.let {(id,value) ->
                steps.dropLast(1).forEach {(step,draft) -> app.control(step,draft)}
                val before=JSONObject(app.state)
                val (median,after)=timedEvent(sandbox,spec,"$name-add",before,action(id,value))
                assertNotEquals("$name: the add keeps the state",before.toString(),after.toString())
                app.act(id,value)
                Triple(action(id,value),median,true)
            }
            val opened=app.send(open)
            for((field,count) in lists(spec)) assertEquals("$name $field",count,opened.getJSONArray(field).length())
            val openMs=timedEvent(sandbox,spec,"$name-open",opened,open).first
            // Each action and liveInput event that the smoke step sends from the full state: the list of a branch grows with each event.
            val sent=linkedSetOf<String>()
            smoked(sandbox,spec,name,app.state) {events -> events.filter {JSONObject(it).getString("type") in setOf("action",SandboxContract.LIVE_INPUT_EVENT)}.forEach {sent+=it}}
            // Only the events that the open screen takes: an event of a control that is not on it would time a refusal.
            val root=JSONObject(app.ui)
            fun live(event: JSONObject)=event.getString("type") == SandboxContract.LIVE_INPUT_EVENT
            val (taken,notOnOpen)=sent.map {JSONObject(it)}.partition {event ->
                val id=event.getString("actionId")
                val value=event.get("value").takeUnless {it == JSONObject.NULL}
                runCatching {if(live(event)) ToolUiCatalog.validateLiveInput(root,id,value,opened) else ToolUiCatalog.validateAction(root,id,value,opened)}.isSuccess
            }
            val (inputs,actions)=taken.partition(::live)
            // A plan with a surface answers a liveInput in its retained session, which this loop does not time (aReplyAtTheByteLimitPassesTheHostPath does).
            assertTrue("$name: a live input beside a surface",inputs.isEmpty() || spec.surfaces.isEmpty())
            // An action whose reply keeps the state does no work (a value that the state holds already, or a refusal):
            // the slowest action is the slowest one that changes the state.
            val timed=actions.mapIndexed {index,event ->
                val (median,state)=timedEvent(sandbox,spec,"$name-a$index",opened,event)
                Triple(event,median,state.toString() != opened.toString())
            }
            val typed=inputs.mapIndexed {index,event -> event to timedEvent(sandbox,spec,"$name-live$index",opened,event).first}
            // Each later action on the state after its earlier event, then once more through Alphy's checks.
            val shown=later[name]?.let {(first,ids) -> ids.map {id ->
                val step=Session(sandbox,spec,"appfile-$name",opened.toString())
                step.send(open)
                step.act(first)
                val before=JSONObject(step.state)
                val (median,after)=timedEvent(sandbox,spec,"$name-$id",before,press(id))
                assertNotEquals("$name: $id keeps the state",before.toString(),after.toString())
                step.act(id)
                Triple(press(id),median,true)
            }}.orEmpty()
            val measured=timed+listOfNotNull(add)+shown
            val slowest=measured.filter {it.third}.maxByOrNull {it.second}
            assertNotNull("$name: no action changes the state",slowest)
            val (slow,slowMs)=checkNotNull(slowest)
            // The slowest action once more through Alphy's checks. The add and the later actions went through them above.
            if(timed.any {it.first === slow}) app.send(slow)
            val host=hosted[name]?.let {(begin,turn) ->
                val surface=Host(context,sandbox,spec,"appfile-$name")
                try {
                    surface.open()
                    surface.send(begin)
                    medianHostMs(surface,"$name surface",turn) {assertTrue(checkNotNull(it.surfaces).length()>0)}
                } finally {surface.close()}
            }
            fun idsOf(events: List<JSONObject>)=events.map {it.getString("actionId")}
            // untimed: each action id of the open screen that no timed event has.
            val untimed=actionIds(root)-idsOf(measured.map {it.first}+typed.map {it.first}).toSet()
            // The counts: the actions that change the state, the actions that the open screen takes, and the actions of the smoke step.
            Log.i("AppFileProofTest","time $name open=$openMs action=$slowMs ${slow.getString("actionId")} add=${add?.second ?: "-"} host=${host ?: "-"} " +
                "live=${typed.map {"${it.first.getString("actionId")}=${it.second}"}} " +
                "later=${shown.map {"${it.first.getString("actionId")}=${it.second}"}} " +
                "actions=${timed.count {it.third}}/${timed.size}/${sent.size} kept=${idsOf(timed.filterNot {it.third}.map {it.first})} " +
                "notOnOpen=${idsOf(notOnOpen)} untimed=$untimed stateBytes=${opened.toString().toByteArray().size}")
            // The host owns the foreground timer of tea: it sends no event to the source.
            assertEquals(name,if(name == "tea") setOf("demleme_timer") else emptySet(),untimed)
            if(openMs>=250) over+="$name open $openMs ms"
            // Each measured action, also an action whose reply keeps the state.
            for((event,median) in measured) if(median>=250) over+="$name action ${event.getString("actionId")} $median ms"
            for((event,median) in typed) if(median>100) over+="$name liveInput ${event.getString("actionId")} $median ms"
            if(host != null && host>=50) over+="$name host $host ms"
        }
        assertEquals(emptyList<String>(),over)
    }
    /** The action of the node of a ui with this text. */
    private fun actionOf(spec: ToolSpec,text: String)=nodes(spec.ui).single {it.optString("text") == text}.getString("action")
    @Test fun aPlainValueThatAnActionWritesKeepsTheRuleOfItsField(): Unit=runBlocking {
        // Item 5 of the final review: a division by zero, a doubling past the largest safe number, and a text that grows past 2048 characters.
        // Before the fix, executeEvent refuses each reply. The kit test of item 5 has the same plan.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"plain",text=PLAIN_VALUES)
        val app=Session(sandbox,spec,"appfile-plain",JSONObject(spec.initialState).put("n",4503599627370496L).put("note","x".repeat(2040)).toString())
        val divide=actionOf(spec,"Divide")
        assertEquals(3.0,app.act(divide).getDouble("r"),0.0)
        app.act("b_field",0)
        // A number that is not finite keeps the value from before the action.
        assertEquals(3.0,app.act(divide).getDouble("r"),0.0)
        assertEquals(9007199254740991L,app.act(actionOf(spec,"Double")).getLong("n"))
        assertEquals(2048,app.act(actionOf(spec,"Grow")).getString("note").length)
    }
    @Test fun anEventPastTheStateLimitKeepsTheState(): Unit=runBlocking {
        // Item 6: names of 300 characters fill the state of list.plan to 49152 bytes before its 100 rows (the sorted view holds each row again).
        val sandbox=SandboxClient(instrumentation.targetContext)
        val app=Session(sandbox,parsedAndSmoked(sandbox,"list","it"),"appfile-list")
        var rows=3
        while(true) {
            app.live("prodotti_new_nome","N".repeat(297)+"%03d".format(rows))
            app.live("prodotti_new_corsia","Latticini")
            val added=app.act("prodotti_add").getJSONArray("prodotti").length()
            if(added == rows) break
            assertEquals(rows+1,added)
            rows=added
        }
        Log.i("AppFileProofTest","state limit rows=$rows bytes=${app.state.toByteArray().size}")
        assertTrue("$rows rows",rows in 50 until 100)
    }
    @Test fun aChoiceStaysInsideItsOptions(): Unit=runBlocking {
        // Item 7: the remove of the row whose name the choice holds, and a button that sets a choice to a value that is no option.
        // The kit test of item 7 has the same plan.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"pick",text=PICK)
        val app=Session(sandbox,spec,"appfile-pick")
        assertEquals("Bo",app.act("people_remove",row("r1",null)).getString("who"))
        assertEquals("a",app.act(actionOf(spec,"Reset")).getString("k"))
    }
    @Test fun aStaticScreenKeepsOneHiddenField(): Unit=runBlocking {
        // Item 2: a plan with no state value keeps one hidden field d0, because Alphy keeps 1 to 40 state fields. The kit test of item 2 has the same plan.
        val spec=parsedAndSmoked(SandboxClient(instrumentation.targetContext),"static",
            text="app(\"T\", lang=\"en\")\nneed intro = content(\"Hello\")\nneed note = card(\"Title\", \"Text\")")
        assertEquals(setOf("d0"),JSONObject(spec.initialState).keys().asSequence().toSet())
    }
    @Test fun aPlanOverTheLengthCapIsAFaultOfTheHost(): Unit=runBlocking {
        // Item 9 of the final review: the host reads at most 65536 characters of a plan, with no call of the sandbox. A plan of 65536 characters goes to the kit.
        val head="app(\"Long\", lang=\"en\")\nroot = Column([Text(\""
        fun text(length: Int)=head+"x".repeat(length-head.length-4)+"\")])"
        assertEquals(65537,text(65537).length)
        val cap=compiler.compile(text(65537))
        assertEquals("faults" to listOf("the plan has 65537 characters, and Alphy reads at most 65536. Use shorter texts and fewer lines"),cap.status to cap.faults)
        val kit=compiler.compile(text(65536))
        assertTrue(kit.faults.toString(),kit.status == "faults" && kit.faults.isNotEmpty() && kit.faults.none {"reads at most 65536" in it})
        // The calls of a reply keep the cap (loop plan, task 5): a reply past it, and a merge of two shorter texts whose plan passes it.
        assertEquals(AppFileRead.Plan(text(65537),cap),compiler.compileReply(text(65537)))
        val merged=planOf(compiler.patch(text(40000),"note = Text(\"${"y".repeat(30000)}\")"))
        assertEquals(listOf("faults",listOf("the plan has ${merged.text.length} characters, and Alphy reads at most 65536. Use shorter texts and fewer lines")),
            listOf(merged.compilation.status,merged.compilation.faults))
        assertTrue("${merged.text.length}",merged.text.length>65536)
        // A stored state past 49152 bytes never goes to the sandbox, and it is a status, so no repair call sends it to a model.
        assertEquals("INVALID_REQUEST",compiler.compile(plan("calc"),AppFilePrevious(plan("calc"),"{}","x".repeat(49153))).status)
    }
    /**
     * The behavior scenarios of the plans that a model wrote (appfile/model-scenarios.json): the steps of each scenario, written from its request,
     * through the agent step checker on a disposable copy of the plan that the sandbox compiled. A step presses or types through the action id of a
     * real control of the screen, in the sandbox, not on the screen. Each scenario runs, and the test asserts at the end that none failed.
     * The folder appfile-model-scenarios of the external files gets the result of each scenario and the screen text after its last step.
     */
    @Test fun eachScenarioOfAModelPlanPasses(): Unit=runBlocking {
        val sandbox=SandboxClient(instrumentation.targetContext)
        val scenarios=JSONArray(instrumentation.context.assets.open("appfile/model-scenarios.json").use {it.readBytes().decodeToString()})
        val results=List(scenarios.length()) {index ->
            val scenario=scenarios.getJSONObject(index)
            val id=scenario.getString("id")
            val result=JSONObject().put("id",id).put("kind",scenario.getString("kind")).put("name",scenario.getString("name"))
            runCatching {
                val compiled=compiler.compile(plan("model/$id"))
                val spec=ToolSpec.parse(checkNotNull(compiled.definition) {"${compiled.status} ${compiled.faults}"}).withAppLanguage(compiled.language)
                checked(sandbox,spec,"appfile-model-$id",scenario.getJSONArray("steps")).first
            }.onSuccess {outcome ->
                result.put("passed",outcome.failure == null).put("expectations",outcome.expectations).put("failure",outcome.failure ?: JSONObject.NULL)
                    .put("screen",outcome.final?.text ?: JSONObject.NULL)
            }.onFailure {result.put("passed",false).put("failure","${it.javaClass.simpleName}: ${it.message}")}
            Log.i("AppFileProofTest","scenario $id passed=${result.getBoolean("passed")} ${result.opt("failure")}")
            result
        }
        val folder=File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),"appfile-model-scenarios")
        check(folder.isDirectory || folder.mkdirs())
        File(folder,"results.json").writeText(JSONArray(results).toString(2))
        assertEquals(emptyList<String>(),results.filterNot {it.getBoolean("passed")}.map {"${it.getString("id")}: ${it.get("failure")}"})
    }
    @Test fun aHostilePlanGivesAFaultAndTheSandboxStaysUsable(): Unit=runBlocking {
        // Item 14: the hostile plans of item 9 of the final review. Each one gives a fault in the time of a compile, and calc compiles directly after it in the same sandbox.
        val context=instrumentation.targetContext
        val compiler=AppFileCompiler(context,SandboxClient(context))
        val app="app(\"T\", lang=\"en\")"
        val hostile=mapOf("cycle" to "$app\nroot = Column([root, root])",
            "growth" to "$app\n\$/n = 0\n\$/a := text(\$/b) + text(\$/b) + \"x\"\n\$/b := \$/a\nb1 = Button(\"go\", inc(\$/n))\nroot = Column([Text(\$/a), Text(text(\$/n)), b1])",
            "each" to "$app\nneed d = drawing(\"D\", 320, 240, [each(500, each(500, each(500, circle(1, 1, 1))))])",
            "map" to "$app\n\$/r = [${(0 until 100).joinToString(", ")}]\n\$/x := len(map(\$/r, map(\$/r, map(\$/r, map(\$/r, \$it)))))\nroot = Column([Text(text(\$/x))])",
            // The other budgets of the kit: the most parts of one screen, a text that a button doubles, and a join of joins.
            "parts" to (listOf(app)+(0 until 18).map {"c$it = Column([c${it+1}, c${it+1}])"}+listOf("c18 = Text(\"x\")","root = Column([c0])")).joinToString("\n"),
            "double" to "$app\n\$/s = \"ab\"\nb1 = Button(\"go\", set(\$/s, \$/s + \$/s))\nroot = Column([Text(\$/s), b1])",
            "join" to "$app\n\$/r = [${(0 until 100).joinToString(", ")}]\n\$/s = \"${"x".repeat(2000)}\"\n\$/x := len(join(map(\$/r, join(map(\$/r, \$/s), \"\")), \"\"))\nroot = Column([Text(text(\$/x))])")
        val calc=plan("calc")
        val seen=hostile.map {(name,text) ->
            val started=SystemClock.elapsedRealtime()
            val result=compiler.compile(text)
            val took=SystemClock.elapsedRealtime()-started
            val after=compiler.compile(calc)
            Log.i("AppFileProofTest","hostile $name status=${result.status} ms=$took faults=${result.faults} calc=${after.status}")
            listOf(name,result.status,result.faults.isNotEmpty(),took<APP_FILE_COMPILE_MS,after.status)
        }
        assertEquals(hostile.keys.map {listOf(it,"faults",true,true,"ok")},seen)
    }

    // The calls of the plan step and the change step (loop plan, task 5): each one is one evaluation of the sandbox.
    /** A call five times: the log prints its times and their median with the label, and the result of the last call comes back. */
    private suspend fun <T> timed(label: String,call: suspend ()->T): T {
        val runs=List(5) {val started=SystemClock.elapsedRealtime();call() to SystemClock.elapsedRealtime()-started}
        val times=runs.map {it.second}
        Log.i("AppFileProofTest","$label times ms=$times median=${times.sorted()[2]}")
        return runs.last().first
    }
    private fun planOf(read: AppFileRead)=read as AppFileRead.Plan
    /** The stored app of a plan after the events, in the forms of the host: the app file, the schema of its spec, and the state of the last reply. */
    private suspend fun stored(sandbox: SandboxClient,name: String,language: String="en",text: String=plan(name),events: suspend Session.()->Unit): AppFilePrevious {
        val spec=parsedAndSmoked(sandbox,name,language,text)
        val app=Session(sandbox,spec,"appfile-$name")
        app.events()
        return AppFilePrevious(text,spec.schema.toString(),app.state)
    }
    @Test fun aFencedOutsideReplyIsRead(): Unit=runBlocking {
        assertEquals(AppFileRead.Outside("game"),compiler.compileReply("```\noutside(\"game\")\n```"))
    }
    @Test fun aFencedPlanCompilesClean(): Unit=runBlocking {
        val read=planOf(timed("compileReply calc") {compiler.compileReply("```\n${plan("calc")}```")})
        assertEquals(read.compilation.faults.toString(),"ok",read.compilation.status)
        // The fence lines go, and the text is the plan that the kit compiled.
        assertEquals(plan("calc"),read.text)
    }
    @Test fun aRepairReplyMergesAndCompiles(): Unit=runBlocking {
        val line="need perPerson = figure(\"money\", \"Per person\", \$/bill * (1 + \$/tip / 100) / \$/people, example=[{bill: 80, tip: 15, people: 4}, 23])"
        val broken=plan("calc").replace("/ \$/people, example","/ , example")
        assertNotEquals(plan("calc"),broken)
        assertEquals("faults",compiler.compile(broken).status)
        val read=planOf(timed("patch calc repair") {compiler.patch(broken,"Here is the fix:\n```\n$line\n```")})
        assertEquals(read.compilation.faults.toString(),"ok",read.compilation.status)
        // The line of the reply takes the place of the broken line, and the prose and the fences of the reply go.
        assertEquals(plan("calc"),read.text)
    }
    @Test fun aChangeKeepsTheRowsOfAList(): Unit=runBlocking {
        val sandbox=SandboxClient(instrumentation.targetContext)
        // Milk (r1) is bought and Bread (r2) is gone: two stored rows. The kit test of a figure added beside a list has the same steps.
        val previous=stored(sandbox,"shopping") {act("items_remove",row("r2",null));act("items_done",row("r1",true))}
        val figure="need bought = figure(\"count\", \"Bought\", count(\$/items, \$done), example=[{items: [{done: true}]}, 1])"
        val read=planOf(timed("patch shopping change") {compiler.patch(previous.appFile,figure,previous)})
        assertEquals(read.compilation.faults.toString(),"ok",read.compilation.status)
        assertEquals(previous.appFile+figure+"\n",read.text)
        val state=JSONObject(checkNotNull(read.compilation.state))
        val items=state.getJSONArray("items")
        assertEquals(listOf("r1","r3") to listOf(true,false),ids(items) to List(items.length()) {items.getJSONObject(it).getBoolean("done")})
        assertEquals("1",state.getJSONObject("d").getString("bought_text"))
    }
    @Test fun aChangeThatTheSavedDataBlocksSaysSo(): Unit=runBlocking {
        val sandbox=SandboxClient(instrumentation.targetContext)
        val text="app(\"Sizes\", lang=\"en\")\nneed size = input(\"number\", \"Size\", 3, min=0, max=10)\n"
        val previous=stored(sandbox,"sizes",text=text) {control("size_field",5)}
        assertEquals(5,JSONObject(previous.state).getInt("size"))
        // The saved number of the size blocks a change that makes it a text.
        val blocked=planOf(compiler.patch(text,"need size = input(\"text\", \"Size\", \"3\")",previous)).compilation
        assertEquals(blocked.faults.toString(),listOf("faults",true,false,null),listOf(blocked.status,blocked.blockedBySavedData,blocked.savedDataMismatch,blocked.state))
        // A stored state that its app file does not give is the one fault of the kit.
        val mismatch=compiler.compile(text,previous.copy(appFile="need x = nothing(1)"))
        assertEquals(mismatch.faults.toString(),listOf("faults",false,true),listOf(mismatch.status,mismatch.blockedBySavedData,mismatch.savedDataMismatch))
    }
    @Test fun aHostileTextStaysData(): Unit=runBlocking {
        // Quotes, backslashes, a line separator, the end of a script tag, and a text that gives each compile an empty app if it runs as code.
        val hostile="\" ' \\ \\\"   </script> \"))});globalThis.compileAppFile=async()=>({faults:[],notes:[],definition:{},language:\"en\",hole:false,state:null});//"
        val read=planOf(compiler.compileReply("```\n$hostile\n```"))
        // The reply comes back whole, and the kit reports its line: the text did not replace the compile.
        assertEquals("$hostile\n",read.text)
        assertEquals("faults",read.compilation.status)
        // The same text in a plan and in a stored row: the change compiles, and the row keeps the text.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val previous=stored(sandbox,"shopping") {act("items_done",row("r1",true))}
        val state=JSONObject(previous.state).apply {getJSONArray("items").getJSONObject(0).put("name",hostile)}
        val figure="need bought = figure(\"count\", \"Bought\", count(\$/items, \$done), example=[{items: [{done: true}]}, 1])"
        val changed=planOf(compiler.patch("# $hostile\n${previous.appFile}",figure,previous.copy(state=state.toString()))).compilation
        assertEquals(changed.faults.toString(),"ok",changed.status)
        assertEquals(hostile,JSONObject(checkNotNull(changed.state)).getJSONArray("items").getJSONObject(0).getString("name"))
    }
    @Test fun aStoredStateAtTheLimitGoesThroughWithAPlan(): Unit=runBlocking {
        // The rows of anEventPastTheStateLimitKeepsTheState: names of 300 characters until an add would take the state past 49152 bytes.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val previous=stored(sandbox,"list","it") {
            var rows=3
            while(true) {
                live("prodotti_new_nome","N".repeat(297)+"%03d".format(rows))
                live("prodotti_new_corsia","Latticini")
                val added=act("prodotti_add").getJSONArray("prodotti").length()
                if(added == rows) break
                rows=added
            }
        }
        val bytes=previous.state.toByteArray().size
        val rows=ids(JSONObject(previous.state).getJSONArray("prodotti"))
        Log.i("AppFileProofTest","stored state bytes=$bytes rows=${rows.size} schema bytes=${previous.stateSchema.toByteArray().size}")
        assertTrue("$bytes bytes",bytes>48_000)
        val same=timed("compile list at the state limit") {compiler.compile(previous.appFile,previous)}
        assertEquals(same.faults.toString(),"ok",same.status)
        assertEquals(rows,ids(JSONObject(checkNotNull(same.state)).getJSONArray("prodotti")))
        // A reply that writes a line again, merged and compiled with the same stored app.
        val line=previous.appFile.lines().single {it.startsWith("need daComprare")}
        val patched=planOf(timed("patch list at the state limit") {compiler.patch(previous.appFile,line,previous)}).compilation
        assertEquals(patched.faults.toString(),"ok",patched.status)
        assertEquals(rows,ids(JSONObject(checkNotNull(patched.state)).getJSONArray("prodotti")))
    }
    @Test fun thePromptAssetFillsItsTemplates() {
        val prompt=AppFilePrompt.load(instrumentation.targetContext)
        // The sections Repair and Change of the asset end the two templates.
        val sections=JSONObject(instrumentation.targetContext.assets.open("appfile/prompt.json").use {it.readBytes().decodeToString()})
        val repair=prompt.repairText("T","P",listOf("a","b"))
        assertTrue(repair,repair.startsWith("T\n\n") && "\nP\n" in repair && "\n- a\n- b\n" in repair && repair.endsWith(sections.getString("repair")))
        // One pass: a placeholder text of the request stays, the plan keeps $/x, and the plan has no blank line at its end.
        val change=prompt.changeText("Add {{plan}} and {{faults}}","need a = counter(\"A\")\n\$/x = 1\n\n")
        assertTrue(change,change.startsWith("Add {{plan}} and {{faults}}\n\n") && change.endsWith("\$/x = 1\n\n${sections.getString("change")}"))
    }
}
