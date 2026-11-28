package com.alpha.assistant.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.data.Draft
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.StagePreviewFrame
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.kitPreview
import com.alpha.assistant.tools.withPlanStep
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stage A of the plan "Ready plans", job AH: the large picture of a stage drawing with no side part (AH1 to AH9), by the tests TH1 to TH4,
 * TH6, and TH8. A screen is a compiled frame of the kit (no card, a caption, an inline choice), and [newUi] can give it another caption.
 * The controller runs the class at the phone size (412 × 915 dp) and at the fold size (864 × 930 dp).
 */
class StageLargePictureTest {
    private val stage = StageShell("stage-large")
    private val robot get() = stage.robot
    private val context get() = stage.fixture.context
    private val density get() = context.resources.displayMetrics.density
    private val operation = UUID.randomUUID().toString()
    @After fun remove() = stage.remove()

    /**
     * TH1: a scene with a stepper and a scene with a choice. The picture card is as wide as the stage less its padding (8 dp, or 16 dp on a
     * wide stage), the caption is under the picture, and the bottom of the caption, the last part, is at most 24 dp over the stage bottom.
     * A caption of 103 characters takes 3 lines on a compact width and at most 2 on a wide stage (F2). On a wide stage the inline choice is
     * at most 440 dp wide, in the center (F3).
     */
    @Test fun aStepperSceneAndAChoiceSceneFillTheStage() {
        val scenes = listOf(newUi(frame(appFilePlan("template/heart")), HEART_CAPTION, "whole") to HEART_CAPTION,
            newUi(frame(SHIP), holds = "whole") to SHIP_CAPTION, newUi(frame(SHIP), LONG_CAPTION, "whole") to LONG_CAPTION)
        previews { scenario, model ->
            scenes.forEach { (frame, caption) ->
                show(model, frame)
                robot.waitUntil(30_000) { picture() != null && stageBounds(robot, caption) != null }
                robot.awaitFrames(5)
                val page = robot.composedBounds(scenario, STAGE).first
                val wide = page.width() / density >= 600
                val inset = dp(if (wide) 16 else 8)
                val picture = checkNotNull(picture())
                val text = checkNotNull(stageBounds(robot, caption))
                val name = frame.spec.title
                assertTrue("$name: the picture $picture is the stage $page less $inset px at each side",
                    abs(picture.left - page.left - inset) <= dp(1) && abs(page.right - picture.right - inset) <= dp(1))
                assertTrue("$name: the caption $text is under the picture $picture", text.top >= picture.bottom)
                assertTrue("$name: the caption $text ends at most 24 dp over the stage bottom ${page.bottom}", page.bottom - text.bottom <= dp(24))
                if (caption == LONG_CAPTION) {
                    // The line height of bodyLarge is 24 sp.
                    val lines = text.height() / (24 * context.resources.configuration.fontScale * density)
                    assertTrue("$name: the caption $text has $lines lines", if (wide) lines <= 2.5f else lines in 2.5f..3.5f)
                }
                readStage(robot) { nodes -> nodes.firstOrNull { it.description() == CHOICE }?.bounds() }?.takeIf { wide }?.let { choice ->
                    assertTrue("$name: the choice $choice is at most 440 dp wide", choice.width() <= dp(441))
                    assertTrue("$name: the choice $choice is in the center of $page", abs(choice.centerX() - page.centerX()) <= dp(1))
                }
            }
        }
    }

    /** F4: at a landscape tablet size (1280 × 800 dp) the floor of the picture comes from the stage height, so the scene fits with no scroll. */
    @Test fun aLandscapeTabletStageFitsWithNoScroll() {
        val size = Regex("""Override size: (\S+)""").find(robot.shell("wm size"))?.groupValues?.get(1)
        robot.shell("wm size ${(1280 * density).roundToInt()}x${(800 * density).roundToInt()}")
        try {
            robot.waitUntil { Resources.getSystem().configuration.screenWidthDp >= 1200 }
            val heart = newUi(frame(appFilePlan("template/heart")), HEART_CAPTION, "whole")
            previews { scenario, model ->
                show(model, heart)
                robot.waitUntil(30_000) { picture() != null && stageBounds(robot, HEART_CAPTION) != null }
                robot.awaitFrames(5)
                val page = robot.composedBounds(scenario, STAGE).first
                val text = checkNotNull(stageBounds(robot, HEART_CAPTION))
                val ranges = readStage(robot) { nodes -> nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue() } }
                assertTrue("the stage $page of ${page.width() / density} dp scrolls: $ranges", ranges.all { it == 0f })
                assertTrue("the caption $text ends inside the stage $page", text.bottom <= page.bottom)
            }
        } finally { robot.shell(size?.let { "wm size $it" } ?: "wm size reset") }
    }

    /** F5: the step text of the narrator is the caption of a scene with no card, the card of a scene that has one, and a bound text gives its value. */
    @Test fun theStepTextIsTheCaptionOfASceneWithNoCard() {
        fun text(value: String, bind: String? = null, emphasis: String? = null) = JSONObject().put("type", "text").put("text", value)
            .apply { bind?.let { put("bind", it) }; emphasis?.let { put("style", JSONObject().put("emphasis", it)) } }
        fun node(type: String, vararg children: JSONObject) = JSONObject().put("type", type).put("children", JSONArray(children.toList()))
        val picture = node("card", JSONObject().put("type", "canvas").put("text", "Сердце")).put("variant", "hero")
        val state = JSONObject().put("d", JSONObject().put("caption", HEART_CAPTION).put("card", "Полые вены приносят кровь."))
        assertEquals(HEART_CAPTION, stepText(node("column", picture, text("", "d.caption", "caption")), state))
        assertEquals("Полые вены приносят кровь.", stepText(node("column", picture, node("card", text("Полая вена", emphasis = "title"), text("", "d.card"))), state))
        assertNull("the picture card holds no step text", stepText(node("column", picture), state))
        assertNull("a caption with no value", stepText(node("column", picture, text("", "d.missing", "caption")), state))
        val empty = JSONObject().put("d", JSONObject().put("empty", JSONObject.NULL))
        assertNull("a caption with a null value", stepText(node("column", picture, text("", "d.empty", "caption")), empty))
    }

    /** TH2: a stepper of 7 steps shows 7 dots and no label text. Its accessible text holds the label and the count, and next moves the present dot. */
    @Test fun aStepperOfSevenStepsShowsSevenDotsAndNoLabelText() {
        val shell = TemplateShell(stage.fixture, robot, stage.socket)
        shell.live(MotionTalk(AgentScriptProvider(appFilePlan("template/heart")))) { _, model, _ ->
            stage.socket.say(MotionTalk.HEART_REQUEST, "d1", 100)
            shell.finalFile(model, shell.shown(model).id)
            robot.waitUntil(30_000) { dots().size == 7 }
            assertEquals("the present dot", 0, dots().indexOf(true))
            assertFalse("the label line", stageHasText(robot, PART))
            assertTrue("no visible count: ${stageTexts(robot)}", stageTexts(robot).none { COUNT.matches(it) })
            assertTrue("the accessible text of the stepper", stepper("1 из 7"))
            assertTrue("the next button", readStage(robot) { nodes -> click(nodes) { it.description() == "Далее: $PART" } })
            robot.waitUntil(10_000) { dots().indexOf(true) == 1 }
            assertTrue("the accessible count after next", stepper("2 из 7"))
        }
    }

    /**
     * TH3: the pause button of a play scene lies inside the picture card, the chip shows the present speed, three taps bring the first speed
     * back, and the shown screen then has the same motion field as before.
     */
    @Test fun thePlayRowLiesInsideThePictureAndTheChipTurnsTheSpeeds() {
        val shell = TemplateShell(stage.fixture, robot, stage.socket)
        val orbit = appFilePlan("template/orbit")
        shell.live(MotionTalk(AgentScriptProvider(orbit))) { _, model, _ ->
            stage.socket.say(MotionTalk.MOTION_REQUEST, "d1", 100)
            val app = shell.shown(model)
            shell.finalFile(model, app.id)
            fun shown() = checkNotNull(ShownScreen.of(shell.stored(model, app.id)))
            robot.waitUntil(30_000) { picture() != null && named(PAUSE) != null && stageHasText(robot, SPEEDS[1]) }
            val before = shown().motion
            val picture = checkNotNull(picture())
            val pause = checkNotNull(named(PAUSE))
            assertTrue("the pause button $pause is inside the picture $picture", picture.contains(pause))
            assertFalse("the label line of the play row", stageHasText(robot, MOTION))
            assertEquals("only the present speed shows", listOf(SPEEDS[1]), SPEEDS.filter { stageHasText(robot, it) })
            for (next in listOf(SPEEDS[2], SPEEDS[0], SPEEDS[1])) {
                val present = SPEEDS.single { stageHasText(robot, it) }
                // A person taps a control only when it is enabled.
                waitFor({ "the chip of $present is enabled" }) { readStage(robot) { nodes -> nodes.any { node -> robot.hasText(present)(node.config) &&
                    generateSequence(node) { it.parent }.firstOrNull { SemanticsActions.OnClick in it.config }?.let { SemanticsProperties.Disabled !in it.config } == true } } }
                assertTrue("the chip of $present", readStage(robot) { nodes -> click(nodes, robot.hasText(present)) })
                waitFor({ "the chip shows $next, the motion is ${shown().motion}" }) { stageHasText(robot, next) && !stageHasText(robot, present) }
                assertTrue("the chip $next is inside the picture", checkNotNull(picture()).contains(checkNotNull(stageBounds(robot, next))))
            }
            waitFor({ "the motion ${shown().motion} is $before again" }) { shown().motion == before }
        }
    }

    /** Waits 10 s for [check], and names [what] and the texts of the stage when it does not come. */
    private fun waitFor(what: () -> String, check: () -> Boolean) =
        try { robot.waitUntil(10_000) { check() } } catch (failure: AssertionError) { throw AssertionError("${what()}: ${stageTexts(robot)}", failure) }

    /** TH4: a drawing with a rect from x = -150 to x = 450 passes the contract, and the rect shows past the box up to the edges of the card. */
    @Test fun aShapePastTheBoxShowsUpToTheEdgesOfTheCard() {
        val shell = TemplateShell(stage.fixture, robot, stage.socket)
        shell.live(TemplateTalk(AgentScriptProvider(WIDE_WATER))) { scenario, model, _ ->
            shell.screen(model)
            robot.waitUntil(30_000) { picture() != null && box() != null }
            robot.awaitFrames(10)
            val picture = checkNotNull(picture())
            val box = checkNotNull(box())
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val wide = robot.composedBounds(scenario, STAGE).first.width() / density >= 600
            val shot = checkNotNull(robot.automation.takeScreenshot())
            try {
                java.io.File(checkNotNull(context.getExternalFilesDir("large-picture")), "wide-water-${if (wide) "fold" else "phone"}.png").outputStream().use {
                    check(shot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                fun at(x: Int, y: Int) = shot.getPixel(origin[0] + x, origin[1] + y)
                // The water fills the box from y = 160 of 300 down. A wide card has room at the left of the box, and a phone card under it.
                val (outside, inside, tint) = if (wide) {
                    assertTrue("room left of the box $box in $picture", box.left - picture.left >= dp(12))
                    val y = box.top + box.height() * 8 / 10
                    Triple(at(box.left - dp(6), y), at(box.left + dp(6), y), at(box.left - dp(6), box.top + box.height() / 10))
                } else {
                    assertTrue("room under the box $box in $picture", picture.bottom - box.bottom >= dp(12))
                    Triple(at(box.left + dp(6), box.bottom + dp(6)), at(box.left + dp(6), box.bottom - dp(6)), at(box.left + dp(6), box.top - dp(6)))
                }
                val found = "outside #${Integer.toHexString(outside)}, inside #${Integer.toHexString(inside)}, tint #${Integer.toHexString(tint)}, " +
                    "box $box, picture $picture, origin ${origin.toList()}, shot ${shot.width} x ${shot.height}"
                assertTrue("the rect shows past the box: $found", near(outside, inside))
                assertFalse("the water differs from the tint of the card: $found", near(outside, tint))
            } finally { shot.recycle() }
        }
    }

    /** TH6: a chat card with a drawing outside the stage keeps its size: a square of at most 180 dp, the canvas height of a chat card. */
    @Test fun aChatCardWithADrawingKeepsItsSize() = withPlanStep(context, AiProvider.OPENAI, true) {
        stage.fixture.install(chat = TemplateTalk(plans = AgentScriptProvider(SHIP)))
        stage.fixture.launch().use { scenario ->
            val model = checkNotNull(stage.fixture.model)
            robot.waitUntil { !model.loading }
            robot.instrumentation.runOnMainSync { model.editDraft(Draft(TemplateTalk.APP_REQUEST)); model.send(search = false) }
            var canvas: Rect? = null
            var large = false
            robot.waitUntil(60_000) {
                scenario.onActivity { activity ->
                    val nodes = robot.nodes(activity)
                    canvas = nodes.firstOrNull { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(SHIP_TITLE) } == true }?.bounds()
                    large = nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == PICTURE }
                }
                canvas != null
            }
            val bounds = checkNotNull(canvas)
            assertFalse("a chat card has no large picture", large)
            assertTrue("the chat canvas $bounds is a square of 180 dp", abs(bounds.height() - dp(180)) <= dp(1) && abs(bounds.width() - bounds.height()) <= dp(1))
        }
    }

    /**
     * TH8 (AH9): a stream of preview frames (the first line, the control, the parts, then the caption) keeps the height of the picture. Only the
     * complete screen may change it, and then only to a larger picture.
     */
    @Test fun aStreamOfPreviewFramesKeepsThePictureHeight() {
        val lines = SHIP.lines().filter { it.isNotBlank() }
        val frames = (1..lines.size).map { count -> newUi(frame(lines.take(count).joinToString("\n") + "\n")) }
        assertEquals("the stream ends with its caption", "card", frames.last().holds)
        previews { _, model ->
            val heights = frames.map { frame ->
                show(model, frame)
                robot.awaitFrames(5)
                robot.waitUntil(10_000) { picture() != null }
                checkNotNull(picture()).height()
            }
            val streaming = heights.dropLast(1)
            assertTrue("the picture keeps its height while the frames stream: $heights", streaming.all { abs(it - streaming.first()) <= 1 })
            assertTrue("the complete screen does not make the picture smaller: $heights", heights.last() >= streaming.last() - 1)
        }
    }

    /**
     * TH7: the look pictures of complete preview frames. Each file of library-plans in the external files directory gives a complete preview
     * frame, saved as look/<file>-<theme>-<size>-newui.png in the theme of the device. The job sets the theme with adb before the
     * run, pushes the files, and looks at each picture. With no `-e lookNewUi` the test is skipped, so the full suite stays green.
     */
    @Test fun eachLibrarySceneGivesItsNewUiPicture() {
        assumeTrue("no -e lookNewUi", InstrumentationRegistry.getArguments().getString("lookNewUi") != null)
        val files = File(context.getExternalFilesDir(null), "library-plans").listFiles { file -> file.name.endsWith(".plan") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no files in library-plans", files.isNotEmpty())
        val frames = files.map { it.nameWithoutExtension to newUi(frame(it.readText()), holds = "whole") }
        val out = File(context.getExternalFilesDir(null), "look").apply { mkdirs() }
        val theme = if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        val size = if (context.resources.configuration.smallestScreenWidthDp < 600) "phone" else "fold"
        previews { _, model ->
            for ((name, frame) in frames) {
                show(model, frame)
                robot.waitUntil(30_000) { picture() != null }
                robot.awaitFrames(30)
                val shot = checkNotNull(robot.automation.takeScreenshot())
                try {
                    File(out, "$name-$theme-$size-newui.png").outputStream().use { check(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { shot.recycle() }
            }
        }
    }

    /** The voice mode with no request, for the previews that the tests show on its stage. */
    private fun previews(test: (androidx.test.core.app.ActivityScenario<com.alpha.assistant.CloudTestActivity>, ShellViewModel) -> Unit) {
        stage.fixture.install(chat = stage.provider(SHIP), liveVoice = stage.fixture.liveVoice(stage.socket, stage.audio))
        stage.live(test)
    }

    /** The frame of [text] from the kit preview step, made before the shell runs, so no other work holds the transient slot. */
    private fun frame(text: String): StagePreviewFrame = checkNotNull(runBlocking { kitPreview(context, text) }) { "previewStep gave no frame" }

    private fun show(model: ShellViewModel, frame: StagePreviewFrame) =
        robot.instrumentation.runOnMainSync { model.tools.stagePreviews.show(operation, frame) }

    private fun dp(value: Int) = (value * density).roundToInt()

    /** The bounds of the large picture card on the stage (its testTag), or null. */
    private fun picture(): Rect? = readStage(robot) { nodes -> nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == PICTURE }?.bounds() }

    /** The bounds of the box of the drawing: the canvas whose label has the text of the drawing, so its first picture came. */
    private fun box(): Rect? = readStage(robot) { nodes -> nodes.firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(SHIP_TITLE) && WATER in it } == true }?.bounds() }

    /** The bounds of the first node on the stage whose accessible name holds [name], or null. */
    private fun named(name: String): Rect? = readStage(robot) { nodes -> nodes.firstOrNull { it.description()?.contains(name) == true }?.bounds() }

    /** The dots of the stepper on the stage: true for the present dot. */
    private fun dots(): List<Boolean> = readStage(robot) { nodes ->
        nodes.filter { it.config.getOrNull(SemanticsProperties.TestTag) == DOT }.map { it.config.getOrNull(SemanticsProperties.Selected) == true }
    }

    /** True when the stage has the stepper with the accessible name [PART] and the accessible count [count]. */
    private fun stepper(count: String) = readStage(robot) { nodes ->
        nodes.any { it.description() == PART && it.config.getOrNull(SemanticsProperties.StateDescription) == count }
    }

    /** A tap of the nearest node with a click action around the first node that matches. False when no node matches. */
    private fun click(nodes: List<SemanticsNode>, match: (androidx.compose.ui.semantics.SemanticsConfiguration) -> Boolean): Boolean {
        val node = nodes.firstOrNull { match(it.config) } ?: return false
        val target = generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }
        checkNotNull(target.config[SemanticsActions.OnClick].action).invoke()
        return true
    }

    private fun androidx.compose.ui.semantics.SemanticsConfiguration.description() = getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    private fun SemanticsNode.description() = config.description()

    private fun SemanticsNode.bounds(): Rect {
        val at = positionInWindow
        return Rect(at.x.roundToInt(), at.y.roundToInt(), (at.x + size.width).roundToInt(), (at.y + size.height).roundToInt())
    }

    private fun near(a: Int, b: Int) = listOf(16, 8, 0).all { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) <= 6 }

    private companion object {
        const val STAGE = "dialogue_stage"
        const val PICTURE = "stage_picture"
        const val DOT = "stage_step_dot"
        const val PART = "Часть"
        const val PAUSE = "Пауза"
        const val MOTION = "Движение"
        val SPEEDS = listOf("Медленно", "Обычно", "Быстро")
        val COUNT = Regex("""\d+ (из|of) \d+""")
        const val SHIP_TITLE = "Почему корабль не тонет?"
        const val SHIP_CAPTION = "Полый корабль вытесняет достаточно воды, чтобы удержаться на плаву."
        const val HEART_CAPTION = "Полая вена приносит в сердце кровь от тела."
        const val LONG_CAPTION = "Полый корабль вытесняет много воды, и вода давит на его дно снизу вверх сильнее, чем весит сам корабль."
        const val CHOICE = "Предмет"
        const val WATER = "Вода"
        val SHIP: String by lazy { appFilePlan("template/ship") }
        /** A ship scene with no card whose water reaches 150 units past the box at each side (the background of AK5). */
        val WIDE_WATER = """
            scene("$SHIP_TITLE", lang="ru", palette="blue")
            control = choice("Предмет", ["Корабль", "Гвоздь"])
            s1 = rect(-150, 160, 600, 300, "water", 0)
            s2 = polygon([45, 75, 255, 75, 225, 140, 75, 140], "ink", visible=${'$'}/v == "Корабль")
            s3 = text(150, 30, "$WATER", "ink", 14)
        """.trimIndent() + "\n"
    }
}

/**
 * [frame] of the kit with [holds] in place of its holds value, and with [caption] as the text of its caption (the text with the emphasis
 * `caption`, stage A, K1) when one is given. The caption of a walk is bound to the sentence of the step: [caption] replaces the bound text.
 */
internal fun newUi(frame: StagePreviewFrame, caption: String? = null, holds: String = frame.holds): StagePreviewFrame {
    val ui = JSONObject(frame.ui)
    fun nodes(node: JSONObject): List<JSONObject> =
        listOf(node) + node.optJSONArray("children")?.let { children -> List(children.length(), children::getJSONObject).flatMap(::nodes) }.orEmpty()
    if (caption != null) checkNotNull(nodes(ui).lastOrNull { it.optString("type") == "text" && it.optJSONObject("style")?.optString("emphasis") == "caption" }) {
        "the frame of ${frame.spec.title} has no caption"
    }.apply { put("text", caption); remove("bind") }
    return frame.copy(spec = ToolSpec.parse(JSONObject(frame.spec.json).put("ui", ui).toString()), ui = ui.toString(), holds = holds)
}
