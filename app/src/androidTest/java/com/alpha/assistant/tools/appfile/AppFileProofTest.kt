package com.alpha.assistant.tools.appfile

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.GraphicsSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.GeneratedOutputDetail
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolGenerationSmoke
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolSmokeFailure
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The kit bundle in the sandbox: the report of a saved plan, the fault of a line, an overlapping call, the time of a compile, compiled apps and each node of the node table in Alphy's checks, events on lists, the quiz, tabs, and a chart, the cards fixture, lists full to the render work limit, the time of an event, the held needs of tabs under an author's root, the timers, the written section, and the free forms. */
@RunWith(AndroidJUnit4::class)
class AppFileProofTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val compiler get()=AppFileCompiler(instrumentation.targetContext,SandboxClient(instrumentation.targetContext))
    private fun plan(name: String)=instrumentation.context.assets.open("appfile/$name.plan").use {it.readBytes().decodeToString()}

    @Test fun compilesAPlanInTheSandbox(): Unit=runBlocking {
        val result=compiler.compile(plan("calc"))
        assertTrue(result.status,result.status in setOf("faults","ok"))
        assertEquals(emptyList<String>(),result.faults)
    }
    @Test fun reportsAFaultOfALine(): Unit=runBlocking {
        val result=compiler.compile("need x = nothing(1)")
        assertEquals("faults",result.status)
        assertEquals(result.faults.toString(),1,result.faults.count {"nothing" in it})
    }
    @Test fun aCallThatOverlapsIsBusyNotAHang(): Unit=runBlocking {
        val text=plan("calc")
        val results=withTimeout(2*APP_FILE_COMPILE_MS+4_000) {
            List(2) {async(Dispatchers.Default) {compiler.compile(text)}}.awaitAll()
        }
        results.forEach {assertTrue(it.status,it.status in setOf("faults","ok","BUSY"))}
    }
    @Test fun compileTakesUnderOneSecond(): Unit=runBlocking {
        val text=plan("quiz")
        val times=List(5) {
            val started=SystemClock.elapsedRealtime()
            val result=compiler.compile(text)
            assertEquals(result.toString(),"ok",result.status)
            assertEquals(emptyList<String>(),result.faults)
            SystemClock.elapsedRealtime()-started
        }
        val median=times.sorted()[2]
        Log.i("AppFileProofTest","compile times ms=$times median=$median")
        assertTrue("median $median ms of $times",median<1000)
    }
    /** A saved plan through compile, ToolSpec.parse, and the smoke step as AppAgent.validate builds it. */
    private suspend fun parsedAndSmoked(sandbox: SandboxClient,name: String,language: String="en",text: String=plan(name),observeReplay: ((List<String>)->Unit)?=null): ToolSpec {
        val result=AppFileCompiler(instrumentation.targetContext,sandbox).compile(text)
        assertEquals(result.faults.toString(),"ok",result.status)
        assertEquals(language,result.language)
        val spec=ToolSpec.parse(checkNotNull(result.definition))
        // The script provider is never asked: the smoke run makes no model call.
        try {
            ToolGenerationSmoke(sandbox,spec,"appfile-$name",1,spec.initialState,checkActive={},
                generation=ToolGenerationService(AgentScriptProvider(),sandbox),observeReplay=observeReplay).run()
        } catch(failure: ToolSmokeFailure) {
            // The failure itself has no message: the validator's text is the detail of its cause.
            throw AssertionError("smoke ${failure.event} at ${failure.path}: ${(failure.cause as? GeneratedOutputDetail)?.detail ?: failure.cause} replay=${failure.replay}",failure)
        }
        return spec
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
    @Test fun calcPassesParseAndSmoke(): Unit=runBlocking {
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"calc")
        // Five events in sequence, each on the state of the reply before it. Alphy's limit for one event is 500 ms.
        var state=JSONObject(spec.initialState)
        val times=listOf(Triple("bill_field",200,"57.50"),Triple("tip_field",20,"60.00"),Triple("people_field",5,"48.00"),
            Triple("bill_field",100,"24.00"),Triple("tip_field",10,"22.00")).map {(actionId,value,perPerson) ->
            val input=JSONObject().put("artifactId","appfile-calc").put("revision",1).put("state",state)
                .put("event",JSONObject().put("type","action").put("actionId",actionId).put("value",value))
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
    /** Events in sequence through ToolGenerationService.executeEvent: Alphy checks each event against the screen before it, and each reply. */
    private class Session(private val sandbox: SandboxClient,private val spec: ToolSpec,private val id: String) {
        private val generation=ToolGenerationService(AgentScriptProvider(),sandbox)
        var ui=spec.ui.toString()
            private set
        var state=spec.initialState
            private set
        suspend fun act(actionId: String,value: Any?=null): JSONObject {
            val event=JSONObject().put("type","action").put("actionId",actionId).put("value",value ?: JSONObject.NULL)
            val preview=generation.executeEvent(spec,id,1,state,event,ui) {source,input -> sandbox.execute(source,input.toString())}
            state=preview.state
            ui=preview.ui
            return JSONObject(state)
        }
    }
    private fun ids(rows: JSONArray)=List(rows.length()) {rows.getJSONObject(it).getString("id")}
    private fun row(key: String,value: Any?)=JSONObject().put("key",key).put("value",value ?: JSONObject.NULL)
    @Test fun listPassesParseAndSmoke(): Unit=runBlocking {
        val sandbox=SandboxClient(instrumentation.targetContext)
        val app=Session(sandbox,parsedAndSmoked(sandbox,"list","it"),"appfile-list")
        app.act("prodotti_new_nome","Burro")
        app.act("prodotti_new_corsia","Latticini")
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
    /** The median time in ms of one event, five times in the bare sandbox, which the log prints with its label. */
    private suspend fun medianEventMs(sandbox: SandboxClient,spec: ToolSpec,label: String,state: JSONObject,actionId: String,value: Any?): Long {
        val input=JSONObject().put("artifactId","appfile-$label").put("revision",1).put("state",state)
            .put("event",JSONObject().put("type","action").put("actionId",actionId).put("value",value ?: JSONObject.NULL)).toString()
        val times=List(5) {
            val started=SystemClock.elapsedRealtime()
            val reply=sandbox.execute(spec.source,input)
            val took=SystemClock.elapsedRealtime()-started
            assertEquals(reply.status,"OK",reply.status)
            took
        }
        val median=times.sorted()[2]
        Log.i("AppFileProofTest","$label event times ms=$times median=$median")
        return median
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
        app.act("gastos_new_concepto","Taxi")
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
    private fun press(actionId: String)=JSONObject().put("type","action").put("actionId",actionId).put("value",JSONObject.NULL)
    private fun timer(timerId: String)=JSONObject().put("type","timer").put("timerId",timerId)
    @Test fun timerRoundsPassesParseAndSmoke(): Unit=runBlocking {
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        // The smoke sends each timer of a reply once. A source with timers and no declared surface fails with widget_timer_host.
        val sent=mutableSetOf<String>()
        val rounds=parsedAndSmoked(sandbox,"timer-rounds","de") {events ->
            events.map {JSONObject(it)}.filter {it.getString("type") == "timer"}.forEach {sent+=it.getString("timerId")}
        }
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
            val seen=List(4) {
                // Each timer event comes 300 ms after the end of the phase in the state of the reply before it.
                val end=JSONObject(short.state).getJSONObject("t_s").getLong("end")
                delay(maxOf(0L,end+300-System.currentTimeMillis()))
                val reply=short.send(timer("t_bar"))
                val state=JSONObject(reply.state)
                val t=state.getJSONObject("t_s")
                listOf(if(t.getBoolean("over")) "over" else if(t.getBoolean("resting")) "rest" else "work",t.getInt("round"),state.getInt("t"),reply.timers)
            }
            // The whole timers of each reply: the id and the interval of each timer that runs.
            assertEquals(listOf(listOf("rest",1,1,ticks),listOf("work",2,1,ticks),listOf("rest",2,2,ticks),listOf("over",2,2,emptyMap<String,Int>())),seen)
        } finally {short.close()}
        // Five timer events of the 40 s work phase of timer-rounds through the same path.
        val app=Host(context,sandbox,rounds,"appfile-timer-rounds")
        try {
            app.open()
            app.send(press("timer_start"))
            val times=List(5) {
                val started=SystemClock.elapsedRealtime()
                val reply=app.send(timer("timer_bar"))
                val took=SystemClock.elapsedRealtime()-started
                assertEquals(mapOf("timer_bar" to 1000),reply.timers)
                took
            }
            val median=times.sorted()[2]
            Log.i("AppFileProofTest","timer event times ms=$times median=$median")
            assertTrue("median $median ms of $times",median<50)
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
        app.act("habits_new_name","Біг")
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
    }
}
