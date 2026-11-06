package com.alpha.assistant.media

import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.view.Surface
import java.io.Closeable

internal object AndroidDeviceMediaBackend : DeviceMediaBackend {
    override fun startTone(tone: DeviceTone, durationMillis: Int): Closeable? {
        val type = when (tone) {
            DeviceTone.BEEP -> ToneGenerator.TONE_PROP_BEEP
            DeviceTone.ACKNOWLEDGMENT -> ToneGenerator.TONE_PROP_ACK
        }
        val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 50)
        var started = false
        try {
            started = generator.startTone(type, durationMillis)
            return if (started) Closeable { generator.release() } else null
        } finally {
            if (!started) generator.release()
        }
    }

    override fun newClip(): MediaClipPlayer = AndroidMediaClipPlayer()
}

private class AndroidMediaClipPlayer : MediaClipPlayer {
    private val player = MediaPlayer()

    override fun prepare(
        source: AssetFileDescriptor,
        length: Long,
        onPrepared: (MediaClipInfo) -> Unit,
        onCompletion: () -> Unit,
        onFailure: () -> Unit,
    ) {
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        player.setOnErrorListener { _, _, _ ->
            onFailure()
            true
        }
        player.setOnCompletionListener { onCompletion() }
        player.setOnPreparedListener { ready ->
            val info = try {
                val tracks = ready.trackInfo
                MediaClipInfo(
                    durationMillis = ready.duration,
                    hasAudio = tracks.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO },
                    hasVideo = tracks.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO },
                    videoWidth = ready.videoWidth,
                    videoHeight = ready.videoHeight,
                )
            } catch (_: RuntimeException) {
                onFailure()
                return@setOnPreparedListener
            }
            onPrepared(info)
        }
        player.setDataSource(source.fileDescriptor, source.startOffset, length)
        player.prepareAsync()
    }

    override fun start(surface: Surface?) {
        if (surface != null) player.setSurface(surface)
        player.start()
    }

    override fun close() = player.release()
}
