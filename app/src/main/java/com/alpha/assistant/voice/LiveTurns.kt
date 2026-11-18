package com.alpha.assistant.voice

/** One user turn of the GPT-Live input transcript. Times are session milliseconds. */
internal data class UserTurn(val text: String, val startMs: Long, val endMs: Long, val overlappedPlayback: Boolean)

private data class Delta(val text: String, val startMs: Long)

/** The session transcript of spec 5.5.1 and 5.5.2: user turns and the request text of a hand-off. */
internal class LiveTranscript {
    private val inputs = mutableListOf<Delta>()
    private val outputEnds = mutableListOf<Long>()
    private var open: UserTurn? = null
    private var lastHandOffMs = -1L

    var outputEndMs: Long = 0
        private set

    fun input(delta: String, startMs: Long, endMs: Long, playbackActive: Boolean) {
        inputs += Delta(delta, startMs)
        val turn = open
        open = if (turn == null) UserTurn(delta, startMs, endMs, playbackActive)
        else turn.copy(text = turn.text + delta, endMs = maxOf(turn.endMs, endMs))
    }

    fun output(delta: String, startMs: Long, endMs: Long): UserTurn? {
        outputEnds += endMs
        outputEndMs = maxOf(outputEndMs, endMs)
        return endTurn()
    }

    fun endTurn(): UserTurn? = open.also { open = null }

    /**
     * The trimmed input after Alphy's latest spoken output and the previous hand-off, up to [offsetMs]. A blank result does not
     * count as a hand-off, so the session can ask again after it waits for late input deltas (spec 5.5.2).
     */
    fun request(offsetMs: Long): String {
        val after = maxOf(lastHandOffMs, outputEnds.filter { it <= offsetMs }.maxOrNull() ?: -1L)
        val text = inputs.filter { it.startMs > after && it.startMs <= offsetMs }.joinToString("") { it.text }.trim()
        if (text.isNotEmpty()) lastHandOffMs = offsetMs
        return text
    }
}

internal enum class SpokenAnswer { APPROVE, REJECT, OTHER }

private val approvePhrases = setOf("да", "да запускай", "запускай", "выполняй", "подтверждаю", "давай", "yes", "go ahead")
private val rejectPhrases = setOf("нет", "не надо", "отмена", "отмени", "стоп", "no", "cancel")

/** The whole turn, without case and punctuation, must be one listed phrase (spec 5.5.10 rule 4). */
internal fun spokenAnswer(turn: String): SpokenAnswer {
    val phrase = turn.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    return when (phrase) {
        in approvePhrases -> SpokenAnswer.APPROVE
        in rejectPhrases -> SpokenAnswer.REJECT
        else -> SpokenAnswer.OTHER
    }
}

/** The approval window of one spoken plan revision (spec 5.5.10). Only the first counting turn is decided. */
internal class ApprovalWindow(val planId: String, val revision: Int, val opensAtMs: Long, val lengthMs: Long = 30_000) {
    private var decided = false

    fun decide(turn: UserTurn): SpokenAnswer? {
        if (decided || turn.startMs < opensAtMs || turn.startMs >= opensAtMs + lengthMs) return null
        // An overlapped turn may be Alphy's own echo, so it must not use up the window for the real answer.
        if (turn.overlappedPlayback) return null
        decided = true
        return spokenAnswer(turn.text)
    }
}
