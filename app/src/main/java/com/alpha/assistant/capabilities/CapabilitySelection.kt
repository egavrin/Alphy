package com.alpha.assistant.capabilities

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alpha.assistant.tools.ToolStore
import java.util.UUID

/** Each registry callback captures this immutable launch identity. It never reads a later launch's effect key. */
data class CapabilitySelectionLaunch(
    val token: String, val effectKey: String, val capability: String, val session: String, val library: String
)

internal sealed interface CapabilityPrompt {
    data class Picker(val launch: CapabilitySelectionLaunch, val intent: Intent, val origin: DeviceReadHost? = null) : CapabilityPrompt
    data class Reading(val launch: CapabilitySelectionLaunch, val origin: DeviceReadHost?) : CapabilityPrompt
    data class Camera(val launch: CapabilitySelectionLaunch, val capture: PhotoCapture, val origin: DeviceReadHost) : CapabilityPrompt
    data class Share(val effectKey: String, val text: String, val origin: DeviceReadHost,
        val launch: (Intent) -> Unit) : CapabilityPrompt
    data class Image(val effectKey: String, val intent: com.alpha.assistant.images.ImageIntent, val origin: DeviceReadHost,
        val selection: com.alpha.assistant.images.ImageSettings.Selection) : CapabilityPrompt
    data class Calendar(val effectKey: String) : CapabilityPrompt
    data class LocationPermission(val token: String, val effectKey: String, val host: DeviceReadHost) : CapabilityPrompt
}

internal data class LocationPermissionAnswer(val granted: Boolean, val settingsRequired: Boolean)

/** The controller survives Activity recreation and keeps one launcher identity for its selected library. */
class CapabilitySelectionState(private val library: String, private val processSession: String = ToolStore.processSession) {
    var active by mutableStateOf<CapabilitySelectionLaunch?>(null); private set
    fun begin(effectKey: String, capability: String): CapabilitySelectionLaunch? {
        if(active != null) return null
        require(effectKey.isNotBlank() && effectKey.length <= 256 && CapabilityCatalog.definition(capability).selection)
        return CapabilitySelectionLaunch(UUID.randomUUID().toString(),effectKey,capability,processSession,library).also { active=it }
    }
    fun owns(launch: CapabilitySelectionLaunch) = launch.session == processSession && launch.library == library
    /** Consume before parsing any returned private content. Stale and duplicate callbacks read nothing. */
    fun consume(token: String): CapabilitySelectionLaunch? {
        val launch=active ?: return null
        if(launch.token != token) return null
        active=null
        return launch.takeIf(::owns)
    }
}
