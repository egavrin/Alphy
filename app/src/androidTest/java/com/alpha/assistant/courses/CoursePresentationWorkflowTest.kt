package com.alpha.assistant.courses

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CoursePresentationWorkflowTest {
    @Test fun emptyLibraryAndLoadFailureAreDifferent() = workflow { store, player, context, name ->
        assertEquals(CoursePage.Library(emptyList()), player.library())
        store.close()
        database(context, name).use { it.version = 200 }
        assertEquals(CoursePage.LoadFailed, player.library())
    }

    @Test fun opensOnlySavedContentAndShowsOnlySavedSources() = workflow { store, player, _, _ ->
        val source = CourseSourceReference("RAE", "https://www.rae.es/")
        val saved = create(store, CourseSlideKind.EXPLANATION, CourseSlideKind.SUMMARY)
        saveSlide(store, saved, slide(sources = listOf(source), note = "An example with ayer."))
        assertEquals(listOf(saved.id), (player.library() as CoursePage.Library).courses.map { it.id })
        assertEquals(saved.outline, (player.outline(saved.id) as CoursePage.Outline).course.outline)
        val first = openSlide(player, saved.id)
        assertEquals(listOf(source), first.sources)
        assertEquals("An example with ayer.", first.adaptationNote)
        assertEquals("Saved course content", JSONObject(first.ui).getString("text"))
        val next = player.next(first).opened() as CoursePage.Unavailable
        assertEquals(CourseSlideIssue.MISSING, next.issue)
        assertEquals(1, next.course.progress.completedSlides)
        assertEquals(next, player.next(first).opened())
        assertNull(store.slide(saved.id, "slide-2"))
    }

    @Test fun wrongThenRightPersistsAndNextRevealsProgressExactlyOnce() = workflow { store, player, context, name ->
        val saved = create(store, CourseSlideKind.CHOICE, CourseSlideKind.SUMMARY)
        saveSlide(store, saved, slide(choice()))
        val wrong = player.submit(openSlide(player, saved.id), "wrong", CourseAnswer.Choice("b")).opened() as CoursePage.Slide
        assertEquals(CourseAnswerFeedback(CourseAnswer.Choice("b"), false), wrong.feedback)
        assertEquals(0, wrong.course.progress.completedSlides)
        CourseStore(context, name).use { reopened ->
            val resumed = presentation(reopened)
            val first = openSlide(resumed, saved.id)
            assertEquals(wrong.feedback, first.feedback)
            val correct = resumed.submit(first, "right", CourseAnswer.Choice("a")).opened() as CoursePage.Slide
            assertEquals(1, correct.course.progress.completedSlides)
            assertEquals("slide-1", correct.step.slide.id)
            assertEquals(CourseAnswerFeedback(CourseAnswer.Choice("a"), true), correct.feedback)
            saveSlide(reopened, correct.course, slide())
            val restored = resumed.player(saved.id, completedAnswerId = "right") as CoursePage.Slide
            assertEquals(correct.step, restored.step)
            assertEquals(correct.feedback, restored.feedback)
            val next = resumed.next(restored).opened() as CoursePage.Slide
            assertEquals("slide-2", next.step.slide.id)
            assertEquals(1, next.course.progress.completedSlides)
            assertTrue(next.sources.isEmpty())
            assertNull(next.adaptationNote)
            assertEquals(next, resumed.next(restored).opened())
            assertTrue((resumed.next(next).opened() as CoursePage.Complete).course.progress.isComplete)
            assertEquals(listOf(false, true), reopened.answers(saved.id).map { it.correct })
        }
    }

    @Test fun gradesTypedFillAndOrder() = workflow { store, player, _, _ ->
        val saved = create(store, CourseSlideKind.FILL_IN, CourseSlideKind.ORDER)
        val fill = JSONObject().put("kind", "FILL_IN").put("question", "Name the form.")
            .put("accepted", JSONArray(listOf("preterite")))
        saveSlide(store, saved, slide(fill))
        val first = openSlide(player, saved.id)
        val filled = player.submit(first, "fill", CourseAnswer.FillIn("preterite")).opened() as CoursePage.Slide
        assertEquals(CourseAnswerFeedback(CourseAnswer.FillIn("preterite"), true), filled.feedback)
        val order = JSONObject().put("kind", "ORDER").put("question", "Order the forms.")
            .put("options", options()).put("correctOrder", JSONArray(listOf("b", "a")))
        saveSlide(store, filled.course, slide(order))
        val second = player.next(filled).opened() as CoursePage.Slide
        val ordered = player.submit(second, "order", CourseAnswer.Order(listOf("b", "a"))).opened() as CoursePage.Slide
        assertEquals(CourseAnswerFeedback(CourseAnswer.Order(listOf("b", "a")), true), ordered.feedback)
        assertTrue(player.next(ordered).opened() is CoursePage.Complete)
    }

    @Test fun rejectsActionBackedMissingAndForbiddenExerciseContent() = workflow { store, player, _, _ ->
        val forbidden = JSONObject("""{"type":"button","text":"Generated button","action":"action"}""")
        val generatedExercise = JSONObject("""{"type":"singleChoice","text":"Choose a form","action":"answer",
            "bind":"value","options":[{"text":"Preterite form","value":"a"},{"text":"Imperfect form","value":"b"}]}""")
        val actionBacked = slide(choice().put("actionId", "answer"), ui = generatedExercise)
        val invalidMetadata = slide(choice().put("correct", "unknown"))
        val invalidUi = slide(choice()).copy(uiJson = forbidden.toString())
        val invalidState = slide(choice()).copy(stateJson = "{}")
        for (content in listOf(actionBacked, slide(), invalidMetadata, invalidUi, invalidState)) {
            val saved = create(store, CourseSlideKind.CHOICE)
            saveSlide(store, saved, content)
            val page = player.player(saved.id) as CoursePage.Unavailable
            assertEquals(CourseSlideIssue.INVALID, page.issue)
            assertTrue(store.answers(saved.id).isEmpty())
        }
    }

    @Test fun failedWriteKeepsSavedPositionAndAllowsTheSameDraftToRetry() = workflow { store, player, context, name ->
        val saved = create(store, CourseSlideKind.FILL_IN)
        val fill = JSONObject().put("kind", "FILL_IN").put("question", "Name the form.")
            .put("accepted", JSONArray(listOf("preterite")))
        saveSlide(store, saved, slide(fill))
        val first = openSlide(player, saved.id)
        val draft = CourseAnswer.FillIn("preterite")
        database(context, name).use {
            it.execSQL("""CREATE TRIGGER fixture_reject_progress BEFORE UPDATE OF completed_slides ON courses
                BEGIN SELECT RAISE(ABORT, 'private fixture message'); END""")
        }
        assertEquals(CoursePlayerUpdate.SaveFailed, player.submit(first, "request", draft))
        assertEquals(first, openSlide(player, saved.id))
        assertTrue(store.answers(saved.id).isEmpty())
        database(context, name).use { it.execSQL("DROP TRIGGER fixture_reject_progress") }
        val retry = player.submit(first, "request", draft).opened() as CoursePage.Slide
        assertEquals(CourseAnswerFeedback(draft, true), retry.feedback)
    }

    @Test fun concurrentDuplicateAndStaleRequestsReloadWithoutReplaying() = workflow { store, player, _, _ ->
        val saved = create(store, CourseSlideKind.CHOICE, CourseSlideKind.SUMMARY)
        saveSlide(store, saved, slide(choice()))
        val first = openSlide(player, saved.id)
        val wrong = CourseAnswer.Choice("b")
        val current = kotlinx.coroutines.coroutineScope {
            val one = async(Dispatchers.Default) { player.submit(first, "request", wrong) }
            val two = async(Dispatchers.Default) { player.submit(first, "request", wrong) }
            val outcomes = listOf(one.await(), two.await())
            assertEquals(1, outcomes.count { (it as? CoursePlayerUpdate.Open)?.reloaded == true })
            outcomes.first().opened() as CoursePage.Slide
        }
        val duplicate = player.submit(current, "request", wrong) as CoursePlayerUpdate.Open
        assertTrue(duplicate.reloaded)
        val stale = player.submit(first, "other", wrong) as CoursePlayerUpdate.Open
        assertTrue(stale.reloaded)
        assertEquals(current, stale.page)
        assertEquals(listOf("request"), store.answers(saved.id).map { it.operationId })
    }

    private fun create(store: CourseStore, vararg kinds: CourseSlideKind): CourseSnapshot = store.create(
        CourseSetup("Past tense", "Beginner", "Use two past tense forms"),
        CourseOutline(listOf(CourseLessonPlan("lesson", "Forms", kinds.mapIndexed { index, kind ->
            CourseSlidePlan("slide-${index + 1}", kind, "Goal ${index + 1}")
        }))),
    )

    private fun saveSlide(store: CourseStore, course: CourseSnapshot, content: CourseSlideContent): CourseSnapshot =
        store.commitGeneration(store.beginGeneration(course.id, course.revision, checkNotNull(course.progress.currentSlideId)), content)

    private fun openSlide(player: CoursePresentation, id: String) = player.player(id) as CoursePage.Slide

    private fun CoursePlayerUpdate.opened() = (this as CoursePlayerUpdate.Open).page

    private fun choice() = JSONObject().put("kind", "CHOICE").put("question", "Choose a form.")
        .put("options", options()).put("correct", "a")

    private fun options() = JSONArray()
        .put(JSONObject().put("id", "a").put("label", "Preterite form"))
        .put(JSONObject().put("id", "b").put("label", "Imperfect form"))

    private fun slide(
        exercise: JSONObject? = null,
        ui: JSONObject = JSONObject("""{"type":"text","text":"Saved course content"}"""),
        sources: List<CourseSourceReference> = emptyList(),
        note: String? = null,
    ): CourseSlideContent {
        val schema = JSONObject().put("value", "string")
        val state = JSONObject().put("value", "a")
        if (exercise != null) {
            schema.put(EXERCISE_FIELD, "string")
            state.put(EXERCISE_FIELD, exercise.toString())
        }
        val definition = JSONObject().put("formatVersion", 3).put("title", "Course fixture").put("description", "")
            .put("capabilities", JSONArray()).put("stateSchema", schema).put("initialState", state)
            .put("ui", ui).put("source", "return {state:input.state};")
        return CourseSlideContent(definition.toString(), ui.toString(), state.toString(), sources, note)
    }

    private val calls = AtomicInteger()

    private fun presentation(store: CourseStore): CoursePresentation {
        val generation = CourseGeneration(
            generateTool = { _, _, _, _, _, _ ->
                calls.incrementAndGet()
                error("Unexpected provider call.")
            },
            evaluateTool = { _, _, _, _, _, _, _ ->
                calls.incrementAndGet()
                error("Unexpected generated event.")
            },
        )
        return CoursePresentation(store, CourseController(store, generation))
    }

    private fun workflow(block: suspend (CourseStore, CoursePresentation, Context, String) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "course-presentation-${UUID.randomUUID()}.db"
        try {
            CourseStore(context, name).use { store -> block(store, presentation(store), context, name) }
            assertEquals(0, calls.get())
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun database(context: Context, name: String): SQLiteDatabase =
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
}
