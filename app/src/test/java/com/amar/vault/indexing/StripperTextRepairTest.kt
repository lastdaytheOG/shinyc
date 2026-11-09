package com.amar.vault.indexing

import com.amar.vault.UnicodeText
import com.amar.vault.retrieval.KeywordWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StripperTextRepairTest {

    private val softHyphen = Char(0x00AD)
    private val zeroWidthSpace = Char(0x200B)
    private val noBreakSpace = Char(0x00A0)
    private val zwj = Char(0x200D)
    private val zwnj = Char(0x200C)

    @Test
    fun textWithNothingToRepairIsReturnedItself() {
        val text = "Invoice 2024-26 paid in full.\nनमस्ते दुनिया\tdone"
        assertSame(text, repairStripperText(text))
    }

    @Test
    fun softHyphenInsideAWordIsDropped() {
        assertEquals("document", repairStripperText("docu${softHyphen}ment"))
    }

    @Test
    fun softHyphenAtTheEndOfALineJoinsTheWordAcrossTheBreak() {
        assertEquals("the document is here", repairStripperText("the docu$softHyphen\nment is here"))
        assertEquals("the document is here", repairStripperText("the docu$softHyphen\r\nment is here"))
        assertEquals("line one\nline two", repairStripperText("line ${softHyphen}one\nline two"))
    }

    @Test
    fun softHyphenAtTheEndOfTheTextIsDropped() {
        assertEquals("end", repairStripperText("end$softHyphen"))
        assertEquals("end\r", repairStripperText("end$softHyphen\r"))
    }

    @Test
    fun zeroWidthSpaceIsDropped() {
        assertEquals("foobar", repairStripperText("foo${zeroWidthSpace}bar"))
    }

    @Test
    fun noBreakSpaceBecomesASpaceAndKeepsTheWordsApart() {
        val repaired = repairStripperText("Rs${noBreakSpace}500 due")
        assertEquals("Rs 500 due", repaired)
        assertEquals("rs 500 due", KeywordWords.of(repaired))
    }

    @Test
    fun latinLigaturesBecomeTheirLetters() {
        val text = listOf(0xFB00, 0xFB01, 0xFB02, 0xFB03, 0xFB04, 0xFB05, 0xFB06).joinToString(" ") { Char(it).toString() }
        assertEquals("ff fi fl ffi ffl st st", repairStripperText(text))
        assertEquals("finance", repairStripperText("${Char(0xFB01)}nance"))
    }

    @Test
    fun aRepairedWordIsTheWordSearchLooksFor() {
        val stored = repairStripperText("docu${softHyphen}ment ${Char(0xFB01)}nance")
        assertEquals("document finance", KeywordWords.of(stored))
    }

    @Test
    fun controlCharactersStaySoTheGateStillSeesABrokenTextLayer() {
        val c0 = "dif${Char(2)}cult"
        val c1 = "don${Char(0x92)}t"
        assertSame(c0, repairStripperText(c0))
        assertSame(c1, repairStripperText(c1))
        val garbage = "page " + (1..40).joinToString(" ") { "${Char(3)}${Char(4)}${Char(5)}word" }
        val result = TextTrustScorer.score(UnicodeText.nfc(repairStripperText(garbage).trim()))
        assertTrue(TextTrustScorer.TrustFailure.CONTROL_CHARS in result.reasons)
    }

    @Test
    fun devanagariWithItsJoinersPassesThroughUntouched() {
        val text = "क्${zwj}ष क्${zwnj}ष हिन्दी"
        assertSame(text, repairStripperText(text))
    }

    @Test
    fun aPageTrustedBeforeRepairIsTrustedAfter() {
        val page = (1..30).joinToString(" ") { "docu${softHyphen}ment${noBreakSpace}${Char(0xFB01)}le$zeroWidthSpace$it" }
        assertTrue(TextTrustScorer.score(UnicodeText.nfc(page)).trusted)
        assertTrue(TextTrustScorer.score(UnicodeText.nfc(repairStripperText(page))).trusted)
        assertFalse(repairStripperText(page).any { it == softHyphen || it == zeroWidthSpace || it == noBreakSpace })
    }
}
