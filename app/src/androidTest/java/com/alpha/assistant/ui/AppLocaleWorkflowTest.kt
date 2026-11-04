package com.alpha.assistant.ui

import android.content.res.Resources
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

class AppLocaleWorkflowTest {
    private val fixture = ShellFixture("app-locale")
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
}
