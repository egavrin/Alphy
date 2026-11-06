package com.alpha.assistant.capabilities

import com.alpha.assistant.media.DeviceTone
import com.alpha.assistant.media.MAX_CLIP_MILLIS
import com.alpha.assistant.media.MAX_MEDIA_BYTES
import com.alpha.assistant.media.MAX_TONE_MILLIS

internal object AudioContract {
    const val TONE = "audio.tone"
    const val PICK = "audio.pick"
    const val PLAY = "audio.play"
    const val RESOURCE_KIND = "audio"
    val playbackIds = setOf(TONE, PLAY)
    val playbackStatuses = setOf("succeeded", "cancelled", "unavailable", "failed")
    val tones = mapOf("beep" to DeviceTone.BEEP, "acknowledgment" to DeviceTone.ACKNOWLEDGMENT)

    val definitions = listOf(
        CapabilityDefinition(TONE, "Короткий звуковой сигнал", CapabilityAction.AUDIO_PLAYBACK,
            mapOf("tone" to CapabilityArgument("string", maxLength = 14, choices = tones.keys),
                "durationMillis" to CapabilityArgument("integer", minimum = 1, maximum = MAX_TONE_MILLIS.toLong())),
            "Play one system tone for 1 to $MAX_TONE_MILLIS milliseconds from an explicit foreground action.",
            "succeeded", "Android accepted the tone and the requested duration elapsed. Audible output is unverified.", foreground = true),
        CapabilityDefinition(PICK, "Выбор аудиофайла", CapabilityAction.AUDIO_PICK, emptyMap(),
            "Use the Android document picker to select one audio file. The provisional file limit is $MAX_MEDIA_BYTES bytes.",
            "awaitingUser", "Returns an opaque app-scoped audio resource handle and bounded metadata. No URI, path or file content.",
            foreground = true, selection = true,
            resultData = """{"resourceId":{"type":"string","format":"opaque-owner-handle"},"kind":{"type":"string","const":"audio"},"mimeType":{"type":"string"},"sizeBytes":{"type":"integer","minimum":1,"maximum":$MAX_MEDIA_BYTES}}"""),
        CapabilityDefinition(PLAY, "Воспроизведение аудиофайла", CapabilityAction.AUDIO_PLAYBACK,
            mapOf("resourceId" to CapabilityArgument("string", maxLength = 64, format = "opaque-owner-handle")),
            "Play one selected local audio resource from an explicit foreground action. Use an owner-scoped audio.pick or file.pick handle. The provisional limits are $MAX_CLIP_MILLIS milliseconds and $MAX_MEDIA_BYTES bytes. Video tracks are unavailable.",
            "succeeded", "The Android player reported completion. Audible output is unverified.", foreground = true),
    )

    fun isAudioMime(mime: String) = mime.startsWith("audio/", ignoreCase = true)
    fun cancelled() = CapabilityResult("cancelled", "Воспроизведение отменено.")
    fun unavailable() = CapabilityResult("unavailable", "Аудио недоступно или не поддерживается.")
    fun failed() = CapabilityResult("failed", "Android не подтвердил завершение воспроизведения.")
}

/** These operations need continuous native foreground authority through execution and result delivery. */
internal object ForegroundCapabilityEffects {
    val executionIds = DeviceReadContract.ids + AudioContract.playbackIds + NetworkEffectContract.ID + VideoContract.PLAY + MiniAppAi.ID
    val deliveryIds = executionIds + AudioContract.PICK + VideoContract.PICK
    fun cancelled(id: String) = when (id) {
        in AudioContract.playbackIds -> AudioContract.cancelled()
        AudioContract.PICK -> CapabilityResult("cancelled", "Выбор аудиофайла отменён.")
        NetworkEffectContract.ID -> NetworkEffectContract.receiptResult("cancelled")
        VideoContract.PLAY -> VideoContract.cancelled()
        VideoContract.PICK -> CapabilityResult("cancelled", "Выбор видеофайла отменён.")
        MiniAppAi.ID -> MiniAppAi.cancelled()
        else -> DeviceReadContract.cancelled()
    }
}
