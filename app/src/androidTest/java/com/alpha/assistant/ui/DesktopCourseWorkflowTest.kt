package com.alpha.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.view.accessibility.AccessibilityNodeInfo
import com.alpha.assistant.R
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.courses.CourseLessonPlan
import com.alpha.assistant.courses.CourseOutline
import com.alpha.assistant.courses.CourseSetup
import com.alpha.assistant.courses.CourseSlideContent
import com.alpha.assistant.courses.CourseSlideKind
import com.alpha.assistant.courses.CourseSlidePlan
import com.alpha.assistant.courses.CourseSnapshot
import com.alpha.assistant.courses.CourseStore
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopCourseWorkflowTest {
    private val fixture = ShellFixture("desktop-course")
    private val robot = ShellRobot("desktop-course")
    private val coursesName = "${fixture.store.databaseName}-courses.db"
    private val toolsName = "${fixture.store.databaseName}-tools.db"
    private val automationsName = "$toolsName-automations.db"
    private val desktopName = "$toolsName-desktop.db"

    @After fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase(coursesName)
        fixture.context.deleteDatabase(desktopName)
    }

    @Test fun nowKeepsAttentionAndResumesTheFirstUnfinishedCourseOffline() {
        assumeTrue(fixture.context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
        val older = seedCourse("Older course", reachedLesson = 2)
        val chosen = seedCourse("Chosen course", reachedLesson = 2)
        seedCourse("Completed course", reachedLesson = 2, completed = true)
        val attention = listOf("Review morning routine", "Review evening routine").map { name ->
            val rule = seedAutomation(name)
            robot.text(R.string.desktop_now_automation_detail, name, rule.attention)
        }
        fixture.install(saveKey = false)

        fixture.launch().use {
            attention.forEach { reveal(it) }
            reveal(robot.text(R.string.desktop_now_course_detail, chosen.setup.topic, 2, 2))
            click(R.string.desktop_edit)
            reveal(robot.text(R.string.desktop_done))
            click(R.string.desktop_add_course)
            click(R.string.desktop_place_course, chosen.setup.topic)
            click(R.string.desktop_done)
            assertEquals(0, fixture.provider.calls)
            click(R.string.desktop_now_course_title)
            robot.waitFor(slideText(chosen, 2))
            robot.waitFor(robot.text(R.string.course_slide_position, 2, 2))
            assertEquals(0, fixture.provider.calls)
            click(R.string.course_next)
            robot.waitFor(robot.text(R.string.course_complete))
            backToDesktop()
            reveal(robot.text(R.string.desktop_now_course_detail, older.setup.topic, 2, 2))
            robot.waitGone(robot.text(R.string.desktop_now_course_detail, chosen.setup.topic, 2, 2))
            reveal(robot.text(R.string.desktop_open_tile, chosen.setup.topic))
            reveal(robot.text(R.string.desktop_course_lesson_progress, 2, 2))
            reveal(robot.text(R.string.course_complete))
            assertEquals(listOf(chosen.id), layoutIds())
            assertTrue(checkNotNull(saved(chosen.id)).progress.isComplete)
            assertFalse(checkNotNull(saved(older.id)).progress.isComplete)
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun unsavedReachedSlideRequestsTheProviderOnlyAfterContinue() {
        val course = seedCourse("Prepare after continue", prepare = false)
        val providerGate = CompletableDeferred<Unit>()
        fixture.install()
        fixture.provider.gate = providerGate
        try {
            fixture.launch().use {
                reveal(robot.text(R.string.desktop_now_course_detail, course.setup.topic, 1, 2))
                robot.instrumentation.waitForIdleSync()
                assertEquals(0, fixture.provider.calls)
                assertNull(saved(course.id)?.generation)
                click(R.string.desktop_now_course_title)
                robot.waitUntil { fixture.provider.calls == 1 }
                robot.waitFor(robot.text(R.string.course_generation_waiting))
                assertEquals("intro", saved(course.id)?.generation?.slideId)
                click(R.string.course_generation_cancel)
                robot.waitFor(robot.text(R.string.course_generation_interrupted))
                CourseStore(fixture.context, coursesName).use { store ->
                    assertNull(store.slide(course.id, "intro"))
                    assertNull(store.slide(course.id, "review"))
                }
            }
        } finally {
            providerGate.complete(Unit)
        }
        assertEquals(1, fixture.provider.calls)
    }

    @Test fun coursePlacementReordersWithOtherTilesAndRefreshesOnReturn() {
        val course = seedCourse("Placed course")
        seedTool()
        val automation = seedAutomation("Placed routine")
        fixture.install(saveKey = false)

        fixture.launch().use { scenario ->
            reveal(robot.text(R.string.desktop_grid_title))
            assertNull(robot.find(robot.text(R.string.desktop_open_tile, course.setup.topic)))
            click(R.string.desktop_edit)
            click(R.string.desktop_add_automation)
            click(R.string.desktop_place_automation, automation.definition.name)
            click(R.string.desktop_add_course)
            click(R.string.desktop_place_course, course.setup.topic)
            val earlier = robot.text(R.string.desktop_move_earlier, course.setup.topic)
            reveal(earlier)
            robot.waitUntil { robot.find(earlier)?.isEnabled == true }
            robot.assertTarget(earlier)
            robot.click(earlier)
            robot.waitUntil { layoutIds() == listOf("desktop-tool", course.id, automation.id) }
            click(R.string.desktop_done)
            reveal(robot.text(R.string.desktop_course_lesson_progress, 1, 2))
            click(R.string.desktop_open_tile, course.setup.topic)
            robot.waitFor(slideText(course, 1))
            click(R.string.course_next)
            robot.waitFor("Review the example")
            assertEquals("second", saved(course.id)?.progress?.currentLessonId)
            backToDesktop()
            reveal(robot.text(R.string.desktop_course_lesson_progress, 2, 2))
            assertEquals(listOf("desktop-tool", course.id, automation.id), layoutIds())

            scenario.recreate()
            reveal(robot.text(R.string.desktop_course_lesson_progress, 2, 2))
            assertEquals(listOf("desktop-tool", course.id, automation.id), layoutIds())
            click(R.string.desktop_edit)
            click(R.string.desktop_remove_tile, course.setup.topic)
            robot.waitUntil { layoutIds() == listOf("desktop-tool", automation.id) }
            click(R.string.desktop_done)
            robot.openDrawer(scenario)
            click(R.string.course_learning)
            robot.awaitDrawerClosed(scenario)
            reveal(course.setup.topic)
            assertEquals(1, saved(course.id)?.progress?.completedSlides)
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun courseReadFailureKeepsPlacementAndMissingCourseHidesOnlyItsTile() {
        val missing = seedCourse("Missing course", prepare = false)
        val surviving = seedCourse("Surviving course", prepare = false)
        seedTool()
        fixture.install(saveKey = false)

        fixture.launch().use {
            click(R.string.desktop_edit)
            click(R.string.desktop_add_course)
            click(R.string.desktop_place_course, missing.setup.topic)
            click(R.string.desktop_add_course)
            click(R.string.desktop_place_course, surviving.setup.topic)
            robot.waitUntil { layoutIds() == listOf("desktop-tool", missing.id, surviving.id) }
        }

        val order = layoutIds()
        openDatabase(coursesName).use { it.version = 2 }
        try {
            fixture.launch().use {
                reveal(robot.text(R.string.desktop_courses_load_failed))
                reveal(robot.text(R.string.desktop_open_tile, "Desktop tool"))
                assertEquals(order, layoutIds())
            }
        } finally {
            openDatabase(coursesName).use { it.version = 1 }
        }

        fixture.launch().use {
            reveal(robot.text(R.string.desktop_open_tile, missing.setup.topic))
            reveal(robot.text(R.string.desktop_open_tile, surviving.setup.topic))
        }
        openDatabase(coursesName).use { db ->
            assertEquals(1, db.delete("courses", "id=?", arrayOf(missing.id)))
        }

        fixture.launch().use {
            reveal(robot.text(R.string.desktop_open_tile, surviving.setup.topic))
            assertNull(robot.find(robot.text(R.string.desktop_open_tile, missing.setup.topic)))
            assertEquals(listOf("desktop-tool", surviving.id), layoutIds().filterNot { it == missing.id })
            assertNotNull(saved(surviving.id))
        }
        assertEquals(0, fixture.provider.calls)
    }

    private fun backToDesktop() {
        click(R.string.course_back_to_outline)
        click(R.string.course_back_to_library)
        click(R.string.course_exit)
        reveal(robot.text(R.string.desktop_grid_title))
    }

    private fun click(id: Int, vararg args: Any) {
        val label = robot.text(id, *args)
        reveal(label)
        robot.waitUntil { robot.find(label)?.isEnabled == true }
        robot.click(label)
    }

    private fun reveal(label: String): AccessibilityNodeInfo = robot.reveal(label, forward = null)

    private fun seedCourse(title: String, reachedLesson: Int = 1, completed: Boolean = false, prepare: Boolean = true): CourseSnapshot =
        CourseStore(fixture.context, coursesName).use { store ->
            val created = store.create(CourseSetup(title, "Beginner", "Practice examples"), CourseOutline(listOf(
                CourseLessonPlan("first", "First lesson", listOf(CourseSlidePlan("intro", CourseSlideKind.EXPLANATION, "Read the example"))),
                CourseLessonPlan("second", "Second lesson", listOf(CourseSlidePlan("review", CourseSlideKind.SUMMARY, "Review the example"))),
            )))
            if (!prepare) return@use created
            val first = prepareSlide(store, created, "intro", 1)
            if (reachedLesson == 1) return@use first
            val second = prepareSlide(store, store.advance(first.id, first.revision, "intro"), "review", 2)
            if (completed) store.advance(second.id, second.revision, "review") else second
        }

    private fun prepareSlide(store: CourseStore, course: CourseSnapshot, slideId: String, lesson: Int): CourseSnapshot {
        val ui = """{"type":"text","text":"${slideText(course, lesson)}"}"""
        val definition = """{"formatVersion":3,"title":"Saved lesson","description":"","capabilities":[],
            "stateSchema":{"value":"string"},"initialState":{"value":""},"ui":$ui,"source":"return {state:input.state};"}"""
        val operation = store.beginGeneration(course.id, course.revision, slideId)
        return store.commitGeneration(operation, CourseSlideContent(definition, ui, """{"value":""}"""))
    }

    private fun slideText(course: CourseSnapshot, lesson: Int) = "${course.setup.topic} saved slide $lesson"

    private fun saved(id: String) = CourseStore(fixture.context, coursesName).use { it.course(id) }

    private fun seedAutomation(name: String) = AutomationStore(fixture.context, automationsName).use { store ->
        store.save(null, JSONObject("""{"name":"$name","trigger":{"type":"manual"},"conditions":{},"actions":[
            {"id":"open","capability":"link.open","args":{"url":"https://example.com/"}}]}"""))
    }

    private fun seedTool() {
        val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Desktop tool","description":"","capabilities":[],
            "stateSchema":{"value":"string"},"initialState":{"value":""},"ui":{"type":"text","text":"Tool content"},
            "source":"return {state:input.state};"}""")
        ToolStore(fixture.context, toolsName).use { store ->
            store.commitTool(ToolRecord("desktop-tool", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true,
                onDesktop = true), null, revision = true)
        }
    }

    private fun openDatabase(name: String): SQLiteDatabase =
        SQLiteDatabase.openDatabase(fixture.context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun layoutIds(): List<String> = openDatabase(desktopName).use { db ->
        db.rawQuery("SELECT item_id FROM desktop_items ORDER BY position", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
    }
}
