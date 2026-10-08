package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which words are marked as the ones a query found — on a result card and on the page a
 * result opens on — and which of them is brought into view.
 */
class MatchMarksTest {

    private fun marked(text: String, vararg query: String): List<String> =
        MatchMarks.ranges(text, query.toList()).map { text.substring(it.first, it.last + 1) }

    /** What the card did before: each typed word where it stands, and nothing else. */
    private fun markedBefore(text: String, vararg query: String): List<String> {
        val out = ArrayList<Pair<Int, String>>()
        for (word in query) {
            var at = text.indexOf(word, ignoreCase = true)
            while (at >= 0) {
                out += at to text.substring(at, at + word.length)
                at = text.indexOf(word, at + word.length, ignoreCase = true)
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    private val calendar = "Second Mid Term 26 October 2026 Last Working Day 27 November 2026"

    @Test
    fun theSameWordInAnotherFormIsMarked() {
        // From the phone report: "last working days" lists the calendar that says "Last Working Day".
        assertEquals(listOf("Last", "Working", "Day"), marked(calendar, "last", "working", "days"))
    }

    @Test
    fun beforeOnlyTheWordAsTypedWasMarked() {
        assertEquals(listOf("Last", "Working"), markedBefore(calendar, "last", "working", "days"))
    }

    @Test
    fun aTypedWordIsMarkedWhereverItStandsAsBefore() {
        assertEquals(listOf("Queue", "Queue", "queue"), marked("Circular Queue, Priority Queues and dequeue", "queue"))
        assertEquals(listOf("claude"), marked("This week in Claude Code".lowercase(), "claude"))
        assertEquals(listOf("रुपये"), marked("कुल राशि चार सौ रुपये", "रुपये"))
        assertEquals(emptyList<String>(), marked("nothing here says it", "invoice"))
        assertEquals(emptyList<String>(), marked("anything", ""))
    }

    @Test
    fun otherFormsOfAWord() {
        assertEquals(listOf("book"), marked("please book the hall", "booking"))
        assertEquals(listOf("plan"), marked("the plan was approved", "planned"))
        assertEquals(listOf("copy"), marked("one copy each", "copies"))
        assertEquals(listOf("payment"), marked("payment received", "payments"))
        assertEquals(listOf("Invoice's"), marked("Invoice's total", "invoices"))
        // The form on the page may itself have a small ending.
        assertEquals(listOf("installed"), marked("installed today", "installation"))
    }

    @Test
    fun aWordThatOnlyBeginsTheSameIsNotMarked() {
        assertEquals(emptyList<String>(), marked("the data was daily and dated", "days"))
        assertEquals(emptyList<String>(), marked("a bookshelf of booklets", "booking"))
        assertEquals("too short to have a root", emptyList<String>(), marked("he is here", "his"))
    }

    @Test
    fun marksDoNotOverlap() {
        // "working" is found as typed and "work" would be its root: one mark, not two.
        assertEquals(listOf("working"), marked("working", "working", "work"))
        val ranges = MatchMarks.ranges("days and days", listOf("days", "day"))
        assertEquals(listOf(0..3, 9..12), ranges)
    }

    // ── On the page ─────────────────────────────────────────────────────────────────────

    private fun line(number: Int, y: Float, vararg words: String) = words.mapIndexed { i, word ->
        WordOnPage(word, left = 100f + i * 150f, top = y, right = 230f + i * 150f, bottom = y + 40f, line = number)
    }

    /** A page that says "day" three times. */
    private val page = line(0, 100f, "Commencement", "of", "Classes", "first", "day") +
        line(1, 200f, "First", "Mid", "Term", "7", "September") +
        line(2, 900f, "Last", "Working", "Day,", "27", "November") +
        line(3, 1000f, "Results", "the", "next", "day")

    @Test
    fun everyOccurrenceOnThePageIsMarked() {
        val match = PageMatches.locate(page, listOf("last", "working", "days"), excerpt = "")
        assertEquals(listOf("day", "Last", "Working", "Day,", "day"), match.marks.map { it.text })
    }

    @Test
    fun theOneBroughtIntoViewIsTheOneTheCardShowed() {
        val match = PageMatches.locate(page, listOf("days"), excerpt = "…October 2026 Last Working Day 27 November 2026 Commencement of Practical…")

        assertEquals("the third line, not the first \"day\" on the page", 2, match.focus!!.line)
        assertEquals("Day,", match.focus!!.text)
    }

    @Test
    fun withNoExcerptTheFirstOneIsBroughtIntoView() {
        assertEquals(0, PageMatches.locate(page, listOf("days"), excerpt = "").focus!!.line)
    }

    @Test
    fun aPageThatWasReadWithoutTheWordHasNoMarks() {
        // The reader got the word wrong: nothing is marked, and nothing is scrolled to.
        val misread = line(0, 100f, "OPERATING", "SVSTEM", "CONCEPTS")
        val match = PageMatches.locate(misread, listOf("system"), excerpt = "operating system concepts")
        assertTrue(match.marks.isEmpty())
        assertNull(match.focus)
    }

    @Test
    fun aWordReadWithPunctuationOnItStillCounts() {
        assertTrue(MatchMarks.says("(Queue)", listOf("queue")))
        assertTrue(MatchMarks.says("Day,", listOf("days")))
        assertFalse(MatchMarks.says("Daily", listOf("days")))
    }
}
