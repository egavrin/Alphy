package com.alpha.assistant.capabilities

import android.content.Context
import android.content.Intent
import com.alpha.assistant.R
import com.alpha.assistant.images.ImageFailure
import com.alpha.assistant.images.ImageGeneration
import com.alpha.assistant.surfaces.ToolWidgets
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** This native interface is shared by tool actions and explicitly approved local operations. */
class CapabilityBroker(private val context: Context, private val store: ToolStore) {
    private val deviceReader = DeviceReadAdapter(context)
    fun cancel(key: String): CapabilityResult? =
        store.rejectPendingEffect(key,CapabilityResult("cancelled","Действие отменено."))

    suspend fun execute(key: String, adapter: suspend (CapabilityRequest) -> CapabilityResult): CapabilityResult =
        executeOwned(key) { effect -> adapter(effect.request) }

    /** Ownership and tokens come from the claimed host effect, never from generated arguments. */
    internal suspend fun executeOwned(key: String, adapter: suspend (CapabilityEffect) -> CapabilityResult): CapabilityResult =
        ToolStore.effectMutex.withLock { executeClaimed(key,adapter) }

    internal suspend fun executeNative(key: String, foreground: ()->Boolean, launch: (CapabilityEffect,Intent)->Unit): CapabilityResult =
        executeOwned(key) { effect ->
            val active = { foreground() && !ToolWidgets.isLocked(context) }
            val open = { intent: Intent -> launch(effect,intent) }
            when(effect.request.capability) {
                in MedicineContract.ids -> withContext(Dispatchers.Main.immediate) {
                    MedicineCapabilities(context,store).execute(effect,active,open)
                }
                in OwnedCalendarContract.ids -> withContext(Dispatchers.Main.immediate) {
                    OwnedCalendarCapabilities(context,store).execute(effect,active,open)
                }
                in NavigationContract.ids -> withContext(Dispatchers.Main.immediate) {
                    NavigationCapabilities(context,store).execute(effect,active,open)
                }
                else -> {
                    if(!withContext(Dispatchers.Main.immediate) {active()})
                        return@executeOwned CapabilityResult("awaitingUser","Откройте и разблокируйте Alphy для этого действия.")
                    if(!store.effectTargetValid(effect))
                        return@executeOwned CapabilityResult("cancelled","Мини-приложение изменилось.")
                    AndroidCapabilities(context).execute(effect.request,true) { intent ->
                        withContext(Dispatchers.Main.immediate) {
                            check(active() && store.effectTargetValid(effect))
                            open(intent)
                        }
                    }
                }
            }
        }

    internal suspend fun executeDeviceRead(key: String, host: DeviceReadHost, hosts: DeviceReadHosts,
        requestLocation: suspend (CapabilityEffect) -> Boolean,
        read: (suspend (CapabilityRequest) -> CapabilityResult)? = null): CapabilityResult? =
        executeForegroundEffect(key,host,hosts,DeviceReadContract.ids, prepare = { effect ->
            val capability = effect.request.capability
            if (capability == DeviceReadContract.LOCATION && !store.permissionGranted(capability)) {
                hosts.whileAttached(host) {
                    if (!hosts.initiating(host) || !store.effectTargetValid(effect)) throw CancellationException()
                    val granted = requestLocation(effect)
                    if (!hosts.awaitResume(host) || !store.effectTargetValid(effect)) throw CancellationException()
                    granted
                }
            } else true
        }) { effect, _, permitted ->
            val capability = effect.request.capability
            if (!permitted || !store.permissionGranted(capability)) DeviceReadContract.denied(capability)
            else try { read?.invoke(effect.request) ?: deviceReader.read(effect.request) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: SecurityException) { DeviceReadContract.denied(capability) }
                catch (_: RuntimeException) { DeviceReadContract.invalid() }
        }

    internal suspend fun executeAudio(key: String, host: DeviceReadHost, hosts: DeviceReadHosts,
        audio: AudioCapabilities = AudioCapabilities(context,store)): CapabilityResult? =
        executeForegroundEffect(key,host,hosts,AudioContract.playbackIds, prepare = { true }) { effect, active, _ ->
            audio.execute(effect,active)
        }

    internal suspend fun executeAi(key: String, host: DeviceReadHost, hosts: DeviceReadHosts,
        ask: suspend (String, () -> Boolean) -> CapabilityResult): CapabilityResult? =
        executeForegroundEffect(key, host, hosts, setOf(MiniAppAi.ID), requireFocus = true, prepare = { true }) { effect, active, _ ->
            ask(effect.request.args.getString("prompt")) { active() && store.effectTargetValid(effect) }
        }

    internal suspend fun executeImage(prompt: CapabilityPrompt.Image, hosts: DeviceReadHosts,
        images: ImageGeneration): CapabilityResult? = images.lease().use {
        val resources = CapabilityResources(context, store)
        var resource: CapabilityResource? = null
        executeForegroundEffect(prompt.effectKey, prompt.origin, hosts, setOf(MiniAppImage.ID),
            requireFocus = true, requireOrigin = true, prepare = { true }, resource = { resource },
            publishAllowed = { images.settings.isCurrent(prompt.selection) }) { effect, active, _ ->
            if (MiniAppImage.intent(effect) != prompt.intent || !active() || !store.effectTargetValid(effect))
                return@executeForegroundEffect ForegroundCapabilityEffects.cancelled(MiniAppImage.ID)
            if (!images.settings.isCurrent(prompt.selection))
                return@executeForegroundEffect CapabilityResult("denied", context.getString(R.string.image_disabled))
            resources.reclaimUnavailable(effect.owner)
            if (store.resources(effect.owner).size + store.acquisitions().count { it.owner == effect.owner } >= CapabilityResources.MAX_RESOURCES)
                return@executeForegroundEffect CapabilityResult("unavailable", context.getString(R.string.image_resource_limit))
            try {
                val image = images.generate("runtime:${effect.key}", listOf(prompt.intent), prompt.selection, 1).single()
                images.ensureCurrent(prompt.selection)
                val acquired = resources.generatedImage(effect.owner, image)
                resource = acquired
                CapabilityResult("succeeded", "Изображение сохранено.", acquired.metadata())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: ImageFailure) {
                CapabilityResult(if (failure.unknownOutcome) "unknownOutcome" else "failed",
                    failure.message ?: context.getString(R.string.image_provider_error))
            } catch (_: Exception) {
                CapabilityResult("failed", context.getString(R.string.image_provider_error))
            }
        }
    }

    /** The ledger owns claim and finish. Android preparation and playback run outside the effect mutex. */
    private suspend fun executeForegroundEffect(key: String, host: DeviceReadHost, hosts: DeviceReadHosts, ids: Set<String>,
        requireFocus: Boolean = false, requireOrigin: Boolean = false,
        resource: (() -> CapabilityResource?)? = null, publishAllowed: () -> Boolean = { true },
        prepare: suspend (CapabilityEffect) -> Boolean?,
        execute: suspend (CapabilityEffect, () -> Boolean, Boolean) -> CapabilityResult): CapabilityResult? {
        val effect = ToolStore.effectMutex.withLock {
            val pending = store.effect(key) ?: return null
            if (pending.owner != host.owner || pending.revision != host.revision || pending.request.capability !in ids) return null
            if (!hosts.initiating(host, requireFocus)) return store.rejectPendingEffect(key, ForegroundCapabilityEffects.cancelled(pending.request.capability))
            store.claimEffect(key) ?: return store.effect(key)?.result
        }
        val cancelled = ForegroundCapabilityEffects.cancelled(effect.request.capability)
        suspend fun finish(result: CapabilityResult, active: () -> Boolean = { true }): CapabilityResult = ToolStore.effectMutex.withLock {
            store.finishEffect(key, result, { active() && (result.status != "succeeded" || publishAllowed()) }, resource?.invoke())
            store.effect(key)?.result ?: cancelled
        }
        try {
            val permitted = prepare(effect) ?: return finish(cancelled)
            return hosts.whileVisible(host, requireFocus, requireOrigin) { active ->
                val result = if (!active() || !store.effectTargetValid(effect)) cancelled else execute(effect,active,permitted)
                currentCoroutineContext().ensureActive()
                finish(result, active)
            } ?: finish(cancelled)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { runCatching { finish(cancelled) } }
            throw cancel
        }
    }

    internal suspend fun executeCalendarDeleteAll(key: String,snapshot: OwnedCalendarSnapshot,foreground: ()->Boolean): CapabilityResult =
        executeOwned(key) {effect -> withContext(Dispatchers.Main.immediate) {
            OwnedCalendarCapabilities(context,store).deleteAll(effect,snapshot,foreground)
        }}
    private suspend fun executeClaimed(key: String, adapter: suspend (CapabilityEffect) -> CapabilityResult): CapabilityResult {
        val effect = store.claimEffect(key) ?: return store.effect(key)?.result ?: CapabilityResult("unavailable","Системное действие недоступно.")
        val result = if(effect.request.capability in ForegroundCapabilityEffects.executionIds) ForegroundCapabilityEffects.cancelled(effect.request.capability)
        else if(!store.effectTargetValid(effect)) CapabilityResult("cancelled","Мини-приложение изменилось.")
        else try { adapter(effect.copy(request=CapabilityCatalog.request(effect.request.json()))) }
        catch(cancel: CancellationException) {
            store.finishEffect(key,CapabilityResult("unknownOutcome","The native action was interrupted. Check its outcome before another request."))
            throw cancel
        }
        catch(_: SecurityException) { CapabilityResult("denied","Android не разрешил действие. Разрешите доступ в настройках Android и повторите.") }
        catch(_: Exception) { CapabilityResult("unknownOutcome","Системная функция не подтвердила результат. Проверь его перед повторным действием.") }
        store.finishEffect(key,result)
        return checkNotNull(store.effect(key)).result
    }
}
