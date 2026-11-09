package com.amar.vault.indexing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class UnsureLineTest {

    @Test
    fun aWordyLineReadWithLowConfidenceIsUnsure() {
        assertTrue(ImageContentExtractor.unsureLine("Balance due", 0.42f))
        assertTrue(ImageContentExtractor.unsureLine("A/c 4821", 0.59f))
    }

    @Test
    fun aLineReadWithConfidenceIsNotUnsure() {
        assertFalse(ImageContentExtractor.unsureLine("Balance due", 0.6f))
        assertFalse(ImageContentExtractor.unsureLine("Balance due", 0.93f))
    }

    @Test
    fun aShortLineDoesNotCountHoweverUnsure() {
        assertFalse(ImageContentExtractor.unsureLine("X", 0.2f))
        assertFalse(ImageContentExtractor.unsureLine("4G", 0.3f))
        assertFalse(ImageContentExtractor.unsureLine("2:41", 0.3f))
        assertFalse(ImageContentExtractor.unsureLine("-- | --", 0.1f))
    }

    @Test
    fun lettersOfAnyScriptAndDigitsCount() {
        assertTrue(ImageContentExtractor.unsureLine("नमस्ते", 0.3f))
        assertTrue(ImageContentExtractor.unsureLine("12:45", 0.3f))
    }
}
