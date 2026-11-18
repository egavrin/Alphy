package com.alpha.assistant.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The 16 kHz mono 16-bit samples of a WAV file. The chunks are walked, because a file can have a chunk before `data`. */
internal fun wavSamples(file: ByteArray): ShortArray {
    val bytes = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
    fun id(offset: Int) = String(file, offset, 4, Charsets.US_ASCII)
    check(id(0) == "RIFF" && id(8) == "WAVE")
    var offset = 12
    while (offset + 8 <= file.size) {
        val size = bytes.getInt(offset + 4)
        if (id(offset) == "fmt ") check(bytes.getShort(offset + 8).toInt() == 1 && bytes.getShort(offset + 10).toInt() == 1 &&
            bytes.getInt(offset + 12) == LIVE_RATE && bytes.getShort(offset + 22).toInt() == 16) { "The fixture is not 16 kHz mono PCM16." }
        if (id(offset) == "data") {
            bytes.position(offset + 8)
            return ShortArray(size / 2).also { bytes.asShortBuffer().get(it) }
        }
        offset += 8 + size + (size and 1)
    }
    error("The fixture has no data chunk.")
}

/** A WAV file of mono PCM16 samples. The encoder of the upload, [wav], writes 16 kHz only. */
internal fun wavBytes(samples: ShortArray, rate: Int = LIVE_RATE): ByteArray {
    require(rate == SAMPLE_RATE) { "Only $SAMPLE_RATE Hz is supported." }
    return wav(samples)
}
