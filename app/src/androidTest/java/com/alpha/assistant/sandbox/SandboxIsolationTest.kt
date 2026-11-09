package com.alpha.assistant.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.GraphicsContract
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** AndroidX production-boundary failures: unavailable features, shared sandbox death, heap/return limits,
 * endless work, cancellation, stale completion, fresh ordinary globals, and retained graphics globals.
 * Isolates share one sandbox process and have the platform's moderate isolation boundary.
 */
@RunWith(AndroidJUnit4::class)
class SandboxIsolationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun input(event: String="{\"type\":\"action\",\"actionId\":\"run\",\"value\":null}")=
        """{"artifactId":"smoke","revision":1,"event":$event,"state":{"count":1}}"""
    private val game="globalThis.n=(globalThis.n||0)+1;return {state:input.state,surfaces:{main:{commands:[{type:'text',x:1,y:20,text:String(globalThis.n),size:12,color:'#ffffff'}]}}};"
    private fun counter(result: SandboxResult)=JSONObject(checkNotNull(result.json)).getJSONObject("surfaces").getJSONObject("main").getJSONArray("commands").getJSONObject(0).getString("text").toInt()
    @Test fun ordinaryEventTypesAndFreshGlobals(): Unit=runBlocking {
        val client=SandboxClient(context)
        val events=listOf("{\"type\":\"preview\"}","{\"type\":\"render\"}","{\"type\":\"action\",\"actionId\":\"run\",\"value\":null}",
            """{"type":"capabilityResults","deliveryId":"00000000-0000-0000-0000-000000000001","entries":[{"requestId":"one","capability":"vibration.play","status":"succeeded","data":{}}]}""",
            """{"type":"recordResults","deliveryId":"00000000-0000-0000-0000-000000000002","entries":[{"requestId":"one","collection":"history","op":"get","status":"notFound"}]}""")
        for(event in events) {
            val result=client.execute("globalThis.privateMarker='first'; return {state:{count:input.state.count+1}};",input(event))
            assertEquals(event,"OK",result.status);assertEquals("androidx.javascriptengine",result.engine)
            assertEquals(2,JSONObject(result.json!!).getJSONObject("state").getInt("count"))
        }
        val clean=client.execute("return {state:{absent:typeof globalThis.privateMarker==='undefined',globals:['fetch','XMLHttpRequest','WebSocket','require','process','Java','std','os','document'].every(k=>typeof globalThis[k]==='undefined')}};",input())
        assertEquals("OK",clean.status);val state=JSONObject(clean.json!!).getJSONObject("state")
        assertTrue(state.getBoolean("absent"));assertTrue(state.getBoolean("globals"))
        println("ANDROIDX_PRODUCTION ordinaryEvents=5 freshGlobals=PASS boundary=shared-sandbox-isolates")
    }
    @Test fun cancelOrdinaryClosesOnlyItsIsolate(): Unit=runBlocking {
        val graphics=GraphicsSession(context)
        try {
            assertEquals("OK",graphics.open(game,"{}").status)
            val job=launch(Dispatchers.Default) {SandboxClient(context).execute("while(true){}",input())}
            delay(50);job.cancelAndJoin()
            assertEquals("OK",graphics.event(GraphicsContract.event("frame",33)).status)
            assertEquals("OK",SandboxClient(context).execute("return {state:input.state};",input()).status)
        } finally {graphics.close()}
    }
    @Test fun cancelGraphicsLeavesOrdinaryAvailableAndOldSessionClosed(): Unit=runBlocking {
        val graphics=GraphicsSession(context)
        val job=launch(Dispatchers.Default) {graphics.open("while(true){}","{}")}
        delay(10);job.cancelAndJoin();graphics.close()
        assertEquals("CLOSED",graphics.event(GraphicsContract.event("frame",33)).status)
        assertEquals("OK",SandboxClient(context).execute("return {state:input.state};",input()).status)
    }
    @Test fun loopOversizeMalformedAndRecovery(): Unit=runBlocking {
        val client=SandboxClient(context)
        val maxValue = 192 * 1024 - """{"state":{"value":""}}""".length
        assertEquals("OK", client.execute("return {state:{value:'x'.repeat($maxValue)}};",input()).status)
        for((source,expected) in listOf("while(true){}" to "TIMEOUT",
            "return {state:{value:'x'.repeat(${maxValue + 1})}};" to "INVALID_RESULT","throw Error('private failure');" to "SCRIPT_ERROR")) {
            val result=client.execute(source,input())
            println("ANDROIDX_FAILURE_STEP kind=${if(source.startsWith("while")) "loop" else if(source.startsWith("function")) "recursion" else if(source.startsWith("return")) "oversize" else "throw"} expected=$expected observed=${result.status}")
            assertEquals(source.take(32),expected,result.status)
        }
        // Some WebView builds report RangeError; the tested 16 KiB-page emulator has a native stack crash.
        val recursion=client.execute("function f(){return f()+1;}return f();",input())
        assertTrue(recursion.status,recursion.status in setOf("SCRIPT_ERROR","SANDBOX_DIED"))
        assertEquals("Fresh execution after recursion must recover without replay","OK",client.execute("return {state:input.state};",input()).status)
        assertEquals("INVALID_REQUEST",client.execute("return {};","{").status)
        assertEquals("OK",client.execute("return {state:input.state};",input()).status)
    }
    @Test fun retainedSessionAndSharedHeapDeathDoNotReplay(): Unit=runBlocking {
        val graphics=GraphicsSession(context)
        try {
            val largeSource = game.padEnd(65536)
            assertEquals("INVALID_REQUEST", GraphicsSession(context).open(largeSource + " ", "{}").status)
            assertEquals(1,counter(graphics.open(largeSource,"{}")))
            repeat(20) {assertEquals(it+2,counter(graphics.event(GraphicsContract.event("frame",33))))}
            val failed=SandboxClient(context).execute("let a=[];while(true)a.push(new Array(100000).fill('memory'));",input())
            assertTrue(failed.status,failed.status in setOf("MEMORY_LIMIT","SANDBOX_DIED"))
            assertEquals("SANDBOX_DIED",graphics.event(GraphicsContract.event("frame",33)).status)
            assertEquals("OK",SandboxClient(context).execute("return {state:input.state};",input()).status)
            assertNotEquals("OK",graphics.event(GraphicsContract.event("frame",33)).status)
        } finally {graphics.close()}
    }
    @Test fun executionDeadlineClosesIsolateWhileMainThreadIsBusy(): Unit=runBlocking {
        val isolate=AndroidJsRuntime.get(context).acquire(retained=true,maxReturnBytes=32768)
        val started=CompletableDeferred<Unit>()
        val task=async(Dispatchers.Main) {started.complete(Unit);isolate.evaluate("while(true){}",25)}
        started.await()
        val entered=java.util.concurrent.CountDownLatch(1)
        val observed=java.util.concurrent.atomic.AtomicReference<String?>()
        val observer=kotlin.concurrent.thread {
            entered.await();Thread.sleep(80);observed.set(isolate.status)
        }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {entered.countDown();Thread.sleep(150)}
            observer.join(1000)
            assertEquals("Native execution must stop while Main cannot dispatch","TIMEOUT",observed.get())
            assertEquals("TIMEOUT",task.await().status)
        } finally {isolate.close();task.cancel();observer.join(1000)}
    }
    /** Failures: continuous callers disagree, a short completion is rejected, delayed watchdog dispatch
     * accepts a late result, runaway work survives, cancellation publishes, or a terminal session retries.
     */
    @Test fun continuousWatchdogIsSharedByRetainedAndDisposableEvents(): Unit=runBlocking {
        assertEquals(100L,GraphicsContract.EVALUATION_MS)
        assertEquals(500L,SandboxContract.EVALUATION_MS)
        val source="const until=Date.now()+40;while(Date.now()<until){};return {state:input.state};"
        val events=listOf(GraphicsContract.event("frame",16),GraphicsContract.event("pointer",x=1.0,y=1.0,phase="down"),
            GraphicsContract.event("control",direction="left"),JSONObject().put("type","timer").put("timerId","clock"))
        val graphics=GraphicsSession(context)
        try {
            assertEquals("OK",graphics.open(source,"{\"count\":1}").status)
            for(event in events)assertEquals(event.toString(),"OK",graphics.event(event).status)
            SandboxClient(context).disposable {evaluate ->
                for(event in events)assertEquals(event.toString(),"OK",evaluate(source,input(event.toString())).status)
                assertEquals("OK",evaluate(source,input()).status)
            }
        } finally {graphics.close()}
    }
    @Test fun monotonicCutoffRejectsLateResultWhenWatchdogCannotDispatch(): Unit=runBlocking {
        val runtime=AndroidJsRuntime.get(context)
        val isolate=runtime.acquire(retained=true,maxReturnBytes=32768)
        // A real isolate plus a blocked watchdog selects the otherwise nondeterministic callback race.
        val field=AndroidJsRuntime::class.java.getDeclaredField("watchdog").apply {isAccessible=true}
        val watchdog=field.get(runtime) as java.util.concurrent.ScheduledThreadPoolExecutor
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        watchdog.execute {entered.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS)}
        try {
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
            val start=System.nanoTime()
            val result=isolate.evaluate("globalThis.attempt=(globalThis.attempt||0)+1;const until=Date.now()+160;while(Date.now()<until){};String(globalThis.attempt);",100)
            println("ANDROIDX_LATE_RESULT status=${result.status} elapsedMs=${(System.nanoTime()-start)/1_000_000.0} watchdog=blocked")
            assertEquals("TIMEOUT",result.status);assertNull(result.json)
            assertEquals("TIMEOUT",isolate.status)
            assertEquals("A terminal isolate must not retry or evaluate again","TIMEOUT",isolate.evaluate("'must not run'",500).status)
        } finally {release.countDown();isolate.close()}
        val next=runtime.acquire(retained=true,maxReturnBytes=32768)
        try {assertEquals("OK",next.evaluate("'fresh'",500).status)} finally {next.close()}
    }
    @Test fun continuousRunawayAndCancellationReleaseTheirOnlyLease(): Unit=runBlocking {
        for(cancel in listOf(false,true)) {
            val graphics=GraphicsSession(context)
            try {
                assertEquals("OK",graphics.open("if(input.event.type==='frame')while(true){};return {state:input.state};","{}").status)
                val task=async(Dispatchers.Default) {graphics.event(GraphicsContract.event("frame",16))}
                if(cancel) {
                    delay(20);task.cancelAndJoin()
                    assertTrue(task.isCancelled)
                } else {
                    val result=withTimeout(2000) {task.await()}
                    assertEquals("TIMEOUT",result.status);assertNull(result.json)
                }
                assertEquals("CLOSED",graphics.event(GraphicsContract.event("frame",16)).status)
            } finally {graphics.close()}
            val next=GraphicsSession(context)
            try {assertEquals("OK",next.open("return {state:input.state};","{}").status)} finally {next.close()}
        }
    }
    @Test fun unavailableIsStableWithoutCreatingEngine(): Unit=runBlocking {
        val runtime=AndroidJsRuntime(context,supported={false})
        val client=SandboxClient(context,runtime)
        repeat(2) {assertEquals("UNAVAILABLE",client.execute("return {state:input.state};",input()).status)}
        assertFalse(AndroidJsRuntime.hasRequiredFeatures {false})
    }
}
