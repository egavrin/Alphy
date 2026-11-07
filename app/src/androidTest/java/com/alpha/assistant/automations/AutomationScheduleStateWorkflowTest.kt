package com.alpha.assistant.automations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationScheduleStateWorkflowTest {
    private val due = Instant.parse("2026-10-30T08:00:00Z").toEpochMilli()
    private val replacement = Instant.parse("2026-10-31T08:00:00Z").toEpochMilli()

    @Test fun completionPreservesAReplacementOccurrence() = withScheduledRule { store, scheduled ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val executor = AutomationExecutor(store, { null }, { _, _ -> null }) { _, _, _ ->
            calls++
            entered.complete(Unit)
            release.await()
            CapabilityResult("succeeded", "Fixture")
        }

        withTimeout(10_000) {
            val delivery = async { AutomationSchedule.deliver(store, scheduled, due, due, executor) }
            entered.await()
            store.next(scheduled.id, scheduled.revision, replacement)
            assertEquals(replacement, store.rule(scheduled.id)?.nextAt)
            release.complete(Unit)
            assertEquals("succeeded", delivery.await()?.status)
            assertEquals(1, calls)
            assertEquals(1, store.history(scheduled.id).size)
            assertEquals("Completing the old occurrence must preserve its replacement", replacement, store.rule(scheduled.id)?.nextAt)
            val current = checkNotNull(store.rule(scheduled.id))
            assertEquals("succeeded", AutomationSchedule.deliver(store, current, replacement, replacement, executor)?.status)
            assertNull("Completing the current occurrence must clear it", store.rule(scheduled.id)?.nextAt)
            assertEquals(2, calls)
            assertEquals(2, store.history(scheduled.id).size)
        }
    }

    @Test fun supersededOccurrenceDoesNotExecute() = assertSupersededOccurrenceIgnored(due)

    @Test fun expiredSupersededOccurrenceDoesNotCreateMissedHistory() =
        assertSupersededOccurrenceIgnored(due + AutomationSchedule.MAX_LATENESS + 1)

    private fun assertSupersededOccurrenceIgnored(now: Long) = withScheduledRule { store, scheduled ->
        store.next(scheduled.id, scheduled.revision, replacement)
        var calls = 0
        val executor = AutomationExecutor(store, { null }, { _, _ -> null }) { _, _, _ ->
            calls++
            CapabilityResult("succeeded", "Fixture")
        }
        val delivered = AutomationSchedule.deliver(store, scheduled, due, now, executor)
        assertEquals("Superseded occurrence must not execute", 0, calls)
        assertEquals("Superseded occurrence must not create history", emptyList<AutomationRun>(), store.history(scheduled.id))
        assertNull(delivered)
        assertEquals(replacement, store.rule(scheduled.id)?.nextAt)
    }

    private fun withScheduledRule(block: suspend (AutomationStore, AutomationRule) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "schedule-completion-${UUID.randomUUID()}.db"
        try {
            AutomationStore(context, database).use { store ->
                val rule = store.save(null, JSONObject("""
                    {"name":"Schedule fixture","trigger":{"type":"schedule","repeat":"daily","time":"08:00",
                     "zone":"UTC","exact":false},"conditions":{},"actions":[
                     {"id":"notice","capability":"notification.post","args":{"title":"Fixture","text":"Reminder"}}]}
                """.trimIndent()))
                store.approve(rule.id, rule.revision)
                store.next(rule.id, rule.revision, due)
                block(store, checkNotNull(store.rule(rule.id)))
            }
        } finally {
            context.deleteDatabase(database)
        }
    }
}
