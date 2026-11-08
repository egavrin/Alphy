package com.alpha.assistant.tools

import com.alpha.assistant.courses.CourseSlideKind
import com.alpha.assistant.tools.generation.GenerationPlan
import com.alpha.assistant.tools.generation.NeedsPlan
import com.alpha.assistant.tools.generation.PartsContractFailure
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
    private fun report(failure: ToolValidationFailure) = ToolGenerationAttemptDiagnostic(
        attempt=1,
        outcome=ToolGenerationOutcome.REJECTED,
        stage=ToolGenerationStage.DEFINITION,
        failureCode="widget_spec",
        disposition=ToolGenerationDisposition.FINISH,
        category=failure.category,
        ui=failure.uiDiagnostic,
        schemaRule=failure.details?.rule
    ).report()

    private fun bindingFailure(bind: Any?,state: JSONObject=JSONObject().put(secret,0),definition: JSONObject=spec()): ToolUiDiagnostic {
        val parsed=ToolSpec.parse(definition.toString())
        val ui=JSONObject().put("type","numberInput").put("text","Local").put("action","edit")
        if(bind!=null)ui.put("bind",bind)
        val failure=runCatching {parsed.validateUi(ui,state)}.exceptionOrNull() as ToolValidationFailure
        assertEquals(ToolValidationCategory.UI_BINDINGS,failure.category)
        return checkNotNull(failure.uiDiagnostic).also { diagnostic ->
            val report=report(failure).toString()
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
        val textDefinition=spec().put("stateSchema",JSONObject().put(secret,"string"))
            .put("initialState",JSONObject().put(secret,""))
        assertEquals("component_binding_schema_type",bindingFailure(secret,JSONObject().put(secret,"SECRET_VALUE"),textDefinition).rule)
        assertEquals("component_binding_schema_type",bindingFailure("$secret.child").rule)
        val invalid=bindingFailure(secret,JSONObject().put(secret,"SECRET_VALUE content://private"))
        assertEquals("component_binding_state_value",invalid.rule);assertEquals("string",invalid.observed)
        assertEquals("component_binding_state_value",bindingFailure(secret,JSONObject().put(secret,9007199254740992L)).rule)
        val nestedSchema=JSONObject().put(secret,JSONObject().put("type","object").put("fields",JSONObject().put("child","number")))
        val nested=spec().put("stateSchema",nestedSchema)
            .put("initialState",JSONObject().put(secret,JSONObject().put("child",0)))
        assertEquals("component_binding_missing_path",bindingFailure("$secret.child",JSONObject().put(secret,JSONObject()),nested).rule)
        assertEquals("component_binding_state_value",bindingFailure("$secret.child",JSONObject().put(secret,"SECRET_VALUE"),nested).rule)
    }
    @Test fun unsupportedScalarsAndMalformedJsonRemainRejectedWithoutPrivateDetails() {
        for(type in listOf("integer","float","date","datetime","bool","text","SECRET_TYPE")) {
            val bad=spec().put("stateSchema",JSONObject().put(secret,type))
            val failure=runCatching {ToolSpec.parse(bad.toString())}.exceptionOrNull() as ToolValidationFailure
            assertEquals(ToolValidationCategory.STATE_SCHEMA,failure.category)
            assertEquals("schema.scalar",failure.details?.rule)
            val report=report(failure).toString()
            assertFalse(report.contains("SECRET"));assertTrue(report.contains("unknown"))
        }
        for(raw in listOf("SECRET_NOT_JSON", "```json\n${spec()}\n```", "{\"SECRET\":\"bad\\q\"}", "{\"SECRET\":1,\"SECRET\":2}")) {
            val failure=runCatching {ToolSpec.parse(raw)}.exceptionOrNull() as ToolValidationFailure
            assertEquals(ToolValidationCategory.JSON,failure.category)
            val report=report(failure)
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

    @Test fun needsPlanGuidanceShowsAValidObject() {
        val example = NeedsPlan.prompt.substringAfter("Use these fields:\n").substringBefore("\nUse the language")
        val parsed = NeedsPlan.parse(example)
        assertTrue(parsed.inputs.isEmpty())
        assertTrue(parsed.needs.single().uses.isEmpty())
        val references = NeedsPlan.prompt.substringAfter("Complete example with a reference:\n").substringBefore("\nReference rules:")
        val referenced = NeedsPlan.parse(references)
        assertEquals(mapOf("step" to "increment"),referenced.needs.single().uses)
        assertEquals(2,(referenced.constants.getValue("increment").raw as Number).toInt())
        assertTrue(NeedsPlan.prompt.contains(NeedsPlan.structureChecklist))
    }

    @Test fun nativeGuidanceRequiresLabelsEvenWhenAValueIsBound() {
        val guidance = ToolUiCatalog.modelGuidance()
        assertTrue(guidance.contains("A bind supplies a value. It does not supply the text label."))
        assertTrue(guidance.contains("The only non-container exceptions are spacer, divider, skeleton, spinner, backHandler, navigation, calendar, medicine."))
        assertTrue(guidance.contains("Every supplied text value must contain 1 to 300 characters"))
        val invalid = spec().put("ui", JSONObject("""{"type":"text","text":"","bind":"$secret"}"""))
        val failure = runCatching { ToolSpec.parse(invalid.toString()) }.exceptionOrNull() as ToolValidationFailure
        val diagnostic = checkNotNull(failure.uiDiagnostic)
        assertEquals("component_text", diagnostic.rule)
        assertTrue(diagnostic.correction.contains("Observed count: 0."))
        assertFalse(diagnostic.correction.contains(secret))
    }

    @Test fun wholeScreenMarkerIsExactAndUnavailableToLessonPlans() {
        val marker = """{"wholeScreen":true,"language":"en"}"""
        assertSame(GenerationPlan.WholeScreen, NeedsPlan.parseResponse(marker, null, emptySet()))
        for (invalid in listOf("{}", """{"wholeScreen":false}""", """{"wholeScreen":1}""",
            """{"wholeScreen":null}""", """{"wholeScreen":"true"}""",
            """{"wholeScreen":true,"questions":[]}""", """{"wholeScreen":true,"wholeScreen":true}""",
            "$marker {}")) {
            assertTrue(runCatching { NeedsPlan.parseResponse(invalid, null, emptySet()) }.exceptionOrNull() is PartsContractFailure)
        }
        assertTrue(runCatching { NeedsPlan.parseResponse(marker, CourseSlideKind.EXPLANATION, emptySet()) }.exceptionOrNull() is PartsContractFailure)
        assertTrue(runCatching { NeedsPlan.parseResponse(marker, null, setOf("s1")) }.exceptionOrNull() is PartsContractFailure)
        assertFalse(NeedsPlan.promptFor(CourseSlideKind.EXPLANATION).contains("wholeScreen"))
    }
}
