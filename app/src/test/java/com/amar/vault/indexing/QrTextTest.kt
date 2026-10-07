package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a picture's codes add to its text: what they say, and no words of the app's own. */
class QrTextTest {

    @Test
    fun aPaymentCodeSaysWhoIsPaidAndAtWhatAddress() {
        assertEquals("Ravi Kumar ravi@okaxis", QrText.readable(listOf("upi://pay?pa=ravi@okaxis&pn=Ravi%20Kumar&am=250")))
        assertEquals("Ravi Kumar ravi@okaxis", QrText.readable(listOf("upi://pay?pa=ravi@okaxis&pn=Ravi Kumar")))
        assertEquals("only an address", "shop@upi", QrText.readable(listOf("UPI://pay?pa=shop@upi")))
    }

    @Test
    fun anyOtherCodeSaysWhatItHolds() {
        assertEquals("https://example.org/menu", QrText.readable(listOf("https://example.org/menu")))
        assertEquals("https://a.example 8901030865278", QrText.readable(listOf("https://a.example", "8901030865278")))
        assertEquals("", QrText.readable(emptyList()))
    }

    @Test
    fun theWordsThatUsedToBeAddedAreTakenOffTheLastLine() {
        assertEquals("Scan to pay\nRavi Kumar ravi@okaxis", QrText.withoutFormerWords("Scan to pay\nRavi Kumar ravi@okaxis upi payment qr scanner"))
        assertEquals("https://example.org/menu", QrText.withoutFormerWords("https://example.org/menu qr scanner barcode"))
        assertEquals("two codes: A a@b and a link", "A a@b https://x.example",
            QrText.withoutFormerWords("A a@b upi payment qr scanner https://x.example qr scanner barcode"))
        assertEquals("a payment code with nothing readable in it left only the words", "Scan here", QrText.withoutFormerWords("Scan here\nupi payment qr scanner"))
    }

    @Test
    fun textThatNeverHadThemIsNotTouched() {
        for (text in listOf("Scan to pay\nRavi Kumar ravi@okaxis", "", "a line \n  indented last line  ", "qr scanner barcode apps\nare many")) {
            assertEquals(text, QrText.withoutFormerWords(text))
        }
    }
}
