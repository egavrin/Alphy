package com.alpha.assistant.tools

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.Test
import java.io.File

/**
 * Draws app UIs one below the other in the shell theme and gives a screenshot to the check.
 * The screenshot also goes to files/[folder]/<test method>.png on the device.
 */
internal fun ShellRobot.renderApps(folder: String, uis: List<String>, state: String, last: String, check: (Bitmap) -> Unit) {
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
                        Column(Modifier.safeDrawingPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uis.forEach { ui -> ToolContent(ui, state, enabled = true, appLanguage = "en") { _, _ -> } }
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
