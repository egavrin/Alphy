package com.alpha.assistant.voice

import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.providerFailure
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A scripted GPT-Live socket: it records what the session sends, and the test plays the server side. */
internal class FakeLiveTransport : LiveTransport {
    val sent = CopyOnWriteArrayList<String>()
    val closes = AtomicInteger()
    @Volatile var url = ""; private set
    @Volatile var key = ""; private set
    @Volatile private var listener: LiveListener? = null

    val connected get() = listener != null
    /** The `type` of each sent text, in order. */
    fun types() = sent.map { JSONObject(it).getString("type") }

    override fun connect(url: String, key: String, listener: LiveListener): LiveConnection {
        this.url = url; this.key = key; this.listener = listener
        return object : LiveConnection {
            override fun send(text: String): Boolean { sent += text; return true }
            override fun close() { closes.incrementAndGet() }
        }
    }

    fun emit(json: String) = checkNotNull(listener).onText(json)
    /** As the real transport: a refused upgrade gives the provider text, a socket failure a fixed text. */
    fun fail(status: Int?) = checkNotNull(listener).onFailure(status,
        if (status != null) providerFailure(AiProvider.OPENAI, status, null, false).message else "Нет соединения с голосовым режимом.")
    fun closeRemote() = checkNotNull(listener).onClosed()
}
