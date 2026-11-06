package com.alpha.assistant.network

import android.net.DnsResolver
import android.net.InetAddresses
import android.os.CancellationSignal
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.SocketFactory

/** Host authority for mini-app requests. Activation tokens and operations live in this process. */
internal class MiniAppNetwork(
    private val limits: NetworkLimits,
    private val persistence: NetworkGrantPersistence,
    events: EventListener = EventListener.NONE,
    private val destinationAccess: NetworkDestinationAccess = closedAccess,
    private val dns: NetworkDns = platformNetworkDns
) {
    private val lock = Any()
    private val persistenceLock = Any()
    private val enabledOwners = persistence.loadEnabled().getOrThrow().toMutableSet()
    private var nextEpoch = 0L
    private val grants = mutableMapOf<Pair<NetworkOwner, Boolean>, NetworkGrant>()
    private val active = mutableMapOf<NetworkInvocation, Operation>()
    private val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis)
    private val baseClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .eventListener(events)
        .build()

    fun isEnabled(owner: NetworkOwner): Boolean = synchronized(lock) { owner in enabledOwners }

    /** Persist an enable action from the host. */
    fun enable(owner: NetworkOwner): Result<Unit> = synchronized(persistenceLock) {
        persistence.setEnabled(owner, true).onSuccess {
            synchronized(lock) { enabledOwners.add(owner) }
        }
    }

    /** Activate the host's current scope if its owner has a grant. */
    fun activate(scope: NetworkScope): NetworkGrant? {
        val (grant, calls) = synchronized(lock) {
            if (scope.owner !in enabledOwners) return null
            val current = grants[scope.lane]
            if (current?.scope == scope) return current
            val grant = NetworkGrant(scope, ++nextEpoch)
            grants[scope.lane] = grant
            grant to stopOperations { it.scope.lane == scope.lane }
        }
        calls.forEach(Call::cancel)
        return grant
    }

    /**
     * Stop local work before the durable write.
     * The host must show a failed write and offer a retry for revocation across restarts.
     */
    fun revoke(owner: NetworkOwner): Result<Unit> = synchronized(persistenceLock) {
        val calls = synchronized(lock) {
            enabledOwners.remove(owner)
            grants.keys.removeAll { it.first == owner }
            stopOperations { it.scope.owner == owner }
        }
        calls.forEach(Call::cancel)
        persistence.setEnabled(owner, false)
    }

    fun invalidate(scope: NetworkScope) {
        val calls = synchronized(lock) {
            if (grants[scope.lane]?.scope == scope) grants.remove(scope.lane)
            stopOperations { it.scope == scope }
        }
        calls.forEach(Call::cancel)
    }

    fun cancel(invocation: NetworkInvocation) {
        val call = synchronized(lock) {
            invocation.cancelled.set(true)
            active[invocation]?.stop(NetworkResult.Cancelled)
        }
        call?.cancel()
    }

    /**
     * Run on a host worker thread. Capture the immutable response in the callback.
     * The callback runs inside the grant gate. Publish through the host after this method returns.
     */
    fun execute(
        invocation: NetworkInvocation,
        request: NetworkRequest,
        capture: (NetworkResponse) -> Unit
    ): NetworkResult {
        val operation = synchronized(lock) {
            if (invocation.cancelled.get()) return NetworkResult.Cancelled
            val grant = invocation.grant
            if (grant == null || grant.scope != invocation.scope) return NetworkResult.Denied
            if (grants[invocation.scope.lane] !== grant) return NetworkResult.Revoked
            if (active.size >= limits.maxConcurrent || invocation in active) return NetworkResult.Unavailable
            Operation().also { active[invocation] = it }
        }
        try {
            val response = try {
                fetch(operation, prepare(request), request.method.safe, request.followRedirects)
            } catch (problem: RequestStopped) {
                return finish(operation, problem.reason)
            } catch (problem: IOException) {
                val reason = when (problem) {
                    is UnknownHostException, is ConnectException, is NoRouteToHostException -> NetworkResult.Unavailable
                    else -> NetworkResult.Failed(NetworkFailure.TRANSPORT)
                }
                return finish(operation, reason)
            }
            return synchronized(lock) {
                stopReason(operation)?.let { return stoppedResult(operation, it) }
                capture(response)
                NetworkResult.Delivered(response.status)
            }
        } finally {
            synchronized(lock) { active.remove(invocation) }
        }
    }

    private fun fetch(operation: Operation, initialRequest: Request, safe: Boolean, followRedirects: Boolean): NetworkResponse {
        val pool = ConnectionPool(0, 1, TimeUnit.SECONDS)
        try {
            var request = initialRequest
            var redirects = 0
            while (true) {
                val target = NetworkTarget(request.url.scheme, request.url.host, request.url.port)
                requireAccess(operation) { destinationAccess.checkTarget(target) }
                val client = baseClient.newBuilder()
                    .connectionPool(pool)
                    .dns { host -> resolve(operation, host) }
                    .socketFactory(CheckedSocketFactory { peer -> requireDestination(operation, target, peer) })
                    .addNetworkInterceptor { chain ->
                        val peer = chain.connection()?.socket()?.remoteSocketAddress as? InetSocketAddress
                            ?: fail(NetworkFailure.TRANSPORT)
                        requireDestination(operation, target, peer)
                        synchronized(lock) {
                            requireActive(operation)
                            if (!safe) {
                                if (operation.writeMayHaveStarted) fail(NetworkFailure.TRANSPORT)
                                operation.writeMayHaveStarted = true
                            }
                        }
                        chain.proceed(chain.request())
                    }
                    .build()
                val call = client.newCall(request)
                synchronized(lock) {
                    requireActive(operation)
                    call.timeout().timeout(remainingNanos(operation).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    operation.call = call
                }
                request = call.execute().use { response ->
                    if (response.headers.byteCount() > limits.maxMetadataBytes) fail(NetworkFailure.RESPONSE_LIMIT)
                    val locations = response.headers.values("Location")
                    if (!safe || response.code !in redirectCodes || locations.isEmpty()) {
                        return readResponse(response)
                    }
                    if (!followRedirects) fail(NetworkFailure.REDIRECT_TARGET)
                    if (redirects >= limits.maxRedirects) fail(NetworkFailure.REDIRECT_LIMIT)
                    if (locations.size != 1) fail(NetworkFailure.REDIRECT_TARGET)
                    redirects++
                    redirectRequest(request, response.code, locations.single())
                }
            }
        } finally {
            pool.evictAll()
        }
    }

    private fun resolve(operation: Operation, host: String): List<InetAddress> {
        val answer = CompletableFuture<List<InetAddress>>()
        val cancellation = CancellationSignal()
        synchronized(lock) {
            requireActive(operation)
            operation.dnsAnswer = answer
        }
        try {
            if (!answer.isDone) dns.query(host, cancellation) { answer.complete(it) }
            val addresses = answer.get(remainingNanos(operation), TimeUnit.NANOSECONDS)
            synchronized(lock) { requireActive(operation) }
            if (addresses.isEmpty()) throw UnknownHostException(host)
            return addresses
        } catch (_: TimeoutException) {
            throw RequestStopped(NetworkResult.Failed(NetworkFailure.TIMEOUT))
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(interrupted)
        } finally {
            cancellation.cancel()
            synchronized(lock) { operation.dnsAnswer = null }
        }
    }

    private fun requireDestination(operation: Operation, target: NetworkTarget, peer: InetSocketAddress) {
        requireAccess(operation) { destinationAccess.checkDestination(target, peer) }
    }

    private fun requireAccess(operation: Operation, check: () -> NetworkAccessDenial?) {
        synchronized(lock) { requireActive(operation) }
        val denial = check()
        synchronized(lock) {
            requireActive(operation)
            if (denial != null) throw RequestStopped(NetworkResult.AccessDenied(denial))
        }
    }

    private fun prepare(request: NetworkRequest): Request {
        val suppliedBody = request.body
        if (request.method.safe && suppliedBody != null) fail(NetworkFailure.INVALID_REQUEST)
        if ((suppliedBody?.size ?: 0) > limits.maxRequestBytes) fail(NetworkFailure.REQUEST_LIMIT)
        if (request.url.length > limits.maxMetadataBytes) fail(NetworkFailure.REQUEST_LIMIT)
        val url = parseUrl(request.url) ?: fail(NetworkFailure.INVALID_REQUEST)
        val headers = Headers.Builder()
        var metadataBytes = url.toString().length.toLong()
        for (header in request.headers) {
            if (!headerName.matches(header.name) || header.name.lowercase(Locale.ROOT) in reservedHeaders) {
                fail(NetworkFailure.INVALID_REQUEST)
            }
            metadataBytes += header.name.length.toLong() + header.value.length + 4
            if (metadataBytes > limits.maxMetadataBytes) fail(NetworkFailure.REQUEST_LIMIT)
            try {
                headers.add(header.name, header.value)
            } catch (_: IllegalArgumentException) {
                fail(NetworkFailure.INVALID_REQUEST)
            }
        }
        if (metadataBytes > limits.maxMetadataBytes) fail(NetworkFailure.REQUEST_LIMIT)
        val body = if (request.method.safe) null else SingleWriteBody(suppliedBody?.copyOf() ?: ByteArray(0))
        return Request.Builder().url(url).headers(headers.build()).method(request.method.name, body).build()
    }

    private fun redirectRequest(request: Request, status: Int, location: String): Request {
        val target = parseUrl(location, request.url) ?: fail(NetworkFailure.REDIRECT_TARGET)
        val sameOrigin = request.url.scheme == target.scheme && request.url.host == target.host &&
            request.url.port == target.port
        val next = request.newBuilder().url(target)
        if (!sameOrigin) next.headers(Headers.EMPTY)
        if (status == 303 && request.method != "HEAD") next.get()
        val redirected = next.build()
        if (target.toString().length + redirected.headers.byteCount() > limits.maxMetadataBytes) {
            fail(NetworkFailure.REQUEST_LIMIT)
        }
        return redirected
    }

    private fun parseUrl(value: String, base: HttpUrl? = null): HttpUrl? {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        if ((uri.isAbsolute || value.startsWith("//")) && uri.rawAuthority == null) return null
        if (uri.isAbsolute && uri.scheme.lowercase(Locale.ROOT) !in webSchemes) return null
        if (uri.rawAuthority?.contains('@') == true) return null
        val parsed = if (base == null) value.toHttpUrlOrNull() else base.resolve(value)
        return parsed?.newBuilder()?.fragment(null)?.build()
    }

    private fun readResponse(response: Response): NetworkResponse {
        val body = response.body
        if (body.contentLength() > limits.maxResponseBytes) fail(NetworkFailure.RESPONSE_LIMIT)
        val buffer = Buffer()
        val source = body.source()
        while (true) {
            val allowance = limits.maxResponseBytes + 1L - buffer.size
            if (source.read(buffer, minOf(allowance, 8_192L)) == -1L) break
            if (buffer.size > limits.maxResponseBytes) fail(NetworkFailure.RESPONSE_LIMIT)
        }
        val headers = response.headers.map { (name, value) -> NetworkHeader(name, value) }
        return NetworkResponse(response.code, headers, buffer.readByteArray())
    }

    private fun stopOperations(matches: (NetworkInvocation) -> Boolean): List<Call> =
        active.mapNotNull { (invocation, operation) ->
            if (matches(invocation)) operation.stop(NetworkResult.Revoked) else null
        }

    private fun requireActive(operation: Operation) {
        stopReason(operation)?.let { throw RequestStopped(it) }
    }

    private fun stopReason(operation: Operation): NetworkResult.Stopped? =
        operation.stopped ?: if (remainingNanos(operation) <= 0) NetworkResult.Failed(NetworkFailure.TIMEOUT) else null

    private fun remainingNanos(operation: Operation): Long = timeoutNanos - (System.nanoTime() - operation.started)

    private fun finish(operation: Operation, failure: NetworkResult.Stopped): NetworkResult = synchronized(lock) {
        stoppedResult(operation, stopReason(operation) ?: failure)
    }

    private fun stoppedResult(operation: Operation, reason: NetworkResult.Stopped): NetworkResult =
        if (operation.writeMayHaveStarted) NetworkResult.UnknownOutcome(reason) else reason

    private class Operation {
        val started = System.nanoTime()
        var stopped: NetworkResult.Stopped? = null
        var call: Call? = null
        var dnsAnswer: CompletableFuture<List<InetAddress>>? = null
        var writeMayHaveStarted = false

        fun stop(reason: NetworkResult.Stopped): Call? {
            if (stopped != null) return null
            stopped = reason
            dnsAnswer?.complete(emptyList())
            return call
        }
    }

    private class RequestStopped(val reason: NetworkResult.Stopped) : IOException()

    private class CheckedSocketFactory(private val check: (InetSocketAddress) -> Unit) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val peer = endpoint as? InetSocketAddress ?: fail(NetworkFailure.TRANSPORT)
                if (peer.isUnresolved) fail(NetworkFailure.TRANSPORT)
                check(peer)
                super.connect(peer, timeout)
            }
        }

        override fun createSocket(host: String, port: Int): Socket = fail(NetworkFailure.TRANSPORT)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            fail(NetworkFailure.TRANSPORT)
        override fun createSocket(host: InetAddress, port: Int): Socket = fail(NetworkFailure.TRANSPORT)
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
            fail(NetworkFailure.TRANSPORT)
    }

    private class SingleWriteBody(private val bytes: ByteArray) : RequestBody() {
        override fun contentType() = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
    }

    private companion object {
        val closedAccess = object : NetworkDestinationAccess {
            override fun checkTarget(target: NetworkTarget) = NetworkAccessDenial.POLICY_UNAVAILABLE
            override fun checkDestination(target: NetworkTarget, peer: InetSocketAddress) =
                NetworkAccessDenial.POLICY_UNAVAILABLE
        }
        val webSchemes = setOf("http", "https")
        val redirectCodes = setOf(301, 302, 303, 307, 308)
        val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val reservedHeaders = setOf(
            "accept-encoding", "connection", "content-length", "expect", "host", "keep-alive",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade"
        )

        fun fail(reason: NetworkFailure): Nothing = throw RequestStopped(NetworkResult.Failed(reason))
    }
}

internal fun interface NetworkDns {
    fun query(host: String, cancellation: CancellationSignal, reply: (List<InetAddress>) -> Unit)
}

internal val platformNetworkDns = NetworkDns { host, cancellation, reply ->
    when {
        InetAddresses.isNumericAddress(host) -> reply(listOf(InetAddresses.parseNumericAddress(host)))
        host.trimEnd('.').equals("localhost", ignoreCase = true) ->
            reply(listOf(InetAddresses.parseNumericAddress("127.0.0.1"), InetAddresses.parseNumericAddress("::1")))
        else -> DnsResolver.getInstance().query(
            null, host, DnsResolver.FLAG_EMPTY, Executor { it.run() }, cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    reply(if (rcode == 0) answer else emptyList())
                }

                override fun onError(error: DnsResolver.DnsException) {
                    reply(emptyList())
                }
            }
        )
    }
}
