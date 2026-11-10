package com.alpha.assistant.tools.generation

import com.alpha.assistant.tools.withFixtureCheckEvidence
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.AndroidJsRuntime
import com.alpha.assistant.sandbox.RuntimeSandbox
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolExecutionPolicy
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.fixtureScene
import com.alpha.assistant.tools.withFixtureDrawingBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationPolicyWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val request = "Make a mini-app with a button that vibrates the phone when pressed"
    private val plan = """
        {"title":"Policy fixture","description":"","language":"en","inputs":[],"constants":[],"needs":[
          {"id":"custom","type":"other","label":"Local","description":"Show one local button"}
        ],"questions":[]}
    """.trimIndent()

    private inner class Provider(var definition: JSONObject) : ChatProvider {
        val stages = mutableListOf<CloudGenerationStage?>()
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            stages += request.generationStage
            emit(CloudEvent.Text(when (request.generationStage) {
                CloudGenerationStage.PLAN -> if (!definition.has("surfaces")) plan else {
                    val scene = fixtureScene()
                    val scenario = scene.getJSONObject("checks").getJSONObject("scenario")
                    scenario.getJSONObject("slots").put("input", JSONObject("""{"kind":"action"}"""))
                    val steps = scenario.getJSONArray("steps")
                    scenario.put("steps", JSONArray().put(JSONObject("""{"op":"action","slot":"input"}""")).put(steps.getJSONObject(0)))
                    request.withFixtureCheckEvidence(JSONObject(plan).put("scene", scene).toString())
                }
                CloudGenerationStage.COMPLETE -> if (request.turns.last().text.contains("Return one JSON patch object only"))
                    JSONObject().put("fields", JSONObject().put("description", "Retry ${stages.size}")).toString()
                else if (definition.has("surfaces")) {
                    JSONObject(definition.toString()).withFixtureDrawingBinding("board").apply {
                        getJSONObject("behaviorBindings").put("input", "apply")
                    }.toString()
                } else definition.toString()
                else -> error("Unexpected provider call.")
            }))
        }
    }

    private fun definition(source: String = "return {state:input.state};") = JSONObject("""
        {"formatVersion":3,"title":"Policy fixture","description":"","capabilities":[],
         "stateSchema":{"value":"number"},"initialState":{"value":0},
         "ui":{"type":"button","text":"Local","action":"apply"}}
    """.trimIndent()).put("source", source)

    private fun action() = JSONObject().put("type", "action").put("actionId", "apply").put("value", JSONObject.NULL)

    private suspend fun generate(service: ToolGenerationService, policy: ToolExecutionPolicy? = null): ToolPreview =
        service.generate("fixture", request, null, emptyList(), CloudSession(), "policy_fixture", policy = policy)

    @Test fun forbiddenDefinitionStopsBeforeAnIsolateCanOpen(): Unit = runBlocking {
        var opens = 0
        val connection = object : RuntimeSandbox {
            override fun isFeatureSupported(feature: String) = true
            override fun createIsolate(parameters: IsolateStartupParameters): JavaScriptIsolate {
                opens++
                error("Rejected source reached the sandbox.")
            }
            override fun close() {}
        }
        val sandbox = SandboxClient(context, AndroidJsRuntime(context, supported = { true }, connect = { connection }))
        val provider = Provider(definition().put("capabilities", JSONArray().put("vibration.play")))
        val observed = mutableListOf<JSONObject>()
        val generation = ToolGenerationService(provider, sandbox, null) { observed += it.report() }
        val policy = ToolExecutionPolicy(validateDefinition = { json ->
            val spec = JSONObject(json)
            require(spec.getJSONArray("capabilities").length() == 0 && !spec.has("surfaces"))
        }, validateResult = { error("Definition rejection reached output validation.") })

        for (definition in listOf(
            provider.definition,
            definition().put("surfaces", JSONArray("""[{"id":"board","width":64,"height":64}]"""))
        )) {
            provider.definition = definition
            provider.stages.clear()
            val error = runCatching { generate(generation, policy) }.exceptionOrNull() as CloudFailure
            assertEquals("widget_spec", error.code)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE), provider.stages)
            assertEquals("definition", observed.last().getString("stage"))
            assertEquals("finish", observed.last().getString("disposition"))
            val spec = ToolSpec.parse(definition.toString())
            val direct = runCatching {
                generation.evaluate(spec, "policy_fixture", 1, spec.initialState, action(), spec.ui.toString(), policy = policy)
            }.exceptionOrNull() as CloudFailure
            assertEquals("widget_spec", direct.code)
        }
        assertEquals(0, opens)
    }

    @Test fun everyRawActionResultIsCheckedBeforeTheCleanPreviewCanReturn(): Unit = runBlocking {
        val sandbox = SandboxClient(context)
        val cases = listOf(
            "effects" to definition("""
                return input.event.type === "action"
                    ? {state:input.state,effects:[{id:"buzz",capability:"vibration.play",args:{milliseconds:1}}]}
                    : {state:input.state};
            """.trimIndent()).put("capabilities", JSONArray().put("vibration.play")),
            "records" to definition("""
                return input.event.type === "action"
                    ? {state:input.state,records:[{id:"read",collection:"saved",op:"get",key:"one"}]}
                    : {state:input.state};
            """.trimIndent()).put("collections", JSONObject("""{"saved":{"type":"object","fields":{"label":"string"}}}""")),
            "frames" to definition("""
                return input.event.type === "action" ? {state:input.state,frames:[]} : {state:input.state};
            """.trimIndent()),
            "timers" to definition("""
                return input.event.type === "action" ? {state:input.state,timers:[]} : {state:input.state};
            """.trimIndent()),
            "surfaces" to definition("""
                return input.event.type === "action"
                    ? {state:input.state,surfaces:{board:{commands:[{type:"circle",x:10,y:10,radius:2,color:"accent"}]}}}
                    : {state:input.state};
            """.trimIndent()).put("surfaces", JSONArray("""[{"id":"board","width":64,"height":64}]"""))
        )
        for ((forbidden, definition) in cases) {
            val provider = Provider(definition)
            val events = mutableListOf<JSONObject>()
            val generation = ToolGenerationService(provider, sandbox, null) { events += it.report() }
            val preview = generate(generation)
            assertEquals("{\"value\":0}", preview.state)
            assertTrue(preview.effects.isEmpty())
            assertEquals(0, preview.records.length())

            val seen = mutableListOf<Set<String>>()
            val policy = ToolExecutionPolicy(validateDefinition = {}, validateResult = { json ->
                val keys = JSONObject(json).keys().asSequence().toSet()
                seen += keys
                require(keys.all { it in setOf("state", "ui") })
            })
            provider.stages.clear()
            val failure = runCatching { generate(generation, policy) }.exceptionOrNull() as CloudFailure
            assertEquals("widget_preview", failure.code)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE), provider.stages)
            assertTrue(seen.first() == setOf("state"))
            assertTrue(forbidden in seen.last())
            assertEquals("action", events.last().getJSONObject("smoke").getString("event"))
            assertEquals("finish", events.last().getString("disposition"))

            val rejected = runCatching {
                generation.evaluate(preview.spec, "policy_fixture", 1, preview.state, action(), preview.ui, policy = policy)
            }.exceptionOrNull() as CloudFailure
            assertEquals("widget_preview", rejected.code)
            val clean = generation.evaluate(preview.spec, "policy_fixture", 1, preview.state,
                JSONObject().put("type", "render"), preview.ui, policy = policy)
            assertEquals(preview.state, clean.state)
            assertEquals(preview.ui, clean.ui)
        }
    }

    @Test fun smokeUsesTheActiveRouteAndChecksBackWithTheExactStack(): Unit = runBlocking {
        val source = """
            const event = input.event, state = input.state;
            if (event.actionId === "push") {
              if (state.stack.length !== 1) throw Error("Inactive root action.");
              state.stack.push("detail");
            }
            if (event.actionId === "detail") {
              if (state.stack[state.stack.length-1] !== "detail") throw Error("Inactive detail action.");
              state.value++;
            }
            if (event.actionId === "routeBack") {
              if (JSON.stringify(event.value) !== JSON.stringify(state.stack)) throw Error("Wrong stack.");
              state.stack.pop();
            }
            return {state};
        """.trimIndent()
        val route = definition(source).apply {
            getJSONObject("stateSchema").put("stack", JSONObject("""{"type":"array","items":"string","maxItems":4}"""))
            getJSONObject("initialState").put("stack", JSONArray().put("home"))
            put("ui", JSONObject("""
                {"type":"routeStack","text":"Local route","bind":"stack","action":"routeBack",
                 "routes":["home","detail"],"children":[
                   {"type":"button","text":"Open","action":"push"},
                   {"type":"button","text":"Detail","action":"detail"}
                 ]}
            """.trimIndent()))
        }
        val provider = Provider(route)
        val events = mutableListOf<JSONObject>()
        val generation = ToolGenerationService(provider, SandboxClient(context), null) { events += it.report() }
        val preview = generate(generation)
        assertEquals("home", JSONObject(preview.state).getJSONArray("stack").getString(0))
        assertEquals("validated", events.last().getString("outcome"))

        provider.definition = JSONObject(route.toString()).put("source", source.replace("state.stack.pop();", "throw Error('Back reached.');"))
        val rejected = runCatching { generate(generation) }.exceptionOrNull() as CloudFailure
        assertEquals("widget_preview", rejected.code)
        assertEquals("action", events.last().getJSONObject("smoke").getString("event"))
    }

    @Test fun policyCancellationDoesNotReturnAPreviewOrSpendTheCorrection(): Unit = runBlocking {
        val provider = Provider(definition())
        val observed = mutableListOf<JSONObject>()
        val generation = ToolGenerationService(provider, SandboxClient(context), null) { observed += it.report() }
        val policy = ToolExecutionPolicy(validateDefinition = {}, validateResult = { throw CancellationException("Fixture stop") })
        val error = runCatching { generate(generation, policy) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE), provider.stages)
        assertEquals("canceled", observed.last().getString("outcome"))
        assertEquals("finish", observed.last().getString("disposition"))
    }
}
