package com.alpha.assistant.courses

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.data.Draft
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellCourseChatOutlineModelWorkflowTest {
    @Test fun outlineEntryStaysInTheSelectedChatAndKeepsCourseProgress() {
        val shell = ShellFixture("course-chat-outline-model")
        val robot = ShellRobot("course-chat-outline-model")
        val database = "${shell.store.databaseName}-courses.db"
        val outline = CourseOutline(listOf(CourseLessonPlan("lesson", "Fractions", listOf(
            CourseSlidePlan("example", CourseSlideKind.EXAMPLE, "Compare two fractions"),
        ))))
        try {
            shell.seedChat("selected", "Selected chat", "Current conversation", selected = true)
            shell.seedChat("other", "Other chat", "Unrelated conversation")
            shell.install(saveKey = false)
            CourseStore(shell.context, database).use { store ->
                val linked = store.create(CourseSetup("Fractions", "Beginner", "Compare fractions", "selected"), outline)
                val other = store.create(CourseSetup("Fractions", "Beginner", "Compare fractions", "other"), outline)
                shell.launch().use { scenario ->
                    val model = checkNotNull(shell.model)
                    robot.waitUntil { !model.loading && model.chatCourses.singleOrNull()?.id == linked.id }
                    scenario.onActivity {
                        model.editDraft(Draft("Keep the current chat draft"))
                        val snapshot = model.snapshot
                        model.openChatCourseOutline(other.id)
                        model.openChatCourseOutline("forged-course")
                        assertNull(model.courseEntry)
                        model.openChatCourseOutline(linked.id)
                        val entry = checkNotNull(model.courseEntry)
                        assertTrue(entry.inChat && entry.request.exitToHostOnBack)
                        assertEquals(linked.id, entry.request.courseId)
                        assertEquals(CourseEntryDestination.OUTLINE, entry.request.destination)
                        assertEquals(snapshot, model.snapshot)
                    }
                    assertEquals(linked, store.course(linked.id))
                    assertEquals(0, shell.provider.calls)

                    scenario.onActivity {
                        val first = checkNotNull(model.courseEntry)
                        model.openChatCourseOutline(linked.id)
                        val second = checkNotNull(model.courseEntry)
                        assertNotEquals(first.request.requestId, second.request.requestId)
                        model.closeChatCourse(first.request.requestId)
                        assertEquals(second, model.courseEntry)

                        model.resumeChatCourse(linked.id)
                        val continued = checkNotNull(model.courseEntry)
                        assertEquals(CourseEntryDestination.REACHED_SLIDE, continued.request.destination)
                        model.closeChatCourse(second.request.requestId)
                        assertEquals(continued, model.courseEntry)

                        model.select("other")
                        assertNull(model.courseEntry)
                        model.openChatCourseOutline(linked.id)
                        assertNull(model.courseEntry)
                    }
                    assertEquals(linked, store.course(linked.id))
                    assertEquals(other, store.course(other.id))
                    assertEquals(0, shell.provider.calls)
                }
            }
        } finally {
            shell.remove()
            shell.context.deleteDatabase(database)
        }
    }
}
