package com.alpha.assistant.tools

import android.graphics.Rect
import com.alpha.assistant.tools.agent.AppScreen
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Failure case: the mocks lead with one large element, such as a ring with the time inside, a water tank,
 * step dots, a tinted hero card, or a big emoji. The catalog had only a 56 dp ring with its label above,
 * a thin bar, and plain cards, so a workout agent drew its ring on a canvas and showed an empty gray disk.
 */
class NativeHeroWorkflowTest {
    private val robot = ShellRobot("native-hero")
    private val density get() = robot.instrumentation.targetContext.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun Rect.contains(other: Rect) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    private val ring = JSONObject("""{"type":"progressRing","text":"Round time","size":"large","bind":"done","min":0,"max":100,
        "children":[{"type":"text","text":"00:40","style":{"emphasis":"display"}}]}""")
    private val done = JSONObject("""{"done":"number"}""")

    @Test fun aLargeRingCentersItsChildren() {
        val ui = themedColumn(ring, JSONObject("""{"type":"text","text":"End of ring"}"""))
        assertEquals(null, validateNativeUi(ui, done, JSONObject("""{"done":25}""")))
        robot.renderApps("hero", listOf(ui.toString()), """{"done":25}""", "End of ring") {
            val frame = robot.bounds("Round time")
            val time = robot.bounds("00:40")
            assertTrue("the ring is at least 180 dp wide: ${frame.width()} px", frame.width() >= dp(180))
            assertTrue("the time is at the ring center", abs(time.centerX() - frame.centerX()) <= dp(12) && abs(time.centerY() - frame.centerY()) <= dp(12))
        }
    }

    @Test fun aSmallRingRejectsChildren() {
        val small = JSONObject(ring.toString()).apply { remove("size") }
        val failure = validateNativeUi(themedColumn(small), done, JSONObject("""{"done":25}"""))
        assertTrue("$failure", failure?.contains("Only a large progressRing holds children.") == true)
    }

    @Test fun longTextStaysInsideALargeRing() {
        val long = JSONObject(ring.toString()).put("children", JSONArray().put(JSONObject("""{"type":"text","text":"Belastungsphase läuft"}""")))
        val ui = themedColumn(long, JSONObject("""{"type":"text","text":"End of ring"}"""))
        assertEquals(null, validateNativeUi(ui, done, JSONObject("""{"done":60}""")))
        robot.renderApps("hero", listOf(ui.toString()), """{"done":60}""", "End of ring", fontScale = 2f) {
            val frame = robot.bounds("Round time")
            val text = robot.bounds("Belastungsphase läuft")
            assertTrue("the text $text stays inside the ring $frame", frame.contains(text))
        }
    }

    @Test fun dotsShowStepsAndRejectMoreThanTwelve() {
        val dots = JSONObject("""{"type":"progress","text":"Glasses","variant":"dots","bind":"glasses","min":0,"max":5}""")
        val fields = JSONObject("""{"glasses":"number"}""")
        val ui = themedColumn(dots, JSONObject("""{"type":"text","text":"End of dots"}"""))
        assertEquals(null, validateNativeUi(ui, fields, JSONObject("""{"glasses":3}""")))
        val many = validateNativeUi(themedColumn(JSONObject(dots.toString()).put("max", 13)), fields, JSONObject("""{"glasses":3}"""))
        assertTrue("$many", many?.contains("Dots show at most 12 steps.") == true)
        robot.renderApps("hero", listOf(ui.toString()), """{"glasses":3}""", "End of dots") {
            assertEquals("3 of 5", robot.waitFor("Glasses").stateDescription?.toString())
        }
    }

    @Test fun aLevelGaugeFillsFromTheBottom() {
        val level = JSONObject("""{"type":"progress","text":"Water today","variant":"level","bind":"water","min":0,"max":100,
            "children":[{"type":"text","text":"1.25 L","style":{"emphasis":"number"}}]}""")
        val ui = themedColumn(level, JSONObject("""{"type":"text","text":"End of level"}"""))
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"water":"number"}"""), JSONObject("""{"water":50}""")))
        robot.renderApps("hero", listOf(ui.toString()), """{"water":50}""", "End of level") { shot ->
            val tank = robot.bounds("Water today")
            val x = tank.left + dp(12)
            val low = shot.getPixel(x, tank.top + tank.height() * 85 / 100)
            val high = shot.getPixel(x, tank.top + tank.height() * 15 / 100)
            assertTrue("the bottom is filled and the top is empty", colorSaturation(low) > colorSaturation(high) + 0.15f)
            assertTrue("the amount is inside the tank", tank.contains(robot.bounds("1.25 L")))
        }
    }

    @Test fun aHeroCardIsTinted() {
        val plain = JSONObject("""{"type":"card","text":"Plain card","children":[{"type":"text","text":"Plain body"}]}""")
        val hero = JSONObject("""{"type":"card","text":"Hero card","variant":"hero","icon":"Drop","children":[{"type":"text","text":"Hero body"}]}""")
        val ui = themedColumn(plain, hero, JSONObject("""{"type":"text","text":"End of cards"}"""))
        assertEquals(null, validateNativeUi(ui))
        robot.renderApps("hero", listOf(ui.toString()), "{}", "End of cards") { shot ->
            fun inside(label: String) = robot.bounds(label).let { shot.getPixel(it.left - dp(8), it.centerY()) }
            assertTrue("the hero card is tinted", colorSaturation(inside("Hero body")) > colorSaturation(inside("Plain body")) + 0.1f)
        }
    }

    @Test fun emojiDrawsInPagesAndRows() {
        val big = JSONObject("""{"type":"emoji","text":"Milk","value":"🥛","size":"large"}""")
        val list = JSONObject("""{"type":"list","text":"Pantry","bind":"items","rowTemplate":{"type":"row","wrap":false,"children":[
            {"type":"emoji","text":"Item picture","bind":"picture","size":"small"},{"type":"text","text":"","bind":"name"}]}}""")
        val fields = JSONObject("""{"items":{"type":"list","maxItems":10,"fields":{"id":"string","picture":"string","name":"string"}}}""")
        val state = """{"items":[{"id":"1","picture":"🍞","name":"Bread"},{"id":"2","picture":"","name":"Salt"}]}"""
        val ui = themedColumn(big, list, JSONObject("""{"type":"text","text":"End of pantry"}"""))
        assertEquals(null, validateNativeUi(ui, fields, JSONObject(state)))
        assertTrue(validateNativeUi(themedColumn(JSONObject("""{"type":"emoji","text":"Nothing","size":"large"}"""))) != null)
        robot.renderApps("hero", listOf(ui.toString()), state, "End of pantry") {
            assertTrue("the large emoji is at least 96 dp: ${robot.bounds("Milk").width()} px", robot.bounds("Milk").width() >= dp(96))
            robot.waitFor("Salt")
        }
    }

    @Test fun circleCheckboxesShowTheLabelAbove() {
        val days = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
        val grid = JSONObject().put("type", "row").put("wrap", false).put("style", JSONObject().put("arrangement", "between")).put("children", JSONArray(days.map { day ->
            JSONObject().put("type", "checkbox").put("variant", "circle").put("text", day).put("bind", "d$day").put("action", "set$day")
        }))
        val ui = themedColumn(grid, JSONObject("""{"type":"text","text":"End of days"}"""))
        val fields = JSONObject().apply { days.forEach { put("d$it", "boolean") } }
        val state = JSONObject().apply { days.forEach { put("d$it", false) } }
        assertEquals(null, validateNativeUi(ui, fields, state))
        val events = CopyOnWriteArrayList<Pair<String, Any?>>()
        robot.renderApps("hero", listOf(ui.toString()), state.toString(), "End of days", event = { id, value -> events += id to value }) {
            val monday = robot.bounds("Mo")
            assertTrue("the label stands above a 36 dp check: $monday", monday.height() >= dp(52) && monday.width() < monday.height() + dp(12))
            robot.click("Mo")
            robot.waitUntil { events.contains("setMo" to true) }
        }
    }

    @Test fun theAgentScreenNamesTheNewNodes() {
        val ui = themedColumn(ring, JSONObject("""{"type":"progress","text":"Glasses","variant":"dots","bind":"glasses","min":0,"max":5}"""),
            JSONObject("""{"type":"emoji","text":"Milk","value":"🥛","size":"medium"}"""))
        val state = JSONObject("""{"done":25,"glasses":3}""")
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"done":"number","glasses":"number"}"""), state))
        val screen = AppScreen.render(ui, state, JSONObject(), 0L).text
        for (part in listOf("progressRing large", "00:40", "3 of 5", "emoji \"Milk\" 🥛")) assertTrue("$part in\n$screen", part in screen)
    }
}
