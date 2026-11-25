package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A result says what was typed only when a word of the query stands in it as a word: "cat"
 * in "a cat asleep on the sofa", not in "catalogue"; "dog in the snow" by "dog" or "snow", not by "the".
 */
class ResultWordingTest {

    private fun hit(text: String, file: String = "x.pdf") = SearchHit(
        row = VaultItem(
            id = "v", uri = "content://x", ocrText = text, lang = "en", itemType = ItemType.PDF,
            sourceFile = file, timestamp = 0,
        ),
    )

    private fun says(text: String, query: String, file: String = "x.pdf") =
        ResultWording.saysAWord(hit(text, file), ResultWording.contentWords(query))

    @Test fun aWordOfItsOwnCounts() = assertTrue(says("a cat asleep on the sofa", "cat"))
    @Test fun aWordInsideALongerOneDoesNot() = assertFalse(says("the spring catalogue, by category, scattered", "cat"))
    @Test fun punctuationEndsAWord() = assertTrue(says("my black cat, sleeping.", "cat"))
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

    private fun picture(id: String) = VaultItem(
        id = id, uri = "content://$id", ocrText = "", lang = "zxx", itemType = ItemType.PHOTO, sourceFile = id, timestamp = 0,
    )

    @Test fun picturesThatSayItDoNotHideTheOnesThatShowIt() {
        val saying = (1..40).map { picture("says$it") }
        val looking = listOf(picture("looks1"), picture("looks2"), picture("says30"))
        val first = ResultWording.mixed(saying, looking).map { it.id }
        assertEquals(listOf("says30", "says1", "looks1", "says2", "looks2", "says3"), first.take(6))
        assertEquals(42, first.size)
    }

    @Test fun romanisedSmallWordsAreSmall() = assertFalse(says("ghar mein sab", "barf mein kutta"))
}
