package com.alpha.assistant.tools

import com.alpha.assistant.tools.generation.PartsContractFailure
import com.alpha.assistant.tools.generation.SceneCheckEvidence
import com.alpha.assistant.tools.generation.SceneRequirement
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Failure cases recorded before production edits. Direct checks isolate diagnostic attribution
 * from provider output and Android scheduling. The grid fixture uses the complete native workflow.
 */
class ScopedDiagnosticRegressionTest {
    private fun spec(ui: JSONObject) = JSONObject()
        .put("formatVersion", 3).put("title", "Scoped diagnostics").put("description", "")
        .put("capabilities", JSONArray()).put("stateSchema", JSONObject().put("value", "number"))
        .put("initialState", JSONObject().put("value", 0)).put("ui", ui).put("source", "return {state:input.state};")

    private fun diagnostic(ui: String): ToolUiDiagnostic {
        val failure = runCatching { ToolSpec.parse(spec(JSONObject(ui)).toString()) }.exceptionOrNull()
        return checkNotNull((failure as ToolValidationFailure).uiDiagnostic)
    }

    @Test fun validLabelThenInvalidLayoutReportsLayout() {
        val failure = diagnostic("""{"type":"text","text":"Valid private label","layout":{"weight":1}}""")
        assertEquals("component_layout", failure.rule)
        assertEquals("$.ui.layout", failure.path)
        assertFalse(failure.correction.contains("Valid private label"))
        ToolSpec.parse(spec(JSONObject("""{"type":"text","text":"Valid private label"}""")).toString())
    }

    @Test fun secondSiblingReportsItsOwnLayout() {
        val failure = diagnostic("""{"type":"column","children":[{"type":"text","text":"First"},
            {"type":"text","text":"Second","layout":{"width":-1}}]}""")
        assertEquals("component_layout", failure.rule)
        assertEquals("$.ui.children[1].layout", failure.path)
    }

    @Test fun unlabeledGuardUsesActiveComponentContract() {
        val failure = diagnostic("""{"type":"input","text":"Input","action":"edit","bind":"value","keyboard":"invalid"}"""
            .replace("\"bind\":\"value\",", ""))
        // A binding-free generic guard is available on button variant validation.
        val button = diagnostic("""{"type":"button","text":"Button","action":"press","variant":"invalid"}""")
        assertEquals("component_contract", button.rule)
        assertEquals("$.ui", button.path)
        assertEquals("component_binding_missing_path", failure.rule)
    }

    @Test fun nestedSpecificRuleSurvivesOuterValidation() {
        val failure = diagnostic("""{"type":"column","children":[{"type":"text","text":"First"},
            {"type":"grid","columns":9,"children":[{"type":"text","text":"Nested"}]}]}""")
        assertEquals("grid_columns", failure.rule)
        assertEquals("$.ui.children[1].columns", failure.path)
    }

    @Test fun duplicateDismissAndButtonActionsReportTheSecondField() {
        val button = JSONObject("""{"type":"button","text":"Cancel","action":"privateDuplicate"}""")
        for (type in listOf("dialog", "bottomSheet")) {
            val modal = JSONObject("""{"type":"$type","text":"Confirmation","visibleBind":"shown",
                "dismissAction":"privateDuplicate","children":[{"type":"text","text":"Continue?"}]}""")
            for ((first, second, field) in listOf(Triple(button, modal, "dismissAction"), Triple(modal, button, "action"))) {
                val ui = JSONObject().put("type", "column").put("children", JSONArray(listOf(first, second)))
                val definition = spec(ui)
                definition.getJSONObject("stateSchema").put("shown", "boolean")
                definition.getJSONObject("initialState").put("shown", true)
                val failure = runCatching { ToolSpec.parse(definition.toString()) }.exceptionOrNull() as ToolValidationFailure
                val diagnostic = checkNotNull(failure.uiDiagnostic)
                assertEquals(ToolValidationCategory.UI_ACTIONS, diagnostic.category)
                assertEquals("component_action", diagnostic.rule)
                assertEquals("$.ui.children[1].$field", diagnostic.path)
                assertEquals("unique_ascii_action", diagnostic.expected)
                assertTrue(diagnostic.correction.contains("dismissAction"))
                assertFalse(diagnostic.correction.contains("privateDuplicate"))
            }
        }
    }

    @Test fun renderedImagesReportCurrentNodeAfterSuccessfulSibling() {
        val image = JSONObject().put("type", "image").put("text", "Image").put("resourceId", "a".repeat(64))
        val ui = JSONObject().put("type", "column").put("children", JSONArray().apply { repeat(ToolUiCatalog.MAX_IMAGES + 1) { put(JSONObject(image.toString())) } })
        val failure = diagnostic(ui.toString())
        assertEquals("render_image_limit", failure.rule)
        assertEquals("$.ui.children[${ToolUiCatalog.MAX_IMAGES}]", failure.path)
    }

    @Test fun observerFailureCannotReplaceDiagnosticOrCancellation() {
        val invalid = spec(JSONObject("""{"type":"text","text":"Valid","layout":{"weight":1}}"""))
        val failure = runCatching { ToolSpec.parse(invalid.toString()) { throw CancellationException("observer") } }
            .exceptionOrNull() as ToolValidationFailure
        assertEquals("component_layout", failure.uiDiagnostic?.rule)
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, runCatching { validateTool(ToolValidationCategory.UI_STRUCTURE) { throw cancelled } }.exceptionOrNull())
        val runtime = UnsupportedOperationException("runtime")
        assertSame(runtime, runCatching { validateTool(ToolValidationCategory.UI_STRUCTURE) { throw runtime } }.exceptionOrNull())
    }

    @Test fun successfulChildRestoresParentAndEscapingChildKeepsItsRule() {
        val trace = ToolUiValidationTrace(ToolUiValidationStage.STATE)
        runCatching {
            trace.checking("component_contract", "$.ui", "catalog_component_contract", JSONObject()) {
                trace.checking("component_text", "$.ui.children[0].text", "string", "Valid") { require(true) }
                require(false)
            }
        }
        assertEquals("component_contract", trace.failure(ToolValidationCategory.UI_STRUCTURE).rule)
        runCatching {
            trace.checking("component_contract", "$.ui", "catalog_component_contract", JSONObject()) {
                trace.checking("component_text", "$.ui.children[1].text", "string", 7) { require(false) }
            }
        }
        assertEquals("component_text", trace.failure(ToolValidationCategory.UI_STRUCTURE).rule)
        assertEquals("$.ui.children[1].text", trace.failure(ToolValidationCategory.UI_STRUCTURE).path)
    }

    @Test fun cancellationAndRuntimeFailuresKeepTheirClassification() {
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, runCatching { validateTool(ToolValidationCategory.UI_STRUCTURE) { throw cancelled } }.exceptionOrNull())
        val runtime = UnsupportedOperationException("runtime")
        assertSame(runtime, runCatching { validateTool(ToolValidationCategory.UI_STRUCTURE) { throw runtime } }.exceptionOrNull())
    }

    private fun scene() = JSONObject("""{"placement":"inline","brief":"Grow the shape.",
        "checks":{"status":"checkable","scenario":{"slots":{"slider":{"kind":"action"},"shape":{"kind":"geometry"}},
        "steps":[{"op":"capture","slot":"shape","as":"before"},{"op":"action","slot":"slider","value":5},
        {"op":"assert","slot":"shape","test":"changed","capture":"before"}]}},
        "checkEvidence":[{"step":3,"excerpt":"Grow the shape.","purpose":"invalid","scope":"whole"}]}""")

    @Test fun evidenceUsesArrayRowInsteadOfScenarioStep() {
        val failure = runCatching { SceneRequirement.read(scene(), request = "Grow the shape.") }.exceptionOrNull() as PartsContractFailure
        assertEquals("$.scene.checkEvidence[0]", failure.path)
    }

    @Test fun visualReviewUsesExplicitRootOrSceneParent() {
        val root = JSONObject().put("visualReview", JSONArray().put("Wrong excerpt"))
        val rootFailure = runCatching { SceneCheckEvidence.readVisual(root, "Grow the shape.", "$") }.exceptionOrNull() as PartsContractFailure
        assertEquals("$.visualReview", rootFailure.path)
        val nested = scene().apply {
            getJSONArray("checkEvidence").getJSONObject(0).put("purpose", "motion")
            put("visualReview", root.getJSONArray("visualReview"))
        }
        val failure = runCatching { SceneRequirement.read(nested, request = "Grow the shape.") }.exceptionOrNull() as PartsContractFailure
        assertEquals("$.scene.visualReview", failure.path)
    }
}
