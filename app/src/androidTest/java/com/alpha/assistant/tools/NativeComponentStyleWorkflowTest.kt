package com.alpha.assistant.tools

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.core.graphics.ColorUtils
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure cases from the mock comparison: weekday choices wrapped onto two lines of gray chips, list rows had no
 * surface of their own, and a range slider showed "20.0 – 80.0".
 */
class NativeComponentStyleWorkflowTest {
    private val robot = ShellRobot("native-component-style")

    private fun app(vararg children: String) =
        """{"type":"theme","preset":"soft","palette":"purple","children":[{"type":"column","style":{"gap":"medium","padding":"medium"},"children":[${children.joinToString(",")}]}]}"""

    private fun render(ui: String, state: String, last: String, check: (Bitmap) -> Unit) =
        robot.renderApps("components", listOf(ui), state, last, check = check)

    private fun dp(value: Int) = (robot.instrumentation.targetContext.resources.displayMetrics.density * value).toInt()


    @Test fun shortWeekdayChoicesShareOneRowOfCircles() {
        val days = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
        val options = days.joinToString(",") { """{"text":"$it","value":"${it.lowercase()}"}""" }
        render(app("""{"type":"multiChoice","text":"Days","bind":"days","action":"days","options":[$options]}"""),
            """{"days":["mo","we"]}""", "Su") { shot ->
            val first = robot.bounds("Mo")
            val last = robot.bounds("Su")
            assertTrue("one row: $first $last", last.top < first.bottom && first.top < last.bottom)
            // Left of the label center, inside a 40 dp circle, whether the bounds are the label or the whole cell.
            val chosen = shot.getPixel(first.centerX() - dp(12), first.centerY())
            val open = shot.getPixel(last.centerX() - dp(12), last.centerY())
            assertTrue("a chosen day is a strong colored circle", colorSaturation(chosen) > 0.3f && ColorUtils.calculateLuminance(chosen) < 0.3)
            assertTrue("a day that is not chosen stays light", ColorUtils.calculateLuminance(open) > 0.6)
        }
    }

    @Test fun templateRowsAreCardsOnThePage() {
        val ui = app("""{"type":"list","text":"Drinks","bind":"rows","rowTemplate":{"type":"row","wrap":false,"children":[
            {"type":"text","text":"","bind":"name","layout":{"weight":1}},{"type":"badge","text":"","bind":"size"}]}}""")
        render(ui, """{"rows":[{"id":"a","name":"Green tea","size":"250 ml"},{"id":"b","name":"Coffee","size":"180 ml"}]}""", "Coffee") { shot ->
            val tea = robot.bounds("Green tea")
            val row = shot.getPixel(tea.left - 12, tea.centerY())
            val page = shot.getPixel(4, tea.centerY())
            assertNotEquals("the row has its own surface", page, row)
            val coffee = robot.bounds("Coffee")
            val gap = shot.getPixel(tea.left - 12, (tea.bottom + coffee.top) / 2)
            assertTrue("rows are separate cards", (tea.bottom until coffee.top).any { y -> shot.getPixel(tea.left - 12, y) == page } || gap == page)
        }
    }

    @Test fun aSceneKeepsMostOfTheScreenAboveTallControls() {
        // Failure case: a 3D scene with a legend and settings below it got only the 160 dp minimum.
        val fixture = ShellFixture("native-component-style")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
                                ToolStageLayout(600.dp, stage = { Box(Modifier.fillMaxWidth().fillMaxHeight().semantics { contentDescription = "Scene" }) },
                                    controls = { Spacer(Modifier.height(700.dp)); Text("Controls") })
                            }
                        }
                    }
                }
                robot.waitFor("Scene")
                assertTrue(robot.bounds("Scene").height() >= dp(600) * 55 / 100)
            }
        } finally { fixture.remove() }
    }

    @Test fun aRangeSliderShowsPlainNumbers() {
        render(app("""{"type":"rangeSlider","text":"Price","bind":"price","action":"price","min":0,"max":100,"step":5}"""),
            """{"price":[20,80]}""", "Price: 20 – 80") { assertEquals(null, robot.texts().firstOrNull { it.contains("20.0") }) }
    }
}
