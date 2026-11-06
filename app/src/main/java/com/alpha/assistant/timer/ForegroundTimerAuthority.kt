package com.alpha.assistant.timer

import java.util.UUID

/** One instance per process. The host supplies a monotonic clock and a live eligibility check. */
internal class ForegroundTimerAuthority(
    private val policy: RatePolicy,
    private val elapsedMillis: () -> Long,
    private val isCurrent: (Target) -> Boolean,
) {
    class DriverGeneration(internal val eligible: () -> Boolean = { false })

    data class Target(
        val physicalLibrary: String,
        val owner: String,
        val revision: Int,
        val timerId: String,
        val driver: DriverGeneration,
    )

    data class RatePolicy(val windowMillis: Long, val perOwner: Int, val aggregate: Int) {
        init { require(windowMillis > 0 && perOwner > 0 && aggregate > 0) }
    }

    class Run
    enum class Decision { ADMITTED, RATE_SUPPRESSED }
    data class TerminalAlert(
        val runId: UUID,
        val target: Target,
        val alert: ForegroundTimerAlert,
        val decision: Decision,
    )
    enum class Result { STALE, EARLY, COMMIT_REJECTED, ADMISSION_COMMITTED, SUPPRESSION_COMMITTED }

    private data class Owner(val physicalLibrary: String, val id: String)
    private data class Slot(val owner: Owner, val timerId: String)
    private data class Armed(
        val handle: Run,
        val deadline: Long,
        val runId: UUID,
        val target: Target,
        val alert: ForegroundTimerAlert,
    )
    private data class Admission(val owner: Owner, val at: Long)

    private val armed = mutableMapOf<Slot, Armed>()
    private val admissions = ArrayDeque<Admission>()

    private fun Target.slot() = Slot(Owner(physicalLibrary, owner), timerId)

    /** Call only for a validated native action. A reset replaces the previous run for this timer. */
    @Synchronized
    fun arm(target: Target, deadline: Long, alert: ForegroundTimerAlert): Run? {
        if (!isCurrent(target) || deadline <= elapsedMillis()) return null
        val handle = Run()
        armed[target.slot()] = Armed(handle, deadline, UUID.randomUUID(), target, alert)
        return handle
    }

    /** Call only for a validated native action against this exact run. */
    @Synchronized
    fun cancel(target: Target, run: Run): Boolean {
        val slot = target.slot()
        val entry = armed[slot] ?: return false
        if (entry.handle !== run || entry.target != target || !isCurrent(target)) return false
        armed.remove(slot)
        return true
    }

    /** Call on driver loss, including any loss of eligibility. Use a new generation to resume. */
    @Synchronized
    fun invalidateDriver(driver: DriverGeneration) {
        armed.values.removeAll { it.target.driver === driver }
    }

    /**
     * Acquire the host transition lock before entering this authority. Acquire the store transaction last.
     * The live check must cover visibility, foreground, unlock, physical library, owner, revision, and driver.
     * For either decision, the callback must commit terminal state and the receipt in one bounded local transaction.
     * Return true only if that transaction succeeds.
     * Neither callback can suspend, perform external I/O, or re-enter this authority.
     *
     * Removing the run orders consumption against reset and revocation. A rejection or exception never restores it.
     * Only ADMISSION_COMMITTED can proceed to a one-use receipt claim.
     * The host must recheck eligibility immediately before each Android dispatch.
     * The host must never dispatch an uncertain or restored receipt.
     */
    @Synchronized
    fun commitTerminal(target: Target, run: Run, commit: (TerminalAlert) -> Boolean): Result {
        val slot = target.slot()
        val entry = armed[slot] ?: return Result.STALE
        if (entry.handle !== run || entry.target != target) return Result.STALE
        if (!isCurrent(target)) {
            armed.remove(slot)
            return Result.STALE
        }
        val now = elapsedMillis()
        if (now < entry.deadline) return Result.EARLY

        armed.remove(slot)
        while (admissions.firstOrNull()?.let { now - it.at >= policy.windowMillis } == true) {
            admissions.removeFirst()
        }
        val decision = if (admissions.size >= policy.aggregate || admissions.count { it.owner == slot.owner } >= policy.perOwner) {
            Decision.RATE_SUPPRESSED
        } else {
            admissions.addLast(Admission(slot.owner, now))
            Decision.ADMITTED
        }
        if (!commit(TerminalAlert(entry.runId, entry.target, entry.alert, decision))) return Result.COMMIT_REJECTED
        return when (decision) {
            Decision.ADMITTED -> Result.ADMISSION_COMMITTED
            Decision.RATE_SUPPRESSED -> Result.SUPPRESSION_COMMITTED
        }
    }
}
