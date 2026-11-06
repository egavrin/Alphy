package com.alpha.assistant.scheduling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Collections
import java.util.UUID

internal enum class ScheduleOwnerKind { MINI_APP, WIDGET }

internal data class ScheduleKey(val ownerKind: ScheduleOwnerKind, val ownerId: String, val scheduleId: String) {
    init {
        require(ownerId.isNotBlank() && scheduleId.isNotBlank())
    }
}

internal data class ScheduleAction(val id: String, val kind: Kind, val declaration: String) {
    enum class Kind { SOURCE_REFRESH, MINI_APP_ACTION }

    init {
        require(id.isNotBlank() && declaration.isNotBlank())
    }
}

/** The host supplies the exact definition and its ordered native action declarations. */
internal class ScheduleDefinition(val exact: String, actions: List<ScheduleAction>) {
    val actions: List<ScheduleAction> = Collections.unmodifiableList(ArrayList(actions))

    init {
        require(exact.isNotBlank() && this.actions.isNotEmpty())
        require(this.actions.distinctBy { it.id }.size == this.actions.size)
    }

    override fun equals(other: Any?): Boolean =
        other is ScheduleDefinition && exact == other.exact && actions == other.actions

    override fun hashCode(): Int = 31 * exact.hashCode() + actions.hashCode()
}

internal data class SchedulePublication(
    val key: ScheduleKey,
    val ownerGeneration: String,
    val libraryGeneration: String,
    val revision: String,
    val definition: ScheduleDefinition,
) {
    init {
        require(ownerGeneration.isNotBlank() && libraryGeneration.isNotBlank() && revision.isNotBlank())
    }
}

internal data class ScheduleActivation(val id: String, val publication: SchedulePublication)

internal data class ScheduleDelivery(val key: ScheduleKey, val activationId: String, val occurrenceId: String) {
    init {
        require(activationId.isNotBlank() && occurrenceId.isNotBlank())
    }
}

/** The host chooses a window in epoch milliseconds. Its end is exclusive. */
internal data class ScheduleDeliveryWindow(val startsAt: Long, val expiresAt: Long) {
    init {
        require(startsAt < expiresAt)
    }

    operator fun contains(now: Long): Boolean = now >= startsAt && now < expiresAt
}

/**
 * Transactions serialize across all authority instances and commit durably before returning success.
 * Owner, library, grant, and delivery changes must use the same serialization boundary.
 * The transaction must use the current host state and keep external effects outside that boundary.
 */
internal interface ScheduleStorage {
    fun <T> transaction(block: Transaction.() -> T): T

    interface Transaction {
        /**
         * Return only a live, permitted publication with host-validated action declarations.
         * Never reuse a generation or revision after a replacement, definition change, or grant revocation.
         */
        fun eligiblePublication(key: ScheduleKey): SchedulePublication?

        fun activation(key: ScheduleKey): ScheduleActivation?
        fun saveActivation(activation: ScheduleActivation)

        /** Return the host's current armed window. Use one stable delivery ID per occurrence and activation. */
        fun deliveryWindow(delivery: ScheduleDelivery): ScheduleDeliveryWindow?

        /** Insert a unique claim. Retain the claim until the delivery is permanently ineligible. */
        fun claim(delivery: ScheduleDelivery): Boolean
    }
}

internal enum class ScheduleActionResult { COMPLETED, STOPPED, OUTCOME_UNKNOWN }
internal enum class ScheduleDeliveryResult { COMPLETED, REJECTED, ALREADY_CLAIMED, STOPPED, OUTCOME_UNKNOWN }

internal fun interface ScheduleExecutor {
    /** Use the existing host operation for the exact approved target. Do not evaluate generated code or retry uncertain effects. */
    suspend fun execute(activation: ScheduleActivation, action: ScheduleAction): ScheduleActionResult
}

internal class ScheduleAuthority(
    private val storage: ScheduleStorage,
    private val clock: () -> Long,
    private val executor: ScheduleExecutor,
) {
    /** Call after explicit user approval of this exact host publication. */
    fun activate(reviewed: SchedulePublication): ScheduleActivation? = storage.transaction {
        if (eligiblePublication(reviewed.key) != reviewed) return@transaction null
        activation(reviewed.key)?.takeIf { it.publication == reviewed }
            ?: ScheduleActivation(UUID.randomUUID().toString(), reviewed).also(::saveActivation)
    }

    /** A committed claim consumes the entire occurrence, including after interruption. */
    suspend fun deliver(delivery: ScheduleDelivery): ScheduleDeliveryResult {
        val context = currentCoroutineContext()
        context.ensureActive()
        val claim = storage.transaction {
            val active = eligibleActivation(delivery) ?: return@transaction null
            context.ensureActive()
            Claim(active, claim(delivery))
        } ?: return ScheduleDeliveryResult.REJECTED
        if (!claim.inserted) return ScheduleDeliveryResult.ALREADY_CLAIMED

        val activation = claim.activation
        for (action in activation.publication.definition.actions) {
            // A lifecycle change can prevent admission of the next action while an earlier action runs.
            val eligible = storage.transaction { eligibleActivation(delivery) == activation }
            context.ensureActive()
            if (!eligible) return ScheduleDeliveryResult.STOPPED
            val result = try {
                executor.execute(activation, action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ScheduleActionResult.OUTCOME_UNKNOWN
            }
            context.ensureActive()
            when (result) {
                ScheduleActionResult.COMPLETED -> Unit
                ScheduleActionResult.STOPPED -> return ScheduleDeliveryResult.STOPPED
                ScheduleActionResult.OUTCOME_UNKNOWN -> return ScheduleDeliveryResult.OUTCOME_UNKNOWN
            }
        }
        return ScheduleDeliveryResult.COMPLETED
    }

    private fun ScheduleStorage.Transaction.eligibleActivation(delivery: ScheduleDelivery): ScheduleActivation? {
        val active = activation(delivery.key) ?: return null
        if (active.id != delivery.activationId || eligiblePublication(delivery.key) != active.publication) return null
        val window = deliveryWindow(delivery) ?: return null
        return active.takeIf { clock() in window }
    }

    private data class Claim(val activation: ScheduleActivation, val inserted: Boolean)
}
