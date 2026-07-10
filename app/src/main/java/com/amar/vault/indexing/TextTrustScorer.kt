package com.amar.vault.indexing

/**
 * Sprint 4A — per-page text-trust gate for PDF extraction.
 *
 * Pure Kotlin, zero Android/PDF/OCR dependencies, single O(n) pass over the page
 * text. Decides whether `PDFTextStripper` output can be trusted for indexing or
 * whether the page must fall back to the OCR ensemble.
 *
 * What it detects (and why):
 *  - BLANK / TOO_FEW_WORDS         → scanned or image-only pages (no text layer);
 *  - REPLACEMENT_CHARS (U+FFFD)    → damaged ToUnicode maps;
 *  - PRIVATE_USE_CHARS             → legacy non-Unicode Hindi fonts (KrutiDev,
 *                                    Chanakya map glyphs into the PUA);
 *  - CONTROL_CHARS                 → binary garbage extracted as text;
 *  - LOW_LETTER_RATIO              → symbol soup / generic mojibake;
 *  - MIXED_SCRIPT_WORDS            → per-word script interleaving ("कpाs
 *                                    Trानsfoर्मer") — corruption that per-char
 *                                    histograms miss because every char is a
 *                                    valid letter of *some* script;
 *  - DEVANAGARI_ORDER              → phonotactically impossible mark sequences
 *                                    (dependent vowel at word start, double vowel
 *                                    signs, orphan virama) — the signature of
 *                                    visually-ordered extraction from shaped glyphs.
 *
 * What it deliberately does NOT do: inspect fonts, parse glyph maps, or run OCR.
 * The extracted string itself is the evidence; anything structural is the render
 * path's job.
 *
 * Latin-only pages can only fail on blank/replacement/control/letter-ratio rules,
 * so healthy English PDFs always pass — the gate cannot slow them down beyond
 * this single scan (microseconds per page).
 */
object TextTrustScorer {

    enum class TrustFailure {
        BLANK,
        TOO_FEW_WORDS,
        REPLACEMENT_CHARS,
        PRIVATE_USE_CHARS,
        CONTROL_CHARS,
        LOW_LETTER_RATIO,
        MIXED_SCRIPT_WORDS,
        DEVANAGARI_ORDER,
    }

    data class TextTrustResult(
        val trusted: Boolean,
        val score: Float,
        val reasons: List<TrustFailure>,
    )

    // ── Thresholds (empirically conservative; tune only with benchmark evidence) ──
    private const val MIN_WORDS = 3                    // fewer ⇒ page effectively textless
    private const val REPLACEMENT_MAX_RATIO = 0.005    // >0.5% U+FFFD ⇒ broken map
    private const val REPLACEMENT_MIN_COUNT = 3        // tolerate 1–2 stray FFFD on large pages
    private const val PUA_MAX_RATIO = 0.01             // >1% PUA ⇒ legacy font encoding
    private const val CONTROL_MAX_RATIO = 0.01
    private const val MIN_LETTER_DIGIT_RATIO = 0.50    // of non-whitespace chars
    private const val MIXED_WORD_MAX_RATIO = 0.05      // >5% of words script-interleaved
    private const val MIXED_WORD_MIN_COUNT = 2
    private const val DEV_VIOLATION_MAX_RATIO = 0.05   // violations per Devanagari mark
    private const val DEV_VIOLATION_MIN_COUNT = 2
    private const val DEV_MIN_CHARS = 10               // don't judge a page on 2 Devanagari chars

    /**
     * Score one page of extracted text. Input should already be NFC-normalized
     * (canonical composition), so decomposed-but-valid sequences don't read as
     * ordering violations.
     */
    fun score(text: String): TextTrustResult {
        val reasons = mutableListOf<TrustFailure>()

        // ── Single pass: character-class histogram + word/script state machines ──
        var nonWs = 0
        var letters = 0
        var digits = 0
        var replacement = 0
        var pua = 0
        var control = 0
        var devChars = 0          // any char in the Devanagari block
        var devMarks = 0          // combining marks/vowel signs/virama/bindu
        var devViolations = 0
        var words = 0
        var mixedWords = 0

        // Per-word state
        var inWord = false
        var wordScript = Script.NONE       // script of the previous LETTER in this word
        var wordTransitions = 0

        // Devanagari phonotactic state: class of the previous char (reset at boundaries)
        var prevDev = DevClass.BOUNDARY

        for (ch in text) {
            val isWs = ch.isWhitespace()
            if (isWs) {
                if (inWord) {
                    words++
                    if (wordTransitions >= 2) mixedWords++
                }
                inWord = false; wordScript = Script.NONE; wordTransitions = 0
                prevDev = DevClass.BOUNDARY
                continue
            }
            nonWs++

            val code = ch.code
            when {
                code == 0xFFFD -> replacement++
                code in 0xE000..0xF8FF -> pua++
                ch.isISOControl() && ch != '\t' -> control++
            }

            val isLetter = ch.isLetter()
            val isDigit = ch.isDigit()
            if (isLetter) letters++
            if (isDigit) digits++

            // Word-level script interleaving (letters only; digits/punct are neutral).
            if (isLetter) {
                val script = when (code) {
                    in 0x0900..0x097F -> Script.DEVANAGARI
                    in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F -> Script.LATIN
                    else -> Script.OTHER
                }
                if (!inWord) {
                    inWord = true
                } else if (wordScript != Script.NONE && script != wordScript) {
                    wordTransitions++
                }
                wordScript = script
            } else if (!inWord && (isDigit || !isWs)) {
                inWord = true // numeric/symbol-led tokens still count as words
            }

            // Devanagari phonotactics.
            if (code in 0x0900..0x097F) {
                devChars++
                val cls = devClassOf(code)
                when (cls) {
                    DevClass.VOWEL_SIGN -> {
                        devMarks++
                        // Valid only directly after a consonant (or nukta'd consonant).
                        if (prevDev != DevClass.CONSONANT && prevDev != DevClass.NUKTA) devViolations++
                    }
                    DevClass.VIRAMA -> {
                        devMarks++
                        if (prevDev != DevClass.CONSONANT && prevDev != DevClass.NUKTA) devViolations++
                    }
                    DevClass.BINDU -> {
                        devMarks++
                        // Anusvara/chandrabindu/visarga ride on a full syllable:
                        // consonant, independent vowel, or a vowel sign.
                        if (prevDev != DevClass.CONSONANT && prevDev != DevClass.INDEPENDENT_VOWEL &&
                            prevDev != DevClass.VOWEL_SIGN) devViolations++
                    }
                    DevClass.NUKTA -> {
                        devMarks++
                        if (prevDev != DevClass.CONSONANT) devViolations++
                    }
                    else -> { /* consonants, independent vowels, digits, danda: always legal */ }
                }
                prevDev = cls
            } else {
                // A non-Devanagari char breaks any pending syllable.
                prevDev = DevClass.BOUNDARY
            }
        }
        if (inWord) {
            words++
            if (wordTransitions >= 2) mixedWords++
        }

        // ── Rules ─────────────────────────────────────────────────────────────
        if (nonWs == 0) {
            return TextTrustResult(trusted = false, score = 0f, reasons = listOf(TrustFailure.BLANK))
        }
        if (words < MIN_WORDS) reasons.add(TrustFailure.TOO_FEW_WORDS)

        val replacementRatio = replacement.toDouble() / nonWs
        if (replacement >= REPLACEMENT_MIN_COUNT && replacementRatio > REPLACEMENT_MAX_RATIO) {
            reasons.add(TrustFailure.REPLACEMENT_CHARS)
        }
        if (pua.toDouble() / nonWs > PUA_MAX_RATIO) reasons.add(TrustFailure.PRIVATE_USE_CHARS)
        if (control.toDouble() / nonWs > CONTROL_MAX_RATIO) reasons.add(TrustFailure.CONTROL_CHARS)
        if ((letters + digits).toDouble() / nonWs < MIN_LETTER_DIGIT_RATIO) {
            reasons.add(TrustFailure.LOW_LETTER_RATIO)
        }
        if (mixedWords >= MIXED_WORD_MIN_COUNT && words > 0 &&
            mixedWords.toDouble() / words > MIXED_WORD_MAX_RATIO) {
            reasons.add(TrustFailure.MIXED_SCRIPT_WORDS)
        }
        if (devChars >= DEV_MIN_CHARS && devMarks > 0 &&
            devViolations >= DEV_VIOLATION_MIN_COUNT &&
            devViolations.toDouble() / devMarks > DEV_VIOLATION_MAX_RATIO) {
            reasons.add(TrustFailure.DEVANAGARI_ORDER)
        }

        // Score: each independent failure burns a share of confidence; purely
        // informational — the trusted flag is what gates OCR.
        val score = (1f - 0.25f * reasons.size).coerceIn(0f, 1f)
        return TextTrustResult(trusted = reasons.isEmpty(), score = score, reasons = reasons)
    }

    private enum class Script { NONE, LATIN, DEVANAGARI, OTHER }

    /** Character classes that matter for Devanagari mark-ordering legality. */
    private enum class DevClass { BOUNDARY, CONSONANT, INDEPENDENT_VOWEL, VOWEL_SIGN, VIRAMA, BINDU, NUKTA, OTHER_DEV }

    private fun devClassOf(code: Int): DevClass = when (code) {
        in 0x0915..0x0939, in 0x0958..0x095F, in 0x0978..0x097F -> DevClass.CONSONANT
        in 0x0904..0x0914, 0x0960, 0x0961, 0x0972 -> DevClass.INDEPENDENT_VOWEL
        0x093A, 0x093B, in 0x093E..0x094C, 0x094E, 0x094F, 0x0962, 0x0963 -> DevClass.VOWEL_SIGN
        0x094D -> DevClass.VIRAMA
        0x0900, 0x0901, 0x0902, 0x0903 -> DevClass.BINDU
        0x093C -> DevClass.NUKTA
        else -> DevClass.OTHER_DEV // digits, danda, om, avagraha… always legal
    }
}
