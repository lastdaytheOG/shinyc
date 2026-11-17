package com.amar.vault.indexing.ppocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CtcTextTest {

    private val letters = listOf("", "a", "b", "c", " ")

    private fun scores(vararg steps: Pair<Int, Float>): FloatArray {
        val out = FloatArray(steps.size * letters.size)
        for ((t, step) in steps.withIndex()) out[t * letters.size + step.first] = step.second
        return out
    }

    @Test
    fun aLetterHeldOverSeveralStepsIsReadOnce() {
        val read = CtcText.read(scores(1 to 0.9f, 1 to 0.8f, 2 to 0.7f, 2 to 0.6f), 0, 4, letters.size, letters)

        assertEquals("ab", read.text)
        assertEquals((0.9f + 0.7f) / 2, read.confidence, 1e-6f)
    }

    @Test
    fun aBlankBetweenTheSameLetterKeepsBoth() {
        val read = CtcText.read(scores(1 to 0.9f, 0 to 0.9f, 1 to 0.5f, 4 to 0.8f, 3 to 0.6f), 0, 5, letters.size, letters)

        assertEquals("aa c", read.text)
        assertEquals((0.9f + 0.5f + 0.8f + 0.6f) / 4, read.confidence, 1e-6f)
    }

    @Test
    fun nothingButBlanksIsNoTextAndNoConfidence() {
        val read = CtcText.read(scores(0 to 0.9f, 0 to 0.9f), 0, 2, letters.size, letters)

        assertEquals("", read.text)
        assertEquals(0f, read.confidence, 0f)
        assertEquals(0f, read.weakest, 0f)
    }

    @Test
    fun theWeakestWordIsTheWordWithTheLowestAverageLetter() {
        val read = CtcText.read(scores(1 to 0.9f, 2 to 0.9f, 4 to 0.95f, 3 to 0.3f, 1 to 0.5f), 0, 5, letters.size, letters)

        assertEquals("ab ca", read.text)
        assertEquals(0.4f, read.weakest, 1e-6f)
        assertEquals((0.9f + 0.9f + 0.95f + 0.3f + 0.5f) / 5, read.confidence, 1e-6f)
    }

    @Test
    fun aLineOfOneWordIsAsWeakAsItsAverage() {
        val read = CtcText.read(scores(1 to 0.9f, 2 to 0.7f), 0, 2, letters.size, letters)

        assertEquals(0.8f, read.weakest, 1e-6f)
    }

    @Test
    fun aLineIsReadFromItsPlaceInABatch() {
        val batch = scores(1 to 0.9f, 2 to 0.9f, 3 to 0.7f, 3 to 0.7f)

        assertEquals("c", CtcText.read(batch, 2 * letters.size, 2, letters.size, letters).text)
    }

    @Test
    fun theDictionaryGetsTheBlankInFrontAndTheSpaceBehindWhenTheReaderCountsThem() {
        assertEquals(listOf("", "a", "b", " "), CtcText.letters(listOf("a", "b"), 4))
        assertEquals(listOf("", "a", "b"), CtcText.letters(listOf("a", "b"), 3))
        assertThrows(IllegalArgumentException::class.java) { CtcText.letters(listOf("a", "b"), 7) }
    }

    @Test
    fun theReferenceReaderOutputsAreTurnedIntoTheReferenceText() {
        val dictionary = PpOcrCases.letters()
        val classes = dictionary.size + 2
        val known = CtcText.letters(dictionary, classes)
        val cases = PpOcrCases.readLines()
        assertTrue(cases.size >= 10)

        for (case in cases) {
            val scores = FloatArray(case.best.size * classes)
            for (t in case.best.indices) scores[t * classes + case.best[t]] = case.score[t]

            val read = CtcText.read(scores, 0, case.best.size, classes, known)

            assertEquals(case.text, read.text)
            assertEquals(case.confidence, read.confidence, 1e-4f)
        }
    }
}
