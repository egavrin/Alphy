package com.alpha.assistant.courses

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.data.Draft
import com.alpha.assistant.ui.ShellDestination
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellCourseDesktopModelWorkflowTest {
    @Test fun desktopResumeUsesOnlyTheCurrentLocalCourseRequest() {
        val shell = ShellFixture("course-desktop-model")
        val robot = ShellRobot("course-desktop-model")
        val database = "${shell.store.databaseName}-courses.db"
        val models = ViewModelStore()
        val owner = Any()
        val outline = CourseOutline(listOf(CourseLessonPlan("lesson", "Fractions", listOf(
            CourseSlidePlan("example", CourseSlideKind.EXAMPLE, "Compare two fractions"),
        ))))
        try {
            shell.seedChat("other", "Other chat", "Keep this conversation")
            CourseStore(shell.context, database).use { store ->
                val saved = store.create(CourseSetup("Fractions", "Beginner", "Compare fractions"), outline)
                val other = store.create(CourseSetup("Decimals", "Beginner", "Compare decimals"), outline)
                lateinit var model: ShellViewModel
                robot.instrumentation.runOnMainSync {
                    model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(
                            shell.context.applicationContext as Application, shell.provider, shell.store, shell.keys,
                        ) as T
                    })[ShellViewModel::class.java]
                }
                robot.waitUntil { !model.loading }
                fun finish(action: () -> Job?) {
                    var job: Job? = null
                    robot.instrumentation.runOnMainSync { job = action() }
                    runBlocking { checkNotNull(job).join() }
                }
                robot.instrumentation.runOnMainSync {
                    model.attachCourseChat(owner)
                    model.editDraft(Draft("Keep the desktop draft"))
                }

                finish { model.resumeDesktopCourse("missing-course") }
                finish { model.resumeDesktopCourse(saved.id).also { model.detachCourseChat(owner) } }
                finish {
                    model.attachCourseChat(owner)
                    model.resumeDesktopCourse(saved.id).also { model.select("other") }
                }
                robot.instrumentation.runOnMainSync {
                    assertNull(model.courseEntry)
                    assertFalse(model.surfacePending)
                    assertEquals("other", model.snapshot.selectedId)
                    model.select(null)
                }

                finish {
                    model.resumeDesktopCourse(other.id)
                    model.resumeDesktopCourse(saved.id)
                }
                robot.instrumentation.runOnMainSync {
                    val entry = checkNotNull(model.courseEntry)
                    assertEquals(saved.id, entry.request.courseId)
                    assertEquals(CourseEntryDestination.REACHED_SLIDE, entry.request.destination)
                    assertFalse(entry.inChat || entry.request.exitToHostOnBack)
                    assertTrue(model.surfacePending)
                    assertEquals(ShellDestination.Courses, model.surfaceDestination)
                    assertEquals(1, model.surfaceSequence)
                    assertNull(model.snapshot.selectedId)
                    assertEquals("Keep the desktop draft", model.snapshot.draft.text)
                }
                assertEquals(0, shell.provider.calls)
                assertEquals(saved, store.course(saved.id))
                assertEquals(other, store.course(other.id))
            }
        } finally {
            robot.instrumentation.runOnMainSync { models.clear() }
            shell.remove()
            shell.context.deleteDatabase(database)
        }
    }
}
