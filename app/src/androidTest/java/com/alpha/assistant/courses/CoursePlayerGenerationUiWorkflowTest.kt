package com.alpha.assistant.courses

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.R
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Source
import com.alpha.assistant.tools.LessonToolPreview
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Exercise missing hosts, one request per reach, provider failure, cancellation, and a stale outline.
@RunWith(AndroidJUnit4::class)
class CoursePlayerGenerationUiWorkflowTest {
    private val robot = ShellRobot("course-player-generation")

    @Test fun generatesOnlyTheReachedSlideAndReopensItFromStorage() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = Runtime {
            entered.complete(Unit)
            release.await()
        }
        withPlayer(runtime) { store, saved ->
            openPlayer()
            waitFor(R.string.course_generation_waiting)
            robot.waitUntil { entered.isCompleted }
            assertEquals(listOf(CourseSlideGenerationRequest(saved.id, saved.revision, "explanation")), runtime.requests)
            assertFalse(robot.text(R.string.course_generation_retry) in robot.texts())
            assertNull(store.slide(saved.id, "future"))
            val otherPlayer = CoursePresentation(store, CourseController(store, runtime.generation))
            val pending = otherPlayer.player(saved.id) as CoursePage.Unavailable
            val duplicate = runBlocking {
                otherPlayer.generate(pending, CourseSlideGenerationHost { error("Unexpected concurrent host call.") }, retry = true)
            }.page as CoursePage.Unavailable
            assertEquals(CourseSlideIssue.GENERATION_BUSY, duplicate.issue)
            release.complete(Unit)
            robot.waitFor(generatedText)
            robot.back()
            waitFor(R.string.course_outline)
            click(R.string.course_resume)
            robot.waitFor(generatedText)
            assertEquals(1, runtime.requests.size)
            assertNull(store.slide(saved.id, "future"))
            click(R.string.course_next)
            robot.waitUntil { runtime.requests.size == 2 && store.slide(saved.id, "future") != null }
            assertEquals(listOf("explanation", "future"), runtime.requests.map { it.slideId })
        }
    }

    @Test fun failureKeepsTheGoalAndSavedAnswersUntilAnExplicitRetry() {
        val runtime = Runtime { attempt -> if (attempt == 1) error("Private generation failure") }
        withPlayer(runtime) { store, saved ->
            openPlayer()
            waitFor(R.string.course_slide_generation_failed)
            robot.waitFor(goal)
            assertFalse(robot.texts().any { it.contains("Private generation failure") })
            assertEquals(1, runtime.requests.size)
            assertSavedWork(store, saved)
            assertNull(store.slide(saved.id, "explanation"))
            click(R.string.course_generation_retry)
            robot.waitFor(generatedText)
            assertEquals(2, runtime.requests.size)
            assertSavedWork(store, saved)
        }
    }

    @Test fun cancelAndBackLeaveThePendingSlideForExplicitRetry() {
        val activeAttempt = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val runtime = Runtime { attempt ->
            activeAttempt.set(attempt)
            release.await()
        }
        withPlayer(runtime) { store, saved ->
            openPlayer()
            waitFor(R.string.course_generation_waiting)
            robot.waitUntil { activeAttempt.get() == 1 }
            click(R.string.course_generation_cancel)
            waitFor(R.string.course_generation_interrupted)
            robot.waitFor(goal)
            robot.waitUntil { store.course(saved.id)?.generationFailure == CourseGenerationFailure.INTERRUPTED }
            assertNull(store.slide(saved.id, "explanation"))
            assertSavedWork(store, saved)
            robot.back()
            waitFor(R.string.course_outline)
            click(R.string.course_resume)
            waitFor(R.string.course_generation_interrupted)
            assertEquals(1, runtime.requests.size)
            click(R.string.course_generation_retry)
            robot.waitUntil { activeAttempt.get() == 2 }
            robot.back()
            waitFor(R.string.course_outline)
            robot.waitUntil { store.course(saved.id)?.generationFailure == CourseGenerationFailure.INTERRUPTED }
            assertNull(store.slide(saved.id, "explanation"))
            click(R.string.course_resume)
            waitFor(R.string.course_generation_interrupted)
            assertEquals(2, runtime.requests.size)
            release.complete(Unit)
            click(R.string.course_generation_retry)
            robot.waitFor(generatedText)
            assertEquals(3, runtime.requests.size)
        }
    }

    @Test fun aLateResultCannotReplaceTheEditedReachedSlide() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = Runtime {
            entered.complete(Unit)
            release.await()
        }
        withPlayer(runtime) { store, saved ->
            openPlayer()
            robot.waitUntil { entered.isCompleted }
            val pending = checkNotNull(store.course(saved.id))
            val lesson = pending.outline.lessons.single()
            val replacement = CourseSlidePlan("replacement", CourseSlideKind.EXPLANATION, "Explain the revised goal")
            store.editOutline(saved.id, pending.revision, CourseOutline(listOf(lesson.copy(
                slides = listOf(lesson.slides.first(), replacement, lesson.slides.last()),
            ))))
            release.complete(Unit)
            waitFor(R.string.course_reloaded)
            robot.waitFor(replacement.goal)
            assertEquals(1, runtime.requests.size)
            assertNull(store.slide(saved.id, "explanation"))
            assertNull(store.slide(saved.id, replacement.id))
            assertEquals(saved.progress.completedSlides, store.course(saved.id)?.progress?.completedSlides)
            click(R.string.course_generation_retry)
            robot.waitFor(generatedText)
            assertEquals(listOf("explanation", "replacement"), runtime.requests.map { it.slideId })
        }
    }

    @Test fun missingHostExplainsUnavailabilityAndKeepsTheCoursePosition() {
        val runtime = Runtime {}
        withPlayer(runtime, provideHost = false) { store, saved ->
            openPlayer()
            waitFor(R.string.course_generation_unavailable)
            robot.waitFor(goal)
            assertEquals(emptyList<CourseSlideGenerationRequest>(), runtime.requests)
            assertEquals(saved, store.course(saved.id))
        }
    }

    private fun openPlayer() {
        robot.click("Fractions")
        click(R.string.course_resume)
    }

    @Test fun sourcedPreparationUsesTheVisibleQueryAndOpensTheSavedSources() {
        val runtime = Runtime {}
        withPlayer(runtime, search = { listOf(Source("Fixture source", "https://example.org/fractions")) }) { store, saved ->
            requestSources()
            robot.waitFor(generatedText)
            robot.waitFor("Fixture source")
            assertEquals(listOf(CourseSlideGenerationRequest(saved.id, saved.revision, "explanation") to sourceQuery),
                runtime.sourceRequests)
            assertTrue(runtime.requests.isEmpty())
            assertEquals(listOf(CourseSourceReference("Fixture source", "https://example.org/fractions")),
                store.slide(saved.id, "explanation")?.content?.sources)
            assertSavedWork(store, saved)
            robot.back()
            waitFor(R.string.course_outline)
            assertFalse(robot.text(R.string.course_sourced_open) in robot.texts())
        }
    }

    @Test fun searchProblemsKeepTheQueryAndOnlyExplicitContinueStartsPlainPreparation() {
        val runtime = Runtime {}
        val calls = AtomicInteger()
        withPlayer(runtime, search = {
            when (calls.incrementAndGet()) {
                1 -> throw CloudFailure("search_unavailable", "Private unavailable detail")
                2 -> emptyList()
                else -> throw CloudFailure("network", "Private network detail")
            }
        }) { store, saved ->
            requestSources()
            for (message in listOf(R.string.course_sourced_unavailable, R.string.course_sourced_empty, R.string.course_sourced_failed)) {
                waitFor(message)
                assertEquals(sourceQuery, robot.field(robot.text(R.string.course_sourced_query)).text.toString())
                assertFalse(robot.texts().any { it.contains("Private") })
                assertTrue(runtime.requests.isEmpty())
                assertEquals(saved, store.course(saved.id))
                if (message != R.string.course_sourced_failed) click(R.string.course_sourced_submit)
            }
            click(R.string.course_sourced_continue)
            robot.waitFor(generatedText)
            assertEquals(3, runtime.sourceRequests.size)
            assertEquals(listOf("explanation"), runtime.requests.map { it.slideId })
            assertEquals(emptyList<CourseSourceReference>(), store.slide(saved.id, "explanation")?.content?.sources)
        }
    }

    @Test fun staleSearchDoesNotPrepareTheOldReachedSlide() {
        val runtime = Runtime {}
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withPlayer(runtime, search = {
            entered.complete(Unit)
            release.await()
            listOf(Source("Fixture source", "https://example.org/fractions"))
        }) { store, saved ->
            requestSources()
            robot.waitUntil { entered.isCompleted }
            val lesson = saved.outline.lessons.single()
            val replacement = CourseSlidePlan("replacement", CourseSlideKind.EXPLANATION, "Explain the revised goal")
            val current = store.editOutline(saved.id, saved.revision, CourseOutline(listOf(lesson.copy(
                slides = listOf(lesson.slides.first(), replacement, lesson.slides.last()),
            ))))
            release.complete(Unit)
            waitFor(R.string.course_sourced_stale)
            assertEquals(current, store.course(saved.id))
            assertNull(store.slide(saved.id, "explanation"))
            assertNull(store.slide(saved.id, replacement.id))
            assertTrue(runtime.requests.isEmpty())
        }
    }

    @Test fun cancelingSearchLeavesTheOutlineAndRegularResumeStillPreparesAutomatically() {
        val runtime = Runtime {}
        val entered = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<List<Source>>()
        withPlayer(runtime, search = {
            try {
                entered.complete(Unit)
                release.await()
            } finally {
                canceled.complete(Unit)
            }
        }) { store, saved ->
            requestSources()
            robot.waitUntil { entered.isCompleted }
            click(R.string.course_sourced_cancel)
            robot.waitUntil { canceled.isCompleted }
            waitFor(R.string.course_outline)
            assertEquals(saved, store.course(saved.id))
            assertNull(store.slide(saved.id, "explanation"))
            click(R.string.course_resume)
            robot.waitFor(generatedText)
            assertEquals(listOf("explanation"), runtime.requests.map { it.slideId })
            assertEquals(1, runtime.sourceRequests.size)
        }
    }

    private fun requestSources() {
        robot.click("Fractions")
        click(R.string.course_sourced_open)
        robot.edit(robot.text(R.string.course_sourced_query), sourceQuery)
        click(R.string.course_sourced_submit)
    }

    private fun click(id: Int) = robot.click(robot.text(id))
    private fun waitFor(id: Int) = robot.waitFor(robot.text(id))

    private fun assertSavedWork(store: CourseStore, original: CourseSnapshot) {
        assertEquals(original.progress, store.course(original.id)?.progress)
        assertEquals(listOf("wrong", "correct"), store.answers(original.id).map { it.operationId })
        assertTrue(store.slide(original.id, "practice") != null)
    }

    private fun withPlayer(
        runtime: Runtime,
        provideHost: Boolean = true,
        search: (suspend (String) -> List<Source>)? = null,
        run: (CourseStore, CourseSnapshot) -> Unit,
    ) {
        val fixture = ShellFixture("course-player-generation")
        val database = "${fixture.name}-course-fixture.db"
        try {
            fixture.install(saveKey = false)
            CourseStore(fixture.context, database).use { store ->
                val saved = seed(store)
                val controller = CourseController(store, runtime.generation)
                val host = CourseSlideGenerationHost { request ->
                    runtime.requests += request
                    controller.generate(request.courseId, request.expectedRevision, request.slideId, "fixture", CloudSession())
                }
                val sourcedHost = search?.let { lookup ->
                    CourseSourcedSlideGenerationHost { request, query ->
                        runtime.sourceRequests += request to query
                        val sources = lookup(query)
                        if (sources.isEmpty()) throw CloudFailure("search_empty", "Private empty detail")
                        controller.generate(request.courseId, request.expectedRevision, request.slideId, "fixture", CloudSession(), sources)
                    }
                }
                fixture.launch().use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            MaterialTheme {
                                CourseLibraryScreen(
                                    store, controller, onExit = {}, generation = host.takeIf { provideHost },
                                    sourcedGeneration = sourcedHost,
                                )
                            }
                        }
                    }
                    run(store, saved)
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase(database)
        }
    }

    private fun seed(store: CourseStore): CourseSnapshot {
        val course = store.create(CourseSetup("Fractions", "Beginner", "Compare fractions"),
            CourseOutline(listOf(CourseLessonPlan("lesson", "Compare fractions", listOf(
                CourseSlidePlan("practice", CourseSlideKind.FILL_IN, "Name the part"),
                CourseSlidePlan("explanation", CourseSlideKind.EXPLANATION, goal),
                CourseSlidePlan("future", CourseSlideKind.SUMMARY, "Review the result"),
            )))))
        val practice = JSONObject().put("kind", "FILL_IN").put("question", "Name the part.")
            .put("accepted", JSONArray(listOf("half")))
        val spec = fixtureSpec("Saved practice", practice)
        var saved = store.commitGeneration(store.beginGeneration(course.id, course.revision, "practice"),
            CourseSlideContent(spec.json, spec.ui.toString(), spec.initialState))
        saved = store.commitAnswer(course.id, CourseAnswerSubmission("wrong", saved.revision, "practice",
            CourseAnswer.FillIn("whole"), false, spec.initialState))
        return store.commitAnswer(course.id, CourseAnswerSubmission("correct", saved.revision, "practice",
            CourseAnswer.FillIn("half"), true, spec.initialState))
    }

    private class Runtime(beforeResult: suspend (Int) -> Unit) {
        val requests = CopyOnWriteArrayList<CourseSlideGenerationRequest>()
        val sourceRequests = CopyOnWriteArrayList<Pair<CourseSlideGenerationRequest, String>>()
        private val calls = AtomicInteger()
        val generation = CourseGeneration(
            generateTool = { _, input, _, _, policy, _ ->
                beforeResult(calls.incrementAndGet())
                val spec = fixtureSpec(generatedText)
                policy.validateDefinition(spec.json)
                policy.validateResult(JSONObject().put("ui", spec.ui).put("state", JSONObject(spec.initialState)).toString())
                LessonToolPreview(ToolPreview(spec, spec.initialState, spec.ui.toString()), input.sources.keys.toList())
            },
            evaluateTool = { _, _, _, _, _, _, _ -> error("Unexpected generated event.") },
        )
    }

    companion object {
        private const val goal = "Explain the fraction"
        private const val generatedText = "Generated course explanation"
        private const val sourceQuery = "Examples of comparing fractions"

        private fun fixtureSpec(text: String, exercise: JSONObject? = null): ToolSpec {
            val raw = JSONObject(
                """{"formatVersion":3,"title":"Fixture","description":"","capabilities":[],"stateSchema":{"value":"string"},
                    "initialState":{"value":"a"},"source":"return {state:input.state};"}""")
                .put("ui", JSONObject().put("type", "text").put("text", text))
            if (exercise != null) {
                raw.getJSONObject("stateSchema").put(EXERCISE_FIELD, "string")
                raw.getJSONObject("initialState").put(EXERCISE_FIELD, exercise.toString())
            }
            return ToolSpec.parse(raw.toString())
        }
    }
}
