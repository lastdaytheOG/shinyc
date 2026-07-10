package com.amar.vault.indexing

import com.amar.vault.indexing.TextTrustScorer.TrustFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 4A — pure-JVM tests for the PDF text-trust gate.
 * No Android, no native libs: these must run green on any host.
 */
class TextTrustScorerTest {

    // ── Trusted pages ─────────────────────────────────────────────────────────

    @Test
    fun `clean English page is trusted`() {
        val text = """
            This agreement is entered into on the twelfth day of March.
            The parties agree to the terms and conditions described below,
            including payment schedules, delivery timelines and warranties.
        """.trimIndent()
        val r = TextTrustScorer.score(text)
        assertTrue("expected trusted, got ${r.reasons}", r.trusted)
        assertEquals(1f, r.score)
    }

    @Test
    fun `clean Hindi page is trusted`() {
        // Phonotactically valid Devanagari: matras follow consonants, anusvara
        // rides on syllables (में = म + े + ं), conjuncts use virama after consonants.
        val text = "कपास के अध्ययन में यह पाया गया कि ट्रांसफार्मर का प्रयोग क्या है। " +
            "भारत में हिन्दी बोलने वाले लोग बहुत हैं और वे अपनी भाषा से प्रेम करते हैं।"
        val r = TextTrustScorer.score(text)
        assertTrue("expected trusted, got ${r.reasons}", r.trusted)
    }

    @Test
    fun `legitimate mixed-script page is trusted`() {
        // Scripts switch at WORD boundaries only — normal Hinglish/technical Hindi.
        val text = "कपास Transformer अध्ययन के लिए मॉडल का उपयोग किया गया। " +
            "यह research बहुत useful है और results अच्छे हैं। और भी data चाहिए।"
        val r = TextTrustScorer.score(text)
        assertTrue("expected trusted, got ${r.reasons}", r.trusted)
    }

    @Test
    fun `numeric table page is trusted`() {
        val text = "2021 4500 12.5 2022 4700 13.1 2023 5100 14.2 totals 14300 39.8"
        val r = TextTrustScorer.score(text)
        assertTrue("expected trusted, got ${r.reasons}", r.trusted)
    }

    // ── Untrusted pages ───────────────────────────────────────────────────────

    @Test
    fun `blank page fails with BLANK`() {
        val r = TextTrustScorer.score("   \n\n \t ")
        assertFalse(r.trusted)
        assertEquals(listOf(TrustFailure.BLANK), r.reasons)
        assertEquals(0f, r.score)
    }

    @Test
    fun `near-empty page fails with TOO_FEW_WORDS`() {
        val r = TextTrustScorer.score("Chapter 1")
        assertFalse(r.trusted)
        assertTrue(TrustFailure.TOO_FEW_WORDS in r.reasons)
    }

    @Test
    fun `replacement characters fail`() {
        val body = "the quick brown fox jumps over the lazy dog ".repeat(3)
        val r = TextTrustScorer.score(body + "��������")
        assertFalse(r.trusted)
        assertTrue(TrustFailure.REPLACEMENT_CHARS in r.reasons)
    }

    @Test
    fun `private use area characters fail (legacy Hindi fonts)`() {
        // KrutiDev-style extraction: glyph codes land in the PUA.
        val pua = (0xE001..0xE040).map { it.toChar() }.joinToString("")
        val r = TextTrustScorer.score("heading $pua more $pua text $pua here")
        assertFalse(r.trusted)
        assertTrue(TrustFailure.PRIVATE_USE_CHARS in r.reasons)
    }

    @Test
    fun `control character soup fails`() {
        val r = TextTrustScorer.score("some words here " + "\u0001\u0002\u0003\u0004\u0005".repeat(4))
        assertFalse(r.trusted)
        assertTrue(TrustFailure.CONTROL_CHARS in r.reasons)
    }

    @Test
    fun `symbol soup fails with LOW_LETTER_RATIO`() {
        val r = TextTrustScorer.score("@@ ## %% ^^ && ** (( )) ++ == ~~ ;; :: ..")
        assertFalse(r.trusted)
        assertTrue(TrustFailure.LOW_LETTER_RATIO in r.reasons)
    }

    @Test
    fun `intra-word script interleaving fails (corrupted extraction)`() {
        // "कpाs Trानsfoर्मer" — Latin and Devanagari letters interleaved INSIDE words.
        val corrupt = "कpाs Trानsfoर्मer कpाs Trानsfoर्मer कpाs Trानsfoर्मer यह पाठ है"
        val r = TextTrustScorer.score(corrupt)
        assertFalse(r.trusted)
        assertTrue("got ${r.reasons}", TrustFailure.MIXED_SCRIPT_WORDS in r.reasons)
    }

    @Test
    fun `visually ordered Devanagari fails (matra before consonant)`() {
        // Visual-order extraction emits the i-matra BEFORE its consonant: "हिन्दी"
        // becomes "िहन्दी", "किताब" becomes "िकताब" — impossible sequences in
        // logical-order Unicode.
        val visual = "िहन्दी िकताब िलखना िमलना िदन िवषय िनयम िशक्षा िवज्ञान िहत"
        val r = TextTrustScorer.score(visual)
        assertFalse(r.trusted)
        assertTrue("got ${r.reasons}", TrustFailure.DEVANAGARI_ORDER in r.reasons)
    }

    @Test
    fun `double vowel signs fail`() {
        val broken = "कीी मााला तेेरा सोोना कीी मााला तेेरा सोोना कुुछ भीी"
        val r = TextTrustScorer.score(broken)
        assertFalse(r.trusted)
        assertTrue("got ${r.reasons}", TrustFailure.DEVANAGARI_ORDER in r.reasons)
    }

    // ── Guards against over-triggering ────────────────────────────────────────

    @Test
    fun `single stray replacement char on a large page stays trusted`() {
        val body = "perfectly ordinary sentence with plenty of readable words ".repeat(10)
        val r = TextTrustScorer.score(body + "�")
        assertTrue("expected trusted, got ${r.reasons}", r.trusted)
    }

    @Test
    fun `one hyphenated loanword does not trip mixed-script rule`() {
        // A single mixed token (e.g. "COVID-19का") must not fail a whole page.
        val text = "महामारी के दौरान COVID-19का प्रभाव बहुत गहरा था और लोग घरों में रहे। " +
            "सरकार ने कई कदम उठाये और टीकाकरण अभियान चलाया गया जिससे राहत मिली।"
        val r = TextTrustScorer.score(text)
        assertTrue("expected trusted, got ${r.reasons}", r.trusted)
    }
}
