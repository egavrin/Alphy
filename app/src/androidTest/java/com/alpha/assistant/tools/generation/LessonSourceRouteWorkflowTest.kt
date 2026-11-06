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
import kotlinx.coroutines.CancellationException
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
        private val choosePart: Boolean = true,
        private val cancelSelection: Boolean = false,
    ) : ChatProvider {
        val requests = mutableListOf<CloudRequest>()
        val stages get() = requests.map { it.generationStage }
        private var planIndex = 0

        override suspend fun respond(
            key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit,
        ) {
            assertFalse(request.search)
            requests += request
            val result = when (request.generationStage) {
                CloudGenerationStage.PLAN -> plans[planIndex++]
                CloudGenerationStage.SELECTOR -> {
                    if (cancelSelection) {
                        session.cancel()
                        session.check()
                    }
                    val questions = JSONObject(request.turns.single().text).getJSONArray("questions")
                    val answers = JSONArray()
                    val choice = if (choosePart) "A" else "B"
                    for (index in 0 until questions.length()) {
                        val question = questions.getJSONObject(index)
                        val scores = JSONObject()
                        question.getJSONObject("choices").keys().forEach { scores.put(it, if (it == choice) 1 else 0) }
                        answers.put(JSONObject().put("id", question.getString("id")).put("choice", choice)
                            .put("confidence", 1).put("probabilities", scores))
                    }
                    JSONObject().put("answers", answers).toString()
                }
                else -> error("Lesson generation must use trusted parts.")
            }
            emit(CloudEvent.Text(result))
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
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR),
            provider.stages)
        assertTrue(provider.requests.first().turns.single().text.contains("PRIVATE_SNIPPET_FIXTURE"))
        for (request in provider.requests.drop(1)) {
            val selector = request.turns.single().text
            assertFalse(selector.contains("PRIVATE_SNIPPET_FIXTURE"))
            assertFalse(selector.contains("sourceIds"))
        }
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
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.PLAN,
            CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR), corrected.stages)

        val missing = Provider(listOf(plan(), plan()))
        assertEquals("widget_plan", (runCatching { generate(missing) }.exceptionOrNull() as CloudFailure).code)
        assertEquals(List(2) { CloudGenerationStage.PLAN }, missing.stages)

        val rejected = Provider(listOf(valid), choosePart = false)
        assertEquals("widget_incomplete", (runCatching { generate(rejected) }.exceptionOrNull() as CloudFailure).code)
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR), rejected.stages)

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

        val canceled = Provider(listOf(valid), cancelSelection = true)
        assertTrue(runCatching { generate(canceled) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR), canceled.stages)
    }
}
