package com.alpha.assistant.capabilities

import android.content.Context
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.ToolSpec

/** This native interface is shared by tool actions and explicitly approved local operations. */
class CapabilityBroker(private val context: Context, private val store: ToolStore) {
    fun granted(owner: String, capability: String) = store.granted(owner,capability)
    fun grant(owner: String, capability: String, enabled: Boolean) {
        require(capability in ToolSpec.parse(checkNotNull(store.tool(owner)).spec).capabilities)
        store.grant(owner,capability,enabled)
    }
    suspend fun execute(key: String, confirmed: Boolean, adapter: suspend (CapabilityRequest) -> CapabilityResult): CapabilityResult {
        val effect = store.claimEffect(key,confirmed) ?: return checkNotNull(store.effect(key)).result
        // Recheck the grant immediately before execution, after the persistent claim.
        val result = if(!granted(effect.owner,effect.request.capability)) CapabilityResult("revoked","Доступ к системной функции отозван.")
        else try { adapter(CapabilityCatalog.request(effect.request.json())) }
        catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch(_: SecurityException) { CapabilityResult("denied","Android отказал в доступе. Проверь разрешения приложения.") }
        catch(_: Exception) { CapabilityResult("unknownOutcome","Системная функция не подтвердила результат. Проверь его перед повторным действием.") }
        store.finishEffect(key,result)
        return result
    }
}
