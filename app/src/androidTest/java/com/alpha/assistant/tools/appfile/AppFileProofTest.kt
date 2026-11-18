package com.alpha.assistant.tools.appfile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.GeneratedOutputDetail
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolGenerationSmoke
import com.alpha.assistant.tools.ToolSmokeFailure
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The kit bundle in the sandbox: the report of a saved plan, the fault of a line, an overlapping call, the time of a compile, compiled apps and each node of the node table in Alphy's checks, events on lists and on the quiz, lists full to the render work limit, and the time of an event. */
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
    private suspend fun parsedAndSmoked(sandbox: SandboxClient,name: String,language: String="en",text: String=plan(name)): ToolSpec {
        val result=AppFileCompiler(instrumentation.targetContext,sandbox).compile(text)
        assertEquals(result.faults.toString(),"ok",result.status)
        assertEquals(language,result.language)
        val spec=ToolSpec.parse(checkNotNull(result.definition))
        // The script provider is never asked: the smoke run makes no model call.
        try {
            ToolGenerationSmoke(sandbox,spec,"appfile-$name",1,spec.initialState,checkActive={},
                generation=ToolGenerationService(AgentScriptProvider(),sandbox)).run()
        } catch(failure: ToolSmokeFailure) {
            // The failure itself has no message: the validator's text is the detail of its cause.
            throw AssertionError("smoke ${failure.event} at ${failure.path}: ${(failure.cause as? GeneratedOutputDetail)?.detail ?: failure.cause} replay=${failure.replay}",failure)
        }
        return spec
    }
    @Test fun eachNodeOfTheTablePassesParseAndSmoke(): Unit=runBlocking {
        // nodes.plan is kit/test/nodes.plan: each component of the emitter's node table, and the kit test reads the same text.
        parsedAndSmoked(SandboxClient(instrumentation.targetContext),"nodes")
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
        private var ui=spec.ui.toString()
        private var state=spec.initialState
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
        // rich.plan is kit/test/rich.plan: two lists with a choice of 6 options in each row. Its start rows fill each list to the count that the render work limit leaves.
        val sandbox=SandboxClient(instrumentation.targetContext)
        val spec=parsedAndSmoked(sandbox,"rich")
        val state=JSONObject(spec.initialState)
        for(list in listOf("shirts","shoes")) {
            assertEquals(list,91,spec.schema.getJSONObject(list).getInt("maxItems"))
            assertEquals(list,91,state.getJSONArray(list).length())
        }
        val checked=Session(sandbox,spec,"appfile-rich").act("shoes_done",row("r92",true))
        assertTrue(checked.getJSONArray("shoes").getJSONObject(0).getBoolean("done"))
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
        val input=JSONObject().put("artifactId","appfile-items").put("revision",1).put("state",JSONObject(spec.initialState))
            .put("event",JSONObject().put("type","action").put("actionId","items_done").put("value",row("r50",true))).toString()
        val times=List(5) {
            val started=SystemClock.elapsedRealtime()
            val reply=sandbox.execute(spec.source,input)
            val took=SystemClock.elapsedRealtime()-started
            assertEquals(reply.status,"OK",reply.status)
            took
        }
        val median=times.sorted()[2]
        Log.i("AppFileProofTest","100-row event times ms=$times median=$median")
        assertTrue("median $median ms of $times",median<250)
    }
}
