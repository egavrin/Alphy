package com.alpha.assistant.voice

import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.checked
import com.alpha.assistant.cloud.providerFailure
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Receives the socket events on OkHttp's thread. The caller hops to its own thread. */
internal interface LiveListener {
    fun onText(text: String)
    /** [status] is the HTTP status of a refused upgrade, else null. [message] never holds the key. */
    fun onFailure(status: Int?, message: String)
    fun onClosed()
}

internal interface LiveConnection {
    fun send(text: String): Boolean
    fun close()
}

internal fun interface LiveTransport {
    /** Returns at once. The socket connects asynchronously and reports through [listener]. */
    fun connect(url: String, key: String, listener: LiveListener): LiveConnection
}

/** The GPT-Live socket URL: the checked OpenAI base with `https` as `wss` and `http` as `ws` (spec 5.3.1). */
internal fun liveUrl(endpoints: Endpoints): String {
    val base = endpoints.checked(AiProvider.OPENAI)
    return base.replaceFirst("http", "ws") + "/v1/live/sessions"
}

internal class OkHttpLiveTransport : LiveTransport {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    override fun connect(url: String, key: String, listener: LiveListener): LiveConnection {
        val socket = client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer $key").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
                // Without this reply OkHttp never finishes the handshake of a server close, and onClosed would not fire.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Only the status and fixed texts leave here: a throwable message could echo the request.
                    val status = response?.code
                    listener.onFailure(status,
                        if (status != null) providerFailure(AiProvider.OPENAI, status, null, false).message
                        else "Нет соединения с голосовым режимом.")
                }
            })
        return object : LiveConnection {
            override fun send(text: String) = socket.send(text)
            override fun close() { socket.close(1000, null) }
        }
    }
}
