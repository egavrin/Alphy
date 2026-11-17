package com.alpha.assistant.cloud

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal enum class KeyCheck { ACCEPTED, LIMITED, NO_CREDIT, REJECTED, UNREACHABLE }

// Anthropic API reference, current version header value.
internal const val ANTHROPIC_VERSION = "2026-10-21"
private const val MAX_BALANCE_BYTES = 64L * 1024

private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

private fun AiAccount.request(base: String): Request {
    // The headers are the ones that chat sends (CloudHttp), so one rule holds the auth of each provider.
    val (path, headers) = when (provider) {
        AiProvider.OPENROUTER -> "/api/v1/key" to bearerAuth(key)
        AiProvider.OPENAI -> "/v1/models" to bearerAuth(key)
        AiProvider.ANTHROPIC -> "/v1/models" to anthropicHeaders(key)
        AiProvider.DEEPSEEK -> "/user/balance" to bearerAuth(key)
    }
    return Request.Builder().url(base + path).apply { headers.forEach { (name, value) -> header(name, value) } }.get().build()
}

private fun Response.result(provider: AiProvider): KeyCheck = when {
    code == 401 -> KeyCheck.REJECTED
    code == 402 -> KeyCheck.NO_CREDIT
    code == 403 -> KeyCheck.LIMITED
    code != 200 -> KeyCheck.UNREACHABLE
    provider == AiProvider.DEEPSEEK && !hasBalance() -> KeyCheck.NO_CREDIT
    else -> KeyCheck.ACCEPTED
}

/** A balance body that is unreadable or too large counts as available: the 200 already proves the key. */
private fun Response.hasBalance(): Boolean = runCatching {
    val source = checkNotNull(body).source()
    require(!source.request(MAX_BALANCE_BYTES + 1))
    JSONObject(source.readUtf8()).optBoolean("is_available", true)
}.getOrDefault(true)

/** Asks the selected provider whether the key works. It sends one request and never logs the key. */
internal suspend fun checkKey(account: AiAccount, endpoints: Endpoints = Endpoints.PRODUCTION): KeyCheck {
    val request = account.request(endpoints.checked(account.provider))
    return suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resume(KeyCheck.UNREACHABLE)
            override fun onResponse(call: Call, response: Response) =
                continuation.resume(response.use { it.result(account.provider) })
        })
    }
}
