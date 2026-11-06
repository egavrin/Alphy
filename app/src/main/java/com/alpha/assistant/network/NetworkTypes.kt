package com.alpha.assistant.network

import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal data class NetworkOwner(val databaseId: String, val miniAppId: String)

/** The host routes all grant writes through one authority for this store. */
internal interface NetworkGrantPersistence {
    fun loadEnabled(): Result<Set<NetworkOwner>>

    /** Commit this owner's state atomically. On failure, keep its last committed state. */
    fun setEnabled(owner: NetworkOwner, enabled: Boolean): Result<Unit>
}

internal data class NetworkScope(val owner: NetworkOwner, val revisionId: String, val sessionId: String,
    val background: Boolean = false) {
    val lane get() = owner to background
}

internal class NetworkGrant internal constructor(val scope: NetworkScope, val epoch: Long)

internal data class NetworkInvocation(
    val scope: NetworkScope,
    val operationId: String,
    val grant: NetworkGrant?
) {
    internal val cancelled = AtomicBoolean()
}

/** Host limits are provisional until measured. Metadata limits apply to each hop. */
internal data class NetworkLimits(
    val maxRequestBytes: Int,
    val maxResponseBytes: Int,
    val maxMetadataBytes: Int,
    val timeoutMillis: Long,
    val maxRedirects: Int,
    val maxConcurrent: Int
) {
    init {
        require(maxRequestBytes >= 0 && maxResponseBytes >= 0 && maxMetadataBytes > 0)
        require(timeoutMillis > 0 && maxRedirects >= 0 && maxConcurrent > 0)
    }
}

internal data class NetworkTarget(val scheme: String, val host: String, val port: Int)

internal enum class NetworkAccessDenial { LOCAL_NETWORK_PERMISSION, CLEARTEXT_POLICY, POLICY_UNAVAILABLE }

/** Check platform access without prompting. The host returns null when access is available. */
internal fun interface NetworkDestinationAccess {
    fun checkTarget(target: NetworkTarget): NetworkAccessDenial? = null
    fun checkDestination(target: NetworkTarget, peer: InetSocketAddress): NetworkAccessDenial?
}

internal enum class NetworkMethod(val safe: Boolean) {
    GET(true), HEAD(true), OPTIONS(true), POST(false), PUT(false), PATCH(false), DELETE(false)
}

internal class NetworkHeader(val name: String, val value: String)

internal class NetworkRequest(
    val url: String,
    val method: NetworkMethod = NetworkMethod.GET,
    val headers: List<NetworkHeader> = emptyList(),
    val body: ByteArray? = null,
    val followRedirects: Boolean = true,
)

internal class NetworkResponse(val status: Int, headers: List<NetworkHeader>, body: ByteArray) {
    val headers: List<NetworkHeader> = Collections.unmodifiableList(ArrayList(headers))
    private val payload = body.copyOf()
    val body: ByteArray get() = payload.copyOf()
}

internal sealed interface NetworkResult {
    data class Delivered(val status: Int) : NetworkResult
    data class UnknownOutcome(val cause: Stopped) : NetworkResult

    sealed interface Stopped : NetworkResult
    data object Denied : Stopped
    data class AccessDenied(val reason: NetworkAccessDenial) : Stopped
    data object Revoked : Stopped
    data object Cancelled : Stopped
    data object Unavailable : Stopped
    data class Failed(val reason: NetworkFailure) : Stopped
}

internal enum class NetworkFailure {
    INVALID_REQUEST, REQUEST_LIMIT, RESPONSE_LIMIT, REDIRECT_TARGET, REDIRECT_LIMIT, TIMEOUT, TRANSPORT
}
