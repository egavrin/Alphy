package com.alpha.assistant.courses

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

@RunWith(AndroidJUnit4::class)
class CourseStorageWorkflowTest {
    @Test fun upgradingCourseStoragePreservesSlidesAndAnswers() = withDatabase { context, name ->
        val wrong = CourseAnswer.Choice("imperfect")
        val id = CourseStore(context, name).use { store ->
            val course = store.create(setup, outline())
            val saved = store.commitGeneration(store.beginGeneration(course.id, course.revision, "practice"), content)
            store.commitAnswer(course.id, answer(saved, "wrong", wrong, false, 1)).id
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("DROP TABLE IF EXISTS slide_scene_settings")
            database.execSQL("DROP TABLE reading_sessions")
            database.execSQL("ALTER TABLE slides DROP COLUMN narration")
            database.version = 1
        }
        CourseStore(context, name).use { store ->
            assertEquals(id, store.course(id)?.id)
            assertEquals(feedbackUi(1), store.slide(id, "practice")?.content?.uiJson)
            assertEquals(wrong, store.answers(id).single().answer)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertEquals(3, database.version)
            database.rawQuery("SELECT version FROM slide_scene_settings", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun resumesGeneratedWorkAndKeepsWrongThenCorrectAnswersInOrder() = withDatabase { context, name ->
        val wrong = CourseAnswer.Choice("imperfect")
        val right = CourseAnswer.Choice("preterite")
        val id = CourseStore(context, name).use { store ->
            val course = store.create(setup, outline())
            assertEquals("practice", course.progress.currentSlideId)
            assertNull(store.slide(course.id, "summary"))
            val operation = store.beginGeneration(course.id, course.revision, "practice")
            store.commitGeneration(operation, content)
            course.id
        }
        CourseStore(context, name).use { store ->
            val resumed = checkNotNull(store.course(id))
            assertEquals(setup, resumed.setup)
            assertEquals(content, store.slide(id, "practice")?.content)
            val incorrect = store.commitAnswer(id, answer(resumed, "wrong", wrong, false, 1))
            assertEquals("practice", incorrect.progress.currentSlideId)
            assertEquals("""{"count":1}""", store.slide(id, "practice")?.content?.stateJson)
            assertEquals(feedbackUi(1), store.slide(id, "practice")?.content?.uiJson)
        }
        CourseStore(context, name).use { store ->
            val resumed = checkNotNull(store.course(id))
            assertEquals(feedbackUi(1), store.slide(id, "practice")?.content?.uiJson)
            val correct = store.commitAnswer(id, answer(resumed, "right", right, true, 2))
            assertEquals(1, correct.progress.completedSlides)
            assertEquals("summary", correct.progress.currentSlideId)
            assertNull(store.slide(id, "summary"))
            val first = store.answers(id, limit = 1).single()
            val second = store.answers(id, afterSequence = first.sequence, limit = 1).single()
            assertEquals(listOf(wrong, right), listOf(first.answer, second.answer))
            assertEquals(listOf(false, true), listOf(first.correct, second.correct))
            assertTrue(first.sequence < second.sequence)
            assertTrue(store.answers(id, afterSequence = second.sequence).isEmpty())
            val generated = store.commitGeneration(store.beginGeneration(id, correct.revision, "summary"), content)
            assertTrue(store.advance(id, generated.revision, "summary").progress.isComplete)
        }
    }

    @Test
    fun rejectsDuplicateAndStaleWorkAndResumesGenerationFailure() = withDatabase { context, name ->
        val first = CourseStore(context, name).use { store ->
            val course = store.create(setup, outline())
            rejected(CourseRejection.SLIDE_NOT_REACHED) {
                store.beginGeneration(course.id, course.revision, "summary")
            }
            store.beginGeneration(course.id, course.revision, "practice")
        }
        CourseStore(context, name).use { store ->
            val id = first.courseId
            val resumed = checkNotNull(store.course(id))
            assertEquals(first, resumed.generation)
            val retry = store.beginGeneration(id, resumed.revision, first.slideId)
            assertNotEquals(first.id, retry.id)
            rejected(CourseRejection.STALE_GENERATION) { store.commitGeneration(first, content) }
            store.failGeneration(retry, CourseGenerationFailure.UNAVAILABLE)
        }
        CourseStore(context, name).use { store ->
            val id = first.courseId
            val failed = checkNotNull(store.course(id))
            assertEquals(CourseGenerationFailure.UNAVAILABLE, failed.generationFailure)
            assertNull(failed.generation)
            assertEquals("practice", failed.progress.currentSlideId)
            val retry = store.beginGeneration(id, failed.revision, "practice")
            val generated = store.commitGeneration(retry, content)
            assertNull(generated.generationFailure)
            rejected(CourseRejection.STALE_GENERATION) { store.commitGeneration(retry, content) }
            rejected(CourseRejection.ANSWER_TYPE_MISMATCH) {
                store.commitAnswer(id, answer(generated, "typed", CourseAnswer.FillIn("ayer"), true, 1))
            }
            val request = answer(generated, "wrong", CourseAnswer.Choice("imperfect"), false, 1)
            val wrong = store.commitAnswer(id, request)
            rejected(CourseRejection.DUPLICATE_ANSWER) { store.commitAnswer(id, request) }
            rejected(CourseRejection.DUPLICATE_ANSWER) { store.commitAnswer(id, request.copy(expectedRevision = wrong.revision)) }
            rejected(CourseRejection.STALE_REVISION) { store.commitAnswer(id, request.copy(operationId = "other")) }
            assertEquals(listOf("wrong"), store.answers(id).map { it.operationId })
            assertEquals(wrong, store.course(id))
        }
    }

    @Test
    fun rollsBackAnswerAndSlideStateWhenProgressUpdateFails() = withDatabase { context, name ->
        val previous = CourseStore(context, name).use { store ->
            val course = store.create(setup, outline())
            val generated = store.commitGeneration(store.beginGeneration(course.id, course.revision, "practice"), content)
            store.commitAnswer(course.id, answer(generated, "wrong", CourseAnswer.Choice("imperfect"), false, 1))
        }
        val request = answer(previous, "right", CourseAnswer.Choice("preterite"), true, 2)
        openFixtureDatabase(context, name).use { db ->
            db.execSQL("""CREATE TRIGGER fixture_reject_progress BEFORE UPDATE OF completed_slides ON courses
                BEGIN SELECT RAISE(ABORT, 'fixture progress failure'); END""")
        }
        CourseStore(context, name).use { store ->
            assertThrows(SQLiteConstraintException::class.java) { store.commitAnswer(previous.id, request) }
        }
        CourseStore(context, name).use { store ->
            assertEquals(previous, store.course(previous.id))
            assertEquals(listOf("wrong"), store.answers(previous.id).map { it.operationId })
            assertEquals("""{"count":1}""", store.slide(previous.id, "practice")?.content?.stateJson)
            assertEquals(feedbackUi(1), store.slide(previous.id, "practice")?.content?.uiJson)
        }
        openFixtureDatabase(context, name).use { it.execSQL("DROP TRIGGER fixture_reject_progress") }
        CourseStore(context, name).use { store ->
            assertEquals(1, store.commitAnswer(previous.id, request).progress.completedSlides)
            assertEquals(listOf("wrong", "right"), store.answers(previous.id).map { it.operationId })
            assertEquals(feedbackUi(2), store.slide(previous.id, "practice")?.content?.uiJson)
        }
    }

    @Test
    fun editsUnfinishedOutlineAndRetainsCompletedSlidesWithoutAConversation() = withDatabase { context, name ->
        val missingConversation = setup.copy(originatingConversationId = "missing-conversation")
        val id = CourseStore(context, name).use { store ->
            val course = store.create(missingConversation, outline())
            val generated = store.commitGeneration(store.beginGeneration(course.id, course.revision, "practice"), content)
            val wrong = store.commitAnswer(course.id, answer(generated, "wrong", CourseAnswer.Choice("imperfect"), false, 1))
            rejected(CourseRejection.PROTECTED_PROGRESS) { store.editOutline(course.id, wrong.revision, outline(firstGoal = "Replace attempted work")) }
            val completed = store.commitAnswer(course.id, answer(wrong, "right", CourseAnswer.Choice("preterite"), true, 2))
            val pending = store.beginGeneration(course.id, completed.revision, "summary")
            val pendingCourse = checkNotNull(store.course(course.id))
            val edited = store.editOutline(course.id, pendingCourse.revision, outline(lastId = "new-summary"))
            rejected(CourseRejection.STALE_GENERATION) { store.commitGeneration(pending, content) }
            rejected(CourseRejection.PROTECTED_PROGRESS) { store.editOutline(course.id, edited.revision, outline(firstGoal = "Replace completed work")) }
            assertEquals(2L, edited.outlineRevision)
            assertEquals(1, edited.progress.completedSlides)
            assertEquals("new-summary", edited.progress.currentSlideId)
            course.id
        }
        CourseStore(context, name).use { store ->
            val course = store.courses().single()
            assertEquals(id, course.id)
            assertEquals(missingConversation, course.setup)
            assertEquals("new-summary", course.progress.currentSlideId)
            assertEquals(1L, store.slide(id, "practice")?.outlineRevision)
            assertEquals("""{"count":2}""", store.slide(id, "practice")?.content?.stateJson)
            assertEquals(listOf("wrong", "right"), store.answers(id).map { it.operationId })
            assertNull(store.slide(id, "summary"))
        }
    }

    @Test
    fun refusesUnknownSchemaWithoutDeletingData() = withDatabase { context, name ->
        val course = CourseStore(context, name).use { it.create(setup, outline()) }
        openFixtureDatabase(context, name).use { it.version = 200 }
        CourseStore(context, name).use { store ->
            assertThrows(IllegalStateException::class.java) { store.course(course.id) }
        }
        openFixtureDatabase(context, name).use { db ->
            assertEquals(200, db.version)
            db.version = 3
        }
        CourseStore(context, name).use { store -> assertEquals(course, store.course(course.id)) }
    }

    @Test
    fun closeRequestRejectsNewOperationsBeforeAnInFlightWriteFinishes() = withDatabase { context, name ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val lessons = outline().lessons
        val blockedOutline = CourseOutline(object : AbstractList<CourseLessonPlan>() {
            override val size: Int get() = lessons.size

            override fun get(index: Int): CourseLessonPlan {
                entered.countDown()
                check(release.await(10, SECONDS)) { "The fixture did not release the course write." }
                return lessons[index]
            }
        })
        val executor = Executors.newFixedThreadPool(2)
        val store = CourseStore(context, name)
        try {
            val write = executor.submit<CourseSnapshot> { store.create(setup, blockedOutline) }
            assertTrue(entered.await(5, SECONDS))
            executor.submit {
                store.beginClose()
                assertClosed { store.courses() }
                assertClosed { store.create(setup, outline()) }
            }.get(2, SECONDS)
            assertFalse(write.isDone)
            assertFalse(context.getDatabasePath(name).exists())
            val close = executor.submit {
                closing.countDown()
                store.close()
            }
            assertTrue(closing.await(5, SECONDS))
            assertThrows(TimeoutException::class.java) { close.get(200, MILLISECONDS) }
            release.countDown()
            val saved = write.get(5, SECONDS)
            close.get(5, SECONDS)

            assertClosed { store.course(saved.id) }
            assertClosed { store.create(setup, outline()) }
            CourseStore(context, name).use { reopened -> assertEquals(listOf(saved), reopened.courses()) }
        } finally {
            release.countDown()
            store.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closingAnUnusedStoreKeepsItsDatabaseUnopened() = withDatabase { context, name ->
        val store = CourseStore(context, name)
        store.close()
        assertClosed { store.courses() }
        assertClosed { store.answers("unused", limit = 0) }
        assertFalse(context.getDatabasePath(name).exists())
    }

    private fun answer(course: CourseSnapshot, id: String, answer: CourseAnswer, correct: Boolean, count: Int) =
        CourseAnswerSubmission(id, course.revision, "practice", answer, correct, """{"count":$count}""", feedbackUi(count))

    private fun feedbackUi(count: Int) = """{"type":"text","text":"Attempt $count"}"""

    private fun assertClosed(block: () -> Unit) {
        val exception = assertThrows(IllegalStateException::class.java) { block() }
        assertEquals("The course store is closed.", exception.message)
    }

    private fun rejected(expected: CourseRejection, block: () -> Unit) {
        val exception = assertThrows(CourseStoreException::class.java) { block() }
        assertEquals(expected, exception.reason)
    }

    private fun outline(firstGoal: String = "Choose a past tense", lastId: String = "summary") = CourseOutline(
        listOf(CourseLessonPlan("lesson-1", "Past tense", listOf(
            CourseSlidePlan("practice", CourseSlideKind.CHOICE, firstGoal),
            CourseSlidePlan(lastId, CourseSlideKind.SUMMARY, "Review the forms"),
        ))),
    )

    private fun withDatabase(block: (Context, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "course-workflow-${UUID.randomUUID()}.db"
        try { block(context, name) } finally { context.deleteDatabase(name) }
    }

    private fun openFixtureDatabase(context: Context, name: String) =
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)

    private val setup = CourseSetup("Spanish past tenses", "Beginner", "Use two past tense forms")
    private val content = CourseSlideContent(
        definitionJson = """{"formatVersion":3,"title":"Practice","description":"Fixture","capabilities":[],"stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"text","text":"Choose a form"},"source":"return {state:input.state};"}""",
        uiJson = """{"type":"text","text":"Choose a form"}""",
        stateJson = """{"count":0}""",
        sources = listOf(CourseSourceReference("Real Academia Española", "https://www.rae.es/")),
        adaptationNote = "An example with ayer gives more context.",
    )
}
