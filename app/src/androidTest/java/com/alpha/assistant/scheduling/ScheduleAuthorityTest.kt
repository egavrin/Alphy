package com.alpha.assistant.scheduling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleAuthorityTest {
    @Test
    fun simultaneousDeliveriesClaimOnceAndReleaseStorageBeforeEffects() = runBlocking {
        val fixture = Fixture()
        val start = CompletableDeferred<Unit>()
        val releaseEffect = CompletableDeferred<Unit>()
        val results = Channel<ScheduleDeliveryResult>(2)
        val authority = fixture.authority { _, action ->
            assertFalse(Thread.holdsLock(fixture.store))
            fixture.effects.add(action.id)
            releaseEffect.await()
            ScheduleActionResult.COMPLETED
        }
        val delivery = fixture.arm(checkNotNull(authority.activate(fixture.publication)))
        val attempts = List(2) {
            async(Dispatchers.Default) {
                start.await()
                results.send(authority.deliver(delivery))
            }
        }
        start.complete(Unit)
        try {
            assertEquals(ScheduleDeliveryResult.ALREADY_CLAIMED, withTimeout(5_000) { results.receive() })
        } finally {
            releaseEffect.complete(Unit)
        }
        assertEquals(ScheduleDeliveryResult.COMPLETED, withTimeout(5_000) { results.receive() })
        attempts.forEach { it.await() }
        assertEquals(listOf("refresh", "update"), fixture.effects)
    }

    @Test
    fun activationChecksExactDefinitionAndDeclaredActions() {
        val fixture = Fixture()
        val authority = fixture.authority()
        val publication = fixture.publication
        val actions = publication.definition.actions
        assertNull(authority.activate(publication.copy(definition = ScheduleDefinition("daily", actions))))
        assertNull(authority.activate(publication.copy(definition = ScheduleDefinition("hourly", actions.reversed()))))
        val changed = actions.toMutableList()
        changed[0] = changed[0].copy(declaration = "refresh:other-source")
        assertNull(authority.activate(publication.copy(definition = ScheduleDefinition("hourly", changed))))

        val mutableActions = actions.toMutableList()
        val review = publication.copy(definition = ScheduleDefinition("hourly", mutableActions))
        mutableActions.clear()
        val activation = checkNotNull(authority.activate(review))
        assertEquals(actions, activation.publication.definition.actions)
        assertEquals(activation, authority.activate(publication))
    }

    @Test
    fun definitionChangeAndReversionRequireNewActivation() = runBlocking {
        val fixture = Fixture()
        val authority = fixture.authority()
        val old = checkNotNull(authority.activate(fixture.publication))
        val oldDelivery = fixture.arm(old)

        fixture.store.current = fixture.publication.copy(
            revision = "changed",
            definition = ScheduleDefinition("daily", fixture.publication.definition.actions),
        )
        assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(oldDelivery))
        assertNull(authority.activate(fixture.publication))

        val restored = fixture.publication.copy(revision = "restored")
        fixture.store.current = restored
        assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(oldDelivery))
        val replacement = checkNotNull(authority.activate(restored))
        assertNotEquals(old.id, replacement.id)
        assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(oldDelivery))
        assertEquals(ScheduleDeliveryResult.COMPLETED, authority.deliver(fixture.arm(replacement)))
    }

    @Test
    fun deletionReplacementAndRevocationInvalidatePriorApproval() = runBlocking {
        val fixture = Fixture()
        val authority = fixture.authority()
        val old = fixture.arm(checkNotNull(authority.activate(fixture.publication)))
        val changed = listOf(
            null,
            fixture.publication.copy(ownerGeneration = "owner-2"),
            fixture.publication.copy(libraryGeneration = "library-2"),
            fixture.publication.copy(revision = "grant-restored"),
        )
        for (current in changed) {
            fixture.store.current = current
            assertNull(authority.activate(fixture.publication))
            assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(old))
        }
        assertEquals(emptyList<String>(), fixture.effects)
    }

    @Test
    fun hostDeliveryWindowRejectsUnarmedEarlyAndExpiredDeliveries() = runBlocking {
        val fixture = Fixture()
        val authority = fixture.authority()
        val activation = checkNotNull(authority.activate(fixture.publication))
        val delivery = fixture.arm(activation)
        assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(delivery.copy(occurrenceId = "unknown")))
        fixture.now = 99L
        assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(delivery))
        fixture.now = 100L
        assertEquals(ScheduleDeliveryResult.COMPLETED, authority.deliver(delivery))

        val next = fixture.arm(activation, "second")
        fixture.now = 200L
        assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(next))
        assertEquals(listOf("refresh", "update"), fixture.effects)
    }

    @Test
    fun revocationAfterClaimPreventsFirstAction() = runBlocking {
        val fixture = Fixture()
        val authority = fixture.authority()
        val delivery = fixture.arm(checkNotNull(authority.activate(fixture.publication)))
        fixture.store.onClaim = { fixture.store.current = null }

        assertEquals(ScheduleDeliveryResult.STOPPED, authority.deliver(delivery))
        assertEquals(emptyList<String>(), fixture.effects)
        assertEquals(setOf(delivery), fixture.store.claims)
    }

    @Test
    fun revocationDuringFirstActionStopsRemainingActions() = runBlocking {
        val fixture = Fixture()
        val authority = fixture.authority { _, action ->
            fixture.effects.add(action.id)
            fixture.store.current = null
            ScheduleActionResult.COMPLETED
        }
        val delivery = fixture.arm(checkNotNull(authority.activate(fixture.publication)))

        assertEquals(ScheduleDeliveryResult.STOPPED, authority.deliver(delivery))
        assertEquals(listOf("refresh"), fixture.effects)
        assertEquals(setOf(delivery), fixture.store.claims)
    }

    @Test
    fun cancelledBeforeClaimLeavesDeliveryAvailable() = runBlocking {
        val fixture = Fixture()
        val authority = fixture.authority()
        val delivery = fixture.arm(checkNotNull(authority.activate(fixture.publication)))
        launch {
            cancel()
            authority.deliver(delivery)
        }.join()

        assertEquals(emptySet<ScheduleDelivery>(), fixture.store.claims)
        assertEquals(ScheduleDeliveryResult.COMPLETED, authority.deliver(delivery))
    }

    @Test
    fun cancelledEffectCannotReplayAfterAuthorityRestart() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val authority = fixture.authority { _, action ->
            fixture.effects.add(action.id)
            entered.complete(Unit)
            awaitCancellation()
        }
        val delivery = fixture.arm(checkNotNull(authority.activate(fixture.publication)))
        val attempt = launch { authority.deliver(delivery) }
        try {
            withTimeout(5_000) { entered.await() }
        } finally {
            attempt.cancelAndJoin()
        }

        assertEquals(ScheduleDeliveryResult.ALREADY_CLAIMED, fixture.authority().deliver(delivery))
        assertEquals(listOf("refresh"), fixture.effects)
    }

    @Test
    fun unknownEffectCannotReplayAfterAuthorityRestart() = runBlocking {
        val fixture = Fixture()
        val authority = fixture.authority { _, action ->
            fixture.effects.add(action.id)
            error("Adapter lost the effect result")
        }
        val delivery = fixture.arm(checkNotNull(authority.activate(fixture.publication)))

        assertEquals(ScheduleDeliveryResult.OUTCOME_UNKNOWN, authority.deliver(delivery))
        assertEquals(ScheduleDeliveryResult.ALREADY_CLAIMED, fixture.authority().deliver(delivery))
        assertEquals(listOf("refresh"), fixture.effects)
    }

    @Test
    fun unacknowledgedClaimDoesNotRunAnyActionOrReplay() = runBlocking {
        val fixture = Fixture()
        val authority = fixture.authority()
        val delivery = fixture.arm(checkNotNull(authority.activate(fixture.publication)))
        fixture.store.onClaim = { error("Storage lost the commit result") }

        assertEquals("Storage lost the commit result", runCatching { authority.deliver(delivery) }.exceptionOrNull()?.message)
        assertEquals(ScheduleDeliveryResult.ALREADY_CLAIMED, fixture.authority().deliver(delivery))
        assertEquals(emptyList<String>(), fixture.effects)
    }

    private class Fixture {
        val publication = SchedulePublication(
            key = ScheduleKey(ScheduleOwnerKind.MINI_APP, "owner", "schedule"),
            ownerGeneration = "owner-1",
            libraryGeneration = "library-1",
            revision = "schedule-1",
            definition = ScheduleDefinition("hourly", listOf(
                ScheduleAction("refresh", ScheduleAction.Kind.SOURCE_REFRESH, "refresh:calendar"),
                ScheduleAction("update", ScheduleAction.Kind.MINI_APP_ACTION, "update:counter"),
            )),
        )
        val store = TestStorage(publication)
        val effects = mutableListOf<String>()
        var now = 150L

        fun authority(
            execute: suspend (ScheduleActivation, ScheduleAction) -> ScheduleActionResult = { _, action ->
                effects.add(action.id)
                ScheduleActionResult.COMPLETED
            },
        ) = ScheduleAuthority(store, { now }, ScheduleExecutor(execute))

        fun arm(activation: ScheduleActivation, occurrenceId: String = "first"): ScheduleDelivery {
            val delivery = ScheduleDelivery(activation.publication.key, activation.id, occurrenceId)
            store.windows[delivery] = ScheduleDeliveryWindow(100L, 200L)
            return delivery
        }
    }

    private class TestStorage(initial: SchedulePublication) : ScheduleStorage {
        var current: SchedulePublication? = initial
        val windows = mutableMapOf<ScheduleDelivery, ScheduleDeliveryWindow>()
        val claims = mutableSetOf<ScheduleDelivery>()
        var onClaim: (() -> Unit)? = null
        private var activation: ScheduleActivation? = null

        override fun <T> transaction(block: ScheduleStorage.Transaction.() -> T): T = synchronized(this) {
            var claimed = false
            val result = object : ScheduleStorage.Transaction {
                override fun eligiblePublication(key: ScheduleKey) = current?.takeIf { it.key == key }
                override fun activation(key: ScheduleKey) = activation?.takeIf { it.publication.key == key }
                override fun saveActivation(activation: ScheduleActivation) {
                    this@TestStorage.activation = activation
                }
                override fun deliveryWindow(delivery: ScheduleDelivery) = windows[delivery]
                override fun claim(delivery: ScheduleDelivery) = claims.add(delivery).also { claimed = it }
            }.block()
            if (claimed) onClaim?.invoke()
            result
        }
    }
}
