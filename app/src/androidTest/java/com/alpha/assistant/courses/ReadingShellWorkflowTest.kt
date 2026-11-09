package com.alpha.assistant.courses

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingShellWorkflowTest {
    @Test fun savedWorkshopShowsCanvasAndRestoresTheNextSlide() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "com.alpha.assistant.readingtest")
        val fixture = ShellFixture("reading-shell")
        val robot = ShellRobot("reading-shell")
        try {
            fixture.install(saveKey = false)
            fixture.launch().use { scenario ->
                robot.waitUntil { robot.composed(scenario, "desktop") }
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.course_learning))
                robot.awaitDrawerClosed(scenario)
                robot.click("Открыть мастерскую слов")
                robot.waitFor("Буква А")
                robot.reveal("Помаши Буквику")
                robot.click("Помаши Буквику")
                robot.waitFor("Настроить голос Evgeny v2")
                robot.diagnostics.capture("workshop-canvas")
                robot.click(robot.text(R.string.course_next))
                robot.waitFor("Послушай слово и выбери его первую букву.")
                scenario.recreate()
                robot.waitFor("Послушай слово и выбери его первую букву.")
                robot.diagnostics.capture("workshop-restored")
            }
        } finally {
            fixture.remove()
        }
    }
    @Test fun leavingPracticeGenerationKeepsTheLibraryOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "com.alpha.assistant.readingtest")
        val fixture = ShellFixture("reading-navigation")
        val robot = ShellRobot("reading-navigation")
        val database = "${fixture.store.databaseName}-courses.db"
        val core = CourseStore(context, database).use { store ->
            val seed = ReadingMaterial.practice(listOf(ReadingItem(ReadingMaterial.vocabulary.first(), false)), 0, false)
            val course = store.saveReading(seed)
            store.commitAnswer(course.id, CourseAnswerSubmission("fixture", course.revision,
                course.progress.currentSlideId!!, CourseAnswer.Choice("tile0"), true,
                seed.slides.values.single().stateJson))
        }
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val slow = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        try {
            fixture.install()
            CloudTestActivity.factory = { app ->
                ShellViewModel(app, slow, fixture.store, fixture.keys).also { fixture.model = it }
            }
            fixture.launch().use { scenario ->
                robot.waitUntil { robot.composed(scenario, "desktop") }
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.course_learning))
                robot.awaitDrawerClosed(scenario)
                robot.click("Открыть мастерскую слов")
                robot.click("Продолжить практику")
                runBlocking { withTimeout(5000) { entered.await() } }
                robot.click(robot.text(R.string.course_back_to_library))
                robot.waitFor(robot.text(R.string.course_library))
                runBlocking { withTimeout(5000) { cancelled.await() } }
                robot.waitGone("Продолжить практику")
                CourseStore(context, database).use { store -> assertEquals(listOf(core.id), store.readingChain(core.id).map { it.id }) }
            }
        } finally {
            fixture.remove()
            context.deleteDatabase(database)
        }
    }
}
