package com.alpha.assistant.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.navigationevent.NavigationEvent
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.roundToInt

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
            val rows = listOf(R.string.search, R.string.new_chat, R.string.mini_apps, R.string.automations, R.string.recent)
                .map { robot.text(it) } + "Beta chat" + robot.text(R.string.access) + robot.text(R.string.profile)
            for ((upper, lower) in rows.zipWithNext()) assertTrue("$upper ends above $lower", robot.bounds(upper).bottom <= robot.bounds(lower).top)
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
            assertTrue(robot.secure(scenario))
            robot.back()
            robot.awaitDrawerClosed(scenario)
            assertTrue(robot.secure(scenario))
            robot.openDrawer(scenario)
            assertTrue(robot.secure(scenario))
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

    // Failure case: a swipe opens the drawer, and the keyboard stays open over it.
    @Test fun swipeOpenDrawerHidesKeyboard() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        fixture.launch().use { scenario ->
            robot.waitFor("Alpha message")
            assertTrue(robot.field(robot.text(R.string.composer_label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            robot.waitUntil { robot.keyboardTop(scenario) != null }
            // The swipe starts 60 dp from the left edge, outside the system Back gesture area, as probe B did.
            val dp = robot.instrumentation.targetContext.resources.displayMetrics.density
            robot.shell("input swipe ${(60 * dp).roundToInt()} ${(173 * dp).roundToInt()} ${(260 * dp).roundToInt()} ${(173 * dp).roundToInt()} 300")
            robot.awaitDrawerOpen(scenario)
            robot.waitUntil { robot.keyboardTop(scenario) == null }
            robot.back()
            robot.awaitDrawerClosed(scenario)
            robot.waitFor("Alpha message")
            assertTrue(robot.composed(scenario, "chat"))
            assertEquals("alpha", selectedId(scenario))
        }
    }

    @Test fun topBarHasMenuTitleAndNewChat() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        fixture.launch().use { scenario ->
            robot.waitFor("Alpha message")
            robot.assertTarget(robot.text(R.string.menu))
            robot.assertTarget(robot.text(R.string.new_chat))
            robot.waitFor(robot.text(R.string.app_name))
            robot.click(robot.text(R.string.new_chat))
            robot.waitGone("Alpha message")
            robot.waitUntil { fixture.store.load().selectedId == null }
            robot.openDrawer(scenario)
            robot.back()
            robot.awaitDrawerClosed(scenario)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test fun backDuringOpenAnimationClosesDrawer() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        fixture.launch().use { scenario ->
            robot.waitFor("Alpha message")
            robot.withBackInput(scenario) { input ->
                var menu: AccessibilityNodeInfo? = robot.waitFor(robot.text(R.string.menu))
                while (menu != null && !menu.isClickable) menu = menu.parent
                // ShellRobot.click waits for an idle UI, and the drawer can finish its open animation in that wait.
                assertTrue(menu!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                var sent = false
                try {
                    // The check and the Back share one main-thread block, so the drawer cannot settle between them.
                    robot.waitUntil {
                        scenario.onActivity { if (!sent && robot.drawerRowShown(it) && !robot.scrimLabeled(it)) { input.complete(); sent = true } }
                        sent
                    }
                } catch (timeout: AssertionError) { throw AssertionError("The test did not see the drawer move within the wait timeout", timeout) }
                var open = false
                robot.waitUntil {
                    var settled = false
                    scenario.onActivity { open = robot.scrimLabeled(it); settled = open || !robot.drawerRowShown(it) }
                    settled
                }
                assertFalse("Back during the open animation closes the drawer", open)
                assertTrue(robot.composed(scenario, "chat"))
                assertEquals("alpha", selectedId(scenario))
            }
        }
    }

    @Test fun backGestureOverOpenDrawerKeepsChat() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        fixture.launch().use { scenario ->
            robot.waitFor("Alpha message")
            robot.withBackInput(scenario) { input ->
                robot.openDrawer(scenario)
                val before = robot.bounds(robot.text(R.string.search))
                scenario.onActivity {
                    input.start(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0f, touchX = 0f, touchY = 320f))
                    input.progress(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0.5f, touchX = 160f, touchY = 320f))
                }
                robot.awaitFrames(10)
                // Data only: the drawer can show its own predictive Back animation. The test does not depend on it.
                println("DRAWER_GESTURE search row before=$before during=${robot.bounds(robot.text(R.string.search))}")
                assertFalse("The gesture over the open drawer starts no pop transition", robot.composed(scenario, "desktop"))
                scenario.onActivity { input.complete() }
                robot.awaitDrawerClosed(scenario)
                assertTrue(robot.composed(scenario, "chat"))
                assertEquals("alpha", selectedId(scenario))
            }
        }
    }

    private fun selectedId(scenario: ActivityScenario<*>): String? {
        var id: String? = null
        scenario.onActivity { id = fixture.model!!.snapshot.selectedId }
        return id
    }
}
