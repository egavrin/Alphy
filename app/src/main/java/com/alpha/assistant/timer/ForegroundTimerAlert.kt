package com.alpha.assistant.timer

import com.alpha.assistant.capabilities.CapabilityCatalog

/** Immutable arguments for one terminal alert. The host checks the app's capability declarations. */
internal data class ForegroundTimerAlert(val vibrationMillis: Long?, val notification: Notification?, val toneMillis: Int? = null) {
    data class Notification(val title: String, val text: String)

    init {
        require(vibrationMillis != null || notification != null || toneMillis != null)
        toneMillis?.let { CapabilityCatalog.definition("audio.tone").parameters.getValue("durationMillis").validate(it) }
        vibrationMillis?.let {
            CapabilityCatalog.definition("vibration.play").parameters.getValue("milliseconds").validate(it)
        }
        notification?.let {
            val arguments = CapabilityCatalog.definition("notification.post").parameters
            arguments.getValue("title").validate(it.title)
            arguments.getValue("text").validate(it.text)
        }
    }
}
