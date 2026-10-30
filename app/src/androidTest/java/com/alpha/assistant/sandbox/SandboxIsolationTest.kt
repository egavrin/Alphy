package com.alpha.assistant.sandbox

import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SandboxIsolationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val input = """{"artifactId":"smoke","revision":1,"event":{},"state":{"count":1}}"""

    @Test fun isolatedWorkflow(): Unit = runBlocking {
        context.openFileOutput("isolation-sentinel", 0).use { it.write(byteArrayOf(1)) }
        val socket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        Os.close(socket)
        val client = SandboxClient(context)
        suspend fun invoke(source: String, expected: String, data: String = input): SandboxResult {
            val started = System.currentTimeMillis()
            val result = client.execute(source, data)
            println("ISOLATION input=${source.take(100)} expected=$expected observed=${result.status} elapsedMs=${System.currentTimeMillis()-started}")
            assertEquals(expected, result.status)
            return result
        }
        try {
            val happy = invoke("return {state: {count: input.state.count + 1}};", "OK")
            assertEquals(2, JSONObject(happy.json!!).getJSONObject("state").getInt("count"))
            assertNotEquals(Process.myUid(), happy.uid)
            assertNotEquals(Process.myPid(), happy.pid)
            assertTrue(happy.networkDenied)
            assertTrue(happy.privateDataDenied)
            val globals = invoke("return {state: {absent: ['fetch','XMLHttpRequest','WebSocket','require','process','Java','android','std','os'].every(k => typeof globalThis[k] === 'undefined')}};", "OK")
            assertTrue(JSONObject(globals.json!!).getJSONObject("state").getBoolean("absent"))
            val started = System.currentTimeMillis()
            invoke("while (true) {}", "INTERRUPTED")
            assertTrue(System.currentTimeMillis() - started < 5000)
            invoke("const a=[]; while(true) a.push(new Uint8Array(1024*1024));", "SCRIPT_ERROR")
            invoke("function f(){ return f()+1; } return f();", "SCRIPT_ERROR")
            invoke("throw new Error('failure');", "SCRIPT_ERROR")
            invoke("return {state: {}, effects: [{type:'network'}]};", "INVALID_RESULT")
            invoke("return {state: {value: 'x'.repeat(70000)}};", "INVALID_RESULT")
            invoke("return {state: {}};", "INVALID_REQUEST", "{")
            invoke("return {state: {}};", "INVALID_REQUEST", "[".repeat(50)+"]".repeat(50))
            invoke(" ".repeat(70000), "INVALID_REQUEST")
            invoke("return {state: {recovered:true}};", "OK")
        } finally {
            context.deleteFile("isolation-sentinel")
        }
    }
}
