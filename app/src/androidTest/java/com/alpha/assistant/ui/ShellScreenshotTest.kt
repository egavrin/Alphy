package com.alpha.assistant.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Saves system screenshots and the node list for each screen. */
class ShellScreenshotTest {
    private val fixture = ShellFixture("screens")
    private val robot = ShellRobot("screens")
    private val title = "План поездки в Казань"
    private val message = "Что посмотреть за два дня?"
    @After fun remove() = fixture.remove()

    @Test fun captureShellScreens() {
        val night = checkNotNull(Regex("""Night mode: (\S+)""").find(robot.shell("cmd uimode night"))).groupValues[1]
        val nightBits = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val scale = robot.shell("settings get system font_scale")
        try {
            val root = checkNotNull(fixture.context.getExternalFilesDir("alphy-ui-screens")).apply { check(deleteRecursively()) }
            fixture.install()
            // A fixed recent date, so the Search row does not show the 1970 default of the fixture.
            fixture.seedChat("trip", title, message, updatedAt = 1_793_000_000_000L)
            for ((mode, bits) in listOf("no" to Configuration.UI_MODE_NIGHT_NO, "yes" to Configuration.UI_MODE_NIGHT_YES)) {
                setNight(mode, bits)
                for (size in listOf("1.0", "2.0")) {
                    setScale(size)
                    fixture.launch().use { scenario ->
                        scenario.onActivity {
                            assertEquals(size.toFloat(), it.resources.configuration.fontScale)
                            assertEquals(bits, it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                        }
                        captureScreens(scenario, File(root, "$mode-$size").apply { mkdirs() }, dark = bits == Configuration.UI_MODE_NIGHT_YES)
                    }
                }
            }
        } finally {
            try { setNight(night, nightBits) } finally { setScale(scale) }
            assertEquals(scale, robot.shell("settings get system font_scale"))
        }
    }

    @Test fun captureDrawerAndChatIcons() {
        val night = checkNotNull(Regex("""Night mode: (\S+)""").find(robot.shell("cmd uimode night"))).groupValues[1]
        val nightBits = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val scale = robot.shell("settings get system font_scale")
        val root = checkNotNull(fixture.context.getExternalFilesDir("alphy-drawer-screens"))
        fixture.install()
        fixture.store.saveSetting("userName", "Евгений")
        fixture.seedChat("trip", title, message, selected = true)
        listOf("Возможности Android", "Приложение для расчета процентов", "Идеи для автоматизаций",
            "Погода в Москве", "График индекса Мосбиржи", "План на выходные").forEachIndexed { index, recent ->
            fixture.seedChat("recent-$index", recent, "Пример разговора", updatedAt = index + 2L)
        }
        try {
            for ((mode, bits) in listOf("no" to Configuration.UI_MODE_NIGHT_NO, "yes" to Configuration.UI_MODE_NIGHT_YES)) {
                setNight(mode, bits)
                for (size in listOf("1.0", "2.0")) {
                    setScale(size)
                    fixture.launch().use { scenario ->
                        val directory = File(root, "$mode-$size").apply { mkdirs() }
                        robot.waitFor(message)
                        robot.assertTarget(robot.text(R.string.new_chat))
                        screen(directory, "chat").recycle()
                        robot.openDrawer(scenario)
                        robot.assertTarget(robot.text(R.string.search))
                        val newChat = robot.assertTarget(robot.text(R.string.new_chat))
                        val settings = robot.assertTarget(robot.text(R.string.settings))
                        scenario.onActivity { activity ->
                            val width = activity.window.decorView.width
                            val density = activity.resources.displayMetrics.density
                            val expected = (width * 0.75f).coerceIn(360 * density, 560 * density)
                                .coerceAtMost(width - 56 * density)
                            assertTrue("Drawer width for $width px is $expected px", abs(robot.drawerRight(activity) - expected) <= 2f)
                            if (width <= 320 * density && size == "2.0") {
                                assertTrue("Large labels have separate rows at 320 dp", newChat.bottom <= settings.top)
                            }
                        }
                        val drawer = screen(directory, "drawer")
                        try {
                            val expectedSurface = if (mode == "yes") 0xFF1D1D1D.toInt() else Color.WHITE
                            val inset = (4 * fixture.context.resources.displayMetrics.density).roundToInt()
                            assertEquals("Graphite drawer surface", expectedSurface, drawer.getPixel(inset, drawer.height / 2))
                        } finally { drawer.recycle() }
                    }
                }
            }
        } finally {
            try { setNight(night, nightBits) } finally { setScale(scale) }
        }
    }

    private fun captureScreens(scenario: ActivityScenario<*>, directory: File, dark: Boolean) {
        robot.waitFor(robot.text(R.string.desktop_prompt))
        val desktop = screen(directory, "desktop")
        assertNotEquals("Desktop center pixel", Color.BLACK, center(desktop))
        // The Desktop draws no background, so its side padding shows colorScheme.background, the color of the shell Surface (AlphaShell).
        val expected = alphaColorScheme(dark).background.toArgb()
        val gutter = desktop.getPixel((12 * fixture.context.resources.displayMetrics.density).roundToInt(), desktop.height / 2)
        assertEquals("Desktop background pixel", Integer.toHexString(expected), Integer.toHexString(gutter))
        robot.openDrawer(scenario)
        screen(directory, "drawer")
        robot.click(title)
        robot.awaitDrawerClosed(scenario)
        robot.waitFor(message)
        screen(directory, "chat")
        open(scenario, R.string.search)
        robot.waitFor(robot.text(R.string.search_field))
        robot.waitUntil { robot.keyboardTop(scenario) != null }
        screen(directory, "search")
        robot.edit(robot.text(R.string.search_field), "посмотреть")
        robot.waitFor(robot.text(R.string.search_results))
        screen(directory, "search-results")
        robot.click(robot.text(R.string.search_back))
        captureSettings(scenario, directory)
    }

    @Test fun captureAdaptiveSettings() {
        val directory = checkNotNull(fixture.context.getExternalFilesDir("alphy-settings-screens"))
        fixture.install()
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.desktop_prompt))
            captureSettings(scenario, directory)
        }
    }

    @Test fun destinationColorsFollowTheShellInBothModes() {
        val night = checkNotNull(Regex("""Night mode: (\S+)""").find(robot.shell("cmd uimode night"))).groupValues[1]
        val nightBits = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val root = checkNotNull(fixture.context.getExternalFilesDir("alphy-theme-screens"))
        val failures = mutableListOf<String>()
        try {
            fixture.install()
            SavedCourseShellWorkflowTest.seedCourse(fixture)
            val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Theme fixture","description":"","capabilities":[],
                "stateSchema":{"value":"string"},"initialState":{"value":""},"ui":{"type":"text","text":"Theme fixture content"},
                "source":"return {state:input.state};"}""")
            ToolStore(fixture.context, "${fixture.store.databaseName}-tools.db").use { store ->
                store.commitTool(ToolRecord("theme-fixture", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat", true), null, true)
            }
            for ((mode, bits) in listOf("no" to Configuration.UI_MODE_NIGHT_NO, "yes" to Configuration.UI_MODE_NIGHT_YES)) {
                setNight(mode, bits)
                val dir = File(root, mode).apply { mkdirs() }
                fixture.launch().use { scenario ->
                    scenario.onActivity { assertEquals(bits, it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) }
                    val colors = alphaColorScheme(mode == "yes")
                    fun capture(name: String, vararg roles: Pair<String, Int>, bounds: Rect? = null) {
                        val image = screen(dir, name)
                        try { checkColors(image, "$mode/$name", roles, failures, bounds) } finally { image.recycle() }
                    }
                    val background = "background" to colors.background.toArgb()
                    val surface = "surface" to colors.surface.toArgb()
                    val primary = "primary" to colors.primary.toArgb()
                    robot.waitFor(robot.text(R.string.desktop_prompt))
                    capture("desktop", background)
                    open(scenario, R.string.course_learning)
                    robot.click(SavedCourseShellWorkflowTest.COURSE_TITLE)
                    robot.click(robot.text(R.string.course_open))
                    robot.waitFor(SavedCourseShellWorkflowTest.SLIDE_TEXT)
                    capture("learning-player", background, primary)
                    robot.back()
                    robot.waitFor(robot.text(R.string.course_outline))
                    open(scenario, R.string.automations)
                    robot.waitFor(robot.text(R.string.automation_empty_title))
                    capture("automations", surface, primary)
                    open(scenario, R.string.mini_apps)
                    robot.waitFor("Theme fixture")
                    capture("mini-app-library", surface, primary, bounds = robot.composedBounds(scenario, "mini-app-library").first)
                    robot.click("Theme fixture")
                    robot.waitFor("Theme fixture content")
                    capture("mini-app-host", surface, bounds = robot.composedBounds(scenario, "mini-app").first)
                    robot.click(robot.text(R.string.tool_options))
                    robot.click(robot.text(R.string.tool_versions))
                    robot.waitFor(robot.text(R.string.tool_version_current))
                    capture("mini-app-dialog", "surfaceContainerHigh" to colors.surfaceContainerHigh.toArgb())
                    robot.click(robot.text(R.string.tool_dialog_close))
                    robot.click(robot.text(R.string.tool_library_back))
                    robot.waitFor(robot.text(R.string.tool_search))
                    open(scenario, R.string.search)
                    robot.waitFor(robot.text(R.string.search_field))
                    capture("search", background)
                    robot.click(robot.text(R.string.search_back))
                    open(scenario, R.string.settings)
                    robot.waitUntil { robot.composed(scenario, "settings-menu") }
                    val settings = screen(dir, "settings")
                    try {
                        val menuColor = if (robot.composed(scenario, "settings-detail")) {
                            "surfaceContainerLow" to colors.surfaceContainerLow.toArgb()
                        } else background
                        checkColors(settings, "$mode/settings", arrayOf(menuColor), failures,
                            robot.composedBounds(scenario, "settings-menu").first)
                    } finally { settings.recycle() }
                    robot.back()
                    robot.waitFor(robot.text(R.string.desktop_prompt))
                    robot.waitUntil { !robot.composed(scenario, "settings-pane") }
                    capture("returned-desktop", background)
                }
            }
        } finally {
            try { setNight(night, nightBits) } finally { fixture.context.deleteDatabase(SavedCourseShellWorkflowTest.databaseName(fixture)) }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun checkColors(
        image: Bitmap, screen: String, roles: Array<out Pair<String, Int>>, failures: MutableList<String>, bounds: Rect?,
    ) {
        val region = bounds ?: Rect(0, 0, image.width, image.height)
        val pixels = IntArray(region.width() * region.height())
        image.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        for ((role, color) in roles) {
            val count = pixels.count { it == color }
            val result = "$screen $role #${Integer.toHexString(color)}: $count pixels"
            Log.i("AlphyThemeWorkflow", result)
            if (count < 32) failures += result
        }
    }

    private fun captureSettings(scenario: ActivityScenario<*>, directory: File) {
        open(scenario, R.string.settings)
        robot.waitUntil { robot.composed(scenario, "settings-menu") }
        val split = robot.composed(scenario, "settings-detail")
        InstrumentationRegistry.getArguments().getString("settingsLayout")?.let {
            assertEquals("The device uses the expected Settings layout", it == "split", split)
        }
        if (split) {
            val rail = robot.composedBounds(scenario, "settings-menu").first
            val detail = robot.composedBounds(scenario, "settings-detail").first
            assertTrue("Settings rail and detail must not overlap", rail.right <= detail.left)
        }
        robot.reveal(robot.text(R.string.settings_name_label), forward = null)
        captureSettingsPage(scenario, directory, "settings")
        for ((section, target, name) in listOf(
            Triple(R.string.settings_deepseek, R.string.key_ready, "deepseek"),
            Triple(R.string.settings_voice, R.string.voice_start, "voice"),
            Triple(R.string.settings_appearance, R.string.settings_theme, "appearance"),
            Triple(R.string.access, R.string.access_intro, "access"),
            Triple(R.string.memory_title, R.string.memory_empty, "memory"),
            Triple(R.string.update_title, R.string.update_check, "updates"),
        )) {
            val label = robot.text(section)
            robot.reveal(label, forward = null)
            robot.click(label)
            robot.reveal(robot.text(target), forward = null)
            assertEquals(split, robot.composed(scenario, "settings-menu"))
            captureSettingsPage(scenario, directory, name)
            if (section == R.string.access) {
                robot.reveal(robot.text(R.string.access_system_settings))
                captureSettingsPage(scenario, directory, "access-end")
            }
            if (section == R.string.memory_title) {
                robot.reveal(robot.text(R.string.memory_add))
                robot.click(robot.text(R.string.memory_add))
                robot.waitFor(robot.text(R.string.memory_text))
                captureSettingsPage(scenario, directory, "memory-editor")
                robot.reveal(robot.text(R.string.memory_cancel), forward = null)
                robot.click(robot.text(R.string.memory_cancel))
                robot.waitFor(robot.text(R.string.memory_empty))
            }
            if (!split) {
                robot.back()
                robot.waitUntil {
                    robot.composed(scenario, "settings-menu") && !robot.composed(scenario, "settings-detail")
                }
            }
        }
        if (split) {
            robot.back()
            robot.waitFor(robot.text(R.string.key_ready))
        }
        robot.back()
        robot.waitUntil { !robot.composed(scenario, "settings-pane") }
        assertFalse(robot.composed(scenario, "settings-menu"))
    }

    private fun captureSettingsPage(scenario: ActivityScenario<*>, directory: File, name: String) {
        val image = screen(directory, name)
        try {
            val pane = robot.composedBounds(scenario, "settings-pane").first
            val sample = IntArray(pane.width())
            val colors = mutableSetOf<Int>()
            for (y in pane.top until pane.bottom step 4) {
                image.getPixels(sample, 0, pane.width(), pane.left, y, pane.width(), 1)
                sample.toCollection(colors)
                if (colors.size >= 16) break
            }
            assertTrue("$name has ${colors.size} colors", colors.size >= 16)
        } finally { image.recycle() }
    }

    private fun open(scenario: ActivityScenario<*>, @StringRes row: Int) {
        robot.openDrawer(scenario)
        robot.click(robot.text(row))
        robot.awaitDrawerClosed(scenario)
    }

    private fun screen(directory: File, name: String): Bitmap =
        settled { checkNotNull(robot.automation.takeScreenshot()) }.also { save(directory, name, it) }

    private fun save(directory: File, name: String, image: Bitmap) {
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.txt").writeText(robot.texts().joinToString("\n", postfix = "\n"))
    }

    private fun center(image: Bitmap): Int = image.getPixel(image.width / 2, image.height / 2)

    // A capture during an animation, such as the 700 ms route fade, can mix two states. Two equal images 100 ms apart show a settled screen.
    private fun settled(take: () -> Bitmap): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 3000
        var last = take()
        while (true) {
            SystemClock.sleep(100)
            val next = take()
            val same = next.sameAs(last)
            last.recycle()
            if (same) return next
            assertTrue("The screen did not settle within 3 seconds", SystemClock.uptimeMillis() < deadline)
            last = next
        }
    }

    private fun setNight(mode: String, bits: Int) {
        robot.shell("cmd uimode night $mode")
        robot.waitUntil { (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == bits }
    }

    // "settings get" prints "null" when the value is unset, and "settings put … null" does not restore that state.
    private fun setScale(value: String) {
        robot.shell(if (value.toFloatOrNull() == null) "settings delete system font_scale" else "settings put system font_scale $value")
        robot.waitUntil { Resources.getSystem().configuration.fontScale == (value.toFloatOrNull() ?: 1f) }
    }
}
