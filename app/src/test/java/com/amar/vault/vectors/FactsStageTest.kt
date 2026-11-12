package com.amar.vault.vectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FactsStageTest {

    private fun facts(text: String) = FactsStage.of("item", "row", text)
    private fun amounts(text: String) = facts(text).filter { it.kind == FactKind.AMOUNT }.map { it.valueNum!! }
    private fun ids(text: String) = facts(text).filter { it.kind == FactKind.ID_NUMBER }.map { it.valueText }

    @Test
    fun indianGroupingIsReadAsOneNumber() {
        assertEquals(listOf(118702.10), amounts("Closing balance 1,18,702.10"))
        assertEquals(listOf(3_200_000.0), amounts("a loan of Rs 32,00,000 for the flat"))
        assertEquals(listOf(25_000.0), amounts("Payment successful\n₹25,000.00\nPaid to Sharma Electricals"))
    }

    @Test
    fun lakhAndCroreScaleTheNumber() {
        assertEquals(listOf(600_000.0), amounts("costs about Rs 6 lakh"))
        assertEquals(listOf(25_000_000.0), amounts("valued at ₹2.5 crore"))
    }

    @Test
    fun hindiLabelsAndTheRupeeSign() {
        assertEquals(listOf(3082.25), amounts("कुल देय राशि ₹3,082.25"))
    }

    @Test
    fun aLabelledAmountIsTakenOnceAndTrustedMost() {
        val f = facts("Net amount payable: Rs 2,814.00").filter { it.kind == FactKind.AMOUNT }
        assertEquals(1, f.size)
        assertEquals(0.9f, f.single().trust)
        val text = "Net amount payable: Rs 2,814.00"
        assertEquals("2,814.00", text.substring(f.single().spanStart, f.single().spanEnd))
    }

    @Test
    fun identifiersNeedTheirLabel() {
        assertEquals(listOf("2468135790"), ids("PNR 2468135790  Train No. 12345"))
        assertEquals(listOf("483920175533"), ids("Policy number 483920175533"))
        assertTrue(ids("the population was 2468135790 in that year").isEmpty())
    }

    @Test
    fun datesInNumbersAndWords() {
        val dates = facts("Bill date: 12 Oct 2026, due 05/11/2026").filter { it.kind == FactKind.DATE }.map { it.valueDate!! }
        val c = Calendar.getInstance()
        c.timeInMillis = dates[0]
        assertEquals(listOf(2026, Calendar.NOVEMBER, 5), listOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)))
        c.timeInMillis = dates[1]
        assertEquals(listOf(2026, Calendar.OCTOBER, 12), listOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)))
        assertTrue(facts("31/02/2026").none { it.kind == FactKind.DATE })
    }

    @Test
    fun partiesFromPaymentsAndLetterheads() {
        val parties = facts("Payment successful\n₹450.00\nPaid to Ramesh Kirana Store\nUPI transaction ID 123456789012")
            .filter { it.kind == FactKind.PARTY }.map { it.valueText }
        assertTrue(parties.toString(), "Ramesh Kirana Store" in parties)
        val letterhead = facts("Shivalik Power Distribution Ltd\nElectricity Bill\nConsumer No. 4471029384")
        assertTrue(letterhead.any { it.kind == FactKind.PARTY && it.valueText == "Shivalik Power Distribution Ltd" })
        assertTrue(letterhead.any { it.kind == FactKind.ID_NUMBER && it.valueText == "4471029384" })
    }

    @Test
    fun phoneNumbers() {
        assertTrue(facts("call 1800 200 3344 or +91 98765 43210").any { it.kind == FactKind.PHONE && it.valueText == "9876543210" })
    }

    @Test
    fun everyFactPointsBackAtItsRowAndRuleVersion() {
        facts("Total Rs 1,250.00 PNR 2468135790").forEach {
            assertEquals("item", it.itemId)
            assertEquals("row", it.rowId)
            assertEquals(FactsStage.RULE_VERSION, it.ruleVersion)
        }
    }
}
