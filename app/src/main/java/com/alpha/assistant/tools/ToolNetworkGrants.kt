package com.alpha.assistant.tools

import android.content.Context
import com.alpha.assistant.capabilities.NetworkEffectContract
import com.alpha.assistant.network.MiniAppNetwork
import com.alpha.assistant.network.NetworkGrant
import com.alpha.assistant.network.NetworkGrantPersistence
import com.alpha.assistant.network.NetworkFailure
import com.alpha.assistant.network.NetworkInvocation
import com.alpha.assistant.network.NetworkOwner
import com.alpha.assistant.network.NetworkRequest
import com.alpha.assistant.network.NetworkResponse
import com.alpha.assistant.network.NetworkResult
import com.alpha.assistant.network.NetworkScope
import com.alpha.assistant.network.ui.MiniAppNetworkAccessApp
import com.alpha.assistant.network.ui.MiniAppNetworkAccessHost
import com.alpha.assistant.scheduling.SourceRefresh
import com.alpha.assistant.scheduling.SourceRefreshJobService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class ToolNetworkGrantEntry(val id: String, val spec: String, val granted: Boolean)

/** No read or write can rebind an old grant adapter to a new physical library. */
internal class ToolNetworkGrantPersistence(private val context: Context, private val library: ToolLibrarySelection) : NetworkGrantPersistence {
    val databaseId: String = context.getDatabasePath(library.physical).absolutePath

    inline fun <T> withStore(block: (ToolStore) -> T): T = ToolStore(context,library.physical).use { store ->
        if (store.library != library) throw ToolLibraryUnavailable()
        block(store)
    }

    override fun loadEnabled(): Result<Set<NetworkOwner>> = runCatching {
        withStore { store -> store.networkGrantedOwners().mapTo(mutableSetOf()) { NetworkOwner(databaseId,it) } }
    }

    override fun setEnabled(owner: NetworkOwner, enabled: Boolean): Result<Unit> = runCatching {
        require(owner.databaseId == databaseId)
        withStore { it.setNetworkGrant(owner.miniAppId,enabled) }
    }
}

/** Shared by controllers. Keep local revocation in memory across controller recreation. */
internal object ToolNetworkGrants {
    private val active = ConcurrentHashMap<String,ToolNetworkAuthority>()

    fun authority(store: ToolStore): ToolNetworkAuthority = ToolLibraryStorage.open(store.context,store.library) {
        active.computeIfAbsent(store.artifactIdentity) { ToolNetworkAuthority(store.context.applicationContext,store.library) }
    }

    fun invalidateApp(store: ToolStore, id: String) { active[store.artifactIdentity]?.invalidateApp(id) }
    fun invalidateBackground(store: ToolStore, id: String) { active[store.artifactIdentity]?.invalidateBackground(id) }

    fun retireLibrary(context: Context, library: ToolLibrarySelection) {
        active.remove(context.getDatabasePath(library.physical).absolutePath)?.retire()
    }
}

internal class ToolNetworkAuthority(context: Context, library: ToolLibrarySelection,
    persistence: NetworkGrantPersistence? = null) {
    private val store = ToolNetworkGrantPersistence(context,library)
    private val destinations = AndroidMiniAppNetworkAccess(context)
    private val grants = persistence ?: store
    private val accessWrites = Mutex()
    private val sessionLock = Any()
    private val uiScope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val apps = MutableStateFlow<List<MiniAppNetworkAccessApp>>(emptyList())
    private val access = MiniAppNetworkAccessHost(apps.asStateFlow(),uiScope,::setGranted)
    @Volatile private var network: MiniAppNetwork? = null
    @Volatile private var retired = false

    private class Active(val controller: Any, val sessionId: String, val grant: NetworkGrant)
    private val sessions = mutableMapOf<Pair<String, Boolean>,Active>()
    private val revoking = mutableMapOf<String,Any>()

    suspend fun refreshAccess(): MiniAppNetworkAccessHost? = withContext(Dispatchers.IO) {
        accessWrites.withLock {
            if (retired) return@withLock null
            try {
                val current = store.withStore { it.networkGrantEntries() }
                if (network == null) network = MiniAppNetwork(NetworkEffectContract.limits,grants,destinationAccess=destinations)
                val entries = current.map { MiniAppNetworkAccessApp(it.id,ToolSpec.storedTitle(it.spec),it.granted) }
                synchronized(sessionLock) {
                    if (retired) null else { apps.value = entries; access }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                null
            }
        }
    }

    suspend fun setGranted(id: String, enabled: Boolean): Result<Unit> {
        // Fence this owner before waiting for an unrelated write or for its artifact queue.
        val revocation = if (enabled || network == null) null else synchronized(sessionLock) {
            if (retired) null else Any().also {
                revoking[id] = it
                invalidateApp(id)
            }
        }
        return withContext(Dispatchers.IO) {
            accessWrites.withLock {
                val authority = network
                if (retired || authority == null) return@withLock Result.failure(ToolLibraryUnavailable())
                try {
                    store.withStore { tools ->
                        ArtifactCoordinator.get(tools,id).ordered {
                            if (retired) return@ordered Result.failure(ToolLibraryUnavailable())
                            val owner = NetworkOwner(store.databaseId,id)
                            val scheduled = if (enabled) null else SourceRefresh(tools).ticket(id)
                            val result = if (enabled) authority.enable(owner) else authority.revoke(owner).also {
                                synchronized(sessionLock) { if (revoking[id] === revocation) revoking.remove(id) }
                            }
                            if (result.isSuccess && scheduled != null) {
                                runCatching { SourceRefreshJobService.cancel(tools, scheduled) }
                            }
                            if (result.isSuccess) synchronized(sessionLock) {
                                if (!retired) apps.value = apps.value.map { if (it.id == id) it.copy(granted=enabled) else it }
                            }
                            result
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    Result.failure(failure)
                }
            }
        }
    }

    /** Admission is for a current saved mini-app and a native host session. It does not execute a request. */
    suspend fun activate(expected: ToolRecord, controller: Any, sessionId: String,
        background: Boolean = false, hostActive: () -> Boolean = { true }): NetworkGrant? =
        withContext(Dispatchers.IO) {
            val authority = network ?: return@withContext null
            if (retired || !hostActive()) return@withContext null
            try {
                store.withStore { tools ->
                    ArtifactCoordinator.get(tools,expected.id).ordered {
                        val current = tools.tool(expected.id) ?: return@ordered null
                        if (!current.saved || current.archived || current.revision != expected.revision ||
                            current.stateVersion != expected.stateVersion || current.createdAt != expected.createdAt ||
                            !tools.networkGrantEnabled(current.id)) return@ordered null
                        synchronized(sessionLock) {
                            if (retired || current.id in revoking || !hostActive()) return@synchronized null
                            val lane = current.id to background
                            val prior = sessions[lane]
                            if (prior != null && prior.controller === controller && prior.sessionId == sessionId) return@synchronized prior.grant
                            val scope = NetworkScope(NetworkOwner(store.databaseId,current.id),current.revision.toString(),
                                UUID.randomUUID().toString(), background)
                            authority.activate(scope)?.also { sessions[lane] = Active(controller,sessionId,it) }
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                null
            }
        }

    fun isCurrent(grant: NetworkGrant): Boolean = synchronized(sessionLock) {
        !retired && sessions[grant.scope.owner.miniAppId to grant.scope.background]?.grant === grant
    }

    /** One-use ordering point. Never perform transport or SQLite work while the permit lock is held. */
    fun publicationPermit(grant: NetworkGrant, active: () -> Boolean): () -> Boolean {
        var used=false
        return { synchronized(sessionLock) {
            if (used || !isCurrent(grant) || !active()) false else { used=true; true }
        } }
    }

    suspend fun exchange(grant: NetworkGrant, key: String, request: NetworkRequest,
        terminal: (NetworkResult) -> Unit): ToolNetworkExchange = withContext(Dispatchers.IO) {
        val transport=network ?: return@withContext ToolNetworkExchange(NetworkResult.Unavailable,null)
        if (!isCurrent(grant)) return@withContext ToolNetworkExchange(NetworkResult.Revoked,null)
        val invocation=NetworkInvocation(grant.scope,key,grant)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { transport.cancel(invocation) }
            var response: NetworkResponse?=null
            val result=try { transport.execute(invocation,request) {response=it} }
            catch (_: RuntimeException) {
                val failure=NetworkResult.Failed(NetworkFailure.TRANSPORT)
                if(request.method.safe) failure else NetworkResult.UnknownOutcome(failure)
            }
            terminal(result)
            continuation.resumeWith(Result.success(ToolNetworkExchange(result,response)))
        }
    }

    fun invalidate(grant: NetworkGrant) = synchronized(sessionLock) {
        val lane = grant.scope.owner.miniAppId to grant.scope.background
        if (sessions[lane]?.grant === grant) {
            sessions.remove(lane)
            network?.invalidate(grant.scope)
        }
    }

    fun invalidateApp(id: String) = synchronized(sessionLock) {
        sessions.values.filter { it.grant.scope.owner.miniAppId == id }.forEach { invalidate(it.grant) }
        Unit
    }

    fun invalidateBackground(id: String) = synchronized(sessionLock) {
        sessions[id to true]?.let { invalidate(it.grant) }
        Unit
    }

    fun closeSessions(controller: Any) = synchronized(sessionLock) {
        sessions.values.filter { it.controller === controller }.forEach { invalidate(it.grant) }
    }

    fun retire() {
        synchronized(sessionLock) {
            retired = true
            sessions.values.toList().forEach { invalidate(it.grant) }
            revoking.clear()
            apps.value = emptyList()
        }
        uiScope.cancel()
    }

}
