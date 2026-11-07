package com.alpha.assistant.ui

import android.content.res.Configuration
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceWorkflowTest {
    private val fixture = ShellFixture("appearance")
    private val robot = ShellRobot("appearance")
    private val system = "Как в системе"
    private val light = "Светлая"
    private val dark = "Тёмная"

    @After fun remove() = fixture.remove()

    @Test fun themeChoiceSurvivesRecreationAndReturnsToSystem() {
        val night = checkNotNull(Regex("""Night mode: (\S+)""").find(robot.shell("cmd uimode night"))).groupValues[1]
        val previous = AppearanceMode.read(fixture.context)
        val composer = robot.text(R.string.composer_label)
        val message = "Appearance workflow message"
        val draft = "Appearance workflow draft"
        fixture.install()
        fixture.seedChat("appearance", "Appearance workflow", message, selected = true)

        try {
            if (previous != AppearanceMode.System) AppearanceMode.System.select(fixture.context)
            setSystemDark(false)
            fixture.launch().use { scenario ->
                robot.waitFor(message)
                robot.edit(composer, draft)
                if (robot.keyboardTop(scenario) != null) robot.back()
                openAppearance(scenario)
                assertMode(scenario, system, false)
                // A different selection must take effect even if it uses the current palette.
                robot.click(light)
                assertMode(scenario, light, false)
                setSystemDark(true)
                assertMode(scenario, light, false)
                robot.click(dark)
                assertMode(scenario, dark, true)
                setSystemDark(false)
                assertMode(scenario, dark, true)
                scenario.recreate()
                assertMode(scenario, dark, true)
                robot.back()
                robot.waitUntil { robot.composed(scenario, "settings-menu") }
                robot.back()
                robot.waitFor(message)
                assertEquals(draft, robot.field(composer).text?.toString())

                openAppearance(scenario)
                assertMode(scenario, dark, true)
                robot.click(system)
                assertMode(scenario, system, false)
                setSystemDark(true)
                assertMode(scenario, system, true)
            }
        } finally {
            try { previous.select(fixture.context) } finally { robot.shell("cmd uimode night $night") }
        }
    }

    private fun openAppearance(scenario: ActivityScenario<*>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.click("Оформление")
        robot.waitFor("Тема")
    }

    private fun setSystemDark(dark: Boolean) {
        robot.shell("cmd uimode night ${if (dark) "yes" else "no"}")
        // The app's resources can reflect the package override. Read global mode from Android.
        robot.waitUntil { robot.shell("dumpsys uimode").contains("mComputedNightMode=$dark ") }
    }

    private fun assertMode(scenario: ActivityScenario<*>, label: String, dark: Boolean) {
        robot.waitUntil {
            val row = generateSequence(robot.find(label)) { it.parent }.firstOrNull { it.isCheckable }
            row?.isChecked == true
        }
        robot.waitUntil {
            var matches = false
            scenario.onActivity {
                matches = isDark(it.resources.configuration) == dark &&
                    WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars == !dark
            }
            matches
        }
    }

    private fun isDark(configuration: Configuration) =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
