package com.alpha.assistant.voice

/** One user turn of the GPT-Live input transcript. Times are session milliseconds. */
internal data class UserTurn(val text: String, val startMs: Long, val endMs: Long, val overlappedPlayback: Boolean)

private data class Delta(val text: String, val startMs: Long, val endMs: Long)

/**
 * Input deltas at most this far apart are one speech run, and a run is current only when its last delta ends at most this far
 * before the hand-off (rulings R31 and R32). The same gap ends the run of a late last word. Gaps inside a turn were at most
 * 400 ms in the live checks. The session uses the same gap for an input piece that comes after the voice began its reply: in
 * the decisions probe of 2026-11-22 such a piece started 0 to 400 ms after the piece before it, the pieces of one phrase were
 * at most 600 ms apart, and a new phrase started at least 4,200 ms later.
 */
internal const val SPEECH_RUN_GAP_MS = 1_000L

/** The input and output stamps of GPT-Live sit on this grid. */
private const val STAMP_STEP_MS = 200L

/** The session transcript of spec 5.5.1 and 5.5.2: user turns and the request text of a hand-off. */
internal class LiveTranscript {
    private val inputs = mutableListOf<Delta>()
    private val outputEnds = mutableListOf<Long>()
    private var open: UserTurn? = null
    private var lastHandOffMs = -1L

    var outputEndMs: Long = 0
        private set

    fun input(delta: String, startMs: Long, endMs: Long, playbackActive: Boolean) {
        inputs += Delta(delta, startMs, endMs)
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
     * The trimmed input after the previous hand-off and after Alphy's latest spoken output, up to [offsetMs]. An output that ends
     * after the start of the current speech run talked over the user, so it does not cut the request (ruling R31). Without a
     * current run, every output end cuts (ruling R32). A blank result does not count as a hand-off, so the session can ask again
     * after it waits for late input deltas (spec 5.5.2).
     *
     * The late last word of an answered turn does not join the request either. A run that began before the answer, has no
     * hand-off, has no output end before its last input, and ends with one single input at most one stamp step after the stamp
     * end of the answer gave that input to the answered turn. A person who heard the answer starts later, because the answer
     * audio ends after its output stamp (form J: « системе» at the end of «Юпитер.»). Each sign makes the cut rarer, so the
     * worst result is the text of the rules above. Known limit: a new request loses its first input when that input starts
     * within one stamp step of the stamp end of an answer, within 1,000 ms of a turn that GPT-Live did not talk over, and
     * before a pause of more than 1,000 ms. A late word still joins the next request after two late inputs, or when its run
     * holds a hand-off.
     */
    fun request(offsetMs: Long): String {
        val heard = inputs.filter { it.startMs <= offsetMs }
        if (heard.isEmpty()) return ""
        fun joinsRun(i: Int) = i in 1..heard.lastIndex && heard[i].startMs - heard[i - 1].endMs <= SPEECH_RUN_GAP_MS
        val runStarts = LongArray(heard.size)
        for (i in heard.indices) runStarts[i] = if (joinsRun(i)) runStarts[i - 1] else heard[i].startMs
        val runStart = if (offsetMs - heard.last().endMs <= SPEECH_RUN_GAP_MS) runStarts.last() else offsetMs
        // An input that starts when an output ends comes after it: the stamps sit on a 200 ms grid, so the two often meet.
        val answered = outputEnds.filter { it <= runStart }.maxOrNull() ?: -1L
        fun lateLastWord(i: Int) = heard[i].startMs - answered <= STAMP_STEP_MS && runStarts[i] < answered &&
            !joinsRun(i + 1) && lastHandOffMs < runStarts[i] && heard[i - 1].startMs < answered &&
            outputEnds.none { it > runStarts[i] && it <= heard[i - 1].endMs }
        val text = heard.filterIndexed { i, it -> it.startMs > lastHandOffMs && it.startMs >= answered && !lateLastWord(i) }
            .joinToString("") { it.text }.trim()
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
