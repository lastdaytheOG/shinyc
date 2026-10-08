package com.amar.vault

import com.amar.vault.retrieval.RetrievalRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * What the app reads into a query beyond words to look for — a period, an order, an amount,
 * a filter — is reported, in words for the screen, next to the words it was read from; and
 * every such reading can be taken back, after which those words are looked for as words.
 */
class QueryReadingTest {

    /** Thursday 8 October 2026, mid-afternoon. */
    private val now: Calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 8, 15, 30, 0) }

    private fun read(query: String, wordsFirst: Boolean = false, asWords: Set<String> = emptySet()) =
        TemporalParser.parse(query, wordsFirst = wordsFirst, asWords = asWords, now = now)

    private fun period(typedAs: String, label: String) = Understood(Understood.Kind.PERIOD, typedAs, label)
    private fun order(typedAs: String, label: String) = Understood(Understood.Kind.ORDER, typedAs, label)

    private fun dayOf(timeMs: Long) = Calendar.getInstance().apply { timeInMillis = timeMs }
        .let { Triple(it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH)) }

    // ── A reading is reported with the words it was read from ───────────────────────────

    @Test
    fun aPeriodIsReportedAsTheDatesItMeans() {
        assertEquals(listOf(period("last month", "September 2026")), read("what did I buy last month").readings)
        assertEquals(listOf(period("this month", "October 2026")), read("amazon orders this month").readings)
        assertEquals(listOf(period("yesterday", "Yesterday (7 October)")), read("screenshots yesterday").readings)
        assertEquals(listOf(period("today", "Today (8 October)")), read("payments today").readings)
        assertEquals(listOf(period("march 2024", "March 2024")), read("fees march 2024").readings)
        assertEquals(listOf(period("2023", "2023")), read("marksheet 2023").readings)
        // A month that has not come yet this year is last year's.
        assertEquals(listOf(period("december", "December 2025")), read("bills december").readings)
        assertEquals(listOf(period("march", "March 2026")), read("fees march").readings)
    }

    @Test
    fun theRangeIsTheWholePeriod() {
        val september = read("bills last month").intent!!.timeRange!!
        assertEquals(Triple(2026, Calendar.SEPTEMBER, 1), dayOf(september.startTimeMs))
        assertEquals(Triple(2026, Calendar.SEPTEMBER, 30), dayOf(september.endTimeMs))
        // Asked on the 31st, "february" used to roll over into March.
        val onThe31st = Calendar.getInstance().apply { clear(); set(2026, Calendar.MARCH, 31, 9, 0, 0) }
        val february = TemporalParser.parse("rent february 2026", now = onThe31st).intent!!.timeRange!!
        assertEquals(Triple(2026, Calendar.FEBRUARY, 1), dayOf(february.startTimeMs))
        assertEquals(Triple(2026, Calendar.FEBRUARY, 28), dayOf(february.endTimeMs))
    }

    @Test
    fun anOrderIsReported() {
        assertEquals(listOf(order("latest", "Newest first")), read("latest invoice", wordsFirst = true).readings)
        assertEquals(listOf(order("oldest", "Oldest first")), read("oldest receipt", wordsFirst = true).readings)
        assertEquals(listOf(order("last", "Newest first")), read("my last electricity bill").readings)
    }

    // ── Hindi, and Hindi in Latin letters ───────────────────────────────────────────────

    @Test
    fun hindiAndHinglishDateWordsAreReadAndReported() {
        for ((typed, phrase, label) in listOf(
            Triple("amazon orders pichle mahine", "pichle mahine", "September 2026"),
            Triple("अमेज़न रसीद पिछले महीने", "पिछले महीने", "September 2026"),
            Triple("bill is mahine", "is mahine", "October 2026"),
            Triple("इस महीने का बिल", "इस महीने", "October 2026"),
            Triple("kal ka bill", "kal", "Yesterday (7 October)"),
            Triple("कल का बिल", "कल", "Yesterday (7 October)"),
            Triple("aaj ki photo", "aaj", "Today (8 October)"),
            Triple("आज की फोटो", "आज", "Today (8 October)"),
            Triple("parso ki photo", "parso", "Two days ago (6 October)"),
            Triple("परसों की फोटो", "परसों", "Two days ago (6 October)"),
            Triple("फीस मार्च 2024", "मार्च 2024", "March 2024"),
        )) {
            val result = read(typed)
            assertEquals(typed, listOf(period(phrase, label)), result.readings)
            assertFalse("$typed: the date words are not searched for", phrase in result.cleanedQuery)
        }
        assertTrue(read("zomato bills is hafte").readings.single().label.startsWith("This week ("))
        assertTrue(read("टिकट पिछले हफ्ते").readings.single().label.startsWith("Last week ("))
        assertEquals(listOf(order("नवीनतम", "Newest first")), read("नवीनतम बिल").readings)
        assertEquals(listOf(order("सबसे पुराना", "Oldest first")), read("सबसे पुराना बिल").readings)
        assertEquals("बिल", read("सबसे पुराना बिल").cleanedQuery)
    }

    // ── Taking a reading back ───────────────────────────────────────────────────────────

    @Test
    fun aReadingTakenBackLeavesItsWordsInTheQuery() {
        val read = read("amazon orders last month")
        assertEquals("amazon orders", read.cleanedQuery)

        val takenBack = read("amazon orders last month", asWords = setOf(read.readings.single().key))
        assertEquals("amazon orders last month", takenBack.cleanedQuery)
        assertNull(takenBack.intent)
        assertEquals(emptyList<Understood>(), takenBack.readings)
    }

    @Test
    fun oneReadingCanBeTakenBackAndAnotherKept() {
        val both = read("latest fees march 2024", wordsFirst = true)
        assertEquals(listOf(period("march 2024", "March 2024"), order("latest", "Newest first")), both.readings)

        val periodBack = read("latest fees march 2024", wordsFirst = true, asWords = setOf(both.readings[0].key))
        assertEquals("fees march 2024", periodBack.cleanedQuery)
        assertEquals(listOf(order("latest", "Newest first")), periodBack.readings)
        assertNull(periodBack.intent!!.timeRange)

        val orderBack = read("latest fees march 2024", wordsFirst = true, asWords = setOf(both.readings[1].key))
        assertEquals("latest fees", orderBack.cleanedQuery)
        assertNull(orderBack.intent!!.sort)
    }

    @Test
    fun wordsInQuotesAreNeverReadAsADateOrAnOrder() {
        val quoted = read("\"last month\" report")
        assertEquals("last month report", quoted.cleanedQuery)
        assertNull(quoted.intent)

        val order = read("the \"last\" working day")
        assertEquals("the last working day", order.cleanedQuery)
        assertNull(order.intent)

        // Only what is inside the quotes is left alone.
        val mixed = read("\"march\" past photos yesterday")
        assertEquals("march past photos", mixed.cleanedQuery)
        assertEquals(listOf(period("yesterday", "Yesterday (7 October)")), mixed.readings)
    }

    // ── "may" ───────────────────────────────────────────────────────────────────────────

    @Test
    fun mayIsAMonthOnlyWhereItReadsAsOne() {
        for (words in listOf("may day parade", "may i come in", "you may kiss the bride", "interest that may accrue")) {
            val result = read(words, wordsFirst = true)
            assertNull("\"$words\" has no date in it", result.intent)
            assertEquals(words, result.cleanedQuery)
        }
        assertEquals(listOf(period("may 2024", "May 2024")), read("fees may 2024").readings)
        assertEquals(listOf(period("may", "May 2026")), read("bills in may").readings)
        assertEquals(listOf(period("may", "May 2026")), read("12 may payment").readings)
        assertEquals(listOf(period("may", "May 2026")), read("rent of may").readings)
        assertEquals(listOf(period("मई", "May 2026")), read("मई का बिल").readings)
    }

    @Test
    fun everyOtherMonthNameIsStillReadOnItsOwn() {
        // The control for the test above: what "may" was read as before, any other month still is.
        assertNotNull(read("fees march").intent)
        assertNotNull(read("august rent").intent)
        assertNotNull(read("bills jan").intent)
    }

    // ── The planner and the search box ──────────────────────────────────────────────────

    @Test
    fun thePlanCarriesEveryReading() {
        val plan = QueryPlanner.parse("latest receipt above 500 last month", forResultList = true)
        assertEquals(
            listOf(Understood.Kind.PERIOD, Understood.Kind.ORDER, Understood.Kind.AMOUNT),
            plan.understood.map { it.kind },
        )
        assertEquals("Over ₹500", plan.understood.last().label)
        assertEquals("receipt", plan.cleanedQuery)

        val amountBack = QueryPlanner.parse("receipt above 500", forResultList = true, asWords = setOf(plan.understood.last().key))
        assertEquals("receipt above 500", amountBack.cleanedQuery)
        assertTrue(amountBack.strict.isEmpty())
    }

    @Test
    fun aQueryWithNothingReadIntoItReportsNothing() {
        for (words in listOf("electricity bill", "last working days", "first aid", "current affairs", "claude")) {
            assertEquals(words, emptyList<Understood>(), QueryPlanner.parse(words, forResultList = true).understood)
        }
    }

    @Test
    fun theSearchBoxKnowsWhatToLookForWhenAPeriodLeavesNothing() {
        // The period's own words, put back: on their own they are all there is to look for.
        assertEquals("last month", RetrievalRequest.forResultList("last month").wordsIfFilterEmpty)
        assertEquals("", RetrievalRequest.forResultList("last month").query)
        assertEquals("invoice 2019", RetrievalRequest.forResultList("invoice 2019").wordsIfFilterEmpty)
        // An order narrows nothing, so it stays read.
        assertEquals("invoice 2019", RetrievalRequest.forResultList("latest invoice 2019").wordsIfFilterEmpty)
        assertNull(RetrievalRequest.forResultList("electricity bill").wordsIfFilterEmpty)
    }

    @Test
    fun aReadingTakenBackInTheSearchBoxIsNotInItsRequest() {
        val first = RetrievalRequest.forResultList("fees march 2024")
        assertEquals("fees", first.query)
        val reading = first.plan!!.understood.single()

        val second = RetrievalRequest.forResultList("fees march 2024", asWords = setOf(reading.key))
        assertEquals("fees march 2024", second.query)
        assertTrue(second.plan!!.strict.isEmpty())
        assertTrue(second.plan!!.understood.isEmpty())
    }

    // ── Filters written out ─────────────────────────────────────────────────────────────

    @Test
    fun aFilterIsReportedInWords() {
        val ops = SearchOperators.parse("queue type:pdf after:2024 has:ocr category:Bills nonsense:1")
        assertEquals(
            listOf("type:pdf" to "PDFs only", "after:2024" to "After 2024", "has:ocr" to "With text on it", "category:Bills" to "In folder Bills"),
            ops.understood.map { it.typedAs to it.label },
        )
        assertTrue(ops.understood.all { it.kind == Understood.Kind.FILTER })
        assertEquals("what is not a filter stays a word", "queue nonsense:1", ops.cleanedQuery)
    }

    // ── What an abbreviation stands for ─────────────────────────────────────────────────

    @Test
    fun anAbbreviationsMeaningIsReportedAndCanBeTakenBack() {
        val reading = AcronymDictionary.understoodIn("otp from bank").single()
        assertEquals(Understood(Understood.Kind.MEANING, "otp", "OTP = one time password"), reading)
        assertEquals(listOf("one time password"), AcronymDictionary.analyze("otp from bank").meanings)

        assertEquals(emptyList<Understood>(), AcronymDictionary.understoodIn("otp from bank", setOf(reading.key)))
        assertEquals(emptyList<String>(), AcronymDictionary.analyze("otp from bank", AcronymDictionary.plainOf(setOf(reading.key))).meanings)
        assertEquals(emptyList<Understood>(), AcronymDictionary.understoodIn("bank statement"))
    }

    // ── A question put to the vault ─────────────────────────────────────────────────────

    @Test
    fun anOrderWordTheVaultSaysInAPhraseIsAWordOfTheQuestion() = runBlocking {
        val vault = setOf("last working", "first mid", "current affairs")
        val says: suspend (String) -> Boolean = { it in vault }

        val asWords = OrderWordsInPhrases.asWords("When is the last working day?", says)
        assertEquals(setOf("ORDER:last"), asWords)
        val plan = QueryPlanner.parse("When is the last working day?", asWords = asWords)
        assertEquals("when is the last working day?", plan.cleanedQuery.lowercase())
        assertNull(plan.sortIntent)

        // The vault says nothing of a "last electricity": here it is an order.
        assertEquals(emptySet<String>(), OrderWordsInPhrases.asWords("my last electricity bill", says))
        val bill = QueryPlanner.parse("my last electricity bill")
        assertEquals(SortOrder.DESC, bill.sortIntent)
        assertEquals(1, bill.limit)
        assertEquals("my electricity bill", bill.cleanedQuery)
    }

    @Test
    fun beforeAnOrderWordWasAlwaysAnOrderInAQuestion() {
        // The control: with nothing asked of the vault, "last working day" is "working day", newest only.
        val plan = QueryPlanner.parse("When is the last working day?")
        assertEquals("when is the working day?", plan.cleanedQuery)
        assertEquals(1, plan.limit)
    }

    @Test
    fun aQuestionsPeriodReachesItsPlan() {
        // Before, the question went through the router first, which took "last month" out, and
        // the plan was made from what was left: no period, every month searched.
        val plan = QueryPlanner.parse("what did I pay for electricity last month")
        assertEquals(listOf("DATE"), plan.strict.map { it.type })
        val whatTheRouterLeft = TemporalParser.parse("what did I pay for electricity last month").cleanedQuery
        val throughTheRouterFirst = QueryPlanner.parse(whatTheRouterLeft)
        assertTrue("the control: the period was lost", throughTheRouterFirst.strict.isEmpty())
    }

    // ── What the screen says ────────────────────────────────────────────────────────────

    @Test
    fun aChipSaysTheWordsAndWhatTheyWereReadAs() {
        assertEquals("“last month” read as September 2026", period("last month", "September 2026").chip)
        assertEquals("“latest” read as Newest first", order("latest", "Newest first").chip)
        assertEquals("PDFs only", Understood(Understood.Kind.FILTER, "type:pdf", "PDFs only").chip)
        assertEquals("OTP = one time password", Understood(Understood.Kind.MEANING, "otp", "OTP = one time password").chip)
    }

    @Test
    fun aDroppedReadingIsSaidNotHidden() {
        assertEquals(
            "Nothing from September 2026. Showing results for the words \"last month\" instead.",
            ReadingWords.droppedBecauseEmpty(listOf(period("last month", "September 2026"))),
        )
        assertEquals(
            "Nothing over ₹500. Showing results for the words instead.",
            ReadingWords.droppedBecauseEmpty(listOf(Understood(Understood.Kind.AMOUNT, "above 500", "Over ₹500"))),
        )
        assertNull(ReadingWords.droppedBecauseEmpty(emptyList()))
        assertEquals(
            "3 results are hidden by the filter: PDFs only. Tap the filter to remove it.",
            ReadingWords.hiddenByFilters(3, listOf(Understood(Understood.Kind.FILTER, "type:pdf", "PDFs only"))),
        )
    }

    @Test
    fun anAnswerSaysWhatWasReadIntoTheQuestion() {
        assertEquals(
            "I read \"last month\" as September 2026; \"last\" as newest first. If you meant the words themselves, put them in quotes.",
            ReadingWords.forAnAnswer(listOf(period("last month", "September 2026"), order("last", "Newest first"))),
        )
        assertNull(ReadingWords.forAnAnswer(emptyList()))
        assertEquals(
            "Nothing in your vault from September 2026 matches. I read \"last month\" as a filter; " +
                "if you meant the words themselves, put them in quotes.",
            ReadingWords.nothingInPeriod(listOf(period("last month", "September 2026"))),
        )
        assertNull("no period was asked for", ReadingWords.nothingInPeriod(listOf(order("last", "Newest first"))))
    }
}
