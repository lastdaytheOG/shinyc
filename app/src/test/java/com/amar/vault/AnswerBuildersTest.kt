package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerBuildersTest {

    private fun page(id: String, text: String, type: ItemType = ItemType.PDF) = VaultItem(
        id = id, uri = "content://docs/$id", ocrText = text, lang = "en",
        itemType = type, sourceFile = "$id.pdf", timestamp = 1L,
    )

    @Test
    fun nothingFoundSaysSoAndNamesTheQuestion() {
        assertEquals(
            "I couldn't find anything matching \"lease terms\" in your vault.",
            AnswerBuilders.fallback("lease terms", emptyList()),
        )
    }

    @Test
    fun paymentsAreListedWithWhoWhenAndTheTotals() {
        val spent = page("a", "Paid to Meera Stores ₹450 on 12 Mar 2026")
        val received = page("b", "Received from Ajay Rao ₹1,200")
        val answer = AnswerBuilders.fallback("how much did i spend", listOf(spent, received))

        assertTrue(answer, answer.startsWith("💰 Found 2 transactions:"))
        assertTrue(answer, "⬆️ ₹450 — Meera Stores (12 Mar 2026)" in answer)
        assertTrue(answer, "⬇️ ₹1200 — Ajay Rao" in answer)
        assertTrue(answer, "Spent: ₹450" in answer)
        assertTrue(answer, "Received: ₹1200" in answer)
        assertTrue(answer, answer.endsWith("Net: +₹750"))
    }

    @Test
    fun aMoneyQuestionWithNoAmountOnThePagesSaysSo() {
        val answer = AnswerBuilders.fallback("total bill", listOf(page("a", "Thank you for your visit")))
        assertEquals("Found 1 results but couldn't extract amounts.", answer)
    }

    @Test
    fun anOtpIsTheLastNumberOfFourToEightDigitsThatIsNotAYear() {
        val answer = AnswerBuilders.fallback(
            "what is my otp",
            listOf(page("a", "Your verification code 2026 is 481516 and will expire soon")),
        )
        assertTrue(answer, answer.startsWith("🔑 OTP: 481516"))
    }

    @Test
    fun aMessageWithNoCodeInItGivesNoOtp() {
        assertEquals(
            "Couldn't extract a specific OTP.",
            AnswerBuilders.fallback("otp", listOf(page("a", "Your parcel is out for delivery"))),
        )
    }

    @Test
    fun aPhoneNumberIsWrittenInGroups() {
        val answer = AnswerBuilders.fallback("contact number", listOf(page("a", "Meera Mobile 9876543210")))
        assertTrue(answer, answer.startsWith("Found 1 result:"))
        assertTrue(answer, "📱 +91 98765 43210" in answer)
    }

    @Test
    fun aMissedCallIsListedWithItsTime() {
        val answer = AnswerBuilders.fallback("missed call", listOf(page("a", "Missed call 10:42 pm")))
        assertTrue(answer, "👤 Missed call — 10:42 pm" in answer)
    }

    @Test
    fun aContactQuestionWithNoNumberFallsBackToTheBestLine() {
        val answer = AnswerBuilders.fallback("phone", listOf(page("a", "Open all week\nCall us any time")))
        assertTrue(answer, answer.startsWith("Found 1 result:"))
    }

    @Test
    fun otherQuestionsShowTheLineThatSaysTheMostOfTheWordsAsked() {
        val item = page("a", "Municipal notice\nWater connection number is on the left\nThanks")
        val answer = AnswerBuilders.fallback("water connection", listOf(item))
        assertEquals("Found 1 result:\n\n📄 Water connection number is on the left", answer)
    }

    @Test
    fun aPictureGetsItsOwnIcon() {
        val shot = page("s", "Order placed Blue kettle", ItemType.SCREENSHOT)
        assertTrue(AnswerBuilders.fallback("kettle", listOf(shot)).contains("📸 Order placed Blue kettle"))
    }

    @Test
    fun theDirectAnswerIsUsedWhenNoSpecialKindOfQuestionApplies() {
        val direct = DirectAnswer(text = "The lease ends on 30 June 2027 (page 2)", sourceId = "a", quote = "30 June 2027")
        assertEquals(
            "The lease ends on 30 June 2027 (page 2)",
            AnswerBuilders.fallback("when does it end", listOf(page("a", "Lease ends 30 June 2027")), direct),
        )
    }

    @Test
    fun atMostThreeResultsAreShown() {
        val items = (1..5).map { page("p$it", "Line number $it of the page") }
        val answer = AnswerBuilders.generic("line", items.map { it.ocrText }, items)
        assertTrue(answer, answer.startsWith("Found 5 results:"))
        assertEquals(3, answer.lines().count { it.startsWith("📄") })
    }
}
