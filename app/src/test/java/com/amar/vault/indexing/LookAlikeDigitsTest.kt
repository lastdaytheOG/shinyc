package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Test

class LookAlikeDigitsTest {

    private val zero = "০"
    private val eight = "৪"

    @Test
    fun aDateAndASectionNumberArePutRight() {
        assertEquals("08/08/2025", LookAlikeDigits.repaired("$zero$eight/08/2025"))
        assertEquals("01/11/2024 UPI 488742918625", LookAlikeDigits.repaired("${zero}1/11/2024 UPI 488742918625"))
        assertEquals("8.2.1 Availability", LookAlikeDigits.repaired("$eight.2.1 Availability"))
        assertEquals("Rs 1008", LookAlikeDigits.repaired("Rs 1${zero}0$eight"))
    }

    @Test
    fun oneThatTouchesALetterIsAMisreadLetterAndStays() {
        val latin = "cost${eight}ing up${zero}n"
        assertEquals(latin, LookAlikeDigits.repaired(latin))
        val hindi = "क$eight ख$zero"
        assertEquals(hindi, LookAlikeDigits.repaired(hindi))
    }

    @Test
    fun realHindiDigitsAndTheOtherBengaliDigitsStay() {
        val devanagari = "दिनांक २०२५ ८ ०"
        assertEquals(devanagari, LookAlikeDigits.repaired(devanagari))
        val rareBengali = "১ ২ ৫ ৭ ৮ ৯"
        assertEquals(rareBengali, LookAlikeDigits.repaired(rareBengali))
    }

    @Test
    fun textWithoutThemIsHandedBackAsItIs() {
        val text = "Total Rs 450\n08/08/2025"
        assertEquals(text, LookAlikeDigits.repaired(text))
    }
}
