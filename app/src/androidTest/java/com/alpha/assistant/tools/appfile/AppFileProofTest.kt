package com.alpha.assistant.tools.appfile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The kit bundle in the sandbox: the report of a saved plan, the fault of a line, an overlapping call, and the time of a compile. */
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
            assertTrue(result.toString(),result.status in setOf("faults","ok") && result.faults.isEmpty())
            SystemClock.elapsedRealtime()-started
        }
        val median=times.sorted()[2]
        Log.i("AppFileProofTest","compile times ms=$times median=$median")
        assertTrue("median $median ms of $times",median<1000)
    }
}
