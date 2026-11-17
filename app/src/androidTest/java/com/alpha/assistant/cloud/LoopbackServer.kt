package com.alpha.assistant.cloud

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Writes one complete JSON reply. */
internal fun OutputStream.jsonResponse(json: Any, status: Int = 200) {
    val bytes = json.toString().toByteArray()
    write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
    write(bytes)
    flush()
}

/** [bytes] is the raw body, for a binary upload that [body] would change. */
internal data class LoopbackRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String, val bytes: ByteArray)

/** One loopback HTTP server for device tests. It checks no headers: each test asserts its own. */
internal class LoopbackServer(val handler: (LoopbackRequest, OutputStream) -> Unit) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}"
    val requests = CopyOnWriteArrayList<LoopbackRequest>()
    val failures = CopyOnWriteArrayList<Throwable>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    init { Thread {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            sockets += socket
            Thread {
                try { socket.use {
                    socket.soTimeout = 15000
                    val input = socket.getInputStream().buffered()
                    fun line(): String { val result = StringBuilder(); while (true) { val b = input.read(); if (b < 0 || b == 10) return result.toString().trimEnd('\r'); result.append(b.toChar()) } }
                    val start = line().split(" ")
                    val headers = LinkedHashMap<String, String>()
                    while (true) { val header = line(); if (header.isEmpty()) break
                        headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..(40 * 1024 * 1024))
                    val bytes = ByteArray(length); var offset = 0
                    while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
                    val request = LoopbackRequest(start[0], start[1], headers, String(bytes, Charsets.UTF_8), bytes)
                    requests += request
                    handler(request, socket.getOutputStream())
                } } catch (failure: Throwable) { if (!server.isClosed && failure !is java.net.SocketException) failures += failure }
            }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true }.start() }
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } } }
}
