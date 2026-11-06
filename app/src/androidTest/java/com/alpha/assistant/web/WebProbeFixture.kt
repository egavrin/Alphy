package com.alpha.assistant.web

import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class WebProbeFixture(private val probe: WebProbe, address: InetAddress) : AutoCloseable {
    private val host = checkNotNull(address.hostAddress)
    private val server = ServerSocket(0, 8, address)
    private val sinkServer = ServerSocket(0, 8, address)
    val origin = "http://$host:${server.localPort}"
    val sink = "http://$host:${sinkServer.localPort}"
    val traffic = ConcurrentLinkedQueue<Exchange>()
    val errors = ConcurrentLinkedQueue<String>()
    val heldRequest = CountDownLatch(1)
    val releaseHeld = CountDownLatch(1)
    @Volatile var heldPeerClosed: Boolean? = null
        private set
    private val knownPaths = probe.expected.map { it.path }.toSet() +
        setOf("/fixture", "/favicon.ico", "/after-revoke")
    private val knownBodies = probe.expected.map { it.body }.toSet() + ""
    private val workers = Executors.newFixedThreadPool(8)
    private val listeners = listOf(listen(server, false), listen(sinkServer, true))

    fun findOrigin(uri: Uri): String? {
        if (uri.scheme != "http" || uri.host != host || uri.userInfo != null) return null
        return when (uri.port) {
            server.localPort -> origin
            sinkServer.localPort -> sink
            else -> null
        }
    }

    fun safePath(path: String, source: String?): String = when {
        source == null -> "<outside fixture>"
        path !in knownPaths -> "<unexpected request target>"
        else -> path
    }

    private fun listen(listener: ServerSocket, isSink: Boolean) = thread(isDaemon = true, name = "web-probe-listener") {
        try {
            while (!listener.isClosed) {
                val socket = listener.accept()
                workers.execute { socket.use { serve(it, isSink) } }
            }
        } catch (failure: Exception) {
            if (failure !is SocketException || !listener.isClosed) errors += "Listener: ${failure.javaClass.simpleName}"
        }
    }

    private fun serve(socket: Socket, isSink: Boolean) {
        var path = "<unread>"
        try {
            socket.soTimeout = 1_500
            val input = BufferedInputStream(socket.getInputStream())
            val line = try {
                input.asciiLine()
            } catch (_: SocketTimeoutException) {
                return
            } ?: return
            socket.soTimeout = 5_000
            val fields = line.split(' ')
            check(fields.size == 3 && fields[2].startsWith("HTTP/1."))
            val method = fields[0].takeIf { it in setOf("GET", "POST", "OPTIONS") } ?: "<unexpected method>"
            path = safePath(fields[1], if (isSink) sink else origin)
            var length = 0
            while (true) {
                val header = checkNotNull(input.asciiLine())
                if (header.isEmpty()) break
                val name = header.substringBefore(':')
                check(!name.equals("Transfer-Encoding", ignoreCase = true)) { "Unsupported fixture request framing" }
                if (name.equals("Content-Length", ignoreCase = true)) {
                    length = header.substringAfter(':').trim().toInt()
                    check(length in 0..1_024)
                }
            }
            val bytes = ByteArray(length)
            DataInputStream(input).readFully(bytes)
            val body = bytes.toString(Charsets.US_ASCII).takeIf { it in knownBodies }
                ?: "<unexpected body: $length bytes>"
            traffic += Exchange(method, path, body, isSink)
            when {
                path == "/favicon.ico" -> socket.respond(404, "Not Found")
                path == "/fixture" && !isSink -> socket.respond(200, "OK", "<title>READY</title><body></body>", "text/html")
                path == "/first" && !isSink -> socket.respond(302, "Found", location = "$origin/middle")
                path == "/middle" && !isSink -> socket.respond(302, "Found", location = "$sink/target")
                path == "/worker.js" && !isSink -> socket.respond(200, "OK", WORKER_SCRIPT, "text/javascript")
                path == "/held" && !isSink -> {
                    heldRequest.countDown()
                    check(releaseHeld.await(8, TimeUnit.SECONDS)) { "Held request was not released" }
                    socket.soTimeout = 750
                    heldPeerClosed = try {
                        input.read() == -1
                    } catch (_: SocketTimeoutException) {
                        false
                    } catch (_: SocketException) {
                        true
                    }
                    if (heldPeerClosed == false) socket.respond(303, "See Other", location = "$sink/target")
                }
                path == probe.expected.last().path && isSink == probe.expected.last().sink ->
                    socket.respond(200, "OK", "<title>OK</title>", "text/html")
                path == "/after-revoke" && !isSink -> socket.respond(200, "OK", "after-revoke")
                else -> socket.respond(404, "Not Found")
            }
        } catch (failure: Exception) {
            if (failure is SocketException && (path == "/held" || path == "/favicon.ico")) return
            errors += "Endpoint $path: ${failure.javaClass.simpleName}"
        }
    }

    override fun close() {
        releaseHeld.countDown()
        server.close()
        sinkServer.close()
        listeners.forEach { it.join(5_000) }
        workers.shutdown()
        check(listeners.none { it.isAlive } && workers.awaitTermination(6, TimeUnit.SECONDS)) {
            "Fixture did not close"
        }
    }

    private fun Socket.respond(
        status: Int,
        reason: String,
        text: String = "",
        type: String = "text/plain",
        location: String? = null,
    ) {
        val body = text.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Content-Type: $type; charset=UTF-8\r\n")
            append("Cache-Control: no-store\r\nConnection: close\r\n")
            append("Access-Control-Allow-Origin: $origin\r\n")
            if (location != null) append("Location: $location\r\n")
            append("\r\n")
        }
        getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun BufferedInputStream.asciiLine(): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 8_192) {
            when (val value = read()) {
                -1 -> return null
                '\n'.code -> return bytes.toString(Charsets.US_ASCII.name())
                '\r'.code -> Unit
                else -> bytes.write(value)
            }
        }
        error("Fixture request line exceeds the limit")
    }
}

internal val WORKER_SCRIPT = """
    fetch('/target').then(response => postMessage(response.ok ? 'OK' : 'BLOCKED'),
        () => postMessage('ERROR'));
""".trimIndent()
