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
        override fun onClosed() = Unit
    }

    @Test fun urlUsesWssInProductionAndWsOnLoopback() {
        assertEquals("wss://api.openai.com/v1/live/sessions", liveUrl(Endpoints.PRODUCTION))
        assertEquals("ws://127.0.0.1:4000/v1/live/sessions", liveUrl(Endpoints { "http://127.0.0.1:4000" }))
    }

    @Test fun connectSendsBearerAndPassesMessagesBothWays() {
        val received = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("from-server") }
                override fun onMessage(webSocket: WebSocket, text: String) { received += text }
            }).build())
            server.start()
            val events = Events()
            val connection = OkHttpLiveTransport().connect(liveUrl(Endpoints { server.url("/").toString().trimEnd('/') }), "alpha-test-only", events)
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
}
