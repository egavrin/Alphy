package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.*
import com.alpha.assistant.cloud.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Failure cases: malformed commands, oversized/deep output, forbidden result fields, stale events/checkpoints,
 * loops, memory exhaustion, cancellation/death, private files/network, and concurrent state updates.
 * Native accessibility and lifecycle transitions require root device acceptance.
 */
@RunWith(AndroidJUnit4::class)
class GraphicsWorkflowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun rejects(block: () -> Unit) {assertTrue(runCatching(block).isFailure)}
    @Test fun strictDrawingAndCheckpointBoundaries() {
        val spec=ToolSpec.parse(ArkanoidFixture.spec())
        assertNotNull(spec.surface)
        for(raw in listOf("{\"commands\":[{\"type\":\"html\"}]}","{\"commands\":[],\"effects\":[]}","{\"commands\":[],\"checkpoint\":{\"score\":3}}")) rejects {GraphicsContract.frame(raw,"frame")}
        rejects {GraphicsContract.frame("{\"commands\":[{\"type\":\"circle\",\"x\":0,\"y\":0,\"radius\":-1,\"color\":\"#ffffff\"}]}","frame")}
        rejects {GraphicsContract.frame(JSONObject().put("commands",JSONArray().apply {repeat(513) {put(JSONObject("{\"type\":\"rect\",\"x\":0,\"y\":0,\"width\":1,\"height\":1,\"color\":\"#ffffff\"}"))}}).toString(),"frame")}
        rejects {GraphicsContract.patch(spec,JSONObject("{\"secret\":1}"))}
        rejects {GraphicsContract.patch(spec,JSONObject("{\"score\":\"bad\"}"))}
        rejects {ToolSpec.parse(ArkanoidFixture.spec().replace("\"width\":320","\"width\":0"))}
    }
    @Test fun retainedIsolatedArkanoidMovesCollidesLosesAndRestarts(): Unit=runBlocking {
        val spec=ToolSpec.parse(ArkanoidFixture.spec())
        val session=GraphicsSession(context)
        try {
            val start=session.open(checkNotNull(spec.surface).source,spec.initialState)
            assertEquals("OK",start.status); assertEquals("androidx.javascriptengine",start.engine)
            var frame=GraphicsContract.frame(checkNotNull(start.json),"start")
            fun ball()=frame.getJSONArray("commands").getJSONObject(1).getDouble("y")
            val initial=ball()
            repeat(12) {frame=GraphicsContract.frame(checkNotNull(session.event(GraphicsContract.event("frame",33)).json),"frame")}
            assertNotEquals(initial,ball(),0.01)
            session.event(GraphicsContract.event("pointer",x=80.0,y=380.0,phase="down"))
            frame=GraphicsContract.frame(checkNotNull(session.event(GraphicsContract.event("frame",33)).json),"frame")
            assertEquals(48.0,frame.getJSONArray("commands").getJSONObject(0).getDouble("x"),0.01)
            repeat(600) {session.event(GraphicsContract.event("frame",33))}
            val saved=GraphicsContract.frame(checkNotNull(session.event(GraphicsContract.event("checkpoint")).json),"checkpoint").getJSONObject("checkpoint")
            assertTrue(saved.getInt("score") >= 10); assertTrue(saved.getInt("lives") < 3)
            session.event(GraphicsContract.event("restart"))
            val reset=GraphicsContract.frame(checkNotNull(session.event(GraphicsContract.event("checkpoint")).json),"checkpoint").getJSONObject("checkpoint")
            assertEquals(0,reset.getInt("score")); assertEquals(3,reset.getInt("lives"))
            println("GRAPHICS_GAME collisionScore=${saved.getInt("score")} remainingLives=${saved.getInt("lives")} restart=PASS")
        } finally {session.close()}
    }
    @Test fun checkpointRejectsDuplicateAndConcurrentState(): Unit=runBlocking {
        val name="graphics-${UUID.randomUUID()}.db"
        val provider=object: ChatProvider {override suspend fun respond(key:String,request:CloudRequest,session:CloudSession,emit:suspend(CloudEvent)->Unit)=error("Offline")}
        try {ToolStore(context,name).use {store ->
            val spec=ToolSpec.parse(ArkanoidFixture.spec()); val record=ToolRecord("game",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            store.saveTool(record,true); val engine=ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
            val next=engine.checkpoint(record,JSONObject("{\"score\":10}"))
            assertEquals(2,next.stateVersion); assertEquals(10,JSONObject(next.state).getInt("score"))
            assertTrue(runCatching {engine.checkpoint(record,JSONObject("{\"score\":20}"))}.isFailure)
            assertEquals(next,store.tool("game"))
        }} finally {context.deleteDatabase(name)}
    }
    @Test fun graphicalWidgetsOnlyOpenTheExactApp(): Unit=runBlocking {
        val name="graphics-widget-${UUID.randomUUID()}.db";val widget=0x4545
        val provider=object: ChatProvider {override suspend fun respond(key:String,request:CloudRequest,session:CloudSession,emit:suspend(CloudEvent)->Unit)=error("Offline")}
        try {ToolStore(context,name).use {store ->
            val json=JSONObject(ArkanoidFixture.spec()).put("ui",JSONObject("{\"type\":\"button\",\"text\":\"Run\",\"action\":\"run\"}"))
            val spec=ToolSpec.parse(json.toString());val tool=ToolRecord("game",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            store.saveTool(tool,true)
            com.alpha.assistant.surfaces.ToolWidgets.bind(context,widget,tool.id,com.alpha.assistant.surfaces.WidgetPreferences())
            val intent=com.alpha.assistant.surfaces.ToolWidgets.actionIntent(context,widget,tool,"run",null)
            assertFalse(com.alpha.assistant.surfaces.ToolWidgets.perform(context,intent,store,ToolEngine(store,ToolGenerationService(provider,SandboxClient(context))),locked=false))
            assertEquals(tool,store.tool(tool.id))
            assertEquals(tool.id,com.alpha.assistant.surfaces.ToolWidgets.openIntent(context,tool.id).getStringExtra("tool_id"))
        }} finally {com.alpha.assistant.surfaces.ToolWidgets.remove(context,widget);context.deleteDatabase(name)}
    }
    @Test fun loopOversizeAndClosedSessionFailSafely(): Unit=runBlocking {
        for(source in listOf("return () => {while(true){}};","return () => ({commands:[],text:'x'.repeat(40000)});","return () => {const a=[];while(true)a.push(new Uint8Array(1048576));};")) {
            val session=GraphicsSession(context)
            try {assertNotEquals("OK",session.open(source,"{}").status)} finally {session.close()}
            assertEquals("CLOSED",session.event(GraphicsContract.event("frame",33)).status)
        }
    }
}

/** Offline playable fixture. Frames have no durable effects. Checkpoint is an explicit native button. */
internal object ArkanoidFixture {
    val source="""
        let frames=0,paddle=128,x=160,y=320,vx=90,vy=-180,score=0,lives=3,bricks;
        function reset(){paddle=128;x=160;y=320;vx=90;vy=-180;score=0;lives=3;bricks=Array.from({length:18},(_,i)=>({x:8+(i%6)*52,y:45+Math.floor(i/6)*22,on:true}));}
        reset();score=state.score;lives=state.lives;
        return function(event){
            if(event.type==='restart')reset();
            if(event.type==='pointer' && event.phase!=='up' && event.phase!=='cancel')paddle=Math.max(0,Math.min(256,event.x-32));
            if(event.type==='control')paddle=Math.max(0,Math.min(256,paddle+(event.direction==='left'?-28:28)));
            if(event.type==='frame')frames++;
            if(event.type==='frame' && lives>0){
                const dt=event.dt/1000; x+=vx*dt;y+=vy*dt;
                if(x<6){x=6;vx=Math.abs(vx);}if(x>314){x=314;vx=-Math.abs(vx);}if(y<6){y=6;vy=Math.abs(vy);}
                for(const b of bricks)if(b.on&&x>=b.x-6&&x<=b.x+50&&y>=b.y-6&&y<=b.y+22){b.on=false;score+=10;vy=-vy;break;}
                if(vy>0&&y>=370&&y<=386&&x>=paddle-6&&x<=paddle+70){y=369;vy=-Math.abs(vy);}
                if(y>406){lives--;x=160;y=320;vy=-180;}
            }
            const commands=[{type:'roundRect',x:paddle,y:380,width:64,height:10,radius:4,color:'#66DDFF'},
                {type:'circle',x,y,radius:6,color:'#FFFFFF'},
                ...bricks.filter(b=>b.on).map(b=>({type:'rect',x:b.x,y:b.y,width:44,height:16,color:'#FFAA66'})),
                {type:'text',x:10,y:25,text:'Score '+score+'  Lives '+lives,size:16,color:'#FFFFFF'},
                {type:'text',x:10,y:410,text:'Tick '+frames+' Paddle '+paddle+' Ball '+Math.round(x)+','+Math.round(y),size:12,color:'#FFFFFF'}];
            return event.type==='checkpoint'?{commands,checkpoint:{score,lives}}:{commands};
        };
    """.trimIndent()
    fun spec()=JSONObject().put("formatVersion",2).put("title","Arkanoid").put("description","Move the paddle. Break the bricks.")
        .put("capabilities",JSONArray()).put("stateSchema",JSONObject("{\"score\":\"number\",\"lives\":\"number\"}"))
        .put("initialState",JSONObject("{\"score\":0,\"lives\":3}"))
        .put("ui",JSONObject("{\"type\":\"text\",\"text\":\"Saved score\",\"bind\":\"score\"}"))
        .put("source","return {state:input.state};")
        .put("surface",JSONObject().put("width",320).put("height",420).put("source",source).put("checkpointFields",JSONArray("[\"score\",\"lives\"]"))).toString()
}
