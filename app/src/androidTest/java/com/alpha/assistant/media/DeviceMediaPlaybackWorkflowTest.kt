package com.alpha.assistant.media

import android.content.res.AssetFileDescriptor
import android.graphics.SurfaceTexture
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class DeviceMediaPlaybackWorkflowTest {
    // Failure cases: missing or unreadable source, invalid range or surface, and resource limits.
    // Playback can fail during preparation or start, time out, or lose foreground authority.
    // A duplicate or late callback must not start playback or release another operation.
    @Test fun rejectsUnusableSourcesBeforeAllocatingAPlayer() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            assertEquals(MediaPlaybackResult.SOURCE_UNAVAILABLE, media.playAudio(null, allowStart))
            withSource { source ->
                source.close()
                assertEquals(MediaPlaybackResult.SOURCE_UNAVAILABLE, media.playAudio(source, allowStart))
            }
            withSource(offset = 8, length = 9) { source ->
                assertEquals(MediaPlaybackResult.INVALID_SOURCE, media.playAudio(source, allowStart))
            }
            withSource(bytes = 0) { source ->
                assertEquals(MediaPlaybackResult.INVALID_SOURCE, media.playAudio(source, allowStart))
            }
            withSource(bytes = MAX_MEDIA_BYTES + 1) { source ->
                assertEquals(MediaPlaybackResult.LIMIT_EXCEEDED, media.playAudio(source, allowStart))
            }
            val (read, write) = ParcelFileDescriptor.createPipe()
            write.use {
                AssetFileDescriptor(read, 0, 1).use { source ->
                    assertEquals(MediaPlaybackResult.INVALID_SOURCE, media.playAudio(source, allowStart))
                }
            }
            withSource { source ->
                val texture = SurfaceTexture(false)
                try {
                    val surface = Surface(texture)
                    surface.release()
                    assertEquals(MediaPlaybackResult.INVALID_REQUEST, media.playVideo(source, surface, allowStart))
                } finally {
                    texture.release()
                }
            }
            assertTrue(backend.clips.isEmpty())
        }
    }

    @Test fun completionReleasesThePlayerAndLateCallbacksCannotAffectTheNextClip() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            withSource(offset = 4, length = 8) { source ->
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    media.playAudio(source) { start ->
                        start()
                        start()
                        true
                    }
                }
                val original = backend.clips.single()
                assertEquals(4L to 8L, original.sourceRange)
                assertEquals(MediaPlaybackResult.BUSY, media.playTone(DeviceTone.BEEP, 1, allowStart))
                original.complete()
                assertFalse(first.isCompleted)
                original.prepared(AUDIO)
                original.prepared(AUDIO)
                original.didStart.await()
                assertEquals(1, original.starts)
                original.complete()
                assertEquals(MediaPlaybackResult.COMPLETED, first.await())
                assertEquals(1, original.releases)
                assertTrue(source.fileDescriptor.valid())

                val next = async(start = CoroutineStart.UNDISPATCHED) { media.playAudio(source, allowStart) }
                val current = backend.clips.last()
                original.prepared(AUDIO)
                original.fail()
                original.complete()
                assertFalse(next.isCompleted)
                assertEquals(0, current.starts)
                current.prepared(AUDIO)
                current.didStart.await()
                current.complete()
                assertEquals(MediaPlaybackResult.COMPLETED, next.await())
                assertEquals(1, original.releases)
                assertEquals(1, current.releases)
            }
        }
    }

    @Test fun cancellationAndCloseReleaseResourcesAndPreventLateStart() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            withSource { source ->
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { media.playAudio(source, allowStart) }
                val beforeStart = backend.clips.single()
                cancelled.cancel()
                beforeStart.prepared(AUDIO)
                beforeStart.complete()
                cancelled.cancelAndJoin()
                assertEquals(0, beforeStart.starts)
                assertEquals(1, beforeStart.releases)

                val playing = async(start = CoroutineStart.UNDISPATCHED) { media.playAudio(source, allowStart) }
                val active = backend.clips.last()
                active.prepared(AUDIO)
                active.didStart.await()
                assertEquals(1, active.starts)
                media.close()
                media.close()
                assertEquals(1, active.releases)
                active.complete()
                assertEquals(MediaPlaybackResult.CANCELLED, playing.await())
                assertEquals(MediaPlaybackResult.CLOSED, media.playAudio(source, allowStart))
                assertEquals(2, backend.clips.size)
            }
        }
    }

    @Test fun clipStartRequiresFreshAuthorizationAfterPreparation() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            withSource { source ->
                var current = true
                var authorizationCalls = 0
                lateinit var lateStart: () -> Unit
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    media.playAudio(source) { start ->
                        authorizationCalls++
                        lateStart = start
                        if (current) start()
                        current
                    }
                }
                val clip = backend.clips.single()
                assertEquals(0, authorizationCalls)
                current = false
                clip.prepared(AUDIO)
                clip.prepared(AUDIO)
                assertEquals(MediaPlaybackResult.CANCELLED, request.await())
                lateStart()
                clip.prepared(AUDIO)
                assertEquals(1, authorizationCalls)
                assertEquals(0, clip.starts)
                assertEquals(1, clip.releases)
                assertTrue(source.fileDescriptor.valid())
            }
        }
    }

    @Test fun closeStopsAnAudioStartThatWaitsForHostAuthorization() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            withSource { source ->
                val gate = CompletableDeferred<() -> Unit>()
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    media.playAudio(source) { start ->
                        gate.complete(start)
                        awaitCancellation()
                    }
                }
                val clip = backend.clips.single()
                clip.prepared(AUDIO)
                val lateStart = gate.await()
                media.close()
                lateStart()
                assertEquals(MediaPlaybackResult.CANCELLED, request.await())
                assertEquals(0, clip.starts)
                assertEquals(1, clip.releases)
            }
        }
    }

    @Test fun videoStartRejectsStaleHostAuthorityAfterPreparation() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            withSource { source ->
                val texture = SurfaceTexture(false)
                val surface = Surface(texture)
                try {
                    var current = true
                    var authorizationCalls = 0
                    lateinit var lateStart: () -> Unit
                    val request = async(start = CoroutineStart.UNDISPATCHED) {
                        media.playVideo(source, surface) { start ->
                            authorizationCalls++
                            lateStart = start
                            if (current) start()
                            current
                        }
                    }
                    val clip = backend.clips.single()
                    assertEquals(0, authorizationCalls)
                    current = false
                    clip.prepared(VIDEO)
                    clip.prepared(VIDEO)
                    assertEquals(MediaPlaybackResult.CANCELLED, request.await())
                    media.close()
                    lateStart()
                    clip.prepared(VIDEO)
                    assertEquals(1, authorizationCalls)
                    assertEquals(0, clip.starts)
                    assertEquals(1, clip.releases)
                    assertTrue(source.fileDescriptor.valid())
                    assertTrue(surface.isValid)
                } finally {
                    surface.release()
                    texture.release()
                }
            }
        }
    }

    @Test fun surfaceDestroyedDuringAuthorizationPreventsVideoStart() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            withSource { source ->
                val texture = SurfaceTexture(false)
                val surface = Surface(texture)
                var released = false
                try {
                    val gateEntered = CompletableDeferred<Unit>()
                    val authorize = CompletableDeferred<Unit>()
                    val request = async(start = CoroutineStart.UNDISPATCHED) {
                        media.playVideo(source, surface) { start ->
                            gateEntered.complete(Unit)
                            authorize.await()
                            start()
                            true
                        }
                    }
                    val clip = backend.clips.single()
                    clip.prepared(VIDEO)
                    gateEntered.await()
                    surface.release()
                    released = true
                    authorize.complete(Unit)
                    assertEquals(MediaPlaybackResult.INVALID_REQUEST, request.await())
                    clip.complete()
                    assertEquals(0, clip.starts)
                    assertEquals(1, clip.releases)
                    assertTrue(source.fileDescriptor.valid())
                } finally {
                    if (!released) surface.release()
                    texture.release()
                }
            }
        }
    }

    @Test fun toneDenialAndCallerCancellationKeepTheBackendIdle() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            assertEquals(MediaPlaybackResult.CANCELLED, media.playTone(DeviceTone.BEEP, 1) { false })
            val gate = CompletableDeferred<() -> Unit>()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                media.playTone(DeviceTone.BEEP, 1) { start ->
                    gate.complete(start)
                    awaitCancellation()
                }
            }
            val lateStart = gate.await()
            request.cancel()
            lateStart()
            request.cancelAndJoin()
            assertTrue(backend.tones.isEmpty())
        }
    }

    @Test fun playerFailuresAndTimeoutReleaseResources() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend, clipTimeoutMillis = 25).use { media ->
            withSource { source ->
                backend.prepareFailure = IOException()
                assertEquals(MediaPlaybackResult.SOURCE_UNAVAILABLE, media.playAudio(source, allowStart))
                backend.prepareFailure = null

                val error = async(start = CoroutineStart.UNDISPATCHED) { media.playAudio(source, allowStart) }
                backend.clips.last().fail()
                assertEquals(MediaPlaybackResult.PLAYBACK_FAILED, error.await())

                val start = async(start = CoroutineStart.UNDISPATCHED) { media.playAudio(source, allowStart) }
                backend.clips.last().apply {
                    startFailure = true
                    prepared(AUDIO)
                }
                assertEquals(MediaPlaybackResult.PLAYBACK_FAILED, start.await())

                assertEquals(MediaPlaybackResult.TIMED_OUT, media.playAudio(source, allowStart))
                backend.clips.last().prepared(AUDIO)
                assertEquals(0, backend.clips.last().starts)
                assertEquals(listOf(1, 1, 1, 1), backend.clips.map { it.releases })
            }
        }
    }

    @Test fun clipMetadataBoundsDurationTracksAndVideoDimensions() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            withSource { source ->
                val texture = SurfaceTexture(false)
                val surface = Surface(texture)
                try {
                    suspend fun checkClip(
                        expected: MediaPlaybackResult,
                        metadata: MediaClipInfo,
                        video: Boolean = false,
                    ) {
                        val request = async(start = CoroutineStart.UNDISPATCHED) {
                            if (video) media.playVideo(source, surface, allowStart) else media.playAudio(source, allowStart)
                        }
                        val clip = backend.clips.last()
                        clip.prepared(metadata)
                        if (expected == MediaPlaybackResult.COMPLETED) clip.didStart.await()
                        clip.complete()
                        assertEquals(expected, request.await())
                        assertEquals(if (expected == MediaPlaybackResult.COMPLETED) 1 else 0, clip.starts)
                        assertEquals(1, clip.releases)
                        assertEquals(if (video && clip.starts == 1) surface else null, clip.surface)
                    }
                    checkClip(MediaPlaybackResult.INVALID_SOURCE, AUDIO.copy(durationMillis = -1))
                    checkClip(MediaPlaybackResult.LIMIT_EXCEEDED, AUDIO.copy(durationMillis = MAX_CLIP_MILLIS + 1))
                    checkClip(MediaPlaybackResult.INVALID_SOURCE, VIDEO)
                    checkClip(MediaPlaybackResult.INVALID_SOURCE, AUDIO, video = true)
                    checkClip(MediaPlaybackResult.INVALID_SOURCE, VIDEO.copy(videoWidth = 0), video = true)
                    checkClip(MediaPlaybackResult.LIMIT_EXCEEDED, VIDEO.copy(videoWidth = 1921), video = true)
                    checkClip(MediaPlaybackResult.COMPLETED, VIDEO, video = true)
                    assertTrue(surface.isValid)
                } finally {
                    surface.release()
                    texture.release()
                }
            }
        }
    }

    @Test fun tonesAreBoundedAndReleaseOnCompletionOrClose() = onMain {
        val backend = FakeBackend()
        DeviceMediaPlayback(backend).use { media ->
            assertEquals(MediaPlaybackResult.INVALID_REQUEST, media.playTone(DeviceTone.BEEP, 0, allowStart))
            assertEquals(
                MediaPlaybackResult.LIMIT_EXCEEDED,
                media.playTone(DeviceTone.BEEP, MAX_TONE_MILLIS + 1, allowStart),
            )
            assertTrue(backend.tones.isEmpty())
            backend.toneAvailable = false
            assertEquals(MediaPlaybackResult.PLAYBACK_FAILED, media.playTone(DeviceTone.BEEP, 1, allowStart))
            backend.toneAvailable = true
            assertEquals(MediaPlaybackResult.COMPLETED, media.playTone(DeviceTone.BEEP, 1, allowStart))
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                media.playTone(DeviceTone.BEEP, MAX_TONE_MILLIS, allowStart)
            }
            media.close()
            assertEquals(MediaPlaybackResult.CANCELLED, pending.await())
            assertEquals(listOf(1, MAX_TONE_MILLIS), backend.tones.map { it.first })
            assertEquals(listOf(1, 1), backend.tones.map { it.second.releases })
        }
    }

    private fun onMain(test: suspend CoroutineScope.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate, test)
    }

    private suspend fun withSource(
        bytes: Long = 16,
        offset: Long = 0,
        length: Long = AssetFileDescriptor.UNKNOWN_LENGTH,
        use: suspend (AssetFileDescriptor) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("media-playback-test", ".bin", context.cacheDir)
        try {
            RandomAccessFile(file, "rw").use { it.setLength(bytes) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(descriptor, offset, length).use { use(it) }
        } finally {
            file.delete()
        }
    }

    private class FakeBackend : DeviceMediaBackend {
        val clips = mutableListOf<FakeClip>()
        val tones = mutableListOf<Pair<Int, FakeResource>>()
        var prepareFailure: IOException? = null
        var toneAvailable = true

        override fun newClip() = FakeClip(prepareFailure).also(clips::add)

        override fun startTone(tone: DeviceTone, durationMillis: Int): Closeable? =
            if (toneAvailable) FakeResource().also { tones.add(durationMillis to it) } else null
    }

    private open class FakeResource : Closeable {
        var releases = 0
        override fun close() { releases++ }
    }

    private class FakeClip(private val prepareFailure: IOException?) : FakeResource(), MediaClipPlayer {
        var starts = 0
        val didStart = CompletableDeferred<Unit>()
        var startFailure = false
        var surface: Surface? = null
        var sourceRange: Pair<Long, Long>? = null
        lateinit var prepared: (MediaClipInfo) -> Unit
        lateinit var complete: () -> Unit
        lateinit var fail: () -> Unit

        override fun prepare(
            source: AssetFileDescriptor,
            length: Long,
            onPrepared: (MediaClipInfo) -> Unit,
            onCompletion: () -> Unit,
            onFailure: () -> Unit,
        ) {
            prepareFailure?.let { throw it }
            sourceRange = source.startOffset to length
            prepared = onPrepared
            complete = onCompletion
            fail = onFailure
        }

        override fun start(surface: Surface?) {
            if (startFailure) throw IllegalStateException()
            this.surface = surface
            starts++
            didStart.complete(Unit)
        }
    }

    private companion object {
        val allowStart: suspend (() -> Unit) -> Boolean = { start ->
            start()
            true
        }
        val AUDIO = MediaClipInfo(100, hasAudio = true, hasVideo = false, videoWidth = 0, videoHeight = 0)
        val VIDEO = MediaClipInfo(100, hasAudio = true, hasVideo = true, videoWidth = 1920, videoHeight = 1080)
    }
}
