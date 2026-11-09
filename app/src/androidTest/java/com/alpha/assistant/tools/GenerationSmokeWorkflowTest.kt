package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** All providers return local fixtures. Smoke must never invoke a native effect or invent a native result. */
@RunWith(AndroidJUnit4::class)
class GenerationSmokeWorkflowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun spec(body: String="",ui: String="""{"type":"button","text":"Add","action":"SECRET_ACTION"}"""): String = JSONObject()
        .put("formatVersion",3).put("title","Disposable smoke").put("description","")
        .put("capabilities",JSONArray()).put("stateSchema",JSONObject("""{"count":"number"}"""))
        .put("initialState",JSONObject("""{"count":0}""")).put("ui",JSONObject(ui))
        .put("surfaces",JSONArray("""[{"id":"SECRET_LEFT","width":64,"height":64},{"id":"SECRET_RIGHT","width":64,"height":64}]"""))
        .put("source","const e=input.event,s=input.state; $body return {state:s,frames:['SECRET_LEFT','SECRET_RIGHT'],timers:[{id:'SECRET_TIMER',intervalMs:1000}],surfaces:{SECRET_LEFT:{commands:[{type:'circle',x:16,y:16,radius:4,color:'accent'}]},SECRET_RIGHT:{commands:[]}}};").toString()
    private class Provider(var outputs: List<String>): ChatProvider {
        val requests=mutableListOf<CloudRequest>()
        var afterResponse: (()->Unit)?=null
        override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend(CloudEvent)->Unit) {
            val hasDrawing = JSONObject(outputs.first()).has("surfaces")
            if (request.replyWithFixturePlan(emit, fixtureScene().takeIf { hasDrawing })) return
            requests += request
            val candidate = JSONObject(outputs[(requests.size - 1).coerceAtMost(outputs.lastIndex)])
            if (hasDrawing && request.generationStage == CloudGenerationStage.COMPLETE) candidate.withFixtureDrawingBinding("SECRET_LEFT")
            val definition = candidate.toString()
            val output = if (request.generationStage == CloudGenerationStage.COMPLETE &&
                request.turns.last().text.contains("Return one JSON patch object only")) {
                writerFixturePatch(request.turns[1].text, definition)
            } else if (request.expectsSavedPatch) {
                val before = JSONObject(request.turns.first().text.substringAfter("\nCurrent tool definition (data): "))
                if (before.has("ui")) request.patchSavedFixture(definition) else {
                    val source = candidate.getString("source")
                    val search = before.getString("source")
                    val patch = if (source == search) {
                        JSONObject().put("fields", JSONObject().put("description", "Fixture repair ${requests.size}"))
                    } else JSONObject().put("sourceEdits", JSONArray().put(JSONObject().put("search", search).put("replace", source)))
                    request.withFixtureLanguage(patch.toString())
                }
            } else request.patchSavedFixture(definition)
            emit(CloudEvent.Text(output))
            afterResponse?.invoke()
        }
    }
    @Test fun previewLayoutControlsAlsoReceiveSmokeActions(): Unit=runBlocking {
        val layout="{type:'button',text:'Preview action',action:'previewAction'}"
        val source="if(input.event.type==='preview')return {state:input.state,ui:$layout}; if(input.event.type==='action')throw Error('SECRET_PREVIEW_ACTION'); return {state:input.state};"
        val invalid=JSONObject(spec()).put("ui",JSONObject("""{"type":"text","text":"Definition"}""")).put("source",source).toString()
        val provider=Provider(listOf(invalid,spec()))
        ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local fixture",null,emptyList(),CloudSession(),"fixture")
        assertEquals(2,provider.requests.size)
        assertFalse(provider.requests.last().turns.last().text.contains("SECRET_"))
    }
    @Test fun hiddenEventFailuresRepairExactlyOnceWithSanitizedLocations(): Unit=runBlocking {
        val predicates=listOf(
            "e.type==='render'", "e.type==='action'", "e.type==='open'",
            "e.type==='frame' && e.surfaceId==='SECRET_RIGHT' && e.dt===50",
            "e.type==='pointer' && e.pointer===1 && e.phase==='cancel'",
            "e.type==='pointer' && e.phase==='up'", "e.type==='control' && e.direction==='right'",
            "e.type==='timer'", "e.type==='start'", "e.type==='pause'", "e.type==='resume'", "e.type==='restart'"
        )
        fun withControls(definition: String) = JSONObject(definition).put("hostControls",
            JSONObject("""{"playback":true,"movement":["SECRET_LEFT","SECRET_RIGHT"]}""")).toString()
        for(predicate in predicates) {
            val invalid=withControls(spec("if($predicate) throw Error('SECRET_STATE content://private/SECRET_LOCATOR');"))
            val provider=Provider(listOf(invalid,withControls(spec("if(e.type==='action')s.count++;"))))
            val result=ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local fixture",null,emptyList(),CloudSession(),"fixture")
            assertEquals(predicate,2,provider.requests.size);assertEquals(0,JSONObject(result.state).getInt("count"))
            val correction=provider.requests[1].turns.last().text
            assertTrue(correction,correction.contains("Smoke event:"));assertTrue(correction,correction.contains("Path: $."))
            assertFalse(correction,correction.contains("SECRET_"));assertFalse(correction,correction.contains("content://"))
            assertFalse(correction,correction.contains("Error:"));assertEquals(JSONObject(invalid).withFixtureDrawingBinding("SECRET_LEFT").toString(),provider.requests[1].turns[1].text)
        }
        val invalid=spec("if(e.type==='action')throw Error('SECRET_RUNTIME');")
        val provider=Provider(listOf(invalid))
        val failure=runCatching {ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local fixture",null,emptyList(),CloudSession(),"fixture")}.exceptionOrNull()
        assertTrue(failure is CloudFailure);assertEquals(4,provider.requests.size)
        assertFalse(failure.toString().contains("SECRET_"))
    }
    @Test fun typedControlsAndIndependentBranchesUseDisposableInitialState(): Unit=runBlocking {
        val ui="""{"type":"column","children":[{"type":"button","text":"First","action":"first"},{"type":"button","text":"Second","action":"second"},{"type":"input","text":"Text","bind":"text","action":"text"},{"type":"numberInput","text":"Number","bind":"count","min":0,"max":10,"action":"number"},{"type":"toggle","text":"Toggle","bind":"on","action":"toggle"},{"type":"dateInput","text":"Date","bind":"date","action":"date"},{"type":"timeInput","text":"Time","bind":"time","action":"time"},{"type":"select","text":"Choice","bind":"choice","options":[{"text":"A","value":"a"},{"text":"B","value":"b"}],"action":"choice"}]}"""
        val json=JSONObject(spec("if(e.type==='action'){if(s.count!==0)throw Error('shared branch');s.count=10;}",ui))
            .put("stateSchema",JSONObject("""{"count":"number","text":"string","on":"boolean","date":"string","time":"number","choice":"string"}"""))
            .put("initialState",JSONObject("""{"count":0,"text":"","on":false,"date":"2026-11-02","time":0,"choice":"a"}"""))
        val good=json.toString();val provider=Provider(listOf(good))
        val result=ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local fixture",null,emptyList(),CloudSession(),"fixture")
        assertEquals(1,provider.requests.size);assertEquals(json.getJSONObject("initialState").toString(),result.state)
        for(predicate in listOf("e.actionId==='text' && typeof e.value==='string' && e.value.length>0","e.actionId==='number' && e.value===10","e.actionId==='toggle' && e.value===true","e.actionId==='date'","e.actionId==='time' && e.value===1439","e.actionId==='choice' && e.value==='b'")) {
            provider.requests.clear();provider.outputs=listOf(JSONObject(good).put("source",JSONObject(good).getString("source").replace("const e=input.event,s=input.state;","const e=input.event,s=input.state; if(e.type==='action' && ($predicate))throw Error('typed branch');")).toString(),good)
            ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local fixture",null,emptyList(),CloudSession(),"fixture")
            assertEquals(predicate,2,provider.requests.size)
        }
    }
    @Test fun inputSamplesRespectMaxLengthWithoutChangingSavedStateOrHidingHandlerFailures(): Unit=runBlocking {
        val name="generation-input-limit-${UUID.randomUUID()}.db"
        fun inputSpec(limit: Int,fail: Boolean=false): String = JSONObject(spec()).apply {
            remove("surfaces")
            put("stateSchema",JSONObject("""{"text":"string"}"""))
            put("initialState",JSONObject("""{"text":"abcdef"}"""))
            put("ui",JSONObject("""{"type":"input","text":"Text","bind":"text","maxLength":$limit,"action":"edit"}"""))
            put("source","if(input.event.type==='action'){${if(fail) "if(input.event.value==='abc')throw Error('SECRET_INPUT_FAILURE');" else ""}input.state.text=input.event.value;}return {state:input.state};")
        }.toString()
        val provider=Provider(listOf(inputSpec(6)))
        val store=ToolStore(context,name)
        val engine=ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
        try {
            val first=engine.createDraft("Input fixture",null)
            engine.generate(first.id,"offline",emptyList(),CloudSession())
            val saved=engine.accept(first.id)
            val bounded=inputSpec(3)
            assertTrue(runCatching {ToolUiCatalog.validateAction(ToolSpec.parse(bounded).ui,"edit","abcdef",JSONObject(saved.state))}.exceptionOrNull() is IllegalArgumentException)
            provider.requests.clear();provider.outputs=listOf(bounded)
            val refinement=engine.createDraft("Limit new input submissions",saved.id)
            val preview=engine.generate(refinement.id,"offline",emptyList(),CloudSession())
            assertEquals(1,provider.requests.size);assertEquals(saved.state,preview.previewState)
            val accepted=engine.accept(refinement.id)
            assertEquals(saved.state,accepted.state);assertEquals("abcdef",JSONObject(accepted.state).getString("text"))
            assertTrue(store.effects(accepted.id).isEmpty())
            val invalid=inputSpec(3,true)
            provider.requests.clear();provider.outputs=listOf(invalid,
                JSONObject(bounded).put("description","Repair valid input submissions").toString())
            val repaired=engine.createDraft("Repair a valid submitted value",accepted.id)
            assertEquals(accepted.state,engine.generate(repaired.id,"offline",emptyList(),CloudSession()).previewState)
            assertEquals(2,provider.requests.size);assertEquals(accepted,store.tool(accepted.id));engine.reject(repaired.id)
            assertTrue(provider.requests.last().turns.last().text.contains("Smoke event: action."))
            assertFalse(provider.requests.last().turns.last().text.contains("SECRET_"))
            provider.requests.clear();provider.outputs=listOf(invalid)
            val rejected=engine.createDraft("Reject repeated handler failure",accepted.id)
            assertTrue(runCatching {engine.generate(rejected.id,"offline",emptyList(),CloudSession())}.exceptionOrNull() is CloudFailure)
            assertEquals(4,provider.requests.size);assertNull(store.draft(rejected.id)!!.candidate)
            assertEquals(accepted,store.tool(accepted.id));assertTrue(store.effects(accepted.id).isEmpty())
        } finally {engine.closeArtifacts();store.close();context.deleteDatabase(name)}
    }
    @Test fun requestsNeverExecuteOrFabricateResultsAndRefinementPreservesData(): Unit=runBlocking {
        val name="generation-smoke-${UUID.randomUUID()}.db";val store=ToolStore(context,name)
        val definition=JSONObject(spec("if(e.type==='capabilityResults'||e.type==='recordResults')throw Error('invented result'); if(e.type==='action')return {state:s,effects:[{id:'buzz',capability:'vibration.play',args:{milliseconds:1}}]};"))
            .put("capabilities",JSONArray("[\"vibration.play\"]")).put("capabilityResults",true)
            .put("collections",JSONObject("""{"history":{"type":"object","fields":{"note":"string"}}}"""))
        val provider=Provider(listOf(definition.toString()));val engine=ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
        try {
            val first=engine.createDraft("Make a mini-app with a button that vibrates the phone when pressed",null)
            val preview = try {
                engine.generate(first.id,"offline",emptyList(),CloudSession())
            } catch (failure: CloudFailure) {
                throw AssertionError("Fixture shortlist and repair: " + provider.requests.map { it.turns.joinToString("\n") { turn -> turn.text }
                    .substringAfter("Alphy Android callable shortlist", "").substringBefore(". Declare only these").take(900) }, failure)
            }
            val saved=engine.accept(first.id)
            assertEquals(0,JSONObject(preview.previewState!!).getInt("count"));assertTrue(store.effects(saved.id).isEmpty())
            store.setNetworkGrant(saved.id,true)
            val revisions=store.revisions(saved.id)
            val recordSource="if(e.type==='capabilityResults'||e.type==='recordResults')throw Error('invented result'); if(e.type==='action')return {state:s,records:[{id:'write',collection:'history',op:'put',key:'entry',expectedToken:null,value:{note:'private'}}]};"
            provider.outputs=listOf(JSONObject(definition.toString()).put("source",JSONObject(spec(recordSource)).getString("source")).toString());provider.requests.clear()
            val draft=engine.createDraft("Private records",saved.id);engine.generate(draft.id,"offline",emptyList(),CloudSession())
            assertNull(ToolRecords(store).pending(saved.id));assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM artifact_records WHERE owner=?",arrayOf(saved.id)).use {it.moveToFirst();it.getInt(0)})
            assertEquals(saved,store.tool(saved.id));assertEquals(revisions,store.revisions(saved.id));assertTrue(store.networkGrantEnabled(saved.id));engine.reject(draft.id)
            provider.outputs=listOf(JSONObject(definition.toString()).put("source",JSONObject(spec(recordSource.replace("note:'private'","note:42"))).getString("source")).toString());provider.requests.clear()
            val bad=engine.createDraft("Bad record value",saved.id)
            assertTrue(runCatching {engine.generate(bad.id,"offline",emptyList(),CloudSession())}.exceptionOrNull() is CloudFailure)
            assertEquals(4,provider.requests.size);assertEquals(saved,store.tool(saved.id));assertTrue(store.networkGrantEnabled(saved.id));assertEquals(revisions,store.revisions(saved.id));engine.reject(bad.id)
            provider.requests.clear();provider.outputs=listOf(definition.toString());provider.afterResponse={throw CancellationException("fixture cancellation")}
            val canceled=engine.createDraft("Canceled refinement",saved.id)
            assertTrue(runCatching {engine.generate(canceled.id,"offline",emptyList(),CloudSession())}.exceptionOrNull() is CancellationException)
            assertEquals(1,provider.requests.size);assertNull(store.draft(canceled.id)!!.candidate);assertEquals(saved,store.tool(saved.id));assertTrue(store.networkGrantEnabled(saved.id));assertEquals(revisions,store.revisions(saved.id))
        } finally {engine.closeArtifacts();store.close();context.deleteDatabase(name)}
    }
    @Test fun retainedSessionSurvivesSmokeAndBusyTransientSlotDoesNotRepair(): Unit=runBlocking {
        val runtime=AndroidJsRuntime.get(context);val retained=GraphicsSession(context)
        assertEquals("OK",retained.open("return {state:input.state};","{\"count\":7}").status)
        try {
            val provider=Provider(listOf(spec()))
            ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local fixture",null,emptyList(),CloudSession(),"fixture")
            assertEquals("OK",retained.event(GraphicsContract.event("pause")).status)
            val occupied=runtime.acquire(false,SandboxContract.MAX_BYTES)
            try {
                provider.requests.clear()
                val failure=runCatching {ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local fixture",null,emptyList(),CloudSession(),"fixture")}.exceptionOrNull() as CloudFailure
                assertEquals("javascript_busy",failure.code);assertEquals(1,provider.requests.size)
                assertEquals("OK",retained.event(GraphicsContract.event("resume")).status)
            } finally {occupied.close()}
        } finally {retained.close()}
    }
    @Test fun mergedDrawingLimitsRejectDuringSmokeAndGuidanceMatchesCatalog(): Unit=runBlocking {
        val bad=spec("if(e.type==='frame')return {state:s,surfaces:{[e.surfaceId]:{commands:Array.from({length:257},()=>({type:'group',children:[]}))}}};")
        val provider=Provider(listOf(bad,spec()))
        ToolGenerationService(provider,SandboxClient(context)).generate("offline","Local fixture",null,emptyList(),CloudSession(),"fixture")
        assertEquals(2,provider.requests.size)
        val guidance=provider.requests.first().turns.first().text
        assertTrue(guidance.contains(ArtifactProtocol.modelGuidance()));assertTrue(guidance.contains(ToolUiCatalog.modelGuidance()))
        assertFalse(guidance.contains("30fps"));assertFalse(guidance.contains("30 fps"));assertFalse(guidance.contains("checkpointFields"))
    }
}
