package com.alpha.assistant.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.GraphicsContract
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production engine probe. The app manager owns the sole sandbox connection. */
@RunWith(AndroidJUnit4::class)
class AndroidXRuntimeProbeTest {
    @Test fun productionRetentionAndLatency(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("OK",SandboxClient(context).availability().status)
        val session=GraphicsSession(context)
        try {
            assertEquals("OK",session.open("let n=0;return ()=>({commands:[{type:'text',x:1,y:20,text:String(++n),size:12,color:'#ffffff'}]});","{}").status)
            val begin=System.nanoTime()
            repeat(100) {
                val result=session.event(GraphicsContract.event("frame",33));assertEquals("OK",result.status)
                assertEquals((it+2).toString(),JSONObject(result.json!!).getJSONArray("commands").getJSONObject(0).getString("text"))
            }
            val elapsed=(System.nanoTime()-begin)/1_000_000.0
            println("ANDROIDX_PRODUCTION retained=100 elapsedMs=$elapsed meanMs=${elapsed/100}")
        } finally {session.close()}
    }
}
