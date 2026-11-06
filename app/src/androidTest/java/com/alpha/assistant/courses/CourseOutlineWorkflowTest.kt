package com.alpha.assistant.courses

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CourseOutlineWorkflowTest {
    @Test fun preparesRussianOutlineAndSavesTheHostOriginOnlyOnCreate(): Unit = runBlocking {
        withStore { _, store ->
            val requests = mutableListOf<CourseOutlineModelRequest>()
            val service = CourseOutlineService(store) { request ->
                requests += request
                outline(lesson("Прошедшее время", slide("EXPLANATION", "Разобрать правило"),
                    slide("CHOICE", "Выбрать подходящую форму")))
            }
            val draft = service.propose(setup)
            assertTrue(store.courses().isEmpty())
            assertEquals(setup, draft.setup)
            assertEquals("Прошедшее время", draft.outline.lessons.single().title)
            val steps = draft.outline.steps()
            assertEquals(listOf(CourseSlideKind.EXPLANATION, CourseSlideKind.CHOICE), steps.map { it.slide.kind })
            assertEquals(2, steps.map { it.slide.id }.toSet().size)
            assertTrue(requests.single().prompt.contains("Выучить прошедшее время"))
            assertTrue(requests.single().prompt.contains("Начальный"))
            assertFalse(requests.single().prompt.contains("PRIVATE-CONVERSATION-ID"))

            val created = service.create(draft)
            assertEquals(created, store.course(created.id))
            assertEquals("PRIVATE-CONVERSATION-ID", created.setup.originatingConversationId)
            assertEquals(draft.outline, created.outline)
            assertEquals(steps.first().slide.id, created.progress.currentSlideId)
        }
    }

    @Test fun rejectsMalformedOrUnboundedModelOutputAndModelSuppliedProvenance(): Unit = runBlocking {
        withStore { _, store ->
            val good = outline(lesson("Lesson", slide("SUMMARY", "Review")))
            var response = good
            var calls = 0
            val service = CourseOutlineService(store) {
                calls++
                response
            }
            val invalid = listOf(
                "{bad",
                """{"lessons":[],"lessons":[]}""",
                """{"lessons":[]}""",
                outline(lesson("Lesson")),
                outline(lesson("Lesson", slide("UNKNOWN", "Review"))),
                outline(lesson("Lesson", slide("SUMMARY", "X".repeat(513)))),
                outline(lesson("Lesson", slide("SUMMARY", "Review", "MODEL-ID"))),
                outline(lesson("Lesson", slide("SUMMARY", "Review")).put("originatingConversationId", "invented")),
                JSONObject(good).put("sources", JSONArray().put("https://example.invalid")).toString(),
                JSONObject(good).put("setup", JSONObject().put("topic", "Replacement")).toString(),
                " ".repeat(65_537) + good,
            )
            for (candidate in invalid) {
                response = candidate
                outlineError(CourseOutlineError.INVALID_OUTPUT) { service.propose(setup) }
            }
            outlineError(CourseOutlineError.INVALID_INPUT) { service.propose(setup.copy(level = " ")) }
            assertEquals(invalid.size, calls)
            assertTrue(store.courses().isEmpty())
        }
    }

    @Test fun refinementKeepsCompletedAndAttemptedStepsAndChangesOnlyAfterApply(): Unit = runBlocking {
        withStore { _, store ->
            val before = attemptedCourse(store)
            val attempts = store.answers(before.id)
            val completed = store.slide(before.id, savedIntro)
            val attempted = store.slide(before.id, savedExercise)
            val request = "Замени последнее задание на пример."
            var response = refinedOutline()
            val service = CourseOutlineService(store) { model ->
                assertTrue(model.prompt.contains(request))
                val context = JSONObject(model.prompt.substringAfter("\nCourse context:\n"))
                assertEquals(2, context.getInt("protectedSlideCount"))
                val lesson = context.getJSONArray("outline").getJSONObject(0)
                assertEquals(1, lesson.get("id"))
                val slides = lesson.getJSONArray("slides")
                assertEquals(listOf(1, 2, 3), List(slides.length()) { slides.getJSONObject(it).get("id") })
                val privateValues = listOf(
                    before.id, savedLesson, savedIntro, savedExercise, savedReview,
                    "PRIVATE-CONVERSATION-ID", "PRIVATE-LEARNER-ANSWER",
                )
                for (value in privateValues) assertFalse(model.prompt.contains(value))
                response
            }

            response = refinedOutline(title = "Changed protected lesson")
            rejected(CourseRejection.PROTECTED_PROGRESS) { service.proposeRefinement(before.id, before.revision, request) }
            response = refinedOutline(choiceGoal = "Changed attempted slide")
            rejected(CourseRejection.PROTECTED_PROGRESS) { service.proposeRefinement(before.id, before.revision, request) }
            response = refinedOutline(reviewReference = 99)
            outlineError(CourseOutlineError.INVALID_OUTPUT) { service.proposeRefinement(before.id, before.revision, request) }
            response = refinedOutline(reviewReference = savedReview)
            outlineError(CourseOutlineError.INVALID_OUTPUT) { service.proposeRefinement(before.id, before.revision, request) }
            assertEquals(before, store.course(before.id))

            response = refinedOutline()
            val proposal = service.proposeRefinement(before.id, before.revision, request)
            assertEquals(before, store.course(before.id))
            val updated = service.apply(proposal)
            assertEquals(before.setup, updated.setup)
            assertEquals(before.progress.completedSlides, updated.progress.completedSlides)
            assertEquals(before.progress.currentSlideId, updated.progress.currentSlideId)
            assertEquals(completed, store.slide(before.id, savedIntro))
            assertEquals(attempted, store.slide(before.id, savedExercise))
            assertEquals(attempts, store.answers(before.id))
            val steps = updated.outline.steps()
            assertEquals(4, steps.size)
            assertEquals(listOf(savedIntro, savedExercise), steps.take(2).map { it.slide.id })
            assertEquals(CourseSlideKind.EXPLANATION, steps[2].slide.kind)
            assertFalse(steps[2].slide.id in listOf(savedIntro, savedExercise, savedReview))
            assertEquals(CourseSlideKind.EXAMPLE, steps[3].slide.kind)
            assertEquals(savedReview, steps[3].slide.id)
            rejected(CourseRejection.STALE_REVISION) { service.apply(proposal) }
        }
    }

    @Test fun rejectsAnOutlineResponseForAnOlderRevisionAndPreservesConcurrentWork(): Unit = runBlocking {
        withStore { _, store ->
            val before = attemptedCourse(store)
            val started = CompletableDeferred<Unit>()
            val continueModel = CompletableDeferred<Unit>()
            val service = CourseOutlineService(store) {
                started.complete(Unit)
                continueModel.await()
                refinedOutline()
            }
            val stale = async {
                rejected(CourseRejection.STALE_REVISION) {
                    service.proposeRefinement(before.id, before.revision, "Change the review.")
                }
            }
            started.await()
            val concurrent = store.editOutline(before.id, before.revision, CourseOutline(listOf(
                before.outline.lessons.single().let { lesson ->
                    lesson.copy(slides = lesson.slides.dropLast(1) + CourseSlidePlan("new", CourseSlideKind.SUMMARY, "Keep this."))
                },
            )))
            continueModel.complete(Unit)
            stale.await()
            assertEquals(concurrent, store.course(before.id))
            assertEquals(1, store.answers(before.id).size)
        }
    }

    @Test fun cancellationAndProviderFailurePreserveTheSavedCourse(): Unit = runBlocking {
        withStore { _, store ->
            val before = attemptedCourse(store)
            val started = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            val service = CourseOutlineService(store) {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    returned.await()
                    refinedOutline()
                }
            }
            val canceled = async { service.proposeRefinement(before.id, before.revision, "Change the review.") }
            started.await()
            canceled.cancel()
            returned.complete(Unit)
            assertTrue(runCatching { canceled.await() }.exceptionOrNull() is CancellationException)
            val failing = CourseOutlineService(store) { error("PRIVATE-PROVIDER-DETAIL") }
            val error = outlineError(CourseOutlineError.UNAVAILABLE) {
                failing.proposeRefinement(before.id, before.revision, "Change the review.")
            }
            assertFalse(error.toString().contains("PRIVATE-PROVIDER-DETAIL"))
            assertEquals(before, store.course(before.id))
            assertEquals(1, store.answers(before.id).size)
        }
    }

    @Test fun refusesAnOutlineContextThatCannotFitWithoutCallingTheModel(): Unit = runBlocking {
        withStore { _, store ->
            val large = CourseOutline(List(12) { lesson ->
                CourseLessonPlan("lesson$lesson", "План", List(8) { slide ->
                    CourseSlidePlan("slide${lesson}_$slide", CourseSlideKind.EXPLANATION, "Ж".repeat(450))
                })
            })
            val before = store.create(setup, large)
            var calls = 0
            val service = CourseOutlineService(store) {
                calls++
                error("The model received an oversized outline.")
            }
            outlineError(CourseOutlineError.CONTEXT_LIMIT) {
                service.proposeRefinement(before.id, before.revision, "Упростить последний урок.")
            }
            assertEquals(0, calls)
            assertEquals(before, store.course(before.id))
        }
    }

    private fun attemptedCourse(store: CourseStore): CourseSnapshot {
        val course = store.create(setup, CourseOutline(listOf(CourseLessonPlan(savedLesson, "Past tense", listOf(
            CourseSlidePlan(savedIntro, CourseSlideKind.EXPLANATION, "Introduce"),
            CourseSlidePlan(savedExercise, CourseSlideKind.FILL_IN, "Practice"),
            CourseSlidePlan(savedReview, CourseSlideKind.SUMMARY, "Review"),
        )))))
        val intro = store.commitGeneration(store.beginGeneration(course.id, course.revision, savedIntro), content)
        val reached = store.advance(course.id, intro.revision, savedIntro)
        val exercise = store.commitGeneration(store.beginGeneration(course.id, reached.revision, savedExercise), content)
        return store.commitAnswer(course.id, CourseAnswerSubmission(
            "attempt", exercise.revision, savedExercise, CourseAnswer.FillIn("PRIVATE-LEARNER-ANSWER"), false, "{}",
        ))
    }

    private fun refinedOutline(title: String = "Past tense", choiceGoal: String = "Practice", reviewReference: Any = 3) =
        outline(lesson(title, slide("EXPLANATION", "Introduce", 1), slide("FILL_IN", choiceGoal, 2),
            slide("EXPLANATION", "Добавить пояснение"), slide("EXAMPLE", "Показать пример", reviewReference), id = 1))

    private fun outline(vararg lessons: JSONObject) = JSONObject().put("lessons", JSONArray(lessons.toList())).toString()

    private fun lesson(title: String, vararg slides: JSONObject, id: Int? = null) =
        JSONObject().put("id", id ?: JSONObject.NULL).put("title", title).put("slides", JSONArray(slides.toList()))

    private fun slide(kind: String, goal: String, id: Any? = null) =
        JSONObject().put("id", id ?: JSONObject.NULL).put("kind", kind).put("goal", goal)

    private suspend fun outlineError(reason: CourseOutlineError, run: suspend () -> Unit): CourseOutlineException {
        val failure = runCatching { run() }.exceptionOrNull()
        assertTrue(failure is CourseOutlineException)
        return (failure as CourseOutlineException).also { assertEquals(reason, it.reason) }
    }

    private suspend fun rejected(reason: CourseRejection, run: suspend () -> Unit) {
        assertEquals(reason, (runCatching { run() }.exceptionOrNull() as? CourseStoreException)?.reason)
    }

    private suspend fun withStore(run: suspend (Context, CourseStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "course-outline-${UUID.randomUUID()}.db"
        try { CourseStore(context, name).use { run(context, it) } } finally { context.deleteDatabase(name) }
    }

    private val setup = CourseSetup("Испанский язык", "Начальный", "Выучить прошедшее время", "PRIVATE-CONVERSATION-ID")
    private val savedLesson = "PRIVATE-OUTLINE-LESSON"
    private val savedIntro = "PRIVATE-OUTLINE-INTRO"
    private val savedExercise = "PRIVATE-OUTLINE-EXERCISE"
    private val savedReview = "PRIVATE-OUTLINE-REVIEW"
    private val content = CourseSlideContent("{}", """{"type":"text","text":"Fixture"}""", "{}")
}
