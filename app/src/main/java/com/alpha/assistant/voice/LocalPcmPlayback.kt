package com.alpha.assistant.voice

import android.media.*
import com.offlineassistant.app.speech.PcmAudio
import java.io.Closeable

class LocalPcmPlayback : Closeable {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false
    fun play(audio: PcmAudio) {
        check(audio.samples.isNotEmpty()) { "Speech contains no audio" }
        stopped = false
        val minimum = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0) { "Unsupported local speech format" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(maxOf(minimum, 19200)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "Audio output initialization failed" }
            output.play()
            var offset = 0
            while (offset < audio.samples.size && !stopped) {
                val count = output.write(audio.samples, offset, minOf(4800, audio.samples.size - offset), AudioTrack.WRITE_BLOCKING)
                check(count > 0 || stopped) { "Local speech playback failed: $count" }
                if (count > 0) offset += count
            }
            while (!stopped && output.playbackHeadPosition.toLong() < offset) Thread.sleep(20)
        } finally { track = null; output.release() }
    }
    fun stop() { stopped = true; runCatching { track?.pause(); track?.flush() } }
    override fun close() = stop()
}
