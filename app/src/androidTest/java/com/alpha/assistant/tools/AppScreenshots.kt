package com.alpha.assistant.tools

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.allViews
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.Test
import java.io.File

/**
 * Draws app UIs one below the other in the shell theme and gives a screenshot to the check.
 * The screenshot also goes to files/[folder]/<test method>.png on the device.
 */
internal fun ShellRobot.renderApps(folder: String, uis: List<String>, state: String, last: String, fontScale: Float = 1f,
    heading: String? = null, event: (String, Any?) -> Unit = { _, _ -> }, check: (Bitmap) -> Unit) {
    val test = Thread.currentThread().stackTrace.first { frame ->
        runCatching { Class.forName(frame.className).getMethod(frame.methodName).isAnnotationPresent(Test::class.java) }.getOrDefault(false)
    }.methodName
    val fixture = ShellFixture(folder)
    try {
        fixture.install()
        fixture.launch().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AlphaTheme {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
                            Column(Modifier.safeDrawingPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                uis.forEach { ui -> ToolContent(ui, state, enabled = true, heading = heading, appLanguage = "en", event = event) }
                            }
                        }
                    }
                }
            }
            waitFor(last)
            awaitFrames(3)
            val screenshot = checkNotNull(automation.takeScreenshot())
            try {
                File(fixture.context.getExternalFilesDir(folder), "$test.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                check(screenshot)
            } finally { screenshot.recycle() }
        }
    } finally { fixture.remove() }
}

/** True when the pixel at [x], [y] differs in luminance from [background] by more than 0.15. */
internal fun Bitmap.ink(x: Int, y: Int, background: Int) =
    kotlin.math.abs(androidx.core.graphics.ColorUtils.calculateLuminance(getPixel(x, y)) - androidx.core.graphics.ColorUtils.calculateLuminance(background)) > 0.15

/** Asserts that two screenshot colors differ by at most 3 in each of red, green, and blue. */
internal fun assertSameColor(message: String, actual: Int, expected: Int) {
    for (shift in listOf(16, 8, 0)) org.junit.Assert.assertTrue("$message: ${Integer.toHexString(actual)} against ${Integer.toHexString(expected)}",
        kotlin.math.abs((actual shr shift and 0xFF) - (expected shr shift and 0xFF)) <= 3)
}

/** Closes the shell model of [this], then removes the fixture and its course and memory databases. */
internal fun ShellFixture.removeWithModel() {
    model?.let { model ->
        runBlocking { model.tools.closeAndJoin() }
        model.courseStore.close()
        model.memoryStore.close()
    }
    remove()
    context.deleteDatabase("$name.db-courses.db")
    context.deleteDatabase("$name.db-memory.db")
}

/** The validation failure of a native UI with its diagnostic, or null when the UI is valid. */
internal fun validateNativeUi(ui: JSONObject, fields: JSONObject = JSONObject(), state: JSONObject = JSONObject()): String? {
    var diagnostic: ToolUiDiagnostic? = null
    val trace = ToolUiValidationTrace(ToolUiValidationStage.DEFINITION) { diagnostic = it }
    return runCatching { ToolUiCatalog.validate(ui, fields, state, trace) }.exceptionOrNull()?.let { "$it $diagnostic" }
}

/** A themed app root with one padded column of [children]. */
internal fun themedColumn(vararg children: JSONObject, preset: String = "soft", palette: String = "blue") =
    JSONObject().put("type", "theme").put("preset", preset).put("palette", palette)
        .put("children", JSONArray().put(JSONObject().put("type", "column").put("style", JSONObject("""{"gap":"medium","padding":"medium"}"""))
            .put("children", JSONArray(children.toList()))))

/** The HSL saturation of a screenshot pixel. */
internal fun colorSaturation(color: Int) = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(color, it) }[1]

/** The text layout of the composed text [label]. */
internal fun textLayout(scenario: ActivityScenario<CloudTestActivity>, label: String): TextLayoutResult {
    lateinit var result: TextLayoutResult
    scenario.onActivity { activity ->
        val owner = activity.window.decorView.allViews.filterIsInstance<ViewRootForTest>().single().semanticsOwner
        val node = owner.getAllSemanticsNodes(mergingEnabled = false).first {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
        }
        val layout = mutableListOf<TextLayoutResult>()
        assertTrue(checkNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layout))
        result = layout.single()
    }
    return result
}

/** True when each line break of [layout] falls between words. */
internal fun wholeWords(layout: TextLayoutResult): Boolean {
    val text = layout.layoutInput.text.text
    return (0 until layout.lineCount - 1).all { line ->
        val end = layout.getLineEnd(line)
        text[end - 1].isWhitespace() || text[end - 1] == '-' || text[end].isWhitespace()
    }
}
