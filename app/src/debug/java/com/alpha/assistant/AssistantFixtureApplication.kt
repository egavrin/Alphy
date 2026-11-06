package com.alpha.assistant

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.alpha.assistant.surfaces.AssistantChat
import com.alpha.assistant.ui.ShellViewModel
import com.alpha.assistant.updates.AppUpdater
import okhttp3.OkHttpClient
import java.io.File

/** A separate store lets instrumentation exercise both activities with one isolated model. */
internal class AssistantFixtureApplication : Application(), AssistantChat.ModelSource, AppUpdater.Source {
    private var updater: AppUpdater? = null

    internal fun installUpdateFixture(client: OkHttpClient) {
        check(updater == null)
        updater = AppUpdater(this, client)
    }

    internal fun removeUpdateFixture() {
        updater?.cancelDownload()
        updater?.discard()
        updater = null
    }

    private val restartUpdater by lazy {
        updateRestartFixture(File(filesDir, "update-test"))?.let { AppUpdater(this, it) }
    }

    override fun updateFixture() = updater ?: restartUpdater

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
