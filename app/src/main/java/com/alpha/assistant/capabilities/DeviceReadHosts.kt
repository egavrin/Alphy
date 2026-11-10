package com.alpha.assistant.capabilities

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** A particular visible native host, scoped to its owning controller and physical library. */
internal class DeviceReadHost(val owner: String, val revision: Int) {
    @Volatile var windowFocused = true
}

internal class DeviceReadHosts(private val available: () -> Boolean) {
    private enum class State { STOPPED, STARTED, RESUMED }
    private val lock = Any()
    private val hosts = MutableStateFlow<Map<DeviceReadHost, State>>(emptyMap())
    private val work = mutableSetOf<Watch>()

    private class Watch(val host: DeviceReadHost, val requireResumed: Boolean, val requireFocus: Boolean, val requireOrigin: Boolean, val job: Job)

    /** null means disposed, covered or locked. false allows an in-flight Android permission dialog. */
    fun update(host: DeviceReadHost, resumed: Boolean?) = set(host,resumed?.let { if (it) State.RESUMED else State.STARTED })

    /** A full-screen Android picker can stop the Activity while the originating route remains attached. */
    fun stop(host: DeviceReadHost) = set(host,State.STOPPED)

    fun updateFocus(host: DeviceReadHost, focused: Boolean) = cancelIneligible { host.windowFocused = focused }

    private fun set(host: DeviceReadHost, state: State?) = cancelIneligible {
        hosts.value = if (state == null) hosts.value - host else hosts.value + (host to state)
    }

    private fun cancelIneligible(update: () -> Unit) {
        val cancelled = synchronized(lock) {
            update()
            work.filterNot(::eligible).also { work.removeAll(it.toSet()) }
        }
        cancelled.forEach { it.job.cancel() }
    }

    fun initiating(host: DeviceReadHost, requireFocus: Boolean = false): Boolean =
        hosts.value[host] == State.RESUMED && (!requireFocus || host.windowFocused) && available()

    suspend fun awaitResume(host: DeviceReadHost): Boolean = hosts.first { it[host] != State.STARTED }[host] == State.RESUMED && available()

    suspend fun awaitPickerReturn(host: DeviceReadHost): Boolean =
        hosts.first { it[host] == State.RESUMED || it[host] == null }[host] == State.RESUMED && available()

    suspend fun <T> whileAttached(host: DeviceReadHost, block: suspend () -> T): T? =
        during(host, requireResumed = false) { block() }

    suspend fun <T> whileVisible(host: DeviceReadHost, requireFocus: Boolean = false, requireOrigin: Boolean = false,
        block: suspend (active: () -> Boolean) -> T): T? = during(host, requireResumed = true, requireFocus, requireOrigin, block)

    private fun eligible(watch: Watch): Boolean = available() && if (watch.requireResumed) {
        hosts.value.any { (host, state) -> state == State.RESUMED && host.owner == watch.host.owner && host.revision == watch.host.revision &&
            (!watch.requireOrigin || host === watch.host) && (!watch.requireFocus || host.windowFocused) }
    } else hosts.value[watch.host]?.let { it != State.STOPPED } == true

    private suspend fun <T> during(host: DeviceReadHost, requireResumed: Boolean, requireFocus: Boolean = false,
        requireOrigin: Boolean = false, block: suspend (() -> Boolean) -> T): T? = coroutineScope {
        lateinit var watch: Watch
        val active = { synchronized(lock) { !watch.job.isCancelled && watch in work && eligible(watch) } }
        val child = async(start = CoroutineStart.LAZY) { if (active()) block(active) else null }
        watch = Watch(host, requireResumed, requireFocus, requireOrigin, child)
        val admitted = synchronized(lock) { eligible(watch) && work.add(watch) }
        if (!admitted) child.cancel()
        try { child.await() }
        catch (_: CancellationException) { currentCoroutineContext().ensureActive(); null }
        finally { synchronized(lock) { work.remove(watch) } }
    }
}
