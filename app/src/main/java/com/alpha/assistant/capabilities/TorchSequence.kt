package com.alpha.assistant.capabilities

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.alpha.assistant.media.MediaPlaybackResult
import com.alpha.assistant.media.MediaStartAuthorization
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

internal class TorchSequence private constructor(
    private val notes: List<Int>, val bpm: Int, val durationMillis: Int, val pulseMillis: Int,
) {
    val sampleRate = 8000
    val beats: List<Long> get() = generateSequence(0L) { it + 1 }.map { it * 60_000L / bpm }
        .takeWhile { it < durationMillis }.toList()
    val samples: ShortArray by lazy {
        ShortArray(durationMillis * sampleRate / 1000) { index ->
            val beat = index.toLong() * bpm / (sampleRate * 60L)
            val note = notes[(beat % notes.size).toInt()]
            val frequency = 440.0 * 2.0.pow((note - 69) / 12.0)
            (8192 * sin(2 * PI * frequency * index / sampleRate)).toInt().toShort()
        }
    }
    val pcmSha256: String by lazy {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)
        MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        const val ID = "audio.torchSequence"
        fun parse(args: JSONObject): TorchSequence {
            require(args.keys().asSequence().toSet() == setOf("notes", "bpm", "durationMillis", "torchPulseMillis"))
            val raw = args.get("notes") as? String ?: throw IllegalArgumentException("Notes must be text.")
            require(raw.length in 1..127)
            val notes = raw.split(',').map { token ->
                val note = token.trim().toIntOrNull()
                require(note != null && note in 0..127) { "Use MIDI notes from 0 to 127." }
                checkNotNull(note)
            }
            require(notes.size in 1..32)
            fun number(key: String, range: IntRange): Int {
                val value = args.get(key)
                require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in range)
                return value.toInt()
            }
            val bpm = number("bpm", 30..240)
            val duration = number("durationMillis", 1..30_000)
            val pulse = number("torchPulseMillis", 1..200)
            require(pulse < 60_000 / bpm)
            val sequence = TorchSequence(notes, bpm, duration, pulse)
            require(sequence.beats.last() + pulse <= duration) { "The final pulse must finish within the audio duration." }
            return sequence
        }

        fun validateReceipt(result: CapabilityResult) {
            if (result.status != "succeeded") { require(result.data.length() == 0); return }
            val data = result.data
            require(data.keys().asSequence().toSet() == setOf("sampleRate", "sampleCount", "beats", "pcmSha256", "physicalVerified", "pulses"))
            require(data.get("sampleRate") == 8000 && data.get("physicalVerified") == false)
            fun integer(value: Any, range: LongRange): Long {
                require(value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() in range)
                return value.toLong()
            }
            integer(data.get("sampleCount"), 8L..240_000L)
            integer(data.get("beats"), 1L..120L)
            require(data.getString("pcmSha256").matches(Regex("[0-9a-f]{64}")))
            val pulses = data.getJSONArray("pulses")
            require(pulses.length() == data.getInt("beats"))
            repeat(pulses.length()) { i ->
                val pulse = pulses.getJSONArray(i)
                require(pulse.length() == 2)
                val on = integer(pulse.get(0), 0L..32_000L)
                integer(pulse.get(1), on..32_000L)
            }
        }
    }
}

/** One foreground owner holds the audio/torch session until both resources are released. */
internal class TorchSequencePlayer(private val context: Context, private val sequence: TorchSequence) {
    var receipt = JSONObject()
        private set

    suspend fun play(authorize: MediaStartAuthorization, active: () -> Boolean): MediaPlaybackResult {
        if (!ownership.tryLock()) return MediaPlaybackResult.BUSY
        var track: AudioTrack? = null
        var camera: CameraManager? = null
        var cameraId: String? = null
        var started = false
        try {
            val manager = context.getSystemService(CameraManager::class.java) ?: return MediaPlaybackResult.SOURCE_UNAVAILABLE
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return MediaPlaybackResult.SOURCE_UNAVAILABLE
            camera = manager; cameraId = id
            val audio = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sequence.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(sequence.samples.size * 2).build()
            track = audio
            if (audio.state != AudioTrack.STATE_INITIALIZED || audio.write(sequence.samples, 0, sequence.samples.size) != sequence.samples.size)
                return MediaPlaybackResult.PLAYBACK_FAILED
            var origin = 0L
            if (!authorize { origin = SystemClock.elapsedRealtime(); audio.play(); started = true }) return MediaPlaybackResult.CANCELLED
            suspend fun until(offset: Long): Boolean {
                currentCoroutineContext().ensureActive()
                val remaining = origin + offset - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                return active()
            }
            val pulses = JSONArray()
            for (beat in sequence.beats) {
                if (!until(beat)) return MediaPlaybackResult.CANCELLED
                // A delayed beat is skipped by failing the run, never replayed as a burst.
                if (SystemClock.elapsedRealtime() - origin - beat > 100) return MediaPlaybackResult.TIMED_OUT
                manager.setTorchMode(id, true)
                val on = SystemClock.elapsedRealtime() - origin
                if (!until(on + sequence.pulseMillis)) return MediaPlaybackResult.CANCELLED
                manager.setTorchMode(id, false)
                pulses.put(JSONArray().put(on).put(SystemClock.elapsedRealtime() - origin))
            }
            if (!until(sequence.durationMillis.toLong())) return MediaPlaybackResult.CANCELLED
            if (audio.playbackHeadPosition < sequence.samples.size) {
                if (!until(sequence.durationMillis + 500L) || audio.playbackHeadPosition < sequence.samples.size) return MediaPlaybackResult.TIMED_OUT
            }
            receipt = JSONObject().put("sampleRate", sequence.sampleRate).put("sampleCount", sequence.samples.size)
                .put("beats", pulses.length()).put("pcmSha256", sequence.pcmSha256).put("physicalVerified", false).put("pulses", pulses)
            return MediaPlaybackResult.COMPLETED
        } finally {
            try {
                withContext(NonCancellable) {
                    if (started) runCatching { camera?.setTorchMode(checkNotNull(cameraId), false) }
                    track?.let { audio -> try { if (audio.playState == AudioTrack.PLAYSTATE_PLAYING) audio.stop() } finally { audio.release() } }
                }
            } finally { ownership.unlock() }
        }
    }

    companion object { private val ownership = Mutex() }
}
