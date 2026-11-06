package com.alpha.assistant.tools

import com.alpha.assistant.tools.generation.PartClockSample
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal fun localPartClock(): PartClockSample = PartClockSample.from(Instant.now(), ZoneId.systemDefault())

/** The caller validates the UI against this view. Only durableState may be persisted or passed to fill. */
internal fun projectToolState(spec: ToolSpec, durableState: JSONObject, sample: PartClockSample?): JSONObject {
    val composition = spec.composition?.takeIf { it.hasReadProjections } ?: return durableState
    val view = composition.projectState(durableState, checkNotNull(sample))
    return spec.validateState(view.toString())
}
