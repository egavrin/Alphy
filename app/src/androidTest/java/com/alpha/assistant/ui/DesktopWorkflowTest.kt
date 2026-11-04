package com.alpha.assistant.ui

import android.content.res.Resources
import android.util.Log
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class DesktopWorkflowTest {
    private val fixture = ShellFixture("desktop")
    private val robot = ShellRobot("desktop")
    @After fun remove() = fixture.remove()

    // The hour can change between composition and the check.
    private fun greetings(name: String) = (-1..1)
        .map { greetingRes((LocalTime.now().hour + it + 24) % 24) }
        .map { robot.text(R.string.greeting_named, robot.text(it), name) }

    @Test fun desktopGreetsByNameAndSuggestsLocally() {
        fixture.install(saveKey = false)
        fixture.store.saveSetting("userName", "Евгений")
        fixture.launch().use { scenario ->
            robot.waitUntil { greetings("Евгений").any { robot.find(it) != null } }
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.waitFor(robot.text(R.string.now_title))
            robot.click(robot.text(R.string.now_connect_title))
            robot.waitUntil { robot.secure(scenario) }
            robot.back()
            robot.click(robot.text(R.string.now_day_title))
            robot.waitFor(robot.text(R.string.automations))
            assertEquals(0, fixture.provider.calls)
        }
    }

    @Test fun desktopSendOpensChatAndBackReturns() {
        fixture.install()
        fixture.launch().use {
            robot.waitFor(robot.text(R.string.now_mini_app_title))
            robot.assertTarget(robot.text(R.string.composer_label))
            robot.instrumentation.waitForIdleSync()
            assertEquals(0, fixture.provider.calls)
            robot.click(robot.text(R.string.now_day_title))
            robot.waitFor(robot.text(R.string.automations))
            robot.back()
            robot.waitFor(robot.text(R.string.desktop_prompt))
            assertEquals(0, fixture.provider.calls)
            robot.edit(robot.text(R.string.composer_label), "Desktop question")
            robot.click(robot.text(R.string.composer_send))
            robot.waitFor("Fixture response")
            assertEquals(1, fixture.provider.calls)
            robot.back()
            robot.waitGone("Fixture response")
            robot.waitFor(robot.text(R.string.desktop_prompt))
        }
    }

    @Test fun largeTextKeepsShellUsable() {
        val scale = robot.shell("settings get system font_scale")
        try {
            robot.shell("settings put system font_scale 2.0")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == 2f }
            fixture.install()
            val name = "Александра-Валентина Константиновна"
            fixture.store.saveSetting("userName", name)
            val title = "Очень длинное название разговора, которое не помещается в одну строку меню"
            fixture.seedChat("long", title, "Long chat message")
            fixture.launch().use { scenario ->
                scenario.onActivity { assertEquals(2f, it.resources.configuration.fontScale) }
                for (id in listOf(R.string.menu, R.string.new_chat, R.string.composer_label, R.string.composer_send))
                    robot.assertTarget(robot.text(id))
                robot.waitUntil { greetings(name).any { robot.find(it) != null } }
                robot.openDrawer(scenario)
                robot.assertTarget(robot.text(R.string.settings))
                robot.click(title)
                robot.awaitDrawerClosed(scenario)
                robot.waitFor("Long chat message")
                try {
                    robot.rotate(scenario, landscape = true)
                    robot.openDrawer(scenario)
                    robot.assertTarget(robot.text(R.string.settings))
                } finally { robot.rotate(scenario, landscape = false) }
            }
        } finally {
            // "settings get" prints "null" when the value is unset, and "settings put … null" does not restore that state.
            robot.shell(if (scale.toFloatOrNull() == null) "settings delete system font_scale" else "settings put system font_scale $scale")
            assertEquals(scale, robot.shell("settings get system font_scale"))
        }
    }

    @Test fun panesStayCenteredInLandscape() {
        fixture.install()
        fixture.seedChat("wide", "Wide chat", "Wide chat message")
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.desktop_prompt))
            try {
                robot.rotate(scenario, landscape = true)
                robot.waitFor(robot.text(R.string.desktop_prompt))
                assertCentered(scenario, "desktop")
                assertCentered(scenario, "composer")
                robot.openDrawer(scenario)
                // In landscape, the recent chats are below the viewport of the drawer list.
                robot.reveal("Wide chat")
                robot.click("Wide chat")
                robot.awaitDrawerClosed(scenario)
                robot.waitFor("Wide chat message")
                assertCentered(scenario, "chat")
            } finally { robot.rotate(scenario, landscape = false) }
        }
    }

    // The node is min(620 dp, root width) wide, and its left and right margins in the root are equal.
    private fun assertCentered(scenario: ActivityScenario<*>, tag: String) {
        val (bounds, rootWidth) = robot.composedBounds(scenario, tag)
        val width = minOf(620 * robot.instrumentation.targetContext.resources.displayMetrics.density, rootWidth.toFloat())
        val right = rootWidth - bounds.right
        Log.i("DesktopWorkflowTest", "tag=$tag bounds=$bounds rootWidth=$rootWidth expectedWidth=$width left=${bounds.left} right=$right")
        assertEquals("Width of $tag", width, bounds.width().toFloat(), 1f)
        assertEquals("Margins of $tag", bounds.left.toFloat(), right.toFloat(), 1f)
    }
}
