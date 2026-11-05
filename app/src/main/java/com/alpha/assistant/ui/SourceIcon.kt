package com.alpha.assistant.ui

import android.graphics.BitmapFactory
import android.net.DnsResolver
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.alpha.assistant.cloud.validSourceUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val MAX_ICON_BYTES = 64 * 1024L
private const val MAX_ICON_SIDE = 256
private const val ICON_TIMEOUT_MS = 4_000L
private val iconClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(ICON_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .dispatcher(Dispatcher().apply { maxRequests = 4 })
    .build()

@Composable internal fun rememberSourceIcon(value: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, value) {
        this.value = null
        val url = value?.takeIf(::validSourceUrl)?.toHttpUrlOrNull() ?: return@produceState
        // Restrict fetches to HTTPS DNS names. OkHttp can bypass custom DNS for numeric addresses.
        val host = url.host.trimEnd('.')
        if (!url.isHttps || url.port != 443 || '.' !in host || host.lastOrNull()?.isLetter() != true ||
            host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".home.arpa")) return@produceState
        this.value = try {
            withTimeoutOrNull(ICON_TIMEOUT_MS) {
                val addresses = resolveIconHost(host).filter(::isPublicIconAddress)
                if (addresses.isEmpty()) return@withTimeoutOrNull null
                // Connect to the checked addresses and verify TLS against the original host.
                val client = iconClient.newBuilder().dns { name ->
                    if (name == url.host) addresses else throw UnknownHostException(name)
                }.build()
                val request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
                fetchIcon(client.newCall(request))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }
    return image
}

private suspend fun resolveIconHost(host: String): List<InetAddress> = suspendCancellableCoroutine { continuation ->
    val cancellation = CancellationSignal()
    continuation.invokeOnCancellation { cancellation.cancel() }
    DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY, Executor { it.run() }, cancellation,
        object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer: List<InetAddress>, rcode: Int) = continuation.resume(if (rcode == 0) answer else emptyList())
            override fun onError(error: DnsResolver.DnsException) = continuation.resume(emptyList())
        })
}

private fun isPublicIconAddress(address: InetAddress): Boolean {
    val bytes = address.address
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val third = bytes[2].toInt() and 0xff
    if (bytes.size == 4) {
        // Exclude private, shared, link-local, reserved, test, and multicast IPv4 ranges.
        return when (first) {
            0, 10, 127, in 224..255 -> false
            100 -> second !in 64..127
            169 -> second != 254
            172 -> second !in 16..31
            192 -> second != 168 && !(second == 0 && (third == 0 || third == 2)) && !(second == 88 && third == 99)
            198 -> second !in 18..19 && !(second == 51 && third == 100)
            203 -> !(second == 0 && third == 113)
            else -> true
        }
    }
    // Admit global unicast IPv6, with no special assignments, documentation, or transition ranges.
    return when (first) {
        0x20 -> when (second) {
            1 -> third > 1 && !(third == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8)
            2 -> false
            else -> true
        }
        0x3f -> second != 0xff
        else -> first in 0x21..0x3e
    }
}

private suspend fun fetchIcon(call: Call): ImageBitmap? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = continuation.resume(null)
        override fun onResponse(call: Call, response: Response) {
            val icon = runCatching {
                response.use {
                    val body = response.body
                    if (!response.isSuccessful || body.contentType()?.type != "image" ||
                        body.contentLength() > MAX_ICON_BYTES) return@use null
                    val source = body.source()
                    if (source.request(MAX_ICON_BYTES + 1)) return@use null
                    val bytes = source.readByteArray()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..MAX_ICON_SIDE || bounds.outHeight !in 1..MAX_ICON_SIDE) return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
            continuation.resume(icon)
        }
    })
}
