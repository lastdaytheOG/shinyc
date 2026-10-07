package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking a row's text apart as it was stored before version 14: the page, the tags glued on
 * as a last line, and what a picture's QR codes held.
 *
 * Each case that the hand-written cuts got wrong carries a control showing them getting it
 * wrong: about thirty places cut at the FIRST line starting with a bracket, and the one
 * careful splitter cut at the LAST one whether or not it was a line of tags.
 */
class FormerStoredTextTest {

    private val nl = 10.toChar()

    /** What about thirty places in the app did. */
    private fun cutAtFirstBracketLine(stored: String) = stored.substringBefore("$nl[")

    /** What the careful splitter did (`StoredText`, removed with version 14). */
    private fun cutAtLastBracketLine(stored: String): String {
        if (!stored.endsWith("]")) return stored
        val tagLine = stored.lastIndexOf("$nl[")
        return if (tagLine < 0) stored else stored.substring(0, tagLine)
    }

    @Test
    fun aPageAndItsTagsComeApart() {
        val parts = FormerStoredText.split("total turnover in the previous year$nl[pdf document invoice billing receipt]")
        assertEquals("total turnover in the previous year", parts.page)
        assertEquals("pdf document invoice billing receipt", parts.tags)
        assertTrue(parts.qrPayloads.isEmpty())
    }

    @Test
    fun aRowWithNoTagsIsAllPage() {
        for (text in listOf("18:09 Now playing ELLA CIAO", "", "plain text [with a bracket]", "ends in a bracket]")) {
            val parts = FormerStoredText.split(text)
            assertEquals(text, parts.page)
            assertEquals("", parts.tags)
        }
    }

    @Test
    fun aPageWithALineStartingWithABracketIsKeptWhole() {
        // From a real page of the Finance Act, 2026.
        val page = "THE FINANCE ACT, 2026$nl(NO. 4 OF 2026)$nl[30th March, 2026]${nl}An Act to give effect to the financial proposals"
        val stored = "$page$nl[pdf document financial payment monetary tax government]"

        val parts = FormerStoredText.split(stored)
        assertEquals(page, parts.page)
        assertEquals("pdf document financial payment monetary tax government", parts.tags)

        // The control: cut at the first bracket line, the page loses everything after its date.
        assertEquals("THE FINANCE ACT, 2026$nl(NO. 4 OF 2026)", cutAtFirstBracketLine(stored))
        assertNotEquals(page, cutAtFirstBracketLine(stored))
    }

    @Test
    fun aPageThatEndsWithABracketedLineAndHasNoTagsLosesNothing() {
        // A picture the tag rules found nothing in has no tag line; this is all its text.
        val stored = "Delete this conversation?$nl[Cancel] [Delete]"
        val parts = FormerStoredText.split(stored)
        assertEquals(stored, parts.page)
        assertEquals("", parts.tags)

        // The control: the careful splitter took the buttons for tags.
        assertEquals("Delete this conversation?", cutAtLastBracketLine(stored))
    }

    @Test
    fun aBracketedLineOfOrdinaryWordsIsNotATagLine() {
        // Only words the indexers wrote make a tag line, in the lower case they wrote them in.
        for (stored in listOf("see$nl[excluding dividend income]", "see$nl[Report]", "see$nl[pdf document attached]")) {
            assertEquals(stored, FormerStoredText.split(stored).page)
        }
    }

    @Test
    fun aRowThatIsOnlyTagsHasAnEmptyPage() {
        // A document that could not be read was stored by its name alone: no text, its tags.
        val parts = FormerStoredText.split("$nl[pdf document]")
        assertEquals("", parts.page)
        assertEquals("pdf document", parts.tags)
    }

    @Test
    fun whatAQrCodeHeldComesOutOfTheTags() {
        val payload = "upi://pay?pa=ravi@okaxis&pn=Ravi Kumar&am=250"
        val parts = FormerStoredText.split("Scan to pay${nl}Ravi Kumar ravi@okaxis upi payment qr scanner$nl[receipt payment bill invoice qr_data:$payload]")
        assertEquals("Scan to pay${nl}Ravi Kumar ravi@okaxis upi payment qr scanner", parts.page)
        assertEquals("receipt payment bill invoice", parts.tags)
        assertEquals("the payee's name has a space in it, and is kept", listOf(payload), parts.qrPayloads)
    }

    @Test
    fun aPictureWithACodeAndNoOtherTag() {
        val parts = FormerStoredText.split("https://example.org/menu qr scanner barcode$nl[qr_data:https://example.org/menu]")
        assertEquals("https://example.org/menu qr scanner barcode", parts.page)
        assertEquals("", parts.tags)
        assertEquals(listOf("https://example.org/menu"), parts.qrPayloads)
    }

    @Test
    fun twoCodesOnOnePicture() {
        val parts = FormerStoredText.split("two codes$nl[qr code scanner barcode qr_data:https://a.example qr_data:WIFI:T:WPA;S:Home Net;P:secret;;]")
        assertEquals("qr code scanner barcode", parts.tags)
        assertEquals(listOf("https://a.example", "WIFI:T:WPA;S:Home Net;P:secret;;"), parts.qrPayloads)
    }

    @Test
    fun aCodeWhoseContentsHaveALineStartingWithABracket() {
        // A contact card is several lines; one of them here starts with a bracket.
        val card = "BEGIN:VCARD${nl}FN:Ravi$nl[work] 98765${nl}END:VCARD"
        val parts = FormerStoredText.split("Ravi's card$nl[contact phone number call qr_data:$card]")
        assertEquals("Ravi's card", parts.page)
        assertEquals("contact phone number call", parts.tags)
        assertEquals(listOf(card), parts.qrPayloads)
    }

    @Test
    fun qrContentsAreKeptAsOneValueAndReadBackAsTheyWere() {
        val several = listOf("upi://pay?pa=a@b&pn=A B", "BEGIN:VCARD${nl}FN:Ravi${nl}END:VCARD")
        assertEquals(several, QrPayloads.split(QrPayloads.join(several)))
        assertNull("a picture without codes stores nothing", QrPayloads.join(emptyList()))
        assertTrue(QrPayloads.split(null).isEmpty())
    }
}
