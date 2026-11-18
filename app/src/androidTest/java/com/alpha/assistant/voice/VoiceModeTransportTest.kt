package com.alpha.assistant.voice

import com.alpha.assistant.cloud.Endpoints
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The GPT-Live transport against a loopback WebSocket server (spec 5.3.1). */
class VoiceModeTransportTest {
    private class Events : LiveListener {
        val texts = LinkedBlockingQueue<String>()
        val failures = LinkedBlockingQueue<Pair<Int?, String>>()
        override fun onText(text: String) { texts += text }
        override fun onFailure(status: Int?, message: String) { failures += status to message }
        val closed = java.util.concurrent.LinkedBlockingQueue<Unit>()
        override fun onClosed() { closed += Unit }
    }

    /** Starts [server] with one WebSocket upgrade handled by [serverSide], and connects the transport to it. */
    private fun connect(server: MockWebServer, events: Events, serverSide: WebSocketListener): LiveConnection {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(serverSide).build())
        server.start()
        return OkHttpLiveTransport().connect(liveUrl(Endpoints { server.url("/").toString().trimEnd('/') }), "alpha-test-only", events)
    }

    /** `onClosed` fires once and `onFailure` never fires. */
    private fun assertClosedOnce(events: Events) {
        assertNotNull(events.closed.poll(5, TimeUnit.SECONDS))
        assertNull(events.closed.poll(500, TimeUnit.MILLISECONDS))
        assertTrue(events.failures.isEmpty())
    }

    @Test fun urlUsesWssInProductionAndWsOnLoopback() {
        assertEquals("wss://api.openai.com/v1/live/sessions", liveUrl(Endpoints.PRODUCTION))
        assertEquals("ws://127.0.0.1:4000/v1/live/sessions", liveUrl(Endpoints { "http://127.0.0.1:4000" }))
    }

    @Test fun connectSendsBearerAndPassesMessagesBothWays() {
        val received = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            val events = Events()
            val connection = connect(server, events, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("from-server") }
                override fun onMessage(webSocket: WebSocket, text: String) { received += text }
            })
            try {
                assertEquals("from-server", events.texts.poll(5, TimeUnit.SECONDS))
                assertTrue(connection.send("from-client"))
                assertEquals("from-client", received.poll(5, TimeUnit.SECONDS))
                val request = server.takeRequest()
                assertEquals("/v1/live/sessions", request.url.encodedPath)
                assertEquals("Bearer alpha-test-only", request.headers["Authorization"])
            } finally { connection.close() }
        }
    }

    @Test fun refusedUpgradeReportsTheStatusWithoutTheKey() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(401).body("""{"error":"bad key alpha-test-only"}""").build())
            server.start()
            val events = Events()
            OkHttpLiveTransport().connect(liveUrl(Endpoints { server.url("/").toString().trimEnd('/') }), "alpha-test-only", events)
            val (status, message) = checkNotNull(events.failures.poll(5, TimeUnit.SECONDS))
            assertEquals(401, status)
            assertFalse(message, "alpha-test-only" in message)
        }
    }

    @Test fun serverCloseReportsClosedOnceAndNoFailure() {
        MockWebServer().use { server ->
            val events = Events()
            connect(server, events, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1000, "done") }
            })
            assertClosedOnce(events)
        }
    }

    @Test fun clientCloseReportsClosedOnce() {
        MockWebServer().use { server ->
            val events = Events()
            val connection = connect(server, events, object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            })
            server.takeRequest()
            connection.close()
            assertClosedOnce(events)
        }
    }
}
