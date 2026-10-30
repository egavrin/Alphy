package com.alpha.assistant.cloud

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.data.*
import com.alpha.assistant.ui.ShellViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.OutputStream
import java.net.ServerSocket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Device workflows use the real HTTP provider, SQLite and Keystore, with isolated test data. */
class CloudWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val automation get() = instrumentation.uiAutomation
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(50) }
        fail("Workflow condition timed out")
    }
    private fun textChunk(value: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", value))))
    private fun end(reason: String = "stop") = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", reason)))
    private fun OutputStream.event(json: JSONObject) { write("data: $json\n\n".toByteArray()); flush() }
    private fun OutputStream.headers(status: Int = 200, type: String = "text/event-stream") { write("HTTP/1.1 $status Test\r\nContent-Type: $type\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
    private fun OutputStream.answer(value: String) { headers(); event(textChunk(value)); event(end()); write("data: [DONE]\n\n".toByteArray()); flush() }
    private data class Request(val path: String, val json: JSONObject)
    private class Server(val handler: (Request, OutputStream) -> Unit) : Closeable {
        private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<Request>()
        val failures = CopyOnWriteArrayList<Throwable>()
        private val sockets = CopyOnWriteArrayList<java.net.Socket>()
        init { Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets += socket
                Thread {
                    try { socket.use {
                        socket.soTimeout = 15000
                        val input = socket.getInputStream().buffered()
                        fun line(): String { val result = StringBuilder(); while (true) { val b = input.read(); if (b < 0 || b == 10) return result.toString().trimEnd('\r'); result.append(b.toChar()) } }
                        val path = line().split(" ")[1]
                        var length = 0
                        var authorized = false
                        while (true) { val header = line(); if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            if (header.equals("Authorization: Bearer alpha-test-only", true)) authorized = true
                        }
                        check(authorized) { "Missing isolated test authorization" }
                        check(length in 1..(40 * 1024 * 1024))
                        val bytes = ByteArray(length); var offset = 0
                        while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
                        val request = Request(path, JSONObject(String(bytes, Charsets.UTF_8)))
                        requests += request
                        handler(request, socket.getOutputStream())
                    } } catch (failure: Throwable) { if (!server.isClosed && failure !is java.net.SocketException) failures += failure }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start() }
        override fun close() { server.close(); sockets.forEach { runCatching { it.close() } } }
    }
    private inner class Fixture(provider: ChatProvider, seed: (ShellStore) -> Unit = {}) : Closeable {
        val name = "cloud-test-${UUID.randomUUID()}"
        val store = ShellStore(app, "$name.db")
        val keys = SecureKeyStore(app, name, name)
        lateinit var model: ShellViewModel
        var scenario: ActivityScenario<CloudTestActivity>? = null
        init {
            seed(store)
            check(keys.save("alpha-test-only").isSuccess)
            CloudTestActivity.factory = { application -> ShellViewModel(application, provider, store, keys).also { model = it } }
            scenario = ActivityScenario.launch(CloudTestActivity::class.java)
            waitUntil { !model.loading }
        }
        fun submit(text: String, search: Boolean = false) { main { model.editDraft(model.snapshot.draft.copy(text = text)); model.setSearch(search); model.send() } }
        fun idle() { waitUntil { model.activeConversationId == null } }
        val last: Message get() { var message: Message? = null; main { message = model.snapshot.selected?.messages?.lastOrNull() }; return checkNotNull(message) }
        fun persisted(): ShellSnapshot { var result: ShellSnapshot? = null; waitUntil { result = store.load(); result!!.selected?.messages?.lastOrNull()?.status == last.status }; return result!! }
        override fun close() {
            main { model.cancelRequest() }
            scenario?.close(); CloudTestActivity.factory = null
            // Wait for the serialized writer before deleting only this fixture's data.
            SystemClock.sleep(150)
            store.close(); app.deleteDatabase("$name.db"); app.deleteDatabase("$name.db-tools.db"); keys.clear()
            app.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        }
    }

    @Test fun consentStreamSearchSourcesAndDurableHistory() {
        Server { request, output ->
            when (request.path) {
                "/v1/tools/search" -> {
                    assertEquals("Alpha test", request.json.getString("query")); assertEquals(5, request.json.getInt("count"))
                    output.headers(type = "application/json")
                    output.write("{\"results\":[{\"title\":\"Verified source\",\"url\":\"https://example.com/alpha\",\"summary\":\"Real fixture result\"}]}".toByteArray())
                }
                "/chat/completions" -> {
                    val messages = request.json.getJSONArray("messages")
                    if (messages.getJSONObject(messages.length() - 1).getString("role") == "tool") {
                        assertEquals("https://example.com/alpha", JSONObject(messages.getJSONObject(messages.length() - 1).getString("content")).getJSONArray("results").getJSONObject(0).getString("url"))
                        output.answer("Проверенный ответ [1]")
                    } else {
                        assertEquals("deepseek-flash", request.json.getString("model"))
                        assertTrue(request.json.getBoolean("stream"))
                        output.headers()
                        output.event(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                            .put("index", 0).put("id", "search-1").put("function", JSONObject().put("name", "web_search").put("arguments", "{\"query\":"))))))))
                        output.event(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                            .put("index", 0).put("function", JSONObject().put("arguments", "\"Alpha test\"}"))))))))
                        output.event(end("tool_calls")); output.write("data: [DONE]\n\n".toByteArray())
                    }
                }
                else -> error("Unexpected path")
            }
        }.use { server -> Fixture(DeepSeekProvider(server.url)).use { fixture ->
            setText("Сообщение ассистенту", "Найди Alpha test")
            main { fixture.model.setSearch(true) }
            click("Отправить сообщение")
            waitFor("Отправить в DeepSeek?")
            assertEquals(0, server.requests.size)
            click("Отправить")
            fixture.idle()
            assertEquals("${fixture.last.errorCode}: ${fixture.last.error}; server failures=${server.failures}; paths=${server.requests.map { it.path }}", "complete", fixture.last.status)
            assertEquals("Проверенный ответ [1]", fixture.last.text)
            assertEquals("found", fixture.last.searchStatus)
            assertEquals("https://example.com/alpha", fixture.last.sources.single().url)
            assertEquals(3, server.requests.size)
            assertEquals(fixture.last, fixture.persisted().selected!!.messages.last())
            fixture.scenario!!.recreate()
            waitFor("Проверенный ответ [1]")
            assertTrue(server.failures.isEmpty())
        } }
    }

    @Test fun imageBytesAreOneTimeAndRevocationIsExplicit() {
        Server { _, output -> output.answer("Изображение принято") }.use { server -> Fixture(DeepSeekProvider(server.url)).use { fixture ->
            val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "alpha-test.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            })!!
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
            app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            try {
                val attachment = Attachment(uri.toString(), "alpha-test.png", "image/png")
                main { fixture.model.addAttachment(attachment.copy(mime = "image/jpeg")) }
                waitUntil { !fixture.model.attachmentBusy }
                assertTrue(fixture.model.snapshot.draft.attachments.isEmpty())
                assertNotNull(fixture.model.requestError)
                main { fixture.model.clearRequestError() }
                app.contentResolver.openOutputStream(uri, "wt")!!.use { output -> output.write(ByteArray(ImageInput.MAX_BYTES + 1)) }
                main { fixture.model.addAttachment(attachment) }; waitUntil { !fixture.model.attachmentBusy }
                assertTrue(fixture.model.snapshot.draft.attachments.isEmpty()); assertTrue(fixture.model.requestError!!.contains("5 МиБ"))
                main { fixture.model.clearRequestError() }
                app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                main { fixture.model.addAttachment(attachment) }
                waitUntil { !fixture.model.attachmentBusy }
                waitFor("Предпросмотр alpha-test.png")
                fixture.submit("Что на изображении?")
                fixture.idle(); assertEquals("complete", fixture.last.status)
                val content = server.requests[0].json.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
                val data = content.getJSONObject(1).getJSONObject("image_url").getString("url")
                assertArrayEquals(bytes, android.util.Base64.decode(data.substringAfter(','), android.util.Base64.DEFAULT))
                fixture.submit("Следующий вопрос")
                fixture.idle()
                val next = server.requests[1].json.toString()
                assertFalse(next.contains("base64")); assertFalse(next.contains(uri.toString()))
                main { fixture.model.editDraft(Draft("Удалённый файл", listOf(attachment))) }
                app.contentResolver.delete(uri, null, null)
                main { fixture.model.send() }; fixture.idle()
                assertEquals("image_access", fixture.last.errorCode)
                assertEquals(2, server.requests.size)
                assertEquals("error", fixture.persisted().selected!!.messages.last().status)
            } finally { runCatching { app.contentResolver.delete(uri, null, null) } }
            assertTrue(server.failures.isEmpty())
        } }
    }

    @Test fun cancelCannotChangeNewConversationAndRetryUsesSameUser() {
        val slowStarted = CountDownLatch(1); val releaseSlow = CountDownLatch(1)
        Server { request, output ->
            val text = request.json.getJSONArray("messages").getJSONObject(1).optString("content")
            when (text) {
                "slow" -> { output.headers(); output.event(textChunk("Сохранённая часть")); slowStarted.countDown(); releaseSlow.await(10, TimeUnit.SECONDS); runCatching { output.event(textChunk("Устаревший ответ")); output.event(end()); output.write("data: [DONE]\n\n".toByteArray()) } }
                "reject" -> { output.headers(401, "application/json"); output.write("{}".toByteArray()) }
                else -> output.answer("Новый ответ")
            }
        }.use { server -> Fixture(DeepSeekProvider(server.url)).use { fixture ->
            fixture.submit("slow"); assertTrue(slowStarted.await(10, TimeUnit.SECONDS))
            waitUntil { fixture.last.text == "Сохранённая часть" }
            val oldId = fixture.model.snapshot.selectedId
            main { fixture.model.cancelRequest(); fixture.model.select(null) }
            fixture.submit("new"); fixture.idle()
            val newId = fixture.model.snapshot.selectedId
            releaseSlow.countDown(); SystemClock.sleep(200)
            assertEquals("Новый ответ", fixture.last.text)
            assertEquals(newId, fixture.model.snapshot.selectedId)
            val old = fixture.model.snapshot.conversations.first { it.id == oldId }.messages.last()
            assertEquals("cancelled", old.status); assertEquals("Сохранённая часть", old.text)
            main { fixture.model.select(null) }; fixture.submit("reject"); fixture.idle()
            assertEquals("auth", fixture.last.errorCode)
            val rejected = fixture.last
            main { fixture.model.retry(rejected.id) }; fixture.idle()
            assertEquals("auth", fixture.last.errorCode)
            assertEquals(rejected.replyTo, fixture.last.replyTo)
            assertEquals(1, fixture.model.snapshot.selected!!.messages.count { it.role == "user" })
            assertEquals("auth", fixture.persisted().selected!!.messages.last().errorCode)
        } }
    }

    @Test fun restartMissingKeySearchQuotaAndEmptyResults() {
        var response = "empty"
        Server { request, output ->
            if (request.path == "/v1/tools/search") {
                if (response == "quota") { output.headers(402, "application/json"); output.write("{}".toByteArray()) }
                else { output.headers(type = "application/json"); output.write("{\"results\":[]}".toByteArray()) }
            } else {
                val messages = request.json.getJSONArray("messages")
                if (messages.getJSONObject(messages.length() - 1).getString("role") == "tool") output.answer("Результаты не найдены")
                else {
                    output.headers(); output.event(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                        .put("index", 0).put("id", "search").put("function", JSONObject().put("name", "web_search").put("arguments", "{\"query\":\"Alpha\"}"))))))))
                    output.event(end("tool_calls")); output.write("data: [DONE]\n\n".toByteArray())
                }
            }
        }.use { server -> Fixture(DeepSeekProvider(server.url), seed = { store ->
            store.saveConversation(Conversation("restart", "Interrupted", 1, 1, messages = listOf(Message("u", "user", "test", 1, "sent"), Message("a", "assistant", "partial", 1, "streaming", replyTo = "u"))))
            store.saveSetting("selected", "restart")
        }).use { fixture ->
            assertEquals("interrupted", fixture.last.status); assertEquals("partial", fixture.last.text)
            assertEquals("interrupted", fixture.persisted().selected!!.messages.last().status)
            fixture.keys.clear(); fixture.submit("No credential"); fixture.idle()
            assertEquals("key_missing", fixture.last.errorCode); assertEquals(0, server.requests.size)
            app.getSharedPreferences(fixture.name, android.content.Context.MODE_PRIVATE).edit().putString("ciphertext", "broken").putString("iv", "broken").commit()
            fixture.submit("Unreadable credential"); fixture.idle()
            assertEquals("key_read", fixture.last.errorCode); assertEquals(0, server.requests.size)
            assertEquals("broken", app.getSharedPreferences(fixture.name, android.content.Context.MODE_PRIVATE).getString("ciphertext", null))
            fixture.keys.clear(); fixture.keys.save("alpha-test-only")
            fixture.submit("Empty search", true); fixture.idle()
            assertEquals("complete", fixture.last.status); assertEquals("empty", fixture.last.searchStatus); assertTrue(fixture.last.sources.isEmpty())
            response = "quota"; fixture.submit("Quota search", true); fixture.idle()
            assertEquals("quota", fixture.last.errorCode); assertEquals("error", fixture.last.searchStatus)
            assertTrue(fixture.last.error.startsWith("Поиск:")); assertTrue(server.failures.isEmpty())
        } }
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { return it } }; return null
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        waitUntil { node = find(automation.rootInActiveWindow, label); node != null }; return node!!
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(checkNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK)); automation.waitForIdle(100, 3000)
    }
    private fun field(label: String): AccessibilityNodeInfo {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> editable(child)?.let { return it } } }
            return null
        }
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null) { editable(node)?.let { return it }; node = node.parent }
        error("No editable field")
    }
    private fun setText(label: String, value: String) {
        assertTrue(field(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        waitUntil { field(label).text?.toString() == value }
    }
}
