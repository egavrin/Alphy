package com.alpha.assistant.timer

import com.alpha.assistant.timer.ForegroundTimerAuthority.Decision
import com.alpha.assistant.timer.ForegroundTimerAuthority.DriverGeneration
import com.alpha.assistant.timer.ForegroundTimerAuthority.RatePolicy
import com.alpha.assistant.timer.ForegroundTimerAuthority.Result
import com.alpha.assistant.timer.ForegroundTimerAuthority.Target
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTimerAuthorityTest {
    private val target = Target("primary.db", "tool-a", 1, "clock", DriverGeneration())
    private val alert = ForegroundTimerAlert(25, ForegroundTimerAlert.Notification("Timer", "Done"))
    private var now = 100L
    private val current = mutableSetOf(target)

    private fun authority(perOwner: Int = 2, aggregate: Int = 3) =
        ForegroundTimerAuthority(RatePolicy(50, perOwner, aggregate), { now }, { it in current })

    @Test fun onlyTheDueTerminalCommitsTheFrozenCombinedAlertOnce() {
        val authority = authority()
        val run = checkNotNull(authority.arm(target, 110, alert))
        assertEquals(Result.EARLY, authority.commitTerminal(target, run) { error("Early commit") })

        now = 110
        assertEquals(Result.ADMISSION_COMMITTED, authority.commitTerminal(target, run) { terminal ->
            assertEquals(Decision.ADMITTED, terminal.decision)
            assertEquals(target, terminal.target)
            assertSame(alert, terminal.alert)
            assertEquals(25L, terminal.alert.vibrationMillis)
            assertEquals("Done", terminal.alert.notification?.text)
            true
        })
        assertEquals(Result.STALE, authority.commitTerminal(target, run) { error("Repeated commit") })
    }

    @Test fun resetReplacesTheRunAndCancellationNeverAppliesToAReplacement() {
        val authority = authority()
        val first = checkNotNull(authority.arm(target, 110, alert))
        val replacement = checkNotNull(authority.arm(target, 120, alert))
        now = 110
        assertFalse(authority.cancel(target, first))
        assertEquals(Result.STALE, authority.commitTerminal(target, first) { error("Reset run committed") })
        assertEquals(Result.EARLY, authority.commitTerminal(target, replacement) { error("Early replacement") })
        assertTrue(authority.cancel(target, replacement))
        now = 120
        assertEquals(Result.STALE, authority.commitTerminal(target, replacement) { error("Canceled run committed") })
    }

    @Test fun twoHostsCannotCommitOrCancelEachOthersRun() {
        val authority = authority()
        val second = target.copy(driver = DriverGeneration())
        val run = checkNotNull(authority.arm(target, 110, alert))
        assertNull(authority.arm(second, 110, alert))
        current.clear()
        current.add(second)
        val secondRun = checkNotNull(authority.arm(second, 110, alert))
        authority.invalidateDriver(target.driver)
        now = 110
        assertFalse(authority.cancel(target, secondRun))
        assertEquals(Result.STALE, authority.commitTerminal(second, run) { error("Old generation") })
        assertEquals(Result.STALE, authority.commitTerminal(target, secondRun) { error("Other host's run") })
        assertEquals(Result.ADMISSION_COMMITTED, authority.commitTerminal(second, secondRun) { true })
    }

    @Test fun ineligibleTargetAndMissedForegroundCannotCatchUp() {
        val authority = authority()
        val replacements = listOf(null, target.copy(revision = 2), target.copy(physicalLibrary = "new.db"))
        for (replacement in replacements) {
            val run = checkNotNull(authority.arm(target, now + 1, alert))
            now++
            current.clear()
            replacement?.let(current::add)
            assertEquals(Result.STALE, authority.commitTerminal(target, run) { error("Ineligible target") })
            current.clear()
            current.add(target)
            assertEquals(Result.STALE, authority.commitTerminal(target, run) { error("Revived run") })
        }

        val hiddenRun = checkNotNull(authority.arm(target, now + 1, alert))
        authority.invalidateDriver(target.driver)
        now++
        val resumed = target.copy(driver = DriverGeneration())
        current.clear()
        current.add(resumed)
        assertEquals(Result.STALE, authority.commitTerminal(resumed, hiddenRun) { error("Foreground catch-up") })
        assertEquals(Result.STALE, authority.commitTerminal(target, hiddenRun) { error("Invalidated driver") })
    }

    @Test fun ratesApplyToBothOwnerAndAggregateAndNeverDefer() {
        val authority = authority(perOwner = 1, aggregate = 2)
        val otherTimer = target.copy(timerId = "other", revision = 2)
        val otherOwner = target.copy(owner = "tool-b")
        val otherLibrary = target.copy(physicalLibrary = "other.db")
        current.addAll(listOf(otherTimer, otherOwner, otherLibrary))

        fun terminal(target: Target, decision: Decision): ForegroundTimerAuthority.Run {
            val run = checkNotNull(authority.arm(target, now + 1, alert))
            now++
            val expected = when (decision) {
                Decision.ADMITTED -> Result.ADMISSION_COMMITTED
                Decision.RATE_SUPPRESSED -> Result.SUPPRESSION_COMMITTED
            }
            var attempts = 0
            assertEquals(expected, authority.commitTerminal(target, run) { terminal ->
                attempts++
                assertEquals(decision, terminal.decision)
                assertEquals(target, terminal.target)
                assertEquals(25L, terminal.alert.vibrationMillis)
                assertEquals("Done", terminal.alert.notification?.text)
                true
            })
            assertEquals(1, attempts)
            return run
        }

        terminal(target, Decision.ADMITTED)
        val limited = terminal(otherTimer, Decision.RATE_SUPPRESSED)
        terminal(otherOwner, Decision.ADMITTED)
        terminal(otherLibrary, Decision.RATE_SUPPRESSED)
        now = 150
        assertEquals(Result.STALE, authority.commitTerminal(otherTimer, limited) { error("Deferred retry") })
        terminal(otherTimer, Decision.ADMITTED)
        now = 152
        terminal(otherLibrary, Decision.ADMITTED)
    }

    @Test fun firstCommitWinsAgainstCancellationAndACompetingAdmission() {
        val authority = authority(perOwner = 1, aggregate = 1)
        val second = target.copy(owner = "tool-b", driver = DriverGeneration())
        current.add(second)
        val run = checkNotNull(authority.arm(target, 101, alert))
        val competingRun = checkNotNull(authority.arm(second, 101, alert))
        now = 101
        val inCommit = CountDownLatch(1)
        val contenders = CountDownLatch(2)
        val finishCommit = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val terminal = executor.submit(Callable {
                authority.commitTerminal(target, run) {
                    inCommit.countDown()
                    check(finishCommit.await(5, SECONDS))
                    true
                }
            })
            assertTrue(inCommit.await(5, SECONDS))
            val cancel = executor.submit(Callable {
                contenders.countDown()
                authority.cancel(target, run)
            })
            val competing = executor.submit(Callable {
                contenders.countDown()
                authority.commitTerminal(second, competingRun) {
                    assertEquals(Decision.RATE_SUPPRESSED, it.decision)
                    true
                }
            })
            assertTrue(contenders.await(5, SECONDS))
            finishCommit.countDown()
            assertEquals(Result.ADMISSION_COMMITTED, terminal.get(5, SECONDS))
            assertFalse(cancel.get(5, SECONDS))
            assertEquals(Result.SUPPRESSION_COMMITTED, competing.get(5, SECONDS))
        } finally {
            finishCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun rejectedCommitAndUncertainCancellationDoNotRearmOrRestoreOnRestart() {
        val authority = authority()
        val rejected = checkNotNull(authority.arm(target, 101, alert))
        now = 101
        assertEquals(Result.COMMIT_REJECTED, authority.commitTerminal(target, rejected) { false })
        assertEquals(Result.STALE, authority.commitTerminal(target, rejected) { error("Rejected commit retried") })
        val uncertain = checkNotNull(authority.arm(target, 102, alert))
        now = 102
        assertThrows(CancellationException::class.java) {
            authority.commitTerminal(target, uncertain) { throw CancellationException() }
        }
        assertEquals(Result.STALE, authority.commitTerminal(target, uncertain) { error("Uncertain commit retried") })
        val armed = checkNotNull(authority.arm(target, 103, alert))
        now = 103
        assertEquals(Result.SUPPRESSION_COMMITTED, authority.commitTerminal(target, armed) {
            assertEquals(Decision.RATE_SUPPRESSED, it.decision)
            true
        })
        val restartedRun = checkNotNull(authority.arm(target, 104, alert))
        now = 104
        assertEquals(Result.STALE, authority().commitTerminal(target, restartedRun) { error("Restored armed run") })
    }

    @Test fun suppressedCommitRollbackAndCancellationConsumeTheRunWithoutSpendingRateCapacity() {
        val authority = authority(perOwner = 1, aggregate = 1)
        val admitted = checkNotNull(authority.arm(target, 101, alert))
        now = 101
        assertEquals(Result.ADMISSION_COMMITTED, authority.commitTerminal(target, admitted) { true })

        val rejected = checkNotNull(authority.arm(target, 110, alert))
        now = 110
        assertEquals(Result.COMMIT_REJECTED, authority.commitTerminal(target, rejected) {
            assertEquals(Decision.RATE_SUPPRESSED, it.decision)
            false
        })
        assertEquals(Result.STALE, authority.commitTerminal(target, rejected) { error("Retried rollback") })

        val interrupted = checkNotNull(authority.arm(target, 120, alert))
        now = 120
        assertThrows(CancellationException::class.java) {
            authority.commitTerminal(target, interrupted) {
                assertEquals(Decision.RATE_SUPPRESSED, it.decision)
                throw CancellationException()
            }
        }
        assertFalse(authority.cancel(target, interrupted))
        assertEquals(Result.STALE, authority.commitTerminal(target, interrupted) { error("Retried cancellation") })

        val next = checkNotNull(authority.arm(target, 151, alert))
        now = 151
        assertEquals(Result.ADMISSION_COMMITTED, authority.commitTerminal(target, next) {
            assertEquals(Decision.ADMITTED, it.decision)
            true
        })
        assertEquals(Result.STALE, authority.commitTerminal(target, rejected) { error("Retried after rate window") })
    }

    @Test fun alertUsesTheCatalogBoundsAndCanRequestEitherOperationAlone() {
        val tone = ForegroundTimerAlert(null, null, 100)
        assertEquals(100, tone.toneMillis)
        val spec = ForegroundTimerSpec("timer", 1000, tone)
        assertEquals("audio.tone", spec.requests().single().capability)
        assertThrows(IllegalArgumentException::class.java) { ForegroundTimerAlert(null, null, 1001) }
        assertEquals(1L, ForegroundTimerAlert(1, null).vibrationMillis)
        val notification = ForegroundTimerAlert.Notification("Timer", "Done")
        assertEquals(notification, ForegroundTimerAlert(null, notification).notification)
        assertThrows(IllegalArgumentException::class.java) { ForegroundTimerAlert(null, null) }
        assertThrows(IllegalArgumentException::class.java) { ForegroundTimerAlert(2_001, null) }
        assertThrows(IllegalArgumentException::class.java) { ForegroundTimerAlert(null, notification.copy(title = " ")) }
        assertThrows(IllegalArgumentException::class.java) { ForegroundTimerAlert(null, notification.copy(text = "x".repeat(1_001))) }
    }
}
