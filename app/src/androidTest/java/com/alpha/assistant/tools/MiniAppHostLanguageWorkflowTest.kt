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

    @Test fun anEnglishAppShowsEnglishHostText() = hostText("en", "Steps", "No records yet", "Next: Steps")

    // Failure case: an Italian app showed English host text, and its live-input messages in Russian.
    @Test fun anItalianAppShowsItalianHostText() = hostText("it", "Passi", "Ancora nessun elemento", "Avanti: Passi")

    @Test fun anAppInALanguageWithoutTranslationsShowsEnglishHostText() = hostText("sw", "Hatua", "No records yet", "Next: Hatua")

    // Failure case: a French 3D app showed the Russian playback buttons "Пауза" and "Сначала".
    @Test fun playbackControlsUseTheAppLanguage() {
        val fixture = ShellFixture("host-language-playback")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) { ToolPlaybackControls("resume", appLanguage = "fr") {} }
                        }
                    }
                }
                robot.waitFor("Reprendre")
                robot.waitFor("Recommencer")
                assertFalse(robot.texts().toString(), robot.texts().any { Regex("\\p{IsCyrillic}").containsMatchIn(it) })
            }
        } finally { fixture.remove() }
    }

    // Failure case (L1 item 7): the empty chart of a Spanish budget app said "Нет данных для графика".
    @Test fun anEmptyChartSaysSoInTheAppLanguage() {
        val ui = """{"type":"chart","kind":"bar","text":"Gastos","categories":"days","series":[{"text":"Total","bind":"totals"}]}"""
        val state = JSONObject().put("days", org.json.JSONArray()).put("totals", org.json.JSONArray())
        for ((language, empty) in listOf("es" to "No hay datos para el gráfico", "en" to "No data for the chart", "ko" to "차트에 표시할 데이터가 없습니다"))
            hostText(language, ui, state, empty)
    }

    private fun hostText(language: String, steps: String, empty: String, next: String) = hostText(language, """{"type":"column","children":[
        {"type":"list","text":"Notes","bind":"notes","columns":[{"text":"Title","field":"title"}]},
        {"type":"pager","text":"$steps","bind":"page","action":"page","children":[
            {"type":"text","text":"1"},{"type":"text","text":"2"}]}]}""", JSONObject().put("notes", org.json.JSONArray()).put("page", 0), empty, next)

    /** Shows the app in this language, waits for each text, and finds no Russian host text. */
    private fun hostText(language: String, ui: String, state: JSONObject, vararg texts: String) {
        val fixture = ShellFixture("host-language")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(ui, state.toString(), enabled = true, appLanguage = language) { _, _ -> }
                            }
                        }
                    }
                }
                texts.forEach { robot.waitFor(it) }
                assertFalse(robot.texts().toString(), robot.texts().any { Regex("\\p{IsCyrillic}").containsMatchIn(it) })
            }
        } finally { fixture.remove() }
    }
}
