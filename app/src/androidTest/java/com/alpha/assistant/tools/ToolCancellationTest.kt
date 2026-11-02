package com.alpha.assistant.tools

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudTurn
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.cloud.ImageInput
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.SecureKeyStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ToolCancellationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<deadline) { if(condition()) return; SystemClock.sleep(25) }
        fail("Cancellation workflow timed out")
    }

    @Test fun blockedImageCancellationSendsNothingAndNextRequestStillWorks() {
        val app = instrumentation.targetContext.applicationContext as Application
        val name = "tool-cancel-${UUID.randomUUID()}"
        val store = ToolStore(app,"$name.db")
        val keys = SecureKeyStore(app,name,name)
        keys.save("tool-cancel-test-only").getOrThrow()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val imageReads = AtomicInteger()
        val received = AtomicInteger()
        val serverFailure = AtomicReference<Throwable?>()
        val server = ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            try {
                while(!server.isClosed) server.accept().use { socket ->
                    received.incrementAndGet()
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val text = StringBuilder()
                        while(true) { val b = input.read(); if(b < 0 || b == 10) return text.toString().trimEnd('\r'); text.append(b.toChar()); check(text.length <= 8192) }
                    }
                    check(line().startsWith("POST /chat/completions "))
                    var size = 0
                    var authorized = false
                    while(true) {
                        val header = line(); if(header.isEmpty()) break
                        if(header.startsWith("Content-Length:",true)) size = header.substringAfter(':').trim().toInt()
                        if(header.equals("Authorization: Bearer tool-cancel-test-only",true)) authorized = true
                    }
                    check(authorized && size in 1..(1024*1024))
                    repeat(size) { check(input.read() >= 0) }
                    socket.getOutputStream().write("HTTP/1.1 401 Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            } catch(failure: Throwable) { if(!server.isClosed) serverFailure.set(failure) }
        }
        val uri = checkNotNull(app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"$name.png"); put(MediaStore.Images.Media.MIME_TYPE,"image/png")
        }))
        val bitmap = Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
        app.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        lateinit var controller: ToolController
        var created = false
        try {
            main {
                controller = ToolController(app,scope,DeepSeekProvider("http://127.0.0.1:${server.localPort}"),keys,store) { attachment ->
                    if(imageReads.incrementAndGet() == 1) {
                        enteredRead.countDown()
                        check(releaseRead.await(10,TimeUnit.SECONDS))
                    }
                    ImageInput.dataUrl(app.contentResolver,attachment)
                }
                created = true
            }
            waitUntil { !controller.busy }
            val attachment = Attachment(uri.toString(),"$name.png","image/png")
            main { controller.generate("Create a counter",null,null,listOf(attachment)) }
            assertTrue(enteredRead.await(10,TimeUnit.SECONDS))
            main { controller.cancel() }
            releaseRead.countDown()
            waitUntil { !controller.busy }
            assertEquals("A stopped image request must not reach HTTP",0,received.get())
            assertNull(store.drafts().single().candidate)
            main { controller.generate("Create another counter",null,null,listOf(attachment)) }
            waitUntil { !controller.busy }
            assertEquals("A later request must use its own live session",1,received.get())
            assertEquals(2,imageReads.get())
            assertNull(serverFailure.get())
            assertNotNull(controller.error) // The isolated fixture deliberately returns HTTP 401.
        } finally {
            releaseRead.countDown()
            main { if(created) controller.close(); scope.cancel() }
            server.close(); serverThread.join(1000)
            runCatching { app.contentResolver.delete(uri,null,null) }
            store.close(); app.deleteDatabase("$name.db"); keys.clear(); app.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        }
    }

    @Test fun providerRejectsCanceledCoroutineAndSessionBeforeConnection() = runBlocking {
        ServerSocket(0,8,InetAddress.getByName("127.0.0.1")).use { server ->
            val received = AtomicInteger()
            val reader = thread(isDaemon = true) {
                runCatching { server.accept().use { received.incrementAndGet() } }
            }
            val provider = DeepSeekProvider("http://127.0.0.1:${server.localPort}")
            val request = CloudRequest(listOf(CloudTurn("user","fixture")),false)
            val canceledSession = CloudSession().apply { cancel() }
            assertTrue(runCatching { provider.respond("tool-cancel-test-only",request,canceledSession) {} }.isFailure)
            val job = launch(Dispatchers.IO) {
                currentCoroutineContext().cancel()
                runCatching { provider.respond("tool-cancel-test-only",request,CloudSession()) {} }
            }
            job.join()
            assertEquals(0,received.get())
            server.close(); reader.join(1000)
        }
    }
}
