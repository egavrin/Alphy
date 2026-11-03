package com.alpha.assistant.tools

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Failure cases precede the channel implementation: untrusted UID, absent peer, leaked listener, and raw error text. */
class OneUseCredentialTest {
    @Test fun untrustedPeerAndTimeoutCloseTheAbstractListener() {
        val name="test-${UUID.randomUUID()}"
        val ready=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val thread=Thread {
            try {OneUseCredential.receive(name,1000) {ready.countDown()}}
            catch(problem: Exception) {failure.set(problem)}
        }
        thread.start()
        assertTrue(ready.await(2,TimeUnit.SECONDS))
        LocalSocket().use {it.connect(LocalSocketAddress("compose-credential-$name"))}
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertNotNull(failure.get())
        LocalServerSocket("compose-credential-$name").close()
        val timeout="timeout-${UUID.randomUUID()}"
        assertTrue(runCatching {OneUseCredential.receive(timeout,20) {}}.isFailure)
        LocalServerSocket("compose-credential-$timeout").close()
    }
}
