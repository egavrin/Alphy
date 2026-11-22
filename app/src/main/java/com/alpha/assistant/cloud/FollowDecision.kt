package com.alpha.assistant.cloud

/**
 * What the stage does after a spoken turn that the voice answers alone (plan "one scene", decision 18).
 * [answered] is false when the voice only promised an answer, such as a waiting line (requirement C5).
 */
internal sealed interface FollowDecision {
    val answered: Boolean
    /** The kind of the decision, as the schema names it, for the trace. */
    val kind: String get() = when (this) {
        is None -> "none"
        is Screen -> "screen"
        is Mark -> "mark"
        is Press -> "press"
        is Clear -> "clear"
    }

    data class None(override val answered: Boolean = true) : FollowDecision
    /** [family] is the family of the screen, as in the routing decision (stage 5 live check, L1). */
    data class Screen(val brief: String, override val answered: Boolean = true, val family: String = "explain") : FollowDecision
    data class Mark(val target: String, override val answered: Boolean = true) : FollowDecision
    data class Press(val target: String, override val answered: Boolean = true) : FollowDecision
    data class Clear(override val answered: Boolean = true) : FollowDecision

    companion object {
        /** A mark or press target must be one exact label of [controls]. A screen needs a brief. Every other case is None. */
        fun decode(event: CloudEvent.Follow, controls: List<String>): FollowDecision = when (event.kind) {
            "screen" -> if (event.brief.isNotBlank()) Screen(event.brief, event.answered, event.family) else None(event.answered)
            "mark" -> if (event.target in controls) Mark(event.target, event.answered) else None(event.answered)
            "press" -> if (event.target in controls) Press(event.target, event.answered) else None(event.answered)
            "clear" -> Clear(event.answered)
            else -> None(event.answered)
        }
    }
}
