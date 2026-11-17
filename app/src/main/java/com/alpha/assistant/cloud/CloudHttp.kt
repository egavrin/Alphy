package com.alpha.assistant.cloud

import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The HTTP transport of every cloud request: no redirects, connection and read timeouts, and a connection
 * that only its own session can cancel. It logs nothing. The caller supplies every header.
 */
internal class CloudHttp(private val onHttpCall: (() -> Unit)?, private val readTimeoutMillis: Int) {
    suspend fun open(url: String, headers: Map<String, String>, contentType: String, bytes: ByteArray,
        session: CloudSession): HttpURLConnection {
        session.checkActive(currentCoroutineContext())
        val connection = URL(url).openConnection() as HttpURLConnection
        session.attach(connection)
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 20000; connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false; connection.doOutput = true
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.setRequestProperty("Content-Type", contentType)
            connection.setFixedLengthStreamingMode(bytes.size)
            session.checkActive(currentCoroutineContext())
            session.beforeHttp?.invoke()
            session.checkActive(currentCoroutineContext())
            onHttpCall?.invoke()
            connection.outputStream.use { it.write(bytes) }
            return connection
        } catch (failure: Exception) { session.detach(connection); throw failure }
    }
}

internal fun JSONObject.utf8(): ByteArray = toString().toByteArray(Charsets.UTF_8)

/** The bearer auth pair alone, for a plain GET such as the key check. */
internal fun bearerAuth(key: String) = mapOf("Authorization" to "Bearer $key")

internal fun bearerHeaders(key: String) = bearerAuth(key) + ("Accept" to "text/event-stream, application/json")

/** Anthropic takes the key in `x-api-key`, never in `Authorization`. */
internal fun anthropicHeaders(key: String) = mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION)

private const val ERROR_BODY_LIMIT = 16 * 1024

/** Returns on a 2xx status. Otherwise it reads at most 16 KiB of the error body, never logs it, and throws [providerFailure]. */
internal fun HttpURLConnection.requireSuccess(provider: AiProvider, search: Boolean) {
    val status = responseCode
    if (status in 200..299) return
    val body = try {
        errorStream?.use { String(it.readNBytes(ERROR_BODY_LIMIT), Charsets.UTF_8) }
    } catch (_: IOException) { null }
    throw providerFailure(provider, status, body, search)
}

/**
 * Sends one streamed chat request and gives [parse] a reader of the response lines. The reader checks the session
 * before each line and counts the received characters against [StreamLimits.maxWireChars]. Invalid JSON gives
 * `stream_invalid`. A transport failure gives a `pre_headers_*` or `stream_*` code.
 */
internal suspend fun <T> CloudHttp.streamLines(provider: AiProvider, url: String, headers: Map<String, String>, body: ByteArray, session: CloudSession,
    limits: StreamLimits, parse: suspend (nextLine: suspend () -> String?) -> T): T {
    var opened: HttpURLConnection? = null
    var headersReceived = false
    try {
        val connection = open(url, headers, "application/json", body, session).also { opened = it }
        connection.requireSuccess(provider, false)
        headersReceived = true
        var received = 0
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            parse {
                session.checkActive(currentCoroutineContext())
                reader.boundedLine(limits.maxWireChars - received)?.also { line -> received += line.length }
            }
        }
    } catch (_: org.json.JSONException) {
        throw streamInvalid()
    } catch (failure: IOException) {
        session.checkActive(currentCoroutineContext())
        if (failure is CloudFailure) throw failure
        if (!headersReceived) {
            if (limits.stage != CompletionStage.GENERATION) throw failure
            if (failure is SocketTimeoutException)
                throw CloudFailure("pre_headers_timeout", "${provider.displayName} did not return response headers in time. Retry the request.")
            throw CloudFailure("pre_headers_io", "The ${provider.displayName} connection failed before response headers. Retry the request.")
        }
        if (failure is SocketTimeoutException)
            throw CloudFailure("stream_timeout", "${provider.displayName} did not finish the response in time. Retry the request.")
        throw CloudFailure("stream_io", "The ${provider.displayName} response stream was interrupted. Retry the request.")
    } finally { opened?.let(session::detach) }
}

/**
 * Sends one request and gives [read] the body of the 2xx reply and a reader of the response headers. The body is null
 * when it is longer than [limit] bytes. A canceled coroutine disconnects only the request of [session]. An HTTP error
 * becomes the [providerFailure] of [provider]. A transport failure of a request that is not canceled stays an
 * [IOException], so the caller maps it. The call ends [session], so each call needs a new session.
 */
internal suspend fun <T> CloudHttp.reply(provider: AiProvider, url: String, headers: Map<String, String>, contentType: String,
    body: ByteArray, limit: Int, session: CloudSession, read: (ByteArray?, (String) -> String?) -> T): T = coroutineScope {
    // A blocking read ignores cancellation, so this child disconnects the request of this session.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { session.cancel() } }
    var connection: HttpURLConnection? = null
    try {
        connection = open(url, headers, contentType, body, session)
        connection.requireSuccess(provider, false)
        val bytes = connection.inputStream.use { readBounded(it, limit + 1) }
        session.checkActive(currentCoroutineContext())
        // [read] runs before the detach, so the response headers are still readable.
        read(bytes.takeIf { it.size <= limit }, connection::getHeaderField)
    } catch (failure: IOException) {
        // A stop disconnects the request, so its IOException reports the stop. This check must run before the
        // finally, because the end of the watcher cancels the session.
        session.checkActive(currentCoroutineContext())
        throw failure
    } finally {
        connection?.let(session::detach)
        watcher.cancel()
    }
}

/**
 * Reads one line as [BufferedReader.readLine] does (`\n`, `\r`, or `\r\n` ends it), but stops with `response_size`
 * when the line has more than [max] characters, so a line without an end never fills memory.
 */
private fun BufferedReader.boundedLine(max: Int): String? {
    val line = StringBuilder()
    while (true) {
        val char = read()
        if (char < 0) return if (line.isEmpty()) null else line.toString()
        if (char == '\n'.code) return line.toString()
        if (char == '\r'.code) {
            mark(1)
            if (read() != '\n'.code) reset()
            return line.toString()
        }
        if (line.length == max) throw CloudFailure("response_size", "Ответ слишком большой")
        line.append(char.toChar())
    }
}

/** The end-of-stream rules of every chat wire: the length outcome and an unfinished stream. */
internal fun requireFinish(finish: String?, done: Boolean, reportLength: Boolean) {
    if (reportLength && done && finish == "length") throw CloudFailure("response_length", "Достигнут лимит ответа. Уточните запрос.")
    if (!done || finish !in listOf("stop", "tool_calls")) {
        val message = if (!reportLength && finish == "length") "Достигнут лимит ответа. Уточните запрос."
            else "Соединение прервано. Ответ неполный. Повторите попытку."
        throw CloudFailure("incomplete", message)
    }
}

/** Records the shape of an empty reply, never its text, and returns the `empty_response` failure. */
internal fun emptyResponse(provider: AiProvider, record: (String) -> Unit, stage: CompletionStage, contentSeen: Boolean, text: CharSequence,
    details: String): CloudFailure {
    val content = when {
        !contentSeen -> "absent"
        text.isEmpty() -> "empty"
        else -> "blank"
    }
    record("empty_response stage=${stage.name} content=$content $details")
    return CloudFailure("empty_response", "${provider.displayName} вернул пустой ответ. Повторите попытку.")
}

/** The wire failures that every chat wire raises with the same code and text. */
internal fun streamInvalid() = CloudFailure("stream_invalid", "Сервис вернул неверный поток ответа")
internal fun toolsRefused() = CloudFailure("tool_invalid", "The answer cannot call tools.")
internal fun toolLimit() = CloudFailure("tool_limit", "Модель превысила лимит функций")
internal fun responseTooLarge() = CloudFailure("response_size", "The assistant response exceeds the allowed size.")

/** The most tool calls that one chat answer may hold. */
internal const val MAX_TOOL_CALLS = 2
