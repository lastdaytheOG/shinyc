package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several readings of one picture become one text: each line once, in the wording most
 * readings gave it, and no word lost that could be searched for.
 *
 * The control in each test is [formerMerge], the merge as it was: every line kept that is
 * not letter for letter inside another.
 */
class ReadingMergeTest {

    /** The merge as it was, copied from ImageContentExtractor.mergeAllOcrResults. */
    private fun formerMerge(readings: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<String>()
        for (text in readings) {
            if (text.isBlank()) continue
            for (line in text.split("\n")) {
                val cleaned = line.trim()
                if (cleaned.isEmpty()) continue
                val normalized = cleaned.lowercase().replace(Regex("\\s+"), " ")
                if (seen.any { it.contains(normalized) }) continue
                seen.removeAll { normalized.contains(it) }
                seen.add(normalized)
                unique.add(cleaned)
            }
        }
        return unique
    }

    private fun merged(vararg readings: String) = ReadingMerge.lines(readings.toList()).map { it.text }
    private fun formerly(vararg readings: String) = formerMerge(readings.toList())

    @Test
    fun aLineReadSlightlyDifferentlyEachTimeIsStoredOnce() {
        val readings = arrayOf(
            "Total due on the 4th of March",
            "Total due on the 4th of March",
            "Total due on the 4th ot March",
            "Total due on the 4th of March.",
            "@ Total due on the 4th of March",
        )
        assertEquals(listOf("Total due on the 4th of March"), merged(*readings))
        assertEquals("the control: it was stored in four wordings", 4, formerly(*readings).size)
    }

    @Test
    fun theWordingMostReadingsGaveIsTheOneStored() {
        val readings = arrayOf(
            "the frame is ernpty until then",   // one reading's slip
            "the frame is empty until then",
            "the frame is empty until then",
        )
        assertEquals(listOf("the frame is empty until then"), merged(*readings))
        assertTrue("the control: the slip was stored too", formerly(*readings).any { "ernpty" in it })
    }

    @Test
    fun aShortLineIsNotLostToALongerOneThatContainsItsLetters() {
        val reading = "Opens at page 300\nRs 250\nOpens at page 30\nRs 25"
        assertEquals(reading.split("\n"), merged(reading, reading, reading))
        assertEquals("the control: page 30 and Rs 25 were thrown away", listOf("Opens at page 300", "Rs 250"), formerly(reading, reading, reading))
    }

    @Test
    fun linesWithDifferentNumbersAreNeverTheSameLine() {
        // Each reading missed one of two lines that differ by a digit.
        val text = merged("Paid at 18:09\nReceipt", "Paid at 18:08\nReceipt", "Receipt")
        assertEquals(listOf("Paid at 18:09", "Paid at 18:08", "Receipt"), text)
    }

    @Test
    fun aLineThatIsOnThePictureTwiceIsStoredTwice() {
        val reading = "Saved Item\nNote\nSaved Item\nNote"
        assertEquals(reading.split("\n"), merged(reading, reading, reading))
        assertEquals("the control: once", listOf("Saved Item", "Note"), formerly(reading, reading, reading))
    }

    @Test
    fun aWordTwoReadingsAgreeOnIsKeptEvenWhenOutvoted() {
        // Three readings ran two words together; two read them apart. Nothing here can tell
        // which is right, and "will" must stay findable.
        val readings = arrayOf(
            "queue 0 is empty willit execute",
            "queue 0 is empty willit execute",
            "queue 0 is empty will it execute",
            "queue 0 is empty willit execute",
            "~ queue 0 is empty will it execute",
        )
        assertEquals(listOf("queue 0 is empty willit execute", "queue 0 is empty will it execute"), merged(*readings))
    }

    @Test
    fun aWordOnlyOneReadingOfALineHasLosesTheVote() {
        val readings = arrayOf(
            "Silberschatz 9th edition",
            "Silberschatz 9th edition",
            "Silberschatz 9th edition",
            "Silberschatz 9th edltion",
        )
        assertEquals(listOf("Silberschatz 9th edition"), merged(*readings))
    }

    @Test
    fun aLineOnlyOneReadingHasIsKeptWhenItSaysSomethingNew() {
        val text = merged("Invoice\nTotal 450", "Invoice\nTotal 450", "Invoice\nTotal 450\nGSTIN 22AAAAA0000A1Z5")
        assertEquals(listOf("Invoice", "Total 450", "GSTIN 22AAAAA0000A1Z5"), text)
    }

    @Test
    fun aLineOnlyOneReadingHasIsLeftOutWhenItOnlyRepeatsWhatIsStored() {
        // The second engine reads a whole row as one line and an icon as a letter.
        val text = merged("Document\nOpens at page 300", "Document\nOpens at page 300", "@ Document Opens at page 300")
        assertEquals(listOf("Document", "Opens at page 300"), text)
        assertEquals("the control: stored a second time, with the icon",
            listOf("Document", "Opens at page 300", "@ Document Opens at page 300"),
            formerly("Document\nOpens at page 300", "Document\nOpens at page 300", "@ Document Opens at page 300"))
    }

    @Test
    fun aLoneCharacterOrAWordInTwoAlphabetsSaysNothingNew() {
        // The Hindi reader's go at Latin text, and an icon read as a letter.
        val text = merged("queue\nSearch", "queue\nSearch\nपपeue\nও\n০Search", "queue\nSearch")
        assertEquals(listOf("queue", "Search"), text)
    }

    @Test
    fun hindiReadByOneReadingOnlyIsKept() {
        // Only the Hindi reader reads Hindi: one reading in several has the line.
        val text = merged("Balance 450", "Balance 450\nकुल राशि हिंदी में", "Balance 450", "Balance 450")
        assertEquals(listOf("Balance 450", "कुल राशि हिंदी में"), text)
    }

    @Test
    fun hindiReadTwiceIsStoredOnce() {
        assertEquals(listOf("कुल राशि ४५० रुपये"), merged("कुल राशि ४५० रुपये", "कुल राशि ४५० रुपये", ""))
    }

    @Test
    fun linesComeOutInTheOrderTheyWereRead() {
        val text = merged(
            "Title\nFirst line\nLast line",
            "Title\nFirst line\nMiddle line only this one saw\nLast line",
            "Header only this one saw\nTitle\nFirst line\nLast line",
        )
        assertEquals(listOf("Header only this one saw", "Title", "First line", "Middle line only this one saw", "Last line"), text)
    }

    @Test
    fun aSingleReadingIsTheTextAsItWasRead() {
        val reading = "Note\nNote\nRs 25\nRs 250\nX"
        assertEquals(reading.split("\n"), merged(reading))
        assertEquals(reading.split("\n"), merged("", reading, "  "))
        assertEquals(emptyList<String>(), merged("", " \n "))
    }

    @Test
    fun whoseWordingAndHowManySawIt() {
        val lines = ReadingMerge.lines(listOf("Totai due", "Total due\nThank you", "Total due"))
        assertEquals(listOf(ReadingMerge.Line("Total due", from = 1, seenBy = 3), ReadingMerge.Line("Thank you", from = 1, seenBy = 1)), lines)
        assertFalse("the first reading's slip is nobody's wording", lines.any { it.from == 0 })
    }

    @Test
    fun noSearchableWordOfAnyReadingAgreedOnTwiceIsLost() {
        val readings = listOf(
            "Dr Mehta Clinic\nParacetamol 500 mg twice daily\nFollow up in 7 days",
            "Dr Mehta Clinic\nParacetamol 500 mg twice daily\nFollow up in 7 days\nPh 98200 12345",
            "Dr Mehta Clinlc\nParacetamol 500 mg twice daily\nFollow up in 7 days\nPh 98200 12345",
            "Dr Mehta Clinic\nParacetamol 500 mg twlce daily\nFollow up in 7 days",
        )
        val stored = ReadingMerge.text(readings).lowercase()
        val words = Regex("[\\p{L}\\p{N}]{2,}")
        val twice = readings.flatMap { words.findAll(it.lowercase()).map { w -> w.value }.toSet() }.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
        assertEquals(emptySet<String>(), twice.filterNot { it in stored }.toSet())
        assertEquals(4, stored.lines().size)
    }
}
