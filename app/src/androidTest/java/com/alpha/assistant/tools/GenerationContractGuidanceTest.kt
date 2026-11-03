package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Cases before implementation: missing paths, malformed paths, incompatible binding schemas,
 * invalid state values, forbidden scalar aliases, invalid JSON, and private diagnostic content.
 * Isolated checks make each validator failure deterministic without provider or device effects.
 */
class GenerationContractGuidanceTest {
    private val secret="SECRET_PRIVATE_FIELD"
    private fun spec()=JSONObject().put("formatVersion",3).put("title","Local contract").put("description","")
        .put("capabilities",JSONArray()).put("stateSchema",JSONObject().put(secret,"number"))
        .put("initialState",JSONObject().put(secret,0)).put("ui",JSONObject("""{"type":"text","text":"Local"}"""))
        .put("source","return {state:input.state};")
    private fun bindingFailure(bind: Any?,state: JSONObject=JSONObject().put(secret,0),fields: JSONObject=JSONObject().put(secret,"number")): ToolUiDiagnostic {
        val parsed=ToolSpec.parse(spec().toString()).copy(schema=fields)
        val ui=JSONObject().put("type","numberInput").put("text","Local").put("action","edit")
        if(bind!=null)ui.put("bind",bind)
        val failure=runCatching {parsed.validateUi(ui,state)}.exceptionOrNull() as ToolValidationFailure
        assertEquals(ToolValidationCategory.UI_BINDINGS,failure.category)
        return checkNotNull(failure.uiDiagnostic).also { diagnostic ->
            val report=ToolGenerationAttemptDiagnostic(1,ToolGenerationOutcome.REJECTED,ToolGenerationStage.DEFINITION,"widget_spec",failure.category,diagnostic).report().toString()
            assertFalse(report,report.contains("SECRET"));assertFalse(report,report.contains("content://"))
            assertEquals("$.ui.bind",diagnostic.path)
            assertFalse(diagnostic.correction,diagnostic.correction.contains("SECRET"))
        }
    }
    @Test fun bindingsReportOnlyTheFailureTheValidatorCanProve() {
        assertEquals("component_binding_missing_path",bindingFailure(null).rule)
        assertEquals("component_binding_missing_path",bindingFailure("SECRET_ABSENT").rule)
        assertEquals("component_binding_missing_path",bindingFailure(secret,JSONObject()).rule)
        assertEquals("component_binding",bindingFailure(7).rule)
        assertEquals("component_binding",bindingFailure("SECRET[0]").rule)
        assertEquals("component_binding",bindingFailure("constructor").rule)
        assertEquals("component_binding_schema_type",bindingFailure(secret,JSONObject().put(secret,"SECRET_VALUE"),JSONObject().put(secret,"string")).rule)
        assertEquals("component_binding_schema_type",bindingFailure("$secret.child").rule)
        val invalid=bindingFailure(secret,JSONObject().put(secret,"SECRET_VALUE content://private"))
        assertEquals("component_binding_state_value",invalid.rule);assertEquals("string",invalid.observed)
        assertEquals("component_binding_state_value",bindingFailure(secret,JSONObject().put(secret,9007199254740992L)).rule)
        val nested=JSONObject().put(secret,JSONObject().put("type","object").put("fields",JSONObject().put("child","number")))
        assertEquals("component_binding_missing_path",bindingFailure("$secret.child",JSONObject().put(secret,JSONObject()),nested).rule)
        assertEquals("component_binding_state_value",bindingFailure("$secret.child",JSONObject().put(secret,"SECRET_VALUE"),nested).rule)
    }
    @Test fun unsupportedScalarsAndMalformedJsonRemainRejectedWithoutPrivateDetails() {
        for(type in listOf("integer","float","date","datetime","bool","text","SECRET_TYPE")) {
            val bad=spec().put("stateSchema",JSONObject().put(secret,type))
            val failure=runCatching {ToolSpec.parse(bad.toString())}.exceptionOrNull() as ToolValidationFailure
            assertEquals(ToolValidationCategory.STATE_SCHEMA,failure.category)
            assertEquals("schema.scalar",failure.details?.rule)
            val report=ToolGenerationAttemptDiagnostic(1,ToolGenerationOutcome.REJECTED,ToolGenerationStage.DEFINITION,"widget_spec",failure.category,schemaRule=failure.details?.rule).report().toString()
            assertFalse(report.contains("SECRET"));assertTrue(report.contains("unknown"))
        }
        for(raw in listOf("SECRET_NOT_JSON", "```json\n${spec()}\n```", "{\"SECRET\":\"bad\\q\"}", "{\"SECRET\":1,\"SECRET\":2}")) {
            val failure=runCatching {ToolSpec.parse(raw)}.exceptionOrNull() as ToolValidationFailure
            assertEquals(ToolValidationCategory.JSON,failure.category)
            val report=ToolGenerationAttemptDiagnostic(1,ToolGenerationOutcome.REJECTED,ToolGenerationStage.DEFINITION,"widget_spec",failure.category).report()
            assertFalse(report.toString().contains("SECRET"));assertFalse(report.has("schema"));assertFalse(report.has("ui"))
            assertNull(failure.details)
        }
    }
    @Test fun guidanceDeclaresBindingTypesAndAValidStructuredHistory() {
        val guidance=ToolUiCatalog.modelGuidance()
        for(component in ToolUiCatalog.components.values.filter {it.binding.isNotEmpty()}) {
            assertTrue(component.type,guidance.contains("${component.type}=${component.binding.sorted().joinToString("|")}"))
        }
        assertTrue(guidance.contains("Required bind:"));assertTrue(guidance.contains("Optional bind:"))
        val history=JSONObject("""{"history":{"type":"array","maxItems":100,"items":{"type":"object","fields":{"id":"string","at":"number","note":"string"}}}}""")
        val fixture=spec().put("stateSchema",history).put("initialState",JSONObject("""{"history":[]}"""))
            .put("ui",JSONObject("""{"type":"list","text":"History","bind":"history","columns":[{"text":"Note","field":"note"}]}"""))
        ToolSpec.parse(fixture.toString())
    }
}
