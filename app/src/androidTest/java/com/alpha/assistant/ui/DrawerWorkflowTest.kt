package com.alpha.assistant.ui

import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DrawerWorkflowTest {
    private val fixture = ShellFixture("drawer")
    private val robot = ShellRobot("drawer")
    @Before fun install() = fixture.install()
    @After fun remove() = fixture.remove()

    @Test fun drawerShowsRowsAndBackClosesItFirst() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        fixture.seedChat("beta", "Beta chat", "Beta message")
        fixture.launch().use { scenario ->
            robot.waitFor("Alpha message")
            robot.openDrawer(scenario)
            val rows = listOf(R.string.search, R.string.new_chat, R.string.mini_apps, R.string.automations,
                R.string.recent, R.string.access).map { robot.text(it) } + "Beta chat"
            val profile = robot.bounds(robot.text(R.string.profile))
            assertTrue(robot.bounds(robot.text(R.string.automations)).bottom <= robot.bounds("Beta chat").top)
            for (row in rows) assertTrue("$row is above the profile", robot.bounds(row).bottom <= profile.top)
            robot.assertTarget(robot.text(R.string.settings))
            robot.back()
            robot.awaitDrawerClosed(scenario)
            robot.waitFor("Alpha message")
            robot.openDrawer(scenario)
            robot.click("Beta chat")
            robot.awaitDrawerClosed(scenario)
            robot.waitFor("Beta message")
            robot.waitUntil { fixture.store.load().selectedId == "beta" }
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.new_chat))
            robot.awaitDrawerClosed(scenario)
            robot.waitGone("Beta message")
            robot.waitUntil { fixture.store.load().selectedId == null }
        }
    }

    @Test fun entryClosesOpenDrawer() {
        fixture.launch().use { scenario ->
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.settings))
            robot.awaitDrawerClosed(scenario)
            robot.waitUntil { robot.secure(scenario) }
            robot.openDrawer(scenario)
            robot.instrumentation.runOnMainSync { fixture.model!!.openSurface(null) }
            robot.awaitDrawerClosed(scenario)
            robot.waitUntil { !robot.secure(scenario) }
            robot.assertTarget(robot.text(R.string.menu))
        }
    }

    @Test fun drawerKeepsSettingsReachableInLandscape() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message")
        fixture.launch().use { scenario ->
            try {
                robot.openDrawer(scenario)
                robot.rotate(scenario, landscape = true)
                for (id in listOf(R.string.search, R.string.access, R.string.settings)) {
                    robot.waitFor(robot.text(id))
                    robot.assertTarget(robot.text(id))
                }
            } finally { robot.rotate(scenario, landscape = false) }
        }
    }

    @Test fun topBarHasMenuTitleAndNewChat() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        fixture.launch().use {
            robot.waitFor("Alpha message")
            robot.assertTarget(robot.text(R.string.menu))
            robot.assertTarget(robot.text(R.string.new_chat))
            robot.waitFor(robot.text(R.string.app_name))
            robot.click(robot.text(R.string.new_chat))
            robot.waitGone("Alpha message")
            robot.waitUntil { fixture.store.load().selectedId == null }
        }
    }
}
