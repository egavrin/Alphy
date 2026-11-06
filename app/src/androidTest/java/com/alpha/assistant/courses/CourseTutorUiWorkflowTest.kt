package com.alpha.assistant.courses

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature as testFoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

// Exercise missing hosts, failed replies, cancellation, private context, and the fold hinge.
@RunWith(AndroidJUnit4::class)
class CourseTutorUiWorkflowTest {
    @get:Rule val folds = WindowLayoutInfoPublisherRule()
    private val robot = ShellRobot("course-tutor")

    @Test fun missingHostShowsUnavailabilityAndBackKeepsTheSavedSlide() = withPlayer { _, store, saved ->
        openSlide()
        openTutor()
        robot.waitFor(robot.text(R.string.course_tutor_unavailable))
        assertFalse(robot.text(R.string.course_tutor_send) in robot.texts())
        robot.back()
        robot.waitFor("Saved explanation")
        robot.back()
        robot.waitFor(robot.text(R.string.course_outline))
        assertEquals(saved, store.course(saved.id))
    }

    @Test fun failedAnswerKeepsTheQuestionAndExplicitRetryUsesOnlyThisCourse() {
        val requests = CopyOnWriteArrayList<CourseTutorRequest>()
        val tutor = CourseTutorHost { request ->
            requests += request
            if (requests.size == 1) error("Private tutor exception")
            "Tutor fixture answer"
        }
        withPlayer(tutor) { _, store, saved ->
            openSlide()
            openTutor()
            val question = robot.text(R.string.course_tutor_question)
            robot.edit(question, "How does this work?")
            click(R.string.course_tutor_send)
            robot.waitFor(robot.text(R.string.course_tutor_failed))
            assertFalse(robot.texts().any { it.contains("Private tutor exception") })
            assertEquals("How does this work?", robot.field(question).text.toString())
            click(R.string.course_tutor_send)
            robot.waitFor("Tutor fixture answer")

            assertEquals(2, requests.size)
            val request = requests.last()
            assertEquals(saved.id, request.courseId)
            assertEquals(saved.revision, request.expectedRevision)
            assertEquals("second", request.slideId)
            assertEquals("How does this work?", request.question)
            assertEquals(listOf("Saved explanation"), request.slide.text)
            assertEquals("Explain the result", request.slide.goal)
            assertEquals(listOf(CourseAnswer.FillIn("earlier wrong"), CourseAnswer.FillIn("earlier correct")),
                request.earlierAnswers.map { it.answer })
            assertEquals(listOf(false, true), request.earlierAnswers.map { it.correct })
            assertTrue(request.history.isEmpty())
            assertEquals(saved, store.course(saved.id))
            assertEquals(2, store.answers(saved.id).size)
        }
    }

    @Test fun cancelKeepsTheQuestionAndDiscardsThePendingReply() {
        val pending = CompletableDeferred<String>()
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val tutor = CourseTutorHost {
            try {
                started.complete(Unit)
                pending.await()
            } finally {
                canceled.complete(Unit)
            }
        }
        withPlayer(tutor) { _, store, saved ->
            openSlide()
            openTutor()
            val question = robot.text(R.string.course_tutor_question)
            robot.edit(question, "Keep this question")
            click(R.string.course_tutor_send)
            robot.waitFor(robot.text(R.string.course_tutor_waiting))
            robot.waitUntil { started.isCompleted }
            click(R.string.course_tutor_cancel)
            robot.waitFor(robot.text(R.string.course_tutor_canceled))
            robot.waitUntil { canceled.isCompleted }
            pending.complete("Late reply")
            assertEquals("Keep this question", robot.field(question).text.toString())
            assertFalse("Late reply" in robot.texts())
            assertEquals(saved, store.course(saved.id))
        }
    }

    @Test fun tutorRequestIdentifiesTheExerciseHeldForCorrectFeedback() {
        val requests = CopyOnWriteArrayList<CourseTutorRequest>()
        val tutor = CourseTutorHost { request ->
            requests += request
            "Tutor fixture answer"
        }
        withPlayer(tutor, startAtExercise = true) { _, store, saved ->
            robot.click("Fractions")
            click(R.string.course_open)
            robot.edit(robot.text(R.string.course_answer_label), "earlier correct")
            click(R.string.course_check)
            robot.waitFor(robot.text(R.string.course_answer_correct))
            val committed = checkNotNull(store.course(saved.id))
            assertEquals("second", committed.progress.currentSlideId)

            openTutor()
            robot.edit(robot.text(R.string.course_tutor_question), "Why is this correct?")
            click(R.string.course_tutor_send)
            robot.waitFor("Tutor fixture answer")
            val request = requests.single()
            assertEquals(saved.id, request.courseId)
            assertEquals(committed.revision, request.expectedRevision)
            assertEquals("first", request.slideId)
            assertEquals(listOf(true), request.earlierAnswers.map { it.correct })
        }
    }

    @Test fun bookFoldPlacesTheOpenTutorBesideTheSlide() = withPlayer { scenario, _, _ ->
        var tablet = false
        scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            tablet = activity.window.decorView.width >= 900 * density
        }
        assumeTrue("Use a test window at least 900 dp wide", tablet)
        openSlide()
        val hinge = publishBookHinge(scenario)
        openTutor()
        val tutor = robot.text(R.string.course_tutor_unavailable)
        robot.waitUntil {
            val slideBounds = robot.bounds("Saved explanation")
            val tutorBounds = robot.bounds(tutor)
            (slideBounds.right <= hinge.left && tutorBounds.left >= hinge.right) ||
                (tutorBounds.right <= hinge.left && slideBounds.left >= hinge.right)
        }
    }

    private fun openSlide() {
        robot.click("Fractions")
        click(R.string.course_resume)
        robot.waitFor("Saved explanation")
    }

    private fun openTutor() = click(R.string.course_tutor_open)

    private fun click(id: Int) = robot.click(robot.text(id))

    private fun publishBookHinge(scenario: ActivityScenario<CloudTestActivity>): android.graphics.Rect {
        lateinit var bounds: android.graphics.Rect
        scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val hinge = testFoldingFeature(activity, center = (450 * density).roundToInt(),
                size = (24 * density).roundToInt(), orientation = FoldingFeature.Orientation.VERTICAL)
            bounds = hinge.bounds
            folds.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(hinge)))
        }
        return bounds
    }

    private fun withPlayer(
        tutor: CourseTutorHost? = null,
        startAtExercise: Boolean = false,
        run: (ActivityScenario<CloudTestActivity>, CourseStore, CourseSnapshot) -> Unit,
    ) {
        val fixture = ShellFixture("course-tutor")
        val database = "${fixture.name}-course-fixture.db"
        try {
            fixture.install(saveKey = false)
            CourseStore(fixture.context, database).use { store ->
                val attempts = if (startAtExercise) emptyList() else listOf("earlier wrong", "earlier correct")
                val saved = seed(store, "Fractions", attempts)
                seed(store, "Unrelated course", listOf("Private other answer"))
                val controller = CourseController(store, CourseGeneration(
                    generateTool = { _, _, _, _, _, _ -> error("Unexpected provider call.") },
                    evaluateTool = { _, _, _, _, _, _, _ -> error("Unexpected generated event.") },
                ))
                fixture.launch().use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            MaterialTheme {
                                CourseLibraryScreen(store, controller, onExit = {}, tutor = tutor)
                            }
                        }
                    }
                    run(scenario, store, saved)
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase(database)
        }
    }

    private fun seed(store: CourseStore, title: String, attempts: List<String>): CourseSnapshot {
        val course = store.create(CourseSetup(title, "Beginner", "Compare fractions"),
            CourseOutline(listOf(CourseLessonPlan("lesson", "Compare fractions", listOf(
                CourseSlidePlan("first", CourseSlideKind.FILL_IN, "Name the part"),
                CourseSlidePlan("second", CourseSlideKind.EXPLANATION, "Explain the result"),
            )))))
        val earlier = content(
            """{"type":"text","text":"Previous exercise"}""",
            JSONObject("""{"kind":"FILL_IN","question":"Name the part.","accepted":["earlier correct"]}"""),
        )
        var saved = store.commitGeneration(store.beginGeneration(course.id, course.revision, "first"), earlier)
        if (attempts.isEmpty()) return saved
        attempts.forEachIndexed { index, answer ->
            saved = store.commitAnswer(course.id, CourseAnswerSubmission("answer-$index", saved.revision, "first",
                CourseAnswer.FillIn(answer), index == attempts.lastIndex, earlier.stateJson))
        }
        val slide = content("""{"type":"column","children":[{"type":"text","text":"Saved explanation"},
            {"type":"text","text":"Private invisible text","visible":false}]}""")
        return store.commitGeneration(store.beginGeneration(course.id, saved.revision, "second"), slide)
    }

    private fun content(ui: String, exercise: JSONObject? = null): CourseSlideContent {
        val state = JSONObject("""{"privateValue":"Private saved state"}""")
        val spec = JSONObject("""{"formatVersion":3,"title":"Fixture","description":"","capabilities":[],
            "stateSchema":{"privateValue":"string"},"source":"return {state:input.state};"}""")
            .put("ui", JSONObject(ui)).put("initialState", state)
        if (exercise != null) {
            spec.getJSONObject("stateSchema").put(EXERCISE_FIELD, "string")
            state.put(EXERCISE_FIELD, exercise.toString())
        }
        return CourseSlideContent(spec.toString(), ui, state.toString())
    }
}
