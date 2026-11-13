package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.generation.PartsContractFailure
import com.alpha.assistant.tools.generation.SceneRequirement
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CanvasGenerationWorkflowTest {
    @Test fun aWriterCannotAddDrawingWithoutAFrozenPlan(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inline = definition().getJSONObject("ui")
        val stage = JSONObject("""{"type":"text","text":"A drawing"}""")
        for ((mode, ui) in listOf("app" to inline, "app" to stage, "immersive" to stage)) {
            val candidate = definition().put("ui", ui).put("presentation", JSONObject().put("mode", mode))
            val provider = object : ChatProvider {
                private var repair = 0
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    val output = when {
                        request.generationStage == CloudGenerationStage.PLAN -> """{"wholeScreen":true,"language":"en"}"""
                        request.turns.size == 1 -> candidate.toString()
                        else -> """{"fields":{"description":"Still unplanned drawing ${++repair}"}}"""
                    }
                    emit(CloudEvent.Text(request.withFixtureCheckEvidence(output)))
                }
            }
            val name = "canvas-plan-${UUID.randomUUID()}.db"
            try {
                ToolStore(context, name).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    val draft = engine.createDraft("Draw four diagrams.", null)
                    val failure = runCatching { engine.generate(draft.id, "offline", emptyList(), CloudSession()) }.exceptionOrNull() as CloudFailure
                    assertEquals("widget_spec", failure.code)
                    assertEquals(null, store.draft(draft.id)?.candidate)
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun aSceneCanCheckEitherLiveOrApplyInput(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((live, required) in listOf(false to false, true to true, false to true)) {
            val plan = JSONObject("""{"wholeScreen":true,"language":"en","scene":{"placement":"inline","brief":"Show a movable dot.",
                "checks":{"status":"checkable","scenario":{"slots":{"position":{"kind":"action"},"dot":{"kind":"geometry"}},
                "steps":[{"op":"capture","slot":"dot","as":"before"},{"op":"action","slot":"position","value":32},
                {"op":"assert","slot":"dot","test":"changed","capture":"before"}]}}}}""")
            if (required) plan.getJSONObject("scene").put("liveInputs", JSONArray().put("position"))
            val event = if (live) "liveInput" else "action"
            val candidate = JSONObject("""{"formatVersion":3,"title":"Dot","description":"","capabilities":[],
                "stateSchema":{"x":"number"},"initialState":{"x":16},"surfaces":[{"id":"board","width":64,"height":64}],
                "ui":{"type":"column","children":[{"type":"canvas","text":"Diagram","surfaces":["board"]},
                {"type":"slider","text":"Position","min":0,"max":48,"bind":"x","action":"move"}]},
                "behaviorBindings":{"position":"move","dot":{"surface":"board","path":[]}}}""")
                .put("source", """
                    const s=input.state,e=input.event;
                    if (e.type==='$event') s.x=e.value;
                    return {state:s,surfaces:{board:{commands:[{type:'circle',x:s.x,y:32,radius:4,color:'accent'}]}}};
                """.trimIndent())
            if (live) candidate.getJSONObject("ui").getJSONArray("children").getJSONObject(1).put("inputMode", "live")
            val provider = object : ChatProvider {
                var repairs = 0
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    val output = when {
                        request.generationStage?.name == "PLAN" -> plan.toString()
                        request.turns.last().text.contains("Return one JSON patch object only") ->
                            JSONObject().put("fields", JSONObject().put("description", "Retry ${++repairs}")).toString()
                        else -> candidate.toString()
                    }
                    emit(CloudEvent.Text(request.withFixtureCheckEvidence(output)))
                }
            }
            val result = runCatching { ToolGenerationService(provider, SandboxClient(context))
                .generate("offline", "Move a dot with a slider.", null, emptyList(), CloudSession(), "canvas-input") }
            if (live || !required) assertTrue(result.isSuccess)
            else assertEquals("widget_behavior", (result.exceptionOrNull() as CloudFailure).code)
        }
    }

    @Test fun sceneExcerptsIgnoreLineWrapsButKeepTheOriginalText() {
        val request = "Show a blue\r\n\tcircle. Keep the original angle."
        val wrapped = "  Show a blue circle.\n"
        fun scene(evidence: String, visual: String) = fixtureScene()
            .put("checkEvidence", JSONArray().put(JSONObject().put("step", 1).put("excerpt", evidence)
                .put("purpose", "outcome").put("scope", "whole")))
            .put("visualReview", JSONArray().put(visual))
        val accepted = SceneRequirement.read(scene(wrapped, wrapped), request = request)
        assertEquals(listOf(wrapped), accepted?.evidence?.visualReview)
        for (invalid in listOf("show a blue circle.", "Show a blue circle!", "Show a green circle.",
            "Show a circle.", "Show a bluecircle.", "\t\n", " ".repeat(601) + "Show")) {
            for ((evidence, visual) in listOf(invalid to wrapped, wrapped to invalid)) {
                assertTrue("Unexpectedly accepted excerpt ${invalid.take(40)}",
                    runCatching { SceneRequirement.read(scene(evidence, visual), request = request) }
                        .exceptionOrNull() is PartsContractFailure)
            }
        }
    }

    @Test fun missingButtonBindingsUseExactIdsAndValidateAtExecution(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        data class Case(val name: String, val slot: String = "move", val binding: Any? = null,
            val unlock: Boolean = false, val payload: Boolean = false, val failure: String? = null)
        val cases = listOf(
            Case("available at its step", unlock = true),
            Case("explicit binding wins", binding = "alternate"),
            Case("wrong case", slot = "Move", failure = "bindings"),
            Case("label only", slot = "MoveDot", failure = "bindings"),
            Case("input", slot = "position", failure = "bindings"),
            Case("payload", payload = true, failure = "bindings"),
            Case("explicit null", binding = JSONObject.NULL, failure = "bindings"),
            Case("invalid explicit binding", binding = 1, failure = "bindings"),
            Case("still disabled at its step", failure = "action or input unavailable"),
        )
        for (test in cases) {
            val plan = JSONObject("""{"wholeScreen":true,"language":"en","scene":{"placement":"inline","brief":"Move the dot.",
                "checks":{"status":"checkable","scenario":{"slots":{"dot":{"kind":"geometry"}},
                "steps":[{"op":"capture","slot":"dot","as":"before"}]}}}}""")
            val scenario = plan.getJSONObject("scene").getJSONObject("checks").getJSONObject("scenario")
            val slots = scenario.getJSONObject("slots")
            val steps = scenario.getJSONArray("steps")
            fun action(id: String): JSONObject {
                slots.put(id, JSONObject().put("kind", "action"))
                return JSONObject().put("op", "action").put("slot", id).also { steps.put(it) }
            }
            if (test.unlock) action("unlock")
            if (test.payload) action(test.slot).put("value", 32) else action(test.slot)
            steps.put(JSONObject("""{"op":"assert","slot":"dot","test":"changed","capture":"before"}"""))
            val candidate = JSONObject("""{"formatVersion":3,"title":"Dot","description":"","capabilities":[],
                "stateSchema":{"x":"number","ready":"boolean"},"initialState":{"x":16,"ready":false},
                "surfaces":[{"id":"board","width":64,"height":64}],
                "ui":{"type":"column","children":[{"type":"canvas","text":"Diagram","surfaces":["board"]},
                    {"type":"button","text":"Unlock","action":"unlock"},
                    {"type":"button","text":"MoveDot","action":"move","enabledBind":"ready"},
                    {"type":"button","text":"Alternate","action":"alternate"},
                    {"type":"slider","text":"Position","min":0,"max":48,"bind":"x","action":"position"}]},
                "behaviorBindings":{"dot":{"surface":"board","path":[]}}}""")
                .put("source", """
                    const s=input.state,e=input.event;
                    if (e.type==='action' && e.actionId==='unlock') s.ready=true;
                    if (e.type==='action' && ['move','alternate'].includes(e.actionId)) s.x=32;
                    return {state:s,surfaces:{board:{commands:[{type:'circle',x:s.x,y:32,radius:4,color:'accent'}]}}};
                """.trimIndent())
            if (test.binding != null) candidate.getJSONObject("behaviorBindings").put(test.slot, test.binding)
            val calls = mutableListOf<CloudGenerationStage>()
            val provider = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    calls += checkNotNull(request.generationStage)
                    val reply = when (calls.toList()) {
                        listOf(CloudGenerationStage.PLAN) -> request.withFixtureCheckEvidence(plan.toString())
                        listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE) -> candidate.toString()
                        else -> error("Unexpected provider call: $calls")
                    }
                    emit(CloudEvent.Text(reply))
                }
            }
            val session = CloudSession().apply {
                if (test.failure != null) repeat(3) { claimGenerationRepair(CloudGenerationStage.COMPLETE) }
            }
            val result = runCatching { ToolGenerationService(provider, SandboxClient(context))
                .generate("offline", "Unlock and move the dot.", null, emptyList(), session, "canvas-button") }
            assertEquals(test.name, listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE), calls)
            if (test.failure == null) result.getOrThrow()
            else {
                val failure = result.exceptionOrNull() as? CloudFailure
                assertEquals(test.name, "widget_behavior", failure?.code)
                assertTrue("${test.name}: ${failure?.message}", failure?.message.orEmpty().contains(test.failure))
            }
        }
    }

    @Test fun inlineCanvasesValidateDeclaredSurfaceOwnership() {
        val raw = definition()
        assertEquals(4, ToolSpec.parse(raw.toString()).surfaces.size)
        for (invalid in listOf(
            definition().apply { getJSONArray("surfaces").remove(3) },
            definition().apply { getJSONObject("ui").getJSONArray("children").getJSONObject(1).put("surfaces", JSONArray(listOf("a", "b"))) },
            definition().apply { getJSONArray("surfaces").put(JSONObject("""{"id":"extra","width":64,"height":64}""")) },
        )) {
            assertTrue(runCatching { ToolSpec.parse(invalid.toString()) }.exceptionOrNull() is ToolValidationFailure)
        }
        val immersive = definition().put("presentation", JSONObject().put("mode", "immersive"))
        val placementFailure = runCatching { ToolSpec.parse(immersive.toString()) }.exceptionOrNull() as ToolValidationFailure
        assertEquals("canvas_placement", placementFailure.details?.rule)
        val legacy = ToolSpec.parse(definition().put("ui", JSONObject("""{"type":"text","text":"Legacy stage"}""")).toString())
        assertTrue(runCatching { legacy.validateUi(raw.getJSONObject("ui")) }.exceptionOrNull() is ToolValidationFailure)
    }

    @Test fun invisibleTransformDoesNotExposeItsCanvasToTheSceneCheck() {
        val ui = JSONObject("""{"type":"transform","pose":[0,0,0,0,0,0,1,0],"children":[
            {"type":"canvas","text":"Hidden drawing","surfaces":["a"]}]}""")
        assertEquals(listOf("a"), canvasSurfaceIds(ui))
        assertTrue(canvasSurfaceIds(ui, JSONObject()).isEmpty())
    }

    @Test fun separateCanvasGroupsShareOneRetainedSession(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "canvas-groups-${UUID.randomUUID()}.db"
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) = error("Offline")
        }
        val spec = ToolSpec.parse(definition().apply {
            put("source", """
                globalThis.phase ??= 0;
                const ids = ['a', 'b', 'c', 'd'], e = input.event;
                const result = {state: input.state, surfaces: {}};
                const output = e.type === 'frame' ? [e.surfaceId] : ids;
                for (const id of output) result.surfaces[id] = {commands:[{type:'circle',x:16 + globalThis.phase,y:32,radius:5,color:'accent'}]};
                if (e.type === 'frame') globalThis.phase++;
                if (e.type === 'open') result.frames = ids;
                return result;
            """.trimIndent())
        }.toString())
        try {
            ToolStore(context, name).use { store ->
                val tool = ToolRecord("groups", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
                store.saveTool(tool, true)
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                try {
                    val opened = engine.openArtifact(tool)
                    assertEquals(spec.surfaces.keys, opened.frames)
                    val left = engine.artifactEvent(tool.id, 1, GraphicsContract.event("frame", 16, surfaceId = "a"), opened.sessionGeneration)
                    val right = engine.artifactEvent(tool.id, 1, GraphicsContract.event("frame", 16, surfaceId = "c"), opened.sessionGeneration)
                    fun x(frame: ArtifactSnapshot, id: String) = frame.surfaces.getJSONObject(id).getJSONArray("commands").getJSONObject(0).getInt("x")
                    assertEquals(16, x(left, "a"))
                    assertEquals(17, x(right, "c"))
                    assertEquals(16, x(right, "b"))
                    assertEquals(16, x(right, "d"))
                } finally {
                    engine.closeArtifacts()
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun requiredCanvasMustDrawAndRepairCannotDropIt(): Unit = runBlocking {
        val blank = definition().apply { put("source", "return {state:input.state};") }
        val valid = definition()
        val calls = mutableListOf<String>()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                val stage = request.generationStage?.name.orEmpty()
                calls += stage
                val result = if (stage == "PLAN") {
                    """{"wholeScreen":true,"language":"en","scene":{"placement":"inline","brief":"Four visible diagrams","checks":{"status":"checkable","scenario":{"slots":{"drawing":{"kind":"geometry"}},"steps":[{"op":"assert","slot":"drawing","test":"nonempty"}]}}}}"""
                } else {
                    val candidate = if (calls.count { it == stage } == 1) blank else valid
                    if (candidate === blank) JSONObject(candidate.toString()).put("behaviorBindings",
                        JSONObject("""{"drawing":{"surface":"a","path":[]}}""")).toString()
                    else JSONObject().put("sourceEdits", JSONArray().put(JSONObject()
                        .put("search", blank.getString("source")).put("replace", valid.getString("source")))).toString()
                }
                emit(CloudEvent.Text(request.withFixtureCheckEvidence(result)))
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "canvas-draft-${UUID.randomUUID()}.db"
        try {
            val id = ToolStore(context, name).use { store ->
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                val draft = engine.createDraft("Show four diagrams between the heading and the description", null)
                engine.generate(draft.id, "offline", emptyList(), CloudSession()).id
            }
            ToolStore(context, name).use { store ->
                val draft = checkNotNull(store.draft(id))
                assertEquals(listOf("a", "b", "c", "d"), canvasSurfaceIds(JSONObject(checkNotNull(draft.previewUi))))
                val drawing = JSONObject(checkNotNull(draft.previewSurfaces))
                assertTrue(drawing.getJSONObject("a").getJSONArray("commands").length() > 0)
            }
        } finally {
            context.deleteDatabase(name)
        }
        assertEquals(2, calls.count { it == "COMPLETE" })
    }

    private fun definition() = JSONObject("""
        {"formatVersion":3,"title":"Shapes","description":"","capabilities":[],"stateSchema":{"ready":"boolean"},"initialState":{"ready":true},
        "surfaces":[{"id":"a","width":64,"height":64},{"id":"b","width":64,"height":64},
        {"id":"c","width":64,"height":64},{"id":"d","width":64,"height":64}],
        "ui":{"type":"column","children":[{"type":"canvas","text":"First pair","surfaces":["a","b"]},
        {"type":"canvas","text":"Second pair","surfaces":["c","d"]}]}}
    """.trimIndent()).put("source", """
        const frame={commands:[{type:'circle',x:32,y:32,radius:10,color:'accent'}]};
        return {state:input.state,surfaces:{a:frame,b:frame,c:frame,d:frame}};
    """.trimIndent())
}
