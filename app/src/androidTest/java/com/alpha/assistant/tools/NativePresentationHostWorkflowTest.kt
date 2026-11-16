package com.alpha.assistant.tools

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.ui.AlphaShell
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePresentationHostWorkflowTest {
    private val robot = ShellRobot("native-presentation-host")

    @Test fun switchingDirectlyAfterASavedActionKeepsTheNextAppUsable() = withFixture { fixture, store ->
        val saved = saveTool(store, "saved", "Saved app", recordOpen = true)
        val ordinary = saveTool(store, "ordinary", "Ordinary app")
        fixture.launch().use { scenario ->
            mountWide(scenario) { AlphaShell(checkNotNull(fixture.model)) }
            robot.waitUntil { robot.composed(scenario, "desktop") }
            scenario.onActivity { checkNotNull(fixture.model).openSurface(saved.id) }
            robot.waitUntil {
                val persisted = checkNotNull(store.tool(saved.id))
                val displayed = fixture.model?.tools?.tools?.firstOrNull { it.id == saved.id }
                JSONObject(persisted.state).getBoolean("opened") && displayed?.stateVersion == persisted.stateVersion
            }
            clickEnabledAction("Saved app primary")
            robot.waitUntil { count(store, saved.id) == 1 }

            scenario.onActivity { checkNotNull(fixture.model).openSurface(ordinary.id) }
            assertColumn("Ordinary app")
            clickEnabledAction("Ordinary app primary")
            robot.waitUntil { count(store, ordinary.id) == 1 }
            assertEquals(1, count(store, saved.id))
        }
        assertEquals(0, fixture.provider.calls)
    }

    private fun findAction(label: String): AccessibilityNodeInfo? =
        generateSequence(robot.find(label)) { it.parent }.firstOrNull { it.isClickable }

    private fun clickEnabledAction(label: String) {
        robot.waitUntil { findAction(label)?.let { it.isEnabled && it.offersClick() } == true }
        robot.click(label)
    }

    private fun AccessibilityNodeInfo.offersClick() =
        actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private fun assertColumn(title: String) {
        val first = robot.bounds("$title primary")
        val second = robot.bounds("$title secondary")
        assertTrue("Default controls should stack: $first, $second", first.bottom <= second.top)
        assertEquals(1, robot.texts().count { it == title })
    }

    private fun mountWide(scenario: ActivityScenario<CloudTestActivity>, content: @Composable () -> Unit) {
        scenario.onActivity { activity ->
            val density = activity.window.decorView.width / 800f
            activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    AlphaTheme { Box(Modifier.fillMaxWidth().safeDrawingPadding()) { content() } }
                }
            }
        }
    }

    private fun saveTool(store: ToolStore, id: String, title: String, recordOpen: Boolean = false): ToolRecord {
        val spec = spec(title, recordOpen)
        return ToolRecord(id, 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true).also { store.saveTool(it, true) }
    }

    private fun spec(title: String, recordOpen: Boolean = false): ToolSpec {
        val raw = JSONObject("""{"formatVersion":3,"title":"$title","description":"$title description","capabilities":[],
            "stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"column","children":[
                {"type":"section","text":"$title","children":[{"type":"button","text":"$title primary","action":"increase"}]},
                {"type":"button","text":"$title secondary","action":"reset"}]},
            "source":"if(input.event.actionId==='increase')input.state.count++;if(input.event.actionId==='reset')input.state.count=0;return {state:input.state};"}""")
        if (recordOpen) {
            raw.getJSONObject("stateSchema").put("opened", "boolean")
            raw.getJSONObject("initialState").put("opened", false)
            raw.put("source", "if(input.event.type==='open')input.state.opened=true;${raw.getString("source")}")
        }
        return ToolSpec.parsePersisted(raw.toString())
    }

    private fun state(store: ToolStore, id: String) = JSONObject(checkNotNull(store.tool(id)).state)

    private fun count(store: ToolStore, id: String) = state(store, id).getInt("count")

    private inline fun withFixture(test: (ShellFixture, ToolStore) -> Unit) {
        val fixture = ShellFixture("native-presentation-host")
        val store = ToolStore(fixture.context, "${fixture.store.databaseName}-tools.db")
        robot.diagnostics.state = {
            val controller = fixture.model?.tools
            "selected=${controller?.selectedId} busy=${controller?.busy} errorCode=${controller?.errorCode} hasError=${controller?.error != null}"
        }
        try {
            fixture.install()
            test(fixture, store)
        } finally {
            store.close()
            fixture.remove()
        }
    }
}
