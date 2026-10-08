package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A question that asks for something written down is answered by reading it off the page
 * search found — with the words it was read from and where they are — and with no model.
 */
class DirectAnswersTest {

    private fun page(doc: String, file: String, pageNumber: Int, text: String) = VaultItem(
        id = "${doc}_chunk${pageNumber - 1}", uri = "content://docs/$doc", ocrText = text, lang = "en",
        itemType = ItemType.PDF, pageNum = pageNumber, sourceFile = file, timestamp = 1L,
        parentDocumentId = doc, chunkIndex = pageNumber - 1,
    )

    private fun picture(id: String, name: String, text: String) = VaultItem(
        id = id, uri = "content://media/$id", ocrText = text, lang = "en",
        itemType = ItemType.PHOTO, sourceFile = name, title = name, timestamp = 1L,
    )

    private val calendar = page(
        "cal", "Academic Calendar (2026-27).pdf", 1,
        "ACADEMIC CALENDAR (2026-27) ODD SEMESTER Commencement of Classes 20 July 2026 First Mid Term 7 September 2026 " +
            "Second Mid Term 26 October 2026 Last Working Day 27 November 2026 Commencement of Practical Exams 30 November 2026",
    )
    private val receipt = page(
        "rec", "receipt.pdf", 1,
        "Payment Receipt Transaction reference NB2609211245541479 The payment will be updated at billers end " +
            "within 3 to 5 working days. This is not a GST invoice but only a confirmation receipt.",
    )
    private val pay = picture("pay", "probe_pay.png", "Rs 250 Paid to Vikram Traders vikram@okaxis Completed 5 Oct 2026, 6:09 pm UPI transaction ID 627812345678")
    private val bill = picture("bill", "probe_bill.png", "Zephyr Diner Bill No: 512 Table 4 Paneer Tikka 1 320.00 Butter Naan 4 160.00 Total Rs 480.00")
    private val qr = picture("qr", "share_probe_qr.png", "Scan to pay Quillon Stores Total Rs 250 only OTP 4471 do not share Call 9876543210 for help")
    private val card = picture("id", "pan card.jpg", "INCOME TAX DEPARTMENT GOVT. OF INDIA Permanent Account Number Card ABCDE1234F Name RAVI KUMAR Date of Birth 04/11/1998")
    private val essay = page("essay", "notes.pdf", 3, "The committee met on working days only and discussed the last report at length without reaching a view.")

    private fun fact(question: String, vararg pages: VaultItem) = DirectAnswers.answer(question, pages.toList())?.fact

    // ── The fact that was asked for ─────────────────────────────────────────────────────

    @Test
    fun aDateIsReadOffThePageThatSaysIt() {
        val answer = DirectAnswers.answer("when is the last working day", listOf(calendar, essay))!!

        assertEquals("27 November 2026", answer.fact)
        assertEquals("cal_chunk0", answer.sourceId)
        assertTrue("the answer comes first: ${answer.text}", answer.text.startsWith("27 November 2026"))
        assertTrue("with the words it was read from", "Last Working Day 27 November 2026" in answer.quote)
        assertTrue("and where they are", answer.text.endsWith("From Academic Calendar (2026-27).pdf, page 1"))
    }

    @Test
    fun theDateNextToTheWordsAskedAboutNotTheFirstOnThePage() {
        assertEquals("7 September 2026", fact("when is the first mid term", calendar))
        assertEquals("26 October 2026", fact("second mid term date", calendar))
        assertEquals("20 July 2026", fact("when do classes commence", calendar.copy(ocrText = calendar.ocrText.replace("Commencement of Classes", "Classes commence"))))
    }

    @Test
    fun anAmountIsReadOffAPicture() {
        assertEquals("Rs 250", fact("how much did i pay vikram traders", pay, bill))
        assertEquals("Rs 480.00", fact("total at zephyr diner", bill, pay))
    }

    @Test
    fun numbersThatHaveAShape() {
        assertEquals("ABCDE1234F", fact("what is my pan number", card))
        assertEquals("vikram@okaxis", fact("upi id of vikram traders", pay))
        assertEquals("4471", fact("otp from quillon stores", qr))
        assertEquals("9876543210", fact("quillon stores phone number", qr))
        assertEquals("NB2609211245541479", fact("transaction reference of the payment receipt", receipt))
        assertEquals("512", fact("bill number zephyr diner", bill))
        assertEquals("04/11/1998", fact("date of birth on pan card", card))
    }

    @Test
    fun theQuestionSaysWhichFactIsWanted() {
        assertEquals(DirectAnswers.Fact.DATE, DirectAnswers.factAsked("when did I pay the fee"))
        assertEquals(DirectAnswers.Fact.AMOUNT, DirectAnswers.factAsked("how much is due"))
        assertEquals(DirectAnswers.Fact.AMOUNT, DirectAnswers.factAsked("bijli ka bill kitna tha"))
        assertEquals(DirectAnswers.Fact.DATE, DirectAnswers.factAsked("exam kab hai"))
        assertEquals(DirectAnswers.Fact.PAN, DirectAnswers.factAsked("my PAN"))
        assertNull("\"pan\" inside another word is not a PAN", DirectAnswers.factAsked("company expansion plans"))
        assertNull(DirectAnswers.factAsked("matthew effect in science"))
    }

    // ── Nothing is claimed that was not read ────────────────────────────────────────────

    @Test
    fun whenThePageHasNoSuchFactItSaysSoAndShowsThePage() {
        val answer = DirectAnswers.answer("when is the committee report due", listOf(essay))!!

        assertNull(answer.fact)
        assertTrue(answer.text, answer.text.startsWith("I found the page, but no date on it."))
        assertTrue("committee met" in answer.quote)
    }

    @Test
    fun aYearIsNotTakenForACode() {
        val notice = picture("n", "notice.png", "Your OTP for the 2026 admission portal is 771204. Valid for 10 minutes.")
        assertEquals("771204", fact("admission otp", notice))
    }

    @Test
    fun aQuestionWithNoFactInItGetsThePagesOwnWords() {
        val answer = DirectAnswers.answer("what does the receipt say about gst", listOf(receipt, calendar))!!

        assertNull(answer.fact)
        assertTrue(answer.text.startsWith("“"))
        assertTrue("not a GST invoice" in answer.quote)
        assertTrue(answer.text.endsWith("From receipt.pdf, page 1"))
    }

    @Test
    fun pagesThatSayNothingOfItGiveNoAnswer() {
        assertNull(DirectAnswers.answer("when is diwali", listOf(receipt, essay)))
        assertNull(DirectAnswers.answer("when is the last working day", emptyList()))
    }

    @Test
    fun theNumberCanBeOnAnotherPageThanTheOneThatSaysMostAboutIt() {
        val intro = page("loan", "loan.pdf", 1, "Home loan agreement between the bank and Ravi Kumar regarding the home loan sanctioned to him.")
        val terms = page("loan", "loan.pdf", 4, "The home loan carries a processing fee of Rs 5,900 payable once.")

        val answer = DirectAnswers.answer("how much is the home loan processing fee", listOf(intro, terms))!!
        assertEquals("Rs 5,900", answer.fact)
        assertEquals("loan_chunk3", answer.sourceId)
        assertTrue(answer.text.endsWith("From loan.pdf, page 4"))
    }

    // ── What the pages are looked for by ────────────────────────────────────────────────

    @Test
    fun theWordsThatOnlyAskAreLeftOutOfTheSearch() {
        assertEquals(listOf("last", "working"), DirectAnswers.aboutWords("When is the last working day?"))
        assertEquals(listOf("vikram", "traders"), DirectAnswers.aboutWords("how much did I pay Vikram Traders"))
        assertEquals(listOf("bijli", "bill"), DirectAnswers.aboutWords("bijli ka bill kitna tha"))
        assertEquals(listOf("pan"), DirectAnswers.aboutWords("what is my pan number"))
    }

    @Test
    fun beforeTheAnswerWasACountAndALineFromEachResult() {
        // The control: what Ask said for this question before — the shape of buildGenericAnswer.
        val results = listOf(calendar, essay)
        val before = "Found ${results.size} results:\n\n" + results.joinToString("\n") { "📄 " + it.ocrText.take(80) }

        assertTrue("it did not say the date", "27 November 2026" !in before)
        assertNotNull(DirectAnswers.answer("when is the last working day", results)!!.fact)
    }

    // ── Where a source opens ────────────────────────────────────────────────────────────

    @Test
    fun aSourceShowsTheWordsAndKeepsItsPage() {
        assertEquals(
            "…Second Mid Term 26 October 2026 Last Working Day 27 November 2026 Commencement of Practical Exams 30 November 2026",
            AnswerSources.wordsOn(calendar, "when is the last working day"),
        )
        assertNull(AnswerSources.wordsOn(calendar, "when is diwali"))

        // Stored with the answer and read back: it still knows which page of which document it is.
        val stored = ChatSourceCodec.decode(ChatSourceCodec.encode(listOf(calendar.copy(pageNum = 7)))).single()
        assertEquals(7, stored.pdfPage)
        assertEquals("cal", stored.parentDocumentId)
    }

    @Test
    fun aSourceStoredBeforeHadLostItsPage() {
        // The control: the stored form as it was, without the document a page belongs to.
        val before = """{"v":1,"sources":[{"id":"cal_chunk6","uri":"content://docs/cal","ocrText":"text","itemType":"pdf","pageNum":7,"sourceFile":"Academic Calendar.pdf"}]}"""
        assertNull("opened at the first page, whatever page it was", ChatSourceCodec.decode(before).single().pdfPage)
    }
}
