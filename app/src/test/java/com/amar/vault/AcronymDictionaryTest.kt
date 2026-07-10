package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 4B — pure-JVM tests for the deterministic acronym layer.
 * No Android, no native libs, no I/O: green on any host.
 */
class AcronymDictionaryTest {

    // ── expand: preserves original, appends expansion ──────────────────────────

    @Test
    fun `single acronym expands and keeps original token`() {
        assertEquals("AI Artificial Intelligence", AcronymDictionary.expand("AI"))
        assertEquals("OCR Optical Character Recognition", AcronymDictionary.expand("OCR"))
        assertEquals("PDF Portable Document Format", AcronymDictionary.expand("PDF"))
    }

    @Test
    fun `expansion is case-insensitive but preserves the typed original`() {
        assertEquals("ai Artificial Intelligence", AcronymDictionary.expand("ai"))
        assertEquals("Ocr Optical Character Recognition", AcronymDictionary.expand("Ocr"))
    }

    @Test
    fun `multiple acronyms all expand, original phrase stays contiguous`() {
        assertEquals(
            "ocr pdf Optical Character Recognition Portable Document Format",
            AcronymDictionary.expand("ocr pdf"),
        )
    }

    @Test
    fun `acronym mixed with normal words expands only the acronym`() {
        assertEquals("machine ML Machine Learning", AcronymDictionary.expand("machine ML"))
    }

    // ── expand: no-op for non-acronym queries (zero drift guarantee) ───────────

    @Test
    fun `query without acronym is returned as the exact same string instance`() {
        val q = "amazon receipt june"
        // Referential identity — proves downstream sees a byte-identical query.
        assertSame(q, AcronymDictionary.expand(q))
    }

    @Test
    fun `hindi query is untouched`() {
        val q = "दिल्ली यात्रा टिकट"
        assertSame(q, AcronymDictionary.expand(q))
    }

    @Test
    fun `blank query is returned unchanged`() {
        assertSame("", AcronymDictionary.expand(""))
        assertSame("   ", AcronymDictionary.expand("   "))
    }

    // ── isKnownAcronym: gate predicate ─────────────────────────────────────────

    @Test
    fun `known acronyms are recognized regardless of case`() {
        assertTrue(AcronymDictionary.isKnownAcronym("AI"))
        assertTrue(AcronymDictionary.isKnownAcronym("ml"))
        assertTrue(AcronymDictionary.isKnownAcronym(" LLM "))
    }

    @Test
    fun `ambiguous everyday words are deliberately NOT acronyms`() {
        // These must never expand — doing so would corrupt normal queries.
        for (w in listOf("it", "us", "or", "in", "ip", "pin", "mac", "pos", "pr", "and")) {
            assertFalse("'$w' must not be a known acronym", AcronymDictionary.isKnownAcronym(w))
            assertSame(w, AcronymDictionary.expand(w))
        }
    }

    @Test
    fun `junk short query is not an acronym`() {
        assertFalse(AcronymDictionary.isKnownAcronym("xq"))
        assertFalse(AcronymDictionary.isKnownAcronym(""))
    }

    // ── expansionOf + coverage ─────────────────────────────────────────────────

    @Test
    fun `expansionOf resolves case-insensitively and returns null for unknown`() {
        assertEquals("Large Language Model", AcronymDictionary.expansionOf("llm"))
        assertNull(AcronymDictionary.expansionOf("zzz"))
    }

    // ── analyze: structured ExpandedQuery (Sprint 4B.1) ────────────────────────

    @Test
    fun `analyze of AI matches the structured spec example`() {
        val e = AcronymDictionary.analyze("AI")
        assertEquals("AI", e.originalQuery)
        assertEquals("ai", e.normalizedQuery)
        assertEquals(listOf("AI", "Artificial Intelligence"), e.expandedTerms)
        assertEquals(setOf("ai", "artificial", "intelligence"), e.gateTerms)
        assertTrue(e.containsKnownAcronym)
    }

    @Test
    fun `analyze joined equals the 4B expand string`() {
        // Representation change only: joined() must reproduce the old concatenation.
        for (q in listOf("AI", "ocr pdf", "machine ML", "amazon receipt", "दिल्ली यात्रा", "")) {
            assertEquals("joined mismatch for '$q'", AcronymDictionary.expand(q),
                AcronymDictionary.analyze(q).joined())
        }
    }

    @Test
    fun `analyze of non-acronym query has no expansion and single original term`() {
        val e = AcronymDictionary.analyze("amazon receipt")
        assertFalse(e.containsKnownAcronym)
        assertEquals(listOf("amazon receipt"), e.expandedTerms)
        assertEquals(setOf("amazon", "receipt"), e.gateTerms)
    }

    @Test
    fun `acceptance set is all covered`() {
        for (a in listOf("AI", "ML", "LLM", "NLP", "OCR", "PDF", "RAG")) {
            assertTrue("$a missing from dictionary", AcronymDictionary.isKnownAcronym(a))
        }
        // Sanity on curated size — enough coverage, not a runaway table.
        assertTrue("dictionary should be curated (>=100)", AcronymDictionary.keys.size >= 100)
    }
}
