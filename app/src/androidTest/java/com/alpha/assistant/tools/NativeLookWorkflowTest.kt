package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.graphics.Rect
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Failure case: generated apps looked flat next to the mocks. Every metric value had one size, so the main
 * number did not stand out. Progress bars were thin. The technical preset printed "0 , 00" in a monospace font.
 * Sliders showed no value at the thumb, a divider in a row drew across the row, fields had no icon, steppers
 * broke into two lines in half-width cells, and list rows were dense.
 */
class NativeLookWorkflowTest {
    private val robot = ShellRobot("native-look")
    private val density get() = robot.instrumentation.targetContext.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()


    private fun render(ui: JSONObject, last: String, state: String = "{}", check: (Bitmap) -> Unit) =
        robot.renderApps("look", listOf(ui.toString()), state, last, check = check)

    @Test fun metricValuesFollowTheEmphasisScale() {
        val metric = { label: String, bind: String, emphasis: String? ->
            JSONObject().put("type", "metric").put("text", label).put("bind", bind).apply {
                if (emphasis != null) put("style", JSONObject().put("emphasis", emphasis))
            }
        }
        val ui = themedColumn(metric("Plain", "a", null), metric("Number", "b", "number"), metric("Display", "c", "display"),
            JSONObject("""{"type":"text","text":"End of metrics"}"""))
        val state = """{"a":"111","b":"222","c":"333"}"""
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"a":"string","b":"string","c":"string"}"""), JSONObject(state)))
        render(ui, "End of metrics", state) {
            val heights = listOf("111", "222", "333").map { robot.bounds(it).height() }
            assertTrue("value heights grow with emphasis: $heights", heights[0] < heights[1] && heights[1] < heights[2])
        }
    }

    @Test fun displayTextValidates() {
        assertEquals(null, validateNativeUi(themedColumn(JSONObject("""{"type":"text","text":"Kanji","style":{"emphasis":"display"}}"""))))
    }

    @Test fun aBoundProgressBarIsThick() {
        val ui = themedColumn(JSONObject("""{"type":"progress","text":"Packing progress","bind":"done","min":0,"max":100}"""),
            JSONObject("""{"type":"text","text":"End of progress"}"""))
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"done":"number"}"""), JSONObject("""{"done":40}""")))
        render(ui, "End of progress", """{"done":40}""") { shot ->
            val bar = robot.bounds("Packing progress")
            val x = bar.left + bar.width() / 5
            val background = shot.getPixel(x, bar.top - dp(12))
            val thickness = (bar.top - dp(8) until bar.bottom + dp(8)).count { y -> shot.ink(x, y, background) }
            assertTrue("the filled bar is at least 8 dp thick: $thickness px", thickness >= dp(8))
        }
    }

    @Test fun technicalMoneyUsesProportionalDigits() {
        fun width(preset: String): Int {
            var width = 0
            val ui = themedColumn(JSONObject("""{"type":"metric","text":"Balance","bind":"total"}"""), preset = preset)
            render(ui, "0,00 €", """{"total":"0,00 €"}""") { width = robot.bounds("0,00 €").width() }
            return width
        }
        val technical = width("technical")
        val clean = width("clean")
        assertTrue("technical $technical px and clean $clean px have the same digit style", abs(technical - clean) < clean * 0.15)
    }

    @Test fun aDividerInAFixedRowIsVertical() {
        val ui = themedColumn(JSONObject("""{"type":"row","wrap":false,"children":[
            {"type":"text","text":"Left side"},{"type":"divider"},{"type":"text","text":"Right side"}]}"""),
            JSONObject("""{"type":"text","text":"End of row"}"""))
        assertEquals(null, validateNativeUi(ui))
        render(ui, "End of row") { shot ->
            val left = robot.bounds("Left side")
            val right = robot.bounds("Right side")
            assertEquals(left.centerY().toFloat(), right.centerY().toFloat(), dp(2).toFloat())
            assertTrue("the divider takes little width: ${right.left - left.right} px", right.left - left.right in 1 until dp(40))
            val background = shot.getPixel(left.left - dp(4), left.top)
            assertTrue("a line stands between the texts", (left.right until right.left).any { x -> shot.ink(x, left.top + dp(2), background) })
        }
    }

    /** Review finding: a divider asked the facts beside it for intrinsic sizes, which they cannot give, and the app crashed. */
    @Test fun aDividerBesideFactsKeepsTheRow() {
        val ui = themedColumn(JSONObject("""{"type":"row","wrap":false,"children":[
            {"type":"fact","text":"Distance","bind":"a"},{"type":"divider"},{"type":"fact","text":"Time","bind":"b"}]}"""),
            JSONObject("""{"type":"text","text":"End of facts"}"""))
        val state = """{"a":"5 km","b":"30 min"}"""
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"a":"string","b":"string"}"""), JSONObject(state)))
        render(ui, "End of facts", state) { shot ->
            val left = maxOf(robot.bounds("Distance").right, robot.bounds("5 km").right)
            val right = minOf(robot.bounds("Time").left, robot.bounds("30 min").left)
            val y = robot.bounds("Distance").bottom
            val background = shot.getPixel(robot.bounds("Distance").left - dp(4), y)
            assertTrue("a line stands between the facts", (left until right).any { x -> shot.ink(x, y, background) })
        }
    }

    @Test fun inputFieldsTakeALeadingIcon() {
        val ui = themedColumn(JSONObject("""{"type":"input","text":"Plain item","bind":"plain","action":"setPlain"}"""),
            JSONObject("""{"type":"input","text":"Icon item","icon":"Search","bind":"found","action":"setFound"}"""),
            JSONObject("""{"type":"text","text":"End of fields"}"""))
        val state = """{"plain":"milk","found":"milk"}"""
        val fields = JSONObject("""{"plain":"string","found":"string"}""")
        assertEquals(null, validateNativeUi(ui, fields, JSONObject(state)))
        render(ui, "End of fields", state) { shot ->
            fun rightmostInk(label: String): Int {
                val field = Rect().also(robot.field(label)::getBoundsInScreen)
                val background = shot.getPixel(field.right - dp(12), field.centerY())
                return (field.left + dp(4) until field.right - dp(12)).last { x -> shot.ink(x, field.centerY(), background) } - field.left
            }
            val plain = rightmostInk("Plain item")
            val icon = rightmostInk("Icon item")
            assertTrue("the icon moves the value right: plain $plain px, icon $icon px", icon - plain >= dp(20))
        }
    }

    // Failure case: a slider said its number twice, in its label and in a pill over the thumb. The picture shows a slider at 0, 50, and 100.
    @Test fun aSliderShowsItsValueOnceAndARangeSliderShowsValuesAtItsThumbs() {
        val slider = { label: String -> JSONObject().put("type", "slider").put("text", label).put("bind", label.lowercase()).put("min", 0)
            .put("max", 100).put("action", "set$label") }
        val ui = themedColumn(slider("Low"), slider("Middle"), slider("High"),
            JSONObject("""{"type":"rangeSlider","text":"Price","bind":"price","min":0,"max":5000,"action":"setPrice"}"""),
            JSONObject("""{"type":"text","text":"End of sliders"}"""))
        val state = """{"low":0,"middle":50,"high":100,"price":[400,3000]}"""
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"low":"number","middle":"number","high":"number",""" +
            """"price":{"type":"array","maxItems":2,"items":"number"}}"""), JSONObject(state)))
        render(ui, "End of sliders", state) {
            val texts = robot.texts()
            for (label in listOf("Low: 0", "Middle: 50", "High: 100")) assertTrue("$label in $texts", label in texts)
            for (pill in listOf("0", "50", "100")) assertTrue("no pill $pill in $texts", pill !in texts)
            fun near(label: String, track: Rect, fraction: Double) {
                val value = robot.bounds(label)
                val thumb = track.left + dp(10) + ((track.width() - dp(20)) * fraction).toInt()
                assertTrue("$label sits at its thumb: ${value.centerX()} near $thumb", abs(value.centerX() - thumb) <= dp(24))
                assertTrue("$label sits above or on the track", value.bottom <= track.centerY())
            }
            near("400", robot.bounds("Price"), 0.08)
            near("3000", robot.bounds("Price"), 0.6)
        }
    }

    // Failure case: a slider with no step said «Громкость: 69.9999988079071» after a share of 0.7 of its range.
    @Test fun aSliderPrintsItsNumberWithTheDecimalsOfItsStep() {
        val slider = { label: String, min: Double, max: Double, step: Double? -> JSONObject().put("type", "slider").put("text", label)
            .put("bind", label.lowercase()).put("min", min).put("max", max).put("action", "set$label").apply { step?.let { put("step", it) } } }
        // No step: at most 2 decimals. A step of 0.1: 1 decimal. A step of 1 from 0.5: the decimal of the bounds. A step of 5: none.
        val ui = themedColumn(slider("Volume", 0.0, 100.0, null), slider("Weight", 0.0, 10.0, null), slider("Share", 0.0, 1.0, 0.1),
            slider("Offset", 0.5, 10.5, 1.0), slider("Guests", 0.0, 50.0, 5.0),
            JSONObject("""{"type":"rangeSlider","text":"Span","bind":"span","min":0,"max":100,"action":"setSpan"}"""),
            JSONObject("""{"type":"text","text":"End of sliders"}"""))
        val state = """{"volume":69.9999988079071,"weight":2.5,"share":0.30000000000000004,"offset":2.5,"guests":19.999999,""" +
            """"span":[12.345678,40.0000001]}"""
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"volume":"number","weight":"number","share":"number","offset":"number",""" +
            """"guests":"number","span":{"type":"array","maxItems":2,"items":"number"}}"""), JSONObject(state)))
        render(ui, "End of sliders", state) {
            val texts = robot.texts()
            for (label in listOf("Volume: 70", "Weight: 2.5", "Share: 0.3", "Offset: 2.5", "Guests: 20", "Span: 12.35 – 40", "12.35", "40"))
                assertTrue("$label in $texts", label in texts)
        }
    }

    @Test fun aSteppedNumberInputIsOneRowInAHalfWidthCell() {
        val stepper = JSONObject("""{"type":"numberInput","text":"Guests","bind":"guests","min":1,"max":20,"step":1,"action":"setGuests"}""")
        val ui = themedColumn(JSONObject().put("type", "grid").put("columns", 2).put("children", JSONArray().put(stepper)
            .put(JSONObject("""{"type":"text","text":"Other cell"}"""))), JSONObject("""{"type":"text","text":"End of grid"}"""))
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"guests":"number"}"""), JSONObject("""{"guests":4}""")))
        render(ui, "End of grid", """{"guests":4}""") {
            val field = Rect().also(robot.field("Guests")::getBoundsInScreen)
            for (button in listOf(R.string.tool_number_decrease_en, R.string.tool_number_increase_en)) {
                val bounds = robot.bounds(robot.text(button, "Guests"))
                assertTrue("the step button is on the field line", bounds.centerY() in field.top until field.bottom)
            }
        }
    }

    @Test fun templateRowsAreAtLeast56dpTall() {
        val ui = themedColumn(JSONObject("""{"type":"list","text":"Rows","bind":"rows","rowTemplate":{"type":"text","text":"","bind":"name"}}"""),
            JSONObject("""{"type":"text","text":"End of list"}"""))
        val state = """{"rows":[{"id":"1","name":"First row"},{"id":"2","name":"Second row"}]}"""
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"rows":{"type":"list","maxItems":10,"fields":{"id":"string","name":"string"}}}"""), JSONObject(state)))
        render(ui, "End of list", state) {
            val step = robot.bounds("Second row").centerY() - robot.bounds("First row").centerY()
            assertTrue("rows are at least 56 dp apart: $step px", step >= dp(56))
        }
    }
}
