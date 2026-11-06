package com.alpha.assistant.network.livesource

import com.alpha.assistant.network.NetworkOwner
import com.alpha.assistant.network.NetworkRequest
import com.alpha.assistant.network.NetworkResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.CharacterCodingException
import java.time.Instant

internal data class LiveSourceKey(val owner: NetworkOwner, val sourceId: String)

internal data class LiveSourceLimits(
    val maxEndpointBytes: Int,
    val maxPathSteps: Int,
    val maxPathBytes: Int,
    val maxResponseBytes: Int
) {
    init {
        require(maxEndpointBytes > 0 && maxPathSteps >= 0 && maxPathBytes >= 0 && maxResponseBytes > 0)
    }
}

internal sealed interface JsonPathStep {
    data class Key(val value: String) : JsonPathStep
    data class Index(val value: Int) : JsonPathStep
}

internal enum class LiveValueKind { TEXT, NUMBER, BOOLEAN }

internal class LiveSourceSample(
    val key: LiveSourceKey,
    val configuredEndpoint: String,
    val value: JsonPrimitive,
    val dataTime: Instant
)

internal sealed interface LiveSourceState {
    val lastValid: LiveSourceSample?

    data class Fresh(override val lastValid: LiveSourceSample) : LiveSourceState

    data class Stale(
        override val lastValid: LiveSourceSample,
        val reason: LiveSourceFailure
    ) : LiveSourceState

    data class Unavailable(val reason: LiveSourceFailure) : LiveSourceState {
        override val lastValid: LiveSourceSample? = null
    }
}

internal enum class LiveSourceFailure { TRANSPORT, HTTP_STATUS, RESPONSE_LIMIT, INVALID_JSON, VALUE_MISMATCH }

internal class JsonLiveSource private constructor(
    val key: LiveSourceKey,
    val configuredEndpoint: String,
    private val path: List<JsonPathStep>,
    private val kind: LiveValueKind,
    private val maxResponseBytes: Int
) {
    /** Run this request through the existing mini-app network grant. */
    fun request() = NetworkRequest(configuredEndpoint)

    fun refresh(response: NetworkResponse, dataTime: Instant, previous: LiveSourceSample?): LiveSourceState {
        if (response.status !in 200..299) return failed(previous, LiveSourceFailure.HTTP_STATUS)
        if (response.body.size > maxResponseBytes) return failed(previous, LiveSourceFailure.RESPONSE_LIMIT)
        val document = try {
            val text = response.body.decodeToString(throwOnInvalidSequence = true)
            if (!validJsonTokens(text)) return failed(previous, LiveSourceFailure.INVALID_JSON)
            Json.parseToJsonElement(text)
        } catch (_: CharacterCodingException) {
            return failed(previous, LiveSourceFailure.INVALID_JSON)
        } catch (_: SerializationException) {
            return failed(previous, LiveSourceFailure.INVALID_JSON)
        }

        var selected = document
        for (step in path) {
            selected = when (step) {
                is JsonPathStep.Key -> (selected as? JsonObject)?.get(step.value)
                is JsonPathStep.Index -> (selected as? JsonArray)?.getOrNull(step.value)
            } ?: return failed(previous, LiveSourceFailure.VALUE_MISMATCH)
        }
        val value = selected as? JsonPrimitive
        if (value == null || value === JsonNull || !matchesKind(value)) {
            return failed(previous, LiveSourceFailure.VALUE_MISMATCH)
        }
        return LiveSourceState.Fresh(LiveSourceSample(key, configuredEndpoint, value, dataTime))
    }

    fun transportFailure(previous: LiveSourceSample?): LiveSourceState =
        failed(previous, LiveSourceFailure.TRANSPORT)

    private fun matchesKind(value: JsonPrimitive): Boolean {
        val boolean = !value.isString && (value.content == "true" || value.content == "false")
        return when (kind) {
            LiveValueKind.TEXT -> value.isString
            LiveValueKind.NUMBER -> !value.isString && !boolean
            LiveValueKind.BOOLEAN -> boolean
        }
    }

    private fun failed(previous: LiveSourceSample?, reason: LiveSourceFailure): LiveSourceState =
        if (previous?.key == key) LiveSourceState.Stale(previous, reason) else LiveSourceState.Unavailable(reason)

    companion object {
        /** Supply a credential-free endpoint and the host's limits for this source. */
        fun validate(
            key: LiveSourceKey,
            endpoint: String,
            path: List<JsonPathStep>,
            kind: LiveValueKind,
            limits: LiveSourceLimits
        ): JsonLiveSource? {
            if (key.sourceId.isBlank() || path.size > limits.maxPathSteps) return null
            val url = validateEndpoint(endpoint, limits.maxEndpointBytes) ?: return null
            val steps = path.toList()
            var remainingBytes = limits.maxPathBytes
            for (step in steps) {
                val stepBytes = when (step) {
                    is JsonPathStep.Key -> utf8Size(step.value, remainingBytes) ?: return null
                    is JsonPathStep.Index -> {
                        if (step.value < 0) return null
                        step.value.toString().length
                    }
                }
                if (stepBytes > remainingBytes) return null
                remainingBytes -= stepBytes
            }
            return JsonLiveSource(key, url, steps, kind, limits.maxResponseBytes)
        }

        private fun validateEndpoint(endpoint: String, maxBytes: Int): String? {
            utf8Size(endpoint, maxBytes) ?: return null
            val uri = try {
                URI(endpoint)
            } catch (_: URISyntaxException) {
                return null
            }
            val authority = uri.rawAuthority ?: return null
            if (!uri.isAbsolute || '@' in authority || uri.rawFragment != null) return null
            val url = endpoint.toHttpUrlOrNull()?.toString() ?: return null
            return url.takeIf { it.length <= maxBytes }
        }

        private fun utf8Size(value: String, maxBytes: Int): Int? {
            if (value.length > maxBytes) return null
            return try {
                value.encodeToByteArray(throwOnInvalidSequence = true).size.takeIf { it <= maxBytes }
            } catch (_: CharacterCodingException) {
                null
            }
        }

        private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        private const val delimiters = " \t\r\n{}[],:"

        // The tree parser accepts some unquoted values and raw controls inside strings.
        private fun validJsonTokens(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                when (text[index]) {
                    in delimiters -> index++
                    '"' -> {
                        index++
                        while (index < text.length && text[index] != '"') {
                            if (text[index] == '\\') index++
                            if (index == text.length || text[index] < ' ') return false
                            index++
                        }
                        if (index == text.length) return false
                        index++
                    }
                    else -> {
                        val start = index++
                        while (index < text.length && text[index] !in delimiters && text[index] != '"') index++
                        val token = text.substring(start, index)
                        if (token != "null" && token != "true" && token != "false" && !jsonNumber.matches(token)) {
                            return false
                        }
                    }
                }
            }
            return true
        }
    }
}
