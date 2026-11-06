package com.alpha.assistant.capabilities

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.media.DeviceMediaBackend
import com.alpha.assistant.media.DeviceMediaPlayback
import com.alpha.assistant.media.DeviceTone
import com.alpha.assistant.media.MAX_CLIP_MILLIS
import com.alpha.assistant.media.MAX_MEDIA_BYTES
import com.alpha.assistant.media.MAX_TONE_MILLIS
import com.alpha.assistant.media.MediaClipInfo
import com.alpha.assistant.media.MediaClipPlayer
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolLibraryStorage
import com.alpha.assistant.tools.ToolLibraryUnavailable
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.selectOtherLibraryForTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Failure cases include foreign or revoked resources, stale state, lost hosts, and late or duplicate callbacks. */
@RunWith(AndroidJUnit4::class)
class AudioCapabilityWorkflowTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun closedContractsAndAndroidPickerKeepTheResourceOpaque(): Unit = runBlocking {
        val metadata = JSONObject().put("resourceId", RESOURCE).put("kind", "audio").put("mimeType", "audio/wav").put("sizeBytes", MAX_MEDIA_BYTES)
        assertEquals(metadata.toString(), CapabilityResultEvent.data("audio.pick", CapabilityResult("succeeded", "Fixture", metadata)).toString())
        for (invalid in listOf(
            JSONObject(metadata.toString()).put("sizeBytes", MAX_MEDIA_BYTES + 1),
            JSONObject(metadata.toString()).put("mimeType", "video/mp4"),
            JSONObject(metadata.toString()).put("uri", "content://private/audio"),
        )) assertTrue(runCatching { CapabilityResultEvent.data("audio.pick", CapabilityResult("succeeded", "Fixture", invalid)) }.isFailure)
        assertTrue(runCatching { CapabilityCatalog.request(tone(MAX_TONE_MILLIS + 1).json()) }.isFailure)
        assertTrue(runCatching { CapabilityCatalog.request(play("content://private/audio").json()) }.isFailure)
        assertTrue(runCatching { CapabilityCatalog.request(pick().json().put("args", JSONObject().put("mime", "video/*"))) }.isFailure)

        withFixture { fixture ->
            val tool = fixture.save(pick())
            val key = fixture.enqueue(tool, pick())
            var selectedIntent: Intent? = null
            val result = fixture.broker.executeNative(key, { true }) { _, intent -> selectedIntent = intent }
            assertEquals("awaitingUser", result.status)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, selectedIntent?.action)
            assertEquals("audio/*", selectedIntent?.type)
            assertEquals("waiting", fixture.store.effect(key)?.phase)
            assertFalse(CapabilityResources(fixture.context, fixture.store).finishSelection(key, CapabilityResult("cancelled", "Picker closed")))
            assertEquals("cancelled", fixture.store.effect(key)?.result?.status)
            assertTrue(fixture.store.resources(tool.id).isEmpty())
        }
    }

    @Test fun selectedLocalAudioCompletesOnceAndOnlyTheForegroundAppReceivesItsResult(): Unit = runBlocking {
        withFixture(MAX_MEDIA_BYTES) { fixture ->
            val tool = fixture.save(play())
            fixture.addResource(tool.id)
            val engine = fixture.engine()
            val next = engine.interact(tool.id, tool.revision, tool.stateVersion, "activate", null)
            val key = fixture.store.effects(tool.id).single().key
            val host = fixture.host(tool)
            val outcome = async { fixture.playEffect(key, host) }
            val clip = withTimeout(5_000) { fixture.backend.prepared.await() }
            assertTrue(clip.descriptor.fileDescriptor.valid())
            assertEquals(MAX_MEDIA_BYTES, clip.descriptor.length)
            withContext(Dispatchers.Main.immediate) { clip.ready() }
            withTimeout(5_000) { clip.started.await() }
            withContext(Dispatchers.Main.immediate) { clip.complete() }
            assertEquals("succeeded", withTimeout(5_000) { outcome.await() }?.status)
            assertEquals(1, clip.releases)
            assertFalse(clip.descriptor.fileDescriptor.valid())
            assertEquals(0, fixture.store.effect(key)!!.result.data.length())
            engine.deliverCapabilityResults(tool.id)
            assertEquals(next, fixture.store.tool(tool.id))
            fixture.hosts.whileVisible(host) { active -> engine.deliverForegroundEffectResults(next, active) }
            assertEquals("succeeded", JSONObject(fixture.store.tool(tool.id)!!.state).getString("outcome"))
            assertEquals("committed", fixture.store.deliveries(tool.id).single().phase)
            assertEquals("succeeded", fixture.playEffect(key, host)?.status)
            assertEquals(1, fixture.backend.clips)
        }
    }

    @Test fun missingForeignAndRevokedAudioNeverOpenADescriptor(): Unit = runBlocking {
        withFixture { fixture ->
            val tool = fixture.save(play())
            val host = fixture.host(tool)
            for (kind in listOf("missing", "foreign", "revoked", "wrongMime")) {
                val current = checkNotNull(fixture.store.tool(tool.id))
                if (kind != "missing") fixture.addResource(if (kind == "foreign") "other" else tool.id,
                    mime = if (kind == "wrongMime") "video/mp4" else "audio/wav")
                fixture.permitted.set(kind != "revoked")
                val key = fixture.enqueue(current, play())
                assertEquals("unavailable", fixture.playEffect(key, host)?.status)
                fixture.store.deleteResource(RESOURCE)
            }
            assertEquals(0, fixture.opens)
            assertEquals(0, fixture.backend.clips)
        }
    }

    @Test fun revokedSelectionCannotPublishACompletedClipResult(): Unit = runBlocking {
        withFixture { fixture ->
            val tool = fixture.save(play())
            fixture.addResource(tool.id)
            val key = fixture.enqueue(tool, play())
            val host = fixture.host(tool)
            val outcome = async { fixture.playEffect(key, host) }
            val clip = withTimeout(5_000) { fixture.backend.prepared.await() }
            withContext(Dispatchers.Main.immediate) { clip.ready() }
            withTimeout(5_000) { clip.started.await() }
            withContext(Dispatchers.Main.immediate) { clip.complete() }
            assertEquals("succeeded", withTimeout(5_000) { outcome.await() }?.status)
            val current = checkNotNull(fixture.store.tool(tool.id))
            fixture.permitted.set(false)
            fixture.hosts.whileVisible(host) { active -> fixture.engine().deliverForegroundEffectResults(current, active) }
            assertEquals("rejected", fixture.store.deliveries(tool.id).single().phase)
            assertEquals(current, fixture.store.tool(tool.id))
            assertEquals(1, fixture.backend.clips)
        }
    }

    @Test fun revocationAndStateChangeDuringPreparationPreventThePlayerStart(): Unit = runBlocking {
        for (revoke in listOf(true, false)) withFixture { fixture ->
            val tool = fixture.save(play())
            fixture.addResource(tool.id)
            val host = fixture.host(tool)
            val key = fixture.enqueue(tool, play())
            val outcome = async { fixture.playEffect(key, host) }
            val clip = withTimeout(5_000) { fixture.backend.prepared.await() }
            if (revoke) fixture.permitted.set(false) else {
                val current = checkNotNull(fixture.store.tool(tool.id))
                fixture.store.commitTool(current.copy(stateVersion = current.stateVersion + 1, state = """{"outcome":"newer"}"""), current, false)
            }
            withContext(Dispatchers.Main.immediate) { clip.ready() }
            assertEquals(if (revoke) "unavailable" else "cancelled", withTimeout(5_000) { outcome.await() }?.status)
            assertFalse(clip.started.isCompleted)
            assertEquals(1, clip.releases)
            assertFalse(clip.descriptor.fileDescriptor.valid())
            if (!revoke) assertEquals("newer", JSONObject(fixture.store.tool(tool.id)!!.state).getString("outcome"))
        }
    }

    @Test fun closingTheLastResumedHostStopsPlaybackAndLateCallbacksCannotReplay(): Unit = runBlocking {
        withFixture { fixture ->
            val tool = fixture.save(play())
            fixture.addResource(tool.id)
            val origin = fixture.host(tool)
            val other = fixture.host(tool)
            val key = fixture.enqueue(tool, play())
            val outcome = async { fixture.playEffect(key, origin) }
            val clip = withTimeout(5_000) { fixture.backend.prepared.await() }
            fixture.hosts.update(origin, null)
            withContext(Dispatchers.Main.immediate) { clip.ready() }
            withTimeout(5_000) { clip.started.await() }
            fixture.hosts.update(other, false)
            assertEquals("cancelled", withTimeout(5_000) { outcome.await() }?.status)
            assertEquals(1, clip.releases)
            assertFalse(clip.descriptor.fileDescriptor.valid())
            fixture.hosts.update(other, true)
            withContext(Dispatchers.Main.immediate) { clip.ready(); clip.complete() }
            assertEquals("cancelled", fixture.playEffect(key, other)?.status)
            assertEquals(1, fixture.backend.clips)
        }
    }

    @Test fun aPreparedClipCannotStartAfterItsLibraryIsReplaced(): Unit = runBlocking {
        withFixture { fixture ->
            val tool = fixture.save(play())
            fixture.addResource(tool.id)
            val host = fixture.host(tool)
            val key = fixture.enqueue(tool, play())
            val outcome = async { runCatching { fixture.playEffect(key, host) } }
            val clip = withTimeout(5_000) { fixture.backend.prepared.await() }
            val current = checkNotNull(fixture.store.tool(tool.id))
            selectOtherLibraryForTest(app, fixture.store.library)
            ToolStore(app, fixture.store.logicalDatabase).use { replacement ->
                replacement.commitTool(current, null, true)
                withContext(Dispatchers.Main.immediate) { clip.ready() }
                assertTrue(withTimeout(5_000) { outcome.await() }.exceptionOrNull() is ToolLibraryUnavailable)
                assertFalse(clip.started.isCompleted)
                assertEquals(1, clip.releases)
                assertFalse(clip.descriptor.fileDescriptor.valid())
                assertEquals(current, replacement.tool(tool.id))
                assertTrue(replacement.effects(tool.id).isEmpty())
            }
        }
    }

    @Test fun pickerReturnWaitsThroughAndroidStopAndRejectsADisposedOrigin(): Unit = runBlocking {
        withFixture { fixture ->
            val tool = fixture.save(pick())
            val origin = fixture.host(tool)
            fixture.hosts.stop(origin)
            val returning = async(start = CoroutineStart.UNDISPATCHED) { fixture.hosts.awaitPickerReturn(origin) }
            val other = fixture.host(tool)
            fixture.hosts.update(origin, false)
            assertFalse(returning.isCompleted)
            fixture.hosts.update(origin, true)
            assertTrue(withTimeout(5_000) { returning.await() })

            fixture.hosts.stop(origin)
            val abandoned = async(start = CoroutineStart.UNDISPATCHED) { fixture.hosts.awaitPickerReturn(origin) }
            fixture.hosts.update(origin, null)
            assertFalse(withTimeout(5_000) { abandoned.await() })
            assertTrue(fixture.hosts.initiating(other))
        }
    }

    @Test fun toneUsesTheSameForegroundLedgerWithoutOpeningMediaOrReplaying(): Unit = runBlocking {
        withFixture { fixture ->
            val tool = fixture.save(tone())
            val key = fixture.enqueue(tool, tone())
            val host = fixture.host(tool)
            assertEquals("succeeded", fixture.playEffect(key, host)?.status)
            assertEquals(DeviceTone.ACKNOWLEDGMENT to 1, fixture.backend.tone)
            assertEquals(1, fixture.backend.toneReleases)
            assertEquals("succeeded", fixture.playEffect(key, host)?.status)
            assertEquals(1, fixture.backend.toneReleases)
            assertEquals(0, fixture.opens)
        }
    }

    private fun tone(millis: Int = 1) = CapabilityRequest("native", "audio.tone", JSONObject().put("tone", "acknowledgment").put("durationMillis", millis))
    private fun play(id: String = RESOURCE) = CapabilityRequest("native", "audio.play", JSONObject().put("resourceId", id))
    private fun pick() = CapabilityRequest("native", "audio.pick", JSONObject())

    private suspend fun withFixture(size: Long = 32L, work: suspend (Fixture) -> Unit) {
        val name = "audio-capability-${UUID.randomUUID()}.db"
        val file = File.createTempFile("selected-audio-", ".fixture", app.cacheDir)
        val permitted = AtomicBoolean(true)
        val context = object : ContextWrapper(app) {
            override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int =
                if (uri == SOURCE && permitted.get()) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        try {
            RandomAccessFile(file, "rw").use { it.setLength(size) }
            ToolStore(context, name).use { store -> work(Fixture(context, store, file, permitted)) }
        } finally {
            file.delete()
            app.databaseList().filter { it.startsWith(name) }.forEach(app::deleteDatabase)
            ToolLibraryStorage.selectionFile(app, name).delete()
        }
    }

    private class Fixture(val context: ContextWrapper, val store: ToolStore, private val file: File, val permitted: AtomicBoolean) {
        val broker = CapabilityBroker(context, store)
        val hosts = DeviceReadHosts { true }
        val backend = Backend()
        var opens = 0
            private set

        fun host(tool: ToolRecord) = DeviceReadHost(tool.id, tool.revision).also { hosts.update(it, true) }

        suspend fun playEffect(key: String, host: DeviceReadHost) = broker.executeAudio(key, host, hosts,
            AudioCapabilities(context, store, playback = { DeviceMediaPlayback(backend) }, openDescriptor = { resource ->
                assertEquals(SOURCE.toString(), resource.locator)
                opens++
                AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
            }))

        fun addResource(owner: String, mime: String = "audio/wav") {
            store.insertResource(CapabilityResource(RESOURCE, owner, "audio", SOURCE.toString(), mime, file.length(),
                false, false, ToolStore.processSession, Long.MAX_VALUE))
        }

        fun save(request: CapabilityRequest): ToolRecord {
            val spec = ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", "Audio capability fixture").put("description", "")
                .put("capabilities", JSONArray(setOf("audio.pick", request.capability).toList())).put("capabilityResults", true)
                .put("stateSchema", JSONObject().put("outcome", "string")).put("initialState", JSONObject().put("outcome", ""))
                .put("ui", JSONObject().put("type", "button").put("text", "Audio").put("action", "activate"))
                .put("source", """if(input.event.type==='action')return {state:input.state,effects:[${request.json()}]};
                    if(input.event.type==='capabilityResults')input.state.outcome=input.event.entries[0].status;
                    return {state:input.state};""").toString())
            return ToolRecord("audio-${UUID.randomUUID()}", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
                .also { store.commitTool(it, null, true) }
        }

        fun enqueue(tool: ToolRecord, request: CapabilityRequest): String {
            store.commitTool(tool.copy(stateVersion = tool.stateVersion + 1), tool, false, effects = listOf(request))
            return store.effects(tool.id).last().key
        }

        fun engine(): ToolEngine {
            val provider = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    error("Local audio called a provider")
                }
            }
            return ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
        }
    }

    private class Backend : DeviceMediaBackend {
        val prepared = CompletableDeferred<Clip>()
        var clips = 0
        var tone: Pair<DeviceTone, Int>? = null
        var toneReleases = 0

        override fun startTone(tone: DeviceTone, durationMillis: Int): Closeable {
            this.tone = tone to durationMillis
            return Closeable { toneReleases++ }
        }

        override fun newClip(): MediaClipPlayer {
            clips++
            return Clip(prepared)
        }
    }

    private class Clip(private val prepared: CompletableDeferred<Clip>) : MediaClipPlayer {
        lateinit var descriptor: AssetFileDescriptor
        private lateinit var onPrepared: (MediaClipInfo) -> Unit
        private lateinit var onCompletion: () -> Unit
        val started = CompletableDeferred<Unit>()
        var releases = 0

        override fun prepare(source: AssetFileDescriptor, length: Long, onPrepared: (MediaClipInfo) -> Unit,
            onCompletion: () -> Unit, onFailure: () -> Unit) {
            descriptor = source
            this.onPrepared = onPrepared
            this.onCompletion = onCompletion
            prepared.complete(this)
        }

        fun ready() = onPrepared(MediaClipInfo(MAX_CLIP_MILLIS, hasAudio = true, hasVideo = false, videoWidth = 0, videoHeight = 0))
        fun complete() = onCompletion()
        override fun start(surface: Surface?) { assertNull(surface); started.complete(Unit) }
        override fun close() { releases++ }
    }

    companion object {
        private val RESOURCE = "a".repeat(64)
        private val SOURCE = Uri.parse("content://audio-capability-fixture/selected")
    }
}
