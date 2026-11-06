package com.alpha.assistant.tools

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityBroker
import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.capabilities.CapabilityResource
import com.alpha.assistant.capabilities.CapabilityResources
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.capabilities.CapabilityResultEvent
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.media.DeviceMediaBackend
import com.alpha.assistant.media.DeviceMediaPlayback
import com.alpha.assistant.media.DeviceTone
import com.alpha.assistant.media.MAX_MEDIA_BYTES
import com.alpha.assistant.media.MediaClipInfo
import com.alpha.assistant.media.MediaClipPlayer
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The native renderer borrows its Surface to the exact foreground host and player operation. */
@RunWith(AndroidJUnit4::class)
class ToolVideoPlaybackWorkflowTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pickerUsesAndroidVideoSelectionAndReturnsOnlyBoundedMetadata(): Unit = runBlocking {
        withFixture { fixture ->
            val request=CapabilityRequest("pick","video.pick",JSONObject())
            val next=fixture.tool.copy(stateVersion=fixture.tool.stateVersion+1)
            fixture.store.commitTool(next,fixture.tool,false,effects=listOf(request))
            val key=fixture.store.effects(fixture.tool.id).single().key
            var intent: Intent?=null
            val result=CapabilityBroker(fixture.context,fixture.store).executeNative(key,{true}) {_, launched -> intent=launched}
            assertEquals("awaitingUser",result.status)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent?.action)
            assertEquals("video/*",intent?.type)
            assertFalse(CapabilityResources(fixture.context,fixture.store).finishSelection(key,CapabilityResult("cancelled","Picker closed")))
            assertEquals("cancelled",fixture.store.effect(key)?.result?.status)
            assertTrue(fixture.store.resources(fixture.tool.id).isEmpty())

            val data=JSONObject().put("resourceId",FIRST).put("kind","video").put("mimeType","video/mp4").put("sizeBytes",MAX_MEDIA_BYTES)
            assertEquals(setOf("resourceId","kind","mimeType","sizeBytes"),CapabilityResultEvent.data("video.pick",CapabilityResult("succeeded","Fixture",data)).keys().asSequence().toSet())
            data.put("uri",FIRST_URI.toString())
            assertTrue(runCatching {CapabilityResultEvent.data("video.pick",CapabilityResult("succeeded","Fixture",data))}.isFailure)
        }
    }

    @Test fun ordinaryActionAndMismatchedDedicatedEffectCannotPersist(): Unit = runBlocking {
        withFixture { fixture ->
            val tool=fixture.tool
            val ordinary=runCatching {fixture.engine.interact(tool.id,tool.revision,tool.stateVersion,"forged",null)}.exceptionOrNull()
            assertEquals("video_control_required",(ordinary as? CloudFailure)?.code)
            val wrongDelivery=runCatching {fixture.engine.interact(tool.id,tool.revision,tool.stateVersion,"first",FIRST)}.exceptionOrNull()
            assertTrue(wrongDelivery is IllegalArgumentException)
            assertEquals(tool,fixture.store.tool(tool.id))
            assertTrue(fixture.store.effects(tool.id).isEmpty())

            val control=NativeVideoControl("first",FIRST)
            assertTrue(runCatching {fixture.engine.interactVideo(tool,control) {false}}.isFailure)
            assertEquals(tool,fixture.store.tool(tool.id))
            assertTrue(fixture.store.effects(tool.id).isEmpty())

            val rewritten=fixture.save(mismatchedEffect=true)
            val failure=runCatching {fixture.engine.interactVideo(rewritten,control) {true}}.exceptionOrNull()
            assertEquals("video_control_required",(failure as? CloudFailure)?.code)
            assertEquals(rewritten,fixture.store.tool(rewritten.id))
            assertTrue(fixture.store.effects(rewritten.id).isEmpty())
        }
    }

    @Test fun dedicatedVideoEnvelopeReachesTheSandboxAndCommitsOnlyTheMatchedEffect(): Unit = runBlocking {
        withFixture { fixture ->
            val tool=fixture.tool
            val changed=fixture.engine.interactVideo(tool,NativeVideoControl("first",FIRST)) {true}
            assertEquals(tool.stateVersion+1,changed.stateVersion)
            val request=fixture.store.effects(tool.id).single().request
            assertEquals("video.play",request.capability)
            assertEquals(setOf("resourceId"),request.args.keys().asSequence().toSet())
            assertEquals(FIRST,request.args.getString("resourceId"))

            val source="return {state:input.state};"
            val event=JSONObject().put("type","action").put("actionId","first").put("value",FIRST)
                .put("video",JSONObject().put("resourceId",FIRST))
            fun input(action: JSONObject) = JSONObject().put("artifactId",tool.id).put("revision",tool.revision)
                .put("state",JSONObject(tool.state)).put("event",action).toString()
            SandboxContract.validateRequest(source,input(event))
            val invalid=listOf(
                JSONObject(event.toString()).put("value",SECOND),
                JSONObject(event.toString()).put("video",JSONObject().put("resourceId","content://private/video")),
                JSONObject(event.toString()).put("video",JSONObject().put("resourceId",FIRST).put("uri","content://private/video")),
                JSONObject(event.toString()).put("video",FIRST),
                JSONObject(event.toString()).put("calendar",JSONObject()),
            )
            invalid.forEach {assertTrue(runCatching {SandboxContract.validateRequest(source,input(it))}.isFailure)}
        }
    }

    @Test fun lostSurfaceClosesThePlayerBeforeReleaseAndAStaleCallbackCannotStartIt(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.resource(FIRST,FIRST_URI)
            val host=fixture.host()
            withLease { lease ->
                val control=NativeVideoControl("first",FIRST)
                onMain {host.onSurfaceAvailable(control,lease); host.play(control,lease)}
                val clip=withTimeout(5_000) {fixture.backend.prepared.receive()}
                onMain {
                    lease.invalidate()
                    host.onSurfaceLost(control,lease)
                    assertEquals(1,clip.releases)
                    assertTrue(lease.surface.isValid)
                    clip.ready()
                }
                fixture.jobs.children.toList().forEach {withTimeout(5_000) {it.join()}}
                assertFalse(clip.started.isCompleted)
                assertFalse(clip.descriptor.fileDescriptor.valid())
                assertEquals("cancelled",fixture.store.effects(fixture.tool.id).single().result.status)
            }
        }
    }

    @Test fun anotherMountedControlAndLateLossDoNotCloseTheCurrentPlayer(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.resource(FIRST,FIRST_URI)
            fixture.resource(SECOND,SECOND_URI)
            val host=fixture.host()
            withLease { first -> withLease { second ->
                val a=NativeVideoControl("first",FIRST)
                val b=NativeVideoControl("second",SECOND)
                onMain {host.onSurfaceAvailable(a,first); host.onSurfaceAvailable(b,second); host.play(a,first)}
                val left=withTimeout(5_000) {fixture.backend.prepared.receive()}
                onMain {left.ready()}
                withTimeout(5_000) {left.started.await()}
                fixture.displayed.set(fixture.store.tool(fixture.tool.id)!!)
                onMain {host.play(b,second)}
                val right=withTimeout(5_000) {fixture.backend.prepared.receive()}
                onMain {right.ready()}
                withTimeout(5_000) {right.started.await()}
                onMain {
                    assertEquals(0,left.releases)
                    first.invalidate();host.onSurfaceLost(a,first)
                    assertEquals(1,left.releases)
                    assertEquals(0,right.releases)
                    right.complete()
                }
                fixture.jobs.children.toList().forEach {withTimeout(5_000) {it.join()}}
                assertEquals(1,right.releases)
                assertEquals("succeeded",fixture.store.effects(fixture.tool.id).last().result.status)
            } }
        }
    }

    @Test fun oldLeaseLossCannotCloseReplacementForTheSameControl(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.resource(FIRST,FIRST_URI)
            val host=fixture.host()
            withLease { old -> withLease { current ->
                val control=NativeVideoControl("first",FIRST)
                onMain {
                    host.onSurfaceAvailable(control,old)
                    host.onSurfaceAvailable(control,current)
                    host.play(control,old)
                }
                assertTrue(fixture.store.effects(fixture.tool.id).isEmpty())
                onMain {host.play(control,current)}
                val clip=withTimeout(5_000) {fixture.backend.prepared.receive()}
                onMain {
                    old.invalidate();host.onSurfaceLost(control,old)
                    assertEquals(0,clip.releases)
                    clip.ready()
                }
                assertSame(current.surface,withTimeout(5_000) {clip.started.await()})
                onMain {clip.complete()}
                fixture.jobs.children.toList().forEach {withTimeout(5_000) {it.join()}}
                assertEquals("succeeded",fixture.store.effects(fixture.tool.id).single().result.status)
            } }
        }
    }

    @Test fun revokedUriAndStaleAppStateCannotStartAWaitingClip(): Unit = runBlocking {
        for (revoke in listOf(true,false)) withFixture { fixture ->
            fixture.resource(FIRST,FIRST_URI)
            val host=fixture.host()
            withLease { lease ->
                val control=NativeVideoControl("first",FIRST)
                onMain {host.onSurfaceAvailable(control,lease); host.play(control,lease)}
                val clip=withTimeout(5_000) {fixture.backend.prepared.receive()}
                if (revoke) fixture.access.set(false) else {
                    val old=fixture.store.tool(fixture.tool.id)!!
                    fixture.store.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false)
                }
                onMain {clip.ready()}
                fixture.jobs.children.toList().forEach {withTimeout(5_000) {it.join()}}
                assertFalse(clip.started.isCompleted)
                assertEquals(1,clip.releases)
                assertFalse(clip.descriptor.fileDescriptor.valid())
            }
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val name="video-playback-host-${UUID.randomUUID()}.db"
        val file=File.createTempFile("video-playback-host-",".fixture",app.cacheDir).apply {writeBytes(ByteArray(64))}
        try {
            val access=AtomicBoolean(true)
            val context=object : ContextWrapper(app) {
                override fun checkUriPermission(uri: Uri,pid: Int,uid: Int,modeFlags: Int) =
                    if(access.get() && uri in setOf(FIRST_URI,SECOND_URI)) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
            ToolStore(context,name).use {store ->
                val fixture=Fixture(context,store,file,access)
                try {block(fixture)} finally {fixture.jobs.cancelAndJoin()}
            }
        } finally {file.delete();app.deleteDatabase(name)}
    }

    private class Fixture(val context: ContextWrapper,val store: ToolStore,private val file: File,val access: AtomicBoolean) {
        val jobs=SupervisorJob()
        val backend=Backend()
        val engine=ToolEngine(store,ToolGenerationService(object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) = error("Video called a provider")
        },SandboxClient(context)))
        val tool=save()
        val displayed=AtomicReference(tool)

        fun save(mismatchedEffect: Boolean=false): ToolRecord {
            val output=if(mismatchedEffect) "'$SECOND'" else "(e.video ? e.video.resourceId : '$FIRST')"
            val source="""const e=input.event;if(e.type==='action')return {state:input.state,effects:[{id:'play',capability:'video.play',args:{resourceId:$output}}]};return {state:input.state};"""
            val spec=ToolSpec.parse(JSONObject().put("formatVersion",3).put("title","Video fixture").put("description","")
                .put("capabilities",org.json.JSONArray(listOf("video.pick","video.play"))).put("capabilityResults",true)
                .put("stateSchema",JSONObject("""{"first":"string","second":"string"}"""))
                .put("initialState",JSONObject().put("first",FIRST).put("second",SECOND))
                .put("ui",JSONObject("""{"type":"column","children":[{"type":"video","text":"First","bind":"first","action":"first"},{"type":"video","text":"Second","bind":"second","action":"second"},{"type":"button","text":"Forged","action":"forged"}]}"""))
                .put("source",source).toString())
            return ToolRecord(UUID.randomUUID().toString(),1,1,spec.json,spec.initialState,spec.ui.toString(),null,true).also {store.commitTool(it,null,true)}
        }

        fun resource(id: String, uri: Uri) = store.insertResource(CapabilityResource(id,tool.id,"video",uri.toString(),"video/mp4",file.length(),
            false,false,ToolStore.processSession,Long.MAX_VALUE))

        fun host(): ToolVideoHost {
            val effects=ToolVideoEffects(context,store,engine,openDescriptor={
                AssetFileDescriptor(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY),0,file.length())
            })
            return ToolVideoHost(CoroutineScope(jobs+Dispatchers.Main.immediate),displayed::get,{true},
                {DeviceMediaPlayback(backend)},effects::execute)
        }
    }

    private class Backend: DeviceMediaBackend {
        val prepared=Channel<Clip>(Channel.UNLIMITED)
        override fun startTone(tone: DeviceTone,durationMillis: Int): Closeable? = error("A video started a tone")
        override fun newClip(): MediaClipPlayer = Clip(prepared)
    }

    private class Clip(private val prepared: Channel<Clip>): MediaClipPlayer {
        lateinit var descriptor: AssetFileDescriptor
        private lateinit var onPrepared: (MediaClipInfo)->Unit
        private lateinit var onCompletion: ()->Unit
        val started=CompletableDeferred<Surface?>()
        var releases=0
        override fun prepare(source: AssetFileDescriptor,length: Long,onPrepared: (MediaClipInfo)->Unit,onCompletion: ()->Unit,onFailure: ()->Unit) {
            descriptor=source;this.onPrepared=onPrepared;this.onCompletion=onCompletion
            check(prepared.trySend(this).isSuccess)
        }
        fun ready()=onPrepared(MediaClipInfo(1,hasAudio=false,hasVideo=true,videoWidth=640,videoHeight=480))
        fun complete()=onCompletion()
        override fun start(surface: Surface?) {started.complete(surface)}
        override fun close() {releases++}
    }

    private suspend fun withLease(block: suspend (NativeVideoSurfaceLease)->Unit) {
        val texture=onMain {SurfaceTexture(false)}
        val lease=onMain {NativeVideoSurfaceLease(Surface(texture))}
        try {block(lease)} finally {onMain {lease.surface.release();texture.release()}}
    }
    private suspend fun <T> onMain(block: ()->T): T = withContext(Dispatchers.Main.immediate) {block()}

    companion object {
        private val FIRST="1".repeat(64)
        private val SECOND="2".repeat(64)
        private val FIRST_URI=Uri.parse("content://video-fixture/first")
        private val SECOND_URI=Uri.parse("content://video-fixture/second")
    }
}
