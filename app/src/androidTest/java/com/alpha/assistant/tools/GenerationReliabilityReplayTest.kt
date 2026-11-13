package com.alpha.assistant.tools

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.generation.GenerationRepair
import com.alpha.assistant.tools.generation.sameToolJson
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationReliabilityReplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun recordedCases(): JSONObject =
        instrumentation.context.assets.open("generation-reliability/recorded.json").bufferedReader()
            .use { JSONObject(it.readText()) }

    private class RecordedProvider(
        private val case: JSONObject,
        private val finalReply: ((CloudRequest, String) -> String)? = null,
    ) : ChatProvider {
        private val replies = case.getJSONArray("replies")
        var nextRequest: CloudRequest? = null
            private set
        var consumed = 0
            private set

        override suspend fun respond(
            key: String,
            request: CloudRequest,
            session: CloudSession,
            emit: suspend (CloudEvent) -> Unit,
        ) {
            nextRequest = request
            check(consumed < replies.length()) { "A generation request exhausted the recorded replies." }
            val reply = replies.getJSONObject(consumed++)
            check(reply.getString("stage") == request.generationStage?.name) {
                "Reply $consumed expected ${reply.getString("stage")}, got ${request.generationStage}."
            }
            val content = if (request.generationStage == CloudGenerationStage.PLAN && consumed == 1 &&
                case.getString("request").startsWith("Shanghai layover:")
            ) JSONObject(reply.getString("content")).put("requiredCapabilities", JSONArray("""
                [{"id":"calendar.deleteAllOwned","excerpt":"wipes every calendar event this app added"},
                {"id":"calendar.open","excerpt":"opens the phone's calendar"}]
            """)).toString() else reply.getString("content")
            emit(CloudEvent.Text(request.withFixtureCapabilityPlan(
                if (consumed == replies.length()) finalReply?.invoke(request, content) ?: content else content)))
        }
    }

    @Test fun recordedInvalidCandidatesReachTheirReportedBoundary() = runBlocking {
        val cases = recordedCases()
        assertEquals(11, cases.length())
        val failures = mutableListOf<String>()
        val sandbox = SandboxClient(instrumentation.targetContext)
        assertEquals("OK", sandbox.execute("return {state:input.state};",
            """{"artifactId":"replay","revision":1,"event":{"type":"preview"},"state":{}}""").status)
        for (id in cases.keys()) {
            // The interval timer now has a repair path, which is replayed below.
            if (id in setOf("fresh:logs-6", "extra:reference-quick-log", "extra:reference-interval-timer")) continue
            val case = cases.getJSONObject(id)
            val expected = case.getJSONObject("expected")
            val expectedBinding = expected.optJSONObject("behavior_binding")
            if (id == "extra:reference-due-items") {
                checkNotNull(expectedBinding).put("nodeKind", "button").put("valueShape", "string")
            }
            val provider = RecordedProvider(case)
            val attempts = mutableListOf<JSONObject>()
            val generation = ToolGenerationService(provider, sandbox, null) { attempts += it.report() }
            val result = runCatching {
                generation.generate(
                    "offline", case.getString("context"), null, emptyList(), CloudSession(), "reliability",
                    privateState = false, draftPreview = null, originalRequest = case.getString("request"),
                )
            }
            val terminal = attempts.lastOrNull { it.optString("disposition") == "finish" }
            if (id in setOf("fresh:unit-8", "fresh:cooking-1")) {
                val preflight = attempts.lastOrNull {
                    it.optString("stage") == "plan" && it.optString("failureCode") == "widget_plan" &&
                        it.optString("disposition") == "correct"
                }
                val repair = provider.nextRequest?.turns?.lastOrNull()?.text.orEmpty()
                val expectedReason = if (id == "fresh:unit-8") "a consistent native workflow"
                    else "a selected native part or a writer slot"
                if (preflight == null || !repair.contains(expectedReason)) {
                    failures += "$id expected $expectedReason, observed $repair."
                }
                continue
            }
            val error = result.exceptionOrNull()
            val actual = (error as? CloudFailure)?.code
            Log.i("ReliabilityReplay", "$id replies=${provider.consumed} code=$actual terminal=$terminal")
            if (error !is CloudFailure || terminal == null ||
                terminal.optString("failureCode") != expected.getString("failure_code") ||
                terminal.optString("stage") != expected.getString("stage") ||
                terminal.optInt("attempt") != expected.getInt("attempt") ||
                !sameToolJson(terminal.optJSONObject("smoke"), expected.optJSONObject("smoke")) ||
                !sameToolJson(terminal.optJSONObject("behaviorBinding"), expectedBinding) ||
                provider.consumed != case.getJSONArray("replies").length()
            ) {
                failures += "$id expected ${expected.getString("stage")}/${expected.getString("failure_code")}, " +
                    "observed $terminal (${error?.javaClass?.simpleName}) after ${provider.consumed} replies."
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun rowSmokeReplaysTheRecordedWriterCandidates() = runBlocking {
        val cases = recordedCases()
        val sandbox = SandboxClient(instrumentation.targetContext)
        val failures = mutableListOf<String>()
        for ((id, rowAction) in mapOf("fresh:logs-6" to "editEntryDate", "extra:reference-quick-log" to "del")) {
            val case = cases.getJSONObject(id)
            val replies = case.getJSONArray("replies")
            val writer = (0 until replies.length()).map(replies::getJSONObject)
                .filter { it.getString("stage") == "COMPLETE" }.map { it.getString("content") }
            val repair = GenerationRepair.writer(ToolSpec.generatedFields + "behaviorBindings", null)
            repair.retain(writer.first())
            var candidate = writer.first()
            for (patch in writer.drop(1)) candidate = repair.apply(patch).text
            val definition = JSONObject(candidate).apply { remove("behaviorBindings") }
            val spec = ToolSpec.parse(definition.toString())
            val generation = ToolGenerationService(RecordedProvider(case), sandbox)
            var exercisedRow = false
            try {
                ToolGenerationSmoke(sandbox, spec, "row-replay", 1, spec.initialState, {}, generation,
                    observeReplay = { events ->
                        if (events.any { raw ->
                                val event = JSONObject(raw)
                                val key = event.optJSONObject("value")?.opt("key") as? String
                                event.optString("actionId") == rowAction && !key.isNullOrBlank()
                            }) exercisedRow = true
                    }).run()
                if (!exercisedRow) failures += "$id skipped $rowAction."
            } catch (error: ToolSmokeFailure) {
                val detail = "$id failed at ${error.path} (${error.cause?.javaClass?.simpleName})."
                Log.i("ReliabilityReplay", "$detail Events: ${error.replay?.joinToString()}")
                val expectedPath = case.getJSONObject("expected").getJSONObject("smoke").getString("path")
                failures += "$detail Expected baseline path: $expectedPath."
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun correctedScriptsPassTheRecordedSmokeBoundary() = runBlocking {
        val cases = recordedCases()
        val sandbox = SandboxClient(instrumentation.targetContext)
        for (id in listOf("fresh:unit-6", "bench:shanghai")) {
            val case = cases.getJSONObject(id)
            val provider = RecordedProvider(case) { request, raw ->
                val feedback = request.turns.last().text
                val patch = JSONObject(raw)
                val edits = patch.getJSONArray("sourceEdits")
                if (id == "fresh:unit-6") {
                    assertTrue(feedback.contains("Read the event from input.event"))
                    val state = edits.getJSONObject(0)
                    state.put("replace", state.getString("replace") + "\nconst event=input.event;")
                } else {
                    assertTrue(feedback.contains("[A-Za-z][A-Za-z0-9_]{0,47}"))
                    for (index in 0 until edits.length()) {
                        val edit = edits.getJSONObject(index)
                        edit.put("replace", edit.getString("replace")
                            .replace("id: 'calendar-delete-all-owned'", "id: 'calendar_delete_all_owned'")
                            .replace("id: 'calendar-open-app'", "id: 'calendar_open_app'"))
                    }
                }
                patch.toString()
            }
            val attempts = mutableListOf<JSONObject>()
            val generation = ToolGenerationService(provider, sandbox, null) { attempts += it.report() }
            val result = runCatching {
                generation.generate("offline", case.getString("context"), null, emptyList(), CloudSession(), "reliability",
                    privateState = false, draftPreview = null, originalRequest = case.getString("request"))
            }
            val terminal = attempts.lastOrNull { it.optString("disposition") == "finish" }
            assertEquals(case.getJSONArray("replies").length(), provider.consumed)
            assertTrue("$id did not pass smoke. Terminal: $terminal. Failure: ${result.exceptionOrNull()?.javaClass?.simpleName}.",
                attempts.any { it.optInt("attempt") == 5 && it.optString("stage") == "behavior" } || result.isSuccess)
        }
    }

    @Test fun recordedUnplannedSurfaceReachesDefinitionRepair() = runBlocking {
        val case = recordedCases().getJSONObject("extra:reference-interval-timer")
        val replies = case.getJSONArray("replies")
        repeat(3) { replies.put(JSONObject().put("stage", "COMPLETE").put("content", """{"invalid":true}""")) }
        val provider = RecordedProvider(case) { request, raw ->
            assertTrue(request.turns.last().text.contains("Remove the surfaces"))
            raw
        }
        val attempts = mutableListOf<JSONObject>()
        val generation = ToolGenerationService(provider, SandboxClient(instrumentation.targetContext), null) {
            attempts += it.report()
        }
        val failure = runCatching {
            generation.generate("offline", case.getString("context"), null, emptyList(), CloudSession(), "reliability",
                privateState = false, draftPreview = null, originalRequest = case.getString("request"))
        }.exceptionOrNull()
        assertEquals(replies.length(), provider.consumed)
        assertEquals("widget_patch", (failure as? CloudFailure)?.code)
        val rejection = attempts.first { it.optString("stage") == "definition" }
        assertEquals(2, rejection.getInt("attempt"))
        assertEquals("widget_spec", rejection.getString("failureCode"))
        assertEquals("correct", rejection.getString("disposition"))
        assertFalse(attempts.any { it.optString("stage") == "smoke" })
    }

    @Test fun nativeFigureProducesTheAdjacentFractionalValue(): Unit = runBlocking {
        val case = recordedCases().getJSONObject("fresh:unit-8")
        val patch = case.getJSONArray("replies").getJSONObject(1)
        val content = JSONObject(patch.getString("content"))
        val assertion = content.getJSONObject("fields").getJSONObject("behavior").getJSONObject("scenario")
            .getJSONArray("steps").getJSONObject(1)
        assertEquals(96.54, assertion.getDouble("value"), 0.0)
        assertion.put("value", Math.nextDown(96.54))
        patch.put("content", content.toString())
        val provider = RecordedProvider(case)
        val sandbox = SandboxClient(instrumentation.targetContext)
        val generation = ToolGenerationService(provider, sandbox)
        val result = generation.generate(
            "offline", case.getString("context"), null, emptyList(), CloudSession(), "numeric-replay",
            privateState = false, draftPreview = null, originalRequest = case.getString("request"),
        )
        assertEquals(4, provider.consumed)
        assertTrue(result.spec.isSealedStandalone)
        val event = JSONObject().put("type", "action").put("actionId", "p3_mph_edit").put("value", 60)
        val updated = sandbox.disposable { execute ->
            generation.executeEvent(result.spec, "numeric-replay", 1, result.state, event, result.ui) { source, input ->
                execute(source, input.toString())
            }
        }
        fun metric(node: JSONObject): JSONObject? {
            if (node.optString("type") == "metric") return node
            val children = node.optJSONArray("children") ?: return null
            return (0 until children.length()).firstNotNullOfOrNull { metric(children.getJSONObject(it)) }
        }
        val output = checkNotNull(metric(JSONObject(updated.ui)))
        val state = projectToolState(updated.spec, JSONObject(updated.state), generation.partClockFor(updated.spec))
        assertEquals(Math.nextDown(96.54), state.getDouble(output.getString("bind")), 0.0)
        Log.i("ReliabilityReplay", "fresh:unit-8 display=${ToolUiCatalog.display(output, state)}")
    }

    private val counterPlan = """
        {"wholeScreen":true,"language":"en","behavior":{"status":"checkable","scenario":{
          "slots":{"add":{"kind":"action"},"value":{"kind":"value"}},
          "steps":[{"op":"action","slot":"add"},{"op":"assert","slot":"value","test":"equals","value":1}]
        }}}
    """.trimIndent()

    private fun counterDefinition(): JSONObject = JSONObject("""
        {"formatVersion":3,"title":"Counter","description":"Add one","capabilities":[],
          "stateSchema":{"count":"number"},"initialState":{"count":0},
          "ui":{"type":"column","children":[{"type":"button","text":"Add","action":"add"},
            {"type":"metric","text":"Count","bind":"count"}]},
          "source":"const s=input.state;if(input.event.type==='action')s.count++;return {state:s};",
          "behaviorBindings":{"add":"add","value":[1]}}
    """.trimIndent())

    private class ScriptedProvider(private val reply: (CloudRequest) -> String) : ChatProvider {
        override suspend fun respond(
            key: String,
            request: CloudRequest,
            session: CloudSession,
            emit: suspend (CloudEvent) -> Unit,
        ) = emit(CloudEvent.Text(request.withFixtureCapabilityPlan(reply(request))))
    }

    private suspend fun generateCounter(provider: ChatProvider) =
        ToolGenerationService(provider, SandboxClient(instrumentation.targetContext)).generate(
            "offline", "Make a counter with an Add button.", null, emptyList(), CloudSession(), "counter-replay",
        )

    private fun selections(request: CloudRequest, preferHole: Boolean = false): String {
        val questions = JSONObject(request.turns.single().text).getJSONArray("questions")
        val answers = JSONArray()
        for (i in 0 until questions.length()) {
            val question = questions.getJSONObject(i)
            val id = question.getString("id")
            val choices = question.getJSONObject("choices")
            val selected = if (preferHole && id == "s_timer") "B" else "A"
            val probabilities = JSONObject()
            choices.keys().forEach { choice -> probabilities.put(choice, if (choice == selected) 1 else 0) }
            answers.put(JSONObject().put("id", id).put("choice", selected)
                .put("confidence", 1).put("probabilities", probabilities))
        }
        return JSONObject().put("answers", answers).toString()
    }

    @Test fun explicitNativeClockSurvivesGenericNoMatch() = runBlocking {
        val plan = """
            {"title":"Stopwatch","description":"Time a run","language":"en","inputs":[],"constants":[],
             "needs":[{"id":"timer","type":"stopwatch","label":"Stopwatch","description":"Start and pause"}],
             "questions":[],"behavior":{"status":"checkable","scenario":{
               "slots":{"start":{"kind":"action","part":"timer","member":"start"},
                        "elapsed":{"kind":"value","part":"timer","member":"value"}},
               "steps":[{"op":"capture","slot":"elapsed","as":"initial"},{"op":"action","slot":"start"},
                        {"op":"clock","slot":"elapsed","milliseconds":1000},
                        {"op":"assert","slot":"elapsed","test":"changed","capture":"initial"}]}}}
        """.trimIndent()
        var planCalls = 0
        val provider = ScriptedProvider { request ->
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> {
                    check(++planCalls == 1)
                    plan
                }
                CloudGenerationStage.SELECTOR -> selections(request, preferHole = true)
                else -> error("A complete native clock needs no writer.")
            }
        }
        val result = ToolGenerationService(provider, SandboxClient(instrumentation.targetContext)).generate(
            "offline", "Make a simple stopwatch with Start and Pause.", null, emptyList(), CloudSession(), "clock-replay",
        )
        assertTrue(result.spec.isSealedStandalone)
    }

    @Test fun nativeNumberTypeIsCorrectedBeforeAWriterCanChangeThePlan() = runBlocking {
        val plan = JSONObject("""
            {"title":"Counter","description":"Add one","language":"en","inputs":[],"constants":[],
             "needs":[{"id":"count","type":"counter","label":"Count","description":"Add one"}],
             "questions":[],"behavior":{"status":"checkable","scenario":{
               "slots":{"add":{"kind":"action","part":"count","member":"add"},
                        "value":{"kind":"value","part":"count","member":"value"}},
               "steps":[{"op":"action","slot":"add"},{"op":"assert","slot":"value","test":"equals","value":"1"}]}}}
        """.trimIndent())
        var planCalls = 0
        val provider = ScriptedProvider { request ->
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> when (++planCalls) {
                    1 -> plan.toString()
                    2 -> {
                        assertTrue(request.turns.last().text.contains("a numeric expected value"))
                        """{"behavior":{}}"""
                    }
                    3 -> {
                        assertTrue(request.turns.last().text.contains("a numeric expected value"))
                        val behavior = plan.getJSONObject("behavior")
                        behavior.getJSONObject("scenario").getJSONArray("steps").getJSONObject(1).put("value", 1)
                        JSONObject().put("fields", JSONObject().put("behavior", behavior)).toString()
                    }
                    else -> error("Unexpected PLAN retry")
                }
                CloudGenerationStage.SELECTOR -> selections(request)
                else -> error("A complete native counter needs no writer.")
            }
        }
        generateCounter(provider)
        assertEquals(3, planCalls)
    }

    @Test fun finalPlanRepairUsesFullEnvelopeAndKeepsSemanticFailure() = runBlocking {
        val plans = mutableListOf<CloudRequest>()
        val provider = ScriptedProvider { request ->
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> {
                    plans += request
                    when (plans.size) {
                        1 -> JSONObject(counterPlan).apply { remove("behavior") }.toString()
                        2, 3 -> """{"behavior":{}}"""
                        4 -> counterPlan
                        else -> error("Unexpected PLAN retry")
                    }
                }
                CloudGenerationStage.COMPLETE -> counterDefinition().toString()
                else -> error("Unexpected stage ${request.generationStage}")
            }
        }
        assertEquals(0, JSONObject(generateCounter(provider).state).getInt("count"))
        assertEquals(4, plans.size)
        for (patch in plans.subList(1, 3)) {
            assertTrue(patch.turns.last().text.contains("Return one JSON patch object only"))
        }
        assertFalse(plans.last().turns.last().text.contains("Return one JSON patch object only"))
        assertTrue(plans.last().turns.last().text.contains("Return the corrected JSON plan"))
        assertTrue(plans.last().systemInstructions.contains("$.behavior"))
        assertTrue(plans.last().systemInstructions.contains("complete JSON plan"))
        assertFalse(plans.last().systemInstructions.contains("The previous patch could not apply"))
    }

    @Test fun writerBindingRepairSharesTheFlatContract() = runBlocking {
        val writers = mutableListOf<CloudRequest>()
        val provider = ScriptedProvider { request ->
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> counterPlan
                CloudGenerationStage.COMPLETE -> {
                    writers += request
                    val definition = counterDefinition()
                    when (writers.size) {
                        1 -> definition.apply { getJSONObject("behaviorBindings").put("value", "1") }.toString()
                        2 -> """{"behaviorBindings":{}}"""
                        3 -> JSONObject().put("fields", JSONObject()
                            .put("behaviorBindings", definition.getJSONObject("behaviorBindings"))).toString()
                        else -> error("Unexpected writer retry")
                    }
                }
                else -> error("Unexpected stage ${request.generationStage}")
            }
        }
        assertEquals(0, JSONObject(generateCounter(provider).state).getInt("count"))
        assertEquals(3, writers.size)
        assertTrue(writers.first().systemInstructions.contains("\"value\":[0]"))
        assertTrue(writers.first().systemInstructions.contains("Put behaviorBindings at the top level"))
        assertFalse(writers.last().systemInstructions.contains("Put behaviorBindings at the top level"))
        assertTrue(writers.last().turns.last().text.contains("\"value\":[0]"))
        assertFalse(writers.last().turns.last().text.contains("Put behaviorBindings at the top level"))
        assertTrue(writers.last().turns.last().text.contains("for slot value"))
        assertTrue(writers.last().turns.last().text.contains("The previous patch could not apply"))
    }

    @Test fun newAppCanRepairAnUnplannedSurfaceWithoutChangingTheCheck() = runBlocking {
        var writers = 0
        var plans = 0
        val provider = ScriptedProvider { request ->
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> {
                    plans++
                    counterPlan
                }
                CloudGenerationStage.COMPLETE -> when (++writers) {
                    1 -> counterDefinition().put("surfaces",
                        JSONArray("""[{"id":"canvas","width":64,"height":64}]""")).toString()
                    2 -> {
                        assertTrue(request.turns.last().text.contains("Remove the surfaces"))
                        """{"removeFields":["surfaces"]}"""
                    }
                    else -> error("Unexpected writer retry")
                }
                else -> error("Unexpected stage ${request.generationStage}")
            }
        }
        val result = generateCounter(provider)
        assertEquals(1, plans)
        assertEquals(2, writers)
        assertTrue(result.spec.surfaces.isEmpty())
    }
}
