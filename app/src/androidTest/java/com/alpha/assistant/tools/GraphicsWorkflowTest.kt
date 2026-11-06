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
    @Test fun drawingRolesAndHexUseTheSameStrictPaintBoundary() {
        fun frame(color: String) = """{"commands":[{"type":"rect","x":0,"y":0,"width":10,"height":10,"color":"$color"}]}"""
        for (color in listOf("default","muted","accent","success","warning","error","#112233","#AABBCCDD"))
            GraphicsContract.frame(frame(color),"frame")
        for (color in listOf("primary","red","Accent","#123","#ZZZZZZ","javascript:red"))
            rejects { GraphicsContract.frame(frame(color),"frame") }
        val gradient = """{"commands":[{"type":"rect","x":0,"y":0,"width":10,"height":10,"gradient":{"type":"linear","x":0,"y":0,"x2":10,"y2":0,"stops":[{"offset":0,"color":"accent"},{"offset":1,"color":"#123456"}]}}]}"""
        GraphicsContract.frame(gradient,"frame")
        rejects { GraphicsContract.frame(gradient.replace("accent","primary"),"frame") }
    }

    @Test fun strictDrawingAndCheckpointBoundaries() {
        val spec=ToolSpec.parse(ArkanoidFixture.spec())
        assertTrue(spec.surfaces.isNotEmpty())
        for(raw in listOf("{\"commands\":[{\"type\":\"html\"}]}","{\"commands\":[],\"effects\":[]}","{\"commands\":[],\"checkpoint\":{\"score\":3}}")) rejects {GraphicsContract.frame(raw,"frame")}
        rejects {GraphicsContract.frame("{\"commands\":[{\"type\":\"circle\",\"x\":0,\"y\":0,\"radius\":-1,\"color\":\"#ffffff\"}]}","frame")}
        rejects {GraphicsContract.frame(JSONObject().put("commands",JSONArray().apply {repeat(513) {put(JSONObject("{\"type\":\"rect\",\"x\":0,\"y\":0,\"width\":1,\"height\":1,\"color\":\"#ffffff\"}"))}}).toString(),"frame")}
        rejects {spec.validateState("{\"secret\":1}")}
        rejects {spec.validateState("{\"score\":\"bad\",\"lives\":3}")}
        rejects {ToolSpec.parse(ArkanoidFixture.spec().replace("\"width\":320","\"width\":0"))}
    }
    @Test fun retainedIsolatedArkanoidMovesCollidesLosesAndRestarts(): Unit=runBlocking {
        val spec=ToolSpec.parse(ArkanoidFixture.spec())
        val session=GraphicsSession(context)
        try {
            val start=session.open(spec.source,spec.initialState)
            assertEquals("OK",start.status); assertEquals("androidx.javascriptengine",start.engine)
            var frame=JSONObject(checkNotNull(start.json)).getJSONObject("surfaces").getJSONObject("main")
            fun ball()=frame.getJSONArray("commands").getJSONObject(1).getDouble("y")
            val initial=ball()
            repeat(12) {frame=JSONObject(checkNotNull(session.event(GraphicsContract.event("frame",33)).json)).getJSONObject("surfaces").getJSONObject("main")}
            assertNotEquals(initial,ball(),0.01)
            session.event(GraphicsContract.event("pointer",x=80.0,y=380.0,phase="down"))
            frame=JSONObject(checkNotNull(session.event(GraphicsContract.event("frame",33)).json)).getJSONObject("surfaces").getJSONObject("main")
            assertEquals(48.0,frame.getJSONArray("commands").getJSONObject(0).getDouble("x"),0.01)
            repeat(600) {session.event(GraphicsContract.event("frame",33))}
            val saved=JSONObject(checkNotNull(session.event(GraphicsContract.event("pause")).json)).getJSONObject("state")
            assertTrue(saved.getInt("score") >= 10); assertTrue(saved.getInt("lives") < 3)
            session.event(GraphicsContract.event("restart"))
            val reset=JSONObject(checkNotNull(session.event(GraphicsContract.event("pause")).json)).getJSONObject("state")
            assertEquals(0,reset.getInt("score")); assertEquals(3,reset.getInt("lives"))
            println("GRAPHICS_GAME collisionScore=${saved.getInt("score")} remainingLives=${saved.getInt("lives")} restart=PASS")
        } finally {session.close()}
    }
    @Test fun mixedGraphicsWidgetsKeepLocalControlsAndOpenTheExactApp(): Unit=runBlocking {
        val name="graphics-widget-${UUID.randomUUID()}.db";val widget=0x4545
        val provider=object: ChatProvider {override suspend fun respond(key:String,request:CloudRequest,session:CloudSession,emit:suspend(CloudEvent)->Unit)=error("Offline")}
        try {ToolStore(context,name).use {store ->
            val json=JSONObject(ArkanoidFixture.spec()).put("ui",JSONObject("{\"type\":\"button\",\"text\":\"Run\",\"action\":\"run\"}"))
            val spec=ToolSpec.parse(json.toString());val tool=ToolRecord("game",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            store.saveTool(tool,true)
            com.alpha.assistant.surfaces.ToolWidgets.bind(context,widget,tool.id,com.alpha.assistant.surfaces.WidgetPreferences())
            val intent=com.alpha.assistant.surfaces.ToolWidgets.actionIntent(context,widget,tool,"run",null)
            assertTrue(com.alpha.assistant.surfaces.ToolWidgets.perform(context,intent,store,ToolEngine(store,ToolGenerationService(provider,SandboxClient(context))),locked=false))
            val saved=checkNotNull(store.tool(tool.id))
            assertEquals(tool.state,saved.state);assertEquals(tool.revision,saved.revision)
            assertTrue(store.effects(tool.id).isEmpty())
            assertEquals(tool.id,com.alpha.assistant.surfaces.ToolWidgets.openIntent(context,tool.id).getStringExtra("tool_id"))
        }} finally {com.alpha.assistant.surfaces.ToolWidgets.remove(context,widget);context.deleteDatabase(name)}
    }
    @Test fun loopOversizeAndClosedSessionFailSafely(): Unit=runBlocking {
        for(source in listOf("while(true){}","return {state:input.state,text:'x'.repeat(70000)};","const a=[];while(true)a.push(new Uint8Array(1048576));")) {
            val session=GraphicsSession(context)
            try {assertNotEquals("OK",session.open(source,"{}").status)} finally {session.close()}
            assertEquals("CLOSED",session.event(GraphicsContract.event("frame",33)).status)
        }
    }
}

/** Offline playable fixture. Every permitted runtime event returns validated local state without native effects. */
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
                {type:'circle',x,y,radius:6,color:'default'},
                ...bricks.filter(b=>b.on).map(b=>({type:'rect',x:b.x,y:b.y,width:44,height:16,color:'#FFAA66'})),
                {type:'text',x:10,y:25,text:'Score '+score+'  Lives '+lives,size:16,color:'default'},
                {type:'text',x:10,y:410,text:'Tick '+frames+' Paddle '+paddle+' Ball '+Math.round(x)+','+Math.round(y),size:12,color:'muted'}];
            return event.type==='checkpoint'?{commands,checkpoint:{score,lives}}:{commands};
        };
    """.trimIndent()
    fun spec()=JSONObject().put("formatVersion",3).put("title","Arkanoid").put("description","Move the paddle. Break the bricks.")
        .put("capabilities",JSONArray()).put("stateSchema",JSONObject("{\"score\":\"number\",\"lives\":\"number\"}"))
        .put("initialState",JSONObject("{\"score\":0,\"lives\":3}"))
        .put("ui",JSONObject("{\"type\":\"text\",\"text\":\"Saved score\",\"bind\":\"score\"}"))
        .put("source","if(!globalThis.game)globalThis.game=(function(state){$source})(input.state); const frame=globalThis.game(input.event); const saved=globalThis.game({type:'checkpoint'}).checkpoint; if(input.event.type!=='preview' && input.event.type!=='render'){input.state.score=saved.score;input.state.lives=saved.lives;} return {state:input.state,surfaces:{main:{commands:frame.commands}},frames:['main']};")
        .put("surfaces",JSONArray().put(JSONObject().put("id","main").put("width",320).put("height",420))).toString()
}
