package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityBroker
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.GraphicsContract
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.ToolSurface
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MixedRetainedArtifactWorkflowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val partValue="p5_count_value"
    private val partAction="p5_count_add"
    private val fillAction="fill_move"
    private val recordAction="fill_save"
    private val surface="fill_board"
    private val offline=object: ChatProvider {
        override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
            error("Retained workflow must run offline.")
        }
    }

    private suspend fun spec(source: String,receipts: Boolean=false): ToolSpec {
        val plan=NeedsPlan.parse("""
            {"title":"Counter and scene","description":"","language":"en","inputs":[],"constants":[],"needs":[
              {"id":"count","type":"counter","label":"Count","description":"Increase and reset"},
              {"id":"board","type":"scene","label":"Board","description":"Move a dot"}
            ],"questions":[]}
        """.trimIndent())
        val assembly=PartsGeneration.build(plan) { raw ->
            val question=JSONObject(raw).getJSONArray("questions").getJSONObject(0)
            val answer=JSONObject().put("id",question.getString("id")).put("choice","A").put("confidence",1)
                .put("probabilities",JSONObject().put("A",1).put("B",0))
            JSONObject().put("answers",JSONArray().put(answer)).toString()
        }
        val move=JSONObject("""{"type":"button","text":"Move","action":"$fillAction"}""")
        val fillRoot=if(receipts) JSONObject().put("type","column").put("children",JSONArray().put(move)
            .put(JSONObject("""{"type":"button","text":"Save","action":"$recordAction"}"""))) else move
        val fill=JSONObject().put("stateSchema",JSONObject("""{"fill_pos":"number","fill_calls":"number"}"""))
            .put("initialState",JSONObject("""{"fill_pos":0,"fill_calls":0}"""))
            .put("ui",JSONObject().put("type","column").put("children",JSONArray().put(fillRoot)))
            .put("surfaces",JSONArray("""[{"id":"$surface","width":64,"height":64,"sizing":"viewport"}]"""))
            .put("capabilities",if(receipts) JSONArray().put("vibration.play") else JSONArray())
            .put("capabilityResults",receipts).put("source",source)
        if(receipts) fill.put("collections",JSONObject("""{"fill_history":{"type":"object","fields":{"count":"number"}}}"""))
        return ToolComposition.complete(assembly,fill.toString())
    }

    private fun engine(store: ToolStore)=ToolEngine(store,ToolGenerationService(offline,SandboxClient(context)),permissionCheck={true})
    private fun frame()=GraphicsContract.event("frame",16,surfaceId=surface)
    private fun count(tool: ToolRecord,key: String)=JSONObject(tool.state).getInt(key)
    private suspend fun withTool(spec: ToolSpec,block: suspend (ToolStore,ToolEngine,ToolRecord)->Unit) {
        val name="mixed-retained-${UUID.randomUUID()}.db"
        val store=ToolStore(context,name)
        val engine=engine(store)
        try {
            val initial=ToolRecord("mixed",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            store.saveTool(initial,true)
            block(store,engine,initial)
        } finally {
            runCatching {engine.closeArtifacts()}
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun capturedDispatchAndHostAcceptedStateSurviveTwoHostsAndReopen(): Unit = runBlocking {
        val spec=spec("""
            if ("$partValue" in input.state) throw Error("Part state entered fill.");
            const e=input.event;
            globalThis.fillRuns=(globalThis.fillRuns||0)+1;
            input.state.fill_calls=globalThis.fillRuns;
            if(e.type==="open"){
              globalThis.__artifact=raw=>({state:{...raw.state,$partValue:91}});
              JSON.parse=()=>{throw Error("Poisoned parse")};
              JSON.stringify=()=>{throw Error("Poisoned stringify")};
            }
            if(e.type==="action" && e.actionId!=="$fillAction") throw Error("Part action entered fill.");
            if(e.type!=="open")input.state.fill_pos++;
            return {state:input.state,frames:["$surface"],timers:[{id:"fill_timer",intervalMs:50}],
              surfaces:{"$surface":{commands:[{type:"circle",x:8,y:16,radius:2,color:"accent"}]}}};
        """.trimIndent())
        withTool(spec) { store,first,initial ->
            val second=engine(store)
            try {
                val a=first.openArtifact(initial)
                first.flushArtifact(initial.id,1,a.sessionGeneration)
                val ready=checkNotNull(store.tool(initial.id))
                val native=first.interact(ready.id,1,ready.stateVersion,partAction,null)
                assertEquals(1,count(native,partValue));assertEquals(1,count(native,"fill_calls"))
                assertTrue(store.effects(initial.id).isEmpty())

                val b=second.openArtifact(native)
                val viewport=GraphicsContract.viewport(ToolSurface(surface,128,96,sizing="viewport"))
                second.artifactEvent(initial.id,1,viewport,b.sessionGeneration)
                val before=first.artifactSnapshot(initial.id,1,a.sessionGeneration)
                assertFalse(before.frameOwner)
                assertEquals(before.tool,first.artifactEvent(initial.id,1,frame(),a.sessionGeneration).tool)
                for(event in listOf(frame(),GraphicsContract.event("pointer",x=10.0,y=12.0,phase="down",surfaceId=surface),
                    JSONObject().put("type","timer").put("timerId","fill_timer"),
                    GraphicsContract.event("control",direction="right",surfaceId=surface))) {
                    second.artifactEvent(initial.id,1,event,b.sessionGeneration)
                }
                val published=second.flushArtifact(initial.id,1,b.sessionGeneration).tool
                assertEquals(1,count(published,partValue));assertEquals(5,count(published,"fill_pos"))
                assertEquals(6,count(published,"fill_calls"))
                assertEquals(published,first.artifactSnapshot(initial.id,1,a.sessionGeneration).tool)

                val external=published.copy(stateVersion=published.stateVersion+1,state=JSONObject(published.state).put("fill_pos",40).toString())
                store.saveTool(external,false)
                val advanced=second.artifactEvent(initial.id,1,frame(),b.sessionGeneration).tool
                assertEquals(41,count(advanced,"fill_pos"))
                assertEquals(1,count(advanced,partValue))
                second.closeArtifact(initial.id,1,b.sessionGeneration)
                first.closeArtifact(initial.id,1,a.sessionGeneration)
                val saved=checkNotNull(store.tool(initial.id))
                val reopened=first.openArtifact(saved)
                assertEquals(41,count(reopened.tool,"fill_pos"));assertEquals(1,count(reopened.tool,partValue))
                assertEquals(1,count(reopened.tool,"fill_calls"))
            } finally {second.closeArtifacts()}
        }
    }

    @Test fun retainedFillForgeriesNeverReplaceStoredPartStateOrUi(): Unit = runBlocking {
        val forged=listOf(
            "input.state.$partValue=91; return {state:input.state};",
            """return {state:input.state,ui:{type:"column",children:[{type:"button",text:"Counter",action:"$partAction"}]}};"""
        )
        for(payload in forged) {
            val spec=spec("if(input.event.type==='action'){$payload} return {state:input.state};")
            withTool(spec) { store,engine,initial ->
                engine.openArtifact(initial)
                val native=engine.interact(initial.id,1,1,partAction,null)
                assertEquals(1,count(native,partValue));assertEquals(initial.ui,native.ui)
                assertTrue(runCatching {engine.interact(initial.id,1,native.stateVersion,fillAction,null)}.isFailure)
                assertEquals(native,store.tool(initial.id))
                assertTrue(store.effects(initial.id).isEmpty())
            }
        }
    }

    @Test fun retainedCapabilityAndRecordResultsUseFillStateAfterNativeAction(): Unit = runBlocking {
        val spec=spec("""
            if ("$partValue" in input.state) throw Error("Foreign state entered a result.");
            const e=input.event;
            if(e.type==="capabilityResults" || e.type==="recordResults") input.state.fill_pos+=e.entries.length;
            if(e.type==="action" && e.actionId==="$recordAction") return {state:input.state,records:[
                {id:"save",collection:"fill_history",op:"put",key:"entry",expectedToken:null,value:{count:1}}
            ]};
            return {state:input.state,effects:e.type==="action"
                ? [{id:"buzz",capability:"vibration.play",args:{milliseconds:10}}] : []};
        """.trimIndent(),receipts=true)
        withTool(spec) { store,engine,initial ->
            engine.openArtifact(initial)
            val part=engine.interact(initial.id,1,1,partAction,null)
            assertEquals(1,count(part,partValue));assertTrue(store.effects(initial.id).isEmpty())
            val fill=engine.interact(initial.id,1,part.stateVersion,fillAction,null)
            val effect=store.effects(initial.id).single()
            val outcome=CapabilityBroker(context,store).execute(effect.key) { CapabilityResult("succeeded","Fixture") }
            assertEquals("succeeded",outcome.status)
            engine.deliverCapabilityResults(initial.id)
            val final=checkNotNull(store.tool(initial.id))
            assertEquals(1,count(final,partValue));assertEquals(1,count(final,"fill_pos"))
            assertTrue(final.stateVersion>fill.stateVersion)
            engine.deliverCapabilityResults(initial.id)
            assertEquals(final,store.tool(initial.id))
            val recorded=engine.interact(initial.id,1,final.stateVersion,recordAction,null)
            assertEquals(1,count(recorded,partValue));assertEquals(2,count(recorded,"fill_pos"))
            assertNull(com.alpha.assistant.tools.ToolRecords(store).pending(initial.id))
            assertEquals(recorded,store.tool(initial.id))
        }
    }

    @Test fun cancellationAndConcurrentRevisionNeverPublishAnInFlightEvent(): Unit = runBlocking {
        val spec=spec("""
            if(input.event.type==="pause") {
              const until=Date.now()+150; while(Date.now()<until){}
              input.state.fill_pos++;
            }
            return {state:input.state};
        """.trimIndent())
        withTool(spec) { store,engine,initial ->
            val opened=engine.openArtifact(initial)
            val pause=GraphicsContract.event("pause",surfaceId=surface)
            val canceled=launch(start=CoroutineStart.UNDISPATCHED) {engine.artifactEvent(initial.id,1,pause,opened.sessionGeneration)}
            canceled.cancelAndJoin()
            assertEquals(initial,store.tool(initial.id))
            assertTrue(runCatching {engine.artifactSnapshot(initial.id,1,opened.sessionGeneration)}.isFailure)
            val reopened=engine.openArtifact(initial)
            val inFlight=async(start=CoroutineStart.UNDISPATCHED) {
                runCatching {engine.artifactEvent(initial.id,1,pause,reopened.sessionGeneration)}
            }
            val revised=initial.copy(revision=2,stateVersion=2)
            store.saveTool(revised,true)
            assertTrue(inFlight.await().isFailure)
            assertEquals(revised,store.tool(initial.id))
            assertEquals(0,count(engine.openArtifact(revised).tool,"fill_pos"))
        }
    }

    @Test fun sandboxDeathAndMalformedStoredManifestFailClosed(): Unit = runBlocking {
        val spec=spec("return {state:input.state,frames:['$surface']};")
        withTool(spec) { store,engine,initial ->
            val opened=engine.openArtifact(initial)
            val death=SandboxClient(context).execute("let a=[];while(true)a.push(new Array(100000).fill('memory'));",
                JSONObject().put("artifactId","death").put("revision",1).put("state",JSONObject()).put("event",JSONObject().put("type","open")).toString())
            assertTrue(death.status,death.status in setOf("MEMORY_LIMIT","SANDBOX_DIED"))
            assertTrue(runCatching {engine.artifactEvent(initial.id,1,frame(),opened.sessionGeneration)}.isFailure)
            assertEquals(initial,store.tool(initial.id))
            for(corrupt in listOf<JSONObject.()->Unit>({put("version",99)},{put("parts",JSONArray())})) {
                val raw=JSONObject(initial.spec).apply {getJSONObject("composition").apply(corrupt)}.toString()
                store.writableDatabase.execSQL("UPDATE tools SET spec=? WHERE id=?",arrayOf(raw,initial.id))
                assertTrue(runCatching {engine.openArtifact(initial.copy(spec=raw))}.isFailure)
                assertEquals(raw,store.tool(initial.id)?.spec)
                assertEquals(initial.state,store.tool(initial.id)?.state)
            }
        }
    }
}
