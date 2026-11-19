package com.alpha.assistant.tools

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.view.WindowCompat
import com.alpha.assistant.R
import kotlin.math.abs
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.alpha.assistant.ui.AlphaDarkColors
import com.alpha.assistant.ui.AlphaLightColors
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Failure case: every generated app was gray. The shell theme is graphite, and the theme presets only moved
 * the primary color between grays, so buttons, switches, and backgrounds had no color.
 */
class NativePaletteWorkflowTest {
    private val robot = ShellRobot("native-palette")

    private fun app(theme: String, vararg children: String) =
        """{"type":"theme",$theme,"children":[{"type":"column","style":{"gap":"medium","padding":"large"},"children":[${children.joinToString(",")}]}]}"""

    private fun hsl(color: Int) = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }

    /** Renders the apps one below the other, and returns a screenshot. */
    private fun render(vararg uis: String, check: (Bitmap) -> Unit) =
        robot.renderApps("palette", uis.toList(), "{}", Regex("\"text\":\"([^\"]+)\"").findAll(uis.last()).last().groupValues[1], check = check)

    /** A pixel inside the left part of a control, away from its label. */
    // The bounds can be those of the label inside a control, so the samples use the screen edges.
    private fun Bitmap.fill(label: String): Int = getPixel(width - 82, robot.bounds(label).centerY())

    /** A pixel of the app background, in the left padding beside a control. */
    private fun Bitmap.backgroundBeside(label: String): Int = getPixel(8, robot.bounds(label).centerY())

    @Test fun aPaletteColorsTheAppBackgroundAndItsFilledButton() {
        render(app(""""preset":"soft","palette":"green"""", """{"type":"button","text":"Log water","action":"log"}""")) { shot ->
            val button = hsl(shot.fill("Log water"))
            assertTrue("button ${button.toList()}", button[1] > 0.3f && button[0] in 80f..160f)
            val background = shot.backgroundBeside("Log water")
            assertTrue("background ${Integer.toHexString(background)}", ColorUtils.calculateLuminance(background) > 0.8 &&
                Color.green(background) > Color.red(background) && Color.green(background) > Color.blue(background))
        }
    }

    @Test fun aThemeWithoutAPaletteStillHasAColor() {
        render(app(""""preset":"clean"""", """{"type":"button","text":"Convert","action":"go"}""")) { shot ->
            val button = hsl(shot.fill("Convert"))
            assertTrue("button ${button.toList()}", button[1] > 0.3f)
        }
    }

    @Test fun successAndWarningStayGreenAndAmberInARedPalette() {
        render(app(""""preset":"technical","palette":"red"""",
            """{"type":"badge","text":"Saved","style":{"tone":"success"}}""",
            """{"type":"badge","text":"Over budget","style":{"tone":"warning"}}""")) { shot ->
            val success = hsl(shot.fill("Saved"))
            val warning = hsl(shot.fill("Over budget"))
            assertTrue("success ${success.toList()}", success[0] in 80f..160f)
            assertTrue("warning ${warning.toList()}", warning[0] in 25f..60f)
        }
    }

    @Test fun darkDrawsADarkAppInALightShell() {
        render(app(""""preset":"expressive","palette":"indigo","dark":true""", """{"type":"button","text":"Spin","action":"spin"}""")) { shot ->
            val background = shot.backgroundBeside("Spin")
            assertTrue("background ${Integer.toHexString(background)}", ColorUtils.calculateLuminance(background) < 0.1)
        }
    }

    @Test fun theCatalogAcceptsKnownPalettesAndTheGuideNamesThem() {
        fun validate(theme: String): String? {
            var diagnostic: ToolUiDiagnostic? = null
            val trace = ToolUiValidationTrace(ToolUiValidationStage.DEFINITION) { diagnostic = it }
            return runCatching { ToolUiCatalog.validate(JSONObject(app(theme, """{"type":"text","text":"Hi"}""")), JSONObject(), JSONObject(), trace) }
                .exceptionOrNull()?.let { "$it $diagnostic" }
        }
        for (name in ToolPalettes.names) assertEquals(name, null, validate(""""preset":"soft","palette":"$name","dark":false"""))
        assertEquals(null, validate(""""preset":"soft""""))
        assertTrue(validate(""""preset":"soft","dark":"yes"""") != null)
        assertTrue(validate(""""preset":"soft","palette":"neon"""") != null)
        val guidance = ToolUiCatalog.modelGuidance()
        assertTrue(ToolPalettes.names.all { it in guidance })
    }

    /** Opens a saved app with this theme in the full view. The check gets a screenshot and the status bar icon mode. */
    private fun openFullView(theme: String, check: (shot: Bitmap, lightIcons: Boolean) -> Unit) {
        val fixture = ShellFixture("native-palette-full")
        val ui = app(theme, """{"type":"button","text":"Log water","action":"log"}""")
        val spec = ToolSpec.parsePersisted("""{"formatVersion":3,"title":"Water log","description":"Palette fixture","capabilities":[],
            "stateSchema":{"count":"number"},"initialState":{"count":0},"ui":$ui,
            "source":"if(input.event.actionId==='log') input.state.count++; return {state:input.state};"}""")
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use {
                it.commitTool(ToolRecord("water", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true), null, revision = true)
            }
            fixture.install()
            fixture.launch().use { scenario ->
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading && !model.tools.busy }
                robot.instrumentation.runOnMainSync { model.openSurface("water") }
                robot.waitFor("Log water")
                robot.awaitFrames(3)
                var lightIcons = false
                scenario.onActivity { lightIcons = !WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars }
                val shot = checkNotNull(robot.automation.takeScreenshot())
                try {
                    val output = fixture.context.getExternalFilesDir("palette")
                    File(output, "full-view-${theme.hashCode()}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    check(shot, lightIcons)
                } finally { shot.recycle() }
            }
        } finally { fixture.remove() }
    }

    private fun near(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) <= 2 && abs(Color.green(a) - Color.green(b)) <= 2 && abs(Color.blue(a) - Color.blue(b)) <= 2

    /**
     * Failure case: the full view drew the palette only inside the app. The status bar, the top bar with the
     * menu, and the side margins stayed the light gray shell color.
     */
    @Test fun theFullViewPaintsTheStatusBarTopBarAndMarginsWithTheAppBackground() {
        openFullView(""""preset":"soft","palette":"green"""") { shot, _ ->
            val night = robot.instrumentation.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val expected = checkNotNull(ToolPalettes.scheme("green", night == Configuration.UI_MODE_NIGHT_YES)).background.toArgb()
            val topBar = robot.bounds(robot.text(R.string.tool_refine)).centerY()
            for ((place, pixel) in listOf("status bar" to shot.getPixel(shot.width / 4, 4), "top bar" to shot.getPixel(8, topBar),
                    "margin" to shot.backgroundBeside("Log water")))
                assertTrue("$place ${Integer.toHexString(pixel)}, app ${Integer.toHexString(expected)}", near(pixel, expected))
        }
    }

    @Test fun aDarkAppInALightShellGetsLightStatusBarIcons() {
        openFullView(""""preset":"expressive","palette":"indigo","dark":true""") { shot, lightIcons ->
            val statusBar = shot.getPixel(shot.width / 4, 4)
            assertTrue("status bar ${Integer.toHexString(statusBar)}", ColorUtils.calculateLuminance(statusBar) < 0.1)
            assertTrue("dark status bar icons on a dark app", lightIcons)
        }
    }

    @Test fun everyPaletteKeepsItsTextReadable() {
        fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
            ColorUtils.calculateContrast(a.toArgb(), b.toArgb())
        for (name in ToolPalettes.names - ToolPalettes.GRAPHITE) for (dark in listOf(false, true)) {
            val s = checkNotNull(ToolPalettes.scheme(name, dark))
            for ((role, pair) in mapOf("onPrimary" to (s.onPrimary to s.primary), "onPrimaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "onSurface" to (s.onSurface to s.surface), "onSurfaceVariant" to (s.onSurfaceVariant to s.surfaceContainer),
                "onSecondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer)))
                assertTrue("$name dark=$dark $role", contrast(pair.first, pair.second) >= 4.5)
            assertEquals("$name dark=$dark background", dark, ColorUtils.calculateLuminance(s.background.toArgb()) < 0.5)
        }
    }

    /**
     * Failure case (L1 item 6): a box in the surface color stayed white in a dark theme, and a text in surface on a light ink was white on pink.
     * On each canvas of each palette and theme, the surface role of a drawing follows the theme, and each ink reads on it. The kit draws a text
     * on a muted shape in the default ink, which reads on that shape too: SurfaceDrawing draws it as the muted ink at MUTED_SHAPE_ALPHA.
     */
    @Test fun eachDrawingInkReadsOnTheSurfaceRoleAndTheDefaultInkOnAMutedShape() {
        val low = mutableListOf<String>()
        for (name in ToolPalettes.names) for (dark in listOf(false, true)) {
            val s = if (name == ToolPalettes.GRAPHITE) if (dark) AlphaDarkColors else AlphaLightColors else checkNotNull(ToolPalettes.scheme(name, dark))
            // The canvas takes the background of the app, a card, a hero, or a role surface (NativeToolUi.ToolRoleSurface).
            val canvases = mapOf("background" to s.background, "surface" to s.surface, "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh, "secondaryContainer" to s.secondaryContainer, "primaryContainer" to s.primaryContainer,
                "surfaceVariant" to s.surfaceVariant, "errorContainer" to s.errorContainer,
                "successContainer" to checkNotNull(ToolPalettes.status("success", dark)).second, "warningContainer" to checkNotNull(ToolPalettes.status("warning", dark)).second)
            for ((canvas, background) in canvases) {
                val roles = s.drawingRoles(background)
                val surface = roles.getValue("surface")
                if ((ColorUtils.calculateLuminance(surface) < 0.5) != dark) low += "$name dark=$dark $canvas: surface ${Integer.toHexString(surface)}"
                val muted = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(roles.getValue("muted"), MUTED_SHAPE_ALPHA), background.toArgb())
                if (ColorUtils.calculateContrast(roles.getValue("default"), muted) < 4.5) low += "$name dark=$dark $canvas: default on muted"
                for (tone in ToolUiCatalog.tones)
                    if (ColorUtils.calculateContrast(roles.getValue(tone), surface) < 4.5) low += "$name dark=$dark $canvas: $tone on surface"
            }
        }
        assertEquals(emptyList<String>(), low)
    }
}
