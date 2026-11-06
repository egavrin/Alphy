package com.alpha.assistant.memory

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class MemorySuggestionWorkflowTest {
    @Test
    fun creatingAndDecliningAReviewCannotSave() = withStore { store, scope ->
        val existing = store.saveExplicitFact("Контрольный факт.")
        val calls = AtomicInteger()
        val review = MemorySuggestionReview("offer-decline", "  Предложенный факт.  ", scope) { id, text ->
            calls.incrementAndGet()
            store.saveAcceptedSuggestion(id, text)
        }

        assertEquals("  Предложенный факт.  ", review.text)
        assertEquals(MemorySuggestionStatus.PENDING, review.status.value)
        assertEquals(listOf(existing), store.list())
        review.decline()
        review.accept()
        assertEquals(MemorySuggestionStatus.DECLINED, review.status.value)
        assertEquals(0, calls.get())
        assertEquals(listOf(existing), store.list())
        assertThrows(IllegalArgumentException::class.java) {
            MemorySuggestionReview("offer-blank", " \n ", scope) { _, _ -> error("Blank proposal reached storage.") }
        }
    }

    @Test
    fun repeatedAcceptanceSavesExactlyTheDisplayedTextOnce() = withStore { store, scope ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val proposed = "  Первая строка.\nВторая строка.  "
        val review = MemorySuggestionReview("offer-once", proposed, scope) { id, text ->
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, SECONDS))
            store.saveAcceptedSuggestion(id, text)
        }

        try {
            review.accept()
            assertTrue(entered.await(5, SECONDS))
            assertEquals(MemorySuggestionStatus.SAVING, review.status.value)
            review.accept()
            review.decline()
            assertEquals(MemorySuggestionStatus.SAVING, review.status.value)
            assertTrue(store.list().isEmpty())
        } finally {
            release.countDown()
        }
        review.await(MemorySuggestionStatus.ACCEPTED)
        review.accept()
        assertEquals(1, calls.get())
        assertEquals(proposed, store.list().single().text)
    }

    @Test
    fun retryAfterAnUncertainResponseKeepsTheSameAcceptanceIdWithoutSavingAgain() = withStore { store, scope ->
        val existing = store.saveExplicitFact("Существующий факт.")
        val attempts = mutableListOf<Pair<String, String>>()
        val proposed = "  Факт для повторной попытки.  "
        val review = MemorySuggestionReview("offer-retry", proposed, scope) { id, text ->
            attempts.add(id to text)
            store.saveAcceptedSuggestion(id, text)
            if (attempts.size == 1) error("Synthetic response failure")
        }

        review.accept()
        review.await(MemorySuggestionStatus.FAILED)
        assertEquals(proposed, review.text)
        val afterFirstAttempt = store.list()
        assertEquals(2, afterFirstAttempt.size)
        assertTrue(existing in afterFirstAttempt)

        review.accept()
        review.await(MemorySuggestionStatus.ACCEPTED)
        assertEquals(listOf("offer-retry" to proposed, "offer-retry" to proposed), attempts)
        assertEquals(afterFirstAttempt, store.list())
    }

    private fun MemorySuggestionReview.await(expected: MemorySuggestionStatus) = runBlocking {
        withTimeout(5_000) { status.first { it == expected } }
    }

    private fun withStore(test: (MemoryStore, CoroutineScope) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "memory-suggestion-${UUID.randomUUID()}.db"
        val job = SupervisorJob()
        try {
            MemoryStore(context, name).use { store ->
                try {
                    test(store, CoroutineScope(job + Dispatchers.Default))
                } finally {
                    job.cancel()
                    runBlocking { job.join() }
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
