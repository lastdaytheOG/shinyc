package com.amar.vault.photos

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The app's tokenizer gives the reference tokenizer's ids for every case in
 * `photos/gemma_bpe_cases.tsv` (written by `tools/visual-search/build_tokenizer.py` from the
 * transformers tokenizer of EmbeddingGemma 2): English, Hindi, Hindi in Latin letters, Bengali,
 * Telugu, emoji, accents, numbers, empty text and every gallery search of the benchmark.
 *
 * The same for the trimmed table the app ships (`prune_query_model.py`, packaged by
 * `package_models.py` with its own cases): what it keeps must tokenize exactly as the reference
 * does with the same trimmed tokenizer.json, and what it dropped must fall back to bytes as the
 * reference does.
 *
 * The tables are local files (not in git); without one, its test is skipped.
 */
class GemmaTokenizerTest {

    private val table = File("src/test/resources/photos/gemma_bpe.bin")
    private val shipped = File("../tools/visual-search/models/apk/photo-search/gemma_bpe.bin")
    private val shippedCases = File("../tools/visual-search/models/shipped_tokenizer_cases.tsv")

    @Test
    fun matchesTheReferenceTokenizerOnEveryCase() {
        assumeTrue("build the table with tools/visual-search/build_tokenizer.py", table.exists())
        val cases = javaClass.classLoader!!.getResourceAsStream("photos/gemma_bpe_cases.tsv")!!
            .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }
        check(table, cases)
    }

    @Test
    fun theShippedTrimmedTableMatchesItsReference() {
        assumeTrue("package the models with tools/visual-search/package_models.py", shipped.exists() && shippedCases.exists())
        check(shipped, shippedCases.readLines(Charsets.UTF_8).filter { it.isNotEmpty() })
    }

    private fun check(table: File, cases: List<String>) {
        val tokenizer = table.inputStream().use { GemmaTokenizer.read(it) }
        var checked = 0
        for (line in cases) {
            val tab = line.lastIndexOf('\t')
            val text = unescape(line.substring(0, tab))
            val expected = line.substring(tab + 1).split(' ').map { it.toInt() }
            val actual = tokenizer.encode(PROMPT + text).toList()
            assertEquals("ids for \"$text\"", expected, actual)
            checked++
        }
        assertEquals(cases.size, checked)
    }

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    private companion object {
        const val PROMPT = "task: search result | query: "
    }
}
