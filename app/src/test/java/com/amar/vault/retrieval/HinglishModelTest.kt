package com.amar.vault.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class HinglishModelTest {

    private val model = File("src/main/hinglish-assets/hinglish/translit.bin").inputStream().use { HinglishModel.read(it) }

    @Test
    fun guessesAreTheOnesTheTrainingCodeMakes() {
        val lines = File("src/test/resources/hinglish/reference.tsv").readLines().filter { it.isNotBlank() }
        val width = lines.first().split('\t')[1].toInt()
        var compared = 0
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            val word = parts[0]
            val expected = parts.drop(1).map { it.substringBefore(':') to it.substringAfter(':').toDouble() }
            val got = model.guesses(word, width)
            assertEquals(word, expected.map { it.first }, got.map { it.key })
            for ((e, g) in expected.zip(got)) {
                assertTrue("$word ${e.first}: ${e.second} vs ${g.chance}", abs(e.second - g.chance) <= 0.002 + 0.01 * e.second)
            }
            compared++
        }
        assertTrue(compared >= 40)
    }

    @Test
    fun onlyPlainLowerCaseLettersAreRead() {
        assertTrue(model.guesses("").isEmpty())
        assertTrue(model.guesses("ab1").isEmpty())
        assertTrue(model.guesses("Paani").isEmpty())
        assertTrue(model.guesses("a".repeat(25)).isEmpty())
    }

    @Test
    fun halfFloatsReadBackExactly() {
        assertEquals(0f, HinglishModel.halfToFloat(0x0000))
        assertEquals(1f, HinglishModel.halfToFloat(0x3C00))
        assertEquals(-2f, HinglishModel.halfToFloat(0xC000))
        assertEquals(65504f, HinglishModel.halfToFloat(0x7BFF))
        assertEquals(Math.pow(2.0, -24.0).toFloat(), HinglishModel.halfToFloat(0x0001))
        assertEquals(Math.pow(2.0, -14.0).toFloat(), HinglishModel.halfToFloat(0x0400))
    }

    @Test
    fun aModelThatCannotBeReadGivesNoGuesses() {
        val spellings = ModelSpellings({ error("missing") })
        assertTrue(spellings.keys("paani").isEmpty())
    }

    @Test
    fun guessesAreRememberedAndFilteredByChance() {
        var loads = 0
        val spellings = ModelSpellings({ loads++; model }, width = 4, minChance = 0.0)
        val first = spellings.keys("paani")
        assertEquals(first, spellings.keys("paani"))
        assertEquals(1, loads)
        assertEquals(model.guesses("paani", 4).map { it.key }, first)
        val strict = ModelSpellings({ model }, width = 4, minChance = 1.01)
        assertTrue(strict.keys("paani").isEmpty())
    }
}
