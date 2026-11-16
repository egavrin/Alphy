package com.alpha.assistant.tools

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Failure case: in a 5-option segmented control, the option with a long label was taller than the others. */
class NativeSegmentedWorkflowTest {
    private val robot = ShellRobot("native-segmented")

    @Test fun everyOptionHasTheSameHeight() {
        val labels = listOf("Daily", "Weekly", "Monthly", "Quarterly", "Every two years")
        val fixture = ShellFixture("segmented")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val options = labels.mapIndexed { index, label -> """{"text":"$label","value":$index}""" }.joinToString(",")
                val ui = """{"type":"column","children":[{"type":"segmented","text":"Repeat","bind":"repeat","action":"repeat","options":[$options]}]}"""
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(ui, JSONObject().put("repeat", 0).toString(), enabled = true, appLanguage = "en") { _, _ -> }
                            }
                        }
                    }
                }
                // The label is a child of the option button, so the check measures the clickable ancestor.
                val bounds = labels.map { label ->
                    val button = generateSequence(robot.waitFor(label)) { it.parent }.first { it.isClickable || it.isCheckable }
                    android.graphics.Rect().also(button::getBoundsInScreen)
                }
                assertEquals(bounds.toString(), 1, bounds.map { it.height() }.distinct().size)
                assertEquals(bounds.toString(), 1, bounds.map { it.top }.distinct().size)
            }
        } finally { fixture.remove() }
    }
}
