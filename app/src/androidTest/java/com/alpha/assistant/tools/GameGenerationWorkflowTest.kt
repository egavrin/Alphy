package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Failure cases recorded before implementation: missing or static board, absent subscriptions,
 * preview mutation, duplicated controls, incorrect saved playback, ignored elapsed time, frame-rate-dependent motion,
 * invalid drawing properties at minimum/maximum viewports, off-board paddle or bricks,
 * missed top/side/corner collisions, repeated scores, lost lives,
 * incorrect win/game-over/reset, effects/records, and lost occupancy or normalized positions after
 * viewport changes, pause, background/stop, and session closure. Collision seeds below isolate
 * cases that a real-time touch workflow cannot reproduce reliably. No provider or device effects.
 */
@RunWith(AndroidJUnit4::class)
class GameGenerationWorkflowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Provider(private val output: String): ChatProvider {
        val requests=mutableListOf<CloudRequest>()
        override suspend fun respond(key:String,request:CloudRequest,session:CloudSession,emit:suspend(CloudEvent)->Unit) {
            requests+=request
            if (request.replyWithFixturePlan(emit)) return
            emit(CloudEvent.Text(output))
        }
    }
    private suspend fun fixture(raw:String,block:suspend(ToolStore,ToolEngine,ToolRecord)->Unit) {
        val name="game-guidance-${UUID.randomUUID()}.db"
        ToolStore(context,name).use {store ->
            val spec=ToolSpec.parse(raw);val tool=ToolRecord("fixture",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            store.saveTool(tool,true)
            val engine=ToolEngine(store,ToolGenerationService(Provider(raw),SandboxClient(context)))
            try {block(store,engine,tool)} finally {engine.closeArtifacts();context.deleteDatabase(name)}
        }
    }
    @Test fun exactPromptExamplePassesProductionGenerationAndKeepsNongameWidgetsValid():Unit=runBlocking {
        val raw=GameGenerationExample.spec();val provider=Provider(raw)
        val result=ToolGenerationService(provider,SandboxClient(context)).generate("offline","A local game",null,emptyList(),CloudSession(),"fixture")
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE),provider.requests.map { it.generationStage })
        val writer = provider.requests.last()
        val writerPrompt = writer.systemInstructions + writer.turns.first().text
        assertFalse(writerPrompt.contains("Neon Arkanoid"))
        assertTrue(provider.requests.first().systemInstructions.contains("The original request defines the feature set."))
        assertTrue(writerPrompt.contains("The original request defines the feature set."))
        assertEquals(ToolSpec.parse(raw).initialState,result.state)
        assertTrue(result.effects.isEmpty());assertEquals(0,result.records.length())
        assertTrue(result.surfaces!!.getJSONObject("main").getJSONArray("commands").length()>0)
        val widget=JSONObject(raw).apply {
            remove("surfaces");remove("presentation");remove("hostControls");put("source","return {state:input.state};")
            put("ui",JSONObject("""{"type":"text","text":"Status","bind":"status"}"""))
        }.toString()
        val nongame=ToolGenerationService(Provider(widget),SandboxClient(context)).generate("offline","A status widget",null,emptyList(),CloudSession(),"fixture")
        assertTrue(nongame.spec.surfaces.isEmpty());assertNull(nongame.frames)
    }
    private val brickColors=setOf("#FF6489","#B48AFF","#648EFF","#42D9CF","#FFC66D")
    private fun bricks(surfaces:JSONObject):List<JSONObject> {
        val commands=surfaces.getJSONObject("main").getJSONArray("commands")
        return (0 until commands.length()).map(commands::getJSONObject)
            .filter {it.optString("type")=="roundRect" && it.optString("color") in brickColors}
    }
    private fun viewport(width:Int,height:Int)=JSONObject().put("type","viewport").put("surfaceId","main")
        .put("width",width).put("height",height)

    @Test fun sampleExecutesDrawingInputAndPersistentLifecycle():Unit=runBlocking {
        fixture(GameGenerationExample.spec()) {store,engine,tool ->
            var current=engine.openArtifact(tool)
            suspend fun event(e:JSONObject):ArtifactSnapshot {current=engine.artifactEvent(tool.id,1,e);return current}
            fun state()=JSONObject(current.tool.state)
            suspend fun action(id:String) {
                engine.interact(tool.id,1,current.tool.stateVersion,id,null)
                current=engine.artifactSnapshot(tool.id,1)
            }
            val spec=ToolSpec.parsePersisted(tool.spec)
            assertEquals("immersive",spec.presentation)
            assertEquals("viewport",spec.surfaces.getValue("main").sizing)
            assertNull(spec.surfaces.getValue("main").x)
            assertEquals("ready",state().getString("status"));assertEquals("ready",current.playback);assertTrue(current.frames.isEmpty())
            assertTrue(state().getString("message").contains("Drag"))
            event(viewport(360,640));assertEquals(360,state().getInt("width"));assertEquals(640,state().getInt("height"))
            event(GraphicsContract.event("start"));assertEquals("playing",current.playback);assertEquals(setOf("main"),current.frames)
            val initialY=state().getDouble("y")
            event(GraphicsContract.event("frame",0));assertEquals(initialY,state().getDouble("y"),0.0)
            event(GraphicsContract.event("frame",50));assertTrue(state().getDouble("y")<initialY)
            val commands=current.surfaces.getJSONObject("main").getJSONArray("commands")
            val ball=(0 until commands.length()).map(commands::getJSONObject)
                .single {it.optString("type")=="circle" && it.optString("color")=="default"}
            assertEquals(state().getDouble("y")*640,ball.getDouble("y"),0.000001)
            event(GraphicsContract.event("pointer",x=80.0,y=580.0,phase="down"))
            assertEquals(80.0/360,state().getDouble("paddle"),0.000001)
            event(GraphicsContract.event("pointer",x=300.0,y=580.0,phase="cancel"))
            assertEquals(80.0/360,state().getDouble("paddle"),0.000001)
            event(GraphicsContract.event("control",direction="right"));val nativeRight=state().getDouble("paddle")
            assertTrue(nativeRight>80.0/360)
            action("left");assertEquals(80.0/360,state().getDouble("paddle"),0.000001)
            action("right");assertEquals(nativeRight,state().getDouble("paddle"),0.000001)
            event(GraphicsContract.event("pointer",x=0.0,y=580.0,phase="move"));assertEquals(0.09,state().getDouble("paddle"),0.000001)
            event(GraphicsContract.event("pointer",x=360.0,y=580.0,phase="move"));assertEquals(0.91,state().getDouble("paddle"),0.000001)
            action("pause");assertEquals("paused",state().getString("status"));assertTrue(current.frames.isEmpty())
            val paused=state().toString();engine.closeArtifact(tool.id,1)
            current=engine.openArtifact(checkNotNull(store.tool(tool.id)))
            assertEquals(paused,current.tool.state);assertEquals("paused",current.playback)
            val beforeResize=state()
            event(viewport(640,360))
            for(key in listOf("x","y","paddle","vx","vy","score","lives","status","bricks"))
                assertEquals("resize preserves $key",beforeResize.get(key).toString(),state().get(key).toString())
            assertTrue(current.frames.isEmpty())
            action("resume");assertEquals("playing",state().getString("status"));assertEquals("playing",current.playback)
            event(GraphicsContract.event("pause"));assertEquals("paused",current.playback);assertTrue(current.frames.isEmpty())
            val background=state().toString()
            current=engine.artifactSnapshot(tool.id,1)
            assertEquals(background,current.tool.state);assertTrue(current.frames.isEmpty())
            event(viewport(640,360));assertEquals(background,current.tool.state)
            event(GraphicsContract.event("resume"));assertEquals(setOf("main"),current.frames)
            event(GraphicsContract.event("stop"));assertEquals("paused",state().getString("status"));assertEquals("paused",current.playback)
            event(GraphicsContract.event("restart"));assertEquals("playing",state().getString("status"));assertEquals("playing",current.playback)
            action("restart");assertEquals(0,state().getInt("score"));assertEquals(3,state().getInt("lives"))
            assertEquals(30,state().getJSONArray("bricks").length())
            assertEquals(30,bricks(current.surfaces).size)
            assertTrue(store.effects(tool.id).isEmpty());assertEquals(0,store.deliveries(tool.id).size)
            println("GAME_SAMPLE immersive=PASS viewport=PASS motion=PASS dt=PASS pointer=PASS nativeControls=PASS lifecycle=PASS reopen=PASS effects=NONE records=NONE")
        }
    }
    @Test fun deterministicWallPaddleBrickLossAndBoardReset():Unit=runBlocking {
        val spec=ToolSpec.parse(GameGenerationExample.spec())
        val service=ToolGenerationService(Provider(spec.json),SandboxClient(context))
        suspend fun frame(seed:JSONObject,dt:Int=50):JSONObject {
            val result=service.evaluate(spec,"fixture",1,seed.toString(),GraphicsContract.event("frame",dt),spec.ui.toString())
            assertTrue(result.effects.isEmpty());assertEquals(0,result.records.length())
            return JSONObject(result.state)
        }
        fun seed()=JSONObject(spec.initialState).put("status","playing")
        val paused=frame(seed().put("status","paused"))
        assertEquals("paused simulation does not move",paused.toString(),frame(paused).toString())
        val left=frame(seed().put("x",0.014).put("vx",-0.34));assertTrue(left.getDouble("vx")>0)
        val right=frame(seed().put("x",0.986).put("vx",0.34));assertTrue(right.getDouble("vx")<0)
        val top=frame(seed().put("y",0.014*360/640).put("vy",-0.72));assertTrue(top.getDouble("vy")>0)
        val paddle=frame(seed().put("x",0.5).put("y",0.9-0.014*360/640-0.001).put("vy",0.72))
        assertTrue(paddle.getDouble("vy")<0)
        val angled=frame(seed().put("x",0.56).put("y",0.9-0.014*360/640-0.001).put("vy",0.72))
        assertTrue("paddle impact steers the ball",angled.getDouble("vx")>paddle.getDouble("vx"))
        val preview=service.evaluate(spec,"fixture",1,spec.initialState,JSONObject("{\"type\":\"preview\"}"),spec.ui.toString())
        val b=bricks(preview.surfaces!!).first()
        fun brickSeed()=seed().put("x",(b.getDouble("x")+b.getDouble("width")/2)/360)
            .put("y",(b.getDouble("y")+b.getDouble("height")+0.014*360+0.5)/640).put("vx",0).put("vy",-0.72)
        val brick=frame(brickSeed(),10)
        assertEquals(10,brick.getInt("score"));assertFalse(brick.getJSONArray("bricks").getBoolean(0))
        val revisit=frame(brickSeed().put("bricks",brick.getJSONArray("bricks")).put("score",10),10)
        assertEquals(10,revisit.getInt("score"));assertFalse(revisit.getJSONArray("bricks").getBoolean(0))
        val side=frame(seed().put("x",(b.getDouble("x")-0.014*360-0.1)/360)
            .put("y",(b.getDouble("y")+b.getDouble("height")/2)/640).put("vx",0.72).put("vy",0),10)
        assertTrue("side collision reflects horizontal velocity",side.getDouble("vx")<0)
        assertEquals(10,side.getInt("score"))
        val corner=frame(seed().put("x",(b.getDouble("x")-0.014*360*0.7)/360)
            .put("y",(b.getDouble("y")-0.014*360*0.7)/640).put("vx",0.5).put("vy",0.5),10)
        assertEquals("corner contact destroys only one brick",10,corner.getInt("score"))
        val loss=frame(seed().put("x",0.02).put("y",1.01).put("vy",0.72));assertEquals(2,loss.getInt("lives"))
        val over=frame(seed().put("x",0.02).put("y",1.01).put("vy",0.72).put("lives",1))
        assertEquals(0,over.getInt("lives"));assertEquals("gameOver",over.getString("status"))
        val onlyFirst=JSONArray().apply {repeat(30) {put(it==0)}}
        val won=frame(brickSeed().put("bricks",onlyFirst),10);assertEquals("won",won.getString("status"))
        val terminal=frame(JSONObject(won.toString()));assertEquals(won.toString(),terminal.toString())
        val reset=service.evaluate(spec,"fixture",1,over.toString(),JSONObject("{\"type\":\"action\",\"actionId\":\"restart\",\"value\":null}"),spec.ui.toString())
        val state=JSONObject(reset.state);assertEquals(3,state.getInt("lives"));assertEquals(0,state.getInt("score"))
        assertEquals(JSONObject(spec.initialState).getJSONArray("bricks").toString(),state.getJSONArray("bricks").toString())
        assertEquals(setOf("main"),reset.frames)
        val oneFrame=frame(seed(),50);var fiveFrames=seed()
        repeat(5) {fiveFrames=frame(fiveFrames,10)}
        for(key in listOf("x","y","vx","vy"))assertEquals("elapsed time $key",oneFrame.getDouble(key),fiveFrames.getDouble(key),0.000001)
        val collisionSpec=JSONObject(spec.json).put("initialState",brickSeed()).toString()
        fixture(collisionSpec) {store,engine,tool ->
            engine.openArtifact(tool)
            engine.artifactEvent(tool.id,1,GraphicsContract.event("frame",10))
            val paused=engine.artifactEvent(tool.id,1,GraphicsContract.event("pause"))
            engine.closeArtifact(tool.id,1)
            val reopened=engine.openArtifact(checkNotNull(store.tool(tool.id)))
            assertEquals(paused.tool.state,reopened.tool.state)
            val resized=engine.artifactEvent(tool.id,1,viewport(640,360))
            assertFalse(JSONObject(resized.tool.state).getJSONArray("bricks").getBoolean(0))
            assertEquals(10,JSONObject(resized.tool.state).getInt("score"));assertEquals(29,bricks(resized.surfaces).size)
        }
        println("GAME_SAMPLE walls=PASS paddle=PASS brickOccupancy=PASS savedBoard=PASS score=PASS loss=PASS gameOver=PASS win=PASS boardReset=PASS elapsedTime=PASS")
    }
    @Test fun savedPlaybackIsAuthoritativeOnOpenForEveryGameState():Unit=runBlocking {
        val raw=GameGenerationExample.spec()
        for(status in listOf("ready","playing","paused","won","gameOver")) {
            val initial=JSONObject(ToolSpec.parse(raw).initialState).put("status",status)
            val seeded=JSONObject(raw).put("initialState",initial).toString()
            fixture(seeded) {store,engine,tool ->
                val opened=engine.openArtifact(tool)
                val expected=if(status in setOf("won","gameOver"))"ended" else status
                assertEquals("open playback for $status",expected,opened.playback)
                assertEquals(status,JSONObject(opened.tool.state).getString("status"))
                engine.closeArtifact(tool.id,1)
                val reopened=engine.openArtifact(checkNotNull(store.tool(tool.id)))
                assertEquals("reopened playback for $status",expected,reopened.playback)
                assertEquals(opened.tool.state,reopened.tool.state)
            }
        }
    }
    @Test fun minimumAndMaximumViewportsKeepAllGameStatesWithinDrawingLimits():Unit=runBlocking {
        val spec=ToolSpec.parse(GameGenerationExample.spec())
        val service=ToolGenerationService(Provider(spec.json),SandboxClient(context))
        for((width,height) in listOf(64 to 64,2048 to 2048,64 to 2048,2048 to 64)) {
            for(status in listOf("ready","playing","paused","won","gameOver")) {
                val seed=JSONObject(spec.initialState).put("status",status)
                val result=service.evaluate(spec,"fixture",1,seed.toString(),viewport(width,height),spec.ui.toString())
                val commands=result.surfaces!!.getJSONObject("main").getJSONArray("commands")
                for(index in 0 until commands.length()) {
                    val command=commands.getJSONObject(index)
                    if(command.getString("type")=="text")
                        assertTrue("$status text $index at $width x $height",command.getDouble("size") in 1.0..128.0)
                }
                assertEquals(status,JSONObject(result.state).getString("status"))
                assertTrue(result.effects.isEmpty());assertEquals(0,result.records.length())
            }
        }
    }
    @Test fun everyBrickInFiveRowsFitsCollidesAndResetsAtEveryViewportShape():Unit=runBlocking {
        val spec=ToolSpec.parse(GameGenerationExample.spec())
        val service=ToolGenerationService(Provider(spec.json),SandboxClient(context))
        for((width,height) in listOf(360 to 640,640 to 360,64 to 64,64 to 2048,2048 to 64,2048 to 2048)) {
            val sized=service.evaluate(spec,"fixture",1,spec.initialState,viewport(width,height),spec.ui.toString())
            val board=bricks(sized.surfaces!!)
            assertEquals("The sample needs five complete rows",30,board.size)
            assertEquals(5,board.map {it.getDouble("y")}.distinct().size)
            assertEquals(6,board.map {it.getDouble("x")}.distinct().size)
            for((index,b) in board.withIndex()) {
                assertTrue("brick $index left",b.getDouble("x")>=0);assertTrue("brick $index top",b.getDouble("y")>=0)
                assertTrue("brick $index right",b.getDouble("x")+b.getDouble("width")<=width)
                assertTrue("brick $index bottom",b.getDouble("y")+b.getDouble("height")<height*0.9)
            }
            var state=JSONObject(sized.state).put("status","playing")
            val radius=0.014*minOf(width,height)
            for(index in board.indices.reversed()) {
                val b=board[index]
                state.put("x",(b.getDouble("x")+b.getDouble("width")/2)/width)
                    .put("y",(b.getDouble("y")+b.getDouble("height")+radius+0.001)/height).put("vx",0).put("vy",-0.72)
                val hit=service.evaluate(spec,"fixture",1,state.toString(),GraphicsContract.event("frame",10),spec.ui.toString())
                state=JSONObject(hit.state)
                assertFalse("drawn brick $index must collide at $width x $height",state.getJSONArray("bricks").getBoolean(index))
                assertEquals((board.size-index)*10,state.getInt("score"));assertEquals(index,bricks(hit.surfaces!!).size)
                assertTrue(hit.effects.isEmpty());assertEquals(0,hit.records.length())
            }
            assertEquals("won",state.getString("status"));assertEquals(300,state.getInt("score"))
            val reset=service.evaluate(spec,"fixture",1,state.toString(),GraphicsContract.event("restart"),spec.ui.toString())
            val restored=JSONObject(reset.state)
            assertEquals(width,restored.getInt("width"));assertEquals(height,restored.getInt("height"))
            assertEquals(0,restored.getInt("score"));assertEquals(3,restored.getInt("lives"))
            assertEquals(JSONObject(spec.initialState).getJSONArray("bricks").toString(),restored.getJSONArray("bricks").toString())
            assertEquals(30,bricks(reset.surfaces!!).size);assertEquals(setOf("main"),reset.frames)
        }
    }
    @Test fun retainedFalseGamesPassPreviewButFailArkanoidWorkflow():Unit=runBlocking {
        for(deadSimulation in listOf(false,true)) {
            val raw=falseGame(deadSimulation)
            val preview=ToolGenerationService(Provider(raw),SandboxClient(context)).generate("offline","Arkanoid",null,emptyList(),CloudSession(),"fixture")
            assertTrue(preview.spec.surfaces.isEmpty());assertNull(preview.surfaces);assertNull(preview.frames)
            fixture(raw) {_,engine,tool ->
                val opened=engine.openArtifact(tool)
                val started=engine.interact(tool.id,1,opened.tool.stateVersion,"start",null)
                val snapshot=engine.artifactSnapshot(tool.id,1)
                assertEquals("playing",JSONObject(started.state).getString("status"))
                assertEquals(300,JSONObject(started.state).getInt("y"))
                assertTrue("Arkanoid workflow FAIL: no board",snapshot.surfaces.length()==0)
                assertTrue("Arkanoid workflow FAIL: no frame subscription",snapshot.frames.isEmpty())
                assertTrue(runCatching {engine.artifactEvent(tool.id,1,GraphicsContract.event("frame",16))}.isFailure)
                println("FALSE_GAME fixture=${if(deadSimulation) "uncalled-simulation" else "status-only"} preview=ACCEPTED workflow=FAIL motion=ABSENT screenshots=NOT_PROOF")
            }
        }
    }
    /** Synthetic reductions of s4-r3-e206aebb8c8f and s4-r1-6d3526377e81, without captured labels or user data. */
    private fun falseGame(deadSimulation:Boolean):String=JSONObject().put("formatVersion",3).put("title","Negative game fixture")
        .put("description","").put("capabilities",JSONArray()).put("stateSchema",JSONObject("""{"status":"string","y":"number"}"""))
        .put("initialState",JSONObject("""{"status":"ready","y":300}"""))
        .put("ui",JSONObject("""{"type":"button","text":"Start","action":"start"}"""))
        .put("source",(if(deadSimulation) "function simulate(s){s.y-=3;return s;} " else "")+
            "if(input.event.type==='action')input.state.status='playing';return {state:input.state};").toString()
}
