package com.amar.vault

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class TemporalParserTest {

    @Test
    fun testEnglishRelativeExpressions() {
        // 1. Last Month
        val lastMonthResult = TemporalParser.parse("What did I buy last month?")
        assertNotNull(lastMonthResult.intent)
        assertNotNull(lastMonthResult.intent?.timeRange)
        assertEquals("what did i buy?", lastMonthResult.cleanedQuery)
        assertTrue(lastMonthResult.confidence >= 0.5f)

        // 2. Last Week
        val lastWeekResult = TemporalParser.parse("Show receipts from last week")
        assertNotNull(lastWeekResult.intent)
        assertNotNull(lastWeekResult.intent?.timeRange)
        assertEquals("show receipts from", lastWeekResult.cleanedQuery)
        
        // 3. This Month
        val thisMonthResult = TemporalParser.parse("Amazon orders this month")
        assertNotNull(thisMonthResult.intent)
        assertNotNull(thisMonthResult.intent?.timeRange)
        assertEquals("amazon orders", thisMonthResult.cleanedQuery)
    }

    @Test
    fun testHinglishRelativeExpressions() {
        // 1. Pichle Mahine
        val pichleMahineResult = TemporalParser.parse("Amazon orders pichle mahine")
        assertNotNull(pichleMahineResult.intent)
        assertNotNull(pichleMahineResult.intent?.timeRange)
        assertEquals("amazon orders", pichleMahineResult.cleanedQuery)

        // 2. Is Hafte
        val isHafteResult = TemporalParser.parse("Zomato bills is hafte")
        assertNotNull(isHafteResult.intent)
        assertNotNull(isHafteResult.intent?.timeRange)
        assertEquals("zomato bills", isHafteResult.cleanedQuery)

        // 3. Aaj
        val aajResult = TemporalParser.parse("Show payments from aaj")
        assertNotNull(aajResult.intent)
        assertNotNull(aajResult.intent?.timeRange)
        assertEquals("show payments from", aajResult.cleanedQuery)

        // 4. Parso
        val parsoResult = TemporalParser.parse("parso ki photo")
        assertNotNull(parsoResult.intent)
        assertNotNull(parsoResult.intent?.timeRange)
        assertEquals("ki photo", parsoResult.cleanedQuery)
    }

    @Test
    fun testHindiRelativeExpressions() {
        // 1. पिछले महीने
        val pichleMahineHindiResult = TemporalParser.parse("अमेज़न रसीद पिछले महीने")
        assertNotNull(pichleMahineHindiResult.intent)
        assertNotNull(pichleMahineHindiResult.intent?.timeRange)
        assertEquals("अमेज़न रसीद", pichleMahineHindiResult.cleanedQuery)

        // 2. इस हफ्ते
        val isHafteHindiResult = TemporalParser.parse("टिकट इस हफ्ते")
        assertNotNull(isHafteHindiResult.intent)
        assertNotNull(isHafteHindiResult.intent?.timeRange)
        assertEquals("टिकट", isHafteHindiResult.cleanedQuery)
    }
}
