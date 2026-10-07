package com.amar.vault.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [FuzzyMatcher] must give exactly the answer the fuzzy lane computed before it existed. The
 * old computation is kept here, verbatim, as the oracle.
 */
class FuzzyMatcherTest {

    private val whitespace = Regex("\\s+")

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0; if (a.isEmpty()) return b.length; if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }; var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev; prev = curr; curr = t
        }
        return prev[b.length]
    }

    /** The lane's former per-item predicate, unchanged. */
    private fun oracle(words: List<String>, text: String): Boolean {
        val docWords = text.split(whitespace).filter { it.length >= 3 }
        return words.any { qw ->
            val md = if (qw.length <= 4) 1 else 2
            docWords.any { dw -> kotlin.math.abs(dw.length - qw.length) <= md && levenshtein(qw, dw) <= md }
        }
    }

    @Test
    fun matchesTyposWithinToleranceOnly() {
        assertTrue(FuzzyMatcher(listOf("electricity")).matches("your electricty bill is due"))
        assertTrue(FuzzyMatcher(listOf("bill")).matches("pay the bil1 now"))        // 1 edit, short word
        assertFalse(FuzzyMatcher(listOf("bill")).matches("pay the ball1 now"))      // 2 edits, short word
        assertTrue(FuzzyMatcher(listOf("passport")).matches("pasport renewal"))
        assertFalse(FuzzyMatcher(listOf("passport")).matches("transport renewal"))
        assertTrue(FuzzyMatcher(listOf("बिजली")).matches("बिजलि का बिल"))
        assertFalse(FuzzyMatcher(listOf("invoice")).matches("in vo ice"))           // words under 3 chars never count
        assertFalse(FuzzyMatcher(listOf("invoice")).matches(""))
    }

    @Test
    fun aRootStandsInForAWordMadeFromItWhenAskedTo() {
        fun roots(word: String) = FuzzyMatcher(listOf(word), roots = true)
        assertTrue(roots("booking").matches("the book is here"))
        assertTrue(roots("installation").matches("install the app"))
        assertTrue(roots("planned").matches("that was the plan."))     // a doubled letter, a full stop after the word
        assertTrue(roots("executing").matches("will it execute"))      // a dropped e
        assertFalse(roots("terminal").matches("first mid term"))       // "inal" is no ending
        assertFalse(roots("notebook").matches("leave a note"))
        assertFalse(roots("paying").matches("pay now"))                // three letters are the start of too much
        assertFalse(roots("booking").matches("the boom is here"))
        assertEquals("book", roots("booking").firstMatch("the book is here"))
        assertFalse("only when asked to", FuzzyMatcher(listOf("booking")).matches("the book is here"))
    }

    @Test
    fun rootsLeaveTheNearSpellingsAsTheyWere() {
        val random = Random(20261007)
        val alphabet = "abdegilnoprst".toList()
        fun word(maxLen: Int) = String(CharArray(1 + random.nextInt(maxLen)) { alphabet[random.nextInt(alphabet.size)] })
        repeat(2_000) {
            val words = List(1 + random.nextInt(3)) { word(9) }
            val text = List(random.nextInt(12)) { word(9) }.joinToString(" ")
            // Whatever is accepted without roots is accepted with them.
            if (FuzzyMatcher(words).matches(text)) assertTrue("$words in '$text'", FuzzyMatcher(words, roots = true).matches(text))
        }
    }

    @Test
    fun agreesWithTheFormerComputationOnRandomText() {
        val random = Random(20261004)
        val alphabet = "abcdeilnorst".toList() + listOf('ब', 'ि', 'ज', 'ल', 'ी', 'क', 'ा')
        // ASCII whitespace, plus characters the JVM and Android classify differently under \s.
        val separators = listOf(" ", "  ", "\t", "\n", "\r\n", "\u000B", " ", "　", " ")
        fun word(maxLen: Int) = String(CharArray(1 + random.nextInt(maxLen)) { alphabet[random.nextInt(alphabet.size)] })

        var positives = 0
        repeat(20_000) {
            val words = List(1 + random.nextInt(3)) { word(9) }.filter { it.length >= 3 }
            val text = buildString {
                repeat(random.nextInt(14)) {
                    // Sometimes plant a near-copy of a query word so both outcomes are well covered.
                    val base = if (words.isNotEmpty() && random.nextInt(4) == 0) {
                        val chars = words[random.nextInt(words.size)].toMutableList()
                        repeat(random.nextInt(4)) {
                            when (random.nextInt(3)) {
                                0 -> if (chars.isNotEmpty()) chars.removeAt(random.nextInt(chars.size))
                                1 -> chars.add(random.nextInt(chars.size + 1), alphabet[random.nextInt(alphabet.size)])
                                else -> if (chars.isNotEmpty()) chars[random.nextInt(chars.size)] = alphabet[random.nextInt(alphabet.size)]
                            }
                        }
                        String(chars.toCharArray())
                    } else word(11)
                    append(separators[random.nextInt(separators.size)]).append(base)
                }
                if (random.nextBoolean()) append(separators[random.nextInt(separators.size)])
            }
            if (words.isEmpty()) return@repeat
            val expected = oracle(words, text)
            if (expected) positives++
            assertEquals("words=$words text=${text.map { it.code }}", expected, FuzzyMatcher(words).matches(text))
        }
        // Guard against a vacuous test: both outcomes must actually occur.
        assertTrue("positives=$positives", positives in 2_000..18_000)
    }

    @Test
    fun oneMatcherCanBeReusedAcrossTextsOfVeryDifferentLengths() {
        val matcher = FuzzyMatcher(listOf("certificate"))
        val long = "x".repeat(400)
        assertFalse(matcher.matches(long))
        assertTrue(matcher.matches("birth certifcate copy"))
        assertFalse(matcher.matches("$long $long"))
        assertTrue(matcher.matches("$long certificat"))
    }
}
