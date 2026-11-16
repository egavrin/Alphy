package com.alpha.assistant.tools

import android.graphics.Rect
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeContainerLayoutWorkflowTest {
    private val robot = ShellRobot("native-container-layout")

    /** Failure case: a row without wrap gives the first long text all the width, and the next text shows one letter per line. */
    @Test fun twoTextsInARowShareTheWidth() {
        val fixture = ShellFixture("row-layout")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val ui = JSONObject("""{"type":"row","wrap":false,"style":{"crossAlign":"center","arrangement":"between"},"children":[
                    {"type":"text","text":"Reading progress","bind":"page","style":{"emphasis":"title"}},
                    {"type":"text","text":"Percent complete","bind":"percent","style":{"emphasis":"number"}}]}""")
                val state = JSONObject().put("page", "Page 1 of 320").put("percent", "0%")
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(ui.toString(), state.toString(), enabled = true) { _, _ -> }
                            }
                        }
                    }
                }
                val first = checkNotNull(robot.waitFor("Reading progress: Page 1 of 320"))
                val second = checkNotNull(robot.waitFor("Percent complete: 0%"))
                val width = { node: android.view.accessibility.AccessibilityNodeInfo -> Rect().also(node::getBoundsInScreen).width() }
                val screen = activityWidth(scenario)
                assertTrue("first ${width(first)} of $screen", width(first) >= screen / 4)
                assertTrue("second ${width(second)} of $screen", width(second) >= screen / 4)
            }
        } finally { fixture.remove() }
    }

    /** Failure case: a list row with a labeled checkbox and a weighted text column showed the text one letter per line. */
    @Test fun checkboxInARowLeavesWidthForWeightedText() {
        val fixture = ShellFixture("checkbox-row-layout")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val ui = JSONObject("""{"type":"list","text":"Stops","bind":"stops","rowTemplate":{"type":"row","wrap":false,
                    "style":{"crossAlign":"center","gap":"small"},"children":[
                    {"type":"checkbox","text":"Visited","bind":"visited","action":"visit"},
                    {"type":"column","layout":{"weight":1},"children":[{"type":"text","text":"Stop","bind":"name"}]}]}}""")
                val state = JSONObject().put("stops", org.json.JSONArray().put(
                    JSONObject().put("id", "a").put("visited", false).put("name", "Botanical garden and the old greenhouse by the river")))
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(ui.toString(), state.toString(), enabled = true) { _, _ -> }
                            }
                        }
                    }
                }
                val stop = checkNotNull(robot.waitFor("Stop: Botanical garden and the old greenhouse by the river"))
                val width = Rect().also(stop::getBoundsInScreen).width()
                val screen = activityWidth(scenario)
                // The checkbox needs only its box and its short label, so the wrapped text gets most of the row.
                assertTrue("stop $width of $screen", width >= screen / 2)
            }
        } finally { fixture.remove() }
    }

    /** Failure case: a page whose content sat in a weighted node showed only its navigation bar in a scrolling screen. */
    @Test fun weightedChildInAScrollingColumnStaysVisible() {
        val fixture = ShellFixture("weighted-column-layout")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val ui = JSONObject("""{"type":"column","children":[
                    {"type":"column","layout":{"weight":1},"children":[{"type":"text","text":"Budget content"}]},
                    {"type":"text","text":"Footer"}]}""")
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
                                ToolContent(ui.toString(), "{}", enabled = true) { _, _ -> }
                            }
                        }
                    }
                }
                robot.waitFor("Footer")
                val content = checkNotNull(composedBounds(scenario, "Budget content"))
                assertTrue("content $content", content.height > 0f)
            }
        } finally { fixture.remove() }
    }

    /** Failure case: stat cards in a grid broke "10,000 steps" into "10,00 / 0 / steps" and "6 h 30 min" into two lines. */
    @Test fun shortMetricValuesStayOnOneLine() {
        val values = listOf("10,000 steps", "6 h 30 min", "5")
        val ui = """{"type":"grid","columns":3,"children":[
            {"type":"metric","text":"Steps","bind":"a"},{"type":"metric","text":"Time","bind":"b"},{"type":"metric","text":"Count","bind":"c"}]}"""
        val state = JSONObject().put("a", values[0]).put("b", values[1]).put("c", values[2])
        val lines = textLayouts(ui, state, values).map { it.lineCount }
        assertTrue("lines $lines", lines.all { it == 1 })
        // At a large font scale one line cannot fit at the smallest size. The value then breaks between words and shows in full.
        val large = textLayouts(ui, state, values, fontScale = 2f)
        assertTrue("large ${large.map { it.lineCount }}", large.all { wholeWords(it) && !it.hasVisualOverflow })
    }

    /** Failure case: segmented options showed "Fortnigh/tly" and "Quarte/rly" broken inside the word. */
    @Test fun segmentedLabelsDoNotBreakInsideAWord() {
        val labels = listOf("Annually", "Biannually", "Quarterly", "Fortnightly", "Week")
        val options = labels.joinToString(",") { """{"text":"$it","value":"$it"}""" }
        val layouts = textLayouts("""{"type":"segmented","text":"Compounding","bind":"period","action":"period","options":[$options]}""",
            JSONObject().put("period", "Week"), labels)
        assertTrue("lines ${layouts.map { it.lineCount }}", layouts.all(::wholeWords))
    }

    /** Failure case: four weighted direction buttons of a 2048 game showed "Dow/n" in a section of a phone screen. */
    @Test fun narrowButtonLabelsDoNotBreakInsideAWord() {
        val labels = listOf("Up", "Down", "Left", "Right")
        val buttons = labels.joinToString(",") { """{"type":"button","text":"$it","action":"${it.lowercase()}","variant":"tonal","layout":{"weight":1}}""" }
        val ui = """{"type":"column","layout":{"width":320},"children":[{"type":"row","wrap":false,"style":{"gap":"small"},"children":[$buttons]}]}"""
        val layouts = textLayouts(ui, JSONObject(), labels)
        assertTrue("lines ${layouts.map { it.lineCount }}", layouts.all(::wholeWords))
        // A label that fits keeps the size of the button text.
        assertTrue("sizes ${layouts.map { it.layoutInput.style.fontSize }}", layouts[0].layoutInput.style.fontSize.value >= 14f)
    }

    /** Failure case: at font scale 2, a menu row with a stepper showed the drink name as "Espress/o". */
    @Test fun aShortNameInAStepperRowDoesNotBreakInsideAWord() {
        val ui = """{"type":"column","layout":{"width":320},"children":[{"type":"row","wrap":false,"style":{"crossAlign":"center","gap":"small"},"children":[
            {"type":"column","style":{"gap":"none"},"layout":{"weight":1},"children":[{"type":"text","text":"Espresso"},{"type":"text","text":"","bind":"price"}]},
            {"type":"icon","name":"Minus","text":"Remove one","action":"less"},{"type":"badge","text":"","bind":"count"},
            {"type":"icon","name":"Plus","text":"Add one","action":"more"}]}]}"""
        val state = JSONObject().put("price", "3.00").put("count", "0")
        val layout = textLayouts(ui, state, listOf("Espresso"), fontScale = 2f).single()
        assertTrue("lines ${layout.lineCount}", wholeWords(layout))
    }

    /** Failure case: a countdown showed its time as body text, "Rest: Remaining 00:04:59", not as a large clock. */
    @Test fun aTimerShowsItsTimeAsALargeClock() {
        val fixture = ShellFixture("timer-layout")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val ui = JSONObject("""{"type":"column","children":[{"type":"timer","text":"Rest","mode":"countdown","bind":"endAt"}]}""")
                val state = JSONObject().put("endAt", System.currentTimeMillis() + 300_000)
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            // Android scales large text less than small text, so the ratio holds only at one font scale.
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                                Column(Modifier.safeDrawingPadding()) {
                                    ToolContent(ui.toString(), state.toString(), enabled = true) { _, _ -> }
                                }
                            }
                        }
                    }
                }
                robot.waitFor("Rest")
                val label = checkNotNull(composedBounds(scenario, "Rest"))
                val clock = checkNotNull(composedBounds(scenario) { Regex("""^\d\d:\d\d:\d\d$""").matches(it) })
                assertTrue("label $label, clock $clock", clock.height >= label.height * 1.5f)
            }
        } finally { fixture.remove() }
    }

    /** The text layouts of [labels] after [ui] renders with [state] at [fontScale]. The device font scale does not apply. */
    private fun textLayouts(ui: String, state: JSONObject, labels: List<String>, fontScale: Float = 1f): List<TextLayoutResult> {
        val fixture = ShellFixture("text-layout")
        try {
            fixture.install()
            return fixture.launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                                Column(Modifier.safeDrawingPadding()) {
                                    ToolContent(JSONObject(ui).toString(), state.toString(), enabled = true) { _, _ -> }
                                }
                            }
                        }
                    }
                }
                robot.waitFor(labels.last())
                labels.map { label ->
                    val results = mutableListOf<TextLayoutResult>()
                    scenario.onActivity { activity ->
                        checkNotNull(semanticsNodes(activity).firstOrNull { node ->
                            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
                        }) { "No text $label" }.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                    }
                    results.single()
                }
            }
        } finally { fixture.remove() }
    }

    /** True when each line break of [layout] falls between words. */
    private fun wholeWords(layout: TextLayoutResult): Boolean {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount - 1).all { line ->
            val end = layout.getLineEnd(line)
            text[end - 1].isWhitespace() || text[end - 1] == '-' || text[end].isWhitespace()
        }
    }

    /** Failure case: a 3 by 3 board of card cells with columns 3 showed only 2 cells in each row on a phone. */
    @Test fun explicitGridColumnsFitAPhone() {
        val fixture = ShellFixture("grid-layout")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val cells = (1..9).joinToString(",") { """{"type":"card","children":[{"type":"text","text":"Cell $it"}]}""" }
                val ui = JSONObject("""{"type":"grid","columns":3,"children":[$cells]}""")
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(ui.toString(), "{}", enabled = true) { _, _ -> }
                            }
                        }
                    }
                }
                robot.waitFor("Cell 9")
                val tops = (1..3).map { checkNotNull(composedBounds(scenario, "Cell $it")).top }
                assertTrue("first row tops $tops", tops.distinct().size == 1)
            }
        } finally { fixture.remove() }
    }

    /** Failure case: a motion wrapper drew its result and its detail text on top of each other. */
    @Test fun motionChildrenDoNotOverlap() {
        val fixture = ShellFixture("motion-layout")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val ui = JSONObject("""{"type":"motion","preset":"tumble","trigger":"roll","children":[
                    {"type":"text","text":"Total","bind":"total","style":{"emphasis":"title"}},
                    {"type":"text","text":"Results","bind":"results"}]}""")
                val state = JSONObject().put("roll", 0).put("total", 6).put("results", "4 and 2")
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(ui.toString(), state.toString(), enabled = true) { _, _ -> }
                            }
                        }
                    }
                }
                robot.waitFor("Results: 4 and 2")
                val total = checkNotNull(composedBounds(scenario, "Total: 6"))
                val results = checkNotNull(composedBounds(scenario, "Results: 4 and 2"))
                assertTrue("total $total, results $results", !total.overlaps(results))
            }
        } finally { fixture.remove() }
    }

    /** Failure case: a segmented row wider than the screen scrolled sideways, so its last option looked cut off. */
    @Test fun segmentedOptionsShareTheScreenWidth() {
        val fixture = ShellFixture("segmented-layout")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val labels = listOf("10%", "15%", "18%", "20%", "25%", "30%")
                val options = labels.joinToString(",") { """{"text":"$it","value":"$it"}""" }
                val ui = JSONObject("""{"type":"segmented","text":"Tip","bind":"tip","action":"tip","options":[$options]}""")
                val state = JSONObject().put("tip", "15%")
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(ui.toString(), state.toString(), enabled = true) { _, _ -> }
                            }
                        }
                    }
                }
                val screen = activityWidth(scenario)
                val bounds = labels.map { label -> Rect().also(checkNotNull(robot.waitFor(label))::getBoundsInScreen) }
                assertTrue("options $bounds of $screen", bounds.all { it.left >= 0 && it.right <= screen })
                val widths = bounds.map { it.width() }
                assertTrue("widths $widths", widths.max() - widths.min() <= widths.max() / 10)
            }
        } finally { fixture.remove() }
    }

    /** Accessibility bounds near the status bar are clipped, so this reads the composed layout. */
    private fun composedBounds(scenario: androidx.test.core.app.ActivityScenario<com.alpha.assistant.CloudTestActivity>, label: String):
        androidx.compose.ui.geometry.Rect? = composedBounds(scenario) { it == label }

    private fun composedBounds(scenario: androidx.test.core.app.ActivityScenario<com.alpha.assistant.CloudTestActivity>, matches: (String) -> Boolean):
        androidx.compose.ui.geometry.Rect? {
        var bounds: androidx.compose.ui.geometry.Rect? = null
        scenario.onActivity { activity ->
            bounds = semanticsNodes(activity)
                .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { matches(it.text) } }
                ?.boundsInRoot
        }
        return bounds
    }

    private fun semanticsNodes(activity: android.app.Activity): List<androidx.compose.ui.semantics.SemanticsNode> {
        fun root(view: android.view.View): androidx.compose.ui.platform.ViewRootForTest? = view as? androidx.compose.ui.platform.ViewRootForTest
            ?: (view as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
        return root(activity.window.decorView)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = false).orEmpty()
    }

    private fun activityWidth(scenario: androidx.test.core.app.ActivityScenario<com.alpha.assistant.CloudTestActivity>): Int {
        var width = 0
        scenario.onActivity { width = it.window.decorView.width }
        return width
    }
}
