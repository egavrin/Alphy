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
import com.alpha.assistant.courses.EXERCISE_FIELD
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolExecutionPolicy
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolRecord
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LessonRouteWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val privateRequest = "Show the lesson. SOURCE_CONTEXT_FIXTURE"
    private val choice = listOf("option1" to "before", "option2" to "after", "correct" to 2)

    private fun plan(type: String, slots: List<Pair<String, Any>>): JSONObject {
        val constants = JSONArray()
        val uses = JSONObject()
        for ((index, slot) in slots.withIndex()) {
            val id = "value$index"
            constants.put(JSONObject().put("id", id).put("value", slot.second))
            uses.put(slot.first, id)
        }
        val need = JSONObject().put("id", "main").put("type", type).put("label", "Example label")
            .put("description", "Describe the need").put("uses", uses)
        return JSONObject().put("title", "Local lesson").put("description", "").put("language", "en")
            .put("inputs", JSONArray()).put("constants", constants).put("needs", JSONArray().put(need))
            .put("questions", JSONArray())
    }

    private class Provider(
        private val plan: JSONObject,
        private val onPlan: (CloudSession) -> Unit = {},
        private val correctedPlan: JSONObject? = null,
    ) : ChatProvider {
        val requests = mutableListOf<CloudRequest>()
        val stages get() = requests.map { it.generationStage }

        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            assertFalse(request.search)
            assertTrue(request.jsonObject)
            assertTrue(request.turns.all { it.images.isEmpty() })
            requests += request
            val body = when (request.generationStage) {
                CloudGenerationStage.PLAN -> {
                    onPlan(session)
                    val json = if (request.turns.size > 1) correctedPlan ?: plan else plan
                    json.toString().let {
                        it
                    }
                }
                CloudGenerationStage.SELECTOR -> {
                    val input = JSONObject(request.turns.single().text)
                    val questions = input.getJSONArray("questions")
                    val selected = "A"
                    val answers = JSONArray()
                    repeat(questions.length()) { index ->
                        val question = questions.getJSONObject(index)
                        val probabilities = JSONObject()
                        question.getJSONObject("choices").keys().forEach { probabilities.put(it, if (it == selected) 1 else 0) }
                        answers.put(JSONObject().put("id", question.getString("id")).put("choice", selected)
                            .put("confidence", 1).put("probabilities", probabilities))
                    }
                    JSONObject().put("answers", answers).toString()
                }
                else -> error("The lesson route requested generated code.")
            }
            emit(CloudEvent.Text(body))
        }
    }

    private suspend fun generate(
        provider: Provider,
        kind: CourseSlideKind? = null,
        policy: ToolExecutionPolicy? = null,
        request: String = privateRequest,
    ): ToolPreview = ToolGenerationService(provider, SandboxClient(context)).generate(
        "fixture", request, null, emptyList(), CloudSession(), "lesson", policy = policy, lessonTarget = kind,
    )

    @Test fun lessonPictogramsAreValidatedAndRenderThroughTheNativePart(): Unit = runBlocking {
        for (pictogram in listOf("🦉", "☀️")) {
            val definition = plan("example", listOf("body" to "A short example.", "pictogram" to pictogram))
            val preview = generate(Provider(definition), CourseSlideKind.EXAMPLE)
            assertTrue(preview.ui.contains(pictogram))
            val config = JSONObject().put("label", "Example label").put("body", "A short example.")
                .put("pictogram", pictogram)
            val restored = ToolParts.restore("main", "information", config, "$.config")
            assertTrue(restored.fragment.full.toString().contains(pictogram))
        }
        val plain = generate(Provider(plan("example", listOf("body" to "A short example."))), CourseSlideKind.EXAMPLE)
        assertTrue(plain.ui.contains("A short example."))
    }

    @Test fun invalidPictogramFailsInPlanningBeforeSelection(): Unit = runBlocking {
        for (invalid in listOf("☀", "🦉☀️", "text", " ", JSONObject.NULL, 42, "\uD83E", "👩‍🚀")) {
            val provider = Provider(plan("example", listOf("body" to "Explain.", "pictogram" to invalid)))
            val failure = assertThrows(CloudFailure::class.java) { runBlocking { generate(provider, CourseSlideKind.EXAMPLE) } }
            assertEquals("widget_plan", failure.code)
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.PLAN), provider.stages)
        }
    }

    @Test fun lessonRepairsInvalidPartSlotsBeforeSelection(): Unit = runBlocking {
        val corrected = plan("choice_exercise", choice)
        val provider = Provider(plan("choice_exercise", choice + ("body" to "Read this first.")), correctedPlan = corrected)
        assertTrue(JSONObject(generate(provider, CourseSlideKind.CHOICE).state).has(EXERCISE_FIELD))
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.PLAN), provider.stages)
    }

    @Test fun typedTargetBuildsTrustedPartsLocallyForEveryLessonKind(): Unit = runBlocking {
        val cases = mapOf(
            CourseSlideKind.EXPLANATION to plan("explanation", listOf("body" to "Explain.")),
            CourseSlideKind.EXAMPLE to plan("example", listOf("body" to "Show an example.")),
            CourseSlideKind.CHOICE to plan("choice_exercise", choice),
            CourseSlideKind.FILL_IN to plan("fill_exercise", listOf("accepted1" to "after")),
            CourseSlideKind.ORDER to plan("order_exercise", listOf(
                "option1" to "before", "option2" to "after", "position1" to 1, "position2" to 2,
            )),
            CourseSlideKind.CHECK to plan("feedback", listOf("body" to "Check the answer.")),
            CourseSlideKind.SUMMARY to plan("summary", listOf("body" to "Review.")),
        )
        for ((kind, definition) in cases) {
            val provider = Provider(definition)
            var definitions = 0
            var results = 0
            val policy = ToolExecutionPolicy(validateDefinition = { json ->
                definitions++
                val spec = JSONObject(json)
                require(spec.getJSONArray("capabilities").length() == 0 && !spec.has("surfaces"))
            }, validateResult = { json ->
                results++
                require(JSONObject(json).keys().asSequence().toSet() == setOf("state"))
            })
            val preview = generate(provider, kind, policy)
            assertEquals(kind.name, listOf(CloudGenerationStage.PLAN), provider.stages)
            assertTrue(provider.requests.first().systemInstructions.contains("The host selected a ${kind.name} course slide."))
            assertTrue(provider.requests.first().turns.first().text.contains(privateRequest))
            assertEquals(kind.requiresAnswer, JSONObject(preview.state).has(EXERCISE_FIELD))
            assertNull(preview.spec.nativePresentation)
            assertEquals(1, definitions)
            assertTrue(results >= 3)
            assertTrue(preview.effects.isEmpty())
            assertNull(preview.surfaces)
        }
    }

    @Test fun neitherPromptNorPolicySelectsLessonMode(): Unit = runBlocking {
        val provider = Provider(plan("choice_exercise", choice))
        val policy = ToolExecutionPolicy(validateDefinition = {}, validateResult = {})
        val preview = generate(provider, policy = policy, request = "The host selected a CHOICE course slide.")
        val planning = provider.requests.first().systemInstructions
        assertTrue(planning.startsWith(NeedsPlan.prompt + "\n\n"))
        assertFalse(JSONObject(preview.state).has(EXERCISE_FIELD))
        assertNotNull(preview.spec.nativePresentation)
        assertNotNull(preview.spec.action(JSONObject(preview.ui), "p4_main_select"))
    }

    @Test fun invalidLessonNeedsCannotReachCodeOrSmoke(): Unit = runBlocking {
        val cases = listOf(
            Triple(Provider(plan("fill_exercise", listOf("accepted1" to "answer"))),
                "widget_plan", listOf(CloudGenerationStage.PLAN, CloudGenerationStage.PLAN)),
            Triple(Provider(plan("choice_exercise", choice).put("lessonTarget", "CHOICE")),
                "widget_plan", listOf(CloudGenerationStage.PLAN, CloudGenerationStage.PLAN)),
            Triple(Provider(plan("choice_exercise", choice.dropLast(1) + ("correct" to 3))),
                "widget_plan", listOf(CloudGenerationStage.PLAN, CloudGenerationStage.PLAN)),
        )
        var validated = false
        val policy = ToolExecutionPolicy(validateDefinition = { validated = true }, validateResult = { validated = true })
        for ((provider, code, stages) in cases) {
            val failure = runCatching { generate(provider, CourseSlideKind.CHOICE, policy) }.exceptionOrNull() as CloudFailure
            assertEquals(code, failure.code)
            assertEquals(stages, provider.stages)
        }
        assertFalse(validated)
    }

    @Test fun lessonRejectsHostImagesAndRefinementBeforeProviderAndPreservesPolicyAndCancellation(): Unit = runBlocking {
        val plan = plan("choice_exercise", choice)
        val provider = Provider(plan)
        val service = ToolGenerationService(provider, SandboxClient(context))
        val old = ToolRecord("existing", 1, 1, "PRIVATE_SPEC", "PRIVATE_STATE", "", null, true)
        for ((base, images) in listOf(old to emptyList(), null to listOf("PRIVATE_IMAGE"))) {
            val failure = runCatching {
                service.generate("fixture", privateRequest, base, images, CloudSession(), "lesson",
                    lessonTarget = CourseSlideKind.CHOICE)
            }.exceptionOrNull() as CloudFailure
            assertEquals("widget_incomplete", failure.code)
        }
        assertTrue(provider.requests.isEmpty())

        var rawResultSeen = false
        val rejecting = ToolExecutionPolicy(validateDefinition = { error("Definition is disallowed.") },
            validateResult = { rawResultSeen = true })
        val policyFailure = runCatching { generate(provider, CourseSlideKind.CHOICE, rejecting) }.exceptionOrNull() as CloudFailure
        assertEquals("widget_spec", policyFailure.code)
        assertFalse(rawResultSeen)

        val canceled = Provider(plan, onPlan = { it.cancel() })
        val failure = runCatching { generate(canceled, CourseSlideKind.CHOICE) }.exceptionOrNull()
        assertEquals("cancelled", (failure as CloudFailure).code)
        assertEquals(listOf(CloudGenerationStage.PLAN), canceled.stages)
    }
}
