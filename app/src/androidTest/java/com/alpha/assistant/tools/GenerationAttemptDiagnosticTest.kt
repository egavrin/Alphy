package com.alpha.assistant.tools

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

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
        val requests=mutableListOf<CloudRequest>()
        override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend(CloudEvent)->Unit) {
            calls++;requests+=request
            if(calls==failAt)failure?.let {throw it}
            emit(CloudEvent.Text(outputs[(calls-1).coerceAtMost(outputs.lastIndex)]))
        }
    }
    private suspend fun generate(provider: Provider,events: MutableList<ToolGenerationAttemptDiagnostic>,base: ToolRecord?=null,
        sandbox: SandboxClient=SandboxClient(context),throwObserver: Boolean=false): ToolPreview =
        ToolGenerationService(provider,sandbox,null,{event -> events+=event; if(throwObserver)throw CancellationException(secret)})
            .generate(secret,"Local fixture",base,emptyList(),CloudSession(),"SECRET_GENERATED_ID")
    private fun safe(events: List<ToolGenerationAttemptDiagnostic>) {
        val encoded=JSONArray(events.map {it.report()}).toString()
        assertFalse(encoded,encoded.contains("SECRET"));assertFalse(encoded,encoded.contains("content://"))
        assertFalse(encoded,encoded.contains("credential"));assertFalse(encoded,encoded.contains("private"))
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
            assertEquals("accepted",events.last().report().getString("outcome"))
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
        generate(provider,events,throwObserver=true)
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
}
