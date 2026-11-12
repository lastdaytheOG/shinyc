package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishCardTest {

    private val bill = VaultItem(
        id = "water", uri = "content://docs/water", ocrText = "नगर निगम जल कार्य विभाग\nपानी का बिल\nदेय राशि ₹612.00",
        lang = "hi", itemType = ItemType.PDF, sourceFile = "jal_101.pdf", timestamp = 0L,
    )

    @Test
    fun aCardFoundByItsHindiSpellingShowsThatWordAndStaysInTheList() {
        val words = queryWordsOf("paani ka bill")
        val hit = oneCardPerDocument(listOf(bill), words, listOf("पानी", "बिल")).single()
        assertEquals("पानी", hit.saidInHindi)
        assertTrue(hit.excerpt!!.contains("पानी"))
        assertTrue(ResultWording.saysAWord(hit, ResultWording.contentWords("paani ka bill")))
    }

    @Test
    fun withoutTheSpellingsTheSameCardWasFoldedAway() {
        val words = queryWordsOf("paani ka bill")
        val hit = oneCardPerDocument(listOf(bill), words).single()
        assertNull(hit.saidInHindi)
        assertFalse(ResultWording.saysAWord(hit, ResultWording.contentWords("paani ka bill")))
    }

    @Test
    fun aSpellingThatIsOnlyPartOfALongerWordDoesNotCount() {
        val hit = oneCardPerDocument(listOf(bill), queryWordsOf("pan"), listOf("पान")).single()
        assertNull(hit.saidInHindi)
    }
}
