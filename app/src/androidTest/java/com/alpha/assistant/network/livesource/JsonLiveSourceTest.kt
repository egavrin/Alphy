package com.alpha.assistant.network.livesource

import com.alpha.assistant.network.NetworkMethod
import com.alpha.assistant.network.NetworkOwner
import com.alpha.assistant.network.NetworkResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JsonLiveSourceTest {
    // A definition can contain an unsupported scheme, malformed authority, or embedded credentials.
    @Test fun acceptsPublicAndLocalEndpointsAndProducesACredentialFreeGet() {
        for (endpoint in listOf(
            "https://example.test/weather?q=Tokyo",
            "http://localhost:8181/weather",
            "http://[::1]:8181/weather"
        )) {
            val request = source(endpoint).request()
            assertEquals(endpoint, request.url)
            assertEquals(NetworkMethod.GET, request.method)
            assertTrue(request.headers.isEmpty())
            assertNull(request.body)
        }

        for (endpoint in listOf(
            "file:///weather", "content://fixtures/weather", "javascript:fixture()",
            "//example.test/weather", "https:/weather", "http:///example.test/weather",
            "http://user:secret@example.test/weather", "http://@example.test/weather",
            "http://example.test:65536/weather", "https://example.test/weather#section",
            "http://example.test/a b", "http://example.test/a\n"
        )) {
            assertNull(JsonLiveSource.validate(key, endpoint, path, LiveValueKind.NUMBER, limits))
        }
    }

    // Oversized responses and selectors can exceed caller limits before extraction.
    @Test fun appliesByteAndPathLimitsToDefinitionsAndResponses() {
        assertNull(JsonLiveSource.validate(
            key, endpoint, path, LiveValueKind.NUMBER, limits.copy(maxPathSteps = path.size - 1)
        ))
        assertNull(JsonLiveSource.validate(
            key, endpoint, listOf(JsonPathStep.Key("é")), LiveValueKind.NUMBER, limits.copy(maxPathBytes = 1)
        ))
        assertNull(JsonLiveSource.validate(
            key, endpoint, listOf(JsonPathStep.Index(-1)), LiveValueKind.NUMBER, limits
        ))
        assertNull(JsonLiveSource.validate(
            key, "$endpoint/é", path, LiveValueKind.NUMBER, limits.copy(maxEndpointBytes = endpoint.length + 3)
        ))
        val limited = source(limits = limits.copy(maxResponseBytes = 2), path = emptyList())
        val fresh = limited.refresh(response("12"), firstTime, null)
        assertEquals("12", fresh.lastValid?.value?.content)
        val stale = limited.refresh(response("123"), secondTime, fresh.lastValid) as LiveSourceState.Stale
        assertEquals(LiveSourceFailure.RESPONSE_LIMIT, stale.reason)
        assertSame(fresh.lastValid, stale.lastValid)
    }

    // A refresh can round a number, keep the old endpoint, or keep the old data time.
    @Test fun successfulRefreshUpdatesExactValueConfiguredSourceAndDataTimeTogether() {
        val first = source().refresh(response("""{"results":[{"value":1}]}"""), firstTime, null)
            as LiveSourceState.Fresh
        val replacement = "https://replacement.test/live"
        val refreshed = source(replacement).refresh(
            response("""{"results":[{"value":9007199254740993}]}"""), secondTime, first.lastValid
        ) as LiveSourceState.Fresh
        assertEquals("9007199254740993", refreshed.lastValid.value.content)
        assertEquals(key, refreshed.lastValid.key)
        assertEquals(replacement, refreshed.lastValid.configuredEndpoint)
        assertEquals(secondTime, refreshed.lastValid.dataTime)
        assertEquals("1", first.lastValid.value.content)
        assertEquals(endpoint, first.lastValid.configuredEndpoint)
        assertEquals(firstTime, first.lastValid.dataTime)
    }

    // Failed refreshes can erase the last valid observation or mislabel its provenance.
    @Test fun failedRefreshKeepsTheLastValidObservationOrMarksFirstLoadUnavailable() {
        val prior = source().refresh(response("""{"results":[{"value":1}]}"""), firstTime, null)
            .lastValid
        val replacement = source("https://replacement.test/live")
        val rejected = replacement.refresh(response("""{"results":[{"value":7}]}""", 503), secondTime, prior)
            as LiveSourceState.Stale
        val malformed = replacement.refresh(response("{"), secondTime, prior) as LiveSourceState.Stale
        val unavailable = replacement.transportFailure(prior) as LiveSourceState.Stale
        assertEquals(LiveSourceFailure.HTTP_STATUS, rejected.reason)
        assertEquals(LiveSourceFailure.INVALID_JSON, malformed.reason)
        assertEquals(LiveSourceFailure.TRANSPORT, unavailable.reason)
        for (state in listOf(rejected, malformed, unavailable)) assertSame(prior, state.lastValid)

        val firstFailure = replacement.transportFailure(null) as LiveSourceState.Unavailable
        assertEquals(LiveSourceFailure.TRANSPORT, firstFailure.reason)
        assertNull(firstFailure.lastValid)
    }

    // A stale observation can disclose the value of a different database, mini-app, or source.
    @Test fun failedRefreshNeverReusesAnotherSourcesObservation() {
        val prior = source().refresh(response("""{"results":[{"value":1}]}"""), firstTime, null).lastValid
        for (other in listOf(
            key.copy(owner = key.owner.copy(databaseId = "another-db")),
            key.copy(owner = key.owner.copy(miniAppId = "another-app")),
            key.copy(sourceId = "another-source")
        )) {
            assertTrue(source(key = other).transportFailure(prior) is LiveSourceState.Unavailable)
        }
    }

    // Strict JSON can still contain an unknown or incorrectly typed value at the configured path.
    @Test fun doesNotCoerceValuesOrSelectAnUnknownPath() {
        for (body in listOf(
            """{"results":[{"value":"1"}]}""", """{"results":[{"value":true}]}""",
            """{"results":[{"value":null}]}""", """{"results":[{"value":{}}]}""",
            """{"results":[{"other":1}]}""", """{"results":[]}""", """{"results":{"0":{"value":1}}}"""
        )) {
            val state = source().refresh(response(body), firstTime, null) as LiveSourceState.Unavailable
            assertEquals(LiveSourceFailure.VALUE_MISMATCH, state.reason)
        }
        val text = source(kind = LiveValueKind.TEXT, path = emptyList())
            .refresh(response("\"green\""), firstTime, null) as LiveSourceState.Fresh
        assertEquals("green", text.lastValid.value.content)
        val flag = source(kind = LiveValueKind.BOOLEAN, path = emptyList())
            .refresh(response("false"), firstTime, null) as LiveSourceState.Fresh
        assertEquals("false", flag.lastValid.value.content)
    }

    // The pinned JSON parser accepts some malformed literals and unescaped controls.
    @Test fun rejectsMalformedJsonEvenOutsideTheSelectedPath() {
        val extractor = source()
        val prior = extractor.refresh(response("""{"results":[{"value":1}]}"""), firstTime, null).lastValid
        for (body in listOf(
            """{"results":[{"value":2}],"ignored":unquoted}""",
            """{"results":[{"value":2}],"ignored":01}""",
            """{"results":[{"value":2}],"ignored":NaN}""",
            """{"results":[{"value":2}],"ignored":NaN,"ignored":1}""",
            """{"results":[{"value":2}],"ignored":trueX}""",
            """{"results":[{"value":2}],}""",
            "{\"results\":[{\"value\":2}],\"ignored\":\"raw\ncontrol\"}",
            "{\"results\":[{\"value\":2}],\"ignored\":\"escaped\\\ncontrol\"}"
        )) {
            val stale = extractor.refresh(response(body), secondTime, prior) as LiveSourceState.Stale
            assertEquals(LiveSourceFailure.INVALID_JSON, stale.reason)
            assertSame(prior, stale.lastValid)
        }
        val malformedUtf8 = NetworkResponse(200, emptyList(), byteArrayOf(0x22, 0xC3.toByte(), 0x22))
        val stale = extractor.refresh(malformedUtf8, secondTime, prior) as LiveSourceState.Stale
        assertEquals(LiveSourceFailure.INVALID_JSON, stale.reason)
        assertSame(prior, stale.lastValid)
    }

    // A mutable discovery input can change the selector after validation.
    @Test fun snapshotsTheValidatedPath() {
        val mutable = path.toMutableList()
        val extractor = source(path = mutable)
        mutable.clear()
        val state = extractor.refresh(response("""{"results":[{"value":2}]}"""), firstTime, null)
        assertEquals("2", state.lastValid?.value?.content)
    }

    private fun source(
        endpoint: String = Companion.endpoint,
        key: LiveSourceKey = Companion.key,
        kind: LiveValueKind = LiveValueKind.NUMBER,
        limits: LiveSourceLimits = Companion.limits,
        path: List<JsonPathStep> = Companion.path
    ) = checkNotNull(JsonLiveSource.validate(key, endpoint, path, kind, limits))

    private fun response(body: String, status: Int = 200) =
        NetworkResponse(status, emptyList(), body.encodeToByteArray())

    companion object {
        private val key = LiveSourceKey(NetworkOwner("test-db", "test-app"), "weather")
        private const val endpoint = "https://example.test/weather"
        private val path = listOf(JsonPathStep.Key("results"), JsonPathStep.Index(0), JsonPathStep.Key("value"))
        private val firstTime = Instant.parse("2026-11-05T10:00:00Z")
        private val secondTime = Instant.parse("2026-11-05T10:05:00Z")
        private val limits = LiveSourceLimits(
            maxEndpointBytes = 256, maxPathSteps = 4, maxPathBytes = 32, maxResponseBytes = 256
        )
    }
}
