package com.alpha.assistant.cloud

import android.content.Context

/**
 * The chat and cloud voice models that the user chose from the curated lists of the provider profile (spec 7.4, 14.8).
 * The lists come only from the profile, and an unknown stored value gives the first curated model. Image models stay in ImageSettings.
 */
class AiModels(context: Context, name: String = "alpha_ai_models") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    internal fun chat(provider: AiProvider): String =
        provider.profile.chatModels.let { models -> prefs.getString(CHAT, null)?.takeIf { it in models } ?: models.first() }

    internal fun selectChat(provider: AiProvider, model: String) {
        require(model in provider.profile.chatModels)
        store(provider, CHAT, model)
    }

    internal fun speech(provider: AiProvider): SpeechModel {
        require(Capability.CLOUD_VOICE in provider.profile.capabilities) { "$provider has no cloud voice" }
        return provider.profile.speechModels.first()
    }

    /** Keeps the choices made for [provider]. Choices made for a different provider reset to the defaults (spec 6.7). */
    internal fun keepFor(provider: AiProvider) {
        if (prefs.getString(PROVIDER, null) != provider.id) prefs.edit().clear().apply()
    }

    // A choice belongs to the provider that it was made for, so the store never mixes the choices of two providers.
    private fun store(provider: AiProvider, field: String, value: String) {
        keepFor(provider)
        prefs.edit().putString(PROVIDER, provider.id).putString(field, value).apply()
    }

    private companion object {
        const val PROVIDER = "provider"
        const val CHAT = "chat"
    }
}
