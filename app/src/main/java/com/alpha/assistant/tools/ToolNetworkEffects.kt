package com.alpha.assistant.tools

import com.alpha.assistant.capabilities.DeviceReadHost
import com.alpha.assistant.capabilities.DeviceReadHosts
import com.alpha.assistant.capabilities.NetworkEffectContract
import com.alpha.assistant.network.NetworkGrant
import com.alpha.assistant.network.NetworkOwner
import com.alpha.assistant.network.NetworkRequest
import com.alpha.assistant.network.NetworkResponse
import com.alpha.assistant.network.NetworkResult
import com.alpha.assistant.network.livesource.LiveSourceEffect
import com.alpha.assistant.scheduling.SourceOffer
import com.alpha.assistant.scheduling.SourceRefresh
import com.alpha.assistant.surfaces.ToolWidgets
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ToolNetworkExchange(val result: NetworkResult, val response: NetworkResponse?)

/** A claimed action owns its exact request and result only for this foreground operation. */
internal class ToolNetworkEffects(private val store: ToolStore, private val engine: ToolEngine,
    private val authority: ToolNetworkAuthority?,
    private val exchange: (suspend (NetworkGrant,String,NetworkRequest,(NetworkResult)->Unit) -> ToolNetworkExchange)? = null) {

    suspend fun execute(action: ToolForegroundAction, host: DeviceReadHost?, hosts: DeviceReadHosts, controller: Any) {
        val prepared=checkNotNull(action.network)
        val validated=NetworkEffectContract.request(prepared.args)
        val tool=action.tool
        val source = LiveSourceEffect.declaration(prepared.args, NetworkOwner(store.artifactIdentity, tool.id), prepared.id)
        val offer = SourceOffer.from(tool, prepared.id, prepared.args)
        val refresh = SourceRefresh(store)
        val request = if (offer == null) validated else NetworkRequest(validated.url, followRedirects = false)
        val key="${tool.id}:${tool.revision}:${tool.stateVersion}:${NetworkEffectContract.RECEIPT_ID}"
        val effect=ToolStore.effectMutex.withLock {
            val pending=store.effect(key) ?: return
            if(pending.request.capability!=NetworkEffectContract.ID || pending.request.args.getString("method")!=request.method.name) return
            store.claimEffect(key) ?: return
        }
        val transportOutcome=AtomicReference<NetworkResult?>()
        var invoked=false
        var grant: NetworkGrant?=null
        try {
            if(host==null || host.owner!=tool.id || host.revision!=tool.revision || !hosts.initiating(host)) return
            hosts.whileVisible(host) { visible ->
                grant=authority?.activate(tool,controller,UUID.randomUUID().toString(),hostActive=visible)
                val accepted=grant
                val active={visible() && (accepted==null || authority?.isCurrent(accepted)==true)}
                if(!active() || !store.effectTargetValid(effect)) return@whileVisible
                val sourceRun = if (offer == null || accepted == null) null else refresh.begin(offer, active)
                val outcome=when {
                    authority==null -> NetworkEffectContract.receiptResult("unavailable")
                    accepted==null -> NetworkEffectContract.receiptResult("denied")
                    offer!=null && sourceRun==null -> NetworkEffectContract.receiptResult("denied")
                    else -> {
                        invoked=true
                        val exchanged=if(exchange==null) authority.exchange(accepted,key,request,transportOutcome::set)
                            else exchange.invoke(accepted,key,request,transportOutcome::set)
                        NetworkEffectContract.outcome(exchanged.result,exchanged.response,source)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (sourceRun != null && invoked && refresh.record(sourceRun, outcome.data.takeIf { outcome.status == "succeeded" }, active)
                    && store.primaryLibrary) ToolWidgets.updateAll(store.context)
                ToolStore.effectMutex.withLock {
                    val receipt=if(accepted!=null && !active() && outcome.status!="unknownOutcome")
                        NetworkEffectContract.receiptResult("revoked") else outcome
                    store.finishEffect(key,receipt,visible)
                }
                if(active() && store.effectTargetValid(effect)) {
                    val deniedPermit=AtomicBoolean()
                    val permit=if(accepted==null) ({ active() && deniedPermit.compareAndSet(false,true) })
                        else checkNotNull(authority).publicationPermit(accepted,visible)
                    engine.deliverNetworkEffectResult(tool,effect.copy(request=effect.request.copy(id=prepared.id)),outcome,active,permit)
                }
            }
        } finally {
            grant?.let {authority?.invalidate(it)}
            withContext(NonCancellable) {
                runCatching {
                    ToolStore.effectMutex.withLock {
                        val observed=transportOutcome.get()
                        val status=if(observed is NetworkResult.UnknownOutcome || invoked && observed==null && !request.method.safe)
                            "unknownOutcome" else "cancelled"
                        store.finishEffect(key,NetworkEffectContract.receiptResult(status))
                        store.rejectForegroundEffectDelivery(tool.id,tool.revision,tool.stateVersion)
                    }
                }
            }
        }
    }
}
