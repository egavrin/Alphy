package com.alpha.assistant.ui

import android.content.IntentFilter
import android.os.Build
import android.os.PatternMatcher
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
        val filter = IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            addDataScheme("package")
            addDataSchemeSpecificPart(robot.instrumentation.targetContext.packageName, PatternMatcher.PATTERN_LITERAL)
        }
        val monitor = robot.instrumentation.addMonitor(filter, null, true)
        try {
            fixture.launch().use { scenario ->
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.access))
                robot.awaitDrawerClosed(scenario)
                assertTrue("The microphone row title is a heading", robot.waitFor(robot.text(R.string.access_microphone)).isHeading)
                val shown = mutableSetOf<String>()
                var end = false
                repeat(15) { if (!end) { shown += robot.texts(); end = !robot.scrollForward() } }
                assertTrue("The access page ends within 15 scrolls", end)
                assertTrue(robot.text(R.string.access_note) in shown)
                val titles = listOf(R.string.access_microphone, R.string.access_location, R.string.access_background_title, R.string.access_calendar,
                    R.string.access_owned_calendar, R.string.access_camera, R.string.access_notifications_title, R.string.access_exact_title,
                    R.string.access_listener_title, R.string.assistant_title) + if (Build.VERSION.SDK_INT >= 33) listOf(R.string.access_notification_permission) else emptyList()
                assertEquals(emptyList<String>(), titles.map { robot.text(it) }.filter { it !in shown })
                val technical = Regex("""[a-z]+\.[a-z_.]+|Android API|API \d+|android\.permission|Реализовано|Запланировано|[A-Z]+_[A-Z_]+|сценари|\b(?!(?:Alphy|Android|DeepSeek)\b)[A-Za-z]{3,}\b""")
                assertEquals(emptyList<String>(), shown.filter { technical.containsMatchIn(it) })
                robot.click(robot.text(R.string.access_system_settings))
                robot.waitUntil { monitor.hits == 1 }
            }
        } finally { robot.instrumentation.removeMonitor(monitor) }
    }
}
