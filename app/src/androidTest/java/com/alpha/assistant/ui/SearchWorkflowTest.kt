package com.alpha.assistant.ui

import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertNull
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
}
