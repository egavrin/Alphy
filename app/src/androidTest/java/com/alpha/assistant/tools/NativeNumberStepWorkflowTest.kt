package com.alpha.assistant.tools

import android.graphics.Rect
import android.os.Bundle
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

class NativeNumberStepWorkflowTest {
    private val robot = ShellRobot("native-number-step")
    private val field = "Доза"
    private val increase get() = robot.text(R.string.tool_number_increase, field)
    private val decrease get() = robot.text(R.string.tool_number_decrease, field)
    private val apply get() = robot.text(R.string.tool_generated_apply, field)

    @Test fun stepIsOptInPositiveFiniteAndLeavesManualPayloadValidationIntact() {
        val standard = node().apply { remove("step") }
        for (ui in listOf(standard, node())) {
            val parsed = spec(ui)
            val state = JSONObject(parsed.initialState)
            ToolUiCatalog.validateAction(ui, "edit", 0.27, state)
            for (invalid in listOf("0.27", -0.16, 0.39, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertTrue("Reject $invalid", runCatching { ToolUiCatalog.validateAction(ui, "edit", invalid, state) }.isFailure)
            }
        }
        assertNull(nativeNumberStep(standard, 0.2, increase = true))
        for (invalid in listOf(0, -1, 1, "0.1", true, JSONObject.NULL)) {
            assertTrue("Reject step $invalid", runCatching { spec(node().put("step", invalid)) }.isFailure)
        }
        for (literal in listOf("1e309", "-1e309")) {
            val raw = definition(node()).toString().replace("\"step\":0.1", "\"step\":$literal")
            assertTrue("Reject step $literal", runCatching { ToolSpec.parse(raw) }.isFailure)
        }
        assertTrue(runCatching { spec(node().put("stepBy", 2)) }.isFailure)
        assertTrue(runCatching { spec(node().put("type", "input")) }.isFailure)
    }

    @Test fun stepArithmeticClampsAndDoesNotAccumulateFloatingPointDrift() {
        val decimals = node()
        assertEquals(0.3, nativeNumberStep(decimals, 0.2, increase = true))
        assertEquals(0.38, nativeNumberStep(decimals, 0.3, increase = true))
        assertEquals(-0.15, nativeNumberStep(decimals, -0.14, increase = false))
        assertNull(nativeNumberStep(decimals, 0.38, increase = true))
        assertNull(nativeNumberStep(decimals, -0.15, increase = false))
        assertNull(nativeNumberStep(decimals, Double.NaN, increase = true))
        assertNull(nativeNumberStep(decimals, 50.0, increase = false))
        val large = node().put("min", 9_007_199_254_740_980L).put("max", 9_007_199_254_740_991L)
        assertNull(nativeNumberStep(large, 9_007_199_254_740_985.0, increase = true))
        val integer = node().put("min", 1).put("max", 9).put("step", 3)
        assertEquals(7.0, nativeNumberStep(integer, 4.0, increase = true))
        assertEquals(9.0, nativeNumberStep(integer, 7.0, increase = true))
        assertEquals(1.0, nativeNumberStep(integer, 4.0, increase = false))
    }

    @Test fun narrowLargeTextStepperOnlyCommitsOnApplyAndClampsBothEnds() = withSaved(node(), enabled = true, fontScale = 2f) { store, saved ->
        robot.assertTarget(increase)
        robot.assertTarget(decrease)
        robot.click(increase)
        awaitDraft("0.3")
        robot.click(increase)
        awaitDraft("0.38")
        assertDisabled(increase)
        assertEquals(saved, store.tool(saved.id))
        robot.click(decrease)
        awaitDraft("0.28")
        assertEquals(saved, store.tool(saved.id))
        robot.click(apply)
        robot.waitUntil { store.tool(saved.id)?.let { JSONObject(it.state).getDouble("amount") == 0.28 } == true }
        val committed = checkNotNull(store.tool(saved.id))
        assertEquals(saved.stateVersion + 1, committed.stateVersion)
        assertDisabled(apply)

        robot.edit(field, "99")
        assertDisabled(increase)
        assertDisabled(decrease)
        assertDisabled(apply)
        robot.edit(field, "-0.14")
        robot.click(decrease)
        awaitDraft("-0.15")
        assertDisabled(decrease)
        assertEquals(committed, store.tool(saved.id))
        robot.diagnostics.capture("number-step-narrow-large-text")
    }

    @Test fun disabledPreviewCannotStepAndOrdinaryFieldKeepsApply() {
        withSaved(node(), enabled = false) { store, saved ->
            for (label in listOf(increase, decrease, apply)) assertDisabled(label)
            assertEquals(saved, store.tool(saved.id))
        }
        withSaved(node().apply { remove("step") }, enabled = true) { store, saved ->
            robot.waitFor(field)
            assertNull(robot.find(increase))
            assertNull(robot.find(decrease))
            robot.edit(field, "0.27")
            assertEquals(saved, store.tool(saved.id))
            robot.click(apply)
            robot.waitUntil { store.tool(saved.id)?.let { JSONObject(it.state).getDouble("amount") == 0.27 } == true }
        }
    }

    @Test fun widthChangeKeepsTheFocusedNumberDraft() {
        val width = mutableStateOf(260.dp)
        val service = robot.automation.serviceInfo
        val originalFlags = service.flags
        try {
            service.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            robot.automation.serviceInfo = service
            withSaved(node(), enabled = true, width = { width.value }) { store, saved ->
                val bounds = Rect().also(robot.field(field)::getBoundsInScreen)
                robot.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                robot.edit(field, "0.27")
                val selection = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 1)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 3)
                }
                assertTrue(robot.field(field).performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection))
                fun keyboardVisible() = robot.automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                robot.waitUntil { keyboardVisible() && robot.field(field).isFocused }
                for (target in listOf(300.dp, 260.dp)) {
                    robot.instrumentation.runOnMainSync { width.value = target }
                    robot.waitUntil {
                        val fieldBounds = Rect().also(robot.field(field)::getBoundsInScreen)
                        (robot.bounds(increase).centerY() in fieldBounds.top until fieldBounds.bottom) == (target == 300.dp)
                    }
                    val input = robot.field(field)
                    assertTrue(input.isFocused)
                    assertTrue(keyboardVisible())
                    assertEquals("0.27", input.text.toString())
                    assertEquals(1, input.textSelectionStart)
                    assertEquals(3, input.textSelectionEnd)
                    assertEquals(saved, store.tool(saved.id))
                }
                robot.diagnostics.capture("number-step-after-resize")
            }
        } finally {
            service.flags = originalFlags
            robot.automation.serviceInfo = service
        }
    }

    private fun awaitDraft(value: String) = robot.waitUntil { robot.field(field).text.toString() == value }

    private fun assertDisabled(label: String) {
        robot.diagnostics.target(label)
        robot.waitUntil {
            generateSequence(robot.find(label)) { it.parent }.firstOrNull { it.isClickable }?.let { control ->
                control.isVisibleToUser && !control.isEnabled && control.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            } == true
        }
    }

    private fun withSaved(ui: JSONObject, enabled: Boolean, fontScale: Float = 1f, width: () -> Dp = { 280.dp },
        block: (ToolStore, ToolRecord) -> Unit) {
        val fixture = ShellFixture("number-step")
        val spec = spec(ui)
        val saved = ToolRecord("number", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
                store.commitTool(saved, null, revision = true)
                fixture.install()
                fixture.launch().use { scenario ->
                    scenario.onActivity { activity ->
                        val model = checkNotNull(fixture.model)
                        activity.setContent {
                            val base = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                                AlphaTheme {
                                    Column(Modifier.safeDrawingPadding().widthIn(max = width()).verticalScroll(rememberScrollState())) {
                                        if (enabled) model.tools.tools.firstOrNull { it.id == saved.id }?.let { record ->
                                            MiniAppContent(model.tools, record, record, spec.title, enabled = true)
                                        } else ToolContent(saved.ui, saved.state, enabled = false) { _, _ ->
                                            error("A disabled preview cannot dispatch a number input")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    robot.waitFor(field)
                    block(store, saved)
                }
            }
        } finally { fixture.remove() }
    }

    private fun node() = JSONObject("""{"type":"numberInput","text":"$field","bind":"amount","action":"edit","min":-0.15,"max":0.38,"step":0.1}""")

    private fun spec(ui: JSONObject) = ToolSpec.parse(definition(ui).toString())

    private fun definition(ui: JSONObject) = JSONObject().put("formatVersion", 3).put("title", "Число").put("description", "")
        .put("capabilities", JSONArray()).put("stateSchema", JSONObject().put("amount", "number"))
        .put("initialState", JSONObject().put("amount", 0.2)).put("ui", ui)
        .put("source", "if(input.event.type==='action') input.state.amount=input.event.value; return {state:input.state};")
}
