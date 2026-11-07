package com.alpha.assistant.ui

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit

internal enum class AppearanceMode(private val nightMode: Int) {
    System(UiModeManager.MODE_NIGHT_AUTO),
    Light(UiModeManager.MODE_NIGHT_NO),
    Dark(UiModeManager.MODE_NIGHT_YES);

    fun applyTo(context: Context) {
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(nightMode)
    }

    fun select(context: Context) {
        context.appearancePreferences().edit { putString("mode", name) }
        applyTo(context)
    }

    companion object {
        fun read(context: Context): AppearanceMode {
            val saved = context.appearancePreferences().getString("mode", null)
            return entries.firstOrNull { it.name == saved } ?: System
        }
    }
}

private fun Context.appearancePreferences() = getSharedPreferences("alphy-appearance", Context.MODE_PRIVATE)
