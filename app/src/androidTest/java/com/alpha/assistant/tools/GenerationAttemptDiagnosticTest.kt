package com.alpha.assistant.tools

import com.alpha.assistant.cloud.*
import com.alpha.assistant.tools.generation.GenerationBehavior
import com.alpha.assistant.tools.generation.PartsContractFailure
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class GenerationAttemptDiagnosticTest {
    private val secret="SECRET_PRIVATE_content://private/credential"
    @Test fun smokeTraceRetainsOnlyStructuralWorkflowPaths() {
        val diagnostic = ToolGenerationAttemptDiagnostic(1, ToolGenerationOutcome.REJECTED,
            ToolGenerationStage.BEHAVIOR, "widget_behavior", ToolGenerationDisposition.FINISH, smokeEvent = "action")
        for (path in listOf("$.behavior.scenario.steps[2]", "$.scene.checks.scenario.steps[4]")) {
            assertEquals(path, diagnostic.copy(smokePath = path).safeSmoke()?.getString("path"))
        }
        assertEquals("unknown", diagnostic.copy(smokePath = "$.scene.checks.$secret").safeSmoke()?.getString("path"))
    }

    @Test fun behaviorPlanFailuresExposeOnlyHostStructuralRules() {
        val valid = """{"status":"checkable","scenario":{"slots":{"result":{"kind":"value"}},
            "steps":[{"op":"assert","slot":"result","test":"nonempty"}]}}"""
        val cases = listOf(
            ResponseShape("behavior.object", "$.behavior", "unknown") to null,
            ResponseShape("plan.fields", "$.behavior", "object") to JSONObject(valid).put(secret, secret),
            ResponseShape("plan.fields", "$.behavior.scenario.slots[0]", "object") to JSONObject(valid).apply {
                getJSONObject("scenario").getJSONObject("slots").getJSONObject("result").put(secret, secret)
            },
            ResponseShape("behavior.steps_count", "$.behavior.scenario.steps[64]", "missing") to JSONObject(valid).apply {
                getJSONObject("scenario").put("steps", JSONArray().apply {
                    repeat(65) { put(JSONObject("""{"op":"assert","slot":"result","test":"nonempty"}""")) }
                })
            },
            ResponseShape("behavior.unused_slot", "$.behavior.scenario.slots[1]", "missing") to JSONObject(valid).apply {
                getJSONObject("scenario").getJSONObject("slots").put("unused", JSONObject("""{"kind":"action"}"""))
            },
            ResponseShape("behavior.final_assertion", "$.behavior", "unknown") to JSONObject(valid).apply {
                getJSONObject("scenario").getJSONObject("slots").put("press", JSONObject("""{"kind":"action"}"""))
                getJSONObject("scenario").getJSONArray("steps").put(JSONObject("""{"op":"action","slot":"press"}"""))
            },
        )
        for ((shape, raw) in cases) {
            val failure = runCatching { GenerationBehavior.parse(raw, emptySet()) }.exceptionOrNull() as PartsContractFailure
            assertEquals(shape, failure.shape)
            assertFalse(failure.message.orEmpty().contains(secret))
        }
    }
}
