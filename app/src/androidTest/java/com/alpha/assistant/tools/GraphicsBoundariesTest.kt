package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphicsBoundariesTest {
    private fun frame(commands: String)=GraphicsContract.frame("{\"commands\":$commands}","frame")
    private fun rejects(block: () -> Unit)=assertTrue(runCatching(block).isFailure)
    private val rect="""{"type":"rect","x":0,"y":0,"width":10,"height":10,"color":"#FF0000"}"""
    /** Failure cases: unknown presentation/sizing fields, positioned or multiple viewport surfaces,
     * invalid viewport bounds, stale pointer bounds, and resize without pointer cancellation.
     * Isolated contract checks diagnose cases that rendered input cannot send through the trusted host.
     */
    @Test fun viewportContractIsClosedAndResizeCancelsOwnedInput() {
        val base=JSONObject(ArkanoidFixture.spec())
        assertEquals("app",ToolSpec.parse(base.toString()).presentation)
        base.put("presentation",JSONObject("{\"mode\":\"immersive\"}"))
        base.getJSONArray("surfaces").getJSONObject(0).put("sizing","viewport")
        val spec=ToolSpec.parse(base.toString())
        assertEquals("immersive",spec.presentation)
        assertEquals("viewport",spec.surfaces.getValue("main").sizing)
        rejects {ToolSpec.parse(JSONObject(base.toString()).put("presentation",JSONObject("{\"mode\":\"immersive\",\"extra\":true}")).toString())}
        rejects {ToolSpec.parse(JSONObject(base.toString()).put("presentation",JSONObject("{\"mode\":\"other\"}")).toString())}
        rejects {ToolSpec.parse(JSONObject(base.toString()).apply {getJSONArray("surfaces").getJSONObject(0).put("x",0).put("y",0)}.toString())}
        rejects {ToolSpec.parse(JSONObject(base.toString()).apply {getJSONArray("surfaces").put(JSONObject("{\"id\":\"other\",\"width\":64,\"height\":64}"))}.toString())}
        val resized=surfaceViewport(spec.surfaces.getValue("main"),900,1500,3f)
        assertEquals(300,resized.width);assertEquals(500,resized.height)
        val bounded=surfaceViewport(resized,10000,1,1f)
        assertEquals(2048,bounded.width);assertEquals(64,bounded.height)
        val viewport=GraphicsContract.viewport(resized)
        GraphicsContract.validateSurfaceEvent(viewport,spec.surfaces)
        rejects {GraphicsContract.validateEvent(JSONObject(viewport.toString()).put("width",2049))}
        rejects {GraphicsContract.validateEvent(JSONObject(viewport.toString()).put("extra",true))}
        rejects {GraphicsContract.validateSurfaceEvent(viewport,mapOf("main" to resized.copy(sizing="fixed")))}
        GraphicsContract.validateSurfaceEvent(GraphicsContract.event("pointer",x=300.0,y=500.0),mapOf("main" to resized))
        rejects {GraphicsContract.validateSurfaceEvent(GraphicsContract.event("pointer",x=301.0,y=500.0),mapOf("main" to resized))}
        val input=SurfaceInputState()
        input.events(GraphicsContract.event("pointer",x=20.0,y=30.0,phase="down"))
        val events=input.events(viewport)
        assertEquals(listOf("pointer","viewport"),events.map {it.getString("type")})
        assertEquals("cancel",events.first().getString("phase"))
        assertTrue(input.events(GraphicsContract.event("pointer",phase="up")).isEmpty())
    }
    /** Invalid output types and enum values cannot enter a snapshot. Omission remains distinguishable. */
    @Test fun playbackOutputIsOptionalClosedAndTyped() {
        val spec=ToolSpec.parse(ArkanoidFixture.spec())
        assertNull(ArtifactProtocol.output(spec,JSONObject()).playback)
        for(value in listOf("ready","playing","paused","ended")) {
            val output=JSONObject().put("state",JSONObject(spec.initialState)).put("playback",value)
            com.alpha.assistant.sandbox.SandboxContract.validateResult(output.toString())
            assertEquals(value,ArtifactProtocol.output(spec,output).playback)
        }
        for(value in listOf("unknown",true,2,JSONObject(),JSONObject.NULL)) {
            val output=JSONObject().put("state",JSONObject(spec.initialState)).put("playback",value)
            rejects {com.alpha.assistant.sandbox.SandboxContract.validateResult(output.toString())}
            rejects {ArtifactProtocol.output(spec,output)}
        }
    }
    @Test fun pathsGradientsAndTransformsAreClosedAndBounded() {
        val path="""{"type":"path","segments":[{"type":"move","x":0,"y":0},{"type":"quad","cx":10,"cy":0,"x":10,"y":10},{"type":"cubic","cx1":10,"cy1":10,"cx2":0,"cy2":10,"x":0,"y":0},{"type":"close"}],"gradient":{"type":"linear","x":0,"y":0,"x2":10,"y2":10,"stops":[{"offset":0,"color":"#FF0000"},{"offset":1,"color":"#0000FF"}]}}"""
        frame("[{\"type\":\"group\",\"transform\":[1,0,0,1,4,4],\"children\":[$path]}]")
        rejects {frame("[$path]".replace("\"offset\":1","\"offset\":-1"))}
        rejects {frame("[$path]".replace("\"x2\":10,\"y2\":10","\"x2\":0,\"y2\":0"))}
        rejects {frame("[$path]".replace("\"type\":\"move\"","\"type\":\"line\""))}
        rejects {frame("[{\"type\":\"group\",\"transform\":[1,0,0,0,0,0],\"children\":[$rect]}]")}
        rejects {frame("[{\"type\":\"group\",\"transform\":[10,0,0,10,8192,0],\"children\":[$rect]}]")}
        rejects {frame("[$rect]".replace("\"width\":10","\"width\":8192").replace("\"x\":0","\"x\":1"))}
        rejects {frame("[$rect]".replace("\"x\":0","\"x\":1e309"))}
        rejects {frame("[$rect]".replace("\"type\":\"rect\"","\"unknown\":true,\"type\":\"rect\""))}
        var nested=rect
        repeat(6) {nested="{\"type\":\"group\",\"children\":[$nested]}"}
        rejects {frame("[$nested]")}
        val segments=JSONArray().put(JSONObject("{\"type\":\"move\",\"x\":0,\"y\":0}"))
        repeat(512) {segments.put(JSONObject("{\"type\":\"line\",\"x\":1,\"y\":1}"))}
        rejects {frame("[{\"type\":\"path\",\"segments\":$segments,\"color\":\"#FFFFFF\"}]")}
    }
    @Test fun aggregateSurfaceBudgetAndPositionsRejectOverflow() {
        val budget=GraphicsContract.Budget()
        val commands=JSONArray().apply {repeat(257) {put(JSONObject(rect))}}
        GraphicsContract.frame(JSONObject().put("commands",commands).toString(),"frame",budget)
        rejects {GraphicsContract.frame(JSONObject().put("commands",commands).toString(),"frame",budget)}
        val schema=JSONObject()
        assertEquals(30,GraphicsContract.declaration(JSONObject("{\"id\":\"overlay\",\"width\":64,\"height\":64,\"x\":30,\"y\":20}"),schema).x)
        rejects {GraphicsContract.declaration(JSONObject("{\"id\":\"overlay\",\"width\":64,\"height\":64,\"x\":30}"),schema)}
        rejects {GraphicsContract.declaration(JSONObject("{\"id\":\"overlay\",\"width\":64,\"height\":64,\"x\":4090,\"y\":0}"),schema)}
    }
    @Test fun drawingRestoresSiblingStateAndClipsSurface() {
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val commands=frame("[{\"type\":\"group\",\"transform\":[1,0,0,1,20,0],\"children\":[$rect]},$rect]").getJSONArray("commands")
        drawSurfaceFrame(canvas,ToolSurface("main",16,16),commands,16f,16f)
        assertEquals(android.graphics.Color.RED,bitmap.getPixel(5,5))
        assertEquals(0,bitmap.getPixel(25,5))
        bitmap.recycle()
    }
    @Test fun pointerOwnershipUsesTopSurfaceAndCancelsAtLastCoordinates() {
        val router=SurfacePointerRouter(listOf(ToolSurface("back",100,100,0,0),ToolSurface("front",64,64,20,20)))
        val down=checkNotNull(router.route(10,"down",25.0,30.0))
        assertEquals("front",down.getString("surfaceId"));assertEquals(5.0,down.getDouble("x"),0.0)
        val move=checkNotNull(router.route(10,"move",200.0,200.0))
        assertEquals("front",move.getString("surfaceId"));assertEquals(64.0,move.getDouble("x"),0.0)
        assertNotNull(router.route(11,"down",5.0,5.0));assertNull(router.route(12,"down",6.0,6.0))
        val cancelled=router.cancel()
        assertEquals(2,cancelled.size);assertEquals("cancel",cancelled[0].getString("phase"));assertEquals(64.0,cancelled[0].getDouble("x"),0.0)
        assertNull(router.route(10,"up",25.0,30.0))
        assertEquals(0,router.route(12,"down",5.0,5.0)!!.getInt("pointer"))
        assertNotNull(router.route(12,"up",5.0,5.0))
    }
    @Test fun lifecycleCancellationFollowsConsumedPointerOrder() {
        fun name(event: JSONObject)=if(event.getString("type")=="pointer")event.getString("phase") else event.getString("type")
        fun fixture(block: (SurfaceEventQueue,SurfacePointerRouter,SurfaceInputState,MutableList<JSONObject>)->Unit) {
            block(SurfaceEventQueue(),SurfacePointerRouter(listOf(ToolSurface("main",100,100))),SurfaceInputState(),mutableListOf())
        }
        fun consume(queue: SurfaceEventQueue,state: SurfaceInputState,delivered: MutableList<JSONObject>) {
            val entry=checkNotNull(queue.next())
            delivered.addAll(state.events(entry.event))
        }
        fixture {queue,router,state,delivered ->
            // An earlier suspended host call lets the producer queue pause before a future down.
            queue.add(GraphicsContract.event("pause"))
            queue.add(checkNotNull(router.route(10,"down",25.0,30.0)))
            consume(queue,state,delivered);consume(queue,state,delivered)
            assertEquals(listOf("pause"),delivered.map(::name));assertTrue(state.cancel().isEmpty())
            queue.add(GraphicsContract.event("resume"));consume(queue,state,delivered)
            router.route(10,"up",25.0,30.0)
            queue.add(checkNotNull(router.route(11,"down",40.0,45.0)));consume(queue,state,delivered)
            assertEquals(listOf("pause","resume","down"),delivered.map(::name))
            val cancel=state.cancel().single()
            assertEquals(40.0,cancel.getDouble("x"),0.0);assertEquals(45.0,cancel.getDouble("y"),0.0)
        }
        fixture {queue,router,state,delivered ->
            queue.add(checkNotNull(router.route(10,"down",25.0,30.0)));consume(queue,state,delivered)
            queue.add(GraphicsContract.event("pause"))
            queue.add(checkNotNull(router.route(10,"up",25.0,30.0)))
            queue.add(checkNotNull(router.route(11,"down",70.0,75.0)))
            repeat(3) {consume(queue,state,delivered)}
            assertEquals(listOf("down","cancel","pause"),delivered.map(::name))
            assertEquals(25.0,delivered[1].getDouble("x"),0.0)
            assertTrue(state.cancel().isEmpty())
        }
        fixture {queue,router,state,delivered ->
            queue.add(checkNotNull(router.route(10,"down",25.0,30.0)));consume(queue,state,delivered)
            queue.add(GraphicsContract.event("restart"))
            queue.add(checkNotNull(router.route(11,"down",70.0,75.0)))
            consume(queue,state,delivered);consume(queue,state,delivered)
            assertEquals(listOf("down","cancel","restart","down"),delivered.map(::name))
            queue.add(checkNotNull(router.route(10,"move",28.0,32.0)))
            queue.add(checkNotNull(router.route(10,"up",28.0,32.0)))
            consume(queue,state,delivered);consume(queue,state,delivered)
            assertEquals(listOf("down","cancel","restart","down"),delivered.map(::name))
            val cancel=state.cancel().single()
            assertEquals(1,cancel.getInt("pointer"));assertEquals(70.0,cancel.getDouble("x"),0.0)
            assertTrue(state.cancel().isEmpty())
        }
        val queue=SurfaceEventQueue()
        repeat(32) {queue.add(GraphicsContract.event("pause"))}
        rejects {queue.add(GraphicsContract.event("pause"))}
    }

    @Test fun frameClockAndMetricsDoNotTreatEvaluationAsDisplay() {
        val clock=SurfaceFrameClock()
        assertEquals(0,clock.tick(1_000_000_000))
        assertEquals(16,clock.tick(1_016_000_000))
        assertEquals(50,clock.tick(2_000_000_000))
        clock.reset();assertEquals(0,clock.tick(9_000_000_000))
        val trace=SurfaceFrameMetrics.start("test",1)
        trace.requested();trace.evaluated(100);trace.validated(7);trace.submitted(7)
        val before=checkNotNull(SurfaceFrameMetrics.snapshot())
        assertEquals(1,before.frameRequests);assertEquals(1,before.evaluations);assertEquals(1,before.validatedSnapshots)
        assertEquals(1,before.submissions);assertEquals(0,before.frameCommits)
        trace.committed(7)
        assertEquals(1,SurfaceFrameMetrics.snapshot()!!.frameCommits)
        trace.stop()
    }
}
