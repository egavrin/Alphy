package com.alpha.assistant.sandbox

import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Connection-only regressions: transient support/bind failure, partial cleanup, shared waiters, and stale failure completion.
 * Fake connections never create an isolate or execute JavaScript. Device workflow tests cover the real AndroidX adapter.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeConnectionRetryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Connection(private val features: Boolean=true,private val cleanup: ()->Unit = {}): RuntimeSandbox {
        val featureChecks=AtomicInteger();val closes=AtomicInteger();val isolates=AtomicInteger()
        override fun isFeatureSupported(feature: String): Boolean {featureChecks.incrementAndGet();return features}
        override fun createIsolate(parameters: IsolateStartupParameters): JavaScriptIsolate {isolates.incrementAndGet();error("No JavaScript in connection tests")}
        override fun close() {closes.incrementAndGet();cleanup()}
    }
    @Test fun explicitRetryRechecksSupportAndConnectsOnce(): Unit=runBlocking {
        val support=AtomicInteger();val connects=AtomicInteger();val healthy=Connection()
        val runtime=AndroidJsRuntime(context,supported={support.incrementAndGet()>1},connect={connects.incrementAndGet();healthy})
        assertEquals("UNAVAILABLE",runtime.availability().status)
        assertEquals(0,connects.get())
        assertEquals("OK",runtime.availability().status)
        assertEquals("OK",runtime.availability().status)
        assertEquals(2,support.get());assertEquals(1,connects.get());assertEquals(3,healthy.featureChecks.get())
        assertEquals(0,healthy.isolates.get());assertEquals(0,healthy.closes.get())
    }
    @Test fun explicitRetryAfterBindFailureDoesNotReplayWork(): Unit=runBlocking {
        val connects=AtomicInteger();val healthy=Connection()
        val runtime=AndroidJsRuntime(context,supported={true},connect={if(connects.incrementAndGet()==1)throw IOException("Synthetic bind failure");healthy})
        assertEquals("UNAVAILABLE",runtime.availability().status)
        assertEquals(1,connects.get())
        assertEquals("OK",runtime.availability().status)
        assertEquals(2,connects.get());assertEquals(0,healthy.isolates.get())
    }
    @Test fun partialCleanupFinishesBeforeConcurrentRetryAndOldCompletionCannotClearSuccess(): Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val releaseConnection=CompletableDeferred<Unit>()
        val closing=CountDownLatch(1);val releaseCleanup=CountDownLatch(1)
        val failed=Connection(features=false) {closing.countDown();check(releaseCleanup.await(3,TimeUnit.SECONDS))}
        val healthy=Connection();val connects=AtomicInteger()
        val runtime=AndroidJsRuntime(context,supported={true},connect={
            if(connects.incrementAndGet()==1) {entered.complete(Unit);releaseConnection.await();failed} else healthy
        })
        val first=async {runtime.availability()};entered.await()
        val siblings=List(16) {async(start=CoroutineStart.UNDISPATCHED) {runtime.availability()}}
        releaseConnection.complete(Unit)
        assertTrue(withContext(Dispatchers.IO) {closing.await(3,TimeUnit.SECONDS)})
        val duringCleanup=async(start=CoroutineStart.UNDISPATCHED) {runtime.availability()}
        assertEquals(1,connects.get());assertFalse(first.isCompleted);assertFalse(duringCleanup.isCompleted)
        releaseCleanup.countDown()
        assertEquals("UNAVAILABLE",first.await().status)
        val retry=async(start=CoroutineStart.UNDISPATCHED) {runtime.availability()}
        siblings.forEach {assertEquals("UNAVAILABLE",it.await().status)}
        assertEquals("UNAVAILABLE",duringCleanup.await().status)
        assertEquals("OK",retry.await().status)
        List(16) {async {runtime.availability()}}.awaitAll().forEach {assertEquals("OK",it.status)}
        assertEquals(2,connects.get());assertEquals(1,failed.closes.get());assertEquals(0,healthy.closes.get())
        assertEquals(0,failed.isolates.get());assertEquals(0,healthy.isolates.get())
    }
}
