package com.alpha.assistant.ui

import android.content.IntentFilter
import android.provider.Settings
import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPageWorkflowTest {
    private val fixture = ShellFixture("access")
    private val robot = ShellRobot("access")
    @After fun remove() = fixture.remove()

    @Test fun accessPageShowsPlainWordsOnly() {
        fixture.install()
        val filter = IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { addDataScheme("package") }
        val monitor = robot.instrumentation.addMonitor(filter, null, true)
        try {
            fixture.launch().use { scenario ->
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.access))
                robot.awaitDrawerClosed(scenario)
                val shown = mutableSetOf<String>()
                var end = false
                repeat(15) { if (!end) { shown += robot.texts(); end = !robot.scrollForward() } }
                assertTrue("The access page ends within 15 scrolls", end)
                assertTrue(robot.text(R.string.access_note) in shown)
                assertTrue(robot.text(R.string.access_owned_calendar) in shown)
                val technical = Regex("""[a-z]+\.[a-z_.]+|Android API|API \d+|android\.permission|Реализовано|Запланировано""")
                assertEquals(emptyList<String>(), shown.filter { technical.containsMatchIn(it) })
                robot.click(robot.text(R.string.access_system_settings))
                robot.waitUntil { monitor.hits == 1 }
            }
        } finally { robot.instrumentation.removeMonitor(monitor) }
    }
}
