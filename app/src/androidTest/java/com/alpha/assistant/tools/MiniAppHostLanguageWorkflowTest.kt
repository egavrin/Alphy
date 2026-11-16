package com.alpha.assistant.tools

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Test

/** Failure case: an English notes app showed the Russian host text for an empty list. */
class MiniAppHostLanguageWorkflowTest {
    private val robot = ShellRobot("mini-app-host-language")

    @Test fun anEnglishAppShowsEnglishHostText() {
        val fixture = ShellFixture("host-language")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                val ui = JSONObject("""{"type":"column","children":[
                    {"type":"list","text":"Notes","bind":"notes","columns":[{"text":"Title","field":"title"}]},
                    {"type":"pager","text":"Steps","bind":"page","action":"page","children":[
                        {"type":"text","text":"First step"},{"type":"text","text":"Second step"}]}]}""")
                val state = JSONObject().put("notes", org.json.JSONArray()).put("page", 0)
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(ui.toString(), state.toString(), enabled = true, appLanguage = "en") { _, _ -> }
                            }
                        }
                    }
                }
                robot.waitFor("No records yet")
                robot.waitFor("Next: Steps")
                assertFalse(robot.texts().toString(), robot.texts().any { Regex("\\p{IsCyrillic}").containsMatchIn(it) })
            }
        } finally { fixture.remove() }
    }
}
