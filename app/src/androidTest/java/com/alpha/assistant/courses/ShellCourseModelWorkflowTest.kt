package com.alpha.assistant.courses

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.ui.ShellFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellCourseModelWorkflowTest {
    @Test fun savedCoursesStayIsolatedAndTheStoreClosesWithTheModel() {
        val first = ShellFixture("course-model-first")
        val second = ShellFixture("course-model-second")
        val firstDatabase = "${first.store.databaseName}-courses.db"
        val secondDatabase = "${second.store.databaseName}-courses.db"
        try {
            val saved = CourseStore(first.context, firstDatabase).use { store ->
                store.create(
                    CourseSetup("Fractions", "Beginner", "Compare fractions"),
                    CourseOutline(listOf(CourseLessonPlan("compare", "Compare fractions", listOf(
                        CourseSlidePlan("example", CourseSlideKind.EXAMPLE, "Compare two fractions"),
                    )))),
                )
            }
            first.install(saveKey = false)
            val firstStore = first.launch().use {
                val model = checkNotNull(first.model)
                assertEquals(CoursePage.Library(listOf(saved)), CoursePresentation(model.courseStore, model.courseController).library())
                val failure = assertThrows(CourseStoreException::class.java) {
                    model.courseController.exercise(saved.id, saved.revision, "example")
                }
                assertEquals(CourseRejection.SLIDE_NOT_GENERATED, failure.reason)
                assertEquals(0, first.provider.calls)
                model.courseStore
            }
            assertEquals("The course store is closed.", assertThrows(IllegalStateException::class.java) {
                firstStore.courses()
            }.message)

            second.install(saveKey = false)
            second.launch().use {
                val model = checkNotNull(second.model)
                assertEquals(CoursePage.Library(emptyList()), CoursePresentation(model.courseStore, model.courseController).library())
                assertEquals(0, second.provider.calls)
            }
            CourseStore(first.context, firstDatabase).use { assertEquals(listOf(saved), it.courses()) }
        } finally {
            second.remove()
            first.remove()
            first.context.deleteDatabase(firstDatabase)
            second.context.deleteDatabase(secondDatabase)
        }
    }
}
