package com.alpha.assistant.ui

import android.content.res.Resources
import android.graphics.Rect
import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SearchWorkflowTest {
    private val fixture = ShellFixture("search")
    private val robot = ShellRobot("search")
    @After fun remove() = fixture.remove()

    @Test fun searchShowsRussianDatesAndPoliteTexts() {
        fixture.install()
        val updated = 1_793_000_000_000L
        fixture.seedChat("dated", "Dated chat", "Dated message", updatedAt = updated)
        fixture.launch().use { scenario ->
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.search))
            robot.awaitDrawerClosed(scenario)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag("ru")).format(Date(updated))
            robot.waitFor(robot.text(R.string.search_row_saved, date))
            robot.assertTarget(robot.text(R.string.conversation_actions, "Dated chat"))
            assertNull(robot.find("⋯"))
            robot.edit(robot.text(R.string.search_field), "нет такого")
            robot.waitFor(robot.text(R.string.search_none))
        }
    }

    // Failure case: at font scale 2.0, the clear dialog cuts its text, and clear_history_kept gets no height.
    @Test fun clearDialogTextScrollsAtLargeFont() {
        val scale = robot.shell("settings get system font_scale")
        try {
            robot.shell("settings put system font_scale 2.0")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == 2f }
            fixture.install()
            fixture.seedChat("large", "Large chat", "Large message")
            fixture.launch().use { scenario ->
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.search))
                robot.awaitDrawerClosed(scenario)
                robot.click(robot.text(R.string.clear_history))
                val kept = robot.reveal(robot.text(R.string.clear_history_kept))
                val bounds = Rect().also { kept.getBoundsInScreen(it) }
                assertTrue("clear_history_kept is on the screen: $bounds", kept.isVisibleToUser && bounds.height() > 0)
            }
        } finally {
            // "settings get" prints "null" when the value is unset, and "settings put … null" does not restore that state.
            robot.shell(if (scale.toFloatOrNull() == null) "settings delete system font_scale" else "settings put system font_scale $scale")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == (scale.toFloatOrNull() ?: 1f) }
        }
    }
}
