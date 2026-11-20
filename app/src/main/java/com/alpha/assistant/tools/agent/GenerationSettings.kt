package com.alpha.assistant.tools.agent

import android.content.Context
import com.alpha.assistant.cloud.AiProvider

/** User choices for app generation. */
internal class GenerationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("alpha_generation", Context.MODE_PRIVATE)

    /** Off by default: the review adds one model step, with an image, to each new app. */
    var screenshotReview: Boolean
        get() = prefs.getBoolean("screenshot_review", false)
        // commit, not apply: a process that stops right after the change must not keep the old value.
        set(value) { check(prefs.edit().putBoolean("screenshot_review", value).commit()) }

    /** The plan step before the agent loop. On by default only for OpenAI, the provider of the paired runs. A stored value wins. */
    fun planStep(provider: AiProvider): Boolean = prefs.getBoolean("plan_step_${provider.id}", provider == AiProvider.OPENAI)

    fun setPlanStep(provider: AiProvider, on: Boolean) = check(prefs.edit().putBoolean("plan_step_${provider.id}", on).commit())
}
