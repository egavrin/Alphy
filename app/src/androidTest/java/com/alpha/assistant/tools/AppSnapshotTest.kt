package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.images.GeneratedImage
import com.alpha.assistant.images.ImageAspect
import com.alpha.assistant.tools.agent.AppSnapshot
import com.alpha.assistant.tools.three.ThreeSceneAssetCleanup
import com.alpha.assistant.tools.three.ThreeSceneAssets
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The screenshot review needs a picture of a generated app while the user may be on another screen,
 * so the host draws the app on a private display without an activity.
 */
class AppSnapshotTest {
    @Test fun theHostDrawsAGeneratedAppWithoutAnActivity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ui = JSONObject("""{"type":"theme","preset":"soft","palette":"green","children":[{"type":"column","style":{"gap":"medium","padding":"large"},
            "children":[{"type":"metric","text":"Water","bind":"water","style":{"emphasis":"number"}},{"type":"button","text":"Log a glass","action":"log"}]}]}""")
        val spec = ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", "Water").put("description", "")
            .put("stateSchema", JSONObject().put("water", "string")).put("initialState", JSONObject().put("water", "1.25 L"))
            .put("ui", ui).put("source", "return {state:input.state};").put("capabilities", org.json.JSONArray()).toString())
        val shot = AppSnapshot.capture(context, ToolPreview(spec, """{"water":"1.25 L"}""", ui.toString()))
        try {
            assertEquals(AppSnapshot.WIDTH_DP, (shot.width / context.resources.displayMetrics.density).toInt())
            val colored = (0 until shot.height step 8).sumOf { y ->
                (0 until shot.width step 8).count { x ->
                    FloatArray(3).also { ColorUtils.colorToHSL(shot.getPixel(x, y), it) }.let { it[1] > 0.4f && it[2] in 0.15f..0.6f }
                }
            }
            assertTrue("the green button is drawn: $colored samples", colored > 20)
        } finally { shot.recycle() }
    }

    // Failure case: the review picture follows the Android font size, so the model judges a screen that the person does not see.
    @Test fun theSnapshotKeepsFontScaleOneAtAnotherSystemFontScale() = runBlocking {
        val robot = ShellRobot("app-snapshot")
        // The screenshot review gets the application context (SandboxClient).
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val ui = JSONObject().put("type", "column").put("children", org.json.JSONArray((1..8).map {
            JSONObject().put("type", "text").put("text", "Строка $it: вода за день, восемь стаканов и прогулка.")
        }))
        val spec = ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", "Text").put("description", "")
            .put("stateSchema", JSONObject().put("note", "string")).put("initialState", JSONObject().put("note", "")).put("ui", ui)
            .put("source", "return {state:input.state};").put("capabilities", org.json.JSONArray()).toString())
        // The lowest row that differs from the bottom-right pixel: the end of the text, or of its surface.
        suspend fun contentBottom(systemScale: String): Int {
            robot.shell("settings put system font_scale $systemScale")
            robot.waitUntil { context.resources.configuration.fontScale == systemScale.toFloat() }
            val shot = AppSnapshot.capture(context, ToolPreview(spec, """{"note":""}""", ui.toString()))
            try {
                val background = shot.getPixel(shot.width - 1, shot.height - 1)
                return (shot.height - 1 downTo 0).first { y -> (0 until shot.width).any { x -> shot.getPixel(x, y) != background } }
            } finally { shot.recycle() }
        }
        val scale = robot.shell("settings get system font_scale")
        try {
            assertEquals("Content bottom at system font scale 1.3 and 1.0", contentBottom("1.0"), contentBottom("1.3"))
        } finally {
            // "settings get" prints "null" when the value is unset, and "settings put … null" does not restore that state.
            robot.shell(if (scale.toFloatOrNull() == null) "settings delete system font_scale" else "settings put system font_scale $scale")
            robot.waitUntil { context.resources.configuration.fontScale == (scale.toFloatOrNull() ?: 1f) }
        }
    }

    @Test fun theSnapshotShowsTheGeneratedPicture() = runBlocking {
        // The review asks the model to judge pictures, so a placeholder in their place would mislead it.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val png = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(Color.MAGENTA)
                ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            } finally { bitmap.recycle() }
        }
        ThreeSceneAssetCleanup(context).lease().use {
            val asset = ThreeSceneAssets(context).importGenerated(png, "hero", "image/png")
            val image = GeneratedImage("hero", "Hero", ImageAspect.SQUARE, asset, 512, 512, "fixture")
            val ui = JSONObject("""{"type":"theme","preset":"soft","palette":"green","children":[{"type":"column","children":[
                {"type":"image","assetSlot":"hero","text":"Hero"},{"type":"text","text":"Below"}]}]}""")
            val spec = ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", "Picture").put("description", "")
                .put("stateSchema", JSONObject().put("note", "string")).put("initialState", JSONObject().put("note", "")).put("ui", ui)
                .put("source", "return {state:input.state};").put("capabilities", org.json.JSONArray()).toString())
                .withGeneratedImages(listOf(image))
            val shot = AppSnapshot.capture(context, ToolPreview(spec, """{"note":""}""", ui.toString()))
            try {
                val magenta = (0 until shot.height step 8).sumOf { y ->
                    (0 until shot.width step 8).count { x -> shot.getPixel(x, y) == Color.MAGENTA }
                }
                assertTrue("the picture is drawn: $magenta samples", magenta > 200)
            } finally { shot.recycle() }
        }
    }
}
