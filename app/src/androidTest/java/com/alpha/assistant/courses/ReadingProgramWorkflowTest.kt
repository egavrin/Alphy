package com.alpha.assistant.courses

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolGenerationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadingProgramWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fullAlphabetAndTwoGeneratedSessionsKeepHostGradesAndResume() = runBlocking {
        val name = "reading-${UUID.randomUUID()}.db"
        var calls = 0
        val program = { store: CourseStore -> ReadingProgram(store) { request ->
            calls++
            val eligible = request.getJSONArray("orderable")
            if (calls == 1) """{"exercises":[{"type":"word","word":"несуществующиеслово"}]}"""
            else JSONObject().put("exercises", JSONArray((0 until 6).map { index ->
                JSONObject().put("type", if (index % 2 == 0) "word" else "order")
                    .put("word", eligible.getString(index))
            })).toString()
        } }
        fun controller(store: CourseStore) = CourseController(store,
            ToolGenerationService(object : com.alpha.assistant.cloud.ChatProvider {
                override suspend fun respond(key: String, request: com.alpha.assistant.cloud.CloudRequest,
                    session: com.alpha.assistant.cloud.CloudSession, emit: suspend (com.alpha.assistant.cloud.CloudEvent) -> Unit) =
                    error("A checked lesson does not call the model for grading.")
            }, SandboxClient(context)))
        lateinit var coreId: String
        lateinit var firstId: String
        try {
            CourseStore(context, name).use { store ->
                val reading = program(store)
                val core = reading.openCore()
                coreId = core.id
                assertEquals(core.id, reading.openCore().id)
                val letters = core.outline.lessons.mapNotNull { it.title.removePrefix("Буква ").takeIf { s -> s.length == 1 } }
                assertEquals("АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ", letters.joinToString(""))
                val player = controller(store)
                val firstSlide = core.progress.currentSlideId!!
                val first = store.slide(core.id, firstSlide)!!
                assertNotNull(first.content.narration)
                assertTrue(parseCourseSpec(first.content, CourseSlideKind.EXPLANATION).surfaces.isNotEmpty())
                var current = core
                while (!current.progress.isComplete) {
                    val step = current.outline.steps()[current.progress.completedSlides].slide
                    if (step.kind.requiresAnswer) {
                        val exercise = exercise(store, current, step.id)
                        val correct = exercise.optString("correct")
                        if (store.answers(current.id).isEmpty()) {
                            val options = exercise.getJSONArray("options")
                            val wrong = (0 until options.length()).map { options.getJSONObject(it).getString("id") }.first { it != correct }
                            current = player.submitAnswer(current.id, current.revision, step.id, UUID.randomUUID().toString(), CourseAnswer.Choice(wrong))
                            assertEquals(step.id, current.progress.currentSlideId)
                            assertFalse(store.answers(current.id).single().correct)
                        }
                        val answer = if (step.kind == CourseSlideKind.CHOICE) CourseAnswer.Choice(correct)
                        else exercise.getJSONArray("correctOrder").let { list -> CourseAnswer.Order(List(list.length()) { list.getString(it) }) }
                        current = player.submitAnswer(current.id, current.revision, step.id, UUID.randomUUID().toString(), answer)
                    } else current = player.advance(current.id, current.revision, step.id)
                }
                val firstPractice = reading.next(core.id)
                firstId = firstPractice.id
                assertEquals(2, calls)
                assertEquals(6, firstPractice.progress.totalSlides)
                assertEquals(firstId, reading.next(core.id).id)
            }
            CourseStore(context, name).use { store ->
                val reading = program(store)
                var current = reading.openCore()
                assertEquals(firstId, current.id)
                val player = controller(store)
                while (!current.progress.isComplete) {
                    val id = current.progress.currentSlideId!!
                    val key = exercise(store, current, id)
                    val answer = if (key.getString("kind") == "CHOICE") CourseAnswer.Choice(key.getString("correct"))
                    else key.getJSONArray("correctOrder").let { list -> CourseAnswer.Order(List(list.length()) { list.getString(it) }) }
                    current = player.submitAnswer(current.id, current.revision, id, UUID.randomUUID().toString(), answer)
                }
                val second = reading.next(current.id)
                assertNotEquals(firstId, second.id)
                assertEquals(3, calls)
                assertTrue(store.course(coreId)!!.progress.isComplete)
                assertEquals(6, store.answers(firstId).size)
                val firstWords = store.course(firstId)!!.outline.steps().map { it.slide.goal }.toSet()
                assertTrue(second.outline.steps().none { it.slide.goal in firstWords })
                assertTrue(store.answers(second.id).isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun exercise(store: CourseStore, course: CourseSnapshot, slide: String): JSONObject =
        JSONObject(JSONObject(store.slide(course.id, slide)!!.content.definitionJson).getJSONObject("initialState")
            .getString(EXERCISE_FIELD))

    @Test fun speechSequenceReplaysCancelsAndRetriesOnlyTheVisibleVisit() = runBlocking {
        withContext(Dispatchers.Main) {
            val requests = mutableListOf<String>()
            val played = mutableListOf<String>()
            var suspendWord: CompletableDeferred<Unit>? = null
            var failOnce = false
            val player = CourseSpeechSession(this, { cue ->
                requests += cue.text
                if (failOnce) {
                    failOnce = false
                    error("Synthetic unavailable")
                }
                if (cue.text == "аист") suspendWord?.await()
                cue.text.toByteArray()
            }, { audio, started ->
                started()
                played += audio.decodeToString()
            })
            val narration = CourseNarration(CourseCue("Буква а"), listOf(CourseCue("аист", "Аист")))
            try {
                player.show(narration, visible = false)
                delay(20)
                assertTrue(requests.isEmpty())
                player.show(narration, visible = true)
                withTimeout(3000) { while (played.size < 2) delay(10) }
                assertEquals(listOf("Буква а", "аист"), played)
                player.replay()
                withTimeout(3000) { while (played.size < 4) delay(10) }
                assertEquals(2, requests.size)
                suspendWord = CompletableDeferred()
                player.show(null, false)
                player.show(narration, true)
                withTimeout(3000) { while (requests.size < 4) delay(10) }
                player.show(null, false)
                suspendWord!!.complete(Unit)
                delay(30)
                assertEquals(5, played.size)
                failOnce = true
                player.show(narration, true)
                withTimeout(3000) { while (!player.state.value.failed) delay(10) }
                player.replay()
                withTimeout(3000) { while (played.size < 7) delay(10) }
                assertEquals("аист", played.last())
            } finally {
                player.show(null, false)
            }
        }
    }
    @Test fun consecutiveMistakesEncourageRetryAndCorrectFeedbackReadsTheAnswer() = runBlocking {
        withContext(Dispatchers.Main) {
            val spoken = mutableListOf<String>()
            val player = CourseSpeechSession(this, { it.text.toByteArray() }, { audio, started ->
                started()
                spoken += audio.decodeToString()
            })
            val plan = CourseNarration(CourseCue("Инструкция"), answer = CourseCue("Ма́ма."))
            suspend fun waitFor(count: Int) = withTimeout(3000) { while (spoken.size < count) delay(10) }
            try {
                player.show(plan, true)
                waitFor(1)
                player.feedback(false, 2)
                waitFor(2)
                player.feedback(false, 3)
                waitFor(3)
                assertNotEquals(spoken[1], spoken[2])
                assertTrue(spoken[1].contains("попробуй", ignoreCase = true))
                assertTrue(spoken[2].contains("попробуй", ignoreCase = true))
                assertFalse(spoken.contains("Ма́ма."))
                player.feedback(true, 4)
                waitFor(5)
                assertTrue(spoken[3].startsWith("[warmly, with a gentle smile] Правильно, молодец."))
                assertFalse(spoken[3].contains('!'))
                assertEquals("Ма́ма.", spoken.last())
            } finally {
                player.show(null, false)
            }
        }
    }
    @Test fun updatedTeachingCuesPreserveTheReachedSlideAndRefreshFutureSlides() {
        val name = "reading-content-${UUID.randomUUID()}.db"
        try {
            CourseStore(context, name).use { store ->
                val original = ReadingMaterial.practice(
                    ReadingMaterial.vocabulary.take(2).map { ReadingItem(it, false) }, 0, false)
                val saved = store.saveReading(original)
                val steps = original.outline.steps()
                val changed = original.slides.mapValues { (_, content) ->
                    content.copy(narration = CourseNarration(CourseCue("Уточнённая инструкция.")))
                }
                val outline = CourseOutline(original.outline.lessons.map { lesson ->
                    lesson.copy(slides = lesson.slides.map { it.copy(goal = "Уточнённая цель") })
                })
                val revised = ReadingCourse(original.setup, outline, changed)
                val updated = store.saveReading(revised)
                assertEquals(saved.id, updated.id)
                assertEquals(saved.progress, updated.progress)
                assertEquals(steps[0].slide.goal, updated.outline.steps()[0].slide.goal)
                assertEquals("Уточнённая цель", updated.outline.steps()[1].slide.goal)
                assertEquals(saved.revision + 1, updated.revision)
                assertEquals(original.slides.getValue(steps[0].slide.id),
                    store.slide(saved.id, steps[0].slide.id)!!.content)
                assertEquals(changed.getValue(steps[1].slide.id),
                    store.slide(saved.id, steps[1].slide.id)!!.content)
                assertEquals(updated.revision, store.saveReading(revised).revision)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
