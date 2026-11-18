package com.alpha.assistant.tools.appfile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolGenerationSmoke
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The kit bundle in the sandbox: the report of a saved plan, the fault of a line, an overlapping call, the time of a compile, and a compiled app in Alphy's checks. */
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
    @Test fun calcPassesParseAndSmoke(): Unit=runBlocking {
        val context=instrumentation.targetContext
        val sandbox=SandboxClient(context)
        val result=AppFileCompiler(context,sandbox).compile(plan("calc"))
        assertEquals(result.faults.toString(),"ok",result.status)
        assertEquals("en",result.language)
        val spec=ToolSpec.parse(checkNotNull(result.definition))
        // The smoke step as AppAgent.validate builds it. The script provider is never asked: the smoke run makes no model call.
        ToolGenerationSmoke(sandbox,spec,"appfile-calc",1,spec.initialState,checkActive={},
            generation=ToolGenerationService(AgentScriptProvider(),sandbox)).run()
        val input=JSONObject().put("artifactId","appfile-calc").put("revision",1).put("state",JSONObject(spec.initialState))
            .put("event",JSONObject().put("type","action").put("actionId","bill_field").put("value",200))
        val reply=sandbox.execute(spec.source,input.toString())
        assertEquals(reply.status,"OK",reply.status)
        val state=JSONObject(checkNotNull(reply.json)).getJSONObject("state")
        assertEquals(200,state.getInt("bill"))
        assertEquals("57.50",state.getJSONObject("d").getString("perPerson_text"))
    }
}
