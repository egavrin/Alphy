package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.LoopbackRequest
import com.alpha.assistant.cloud.LoopbackServer
import com.alpha.assistant.cloud.responsesStart
import com.alpha.assistant.cloud.stallingResponses
import com.alpha.assistant.sandbox.SandboxClient
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The limits of one app-file call: each slow call ends near its limit and leaves the session open. A cancel of the person still ends it. */
@RunWith(AndroidJUnit4::class)
class AppFileCallWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "alpha-test-only"

    private fun service(provider: ChatProvider) = ToolGenerationService(provider, SandboxClient(context))

    // A short read timeout bounds a test whose watcher does not end the read.
    private fun openAi(server: LoopbackServer) = CloudChatProvider({ AiAccount(AiProvider.OPENAI, key) }, Endpoints { server.url },
        responseReadTimeoutMillis = 10_000, recordEmptyCompletion = {})

    private class Timed(val reply: AppFileReply, val millis: Long, val timings: List<ModelCompletionTiming>)

    /** Runs one plan call in the thread of the caller, as a blocked HTTP read would hold it. */
    private fun ToolGenerationService.timedCall(session: CloudSession, limits: AppFileLimits): Timed {
        val timings = CopyOnWriteArrayList<ModelCompletionTiming>()
        val started = System.nanoTime()
        val reply = runBlocking {
            appFileCall(key, session, CloudGenerationStage.APP_FILE, "PLAN SYSTEM", "A tip calculator.", limits) { timings += it }
        }
        return Timed(reply, (System.nanoTime() - started) / 1_000_000, timings)
    }

    /**
     * Runs a plan call that the server stalls after [start], then a second call on the same session, which must get DONE.
     * Returns the stalled call and the HTTP requests.
     */
    private fun stallThenRecover(limits: AppFileLimits, start: OutputStream.() -> Unit): Pair<Timed, List<LoopbackRequest>> {
        val release = CountDownLatch(1)
        stallingResponses(release, "DONE", start).use { server ->
            val result = try {
                val service = service(openAi(server))
                val session = CloudSession()
                val stalled = service.timedCall(session, limits)
                assertEquals(AppFileReply.Text("DONE"), service.timedCall(session, AppFileLimits()).reply)
                stalled to server.requests.toList()
            } finally { release.countDown() }
            assertTrue(server.failures.toString(), server.failures.isEmpty())
            return result
        }
    }

    @Test fun aStallAfterTheFirstTextEndsAtTheTotalLimit() {
        val (stalled, requests) = stallThenRecover(AppFileLimits(firstTextMs = 1_000, totalMs = 2_500)) { responsesStart("PLAN") }
        assertEquals(AppFileReply.Failed("total_timeout"), stalled.reply)
        assertTrue("${stalled.millis} ms", stalled.millis in 2_500..3_500)
        val timing = stalled.timings.single()
        assertNotNull(timing.firstTextNanos)
        assertFalse(timing.completed)
        assertEquals(2, requests.size)
        // The plan stage sends plain text: no JSON mode on the wire.
        val sent = JSONObject(requests[1].body)
        assertEquals("PLAN SYSTEM", sent.getString("instructions"))
        assertFalse(sent.optJSONObject("text")?.has("format") == true)
    }

    @Test fun aStallBeforeTheHeadersEndsAtTheFirstTextLimit() {
        val (stalled, _) = stallThenRecover(AppFileLimits(firstTextMs = 1_500, totalMs = 8_000)) {}
        assertEquals(AppFileReply.Failed("first_text_timeout"), stalled.reply)
        assertTrue("${stalled.millis} ms", stalled.millis in 1_500..2_500)
    }

    /** A fake has no connection to disconnect, so only the cancel of the call ends it. */
    @Test fun aCallWithNoConnectionEndsAtItsLimits() {
        for ((text, reason) in listOf(null to "first_text_timeout", "PLAN" to "total_timeout")) {
            val provider = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    if (text != null) emit(CloudEvent.Text(text))
                    awaitCancellation()
                }
            }
            val stalled = service(provider).timedCall(CloudSession(), AppFileLimits(firstTextMs = 1_000, totalMs = 2_000))
            assertEquals(AppFileReply.Failed(reason), stalled.reply)
            val limit = if (text == null) 1_000L else 2_000L
            assertTrue("$reason ${stalled.millis} ms", stalled.millis in limit..limit + 1_000)
        }
    }

    @Test fun aFailedCallGivesCallFailed() {
        for (failure in listOf(CloudFailure("unavailable", "Fixture outage"), IOException("Fixture reset"))) {
            val provider = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    throw failure
                }
            }
            assertEquals(AppFileReply.Failed("call_failed"), service(provider).timedCall(CloudSession(), AppFileLimits()).reply)
        }
    }

    @Test fun aCancelOfThePersonEndsTheRequest() {
        val release = CountDownLatch(1)
        val written = CountDownLatch(1)
        stallingResponses(release, "DONE") { responsesStart("PLAN"); written.countDown() }.use { server ->
            val service = service(openAi(server))
            val session = CloudSession()
            try {
                val failure = runBlocking {
                    val call = async(Dispatchers.IO) {
                        try { service.appFileCall(key, session, CloudGenerationStage.APP_FILE, "PLAN SYSTEM", "A tip calculator.", AppFileLimits()) {}; null }
                        catch (failure: CloudFailure) { failure }
                    }
                    assertTrue(written.await(10, TimeUnit.SECONDS))
                    Thread.sleep(300) // The client reads the first text and waits for more.
                    session.cancel()
                    withTimeout(5_000) { call.await() }
                }
                assertEquals("cancelled", failure?.code)
                // The session stays canceled: a later call fails before any HTTP request.
                val later = runCatching { service.timedCall(session, AppFileLimits()) }.exceptionOrNull()
                assertEquals("cancelled", (later as? CloudFailure)?.code)
            } finally { release.countDown() }
            assertEquals(1, server.requests.size)
            assertTrue(server.failures.toString(), server.failures.isEmpty())
        }
    }

    @Test fun aCancelOfTheCallerGoesUp(): Unit = runBlocking {
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                emit(CloudEvent.Text("PLAN"))
                awaitCancellation()
            }
        }
        var outcome: Any? = null
        val job = launch {
            try { outcome = service(provider).appFileCall(key, CloudSession(), CloudGenerationStage.APP_FILE, "PLAN SYSTEM",
                "A tip calculator.", AppFileLimits()) {} }
            catch (cancel: CancellationException) { outcome = cancel; throw cancel }
        }
        delay(300)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue("$outcome", outcome is CancellationException)
    }
}
