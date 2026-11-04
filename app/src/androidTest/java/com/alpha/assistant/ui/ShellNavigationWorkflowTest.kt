package com.alpha.assistant.ui

import android.accessibilityservice.AccessibilityService
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShellNavigationWorkflowTest {
    private val fixture = ShellFixture("shell-navigation")
    private val robot = ShellRobot("shell-navigation")
    @Before fun install() = fixture.install()
    @After fun remove() = fixture.remove()

    private fun openSettings(scenario: ActivityScenario<*>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.waitUntil { robot.secure(scenario) }
    }

    @Test fun backLeavesSettingsThenChatThenApp() {
        fixture.seedChat("nav", "Nav fixture", "Nav fixture message", selected = true)
        fixture.launch().use { scenario ->
            robot.waitFor("Nav fixture message")
            openSettings(scenario)
            scenario.recreate()
            robot.waitUntil { robot.secure(scenario) }
            robot.back()
            robot.waitFor("Nav fixture message")
            robot.waitUntil { !robot.secure(scenario) }
            robot.back()
            robot.waitGone("Nav fixture message")
            robot.waitUntil { fixture.store.load().selectedId == null }
            robot.back()
            robot.waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun entryReplacesDestination() {
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.instrumentation.runOnMainSync { fixture.model!!.openSurface(null) }
            robot.waitUntil { !robot.secure(scenario) }
            robot.back()
            robot.waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun settingsStaySecureUntilTheyLeave() {
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.waitUntil { robot.composed(scenario, "secure") }
            assertTrue(robot.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            robot.waitUntil {
                // Read the flag first. The tagged content leaves composition in the frame that clears the flag.
                val secure = robot.secure(scenario)
                val shown = robot.composed(scenario, "secure")
                assertTrue("Settings content shows without FLAG_SECURE", secure || !shown)
                !shown
            }
            assertFalse(robot.secure(scenario))
        }
    }
}
