package com.alpha.assistant.ui

internal sealed interface ShellRoute {
    data object Desktop : ShellRoute
    data class Chat(val id: String) : ShellRoute
}

internal enum class ShellDestination : ShellRoute { Search, MiniApps, Automations, Access, Settings }
