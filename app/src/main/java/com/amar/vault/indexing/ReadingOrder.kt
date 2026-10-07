package com.amar.vault.indexing

import kotlin.math.min

/** A block of text and where on the picture it is. [lineHeight] is how tall one of its lines is. */
data class PlacedText(val text: String, val left: Int, val top: Int, val lineHeight: Int)

/**
 * Puts the blocks of text found on a picture in the order they are read: from the top down,
 * and from the left along a row.
 *
 * The reader hands its blocks back in an order of its own: on a screenshot, a line from the
 * bottom of the screen can come between the title at the top and the text under it. Stored
 * that way, what is shown of a picture in a search result reads as a jumble, and two lines
 * that follow each other on the screen do not follow each other in the text.
 *
 * Blocks are moved, never taken apart: the lines of a paragraph stay together.
 */
object ReadingOrder {

    /** Blocks whose tops are within this share of a line's height are on the same row. */
    private const val SAME_ROW = 0.5

    fun of(blocks: List<PlacedText>): List<PlacedText> {
        val down = blocks.sortedWith(compareBy({ it.top }, { it.left }))
        val ordered = ArrayList<PlacedText>(blocks.size)
        val row = ArrayList<PlacedText>()
        for (block in down) {
            val first = row.firstOrNull()
            if (first != null && block.top - first.top > SAME_ROW * min(first.lineHeight, block.lineHeight)) {
                ordered += row.sortedBy { it.left }
                row.clear()
            }
            row += block
        }
        ordered += row.sortedBy { it.left }
        return ordered
    }

    fun text(blocks: List<PlacedText>): String = of(blocks).joinToString("\n") { it.text }
}
