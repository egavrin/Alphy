package com.alpha.assistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alpha.assistant.tools.SceneNarration
import com.alpha.assistant.voice.LiveProtocol
import com.alpha.assistant.voice.afterSentenceEnd
import com.alpha.assistant.voice.coversSentence
import com.alpha.assistant.voice.startsSentence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The cause that stops a walk: the person speaks, taps the scene, gets a new answer, or the mode ends (plan "one scene", decision 14). */
internal enum class WalkStop { SPEECH, TAP, ANSWER, END }

/**
 * The walk of a stepped scene with the voice (plan "one scene", decisions 14 to 16). The bridge owns it and calls it on the main thread.
 * The narrator sends the sentence of step K, arms at the first transcript piece that starts that sentence, and at the sync fire presses step
 * K and sends the sentence of step K+1, so at most one sentence waits. A piece that does not go on with the armed sentence before its end
 * disarms it, so the opener, a wait line, or the own words of the voice move no step, and a repeated sentence moves no second step
 * (ruling 2 of stage 2). A stop calls [skip] for the waiting sentence. [skipAfterSpeech] is true for branch (b) of Task 24: a stop by speech
 * skips too (ruling 1 of stage 2). [scope] runs the presses on the main thread.
 */
internal class StageNarrator(
    private val send: (String) -> Unit,
    private val press: suspend (step: String) -> Boolean,
    private val cardText: () -> String?,
    private val onsetAfter: (Long, () -> Unit) -> Unit,
    private val cancelOnset: () -> Unit,
    private val skip: (String) -> Unit,
    private val skipAfterSpeech: Boolean,
    private val scope: CoroutineScope,
) {
    var walking by mutableStateOf(false); private set
    /** The scene of the last walk. */
    var scene: String? = null; private set
    /** The step of the last press of the walk, from 1, or 0 before the first press. */
    var step = 0; private set
    private var steps: List<String> = emptyList()
    private var narration = SceneNarration(false, emptyMap(), null)
    /** The step whose sentence went to the voice and waits to be heard, its text, and whether its step is already pressed. */
    private var waiting: Int? = null
    private var waitingText: String? = null
    private var waitingPressed = false
    /** The transcript started the waiting sentence, so its sync fire is pending, and [confirmed] once the whole sentence came. */
    private var armed = false
    private var confirmed = false
    /** The transcript of the voice since the end of its last sentence, or since the last reply boundary when no sentence is armed. */
    private var heard = ""
    /** A reply boundary came inside the armed sentence since the last piece ([boundary]). */
    private var crossed = false
    /** The step with no sentence that the narrator presses at the next reply end, and then says its card text (decision 14). */
    private var cardStep: Int? = null
    /** The press of the walk that runs now. A stop cancels it, so a press that the stage has not run yet never runs (fix list item 1). */
    private var pressJob: Job? = null

    /** Starts the walk of [toolId] from its first step: the sentence of step 1 goes to the voice at once and waits behind the opener. */
    fun start(toolId: String, steps: List<String>, narration: SceneNarration) {
        walk(toolId, steps, narration, 1)
        sendStep(1, send = true)
    }

    /**
     * The walk of the rest of the steps of [toolId] after [present] («Продолжай», decision 16). Gives the sentence of the next step for the
     * caller to say, and the narrator arms on it as on its own. Null when no step is left, or when that step has no sentence: then the
     * narrator presses it and says its card text.
     */
    fun rest(toolId: String, steps: List<String>, narration: SceneNarration, present: Int): String? {
        if (present >= steps.size) return null
        walk(toolId, steps, narration, present + 1)
        sendStep(present + 1, send = false)
        return waitingText
    }

    private fun walk(toolId: String, steps: List<String>, narration: SceneNarration, from: Int) {
        stop(WalkStop.ANSWER)
        scene = toolId; this.steps = steps; this.narration = narration
        walking = true; heard = ""; crossed = false; cardStep = null; step = from - 1
    }

    /** Sends the sentence of [k]. A step with no sentence is pressed at once when nothing is said before it, else at the next reply end. */
    private fun sendStep(k: Int, send: Boolean) {
        val sentence = narration.sentence(k)
        when {
            sentence != null -> { wait(k, sentence, pressed = false); if (send) send(LiveProtocol.MARK + sentence) }
            k == 1 || !send -> pressCard(k)
            else -> cardStep = k
        }
    }

    private fun wait(k: Int, sentence: String, pressed: Boolean) {
        waiting = k; waitingText = sentence; waitingPressed = pressed; armed = false; confirmed = false
    }

    /** Presses step [k] with no sentence, then sends its card text, which exists only after the press. */
    private fun pressCard(k: Int) {
        val walkOf = scene
        pressJob = scope.launch {
            if (!walking || scene != walkOf || !press(steps[k - 1])) return@launch
            step = k
            val card = cardText()?.takeIf { it.isNotBlank() }
            if (!walking || scene != walkOf) return@launch
            if (card == null) { if (k < steps.size) sendStep(k + 1, send = true) else walking = false; return@launch }
            wait(k, card, pressed = true)
            send(LiveProtocol.MARK + card)
        }
    }

    /** Each output transcript piece of the voice (decision 14). A reply start is not necessary: a sentence can come inside the reply before it. */
    fun output(text: String, replyStart: Boolean, arrivedNanos: Long) {
        if (!walking) return
        if (replyStart) boundary()
        val across = crossed
        crossed = false
        var said = heard + text
        heard = afterSentenceEnd(said)
        val sentence = waitingText ?: return
        val k = checkNotNull(waiting)
        if (armed) {
            if (confirmed) return
            if (startsSentence(said, sentence)) { confirmed = coversSentence(said, sentence); return }
            // The voice said other words before the end of the sentence: no step moves on them.
            armed = false
            cancelOnset()
            // After a reply boundary, this piece starts anew, so the words before the boundary block no later arm.
            if (across) { said = text; heard = afterSentenceEnd(text) }
        }
        // The start of a sentence after an end inside this piece, or the whole sentence when this piece ends it.
        val start = heard.ifBlank { said }
        if (startsSentence(start, sentence)) {
            armed = true
            confirmed = coversSentence(start, sentence)
            onsetAfter(arrivedNanos) { fire(k) }
        }
    }

    /** The sync fire of step [k]: presses it and sends the sentence of the next step. */
    private fun fire(k: Int) {
        if (!walking || waiting != k || !armed) return
        val pressed = waitingPressed
        waiting = null; waitingText = null; armed = false
        val walkOf = scene
        pressJob = scope.launch {
            if (!pressed && (!walking || scene != walkOf || !press(steps[k - 1]))) return@launch
            step = k
            if (!walking || scene != walkOf) return@launch
            if (k < steps.size) sendStep(k + 1, send = true) else walking = false
        }
    }

    /** The end of a reply: a step with no sentence is pressed now, and its card text goes to the voice (decision 14). */
    fun replyEnded() {
        boundary()
        if (!walking) return
        val k = cardStep ?: return
        cardStep = null
        pressCard(k)
    }

    /**
     * A reply end or a reply start. The transcript runs ahead of the audio, and a reply ends by its audio, so a boundary can fall inside a
     * sentence (live record live-2-uxHeart): an armed sentence keeps its words, and other words before the boundary go.
     */
    private fun boundary() { if (armed && !confirmed) crossed = true else heard = "" }

    /**
     * Stops the walk (decision 15). The sentence that plays ends. The waiting sentence gets [skip], for speech only with [skipAfterSpeech].
     * It never starts again by itself.
     */
    fun stop(reason: WalkStop) {
        if (!walking) return
        walking = false
        cancelOnset()
        pressJob?.cancel(); pressJob = null
        val sentence = waitingText.takeIf { !armed }
        waiting = null; waitingText = null; armed = false; confirmed = false; cardStep = null
        if (sentence != null && (reason != WalkStop.SPEECH || skipAfterSpeech)) skip(sentence)
    }
}
