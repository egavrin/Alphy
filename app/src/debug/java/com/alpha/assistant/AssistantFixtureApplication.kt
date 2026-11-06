package com.alpha.assistant

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.alpha.assistant.surfaces.AssistantChat
import com.alpha.assistant.ui.ShellViewModel

/** A separate store lets instrumentation exercise both activities with one isolated model. */
class AssistantFixtureApplication : Application(), AssistantChat.ModelSource {
    private val fixtureStore = ViewModelStore()
    private var fixtureFactory: ViewModelProvider.Factory? = null

    fun installAssistantFixture(createModel: (Application) -> ShellViewModel) {
        check(fixtureFactory == null)
        fixtureFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = createModel(this@AssistantFixtureApplication) as T
        }
    }

    fun removeAssistantFixture() {
        fixtureFactory = null
        fixtureStore.clear()
    }

    override fun assistantModel(application: Application): ShellViewModel? =
        fixtureFactory?.let { ViewModelProvider.create(fixtureStore, it)[ShellViewModel::class.java] }
}
