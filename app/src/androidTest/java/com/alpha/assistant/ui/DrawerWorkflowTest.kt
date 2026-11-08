package com.alpha.assistant.ui

import android.view.Choreographer
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.compositionContext
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            val historyLabel = robot.text(R.string.chat_history)
            val history = robot.waitFor(historyLabel)
            assertTrue("History is an accessibility heading", history.isHeading)
            assertTrue("The history heading has no visible text", history.text.isNullOrEmpty())
            assertEquals("The drawer exposes one history heading", 1, robot.texts().count { it == historyLabel })
            val currentChatNodes = generateSequence(robot.waitFor("Alpha chat")) { it.parent }
            assertTrue("The current chat row exposes selection", currentChatNodes.any { it.isSelected })
            val rows = listOf(R.string.search, R.string.mini_apps, R.string.automations, R.string.course_learning)
                .map { robot.text(it) } + listOf(historyLabel, "Beta chat")
            for ((upper, lower) in rows.zipWithNext()) assertTrue("$upper ends above $lower", robot.bounds(upper).bottom <= robot.bounds(lower).top)
            robot.assertTarget(robot.text(R.string.search))
            val newChat = robot.assertTarget(robot.text(R.string.new_chat))
            val settings = robot.assertTarget(robot.text(R.string.settings))
            assertTrue("Footer sits below history", robot.bounds("Beta chat").bottom <= newChat.top)
            assertEquals("Both footer actions share a row", newChat.top, settings.top)
            assertTrue("New Chat is left of Settings", newChat.right <= settings.left)
            robot.back()
            robot.awaitDrawerClosed(scenario)
            robot.waitFor("Alpha message")
            robot.openDrawer(scenario)
            robot.click("Beta chat")
            robot.awaitDrawerClosed(scenario)
            robot.waitFor("Beta message")
            robot.waitUntil { fixture.model?.snapshot?.selectedId == "beta" }
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.new_chat))
            robot.awaitDrawerClosed(scenario)
            robot.waitGone("Beta message")
            robot.waitUntil { fixture.model?.snapshot?.selectedId == null }
        }
    }

    @Test fun footerStaysReachableWhileHistoryScrolls() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        repeat(30) { index ->
            fixture.seedChat("chat-$index", "Recent conversation $index", "Sample message", updatedAt = index + 2L)
        }
        fixture.launch().use { scenario ->
            robot.waitFor("Alpha message")
            robot.openDrawer(scenario)
            val actions = listOf(R.string.search, R.string.new_chat, R.string.settings).map { robot.text(it) }
            val before = actions.map(robot::assertTarget)
            assertTrue("The drawer scrolls through recent chats", robot.scrollForward())
            assertEquals("Header and footer stay visible and in place", before, actions.map(robot::assertTarget))

            robot.click(robot.closeDrawerLabel(scenario))
            robot.awaitDrawerClosed(scenario)
            robot.waitFor("Alpha message")
            assertEquals("alpha", selectedId(scenario))
        }
    }

    @Test fun entryClosesOpenDrawer() {
        fixture.launch().use { scenario ->
            robot.openDrawer(scenario)
            assertFalse("An empty drawer has no history heading", robot.find(robot.text(R.string.chat_history))?.isVisibleToUser == true)
            robot.click(robot.text(R.string.settings))
            robot.awaitDrawerClosed(scenario)
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
            robot.openDrawer(scenario)
            assertTrue(robot.composed(scenario, "settings-pane"))
            robot.back()
            robot.awaitDrawerClosed(scenario)
            assertTrue(robot.composed(scenario, "settings-pane"))
            robot.openDrawer(scenario)
            assertTrue(robot.composed(scenario, "settings-pane"))
            robot.instrumentation.runOnMainSync { fixture.model!!.openSurface(null) }
            robot.awaitDrawerClosed(scenario)
            robot.waitUntil { !robot.composed(scenario, "settings-pane") }
            robot.assertTarget(robot.text(R.string.menu))
        }
    }

    @Test fun drawerKeepsSettingsReachableInLandscape() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message")
        fixture.launch().use { scenario ->
            try {
                robot.openDrawer(scenario)
                robot.rotate(scenario, landscape = true)
                for (id in listOf(R.string.search, R.string.mini_apps, R.string.settings)) {
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
            assertTrue("The top bar title is a heading", robot.waitFor(robot.text(R.string.app_name)).isHeading)
            robot.click(robot.text(R.string.new_chat))
            robot.waitGone("Alpha message")
            robot.waitUntil { fixture.model?.snapshot?.selectedId == null }
            robot.openDrawer(scenario)
            robot.back()
            robot.awaitDrawerClosed(scenario)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test fun backDuringOpenAnimationClosesDrawer() {
        val previousScale = robot.shell("settings get global animator_duration_scale")
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        fixture.launch().use { scenario ->
            robot.waitFor("Alpha message")
            robot.openDrawer(scenario)
            var settledRight = 0f
            scenario.onActivity { settledRight = robot.drawerRight(it) }
            assertTrue("Open drawer has a visible row", settledRight > 0)
            robot.back()
            robot.awaitDrawerClosed(scenario)

            fun awaitComposeScale(scale: Float) {
                robot.waitUntil {
                    var current: Float? = null
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        current = (root.compositionContext as? Recomposer)?.effectCoroutineContext
                            ?.get(MotionDurationScale)?.scaleFactor
                    }
                    current == scale
                }
            }
            try {
                // The Compose observer can retain the old scale while no window is subscribed.
                robot.shell("settings put global animator_duration_scale 0")
                awaitComposeScale(0f)
                robot.openDrawer(scenario)
                robot.back()
                robot.awaitDrawerClosed(scenario)
                robot.shell("settings put global animator_duration_scale 1")
                awaitComposeScale(1f)
                robot.withBackInput(scenario) { input ->
                    var menu: AccessibilityNodeInfo? = robot.waitFor(robot.text(R.string.menu))
                    while (menu != null && !menu.isClickable) menu = menu.parent
                    val backSent = CountDownLatch(1)
                    lateinit var frame: Choreographer.FrameCallback
                    scenario.onActivity { activity ->
                        frame = Choreographer.FrameCallback {
                            val right = robot.drawerRight(activity)
                            if (right > 0 && right < settledRight) {
                                input.complete()
                                backSent.countDown()
                            } else Choreographer.getInstance().postFrameCallback(frame)
                        }
                        Choreographer.getInstance().postFrameCallback(frame)
                    }
                    try {
                        assertTrue(menu!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        assertTrue("The drawer moved before Back", backSent.await(10, TimeUnit.SECONDS))
                    } finally {
                        scenario.onActivity { Choreographer.getInstance().removeFrameCallback(frame) }
                    }
                    var open = false
                    robot.waitUntil {
                        var settled = false
                        scenario.onActivity { open = robot.scrimLabeled(it); settled = open || robot.drawerRight(it) == 0f }
                        settled
                    }
                    assertFalse("Back during the open animation closes the drawer", open)
                    assertTrue(robot.composed(scenario, "chat"))
                    assertEquals("alpha", selectedId(scenario))
                }
            } finally {
                if (previousScale == "null") robot.shell("settings delete global animator_duration_scale")
                else robot.shell("settings put global animator_duration_scale $previousScale")
                awaitComposeScale(previousScale.toFloatOrNull() ?: 1f)
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
