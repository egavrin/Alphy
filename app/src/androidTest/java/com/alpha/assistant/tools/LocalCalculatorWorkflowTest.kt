package com.alpha.assistant.tools

import android.view.accessibility.AccessibilityNodeInfo
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalCalculatorWorkflowTest {
    private val robot = ShellRobot("local-calculator")

    @Test fun rapidKeypadInputStaysLocalAndKeepsEveryTap() {
        val fixture = ShellFixture("local-calculator")
        val name = "${fixture.name}.db-tools.db"
        val spec = ToolSpec.parse("""
            {"formatVersion":3,"title":"Local keypad","description":"","capabilities":[],
            "stateSchema":{"display":"string","left":"number","entering":"boolean"},
            "initialState":{"display":"","left":0,"entering":true},
            "ui":{"type":"column","children":[
                {"type":"text","text":"Result","bind":"display"},
                {"type":"grid","columns":4,"children":[
                    {"type":"button","text":"3","action":"three"},{"type":"button","text":"0","action":"zero"},
                    {"type":"button","text":"+","action":"plus"},{"type":"button","text":"=","action":"equals"}]}]},
            "source":"const s=input.state; switch(input.event.actionId){case 'three': s.display+='3'; break; case 'zero': s.display+='0'; break; case 'plus': s.left=Number(s.display); s.display=''; s.entering=false; break; case 'equals': if(!s.entering){s.display=String(s.left+Number(s.display)); s.entering=true;} break;} return {state:s};"}
        """.trimIndent())
        fixture.install()
        try {
            ToolStore(fixture.context, name).use {
                it.saveTool(ToolRecord("keypad", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true), true)
            }
            fixture.launch().use { scenario ->
                scenario.onActivity { checkNotNull(fixture.model).openSurface("keypad") }
                val options = robot.text(R.string.tool_options)
                robot.waitFor(options)
                robot.waitUntil { fixture.model?.tools?.busy == false }
                assertEquals(1, listOf("3", "0", "+", "=").map { robot.bounds(it).centerY() }.distinct().size)
                val keys = listOf("3", "0", "+", "3", "=")
                for (label in keys) {
                    val key = generateSequence(robot.waitFor(label)) { it.parent }.first { it.isClickable }
                    assertTrue("Keypad key stays enabled", key.isEnabled)
                    assertTrue("Keypad accepts $label", key.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    assertNull(robot.find(robot.text(R.string.tool_working)))
                }
                robot.waitFor("Result: 33")
                scenario.recreate()
                robot.waitFor("Result: 33")
                ToolStore(fixture.context, name).use {
                    assertEquals("33", JSONObject(checkNotNull(it.tool("keypad")).state).getString("display"))
                }
                assertEquals(0, fixture.provider.calls)
                robot.diagnostics.capture("result-reopened")
            }
        } finally { fixture.remove() }
    }
}
