package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationBehaviorWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val appPlan = """
        {"slots":{"enter":{"kind":"action"},"apply":{"kind":"action"},
        "reset":{"kind":"action"},"result":{"kind":"value"}},
        "steps":[{"op":"capture","slot":"result","as":"initial"},{"op":"action","slot":"enter","value":7},
        {"op":"action","slot":"apply"},{"op":"assert","slot":"result","test":"equals","value":14},
        {"op":"action","slot":"reset"},{"op":"assert","slot":"result","test":"equals","capture":"initial"}]}
    """.trimIndent()
    private val appBindings = """{"enter":"amount","apply":"apply","reset":"reset","result":[2]}"""
    private val gamePlan = """
        {"slots":{"screen":{"kind":"surface"},"object":{"kind":"geometry"}},
        "steps":[{"op":"surface","slot":"screen","event":"restart"},{"op":"capture","slot":"object","as":"initial"},
        {"op":"frames","slot":"screen","count":4,"observe":"object","changes":3},
        {"op":"surface","slot":"screen","event":"restart"},{"op":"assert","slot":"object","test":"equals","capture":"initial"}]}
    """.trimIndent()

    private class Provider(private val plan: String, private vararg val candidates: String) : ChatProvider {
        val calls = mutableListOf<CloudRequest>()
        private var next = 0
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            calls += request
            emit(CloudEvent.Text(if (request.generationStage == CloudGenerationStage.PLAN) plan else candidates[next++]))
        }
    }

    private fun plan(scenario: String) = """{"wholeScreen":true,"behavior":{"status":"checkable","scenario":$scenario}}"""
    private fun candidate(definition: String, bindings: String) =
        JSONObject(definition).put("behaviorBindings", JSONObject(bindings)).toString()

    private suspend fun generate(provider: Provider): ToolPreview =
        ToolGenerationService(provider, SandboxClient(context))
            .generate("offline", "Build the requested tool", null, emptyList(), CloudSession(), "behavior")

    private fun app(working: Boolean) = """
        {"formatVersion":3,"title":"Double","description":"","capabilities":[],
        "stateSchema":{"amount":"number","result":"number"},"initialState":{"amount":0,"result":0},
        "ui":{"type":"column","children":[{"type":"numberInput","text":"Amount","bind":"amount","action":"amount","min":0,"max":10},
        {"type":"button","text":"Double","action":"apply"},{"type":"metric","text":"Result","bind":"result"},
        {"type":"button","text":"Reset","action":"reset"}]},
        "source":"const {state:s,event:e}=input;if(e.type==='action'){if(e.actionId==='amount')s.amount=e.value;if(e.actionId==='apply')s.result=${if (working) "s.amount*2" else "s.amount"};if(e.actionId==='reset'){s.amount=0;s.result=0;}}return {state:s};"}
    """.trimIndent()

    @Test fun repairsTheDeclaredVisibleOutcomeAndDoesNotExposeTheTestInput(): Unit = runBlocking {
        val provider = Provider(plan(appPlan), candidate(app(false), appBindings), candidate(app(true), appBindings))
        val result = generate(provider)
        assertEquals(0, JSONObject(result.state).getInt("result"))
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.COMPLETE),
            provider.calls.map { it.generationStage })
        val writer = provider.calls[1]
        assertTrue(writer.systemInstructions.contains("behaviorBindings"))
        assertTrue(writer.systemInstructions.contains("\"enter\":[\"number\"]"))
        assertTrue(writer.systemInstructions.contains("\"apply\":[\"null\"]"))
        assertFalse(writer.systemInstructions.contains("\"value\":7"))
        assertFalse(writer.systemInstructions.contains("\"value\":14"))
        assertFalse(writer.turns.first().text.contains("\"value\":7"))
        val repair = provider.calls.last().turns.last().text
        assertTrue(repair.contains("assertion"))
        assertFalse(repair.contains("14"))
    }

    @Test fun terminalBehaviorFailurePreservesTheSavedTool(): Unit = runBlocking {
        val raw = candidate(app(false), appBindings)
        val provider = Provider("""{"behavior":{"status":"checkable","scenario":$appPlan}}""", raw, raw)
        val name = "behavior-${java.util.UUID.randomUUID()}.db"
        ToolStore(context, name).use { store ->
            val spec = ToolSpec.parse(app(true))
            val old = ToolRecord("behavior", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
            store.saveTool(old, true)
            val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
            val draft = engine.createDraft("Keep the calculator working", old.id)
            val failure = runCatching { engine.generate(draft.id, "offline", emptyList(), CloudSession()) }.exceptionOrNull()
            assertEquals("widget_behavior", (failure as? CloudFailure)?.code)
            assertTrue(provider.calls.first().systemInstructions.contains("English (en)"))
            assertNull(store.draft(draft.id)?.candidate)
            assertEquals(old, store.tool(old.id))
        }
        context.deleteDatabase(name)
    }

    @Test fun gameRequiresRepeatedGeometryChangeAndReset(): Unit = runBlocking {
        val game = GameGenerationExample.spec()
        val bindings = """{"screen":"main","object":{"surface":"main","path":[96]}}"""
        val raw = candidate(game, bindings)
        val result = generate(Provider(plan(gamePlan), raw))
        assertTrue(result.spec.surfaces.containsKey("main"))
        val stationary = JSONObject(game).apply {
            put("source", getString("source").replace("if(e.type==='frame'&&s.status==='playing')", "if(false)"))
        }.toString()
        val broken = candidate(stationary, bindings)
        val failure = runCatching { generate(Provider(plan(gamePlan), broken, broken)) }.exceptionOrNull()
        assertEquals("widget_behavior", (failure as? CloudFailure)?.code)
    }

    @Test fun gameReceivesViewportAndAutomaticStartBeforeTheScenario(): Unit = runBlocking {
        val raw = movingDrawing("""[{type:'circle',x:s.x,y:24,radius:5,color:'accent'}]""")
        val result = generate(Provider(plan(automaticMotionPlan), raw))
        assertEquals(20, JSONObject(result.state).getInt("x"))
    }

    @Test fun motionRemainsObservableOverAnOpaqueBackground(): Unit = runBlocking {
        val drawing = """[{type:'rect',x:0,y:0,width:64,height:64,color:'#000000'},
            {type:'circle',x:s.x,y:24,radius:5,color:'#FFFFFF'}]"""
        val raw = movingDrawing(drawing, path = "[]")
        assertNotNull(generate(Provider(plan(automaticMotionPlan), raw)).surfaces)
    }

    @Test fun anObjectThatMatchesItsBackgroundCannotSatisfyMotion(): Unit = runBlocking {
        val drawing = """[{type:'rect',x:0,y:0,width:64,height:64,color:'#000000'},
            {type:'circle',x:s.x,y:24,radius:5,color:'#000000'}]"""
        for (path in listOf("[]", "[1]")) assertMotionRejected(drawing, path)
    }

    @Test fun checkedCandidateStillRejectsNonIntegerProtocolVersion(): Unit = runBlocking {
        val raw = candidate(app(true), appBindings).replace("\"formatVersion\":3", "\"formatVersion\":3.0")
        val failure = runCatching { generate(Provider(plan(appPlan), raw, raw)) }.exceptionOrNull()
        assertEquals("widget_spec", (failure as? CloudFailure)?.code)
    }

    @Test fun invisibleOrStationaryRenderedGeometryCannotSatisfyMotion(): Unit = runBlocking {
        val drawings = listOf(
            """[{type:'circle',x:s.x,y:24,radius:0,color:'accent'}]""",
            """[{type:'circle',x:s.x,y:24,radius:5,color:'#00FF0000'}]""",
            """[{type:'circle',x:s.x,y:94,radius:5,color:'accent'}]""",
            """[{type:'circle',x:20,y:24,radius:5,color:s.x%2?'#FF0000':'#0000FF'}]""",
            """[{type:'group',transform:[1,0,0,1,s.x,0],children:[{type:'circle',x:40-s.x,y:24,radius:5,color:'accent'}]}]"""
        )
        for (drawing in drawings) assertMotionRejected(drawing)
    }

    private suspend fun assertMotionRejected(drawing: String, path: String = "[0]") {
        val raw = movingDrawing(drawing, path)
        val failure = runCatching { generate(Provider(plan(automaticMotionPlan), raw, raw)) }.exceptionOrNull()
        assertEquals(drawing, "widget_behavior", (failure as? CloudFailure)?.code)
        assertTrue(failure?.message.orEmpty().contains("repeated geometry change"))
    }

    private val automaticMotionPlan = """
        {"slots":{"screen":{"kind":"surface"},"object":{"kind":"geometry"}},
        "steps":[{"op":"frames","slot":"screen","count":4,"observe":"object","changes":3}]}
    """.trimIndent()

    private fun movingDrawing(commands: String, path: String = "[0]"): String {
        val definition = JSONObject("""{"formatVersion":3,"title":"Motion","description":"","capabilities":[],
            "stateSchema":{"x":"number","viewport":"boolean","started":"boolean"},
            "initialState":{"x":20,"viewport":false,"started":false},"ui":{"type":"text","text":"Motion"},
            "surfaces":[{"id":"main","width":64,"height":64,"sizing":"viewport"}],"source":""}""")
        definition.put("source", """
            const s=input.state,e=input.event;
            if(e.type==='viewport')s.viewport=true;
            if(e.type==='start'&&s.viewport)s.started=true;
            if(e.type==='frame')s.x+=3;
            return {state:s,frames:s.started?['main']:[],surfaces:{main:{commands:$commands}}};
        """.trimIndent())
        return candidate(definition.toString(), """{"screen":"main","object":{"surface":"main","path":$path}}""")
    }

    @Test fun aStatusOnlyGameAndAnUncalledSimulationCannotPass(): Unit = runBlocking {
        val bindings = """{"screen":"main","object":{"surface":"main","path":[0]}}"""
        for (source in listOf("", "function simulate(s){s.y-=3;return s;}")) {
            val game = JSONObject("""{"formatVersion":3,"title":"False game","description":"","capabilities":[],
                "stateSchema":{"y":"number"},"initialState":{"y":300},"ui":{"type":"button","text":"Start","action":"start"},
                "source":""}""").apply {
                put("source", source + "return {state:input.state};")
            }.toString()
            val raw = candidate(game, bindings)
            val failure = runCatching { generate(Provider(plan(gamePlan), raw, raw)) }.exceptionOrNull()
            assertEquals("widget_behavior", (failure as? CloudFailure)?.code)
        }
    }
}
