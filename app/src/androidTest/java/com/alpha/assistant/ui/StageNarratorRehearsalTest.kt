package com.alpha.assistant.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.SceneNarration
import com.alpha.assistant.voice.LiveProtocol.MARK
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The narrator of plan "one scene" (Tasks 31 and 32, decisions 14 to 16, rulings 1 and 2 of stage 2) with scripted parts: the transcript
 * pieces of the voice, a sync rule that fires when the test says, and presses that run at once. The session test pins the sync rule, and the
 * tap test pins the bridge on a real stage.
 */
class StageNarratorRehearsalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cancel() = scope.cancel()

    /** The narrator with scripted parts: each call goes to a list, and [fire] runs the pending sync fire. */
    private inner class Rig(private val narration: SceneNarration = NARRATION, skipAfterSpeech: Boolean = true,
        private val pressGate: CompletableDeferred<Boolean>? = null) {
        val sent = mutableListOf<String>()
        val pressed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var arms = 0
        var cancels = 0
        /** The presses that a cancel stopped while they waited for [pressGate]. */
        var pressCancels = 0
        private var pending: (() -> Unit)? = null
        val narrator = StageNarrator(send = { sent += it }, press = { step ->
                pressed += step
                try { pressGate?.await() ?: true } catch (cancel: CancellationException) { pressCancels++; throw cancel }
            },
            cardText = { CARDS.getOrNull(STEPS.indexOf(pressed.lastOrNull())) },
            onsetAfter = { _, fire -> arms++; pending = fire }, cancelOnset = { cancels++; pending = null }, skip = { skipped += it },
            skipAfterSpeech = skipAfterSpeech, scope = scope)

        fun start() = main { narrator.start(TOOL, STEPS, narration) }
        /** The voice says [text] as one piece. */
        fun say(text: String, replyStart: Boolean = false) = main { narrator.output(text, replyStart, 0L) }
        /** The speaker plays the start of the armed sentence. */
        fun fire() = main { checkNotNull(pending) { "no sentence is armed" }.invoke(); pending = null }
        val armed get() = main { pending != null }
        fun replyEnded() = main { narrator.replyEnded() }
        fun stop(reason: WalkStop) = main { narrator.stop(reason) }
        /** The voice says each sentence that the narrator sent, and its start plays, until the walk ends. */
        fun walkAll() {
            var spoken = 0
            while (main { narrator.walking } && spoken < sent.size) {
                say(sent[spoken].removePrefix(MARK), replyStart = true)
                fire()
                spoken++
            }
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    @Test fun theWalkGoesFromTheFirstStepToTheLast() {
        val rig = Rig()
        rig.start()
        // Sentence 1 waits behind the opener. No step moves while the opener plays.
        assertEquals(listOf(MARK + SAYS[0]), rig.sent)
        rig.say("Корабль держится на воде, потому что он полый.", replyStart = true)
        assertEquals(0, rig.arms)
        for (k in 1..3) {
            rig.say(SAYS[k - 1], replyStart = true)
            // At most one sentence waits: the next one goes only at the fire of this one.
            assertEquals(k, rig.sent.size)
            rig.fire()
            assertEquals(STEPS.take(k), rig.pressed)
        }
        assertEquals(SAYS.map { MARK + it }, rig.sent)
        assertFalse(main { rig.narrator.walking })
    }

    @Test fun aSentenceInsideTheSameReplyStillMovesTheStage() {
        val rig = Rig()
        rig.start()
        rig.say(SAYS[0], replyStart = true)
        rig.fire()
        // Sentence 2 comes in the reply of sentence 1, after its end, in one piece with no reply start.
        rig.say(" Гвоздь", replyStart = false)
        assertTrue(rig.armed)
        rig.fire()
        assertEquals(STEPS.take(2), rig.pressed)
    }

    /** Ruling 2 of stage 2: the own words of the voice after a marked text move no step, and only the text of the sentence arms it. */
    @Test fun ownWordsMoveNoStep() {
        val rig = Rig()
        rig.start()
        rig.say(SAYS[0], replyStart = true)
        rig.fire()
        rig.say(" Интересно, правда?")
        rig.say(" Давай посмотрим дальше.")
        assertFalse(rig.armed)
        assertEquals(STEPS.take(1), rig.pressed)
        rig.say(" " + SAYS[1])
        assertTrue(rig.armed)
    }

    /** Ruling 2 of stage 2: words that start like the sentence and then go another way disarm it before its fire. */
    @Test fun aDifferentSentenceWithTheSameStartMovesNoStep() {
        val rig = Rig()
        rig.start()
        rig.say("Корабль ", replyStart = true)
        assertTrue(rig.armed)
        rig.say("плывёт быстро.")
        assertFalse(rig.armed)
        assertEquals(1, rig.cancels)
        assertTrue(rig.pressed.isEmpty())
    }

    /** Ruling 2 of stage 2: the voice says a sentence twice. The step moves once, and the repeat moves no later step. */
    @Test fun aRepeatedSentenceMovesNoSecondStep() {
        val rig = Rig()
        rig.start()
        rig.say(SAYS[0], replyStart = true)
        rig.say(" " + SAYS[0])
        assertEquals(1, rig.arms)
        rig.fire()
        rig.say(SAYS[0], replyStart = true)
        assertFalse(rig.armed)
        assertEquals(STEPS.take(1), rig.pressed)
    }

    @Test fun aNarratorPressDoesNotStopTheWalk() {
        val rig = Rig()
        rig.start()
        rig.walkAll()
        assertEquals(STEPS, rig.pressed)
        assertEquals(3, main { rig.narrator.step })
    }

    /** Ruling 1 of stage 2 (branch b): speech during sentence 2 skips the waiting sentence 3, and no step moves after it. */
    @Test fun speechStopsTheWalk() {
        for (skipAfterSpeech in listOf(true, false)) {
            val rig = Rig(skipAfterSpeech = skipAfterSpeech)
            rig.start()
            rig.say(SAYS[0], replyStart = true); rig.fire()
            rig.say(SAYS[1], replyStart = true); rig.fire()
            rig.stop(WalkStop.SPEECH)
            assertEquals(if (skipAfterSpeech) listOf(SAYS[2]) else emptyList(), rig.skipped)
            rig.say(SAYS[2], replyStart = true)
            assertFalse(rig.armed)
            assertEquals(STEPS.take(2), rig.pressed)
        }
    }

    @Test fun aTapStopsTheWalk() {
        val rig = Rig(skipAfterSpeech = false)
        rig.start()
        rig.say(SAYS[0], replyStart = true); rig.fire()
        rig.say(SAYS[1], replyStart = true); rig.fire()
        rig.stop(WalkStop.TAP)
        assertEquals(listOf(SAYS[2]), rig.skipped)
        assertEquals(STEPS.take(2), rig.pressed)
    }

    /** Review focus 5: after a stop, the reply of the next sentence still comes, and a late fire of an armed sentence moves no step. */
    @Test fun aStoppedWalkMovesNoStep() {
        val rig = Rig()
        rig.start()
        rig.say(SAYS[0], replyStart = true)
        val armed = rig.arms
        rig.stop(WalkStop.TAP)
        // The armed sentence plays to its end, so it gets no skip, and its sync fire is canceled.
        assertTrue(rig.skipped.isEmpty())
        assertEquals(1, rig.cancels)
        rig.say(SAYS[1], replyStart = true)
        assertEquals(armed, rig.arms)
        assertTrue(rig.pressed.isEmpty())
    }

    /**
     * Fix list item 1: speech stops the walk while the press of step 2 still runs. The stop cancels that press, so it gives no step and no
     * next sentence when it would have ended, and the stage never runs a press of the walk after the stop.
     */
    @Test fun aStopCancelsAPressThatRuns() {
        val gate = CompletableDeferred<Boolean>()
        val rig = Rig(pressGate = gate)
        rig.start()
        rig.say(SAYS[0], replyStart = true); rig.fire()
        assertEquals(STEPS.take(1), rig.pressed)
        rig.stop(WalkStop.SPEECH)
        assertEquals(1, rig.pressCancels)
        main { gate.complete(true) }
        assertEquals(0, main { rig.narrator.step })
        assertEquals(listOf(MARK + SAYS[0]), rig.sent)
    }

    @Test fun aNewAnswerStopsTheWalk() {
        val rig = Rig()
        rig.start()
        rig.say(SAYS[0], replyStart = true); rig.fire()
        rig.stop(WalkStop.ANSWER)
        assertEquals(listOf(SAYS[1]), rig.skipped)
        assertFalse(main { rig.narrator.walking })
    }

    /** With no say2, step 2 is pressed at the reply end of sentence 1, and its card text goes to the voice. */
    @Test fun aMissingSentenceUsesTheCardText() {
        val rig = Rig(SceneNarration(true, mapOf(1 to SAYS[0], 3 to SAYS[2]), null))
        rig.start()
        rig.say(SAYS[0], replyStart = true); rig.fire()
        assertEquals(listOf(MARK + SAYS[0]), rig.sent)
        rig.replyEnded()
        assertEquals(STEPS.take(2), rig.pressed)
        assertEquals(MARK + CARDS[1], rig.sent.last())
        rig.say(CARDS[1], replyStart = true); rig.fire()
        // The step of a card text is already pressed, so its fire only sends the next sentence.
        assertEquals(STEPS.take(2), rig.pressed)
        assertEquals(MARK + SAYS[2], rig.sent.last())
    }

    @Test fun aSceneWithNoNarrationWalksWithItsCardTexts() {
        val rig = Rig(SceneNarration(true, emptyMap(), null))
        rig.start()
        assertEquals(STEPS.take(1), rig.pressed)
        assertEquals(listOf(MARK + CARDS[0]), rig.sent)
        rig.say(CARDS[0], replyStart = true); rig.fire()
        rig.replyEnded()
        rig.say(CARDS[1], replyStart = true); rig.fire()
        rig.replyEnded()
        assertEquals(STEPS, rig.pressed)
        assertEquals(CARDS.map { MARK + it }, rig.sent)
    }

    /** «Продолжай» (decision 16): the walk goes on from the step after the present one, and the caller says its sentence. */
    @Test fun continueWalksTheRest() {
        val rig = Rig()
        val first = main { rig.narrator.rest(TOOL, STEPS, NARRATION, present = 1) }
        assertEquals(SAYS[1], first)
        assertTrue("the caller says the first sentence", rig.sent.isEmpty())
        rig.say(SAYS[1], replyStart = true); rig.fire()
        rig.say(SAYS[2], replyStart = true); rig.fire()
        assertEquals(STEPS.drop(1), rig.pressed)
        assertEquals(listOf(MARK + SAYS[2]), rig.sent)
        assertEquals(null, main { rig.narrator.rest(TOOL, STEPS, NARRATION, present = 3) })
    }

    private companion object {
        const val TOOL = "scene"
        val STEPS = listOf("Корабль", "Гвоздь", "Лодка")
        val SAYS = listOf("Корабль полый, и вода держит его.", "Гвоздь сплошной, и он тонет.", "Лодка тоже полая и плавает.")
        val CARDS = listOf("Корабль вытесняет много воды.", "Гвоздь вытесняет мало воды.", "Лодка вытесняет воду своего веса.")
        val NARRATION = SceneNarration(true, SAYS.withIndex().associate { (index, say) -> index + 1 to say }, null)
    }
}
