package com.alpha.assistant.tools

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.allViews
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Failure cases: a segmented control broke "Dokumen te" in its share of a phone row (L1 item 4), and a long label made its option taller
 * than the others. A segmented control whose labels do not fit its width is cells that wrap, and one whose labels fit stays one row.
 */
class NativeSegmentedWorkflowTest {
    private val robot = ShellRobot("native-segmented")
    private val long = listOf("Daily", "Weekly", "Monthly", "Quarterly", "Every two years")

    private class Option(val text: String, val selected: Boolean, val bounds: Rect)

    @Test fun longLabelsAreCellsThatWrapOnAPhone() = show(long, width = 360.dp) { scenario, events, selectedColor ->
        val options = options(scenario)
        assertEquals(long, options.map { it.text })
        assertEquals(listOf("Daily"), options.filter { it.selected }.map { it.text })
        assertTrue("the cells wrap: ${options.map { it.bounds }}", options.map { it.bounds.top }.distinct().size > 1)
        assertWholeAtNormalSize(scenario, long)
        // The selected cell has the selected container role of the app palette, as a selected segment has, not the fixed blue of a course scene.
        val cell = options.first { it.selected }.bounds
        val pixel = robot.automation.takeScreenshot().getPixel(cell.left.toInt() + 8, cell.center.y.toInt())
        assertNotEquals(Color(0xFFC7DFFF).toArgb(), pixel)
        assertTrue("cell ${Integer.toHexString(pixel)}, role ${Integer.toHexString(selectedColor())}", close(pixel, selectedColor()))
        robot.click("Quarterly")
        robot.waitUntil { options(scenario).singleOrNull { it.selected }?.text == "Quarterly" }
        assertEquals(listOf("repeat" to 3), events.toList())
    }

    @Test fun shortLabelsAreOneSegmentedRow() {
        val labels = listOf("Day", "Week", "Month", "Year")
        show(labels, width = 360.dp) { scenario, _, _ -> assertSegmentedRow(scenario, labels) }
    }

    /** At half the density, the screen is twice as wide in dp, so the five long labels fit a segmented row. */
    @Test fun longLabelsAreOneSegmentedRowWhereTheyFit() = show(long, width = null, density = 0.5f) { scenario, events, _ ->
        assertSegmentedRow(scenario, long)
        robot.click("Quarterly")
        robot.waitUntil { options(scenario).singleOrNull { it.selected }?.text == "Quarterly" }
        assertEquals(listOf("repeat" to 3), events.toList())
    }

    private fun assertSegmentedRow(scenario: ActivityScenario<CloudTestActivity>, labels: List<String>) {
        val options = options(scenario)
        assertEquals(labels, options.map { it.text })
        val bounds = options.map { it.bounds }
        assertEquals("one row of one height: $bounds", 1, bounds.map { it.top to it.height }.distinct().size)
        // The segments touch: their borders overlap, while cells have a gap of 8 dp.
        assertTrue("the segments touch: $bounds", bounds.zipWithNext().all { (left, right) -> right.left - left.right < 1f })
        assertWholeAtNormalSize(scenario, labels)
    }

    private fun assertWholeAtNormalSize(scenario: ActivityScenario<CloudTestActivity>, labels: List<String>) {
        val layouts = labels.map { textLayout(scenario, it) }
        // A centered label of a cell reports the paragraph width of its row as an overflow, so the check compares its one line with its own width.
        val lines = layouts.map { listOf(it.lineCount, it.multiParagraph.getLineWidth(0), it.size.width) }
        assertTrue("lines $lines", layouts.all { it.lineCount == 1 && it.multiParagraph.getLineWidth(0) <= it.size.width + 1 })
        assertTrue("sizes ${layouts.map { it.layoutInput.style.fontSize }}", layouts.all { it.layoutInput.style.fontSize.value >= 14f })
    }

    private fun close(a: Int, b: Int) = listOf(16, 8, 0).all { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) <= 3 }

    private fun options(scenario: ActivityScenario<CloudTestActivity>): List<Option> {
        var options = emptyList<Option>()
        scenario.onActivity { activity ->
            val owner = activity.window.decorView.allViews.filterIsInstance<ViewRootForTest>().single().semanticsOwner
            options = owner.getAllSemanticsNodes(mergingEnabled = true).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }.map {
                Option(it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { text -> text.text },
                    it.config.getOrNull(SemanticsProperties.Selected) == true, it.boundsInWindow)
            }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        }
        return options
    }

    /** Shows a segmented node of [labels] in a column of [width], at [density] times the screen density, with the first option selected. */
    private fun show(labels: List<String>, width: Dp?, density: Float = 1f,
                     check: (ActivityScenario<CloudTestActivity>, List<Pair<String, Any?>>, () -> Int) -> Unit) {
        val fixture = ShellFixture("segmented")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val options = labels.mapIndexed { index, label -> """{"text":"$label","value":$index}""" }.joinToString(",")
                val ui = """{"type":"column","children":[{"type":"segmented","text":"Repeat","bind":"repeat","action":"repeat","options":[$options]}]}"""
                val events = CopyOnWriteArrayList<Pair<String, Any?>>()
                var selectedColor = 0
                var state by mutableStateOf(JSONObject().put("repeat", 0).toString())
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            selectedColor = MaterialTheme.colorScheme.secondaryContainer.toArgb()
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density * density, 1f)) {
                                Column(Modifier.safeDrawingPadding().then(if (width != null) Modifier.width(width) else Modifier)) {
                                    ToolContent(ui, state, enabled = true, appLanguage = "en") { action, value ->
                                        events += action to value
                                        state = JSONObject().put("repeat", value).toString()
                                    }
                                }
                            }
                        }
                    }
                }
                robot.waitFor(labels.last())
                check(scenario, events) { selectedColor }
            }
        } finally { fixture.remove() }
    }
}
