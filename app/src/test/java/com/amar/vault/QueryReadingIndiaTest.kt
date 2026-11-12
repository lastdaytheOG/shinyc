package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class QueryReadingIndiaTest {

    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 12, 10, 0, 0); set(Calendar.MILLISECOND, 0) }

    private fun monthOf(query: String): Pair<Int, Int>? {
        val r = TemporalParser.parse(query, wordsFirst = true, now = now)
        val start = r.intent?.timeRange?.startTimeMs ?: return null
        return Calendar.getInstance().apply { timeInMillis = start }.let { it.get(Calendar.YEAR) to it.get(Calendar.MONTH) }
    }

    @Test
    fun hinglishAndHindiMonths() {
        assertEquals(2026 to Calendar.OCTOBER, monthOf("pichhle mahine ka bijli bill"))
        assertEquals(2026 to Calendar.OCTOBER, monthOf("pichle mahine ka bill"))
        assertEquals(2026 to Calendar.OCTOBER, monthOf("पिछले महीने का बिजली बिल"))
        assertEquals(2026 to Calendar.NOVEMBER, monthOf("is mahine ke bill"))
    }

    @Test
    fun countedPeriodsAgo() {
        assertEquals(2026 to Calendar.SEPTEMBER, monthOf("train ticket 2 months ago"))
        assertEquals(2026 to Calendar.SEPTEMBER, monthOf("do mahine pehle ka train ticket"))
        assertEquals(2026 to Calendar.AUGUST, monthOf("teen mahine pehle ki payments"))
        assertEquals(2026 to Calendar.AUGUST, monthOf("three months ago salary slip"))
        assertEquals(2026 to Calendar.NOVEMBER, monthOf("photos 3 days ago"))
        assertEquals(2026 to Calendar.OCTOBER, monthOf("do hafte pehle")?.let { if (it.second == Calendar.OCTOBER) it else it })
    }

    @Test
    fun yearsBack() {
        assertEquals(2025 to Calendar.JANUARY, monthOf("last year tax receipt"))
        assertEquals(2025 to Calendar.JANUARY, monthOf("pichhle saal ka bill"))
        assertEquals(2026 to Calendar.JANUARY, monthOf("is saal ki payments"))
    }

    @Test
    fun theWordsLeftAreWhatIsLookedFor() {
        assertEquals("ka bijli bill", TemporalParser.parse("pichhle mahine ka bijli bill", wordsFirst = true, now = now).cleanedQuery)
        assertEquals("train ticket", TemporalParser.parse("train ticket 2 months ago", wordsFirst = true, now = now).cleanedQuery)
    }

    private fun amount(q: String) = AmountReading.read(q)

    @Test
    fun amountsWithIndianScalesAndGrouping() {
        val around = amount("payment around ₹25,000")!!
        assertEquals(22_500.0, around.low, 0.01)
        assertEquals(27_500.0, around.high, 0.01)
        val under = amount("bills under 5 lakh")!!
        assertEquals(ConstraintOperator.LESS_THAN, under.operator)
        assertEquals(500_000.0, under.high, 0.01)
        val between = amount("between 10,000 and 20,000")!!
        assertEquals(10_000.0 to 20_000.0, between.low to between.high)
        assertEquals(25_000_000.0, amount("above 2.5 crore")!!.low, 0.01)
        assertEquals(1_18_702.10, amount("about 1,18,702.10")!!.low / 0.9, 0.01)
    }

    @Test
    fun hinglishAmounts() {
        assertEquals(ConstraintOperator.LESS_THAN, amount("500 se kam ki payments")!!.operator)
        assertEquals(1_000.0 to 2_000.0, amount("1000 se 2000 ke beech").let { it!!.low to it.high })
        assertEquals(ConstraintOperator.GREATER_THAN, amount("10 हज़ार से ज़्यादा")!!.operator)
        assertEquals(10_000.0, amount("10 हज़ार से ज़्यादा")!!.low, 0.01)
    }

    @Test
    fun aPlainNumberIsNotARange() {
        assertNull(amount("1,18,702.10"))
        assertNull(amount("pnr 2468135790"))
    }

    @Test
    fun thePlannerTurnsAnAmountIntoAFilter() {
        val plan = QueryPlanner.parse("payment around 25000", forResultList = true)
        val c = plan.strict.single { it.type == "AMOUNT" }
        assertNotNull(c.amountRange)
        assertEquals("payment", plan.cleanedQuery)
    }
}
