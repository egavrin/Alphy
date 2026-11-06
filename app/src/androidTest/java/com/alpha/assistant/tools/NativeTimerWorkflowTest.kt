package com.alpha.assistant.tools

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTimerWorkflowTest {
    private val robot = ShellRobot("native-timer")

    @Test fun timerContractUsesBoundedEpochMillisecondsAndHasNoActions() {
        val countdown = timer("Разминка", "countdown", "time")
        val stopwatch = timer("Тренировка", "stopwatch", "time")
        fun reading(node: JSONObject, timestamp: Long, now: Long): Long? {
            val parsed = spec(node, timestamp)
            return checkNotNull(ToolUiCatalog.timerBinding(parsed.ui, JSONObject(parsed.initialState))).secondsAt(now)
        }
        val end = 1_800_000_000_000L
        assertNull(reading(countdown, 0, end))
        assertEquals(1L, reading(countdown, end, end - 1))
        assertEquals(2L, reading(countdown, end, end - 1001))
        assertEquals(0L, reading(countdown, end, end + 2_000))
        assertEquals(3L, reading(countdown, end, end - 3_000))
        assertEquals(0L, reading(stopwatch, end, end - 3_000))
        assertEquals(1L, reading(stopwatch, end, end + 1_999))
        assertEquals(4L, reading(stopwatch, end, end + 4_000))
        assertEquals(2L, reading(stopwatch, end, end + 2_000))
        assertEquals(0L, reading(countdown, 253_402_300_799_999L, Long.MAX_VALUE))
        assertTrue(reading(stopwatch, 1L, Long.MAX_VALUE)!! > 0)

        for (invalid in listOf(-1L, 1.5, 253_402_300_800_000L, "1800000000000", true, JSONObject.NULL)) {
            assertTrue("Reject $invalid", runCatching { spec(JSONObject(countdown.toString()).put("visible", false), invalid) }.isFailure)
        }
        for (invalid in listOf(null, "elapsed", 1, JSONObject.NULL)) {
            val node = JSONObject(countdown.toString()).put("mode", invalid)
            assertTrue("Reject mode $invalid", runCatching { spec(node, end) }.isFailure)
        }
        for ((property, value) in listOf("action" to "fire", "interactions" to JSONObject("""{"click":"fire"}"""))) {
            val node = JSONObject(countdown.toString()).put(property, value)
            assertTrue("Reject $property", runCatching { spec(node, end) }.isFailure)
        }
        assertTrue(runCatching { spec(JSONObject(countdown.toString()).put("bind", "missing"), end) }.isFailure)
        val root = spec(countdown, end)
        val state = JSONObject(root.initialState)
        assertTrue(runCatching { ToolUiCatalog.validateAction(root.ui, "fire", null, state) }.isFailure)
        assertNull(ToolUiCatalog.timerBinding(root.ui, JSONObject().put("time", -1)))
        assertEquals(ToolUiCatalog.Projection.SUMMARY, ToolUiCatalog.component("timer").projection)
        assertFalse(ToolUiCatalog.widgetLabel(root.ui, state).contains(end.toString()))
    }

    @Test fun nativeTimerTicksFromSavedStateAndExpiresWithoutRunningItsScript() {
        val fixture = ShellFixture("timer-tile")
        try {
            val ui = JSONObject().put("type", "column").put("children", JSONArray()
                .put(timer("Разминка", "countdown", "time"))
                .put(timer("Тренировка", "stopwatch", "start")))
            val spec = spec(ui, 0)
            val initial = ToolRecord("timer", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true, onDesktop = true)
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
                store.commitTool(initial, null, revision = true)
                fixture.install()
                fixture.launch().use {
                    robot.reveal("Разминка: ${robot.text(R.string.tool_timer_idle)}")
                    val now = System.currentTimeMillis()
                    val armed = initial.copy(stateVersion = 2, state = JSONObject(initial.state)
                        .put("time", now + 4_000).put("start", now).toString())
                    store.commitTool(armed, initial, revision = false)
                    robot.waitUntil { robot.texts().any { it.startsWith("Разминка: Осталось ") } }
                    robot.waitFor("Разминка: ${robot.text(R.string.tool_timer_expired)}")
                    robot.waitUntil { robot.texts().any { it.startsWith("Тренировка: Прошло ") && !it.endsWith("00:00:00") } }
                    assertEquals(armed, store.tool(initial.id))
                    assertNull(fixture.model?.tools?.error)
                    assertEquals(0, fixture.provider.calls)
                    robot.diagnostics.capture("desktop-timer-expired")
                }
            }
        } finally { fixture.remove() }
    }

    @Test fun clippedAndBackgroundTimerCatchUpOnlyWhenVisibleAgain() {
        val fixture = ShellFixture("timer-visibility")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val spec = spec(timer("Тренировка", "stopwatch", "time"), System.currentTimeMillis())
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding().height(200.dp).verticalScroll(rememberScrollState())) {
                                Spacer(Modifier.height(300.dp))
                                ToolContent(spec.ui.toString(), spec.initialState, enabled = true) { _, _ ->
                                    error("The native timer cannot dispatch an action")
                                }
                            }
                        }
                    }
                }
                robot.waitUntil { composedReading(scenario) != null }
                val clipped = checkNotNull(composedReading(scenario))
                assertFalse(robot.find(clipped)?.isVisibleToUser == true)
                awaitSecondBoundary()
                assertEquals(clipped, composedReading(scenario))
                assertTrue(robot.scrollForward())
                robot.waitUntil { composedReading(scenario)?.let { it != clipped && robot.find(it)?.isVisibleToUser == true } == true }

                scenario.moveToState(Lifecycle.State.STARTED)
                val paused = checkNotNull(composedReading(scenario))
                awaitSecondBoundary()
                assertEquals(paused, composedReading(scenario))
                scenario.moveToState(Lifecycle.State.RESUMED)
                robot.waitUntil { composedReading(scenario)?.let { it != paused && robot.find(it)?.isVisibleToUser == true } == true }
            }
        } finally { fixture.remove() }
    }

    private fun awaitSecondBoundary() {
        val deadline = SystemClock.elapsedRealtime() + 1_100
        robot.waitUntil { SystemClock.elapsedRealtime() >= deadline }
    }

    private fun composedReading(scenario: ActivityScenario<CloudTestActivity>): String? {
        fun root(view: View): ViewRootForTest? = view as? ViewRootForTest
            ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
        var reading: String? = null
        scenario.onActivity { activity ->
            reading = root(activity.window.decorView)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = false)
                ?.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                ?.firstOrNull { it.text.startsWith("Тренировка: Прошло ") }?.text
        }
        return reading
    }

    private fun timer(label: String, mode: String, binding: String) =
        JSONObject().put("type", "timer").put("text", label).put("mode", mode).put("bind", binding)

    private fun spec(ui: JSONObject, timestamp: Any): ToolSpec = ToolSpec.parse(JSONObject("""
        {"formatVersion":3,"title":"Таймер занятия","description":"","capabilities":[],
         "stateSchema":{"time":"number","start":"number"},"source":"throw Error('The tile cannot run JavaScript');"}
    """.trimIndent()).put("initialState", JSONObject().put("time", timestamp).put("start", 0)).put("ui", ui).toString())
}
