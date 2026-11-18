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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The kit bundle in the sandbox: the report of a saved plan, the fault of a line, an overlapping call, the time of a compile, compiled apps and each node of the node table in Alphy's checks, and the time of an event. */
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
            // The report of quiz.plan is clean. Its list of cards is a fault of the emitter until the kit maps lists (Task 4 of the plan).
            assertTrue(result.toString(),result.status in setOf("faults","ok") && result.faults.all {"is not built yet" in it})
            SystemClock.elapsedRealtime()-started
        }
        val median=times.sorted()[2]
        Log.i("AppFileProofTest","compile times ms=$times median=$median")
        assertTrue("median $median ms of $times",median<1000)
    }
    /** A saved plan through compile, ToolSpec.parse, and the smoke step as AppAgent.validate builds it. */
    private suspend fun parsedAndSmoked(sandbox: SandboxClient,name: String): ToolSpec {
        val result=AppFileCompiler(instrumentation.targetContext,sandbox).compile(plan(name))
        assertEquals(result.faults.toString(),"ok",result.status)
        assertEquals("en",result.language)
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
}
