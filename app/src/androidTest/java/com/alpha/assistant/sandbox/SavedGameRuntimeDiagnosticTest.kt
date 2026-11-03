package com.alpha.assistant.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.GraphicsContract
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Failure cases: saved game cost exceeds the limit, or host completion exceeds script time.
 * The wrapper measures script time only. Host time also includes IPC, scheduling, and JSON validation.
 */
@RunWith(AndroidJUnit4::class)
class SavedGameRuntimeDiagnosticTest {
    @Test fun savedArkanoidScriptAndHostLatency(): Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val assets=instrumentation.context.assets
        for(file in checkNotNull(assets.list("runtime-replay")).sorted())for(pacingMs in listOf(0L,16L)) {
            val raw=assets.open("runtime-replay/$file").bufferedReader().use {it.readText()}
            val spec=JSONObject(raw)
            val source="""
                const diagnosticStart=Date.now();
                const diagnosticResult=(function(input){${spec.getString("source")}
                })(input);
                diagnosticResult.state.__runtimeScriptMs=Date.now()-diagnosticStart;
                return diagnosticResult;
            """.trimIndent()
            val session=GraphicsSession(instrumentation.targetContext)
            var state=spec.getJSONObject("initialState").toString()
            val hostMs=mutableListOf<Double>()
            val scriptMs=mutableListOf<Double>()
            var status="NOT_STARTED"
            var eventIndex=-2
            var eventType="open"
            var hostElapsedMs: Double?=null
            fun accept(result: SandboxResult) {
                status=result.status
                if(status=="OK") {
                    val next=JSONObject(checkNotNull(result.json)).getJSONObject("state")
                    scriptMs+=next.getDouble("__runtimeScriptMs")
                    next.remove("__runtimeScriptMs");state=next.toString()
                }
                if(status!="OK")println("SAVED_GAME_RUNTIME_FAILURE file=$file pacingMs=$pacingMs eventIndex=$eventIndex eventType=$eventType hostElapsedMs=$hostElapsedMs status=$status")
                assertEquals("$file pacingMs=$pacingMs eventIndex=$eventIndex eventType=$eventType hostElapsedMs=$hostElapsedMs", "OK",status)
            }
            try {
                accept(session.open(source,state))
                eventIndex=-1;eventType="start"
                accept(session.event(GraphicsContract.event("start"),state))
                repeat(300) {index ->
                    if(pacingMs>0)delay(pacingMs)
                    val event=when {
                        index%100==0 -> GraphicsContract.event("restart")
                        index%10==0 -> GraphicsContract.event("control",direction=if(index%20==0)"left" else "right")
                        else -> GraphicsContract.event("frame",16)
                    }
                    eventIndex=index;eventType=event.getString("type")
                    val begin=System.nanoTime()
                    val result=session.event(event,state)
                    hostElapsedMs=(System.nanoTime()-begin)/1_000_000.0
                    hostMs+=checkNotNull(hostElapsedMs)
                    accept(result)
                }
            } finally {
                session.close()
                fun stats(values: List<Double>): String {
                    val sorted=values.sorted()
                    return if(sorted.isEmpty())"empty" else "p50=${sorted[sorted.size/2]} p95=${sorted[((sorted.size-1)*0.95).toInt()]} max=${sorted.last()}"
                }
                val hash=java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") {"%02x".format(it)}
                println("SAVED_GAME_RUNTIME file=$file sha256=$hash pacingMs=$pacingMs status=$status completed=${scriptMs.size} scriptMs=[${stats(scriptMs)}] hostMs=[${stats(hostMs)}] deadlineMs=${GraphicsContract.EVALUATION_MS}")
            }
        }
    }
}
