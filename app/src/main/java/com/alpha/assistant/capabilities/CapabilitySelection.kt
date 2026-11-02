package com.alpha.assistant.capabilities

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import com.alpha.assistant.tools.ToolStore
import java.util.UUID

/** Each registry callback captures this immutable launch identity. It never reads a later launch's effect key. */
data class CapabilitySelectionLaunch(val token: String, val effectKey: String, val capability: String, val session: String)

class CapabilitySelectionState(private val processSession: String = ToolStore.processSession) {
    var active by mutableStateOf<CapabilitySelectionLaunch?>(null); private set
    fun begin(effectKey: String, capability: String): CapabilitySelectionLaunch? {
        if(active != null) return null
        require(effectKey.isNotBlank() && effectKey.length <= 256 && CapabilityCatalog.definition(capability).selection)
        return CapabilitySelectionLaunch(UUID.randomUUID().toString(),effectKey,capability,processSession).also { active=it }
    }
    /** Consume before parsing any returned private content. Stale and duplicate callbacks read nothing. */
    fun consume(token: String): CapabilitySelectionLaunch? {
        val launch=active ?: return null
        if(launch.token != token) return null
        active=null
        return launch.takeIf { it.session == processSession }
    }
    fun snapshot(): List<String> = active?.let { listOf(it.token,it.effectKey,it.capability,it.session) }.orEmpty()
    companion object {
        fun restore(values: List<String>, processSession: String = ToolStore.processSession) = CapabilitySelectionState(processSession).apply {
            if(values.size == 4 && values[3] == processSession && values[0].matches(Regex("[0-9a-f-]{36}")) &&
                values[1].isNotBlank() && values[1].length <= 256 && CapabilityCatalog.definitions.any { it.id == values[2] && it.selection }) {
                active=CapabilitySelectionLaunch(values[0],values[1],values[2],values[3])
            }
        }
        val Saver=listSaver<CapabilitySelectionState,String>(save={ it.snapshot() },restore={ restore(it) })
    }
}
