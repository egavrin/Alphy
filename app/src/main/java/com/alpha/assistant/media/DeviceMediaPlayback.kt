package com.alpha.assistant.media

import android.content.res.AssetFileDescriptor
import android.os.Looper
import android.view.Surface
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException

internal const val MAX_TONE_MILLIS = 1_000
internal const val MAX_CLIP_MILLIS = 30_000
internal const val MAX_MEDIA_BYTES = 32L * 1024 * 1024
private const val CLIP_TIMEOUT_MILLIS = MAX_CLIP_MILLIS + 5_000L

enum class DeviceTone { BEEP, ACKNOWLEDGMENT }

/** Calls the action inline on Main under current host authority. Returns false on denial without a call. */
internal typealias MediaStartAuthorization = suspend (start: () -> Unit) -> Boolean

enum class MediaPlaybackResult {
    COMPLETED,
    CANCELLED,
    CLOSED,
    BUSY,
    INVALID_REQUEST,
    INVALID_SOURCE,
    SOURCE_UNAVAILABLE,
    LIMIT_EXCEEDED,
    TIMED_OUT,
    PLAYBACK_FAILED,
}

/** Owns one playback operation for a foreground host. The host closes it when that authority ends. */
class DeviceMediaPlayback internal constructor(
    private val backend: DeviceMediaBackend,
    private val clipTimeoutMillis: Long = CLIP_TIMEOUT_MILLIS,
) : Closeable {
    constructor() : this(AndroidDeviceMediaBackend)

    private var active: Operation? = null
    private var closed = false

    internal suspend fun playTone(
        tone: DeviceTone,
        durationMillis: Int,
        authorizeStart: MediaStartAuthorization,
    ): MediaPlaybackResult = runOperation { operation ->
        if (durationMillis <= 0) return@runOperation MediaPlaybackResult.INVALID_REQUEST
        if (durationMillis > MAX_TONE_MILLIS) return@runOperation MediaPlaybackResult.LIMIT_EXCEEDED
        if (!startAuthorized(operation, authorizeStart) { operation.resource = backend.startTone(tone, durationMillis) }) {
            return@runOperation MediaPlaybackResult.CANCELLED
        }
        if (operation.resource == null) return@runOperation MediaPlaybackResult.PLAYBACK_FAILED
        withTimeoutOrNull(durationMillis.toLong()) { operation.result.await() } ?: MediaPlaybackResult.COMPLETED
    }

    /** The host owns the local descriptor and checks current authority again before playback starts. */
    internal suspend fun playAudio(
        source: AssetFileDescriptor?,
        authorizeStart: MediaStartAuthorization,
    ): MediaPlaybackResult = playClip(source, null, authorizeStart)

    /** The host owns the descriptor and surface. It closes playback on Main before it releases the surface. */
    internal suspend fun playVideo(
        source: AssetFileDescriptor?,
        surface: Surface,
        authorizeStart: MediaStartAuthorization,
    ): MediaPlaybackResult = playClip(source, surface, authorizeStart)

    @MainThread
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        closed = true
        active?.let { finish(it, MediaPlaybackResult.CANCELLED) }
    }

    private suspend fun playClip(
        source: AssetFileDescriptor?,
        surface: Surface?,
        authorizeStart: MediaStartAuthorization,
    ) = runOperation { operation ->
        if (source == null || !source.fileDescriptor.valid()) {
            return@runOperation MediaPlaybackResult.SOURCE_UNAVAILABLE
        }
        val fileSize = source.parcelFileDescriptor.statSize
        val offset = source.startOffset
        val length = source.length
        if (offset < 0 || offset > fileSize || length <= 0 || length > fileSize - offset) {
            return@runOperation MediaPlaybackResult.INVALID_SOURCE
        }
        if (length > MAX_MEDIA_BYTES) return@runOperation MediaPlaybackResult.LIMIT_EXCEEDED
        if (surface?.isValid == false) return@runOperation MediaPlaybackResult.INVALID_REQUEST

        withTimeoutOrNull(clipTimeoutMillis) {
            val owner = currentCoroutineContext()
            val player = backend.newClip()
            operation.resource = player
            val prepared = CompletableDeferred<MediaClipInfo>()
            var started = false
            player.prepare(
                source,
                length,
                onPrepared = { prepared.complete(it) },
                onCompletion = {
                    if (started && owner.isActive) finish(operation, MediaPlaybackResult.COMPLETED)
                },
                onFailure = {
                    if (owner.isActive) finish(operation, MediaPlaybackResult.PLAYBACK_FAILED)
                },
            )
            val info = select<MediaClipInfo?> {
                operation.result.onAwait { null }
                prepared.onAwait { it }
            } ?: return@withTimeoutOrNull operation.result.await()
            if (active !== operation) return@withTimeoutOrNull operation.result.await()
            val failure = info.rejection(surface)
            if (failure != null) return@withTimeoutOrNull failure

            val authorized = startAuthorized(operation, authorizeStart) {
                if (surface?.isValid != false) {
                    started = true
                    player.start(surface)
                }
            }
            if (!authorized) {
                return@withTimeoutOrNull MediaPlaybackResult.CANCELLED
            }
            if (!started) return@withTimeoutOrNull MediaPlaybackResult.INVALID_REQUEST
            operation.result.await()
        } ?: MediaPlaybackResult.TIMED_OUT
    }

    private suspend fun startAuthorized(
        operation: Operation,
        authorizeStart: MediaStartAuthorization,
        start: () -> Unit,
    ): Boolean = coroutineScope {
        val authorization = async(start = CoroutineStart.UNDISPATCHED) {
            val owner = currentCoroutineContext()
            var open = true
            var invoked = false
            try {
                val allowed = authorizeStart {
                    if (Looper.myLooper() == Looper.getMainLooper() &&
                        open && !invoked && owner.isActive && active === operation
                    ) {
                        invoked = true
                        start()
                    }
                }
                allowed && invoked
            } finally {
                open = false
            }
        }
        try {
            select {
                operation.result.onAwait { false }
                authorization.onAwait { it }
            }
        } finally {
            authorization.cancel()
        }
    }

    private suspend fun runOperation(action: suspend (Operation) -> MediaPlaybackResult): MediaPlaybackResult =
        withContext(Dispatchers.Main.immediate) {
            if (closed) return@withContext MediaPlaybackResult.CLOSED
            if (active != null) return@withContext MediaPlaybackResult.BUSY
            val operation = Operation()
            active = operation
            try {
                val result = try {
                    action(operation)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: IOException) {
                    MediaPlaybackResult.SOURCE_UNAVAILABLE
                } catch (_: SecurityException) {
                    MediaPlaybackResult.SOURCE_UNAVAILABLE
                } catch (_: RuntimeException) {
                    MediaPlaybackResult.PLAYBACK_FAILED
                }
                finish(operation, result)
                operation.result.await()
            } finally {
                finish(operation, MediaPlaybackResult.CANCELLED)
            }
        }

    private fun finish(operation: Operation, result: MediaPlaybackResult) {
        if (active !== operation) return
        active = null
        val releasedResult = try {
            operation.resource?.close()
            result
        } catch (_: Exception) {
            if (result == MediaPlaybackResult.COMPLETED) MediaPlaybackResult.PLAYBACK_FAILED else result
        }
        operation.result.complete(releasedResult)
    }

    private class Operation {
        val result = CompletableDeferred<MediaPlaybackResult>()
        var resource: Closeable? = null
    }
}

internal data class MediaClipInfo(
    val durationMillis: Int,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val videoWidth: Int,
    val videoHeight: Int,
) {
    fun rejection(surface: Surface?): MediaPlaybackResult? = when {
        durationMillis <= 0 -> MediaPlaybackResult.INVALID_SOURCE
        durationMillis > MAX_CLIP_MILLIS -> MediaPlaybackResult.LIMIT_EXCEEDED
        surface == null -> if (hasAudio && !hasVideo) null else MediaPlaybackResult.INVALID_SOURCE
        !surface.isValid -> MediaPlaybackResult.INVALID_REQUEST
        !hasVideo || videoWidth <= 0 || videoHeight <= 0 -> MediaPlaybackResult.INVALID_SOURCE
        maxOf(videoWidth, videoHeight) > 1920 || minOf(videoWidth, videoHeight) > 1080 -> MediaPlaybackResult.LIMIT_EXCEEDED
        else -> null
    }
}

internal interface DeviceMediaBackend {
    fun startTone(tone: DeviceTone, durationMillis: Int): Closeable?
    fun newClip(): MediaClipPlayer
}

internal interface MediaClipPlayer : Closeable {
    fun prepare(
        source: AssetFileDescriptor,
        length: Long,
        onPrepared: (MediaClipInfo) -> Unit,
        onCompletion: () -> Unit,
        onFailure: () -> Unit,
    )

    fun start(surface: Surface?)
}
