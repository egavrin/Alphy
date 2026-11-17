package com.alpha.assistant.tools

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure case: generated apps had almost no icons. The catalog had 22 system glyphs, and only icon nodes and
 * buttons could show one, so cards, rows, metrics, and badges were text only.
 */
class NativeIconWorkflowTest {
    private val robot = ShellRobot("native-icon")


    private fun render(ui: JSONObject, last: String, state: String = "{}", check: (Bitmap) -> Unit) =
        robot.renderApps("icons", listOf(ui.toString()), state, last, check = check)

    /** The most saturated pixel in a horizontal band left of a text. */
    private fun Bitmap.colorLeftOf(label: String): Float {
        val bounds = robot.bounds(label)
        return (maxOf(0, bounds.left - 160) until bounds.left).maxOf { x ->
            FloatArray(3).also { ColorUtils.colorToHSL(getPixel(x, bounds.centerY()), it) }[1]
        }
    }

    /** The most saturated pixel in a vertical band above a text. */
    private fun Bitmap.colorAbove(label: String): Float {
        val bounds = robot.bounds(label)
        return (maxOf(0, bounds.top - 200) until bounds.top).maxOf { y ->
            FloatArray(3).also { ColorUtils.colorToHSL(getPixel(bounds.centerX(), y), it) }[1]
        }
    }

    @Test fun everyCatalogIconValidatesAndDraws() {
        assertTrue("the catalog offers domain icons", ToolUiCatalog.icons.size >= 100)
        // A container holds at most 50 children, so the icons fill several rows.
        val rows = ToolUiCatalog.icons.sorted().chunked(40).map { names ->
            JSONObject().put("type", "flowRow").put("children", JSONArray(names.map { name ->
                JSONObject().put("type", "icon").put("name", name).put("text", "icon $name")
            }))
        }
        val ui = themedColumn(*rows.toTypedArray(), JSONObject().put("type", "text").put("text", "End of icons"))
        assertEquals(null, validateNativeUi(ui))
        render(ui, "End of icons") { }
    }

    @Test fun cardsRowsMetricsBadgesAndEmptyStatesTakeAnIcon() {
        val card = JSONObject("""{"type":"card","text":"Water today","icon":"Drop","children":[
            {"type":"metric","text":"Glasses","icon":"Cup","bind":"glasses"},
            {"type":"badge","text":"On track","icon":"Check"}]}""")
        val item = JSONObject("""{"type":"listItem","text":"Vitamin D","icon":"Pill","children":[{"type":"text","text":"After breakfast"}]}""")
        val empty = JSONObject("""{"type":"emptyState","text":"No plants yet","icon":"Sprout","children":[{"type":"text","text":"Add the first plant."}]}""")
        val ui = themedColumn(card, item, empty)
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"glasses":"number"}"""), JSONObject("""{"glasses":3}""")))
        assertTrue(validateNativeUi(themedColumn(JSONObject("""{"type":"card","text":"Water","icon":"NoSuchIcon","children":[]}"""))) != null)
        render(ui, "Add the first plant.", """{"glasses":3}""") { shot ->
            for (label in listOf("Water today", "Vitamin D")) assertTrue("$label has a colored icon at its left", shot.colorLeftOf(label) > 0.2f)
            assertTrue("the empty state has a colored icon above its title", shot.colorAbove("No plants yet") > 0.2f)
        }
    }
}
