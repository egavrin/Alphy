package com.alpha.assistant.ui

import android.content.res.Resources
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.AssistantFixtureApplication
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import com.alpha.assistant.VoiceTestActivity
import com.alpha.assistant.capabilities.MedicineReportActivity
import com.alpha.assistant.surfaces.AssistantActivity
import com.alpha.assistant.surfaces.WidgetConfigurationActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AppLocaleWorkflowTest {
    private val fixture = ShellFixture("app-locale")
    private val robot = ShellRobot("app-locale")
    @After fun remove() = fixture.remove()

    @Test fun activitiesUseRussianOnAnotherDeviceLocale() {
        assumeFalse("The device locale is Russian", Resources.getSystem().configuration.locales[0].language == "ru")
        fixture.install()
        fixture.launch().use { scenario ->
            scenario.onActivity {
                assertEquals("ru", it.resources.configuration.locales[0].language)
                assertEquals("Закрыть меню навигации", it.getString(androidx.compose.ui.R.string.close_drawer))
                assertEquals("Alphy", it.getString(R.string.app_name))
            }
        }
        for (type in listOf(MainActivity::class, AssistantActivity::class, WidgetConfigurationActivity::class,
                MedicineReportActivity::class, CloudTestActivity::class, VoiceTestActivity::class))
            assertTrue("${type.simpleName} extends AlphyActivity", AlphyActivity::class.java.isAssignableFrom(type.java))
    }

    // Failure case: the font size setting of Android changes the text and the layout inside the app.
    @Test fun mainActivityKeepsFontScaleOneAtAnotherSystemFontScale() {
        val app = robot.instrumentation.targetContext.applicationContext as AssistantFixtureApplication
        val scale = robot.shell("settings get system font_scale")
        fixture.install()
        robot.instrumentation.runOnMainSync { app.installAssistantFixture { checkNotNull(CloudTestActivity.factory)(it) } }
        try {
            robot.shell("settings put system font_scale 1.3")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == 1.3f }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val composed = AtomicReference<Pair<Float, Float>>()
                scenario.onActivity {
                    assertEquals(1f, it.resources.configuration.fontScale)
                    it.setContent { composed.set(LocalDensity.current.fontScale to LocalConfiguration.current.fontScale) }
                }
                robot.waitUntil { composed.get() != null }
                assertEquals("LocalDensity and LocalConfiguration font scales", 1f to 1f, composed.get())
            }
        } finally {
            robot.instrumentation.runOnMainSync { app.removeAssistantFixture() }
            // "settings get" prints "null" when the value is unset, and "settings put … null" does not restore that state.
            robot.shell(if (scale.toFloatOrNull() == null) "settings delete system font_scale" else "settings put system font_scale $scale")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == (scale.toFloatOrNull() ?: 1f) }
        }
    }
}
