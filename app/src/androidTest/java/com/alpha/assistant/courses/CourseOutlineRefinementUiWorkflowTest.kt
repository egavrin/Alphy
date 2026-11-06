package com.alpha.assistant.courses

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

// Exercise proposal-only work, confirmation, pending cancellation, model failure, and stale confirmation.
@RunWith(AndroidJUnit4::class)
class CourseOutlineRefinementUiWorkflowTest {
    private val robot = ShellRobot("course-outline-refinement")

    @Test fun reviewAndCancelLeaveTheOutlineAndProgressUntilExplicitConfirmation() =
        withOutline({ response() }) { store, original, applies ->
            requestRevision()
            waitFor(R.string.course_refinement_review)
            robot.waitFor(revisedGoal)
            assertEquals(0, applies.get())
            assertEquals(original, store.course(original.id))

            click(R.string.course_refinement_cancel)
            waitFor(R.string.course_outline)
            assertEquals(original, store.course(original.id))
            assertEquals(0, applies.get())

            requestRevision()
            waitFor(R.string.course_refinement_review)
            click(R.string.course_refinement_apply)
            robot.waitFor(revisedGoal)
            robot.waitUntil { store.course(original.id)?.outline == outline(revisedGoal) }
            val updated = checkNotNull(store.course(original.id))
            assertEquals(original.progress, updated.progress)
            assertEquals(original.outline.steps().first(), updated.outline.steps().first())
            assertEquals(1, applies.get())
        }

    @Test fun failedProposalKeepsTheInstructionAndDoesNotExposeTheError() {
        val calls = AtomicInteger()
        withOutline({
            if (calls.incrementAndGet() == 1) error("Private refinement failure")
            response()
        }) { store, original, applies ->
            requestRevision()
            waitFor(R.string.course_refinement_request_failed)
            assertEquals(instruction, robot.field(robot.text(R.string.course_refinement_instruction)).text.toString())
            assertFalse(robot.texts().any { it.contains("Private refinement failure") })
            assertEquals(original, store.course(original.id))
            click(R.string.course_refinement_request)
            waitFor(R.string.course_refinement_review)
            assertEquals(2, calls.get())
            assertEquals(0, applies.get())
        }
    }

    @Test fun cancelWhilePendingDiscardsALateProposal() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        withOutline({
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                finished.complete(Unit)
                response()
            }
        }) { store, original, applies ->
            requestRevision()
            waitFor(R.string.course_refinement_waiting)
            robot.waitUntil { started.isCompleted }
            click(R.string.course_refinement_cancel)
            waitFor(R.string.course_outline)
            release.complete(Unit)
            robot.waitUntil { finished.isCompleted }
            assertFalse(robot.text(R.string.course_refinement_review) in robot.texts())
            assertEquals(original, store.course(original.id))
            assertEquals(0, applies.get())
        }
    }

    @Test fun staleConfirmationPreservesTheNewerSavedOutline() =
        withOutline({ response() }) { store, original, applies ->
            requestRevision()
            waitFor(R.string.course_refinement_review)
            val concurrent = store.editOutline(original.id, original.revision, outline("Another saved goal"))
            click(R.string.course_refinement_apply)
            waitFor(R.string.course_refinement_stale)
            assertEquals(concurrent, store.course(original.id))
            assertEquals(1, applies.get())
            assertFalse(robot.text(R.string.course_refinement_apply) in robot.texts())
        }

    private fun requestRevision() {
        click(R.string.course_refinement_open)
        robot.edit(robot.text(R.string.course_refinement_instruction), instruction)
        click(R.string.course_refinement_request)
    }

    private fun click(id: Int) = robot.click(robot.text(id))
    private fun waitFor(id: Int) = robot.waitFor(robot.text(id))

    private fun withOutline(
        reply: suspend () -> String,
        run: (CourseStore, CourseSnapshot, AtomicInteger) -> Unit,
    ) {
        val fixture = ShellFixture("course-outline-refinement")
        val database = "${fixture.name}-course-fixture.db"
        try {
            fixture.install(saveKey = false)
            CourseStore(fixture.context, database).use { store ->
                val course = store.create(CourseSetup("Fractions", "Beginner", "Compare fractions"), outline("Review fractions"))
                val ui = """{"type":"text","text":"Saved introduction"}"""
                val state = """{"value":"a"}"""
                val definition = """{"formatVersion":3,"title":"Fixture","description":"","capabilities":[],
                    "stateSchema":{"value":"string"},"initialState":$state,"ui":$ui,"source":"return {state:input.state};"}"""
                val first = store.commitGeneration(store.beginGeneration(course.id, course.revision, "intro"),
                    CourseSlideContent(definition, ui, state))
                val saved = store.advance(first.id, first.revision, "intro")
                val service = CourseOutlineService(store) { reply() }
                val applies = AtomicInteger()
                val host = CourseOutlineRefinementHost(service::proposeRefinement) {
                    applies.incrementAndGet()
                    service.apply(it)
                }
                val controller = CourseController(store, CourseGeneration(
                    generateTool = { _, _, _, _, _, _ -> error("Unexpected slide generation.") },
                    evaluateTool = { _, _, _, _, _, _, _ -> error("Unexpected generated event.") },
                ))
                fixture.launch().use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            MaterialTheme {
                                CourseLibraryScreen(store, controller, onExit = {}, refinement = host)
                            }
                        }
                    }
                    robot.click("Fractions")
                    waitFor(R.string.course_outline)
                    run(store, saved, applies)
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase(database)
        }
    }

    companion object {
        private const val revisedGoal = "Compare two fractions with the same denominator"
        private const val instruction = "Change the final lesson goal"

        private fun outline(lastGoal: String) = CourseOutline(listOf(
            CourseLessonPlan("lesson", "Fractions", listOf(
                CourseSlidePlan("intro", CourseSlideKind.EXPLANATION, "Introduce fractions"),
                CourseSlidePlan("review", CourseSlideKind.SUMMARY, lastGoal),
            )),
        ))

        private fun response(): String = """
            {"lessons":[{"id":1,"title":"Fractions","slides":[
                {"id":1,"kind":"EXPLANATION","goal":"Introduce fractions"},
                {"id":2,"kind":"SUMMARY","goal":"$revisedGoal"}
            ]}]}
        """.trimIndent()
    }
}
