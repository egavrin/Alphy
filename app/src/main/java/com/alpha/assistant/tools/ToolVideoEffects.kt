package com.alpha.assistant.tools

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.alpha.assistant.capabilities.CapabilityEffect
import com.alpha.assistant.capabilities.CapabilityResource
import com.alpha.assistant.capabilities.CapabilityResources
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.capabilities.VideoContract
import com.alpha.assistant.media.DeviceMediaPlayback
import com.alpha.assistant.media.MediaPlaybackResult
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
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** The player and surface are borrowed from one exact mounted host. */
internal class ToolVideoEffects(context: Context, private val store: ToolStore, private val engine: ToolEngine,
    private val openDescriptor: (suspend (CapabilityResource) -> AssetFileDescriptor?)? = null) {
    private val resources=CapabilityResources(context,store)

    suspend fun execute(expected: ToolRecord, control: NativeVideoControl, lease: NativeVideoSurfaceLease,
        player: DeviceMediaPlayback, active: () -> Boolean, playing: () -> Unit): CapabilityResult = withContext(Dispatchers.IO) {
        if(!active()) return@withContext VideoContract.cancelled()
        val selected=runCatching {resources.resolveVideo(expected.id,control.resourceId)}.getOrNull()
            ?: return@withContext VideoContract.unavailable()
        val changed=try { engine.interactVideo(expected,control,active) }
        catch(cancel: CancellationException) {throw cancel}
        catch(_: Exception) {return@withContext VideoContract.failed()}
        val pending=store.effects(changed.id).singleOrNull {
            it.revision==changed.revision && it.stateVersion==changed.stateVersion && it.request.capability==VideoContract.PLAY
        } ?: return@withContext VideoContract.cancelled()
        try {
            val effect=ToolStore.effectMutex.withLock {if(active()) store.claimEffect(pending.key) else null}
                ?: return@withContext VideoContract.cancelled()
            val result=play(effect,control,lease,selected,player,active,playing)
            currentCoroutineContext().ensureActive()
            ToolStore.effectMutex.withLock {store.finishEffect(effect.key,result,active)}
            if(active() && store.effectTargetValid(effect)) engine.deliverForegroundEffectResults(changed,active)
            result
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    ToolStore.effectMutex.withLock {
                        store.rejectPendingEffect(pending.key,VideoContract.cancelled())
                        store.finishEffect(pending.key,VideoContract.cancelled())
                        store.rejectForegroundEffectDelivery(changed.id,changed.revision,changed.stateVersion)
                    }
                }
            }
        }
    }

    private suspend fun play(effect: CapabilityEffect, control: NativeVideoControl, lease: NativeVideoSurfaceLease,
        selected: CapabilityResource, player: DeviceMediaPlayback, active: () -> Boolean, playing: () -> Unit): CapabilityResult {
        val started=AtomicBoolean()
        fun bindingCurrent(): Boolean {
            val current=store.tool(effect.owner) ?: return false
            if(!current.saved || current.archived || current.revision!=effect.revision ||
                !started.get() && current.stateVersion!=effect.stateVersion) return false
            return runCatching {
                val spec=ToolSpec.parsePersisted(current.spec)
                require(VideoContract.PLAY in spec.capabilities)
                ToolUiCatalog.validateVideoAction(JSONObject(current.ui),control.actionId,control.resourceId,JSONObject(current.state))
            }.isSuccess
        }
        fun selectedCurrent() = runCatching {resources.resolveVideo(effect.owner,selected.id)}.getOrNull()==selected
        var accessLost=false
        suspend fun authorizeStart(start: () -> Unit): Boolean = withContext(Dispatchers.IO) {
            ArtifactCoordinator.get(store,effect.owner).ordered {
                ToolStore.effectMutex.withLock {
                    if(!active() || !bindingCurrent() || !store.effectTargetValid(effect) || store.effect(effect.key)?.phase!="running")
                        return@withLock false
                    if(!selectedCurrent()) {accessLost=true;return@withLock false}
                    withContext(Dispatchers.Main.immediate) {
                        ToolLibraryStorage.open(store.context,store.library) {
                            if(!active() || !lease.isCurrent) return@open false
                            if(!resources.permissionAvailable(selected)) {accessLost=true;return@open false}
                            start()
                            started.set(true)
                            playing()
                            true
                        }
                    }
                }
            }
        }
        return try {
            val result=whileCurrent({active() && bindingCurrent() && selectedCurrent()}) {
                val descriptor=if(openDescriptor==null) resources.openMedia(selected) else openDescriptor.invoke(selected)
                descriptor?.use {player.playVideo(it,lease.surface,::authorizeStart)} ?: MediaPlaybackResult.SOURCE_UNAVAILABLE
            }
            if(accessLost) VideoContract.unavailable() else when(result) {
                MediaPlaybackResult.COMPLETED -> CapabilityResult("succeeded","Проигрыватель сообщил о завершении видео.")
                null, MediaPlaybackResult.CANCELLED, MediaPlaybackResult.CLOSED -> VideoContract.cancelled()
                MediaPlaybackResult.BUSY, MediaPlaybackResult.INVALID_SOURCE, MediaPlaybackResult.SOURCE_UNAVAILABLE,
                MediaPlaybackResult.LIMIT_EXCEEDED -> VideoContract.unavailable()
                MediaPlaybackResult.INVALID_REQUEST, MediaPlaybackResult.TIMED_OUT, MediaPlaybackResult.PLAYBACK_FAILED -> VideoContract.failed()
            }
        } catch(cancel: CancellationException) {throw cancel}
          catch(_: SecurityException) {VideoContract.unavailable()}
          catch(_: java.io.IOException) {VideoContract.unavailable()}
          catch(_: android.os.OperationCanceledException) {VideoContract.cancelled()}
          catch(_: IllegalArgumentException) {VideoContract.unavailable()}
    }

    private suspend fun <T> whileCurrent(current: () -> Boolean, playback: suspend () -> T): T? = coroutineScope {
        val operation=async(start=CoroutineStart.LAZY) {playback()}
        val watcher=launch(start=CoroutineStart.UNDISPATCHED) {
            store.changes.first {!runCatching(current).getOrDefault(false)}
            operation.cancel()
        }
        try {operation.await()}
        catch(_: CancellationException) {currentCoroutineContext().ensureActive();null}
        finally {watcher.cancel()}
    }
}
