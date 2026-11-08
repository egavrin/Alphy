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
        val example = JSONObject(writer.systemInstructions.substringAfter("Schema:\n").lineSequence().first())
            .getJSONObject("behaviorBindings")
        assertEquals(JSONObject(appBindings).keys().asSequence().toSet(), example.keys().asSequence().toSet())
        assertTrue(example.get("enter") is String)
        assertEquals(0, example.getJSONArray("result").getInt(0))
        assertTrue(writer.systemInstructions.contains("\"enter\":[\"number\"]"))
        assertTrue(writer.systemInstructions.contains("\"apply\":[\"null\"]"))
        assertFalse(writer.systemInstructions.contains("\"value\":7"))
        assertFalse(writer.systemInstructions.contains("\"value\":14"))
        assertFalse(writer.turns.first().text.contains("\"value\":7"))
        val repair = provider.calls.last().turns.last().text
        assertTrue(repair.contains("assertion"))
        assertFalse(repair.contains("14"))
    }

    @Test fun capturedTipWriterActionPathsPassTheBehaviorCheck(): Unit = runBlocking {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("behavior-replay/tip-action-paths.json").bufferedReader().use { JSONObject(it.readText()) }
        val raw = fixture.getJSONObject("candidate").toString()
        val provider = Provider(plan(fixture.getJSONObject("scenario").toString()), raw, raw)
        generate(provider)
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE), provider.calls.map { it.generationStage })
    }

    @Test fun unavailableActionPathsStillRejectTheCandidate(): Unit = runBlocking {
        val ordinary = app(true)
        val hidden = JSONObject(ordinary).apply {
            getJSONObject("ui").getJSONArray("children").getJSONObject(0).put("visible", false)
        }.toString()
        for ((definition, path) in listOf(ordinary to "[]", ordinary to "[2]", ordinary to "[8]",
            ordinary to "[0.5]", ordinary to "[1]", hidden to "[0]")) {
            val bindings = JSONObject(appBindings).put("enter", org.json.JSONArray(path))
            val raw = candidate(definition, bindings.toString())
            val provider = Provider(plan(appPlan), raw, raw)
            val failure = runCatching { generate(provider) }.exceptionOrNull() as? CloudFailure
            assertEquals(path, "widget_behavior", failure?.code)
            assertEquals(path, 3, provider.calls.size)
        }
    }

    @Test fun malformedWriterBindingsIdentifyTheRejectedSlotAndKeepTheCorrectionLimit(): Unit = runBlocking {
        val scenario = """{"slots":{"action":{"kind":"action"},"surface":{"kind":"surface"},
            "timer":{"kind":"timer"},"privateGeometry":{"kind":"geometry"},"value":{"kind":"value"},
            "collection":{"kind":"collection"}},"steps":[{"op":"action","slot":"action"},
            {"op":"surface","slot":"surface","event":"restart"},{"op":"timers","slot":"timer","count":1},
            {"op":"assert","slot":"privateGeometry","test":"nonempty"},
            {"op":"assert","slot":"value","test":"nonempty"},{"op":"assert","slot":"collection","test":"equals","value":0}]}"""
        val valid = """{"action":"apply","surface":"main","timer":"tick",
            "privateGeometry":{"surface":"main","path":[0]},"value":[2],"collection":[4]}"""
        data class Rejection(val slot: Int?, val kind: String?, val shape: String, val expected: String,
            val invalidBindings: JSONObject.() -> Unit)
        val cases = listOf(
            Rejection(1, "action", "object", "action ID string") { put("action", JSONObject().put("privateData", 7)) },
            Rejection(1, "action", "array", "integer") { put("action", org.json.JSONArray().put(0.5)) },
            Rejection(2, "surface", "number", "surface ID string") { put("surface", 7) },
            Rejection(3, "timer", "boolean", "timer ID string") { put("timer", false) },
            Rejection(4, "geometry", "array", "geometry") { put("privateGeometry", org.json.JSONArray()) },
            Rejection(4, "geometry", "object", "surface ID string") {
                getJSONObject("privateGeometry").put("surface", JSONObject().put("privateData", true))
            },
            Rejection(4, "geometry", "string", "geometry path") {
                getJSONObject("privateGeometry").put("path", "PRIVATE_LABEL")
            },
            Rejection(5, "value", "number", "index path") { put("value", 2) },
            Rejection(6, "collection", "string", "index path") { put("collection", "PRIVATE_LABEL") },
            Rejection(null, null, "object", "requested slot IDs") { remove("action"); put("privateData", "apply") },
            Rejection(null, null, "missing", "object") {},
        )
        for (case in cases) {
            val raw = JSONObject(app(true)).apply {
                if (case.shape != "missing") put("behaviorBindings", JSONObject(valid).apply(case.invalidBindings))
            }.toString()
            val provider = Provider(plan(scenario), raw, raw)
            val diagnostics = mutableListOf<JSONObject>()
            val result = runCatching {
                ToolGenerationService(provider, SandboxClient(context), observer = null,
                    attemptObserver = { diagnostics += it.report() })
                    .generate("offline", "Build the requested tool", null, emptyList(), CloudSession(), "behavior")
            }
            assertEquals("widget_behavior", (result.exceptionOrNull() as? CloudFailure)?.code)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.COMPLETE),
                provider.calls.map { it.generationStage })
            val diagnostic = diagnostics.last().getJSONObject("behaviorBinding")
            assertEquals(case.slot, diagnostic.opt("slot"))
            assertEquals(case.kind, diagnostic.opt("slotKind"))
            assertEquals(case.shape, diagnostic.getString("valueShape"))
            assertEquals("binding_shape", diagnostic.getString("rule"))
            val correction = provider.calls.last().turns.last().text
            assertTrue(correction, correction.contains(case.expected))
            for (private in listOf("privateGeometry", "privateData", "PRIVATE_LABEL")) {
                assertFalse(diagnostics.toString().contains(private))
                assertFalse(correction.contains(private))
            }
        }
    }

    @Test fun aCorrectedWriterBindingPassesTheSameBehaviorScenario(): Unit = runBlocking {
        val malformed = JSONObject(appBindings).put("apply", JSONObject()).toString()
        val provider = Provider(plan(appPlan), candidate(app(true), malformed), candidate(app(true), appBindings))
        val result = generate(provider)
        assertEquals(0, JSONObject(result.state).getInt("result"))
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.COMPLETE),
            provider.calls.map { it.generationStage })
        val correction = provider.calls.last().turns.last().text
        assertTrue(correction, correction.contains("action ID string"))
        assertTrue(correction, correction.contains("\"slot\":2"))
        assertTrue(correction, correction.contains("\"valueShape\":\"object\""))
    }

    @Test fun writerResultDiagnosticsDescribeTheCurrentUiWithoutCandidateData(): Unit = runBlocking {
        val scenario = """{"slots":{"press":{"kind":"action"},"privateResult":{"kind":"value"}},
            "steps":[{"op":"action","slot":"press"},
            {"op":"assert","slot":"privateResult","test":"equals","value":1}]}"""
        val bindings = """{"press":"press","privateResult":[1]}"""
        val visible = """{type:'metric',text:'PRIVATE_LABEL',bind:'privateCount'}"""
        val cases = listOf(
            visible to null,
            """{type:'metric',text:'PRIVATE_LABEL',bind:'privateCount',visible:false}""" to "target_hidden",
            """{type:'button',text:'PRIVATE_LABEL',action:'privateAction'}""" to "observation_kind",
        )
        for ((resultNode, rule) in cases) {
            val definition = """{"formatVersion":3,"title":"Dynamic result","description":"","capabilities":[],
                "stateSchema":{"privateCount":"number"},"initialState":{"privateCount":0},
                "ui":{"type":"column","children":[{"type":"button","text":"Press","action":"press"},
                {"type":"text","text":"Ready"}]},
                "source":"const s=input.state,e=input.event;if(e.type==='action'&&e.actionId==='press')s.privateCount=1;
                const children=[{type:'button',text:'Press',action:'press'},s.privateCount===1?$resultNode:{type:'text',text:'Ready'}];
                return {state:s,ui:{type:'column',children}};"}""".replace("\n", " ")
            val raw = candidate(definition, bindings)
            val provider = Provider(plan(scenario), raw, raw)
            val diagnostics = mutableListOf<JSONObject>()
            val outcome = runCatching {
                ToolGenerationService(provider, SandboxClient(context), observer = null, attemptObserver = { diagnostics += it.report() })
                    .generate("offline", "Build the requested tool", null, emptyList(), CloudSession(), "behavior")
            }
            if (rule == null) {
                assertEquals(0, JSONObject(outcome.getOrThrow().state).getInt("privateCount"))
            } else {
                assertEquals("widget_behavior", (outcome.exceptionOrNull() as? CloudFailure)?.code)
                val report = diagnostics.last().getJSONObject("behaviorBinding")
                assertEquals("whole_screen", report.getString("route"))
                assertEquals("writer", report.getString("origin"))
                assertEquals(2, report.getInt("step"))
                assertEquals(2, report.getInt("slot"))
                assertEquals("value", report.getString("slotKind"))
                assertEquals(rule, report.getString("rule"))
                if (rule == "observation_kind") assertEquals("button", report.getString("nodeKind"))
                for (secret in listOf("PRIVATE_LABEL", "privateCount", "privateResult", "privateAction")) {
                    assertFalse(secret, diagnostics.toString().contains(secret))
                    assertFalse(secret, provider.calls.last().turns.last().text.contains(secret))
                }
            }
        }
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
