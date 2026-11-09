package com.alpha.assistant.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import com.alpha.assistant.semantic.SemanticRuntime
import java.util.Locale

/** Every Alphy activity uses Russian resources, so Material component labels are Russian on any device locale. */
abstract class AlphyActivity : ComponentActivity() {
    // Only the locale: a copy of the base configuration would keep the attach-time screen size and window bounds after a resize.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.createConfigurationContext(Configuration().apply { setLocales(LocaleList(Locale.forLanguageTag("ru"))) }))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppearanceMode.read(this).applyTo(this)
    }

    override fun onStart() {
        super.onStart()
        SemanticRuntime.activityStarted()
    }

    override fun onStop() {
        SemanticRuntime.activityStopped()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level != TRIM_MEMORY_UI_HIDDEN) SemanticRuntime.trim()
    }
}
