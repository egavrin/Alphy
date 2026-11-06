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
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
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

    @Test fun authorizedChatReviewsThenStartsOneCourseAndReturnsToTheSameDraft() {
        val provider = CourseProvider()
        withShell(provider) { scenario, model, store ->
            robot.edit(robot.text(R.string.composer_label), "Teach me Spanish")
            click(R.string.composer_send)
            waitFor(R.string.shell_course_setup)
            assertEquals(listOf(true), provider.calls.map { it.request.chatDecision })
            assertTrue(provider.calls.single().request.allowCourseDecision)
            assertTrue(store.courses().isEmpty())
            scenario.onActivity { model.editDraft(kept) }
            fillLevelAndGoal()
            click(R.string.shell_course_plan)
            robot.waitFor(lesson)
            assertEquals(2, provider.calls.size)
            assertTrue(store.courses().isEmpty())
            val outline = provider.calls.last().request
            assertTrue(outline.jsonObject)
            assertFalse(outline.search)
            val payload = outline.turns.single().text
            assertTrue(payload.contains(topic) && payload.contains("Beginner") && payload.contains("Hold a conversation"))
            assertFalse(payload.contains("PRIVATE-"))
            assertTrue(outline.turns.single().images.isEmpty())

            scenario.onActivity {
                val setup = checkNotNull(model.courseSetup)
                model.startPreparedCourse(setup.id)
                model.startPreparedCourse(setup.id)
            }
            robot.waitFor(slide)
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

    @Test fun composerAndLibraryWaitForSetupAndFailureOrCancelPreserveTheDraft() {
        val provider = CourseProvider().apply { invalidOutline = true }
        withShell(provider) { scenario, model, store ->
            val existing = store.create(CourseSetup("Existing", "Beginner", "Keep this course"),
                CourseOutline(listOf(CourseLessonPlan("keep", "Keep", listOf(
                    CourseSlidePlan("first", CourseSlideKind.EXPLANATION, "Keep this slide"),
                )))))
            scenario.onActivity {
                model.editDraft(kept)
                model.beginCourseSetup(kept.text, fromChat = true)
            }
            waitFor(R.string.shell_course_setup)
            assertTrue(provider.calls.isEmpty())
            assertEquals(kept.text, robot.field(robot.text(R.string.shell_course_topic)).text.toString())
            fillLevelAndGoal()
            click(R.string.shell_course_plan)
            waitFor(R.string.shell_course_plan_failed)
            assertEquals(listOf(existing), store.courses())
            assertFalse(provider.calls.single().request.turns.single().text.contains("PRIVATE-"))

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
            assertEquals(2, provider.calls.size)
            assertEquals(listOf(existing), store.courses())
        }
    }

    @Test fun navigationCancelsOutlineAndForgedTopicCannotOpenSetup() {
        val provider = CourseProvider()
        val release = CompletableDeferred<Unit>()
        provider.outlineGate = release
        withShell(provider) { scenario, model, store ->
            try {
                scenario.onActivity {
                    model.editDraft(kept)
                    model.beginCourseSetup(topic, fromChat = true)
                }
                waitFor(R.string.shell_course_setup)
                fillLevelAndGoal()
                click(R.string.shell_course_plan)
                robot.waitUntil { provider.calls.isNotEmpty() }
                val active = provider.calls.single().session
                scenario.onActivity { model.select("other") }
                assertTrue(runCatching { active.check() }.isFailure)
                release.complete(Unit)
                robot.waitGone(robot.text(R.string.shell_course_setup))
                assertTrue(store.courses().isEmpty())
                scenario.onActivity { assertEquals(kept, model.snapshot.conversations.single { it.id == chatId }.draft) }

                robot.edit(robot.text(R.string.composer_label), "What time is it?")
                click(R.string.composer_send)
                robot.waitFor("Please confirm whether you want a course and name its topic.")
                assertEquals(2, provider.calls.size)
                assertTrue(store.courses().isEmpty())
            } finally { release.complete(Unit) }
        }
    }

    private fun fillLevelAndGoal() {
        robot.edit(robot.text(R.string.shell_course_level), "Beginner")
        robot.edit(robot.text(R.string.shell_course_goal), "Hold a conversation")
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
            CloudTestActivity.factory = { app -> ShellViewModel(app, provider, shell.store, shell.keys).also { shell.model = it } }
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

    private inner class CourseProvider : ChatProvider {
        val calls = CopyOnWriteArrayList<Call>()
        @Volatile var invalidOutline = false
        @Volatile var outlineGate: CompletableDeferred<Unit>? = null

        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            check(key == "PRIVATE-FIXTURE-KEY")
            calls += Call(request, session)
            if (request.chatDecision) {
                emit(CloudEvent.RoundComplete("""{"kind":"course","text":"","brief":"$topic","search":"none"}""", false))
                return
            }
            when (request.generationStage) {
                null -> {
                    outlineGate?.let { withContext(NonCancellable) { it.await() } }
                    val outline = """{"lessons":[{"id":null,"title":"$lesson","slides":[{"id":null,"kind":"EXPLANATION","goal":"Practice a first greeting"}]}]}"""
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
