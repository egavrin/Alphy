package com.alpha.assistant.ui

import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.DIALOGUE_WIDE_DP
import com.alpha.assistant.tools.TABLE_ROWS_TAG
import com.alpha.assistant.tools.TABLE_ROW_DP
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.voice.FakeLiveTransport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Gate 3 (Task 9): a template file at its text caps, also with each text inside the picture at 1.25 times its cap, fits the dialogue stage with no scroll. For each cap
 * file, the scripted template reply is the file and the voice path stores it. Then the stage has no vertical scroll, and the canvas, the
 * control, and each text, also the whole card text, are shown above the strip. Each file prints one JSON line with the file and the size
 * class. The controller runs the class at the phone and the fold size.
 */
class StageTemplateFitTest {
    private val robot = ShellRobot("stage-template-fit")

    @Test fun eachCapFileFitsTheStage() {
        // A run with -e fitFreePlan lesson stores that free plan instead and must fail: the proof that the scroll check finds an overflow (Step 2).
        val free = InstrumentationRegistry.getArguments().getString("fitFreePlan")
        val names = free?.let(::listOf) ?: FILES.map { "template/$it" }
        val misses = mutableListOf<String>()
        for (name in names) {
            val file = appFilePlan(name)
            val fixture = ShellFixture("stage-template-fit")
            val shell = TemplateShell(fixture, robot, FakeLiveTransport())
            val size = if (fixture.context.resources.configuration.smallestScreenWidthDp >= DIALOGUE_WIDE_DP) "fold" else "phone"
            val density = fixture.context.resources.displayMetrics.density
            val talk = if (free != null) TemplateTalk(plans = AgentScriptProvider(file)) else TemplateTalk(AgentScriptProvider(file))
            // A form of the data family has no picture and no control on the stage: a tap on its rows or points marks one (block 2 plan, D3).
            val data = name.substringAfterLast('/').substringBefore('-') in DATA_FORMS
            try {
                shell.live(talk) { scenario, model, _ ->
                    val faults = mutableListOf<String>()
                    // A nodata file gets the narration line of its card, so its stored file starts with the reply.
                    val stored = shell.finalFile(model, shell.screen(model).id)
                    if (if (data) stored?.startsWith(file.trimEnd()) != true else stored != file) faults += "the stored app file is not the reply"
                    val template = if (free == null) Template(file) else null
                    if (template != null) robot.waitUntil(30_000) {
                        (data || stageParts(scenario).any { it.isCanvasOf(template.title) }) && robot.composedTextIn(scenario, STAGE, template.card.first())
                    }
                    // The stage settles: an app shows its parts with a short motion, so the texts and the bounds of each part stay the same for 1 s.
                    fun key(parts: List<Part>) = parts.map { it.bounds to it.config.getOrNull(SemanticsProperties.Text) }
                    var parts = stageParts(scenario)
                    robot.waitUntil(30_000) {
                        SystemClock.sleep(1_000)
                        val next = stageParts(scenario)
                        (key(next) == key(parts) && next.count { it.config.getOrNull(SemanticsProperties.Text) != null } >= 3).also { parts = next }
                    }
                    val strip = robot.composedBounds(scenario, STRIP).first.top
                    // The rows of a table scroll inside its box, and the page does not (block 2 plan, D3).
                    val scroll = parts.filterNot { it.isTableRows() }.mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() }.maxOrNull() ?: 0f
                    if (scroll != 0f) faults += "the stage scrolls by ${dp(scroll, density)} dp"
                    // A compact width shows at most 6 rows in the box of a table, and a wider screen at most 8 (controller ruling, stage 5).
                    val rowsMax = if (size == "phone") 6 else 8
                    parts.filter { it.isTableRows() }.forEach { if (dp(it.bounds.height().toFloat(), density) > TABLE_ROW_DP * rowsMax) faults += "the row box is taller than $rowsMax rows" }
                    // Each text and each control of the stage: none may end under the top of the strip.
                    val shown = parts.filter { part ->
                        part.config.getOrNull(SemanticsProperties.Text) != null || SemanticsActions.OnClick in part.config || part.isTableRows() ||
                            SemanticsActions.SetProgress in part.config || template != null && part.isCanvasOf(template.title)
                    }
                    if (template != null && !data) {
                        if (shown.none { it.isCanvasOf(template.title) }) faults += "no canvas"
                        // A slider shows its label with its value, such as "Votes for: 11".
                        if (shown.none { part -> part.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(checkNotNull(template.control)) } == true })
                            faults += "no control label"
                        if (shown.none { SemanticsActions.OnClick in it.config || SemanticsActions.SetProgress in it.config }) faults += "no control"
                    }
                    template?.card?.filterNot { text -> shown.any { robot.hasText(text)(it.config) } }?.forEach { faults += "no card text \"$it\"" }
                    val lowest = shown.maxOfOrNull { it.bounds.bottom } ?: strip
                    shown.filter { it.bounds.bottom > strip }.forEach { faults += "${it.name()} ends ${dp((it.bounds.bottom - strip).toFloat(), density)} dp under the top of the strip" }
                    if (faults.any { it.startsWith("no control") }) println("gate 3 ${name}: stage parts " + parts.mapNotNull { it.name().takeIf { n -> n != "a part" } })
                    // The whole card text: no card text is cut. Some one-line labels report an overflow of their font padding, so only the card texts count.
                    template?.card?.let { card -> robot.composedTextFit(scenario, STAGE).filter { it.third && it.first.trimEnd() in card } }
                        ?.forEach { faults += "the card text \"${it.first}\" is cut" }
                    val line = JSONObject().put("gate", "3").put("file", name).put("size", size).put("scrollDp", dp(scroll, density))
                        .put("underStripDp", dp(maxOf(0, lowest - strip).toFloat(), density)).put("faults", JSONArray(faults))
                        // The box of the rows of a table and the stage height above the strip, so an overflow names its parts.
                        .put("rowsBoxDp", parts.firstOrNull { it.isTableRows() }?.let { dp(it.bounds.height().toFloat(), density) } ?: JSONObject.NULL)
                        .put("aboveStripDp", parts.minOfOrNull { it.bounds.top }?.let { dp((strip - it).toFloat(), density) } ?: JSONObject.NULL)
                    println(line)
                    if (faults.isNotEmpty()) misses += "$name ($size): $faults"
                }
            } finally { shell.remove() }
        }
        assertTrue("cap files that do not fit the stage: $misses", misses.isEmpty())
    }

    /** One composed node of the stage: its configuration, and its window bounds, which no scroll container and no strip clips. */
    private class Part(val config: SemanticsConfiguration, val bounds: Rect) {
        fun isCanvasOf(title: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(title) } == true
        /** The box of the rows of a table: its rows scroll inside it, so the gate counts the box and none of its rows. */
        fun isTableRows() = config.getOrNull(SemanticsProperties.TestTag) == TABLE_ROWS_TAG
        /** The text or the description of the part, for the output. */
        fun name() = config.getOrNull(SemanticsProperties.Text)?.joinToString()?.let { "the text \"$it\"" }
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.let { "the part \"${it.take(40)}\"" }
            ?: config.getOrNull(SemanticsProperties.StateDescription)?.let { "the control \"$it\"" } ?: "a part"
    }

    private fun stageParts(scenario: ActivityScenario<CloudTestActivity>): List<Part> {
        val parts = mutableListOf<Part>()
        scenario.onActivity { activity ->
            fun visit(node: SemanticsNode) {
                val at = node.positionInWindow
                parts += Part(node.config, Rect(at.x.roundToInt(), at.y.roundToInt(), (at.x + node.size.width).roundToInt(), (at.y + node.size.height).roundToInt()))
                if (node.config.getOrNull(SemanticsProperties.TestTag) != TABLE_ROWS_TAG) node.children.forEach(::visit)
            }
            robot.nodes(activity).filter { it.config.getOrNull(SemanticsProperties.TestTag) == STAGE }.forEach(::visit)
        }
        return parts
    }

    /** The title (the first text of the first line), the control label, and the card texts of a template file. */
    private class Template(file: String) {
        private val lines = file.lines()
        val title = texts(lines.first()).first()
        val control = lines.singleOrNull { it.startsWith("control = ") }?.let { texts(it).first() }
        val card = texts(lines.single { it.startsWith("card = ") })
        private fun texts(line: String) = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(line).map { it.groupValues[1] }.toList()
    }

    private fun dp(px: Float, density: Float) = Math.round(px / density * 10) / 10.0

    private companion object {
        const val STAGE = "dialogue_stage"
        const val STRIP = "dialogue_strip"
        /**
         * The cap files of the three templates (kit/test/fixtures/template), at the caps, and with each text inside the picture at 1.25 times its cap (a text outside the picture has
         * a hard cap). The steps files have six steps and six narration lines at their caps (plan "one scene", Task 25).
         */
        val FILES = listOf("scene-caps", "scene-caps125", "items-caps", "items-caps125", "cells-caps", "cells-caps125", "steps-caps", "items-steps-caps",
            "compare-caps", "table-caps", "chart-caps", "nodata-caps")
        /** The forms of the data family (block 2 plan, stage 5). */
        val DATA_FORMS = setOf("compare", "table", "chart", "nodata")
    }
}
