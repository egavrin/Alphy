package com.alpha.assistant.tools

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Failure case: a generated stopwatch ticks every 100 ms, and its Stop action failed with the generic tool error. */
class TickingAppActionWorkflowTest {
    @Test fun stopWorksWhileTheAppTicks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val artifact = instrumentation.context.assets.open("generation-reliability/stopwatch-ticks.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val fixture = ShellFixture("ticking-app-action")
        val robot = ShellRobot(fixture.name)
        val id = artifact.getString("artifact_id")
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
                val spec = ToolSpec.parsePersisted(artifact.getJSONObject("spec").toString())
                store.saveTool(ToolRecord(id, 1, 1, spec.json, artifact.getString("state"), artifact.getString("ui"),
                    null, true), revision = true)
                fixture.install()
                fixture.launch().use {
                    val model = checkNotNull(fixture.model)
                    robot.waitUntil { !model.loading && !model.tools.busy }
                    instrumentation.runOnMainSync { model.openSurface(id) }
                    robot.waitFor(robot.text(R.string.tool_refine))
                    fun state() = JSONObject(checkNotNull(store.tool(id)).state)
                    repeat(3) { round ->
                        robot.click("Start")
                        robot.waitUntil { state().getBoolean("running") && state().getLong("elapsedMs") > 500 }
                        robot.click("Stop")
                        robot.waitUntil { !state().getBoolean("running") || model.tools.error != null }
                        assertNull("round $round", model.tools.error)
                        assertFalse(state().getBoolean("running"))
                    }
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally {
            fixture.removeWithModel()
        }
    }
}
