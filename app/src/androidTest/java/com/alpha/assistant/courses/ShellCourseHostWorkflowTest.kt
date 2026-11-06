package com.alpha.assistant.courses

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudSearchProvider
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Source
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ShellCourseHostWorkflowTest {
    private val robot = ShellRobot("shell-course-host")
    private val courseTitle = "Compare fractions"
    private val slideText = "Explain the fractions"
    private val conversationId = "PRIVATE-CHAT-ID"
    private val credential = "PRIVATE-TEST-CREDENTIAL"
    private val outline = CourseOutline(listOf(CourseLessonPlan("lesson", "Common denominators", listOf(
        CourseSlidePlan("current", CourseSlideKind.EXPLANATION, "Explain the current fraction"),
        CourseSlidePlan("future", CourseSlideKind.EXPLANATION, "Explain the next fraction"),
    ))))

    @Test fun chatCardReviewsAndAppliesRefinementWhileKeepingDraftAndSavedSlide() {
        val provider = CourseProvider()
        withShell(provider, completeSlide = true) { scenario, model, store, saved ->
            val current = checkNotNull(store.course(saved.id))
            val savedSlide = checkNotNull(store.slide(saved.id, "current"))
            val draft = Draft("Keep this unsent draft")
            scenario.onActivity { model.editDraft(draft) }
            click(R.string.course_refinement_open)
            assertTrue(provider.calls.isEmpty())
            robot.edit(robot.text(R.string.course_refinement_instruction), "Add an example to the next slide")
            click(R.string.composer_send)
            robot.waitFor(robot.text(R.string.course_refinement_review))
            assertEquals(current, store.course(saved.id))
            click(R.string.course_refinement_apply)
            robot.waitUntil { model.chatCourses.singleOrNull()?.revision == current.revision + 1 }
            val revised = checkNotNull(store.course(saved.id))
            assertEquals("Explain the next fraction with an example", revised.outline.steps().last().slide.goal)
            assertEquals(current.progress, revised.progress)
            assertEquals(1, revised.progress.completedSlides)
            assertEquals(savedSlide, store.slide(saved.id, "current"))
            assertEquals(1, provider.calls.size)

            robot.back()
            click(R.string.shell_course_continue)
            robot.waitFor(slideText)
            val plan = provider.calls.single { it.request.generationStage == CloudGenerationStage.PLAN }
            assertTrue(plan.request.turns.single().text.contains("Explain the next fraction with an example"))
            robot.back()
            robot.waitFor(robot.text(R.string.shell_course_continue))
            scenario.onActivity {
                assertEquals(conversationId, model.snapshot.selectedId)
                assertEquals(draft, model.snapshot.draft)
            }
        }
    }

    @Test fun libraryOutlineOffersRefinementAndPreparesSourcesOnlyOnSubmit() {
        val provider = SearchingCourseProvider()
        withShell(provider) { scenario, _, store, saved ->
            openOutline(scenario)
            click(R.string.course_refinement_open)
            robot.waitFor(robot.text(R.string.course_refinement_instruction))
            click(R.string.course_refinement_cancel)
            assertEquals(saved, store.course(saved.id))

            val action = robot.text(R.string.course_sourced_open)
            robot.reveal(action)
            robot.click(action)
            val query = "Worked examples of equivalent fractions"
            robot.edit(robot.text(R.string.course_sourced_query), query)
            assertTrue(provider.searches.isEmpty())
            assertTrue(provider.calls.isEmpty())
            click(R.string.course_sourced_submit)
            robot.waitFor(slideText)
            assertEquals(query, provider.searches.single().query)
            assertEquals(listOf(CourseSourceReference(provider.source.title, provider.source.url)),
                store.slide(saved.id, "current")?.content?.sources)
            assertNull(store.slide(saved.id, "future"))
        }
    }

    @Test fun chatOutlineCancelsSearchThenPreparesAndReopensTheAttributedSlide() {
        val provider = SearchingCourseProvider()
        val transport = BlockingTransport()
        withShell(provider) { scenario, model, store, saved ->
            val draft = Draft("Keep this unsent draft")
            scenario.onActivity { model.editDraft(draft) }
            click(R.string.shell_course_open_outline)
            robot.waitFor("Explain the current fraction")
            assertTrue(provider.searches.isEmpty())
            assertTrue(provider.calls.isEmpty())

            val cancelledQuery = "Equivalent fraction examples"
            provider.searchBlock = transport
            try {
                click(R.string.course_sourced_open)
                robot.edit(robot.text(R.string.course_sourced_query), cancelledQuery)
                assertTrue(provider.searches.isEmpty())
                click(R.string.course_sourced_submit)
                transport.assertStarted()
                click(R.string.course_sourced_cancel)
                transport.assertDisconnected()
                robot.waitGone(robot.text(R.string.course_sourced_query))
            } finally { transport.release.countDown() }
            transport.assertReturned()
            assertEquals(cancelledQuery, provider.searches.single().query)
            assertTrue(provider.calls.isEmpty())
            assertEquals(saved, store.course(saved.id))
            assertNull(store.slide(saved.id, "current"))

            provider.searchBlock = null
            click(R.string.course_sourced_open)
            val submittedQuery = "Worked examples of equivalent fractions"
            robot.edit(robot.text(R.string.course_sourced_query), submittedQuery)
            assertEquals(1, provider.searches.size)
            click(R.string.course_sourced_submit)
            robot.waitFor(slideText)
            robot.waitFor(provider.source.title)
            robot.waitFor(provider.source.url)
            assertEquals(listOf(cancelledQuery, submittedQuery), provider.searches.map { it.query })
            val completedCalls = provider.calls.toList()
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR),
                completedCalls.map { it.request.generationStage })
            assertTrue(completedCalls.all { it.session === provider.searches.last().session })
            assertEquals(listOf(CourseSourceReference(provider.source.title, provider.source.url)),
                store.slide(saved.id, "current")?.content?.sources)
            assertNull(store.slide(saved.id, "future"))
            assertEquals(saved.progress, store.course(saved.id)?.progress)

            robot.back()
            robot.waitFor(robot.text(R.string.shell_course_continue))
            scenario.onActivity {
                assertEquals(conversationId, model.snapshot.selectedId)
                assertEquals(draft, model.snapshot.draft)
            }
            click(R.string.shell_course_continue)
            robot.waitFor(slideText)
            robot.waitFor(provider.source.title)
            assertEquals(2, provider.searches.size)
            assertEquals(completedCalls, provider.calls.toList())
            assertEquals(saved.progress, store.course(saved.id)?.progress)
        }
    }

    @Test fun outlineProposalUsesCourseContextAndOnlyReviewedCurrentRevisionCanApply() {
        val provider = CourseProvider()
        withShell(provider) { scenario, model, store, saved -> runBlocking {
            val instruction = "Add a worked example to the second slide."
            val first = model.courseRefinement.propose(saved.id, saved.revision, Draft(instruction))
            assertEquals(saved, store.course(saved.id))
            val request = provider.calls.single().request
            assertTrue(request.jsonObject)
            assertFalse(request.search || request.chatDecision)
            val prompt = request.turns.single().text
            assertTrue(courseTitle in prompt && instruction in prompt)
            assertTrue("\"id\":1" in prompt)
            assertFalse(listOf(saved.id, conversationId, "PRIVATE-MEMORY", "PRIVATE-OTHER-COURSE", "\"current\"", "\"future\"")
                .any { it in prompt })

            val edited = store.editOutline(saved.id, saved.revision, outline.copy(lessons = listOf(outline.lessons.single().copy(title = "Concurrent edit"))))
            val stale = runCatching { model.courseRefinement.apply(first) }.exceptionOrNull() as CourseStoreException
            assertEquals(CourseRejection.STALE_REVISION, stale.reason)
            assertEquals(edited, store.course(saved.id))

            val reviewed = model.courseRefinement.propose(saved.id, edited.revision, Draft(instruction))
            scenario.onActivity { model.clearKey() }
            robot.waitUntil { !model.keyBusy && model.keyStatus == com.alpha.assistant.ui.KeyStatus.Missing }
            val applied = model.courseRefinement.apply(reviewed)
            assertEquals(reviewed.outline, applied.outline)
            assertEquals(edited.progress, applied.progress)
            val missingKey = runCatching { model.courseRefinement.propose(saved.id, applied.revision, Draft(instruction)) }
                .exceptionOrNull() as CourseOutlineException
            assertEquals(CourseOutlineError.UNAVAILABLE, missingKey.reason)
            assertEquals(2, provider.calls.size)
        } }
    }

    @Test fun sourcedSlideSearchUsesOnlySubmittedQueryAndPersistsUsedAttribution() {
        val provider = SearchingCourseProvider()
        withShell(provider) { _, model, store, saved -> runBlocking {
            val request = CourseSlideGenerationRequest(saved.id, saved.revision, "current")
            val query = "Worked examples of equivalent fractions"
            model.generateSourcedCourseSlide(request, query)
            assertEquals(query, provider.searches.single().query)
            assertTrue(provider.calls.all { it.session === provider.searches.single().session })
            assertTrue(provider.calls.none { it.request.search || it.request.chatDecision })
            val planner = provider.calls.first().request.turns.single().text
            assertTrue(provider.source.summary in planner && "\"id\":\"s1\"" in planner)
            assertFalse(listOf(saved.id, conversationId, "PRIVATE-MEMORY", "PRIVATE-OTHER-COURSE", provider.source.title, provider.source.url)
                .any { it in planner })
            val slide = checkNotNull(store.slide(saved.id, "current"))
            assertEquals(listOf(CourseSourceReference(provider.source.title, provider.source.url)), slide.content.sources)
            val current = checkNotNull(store.course(saved.id))
            model.generateSourcedCourseSlide(request.copy(expectedRevision = current.revision), query)
            assertEquals(1, provider.searches.size)
        } }
    }

    @Test fun failedAndUnavailableSearchLeaveTheReachedSlideUnsaved() {
        val capable = SearchingCourseProvider()
        for (provider in listOf(CourseProvider(), capable)) {
            withShell(provider) { _, model, store, saved -> runBlocking {
                val request = CourseSlideGenerationRequest(saved.id, saved.revision, "current")
                suspend fun failure(query: String) = runCatching { model.generateSourcedCourseSlide(request, query) }.exceptionOrNull()
                assertEquals("search_query", (failure("a".repeat(129)) as CloudFailure).code)
                if (provider === capable) {
                    assertTrue(capable.searches.isEmpty())
                    capable.results = emptyList()
                    assertEquals("search_empty", (failure("Equivalent fractions") as CloudFailure).code)
                    capable.results = listOf(capable.source.copy(url = "file:///private"))
                    assertEquals(CourseWorkflowError.INVALID_SOURCE, (failure("Equivalent fractions") as CourseWorkflowException).reason)
                } else assertEquals("search_unavailable", (failure("Equivalent fractions") as CloudFailure).code)
                assertEquals(saved, store.course(saved.id))
                assertNull(store.slide(saved.id, "current"))
                assertTrue(provider.calls.isEmpty())
            } }
        }
    }

    @Test fun staleTargetAndKeyRemovalRejectLateSearchWithoutGenerating() {
        for (removeKey in listOf(false, true)) {
            val provider = SearchingCourseProvider()
            val transport = BlockingTransport()
            provider.searchBlock = transport
            withShell(provider) { scenario, model, store, saved -> runBlocking {
                val request = CourseSlideGenerationRequest(saved.id, saved.revision, "current")
                val stale = runCatching { model.generateSourcedCourseSlide(request.copy(expectedRevision = saved.revision + 1), "Test query") }
                    .exceptionOrNull() as CourseStoreException
                assertEquals(CourseRejection.STALE_REVISION, stale.reason)
                assertTrue(provider.searches.isEmpty())
                val pending = async(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { model.generateSourcedCourseSlide(request, "Test query") }.exceptionOrNull()
                }
                try {
                    transport.assertStarted()
                    if (removeKey) {
                        scenario.onActivity { model.clearKey() }
                        transport.assertDisconnected()
                    } else {
                        val edited = outline.copy(lessons = listOf(outline.lessons.single().copy(title = "Changed during search")))
                        store.editOutline(saved.id, saved.revision, edited)
                    }
                } finally { transport.release.countDown() }
                val failure = pending.await()
                if (removeKey) assertTrue(failure is CancellationException)
                else assertEquals(CourseRejection.STALE_REVISION, (failure as CourseStoreException).reason)
                assertEquals(1, provider.searches.size)
                assertTrue(provider.calls.isEmpty())
                assertNull(store.slide(saved.id, "current"))
            } }
        }
    }

    @Test fun reachedSlideAndExplicitTutorUseOnlyThisCourseAndSavedSlideReopensOffline() {
        val provider = CourseProvider()
        withShell(provider) { scenario, _, store, saved ->
            openOutline(scenario)
            assertTrue(provider.calls.isEmpty())
            click(R.string.course_open)
            robot.waitFor(slideText)
            assertNotNull(store.slide(saved.id, "current"))
            assertNull(store.slide(saved.id, "future"))
            val slideCalls = provider.calls.toList()
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.SELECTOR, CloudGenerationStage.SELECTOR),
                slideCalls.map { it.request.generationStage })
            assertTrue(slideCalls.all { it.session === slideCalls.first().session })
            assertTrue(slideCalls.first().request.turns.single().text.contains("Explain the current fraction"))

            openTutor("Why do the denominators match?")
            click(R.string.composer_add_context)
            click(R.string.context_paste)
            robot.edit(robot.text(R.string.context_text_label), "My reviewed fraction example")
            click(R.string.context_text_attach)
            assertEquals(slideCalls, provider.calls.toList())
            click(R.string.composer_send)
            robot.waitFor("Tutor fixture answer")
            val tutor = provider.calls.last()
            assertNull(tutor.request.generationStage)
            assertNotSame(slideCalls.first().session, tutor.session)
            assertTrue(tutor.request.turns.single().text.contains("Why do the denominators match?"))
            assertTrue(tutor.request.turns.single().text.contains(slideText))
            assertTrue(tutor.request.turns.single().text.contains("My reviewed fraction example"))
            provider.calls.forEach { call ->
                val payload = call.request.systemInstructions + call.request.turns.joinToString { it.text }
                assertFalse(payload.contains("PRIVATE-"))
                assertFalse(payload.contains(saved.id))
                assertTrue(call.request.turns.all { it.images.isEmpty() })
                assertFalse(call.request.search || call.request.chatDecision)
                assertTrue(call.request.selectedText.isEmpty())
            }

            click(R.string.course_tutor_close)
            robot.waitFor(slideText)
            click(R.string.course_back_to_outline)
            robot.waitFor(robot.text(R.string.course_outline))
            click(R.string.course_open)
            robot.waitFor(slideText)
            assertEquals(slideCalls.size + 1, provider.calls.size)
            assertNull(store.slide(saved.id, "future"))
        }
    }

    @Test fun leavingPlayerDisconnectsGenerationAndDiscardsTheLateSlide() {
        val transport = BlockingTransport()
        val provider = CourseProvider(CloudGenerationStage.PLAN).apply { block = transport }
        withShell(provider) { scenario, _, store, saved ->
            try {
                openOutline(scenario)
                click(R.string.course_open)
                transport.assertStarted()
                click(R.string.course_back_to_outline)
                transport.assertDisconnected()
                robot.waitFor(robot.text(R.string.course_outline))
                transport.release.countDown()
                robot.waitUntil { store.course(saved.id)?.generationFailure == CourseGenerationFailure.INTERRUPTED }
                assertNull(store.slide(saved.id, "current"))
                assertEquals(saved.progress, store.course(saved.id)?.progress)
                assertEquals(listOf(CloudGenerationStage.PLAN), provider.calls.map { it.request.generationStage })
            } finally { transport.release.countDown() }
        }
    }

    @Test fun cancelAndKeyRemovalDisconnectTutorBeforeTransportReturns() {
        val provider = CourseProvider()
        withShell(provider, saveSlide = true) { scenario, model, _, _ ->
            openOutline(scenario)
            click(R.string.course_open)
            robot.waitFor(slideText)
            val question = "Keep my question"
            openTutor(question)

            val first = BlockingTransport()
            provider.block = first
            try {
                click(R.string.composer_send)
                first.assertStarted()
                click(R.string.composer_stop)
                first.assertDisconnected()
                robot.waitFor(robot.text(R.string.course_tutor_canceled))
                assertEquals(question, robot.field(robot.text(R.string.course_tutor_question)).text.toString())
            } finally { first.release.countDown() }

            val second = BlockingTransport()
            provider.block = second
            try {
                click(R.string.composer_send)
                second.assertStarted()
                scenario.onActivity { model.clearKey() }
                second.assertDisconnected()
                robot.waitUntil { !model.keyBusy && model.keyStatus == com.alpha.assistant.ui.KeyStatus.Missing }
                assertNotSame(provider.calls.first().session, provider.calls.last().session)
                assertFalse("Tutor fixture answer" in robot.texts())
            } finally { second.release.countDown() }
        }
    }

    @Test fun externalOutlineEditDiscardsTheLateTutorReply() {
        val provider = CourseProvider()
        val transport = BlockingTransport()
        provider.block = transport
        withShell(provider, saveSlide = true) { scenario, _, store, saved ->
            try {
                openOutline(scenario)
                click(R.string.course_open)
                robot.waitFor(slideText)
                openTutor("Explain the old outline")
                click(R.string.composer_send)
                transport.assertStarted()
                val current = checkNotNull(store.course(saved.id))
                val lesson = current.outline.lessons.single()
                val edited = store.editOutline(saved.id, current.revision, CourseOutline(listOf(lesson.copy(
                    slides = listOf(lesson.slides.first(), lesson.slides.last().copy(goal = "A new goal")),
                ))))
                transport.release.countDown()
                robot.waitFor(robot.text(R.string.course_tutor_failed))
                assertFalse("Tutor fixture answer" in robot.texts())
                assertEquals(edited, store.course(saved.id))
            } finally { transport.release.countDown() }
        }
    }

    private fun openOutline(scenario: ActivityScenario<CloudTestActivity>) {
        robot.openDrawer(scenario)
        click(R.string.course_learning)
        robot.awaitDrawerClosed(scenario)
        robot.click(courseTitle)
        robot.waitFor(robot.text(R.string.course_outline))
    }

    private fun openTutor(question: String) {
        click(R.string.course_tutor_open)
        val label = robot.text(R.string.course_tutor_question)
        robot.waitFor(label)
        robot.edit(label, question)
    }

    private fun click(id: Int) = robot.click(robot.text(id))

    private fun withShell(provider: CourseProvider, saveSlide: Boolean = false, completeSlide: Boolean = false,
        test: (ActivityScenario<CloudTestActivity>, ShellViewModel, CourseStore, CourseSnapshot) -> Unit) {
        val shell = ShellFixture("course-host")
        val courseDb = "${shell.store.databaseName}-courses.db"
        val memoryDb = "${shell.store.databaseName}-memory.db"
        try {
            shell.seedChat(conversationId, "PRIVATE-CHAT-TITLE", "PRIVATE-CHAT-MESSAGE", selected = true)
            MemoryStore(shell.context, memoryDb).use { it.saveExplicitFact("PRIVATE-MEMORY Compare fractions") }
            check(shell.keys.save(credential).isSuccess)
            CloudTestActivity.factory = { app -> ShellViewModel(app, provider, shell.store, shell.keys).also { shell.model = it } }
            CourseStore(shell.context, courseDb).use { store ->
                store.create(CourseSetup("PRIVATE-OTHER-COURSE", "Beginner", "Unrelated goal"), outline)
                val saved = store.create(CourseSetup(courseTitle, "Beginner", "Learn equivalent fractions", conversationId), outline)
                if (saveSlide || completeSlide) {
                    val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Fractions","description":"","capabilities":[],
                        "stateSchema":{"fixture":"boolean"},"initialState":{"fixture":false},"ui":{"type":"text","text":"$slideText"},
                        "source":"return {state:input.state};"}""")
                    val prepared = store.commitGeneration(store.beginGeneration(saved.id, saved.revision, "current"),
                        CourseSlideContent(spec.json, spec.ui.toString(), spec.initialState))
                    if (completeSlide) store.advance(saved.id, prepared.revision, "current")
                }
                shell.launch().use { scenario -> test(scenario, checkNotNull(shell.model), store, saved) }
            }
        } finally {
            shell.remove()
            shell.context.deleteDatabase(courseDb)
            shell.context.deleteDatabase(memoryDb)
        }
    }

    private data class Call(val request: CloudRequest, val session: CloudSession)

    private open inner class CourseProvider(private val blockedStage: CloudGenerationStage? = null) : ChatProvider {
        val calls = CopyOnWriteArrayList<Call>()
        @Volatile var block: BlockingTransport? = null
        open val sourceIds: List<String> = emptyList()

        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            check(key == credential)
            calls += Call(request, session)
            if (request.generationStage == blockedStage) block?.waitForRelease(session)
            when (request.generationStage) {
                CloudGenerationStage.PLAN -> {
                    val plan = JSONObject("""{"title":"Fractions","description":"","language":"en",
                    "inputs":[],"constants":[{"id":"body","value":"$slideText"}],"needs":[{"id":"lesson",
                    "type":"explanation","label":"Fractions","description":"Explain the form","uses":{"body":"body"}}],"questions":[]}""")
                    if (sourceIds.isNotEmpty()) plan.getJSONArray("needs").getJSONObject(0).put("sourceIds", JSONArray(sourceIds))
                    emit(CloudEvent.Text(plan.toString()))
                }
                CloudGenerationStage.SELECTOR -> {
                    val questions = JSONObject(request.turns.single().text).getJSONArray("questions")
                    val answers = JSONArray()
                    repeat(questions.length()) { index ->
                        answers.put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                            .put("choice", "A").put("confidence", 1).put("probabilities", JSONObject().put("A", 1).put("B", 0)))
                    }
                    emit(CloudEvent.Text(JSONObject().put("answers", answers).toString()))
                }
                null -> emit(CloudEvent.RoundComplete(if (request.jsonObject) """{"lessons":[{"id":1,"title":"Common denominators",
                    "slides":[{"id":1,"kind":"EXPLANATION","goal":"Explain the current fraction"},
                    {"id":2,"kind":"EXPLANATION","goal":"Explain the next fraction with an example"}]}]}""" else "Tutor fixture answer", false))
                else -> error("Unexpected request")
            }
        }
    }

    private data class SearchCall(val query: String, val session: CloudSession)

    private inner class SearchingCourseProvider : CourseProvider(), CloudSearchProvider {
        val source = Source("Local source title", "https://example.org/fractions", "An equivalent fraction has the same value.")
        val searches = CopyOnWriteArrayList<SearchCall>()
        var results = listOf(source)
        var searchBlock: BlockingTransport? = null
        override val sourceIds = listOf("s1")

        override suspend fun search(key: String, query: String, session: CloudSession): List<Source> {
            check(key == credential)
            searches += SearchCall(query, session)
            searchBlock?.waitForRelease(session)
            return results
        }
    }

    /** The fake transport stays blocked after disconnect until the test releases it. */
    private class BlockingTransport {
        private val started = CountDownLatch(1)
        private val disconnected = CountDownLatch(1)
        private val returned = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val connection = object : HttpURLConnection(URL("http://127.0.0.1")) {
            override fun disconnect() { disconnected.countDown() }
            override fun connect() = error("No network")
            override fun usingProxy() = false
        }
        fun waitForRelease(session: CloudSession) {
            session.attach(connection)
            started.countDown()
            try { release.await() } finally {
                session.detach(connection)
                returned.countDown()
            }
        }
        fun assertStarted() { assertTrue("The provider did not start", started.await(10, TimeUnit.SECONDS)) }
        fun assertDisconnected() {
            assertTrue("The transport stayed attached after cancellation", disconnected.await(5, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
        }
        fun assertReturned() { assertTrue("The provider did not return", returned.await(5, TimeUnit.SECONDS)) }
    }
}
