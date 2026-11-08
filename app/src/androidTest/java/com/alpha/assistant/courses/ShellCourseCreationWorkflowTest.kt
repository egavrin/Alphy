package com.alpha.assistant.courses

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.MessageProvenance
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import com.alpha.assistant.ui.openSeededChat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ShellCourseCreationWorkflowTest {
    private val robot = ShellRobot("shell-course-creation")
    private val chatId = "PRIVATE-ORIGIN-ID"
    private val topic = "Spanish"
    private val lesson = "First conversation"
    private val slide = "Say hello in Spanish"
    private val kept = Draft("Kept composer topic", listOf(
        Attachment("content://fixture/attachment", "Fixture attachment", "text/plain", "PRIVATE-ATTACHMENT-TEXT"),
    ))

    @Test fun onlyTypedCoursePreparationOpensReviewWithTheModelTopic() {
        val request = "хочу научиться считать проценты"
        val modelTopic = "Расчёт процентов"
        for (typed in listOf(false, true)) {
            val provider = CourseProvider(modelTopic, typed)
            withShell(provider) { _, model, store ->
                robot.edit(robot.text(R.string.composer_label), request)
                click(R.string.composer_send)
                if (typed) {
                    waitFor(R.string.shell_course_start)
                    robot.waitFor(modelTopic)
                    assertEquals(listOf(true, false), provider.calls.map { it.request.chatDecision })
                } else {
                    robot.waitUntil { provider.calls.isNotEmpty() && model.activeConversationId == null }
                    assertEquals(1, provider.calls.size)
                    assertNull(robot.find(robot.text(R.string.shell_course_review)))
                }
                assertTrue(store.courses().isEmpty())
            }
        }
    }

    @Test fun authorizedChatReviewsThenStartsOneCourseAndReturnsToTheSameDraft() {
        val provider = CourseProvider()
        withShell(provider) { scenario, model, store ->
            robot.edit(robot.text(R.string.composer_label), "Teach me Spanish")
            scenario.onActivity {
                assertTrue(model.addReviewedText("A reviewed Spanish greeting", "Fixture", model.contextDraftKey()))
            }
            click(R.string.composer_send)
            robot.waitFor(lesson)
            assertEquals(listOf(true, false), provider.calls.map { it.request.chatDecision })
            assertTrue(provider.calls.first().request.allowCourseDecision)
            assertNull(robot.find(robot.text(R.string.shell_course_topic)))
            scenario.onActivity { model.editDraft(kept) }
            assertEquals(2, provider.calls.size)
            assertTrue(store.courses().isEmpty())
            val outline = provider.calls.last().request
            assertTrue(outline.jsonObject)
            assertFalse(outline.search)
            val payload = outline.turns.single().text
            assertTrue(payload.contains(topic) && payload.contains("Teach me Spanish"))
            assertTrue(payload.contains("A reviewed Spanish greeting"))
            assertFalse(payload.contains("PRIVATE-"))
            assertTrue(outline.turns.single().images.isEmpty())

            robot.edit(robot.text(R.string.composer_label), "Add conversation practice")
            click(R.string.composer_send)
            robot.waitFor("Practice a conversation")
            assertEquals(3, provider.calls.size)
            assertTrue(provider.calls.last().request.turns.single().text.contains("Add conversation practice"))
            assertTrue(store.courses().isEmpty())
            robot.diagnostics.capture("inline-plan")
            click(R.string.shell_course_start)
            try {
                robot.waitFor(slide)
            } catch (failure: AssertionError) {
                val generation = store.courses().singleOrNull()?.generationFailure
                val stages = provider.calls.map { it.request.generationStage }
                throw AssertionError("First slide missing. Saved generation failure: $generation. Model stages: $stages.", failure)
            }
            assertNull(robot.find(robot.text(R.string.course_tutor_close)))
            robot.diagnostics.capture("lesson-first")
            val saved = store.courses().single()
            assertEquals(chatId, saved.setup.originatingConversationId)
            assertEquals(topic, saved.setup.topic)
            assertEquals(0, saved.progress.completedSlides)
            robot.back()
            waitFor(R.string.shell_course_continue)
            scenario.onActivity {
                assertEquals(chatId, model.snapshot.selectedId)
                assertEquals(kept, model.snapshot.draft)
            }
            val calls = provider.calls.size
            click(R.string.shell_course_continue)
            robot.waitFor(slide)
            assertEquals(calls, provider.calls.size)
            robot.back()
            waitFor(R.string.shell_course_continue)
        }
    }

    @Test fun failedPlanCanRetryAndCancelPreservesTheRegularDraft() {
        val provider = CourseProvider().apply { invalidOutline = true }
        withShell(provider) { scenario, model, store ->
            val existing = store.create(CourseSetup("Existing", "Beginner", "Keep this course"),
                CourseOutline(listOf(CourseLessonPlan("keep", "Keep", listOf(
                    CourseSlidePlan("first", CourseSlideKind.EXPLANATION, "Keep this slide"),
                )))))
            robot.edit(robot.text(R.string.composer_label), "Teach me Spanish")
            click(R.string.composer_send)
            waitFor(R.string.shell_course_plan_failed)
            scenario.onActivity { model.editDraft(kept) }
            assertEquals(listOf(existing), store.courses())
            assertFalse(provider.calls.last().request.turns.single().text.contains("PRIVATE-"))

            provider.invalidOutline = false
            click(R.string.shell_course_plan)
            robot.waitFor(lesson)
            click(R.string.cancel)
            robot.waitGone(robot.text(R.string.shell_course_review))
            assertEquals(listOf(existing), store.courses())
            scenario.onActivity { assertEquals(kept, model.snapshot.draft) }

            robot.openDrawer(scenario)
            click(R.string.course_learning)
            robot.awaitDrawerClosed(scenario)
            robot.waitFor("Existing")
            assertNull(robot.find(robot.text(R.string.course_new)))
            assertEquals(3, provider.calls.size)
            assertEquals(listOf(existing), store.courses())
        }
    }

    @Test fun navigationCancelsOutlineAndForgedTopicCannotOpenSetup() {
        val provider = CourseProvider()
        val release = CompletableDeferred<Unit>()
        provider.outlineGate = release
        withShell(provider) { scenario, model, store ->
            try {
                robot.edit(robot.text(R.string.composer_label), "Teach me Spanish")
                click(R.string.composer_send)
                robot.waitUntil { provider.calls.size == 2 }
                scenario.onActivity { model.editDraft(kept) }
                val active = provider.calls.last().session
                scenario.onActivity { model.select("other") }
                assertTrue(runCatching { active.check() }.isFailure)
                release.complete(Unit)
                robot.waitGone(robot.text(R.string.shell_course_review))
                assertTrue(store.courses().isEmpty())
                scenario.onActivity { assertEquals(kept, model.snapshot.conversations.single { it.id == chatId }.draft) }

                provider.rejectAsGeneric = true
                robot.edit(robot.text(R.string.composer_label), "What time is it?")
                click(R.string.composer_send)
                robot.waitUntil { provider.calls.size == 3 && model.activeConversationId == null }
                assertNull(robot.find(robot.text(R.string.shell_course_review)))
                assertEquals(3, provider.calls.size)
                assertTrue(store.courses().isEmpty())
            } finally { release.complete(Unit) }
        }
    }

    private fun click(resource: Int) = robot.click(robot.text(resource))
    private fun waitFor(resource: Int) = robot.waitFor(robot.text(resource))

    private fun withShell(provider: CourseProvider,
        test: (ActivityScenario<CloudTestActivity>, ShellViewModel, CourseStore) -> Unit) {
        val shell = ShellFixture("course-creation")
        val courseDb = "${shell.store.databaseName}-courses.db"
        val memoryDb = "${shell.store.databaseName}-memory.db"
        try {
            shell.seedChat(chatId, "Origin chat", "PRIVATE-EARLIER-TEXT", selected = true)
            shell.seedChat("other", "Other chat", "PRIVATE-OTHER-TEXT")
            check(shell.keys.save("PRIVATE-FIXTURE-KEY").isSuccess)
            CloudTestActivity.factory = { app -> ShellViewModel(app, provider, shell.store, shell.keys).openSeededChat(shell.store).also { shell.model = it } }
            CourseStore(shell.context, courseDb).use { store ->
                shell.launch().use { scenario ->
                    waitFor(R.string.composer_label)
                    test(scenario, checkNotNull(shell.model), store)
                }
            }
        } finally {
            shell.remove()
            shell.context.deleteDatabase(courseDb)
            shell.context.deleteDatabase(memoryDb)
        }
    }

    private data class Call(val request: CloudRequest, val session: CloudSession)

    private inner class CourseProvider(private val proposedTopic: String = topic, private val typed: Boolean = true) : ChatProvider {
        val calls = CopyOnWriteArrayList<Call>()
        @Volatile var invalidOutline = false
        @Volatile var rejectAsGeneric = false
        @Volatile var outlineGate: CompletableDeferred<Unit>? = null

        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            check(key == "PRIVATE-FIXTURE-KEY")
            calls += Call(request, session)
            if (request.chatDecision) {
                if (typed && !rejectAsGeneric) emit(CloudEvent.RoundComplete("", false, MessageProvenance.CLEAN, courseTopic = proposedTopic))
                else emit(CloudEvent.RoundComplete("""{"kind":"course","text":"","brief":"$proposedTopic","search":"none"}""", false))
                return
            }
            when (request.generationStage) {
                null -> {
                    outlineGate?.let { withContext(NonCancellable) { it.await() } }
                    val goal = if (request.turns.single().text.contains("Add conversation practice")) "Practice a conversation" else "Practice a first greeting"
                    val outline = """{"lessons":[{"id":null,"title":"$lesson","slides":[{"id":null,"kind":"EXPLANATION","goal":"$goal"}]}]}"""
                    emit(CloudEvent.RoundComplete(if (invalidOutline) "{}" else outline, false))
                }
                CloudGenerationStage.PLAN -> emit(CloudEvent.Text("""{"title":"Spanish","description":"","language":"en",
                    "inputs":[],"constants":[{"id":"body","value":"$slide"}],"needs":[{"id":"lesson","type":"explanation",
                    "label":"Spanish","description":"Explain the greeting","uses":{"body":"body"}}],"questions":[]}"""))
                CloudGenerationStage.SELECTOR -> {
                    val questions = JSONObject(request.turns.single().text).getJSONArray("questions")
                    val answers = JSONArray()
                    repeat(questions.length()) { index ->
                        answers.put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                            .put("choice", "A").put("confidence", 1).put("probabilities", JSONObject().put("A", 1).put("B", 0)))
                    }
                    emit(CloudEvent.Text(JSONObject().put("answers", answers).toString()))
                }
                else -> error("Unexpected request")
            }
        }
    }
}
