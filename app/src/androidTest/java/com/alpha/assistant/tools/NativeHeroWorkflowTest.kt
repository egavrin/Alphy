package com.alpha.assistant.tools

import android.graphics.Rect
import androidx.compose.ui.graphics.toArgb
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

    /** Review finding: display text wrapped inside a large ring, so a time such as 1:05:30 broke across lines. */
    @Test fun displayTextInALargeRingStaysOnOneLine() {
        val long = JSONObject(ring.toString()).put("children", JSONArray().put(JSONObject("""{"type":"text","text":"1:05:30","style":{"emphasis":"display"}}""")))
        val ui = themedColumn(JSONObject("""{"type":"text","text":"8","style":{"emphasis":"display"}}"""), long, JSONObject("""{"type":"text","text":"End of ring"}"""))
        assertEquals(null, validateNativeUi(ui, done, JSONObject("""{"done":25}""")))
        robot.renderApps("hero", listOf(ui.toString()), """{"done":25}""", "End of ring", fontScale = 2f) {
            val line = robot.bounds("8").height()
            val time = robot.bounds("1:05:30").height()
            assertTrue("the time keeps one line: $time px against $line px", time <= line * 5 / 4)
        }
    }

    /** Review finding: in a half-width cell the ring cannot grow, so long text at 200% font ran out of the circle. */
    @Test fun aLargeRingInAHalfCellKeepsItsTextInside() {
        val long = JSONObject(ring.toString()).put("children", JSONArray().put(JSONObject("""{"type":"text","text":"Belastungsphase läuft"}""")))
        val grid = JSONObject().put("type", "grid").put("columns", 2).put("children", JSONArray().put(long).put(JSONObject("""{"type":"text","text":"Other cell"}""")))
        val ui = themedColumn(grid, JSONObject("""{"type":"text","text":"End of ring"}"""))
        assertEquals(null, validateNativeUi(ui, done, JSONObject("""{"done":60}""")))
        robot.renderApps("hero", listOf(ui.toString()), """{"done":60}""", "End of ring", fontScale = 2f) {
            val frame = robot.bounds("Round time")
            val text = robot.bounds("Belastungsphase läuft")
            // The ring stroke is 14 dp. Every corner of the text box stays inside the circle within the stroke.
            val radius = frame.width() / 2.0 - dp(14)
            val corners = listOf(text.left to text.top, text.right to text.top, text.left to text.bottom, text.right to text.bottom)
            assertTrue("the text $text stays inside the circle of $frame",
                corners.all { (x, y) -> kotlin.math.hypot(x - frame.exactCenterX().toDouble(), y - frame.exactCenterY().toDouble()) <= radius + 2 })
        }
    }

    /** Review finding: twelve 20 dp dots need 328 dp, so in a narrow cell the last dots got no width. */
    @Test fun twelveDotsFitInAHalfCell() {
        val dots = JSONObject("""{"type":"progress","text":"Glasses","variant":"dots","bind":"glasses","min":0,"max":12}""")
        val grid = JSONObject().put("type", "grid").put("columns", 2).put("children", JSONArray().put(dots).put(JSONObject("""{"type":"text","text":"Other cell"}""")))
        val ui = themedColumn(grid, JSONObject("""{"type":"text","text":"End of dots"}"""))
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"glasses":"number"}"""), JSONObject("""{"glasses":12}""")))
        robot.renderApps("hero", listOf(ui.toString()), """{"glasses":12}""", "End of dots") { shot ->
            val row = robot.bounds("Glasses")
            val background = shot.getPixel(row.centerX(), row.bottom + dp(3))
            val ink = (row.left until row.right).map { x -> shot.ink(x, row.centerY(), background) }
            val dotsSeen = ink.indices.count { ink[it] && (it == 0 || !ink[it - 1]) }
            assertEquals("dots drawn in $row", 12, dotsSeen)
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
            assertEquals("3 / 5", robot.waitFor("Glasses").stateDescription?.toString())
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

    /** Failure case: hero cards used the palette's primary container, which is neon in the teal and green palettes. */
    @Test fun aHeroCardUsesTheQuietContainer() {
        val hero = JSONObject("""{"type":"card","text":"Hero card","variant":"hero","children":[{"type":"text","text":"Hero body"}]}""")
        val ui = themedColumn(hero, JSONObject("""{"type":"text","text":"End of cards"}"""), palette = "teal").put("dark", false)
        assertEquals(null, validateNativeUi(ui))
        val scheme = checkNotNull(ToolPalettes.scheme("teal", false))
        robot.renderApps("hero", listOf(ui.toString()), "{}", "End of cards") { shot ->
            val body = robot.bounds("Hero body")
            assertSameColor("the hero background", shot.getPixel(body.left - dp(8), body.centerY()), scheme.secondaryContainer.toArgb())
        }
    }

    /** Review finding: the default progress track is the secondary container, so it disappeared on a hero card. */
    @Test fun progressTracksShowOnAHeroCard() {
        val hero = JSONObject("""{"type":"card","variant":"hero","children":[{"type":"text","text":"Hero body"},
            {"type":"progress","text":"Monthly budget","bind":"used","min":0,"max":100},
            {"type":"progress","text":"Glasses","variant":"dots","bind":"glasses","min":0,"max":5}]}""")
        val ui = themedColumn(hero, JSONObject("""{"type":"text","text":"End of cards"}""")).put("dark", false)
        val state = """{"used":40,"glasses":2}"""
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"used":"number","glasses":"number"}"""), JSONObject(state)))
        robot.renderApps("hero", listOf(ui.toString()), state, "End of cards") { shot ->
            val bar = robot.bounds("Monthly budget")
            val card = shot.getPixel(bar.left - dp(8), bar.centerY())
            assertTrue("the empty part of the bar shows", shot.ink(bar.left + bar.width() * 3 / 4, bar.centerY(), card))
            val dots = robot.bounds("Glasses")
            assertTrue("an empty dot shows", shot.ink(dots.left + dp(4 * 28 + 10), dots.centerY(), card))
        }
    }

    private val bolt = JSONObject("""{"type":"emoji","text":"Bolt","value":"⚡","size":"large"}""")
    private val cost = JSONObject("""{"type":"metric","text":"Cost","bind":"cost","style":{"emphasis":"display"}}""")
    private val costState = """{"cost":"42 €","used":40}"""
    private val costFields = JSONObject("""{"cost":"string","used":"number"}""")
    private val endOfHero = JSONObject("""{"type":"text","text":"End of hero"}""")
    private fun heroOf(vararg children: JSONObject, align: String? = null) = JSONObject().put("type", "card").put("variant", "hero")
        .put("children", JSONArray(children.toList())).apply { if (align != null) put("style", JSONObject().put("align", align)) }

    /** Renders [ui] with the cost state and gives the emoji and the cost value boxes to [check]. */
    private fun heroBounds(ui: JSONObject, fontScale: Float = 1f, check: (emoji: Rect, value: Rect) -> Unit) {
        assertEquals(null, validateNativeUi(ui, costFields, JSONObject(costState)))
        robot.renderApps("hero", listOf(ui.toString()), costState, "End of hero", fontScale = fontScale) {
            check(robot.bounds("Bolt"), robot.bounds("42 €"))
        }
    }

    /** Failure case: a hero card centered its large emoji above left-aligned numbers, so the card looked like a template. */
    @Test fun aHeroEmojiStandsBesideTheResult() {
        val bar = JSONObject("""{"type":"progress","text":"Monthly budget","bind":"used","min":0,"max":100}""")
        for (scale in listOf(1f, 2f)) heroBounds(themedColumn(heroOf(bolt, cost, bar), endOfHero), scale) { emoji, value ->
            assertTrue("at $scale the emoji $emoji is right of the value $value", emoji.left >= value.right)
            assertTrue("at $scale the emoji and the value share a line", emoji.top < value.bottom && emoji.bottom > value.top)
            assertTrue("at $scale the bar keeps the card width", robot.bounds("Monthly budget").right >= emoji.centerX())
        }
    }

    @Test fun aNarrowHeroKeepsItsEmojiAtTheStart() {
        val grid = JSONObject().put("type", "grid").put("columns", 2).put("children", JSONArray().put(heroOf(bolt, cost)).put(JSONObject("""{"type":"text","text":"Other cell"}""")))
        heroBounds(themedColumn(grid, endOfHero)) { emoji, value ->
            assertTrue("the emoji $emoji is above the value $value", emoji.bottom <= value.top)
            assertTrue("the emoji tile $emoji keeps its size", emoji.width() <= dp(120))
            assertTrue("the emoji starts where the value starts", abs(emoji.left - value.left) <= dp(4))
        }
    }

    @Test fun aCenteredHeroCentersItsText() = heroBounds(themedColumn(heroOf(bolt, cost, align = "center"), endOfHero)) { emoji, value ->
        assertTrue("the emoji $emoji is above the value $value", emoji.bottom <= value.top)
        assertTrue("the value $value is centered under the emoji $emoji", abs(emoji.centerX() - value.centerX()) <= dp(8))
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

    /** Review focus: an empty or undrawable emoji keeps an empty tile and its description, not a blank gap or a box glyph. */
    @Test fun anEmojiWithoutAGlyphShowsAnEmptyTile() {
        val ui = themedColumn(JSONObject("""{"type":"emoji","text":"Empty picture","bind":"empty","size":"large"}"""),
            JSONObject("""{"type":"emoji","text":"Unknown picture","bind":"unknown","size":"large"}"""),
            JSONObject("""{"type":"text","text":"End of pictures"}"""))
        val state = JSONObject().put("empty", "").put("unknown", "\uE000")
        assertEquals(null, validateNativeUi(ui, JSONObject("""{"empty":"string","unknown":"string"}"""), state))
        robot.renderApps("hero", listOf(ui.toString()), state.toString(), "End of pictures") { shot ->
            for (label in listOf("Empty picture", "Unknown picture")) {
                val tile = robot.bounds(label)
                val page = shot.getPixel(tile.right + dp(8), tile.centerY())
                val center = shot.getPixel(tile.centerX(), tile.centerY())
                assertTrue("$label draws a tile", center != page)
                val inside = (2..8).flatMap { i -> (2..8).map { j -> shot.getPixel(tile.left + tile.width() * i / 10, tile.top + tile.height() * j / 10) } }
                assertTrue("$label draws no glyph", inside.all { it == center })
            }
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
        for (part in listOf("progressRing large", "00:40", "3 / 5", "emoji \"Milk\" 🥛")) assertTrue("$part in\n$screen", part in screen)
    }
}
