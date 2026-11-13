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
        val firstRejected get() = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
            .first { it.outcome == ToolGenerationOutcome.REJECTED }
        val rowMismatch get() = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
            .first { it.behaviorBinding?.rule == BehaviorFailureRule.ROW_SCOPE_MISMATCH }

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
                        throw AssertionError("$id unexpected PLAN request ${plansSeen.size + 1}: ${request.turns.last().text.take(800)}")
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

    @Test fun nativeInconsistenciesRepairBeforeAcceptingThePlan() = runBlocking {
        val fixtures = instrumentation.context.assets.open("generation-reliability/native-preflight.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val failures = mutableListOf<String>()
        for (id in fixtures.keys()) {
            val fixture = fixtures.getJSONObject(id)
            runCatching {
                val run = replay(id, fixture, listOf(fixture.getJSONObject("invalid").toString(),
                    fixture.getJSONObject("corrected").toString()))
                run.result.getOrThrow()
                assertEquals(2, run.planRequests.size)
                assertEquals(1, run.rejectedPlans.size)
                assertEquals(listOf(CloudGenerationStage.PLAN), run.repairs.map { it.providerStage })
                assertTrue(run.writerRequests.isEmpty())
                run.assertWriterBudgetIntact()
            }.onFailure { failures += "$id: ${it.message}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun recordedScalarPlansRepairBeforeWriterAndKeepTheirBudget() = runBlocking {
        for ((id, slot, step) in listOf(
            Triple("fresh:unit-3", "inches", 3),
            Triple("fresh:home-5", "covered", 2),
            Triple("fresh:beyond-1", "total", 3),
        )) {
            val case = cases.getJSONObject(id)
            val plan = candidate(case, CloudGenerationStage.PLAN).let { captured ->
                if (id != "fresh:home-5") captured else {
                    val workflow = scenario(captured)
                    val slots = workflow.getJSONObject("slots")
                    for (slotId in slots.keys()) slots.getJSONObject(slotId).apply { remove("part"); remove("member") }
                    JSONObject().put("wholeScreen", true).put("language", "en")
                        .put("behavior", captured.getJSONObject("behavior"))
                }
            }
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
        val request = "Make a drawing with a timer that counts delivered ticks."
        val case = JSONObject().put("request", request).put("context", request)
        for ((counts, allowed, rule) in listOf(
            Triple(listOf(25), listOf(24), "behavior.timer_count"),
            Triple(listOf(24, 24, 12), listOf(24, 24, 11), "behavior.event_limit"),
        )) {
            fun plan(batches: List<Int>): JSONObject {
                val scene = fixtureScene("inline")
                val workflow = scene.getJSONObject("checks").getJSONObject("scenario")
                val slots = workflow.getJSONObject("slots")
                slots.put("t", JSONObject().put("kind", "timer"))
                slots.put("v", JSONObject().put("kind", "value"))
                val steps = workflow.getJSONArray("steps")
                for (count in batches) steps.put(JSONObject().put("op", "timers").put("slot", "t").put("count", count))
                steps.put(assertion("v", "equals", batches.sum()))
                return JSONObject("""{"wholeScreen":true,"language":"en"}""").put("scene", scene)
            }
            val definition = """{"formatVersion":3,"title":"Timer","description":"Count ticks","capabilities":[],
                "stateSchema":{"ticks":"number"},"initialState":{"ticks":0},
                "surfaces":[{"id":"board","width":64,"height":64}],
                "ui":{"type":"column","children":[{"type":"metric","text":"Ticks","bind":"ticks"},{"type":"canvas","text":"Drawing","surfaces":["board"]}]},
                "behaviorBindings":{"t":"tick","v":[0],"drawing":{"surface":"board","path":[]}},
                "source":"const s=input.state;if(input.event.type==='timer') s.ticks++;return {state:s,timers:[{id:'tick',intervalMs:1000}],surfaces:{board:{commands:[{type:'circle',x:32,y:32,radius:4,color:'accent'}]}}};"}"""
            val run = replay(rule, case, listOf(plan(counts).toString(), patch("scene" to plan(allowed).getJSONObject("scene"))),
                listOf(definition))
            run.result.getOrThrow()
            assertEquals(rule, run.rejectedPlans.single().planShape?.rule)
            val perBatch = counts.size == 1
            assertEquals(if (perBatch) "\$.scene.checks.scenario.steps[1].count" else "\$.scene.checks.scenario.steps[3]",
                run.rejectedPlans.single().planPath)
            assertTrue(run.planRequests.last().turns.last().text.contains(if (perBatch) "1 to 24 timer deliveries"
                else "64 total events including 5 reserved for the host (this step reaches 65)"))
            assertEquals(1, run.writerRequests.size)
            run.assertWriterBudgetIntact()
        }
    }

    @Test fun anUnscheduledStopwatchPlanRepairsToANativeClock() = runBlocking {
        val recorded = instrumentation.context.assets.open("generation-reliability/unscheduled-stopwatch.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val fixture = instrumentation.context.assets.open("generation-reliability/native-preflight.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("compose:stopwatch") }
        val native = fixture.getJSONObject("invalid")
            .put("behavior", fixture.getJSONObject("corrected").getJSONObject("fields").getJSONObject("behavior"))
        val corrected = JSONObject().put("fields", native).put("removeFields", JSONArray().put("wholeScreen"))
        val run = replay("recorded unscheduled stopwatch", fixture,
            listOf(recorded.getJSONObject("plan").toString(), corrected.toString()))
        run.result.getOrThrow()
        val rejected = run.rejectedPlans.single()
        assertEquals("behavior.timer_host", rejected.planShape?.rule)
        assertEquals("\$.behavior.scenario.steps[3]", rejected.planPath)
        assertTrue(run.planRequests.last().turns.last().text.contains("native countdown or stopwatch"))
        assertTrue(run.writerRequests.isEmpty())
        run.assertWriterBudgetIntact()

        val spec = ToolSpec.parsePersisted(recorded.getJSONObject("spec").toString())
        val sandbox = SandboxClient(instrumentation.targetContext)
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession,
                emit: suspend (CloudEvent) -> Unit) = error("No provider in a smoke replay")
        }
        val smoke = ToolGenerationSmoke(sandbox, spec, "stopwatch-replay", 1, spec.initialState,
            checkActive = {}, generation = ToolGenerationService(provider, sandbox))
        val failure = runCatching { smoke.run() }.exceptionOrNull() as ToolSmokeFailure
        assertEquals("\$.timers[0]", failure.path)
        assertEquals("widget_timer_host", (failure.cause as CloudFailure).code)
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
        val misplacedType = JSONArray().put(action("start")).put(advance(86_400_000))
            .put(assertion("elapsed", "equals", 86_400).put("valueType", "number"))
        val run = replay("authored clock and step limits", case,
            listOf(plan.put("behavior", behavior(initial)).toString(), patch("behavior" to behavior(tooMany)),
                patch("behavior" to behavior(misplacedType)), patch("behavior" to behavior(valid))))
        run.result.getOrThrow()
        assertEquals(listOf("behavior.clock_limit", "behavior.steps_count", "plan.fields"), run.rejectedPlans.map { it.planShape?.rule })
        assertEquals(listOf("\$.behavior.scenario.steps[2].milliseconds", "\$.behavior.scenario.steps[64]", "\$.behavior.scenario.steps[2]"),
            run.rejectedPlans.map { it.planPath })
        assertTrue(run.planRequests[1].turns.last().text.contains("86400000 milliseconds (24 hours)"))
        assertTrue(run.planRequests[2].turns.last().text.contains("at most 64 steps"))
        assertTrue(run.planRequests[3].turns.last().text.contains("required keys [op, slot, test, value]"))
        assertEquals("object", run.rejectedPlans.last().planShape?.observedType)
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
    private val rowCase by lazy {
        instrumentation.context.assets.open("generation-reliability/row-checkbox-recorded.json")
            .bufferedReader().use { JSONObject(it.readText()) }.apply { put("context", getString("request")) }
    }

    private fun rowPlan(declared: Boolean = true): JSONObject = JSONObject(rowCase.getJSONObject("plan").toString()).apply {
        if (declared) {
            scenario(this).getJSONObject("slots").getJSONObject("toggle").put("rowValueType", "boolean")
            scenario(this).getJSONArray("steps").getJSONObject(4).put("value", rowPayload())
        }
    }

    private fun rowPayload(key: Any = "t1", value: Any = true) = JSONObject().put("key", key).put("value", value)

    private fun rowWriter(call: Int = 4): JSONObject {
        val responses = rowCase.getJSONArray("candidates")
        val repair = GenerationRepair.writer(ToolSpec.generatedFields + "behaviorBindings", null)
        var text = responses.getJSONObject(0).getJSONObject("response").toString()
        repair.retain(text)
        for (index in 1..call - 3) text = repair.apply(responses.getJSONObject(index).getJSONObject("response").toString()).text
        return JSONObject(text)
    }

    private fun repeatedWriter(writer: JSONObject) = listOf(writer.toString()) + List(3) { patch("title" to "Row replay repair ${it + 1}") }

    @Test fun recordedRowCandidatesExposePlanScopeAndRetainDistinctOtherFailures() = runBlocking {
        for (call in 3..6) {
            val run = replay("recorded row call $call", rowCase, listOf(rowPlan(false).toString()), repeatedWriter(rowWriter(call)))
            val rejected = run.events.filterIsInstance<ToolGenerationTraceEvent.Attempt>().last { it.outcome == ToolGenerationOutcome.REJECTED }
            when (call) {
                3, 4 -> {
                    assertEquals("widget_plan", (run.result.exceptionOrNull() as? CloudFailure)?.code)
                    assertEquals(BehaviorFailureRule.ROW_SCOPE_MISMATCH, rejected.behaviorBinding?.rule)
                }
                5 -> {
                    assertEquals(5, rejected.behaviorBinding?.step)
                    assertEquals(BehaviorFailureRule.ACTION_UNAVAILABLE, rejected.behaviorBinding?.rule)
                }
                6 -> assertEquals("component_binding_schema_type", rejected.validationRule)
            }
            assertEquals(if (call < 5) 1 else 4, run.writerRequests.size)
            assertEquals(if (call < 5) emptyList() else listOf(1, 2, 3), run.repairs.map { it.repairNumber })
        }
    }

    @Test fun malformedDeclaredRowPayloadRepairsOnlyPlan() = runBlocking {
        val malformed = listOf<Any?>(null, JSONObject.NULL, true, "true", JSONObject(),
            JSONObject().put("key", "t1"), JSONObject().put("value", true), rowPayload().put("extra", false),
            rowPayload(""), rowPayload(" "), rowPayload("a".repeat(129)), rowPayload(1),
            rowPayload(value = JSONObject.NULL), rowPayload(value = "true"), rowPayload(value = 1))
        for ((index, value) in malformed.withIndex()) {
            val plan = rowPlan()
            val step = scenario(plan).getJSONArray("steps").getJSONObject(4)
            if (value == null) step.remove("value") else step.put("value", value)
            val run = replay("row payload $index", rowCase, listOf(plan.toString(),
                patch("behavior" to rowPlan().getJSONObject("behavior"))), listOf(rowWriter().toString()))
            run.result.getOrThrow()
            assertEquals(listOf("behavior.action_payload"), run.rejectedPlans.map { it.planShape?.rule })
            assertEquals(listOf("\$.behavior.scenario.steps[4].value"), run.rejectedPlans.map { it.planPath })
            assertEquals(listOf(CloudGenerationStage.PLAN), run.repairs.map { it.providerStage })
            run.assertWriterBudgetIntact()
        }
    }

    @Test fun invalidRowDeclarationRepairsOnlyPlanAndKeepsNumericPaths() = runBlocking {
        for ((slot, type) in listOf("toggle" to "number", "toggle" to true, "toggle" to JSONObject.NULL, "remaining" to "boolean")) {
            val plan = rowPlan()
            scenario(plan).getJSONObject("slots").getJSONObject(slot).put("rowValueType", type)
            val run = replay("row declaration $slot $type", rowCase, listOf(plan.toString(),
                patch("behavior" to rowPlan().getJSONObject("behavior"))), listOf(rowWriter().toString()))
            run.result.getOrThrow()
            assertEquals("behavior.slot_row_value_type", run.rejectedPlans.single().planShape?.rule)
            assertEquals("\$.behavior.scenario.slots[${if (slot == "toggle") 2 else 3}].rowValueType", run.rejectedPlans.single().planPath)
            run.assertWriterBudgetIntact()
        }
    }

    @Test fun declaredRowRequiresAnActionIdForAnActualRowCheckbox() = runBlocking {
        val correct = rowWriter()
        val global = rowWriter(5)
        val rowButton = rowWriter().apply {
            getJSONObject("behaviorBindings").put("toggle", "remove")
        }
        val wrongType = rowWriter().apply {
            getJSONObject("ui").getJSONArray("children").getJSONObject(2).getJSONObject("rowTemplate")
                .getJSONArray("children").getJSONObject(0).put("type", "toggle")
        }
        val uiPath = rowWriter().apply { getJSONObject("behaviorBindings").put("toggle", JSONArray().put(2)) }
        for ((index, writer) in listOf(global, rowButton, wrongType, uiPath).withIndex()) {
            val run = replay("wrong row binding $index", rowCase, listOf(rowPlan().toString()),
                listOf(writer.toString(), patch("ui" to correct.getJSONObject("ui"), "behaviorBindings" to correct.getJSONObject("behaviorBindings"))))
            run.result.getOrThrow()
            assertTrue(run.rejectedPlans.isEmpty())
            assertEquals(BehaviorFailureRule.BINDING_SHAPE, run.firstRejected.behaviorBinding?.rule)
            assertEquals(5, run.firstRejected.behaviorBinding?.step)
            assertEquals(if (index == 3) "ui" else "id", run.firstRejected.behaviorBinding?.bindingKind)
            assertEquals(listOf("checkbox", "button", "toggle", null)[index], run.firstRejected.behaviorBinding?.nodeKind)
            assertEquals(listOf(CloudGenerationStage.COMPLETE), run.repairs.map { it.providerStage })
            assertEquals(1, run.session.claimGenerationRepair(CloudGenerationStage.PLAN))
            assertEquals(2, run.session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
        }
    }

    @Test fun undeclaredRowScopeStopsBeforeWriterRepairAndPreservesItsRemainingBudget() = runBlocking {
        for (writer in listOf(rowWriter(), rowWriter().put("source", "throw new Error('broken writer');"))) {
            val run = replay("late row scope", rowCase, listOf(rowPlan(false).toString()), listOf(writer.toString()))
            assertEquals("widget_plan", (run.result.exceptionOrNull() as? CloudFailure)?.code)
            assertEquals(1, run.writerRequests.size)
            assertTrue(run.repairs.isEmpty())
            val diagnostic = run.events.filterIsInstance<ToolGenerationTraceEvent.Attempt>().last().behaviorBinding!!.report()
            assertEquals("plan", diagnostic.getString("contractOwner"))
            assertEquals("writer_binding", diagnostic.getString("discoveryStage"))
            assertEquals("row_scope_mismatch", diagnostic.getString("rule"))
            assertEquals(3, diagnostic.getInt("slot"))
            assertEquals("id", diagnostic.getString("bindingKind"))
            assertEquals("checkbox", diagnostic.getString("nodeKind"))
            assertFalse(diagnostic.toString().contains("toggle"))
            assertEquals(1, run.session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
        }
    }

    @Test fun lateRowScopeAfterInputRepairPreservesSpentWriterBudget() = runBlocking {
        val writer = rowWriter(3)
        writer.getJSONObject("ui").getJSONArray("children").getJSONObject(2).remove("rowTemplate")
        val first = writer.toString()
        val run = replay("late row after input repair", rowCase, listOf(rowPlan(false).toString()),
            listOf(first, JSONObject().put("fields", JSONObject().put("ui", rowWriter().getJSONObject("ui")))
                .put("sourceEdits", rowCase.getJSONArray("candidates").getJSONObject(1).getJSONObject("response").getJSONArray("sourceEdits")).toString()))
        assertEquals("widget_plan", (run.result.exceptionOrNull() as? CloudFailure)?.code)
        assertEquals(BehaviorFailureRule.ROW_SCOPE_MISMATCH, run.rowMismatch.behaviorBinding?.rule)
        assertEquals(listOf(CloudGenerationStage.COMPLETE), run.repairs.map { it.providerStage })
        assertEquals(2, run.session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
        assertEquals(1, run.session.claimGenerationRepair(CloudGenerationStage.PLAN))
    }

    @Test fun undeclaredKeyedBooleanGetsPlanSlotFeedback() = runBlocking {
        val invalid = rowPlan(false)
        scenario(invalid).getJSONArray("steps").getJSONObject(4).put("value", rowPayload())
        val run = replay("missing row slot type", rowCase,
            listOf(invalid.toString(), patch("behavior" to rowPlan().getJSONObject("behavior"))), listOf(rowWriter().toString()))
        run.result.getOrThrow()
        val rejected = run.rejectedPlans.single()
        assertEquals("behavior.slot_row_value_type", rejected.planShape?.rule)
        assertTrue(rejected.planPath.orEmpty().endsWith(".rowValueType"))
        run.assertWriterBudgetIntact()
    }

    @Test fun writerRepairsTheMissingStableKeyAndDeletesTheRightRowAfterReorder() = runBlocking {
        val plan = """{"wholeScreen":true,"language":"en","behavior":{"status":"checkable","scenario":{
            "slots":{"reverse":{"kind":"action"},"delete":{"kind":"action"},"undo":{"kind":"action"},
            "clear":{"kind":"action"},"first":{"kind":"value"},"rows":{"kind":"collection"}},
            "steps":[{"op":"action","slot":"reverse"},{"op":"assert","slot":"first","test":"equals","value":"Erase"},
            {"op":"action","slot":"delete","value":"row-erase"},{"op":"assert","slot":"rows","test":"equals","value":1},
            {"op":"assert","slot":"first","test":"equals","value":"Keep"},{"op":"action","slot":"undo"},
            {"op":"assert","slot":"rows","test":"equals","value":2},{"op":"assert","slot":"first","test":"equals","value":"Erase"},
            {"op":"action","slot":"clear"},{"op":"assert","slot":"rows","test":"equals","value":0}]}}}"""
        val state = JSONObject("""{"rows":[{"id":"row-keep","label":"Keep"},{"id":"row-erase","label":"Erase"}],"previous":[],"first":"Keep"}""")
        val writer = JSONObject("""{"formatVersion":3,"title":"Notes","description":"Manage notes","capabilities":[],
            "stateSchema":{"rows":{"type":"list","maxItems":8,"fields":{"id":"string","label":"string"}},
            "previous":{"type":"list","maxItems":8,"fields":{"id":"string","label":"string"}},"first":"string"},
            "initialState":{},"ui":{"type":"column","children":[{"type":"text","text":"First note","bind":"first"},
            {"type":"list","text":"Notes","bind":"rows","columns":[{"text":"Note","field":"label"}],
            "rowActions":[{"text":"Delete","action":"delete"}]},
            {"type":"button","text":"Reverse","action":"reverse"},{"type":"button","text":"Undo","action":"undo"},
            {"type":"button","text":"Clear","action":"clear"}]},"behaviorBindings":{"reverse":"reverse","delete":"delete",
            "undo":"undo","clear":"clear","first":[0],"rows":[1]}}""")
            .put("initialState", JSONObject(state.toString().replace("row-erase", "wrong-key")))
            .put("source", """const s = input.state, e = input.event;
                if (e.type !== 'action') return {state:s};
                if (e.actionId === 'reverse') s.rows.reverse();
                if (e.actionId === 'delete') {
                  s.previous = s.rows;
                  s.rows = s.rows.filter(row => row.id !== e.value);
                }
                if (e.actionId === 'undo' && s.previous.length) { s.rows = s.previous; s.previous = []; }
                if (e.actionId === 'clear') s.rows = [];
                s.first = s.rows.length ? s.rows[0].label : '';
                return {state:s};""")
        val request = "Create a notes list with reverse ordering, addressed deletion, undo, and clear."
        val fixture = JSONObject().put("request", request).put("context", request)
        val run = replay("stable row deletion", fixture, listOf(plan),
            listOf(writer.toString(), patch("initialState" to state)))
        run.result.getOrThrow()
        assertEquals(listOf(CloudGenerationStage.COMPLETE), run.repairs.map { it.providerStage })
        assertTrue(run.writerRequests[1].turns.last().text.contains("stable row key"))
    }

    @Test fun currentRowsResolveAtTheActionStepAndUnavailableRowsKeepWriterOwnership() = runBlocking {
        replay("row created earlier", rowCase, listOf(rowPlan().toString()), listOf(rowWriter().toString())).result.getOrThrow()
        for (condition in listOf("missing", "visible", "enabled")) {
            val wrong = rowWriter()
            if (condition == "missing") {
                wrong.put("source", wrong.getString("source").replace("id: 't' + s.nextId", "id: 'other' + s.nextId"))
            } else {
                wrong.getJSONObject("ui").getJSONArray("children").getJSONObject(2).getJSONObject("rowTemplate").put(condition, false)
            }
            val run = replay("unavailable row $condition", rowCase, listOf(rowPlan().toString()), repeatedWriter(wrong))
            assertTrue(run.result.isFailure)
            assertTrue(run.rejectedPlans.isEmpty())
            assertEquals(BehaviorFailureRule.ACTION_UNAVAILABLE, run.firstRejected.behaviorBinding?.rule)
            assertEquals(5, run.firstRejected.behaviorBinding?.step)
            assertEquals("id", run.firstRejected.behaviorBinding?.bindingKind)
            assertEquals("checkbox", run.firstRejected.behaviorBinding?.nodeKind)
            assertEquals(4, run.writerRequests.size)
            assertEquals(listOf(1, 2, 3), run.repairs.map { it.repairNumber })
            assertTrue(run.repairs.all { it.providerStage == CloudGenerationStage.COMPLETE })
            assertNull(run.session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
            assertEquals(1, run.session.claimGenerationRepair(CloudGenerationStage.PLAN))
        }
    }

    @Test fun malformedRowsStopAtTheExistingPlanRepairBoundary() = runBlocking {
        val plan = rowPlan().apply { scenario(this).getJSONArray("steps").getJSONObject(4).remove("value") }
        val run = replay("row plan boundary", rowCase,
            listOf(plan.toString()) + List(3) { index ->
                scenario(plan).getJSONArray("steps").getJSONObject(4).put("value", "invalid$index")
                patch("behavior" to plan.getJSONObject("behavior"))
            })
        assertEquals("widget_plan", (run.result.exceptionOrNull() as? CloudFailure)?.code)
        assertEquals(4, run.rejectedPlans.size)
        assertEquals(listOf(1, 2, 3), run.repairs.map { it.repairNumber })
        assertTrue(run.writerRequests.isEmpty())
        assertNull(run.session.claimGenerationRepair(CloudGenerationStage.PLAN))
        run.assertWriterBudgetIntact()
    }

    @Test fun rowDeclarationRejectsNativeAndThreeSceneSlots() {
        for (three in listOf(false, true)) {
            val behavior = rowPlan().getJSONObject("behavior")
            if (!three) behavior.getJSONObject("scenario").getJSONObject("slots").getJSONObject("toggle")
                .put("part", "native").put("member", "visit")
            val failure = runCatching {
                com.alpha.assistant.tools.generation.GenerationBehavior.parse(behavior, setOf("native"), threeScene = three)
            }.exceptionOrNull() as? com.alpha.assistant.tools.generation.PartsContractFailure
            assertEquals("behavior.slot_row_value_type", failure?.shape?.rule)
        }
    }

    @Test fun rowScopeFirstRevealedDuringExecutionIsATerminalPlanMismatch() = runBlocking {
        val writer = rowWriter()
        val dynamicUi = writer.getJSONObject("ui").toString()
        val source = writer.getString("source").replace(
            "return { state: Object.assign({}, s, { items: items, newItem: '', nextId: s.nextId + 1, remaining: remainingOf(items) }) };",
            "return { ui: $dynamicUi, state: Object.assign({}, s, { items: items, newItem: '', nextId: s.nextId + 1, remaining: remainingOf(items) }) };")
        assertTrue(source != writer.getString("source"))
        writer.put("source", source)
        writer.getJSONObject("ui").getJSONArray("children").getJSONObject(2).remove("rowTemplate")
        val run = replay("runtime row scope", rowCase, listOf(rowPlan(false).toString()), listOf(writer.toString()))
        assertEquals("widget_plan", (run.result.exceptionOrNull() as? CloudFailure)?.code)
        assertEquals(1, run.writerRequests.size)
        assertTrue(run.repairs.isEmpty())
        val diagnostic = run.events.filterIsInstance<ToolGenerationTraceEvent.Attempt>().last().behaviorBinding!!.report()
        assertEquals("behavior_execution", diagnostic.getString("discoveryStage"))
        assertEquals("row_scope_mismatch", diagnostic.getString("rule"))
        assertEquals("plan", diagnostic.getString("contractOwner"))
        assertEquals("id", diagnostic.getString("bindingKind"))
        assertEquals("checkbox", diagnostic.getString("nodeKind"))
        assertEquals(3, diagnostic.getInt("slot"))
        assertFalse(diagnostic.toString().contains("toggle"))
        assertEquals(5, diagnostic.getInt("step"))
        assertEquals(1, run.session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
    }

    @Test fun lateScopeMismatchSurvivesAnExhaustedWriterBudget() = runBlocking {
        val writer = rowWriter(3)
        writer.getJSONObject("ui").getJSONArray("children").getJSONObject(2).remove("rowTemplate")
        val inputRepair = JSONObject().put("fields", JSONObject().put("ui", rowWriter().getJSONObject("ui")))
            .put("sourceEdits", rowCase.getJSONArray("candidates").getJSONObject(1).getJSONObject("response").getJSONArray("sourceEdits"))
        val run = replay("late row exhausted budget", rowCase, listOf(rowPlan(false).toString()),
            listOf(writer.toString(), patch("title" to "Input repair one"), patch("title" to "Input repair two"), inputRepair.toString()))
        assertEquals("widget_plan", (run.result.exceptionOrNull() as? CloudFailure)?.code)
        assertEquals(BehaviorFailureRule.ROW_SCOPE_MISMATCH, run.rowMismatch.behaviorBinding?.rule)
        assertEquals(listOf(1, 2, 3), run.repairs.map { it.repairNumber })
        assertEquals(4, run.writerRequests.size)
        assertNull(run.session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
        assertEquals(1, run.session.claimGenerationRepair(CloudGenerationStage.PLAN))
    }

    private fun dynamicRowWriter(event: String): JSONObject = rowWriter().apply {
        val dynamicUi = getJSONObject("ui").toString()
        val source = getString("source")
        put("source", if (event == "open") {
            "if(input.event.type==='open') return {state:input.state,ui:$dynamicUi};\n$source"
        } else source.replace(
            "return { state: Object.assign({}, s, { items: items, newItem: '', nextId: s.nextId + 1, remaining: remainingOf(items) }) };",
            "return { ui: $dynamicUi, state: Object.assign({}, s, { items: items, newItem: '', nextId: s.nextId + 1, remaining: remainingOf(items) }) };"))
        getJSONObject("ui").getJSONArray("children").getJSONObject(2).remove("rowTemplate")
    }

    @Test fun declaredRowsCanAppearOnOpenOrAnEarlierScenarioAction() = runBlocking {
        for (event in listOf("open", "add")) {
            val run = replay("dynamic declared row $event", rowCase, listOf(rowPlan().toString()), listOf(dynamicRowWriter(event).toString()))
            run.result.getOrThrow()
            assertTrue(run.repairs.isEmpty())
            assertEquals(1, run.writerRequests.size)
        }
    }

    @Test fun declaredRowAvailabilityRepairsPlanBeforeWriter() = runBlocking {
        val plan = rowPlan()
        scenario(plan).getJSONArray("steps").put(4, JSONObject().put("op", "availability").put("slot", "toggle").put("value", false))
        val run = replay("declared row availability", rowCase,
            listOf(plan.toString(), patch("behavior" to rowPlan().getJSONObject("behavior"))), listOf(rowWriter().toString()))
        run.result.getOrThrow()
        assertEquals("behavior.row_availability", run.rejectedPlans.single().planShape?.rule)
        assertEquals("\$.behavior.scenario.steps[4]", run.rejectedPlans.single().planPath)
        run.assertWriterBudgetIntact()
    }

    @Test fun nonCheckboxRowBindingsAndAvailabilityKeepCompleteOwnership() = runBlocking {
        for (kind in listOf("button", "toggle", "input", "availability")) {
            val plan = rowPlan(false)
            val writer = rowWriter()
            when (kind) {
                "button" -> writer.getJSONObject("behaviorBindings").put("toggle", "remove")
                "toggle", "input" -> {
                    val control = writer.getJSONObject("ui").getJSONArray("children").getJSONObject(2)
                        .getJSONObject("rowTemplate").getJSONArray("children").getJSONObject(0)
                    control.put("type", kind)
                    if (kind == "input") control.put("bind", "text")
                    scenario(plan).getJSONArray("steps").getJSONObject(4).put("value", if (kind == "input") "Done" else true)
                }
                else -> scenario(plan).getJSONArray("steps").put(4,
                    JSONObject().put("op", "availability").put("slot", "toggle").put("value", false))
            }
            val run = replay("legacy row $kind", rowCase, listOf(plan.toString()), repeatedWriter(writer))
            assertEquals("widget_behavior", (run.result.exceptionOrNull() as? CloudFailure)?.code)
            assertEquals(if (kind == "availability") BehaviorFailureRule.TARGET_MISSING else BehaviorFailureRule.ACTION_UNAVAILABLE,
                run.firstRejected.behaviorBinding?.rule)
            assertEquals(5, run.firstRejected.behaviorBinding?.step)
            assertEquals("id", run.firstRejected.behaviorBinding?.bindingKind)
            assertEquals(kind.takeUnless { it == "availability" }, run.firstRejected.behaviorBinding?.nodeKind)
            assertTrue(run.repairs.all { it.providerStage == CloudGenerationStage.COMPLETE })
            assertEquals(3, run.repairs.size)
        }
    }

    @Test fun knownPlanMismatchPrecedesOtherDeclaredBindingFailures() = runBlocking {
        for (badFirst in listOf(true, false)) {
            val plan = rowPlan(false)
            val workflow = scenario(plan)
            val oldSlots = workflow.getJSONObject("slots")
            val bad = JSONObject("""{"kind":"action","rowValueType":"boolean"}""")
            val slots = JSONObject()
            if (badFirst) slots.put("bad", bad)
            ToolValueSchema.keys(oldSlots).forEach { slots.put(it, oldSlots.get(it)) }
            if (!badFirst) slots.put("bad", bad)
            workflow.put("slots", slots)
            val steps = JSONArray().put(action("bad", rowPayload()))
            val oldSteps = workflow.getJSONArray("steps")
            repeat(oldSteps.length()) { steps.put(oldSteps.get(it)) }
            workflow.put("steps", steps)
            val writer = rowWriter().apply { getJSONObject("behaviorBindings").put("bad", "add") }
            val run = replay("scope priority $badFirst", rowCase, listOf(plan.toString()), listOf(writer.toString()))
            assertEquals("widget_plan", (run.result.exceptionOrNull() as? CloudFailure)?.code)
            assertEquals(BehaviorFailureRule.ROW_SCOPE_MISMATCH, run.firstRejected.behaviorBinding?.rule)
            assertEquals(6, run.firstRejected.behaviorBinding?.step)
            assertTrue(run.repairs.isEmpty())
            assertEquals(1, run.session.claimGenerationRepair(CloudGenerationStage.COMPLETE))
        }
    }

    @Test fun unrelatedBadBindingsStillFollowSmokeValidation() = runBlocking {
        val writer = rowWriter().apply {
            getJSONObject("ui").getJSONArray("children").getJSONObject(2).remove("rowTemplate")
            getJSONObject("behaviorBindings").remove("remaining")
            put("source", "throw new Error('unrelated source failure');")
        }
        val run = replay("non-row validation order", rowCase, listOf(rowPlan(false).toString()), repeatedWriter(writer))
        assertEquals("widget_preview", run.firstRejected.failureCode)
        assertEquals(ToolGenerationStage.SMOKE, run.firstRejected.stage)
        assertEquals("preview", run.firstRejected.smoke?.getString("event"))
        assertEquals(3, run.repairs.size)
    }

    @Test fun mixedNativeAndWriterRowMismatchPreservesPlanCodeAndBudget() = runBlocking {
        val plan = JSONObject("""{"title":"Counter and tasks","description":"Count and check tasks","language":"en",
            "inputs":[],"constants":[],"questions":[],"needs":[
                {"id":"count","type":"counter","label":"Count","description":"Increase and reset"},
                {"id":"custom","type":"other","label":"Tasks","description":"Add and check tasks","reads":[],"writes":[]}]}""")
            .put("behavior", rowPlan(false).getJSONObject("behavior"))
        var text = rowWriter().toString()
        for (id in listOf("newItem", "nextId", "remaining", "items", "add", "toggle", "remove", "clearDone")) {
            text = text.replace(Regex("\\b$id\\b"), "fill_$id")
        }
        val fill = JSONObject(text).apply {
            remove("formatVersion")
            val inner = getJSONObject("ui")
            put("ui", JSONObject().put("type", "column").put("children", JSONArray().put(inner)))
            put("behaviorBindings", JSONObject().put("newItem", "fill_newItem").put("add", "fill_add")
                .put("toggle", "fill_toggle").put("remaining", JSONArray().put(1).put(0)).put("list", JSONArray().put(1).put(2)))
        }
        val writer = JSONObject().put("holes", JSONArray().put("custom")).put("fill", fill)
        val run = replay("mixed row scope", rowCase, listOf(plan.toString()), listOf(writer.toString()))
        assertEquals("widget_plan", (run.result.exceptionOrNull() as? CloudFailure)?.code)
        assertEquals(BehaviorFailureRule.ROW_SCOPE_MISMATCH, run.firstRejected.behaviorBinding?.rule)
        assertEquals(ToolGenerationRoute.MIXED, run.firstRejected.behaviorBinding?.route)
        assertTrue(run.repairs.isEmpty())
        assertEquals(1, run.session.claimGenerationRepair(CloudGenerationStage.COMPLETE))

        val unrelated = JSONObject(writer.toString()).apply {
            val body = getJSONObject("fill")
            body.getJSONObject("ui").getJSONArray("children").getJSONObject(0)
                .getJSONArray("children").getJSONObject(2).remove("rowTemplate")
            body.getJSONObject("behaviorBindings").remove("remaining")
            body.put("source", "throw new Error('unrelated mixed source failure');")
        }
        val smoke = replay("mixed non-row validation order", rowCase, listOf(plan.toString()), repeatedWriter(unrelated))
        assertEquals("widget_preview", smoke.firstRejected.failureCode)
        assertEquals(ToolGenerationStage.SMOKE, smoke.firstRejected.stage)
        assertEquals("widget_incomplete", (smoke.result.exceptionOrNull() as? CloudFailure)?.code)
    }


}
