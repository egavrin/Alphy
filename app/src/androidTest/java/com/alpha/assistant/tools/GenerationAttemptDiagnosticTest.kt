package com.alpha.assistant.tools

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.*
import com.alpha.assistant.tools.generation.NeedsPlan
import com.alpha.assistant.tools.generation.GenerationBehavior
import com.alpha.assistant.tools.generation.PartsContractFailure
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Cases recorded before implementation: definition/schema/UI, saved-state incompatibility,
 * preview mutation/effects/UI, smoke script, provider/size/unknown errors, cancellation,
 * unavailable/busy runtime, correction association, observer errors, and secret disclosure.
 * Real-provider E2E cannot deterministically produce these failures.
 */
class GenerationAttemptDiagnosticTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val secret="SECRET_PRIVATE_content://private/credential"
    private fun spec(source: String="return {state:input.state};")=JSONObject()
        .put("formatVersion",3).put("title",secret).put("description",secret).put("capabilities",JSONArray())
        .put("stateSchema",JSONObject("""{"count":"number"}"""))
        .put("initialState",JSONObject("""{"count":7}"""))
        .put("ui",JSONObject("""{"type":"button","text":"Local","action":"SECRET_ACTION"}"""))
        .put("source",source).toString()
    private class Provider(val outputs: List<String>,val failure: Exception?=null,val failAt: Int=1): ChatProvider {
        var calls=0
        var planningCalls=0
        val requests=mutableListOf<CloudRequest>()
        override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend(CloudEvent)->Unit) {
            if (request.replyWithFixturePlan(emit)) {
                planningCalls++
                return
            }
            calls++;requests+=request
            if(calls==failAt)failure?.let {throw it}
            emit(CloudEvent.Text(outputs[(calls-1).coerceAtMost(outputs.lastIndex)]))
        }
    }
    private suspend fun generate(provider: Provider,events: MutableList<ToolGenerationAttemptDiagnostic>,base: ToolRecord?=null,
        sandbox: SandboxClient=SandboxClient(context),throwObserver: Boolean=false,
        trace: ToolGenerationTrace? = null): ToolPreview {
        val planningEvents=mutableListOf<ToolGenerationAttemptDiagnostic>()
        val priorPlanningCalls=provider.planningCalls
        return try {
            ToolGenerationService(provider,sandbox,null,{event ->
                if (event.stage == ToolGenerationStage.PLAN) planningEvents+=event else events+=event
                if (throwObserver) throw CancellationException(secret)
            }).generate(secret,"Local fixture",base,emptyList(),CloudSession(),"SECRET_GENERATED_ID",
                privateState = false, draftPreview = null, trace = trace)
        } finally {
            assertEquals(provider.planningCalls-priorPlanningCalls,planningEvents.size)
            planningEvents.forEach { event ->
                assertEquals("completed",event.report().getString("outcome"))
                assertEquals("continue",event.report().getString("disposition"))
            }
            safe(planningEvents)
        }
    }
    private fun safe(events: List<ToolGenerationAttemptDiagnostic>) {
        val encoded=JSONArray(events.map {it.report()}).toString()
        assertFalse(encoded,encoded.contains("SECRET"));assertFalse(encoded,encoded.contains("content://"))
        assertFalse(encoded,encoded.contains("credential"));assertFalse(encoded,encoded.contains("private"))
    }
    @Test fun behaviorPlanFailuresExposeOnlyHostStructuralRules() {
        // Failure cases: missing behavior, unknown fields, excess steps, unused slots, and missing final assertions.
        val valid = """{"status":"checkable","scenario":{"slots":{"result":{"kind":"value"}},
            "steps":[{"op":"assert","slot":"result","test":"nonempty"}]}}"""
        val cases = listOf(
            "object" to null,
            "fields" to JSONObject(valid).put(secret, secret),
            "steps_count" to JSONObject(valid).apply {
                getJSONObject("scenario").put("steps", JSONArray().apply {
                    repeat(17) { put(JSONObject("""{"op":"assert","slot":"result","test":"nonempty"}""")) }
                })
            },
            "slots_or_events" to JSONObject(valid).apply {
                getJSONObject("scenario").getJSONObject("slots").put("unused", JSONObject("""{"kind":"action"}"""))
            },
            "final_assertion" to JSONObject(valid).apply {
                getJSONObject("scenario").getJSONObject("slots").put("press", JSONObject("""{"kind":"action"}"""))
                getJSONObject("scenario").getJSONArray("steps").put(JSONObject("""{"op":"action","slot":"press"}"""))
            },
        )
        for ((rule, raw) in cases) {
            val failure = runCatching { GenerationBehavior.parse(raw, emptySet()) }.exceptionOrNull() as PartsContractFailure
            assertEquals(ResponseShape("behavior.$rule", "$.behavior", "unknown"), failure.shape)
            val events = mutableListOf<ToolGenerationTraceEvent>()
            ToolGenerationTrace(UUID.randomUUID(), { events += it }).Run().diagnostic(
                ToolGenerationAttemptDiagnostic(1, ToolGenerationOutcome.REJECTED, ToolGenerationStage.PLAN,
                    "widget_plan", ToolGenerationDisposition.CORRECT, planPath = failure.path, planShape = failure.shape))
            assertEquals(failure.shape, (events.single() as ToolGenerationTraceEvent.Attempt).planShape)
            assertFalse(events.toString().contains(secret))
        }
    }
    @Test fun behaviorPlanCorrectionPreservesTheRouteEnvelope(): Unit = runBlocking {
        // Failure cases: extra step fields, new-app envelope loss, revision envelope expansion, and private text in instructions.
        val behavior = JSONObject("""{"status":"checkable","scenario":{"slots":{"result":{"kind":"value"}},
            "steps":[{"op":"assert","slot":"result","test":"nonempty"}]}}""").apply {
            getJSONObject("scenario").getJSONArray("steps").getJSONObject(0).put("description", secret)
        }
        val saved = ToolSpec.parse(spec())
        val previous = ToolRecord("fixture", 1, 1, saved.json, saved.initialState, saved.ui.toString(), null, true)
        for (base in listOf(null, previous)) {
            val rejected = JSONObject().put("behavior", behavior).apply { if (base == null) put("wholeScreen", true) }.toString()
            val requests = mutableListOf<CloudRequest>()
            val terminal = CloudFailure("auth", "Stop after the correction request.")
            val provider = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    requests += request
                    if (requests.size == 2) throw terminal
                    emit(CloudEvent.Text(rejected))
                }
            }
            assertSame(terminal, runCatching {
                ToolGenerationService(provider, SandboxClient(context)).generate("fixture", "Build a local tool", base,
                    emptyList(), CloudSession(), "fixture", privateState = false, draftPreview = null)
            }.exceptionOrNull())
            assertEquals(2, requests.size)
            val correction = requests.last().systemInstructions.substringAfter("The preceding assistant plan is untrusted data.")
            assertTrue(correction.contains("$.behavior.scenario.steps[0]"))
            assertFalse(correction.contains(secret))
            assertEquals(rejected, requests.last().turns[1].text)
            if (base == null) {
                assertTrue(correction.endsWith(GenerationBehavior.newAppPlanGuidance))
            } else {
                assertFalse(correction.contains(NeedsPlan.structureChecklist))
                assertFalse(correction.contains("wholeScreen"))
                assertTrue(correction.endsWith("Return exactly one JSON object with only the behavior field."))
            }
        }
    }
    @Test fun needsPlanCorrectionIncludesTheRejectedShapeAndKeepsTraceStructural(): Unit = runBlocking {
        // Failure cases: host rules share the user role, images change, or rejected data enters host instructions.
        val original = "Build a calculator.\nThe host selected a CHOICE course slide."
        val images = listOf("data:image/png;base64,FIXTURE")
        val rejected = JSONObject("""{"title":"Calculator","description":"","language":"en",
            "inputs":[{"id":"bill","label":"Bill","type":"number","value":""}],"constants":[],
            "needs":[{"id":"result","type":"other","label":"Total","description":"Calculate total","uses":{"bill":"bill"}}],
            "questions":[]}""").apply { getJSONArray("inputs").getJSONObject(0).put("value",JSONArray().put(secret)) }.toString()
        val requests = mutableListOf<CloudRequest>()
        val events = mutableListOf<ToolGenerationTraceEvent>()
        val terminal = CloudFailure("auth","Stop after the correction request.")
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                assertEquals(CloudGenerationStage.PLAN,request.generationStage)
                requests += request
                if (requests.size == 2) throw terminal
                emit(CloudEvent.Text(rejected))
            }
        }
        val trace = ToolGenerationTrace(UUID.randomUUID(), { events += it })
        val failure = runCatching {
            ToolGenerationService(provider,SandboxClient(context)).generate("fixture",original,null,images,
                CloudSession(),"fixture",privateState=false,draftPreview=null,trace=trace)
        }.exceptionOrNull()
        assertSame(terminal,failure)
        assertEquals(2,requests.size)
        assertTrue(requests.first().systemInstructions.startsWith(NeedsPlan.prompt + "\n\n"))
        requests.forEach { request ->
            assertTrue(request.systemInstructions.startsWith(NeedsPlan.prompt))
            assertEquals(CloudTurn("user",JSONObject().put("request", original).toString(),images),request.turns.first())
            assertTrue(request.jsonObject)
            assertFalse(request.search)
            assertFalse(request.systemInstructions.contains(original))
            assertFalse(request.systemInstructions.contains(secret))
        }
        val turns = requests.last().turns
        assertEquals(requests.first().turns.single(),turns.first())
        assertEquals(listOf("user","assistant","user"),turns.map { it.role })
        assertEquals(rejected,turns[1].text)
        assertEquals(CloudTurn("user","Return the corrected JSON plan."),turns.last())
        val hostCorrection = requests.last().systemInstructions.removePrefix(NeedsPlan.prompt)
        assertTrue(hostCorrection.contains("$.inputs[0].value"))
        assertTrue(hostCorrection.contains(NeedsPlan.structureChecklist))
        assertFalse(hostCorrection.contains(secret))
        val correction = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
            .single { it.disposition == ToolGenerationDisposition.CORRECT }
        assertEquals("$.inputs[0].value",correction.planPath)
        assertEquals("plan.value.type",correction.planShape?.rule)
        assertEquals("array",correction.planShape?.observedType)
        assertFalse(events.toString().contains(secret))
    }

    @Test fun planReferenceAndDescriptionFailuresKeepOneSharedCorrection(): Unit = runBlocking {
        // Failure cases: array references, excess references, undeclared IDs, and invalid descriptions.
        val valid = """{"title":"Local","description":"","language":"en","inputs":[],
            "constants":[],"needs":[{"id":"custom","type":"other","label":"Local","description":"Show a total"}],"questions":[]}"""
        val mutations: List<Pair<ResponseShape, (JSONObject) -> Unit>> = listOf(
            ResponseShape("plan.uses.object","$.needs[0].uses","array") to { row -> row.put("uses",JSONArray().put(secret)) },
            ResponseShape("plan.uses.count","$.needs[0].uses","object") to { row -> row.put("uses",JSONObject().apply { repeat(9) { put("r$it",secret) } }) },
            ResponseShape("plan.uses.reference","$.needs[0].uses","string") to { row -> row.put("uses",JSONObject().put("amount",secret)) },
            ResponseShape("plan.description.missing","$.needs[0]","missing") to { row -> row.remove("description") },
            ResponseShape("plan.description.blank","$.needs[0].description","string") to { row -> row.put("description","") },
            ResponseShape("plan.description.blank","$.needs[0].description","string") to { row -> row.put("description"," \n\t") },
            ResponseShape("plan.description.length","$.needs[0].description","string") to { row -> row.put("description","x".repeat(2_001)) },
            ResponseShape("plan.description.type","$.needs[0].description","object") to { row -> row.put("description",JSONObject().put("text",secret)) },
            ResponseShape("plan.description.type","$.needs[0].description","null") to { row -> row.put("description",JSONObject.NULL) },
        )
        val boundary = JSONObject(valid).apply { getJSONArray("needs").getJSONObject(0).put("description","x".repeat(2_000)) }
        assertEquals(2_000,NeedsPlan.parse(boundary.toString()).needs.single().description.length)
        val invalidConstants = listOf(
            ResponseShape("plan.constants.array", "$.constants", "object") to JSONObject(),
            ResponseShape("plan.constants.count", "$.constants", "array") to JSONArray().apply { repeat(25) { put(it) } },
            ResponseShape("plan.constants.unused", "$.constants", "array") to JSONArray().put(JSONObject().put("id", "extra").put("value", secret)),
        )
        val invalidPlans = mutations.map { (shape, mutate) ->
            shape to JSONObject(valid).apply { mutate(getJSONArray("needs").getJSONObject(0)) }
        } + invalidConstants.map { (shape, value) -> shape to JSONObject(valid).put("constants", value) }
        for ((shape, plan) in invalidPlans) {
            val path = shape.path
            val rejected = plan.toString()
            for (alreadyCorrected in listOf(false,true)) {
                val requests = mutableListOf<CloudRequest>()
                val events = mutableListOf<ToolGenerationTraceEvent>()
                val session = CloudSession().apply { if (alreadyCorrected) assertTrue(claimCorrection()) }
                val provider = object : ChatProvider {
                    override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent) -> Unit) {
                        assertEquals(CloudGenerationStage.PLAN,request.generationStage)
                        requests += request
                        emit(CloudEvent.Text(rejected))
                    }
                }
                val failure = runCatching {
                    ToolGenerationService(provider,SandboxClient(context)).generate("fixture","Build a local calculator",null,
                        emptyList(),session,"fixture",privateState=false,draftPreview=null,
                        trace=ToolGenerationTrace(UUID.randomUUID(), { events += it }))
                }.exceptionOrNull() as CloudFailure
                assertEquals("widget_plan",failure.code)
                assertEquals(if (alreadyCorrected) 1 else 2,requests.size)
                val attempts = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
                assertTrue(attempts.all { it.planPath == path })
                assertTrue(attempts.all { it.planShape == shape })
                assertEquals(ToolGenerationDisposition.FINISH,attempts.last().disposition)
                assertFalse(events.toString().contains(secret))
                if (!alreadyCorrected) {
                    assertEquals(ToolGenerationDisposition.CORRECT,attempts.first().disposition)
                    assertEquals(rejected,requests.last().turns[1].text)
                    assertEquals(CloudTurn("user","Return the corrected JSON plan."),requests.last().turns.last())
                    val hostCorrection = requests.last().systemInstructions.removePrefix(NeedsPlan.prompt)
                    assertTrue(hostCorrection.contains(path))
                    assertTrue(hostCorrection.contains(NeedsPlan.structureChecklist))
                    assertFalse(hostCorrection.contains(secret))
                }
            }
        }
    }

    @Test fun truncatedPlanCorrectionKeepsPartialOutputOutOfEveryInstruction(): Unit = runBlocking {
        // Failure cases: partial output enters a retry, or output-limit guidance enters the user role.
        val original = "Build a local calculator"
        val images = listOf("data:image/png;base64,FIXTURE")
        val requests = mutableListOf<CloudRequest>()
        val session = CloudSession()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent) -> Unit) {
                assertEquals(CloudGenerationStage.PLAN,request.generationStage)
                requests += request
                emit(CloudEvent.Text(secret))
                throw CloudFailure("response_length","Output limit")
            }
        }
        val failure = runCatching {
            ToolGenerationService(provider,SandboxClient(context)).generate("fixture",original,null,images,
                session,"fixture",privateState=false,draftPreview=null)
        }.exceptionOrNull() as CloudFailure
        assertEquals("response_length",failure.code)
        assertEquals(2,requests.size)
        assertTrue(requests.first().systemInstructions.startsWith(NeedsPlan.prompt + "\n\n"))
        assertEquals(listOf(CloudTurn("user",JSONObject().put("request", original).toString(),images),CloudTurn("user","Return the corrected JSON plan.")),
            requests.last().turns)
        val hostCorrection = requests.last().systemInstructions.removePrefix(NeedsPlan.prompt)
        assertTrue(hostCorrection.contains("output limit"))
        assertTrue(hostCorrection.contains("shorter complete JSON object"))
        assertFalse(requests.toString().contains(secret))
        assertFalse(session.claimCorrection())
    }

    @Test fun typedFailuresAreAssociatedWithFirstAndCorrectionAttempts(): Unit=runBlocking {
        val good=spec()
        val fixtures=listOf(
            Triple("not JSON $secret","tool_json","definition"),
            Triple(JSONObject(good).put("stateSchema",JSONObject().put("SECRET_FIELD",JSONObject().put("type",secret))).toString(),"tool_state_schema","definition"),
            Triple(JSONObject(good).put("ui",JSONObject().put("type","button").put("text","Local").put("action","SECRET_ACTION").put("SECRET_PROPERTY",secret)).toString(),"tool_ui_properties","definition"),
            Triple(spec("return {state:input.state,ui:{type:'text',text:'Local',SECRET_PROPERTY:'$secret'}};"),"tool_ui_properties","smoke"),
            Triple(spec("if(input.event.type==='action')throw Error('$secret');return {state:input.state};"),"unknown","smoke"),
            Triple(spec("return {state:{count:8}};"),"unknown","smoke"),
            Triple(JSONObject(spec("return {state:input.state,effects:[{id:'SECRET_EFFECT',capability:'vibration.play',args:{milliseconds:1}}]};")).put("capabilities",JSONArray("[\"vibration.play\"]")).toString(),"unknown","smoke")
        )
        for((bad,category,stage) in fixtures) {
            val events=mutableListOf<ToolGenerationAttemptDiagnostic>();val provider=Provider(listOf(bad,good))
            val result=generate(provider,events,throwObserver=true)
            assertEquals(2,provider.calls);assertEquals(listOf(1,2),events.map {it.attempt})
            val first=events.first().report()
            assertEquals("rejected",first.getString("outcome"));assertEquals(category,first.getString("category"));assertEquals(stage,first.getString("stage"))
            assertEquals("validated",events.last().report().getString("outcome"))
            assertEquals("{\"count\":7}",result.state);assertTrue(result.effects.isEmpty());safe(events)
            if(stage=="smoke")assertTrue(first.has("smoke"))
        }
        val events=mutableListOf<ToolGenerationAttemptDiagnostic>();val provider=Provider(listOf("$secret not JSON"))
        assertTrue(runCatching {generate(provider,events)}.exceptionOrNull() is CloudFailure)
        assertEquals(2,provider.calls);assertEquals(listOf("rejected","rejected"),events.map {it.report().getString("outcome")});safe(events)
    }
    @Test fun correctionRetainsOriginalRequestAndSuppliesPreciseSafeBindingGuidance(): Unit=runBlocking {
        val good=spec()
        val bad=JSONObject(good).put("ui",JSONObject().put("type","numberInput").put("text","Local").put("bind","SECRET_MISSING").put("action","edit")).toString()
        val events=mutableListOf<ToolGenerationAttemptDiagnostic>();val provider=Provider(listOf(bad,good))
        val traceEvents = mutableListOf<ToolGenerationTraceEvent>()
        generate(provider,events,throwObserver=true,trace=ToolGenerationTrace(UUID.randomUUID(), { traceEvents += it }))
        val rejection = traceEvents.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
            .single { it.disposition == ToolGenerationDisposition.CORRECT }
        assertEquals("component_binding_missing_path", rejection.validationRule)
        assertFalse(traceEvents.toString().contains("SECRET"))
        assertEquals(2,provider.requests.size)
        assertEquals(provider.requests[0].turns.single(),provider.requests[1].turns.first())
        assertEquals(bad,provider.requests[1].turns[1].text)
        val original=provider.requests[0].turns.single().text
        assertTrue(original.endsWith("Request: Local fixture"))
        assertTrue(original.contains("JSON string escaping"))
        assertTrue(original.contains("Scalar schema types are exactly"))
        assertTrue(original.contains("\"history\":{\"type\":\"array\""))
        val correction=provider.requests[1].turns.last().text
        assertTrue(correction.contains("component_binding_missing_path"))
        assertFalse(correction.contains("SECRET"));safe(events)
    }
    @Test fun savedStateFailureHasNoInventedCategoryAndNeverChangesTheBase(): Unit=runBlocking {
        val good=spec();val parsed=ToolSpec.parse(good)
        val base=ToolRecord("SECRET_OWNER",1,1,good,parsed.initialState,parsed.ui.toString(),null,true,createdAt=0)
        val bad=JSONObject(good).put("stateSchema",JSONObject("""{"other":"number"}""")).put("initialState",JSONObject("""{"other":0}""")).toString()
        val events=mutableListOf<ToolGenerationAttemptDiagnostic>();val provider=Provider(listOf(bad,good))
        assertEquals(base.state,generate(provider,events,base).state)
        assertEquals("state",events.first().report().getString("stage"));assertEquals("widget_state",events.first().report().getString("failureCode"))
        assertEquals("unknown",events.first().report().getString("category"));assertEquals(good,base.spec);safe(events)
    }
    @Test fun providerCancellationSizeAndUnknownErrorsNeverAcquireValidationCategories(): Unit=runBlocking {
        val cases=listOf(
            CloudFailure("auth",secret) to "providerFailure",
            CloudFailure(secret,secret) to "providerFailure",
            java.io.IOException(secret) to "providerFailure",
            IllegalStateException(secret) to "providerFailure",
            CloudFailure("cancelled",secret) to "canceled",
            CancellationException(secret) to "canceled"
        )
        for((failure,outcome) in cases) {
            val events=mutableListOf<ToolGenerationAttemptDiagnostic>();val provider=Provider(emptyList(),failure)
            assertNotNull(runCatching {generate(provider,events,throwObserver=true)}.exceptionOrNull())
            assertEquals(1,provider.calls);assertEquals(1,events.size)
            assertEquals(outcome,events.single().report().getString("outcome"));assertEquals("unknown",events.single().report().getString("category"));safe(events)
        }
        val events=mutableListOf<ToolGenerationAttemptDiagnostic>();val provider=Provider(listOf(secret.repeat(3000)))
        assertTrue(runCatching {generate(provider,events)}.exceptionOrNull() is CloudFailure)
        assertEquals(1,provider.calls);assertEquals("widget_response_size",events.single().report().getString("failureCode"));safe(events)
    }
    @Test fun unavailableAndBusyRuntimeNeverRequestCorrection(): Unit=runBlocking {
        val unavailable=SandboxClient(context,AndroidJsRuntime(context,supported={false}))
        val events=mutableListOf<ToolGenerationAttemptDiagnostic>();val provider=Provider(listOf(spec()))
        assertTrue(runCatching {generate(provider,events,sandbox=unavailable)}.exceptionOrNull() is CloudFailure)
        assertEquals(0,provider.calls);assertEquals("runtimeUnavailable",events.single().report().getString("outcome"))
        assertEquals("availability",events.single().report().getString("stage"));safe(events)
        val occupied=AndroidJsRuntime.get(context).acquire(false,SandboxContract.MAX_BYTES)
        try {
            events.clear()
            assertTrue(runCatching {generate(provider,events)}.exceptionOrNull() is CloudFailure)
            assertEquals(1,provider.calls);assertEquals("runtimeUnavailable",events.single().report().getString("outcome"))
            assertEquals("javascript_busy",events.single().report().getString("failureCode"));safe(events)
        } finally {occupied.close()}
    }
    @Test fun correctionProviderFailureAndCancellationKeepTheAttemptNumber(): Unit=runBlocking {
        for(failure in listOf(CloudFailure("auth",secret),CancellationException(secret))) {
            val events=mutableListOf<ToolGenerationAttemptDiagnostic>();val provider=Provider(listOf("not JSON"),failure,2)
            assertNotNull(runCatching {generate(provider,events)}.exceptionOrNull())
            assertEquals(listOf(1,2),events.map {it.attempt})
            assertEquals("rejected",events.first().report().getString("outcome"))
            assertEquals(if(failure is CancellationException) "canceled" else "providerFailure",events.last().report().getString("outcome"))
            safe(events)
        }
    }

    @Test fun timedCorrectionAndTerminalFailureStayOnOneOperationWithoutPrivateData(): Unit = runBlocking {
        val operation = UUID.randomUUID()
        for (failure in listOf(CloudFailure(secret, secret), CancellationException(secret))) {
            val events = mutableListOf<ToolGenerationTraceEvent>()
            var now = 100L
            val trace = ToolGenerationTrace(operation, { events += it }, { now++ })
            val result = runCatching {
                generate(Provider(listOf("not JSON $secret"), failure, 2), mutableListOf(), trace = trace)
            }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(events.all { it.operationId == operation })
            assertEquals(events.map { it.atNanos }.sorted(), events.map { it.atNanos })
            assertFalse(events.toString().contains(secret))
            val stages = events.filterIsInstance<ToolGenerationTraceEvent.Stage>()
            val active = ArrayDeque<ToolGenerationTraceEvent.Stage>()
            for (event in stages) {
                if (event.started) active.addLast(event) else {
                    val start = active.removeLast()
                    assertEquals(start.copy(atNanos = event.atNanos, started = false), event)
                }
            }
            assertTrue(active.isEmpty())
            val correction = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
                .single { it.disposition == ToolGenerationDisposition.CORRECT }
            assertEquals(1, correction.attempt)
            assertEquals(ToolGenerationStage.DEFINITION, correction.stage)
            assertEquals(ToolValidationCategory.JSON, correction.validationCategory)
            val second = stages.first { it.attempt == 2 }
            assertTrue(second.started && second.atNanos > correction.atNanos)
            assertEquals(ToolGenerationStage.PROVIDER, second.stage)
            val terminal = events.filterIsInstance<ToolGenerationTraceEvent.Finished>().single()
            assertEquals(events.last(), terminal)
            assertEquals(2, terminal.attempt)
            assertEquals(false, terminal.partsOnly)
            if (failure is CancellationException) {
                assertEquals(ToolGenerationTraceOutcome.CANCELED, terminal.outcome)
                assertEquals("cancelled", terminal.failureCode)
            } else {
                assertEquals(ToolGenerationTraceOutcome.FAILED, terminal.outcome)
                assertEquals("unknown", terminal.failureCode)
            }
        }
    }
}
