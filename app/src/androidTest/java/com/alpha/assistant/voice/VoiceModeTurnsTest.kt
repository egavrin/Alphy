package com.alpha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Turns, request text, and voice approval rules of spec 5.5.2 and 5.5.10. */
class VoiceModeTurnsTest {
    private fun transcript(): LiveTranscript {
        val t = LiveTranscript()
        t.input("Привет", 0, 800, false)
        t.output("Привет!", 1_000, 1_600)
        t.input("Поставь таймер", 2_000, 3_000, false)
        return t
    }

    @Test fun requestIsTheLastUserTurnOnly() {
        assertEquals("Поставь таймер", transcript().request(3_200))
    }

    @Test fun requestStartsAfterThePreviousHandOff() {
        val t = transcript()
        t.request(3_200)
        t.input(" на пять минут", 3_400, 4_200, false)
        assertEquals("на пять минут", t.request(4_400))
    }

    @Test fun requestIsBlankWithoutNewInput() {
        val t = transcript()
        t.request(3_200)
        t.input("на пять минут", 3_400, 4_200, false)
        t.request(4_400)
        assertEquals("", t.request(5_000))
    }

    @Test fun requestIgnoresInputAfterTheOffset() {
        val t = transcript()
        t.input("потом", 3_500, 3_900, false)
        assertEquals("Поставь таймер", t.request(3_200))
    }

    @Test fun outputEndsAndReturnsTheOpenTurn() {
        val t = LiveTranscript()
        t.input("Привет", 0, 800, true)
        t.input(" мир", 800, 1_200, false)
        assertEquals(UserTurn("Привет мир", 0, 1_200, true), t.output("Ответ", 1_500, 2_000))
        assertEquals(2_000, t.outputEndMs)
        assertNull(t.endTurn())
    }

    @Test fun endTurnReturnsTheOpenTurnOnce() {
        val t = LiveTranscript()
        t.input("да", 5, 9, false)
        assertEquals(UserTurn("да", 5, 9, false), t.endTurn())
        assertNull(t.endTurn())
    }

    @Test fun closedPhraseListOnly() {
        listOf("Да", "да, запускай.", "Go ahead", "ПОДТВЕРЖДАЮ").forEach { assertEquals(it, SpokenAnswer.APPROVE, spokenAnswer(it)) }
        listOf("Нет", "отмена", "Cancel").forEach { assertEquals(it, SpokenAnswer.REJECT, spokenAnswer(it)) }
        listOf("да, но на десять минут", "давай позже", "не знаю", "").forEach { assertEquals(it, SpokenAnswer.OTHER, spokenAnswer(it)) }
    }

    private fun turn(text: String, startMs: Long, overlapped: Boolean = false) = UserTurn(text, startMs, startMs + 500, overlapped)

    @Test fun windowRejectsLateOverlappedOrEarlyTurns() {
        val window = ApprovalWindow("plan", 1, opensAtMs = 10_000)
        assertNull(window.decide(turn("да", 9_000)))
        assertNull(window.decide(turn("да", 41_000)))
        assertNull(window.decide(turn("да", 12_000, overlapped = true)))
        assertEquals(SpokenAnswer.APPROVE, window.decide(turn("да", 12_000)))
    }

    @Test fun onlyTheFirstTurnInTheWindowCounts() {
        val window = ApprovalWindow("plan", 1, opensAtMs = 10_000)
        assertEquals(SpokenAnswer.OTHER, window.decide(turn("да, но позже", 12_000)))
        assertNull(window.decide(turn("да", 14_000)))
    }

    @Test fun anOverlappedTurnDoesNotUseUpTheWindow() {
        val window = ApprovalWindow("plan", 1, opensAtMs = 10_000)
        assertNull(window.decide(turn("да", 11_000, overlapped = true)))
        assertEquals(SpokenAnswer.REJECT, window.decide(turn("нет", 13_000)))
    }

    @Test fun windowIsOpenAtItsStartAndClosedAtItsEnd() {
        assertNotNull(ApprovalWindow("p", 1, 10_000).decide(turn("да", 10_000)))
        assertNull(ApprovalWindow("p", 1, 10_000).decide(turn("да", 40_000)))
    }
}
