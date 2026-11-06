package com.alpha.assistant.capabilities

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.alpha.assistant.media.DeviceMediaPlayback
import com.alpha.assistant.media.MediaPlaybackResult
import com.alpha.assistant.tools.ArtifactCoordinator
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Opens only selected local audio. The player borrows the descriptor for a single claimed effect. */
internal class AudioCapabilities(context: Context, private val store: ToolStore,
    private val playback: () -> DeviceMediaPlayback = { DeviceMediaPlayback() },
    private val openDescriptor: (suspend (CapabilityResource) -> AssetFileDescriptor?)? = null) {
    private val resources = CapabilityResources(context,store)

    suspend fun execute(effect: CapabilityEffect, active: () -> Boolean): CapabilityResult = withContext(Dispatchers.IO) {
        val request = effect.request
        val selected = if (request.capability == AudioContract.PLAY) {
            runCatching { resources.resolveAudio(effect.owner,request.args.getString("resourceId")) }.getOrNull()
                ?: return@withContext AudioContract.unavailable()
        } else null
        var accessLost = false
        fun current() = store.effectTargetValid(effect) && store.effect(effect.key)?.phase == "running"
        suspend fun authorizeStart(start: () -> Unit): Boolean = withContext(Dispatchers.IO) {
            ArtifactCoordinator.get(store,effect.owner).ordered {
                ToolStore.effectMutex.withLock {
                    if (!active() || !current()) return@withLock false
                    if (selected != null && runCatching { resources.resolveAudio(effect.owner,selected.id) }.getOrNull() != selected) {
                        accessLost = true
                        return@withLock false
                    }
                    withContext(Dispatchers.Main.immediate) onMain@{
                        if (!active()) return@onMain false
                        if (selected != null && !resources.permissionAvailable(selected)) {
                            accessLost = true
                            return@onMain false
                        }
                        start()
                        true
                    }
                }
            }
        }

        val media = playback()
        try {
            val result = whileCurrent(::current) {
                if (selected == null) media.playTone(AudioContract.tones.getValue(request.args.getString("tone")),
                    request.args.getInt("durationMillis"), ::authorizeStart)
                else {
                    val descriptor = if (openDescriptor == null) resources.openMedia(selected) else openDescriptor.invoke(selected)
                    descriptor?.use { media.playAudio(it, ::authorizeStart) } ?: MediaPlaybackResult.SOURCE_UNAVAILABLE
                }
            }
            if (accessLost) return@withContext AudioContract.unavailable()
            when (result) {
                MediaPlaybackResult.COMPLETED -> CapabilityResult("succeeded", if (selected == null)
                    "Длительность звукового сигнала истекла. Слышимость не подтверждена."
                    else "Проигрыватель сообщил о завершении. Слышимость не подтверждена.")
                null, MediaPlaybackResult.CANCELLED, MediaPlaybackResult.CLOSED -> AudioContract.cancelled()
                MediaPlaybackResult.BUSY, MediaPlaybackResult.INVALID_SOURCE, MediaPlaybackResult.SOURCE_UNAVAILABLE,
                MediaPlaybackResult.LIMIT_EXCEEDED -> AudioContract.unavailable()
                MediaPlaybackResult.INVALID_REQUEST, MediaPlaybackResult.TIMED_OUT,
                MediaPlaybackResult.PLAYBACK_FAILED -> AudioContract.failed()
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: android.os.OperationCanceledException) { AudioContract.cancelled() }
          catch (_: SecurityException) { AudioContract.unavailable() }
          catch (_: java.io.IOException) { AudioContract.unavailable() }
          catch (_: IllegalArgumentException) { AudioContract.unavailable() }
        finally { withContext(NonCancellable + Dispatchers.Main.immediate) { media.close() } }
    }

    private suspend fun <T> whileCurrent(current: () -> Boolean, play: suspend () -> T): T? = coroutineScope {
        val operation = async(start = CoroutineStart.LAZY) { play() }
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            store.changes.first { !runCatching(current).getOrDefault(false) }
            operation.cancel()
        }
        try { operation.await() }
        catch (_: CancellationException) { currentCoroutineContext().ensureActive(); null }
        finally { watch.cancel() }
    }
}
