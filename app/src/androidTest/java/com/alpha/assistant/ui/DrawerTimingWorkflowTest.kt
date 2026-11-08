package com.alpha.assistant.ui

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class DrawerTimingWorkflowTest {
    private val fixture = ShellFixture("drawer-timing")
    private val robot = ShellRobot("drawer-timing")

    @Before fun install() = fixture.install()
    @After fun remove() = fixture.remove()

    @Test fun measureFirstOpenAndTwentyRepeatCycles() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        repeat(30) { index ->
            fixture.seedChat("chat-$index", "Recent conversation $index", "Sample message", updatedAt = index + 2L)
        }
        fixture.launch().use { scenario ->
            robot.waitFor("Alpha message")
            val closeLabel = robot.closeDrawerLabel(scenario)
            val frameThread = HandlerThread("drawer-frame-metrics").apply { start() }
            val frames = mutableListOf<DrawnFrame>()
            var droppedReports = 0
            val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
                droppedReports += dropped
                frames += DrawnFrame(
                    frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                    frame.getMetric(FrameMetrics.TOTAL_DURATION),
                    frame.getMetric(FrameMetrics.DEADLINE),
                )
            }
            val samples = mutableListOf<Transition>()
            try {
                scenario.onActivity {
                    Log.i(TAG, "refreshHz=${it.display?.refreshRate} density=${it.resources.displayMetrics.density}")
                    it.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper))
                }
                try {
                    val menu = robot.text(R.string.menu)
                    for (cycle in 0..20) {
                        samples += measure(scenario, menu, opening = true, cycle)
                        samples += measure(scenario, closeLabel, opening = false, cycle)
                    }
                    robot.awaitFrames(2)
                } finally {
                    scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
                }
            } finally {
                frameThread.quitSafely()
                frameThread.join(5000)
                assertTrue("Frame metrics listener stopped", !frameThread.isAlive)
            }
            assertEquals("Dropped frame metrics reports", 0, droppedReports)
            for (sample in samples) {
                val drawn = frames.filter { it.vsync in sample.dispatched..sample.settled && it.deadline > 0 }
                assertTrue("Rendered frames for ${sample.direction}, cycle ${sample.cycle}", drawn.isNotEmpty())
                Log.i(TAG, "${sample.direction} cycle=${sample.cycle} firstNs=${sample.first - sample.dispatched} " +
                    "slideNs=${sample.settled - sample.first} totalNs=${sample.settled - sample.dispatched} " +
                    "maxFrameGapNs=${sample.longestGap} drawnFrames=${drawn.size} " +
                    "missedDeadlines=${drawn.count { it.duration >= it.deadline }}")
            }
        }
    }

    private fun measure(scenario: ActivityScenario<*>, label: String, opening: Boolean, cycle: Int): Transition {
        val action = generateSequence(robot.waitFor(label)) { it.parent }.first { it.isClickable }
        val dispatchedAt = AtomicLong()
        val settled = CountDownLatch(1)
        var first = 0L
        var last = 0L
        var longestGap = 0L
        lateinit var callback: Choreographer.FrameCallback
        scenario.onActivity { activity ->
            val startRight = robot.drawerRight(activity)
            val choreographer = Choreographer.getInstance()
            callback = Choreographer.FrameCallback { frame ->
                val dispatch = dispatchedAt.get()
                if (dispatch != 0L && frame >= dispatch) {
                    val right = robot.drawerRight(activity)
                    if (first == 0L && abs(right - startRight) >= 1f) first = frame
                    if (last != 0L) longestGap = maxOf(longestGap, frame - last)
                    last = frame
                    val atDestination = if (opening) robot.scrimLabeled(activity) else right == 0f
                    if (first != 0L && atDestination) {
                        settled.countDown()
                    }
                }
                if (settled.count != 0L) choreographer.postFrameCallback(callback)
            }
            choreographer.postFrameCallback(callback)
        }
        try {
            dispatchedAt.set(System.nanoTime())
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue("Drawer did not settle for $label, cycle $cycle", settled.await(5, TimeUnit.SECONDS))
            return Transition(if (opening) "open" else "close", cycle, dispatchedAt.get(), first, last, longestGap)
        } finally {
            scenario.onActivity { Choreographer.getInstance().removeFrameCallback(callback) }
        }
    }

    private data class DrawnFrame(val vsync: Long, val duration: Long, val deadline: Long)

    private data class Transition(val direction: String, val cycle: Int, val dispatched: Long,
        val first: Long, val settled: Long, val longestGap: Long)

    private companion object {
        const val TAG = "AlphyDrawerTiming"
    }
}
