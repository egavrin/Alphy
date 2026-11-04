package com.alpha.assistant.ui

import androidx.annotation.StringRes
import com.alpha.assistant.R

internal sealed interface ShellRoute {
    data object Desktop : ShellRoute
    data class Chat(val id: String) : ShellRoute
}

internal enum class ShellDestination(@StringRes val title: Int) : ShellRoute {
    Search(R.string.search), MiniApps(R.string.mini_apps), Automations(R.string.automations), Access(R.string.access), Settings(R.string.settings)
}
