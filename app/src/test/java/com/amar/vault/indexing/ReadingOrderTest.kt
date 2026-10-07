package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The blocks of text found on a picture are stored in the order they are read. The control
 * is the order the reader handed them back in, which is what was stored before.
 */
class ReadingOrderTest {

    private fun block(text: String, left: Int, top: Int, lineHeight: Int = 40) = PlacedText(text, left, top, lineHeight)

    // A search screen as the reader gave it back: the card at the bottom came in the middle.
    private val asHandedBack = listOf(
        block("All", 40, 300),
        block("See everything about \"queue\"", 40, 420),
        block("18:09", 600, 1900),
        block("Operating systems.pdf\n...multilevel feedback\nqueues. Only when", 260, 560),
        block("Saved Item", 40, 1900),
        block("queue", 120, 160),
        block("Images", 180, 302),
        block("Videos", 360, 298),
        block("Opens at page 300", 400, 760),
        block("Document", 260, 762),
    )

    @Test
    fun topToBottomAndLeftToRightAlongARow() {
        assertEquals(
            listOf("queue", "All", "Images", "Videos", "See everything about \"queue\"",
                "Operating systems.pdf\n...multilevel feedback\nqueues. Only when", "Document", "Opens at page 300", "Saved Item", "18:09"),
            ReadingOrder.of(asHandedBack).map { it.text },
        )
        assertNotEquals("the control: as handed back", asHandedBack, ReadingOrder.of(asHandedBack))
    }

    @Test
    fun theLinesOfABlockStayTogether() {
        // A thumbnail's small print beside a paragraph: neither is cut into by the other.
        val side = block("OPERATING\nSYSTEM\nCONCEPTS", 40, 580, lineHeight = 20)
        val paragraph = block("...multilevel feedback\nqueues. Only when\nqueue 0 is empty", 260, 560)
        assertEquals("...multilevel feedback\nqueues. Only when\nqueue 0 is empty\nOPERATING\nSYSTEM\nCONCEPTS",
            ReadingOrder.text(listOf(side, paragraph)))
    }

    @Test
    fun whatIsNearlyLevelIsARowWhatIsALineLowerIsNot() {
        val left = block("Document", 40, 762)
        val right = block("Opens at page 300", 400, 750)   // a few pixels higher, on the same row
        assertEquals(listOf(left, right), ReadingOrder.of(listOf(right, left)))

        val lower = block("Document", 40, 800)              // a whole line lower: read after
        assertEquals(listOf(right, lower), ReadingOrder.of(listOf(lower, right)))
    }

    @Test
    fun nothingIsAddedOrLost() {
        assertEquals(asHandedBack.sortedBy { it.text }, ReadingOrder.of(asHandedBack).sortedBy { it.text })
        assertEquals(emptyList<PlacedText>(), ReadingOrder.of(emptyList()))
    }
}
