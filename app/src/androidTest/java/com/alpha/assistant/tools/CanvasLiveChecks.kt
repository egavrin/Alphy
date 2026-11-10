package com.alpha.assistant.tools

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject

internal class CanvasLiveChecks(
    private val robot: ShellRobot,
    private val evidence: JSONObject,
    private val screenshot: (String) -> Unit,
) {
    fun slider(label: String, scene: String, expandsHorizontally: Boolean = false): Float {
        sliderNode(label, reveal = true)
        robot.waitUntil { sliderNode(label).isEnabled }
        val slider = sliderNode(label)
        val drawing = bounds(visible(scene))
        val initial = progress(label)
        val track = bounds(slider)
        check(drawing.top < track.top || drawing.right <= track.left || track.right <= drawing.left)
        val x = track.left + track.width() * (0.06f + 0.88f * fraction(slider))
        val y = track.exactCenterY()
        var widthBefore = 0
        if (expandsHorizontally) {
            robot.waitUntil { horizontalExtent(drawing).also { widthBefore = it } > 0 }
        }
        val before = pixels(drawing)
        screenshot("scene-before")
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, x, y)
        var middle = initial
        try {
            touch(down, MotionEvent.ACTION_MOVE, track.left + track.width() * 0.55f, y)
            robot.waitUntil { progress(label) != initial && pixels(drawing) != before }
            if (expandsHorizontally) {
                robot.waitUntil { horizontalExtent(drawing) > widthBefore }
                evidence.put("drawing_width_px", JSONObject().put("before", widthBefore)
                    .put("during", horizontalExtent(drawing)))
            }
            middle = progress(label)
            screenshot("scene-during-drag")
            touch(down, MotionEvent.ACTION_MOVE, track.left + track.width() * 0.82f, y)
            robot.waitUntil { progress(label) != middle }
        } finally {
            touch(down, MotionEvent.ACTION_UP, track.left + track.width() * 0.82f, y)
        }
        robot.waitUntil {
            fun visible(id: Int) = robot.find(robot.text(id))?.isVisibleToUser == true
            check(!visible(R.string.tool_live_input_unsaved) && !visible(R.string.tool_live_input_unsaved_en)) { "$label was not saved." }
            !visible(R.string.tool_live_input_pending) && !visible(R.string.tool_live_input_pending_en)
        }
        val after = progress(label)
        check(after > initial && after != middle)
        evidence.put("slider", JSONObject().put("before", initial).put("during", middle).put("after", after))
        screenshot("scene-after")
        return after
    }

    fun progress(label: String, reveal: Boolean = false) = checkNotNull(sliderNode(label, reveal).rangeInfo).current

    private fun sliderNode(label: String, reveal: Boolean = false): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                generateSequence(node) { it.parent }.firstOrNull { it.rangeInfo != null && it.isVisibleToUser }?.let { return it }
            }
            repeat(node.childCount) { find(node.getChild(it))?.let { return it } }
            return null
        }
        if (reveal) repeat(4) {
            find(robot.diagnostics.freshRoot())?.let { return it }
            if (!robot.scrollForward()) return@repeat
        }
        var match: AccessibilityNodeInfo? = null
        robot.waitUntil { find(robot.diagnostics.freshRoot()).also { match = it } != null }
        return checkNotNull(match)
    }

    fun game(title: String, initial: Double, score: () -> Double?, reopen: () -> Unit) {
        fun control(id: Int) = robot.text(id)
        fun gameMenu() {
            val open = control(R.string.tool_game_controls_open)
            val close = control(R.string.tool_game_controls_close)
            robot.waitUntil { robot.find(open)?.isVisibleToUser == true || robot.find(close)?.isVisibleToUser == true }
            if (robot.find(close)?.isVisibleToUser != true) robot.click(open)
            robot.waitFor(control(R.string.tool_game_restart))
        }
        fun hideMenu() {
            robot.find(control(R.string.tool_game_controls_close))?.let { robot.click(control(R.string.tool_game_controls_close)) }
        }
        gameMenu()
        robot.click(control(R.string.tool_game_restart))
        robot.waitUntil { score() == initial }
        robot.find(control(R.string.tool_game_start))?.let { robot.click(control(R.string.tool_game_start)) }
        hideMenu()
        val stage = bounds(visible("$title."))
        fun x(f: Float) = stage.left + stage.width() * f
        fun y(f: Float) = stage.top + stage.height() * f
        val before = pixels(stage)
        screenshot("game-before")
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, x(0.5f), y(0.85f))
        try {
            touch(down, MotionEvent.ACTION_MOVE, x(0.65f), y(0.85f))
            robot.waitUntil { pixels(stage) != before }
            screenshot("game-motion")
        } finally {
            touch(down, MotionEvent.ACTION_UP, x(0.65f), y(0.85f))
        }
        val deadline = SystemClock.uptimeMillis() + 25000
        while ((score() ?: initial) <= initial && SystemClock.uptimeMillis() < deadline) {
            val tap = SystemClock.uptimeMillis()
            touch(tap, MotionEvent.ACTION_DOWN, x(0.5f), y(0.85f))
            touch(tap, MotionEvent.ACTION_UP, x(0.5f), y(0.85f))
            SystemClock.sleep(850)
        }
        val hit = checkNotNull(score())
        check(hit > initial) { "No scored brick hit was observed." }
        evidence.put("score_before", initial).put("score_after_hit", hit)
        screenshot("game-brick-hit")
        gameMenu()
        robot.click(control(R.string.tool_game_pause))
        robot.waitFor(control(R.string.tool_game_resume))
        screenshot("game-paused")
        robot.click(control(R.string.tool_game_restart))
        robot.waitUntil { score() == initial }
        screenshot("game-restarted")
        evidence.put("paused", true).put("restarted", true)
        reopen()
        visible("$title.", "score")
        robot.waitFor(control(R.string.tool_game_controls_open))
        robot.automation.waitForIdle(350, 5_000)
        check(score() != null)
        screenshot("game-reopened")
        evidence.put("reopened", true)
    }

    fun captureCompact(label: String, waitForContent: () -> Unit = {}) {
        fun shell(command: String) {
            robot.automation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
        fun width() = checkNotNull(robot.automation.takeScreenshot()).let { shot ->
            try { shot.width } finally { shot.recycle() }
        }
        val unfolded = width()
        shell("wm size 1080x2424")
        try {
            robot.waitUntil { width() < unfolded }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            visible(label)
            waitForContent()
            robot.automation.waitForIdle(350, 5_000)
            evidence.put("display_widths", JSONObject().put("unfolded", unfolded).put("compact", width()))
            screenshot("compact")
        } finally {
            shell("wm size reset")
            robot.waitUntil { width() == unfolded }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        }
    }

    private fun visible(prefix: String, contains: String? = null): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val description = node.contentDescription?.toString()
            if (node.isVisibleToUser && description?.startsWith(prefix, ignoreCase = true) == true &&
                (contains == null || description.contains(contains, ignoreCase = true))) return node
            repeat(node.childCount) { find(node.getChild(it))?.let { match -> return match } }
            return null
        }
        var match: AccessibilityNodeInfo? = null
        robot.waitUntil { find(robot.diagnostics.freshRoot()).also { match = it } != null }
        return checkNotNull(match)
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun fraction(node: AccessibilityNodeInfo): Float = checkNotNull(node.rangeInfo).let {
        (it.current - it.min) / (it.max - it.min)
    }

    private fun pixels(area: Rect): Int {
        val bitmap = checkNotNull(robot.automation.takeScreenshot())
        try {
            val clipped = Rect(area)
            check(clipped.intersect(0, 0, bitmap.width, bitmap.height))
            var hash = 1
            for (y in clipped.top until clipped.bottom step 12) {
                for (x in clipped.left until clipped.right step 12) hash = 31 * hash + bitmap.getPixel(x, y)
            }
            return hash
        } finally {
            bitmap.recycle()
        }
    }

    private fun horizontalExtent(area: Rect): Int {
        val bitmap = checkNotNull(robot.automation.takeScreenshot())
        try {
            check(Rect(0, 0, bitmap.width, bitmap.height).contains(area))
            val background = bitmap.getPixel(area.left, area.top)
            var left = area.right
            var right = area.left
            for (y in area.top until area.bottom step 4) {
                for (x in area.left until area.right step 4) {
                    if (bitmap.getPixel(x, y) != background) {
                        left = minOf(left, x)
                        right = maxOf(right, x)
                    }
                }
            }
            return (right - left).coerceAtLeast(0)
        } finally {
            bitmap.recycle()
        }
    }

    private fun touch(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(robot.automation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }
}
