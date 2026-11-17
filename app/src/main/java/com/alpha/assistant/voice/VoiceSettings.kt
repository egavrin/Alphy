package com.alpha.assistant.voice

import android.content.Context
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Capability

/** «Источник голоса» (spec 12.2): the phone engine, or the cloud voice of the provider. */
internal enum class SpeechSource { PHONE, CLOUD }

/** The voice preferences of «Голос» (spec 12.2). [name] is the preference file: the app passes [STORE]. */
internal class VoiceSettings(context: Context, name: String = STORE) {
    companion object {
        /** The preference file of the app. */
        const val STORE = "alpha_voice"
    }
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    /** «Читать ответы вслух». Off by default. */
    var readAloud: Boolean
        get() = prefs.getBoolean("read_aloud", false)
        set(value) { prefs.edit().putBoolean("read_aloud", value).apply() }
    /** The stored «Источник голоса». «Телефон» by default. */
    var source: SpeechSource
        get() = SpeechSource.entries.firstOrNull { it.name == prefs.getString("source", null) } ?: SpeechSource.PHONE
        set(value) { prefs.edit().putString("source", value.name).apply() }
    /** The source for an account of [provider]: «Телефон» when [provider] does not offer the stored source, which stays stored. */
    fun sourceFor(provider: AiProvider?): SpeechSource = source.takeIf { it in speechSources(provider) } ?: SpeechSource.PHONE
}

/** The sources that an account of [provider] offers: «Облако» only with cloud voice (spec 12.2). */
internal fun speechSources(provider: AiProvider?): List<SpeechSource> =
    if (provider != null && Capability.CLOUD_VOICE in provider.profile.capabilities) SpeechSource.entries else listOf(SpeechSource.PHONE)
