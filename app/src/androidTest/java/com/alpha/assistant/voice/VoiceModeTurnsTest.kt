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

    @Test fun blankRequestKeepsTheHandOffMark() {
        val t = LiveTranscript()
        assertEquals("", t.request(3_000))
        t.input("Поставь таймер", 1_000, 2_500, false)
        assertEquals("Поставь таймер", t.request(3_000))
    }

    /** Each delta of the live check log spans 200 ms. */
    private fun LiveTranscript.heard(vararg deltas: Pair<Long, String>) = deltas.forEach { (at, d) -> input(d, at, at + 200, false) }
    private fun LiveTranscript.spoke(vararg deltas: Pair<Long, String>) = deltas.forEach { (at, d) -> output(d, at, at + 200) }

    /** The transcript of live check run 2 (`live-2a3dd7bb5762`) in arrival order: GPT-Live talked over the second request. */
    @Test fun requestKeepsTheWordsThatGptLiveTalkedOver() {
        val t = LiveTranscript()
        t.heard(1_400L to " Постав", 1_600L to "ь", 2_000L to " таймер", 2_200L to " на", 2_400L to " пять", 2_800L to " минут")
        assertEquals("Поставь таймер на пять минут", t.request(2_800))
        t.spoke(3_000L to " Сейчас сдел", 3_200L to "аю.")
        t.spoke(28_800L to " Пожал", 29_000L to "уйста,", 29_200L to " подтвердите", 29_800L to " действие", 30_400L to " на эк",
            30_600L to "ране.")
        t.spoke(33_600L to " Готов", 33_800L to "о,", 34_000L to " план", 34_200L to " завершён", 34_400L to ".")
        t.spoke(37_400L to " Чет", 37_600L to "ыре", 37_800L to ".")
        t.spoke(42_200L to " Луна", 42_400L to " —", 42_600L to " единственный", 43_400L to " естественный", 44_000L to " спутник")
        t.heard(44_200L to " Постав", 44_400L to "ь")
        t.spoke(44_400L to " Земли", 44_600L to ".")
        t.heard(44_800L to " таймер на", 45_200L to " пять", 45_600L to " минут")
        assertEquals("Поставь таймер на пять минут", t.request(45_600))
    }

    @Test fun answeredSmallTalkIsCutButAPauseInTheRequestIsNot() {
        val t = transcript()
        t.input(" на пять минут", 4_500, 5_300, false)
        assertEquals("Поставь таймер на пять минут", t.request(5_400))
    }

    @Test fun secondHandOffInOneSpeechRunKeepsOnlyItsOwnWords() {
        val t = LiveTranscript()
        t.input("Поставь таймер", 1_000, 1_800, false)
        assertEquals("Поставь таймер", t.request(1_800))
        t.output("Сейчас", 2_000, 2_400)
        t.input(" и включи свет", 2_200, 3_000, false)
        assertEquals("и включи свет", t.request(3_000))
    }

    @Test fun delegationBeforeLateRequestDeltasWaitsInsteadOfTakingAnsweredSmallTalk() {
        val t = LiveTranscript()
        t.input("Привет", 0, 800, false)
        t.output("Привет!", 1_000, 1_600)
        assertEquals("", t.request(3_000))
        t.input("Поставь таймер", 2_000, 2_800, false)
        assertEquals("Поставь таймер", t.request(3_000))
    }

    /** The transcript of form J (`live-8377b64de824`, `events.log` lines 20 to 51) in arrival order: GPT-Live answered the question. */
    @Test fun aLateLastWordOfAnAnsweredQuestionStaysOutOfTheNextRequest() {
        val t = LiveTranscript()
        t.heard(1_600L to " Какая", 2_200L to " планета", 2_600L to " самая", 3_000L to " большая", 3_200L to " в", 3_800L to " Солнечной")
        t.spoke(4_000L to " Юп", 4_200L to "итер.")
        t.heard(4_400L to " системе")
        t.heard(19_600L to " Объяс", 20_000L to "ни", 20_600L to ", почему", 21_000L to " Луна", 21_400L to " всегда", 22_000L to " повернута",
            22_200L to " к", 22_400L to " Земле", 22_600L to " одной", 23_200L to " стороной")
        assertEquals("Объясни, почему Луна всегда повернута к Земле одной стороной", t.request(23_200))
    }

    /** The first input of a new speech run starts at the output end. With and without a current run, it stays in the request. */
    @Test fun aRequestThatStartsWhenAnAnswerEndsKeepsItsFirstWord() {
        fun answered() = LiveTranscript().apply {
            input(" Как дела", 1_000, 1_400, false)
            output("Хорошо.", 1_800, 2_600)
            input(" Поставь", 2_600, 2_800, false)
            input(" таймер", 2_800, 3_000, false)
        }
        assertEquals("Поставь таймер", answered().request(3_000))
        assertEquals("Поставь таймер", answered().request(4_200))
    }

    /** The person starts the request over the answer, 1,000 ms after the answered turn, and pauses inside it. */
    @Test fun aRequestStartedOverAnAnswerKeepsItsFirstWordsBeforeAPause() {
        val t = LiveTranscript()
        t.input("Привет", 0, 800, false)
        t.output("Привет!", 1_000, 1_600)
        t.heard(1_800L to " Поставь", 2_000L to " таймер")
        t.input(" на пять минут", 3_500, 4_300, false)
        assertEquals("Поставь таймер на пять минут", t.request(4_400))
    }

    /** The request starts at the answer end, and its second input ends the run before a pause. */
    @Test fun aRequestThatStartsAtAnAnswerEndKeepsItsSecondWordBeforeAPause() {
        val t = LiveTranscript()
        t.input("Привет", 0, 800, false)
        t.output("Привет!", 1_000, 1_600)
        t.heard(1_600L to " Поставь", 1_800L to " таймер")
        t.input(" на пять минут", 3_500, 4_300, false)
        assertEquals("Поставь таймер на пять минут", t.request(4_400))
    }

    /**
     * The run 2 timeline with a pause of 1,200 ms after « таймер на»: the person talked over GPT-Live, so the run holds output
     * ends. The current rule already loses «Поставь» here (R31 protects only the current run), and this test does not change that.
     */
    @Test fun aRequestSpokenOverAnAnswerBeforeAPauseKeepsTheWordsAfterTheAnswerEnd() {
        val t = LiveTranscript()
        t.heard(1_400L to " Постав", 1_600L to "ь", 2_000L to " таймер", 2_200L to " на", 2_400L to " пять", 2_800L to " минут")
        assertEquals("Поставь таймер на пять минут", t.request(2_800))
        t.spoke(3_000L to " Сейчас сдел", 3_200L to "аю.")
        t.spoke(28_800L to " Пожал", 29_000L to "уйста,", 29_200L to " подтвердите", 29_800L to " действие", 30_400L to " на эк",
            30_600L to "ране.")
        t.spoke(33_600L to " Готов", 33_800L to "о,", 34_000L to " план", 34_200L to " завершён", 34_400L to ".")
        t.spoke(37_400L to " Чет", 37_600L to "ыре", 37_800L to ".")
        t.spoke(42_200L to " Луна", 42_400L to " —", 42_600L to " единственный", 43_400L to " естественный", 44_000L to " спутник")
        t.heard(44_200L to " Постав", 44_400L to "ь")
        t.spoke(44_400L to " Земли", 44_600L to ".")
        t.heard(44_800L to " таймер на", 46_200L to " пять", 46_400L to " минут")
        assertEquals("таймер на пять минут", t.request(46_400))
    }

    /** The second request of one speech run starts when the waiting phrase ends, and pauses inside it. */
    @Test fun aSecondRequestAfterAHandOffKeepsItsWordsBeforeAPause() {
        val t = LiveTranscript()
        t.input("Поставь таймер", 1_000, 1_800, false)
        assertEquals("Поставь таймер", t.request(1_800))
        t.output("Сейчас", 2_000, 2_400)
        t.input(" и включи", 2_400, 2_800, false)
        t.input(" свет в спальне", 4_000, 4_600, false)
        assertEquals("и включи свет в спальне", t.request(4_600))
    }

    /** Known limit of spec 5.5.2: a first part of one input, started over the answer and followed by a pause, is cut. */
    @Test fun aOneInputStartOfARequestOverAnAnswerBeforeAPauseIsCut() {
        val t = LiveTranscript()
        t.input("Привет", 0, 800, false)
        t.output("Привет!", 1_000, 1_600)
        t.heard(1_800L to " Поставь")
        t.input(" таймер на пять минут", 3_500, 4_300, false)
        assertEquals("таймер на пять минут", t.request(4_400))
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
