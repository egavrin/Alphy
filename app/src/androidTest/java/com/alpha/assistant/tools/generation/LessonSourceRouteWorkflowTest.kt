package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.courses.CourseSlideKind
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.LessonToolPreview
import com.alpha.assistant.tools.ToolExecutionPolicy
import com.alpha.assistant.tools.ToolGenerationService
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LessonSourceRouteWorkflowTest {
    private class Provider(
        private val plans: List<String>,
        private val cancelPlan: Boolean = false,
    ) : ChatProvider {
        val requests = mutableListOf<CloudRequest>()
        val stages get() = requests.map { it.generationStage }
        private var planIndex = 0

        override suspend fun respond(
            key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit,
        ) {
            assertFalse(request.search)
            requests += request
            assertEquals(CloudGenerationStage.PLAN, request.generationStage)
            if (cancelPlan) session.cancel()
            emit(CloudEvent.Text(plans[planIndex++]))
        }
    }

    private fun plan(first: JSONArray? = null, second: JSONArray? = null): String {
        val root = JSONObject("""
            {"title":"Lesson","description":"","language":"en","inputs":[],
             "constants":[{"id":"bodyA","value":"First explanation."},{"id":"bodyB","value":"Second explanation."}],
             "needs":[
               {"id":"first","type":"explanation","label":"First","description":"Explain","uses":{"body":"bodyA"}},
               {"id":"second","type":"content","label":"Second","description":"Add context","uses":{"body":"bodyB"}}],
             "questions":[]}
        """.trimIndent())
        first?.let { root.getJSONArray("needs").getJSONObject(0).put("sourceIds", it) }
        second?.let { root.getJSONArray("needs").getJSONObject(1).put("sourceIds", it) }
        return root.toString()
    }

    private suspend fun generate(
        provider: Provider,
        allowed: Set<String> = setOf("s1", "s2", "s3"),
        policy: ToolExecutionPolicy? = null,
    ): LessonToolPreview {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ToolGenerationService(provider, SandboxClient(context)).generateLesson(
            "fixture", "Make a lesson using this context: PRIVATE_SNIPPET_FIXTURE", CloudSession(), "lesson",
            CourseSlideKind.EXPLANATION, allowed, policy,
        )
    }

    @Test fun returnsOnlyInstalledSourceSelectionsInUiOrderAfterPolicyAndSmoke(): Unit = runBlocking {
        val provider = Provider(listOf(plan(JSONArray(listOf("s2", "s1")), JSONArray(listOf("s2")))))
        val definitionChecks = mutableListOf<String>()
        val resultChecks = mutableListOf<String>()
        val result = generate(provider, policy = ToolExecutionPolicy(
            validateDefinition = { definitionChecks += it },
            validateResult = { resultChecks += it },
        ))
        assertEquals(listOf("s2", "s1"), result.sourceIds)
        assertEquals(listOf(CloudGenerationStage.PLAN), provider.stages)
        assertTrue(provider.requests.first().turns.single().text.contains("PRIVATE_SNIPPET_FIXTURE"))
        assertEquals(1, definitionChecks.size)
        assertTrue(resultChecks.isNotEmpty())
        for (raw in definitionChecks + resultChecks + listOf(result.preview.spec.json, result.preview.state, result.preview.ui)) {
            assertFalse(raw.contains("sourceIds", ignoreCase = true))
            assertFalse(raw.contains("PRIVATE_SNIPPET_FIXTURE"))
        }
        assertTrue(result.preview.ui.indexOf("First explanation.") in 0 until result.preview.ui.indexOf("Second explanation."))
    }

    @Test fun retriesOnlyPlanningForInvalidSelectionAndRejectsMissingOrUninstalledSources(): Unit = runBlocking {
        val invalid = plan(JSONArray(listOf("s9")))
        val valid = plan(JSONArray(listOf("s2")))
        val corrected = Provider(listOf(invalid, valid))
        assertEquals(listOf("s2"), generate(corrected).sourceIds)
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.PLAN), corrected.stages)

        val missing = Provider(listOf(plan(), plan()))
        assertEquals("widget_plan", (runCatching { generate(missing) }.exceptionOrNull() as CloudFailure).code)
        assertEquals(List(2) { CloudGenerationStage.PLAN }, missing.stages)

        val unsourced = Provider(listOf(plan()))
        assertTrue(generate(unsourced, allowed = emptySet()).sourceIds.isEmpty())
    }

    @Test fun doesNotReturnProvenanceWhenPolicyRejectsOrGenerationCancels(): Unit = runBlocking {
        val valid = plan(JSONArray(listOf("s1")))
        var resultChecked = false
        val reject = ToolExecutionPolicy(validateDefinition = { error("Disallowed lesson.") },
            validateResult = { resultChecked = true })
        val failure = runCatching { generate(Provider(listOf(valid)), policy = reject) }.exceptionOrNull()
        assertEquals("widget_spec", (failure as CloudFailure).code)
        assertFalse(resultChecked)

        val canceled = Provider(listOf(valid), cancelPlan = true)
        assertEquals("cancelled", (runCatching { generate(canceled) }.exceptionOrNull() as CloudFailure).code)
        assertEquals(listOf(CloudGenerationStage.PLAN), canceled.stages)
    }
}
