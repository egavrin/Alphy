package com.alpha.assistant.tools

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.alpha.assistant.tools.generation.PartClockSample
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal fun localPartClock(context: Context): PartClockSample = PartClockSample.from(Instant.now(), ZoneId.systemDefault())
    .copy(elapsedMillis = SystemClock.elapsedRealtime(),
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0))

/** The caller validates the UI against this view. Only durableState may be persisted or passed to fill. */
internal fun projectToolState(spec: ToolSpec, durableState: JSONObject, sample: PartClockSample?): JSONObject {
    val composition = spec.composition?.takeIf { it.hasReadProjections } ?: return durableState
    val view = composition.projectState(durableState, checkNotNull(sample))
    return spec.validateState(view.toString())
}
