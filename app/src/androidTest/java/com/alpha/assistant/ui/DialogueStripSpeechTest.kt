package com.alpha.assistant.ui

import android.view.View
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveTiming
import com.alpha.assistant.voice.outputAudio
import com.alpha.assistant.voice.outputDelta
import com.alpha.assistant.voice.pcmBlock
import com.alpha.assistant.voice.silentAudio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** Plan D, Da3: the strip shows the words that the voice speaks now under the status row. */
class DialogueStripSpeechTest {
    private val stage = StageShell("dialogue-strip-speech")
    private val robot get() = stage.robot
    private val socket get() = stage.socket
    @After fun remove() = stage.remove()

    @Test fun stripShowsTheCurrentLine() = live { scenario, _ ->
        socket.emit(outputDelta("Сердце — ", 0, 300))
        socket.emit(outputDelta("это насос.", 300, 900))
        robot.waitUntil { inStrip(scenario, "Сердце — это насос.") }
        endReply()
        socket.emit(outputDelta("Дальше.", 0, 300))
        robot.waitUntil { inStrip(scenario, "Дальше.") }
    }

    @Test fun aLongLineShowsItsEnd() = live { scenario, _ ->
        socket.emit(outputDelta(LONG, 0, 20_000))
        robot.waitUntil { inStrip(scenario, LONG) }
        // The clipped bounds are what a person sees. The unclipped bounds hold the whole text.
        val shown = robot.composedBounds(scenario, SPEECH).first
        val whole = checkNotNull(robot.composedBoundsIn(scenario, SPEECH) { true })
        val lineCount = robot.composedTextFit(scenario, SPEECH).single().second
        val lineHeight = whole.height().toFloat() / lineCount
        assertTrue("the text has more lines ($lineCount) than its place", lineCount > LINES)
        assertEquals("the lines in view of $shown", LINES, (shown.height() / lineHeight).roundToInt())
        assertTrue("the end of the text $whole is in view $shown", abs(whole.bottom - shown.bottom) <= 1)
        assertTrue("the first line of the text $whole is cut $shown", whole.top + lineHeight <= shown.top + 1)
    }

    @Test fun theStripKeepsItsPlace() = live { scenario, _ ->
        val base = stripTop(scenario)
        socket.emit(outputDelta("Сердце — насос.", 0, 900))
        robot.waitUntil { inStrip(scenario, "Сердце — насос.") }
        val placed = stripTop(scenario)
        assertTrue("the line takes its place once: $placed under $base", placed < base)
        socket.emit(outputDelta(" $LONG", 900, 20_000))
        robot.waitUntil { inStrip(scenario, "Сердце — насос. $LONG") }
        assertEquals("during a long reply", placed, stripTop(scenario))
        endReply()
        assertEquals("after the reply", placed, stripTop(scenario))
        socket.emit(outputDelta("Дальше.", 0, 300))
        robot.waitUntil { inStrip(scenario, "Дальше.") }
        assertEquals("at the next reply", placed, stripTop(scenario))
    }

    /** The voice speaks the same words, so a screen reader does not hear the line a second time. */
    @Test fun theLineIsHiddenFromAScreenReader() = live { scenario, _ ->
        socket.emit(outputDelta("Сердце — насос.", 0, 900))
        robot.waitUntil { inStrip(scenario, "Сердце — насос.") }
        assertTrue(robot.composedIn(scenario, SPEECH) { SemanticsProperties.HideFromAccessibility in it })
        assertFalse(robot.composedIn(scenario, SPEECH) { SemanticsProperties.LiveRegion in it })
        robot.awaitFrames(10)
        val status = robot.find(robot.text(R.string.live_voice_listening))
        assertTrue("the strip status is visible to a screen reader", status?.isVisibleToUser == true)
        // A screen reader reads only a node that is visible to the user.
        robot.find("Сердце — насос.")?.let { node ->
            assertFalse("the line is visible to a screen reader", node.isVisibleToUser)
            assertFalse("the line is important for accessibility", node.isImportantForAccessibility)
            assertEquals("the line is a live region", View.ACCESSIBILITY_LIVE_REGION_NONE, node.liveRegion)
        }
    }

    @Test fun theEndOfTheModeClearsTheLine() = live { scenario, model ->
        val base = stripTop(scenario)
        socket.emit(outputDelta("Сердце — насос.", 0, 900))
        robot.waitUntil { inStrip(scenario, "Сердце — насос.") }
        scenario.onActivity { model.endLiveVoice() }
        robot.waitUntil { !model.liveVoice.active }
        scenario.onActivity { model.startLiveVoice() }
        robot.waitUntil { socket.types().count { it == "session.start" } == 2 }
        socket.emit(LIVE_STARTED)
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING && robot.composedBoundsOrNull(scenario, STRIP) != null }
        assertFalse(robot.composedIn(scenario, SPEECH) { true })
        assertEquals(base, stripTop(scenario))
    }

    /**
     * A barge-in drops the rest of the reply (spec 5.4.5), and the reply ends at its silent count. The line keeps the text of the
     * interrupted reply, also the deltas after the barge-in, until the first delta of the next reply.
     */
    @Test fun anInterruptionKeepsTheLineUntilTheNextReply() = live(LiveTiming(replyGapMs = LONG_GAP_MS)) { scenario, model ->
        val audio = stage.audio
        val voice = Random(11)
        audio.playbackActive = true
        socket.emit(outputDelta("Сердце — насос, ", 0, 600))
        socket.emit(outputAudio(1))
        robot.waitUntil { model.liveVoice.phase == LivePhase.SPEAKING }
        // The detector learns the echo level in its 240 ms grace, then 200 ms of loud blocks are the person.
        repeat(15) { audio.capture(voice.pcmBlock(0.002)) }
        repeat(10) { audio.capture(voice.pcmBlock(0.15)) }
        robot.waitUntil { audio.flushes.get() == 1 }
        audio.playbackActive = false
        audio.capture(voice.pcmBlock(0.002))
        robot.waitUntil { !model.liveVoice.userSpeaking }
        socket.emit(outputDelta("которое не устаёт.", 600, 1_200))
        robot.waitUntil { inStrip(scenario, "Сердце — насос, которое не устаёт.") }
        endReply(LONG_GAP_MS)
        socket.emit(outputDelta("Слушаю.", 1_300, 1_600))
        robot.waitUntil { inStrip(scenario, "Слушаю.") }
    }

    /** A voiced chunk, then [gapMs] of silent stream audio: the session ends the reply by the sample count (ruling R27). */
    private fun endReply(gapMs: Long = LiveTiming().replyGapMs) {
        socket.emit(outputAudio(1))
        repeat((gapMs / 100).toInt()) { socket.emit(silentAudio()) }
        robot.instrumentation.runOnMainSync { }
    }

    /** The voice mode on an empty stage, with no app request. */
    private fun live(timing: LiveTiming = LiveTiming(), test: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) {
        stage.fixture.install(liveVoice = stage.fixture.liveVoice(socket, stage.audio, timing))
        stage.live(test)
    }


    private fun stripTop(scenario: ActivityScenario<CloudTestActivity>): Int {
        robot.awaitFrames(3)
        return robot.composedBounds(scenario, STRIP).first.top
    }

    private fun inStrip(scenario: ActivityScenario<CloudTestActivity>, text: String) = robot.composedTextIn(scenario, STRIP, text)

    private companion object {
        const val STRIP = "dialogue_strip"
        const val SPEECH = "live_voice_speech"
        /** The place of the line at each size. */
        const val LINES = 2
        /** A reply gap that the steps of a barge-in cannot reach, so only the silent audio of the test ends the reply. */
        const val LONG_GAP_MS = 5_000L
        /** A reply of about 600 characters: more than two lines at the tablet size. Its first and last words differ from the rest. */
        val LONG = "Начало ответа. " + "Кровь идёт по кругу: сердце толкает её в лёгкие, а потом по всему телу. ".repeat(7) + "Конец ответа."
    }
}
