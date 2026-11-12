package com.amar.vault.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishTest {

    private fun same(devanagari: String, vararg typed: String) {
        val shadow = Hinglish.fold(Hinglish.romanise(devanagari))
        typed.forEach { assertEquals("$devanagari vs $it", shadow, Hinglish.fold(it)) }
    }

    @Test
    fun everydayWordsMeetTheWayTheyAreTyped() {
        same("बिजली", "bijli", "bijlee")
        same("बिल", "bil", "bill")
        same("पानी", "pani", "paani")
        same("महीने", "mahine", "maheene")
        same("पिछले", "pichle", "pichhle")
        same("दवाई", "dawai", "davai", "dawaai")
        same("बारिश", "barish", "baarish")
        same("मौसम", "mausam", "mosam")
        same("चाय", "chai", "chay")
        same("का", "ka", "kaa")
    }

    @Test
    fun shadowsAreOnlyMadeForDevanagari() {
        assertEquals("", Hinglish.shadows("Electricity bill for March"))
        val shadows = Hinglish.shadows("बिजली का बिल हर महीने").split(' ')
        assertTrue(shadows.containsAll(listOf("bijli", "ka", "bil", "mahine")))
    }

    @Test
    fun typedLatinWordsAreFoldedTheSameWay() {
        assertEquals(listOf("bijli", "ka", "bil"), Hinglish.foldedQueryWords("Bijlee ka Bill"))
    }
}
