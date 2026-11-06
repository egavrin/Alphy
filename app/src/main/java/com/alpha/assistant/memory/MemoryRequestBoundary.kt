package com.alpha.assistant.memory

/** Host-assigned provenance for model output and other request inputs. */
internal enum class MemoryProvenance {
    CLEAN,
    MEMORY_DERIVED,
    UNKNOWN;

    /** Only clean model output is eligible for mini-app, lesson, and automation inputs. */
    val isEligibleForRestrictedRequest: Boolean get() = this == CLEAN

    fun merge(other: MemoryProvenance): MemoryProvenance = when {
        this == MEMORY_DERIVED || other == MEMORY_DERIVED -> MEMORY_DERIVED
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> CLEAN
    }
}

/** An opaque set of fact revisions for one active request. */
internal sealed interface MemoryRequestSelection

internal class MemoryRequestFacts(
    val texts: List<String>,
    val outputProvenance: MemoryProvenance,
)

internal class MemoryRequestInvalidatedException : IllegalStateException("A selected memory fact changed.")
