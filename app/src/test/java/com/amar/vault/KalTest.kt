package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KalTest {

    @Test
    fun kalWithAFutureVerbIsTomorrowAndStaysAWord() {
        listOf("plumber kal aayega", "kal milegi report", "hum kal chalenge", "बिजली वाला कल आएगा").forEach { q ->
            val r = TemporalParser.parse(q, wordsFirst = true)
            assertNull(q, r.intent?.timeRange)
            assertEquals(q, q.lowercase(), r.cleanedQuery)
        }
    }

    @Test
    fun kalWithoutAFutureVerbIsStillYesterday() {
        listOf("kal ka bill", "kal wali photo", "कल की रसीद", "kal").forEach { q ->
            assertNotNull(q, TemporalParser.parse(q, wordsFirst = true).intent?.timeRange)
        }
    }

    @Test
    fun aWordThatOnlyEndsLikeAVerbDoesNotCount() {
        assertNotNull(TemporalParser.parse("omega watch kal").intent?.timeRange)
    }
}
