package com.alpha.assistant.tools.generation

import com.alpha.assistant.tools.withFixtureCheckEvidence
import com.alpha.assistant.tools.patchSavedFixture
import com.alpha.assistant.tools.fixtureScene
import com.alpha.assistant.tools.nativeFixtureBehavior
import com.alpha.assistant.tools.withFixtureDrawingBinding
import com.alpha.assistant.tools.withFixtureLanguage
import com.alpha.assistant.tools.ToolRecord
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.MainActivity
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.GraphicsContract
import com.alpha.assistant.tools.NativeToolEditRequest
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
    private val scene = """{"id":"scene","type":"scene","writes":[],"label":"Scene","description":"Move a dot on each frame"}"""
    private val surface = "fill_dot"
    private val counterState = "p5_count_value"
    private val counterAction = "p5_count_add"

    private fun plan(needs: String, questions: String = "[]"): String {
        val raw = """{"title":"Route fixture","description":"Local provider fixtures","language":"en","inputs":[],"constants":[],"needs":[$needs],"questions":$questions}"""
        if (needs != count || questions != "[]") return raw
        val behavior = nativeFixtureBehavior(
            """"add":{"kind":"action","part":"count","member":"add"},"reset":{"kind":"action","part":"count","member":"reset"},
                "value":{"kind":"value","part":"count","member":"value"}""",
            """{"op":"action","slot":"add"},{"op":"assert","slot":"value","test":"equals","value":1},
                {"op":"action","slot":"reset"},{"op":"assert","slot":"value","test":"equals","value":0}""")
        return JSONObject(raw).put("behavior", behavior).toString()
    }

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
        JSONObject().put("holes", JSONArray().put(hole)).put("fill", fill.apply {
            if (has("surfaces")) withFixtureDrawingBinding(surface, moving = true)
        }).toString()

    private class FixtureProvider(
        var fixture: suspend (CloudRequest, CloudSession) -> String
    ) : ChatProvider {
        val requests = mutableListOf<CloudRequest>()
        val stages get() = requests.map { it.generationStage }
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            assertTrue(request.jsonObject)
            assertFalse(request.search)
            requests += request
            val output = fixture(request, session)
            emit(CloudEvent.Text(if (output.startsWith("{") && output.endsWith("}"))
                request.withFixtureCheckEvidence(request.withFixtureLanguage(output)) else output))
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

    @Test fun partsWithoutAWorkflowCannotGenerateImagesOrSave(): Unit = runBlocking {
        val prompt = "Add one to a counter and generate a square illustration."
        val database = "parts-missing-workflow-${UUID.randomUUID()}.db"
        val settings = com.alpha.assistant.images.ImageSettings(context, database)
        var imagePlans = 0
        var imageCalls = 0
        val images = com.alpha.assistant.images.ImageGeneration(context, settings, mapOf("openrouter" to
            com.alpha.assistant.images.ImageProvider { _, _, _ ->
                imageCalls++
                error("An unchecked core reached the image provider.")
            }))
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                val response = when {
                    request.systemInstructions.startsWith("Plan only native mini-app illustrations") -> {
                        imagePlans++
                        """{"disposition":"generate","excerpt":"generate a square illustration","removeImageSlots":[],
                            "imageRequests":[{"slot":"art","prompt":"A bright square","alt":"A square","aspect":"1:1"}]}"""
                    }
                    request.generationStage == CloudGenerationStage.PLAN && request.turns.size > 1 -> "{}"
                    request.generationStage == CloudGenerationStage.PLAN -> JSONObject(plan(count)).apply {
                        remove("behavior")
                        put("assetRequest", "generate a square illustration")
                    }.toString()
                    request.generationStage == CloudGenerationStage.SELECTOR -> answer(request)
                    else -> error("Unexpected provider call.")
                }
                emit(CloudEvent.Text(response))
            }
        }
        try {
            settings.saveKey("fixture-only").getOrThrow()
            settings.setEnabled(true).getOrThrow()
            ToolStore(context, database).use { store ->
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context), imageGeneration = images))
                try {
                    val draft = engine.createDraft(prompt, null)
                    failure("widget_plan") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                    assertEquals(0, imagePlans)
                    assertEquals(0, imageCalls)
                    assertNull(store.draft(draft.id)?.candidate)
                    assertTrue(runCatching { engine.accept(draft.id) }.isFailure)
                    assertNull(store.tool(draft.id))
                } finally { engine.closeArtifacts() }
            }
        } finally {
            settings.clearKey()
            context.deleteDatabase(database)
        }
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
                ToolGenerationStage.STATE, ToolGenerationStage.SMOKE, ToolGenerationStage.BEHAVIOR), starts.map { it.stage })
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

    @Test fun invalidVisualChoiceReportsTheSelectorFailureAndKeepsTheSelectedPart(): Unit = runBlocking {
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
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR),
                provider.stages)
            val spec = ToolSpec.parsePersisted(checkNotNull(candidate.candidate))
            assertNull(spec.nativePresentation)
            assertNotNull(spec.action(spec.ui, counterAction))
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
                    turn.patchSavedFixture(checkNotNull(refinement))
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
                assertEquals(List(4) { CloudGenerationStage.REFINE }, provider.stages)
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

    @Test fun incompleteMixedFillUsesTheExistingPlanAndCompleteBudgets(): Unit = runBlocking {
        val independent = JSONObject(custom).put("writes", JSONArray()).put("reads", JSONArray())
        for (afterPatch in listOf(false, true)) {
            var completes = 0
            val provider = FixtureProvider { turn, _ ->
                when (turn.generationStage) {
                    CloudGenerationStage.PLAN -> if (turn.turns.size == 1) plan("$count,$independent") else {
                        assertTrue(turn.turns.last().text.contains("wholeScreen"))
                        """{"fields":{"wholeScreen":true},"removeFields":["title","description","inputs","constants","needs","questions"]}"""
                    }
                    CloudGenerationStage.SELECTOR -> answer(turn)
                    CloudGenerationStage.COMPLETE -> when (completes++) {
                        0 -> if (afterPatch) """{"holes":["custom"],"fill":{"source":"return {state:input.state};"}}"""
                            else """{"incomplete":"The selected native part cannot share the reset action."}"""
                        1 -> if (afterPatch) """{"incomplete":"The selected native part cannot share the reset action."}"""
                            else definition()
                        else -> definition()
                    }
                    else -> error("Unexpected generation stage")
                }
            }
            withEngine(provider) { _, engine, _ ->
                val session = CloudSession()
                val draft = engine.createDraft(request, null)
                engine.generate(draft.id, "fixture", emptyList(), session)
                assertNull(ToolSpec.parsePersisted(engine.accept(draft.id).spec).composition)
                assertEquals(provider.stages.toString(), 2, provider.stages.count { it == CloudGenerationStage.PLAN })
                assertEquals(if (afterPatch) 3 else 2, provider.stages.count { it == CloudGenerationStage.COMPLETE })
                assertEquals(2, session.claimGenerationRepair(CloudGenerationStage.PLAN))
                assertEquals(if (afterPatch) 3 else 2, session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
            }
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
        val scene = """{"id":"scene","type":"scene","writes":[],"label":"Scene","description":"Move a dot on each frame and reset it on restart"}"""
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
            .put("presentation", JSONObject("""{"mode":"immersive"}""")).withFixtureDrawingBinding("dot", moving = true).toString()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> JSONObject(plan(scene)).put("scene", fixtureScene("immersive", moving = true)).toString()
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
        var translate = false
        var attemptedOwnershipEdit = false
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> if (turn.systemInstructions.startsWith("Plan required Android capabilities"))
                    """{"requiredCapabilities":[]}"""
                else JSONObject(plan("$count,$scene")).put("scene", fixtureScene(moving = true)).toString()
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.COMPLETE -> {
                    assertTrue(turn.turns.first().text.contains("Move a dot on each frame"))
                    assertTrue(turn.turns.first().text.contains("""["scene"]"""))
                    assertFalse(turn.turns.first().text.contains("\"nativePresentation\""))
                    val increment = "input.state.fill_value += 1;"
                    val stationary = "throw Error('frame fixture');"
                    if (turn.turns.size == 1) {
                        val fill = sceneFill()
                        fill.put("source", fill.getString("source").replace(increment, stationary))
                        mixed(fill)
                    } else {
                        assertTrue(turn.turns.last().text.contains("Preserve native ownership and hole order"))
                        if (!attemptedOwnershipEdit) {
                            attemptedOwnershipEdit = true
                            """{"fields":{"holes":[]}}"""
                        } else {
                            assertTrue(turn.turns.last().text.contains("could not apply (shape)"))
                            assertEquals("""["scene"]""", JSONObject(turn.turns[1].text).getJSONArray("holes").toString())
                            JSONObject().put("sourceEdits", JSONArray().put(JSONObject()
                                    .put("search", stationary).put("replace", increment))).toString()
                        }
                    }
                }
                CloudGenerationStage.REFINE -> {
                    val prompt = turn.turns.first().text
                    assertTrue(prompt.contains("Fill child positions in the final UI root: [1]"))
                    assertFalse(prompt.contains("p5_count"))
                    assertFalse(prompt.contains("\"partInstanceId\":"))
                    assertFalse(prompt.contains("\"nativePresentation\""))
                    assertFalse(prompt.contains(""""fill_value":1"""))
                    val fill = sceneFill()
                    fill.getJSONObject("ui").getJSONArray("children").getJSONObject(0).put("text", "Move the dot")
                    if (rejectRefinement) fill.put("composition", JSONObject())
                    turn.patchSavedFixture(JSONObject().put("fill", fill).toString(),
                        language = "it".takeIf { translate },
                        languageChange = "Translate the interface to Italian".takeIf { translate })
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
                CloudGenerationStage.COMPLETE, CloudGenerationStage.COMPLETE, CloudGenerationStage.COMPLETE), provider.stages)
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
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.REFINE), provider.stages)
            assertEquals(live.state, refined.previewState)
            rejectRefinement = true
            provider.requests.clear()
            failure("widget_incomplete") { engine.generate(refine.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(listOf(CloudGenerationStage.PLAN) + List(4) { CloudGenerationStage.REFINE }, provider.stages)
            assertEquals(live, store.tool(live.id))
            assertEquals(refined, store.draft(refine.id))
            val next = engine.accept(refine.id)
            assertEquals(2, next.revision)
            assertEquals(live.state, next.state)
            val beforeRoot = ToolSpec.parsePersisted(live.spec).ui.getJSONArray("children").getJSONObject(0)
            val afterSpec = ToolSpec.parsePersisted(next.spec)
            assertTrue(recipe.sameSelection(afterSpec.nativePresentation))
            assertEquals(beforeRoot.toString(), afterSpec.ui.getJSONArray("children").getJSONObject(0).toString())

            rejectRefinement = false
            translate = true
            provider.requests.clear()
            val translation = engine.createDraft("Translate the interface to Italian", next.id)
            failure("widget_language_native") { engine.generate(translation.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.REFINE), provider.stages)
            assertEquals(next, store.tool(next.id))
            assertNull(store.draft(translation.id)?.candidate)
            assertEquals("Move the dot", afterSpec.action(afterSpec.ui, "fill_copy")!!.getString("text"))
        }
    }

    @Test fun mixedWriterExampleKeepsTheHostFillPositionsWithoutTestBindings(): Unit = runBlocking {
        val first = """{"id":"first","type":"other","label":"Total","description":"Show a total","writes":[],"reads":[]}"""
        val last = """{"id":"last","type":"other","label":"Rows","description":"Show a list","writes":[],"reads":[]}"""
        val counter = count.replace("\"count\"", "\"second\"")
        val fill = """{"holes":["first","last"],"fill":{"capabilities":[],
            "stateSchema":{"fill_value":"number","fill_rows":{"type":"list","maxItems":4,"fields":{"id":"string","text":"string"}}},
            "initialState":{"fill_value":7,"fill_rows":[]},
            "ui":{"type":"column","children":[{"type":"metric","text":"Total","bind":"fill_value"},
            {"type":"list","text":"Rows","bind":"fill_rows"}]},"source":"return {state:input.state};"}}"""
        val provider = FixtureProvider { turn, _ -> when (turn.generationStage) {
            CloudGenerationStage.PLAN -> plan("$count,$first,$counter,$last")
            CloudGenerationStage.SELECTOR -> answer(turn)
            CloudGenerationStage.COMPLETE -> fill
            else -> error("Unexpected request stage.")
        } }
        withEngine(provider) { _, engine, _ ->
            val draft = engine.createDraft(request, null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val writer = provider.requests.last().turns.single().text
            assertTrue(writer.contains("Fill child positions in the final UI root: [1,3]"))
            val example = JSONObject(writer.substringAfter("Schema:\n").lineSequence().first())
            val children = example.getJSONObject("fill").getJSONObject("ui").getJSONArray("children")
            assertEquals(2, children.length())
            assertFalse(example.has("behaviorBindings"))
        }
    }

    @Test fun mixedMissingCoverageForgedOwnershipAndCancellationKeepLastCandidate(): Unit = runBlocking {
        var output = mixed(sceneFill())
        var cancel = false
        var providerRepairNumber = 0
        val provider = FixtureProvider { turn, session ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> JSONObject(plan("$count,$scene")).put("scene", fixtureScene(moving = true)).toString()
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.COMPLETE -> {
                    if (cancel) { session.cancel(); session.check() }
                    if (turn.turns.last().text.contains("Return one JSON patch object only")) {
                        val revision = providerRepairNumber++
                        """{"fields":{"description":"Rejected repair $revision"}}"""
                    } else output
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
                JSONObject(mixed(sceneFill())).put("behaviorBindings", JSONObject()).toString(),
                mixed(sceneFill().put("composition", JSONObject())),
                mixed(forgedNode),
                mixed(stolenAction),
                mixed(forgedState),
                mixed(sceneFill("return {state:input.state};"))
            )) {
                output = invalid
                provider.requests.clear()
                failure("widget_incomplete") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                val envelope = JSONObject(invalid)
                val expected = if (envelope.getJSONArray("holes").toString() == """["scene"]""" &&
                    !envelope.has("behaviorBindings")) 4 else 2
                assertEquals(expected, provider.stages.count { it == CloudGenerationStage.COMPLETE })
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
        val marker = """{"wholeScreen":true,"language":"en"}"""
        for (first in listOf(marker, """{"wholeScreen":"true"}""")) {
            var plans = 0
            val provider = FixtureProvider { turn, _ ->
                when (turn.generationStage) {
                    CloudGenerationStage.PLAN -> if (plans++ == 0) first else """{"fields":{"wholeScreen":true,"language":"en"}}"""
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
            // A spent generation correction still permits the writer to fill a valid plan.
            val session = CloudSession().apply { assertTrue(claimGenerationCorrection()) }
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

    @Test fun incompatibleNativeBindingReturnsToPlanningBeforeTheWriter(): Unit = runBlocking {
        val valid = plan(count)
        val invalidPlans = listOf(
            valid.replace("\"member\":\"add\"", "\"member\":\"unknown\""),
            valid.replace("\"kind\":\"value\",\"part\":\"count\",\"member\":\"value\"", "\"kind\":\"value\""),
        )
        for (invalid in invalidPlans) {
            check(invalid != valid)
            var plans = 0
            val provider = FixtureProvider { turn, _ ->
                when (turn.generationStage) {
                    CloudGenerationStage.PLAN -> if (plans++ == 0) invalid else JSONObject().put("fields",
                        JSONObject().put("behavior", JSONObject(valid).getJSONObject("behavior"))).toString()
                    CloudGenerationStage.SELECTOR -> answer(turn)
                    else -> error("Only planning can correct a native binding.")
                }
            }
            withEngine(provider) { _, engine, _ ->
                val draft = engine.createDraft("Build a count with Add and Reset.", null)
                engine.generate(draft.id, "fixture", emptyList(), CloudSession())
                assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR,
                    CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR), provider.stages)
                val saved = engine.accept(draft.id)
                val after = engine.interact(saved.id, saved.revision, saved.stateVersion, counterAction, null)
                assertEquals(1, JSONObject(after.state).getInt(counterState))
            }
        }
    }

    @Test fun outOfRangeNativeInputReturnsToPlanning(): Unit = runBlocking {
        fun behavior(value: Int) = nativeFixtureBehavior(
            """"edit":{"kind":"action","part":"people","member":"edit"},
                "value":{"kind":"value","part":"people","member":"value"}""",
            """{"op":"action","slot":"edit","value":$value},{"op":"assert","slot":"value","test":"equals","value":4}"""
        )
        val raw = JSONObject(plan("")).put("inputs", JSONArray("""
            [{"id":"people","label":"People","type":"number","value":1,"min":1,"max":12}]
        """.trimIndent())).put("behavior", behavior(0)).toString()
        var plans = 0
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> if (plans++ == 0) raw else JSONObject().put("fields",
                    JSONObject().put("behavior", behavior(4))).toString()
                CloudGenerationStage.SELECTOR -> answer(turn)
                else -> error("A native input must not call the writer.")
            }
        }
        withEngine(provider) { _, engine, _ ->
            val draft = engine.createDraft("Let me enter the number of people, from one to twelve.", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertEquals(2, plans)
            assertTrue(engine.accept(draft.id).saved)
        }
    }

    @Test fun onePlanRepairNamesEveryUnselectedSlotOwner(): Unit = runBlocking {
        val custom = """{"id":"note","type":"other","label":"Note","description":"Independent custom output","writes":[],"reads":[]}"""
        val rejected = JSONObject(plan("$count,$custom")).put("behavior", nativeFixtureBehavior(
            """"write":{"kind":"action","part":"note","member":"edit"},
                "summary":{"kind":"value","part":"note","member":"value"}""",
            """{"op":"action","slot":"write","value":"Example"},
                {"op":"assert","slot":"summary","test":"equals","value":"Example"}"""
        ))
        val corrected = JSONObject(plan(count))
        var plans = 0
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> if (++plans == 1) rejected.toString() else {
                    val feedback = turn.turns.last().text
                    assertTrue(feedback, "write -> note" in feedback && "summary -> note" in feedback)
                    JSONObject().put("fields", JSONObject()
                        .put("needs", corrected.getJSONArray("needs"))
                        .put("behavior", corrected.getJSONObject("behavior"))).toString()
                }
                CloudGenerationStage.SELECTOR -> answer(turn)
                else -> error("The corrected native plan must skip the writer.")
            }
        }
        withEngine(provider) { _, engine, _ ->
            val draft = engine.createDraft("Build a count with Add and Reset.", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertEquals(2, plans)
            assertTrue(engine.accept(draft.id).saved)
        }
    }

    @Test fun receiptRepairReselectsChangedNeedsAndExplainsMissingNativeCapability(): Unit = runBlocking {
        val prompt = "Keep a chime count and play a beep each time I press Chime."
        val initial = JSONObject(plan(count)).put("requiredCapabilities", JSONArray()
            .put(JSONObject().put("id", "audio.tone").put("excerpt", "play a beep")))
            .put("behavior", nativeFixtureBehavior(
                """"play":{"kind":"action"},"value":{"kind":"value","valueType":"number"}""",
                """{"op":"action","slot":"play"},{"op":"assert","slot":"value","test":"equals","value":1}"""))
        val unchecked = JSONObject("""{"status":"unchecked","reason":"external_outcome"}""")
        val writer = JSONObject(definition(stateKey = "count", action = "play", source = """
            const s = input.state;
            if (input.event.actionId !== 'play') return {state:s};
            return {state:{count:s.count+1},effects:[{id:'tone',capability:'audio.tone',args:{tone:'beep',durationMillis:120}}]};
        """.trimIndent())).put("capabilities", JSONArray().put("audio.tone"))
            .put("ui", JSONObject("""{"type":"column","children":[{"type":"button","text":"Chime","action":"play"},
                {"type":"metric","text":"Chimes","bind":"count"}]}"""))
        var plans = 0
        var selections = 0
        var writers = 0
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> when (++plans) {
                    1 -> initial.toString()
                    2 -> {
                        assertTrue(turn.turns.last().text.contains("required native receipt"))
                        JSONObject().put("fields", JSONObject().put("behavior", unchecked)
                            .put("needs", JSONArray().put(JSONObject(count).put("label", "Chimes")))).toString()
                    }
                    3 -> {
                        val feedback = turn.turns.last().text
                        assertTrue(feedback, "$.requiredCapabilities" in feedback && "audio.tone" in feedback && "wholeScreen" in feedback)
                        JSONObject().put("fields", JSONObject().put("wholeScreen", true))
                            .put("removeFields", JSONArray(listOf("title", "description", "inputs", "constants", "needs", "questions")))
                            .toString()
                    }
                    else -> error("Unexpected PLAN repair.")
                }
                CloudGenerationStage.SELECTOR -> if (++selections == 1) answer(turn, mapOf("s_count" to "B")) else answer(turn)
                CloudGenerationStage.COMPLETE -> if (++writers == 1) JSONObject(writer.toString())
                    .put("behaviorBindings", JSONObject("""{"play":"play","value":[1]}""")).toString() else writer.toString()
                else -> error("Unexpected generation stage.")
            }
        }
        withEngine(provider) { _, engine, _ ->
            val draft = engine.createDraft(prompt, null)
            val session = CloudSession()
            engine.generate(draft.id, "fixture", emptyList(), session)
            val saved = engine.accept(draft.id)
            assertEquals(setOf("audio.tone"), ToolSpec.parsePersisted(saved.spec).capabilities)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.COMPLETE,
                CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR,
                CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE), provider.stages)
            assertEquals(1, session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
        }
    }

    private val receiptPrompt = "Count each change and play a beep when the switch turns on."

    private fun receiptBehavior(enabled: Boolean, expected: Int) = nativeFixtureBehavior(
        """"change":{"kind":"action"},"count":{"kind":"value","valueType":"number"}""",
        """{"op":"action","slot":"change","value":$enabled},{"op":"assert","slot":"count","test":"equals","value":$expected}""")

    private fun receiptPlan() = JSONObject("""{"wholeScreen":true,"language":"en"}""")
        .put("requiredCapabilities", JSONArray().put(JSONObject().put("id", "audio.tone").put("excerpt", "play a beep")))
        .put("behavior", receiptBehavior(true, 1))

    private fun receiptWriter() = JSONObject(definition(stateKey = "count", source = """
        const e = input.event, s = input.state;
        if (e.type !== 'action') return {state:s};
        const result = {state:{count:s.count + 1, enabled:e.value}};
        if (e.value) result.effects = [{id:'tone',capability:'audio.tone',args:{tone:'beep',durationMillis:120}}];
        return result;
    """.trimIndent())).put("capabilities", JSONArray().put("audio.tone"))
        .put("ui", JSONObject("""{"type":"column","children":[{"type":"toggle","text":"Enable beep","bind":"enabled","action":"change"},
            {"type":"metric","text":"Changes","bind":"count"}]}"""))
        .put("behaviorBindings", JSONObject("""{"change":"change","count":[1]}"""))
        .apply {
            getJSONObject("stateSchema").put("enabled", "boolean")
            getJSONObject("initialState").put("enabled", false)
        }

    @Test fun receiptRepairDiscardsTheHandoffAfterAnIncompatiblePlan(): Unit = runBlocking {
        val writer = receiptWriter()
        val first = JSONObject(plan(count))
        val receipt = receiptPlan()
        first.put("requiredCapabilities", receipt.getJSONArray("requiredCapabilities"))
            .put("behavior", receipt.getJSONObject("behavior"))
        val nativeOwner = receiptBehavior(true, 1).apply {
            getJSONObject("scenario").getJSONObject("slots").getJSONObject("change").put("part", "count").put("member", "add")
        }
        val rebuilt = JSONObject(writer.toString()).put("title", "New writer").apply { remove("behaviorBindings") }
        var plans = 0
        var writers = 0
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> when (++plans) {
                    1 -> first.toString()
                    2 -> JSONObject().put("fields", JSONObject().put("behavior", nativeOwner)).toString()
                    3 -> {
                        assertTrue("These slot owners were not selected" in turn.turns.last().text)
                        """{"fields":{"behavior":{"status":"unchecked","reason":"external_outcome"}}}"""
                    }
                    else -> error("Unexpected PLAN repair.")
                }
                CloudGenerationStage.SELECTOR -> answer(turn, mapOf("s_count" to "B"))
                CloudGenerationStage.COMPLETE -> {
                    assertEquals(1, turn.turns.size)
                    if (++writers == 1) writer.toString() else rebuilt.toString()
                }
                else -> error("Unexpected generation stage.")
            }
        }
        withEngine(provider) { _, engine, _ ->
            val session = CloudSession()
            val draft = engine.createDraft(receiptPrompt, null)
            engine.generate(draft.id, "fixture", emptyList(), session)
            assertEquals("New writer", ToolSpec.parsePersisted(engine.accept(draft.id).spec).title)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.COMPLETE,
                CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.PLAN,
                CloudGenerationStage.SELECTOR, CloudGenerationStage.COMPLETE), provider.stages)
            assertEquals(1, session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
        }
    }

    @Test fun receiptRepairRechecksRetainedWriterAndKeepsItsPatchBase(): Unit = runBlocking {
        for (expected in listOf(1, 2)) {
            val writer = receiptWriter()
            val provider = FixtureProvider { turn, _ ->
                when (turn.generationStage) {
                    CloudGenerationStage.PLAN -> if (turn.turns.size == 1) receiptPlan().toString()
                        else JSONObject().put("fields", JSONObject().put("behavior", receiptBehavior(false, expected))).toString()
                    CloudGenerationStage.COMPLETE -> if (turn.turns.size == 1) writer.toString()
                        else {
                            assertEquals(2, expected)
                            assertTrue(sameToolJson(writer, JSONObject(turn.turns[turn.turns.lastIndex - 1].text)))
                            assertTrue("assertion_failed" in turn.turns.last().text)
                            """{"sourceEdits":[{"search":"s.count + 1","replace":"s.count + 2"}]}"""
                        }
                    else -> error("Unexpected generation stage.")
                }
            }
            val diagnostics = mutableListOf<JSONObject>()
            withEngine(provider, observer = { diagnostics += it }) { _, engine, _ ->
                val session = CloudSession()
                val draft = engine.createDraft(receiptPrompt, null)
                engine.generate(draft.id, "fixture", emptyList(), session)
                val saved = ToolSpec.parsePersisted(engine.accept(draft.id).spec)
                val stages = listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.PLAN)
                assertEquals(if (expected == 2) stages + CloudGenerationStage.COMPLETE else stages, provider.stages)
                assertEquals(expected, session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
                assertEquals(0, JSONObject(saved.initialState).getInt("count"))
                assertEquals(writer.getString("source").replace("s.count + 1", "s.count + $expected"), saved.source)
                assertEquals(expected == 2, diagnostics.any {
                    it.optJSONObject("behaviorBinding")?.optString("rule") == "assertion_failed"
                })
            }
        }
    }

    @Test fun receiptRepairUsesEachHandoffOnceAndStopsAtThePlanBudget(): Unit = runBlocking {
        val writer = receiptWriter()
        var plans = 0
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> when (++plans) {
                    1 -> receiptPlan().toString()
                    in 2..4 -> JSONObject().put("fields", JSONObject().put("behavior", receiptBehavior(true, plans))).toString()
                    else -> error("Unexpected PLAN repair.")
                }
                CloudGenerationStage.COMPLETE -> {
                    assertEquals(1, turn.turns.size)
                    writer.toString()
                }
                else -> error("Unexpected generation stage.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val session = CloudSession()
            val draft = engine.createDraft(receiptPrompt, null)
            val failure = runCatching { engine.generate(draft.id, "fixture", emptyList(), session) }.exceptionOrNull()
            assertEquals("widget_plan", (failure as? CloudFailure)?.code)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.PLAN,
                CloudGenerationStage.PLAN, CloudGenerationStage.PLAN), provider.stages)
            assertEquals(1, session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
            assertNull(store.draft(draft.id)?.candidate)
            assertTrue(store.tools().isEmpty())
        }
    }

    @Test fun receiptRepairStopsOnCancellationOrExhaustedWriterBudget(): Unit = runBlocking {
        for (cancel in listOf(true, false)) {
            val session = CloudSession()
            if (!cancel) repeat(3) { session.claimGenerationRepair(CloudGenerationStage.COMPLETE) }
            val provider = FixtureProvider { turn, _ ->
                when (turn.generationStage) {
                    CloudGenerationStage.PLAN -> if (turn.turns.size == 1) receiptPlan().toString()
                        else JSONObject().put("fields", JSONObject().put("behavior", receiptBehavior(false, 2))).toString()
                    CloudGenerationStage.COMPLETE -> {
                        assertEquals(1, turn.turns.size)
                        receiptWriter().toString()
                    }
                    else -> error("Unexpected generation stage.")
                }
            }
            withEngine(provider) { store, engine, _ ->
                val draft = engine.createDraft(receiptPrompt, null)
                val trace = ToolGenerationTrace(UUID.fromString(draft.id), { event ->
                    if (cancel && event is ToolGenerationTraceEvent.Stage && event.started &&
                        event.attempt == 2 && event.stage == ToolGenerationStage.SMOKE) session.cancel()
                })
                val failure = runCatching {
                    engine.generate(draft.id, "fixture", emptyList(), session, progress = null, previewObserver = null, trace = trace)
                }.exceptionOrNull()
                assertEquals(if (cancel) "cancelled" else "widget_behavior", (failure as? CloudFailure)?.code)
                assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.PLAN), provider.stages)
                assertNull(store.draft(draft.id)?.candidate)
                assertTrue(store.tools().isEmpty())
            }
        }
    }

    @Test fun inlineSceneChecksUseDeclaredNativeControlsAndResults(): Unit = runBlocking {
        val scenePlan = fixtureScene(moving = true)
        val scenario = scenePlan.getJSONObject("checks").getJSONObject("scenario")
        val oldSteps = scenario.getJSONArray("steps")
        scenario.getJSONObject("slots")
            .put("add", JSONObject("""{"kind":"action","part":"count","member":"add"}"""))
            .put("count", JSONObject("""{"kind":"value","part":"count","member":"value"}"""))
        scenario.put("steps", JSONArray("""[
            {"op":"action","slot":"add"},{"op":"assert","slot":"count","test":"equals","value":1}
        ]""").put(oldSteps.getJSONObject(0)))
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> {
                    assertEquals(1, turn.turns.size)
                    JSONObject(plan("$count,$scene")).put("scene", scenePlan).toString()
                }
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.COMPLETE -> mixed(sceneFill())
                else -> error("Unexpected stage.")
            }
        }
        withEngine(provider) { _, engine, _ ->
            val draft = engine.createDraft("Build a counter and a separate moving dot.", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val saved = engine.accept(draft.id)
            assertEquals(0, JSONObject(saved.state).getInt(counterState))
            assertEquals(1, provider.stages.count { it == CloudGenerationStage.COMPLETE })
        }
    }

    @Test fun mixedNativeAssertionRepairsThePlanBeforeRebuildingTheWriter(): Unit = runBlocking {
        val local = """{"id":"note","type":"other","label":"Note","description":"An independent action","writes":[],"reads":[]}"""
        fun behavior(expected: Int) = nativeFixtureBehavior(
            """"add":{"kind":"action","part":"count","member":"add"},
                "value":{"kind":"value","part":"count","member":"value"}""",
            """{"op":"action","slot":"add"},{"op":"assert","slot":"value","test":"equals","value":$expected}"""
        )
        val raw = JSONObject(plan("$count,$local")).put("behavior", behavior(2)).toString()
        for (pauseOwner in listOf(false, true)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var plans = 0
                val provider = FixtureProvider { turn, _ ->
                    when (turn.generationStage) {
                        CloudGenerationStage.PLAN -> if (++plans == 1) raw else {
                            assertTrue(turn.turns.last().text, "$.behavior.scenario.steps[1]" in turn.turns.last().text)
                            JSONObject().put("fields", JSONObject().put("behavior", behavior(1))).toString()
                        }
                        CloudGenerationStage.SELECTOR -> answer(turn)
                        CloudGenerationStage.COMPLETE -> {
                            assertEquals("Writer repairs must not pay for the native mismatch.", 1, turn.turns.size)
                            """{"holes":["note"],"fill":{"capabilities":[],
                                "stateSchema":{"fill_value":"number"},"initialState":{"fill_value":0},
                                "ui":{"type":"column","children":[{"type":"button","text":"Note","action":"fill_note"}]},
                                "source":"return {state:input.state};"}}"""
                        }
                        else -> error("Unexpected generation stage.")
                    }
                }
                val suspendOwner: ((JSONObject) -> Unit)? = if (pauseOwner) { event ->
                    if (event.optString("stage") == "plan" && event.optString("disposition") == "correct") {
                        scenario.moveToState(Lifecycle.State.STARTED)
                    }
                } else null
                withEngine(provider, suspendOwner) { store, engine, _ ->
                    val draft = engine.createDraft("Add one to a count and include a separate Note button.", null)
                    val session = CloudSession()
                    if (pauseOwner) {
                        failure("widget_owner") { engine.generate(draft.id, "fixture", emptyList(), session) }
                        assertEquals(1, plans)
                        assertEquals(1, provider.stages.count { it == CloudGenerationStage.COMPLETE })
                        assertNull(store.tool(draft.id))
                        return@withEngine
                    }
                    engine.generate(draft.id, "fixture", emptyList(), session)
                    assertEquals(2, plans)
                    assertEquals(2, provider.stages.count { it == CloudGenerationStage.COMPLETE })
                    assertEquals(1, session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
                    assertTrue(engine.accept(draft.id).saved)
                }
            }
        }
    }

    @Test fun crossOwnerResetReachesAWriterThatCanResetTheInputs(): Unit = runBlocking {
        val detail = "Calculate the tip, total, and share from the three editable values. " +
            "Keep the input values editable and recalculate the amounts after each change. ".repeat(9).trimEnd()
        check(detail.length in 501..2_000)
        val dependency = JSONObject("""
            {"title":"Tip calculator","description":"","language":"en",
             "inputs":[{"id":"bill","label":"Bill","type":"number","value":0},
             {"id":"percent","label":"Tip percent","type":"number","value":15},
             {"id":"people","label":"People","type":"number","value":1}],"constants":[],
             "needs":[{"id":"reset","type":"action","label":"Reset","description":"Restore the three inputs","writes":["bill","percent","people"]},
               {"id":"result","type":"other","label":"Totals","description":${JSONObject.quote(detail)},"reads":["bill","percent","people"],"writes":[]}],"questions":[]}
        """.trimIndent()).put("behavior", nativeFixtureBehavior(
            """"bill":{"kind":"action","part":"bill","member":"edit"},
                "percent":{"kind":"action","part":"percent","member":"edit"},
                "people":{"kind":"action","part":"people","member":"edit"},
                "reset":{"kind":"action","part":"reset"},"total":{"kind":"value"}""",
            """{"op":"action","slot":"bill","value":80},{"op":"action","slot":"percent","value":20},
                {"op":"action","slot":"people","value":4},{"op":"assert","slot":"total","test":"equals","value":96},
                {"op":"action","slot":"reset"},{"op":"assert","slot":"total","test":"equals","value":0}"""
        )).toString()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> dependency
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.COMPLETE -> {
                    assertTrue(turn.turns.single().text.contains("The hole covers the whole screen."))
                    assertFalse(turn.turns.single().text.contains("Selected need IDs:"))
                    """{"formatVersion":3,"title":"Tip calculator","description":"","capabilities":[],
                        "behaviorBindings":{"bill":"bill","percent":"percent","people":"people","reset":"reset","total":[4]},
                        "stateSchema":{"bill":"number","percent":"number","people":"number","tip":"number","total":"number","share":"number"},
                        "initialState":{"bill":0,"percent":15,"people":1,"tip":0,"total":0,"share":0},
                        "ui":{"type":"column","children":[
                          {"type":"numberInput","text":"Bill","bind":"bill","action":"bill","min":0,"max":1000},
                          {"type":"slider","text":"Tip percent","bind":"percent","action":"percent","min":0,"max":100},
                          {"type":"numberInput","text":"People","bind":"people","action":"people","min":1,"max":100},
                          {"type":"text","text":"Tip","bind":"tip"},{"type":"text","text":"Total","bind":"total"},
                          {"type":"text","text":"Each person pays","bind":"share"},
                          {"type":"button","text":"Reset","action":"reset"}]},
                        "source":"const s=input.state,e=input.event;if(e.type==='action'){if(e.actionId==='reset'){s.bill=0;s.percent=15;s.people=1;}else if(typeof e.value==='number'){s[e.actionId]=e.value;}s.tip=s.bill*s.percent/100;s.total=s.bill+s.tip;s.share=s.total/Math.max(1,s.people);}return {state:s};"}"""
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft("Build a tip calculator with a bill, tip slider, people, totals, and Reset to 0, 15 and 1.", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR,
                CloudGenerationStage.COMPLETE), provider.stages)
            var saved = engine.accept(draft.id)
            assertNull(ToolSpec.parsePersisted(saved.spec).composition)
            for ((action,value) in listOf("bill" to 80,"percent" to 20,"people" to 4)) {
                saved = engine.interact(saved.id,saved.revision,saved.stateVersion,action,value)
            }
            val reopened = JSONObject(checkNotNull(store.tool(saved.id)).state)
            assertEquals(16.0,reopened.getDouble("tip"),0.0)
            assertEquals(96.0,reopened.getDouble("total"),0.0)
            assertEquals(24.0,reopened.getDouble("share"),0.0)
            saved = engine.interact(saved.id, saved.revision, saved.stateVersion, "reset", null)
            val reset = JSONObject(saved.state)
            assertEquals(0.0, reset.getDouble("bill"), 0.0)
            assertEquals(15.0, reset.getDouble("percent"), 0.0)
            assertEquals(1.0, reset.getDouble("people"), 0.0)
            assertEquals(0.0, reset.getDouble("total"), 0.0)
        }
    }

    @Test fun connectedFiguresUseNativePartsAndRecomputeAfterReopen(): Unit = runBlocking {
        val needs = """
          {"id":"twice","type":"figure","label":"Double","description":"Twice the amount",
           "expression":{"op":"multiply","args":[{"ref":"amount"},{"number":2}]}},
          {"id":"share","type":"figure","label":"Share","description":"Divide the double by the divisor",
           "expression":{"op":"divide","args":[{"ref":"twice"},{"ref":"divisor"}]}}
        """.trimIndent()
        val response = JSONObject(plan(needs)).put("inputs", JSONArray("""
            [{"id":"amount","label":"Amount","type":"number","value":4,"max":100},
             {"id":"divisor","label":"Divisor","type":"number","value":2,"min":0,"step":1}]
        """.trimIndent())).put("behavior", nativeFixtureBehavior(
            """"amount":{"kind":"action","part":"amount","member":"edit"},"share":{"kind":"value","part":"share","member":"value"}""",
            """{"op":"action","slot":"amount","value":12},{"op":"assert","slot":"share","test":"equals","value":12}"""
        )).toString()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> response
                CloudGenerationStage.SELECTOR -> {
                    val questions = JSONObject(turn.turns.single().text).getJSONArray("questions")
                    val omitted = (0 until questions.length()).map(questions::getJSONObject)
                        .filter { it.getString("id") in setOf("s_twice", "s_share") }
                        .filterNot { it.getJSONObject("choices").getString("A").contains("computed numeric outputs") }
                        .associate { it.getString("id") to "B" }
                    answer(turn, omitted)
                }
                CloudGenerationStage.REFINE -> {
                    val payload = JSONObject(turn.turns.single().text)
                    val context = payload.getJSONObject("context")
                    val figure = context.getJSONArray("parts").getJSONObject(0)
                    assertEquals(setOf("alias", "type", "label", "expression", "unit"), figure.keys().asSequence().toSet())
                    val expression = if (payload.getString("request") == "Introduce a cycle.") """{"ref":"share"}"""
                        else """{"op":"multiply","args":[{"ref":"amount"},{"number":3}]}"""
                    """{"token":${JSONObject.quote(context.getString("token"))},"operations":[
                        {"op":"set_figure_expression","part":${JSONObject.quote(figure.getString("alias"))},"expression":$expression}]}"""
                }
                else -> error("The native calculation must not call the code writer.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft("Double an amount and divide by the editable divisor.", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            var saved = engine.accept(draft.id)
            assertTrue(ToolSpec.parsePersisted(saved.spec).isSealedStandalone)
            saved = engine.interact(saved.id, saved.revision, saved.stateVersion, "p6_amount_edit", 12)
            val reopened = checkNotNull(store.tool(saved.id))
            val spec = ToolSpec.parsePersisted(reopened.spec)
            val clock = com.alpha.assistant.tools.localPartClock(context)
            val view = checkNotNull(spec.composition).projectState(JSONObject(reopened.state), clock)
            assertEquals(12.0, view.getDouble("p5_share_value"), 0.0)
            assertEquals(0.0, JSONObject(reopened.state).getDouble("p5_share_value"), 0.0)
            saved = engine.interact(saved.id, saved.revision, saved.stateVersion, "p7_divisor_edit", 0)
            assertFalse(checkNotNull(spec.composition).projectState(JSONObject(saved.state), clock).getBoolean("p5_share_available"))
            assertFalse(CloudGenerationStage.COMPLETE in provider.stages)
            assertEquals(1, provider.stages.count { it == CloudGenerationStage.PLAN })
            val parts = JSONObject(reopened.spec).getJSONObject("composition").getJSONArray("parts")
            val configs = (0 until parts.length()).map { parts.getJSONObject(it) }.associate {
                it.getString("needId") to it.getJSONObject("config")
            }
            assertFalse(configs.getValue("amount").has("min"))
            assertFalse(configs.getValue("divisor").has("max"))
            saved = engine.interact(saved.id, saved.revision, saved.stateVersion, "p7_divisor_edit", 2)
            val edit = NativeToolEditRequest("change-formula")
            engine.prepareNativeEdit(saved, edit, "Multiply by three instead.", "fixture")
            assertEquals(saved, store.tool(saved.id))
            val refined = engine.confirmNativeEdit(edit)
            assertEquals(saved.state, refined.state)
            val revised = ToolSpec.parsePersisted(refined.spec)
            assertEquals(18.0, checkNotNull(revised.composition).projectState(JSONObject(refined.state), clock)
                .getDouble("p5_share_value"), 0.0)
            val invalid = runCatching {
                engine.prepareNativeEdit(refined, NativeToolEditRequest("cycle"), "Introduce a cycle.", "fixture")
            }.exceptionOrNull() as? CloudFailure
            assertEquals("tool_native_edit_unsupported", invalid?.code)
            assertEquals(refined, store.tool(refined.id))
        }
    }

    @Test fun mixedFillReadsOnlyDeclaredValuesAndRefreshesAfterNativeEdit(): Unit = runBlocking {
        val needs = """
          {"id":"twice","type":"figure","label":"Double","description":"Twice the amount",
           "expression":{"op":"multiply","args":[{"ref":"amount"},{"number":2}]}},
          {"id":"message","type":"other","label":"Summary","description":"Show a summary of the double", "writes":[],"reads":["twice"]}
        """.trimIndent()
        val response = JSONObject(plan(needs)).put("inputs", JSONArray("""
            [{"id":"amount","label":"Amount","type":"number","value":3}]
        """.trimIndent())).toString()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> response
                CloudGenerationStage.SELECTOR -> answer(turn)
                CloudGenerationStage.COMPLETE -> {
                    assertEquals(turn.turns.last().text, 1, turn.turns.size)
                    assertTrue(turn.turns.first().text.contains("input.ports"))
                    val fill = JSONObject(definition(source = """
                        if (Object.keys(input.ports).join() !== 'twice') throw Error('Unexpected port');
                        if (Object.keys(input.state).join() !== 'fill_value') throw Error('Foreign state');
                        return {state:input.state,ui:{type:'column',children:[{type:'text',text:'Double: '+input.ports.twice}]}};
                    """.trimIndent())).apply {
                        remove("formatVersion")
                        put("ui", JSONObject("""{"type":"column","children":[{"type":"text","text":"Summary"}]}"""))
                    }
                    mixed(fill, "message")
                }
                else -> error("Unexpected generation stage.")
            }
        }
        withEngine(provider) { _, engine, _ ->
            val draft = engine.createDraft("Double an amount and show a summary.", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val saved = engine.accept(draft.id)
            assertTrue(saved.ui.contains("Double: 6"))
            val changed = engine.interact(saved.id, saved.revision, saved.stateVersion, "p6_amount_edit", 7)
            assertTrue(changed.ui.contains("Double: 14"))
            assertEquals(0, JSONObject(changed.state).getInt("fill_value"))
        }
    }

    @Test fun invalidCalculationPlansNeverReachTheWriterOrPublication(): Unit = runBlocking {
        val expressions = listOf(
            """{"ref":"result"}""",
            """{"ref":"missing"}""",
            """{"op":"eval","args":[{"number":1}]}""",
            """{"op":"divide","args":[{"number":1},{"number":0}]}"""
        )
        for (expression in expressions) {
            val need = """{"id":"result","type":"figure","label":"Result","description":"A calculation",
                "expression":$expression}"""
            var calls = 0
            val provider = FixtureProvider { turn, _ ->
                assertEquals(CloudGenerationStage.PLAN, turn.generationStage)
                if (++calls == 1) plan(need) else """{"fields":{"description":"Repair $calls"}}"""
            }
            withEngine(provider) { store, engine, _ ->
                val draft = engine.createDraft("Show the requested calculation.", null)
                assertEquals("widget_plan", failure("widget_plan") {
                    engine.generate(draft.id, "fixture", emptyList(), CloudSession())
                }.code)
                assertTrue(store.tools().isEmpty())
                assertEquals(List(4) { CloudGenerationStage.PLAN }, provider.stages)
            }
        }
    }

    @Test fun nativeRecordTotalsFollowAddedAndRemovedRows(): Unit = runBlocking {
        val id = "abcdefghijklmnopqrstuvwx"
        val needs = """{"id":"$id","type":"entries","label":"Expenses","description":"Add and remove named amounts",
            "fields":[{"id":"name","label":"Item","type":"text"},{"id":"$id","label":"Amount","type":"number"}]},
            {"id":"total","type":"figure","label":"Total","description":"Sum the recorded amounts",
            "expression":{"aggregate":"sum","need":"$id","field":"$id"}}"""
        val provider = FixtureProvider { turn, _ -> when (turn.generationStage) {
            CloudGenerationStage.PLAN -> JSONObject(plan(needs)).put("behavior", nativeFixtureBehavior(
                """"name":{"kind":"action","part":"$id","member":"edit_1"},
                    "amount":{"kind":"action","part":"$id","member":"edit_2"},
                    "add":{"kind":"action","part":"$id","member":"add"},
                    "total":{"kind":"value","part":"total","member":"value"}""",
                """{"op":"action","slot":"name","value":"Coffee"},{"op":"action","slot":"amount","value":3.5},
                    {"op":"action","slot":"add"},{"op":"assert","slot":"total","test":"equals","value":3.5}"""
            )).toString()
            CloudGenerationStage.SELECTOR -> answer(turn)
            else -> error("Record totals must use native parts.")
        } }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft("Keep named expenses and show their total.", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            var saved = engine.accept(draft.id)
            val opened = engine.openArtifact(checkNotNull(store.tool(saved.id)))
            assertEquals(saved.id, opened.tool.id)
            engine.closeArtifact(saved.id, saved.revision, opened.sessionGeneration)
            saved = checkNotNull(store.tool(saved.id))
            suspend fun act(action: String, value: Any?) {
                saved = engine.interact(saved.id, saved.revision, saved.stateVersion, "${partInstanceId(id)}_$action", value)
            }
            act("edit_1", "Coffee"); act("edit_2", 3.5); act("add", null)
            act("edit_1", "Lunch"); act("edit_2", 12); act("add", null)
            fun total(): Double {
                val reopened = checkNotNull(store.tool(saved.id))
                return ToolSpec.parsePersisted(reopened.spec).composition!!.projectState(JSONObject(reopened.state),
                    com.alpha.assistant.tools.localPartClock(context)).getDouble("p5_total_value")
            }
            assertEquals(15.5, total(), 0.0)
            act("remove", "0")
            assertEquals(12.0, total(), 0.0)
            assertEquals("Lunch", JSONObject(saved.state).getJSONArray("${partInstanceId(id)}_items").getJSONObject(0).getString("name"))
        }
    }

    @Test fun planAndWriterHaveIndependentRepairLimits(): Unit = runBlocking {
        var planCalls = 0
        var writerCalls = 0
        var writerSucceeds = false
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> when (++planCalls) {
                    1 -> """{"wholeScreen":false}"""
                    2 -> """{"fields":{"wholeScreen":1}}"""
                    3 -> """{"fields":{"wholeScreen":"true"}}"""
                    4 -> """{"fields":{"wholeScreen":true,"language":"en",
                        "behavior":{"status":"unchecked","reason":"unsupported_observation"}}}"""
                    else -> error("A fourth PLAN repair dispatched.")
                }
                CloudGenerationStage.COMPLETE -> when (++writerCalls) {
                    1 -> JSONObject(definition()).apply {
                        put("ui", JSONObject().put("type", "invalid_component"))
                    }.toString()
                    2 -> """{"fields":{"description":"First repair"}}"""
                    3 -> """{"fields":{"description":"Second repair"}}"""
                    4 -> if (writerSucceeds) """{"fields":{"ui":{"type":"text","text":"Result","bind":"fill_value"}}}""" else """{"fields":{"description":"Third repair"}}"""
                    else -> error("A fourth COMPLETE repair dispatched.")
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            failure("widget_spec") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(4, planCalls)
            assertEquals(4, writerCalls)
            assertNull(store.draft(draft.id)?.candidate)
            planCalls = 0
            writerCalls = 0
            writerSucceeds = true
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertEquals(4, planCalls)
            assertEquals(4, writerCalls)
            assertNotNull(store.draft(draft.id)?.candidate)
        }
    }

    @Test fun lengthRewriteKeepsTheRemainingPatchAllowanceAndInterruptedOutputDoesNotRetry(): Unit = runBlocking {
        var complete = 0
        var interrupted = false
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(custom)
                CloudGenerationStage.COMPLETE -> when (++complete) {
                    1 -> throw CloudFailure(if (interrupted) "incomplete" else "response_length", "Fixture")
                    2 -> JSONObject(definition()).put("formatVersion", 2).toString()
                    3 -> """{"fields":{"formatVersion":3}}"""
                    else -> error("Unexpected COMPLETE request.")
                }
                else -> error("Unexpected request stage.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            val good = engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertEquals(3, complete)
            val turns = provider.requests.filter { it.generationStage == CloudGenerationStage.COMPLETE }
            assertEquals(turns.first().turns.single(), turns[1].turns.first())
            assertTrue(turns[1].turns.last().text.contains("limit"))
            assertTrue(turns[2].turns.last().text.contains("fields"))
            complete = 0
            interrupted = true
            failure("incomplete") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(1, complete)
            assertEquals(good, store.draft(draft.id))
        }
    }

    @Test fun cancellationAfterReceivingAPatchKeepsThePriorDraft(): Unit = runBlocking {
        var complete = 0
        val provider = FixtureProvider { turn, session ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(custom)
                CloudGenerationStage.COMPLETE -> when (++complete) {
                    1 -> definition()
                    2 -> JSONObject(definition()).put("formatVersion", 2).toString()
                    3 -> {
                        session.cancel()
                        """{"fields":{"formatVersion":3}}"""
                    }
                    else -> error("A canceled repair must never dispatch another request.")
                }
                else -> error("Unexpected stage.")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            val original = engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            failure("cancelled") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(3, complete)
            assertEquals(original, store.draft(draft.id))
            assertNull(store.tool(draft.id))
        }
    }

    @Test fun malformedPlanUsesOneRewriteThenStops(): Unit = runBlocking {
        val events = mutableListOf<JSONObject>()
        val provider = FixtureProvider { turn, _ ->
            assertEquals(CloudGenerationStage.PLAN, turn.generationStage)
            "Never put this in diagnostics"
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

    @Test fun savedPatchKeepsPrivateFieldsLocalAcrossMultiLocationEdits(): Unit = runBlocking {
        val source = """
            if (input.event.type === 'action') {
                input.state.fill_value += 3;
                input.state.fill_other += 1;
            }
            return {state:input.state};
        """.trimIndent()
        val original = JSONObject(definition(source = source)).apply {
            getJSONObject("stateSchema").put("fill_other", "number").put("fill_note", "string")
            getJSONObject("initialState").put("fill_other", 0).put("fill_note", "PRIVATE_DEFAULT")
            put("ui", JSONObject("""{"type":"column","children":[{"type":"button","text":"Copy","action":"fill_copy"},
                {"type":"metric","text":"PRIVATE_LABEL","bind":"fill_value"}]}"""))
        }
        val provider = FixtureProvider { turn, _ ->
            assertTrue(turn.turns.none { it.text.contains("PRIVATE_") || it.text.contains("7654321") })
            assertEquals(CloudGenerationStage.REFINE, turn.generationStage)
            """{"sourceEdits":[{"search":"fill_value += 3","replace":"fill_value += 4"},
                {"search":"fill_other += 1","replace":"fill_other += 2"}]}"""
        }
        withEngine(provider) { _, _, generation ->
            val spec = ToolSpec.parse(original.toString())
            val liveState = JSONObject(spec.initialState).put("fill_value", 7654321).toString()
            val live = ToolRecord("private-saved-app", 2, 4, spec.json, liveState, spec.ui.toString(), null, true)
            val edited = generation.generate("fixture", "Change the increment to four", live, emptyList(), CloudSession(),
                live.id, privateState = true, draftPreview = null, trace = null)
            assertTrue(provider.requests.all { request -> request.turns.none { it.text.contains("PRIVATE_") || it.text.contains("7654321") } })
            assertEquals(live.state, edited.state)
            assertEquals(spec.ui.toString(), edited.spec.ui.toString())
            assertEquals(spec.initialState, edited.spec.initialState)
            assertTrue(edited.spec.source.contains("fill_other += 2"))
            assertEquals(listOf(CloudGenerationStage.REFINE), provider.stages)
        }
    }

    @Test fun protectedRefinementAddsOneLineWithoutReplacingTheSavedLayout(): Unit = runBlocking {
        val source = """
            if (input.event.type === 'action' && input.event.actionId === 'fill_copy') input.state.fill_value++;
            if (input.event.type === 'action' && input.event.actionId === 'refresh') {
                return {state:input.state,effects:[{id:'clock',capability:'network.request',args:{
                    method:'GET',url:'https://example.com/clock',extract:{kind:'number',path:['epoch']}}}]};
            }
            return {state:input.state};
        """.trimIndent()
        val original = JSONObject(definition(source = source)).apply {
            put("capabilityResults", true)
            put("capabilities", JSONArray().put("network.request"))
            getJSONObject("stateSchema").put("note", "string")
            getJSONObject("initialState").put("note", "PRIVATE_DEFAULT")
            put("ui", JSONObject("""{"type":"column","style":{"gap":"small"},"children":[
                {"type":"column","children":[{"type":"text","text":"PRIVATE_LABEL"},
                {"type":"metric","text":"Count","bind":"fill_value"}]},
                {"type":"button","text":"Copy","action":"fill_copy"},
                {"type":"button","text":"Refresh","action":"refresh"}]}"""))
        }
        val spec = ToolSpec.parse(original.toString()).withAppLanguage("en")
        val liveState = JSONObject(spec.initialState).put("fill_value", 7).put("note", "PRIVATE_LIVE").toString()
        val saved = ToolRecord("protected-ui", 1, 2, spec.json, liveState, spec.ui.toString(), null, true)
        val replacement = """{"type":"text","text":"Handoff audit ready"}"""
        val responses = ArrayDeque(listOf(
            """{"fields":{"ui":$replacement}}""", """{"removeFields":["ui"]}""", "bad JSON",
            JSONObject(spec.json).apply { remove("appLanguage"); put("ui", JSONObject(replacement)) }.toString()
        ))
        val provider = FixtureProvider { turn, _ ->
            assertEquals(CloudGenerationStage.REFINE, turn.generationStage)
            assertTrue(turn.turns.none { it.text.contains("PRIVATE_") })
            assertTrue(turn.turns.last().text.contains("Return one JSON patch object only"))
            responses.removeFirst()
        }
        withEngine(provider) { store, engine, _ ->
            store.saveTool(saved, revision = true)
            val edit = "Rename the app and add Handoff audit ready above the controls"
            val bad = engine.createDraft(edit, saved.id)
            failure("widget_patch") { engine.generate(bad.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(List(4) { CloudGenerationStage.REFINE }, provider.stages)
            assertTrue(responses.isEmpty())
            assertEquals(saved, store.tool(saved.id))
            assertNull(store.draft(bad.id)!!.candidate)
            engine.reject(bad.id)

            responses.add("""{"fields":{"description":"${"x".repeat(501)}","prependText":"Handoff audit ready"}}""")
            responses.add("""{"fields":{"description":"","title":"Updated","prependText":"Handoff audit ready"}}""")
            val good = engine.createDraft(edit, saved.id)
            engine.generate(good.id, "fixture", emptyList(), CloudSession())
            val revised = engine.accept(good.id)
            assertTrue(responses.isEmpty())
            assertEquals(saved.revision + 1, revised.revision)
            assertEquals(saved.state, revised.state)
            val revisedSpec = ToolSpec.parsePersisted(revised.spec)
            assertEquals("Updated", revisedSpec.title)
            assertEquals(spec.source, revisedSpec.source)
            assertEquals(spec.initialState, revisedSpec.initialState)
            val actual = JSONObject(revised.ui)
            val children = actual.getJSONArray("children")
            assertEquals(replacement, children.remove(0).toString())
            assertEquals(saved.ui, actual.toString())

            val counted = engine.interact(revised.id, revised.revision, revised.stateVersion, "fill_copy", null)
            assertEquals(8, JSONObject(counted.state).getInt("fill_value"))
            val refresh = engine.interactForeground(counted.id, counted.revision, counted.stateVersion, "refresh", null,
                acceptNetwork = true)
            assertEquals("https://example.com/clock", checkNotNull(refresh.network).args.getString("url"))
        }
    }

    @Test fun protectedRefinementKeepsTheAddedLineInRuntimeUi(): Unit = runBlocking {
        val ui = JSONObject("""{"type":"column","children":[{"type":"section","text":"Status","children":[{"type":"metric","text":"Clock","bind":"fill_value"}]},
            {"type":"button","text":"Copy","action":"fill_copy"},{"type":"button","text":"Refresh","action":"refresh"}]}""").toString()
        val hiddenUi = JSONObject(ui).put("visible", false).apply {
            val section = getJSONArray("children").getJSONObject(0)
            section.put("children", JSONArray().put(JSONObject("""{"type":"text","text":"Handoff audit ready"}"""))
                .put(section.getJSONArray("children").getJSONObject(0)))
        }
        val source = """
            function buildUi() { return $ui; }
            if (input.event.actionId === 'fill_copy') input.state.fill_value++;
            if (input.event.type === 'capabilityResults') input.state.fill_value = input.event.entries[0].data.value;
            return {state:input.state,ui:buildUi()};
        """.trimIndent()
        val spec = ToolSpec.parse(JSONObject(definition(source = source)).put("ui", JSONObject(ui))
            .put("capabilityResults", true).put("capabilities", JSONArray().put("network.request")).toString()).withAppLanguage("en")
        val saved = ToolRecord("protected-runtime-ui", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
        var calls = 0
        val provider = FixtureProvider { turn, _ ->
            if (++calls == 1) """{"fields":{"prependText":"Handoff audit ready"}}"""
            else {
                assertTrue(turn.turns.last().text, turn.turns.last().text.contains("protected_ui_text"))
                val edit = if (calls == 2) JSONObject()
                    .put("search", "return $ui;").put("replace", "return $hiddenUi;")
                else JSONObject().put("search", ""","visible":false""").put("replace", "")
                JSONObject().put("sourceEdits", JSONArray().put(edit)).toString()
            }
        }
        withEngine(provider) { store, engine, generation ->
            store.saveTool(saved, revision = true)
            val draft = engine.createDraft("Add Handoff audit ready at the top", saved.id)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val revised = engine.accept(draft.id)
            assertEquals(3, calls)
            val rendered = JSONObject(revised.ui)
            val top = rendered.getJSONArray("children").getJSONObject(0).getJSONArray("children")
            assertEquals("Handoff audit ready", (top.remove(0) as JSONObject).getString("text"))
            assertEquals(saved.ui, rendered.toString())
            val counted = engine.interact(revised.id, revised.revision, revised.stateVersion, "fill_copy", null)
            assertEquals(revised.ui, counted.ui)
            assertEquals(1, JSONObject(counted.state).getInt("fill_value"))
            val result = JSONObject("""{"type":"capabilityResults","deliveryId":"${UUID.randomUUID()}",
                "entries":[{"requestId":"clock","capability":"network.request","status":"succeeded",
                "data":{"value":17,"source":"https://example.com/clock","dataTimeMs":12345}}]}""")
            val refreshed = generation.evaluate(ToolSpec.parsePersisted(counted.spec), counted.id, counted.revision,
                counted.state, result, counted.ui)
            assertEquals(17, JSONObject(refreshed.state).getInt("fill_value"))
            assertEquals(revised.ui, refreshed.ui)
        }
    }

    @Test fun savedEditUsesOneFullFallbackAfterThreeRejectedPatches(): Unit = runBlocking {
        val original = definition()
        val fallback = JSONObject(original).put("title", "Fallback revision").toString()
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(custom)
                CloudGenerationStage.COMPLETE -> original
                CloudGenerationStage.REFINE -> {
                    val feedback = turn.turns.last().text
                    when {
                        feedback.startsWith("Apply the requested edit") ->
                            """{"sourceEdits":[{"search":"state","replace":"value"}]}"""
                        "(ambiguous_match)" in feedback ->
                            """{"sourceEdits":[{"search":"return","replace":"x"},{"search":"return {state","replace":"y"}]}"""
                        "(overlap)" in feedback -> """{"fields":{"title":"Generated"}}"""
                        "(no_change)" in feedback && feedback.startsWith("Return the complete corrected app definition") -> fallback
                        else -> error("Unexpected patch feedback")
                    }
                }
                else -> error("Unexpected request stage")
            }
        }
        withEngine(provider) { store, engine, _ ->
            val draft = engine.createDraft(request, null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val saved = engine.accept(draft.id)
            val edit = engine.createDraft("Rename the app", saved.id)
            provider.requests.clear()
            engine.generate(edit.id, "fixture", emptyList(), CloudSession())
            assertEquals(saved, store.tool(saved.id))
            val revised = engine.accept(edit.id)
            assertEquals(saved.revision + 1, revised.revision)
            assertEquals(saved.state, revised.state)
            assertEquals("Fallback revision", ToolSpec.parsePersisted(revised.spec).title)
            assertEquals(List(4) { CloudGenerationStage.REFINE }, provider.stages)
        }
    }

    @Test fun refinementSkipsTestPlanningAndKeepsLiveStateOnRepeatedFailure(): Unit = runBlocking {
        val definition = definition()
        var invalid = false
        val provider = FixtureProvider { turn, _ ->
            when (turn.generationStage) {
                CloudGenerationStage.PLAN -> plan(custom)
                CloudGenerationStage.COMPLETE -> definition
                CloudGenerationStage.REFINE -> {
                    assertFalse(turn.turns.any { it.text.contains("5418307") })
                    if (invalid) "bad JSON" else """{"fields":{"title":"Revised"}}"""
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
            val draft = engine.createDraft("Rename the app", live.id)
            provider.requests.clear()
            val good = engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            assertEquals(listOf(CloudGenerationStage.REFINE), provider.stages)
            val prompt = provider.requests.single().turns.first().text
            assertFalse(prompt.contains("behaviorBindings"))
            assertFalse(prompt.contains("5418307"))
            assertTrue(provider.requests.last().turns.last().text.contains("Return one JSON patch object only"))
            assertFalse(provider.requests.last().turns.first().text.contains("Schema:"))
            assertTrue(provider.requests.last().turns.first().text.contains("Change only what the user requested"))
            assertEquals("Revised", ToolSpec.parsePersisted(checkNotNull(good.candidate)).title)
            assertEquals(live.state, good.previewState)
            invalid = true
            provider.requests.clear()
            failure("widget_spec") { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
            assertEquals(List(4) { CloudGenerationStage.REFINE }, provider.stages)
            assertTrue(provider.requests.take(3).all { it.turns.last().text.contains("Return one JSON patch object only") })
            assertTrue(provider.requests.last().turns.last().text.contains("complete corrected app definition"))
            assertEquals(good, store.draft(draft.id))
            assertEquals(live, store.tool(live.id))
        }
    }
}
