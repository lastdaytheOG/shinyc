package com.amar.vault.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsInOrderTest {

    private val lastWorkingDays = WordsInOrder(listOf("last", "working", "days"))

    @Test
    fun allTheWordsTogetherIsAFullRun() {
        assertEquals(3, lastWorkingDays.longestRun("the last working days of term"))
    }

    @Test
    fun aPluralCountsAsItsSingularAndTheOtherWayRound() {
        assertEquals(3, lastWorkingDays.longestRun("second mid term last working day commencement"))
        assertEquals(2, WordsInOrder(listOf("feedback", "queue")).longestRun("multilevel feedback queues."))
    }

    @Test
    fun someOfTheWordsTogetherIsAShorterRun() {
        assertEquals(2, lastWorkingDays.longestRun("within 3 to 5 working days."))
        assertEquals(2, lastWorkingDays.longestRun("on the last working friday"))
    }

    @Test
    fun theSameWordsApartAreNoRun() {
        assertEquals(0, lastWorkingDays.longestRun("working set. the last fault. over many days"))
        assertEquals(0, lastWorkingDays.longestRun("days working last"))
    }

    @Test
    fun lineBreaksAndPunctuationBetweenWordsDoNotBreakARun() {
        assertEquals(3, lastWorkingDays.longestRun("last\nworking - days"))
        assertEquals(3, lastWorkingDays.longestRun("(last, working, days)"))
    }

    @Test
    fun aWordInsideALongerWordIsNotThatWord() {
        assertEquals(0, WordsInOrder(listOf("work", "day")).longestRun("homework daybreak"))
        // "blast" is not "last": what is left is the run of two that follows it.
        assertEquals(2, lastWorkingDays.longestRun("blast working days"))
    }

    @Test
    fun hindiWordsRunTogetherToo() {
        val pragatiReport = WordsInOrder(listOf("प्रगति", "रिपोर्ट"))
        assertEquals(2, pragatiReport.longestRun("विषयः तिमाही प्रगति रिपोर्ट दिनांक"))
        assertEquals(0, pragatiReport.longestRun("रिपोर्ट की प्रगति"))
    }

    @Test
    fun oneWordHasNoOrderToBeIn() {
        assertEquals(0, WordsInOrder(listOf("queue")).longestRun("queue queue queue"))
    }
}
