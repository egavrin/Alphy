package com.alpha.assistant.courses

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Source
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.LessonToolPreview
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.agentAppAccepted
import com.alpha.assistant.tools.agentFixtureFinish
import com.alpha.assistant.tools.agentStall
import com.alpha.assistant.tools.agentWrite
import com.alpha.assistant.tools.writeAppReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CourseGenerationWorkflowTest {
    @Test fun resumesExerciseAndAdaptsOnlyFromThisCoursesTypedAttempts(): Unit = runBlocking {
        withDatabase { context, name ->
            val runtime = FixtureRuntime()
            val practice = "PRIVATE-PRACTICE-ID"
            val reviewSlide = "PRIVATE-REVIEW-ID"
            val firstLesson = "PRIVATE-FIRST-LESSON-ID"
            val secondLesson = "PRIVATE-SECOND-LESSON-ID"
            val outline = CourseOutline(listOf(
                CourseLessonPlan(firstLesson, "Practice tenses", listOf(
                    CourseSlidePlan(practice, CourseSlideKind.CHOICE, "Choose the past tense"),
                )),
                CourseLessonPlan(secondLesson, "Review tenses", listOf(
                    CourseSlidePlan(reviewSlide, CourseSlideKind.SUMMARY, "Review earlier errors"),
                )),
            ))
            val id = CourseStore(context, name).use { store ->
                val course = store.create(setup, outline)
                val unrelated = store.create(CourseSetup("Other course secret", "Other level", "Other goal"), outline)
                val other = store.commitGeneration(
                    store.beginGeneration(unrelated.id, unrelated.revision, practice),
                    CourseSlideContent(definition(choice()).json, """{"type":"text","text":"Other"}""", "{}"),
                )
                store.commitAnswer(other.id, CourseAnswerSubmission(
                    "unrelated", other.revision, practice, CourseAnswer.Choice("OTHER-ANSWER"), true, "{}",
                ))
                val player = CourseController(store, runtime.generation)
                rejected(CourseRejection.SLIDE_NOT_REACHED) { player.generate(course.id, course.revision, reviewSlide, key, CloudSession()) }
                runtime.response = { preview(definition(choice())) }
                val active = player.generate(course.id, course.revision, practice, key, CloudSession())
                assertEquals(listOf("a", "b"), player.exercise(course.id, active.revision, practice)!!.options.map { it.id })
                assertNull(store.slide(course.id, reviewSlide))
                course.id
            }
            CourseStore(context, name).use { store ->
                val resumed = checkNotNull(store.course(id))
                val player = CourseController(store, runtime.generation)
                assertEquals(resumed, player.generate(id, resumed.revision, practice, key, CloudSession()))
                assertEquals(1, runtime.prompts.size)
                workflowError(CourseWorkflowError.INVALID_ANSWER) {
                    player.submitAnswer(id, resumed.revision, practice, "bad", CourseAnswer.Choice("injected"))
                }
                assertTrue(runtime.events.isEmpty())
                runtime.action = { spec, state, _ ->
                    // Even a different key in mutable state cannot change the host grade.
                    state.put("courseExerciseV1", choice(correct = "b").toString())
                    state.put("selected", "b")
                    ToolPreview(spec, state.toString(), spec.ui.toString())
                }
                val wrong = player.submitAnswer(id, resumed.revision, practice, "wrong", CourseAnswer.Choice("b"))
                assertEquals(practice, wrong.progress.currentSlideId)
                rejected(CourseRejection.DUPLICATE_ANSWER) {
                    player.submitAnswer(id, wrong.revision, practice, "wrong", CourseAnswer.Choice("b"))
                }
                rejected(CourseRejection.STALE_REVISION) {
                    player.submitAnswer(id, resumed.revision, practice, "stale", CourseAnswer.Choice("a"))
                }
                assertEquals(1, runtime.events.size)
                val right = player.submitAnswer(id, wrong.revision, practice, "right", CourseAnswer.Choice("a"))
                assertEquals(listOf(false, true), store.answers(id).map { it.correct })
                assertEquals(listOf("b", "a"), runtime.events.map { it.getString("value") })
                assertEquals(reviewSlide, right.progress.currentSlideId)
                runtime.response = { preview(definition()) }
                val review = player.generate(id, right.revision, reviewSlide, key, CloudSession())
                val prompt = runtime.prompts.last()
                val wrongAt = prompt.indexOf("Imperfect form")
                val rightAt = prompt.indexOf("Preterite form")
                assertTrue(wrongAt >= 0 && rightAt > wrongAt)
                assertTrue(prompt.contains(""""correct":false""") && prompt.contains(""""correct":true"""))
                assertTrue(prompt.contains(""""kind":"CHOICE""""))
                assertTrue(prompt.contains("Choose the past tense") && prompt.contains("Review earlier errors"))
                val context = JSONObject(prompt.substringAfter("\nCourse and reached slide:\n")
                    .substringBefore("\nEarlier answers in order, including errors:\n"))
                assertEquals(2, context.getInt("reachedSlideNumber"))
                assertEquals("Review earlier errors", context.getString("reachedSlideGoal"))
                assertEquals(2, context.getJSONArray("lessonPlan").getJSONObject(0).getInt("slideNumber"))
                val answers = JSONArray(prompt.substringAfter("\nEarlier answers in order, including errors:\n")
                    .substringBefore("\nTrusted search result data:\n"))
                assertEquals(listOf(1, 1), List(answers.length()) { answers.getJSONObject(it).getInt("slideNumber") })
                val privateValues = listOf(
                    key, id, practice, reviewSlide, firstLesson, secondLesson, "PRIVATE-CONVERSATION",
                    "Other course secret", "OTHER-ANSWER",
                )
                for (request in runtime.prompts) {
                    for (privateValue in privateValues) assertFalse(request.contains(privateValue))
                }
                assertTrue(player.advance(id, review.revision, reviewSlide).progress.isComplete)
            }
        }
    }

    @Test fun checksTheCurrentActionBeforeStartingTheSandbox(): Unit = runBlocking {
        withDatabase { context, name ->
            CourseStore(context, name).use { store ->
                val runtime = FixtureRuntime()
                val player = CourseController(store, runtime.generation)
                val course = store.create(setup, outline())
                runtime.response = { preview(definition(choice())) }
                val active = player.generate(course.id, course.revision, "practice", key, CloudSession())
                val stored = checkNotNull(store.slide(course.id, "practice"))
                // Another writer can save valid JSON with a disabled control. It must not reach the sandbox.
                val disabled = JSONObject(stored.content.uiJson).put("enabled", false)
                val updated = store.commitAnswer(course.id, CourseAnswerSubmission(
                    "disable", active.revision, "practice", CourseAnswer.Choice("b"), false,
                    stored.content.stateJson, disabled.toString(),
                ))
                workflowError(CourseWorkflowError.INVALID_ACTION) {
                    player.submitAnswer(course.id, updated.revision, "practice", "disabled", CourseAnswer.Choice("a"))
                }
                assertTrue(runtime.events.isEmpty())
                assertEquals(listOf("disable"), store.answers(course.id).map { it.operationId })
            }
        }
    }

    @Test fun rejectsEachForbiddenRawAnswerOutputAndAcceptsALaterCleanAnswer(): Unit = runBlocking {
        withDatabase { context, name ->
            val fill = JSONObject().put("kind", "FILL_IN").put("question", "Name the form.")
                .put("accepted", JSONArray(listOf("preterite"))).put("actionId", "answer")
            val definition = JSONObject(definition().json)
            definition.getJSONObject("stateSchema").put("courseExerciseV1", "string")
            definition.getJSONObject("initialState").put("courseExerciseV1", fill.toString())
            definition.put("ui", JSONObject("""{"type":"input","text":"Answer","action":"answer","bind":"selected"}"""))
            definition.put("source", """
                const result = {state:input.state};
                const forbidden = {effects:[],records:[],surfaces:{},frames:[],timers:[],playback:'ready'};
                if (input.event.type === 'action' && Object.prototype.hasOwnProperty.call(forbidden,input.event.value)) {
                    result[input.event.value] = forbidden[input.event.value];
                }
                return result;
            """.trimIndent())
            val provider = PolicyProvider(definition.toString())
            val service = ToolGenerationService(provider, SandboxClient(context))
            CourseStore(context, name).use { store ->
                val course = store.create(setup, CourseOutline(listOf(CourseLessonPlan("lesson", "Past", listOf(
                    CourseSlidePlan("fill", CourseSlideKind.FILL_IN, "Name the form"),
                )))))
                val player = policyFixtureController(store, service)
                val active = player.generate(course.id, course.revision, "fill", key, CloudSession())
                val stored = store.slide(course.id, "fill")
                assertNotNull(stored)
                for (forbidden in listOf("effects", "records", "surfaces", "frames", "timers", "playback")) {
                    workflowError(CourseWorkflowError.UNSUPPORTED_OUTPUT) {
                        player.submitAnswer(course.id, active.revision, "fill", "answer", CourseAnswer.FillIn(forbidden))
                    }
                    assertEquals(active, store.course(course.id))
                    assertEquals(stored, store.slide(course.id, "fill"))
                    assertTrue(store.answers(course.id).isEmpty())
                }
                val complete = player.submitAnswer(course.id, active.revision, "fill", "answer", CourseAnswer.FillIn("preterite"))
                assertTrue(complete.progress.isComplete)
                assertTrue(store.answers(course.id).single().correct)
                // One step writes the lesson app, and the next step finishes.
                assertEquals(listOf(CloudGenerationStage.AGENT, CloudGenerationStage.AGENT), provider.stages)
            }
        }
    }

    @Test fun buildsAllSevenKindsFromTheReachedHostTargetAndGradesTrustedExercises(): Unit = runBlocking {
        withDatabase { context, name ->
            val provider = LessonAgentProvider()
            CourseStore(context, name).use { store ->
                store.create(CourseSetup("OTHER-COURSE-SECRET", "Beginner", "Unrelated goal"), outline())
                val steps = CourseSlideKind.entries.map { CourseSlidePlan(it.name, it, "Learn ${it.name}") }
                // The topic copies the kind line of the rules. Only the rules may carry it.
                val topic = "This slide is FILL_IN."
                var current = store.create(setup.copy(topic = topic),
                    CourseOutline(listOf(CourseLessonPlan("lesson", "Past tenses", steps))))
                val player = CourseController(store, ToolGenerationService(provider, SandboxClient(context)))
                for (step in steps) {
                    provider.slide = lessonSlide(step.kind)
                    val start = provider.requests.size
                    current = player.generate(current.id, current.revision, step.id, key, CloudSession())
                    val requests = provider.requests.drop(start)
                    assertEquals(lessonStages, requests.map { it.generationStage })
                    val prompt = requests.first().turns.single().text
                    assertTrue(requests.first().systemInstructions.contains("This slide is ${step.kind.name}."))
                    assertTrue(prompt.contains(topic))
                    val exercise = player.exercise(current.id, current.revision, step.id)
                    if (exercise == null) {
                        assertFalse(step.kind.requiresAnswer)
                        current = player.advance(current.id, current.revision, step.id)
                    } else {
                        assertEquals(step.kind, exercise.kind)
                        val (wrong, correct) = when (step.kind) {
                            CourseSlideKind.CHOICE -> {
                                val ids = exercise.options.map { it.id }
                                CourseAnswer.Choice(ids[0]) to CourseAnswer.Choice(ids[1])
                            }
                            CourseSlideKind.FILL_IN -> CourseAnswer.FillIn("before") to CourseAnswer.FillIn("after")
                            CourseSlideKind.ORDER -> {
                                val ids = exercise.options.map { it.id }
                                CourseAnswer.Order(ids) to CourseAnswer.Order(ids.reversed())
                            }
                            else -> error("Unexpected exercise.")
                        }
                        val saved = store.slide(current.id, step.id)
                        current = player.submitAnswer(current.id, current.revision, step.id, "${step.id}-wrong", wrong)
                        assertEquals(step.id, current.progress.currentSlideId)
                        assertEquals(saved, store.slide(current.id, step.id))
                        current = player.submitAnswer(current.id, current.revision, step.id, "${step.id}-right", correct)
                    }
                }
                assertTrue(current.progress.isComplete)
                assertEquals(listOf(false, true, false, true, false, true), store.answers(current.id).map { it.correct })
                assertTrue(provider.requests.last().turns.first().text.contains(""""correct":false"""))
                for (request in provider.requests) {
                    assertFalse(request.search)
                    assertTrue(request.turns.all { it.images.isEmpty() })
                    val sent = request.systemInstructions + request.turns.joinToString("\n") { it.text }
                    for (secret in listOf(key, "PRIVATE-CONVERSATION", "OTHER-COURSE-SECRET")) {
                        assertFalse(sent.contains(secret))
                    }
                }
            }
        }
    }

    @Test fun localizedChoiceSlidesGenerateOnceAndKeepGradesAfterReopen(): Unit = runBlocking {
        val questions = mapOf(
            "en" to "Which number is even?",
            "ru" to "Какое число чётное?",
            "it" to "Quale numero è pari?",
        )
        for ((language, question) in questions) withDatabase { context, name ->
            val provider = LessonAgentProvider().apply {
                slide = lessonSlide(CourseSlideKind.CHOICE, question, language) { exercise ->
                    val options = exercise.getJSONArray("options")
                    options.getJSONObject(0).put("label", "3")
                    options.getJSONObject(1).put("label", "4")
                }
            }
            val tools = ToolGenerationService(provider, SandboxClient(context))
            val step = CourseSlidePlan("choice", CourseSlideKind.CHOICE, question)
            val outline = CourseOutline(listOf(CourseLessonPlan("lesson", question, listOf(step))))
            val courseId = CourseStore(context, name).use { store ->
                val course = store.create(setup.copy(topic = question, goal = question), outline)
                val controller = CourseController(store, tools)
                controller.generate(course.id, course.revision, step.id, key, CloudSession())
                val player = CoursePresentation(store, controller)
                val slide = player.player(course.id) as CoursePage.Slide
                val exercise = checkNotNull(slide.prompt)
                assertEquals(question, exercise.question)
                assertEquals(listOf("3", "4"), exercise.options.map { it.label })
                val wrong = player.submit(slide, "wrong", CourseAnswer.Choice(exercise.options.first().id))
                assertEquals(false, ((wrong as CoursePlayerUpdate.Open).page as CoursePage.Slide).feedback?.correct)
                course.id
            }
            CourseStore(context, name).use { store ->
                val player = CoursePresentation(store, CourseController(store, tools))
                val slide = player.player(courseId) as CoursePage.Slide
                val right = checkNotNull(slide.prompt).options.single { it.label == "4" }
                assertEquals(false, slide.feedback?.correct)
                val result = player.submit(slide, "right", CourseAnswer.Choice(right.id))
                assertEquals(true, ((result as CoursePlayerUpdate.Open).page as CoursePage.Slide).feedback?.correct)
            }
            CourseStore(context, name).use { store ->
                val player = CoursePresentation(store, CourseController(store, tools))
                assertTrue(player.player(courseId) is CoursePage.Complete)
                val slide = player.player(courseId, completedAnswerId = "right") as CoursePage.Slide
                assertEquals(true, slide.feedback?.correct)
                assertEquals(listOf(false, true), store.answers(courseId).map { it.correct })
            }
            assertEquals(language, lessonStages, provider.requests.map { it.generationStage })
            // The reached slide goal names the language. The rest of the prompt is English.
            if (language == "ru") assertTrue(provider.requests.first().turns.single().text.contains("which is Russian (ru)"))
        }
    }

    @Test fun generatesAndReopensFeedbackForFourOrderedOptions(): Unit = runBlocking {
        withDatabase { context, name ->
            val kind = CourseSlideKind.ORDER
            val hint = "Start with the shortest side."
            val correctFeedback = "The sides are in increasing order."
            val provider = LessonAgentProvider().apply {
                slide = lessonSlide(kind) { exercise ->
                    exercise.getJSONArray("options").put(JSONObject().put("id", "c").put("label", "third"))
                        .put(JSONObject().put("id", "d").put("label", "fourth"))
                    exercise.put("correctOrder", JSONArray(listOf("b", "a", "c", "d")))
                        .put("wrongHint", hint).put("correctFeedback", correctFeedback)
                }
            }
            val tools = ToolGenerationService(provider, SandboxClient(context))
            val step = CourseSlidePlan("order", kind, "Order four sides")
            val outline = CourseOutline(listOf(CourseLessonPlan("lesson", "Compare sides", listOf(step))))
            val id = CourseStore(context, name).use { store ->
                val course = store.create(setup, outline)
                CourseController(store, tools).generate(course.id, course.revision, step.id, key, CloudSession())
                course.id
            }
            CourseStore(context, name).use { store ->
                val saved = checkNotNull(store.slide(id, step.id))
                val state = JSONObject(parseCourseSpec(saved.content, kind).initialState)
                val exercise = JSONObject(state.getString(EXERCISE_FIELD))
                assertEquals(hint, exercise.getString("wrongHint"))
                assertEquals(correctFeedback, exercise.getString("correctFeedback"))
                val player = CoursePresentation(store, CourseController(store, tools))
                val slide = player.player(id) as CoursePage.Slide
                val options = checkNotNull(slide.prompt).options.map { it.id }
                val result = player.submit(slide, "ordered", CourseAnswer.Order(listOf(options[1], options[0], options[2], options[3])))
                assertEquals(true, ((result as CoursePlayerUpdate.Open).page as CoursePage.Slide).feedback?.correct)
            }
            assertEquals(lessonStages, provider.requests.map { it.generationStage })
        }
    }

    @Test fun keepsCompletedWorkWhenTheLessonRouteRejectsOrCancelsTheNextSlide(): Unit = runBlocking {
        withDatabase { context, name ->
            val provider = LessonAgentProvider()
            CourseStore(context, name).use { store ->
                val course = store.create(setup, CourseOutline(listOf(CourseLessonPlan("lesson", "Past tenses", listOf(
                    CourseSlidePlan("intro", CourseSlideKind.EXPLANATION, "Introduce the topic"),
                    CourseSlidePlan("practice", CourseSlideKind.CHOICE, "Choose the past tense"),
                )))))
                val player = CourseController(store, ToolGenerationService(provider, SandboxClient(context)))
                provider.slide = lessonSlide(CourseSlideKind.EXPLANATION)
                val intro = player.generate(course.id, course.revision, "intro", key, CloudSession())
                var reached = player.advance(course.id, intro.revision, "intro")
                val completed = store.slide(course.id, "intro")

                val beforeRejection = provider.requests.size
                provider.slide = lessonSlide(CourseSlideKind.CHOICE).put("capabilities", JSONArray().put("notification.post"))
                workflowError(CourseWorkflowError.UNSUPPORTED_OUTPUT) {
                    player.generate(course.id, reached.revision, "practice", key, CloudSession())
                }
                assertTrue(provider.requests[beforeRejection + 1].turns.last().text.startsWith("write_app: REJECTED"))
                reached = checkNotNull(store.course(course.id))

                // A CHOICE slide without its exercise never passes the course check.
                provider.slide = lessonSlide(CourseSlideKind.EXPLANATION)
                workflowError(CourseWorkflowError.UNSUPPORTED_LESSON) {
                    player.generate(course.id, reached.revision, "practice", key, CloudSession())
                }
                reached = checkNotNull(store.course(course.id))
                assertEquals(CourseGenerationFailure.INVALID_OUTPUT, reached.generationFailure)
                assertEquals(1, reached.progress.completedSlides)

                provider.slide = lessonSlide(CourseSlideKind.CHOICE)
                provider.before = { it.cancel() }
                val cancellation = runCatching {
                    player.generate(course.id, reached.revision, "practice", key, CloudSession())
                }.exceptionOrNull()
                assertTrue(cancellation is CancellationException)
                assertEquals(CourseGenerationFailure.INTERRUPTED, store.course(course.id)?.generationFailure)
                assertEquals(completed, store.slide(course.id, "intro"))
                assertEquals(1, store.course(course.id)?.progress?.completedSlides)
                assertNull(store.slide(course.id, "practice"))
            }
        }
    }

    @Test fun aSlideThatNeverFinishesIsRetryableAndSavesNothing(): Unit = runBlocking {
        withDatabase { context, name ->
            CourseStore(context, name).use { store ->
                val course = store.create(setup, outline())
                val provider = AgentScriptProvider(agentStall)
                val controller = CourseController(store, ToolGenerationService(provider, SandboxClient(context)))
                workflowError(CourseWorkflowError.UNSUPPORTED_LESSON) {
                    controller.generate(course.id, course.revision, "practice", key, CloudSession())
                }
                val failed = checkNotNull(store.course(course.id))
                assertEquals(CourseGenerationFailure.INVALID_OUTPUT, failed.generationFailure)
                assertNull(store.slide(course.id, "practice"))
                val page = CoursePresentation(store, controller).player(course.id) as CoursePage.Unavailable
                assertNotNull(page.generationRequest)
            }
        }
    }

    @Test fun aFixedRejectionDoesNotNameTheLaterFailure(): Unit = runBlocking {
        withDatabase { context, name ->
            CourseStore(context, name).use { store ->
                val course = store.create(setup, outline())
                // The course policy rejects the first version. The second passes it and fails the exercise check.
                val provider = AgentScriptProvider(
                    writeAppReply(lessonSlide(CourseSlideKind.CHOICE).put("capabilities", JSONArray().put("notification.post"))),
                    writeAppReply(lessonSlide(CourseSlideKind.EXPLANATION)), agentStall)
                val controller = CourseController(store, ToolGenerationService(provider, SandboxClient(context)))
                workflowError(CourseWorkflowError.UNSUPPORTED_LESSON) {
                    controller.generate(course.id, course.revision, "practice", key, CloudSession())
                }
                assertTrue(provider.feedback(2), provider.feedback(2).startsWith("write_app: REJECTED"))
            }
        }
    }

    @Test fun savesOnlyTheSelectedTrustedSourceAndKeepsLinksOutOfThePlannerPrompt(): Unit = runBlocking {
        withDatabase { context, name ->
            val provider = LessonAgentProvider()
            CourseStore(context, name).use { store ->
                val course = store.create(setup, outline())
                val player = CourseController(store, ToolGenerationService(provider, SandboxClient(context)))
                val firstUrl = "https://example.org/first"
                val secondUrl = "https://example.org/second"
                val sources = listOf(
                    Source("FIRST-LOCAL-TITLE", firstUrl, "First excerpt from $firstUrl"),
                    Source("SECOND-LOCAL-TITLE", secondUrl, "Second excerpt with an inline [link]($secondUrl)."),
                )
                workflowError(CourseWorkflowError.INVALID_SOURCE) {
                    player.generate(course.id, course.revision, "practice", key, CloudSession(),
                        listOf(sources.first().copy(url = "file:///private")))
                }
                assertTrue(provider.requests.isEmpty())
                assertEquals(course, store.course(course.id))

                provider.slide = lessonSlide(CourseSlideKind.CHOICE)
                provider.sources = listOf("s2")
                player.generate(course.id, course.revision, "practice", key, CloudSession(), sources)
                val slide = checkNotNull(store.slide(course.id, "practice"))
                assertEquals(listOf(CourseSourceReference(sources[1].title, secondUrl)), slide.content.sources)
                assertEquals(lessonStages, provider.requests.map { it.generationStage })
                val prompt = provider.requests.first().turns.single().text
                assertTrue(prompt.contains("First excerpt") && prompt.contains("Second excerpt"))
                assertTrue(prompt.contains(""""id":"s1"""") && prompt.contains(""""id":"s2""""))
                for (privateValue in listOf(firstUrl, secondUrl, sources[0].title, sources[1].title)) {
                    assertFalse(provider.requests.any { request ->
                        (request.systemInstructions + request.turns.joinToString("\n") { it.text }).contains(privateValue)
                    })
                }
            }
        }
    }

    @Test fun gradesFillAndOrderOnTheHost(): Unit = runBlocking {
        withDatabase { context, name ->
            CourseStore(context, name).use { store ->
                val runtime = FixtureRuntime()
                val player = CourseController(store, runtime.generation)
                val course = store.create(setup, CourseOutline(listOf(CourseLessonPlan("lesson", "Past", listOf(
                    CourseSlidePlan("fill", CourseSlideKind.FILL_IN, "Name the form"),
                    CourseSlidePlan("order", CourseSlideKind.ORDER, "Put the steps in order"),
                )))))
                val fill = JSONObject().put("kind", "FILL_IN").put("question", "Name the form.")
                    .put("accepted", JSONArray(listOf("preterite")))
                runtime.response = { preview(definition(fill)) }
                var active = player.generate(course.id, course.revision, "fill", key, CloudSession())
                active = player.submitAnswer(course.id, active.revision, "fill", "wrong", CourseAnswer.FillIn("imperfect"))
                active = player.submitAnswer(course.id, active.revision, "fill", "right", CourseAnswer.FillIn(" preterite "))
                val order = JSONObject().put("kind", "ORDER").put("question", "Put the steps in order.")
                    .put("options", options()).put("correctOrder", JSONArray(listOf("b", "a")))
                runtime.response = { preview(definition(order)) }
                active = player.generate(course.id, active.revision, "order", key, CloudSession())
                workflowError(CourseWorkflowError.INVALID_ANSWER) {
                    player.submitAnswer(course.id, active.revision, "order", "incomplete", CourseAnswer.Order(listOf("b")))
                }
                active = player.submitAnswer(course.id, active.revision, "order", "reverse", CourseAnswer.Order(listOf("a", "b")))
                active = player.submitAnswer(course.id, active.revision, "order", "ordered", CourseAnswer.Order(listOf("b", "a")))
                assertTrue(active.progress.isComplete)
                assertEquals(listOf(false, true, false, true), store.answers(course.id).map { it.correct })
                assertTrue(runtime.events.isEmpty())
            }
        }
    }

    @Test fun discardsStaleGenerationAndPreservesWorkOnFailureAndCancellation(): Unit = runBlocking {
        withDatabase { context, name ->
            CourseStore(context, name).use { store ->
                val runtime = FixtureRuntime()
                val player = CourseController(store, runtime.generation)
                val course = store.create(setup, outline())
                runtime.response = { preview(definition(choice())) }
                val active = player.generate(course.id, course.revision, "practice", key, CloudSession())
                val answered = player.submitAnswer(course.id, active.revision, "practice", "right", CourseAnswer.Choice("a"))
                val completed = store.slide(course.id, "practice")
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                runtime.response = {
                    started.complete(Unit)
                    release.await()
                    preview(definition())
                }
                val stale = async {
                    rejected(CourseRejection.STALE_GENERATION) {
                        player.generate(course.id, answered.revision, "review", key, CloudSession())
                    }
                }
                started.await()
                val pending = checkNotNull(store.course(course.id))
                val edited = store.editOutline(course.id, pending.revision, outline("replacement"))
                release.complete(Unit)
                stale.await()
                assertNull(store.slide(course.id, "review"))
                assertEquals(edited, store.course(course.id))
                runtime.response = { throw IllegalStateException("fixture provider unavailable") }
                workflowError(CourseWorkflowError.UNAVAILABLE) {
                    player.generate(course.id, edited.revision, "replacement", key, CloudSession())
                }
                val failed = checkNotNull(store.course(course.id))
                assertEquals(CourseGenerationFailure.UNAVAILABLE, failed.generationFailure)
                runtime.response = { session -> session.cancel(); preview(definition()) }
                val cancellation = runCatching {
                    player.generate(course.id, failed.revision, "replacement", key, CloudSession())
                }.exceptionOrNull()
                assertTrue(cancellation is CancellationException)
                assertEquals(CourseGenerationFailure.INTERRUPTED, store.course(course.id)?.generationFailure)
                assertEquals(completed, store.slide(course.id, "practice"))
                assertEquals(listOf("right"), store.answers(course.id).map { it.operationId })
                assertNull(store.slide(course.id, "replacement"))
            }
        }
    }

    @Test fun refusesMissingGradeDataAndUntrustedSourcesAndChecksFullContextBudget(): Unit = runBlocking {
        withDatabase { context, name ->
            CourseStore(context, name).use { store ->
                val runtime = FixtureRuntime()
                val player = CourseController(store, runtime.generation)
                val course = store.create(setup, outline())
                runtime.response = { preview(definition()) }
                workflowError(CourseWorkflowError.INVALID_OUTPUT) {
                    player.generate(course.id, course.revision, "practice", key, CloudSession())
                }
                var active = checkNotNull(store.course(course.id))
                assertEquals(CourseGenerationFailure.INVALID_OUTPUT, active.generationFailure)
                assertNull(store.slide(course.id, "practice"))
                val surfaced = JSONObject(definition(choice()).json)
                    .put("surfaces", JSONArray().put(JSONObject("""{"id":"main","width":160,"height":160}""")))
                runtime.response = { preview(ToolSpec.parse(surfaced.toString())) }
                workflowError(CourseWorkflowError.UNSUPPORTED_OUTPUT) {
                    player.generate(course.id, active.revision, "practice", key, CloudSession())
                }
                active = checkNotNull(store.course(course.id))
                val trusted = Source("Trusted title", "https://example.org/source", "Full source text for the model.")
                val other = Source("Other result", "https://example.org/other", "The model also received this text.")
                val sources = listOf(trusted, other)
                runtime.response = { preview(definition(choice(), forgedSourceIds = JSONArray(listOf("s2")))) }
                for (rejectedIds in listOf(emptyList(), listOf("s9"), listOf("s1", "s1"))) {
                    runtime.sourceIds = rejectedIds
                    workflowError(CourseWorkflowError.INVALID_OUTPUT) {
                        player.generate(course.id, active.revision, "practice", key, CloudSession(), sources)
                    }
                    active = checkNotNull(store.course(course.id))
                    assertEquals(CourseGenerationFailure.INVALID_OUTPUT, active.generationFailure)
                    assertNull(store.slide(course.id, "practice"))
                }
                runtime.sourceIds = listOf("s1")
                active = player.generate(course.id, active.revision, "practice", key, CloudSession(), sources)
                assertTrue(runtime.prompts.last().contains(trusted.summary))
                assertTrue(runtime.prompts.last().contains(other.summary))
                assertEquals(listOf(CourseSourceReference(trusted.title, trusted.url)), store.slide(course.id, "practice")?.content?.sources)
                active = player.submitAnswer(course.id, active.revision, "practice", "correct", CourseAnswer.Choice("a"))
                val calls = runtime.prompts.size
                val oversized = trusted.copy(summary = "X".repeat(16_001))
                workflowError(CourseWorkflowError.CONTEXT_LIMIT) {
                    player.generate(course.id, active.revision, "review", key, CloudSession(), listOf(oversized))
                }
                assertEquals(calls, runtime.prompts.size)
                assertNull(store.slide(course.id, "review"))
                assertEquals(1, store.course(course.id)?.progress?.completedSlides)
                runtime.response = { preview(definition()) }
                workflowError(CourseWorkflowError.INVALID_OUTPUT) {
                    player.generate(course.id, active.revision, "review", key, CloudSession())
                }
                val current = checkNotNull(store.course(course.id))
                runtime.sourceIds = emptyList()
                player.generate(course.id, current.revision, "review", key, CloudSession())
                assertEquals(emptyList<CourseSourceReference>(), store.slide(course.id, "review")?.content?.sources)
            }
        }
    }

    @Test fun refusesToDropEarlierAnswersToFitThePrompt(): Unit = runBlocking {
        withDatabase { context, name ->
            CourseStore(context, name).use { store ->
                val runtime = FixtureRuntime()
                val player = CourseController(store, runtime.generation)
                val course = store.create(setup, CourseOutline(listOf(CourseLessonPlan("lesson", "Past", listOf(
                    CourseSlidePlan("fill", CourseSlideKind.FILL_IN, "Name the form"),
                    CourseSlidePlan("review", CourseSlideKind.SUMMARY, "Review earlier errors"),
                )))))
                val fill = JSONObject().put("kind", "FILL_IN").put("question", "Name the form.")
                    .put("accepted", JSONArray(listOf("preterite")))
                runtime.response = { preview(definition(fill)) }
                var active = player.generate(course.id, course.revision, "fill", key, CloudSession())
                repeat(5) { i ->
                    active = player.submitAnswer(course.id, active.revision, "fill", "wrong-$i", CourseAnswer.FillIn("$i".repeat(4000)))
                }
                active = player.submitAnswer(course.id, active.revision, "fill", "correct", CourseAnswer.FillIn("preterite"))
                workflowError(CourseWorkflowError.CONTEXT_LIMIT) {
                    player.generate(course.id, active.revision, "review", key, CloudSession())
                }
                assertEquals(1, runtime.prompts.size)
                assertEquals(6, store.answers(course.id).size)
                assertEquals("review", store.course(course.id)?.progress?.currentSlideId)
            }
        }
    }

    // The trusted lesson route cannot produce these hostile definitions. Inject the shared custom route.
    private fun policyFixtureController(store: CourseStore, tools: ToolGenerationService) = CourseController(
        store, CourseGeneration(
            generateTool = { key, input, session, id, policy, _ ->
                val preview = tools.generate(key, input.prompt, null, emptyList(), session, id)
                policy.validateDefinition(preview.spec.json)
                policy.validateResult(JSONObject().put("state", JSONObject(preview.state)).put("ui", JSONObject(preview.ui)).toString())
                LessonToolPreview(preview, emptyList())
            },
            evaluateTool = { spec, id, revision, state, event, ui, policy ->
                tools.evaluate(spec, id, revision, state, event, ui, policy = policy)
            },
        ),
    )

    private class PolicyProvider(private val definition: String) : ChatProvider {
        val stages = mutableListOf<CloudGenerationStage?>()

        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            stages += request.generationStage
            check(request.generationStage == CloudGenerationStage.AGENT) { "Unexpected provider call." }
            // The app agent writes the definition until the host accepts it, then finishes.
            emit(CloudEvent.Text(if (request.agentAppAccepted) agentFixtureFinish else agentWrite(definition)))
        }
    }

    private class FixtureRuntime {
        val prompts = mutableListOf<String>()
        val events = mutableListOf<JSONObject>()
        var sourceIds: List<String> = emptyList()
        var response: suspend (CloudSession) -> ToolPreview = { error("Unexpected provider call.") }
        var action: suspend (ToolSpec, JSONObject, JSONObject) -> ToolPreview = { spec, state, _ ->
            ToolPreview(spec, state.toString(), spec.ui.toString())
        }
        val generation = CourseGeneration(
            generateTool = { _, input, session, _, policy, _ ->
                prompts += input.prompt
                val preview = response(session).also { preview ->
                    policy.validateDefinition(preview.spec.json)
                    policy.validateResult(preview.rawResult())
                }
                LessonToolPreview(preview, sourceIds)
            },
            evaluateTool = { spec, _, _, state, event, _, policy ->
                policy.validateDefinition(spec.json)
                events += event
                action(spec, JSONObject(state), event).also { policy.validateResult(it.rawResult()) }
            },
        )

        private fun ToolPreview.rawResult() =
            JSONObject().put("state", JSONObject(state)).put("ui", JSONObject(ui)).toString()
    }

    private fun definition(exercise: JSONObject? = null, forgedSourceIds: JSONArray? = null): ToolSpec {
        val schema = JSONObject().put("selected", "string")
        val state = JSONObject().put("selected", "a")
        exercise?.let { schema.put("courseExerciseV1", "string"); state.put("courseExerciseV1", it.toString()) }
        forgedSourceIds?.let { schema.put("courseSourceIdsV1", "string"); state.put("courseSourceIdsV1", it.toString()) }
        val ui = if (exercise?.has("actionId") == true) {
            JSONObject("""{"type":"singleChoice","text":"Choose a form","action":"answer","bind":"selected","options":[{"text":"Preterite form","value":"a"},{"text":"Imperfect form","value":"b"}]}""")
        } else JSONObject("""{"type":"text","text":"Course content"}""")
        return ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", "Course fixture").put("description", "")
            .put("capabilities", JSONArray()).put("stateSchema", schema).put("initialState", state)
            .put("ui", ui).put("source", "return {state:input.state};").toString())
    }

    private fun preview(spec: ToolSpec) = ToolPreview(spec, spec.initialState, spec.ui.toString())

    private fun choice(correct: String = "a") = JSONObject().put("kind", "CHOICE").put("question", "Choose a form.")
        .put("options", options()).put("correct", correct).put("actionId", "answer")

    private fun options() = JSONArray().put(JSONObject().put("id", "a").put("label", "Preterite form"))
        .put(JSONObject().put("id", "b").put("label", "Imperfect form"))

    private suspend fun rejected(reason: CourseRejection, block: suspend () -> Unit) {
        assertEquals(reason, (runCatching { block() }.exceptionOrNull() as? CourseStoreException)?.reason)
    }

    private suspend fun workflowError(error: CourseWorkflowError, block: suspend () -> Unit) {
        assertEquals(error, (runCatching { block() }.exceptionOrNull() as? CourseWorkflowException)?.reason)
    }

    private suspend fun withDatabase(block: suspend (Context, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "course-generation-${UUID.randomUUID()}.db"
        try { block(context, name) } finally { context.deleteDatabase(name) }
    }

    private fun outline(last: String = "review") = CourseOutline(listOf(CourseLessonPlan("lesson", "Past tenses", listOf(
        CourseSlidePlan("practice", CourseSlideKind.CHOICE, "Choose the past tense"),
        CourseSlidePlan(last, CourseSlideKind.SUMMARY, "Review earlier errors"),
    ))))

    private val key = "PRIVATE-KEY"
    private val lessonStages = listOf(CloudGenerationStage.AGENT, CloudGenerationStage.AGENT)
    private val setup = CourseSetup("Past tense", "Beginner", "Use past tense", "PRIVATE-CONVERSATION")
}
