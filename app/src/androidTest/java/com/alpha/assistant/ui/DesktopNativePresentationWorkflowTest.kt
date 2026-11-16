package com.alpha.assistant.ui

import android.content.res.Resources
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopNativePresentationWorkflowTest {
    private val fixture = ShellFixture("desktop-presentation")
    private val robot = ShellRobot("desktop-presentation")
    private val toolsName = "${fixture.name}.db-tools.db"
    private val tileActions = DesktopTileActions(open = {}, remove = null, moveEarlier = null, moveLater = null)

    @After fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase("$toolsName-desktop.db")
    }

    @Test fun desktopTilesOpenWithoutEmbeddingActionsAtLargeFont() {
        val plain = saveTool("ordinary", "Plain timer", "Add plain")
        val opened = java.util.concurrent.atomic.AtomicReference<String>()
        val originalScale = robot.shell("settings get system font_scale")
        try {
            robot.shell("settings put system font_scale 2.0")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == 2f }
            fixture.install()
            fixture.launch().use { scenario ->
                mountNarrow(scenario) { _ ->
                    DesktopToolTile(plain, editing = false, actions = tileActions.copy(open = { opened.set(plain.id) }))
                }
                val label = robot.text(R.string.desktop_open_tile, ToolSpec.storedTitle(plain.spec))
                robot.assertTarget(label)
                assertNull(robot.find("Add plain"))
                robot.click(label)
                assertEquals(plain.id, opened.get())
                assertEquals(17, count(plain.id))
                robot.diagnostics.capture("desktop-shortcuts-large-font")
            }
        } finally {
            val restore = if (originalScale.toFloatOrNull() == null) "settings delete system font_scale"
                else "settings put system font_scale $originalScale"
            robot.shell(restore)
        }
        assertEquals(0, fixture.provider.calls)
    }

    private fun mountNarrow(scenario: ActivityScenario<CloudTestActivity>, content: @Composable (ShellViewModel) -> Unit) {
        scenario.onActivity { activity ->
            val model = checkNotNull(fixture.model)
            activity.setContent {
                AlphaTheme {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Box(Modifier.width(320.dp)) { content(model) }
                    }
                }
            }
        }
    }

    private fun saveTool(id: String, title: String, action: String): ToolRecord {
        val raw = JSONObject("""{"formatVersion":3,"title":"$title","description":"Desktop presentation fixture","capabilities":[],
            "stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"column","children":[
                {"type":"section","text":"$title","children":[{"type":"metric","text":"Value","bind":"count"},
                    {"type":"button","text":"$action","action":"add"}]}]},
            "source":"if(input.event.actionId==='add') input.state.count++; return {state:input.state};"}""")
        val spec = ToolSpec.parsePersisted(raw.toString())
        val record = ToolRecord(id, 1, 1, spec.json, """{"count":17}""", spec.ui.toString(), null, true, onDesktop = true)
        ToolStore(fixture.context, toolsName).use { it.commitTool(record, null, revision = true) }
        return record
    }

    private fun stored(id: String) = ToolStore(fixture.context, toolsName).use { checkNotNull(it.tool(id)) }

    private fun count(id: String) = JSONObject(stored(id).state).getInt("count")
}
