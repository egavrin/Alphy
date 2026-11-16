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

/**
 * Failure case: a 2048 tile (a card of height 56 with padding "small") showed only the top of its number.
 * The card added 16 dp inside and used the style padding as an outer margin, so the title text was clipped.
 */
class NativeCardPaddingWorkflowTest {
    private val robot = ShellRobot("native-card-padding")

    @Test fun aSmallCardShowsItsWholeTitle() {
        val fixture = ShellFixture("card-padding")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val tile = """{"type":"card","layout":{"height":56},"style":{"tone":"accent","padding":"small"},
                    "children":[{"type":"text","text":"2","maxLines":1,"style":{"emphasis":"title","align":"center"}}]}"""
                val ui = """{"type":"column","children":[{"type":"text","text":"8","maxLines":1,"style":{"emphasis":"title"}},
                    {"type":"grid","columns":4,"style":{"gap":"small"},"children":[$tile,$tile,$tile,$tile]}]}"""
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(ui, "{}", enabled = true, appLanguage = "en") { _, _ -> }
                            }
                        }
                    }
                }
                fun height(label: String) = android.graphics.Rect().also(robot.waitFor(label)::getBoundsInScreen).height()
                // The bounds of a clipped text end at the card edge, so a whole title has the height of the free title.
                assertEquals(height("8"), height("2"))
            }
        } finally { fixture.remove() }
    }
}
