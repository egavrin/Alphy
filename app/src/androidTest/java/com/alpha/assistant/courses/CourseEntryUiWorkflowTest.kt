package com.alpha.assistant.courses

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Exercise an unavailable creator, delegated creation, a missing ID, and a retained entry request.
@RunWith(AndroidJUnit4::class)
class CourseEntryUiWorkflowTest {
    private val robot = ShellRobot("course-entry")

    @Test fun libraryShowsWhenTheCreationHostIsUnavailable() = withLibrary { _, store, saved ->
        robot.waitFor(saved.setup.topic)
        assertNull(robot.find(robot.text(R.string.course_new)))
        robot.click(saved.setup.topic)
        waitFor(R.string.course_outline)
        assertEquals(listOf(saved), store.courses())
    }

    @Test fun newCourseActionDelegatesWithoutWritingARecord() {
        val starts = AtomicInteger()
        withLibrary(onStartCourse = { starts.incrementAndGet() }) { _, store, saved ->
            click(R.string.course_new)
            robot.waitUntil { starts.get() == 1 }
            assertEquals(listOf(saved), store.courses())
        }
    }

    @Test fun reachedEntryUsesTheSavedPositionAndBackDoesNotReplayIt() = withLibrary(
        entryOnMount = { CourseEntryRequest("resume-chat-card", it.id, CourseEntryDestination.REACHED_SLIDE) },
    ) { screen, store, saved ->
        robot.waitFor(reachedText)
        robot.waitUntil { screen.handled == listOf("resume-chat-card") }
        robot.back()
        waitFor(R.string.course_outline)
        robot.back()
        waitFor(R.string.course_library)
        assertEquals(listOf("resume-chat-card"), screen.handled)
        assertEquals(saved, store.course(saved.id))
    }

    @Test fun backFromAChatPlayerExitsDirectlyToItsHost() {
        val exits = AtomicInteger()
        withLibrary(
            entryOnMount = { CourseEntryRequest("chat", it.id, CourseEntryDestination.REACHED_SLIDE, exitToHostOnBack = true) },
            onExit = { exits.incrementAndGet() },
        ) { screen, store, saved ->
            robot.waitFor(reachedText)
            waitFor(R.string.course_exit)
            robot.back()
            robot.waitUntil { exits.get() == 1 }
            robot.waitFor(originChatDraft)
            assertEquals(listOf("chat"), screen.handled)
            assertEquals(saved, store.course(saved.id))
        }
    }

    @Test fun chatOutlineShowsSourcedPreparationAndBackCancelsItBeforeLeaving() {
        val entered = AtomicReference<Pair<CourseSlideGenerationRequest, String>>()
        val canceled = CompletableDeferred<Unit>()
        val exits = AtomicInteger()
        val host = CourseSourcedSlideGenerationHost { request, query ->
            try {
                entered.set(request to query)
                awaitCancellation()
            } finally {
                canceled.complete(Unit)
            }
        }
        withLibrary(
            entryOnMount = {
                CourseEntryRequest("chat-outline", it.id, CourseEntryDestination.OUTLINE, exitToHostOnBack = true)
            },
            hostModifier = Modifier.size(0.dp),
            saveReached = false,
            sourcedGeneration = host,
            onExit = { exits.incrementAndGet() },
        ) { screen, store, saved ->
            waitFor(R.string.course_outline)
            click(R.string.course_sourced_open)
            val query = "Examples for the reached slide"
            robot.edit(robot.text(R.string.course_sourced_query), query)
            click(R.string.course_sourced_submit)
            robot.waitUntil { entered.get() != null }
            assertEquals(CourseSlideGenerationRequest(saved.id, saved.revision, "reached") to query, entered.get())

            robot.back()
            robot.waitUntil { canceled.isCompleted }
            robot.waitGone(robot.text(R.string.course_sourced_query))
            waitFor(R.string.course_outline)
            assertEquals(0, exits.get())
            assertEquals(saved, store.course(saved.id))

            robot.back()
            robot.waitUntil { exits.get() == 1 }
            robot.waitFor(originChatDraft)
            assertEquals(listOf("chat-outline"), screen.handled)
            assertEquals(saved, store.course(saved.id))
        }
    }

    @Test fun completedChatEntryCanExitWithoutOpeningTheLibrary() {
        val exits = AtomicInteger()
        withLibrary(
            entryOnMount = { CourseEntryRequest("completed", it.id, CourseEntryDestination.REACHED_SLIDE, exitToHostOnBack = true) },
            complete = true,
            onExit = { exits.incrementAndGet() },
        ) { screen, store, saved ->
            waitFor(R.string.course_complete)
            click(R.string.course_exit)
            robot.waitUntil { exits.get() == 1 }
            robot.waitFor(originChatDraft)
            assertEquals(listOf("completed"), screen.handled)
            assertEquals(saved, store.course(saved.id))
        }
    }

    @Test fun missingChatEntryAcknowledgesTheRequestAndDismissesTheOverlay() {
        val exits = AtomicInteger()
        withLibrary(
            entryOnMount = { CourseEntryRequest("missing-chat", "missing-course", CourseEntryDestination.REACHED_SLIDE, exitToHostOnBack = true) },
            onExit = { exits.incrementAndGet() },
        ) { screen, store, saved ->
            robot.waitUntil { exits.get() == 1 }
            robot.waitFor(originChatDraft)
            assertEquals(listOf("missing-chat"), screen.handled)
            assertEquals(listOf(saved), store.courses())
        }
    }

    @Test fun missingEntryAcknowledgesTheRequestAndKeepsTheExistingOutline() = withLibrary { screen, store, saved ->
        val outline = CourseEntryRequest("open-outline", saved.id, CourseEntryDestination.OUTLINE)
        screen.request(outline)
        waitFor(R.string.course_outline)
        val missing = CourseEntryRequest("missing", "missing-course", CourseEntryDestination.REACHED_SLIDE)
        screen.request(missing)
        waitFor(R.string.course_entry_unavailable)
        waitFor(R.string.course_outline)
        assertEquals(listOf(outline.requestId, missing.requestId), screen.handled)
        assertEquals(saved, store.course(saved.id))
    }

    private fun withLibrary(
        onStartCourse: (() -> Unit)? = null,
        entryOnMount: ((CourseSnapshot) -> CourseEntryRequest)? = null,
        complete: Boolean = false,
        hostModifier: Modifier = Modifier,
        saveReached: Boolean = true,
        sourcedGeneration: CourseSourcedSlideGenerationHost? = null,
        onExit: () -> Unit = {},
        run: (EntryScreen, CourseStore, CourseSnapshot) -> Unit,
    ) {
        val fixture = ShellFixture("course-entry")
        val database = "${fixture.name}-course-fixture.db"
        try {
            fixture.install(saveKey = false)
            CourseStore(fixture.context, database).use { store ->
                val reached = seed(store, saveReached)
                val saved = if (complete) store.advance(reached.id, reached.revision, "reached") else reached
                val controller = CourseController(store, CourseGeneration(
                    generateTool = { _, _, _, _, _, _ -> error("Unexpected provider call.") },
                    evaluateTool = { _, _, _, _, _, _, _ -> error("Unexpected generated event.") },
                ))
                fixture.launch().use { scenario ->
                    val screen = EntryScreen(scenario)
                    entryOnMount?.let { screen.request(it(saved)) }
                    scenario.onActivity { activity ->
                        activity.setContent {
                            MaterialTheme {
                                val showCourses = remember { mutableStateOf(true) }
                                if (showCourses.value) {
                                    CourseLibraryScreen(
                                        store, controller, onExit = {
                                            showCourses.value = false
                                            onExit()
                                        },
                                        modifier = hostModifier,
                                        onStartCourse = onStartCourse, entryRequest = screen.entry.value,
                                        onEntryHandled = { screen.handled += it },
                                        sourcedGeneration = sourcedGeneration,
                                    )
                                } else {
                                    Text(originChatDraft)
                                }
                            }
                        }
                    }
                    if (entryOnMount == null) waitFor(R.string.course_library)
                    run(screen, store, saved)
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase(database)
        }
    }

    private fun seed(store: CourseStore, saveReached: Boolean): CourseSnapshot {
        val course = store.create(CourseSetup("Fractions", "Beginner", "Compare fractions"),
            CourseOutline(listOf(CourseLessonPlan("lesson", "Compare fractions", listOf(
                CourseSlidePlan("first", CourseSlideKind.EXPLANATION, "Explain fractions"),
                CourseSlidePlan("reached", CourseSlideKind.SUMMARY, "Review the result"),
            )))))
        val first = store.commitGeneration(store.beginGeneration(course.id, course.revision, "first"), content("Earlier slide"))
        val reached = store.advance(first.id, first.revision, "first")
        if (!saveReached) return reached
        return store.commitGeneration(store.beginGeneration(reached.id, reached.revision, "reached"), content(reachedText))
    }

    private fun content(text: String): CourseSlideContent {
        val ui = """{"type":"text","text":"$text"}"""
        val state = """{"value":"a"}"""
        val spec = """{"formatVersion":3,"title":"Fixture","description":"","capabilities":[],
            "stateSchema":{"value":"string"},"initialState":$state,"ui":$ui,"source":"return {state:input.state};"}"""
        return CourseSlideContent(spec, ui, state)
    }

    private fun click(id: Int) = robot.click(robot.text(id))
    private fun waitFor(id: Int) = robot.waitFor(robot.text(id))

    private class EntryScreen(private val scenario: ActivityScenario<CloudTestActivity>) {
        val entry: MutableState<CourseEntryRequest?> = mutableStateOf(null)
        val handled = CopyOnWriteArrayList<String>()
        fun request(value: CourseEntryRequest) {
            scenario.onActivity { entry.value = value }
        }
    }

    companion object {
        private const val reachedText = "Saved reached slide"
        private const val originChatDraft = "Origin chat draft"
    }
}
