package com.alpha.assistant

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.alpha.assistant.ui.AlphaShell
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellViewModel

/** Only the instrumentation harness supplies this factory. This activity is absent from release builds. */
class CloudTestActivity : ComponentActivity() {
    companion object { var factory: ((Application) -> ShellViewModel)? = null }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(factory)(application) as T
        })[ShellViewModel::class.java]
        setContent { AlphaTheme(model.snapshot.theme) { AlphaShell(model) } }
    }
}
