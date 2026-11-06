package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.GraphicsContract
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationProgress
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolGenerationStage
import com.alpha.assistant.tools.ToolGenerationTrace
import com.alpha.assistant.tools.ToolGenerationTraceEvent
import com.alpha.assistant.tools.ToolGenerationTraceOutcome
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GenerationRouteWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val request = "Build a count and keep every requested control."
    private val count = """{"id":"count","type":"counter","label":"Count","description":"Add one and reset"}"""
    private val custom = """{"id":"custom","type":"other","label":"Custom","description":"Save a copy of the current count"}"""
    private val scene = """{"id":"scene","type":"scene","label":"Scene","description":"Move a dot on each frame"}"""
    private val surface = "fill_dot"
    private val counterState = "p5_count_value"
    private val counterAction = "p5_count_add"

    private fun plan(needs: String, questions: String = "[]") =
        """{"title":"Route fixture","description":"Local provider fixtures","language":"en","inputs":[],"constants":[],"needs":[$needs],"questions":$questions}"""

    private fun answer(request: CloudRequest, selections: Map<String, String> = emptyMap()): String {
        val questions = JSONObject(request.turns.single().text).getJSONArray("questions")
        val answers = JSONArray()
        repeat(questions.length()) { i ->
            val question = questions.getJSONObject(i)
            val id = question.getString("id")
            val row = JSONObject().put("id", id)
            if (question.getString("type") == "noul") {
                row.put("probability", 0.8)
            } else {
                val choices = question.getJSONObject("choices")
                val selected = selections[id] ?: "A"
                val probabilities = JSONObject()
                choices.keys().forEach { key -> probabilities.put(key, if (key == selected) 1 else 0) }
                row.put("choice", selected).put("confidence", 1).put("probabilities", probabilities)
            }
            answers.put(row)
        }
        return JSONObject().put("answers", answers).toString()
    }

    private fun definition(
        stateKey: String = "fill_value",
        action: String = "fill_copy",
        source: String = "return {state:input.state};"
    ): String {
        val control = JSONObject().put("type", "button").put("text", "Copy").put("action", action)
        return JSONObject().put("formatVersion", 3).put("title", "Generated").put("description", "")
            .put("capabilities", JSONArray()).put("stateSchema", JSONObject().put(stateKey, "number"))
            .put("initialState", JSONObject().put(stateKey, 0)).put("ui", control).put("source", source).toString()
    }

    private fun sceneFill(
        source: String = """
            if (Object.keys(input.state).join(",") !== "fill_value") throw Error("Part state entered fill.");
            if (input.event.type === "open") {
              try {
                globalThis.__artifact = raw => {
                  raw.state["p" + "5_count_value"] = 91;
                  return {state:raw.state};
                };
              } catch (error) {
                if (!(error instanceof TypeError)) throw error;
              }
            }
            if (input.event.type === "frame" || input.event.type === "action") input.state.fill_value += 1;
            return {state:input.state,frames:["$surface"],surfaces:{"$surface":{commands:[
              {type:"circle",x:8+input.state.fill_value%32,y:16,radius:2,color:"accent"}
            ]}}};
        """.trimIndent()
    ): JSONObject = JSONObject(definition(source = source)).apply {
        remove("formatVersion")
        put("ui", JSONObject().put("type", "column").put("children", JSONArray().put(getJSONObject("ui"))))
        put("surfaces", JSONArray("""[{"id":"$surface","width":64,"height":64}]"""))
    }

    private fun mixed(fill: JSONObject, hole: String = "scene") =
        JSONObject().put("holes", JSONArray().put(hole)).put("fill", fill).toString()

    private class FixtureProvider(
        var fixture: suspend (CloudRequest, CloudSession) -> String
    ) : ChatProvider {
        val requests = mutableListOf<CloudRequest>()
        val stages get() = requests.map { it.generationStage }
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            assertTrue(request.jsonObject)
            assertFalse(request.search)
            requests += request
            emit(CloudEvent.Text(fixture(request, session)))
        }
    }

    private suspend fun withEngine(
        provider: FixtureProvider,
        observer: ((JSONObject) -> Unit)? = null,
        test: suspend (ToolStore, ToolEngine, ToolGenerationService) -> Unit
    ) {
        val name = "generation-route-${UUID.randomUUID()}.db"
        val store = ToolStore(context, name)
        val generation = ToolGenerationService(provider, SandboxClient(context), null) { event ->
            observer?.invoke(event.report())
        }
        val engine = ToolEngine(store, generation)
        try {
            test(store, engine, generation)
        } finally {
            engine.closeArtifacts()
            store.close()
            context.deleteDatabase(name)
            context.deleteDatabase(store.automationDatabase)
        }
    }

    private fun reports(events: List<JSONObject>): List<String> = events.map {
        "${it.getInt("attempt")} ${it.getString("stage")} ${it.optString("selectorBatch", "-")} " +
            "${it.getString("outcome")} ${it.getString("failureCode")} ${it.getString("disposition")}"
    }

    private suspend fun failure(code: String, block: suspend () -> Unit): CloudFailure {
        val failure = runCatching { block() }.exceptionOrNull() as? CloudFailure ?: error("Expected $code.")
        assertEquals(code, failure.code)
        return failure
    }

    @Test fun completePartsSkipCodeAndPublishAfterSmoke(): Unit = runBlocking {
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(count)
                CloudGenerationStage.SELECTOR -> answer(turn, mapOf("v_profile" to "B", "v_preset" to "B"))
                else -> error("Unexpected code generation.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            val progress = mutableListOf<ToolGenerationProgress>()
            val events = mutableListOf<ToolGenerationTraceEvent>()
            val operation = UUID.fromString(draft.id)
            var now = 100L
            val trace = ToolGenerationTrace(operation, onEvent = {
                events += it
                throw IllegalStateException("Trace observer failed.")
            }, clockNanos = { now++ })
            val result = engine.generate(draft.id, "fixture", emptyList(), CloudSession(), { progress += it },
                previewObserver = null, trace = trace)
            assertTrue(events.all { it.operationId == operation && it.attempt == 1 })
            assertEquals(events.map { it.atNanos }.sorted(), events.map { it.atNanos })
            val starts = events.filterIsInstance<ToolGenerationTraceEvent.Stage>().filter { it.started }
            assertEquals(listOf(ToolGenerationStage.PLAN, ToolGenerationStage.ASSEMBLY,
                ToolGenerationStage.SELECTOR, ToolGenerationStage.SELECTOR, ToolGenerationStage.AVAILABILITY, ToolGenerationStage.DEFINITION,
                ToolGenerationStage.STATE, ToolGenerationStage.SMOKE), starts.map { it.stage })
            assertEquals(listOf("sections", "parts"), starts.mapNotNull { it.selectorBatch })
            val finished = events.last() as ToolGenerationTraceEvent.Finished
            assertEquals(ToolGenerationTraceOutcome.VALIDATED, finished.outcome)
            assertEquals(true, finished.partsOnly)
            assertEquals("none", finished.failureCode)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR), provider.stages)
            assertEquals(listOf(ToolGenerationProgress.PLANNING, ToolGenerationProgress.SELECTING, ToolGenerationProgress.VALIDATING), progress)
            val partsBatch = JSONObject(provider.requests.last().turns.single().text).getJSONArray("questions")
            assertEquals(listOf("p_count", "v_profile", "v_preset", "v_focal"),
                (0 until partsBatch.length()).map { partsBatch.getJSONObject(it).getString("id") })
            val spec = ToolSpec.parsePersisted(checkNotNull(result.candidate))
            assertNull(ToolSpec.available(spec.json))
            val recipe = checkNotNull(spec.nativePresentation)
            assertEquals(NativePresentationProfile.WORKSPACE, recipe.profile)
            assertEquals("editorial", recipe.preset)
            assertEquals(counterAction, recipe.primaryActionId)
            assertNotNull(spec.action(spec.ui, counterAction))
            assertEquals(0, JSONObject(result.previewState!!).getInt(counterState))
            assertEquals(result, store.draft(draft.id))
            assertNull(store.tool(draft.id))
            assertEquals(1, engine.accept(draft.id).revision)
        }
    }

    @Test fun invalidVisualChoiceReportsTheSelectorFailureAndUsesThePlainWholeScreen(): Unit = runBlocking {
        val events = mutableListOf<JSONObject>()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(count)
                CloudGenerationStage.SELECTOR -> answer(turn, mapOf("v_profile" to "Z"))
                CloudGenerationStage.COMPLETE -> definition()
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider, { events += it }) { _, engine, _ ->
            val draft = engine.createDraft(request, null)
            val candidate = engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR,
                CloudGenerationStage.COMPLETE), provider.stages)
            assertNull(ToolSpec.parsePersisted(checkNotNull(candidate.candidate)).nativePresentation)
            assertTrue(events.any {
                it.getString("stage") == "selector" && it.getString("failureCode") == "widget_selector"
            })
        }
    }

    @Test fun ordinaryRefinementKeepsTheRecipeLocalAndPreservesTheLastRevision(): Unit = runBlocking {
        var refinement: String? = null
        var cancel = false
        val provider = FixtureProvider { turn, session ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(count)
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.REFINE -> {
                    assertFalse(turn.turns.first().text.contains("\"nativePresentation\""))
                    assertFalse(turn.turns.first().text.contains("\"$counterState\":1"))
                    if (cancel) { session.cancel(); session.check() }
                    checkNotNull(refinement)
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val first = engine.createDraft(request, null)
            engine.generate(first.id, "fixture", emptyList(), CloudSession())
            val saved = engine.accept(first.id)
            val live = engine.interact(saved.id, saved.revision, saved.stateVersion, counterAction, null)
            val original = ToolSpec.parsePersisted(live.spec)
            val recipe = checkNotNull(original.nativePresentation)
            assertEquals(1, JSONObject(live.state).getInt(counterState))
            val revised = JSONObject(original.providerDefinition()).put("title", "Renamed")
            refinement = revised.toString()
            val draft = engine.createDraft("Rename the counter.", live.id)
            val good = engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertTrue(recipe.sameSelection(ToolSpec.parsePersisted(checkNotNull(good.candidate)).nativePresentation))
            assertEquals(live.state, good.previewState)

            val forged = JSONObject(revised.toString()).put("nativePresentation", recipe.json())
            val displaced = JSONObject(revised.toString()).apply {
                getJSONObject("ui").getJSONArray("children").put(0, JSONObject("""{"type":"text","text":"Different slot"}"""))
            }
            for (invalid in listOf(forged, displaced)) {
                provider.requests.clear()
                refinement = invalid.toString()
                failure("widget_spec") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                assertEquals(listOf(CloudGenerationStage.REFINE, CloudGenerationStage.REFINE), provider.stages)
                assertEquals(good, store.draft(draft.id))
                assertEquals(live, store.tool(live.id))
            }
            cancel = true
            failure("cancelled") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(good, store.draft(draft.id))
            assertEquals(live, store.tool(live.id))
            val accepted = engine.accept(draft.id)
            assertTrue(recipe.sameSelection(ToolSpec.parsePersisted(accepted.spec).nativePresentation))
            assertEquals(2, accepted.revision)
            assertEquals(live.state, accepted.state)
        }
    }

    @Test fun blockingQuestionStopsBeforeCodeOrCandidate(): Unit = runBlocking {
        val questions = """[{"id":"target","ask":"What should the counter measure?","options":["Steps","Laps"]}]"""
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(count, questions)
                CloudGenerationStage.SELECTOR -> answer(turn)
                else -> error("A blocking question reached code generation.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            val question = failure("widget_questions") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertTrue(question.message.contains("What should the counter measure?"))
            assertTrue(question.message.contains("Steps"))
            assertEquals(draft, store.draft(draft.id))
        }
    }

    @Test fun selectorFailureCompletesWholeScreenWithAllNeedsAndTheOriginalRequest(): Unit = runBlocking {
        val events = mutableListOf<JSONObject>()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan("$count,$custom")
                CloudGenerationStage.SELECTOR -> """{"answers":[]}"""
                CloudGenerationStage.COMPLETE -> {
                    val prompt = turn.turns.first().text
                    assertTrue(prompt.contains(request))
                    assertTrue(prompt.contains("Add one and reset"))
                    assertTrue(prompt.contains("Save a copy of the current count"))
                    assertEquals(listOf("fixture:image"), turn.turns.first().images)
                    definition()
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider, { events += it }) { _, engine, _ ->
            val draft = engine.createDraft(request, null)
            assertNotNull(engine.generate(draft.id, "fixture", listOf("fixture:image"), CloudSession()).candidate)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.COMPLETE), provider.stages)
            assertTrue(provider.requests[1].turns.single().images.isEmpty())
            assertEquals(listOf(
                "1 plan - completed none continue",
                "1 selector sections completed none continue",
                "1 selector - rejected widget_selector continue",
                "1 smoke - validated none finish"
            ), reports(events))
        }
    }

    @Test fun wholeScreenSceneKeepsItsSurfaceAndChangesDrawingOnAFrame(): Unit = runBlocking {
        val scene = """{"id":"scene","type":"scene","label":"Scene","description":"Move a dot on each frame and reset it on restart"}"""
        val source = """
            const state = input.state;
            if (input.event.type === "frame") state.fill_value = (state.fill_value + 1) % 32;
            if (input.event.type === "restart") state.fill_value = 0;
            return {state, frames:["dot"], surfaces:{dot:{commands:[
              {type:"circle",x:8+state.fill_value,y:16,radius:2,color:"accent"}
            ]}}};
        """.trimIndent()
        val fragment = JSONObject(definition(source = source))
            .put("surfaces", JSONArray("""[{"id":"dot","width":64,"height":64}]"""))
            .put("presentation", JSONObject("""{"mode":"immersive"}""")).toString()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(scene)
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.COMPLETE -> fragment
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider) { _, engine, generation ->
            val draft = engine.createDraft("Build a moving dot.", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val saved = engine.accept(draft.id)
            val spec = ToolSpec.parsePersisted(saved.spec)
            assertEquals("immersive", spec.presentation)
            assertEquals(setOf("dot"), spec.surfaces.keys)
            val preview = generation.evaluate(spec, saved.id, saved.revision, saved.state, JSONObject().put("type", "preview"), saved.ui)
            val frame = generation.evaluate(spec, saved.id, saved.revision, preview.state, GraphicsContract.event("frame", 16, surfaceId = "dot"), preview.ui)
            assertEquals(setOf("dot"), frame.frames)
            assertEquals(1, JSONObject(frame.state).getInt("fill_value"))
            assertNotEquals(preview.surfaces.toString(), frame.surfaces.toString())
            val reset = generation.evaluate(spec, saved.id, saved.revision, frame.state, GraphicsContract.event("restart", surfaceId = "dot"), frame.ui)
            assertEquals(preview.state, reset.state)
            assertEquals(preview.surfaces.toString(), reset.surfaces.toString())
        }
    }

    @Test fun mixedSceneSurvivesSaveReopenAndFillOnlyRefinement(): Unit = runBlocking {
        var rejectRefinement = false
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan("$count,$scene")
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.COMPLETE -> {
                    assertTrue(turn.turns.first().text.contains("Move a dot on each frame"))
                    assertTrue(turn.turns.first().text.contains("""["scene"]"""))
                    assertFalse(turn.turns.first().text.contains("\"nativePresentation\""))
                    mixed(sceneFill())
                }
                CloudGenerationStage.REFINE -> {
                    val prompt = turn.turns.first().text
                    assertFalse(prompt.contains("p5_count"))
                    assertFalse(prompt.contains("\"partInstanceId\":"))
                    assertFalse(prompt.contains("\"nativePresentation\""))
                    assertFalse(prompt.contains(""""fill_value":1"""))
                    val fill = sceneFill()
                    fill.getJSONObject("ui").getJSONArray("children").getJSONObject(0).put("text", "Move the dot")
                    if (rejectRefinement) fill.put("composition", JSONObject())
                    JSONObject().put("fill", fill).toString()
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider) { store, engine, generation ->
            val draft = engine.createDraft("Build a counter and a separate moving dot.", null)
            val candidate = engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val sealed = checkNotNull(candidate.candidate)
            val spec = ToolSpec.parsePersisted(sealed)
            assertNotNull(spec.composition)
            val recipe = checkNotNull(spec.nativePresentation)
            assertEquals(counterAction, recipe.primaryActionId)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR,
                CloudGenerationStage.COMPLETE), provider.stages)
            assertNull(ToolSpec.available(sealed))
            val saved = engine.accept(draft.id)
            ToolStore(context, store.databaseName).use { reopened ->
                val reopenedEngine = ToolEngine(reopened, generation)
                try {
                    val artifact = checkNotNull(reopened.tool(saved.id))
                    val open = reopenedEngine.openArtifact(artifact)
                    assertEquals(setOf(surface), open.frames)
                    val counter = reopenedEngine.interact(saved.id, saved.revision, open.tool.stateVersion, counterAction, null)
                    val frame = reopenedEngine.artifactEvent(saved.id, saved.revision,
                        GraphicsContract.event("frame", 16, surfaceId = surface), open.sessionGeneration)
                    assertNotEquals(open.surfaces.toString(), frame.surfaces.toString())
                    assertEquals(1, JSONObject(counter.state).getInt(counterState))
                    assertEquals(0, JSONObject(counter.state).getInt("fill_value"))
                    assertEquals(1, JSONObject(frame.tool.state).getInt(counterState))
                    assertEquals(1, JSONObject(frame.tool.state).getInt("fill_value"))
                    reopenedEngine.closeArtifact(saved.id, saved.revision, open.sessionGeneration)
                } finally {
                    reopenedEngine.closeArtifacts()
                }
            }
            val live = checkNotNull(store.tool(saved.id))
            assertEquals(1, JSONObject(live.state).getInt("fill_value"))
            val refine = engine.createDraft("Rename the dot control.", live.id)
            provider.requests.clear()
            val refined = engine.generate(refine.id, "fixture", emptyList(), CloudSession())
            assertEquals(listOf(CloudGenerationStage.REFINE), provider.stages)
            assertEquals(live.state, refined.previewState)
            rejectRefinement = true
            provider.requests.clear()
            failure("widget_incomplete") { engine.generate(refine.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(listOf(CloudGenerationStage.REFINE, CloudGenerationStage.REFINE), provider.stages)
            assertEquals(live, store.tool(live.id))
            assertEquals(refined, store.draft(refine.id))
            val next = engine.accept(refine.id)
            assertEquals(2, next.revision)
            assertEquals(live.state, next.state)
            val beforeRoot = ToolSpec.parsePersisted(live.spec).ui.getJSONArray("children").getJSONObject(0)
            val afterSpec = ToolSpec.parsePersisted(next.spec)
            assertTrue(recipe.sameSelection(afterSpec.nativePresentation))
            assertEquals(beforeRoot.toString(), afterSpec.ui.getJSONArray("children").getJSONObject(0).toString())
            assertEquals("Move the dot", afterSpec.action(afterSpec.ui, "fill_copy")!!.getString("text"))
        }
    }

    @Test fun mixedMissingCoverageForgedOwnershipAndCancellationKeepLastCandidate(): Unit = runBlocking {
        var output = mixed(sceneFill())
        var cancel = false
        val provider = FixtureProvider { turn, session ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan("$count,$scene")
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.COMPLETE -> {
                    if (cancel) { session.cancel(); session.check() }
                    output
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft("Build a counter and a separate moving dot.", null)
            val good = engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val forgedNode = sceneFill().apply {
                getJSONObject("ui").getJSONArray("children").getJSONObject(0).put("partInstanceId", "p5_count")
            }
            val stolenAction = sceneFill().apply {
                getJSONObject("ui").getJSONArray("children").getJSONObject(0).put("action", counterAction)
            }
            val forgedState = sceneFill("""
                if (input.event.type === "action") input.state.$counterState = 91;
                return {state:input.state,surfaces:{"$surface":{commands:[
                  {type:"circle",x:8,y:16,radius:2,color:"accent"}
                ]}}};
            """.trimIndent())
            for (invalid in listOf(
                mixed(sceneFill(), "other"),
                JSONObject(mixed(sceneFill())).put("holes", JSONArray()).toString(),
                mixed(sceneFill().put("composition", JSONObject())),
                mixed(forgedNode),
                mixed(stolenAction),
                mixed(forgedState),
                mixed(sceneFill("return {state:input.state};"))
            )) {
                output = invalid
                provider.requests.clear()
                failure("widget_incomplete") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                assertEquals(2, provider.stages.count { it == CloudGenerationStage.COMPLETE })
                assertEquals(good, store.draft(draft.id))
            }
            output = """{"incomplete":"The scene must read the selected counter."}"""
            provider.requests.clear()
            failure("widget_incomplete") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(1, provider.stages.count { it == CloudGenerationStage.COMPLETE })
            assertEquals(good, store.draft(draft.id))
            cancel = true
            provider.requests.clear()
            failure("cancelled") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(1, provider.stages.count { it == CloudGenerationStage.COMPLETE })
            assertEquals(good, store.draft(draft.id))
            assertNull(store.tool(draft.id))
            val saved = engine.accept(draft.id)
            assertEquals(0, JSONObject(saved.state).getInt(counterState))
        }
    }

    @Test fun explicitWholeScreenPlanUsesTheOriginalRequestAndSkipsSelectors(): Unit = runBlocking {
        val marker = """{"wholeScreen":true}"""
        for (first in listOf(marker, """{"wholeScreen":"true"}""")) {
            var plans = 0
            val provider = FixtureProvider { turn, _ ->
                when (turn.generationStage) {
                    CloudGenerationStage.PLAN -> if (plans++ == 0) first else marker
                    CloudGenerationStage.COMPLETE -> {
                        val message = turn.turns.single()
                        assertEquals("Request: $request", message.text)
                        assertTrue(turn.systemInstructions.contains("Return exactly one strict JSON object"))
                        assertTrue(turn.systemInstructions.contains("Implement the original request as one complete screen."))
                        assertFalse(message.text.contains("Selected need IDs:"))
                        assertFalse(message.text.contains(marker))
                        assertEquals(listOf("fixture:image"), message.images)
                        definition()
                    }
                    else -> error("Unexpected request stage.")
                }
            }
            withEngine(provider) { _, engine, _ ->
                val draft = engine.createDraft(request, null)
                assertNotNull(engine.generate(draft.id, "fixture", listOf("fixture:image"), CloudSession()).candidate)
                assertEquals(List(if (first == marker) 1 else 2) { CloudGenerationStage.PLAN } + CloudGenerationStage.COMPLETE,
                    provider.stages)
                assertNull(ToolSpec.parsePersisted(engine.accept(draft.id).spec).composition)
            }
        }
    }

    @Test fun connectedControlsAndResultsGenerateOneWorkingScreen(): Unit = runBlocking {
        val detail = "Calculate the tip, total, and share from the three editable values. " +
            "Keep the input values editable and recalculate the amounts after each change. ".repeat(9).trimEnd()
        check(detail.length in 501..2_000)
        val dependency = """
            {"title":"Tip calculator","description":"","language":"en",
             "inputs":[{"id":"bill","label":"Bill","type":"number","value":0},
             {"id":"percent","label":"Tip percent","type":"number","value":0},
             {"id":"people","label":"People","type":"number","value":1}],"constants":[],
             "needs":[{"id":"result","type":"other","label":"Totals","description":${JSONObject.quote(detail)}}],"questions":[]}
        """.trimIndent()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> dependency
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.COMPLETE -> {
                    assertTrue(turn.turns.single().text.contains("The hole covers the whole screen."))
                    assertFalse(turn.turns.single().text.contains("Selected need IDs:"))
                    """{"formatVersion":3,"title":"Tip calculator","description":"","capabilities":[],
                        "stateSchema":{"bill":"number","percent":"number","people":"number","tip":"number","total":"number","share":"number"},
                        "initialState":{"bill":0,"percent":0,"people":1,"tip":0,"total":0,"share":0},
                        "ui":{"type":"column","children":[
                          {"type":"numberInput","text":"Bill","bind":"bill","action":"bill","min":0,"max":1000},
                          {"type":"slider","text":"Tip percent","bind":"percent","action":"percent","min":0,"max":100},
                          {"type":"numberInput","text":"People","bind":"people","action":"people","min":1,"max":100},
                          {"type":"text","text":"Tip","bind":"tip"},{"type":"text","text":"Total","bind":"total"},
                          {"type":"text","text":"Each person pays","bind":"share"}]},
                        "source":"const s=input.state,e=input.event;if(e.type==='action'&&typeof e.value==='number'){s[e.actionId]=e.value;s.tip=s.bill*s.percent/100;s.total=s.bill+s.tip;s.share=s.total/Math.max(1,s.people);}return {state:s};"}"""
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft("Build a tip calculator with a bill, tip slider, people and totals.", null)
            // A prior chat correction must leave a valid detailed plan usable.
            val session = CloudSession().apply { assertTrue(claimCorrection()) }
            engine.generate(draft.id, "fixture", emptyList(), session)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR,
                CloudGenerationStage.COMPLETE), provider.stages)
            var saved = engine.accept(draft.id)
            assertNull(ToolSpec.parsePersisted(saved.spec).composition)
            for ((action,value) in listOf("bill" to 80,"percent" to 15,"people" to 4)) {
                saved = engine.interact(saved.id,saved.revision,saved.stateVersion,action,value)
            }
            val reopened = JSONObject(checkNotNull(store.tool(saved.id)).state)
            assertEquals(12.0,reopened.getDouble("tip"),0.0)
            assertEquals(92.0,reopened.getDouble("total"),0.0)
            assertEquals(23.0,reopened.getDouble("share"),0.0)
        }
    }

    @Test fun correctionsShareOneBudgetAndInterruptionNeverUsesIt(): Unit = runBlocking {
        val events = mutableListOf<JSONObject>()
        var code = "response_length"
        var failPlanning = false
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> {
                    if (failPlanning && turn.turns.size == 1) throw CloudFailure("response_length", "Token limit")
                    plan(custom)
                }
                CloudGenerationStage.COMPLETE -> {
                    if (turn.turns.size == 1) throw CloudFailure(code, "Fixture")
                    definition()
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider, { events += it }) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            val good = engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val codeTurns = provider.requests.filter { it.generationStage == CloudGenerationStage.COMPLETE }
            assertEquals(2, codeTurns.size)
            assertEquals(codeTurns.first().turns.single(), codeTurns.last().turns.first())
            assertTrue(codeTurns.last().turns.last().text.contains("limit"))
            assertEquals(listOf(
                "1 plan - completed none continue",
                "1 provider - providerFailure response_length correct",
                "2 smoke - validated none finish"
            ), reports(events))
            provider.requests.clear()
            events.clear()
            code = "incomplete"
            failure("incomplete") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(1, provider.stages.count { it == CloudGenerationStage.COMPLETE })
            assertEquals(good, store.draft(draft.id))
            assertEquals(listOf(
                "1 plan - completed none continue",
                "1 provider - providerFailure incomplete finish"
            ), reports(events))
            provider.requests.clear()
            events.clear()
            failPlanning = true
            code = "response_length"
            failure("response_length") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE), provider.stages)
            assertEquals(good, store.draft(draft.id))
            assertEquals(listOf(
                "1 plan - providerFailure response_length correct",
                "2 plan - completed none continue",
                "2 provider - providerFailure response_length finish"
            ), reports(events))
            provider.requests.clear()
            events.clear()
            val correctedDecision = CloudSession()
            assertTrue(correctedDecision.claimCorrection())
            failure("response_length") { engine.generate(draft.id, "fixture", emptyList(), correctedDecision) }
            assertEquals(listOf(CloudGenerationStage.PLAN), provider.stages)
            assertEquals(listOf("2 plan - providerFailure response_length finish"), reports(events))
            assertEquals(good, store.draft(draft.id))
        }
    }

    @Test fun invalidPlanReportsBothAttempts(): Unit = runBlocking {
        val events = mutableListOf<JSONObject>()
        val provider = FixtureProvider { turn, _ ->
            assertEquals(CloudGenerationStage.PLAN, turn.generationStage)
            """{"privateText":"Never put this in diagnostics"}"""
        }
        withEngine(provider, { events += it }) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            failure("widget_plan") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(listOf(
                "1 plan - rejected widget_plan correct",
                "2 plan - rejected widget_plan finish"
            ), reports(events))
            assertFalse(events.toString().contains("Never put this"))
            assertEquals(draft, store.draft(draft.id))
        }
    }

    @Test fun cancellationDuringSelectionStopsWithoutFallback(): Unit = runBlocking {
        val events = mutableListOf<JSONObject>()
        val provider = FixtureProvider { turn, session ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(count)
                CloudGenerationStage.SELECTOR -> {
                    session.cancel()
                    session.check()
                    error("Canceled session returned.")
                }
                else -> error("Cancellation reached completion.")
            }
        }
        withEngine(provider, { events += it }) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            val error = runCatching { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }.exceptionOrNull()
            assertTrue(error is CancellationException || (error as? CloudFailure)?.code == "cancelled")
            assertEquals(draft, store.draft(draft.id))
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR), provider.stages)
            assertEquals(listOf(
                "1 plan - completed none continue",
                "1 selector sections canceled cancelled finish"
            ), reports(events))
        }
    }

    @Test fun cancellationAfterSelectionIsObservedBeforeSmoke(): Unit = runBlocking {
        val session = CloudSession()
        val events = mutableListOf<JSONObject>()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(count)
                CloudGenerationStage.SELECTOR -> answer(turn)
                else -> error("Cancellation reached completion.")
            }
        }
        withEngine(provider, {
            events += it
            if (it.optString("selectorBatch") == "parts") session.cancel()
        }) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            failure("cancelled") { engine.generate(draft.id, "fixture", emptyList(), session) }
            assertEquals(listOf(
                "1 plan - completed none continue",
                "1 selector sections completed none continue",
                "1 selector parts completed none continue",
                "1 selector - canceled cancelled finish"
            ), reports(events))
            assertEquals(draft, store.draft(draft.id))
        }
    }

    @Test fun validationReportDoesNotClaimCanceledDraftWasPublished(): Unit = runBlocking {
        val session = CloudSession()
        val events = mutableListOf<JSONObject>()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(custom)
                CloudGenerationStage.COMPLETE -> definition()
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider, {
            events += it
            if (it.getString("stage") == "smoke") session.cancel()
        }) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            failure("cancelled") { engine.generate(draft.id, "fixture", emptyList(), session) }
            assertEquals(listOf(
                "1 plan - completed none continue",
                "1 smoke - validated none finish"
            ), reports(events))
            assertEquals(draft, store.draft(draft.id))
            assertNull(store.tool(draft.id))
        }
    }

    @Test fun refinementBypassesPlanningAndKeepsLiveStateOnRepeatedFailure(): Unit = runBlocking {
        val definition = definition()
        var invalid = false
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(custom)
                CloudGenerationStage.COMPLETE -> definition
                CloudGenerationStage.REFINE -> {
                    assertFalse(turn.turns.first().text.contains("5418307"))
                    if (invalid) "bad JSON" else definition
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val first = engine.createDraft(request, null)
            engine.generate(first.id, "fixture", emptyList(), CloudSession())
            val saved = engine.accept(first.id)
            val live = saved.copy(state = """{"fill_value":5418307}""", stateVersion = saved.stateVersion + 1)
            store.saveTool(live, revision = false)
            val draft = engine.createDraft("Change the button label", live.id)
            provider.requests.clear()
            val good = engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertEquals(listOf(CloudGenerationStage.REFINE), provider.stages)
            assertEquals(live.state, good.previewState)
            invalid = true
            provider.requests.clear()
            failure("widget_spec") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(listOf(CloudGenerationStage.REFINE, CloudGenerationStage.REFINE), provider.stages)
            assertEquals(good, store.draft(draft.id))
            assertEquals(live, store.tool(live.id))
        }
    }
}
