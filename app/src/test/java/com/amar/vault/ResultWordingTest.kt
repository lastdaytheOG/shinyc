package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A result says what was typed only when a word of the query stands in it as a word: "hand"
 * in "a hand with henna", not in "handbooks"; "dog in the snow" by "dog" or "snow", not by "the".
 */
class ResultWordingTest {

    private fun hit(text: String, file: String = "x.pdf") = SearchHit(
        row = StashItemWithVaultItem(
            stashId = "s", sessionId = null, vaultItemId = "v", vaultType = "pdf", category = "", savedAt = 0,
            sourceApp = "", isFavorite = false, createdAt = 0, userNote = null, thumbnailPath = null,
            uri = "content://x", ocrText = text, itemType = ItemType.PDF, sourceFile = file, timestamp = 0,
            title = null, mimeType = null,
        ),
    )

    private fun says(text: String, query: String, file: String = "x.pdf") =
        ResultWording.saysAWord(hit(text, file), ResultWording.contentWords(query))

    @Test fun aWordOfItsOwnCounts() = assertTrue(says("a hand with henna on it", "hand"))
    @Test fun aWordInsideALongerOneDoesNot() = assertFalse(says("training manuals and handbooks, handle, handed", "hand"))
    @Test fun punctuationEndsAWord() = assertTrue(says("my left hand, painted.", "hand"))
    @Test fun aFileNameSaysItsWords() = assertTrue(says("", "aadhaar", file = "Aadhaar_Card-2024.pdf"))
    @Test fun smallWordsAloneDoNotCount() = assertFalse(says("in other words, the reward system", "dog in the snow"))
    @Test fun oneContentWordIsEnough() = assertTrue(says("snow fell all night", "dog in the snow"))
    @Test fun aQueryOfOnlySmallWordsKeepsThem() = assertEquals(listOf("the"), ResultWording.contentWords("the"))
    @Test fun numbersAreWords() = assertTrue(says("payment of rs 500 paid to ravi", "rs 500"))

    @Test fun hindiWordsStandAlone() {
        assertTrue(says("बर्फ़ में कुत्ता खेल रहा है", "कुत्ता"))
        // A vowel sign joined after the typed letters makes it another word.
        assertFalse(says("कुत्ते", "कुत्त"))
    }

    @Test fun romanisedSmallWordsAreSmall() = assertFalse(says("ghar mein sab", "barf mein kutta"))
}
