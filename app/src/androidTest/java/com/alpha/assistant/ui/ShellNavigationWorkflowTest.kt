package com.alpha.assistant.ui

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import org.junit.After
import org.junit.Before
import org.junit.Test

class ShellNavigationWorkflowTest {
    private val fixture = ShellFixture("shell-navigation")
    private val robot = ShellRobot("shell-navigation")
    @Before fun install() = fixture.install()
    @After fun remove() = fixture.remove()

    private fun openSettings(scenario: ActivityScenario<*>) {
        robot.click(robot.text(R.string.menu))
        robot.click(robot.text(R.string.settings))
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
}
