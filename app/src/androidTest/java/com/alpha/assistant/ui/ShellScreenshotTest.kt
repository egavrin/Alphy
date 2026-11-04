package com.alpha.assistant.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The visual check method for FLAG_SECURE screens (spec 9). Each capture saves a screenshot and the node list of the screen.
 * The screenshot of «Настройки» is black, so the test also draws the window of that page into bitmaps in the app process.
 */
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
                    // The Chat capture selects the chat, and a launch restores the selected chat.
                    fixture.store.saveSetting("selected", "")
                    fixture.launch().use { scenario ->
                        scenario.onActivity {
                            assertEquals(size.toFloat(), it.resources.configuration.fontScale)
                            assertEquals(bits, it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                        }
                        captureScreens(scenario, File(root, "$mode-$size").apply { mkdirs() })
                    }
                }
            }
        } finally {
            try { setNight(night, nightBits) } finally { setScale(scale) }
            assertEquals(scale, robot.shell("settings get system font_scale"))
        }
    }

    private fun captureScreens(scenario: ActivityScenario<*>, directory: File) {
        robot.waitFor(robot.text(R.string.desktop_prompt))
        // Color.BLACK is exactly 0xFF000000, the FLAG_SECURE black. A dark background is not black.
        assertNotEquals("Desktop center pixel", Color.BLACK, center(screen(directory, "desktop")))
        robot.openDrawer(scenario)
        screen(directory, "drawer")
        robot.click(title)
        robot.awaitDrawerClosed(scenario)
        robot.waitFor(message)
        screen(directory, "chat")
        open(scenario, R.string.search)
        robot.waitFor(robot.text(R.string.search_field))
        screen(directory, "search")
        open(scenario, R.string.access)
        robot.waitFor(robot.text(R.string.access_intro))
        screen(directory, "access")
        open(scenario, R.string.settings)
        robot.waitUntil { robot.secure(scenario) }
        robot.waitFor(robot.text(R.string.key_ready))
        assertEquals("Settings center pixel", Color.BLACK, center(screen(directory, "settings")))
        var page = 0
        do {
            val image = settled { draw(scenario) }
            save(directory, "settings-$page", image)
            val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
            val colors = pixels.toSet().size
            assertTrue("settings-$page has $colors colors", colors >= 16)
            page++
        } while (robot.scrollForward())
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
            if (next.sameAs(last)) return next
            assertTrue("The screen did not settle within 3 seconds", SystemClock.uptimeMillis() < deadline)
            last = next
        }
    }

    // Assumption: View.draw on a software canvas draws the Compose content, and FLAG_SECURE does not block it,
    // because the flag protects only the window surface. The distinct-color assertion tests this.
    private fun draw(scenario: ActivityScenario<*>): Bitmap {
        var image: Bitmap? = null
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        return checkNotNull(image)
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
