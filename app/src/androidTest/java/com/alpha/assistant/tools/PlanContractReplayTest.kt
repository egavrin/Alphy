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
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.generation.GenerationRepair
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlanContractReplayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val cases by lazy {
        instrumentation.context.assets.open("generation-reliability/plan-contract-recorded.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("cases") }
    }

    private fun replies(case: JSONObject, stage: CloudGenerationStage): List<String> {
        val replies = case.getJSONArray("replies")
        return (0 until replies.length()).map(replies::getJSONObject)
            .filter { it.getString("stage") == stage.name }.map { it.getString("content") }
    }

    private fun candidate(case: JSONObject, stage: CloudGenerationStage): JSONObject {
        val replies = replies(case, stage)
        val repair = if (stage == CloudGenerationStage.PLAN) GenerationRepair.plan()
            else GenerationRepair.writer(ToolSpec.generatedFields + "behaviorBindings", null)
        repair.retain(replies.first())
        var text = replies.first()
        for (patch in replies.drop(1)) text = repair.apply(patch).text
        return JSONObject(text)
    }

    private fun scenario(plan: JSONObject) = plan.getJSONObject("behavior").getJSONObject("scenario")
    private fun patch(vararg fields: Pair<String, Any>) = JSONObject().put("fields", JSONObject(fields.toMap())).toString()
    private fun assertion(slot: String, test: String, value: Any) =
        JSONObject().put("op", "assert").put("slot", slot).put("test", test).put("value", value)
    private fun action(slot: String, value: Any? = null) =
        JSONObject().put("op", "action").put("slot", slot).apply { value?.let { put("value", it) } }

    private data class Replay(
        val result: Result<ToolPreview>,
        val session: CloudSession,
        val events: List<ToolGenerationTraceEvent>,
        val planRequests: List<CloudRequest>,
        val writerRequests: List<CloudRequest>,
    ) {
        val rejectedPlans get() = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
            .filter { it.stage == ToolGenerationStage.PLAN && it.outcome == ToolGenerationOutcome.REJECTED }
        val repairs get() = events.filterIsInstance<ToolGenerationTraceEvent.Repair>()

        fun assertWriterBudgetIntact() {
            assertFalse(repairs.any { it.providerStage == CloudGenerationStage.COMPLETE })
            assertEquals(1, session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
        }
    }

    private suspend fun replay(
        id: String,
        case: JSONObject,
        plans: List<String>,
        writers: List<String> = emptyList(),
    ): Replay {
        val session = CloudSession()
        val events = mutableListOf<ToolGenerationTraceEvent>()
        val plansSeen = mutableListOf<CloudRequest>()
        val writersSeen = mutableListOf<CloudRequest>()
        val selections = if (case.has("replies")) replies(case, CloudGenerationStage.SELECTOR) else emptyList()
        fun ids(rows: JSONArray): List<String> = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
        val recordedSelections = selections.associateBy { ids(JSONObject(it).getJSONArray("answers")) }
        val provider = object : ChatProvider {
            override suspend fun respond(
                key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit,
            ) {
                val reply = when (request.generationStage) {
                    CloudGenerationStage.PLAN -> plans.getOrElse(plansSeen.size) {
                        throw AssertionError("$id unexpected PLAN request ${plansSeen.size + 1}")
                    }.let(request::withFixtureCheckEvidence).also { plansSeen += request }
                    CloudGenerationStage.SELECTOR -> {
                        val body = request.turns.single().text
                        if (recordedSelections.isEmpty()) firstSelectorChoices(body)
                        else recordedSelections.getValue(ids(JSONObject(body).getJSONArray("questions")))
                    }
                    CloudGenerationStage.COMPLETE -> {
                        assertEquals("$id writer began before corrected PLAN", plans.size, plansSeen.size)
                        writers.getOrElse(writersSeen.size) {
                            throw AssertionError("$id unexpected COMPLETE request ${writersSeen.size + 1}")
                        }.also { writersSeen += request }
                    }
                    else -> error("$id unexpected stage ${request.generationStage}")
                }
                emit(CloudEvent.Text(reply))
            }
        }
        val result = runCatching {
            ToolGenerationService(provider, SandboxClient(instrumentation.targetContext)).generate(
                "offline", case.getString("context"), null, emptyList(), session, "plan-contract",
                privateState = false, draftPreview = null, originalRequest = case.getString("request"),
                trace = ToolGenerationTrace(UUID.randomUUID(), { events += it }),
            )
        }
        val run = Replay(result, session, events, plansSeen, writersSeen)
        Log.i("PlanContractReplay", "$id PLAN=${plansSeen.size} COMPLETE=${writersSeen.size} " +
            "rejected=${run.rejectedPlans.map { it.planShape?.rule to it.planPath }} " +
            "repairs=${run.repairs.map { it.providerStage to it.repairNumber }} " +
            "result=${result.exceptionOrNull()?.message ?: "validated"}")
        return run
    }

    @Test fun recordedScalarPlansRepairBeforeWriterAndKeepTheirBudget() = runBlocking {
        for ((id, slot, step) in listOf(
            Triple("fresh:unit-3", "inches", 3),
            Triple("fresh:home-5", "covered", 2),
            Triple("fresh:beyond-1", "total", 3),
        )) {
            val case = cases.getJSONObject(id)
            val plan = candidate(case, CloudGenerationStage.PLAN)
            val original = plan.toString()
            val workflow = scenario(plan)
            workflow.getJSONObject("slots").getJSONObject(slot).put("valueType", "number")
            val steps = workflow.getJSONArray("steps")
            val conflictingType = patch("behavior" to plan.getJSONObject("behavior"))
            val writer = candidate(case, CloudGenerationStage.COMPLETE)
            when (id) {
                "fresh:unit-3" -> {
                    steps.put(step, assertion(slot, "equals", 1))
                    writer.getJSONObject("behaviorBindings").put("cm", JSONArray().put(1)).put("inches", JSONArray().put(0))
                }
                "fresh:home-5" -> steps.put(step, assertion(slot, "equals", 21.2))
                else -> {
                    val bounded = JSONArray()
                    for (index in 0 until steps.length()) {
                        if (index == step) bounded.put(assertion(slot, "greater", 2)).put(assertion(slot, "less", 61))
                        else bounded.put(steps.getJSONObject(index))
                    }
                    workflow.put("steps", bounded)
                }
            }
            val corrections = listOfNotNull(conflictingType.takeIf { id == "fresh:unit-3" },
                patch("behavior" to plan.getJSONObject("behavior")))
            val run = replay(id, case, listOf(original) + corrections, listOf(writer.toString()))
            run.result.getOrThrow()
            val expected = listOfNotNull("behavior.value_type_required", "behavior.assertion_type".takeIf { id == "fresh:unit-3" })
            assertEquals(id, expected, run.rejectedPlans.map { it.planShape?.rule })
            assertEquals(id, expected.map { "\$.behavior.scenario.steps[$step]" }, run.rejectedPlans.map { it.planPath })
            assertEquals(id, 1, run.writerRequests.size)
            assertEquals(expected.map { CloudGenerationStage.PLAN }, run.repairs.map { it.providerStage })
            run.assertWriterBudgetIntact()
        }
    }

    @Test fun wrongWriterBindingRepairsTheWriterWithPlanIntact() = runBlocking {
        val id = "fresh:unit-3"
        val case = cases.getJSONObject(id)
        val plan = candidate(case, CloudGenerationStage.PLAN)
        val scenario = scenario(plan)
        scenario.getJSONObject("slots").getJSONObject("cm").put("valueType", "number")
        scenario.getJSONArray("steps").put(3, assertion("inches", "equals", 1))
        val writer = candidate(case, CloudGenerationStage.COMPLETE)
        val bindings = writer.getJSONObject("behaviorBindings")
        bindings.put("cm", JSONArray().put(4)).put("inches", JSONArray().put(0))
        val wrong = writer.toString()
        bindings.put("cm", JSONArray().put(1))
        val run = replay("$id wrong text binding", case, listOf(plan.toString()), listOf(wrong, patch("behaviorBindings" to bindings)))
        run.result.getOrThrow()
        assertTrue(run.rejectedPlans.isEmpty())
        assertEquals(1, run.planRequests.size)
        assertEquals(listOf(CloudGenerationStage.COMPLETE), run.repairs.map { it.providerStage })
        assertTrue(run.writerRequests.last().turns.last().text.contains("slot cm requires a visible number output"))
        assertEquals(BehaviorFailureRule.SCALAR_SHAPE, run.events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
            .mapNotNull { it.behaviorBinding }.single().rule)
        assertEquals(1, run.session.claimGenerationRepair(CloudGenerationStage.PLAN))
        assertEquals(2, run.session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
    }

    @Test fun invalidDeclaredTypeAndNullAssertionStayInPlan() = runBlocking {
        val case = cases.getJSONObject("fresh:unit-3")
        val plan = candidate(case, CloudGenerationStage.PLAN)
        val workflow = scenario(plan)
        val inches = workflow.getJSONObject("slots").getJSONObject("inches")
        val steps = workflow.getJSONArray("steps")
        inches.put("valueType", "integer")
        val invalidType = plan.toString()
        inches.remove("valueType")
        steps.put(3, assertion("inches", "equals", JSONObject.NULL))
        val nullAssertion = patch("behavior" to plan.getJSONObject("behavior"))
        steps.put(3, assertion("inches", "equals", 1))
        val writer = candidate(case, CloudGenerationStage.COMPLETE)
        writer.getJSONObject("behaviorBindings").put("cm", JSONArray().put(1)).put("inches", JSONArray().put(0))
        val run = replay("authored invalid scalar type", case,
            listOf(invalidType, nullAssertion, patch("behavior" to plan.getJSONObject("behavior"))), listOf(writer.toString()))
        run.result.getOrThrow()
        assertEquals(listOf("behavior.slot_value_type", "behavior.assertion_type"), run.rejectedPlans.map { it.planShape?.rule })
        assertEquals("null", run.rejectedPlans.last().planShape?.observedType)
        run.assertWriterBudgetIntact()
    }

    @Test fun recordedNativeNotesRejectWrongPayloadsThenUseCurrentRow() = runBlocking {
        val id = "compose:shopping"
        val case = cases.getJSONObject(id)
        val plan = candidate(case, CloudGenerationStage.PLAN)
        val original = plan.toString()
        val scenario = scenario(plan)
        scenario.getJSONObject("slots").put("edit", JSONObject("""{"kind":"action","part":"items","member":"edit"}"""))
        val steps = JSONArray().put(assertion("list", "equals", 0)).put(action("edit", "Milk"))
            .put(action("add")).put(assertion("list", "equals", 1)).put(action("remove"))
            .put(assertion("list", "equals", 0))
        scenario.put("steps", steps)
        val withoutKey = patch("behavior" to plan.getJSONObject("behavior"))
        steps.put(4, action("remove", ""))
        val blankKey = patch("behavior" to plan.getJSONObject("behavior"))
        steps.put(4, action("remove", "0"))
        val run = replay(id, case, listOf(original, withoutKey, blankKey, patch("behavior" to plan.getJSONObject("behavior"))))
        run.result.getOrThrow()
        assertEquals(listOf("\$.behavior.scenario.steps[1].value", "\$.behavior.scenario.steps[4].value",
            "\$.behavior.scenario.steps[4].value"),
            run.rejectedPlans.map { it.planPath })
        assertTrue(run.rejectedPlans.all { it.planShape?.rule == "behavior.action_payload" })
        assertTrue(run.planRequests[1].turns.last().text.contains("press without a value"))
        assertTrue(run.planRequests[2].turns.last().text.contains("row key"))
        assertTrue(run.planRequests[3].turns.last().text.contains("a string row key with 1 to 128 characters"))
        assertTrue(run.writerRequests.isEmpty())
        run.assertWriterBudgetIntact()
    }

    @Test fun recordedMortgageUsesTheUnusedOutputInTheCorrectedPlan() = runBlocking {
        val id = "fresh:math-2"
        val case = cases.getJSONObject(id)
        val plan = candidate(case, CloudGenerationStage.PLAN)
        val original = plan.toString()
        val steps = scenario(plan).getJSONArray("steps")
        steps.put(assertion("totalInterest", "greater", 0))
        val valid = patch("behavior" to plan.getJSONObject("behavior"))
        steps.put(3, action("years", 51))
        val run = replay(id, case, listOf(original, patch("behavior" to plan.getJSONObject("behavior")), valid))
        run.result.getOrThrow()
        assertEquals(listOf("behavior.unused_slot", "behavior.action_payload"), run.rejectedPlans.map { it.planShape?.rule })
        assertEquals("\$.behavior.scenario.steps[3].value", run.rejectedPlans.last().planPath)
        assertTrue(run.planRequests[1].turns.last().text.contains("totalInterest"))
        assertTrue(run.planRequests[2].turns.last().text.contains("between 1.0 and 50.0"))
        run.assertWriterBudgetIntact()
    }

    @Test fun recordedReadingStatusUsesConstantsAndStableKeys() = runBlocking {
        val id = "extra:reference-reading-status"
        val case = cases.getJSONObject(id)
        val plan = candidate(case, CloudGenerationStage.PLAN)
        val original = plan.toString()
        val needs = plan.getJSONArray("needs")
        val log = needs.getJSONObject(0)
        log.remove("unit")
        log.remove("initial")
        log.put("uses", JSONObject("""{"unit":"rpm","initial":"firstReading"}"""))
        val constants = JSONArray("""[{"id":"rpm","value":"об/мин"},{"id":"firstReading","value":1200}]""")
        val steps = JSONArray().put(assertion("latest", "equals", 1200)).put(assertion("status", "equals", "Рабочий режим"))
            .put(action("edit", "850")).put(action("add")).put(assertion("status", "equals", "Медленно"))
            .put(action("edit", "1600")).put(action("add")).put(assertion("status", "equals", "Быстро"))
            .put(action("remove", "1")).put(assertion("latest", "equals", 850)).put(assertion("history", "equals", 2))
        scenario(plan).put("steps", steps)
        val valid = patch("behavior" to plan.getJSONObject("behavior"))
        steps.put(2, action("edit", 850))
        val run = replay(id, case, listOf(original, patch("needs" to needs, "constants" to constants,
            "behavior" to plan.getJSONObject("behavior")), valid))
        val preview = run.result.getOrThrow()
        assertEquals(listOf("\$.needs[0]", "\$.behavior.scenario.steps[2].value"), run.rejectedPlans.map { it.planPath })
        assertEquals("behavior.action_payload", run.rejectedPlans.last().planShape?.rule)
        assertTrue(run.planRequests[1].turns.last().text.contains("uses.unit and uses.initial"))
        assertTrue(run.planRequests[2].turns.last().text.contains("a string of at most 2048 characters"))
        assertTrue(preview.ui.replace("\\/", "/").contains("об/мин"))
        assertTrue(run.writerRequests.isEmpty())
        run.assertWriterBudgetIntact()
    }

    @Test fun nativeChoiceAndRowTemplateRequireTheirDeclaredPayloads() = runBlocking {
        val ledger = JSONObject("""{"title":"Team score","description":"Track points","language":"en","inputs":[],"questions":[],
            "constants":[{"id":"red","value":"Red"},{"id":"blue","value":"Blue"},
                {"id":"win","value":3},{"id":"tie","value":1},{"id":"cost","value":2}],
            "needs":[{"id":"scores","type":"points_ledger","label":"Scores","description":"Track both team balances.",
                "uses":{"team1":"red","team2":"blue","earn1":"win","earn2":"tie","cost":"cost"}}]}""")
        val walk = JSONObject("""{"title":"Walk","description":"Mark a visit","language":"en","inputs":[],
            "constants":[{"id":"startTime","value":550}],"questions":[],
            "needs":[{"id":"walk","type":"itinerary","label":"Stops","description":"Mark the visit to the hall.",
                "uses":{"start":"startTime"},"stops":[{"name":"Hall","minutes":25}]}]}""")
        fun behavior(slots: String, vararg steps: JSONObject) = JSONObject().put("status", "checkable")
            .put("scenario", JSONObject().put("slots", JSONObject(slots)).put("steps", JSONArray(steps.toList())))
        fun ledgerBehavior(team: Int) = behavior(
            """{"choose":{"kind":"action","part":"scores","member":"select"},"credit":{"kind":"action","part":"scores","member":"post1"},
                "balance":{"kind":"value","part":"scores","member":"balance2"}}""",
            action("choose", team), action("credit"), assertion("balance", "equals", 3))
        fun walkBehavior(payload: Any) = behavior(
            """{"visit":{"kind":"action","part":"walk","member":"visit"},"stops":{"kind":"collection","part":"walk","member":"stops"}}""",
            action("visit", payload), assertion("stops", "equals", 1))
        for ((plan, invalid, valid) in listOf(
            Triple(ledger, ledgerBehavior(3), ledgerBehavior(2)),
            Triple(walk, walkBehavior("stop1"), walkBehavior(JSONObject().put("key", "stop1").put("value", true))),
        )) {
            val request = plan.getString("description")
            val case = JSONObject().put("request", request).put("context", request)
            val invalidInnerValue = if (plan === walk) {
                patch("behavior" to walkBehavior(JSONObject().put("key", "stop1").put("value", "yes")))
            } else null
            val run = replay("authored $request", case,
                listOfNotNull(plan.put("behavior", invalid).toString(), invalidInnerValue, patch("behavior" to valid)))
            run.result.getOrThrow()
            assertEquals(if (plan === walk) 2 else 1, run.rejectedPlans.size)
            assertTrue(run.rejectedPlans.all {
                it.planShape?.rule == "behavior.action_payload" && it.planPath == "\$.behavior.scenario.steps[0].value"
            })
            assertTrue(run.planRequests[1].turns.last().text.contains(if (plan === ledger) "a declared choice value"
                else "an object with a string row key and value"))
            if (plan === walk) assertTrue(run.planRequests[2].turns.last().text.contains("a Boolean input"))
            assertTrue(run.writerRequests.isEmpty())
            run.assertWriterBudgetIntact()
        }
    }

    @Test fun recordedTimerPlansStopAtTheThreeRepairLimit() = runBlocking {
        for ((id, rule, stepPath) in listOf(
            Triple("fresh:cooking-2", "behavior.timer_count", "steps[26].count"),
            Triple("compose:tea", "behavior.event_limit", "steps[21]"),
        )) {
            val case = cases.getJSONObject(id)
            val run = replay(id, case, replies(case, CloudGenerationStage.PLAN))
            assertEquals("widget_plan", (run.result.exceptionOrNull() as? CloudFailure)?.code)
            assertEquals(rule, run.rejectedPlans.last().planShape?.rule)
            assertEquals("\$.behavior.scenario.$stepPath", run.rejectedPlans.last().planPath)
            assertEquals(listOf(1, 2, 3), run.repairs.map { it.repairNumber })
            assertTrue(run.repairs.all { it.providerStage == CloudGenerationStage.PLAN })
            assertNull(run.session.claimGenerationRepair(CloudGenerationStage.PLAN))
            assertTrue(run.writerRequests.isEmpty())
            run.assertWriterBudgetIntact()
        }
    }

    @Test fun timerBatchAndEventBoundaryUseTheRealChecker() = runBlocking {
        val request = "Make a local timer that counts delivered ticks."
        val case = JSONObject().put("request", request).put("context", request)
        for ((counts, allowed, rule) in listOf(
            Triple(listOf(25), listOf(24), "behavior.timer_count"),
            Triple(listOf(24, 24, 12), listOf(24, 24, 11), "behavior.event_limit"),
        )) {
            fun plan(batches: List<Int>): JSONObject {
                val steps = JSONArray()
                for (count in batches) steps.put(JSONObject().put("op", "timers").put("slot", "t").put("count", count))
                steps.put(assertion("v", "equals", batches.sum()))
                return JSONObject("""{"wholeScreen":true,"language":"en","behavior":{"status":"checkable",
                    "scenario":{"slots":{"t":{"kind":"timer"},"v":{"kind":"value"}}}}}""")
                    .apply { scenario(this).put("steps", steps) }
            }
            val definition = """{"formatVersion":3,"title":"Timer","description":"Count ticks","capabilities":[],
                "stateSchema":{"ticks":"number"},"initialState":{"ticks":0},
                "ui":{"type":"column","children":[{"type":"metric","text":"Ticks","bind":"ticks"}]},
                "behaviorBindings":{"t":"tick","v":[0]},
                "source":"const s=input.state; if(input.event.type==='open') return {state:s,timers:[{id:'tick',intervalMs:1000}]}; if(input.event.type==='timer') s.ticks++; return {state:s};"}"""
            val run = replay(rule, case, listOf(plan(counts).toString(), patch("behavior" to plan(allowed).getJSONObject("behavior"))),
                listOf(definition))
            run.result.getOrThrow()
            assertEquals(rule, run.rejectedPlans.single().planShape?.rule)
            val perBatch = counts.size == 1
            assertEquals(if (perBatch) "\$.behavior.scenario.steps[0].count" else "\$.behavior.scenario.steps[2]",
                run.rejectedPlans.single().planPath)
            assertTrue(run.planRequests.last().turns.last().text.contains(if (perBatch) "1 to 24 timer deliveries"
                else "64 total events including 5 reserved for the host (this step reaches 65)"))
            assertEquals(1, run.writerRequests.size)
            run.assertWriterBudgetIntact()
        }
    }

    @Test fun clockAndStepLimitsRejectTheFirstExcessStep() = runBlocking {
        val request = "Make a native stopwatch."
        val case = JSONObject().put("request", request).put("context", request)
        val plan = JSONObject("""{"title":"Stopwatch","description":"","language":"en","inputs":[],"constants":[],
            "needs":[{"id":"clock","type":"stopwatch","label":"Elapsed","description":"Measure elapsed seconds."}],"questions":[]}""")
        fun advance(milliseconds: Int) = JSONObject().put("op", "clock").put("slot", "elapsed").put("milliseconds", milliseconds)
        val slots = JSONObject("""{"start":{"kind":"action","part":"clock","member":"start"},
            "elapsed":{"kind":"value","part":"clock","member":"value"}}""")
        fun behavior(steps: JSONArray) = JSONObject().put("status", "checkable")
            .put("scenario", JSONObject().put("slots", slots).put("steps", steps))
        val initial = JSONArray().put(action("start")).put(advance(86_400_000)).put(advance(1)).put(assertion("elapsed", "equals", 86_400))
        val tooMany = JSONArray().put(action("start")).put(advance(86_400_000))
        repeat(63) { tooMany.put(assertion("elapsed", "equals", 86_400)) }
        val valid = JSONArray(tooMany.toString()).apply { remove(64) }
        val run = replay("authored clock and step limits", case,
            listOf(plan.put("behavior", behavior(initial)).toString(), patch("behavior" to behavior(tooMany)), patch("behavior" to behavior(valid))))
        run.result.getOrThrow()
        assertEquals(listOf("behavior.clock_limit", "behavior.steps_count"), run.rejectedPlans.map { it.planShape?.rule })
        assertEquals(listOf("\$.behavior.scenario.steps[2].milliseconds", "\$.behavior.scenario.steps[64]"),
            run.rejectedPlans.map { it.planPath })
        assertTrue(run.planRequests[1].turns.last().text.contains("86400000 milliseconds (24 hours)"))
        assertTrue(run.planRequests[2].turns.last().text.contains("at most 64 steps"))
        assertTrue(run.writerRequests.isEmpty())
        run.assertWriterBudgetIntact()
    }

    @Test fun frameLimitsUseTheScenePlanPath() = runBlocking {
        val request = "Make an Arkanoid game with a moving ball."
        val case = JSONObject().put("request", request).put("context", request)
        val scene = fixtureScene("immersive", moving = true, start = true)
        val plan = JSONObject("""{"wholeScreen":true,"language":"en"}""").put("scene", scene)
        val workflow = scene.getJSONObject("checks").getJSONObject("scenario")
        val steps = workflow.getJSONArray("steps")
        steps.getJSONObject(1).put("count", 25)
        val badBatch = plan.toString()
        steps.getJSONObject(1).put("count", 24)
        val valid = patch("scene" to scene)
        workflow.getJSONObject("slots").put("score", JSONObject("""{"kind":"value","valueType":"number"}"""))
        steps.put(JSONObject("""{"op":"assert","slot":"score","test":"nonempty"}"""))
        val badType = patch("scene" to scene)
        workflow.getJSONObject("slots").remove("score")
        steps.remove(2)
        repeat(49) { steps.put(JSONObject("""{"op":"frames","slot":"surface","count":24}""")) }
        steps.put(JSONObject("""{"op":"frames","slot":"surface","count":1}"""))
        val writer = JSONObject(GameGenerationExample.spec()).withFixtureDrawingBinding("main", moving = true)
        val run = replay("authored scene frame limits", case, listOf(badBatch, patch("scene" to scene), badType, valid),
            listOf(writer.toString()))
        run.result.getOrThrow()
        assertEquals(listOf("behavior.frame_count", "behavior.frame_limit", "behavior.assertion_type"),
            run.rejectedPlans.map { it.planShape?.rule })
        assertEquals(listOf("\$.scene.checks.scenario.steps[1].count", "\$.scene.checks.scenario.steps[51]", "\$.scene.checks.scenario.steps[2]"),
            run.rejectedPlans.map { it.planPath })
        assertTrue(run.planRequests[1].turns.last().text.contains("1 to 24 frame deliveries"))
        assertTrue(run.planRequests[2].turns.last().text.contains("1200 total frames (this step reaches 1201)"))
        run.assertWriterBudgetIntact()
    }
}
