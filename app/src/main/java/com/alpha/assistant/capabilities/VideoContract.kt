package com.alpha.assistant.capabilities

import com.alpha.assistant.media.MAX_CLIP_MILLIS
import com.alpha.assistant.media.MAX_MEDIA_BYTES

internal object VideoContract {
    const val PICK = "video.pick"
    const val CAPTURE = "video.capture"
    const val CAPTURE_KIND = "cameraVideo"
    const val PLAY = "video.play"
    const val RESOURCE_KIND = "video"
    val statuses = setOf("succeeded","cancelled","unavailable","failed")

    val definitions = listOf(
        CapabilityDefinition(PICK,"Выбор видеофайла",CapabilityAction.VIDEO_PICK,emptyMap(),
            "Use the Android document picker to select one video file. The provisional file limit is $MAX_MEDIA_BYTES bytes.",
            "awaitingUser","Returns an opaque app-scoped video resource handle and bounded metadata. No URI, path or file content.",
            foreground=true,selection=true,
            resultData="""{"resourceId":{"type":"string","format":"opaque-owner-handle"},"kind":{"type":"string","const":"video"},"mimeType":{"type":"string"},"sizeBytes":{"type":"integer","minimum":1,"maximum":$MAX_MEDIA_BYTES}}"""),
        CapabilityDefinition(CAPTURE, "Запись короткого видео", CapabilityAction.VIDEO_CAPTURE, emptyMap(),
            "Open the Android camera for one short foreground recording. Set capabilityResults to true and use the returned handle with video.play.",
            "awaitingUser", "Returns a session-scoped private video handle after user acceptance, up to $MAX_CLIP_MILLIS ms and $MAX_MEDIA_BYTES bytes.",
            android.Manifest.permission.CAMERA, android.content.pm.PackageManager.FEATURE_CAMERA_ANY,
            foreground = true, selection = true,
            resultData = """{"resourceId":{"type":"string","format":"opaque-owner-handle"},"kind":{"type":"string","const":"cameraVideo"},"mimeType":{"type":"string","const":"video/mp4"},"sizeBytes":{"type":"integer","minimum":1,"maximum":$MAX_MEDIA_BYTES}}"""),
        CapabilityDefinition(PLAY,"Воспроизведение видео",CapabilityAction.VIDEO_PLAYBACK,
            mapOf("resourceId" to CapabilityArgument("string",maxLength=64,format="opaque-owner-handle")),
            "Only the visible native video control can start playback. Echo input.event.video exactly as args of one video.play effect. Keep that control and handle active. The provisional limits are $MAX_CLIP_MILLIS milliseconds and $MAX_MEDIA_BYTES bytes.",
            "succeeded","Android reported playback completion. Audio and image output are unverified.",foreground=true),
    )

    fun isVideoMime(mime: String) = mime.startsWith("video/",ignoreCase=true)
    fun cancelled() = CapabilityResult("cancelled","Воспроизведение видео отменено.")
    fun unavailable() = CapabilityResult("unavailable","Видео недоступно или не поддерживается.")
    fun failed() = CapabilityResult("failed","Android не подтвердил завершение видео.")
}
