package com.alpha.assistant.tools

import android.Manifest
import android.app.Application
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityResource
import com.alpha.assistant.capabilities.CapabilityResources
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SurfaceBitmapWorkflowTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val offline=object: ChatProvider {
        override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) = error("Drawing stays offline")
    }
    private val handle="a".repeat(64)
    private fun bitmapCommand(id: String=handle)=JSONObject("""{"type":"bitmap","x":0,"y":0,"width":10,"height":10,"label":"Photo"}""").put("resourceId",id)
    private fun frame(vararg commands: JSONObject)=JSONObject().put("commands",JSONArray(commands.toList()))
    private fun parsed(vararg commands: JSONObject)=GraphicsContract.frame(frame(*commands).toString(),"frame").getJSONArray("commands")
    private fun pixels(width: Int=2,height: Int=2,color: Int=Color.RED)=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {eraseColor(color)}

    @Test fun bitmapUsesTransformAndSurfaceClipWithoutChangingLegacyDrawing() {
        val source=pixels()
        val target=pixels(32,32,Color.TRANSPARENT)
        try {
            val group=JSONObject("""{"type":"group","transform":[1,0,0,1,12,0]}""").put("children",JSONArray().put(bitmapCommand()))
            val rect=JSONObject("""{"type":"rect","x":0,"y":0,"width":5,"height":5,"color":"#0000FF"}""")
            val commands=parsed(group,rect)
            drawSurfaceFrame(Canvas(target),ToolSurface("main",16,16),commands,16f,16f,emptyMap(),mapOf(handle to source))
            assertEquals(Color.BLUE,target.getPixel(2,2))
            assertEquals(Color.RED,target.getPixel(13,2))
            assertEquals(Color.TRANSPARENT,target.getPixel(17,2))
            assertTrue(surfaceLabels(commands).contains("Photo"))
            val other=pixels(color=Color.GREEN)
            try {
                val canvas=Canvas(target)
                canvas.translate(16f,0f)
                drawSurfaceFrame(canvas,ToolSurface("second",16,16),parsed(bitmapCommand("b".repeat(64))),16f,16f,emptyMap(),mapOf("b".repeat(64) to other))
                assertEquals(Color.GREEN,target.getPixel(17,2))
                assertEquals(Color.RED,target.getPixel(13,2))
            } finally {other.recycle()}
            target.eraseColor(Color.TRANSPARENT)
            drawSurfaceFrame(Canvas(target),ToolSurface("main",16,16),commands,16f,16f,emptyMap())
            assertNotEquals(Color.TRANSPARENT,target.getPixel(13,2))
            assertNotEquals(Color.RED,target.getPixel(13,2))
            assertEquals(Color.TRANSPARENT,target.getPixel(17,2))
        } finally {source.recycle();target.recycle()}
    }

    @Test fun closedHandleGeometryAndMergedSurfaceBudgetsRejectInvalidResults() {
        for(command in listOf(bitmapCommand("https://example.com/x"),bitmapCommand("/data/photo"),
            bitmapCommand().put("pixels",JSONArray()),bitmapCommand().put("width",0),bitmapCommand().put("height",-1),
            bitmapCommand().put("x",8191),bitmapCommand().put("label",""),bitmapCommand().put("color","accent"))) {
            assertTrue(command.toString(),runCatching {parsed(command)}.isFailure)
        }
        val merged=JSONObject()
        repeat(ToolUiCatalog.MAX_IMAGES) {index -> merged.put("surface$index",frame(bitmapCommand(index.toString().padStart(64,'a'))))}
        ArtifactProtocol.validateSurfaces(merged)
        assertEquals(ToolUiCatalog.MAX_IMAGES,ArtifactProtocol.bitmapHandles(merged).size)
        merged.put("extra",frame(bitmapCommand("f".repeat(64))))
        assertTrue(runCatching {ArtifactProtocol.validateSurfaces(merged)}.isFailure)
        val repeated=Array(256) {bitmapCommand()}
        val wide=JSONObject().put("one",frame(*repeated)).put("two",frame(*repeated))
        assertTrue(runCatching {ArtifactProtocol.validateSurfaces(wide)}.isFailure)
    }

    @Test fun simultaneousDrawingCachesShareReservationsAndDropPixelsOnReplacement() = runBlocking {
        val shared=SurfaceBitmapMemory(64)
        val first=SurfaceBitmapCache(shared)
        val second=SurfaceBitmapCache(shared)
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val a=pixels(2,2)
        val b=pixels(2,2,Color.BLUE)
        val excess=pixels(4,4)
        val late=pixels(2,2)
        val lateEntered=CompletableDeferred<Unit>()
        val lateRelease=CompletableDeferred<Unit>()
        try {
            val pending=async {first.refresh(setOf("a"),{true},{_,budget ->
                assertEquals(64,budget);entered.complete(Unit);release.await();a
            })}
            entered.await()
            second.refresh(setOf("b"),{true},{_,_ -> error("The pending decode owns the available budget")})
            assertTrue(second.images.isEmpty())
            release.complete(Unit);pending.await()
            second.refresh(setOf("b"),{true},{_,budget -> assertEquals(48,budget);b})
            assertSame(a,first.images.getValue("a"))
            assertSame(b,second.images.getValue("b"))
            var reads=0
            first.refresh(setOf("a","excess"),{true},{_,_ -> reads++;excess})
            first.refresh(setOf("a","excess"),{true},{_,_ -> reads++;error("A corrupt decode must not repeat")})
            assertEquals(1,reads)
            assertTrue(excess.isRecycled)
            assertSame(a,first.images.getValue("a"))
            first.refresh(setOf("a"),{false},{_,_ -> error("A revoked resource must not decode")})
            assertTrue(first.images.isEmpty())
            second.close()
            assertTrue(second.images.isEmpty())
            assertEquals(0,shared.ownedBytes)
            val lateJob=async {first.refresh(setOf("late"),{true},{_,_ -> lateEntered.complete(Unit);lateRelease.await();late})}
            lateEntered.await()
            first.close()
            lateRelease.complete(Unit);lateJob.await()
            assertTrue(first.images.isEmpty())
            assertTrue(late.isRecycled)
            assertEquals(0,shared.ownedBytes)
        } finally {
            release.complete(Unit);lateRelease.complete(Unit);first.close();second.close()
            listOf(a,b,excess,late).filterNot(Bitmap::isRecycled).forEach(Bitmap::recycle)
        }
    }

    private fun spec(source: String,capability: String)=ToolSpec.parse(JSONObject("""{
        "formatVersion":3,"title":"Bitmap fixture","description":"","stateSchema":{"count":"number"},"initialState":{"count":0},
        "ui":{"type":"button","text":"Act","action":"act"},"surfaces":[{"id":"main","width":64,"height":64}]}
    """).put("capabilities",JSONArray().put(capability)).put("source",source).toString())
    private fun record(id: String,spec: ToolSpec)=ToolRecord(id,1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)

    @Test fun malformedBitmapRejectsStateAndEffectBeforeCommit() = runBlocking {
        val name="bitmap-invalid-${UUID.randomUUID()}.db"
        try {ToolStore(app,name).use {store ->
            val invalid=frame(bitmapCommand("content://private/path"))
            val spec=spec("""return {state:{count:1},surfaces:{main:$invalid},effects:[{id:'notice',capability:'notification.post',args:{title:'Fixture',text:'Never'}}]};""","notification.post")
            val tool=record("invalid",spec)
            store.saveTool(tool,true)
            val engine=ToolEngine(store,ToolGenerationService(offline,SandboxClient(app)))
            try {
                assertTrue(runCatching {engine.interact(tool.id,1,1,"act",null)}.isFailure)
                assertEquals(tool,store.tool(tool.id))
                assertTrue(store.effects(tool.id).isEmpty())
            } finally {engine.closeArtifacts()}
        }} finally {app.deleteDatabase(name)}
    }

    @Test fun realHostChecksOwnerGrantDecodeLimitsAndClosedGeneration() = runBlocking {
        val name="bitmap-owner-${UUID.randomUUID()}.db"
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val files=mutableListOf<File>()
        val context=object: ContextWrapper(app) {
            override fun checkSelfPermission(permission: String): Int =
                if(permission==Manifest.permission.CAMERA) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)
        }
        val store=ToolStore(context,name)
        val db=store.writableDatabase
        val keys=SecureKeyStore(app,name,name)
        var controller: ToolController?=null
        fun resource(owner: String,id: String,write: (File)->Unit) {
            val file=File(app.filesDir,"capture-${UUID.randomUUID()}.jpg");files+=file;write(file)
            store.insertResource(CapabilityResource(id,owner,"cameraPhoto",file.canonicalPath,"image/jpeg",file.length(),true,false,ToolStore.processSession,Long.MAX_VALUE))
        }
        try {
            val spec=spec("return {state:input.state};","camera.capture")
            val tool=record("owner",spec)
            store.saveTool(tool,true);store.saveTool(record("other",spec),true)
            fun valid(file: File) {val bitmap=pixels(64,64);try {file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))}} finally {bitmap.recycle()}}
            resource(tool.id,handle,::valid)
            resource("other","b".repeat(64),::valid)
            resource(tool.id,"c".repeat(64)) {it.writeText("Corrupt image")}
            resource(tool.id,"d".repeat(64)) {RandomAccessFile(it,"rw").use {file -> file.setLength(CapabilityResources.MAX_FILE_BYTES+1)}}
            val current=withContext(Dispatchers.Main) {ToolController(app,scope,offline,keys,store)}
            controller=current
            val host=current.artifactHost(tool)
            val opened=host.open()
            try {
                assertTrue(host.drawingImageAvailable(opened.sessionGeneration,handle))
                val owned=checkNotNull(host.loadDrawingImage(opened.sessionGeneration,handle,1024))
                try {assertTrue(owned.allocationByteCount<=1024)} finally {owned.recycle()}
                for(id in "bcde".map {it.toString().repeat(64)}) {
                    assertNull(host.loadDrawingImage(opened.sessionGeneration,id,ToolUiCatalog.MAX_IMAGE_BYTES))
                }
                CapabilityResources(app,store).release(checkNotNull(store.resource(handle)))
                assertFalse(host.drawingImageAvailable(opened.sessionGeneration,handle))
                assertNull(host.loadDrawingImage(opened.sessionGeneration,handle,1024))
                resource(tool.id,handle,::valid)
                val revised=tool.copy(revision=2)
                store.commitTool(revised,tool,true)
                assertNull(host.loadDrawingImage(opened.sessionGeneration,handle,1024))
                assertEquals(revised,store.tool(tool.id))
                assertTrue(store.effects(tool.id).isEmpty())
            } finally {host.close()}
            assertFalse(host.drawingImageAvailable(opened.sessionGeneration,handle))
        } finally {
            val current=controller
            if(current==null) store.close()
            else {
                withContext(Dispatchers.Main) {current.close()}
                withTimeout(5000) {while(db.isOpen) yield()}
            }
            scope.cancel();files.forEach(File::delete);app.deleteDatabase(name);app.deleteDatabase("$name-automations.db")
        }
    }
}
