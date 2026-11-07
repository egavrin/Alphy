package com.alpha.assistant.tools.generation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One sample supplied by the host for an entire event. */
internal data class PartClockSample(val localDate: LocalDate, val minuteOfDay: Int, val elapsedMillis: Long = 0, val bootCount: Int = 0) {
    init {
        require(minuteOfDay in 0..1439)
    }

    companion object {
        fun from(instant: Instant, zone: ZoneId): PartClockSample {
            val local = instant.atZone(zone)
            return PartClockSample(local.toLocalDate(), local.hour * 60 + local.minute, instant.toEpochMilli())
        }
    }
}
