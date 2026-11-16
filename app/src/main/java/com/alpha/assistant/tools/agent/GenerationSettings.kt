package com.alpha.assistant.tools.agent

import android.content.Context

/** User choices for app generation. */
internal class GenerationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("alpha_generation", Context.MODE_PRIVATE)

    /** Off by default: the review adds one model step, with an image, to each new app. */
    var screenshotReview: Boolean
        get() = prefs.getBoolean("screenshot_review", false)
        // commit, not apply: a process that stops right after the change must not keep the old value.
        set(value) { check(prefs.edit().putBoolean("screenshot_review", value).commit()) }
}
