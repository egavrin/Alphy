package com.alpha.assistant.capabilities

import android.content.Context
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.sync.withLock

/** This native interface is shared by tool actions and explicitly approved local operations. */
class CapabilityBroker(private val context: Context, private val store: ToolStore) {
    fun granted(owner: String, capability: String) = store.granted(owner,capability)
    fun grant(owner: String, capability: String, enabled: Boolean) {
        require(capability in ToolSpec.parse(checkNotNull(store.tool(owner)).spec).capabilities)
        store.grant(owner,capability,enabled)
    }
    suspend fun execute(key: String, confirmed: Boolean, adapter: suspend (CapabilityRequest) -> CapabilityResult): CapabilityResult {
        return executeOwned(key,confirmed) { effect -> adapter(effect.request) }
    }
    /** Ownership and tokens come from the claimed host effect, never from generated arguments. */
    internal suspend fun executeOwned(key: String, confirmed: Boolean, adapter: suspend (CapabilityEffect) -> CapabilityResult): CapabilityResult =
        ToolStore.effectMutex.withLock { executeClaimed(key,confirmed,adapter) }
    suspend fun executeNative(key: String, confirmed: Boolean, foreground: ()->Boolean, launch: (android.content.Intent)->Unit): CapabilityResult =
        executeOwned(key,confirmed) { effect ->
            if(effect.request.capability in MedicineContract.ids) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                MedicineCapabilities(context,store).execute(effect,foreground,launch)
            } else if(effect.request.capability in OwnedCalendarContract.ids) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                OwnedCalendarCapabilities(context,store).execute(effect,foreground,launch)
            } else if(effect.request.capability in NavigationContract.ids) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                NavigationCapabilities(context,store).execute(effect,foreground,launch)
            } else AndroidCapabilities(context).execute(effect.request,kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {foreground()}) { intent ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {launch(intent)}
            }
        }
    internal suspend fun executeCalendarDeleteAll(key: String,snapshot: OwnedCalendarSnapshot,foreground: ()->Boolean): CapabilityResult =
        executeOwned(key,true) {effect -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            OwnedCalendarCapabilities(context,store).deleteAll(effect,snapshot,foreground)
        }}
    private suspend fun executeClaimed(key: String, confirmed: Boolean, adapter: suspend (CapabilityEffect) -> CapabilityResult): CapabilityResult {
        val effect = store.claimEffect(key,confirmed) ?: return checkNotNull(store.effect(key)).result
        // Recheck the grant immediately before execution, after the persistent claim.
        val result = if(store.tool(effect.owner)?.archived != false) CapabilityResult("cancelled","Инструмент в архиве или недоступен.")
        else if(!granted(effect.owner,effect.request.capability)) CapabilityResult("revoked","Доступ к системной функции отозван.")
        else try { adapter(effect.copy(request=CapabilityCatalog.request(effect.request.json()))) }
        catch(cancel: kotlinx.coroutines.CancellationException) {
            store.finishEffect(key,CapabilityResult("unknownOutcome","The native action was interrupted. Check its outcome before another request."))
            throw cancel
        }
        catch(_: SecurityException) { CapabilityResult("denied","Android отказал в доступе. Проверь разрешения приложения.") }
        catch(_: Exception) { CapabilityResult("unknownOutcome","Системная функция не подтвердила результат. Проверь его перед повторным действием.") }
        store.finishEffect(key,result)
        return if(effect.request.capability=="calendar.deleteAllOwned") checkNotNull(store.effect(key)).result else result
    }
}
