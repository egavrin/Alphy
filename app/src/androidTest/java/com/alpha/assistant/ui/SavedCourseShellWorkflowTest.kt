package com.alpha.assistant.ui

import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.courses.CourseAnswer
import com.alpha.assistant.courses.CourseLessonPlan
import com.alpha.assistant.courses.CourseOutline
import com.alpha.assistant.courses.CourseSetup
import com.alpha.assistant.courses.CourseSlideContent
import com.alpha.assistant.courses.CourseSlideKind
import com.alpha.assistant.courses.CourseSlidePlan
import com.alpha.assistant.courses.CourseStore
import com.alpha.assistant.courses.EXERCISE_FIELD
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedCourseShellWorkflowTest {
    private val robot = ShellRobot("saved-course-shell")

    @Test fun savedCourseRestoresPlayerAndReturnsToTheOriginalChat() = withFixture { fixture ->
        seedCourse(fixture, includeFutureSlide = false)
        fixture.seedChat("course-chat", "Original discussion", "Original discussion message", selected = true)
        fixture.install(saveKey = false)

        fixture.launch().use { scenario ->
            robot.waitFor("Original discussion message")
            val composer = robot.text(R.string.composer_label)
            robot.edit(composer, "Original chat draft")
            openCourses(scenario)
            robot.click(COURSE_TITLE)
            robot.waitFor(robot.text(R.string.course_outline))
            robot.waitFor("Read a saved example")
            robot.click(robot.text(R.string.course_open))
            robot.waitFor(SLIDE_TEXT)
            robot.waitFor(robot.text(R.string.course_slide_position, 1, 1))

            scenario.recreate()
            robot.waitFor(SLIDE_TEXT)
            robot.waitFor(robot.text(R.string.course_slide_position, 1, 1))
            robot.waitGone(robot.text(R.string.menu))
            robot.diagnostics.capture("restored-player")

            robot.back()
            robot.waitGone(SLIDE_TEXT)
            robot.waitFor(robot.text(R.string.course_outline))
            robot.openDrawer(scenario)
            robot.back()
            robot.awaitDrawerClosed(scenario)
            robot.waitFor(robot.text(R.string.course_outline))
            robot.back()
            robot.waitGone(robot.text(R.string.course_outline))
            robot.waitFor(robot.text(R.string.course_library))
            robot.back()
            robot.waitFor("Original discussion message")
            assertEquals("Original chat draft", robot.field(composer).text.toString())

            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.settings))
            robot.awaitDrawerClosed(scenario)
            robot.click(robot.text(R.string.settings_deepseek))
            robot.reveal(robot.text(R.string.key_label))
            assertTrue(robot.composed(scenario, "settings-detail"))
            robot.back()
            robot.back()
            robot.waitFor("Original discussion message")
            robot.waitUntil { !robot.composed(scenario, "settings-pane") }
        }
        assertEquals(0, fixture.provider.calls)
        val closedStore = checkNotNull(fixture.model).courseStore
        assertEquals("The course store is closed.", runCatching { closedStore.courses() }.exceptionOrNull()?.message)
        CourseStore(fixture.context, databaseName(fixture)).use {
            assertEquals(listOf(COURSE_TITLE), it.courses().map { course -> course.setup.topic })
        }
    }

    @Test fun finalChoiceFeedbackSurvivesRecreationUntilFinish() = withFixture { fixture ->
        val saved = CourseStore(fixture.context, databaseName(fixture)).use { store ->
            val course = store.create(CourseSetup(COURSE_TITLE, "Beginner", "Practice saved examples"),
                CourseOutline(listOf(CourseLessonPlan("lesson", "Saved lesson", listOf(
                    CourseSlidePlan("choice", CourseSlideKind.CHOICE, "Choose the next letter"),
                )))))
            val exercise = """{"kind":"CHOICE","question":"Which letter follows A?","options":[
                {"id":"b","label":"B"},{"id":"c","label":"C"}],"correct":"b"}"""
            val state = JSONObject().put(EXERCISE_FIELD, exercise)
            val ui = """{"type":"text","text":"$SLIDE_TEXT"}"""
            val definition = JSONObject("""{"formatVersion":3,"title":"Saved course fixture","description":"",
                "capabilities":[],"ui":$ui,"source":"return {state:input.state};"}""")
                .put("stateSchema", JSONObject().put(EXERCISE_FIELD, "string"))
                .put("initialState", state)
            store.commitGeneration(store.beginGeneration(course.id, course.revision, "choice"),
                CourseSlideContent(definition.toString(), ui, state.toString()))
        }
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openCourses(scenario)
            robot.click(COURSE_TITLE)
            robot.click(robot.text(R.string.course_open))
            robot.click("B")
            val correct = robot.text(R.string.course_answer_correct)
            robot.waitFor(correct)
            assertTrue(fixture.model?.courseStore?.course(saved.id)?.progress?.isComplete == true)
            assertFalse(robot.text(R.string.course_complete) in robot.texts())

            scenario.recreate()
            robot.waitFor(SLIDE_TEXT)
            robot.waitFor(correct)
            assertFalse(robot.text(R.string.course_next) in robot.texts())
            val store = checkNotNull(fixture.model).courseStore
            assertEquals(listOf(CourseAnswer.Choice("b")), store.answers(saved.id).map { it.answer })
            robot.diagnostics.capture("restored-final-choice-feedback")
            robot.click("Завершить")
            robot.waitFor(robot.text(R.string.course_complete))
            assertEquals(1, store.answers(saved.id).size)
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun aSecondShellShowsItsOwnEmptyCourseLibrary() = withFixture { seeded ->
        seedCourse(seeded)
        withFixture { empty ->
            empty.install()
            empty.launch().use { scenario ->
                robot.waitUntil { robot.composed(scenario, "desktop") }
                openCourses(scenario)
                robot.waitFor(robot.text(R.string.course_empty))
                robot.waitGone(COURSE_TITLE)
                robot.diagnostics.capture("isolated-empty-library")
                robot.click(robot.text(R.string.course_exit))
                robot.waitGone(robot.text(R.string.course_library))
                robot.waitUntil { robot.composed(scenario, "desktop") }
                assertFalse(robot.composed(scenario, "settings-pane"))
            }
            assertEquals(0, empty.provider.calls)
        }
        CourseStore(seeded.context, databaseName(seeded)).use {
            assertEquals(listOf(COURSE_TITLE), it.courses().map { course -> course.setup.topic })
        }
    }

    private fun openCourses(scenario: ActivityScenario<CloudTestActivity>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.course_learning))
        robot.awaitDrawerClosed(scenario)
        robot.waitFor(robot.text(R.string.course_library))
    }

    internal companion object {
        const val COURSE_TITLE = "Saved course for the shell"
        const val SLIDE_TEXT = "Saved shell course slide"

        fun seedCourse(fixture: ShellFixture, includeFutureSlide: Boolean = true) {
            val ui = """{"type":"text","text":"$SLIDE_TEXT"}"""
            val definition = """{"formatVersion":3,"title":"Saved course fixture","description":"","capabilities":[],
                "stateSchema":{"value":"string"},"initialState":{"value":""},"ui":$ui,"source":"return {state:input.state};"}"""
            CourseStore(fixture.context, databaseName(fixture)).use { store ->
                val slides = buildList {
                    add(CourseSlidePlan("example", CourseSlideKind.EXPLANATION, "Read a saved example"))
                    if (includeFutureSlide) add(CourseSlidePlan("summary", CourseSlideKind.SUMMARY, "Review the example"))
                }
                val course = store.create(CourseSetup(COURSE_TITLE, "Beginner", "Practice saved examples"),
                    CourseOutline(listOf(CourseLessonPlan("lesson", "Saved lesson", slides))))
                val operation = store.beginGeneration(course.id, course.revision, "example")
                store.commitGeneration(operation, CourseSlideContent(definition, ui, """{"value":""}"""))
            }
        }

        fun databaseName(fixture: ShellFixture) = "${fixture.store.databaseName}-courses.db"
    }

    private fun withFixture(test: (ShellFixture) -> Unit) {
        val fixture = ShellFixture("saved-course-shell")
        try {
            test(fixture)
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase(databaseName(fixture))
        }
    }
}
