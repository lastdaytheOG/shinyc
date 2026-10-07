package com.amar.vault.indexing

import com.amar.vault.ItemType
import com.amar.vault.indexing.AutoTags.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tags the app gives an item, and the evidence each one needs.
 *
 * The rules they replace are kept here as they were ([oldDocumentTags], [oldPictureTags]) so
 * that each thing they got wrong is shown going wrong: a test that only says "the new rules do
 * not tag this" would pass just as well if the old ones had not either.
 */
class AutoTagsTest {

    // ── The rules as they were until 2026-10 ────────────────────────────────────────────

    private val oldDocumentRules = listOf(
        listOf("invoice", "bill", "receipt") to "invoice billing receipt",
        listOf("contract", "agreement", "terms and conditions") to "contract agreement legal",
        listOf("₹", "$", "€", "amount", "total", "subtotal", "payment") to "financial payment monetary",
        listOf("resume", "curriculum vitae", "cv", "work experience") to "resume cv career",
        listOf("confidential", "private", "restricted") to "confidential sensitive",
        listOf("meeting", "minutes", "agenda", "attendees") to "meeting minutes notes",
        listOf("report", "analysis", "findings", "summary") to "report analysis",
        listOf("prescription", "diagnosis", "patient", "mg", "dosage") to "medical health",
        listOf("marks", "grade", "semester", "exam", "cgpa", "gpa") to "academic education",
        listOf("tax", "gst", "pan", "itr", "tds") to "tax government",
    )

    /** What a page of a document was tagged with: any of a rule's fragments anywhere in it. */
    private fun oldDocumentTags(text: String): Set<String> {
        val lower = text.lowercase()
        return oldDocumentRules.filter { (fragments, _) -> fragments.any { it in lower } }.flatMap { it.second.split(' ') }.toSet()
    }

    /** What a picture was tagged with. */
    private fun oldPictureTags(text: String): Set<String> {
        val lower = text.lowercase()
        val tags = mutableListOf<String>()
        if ("₹" in lower || "rs" in lower || "total" in lower || "amount" in lower) tags += "receipt payment bill invoice"
        if ("otp" in lower || "one time" in lower) tags += "otp verification code"
        if (Regex("[6-9]\\d{9}").containsMatchIn(text)) tags += "contact phone number call"
        return tags.flatMap { it.split(' ') }.toSet()
    }

    private fun kinds(text: String) = AutoTags.kinds(text)
    private fun pictureTags(text: String, codes: List<String> = emptyList()) = AutoTags.of(text, ItemType.SCREENSHOT, codes)

    // ── A fragment is not evidence ──────────────────────────────────────────────────────

    @Test
    fun aWordInsideAnotherWordSetsNothingOff() {
        val page = "For example, the company shall examine the syntax of its remarks, upgrade the " +
            "billing ability of occupants, and keep mgmt standards. Arbitrary nitrogen levels are reported."
        assertEquals("the page is a page of a PDF and nothing more", "pdf document", AutoTags.of(page, ItemType.PDF))
        assertTrue(kinds(page).isEmpty())

        // The control: every one of these came from a fragment.
        val old = oldDocumentTags(page)
        assertTrue("exam in example: $old", "academic" in old)
        assertTrue("pan in company, tax in syntax: $old", "tax" in old)
        assertTrue("mg in mgmt: $old", "medical" in old)
        assertTrue("bill in billing/ability: $old", "invoice" in old)
        assertTrue("report in reported: $old", "report" in old)
    }

    @Test
    fun rsInsideAWordIsNotMoney() {
        val shot = "18:09\nTop users this hour\nOthers are typing\nFirst results in 3 hours"
        assertEquals("", pictureTags(shot))
        assertTrue("the control — it was a receipt before: ", "receipt" in oldPictureTags(shot))
    }

    @Test
    fun oneCommonWordDoesNotMakeAPageAKindOfDocument() {
        // A page of a statute says tax, payment, amount, receipt, agreement, report and patient.
        val page = "Where the total amount of tax is paid by the assessee, the Assessing Officer shall, on receipt " +
            "of the report, record the payment. Any agreement in respect of a patient admitted under this " +
            "section, and the private particulars furnished at a meeting, shall be kept in a summary."
        assertTrue("${kinds(page)}", kinds(page).isEmpty())
        val old = oldDocumentTags(page)
        assertTrue("the control — the old rules called it all of these: $old",
            old.containsAll(listOf("invoice", "contract", "financial", "confidential", "meeting", "report", "medical", "tax")))
    }

    // ── A payment ───────────────────────────────────────────────────────────────────────

    @Test
    fun aPaymentAppsScreenIsAPayment() {
        val googlePay = """
            ₹250
            Paid to Ravi Kumar
            ravi@okaxis
            Completed
            5 Oct 2026, 6:09 pm
            UPI transaction ID
            627912345678
            To: RAVI KUMAR
            From: Amar (State Bank of India)
            POWERED BY UPI
        """.trimIndent()
        val phonePe = """
            Transaction Successful
            05:32 pm on 05 Oct 2026
            Paid to
            Sharma Kirana Store
            ₹1,240
            Transaction ID
            T2610051732123456789012
            Debited from XXXXXX1234
        """.trimIndent()
        for (shot in listOf(googlePay, phonePe)) {
            assertEquals(shot.lines().first(), listOf(Kind.PAYMENT), kinds(shot))
            assertEquals("payment receipt transaction", pictureTags(shot))
        }
    }

    @Test
    fun aBanksMessageIsAPayment() {
        val debit = "Rs.250.00 debited from A/c **1234 on 05-10-26 to VPA ravi@okaxis(UPI Ref No 627912345678). Not you? Call on 18002586161"
        val credit = "Rs 5,000.00 credited to a/c XX1234 on 05-10-26 by a/c linked to VPA amar@oksbi (UPI Ref no 627912345678)."
        val noCurrency = "Dear UPI user A/C X1234 debited by 250.0 on date 05Oct26 trf to RAVI KUMAR Refno 627912345678. -SBI"
        val neft = "Your NEFT transaction of INR 25,000.00 to beneficiary ANIL has been credited. UTR No. SBIN626279123456"
        for (message in listOf(debit, credit, noCurrency, neft)) assertTrue(message, Kind.PAYMENT in kinds(message))
        assertFalse("the helpline is not the payer's phone number", Kind.PHONE in kinds(debit))
    }

    @Test
    fun aPaymentReceiptWithNoAmountOnItIsStillOne() {
        // From a real receipt: it names itself and its transaction, and prints no amount.
        val receipt = "Payment Receipt\nTransaction reference NB2609211245541479\nThe payment will be updated at billers end " +
            "within 3 to 5 working days. This is not a GST invoice but only a confirmation receipt."
        assertEquals(listOf(Kind.PAYMENT), kinds(receipt))
        assertEquals("pdf document payment receipt transaction", AutoTags.of(receipt, ItemType.PDF))
    }

    @Test
    fun moneyAndAWordForPayingAreNotATransaction() {
        val priceTag = "Scan to pay Quillon Stores\nTotal Rs 250 only"
        val news = "The government transferred Rs 500 crore to farmers through UPI last week, the ministry said."
        val statute = "the payment of tax of Rs. 10,000 shall be credited to the account of the Central Government"
        for (text in listOf(priceTag, news, statute)) assertFalse(text, Kind.PAYMENT in kinds(text))
        assertTrue("the control: ", "receipt" in oldPictureTags(priceTag) && "payment" in oldDocumentTags(statute))
    }

    // ── A bill ──────────────────────────────────────────────────────────────────────────

    @Test
    fun anInvoiceIsABill() {
        val invoice = """
            TAX INVOICE
            Invoice No: INV-2026-0042
            Bill To: Amar
            GSTIN: 27ABCDE1234F1Z5
            Widget 2 ₹500.00 ₹1,000.00
            Grand Total ₹1,180.00
        """.trimIndent()
        val electricity = "Bill No. 123456789  Bill Date 01-10-2026  Due Date 15-10-2026  Amount Due Rs. 1,240.00"
        val restaurant = "Bill No: 512  Table 4\nPaneer Tikka 1 320.00\nSub Total 840.00\nGST 5% 42.00\nGrand Total 882.00"
        for (bill in listOf(invoice, electricity, restaurant)) assertTrue(bill.lines().first(), Kind.BILL in kinds(bill))
        assertEquals("invoice bill receipt", pictureTags(restaurant))
    }

    @Test
    fun theWordBillOrInvoiceAloneIsNotABill() {
        for (text in listOf(
            "The Finance Bill, 2026 was introduced in the Lok Sabha.",
            "upon receipt of the invoice the officer shall pass an order",
            "Total Rs 250 only",
        )) assertFalse(text, Kind.BILL in kinds(text))
    }

    // ── A one-time password ─────────────────────────────────────────────────────────────

    @Test
    fun aCodeBesideTheWordsForOneIsAnOtp() {
        for (message in listOf(
            "123456 is your OTP for login to SBI. Do not share it with anyone. -SBI",
            "Your OTP is 4471. Valid for 10 minutes.",
            "Use verification code 845210 to verify your number",
            "Your one-time password for the transaction is 77120",
        )) assertEquals(message, listOf(Kind.OTP), kinds(message))
    }

    @Test
    fun talkOfOtpsIsNotOne() {
        val advice = "Never share your OTP with anyone, even bank staff."
        val article = "OTP fraud cases rose sharply in 2026, the report said."
        val laptop = "Laptop repair 4471 Hotpot street"
        for (text in listOf(advice, article, laptop)) assertTrue(text, kinds(text).isEmpty())
        assertTrue("the control: ", "otp" in oldPictureTags(advice) && "otp" in oldPictureTags(laptop))
    }

    // ── A phone number ──────────────────────────────────────────────────────────────────

    @Test
    fun aNumberToCallIsAPhoneNumber() {
        for (text in listOf("Call 9876543210 for help", "Mob: 98765-43210", "+91 98765 43210", "WhatsApp us on +91-9876543210")) {
            assertEquals(text, listOf(Kind.PHONE), kinds(text))
        }
        assertEquals("phone number contact", pictureTags("Call 9876543210 for help"))
    }

    @Test
    fun tenDigitsThatAreNotSaidToBeAPhoneNumberAreLeftAlone() {
        val reference = "UPI Ref No 627912345678"
        val account = "A/c 9876543210 credited"
        val order = "Order 8765432109 dispatched"
        for (text in listOf(reference, account, order)) assertFalse(text, Kind.PHONE in kinds(text))
        assertTrue("the control: any ten digits were one, even inside a longer number",
            "phone" in oldPictureTags(reference) && "phone" in oldPictureTags(account))
    }

    // ── Kinds of document ───────────────────────────────────────────────────────────────

    @Test
    fun aMarksheetIsMarksAndTheStudentTheyAreFor() {
        val marksheet = "STATEMENT OF MARKS\nName of Student: AMAR\nRoll No: 2024ADS042\nSemester: III\n" +
            "Data Structures 100 87\nSGPA 8.9 CGPA 8.7"
        assertEquals(listOf(Kind.MARKSHEET), kinds(marksheet))
        assertEquals("pdf document marksheet result academic", AutoTags.of(marksheet, ItemType.PDF))

        // A syllabus has marks and semesters and no student; a calendar has exams and neither.
        val syllabus = "Scheme and Syllabus\nSemester III\nData Structures  Max Marks 100  Total Marks 100"
        val calendar = "ACADEMIC CALENDAR (2026-27) ODD SEMESTER\nCommencement of Theory Exams 9 December 2026"
        assertTrue(kinds(syllabus).isEmpty() && kinds(calendar).isEmpty())
        assertTrue("the control: ", "academic" in oldDocumentTags(syllabus) && "academic" in oldDocumentTags(calendar))
    }

    @Test
    fun aPrescriptionSaysItIsOneAndNamesADose() {
        val prescription = "Dr. A. Sharma MBBS\nRx\nTab Paracetamol 500 mg 1-0-1 x 5 days\nSyrup Cofsils 5 ml twice daily"
        assertEquals(listOf(Kind.PRESCRIPTION), kinds(prescription))
        val chemistry = "magnesium (Mg) was measured at 500 mg per litre in each patient sample"
        assertTrue(kinds(chemistry).isEmpty())
        assertTrue("the control: ", "medical" in oldDocumentTags(chemistry))
    }

    @Test
    fun aResumeIsItsHeadingAndItsSections() {
        val cv = "CURRICULUM VITAE\nAmar Singh\nObjective: a data role\nEducation: B.E. 2026\nSkills: Kotlin, SQL\nExperience: intern"
        val resume = "RESUME\nAmar Singh\nEducation ...\nSkills ...\nProjects ..."
        assertEquals(listOf(Kind.RESUME), kinds(cv))
        assertEquals(listOf(Kind.RESUME), kinds(resume))
        assertEquals("word document resume cv", AutoTags.of(cv, ItemType.WORD))

        val verb = "The committee will resume its work on education policy next week."
        assertTrue(kinds(verb).isEmpty())
        assertTrue("the control: ", "resume" in oldDocumentTags(verb))
    }

    @Test
    fun anAgreementCallsItselfOneAndIsWordedAsOne() {
        val rent = "RENT AGREEMENT\nThis agreement is made between Mr A (hereinafter called the LANDLORD) and Mr B " +
            "(hereinafter called the TENANT). WHEREAS the landlord is the owner of the flat"
        assertEquals(listOf(Kind.AGREEMENT), kinds(rent))
        val mention = "any agreement entered into before the commencement of this Act shall continue"
        assertTrue(kinds(mention).isEmpty())
        assertTrue("the control: ", "contract" in oldDocumentTags(mention))
    }

    // ── Codes on a picture ──────────────────────────────────────────────────────────────

    @Test
    fun aCodeThatWasReadIsItsOwnEvidence() {
        assertEquals("qr code", pictureTags("", listOf("https://example.org/menu")))
        assertEquals("qr code upi payment", pictureTags("Scan to pay", listOf("upi://pay?pa=ravi@okaxis&pn=Ravi Kumar")))
        assertEquals("a shop's barcode holds a product number", "barcode", pictureTags("", listOf("8901030865278")))
        assertEquals("", pictureTags("https://example.org is our site"))
        // The control: three words and a web address used to be called a QR code.
    }

    // ── What comes out ──────────────────────────────────────────────────────────────────

    @Test
    fun aDocumentIsTaggedWithWhatItIsAndAPictureIsNot() {
        assertEquals("pdf document", AutoTags.of("", ItemType.PDF))
        assertEquals("word document", AutoTags.of("", ItemType.WORD))
        assertEquals("spreadsheet excel", AutoTags.of("", ItemType.EXCEL))
        assertEquals("ebook epub", AutoTags.of("", ItemType.EPUB))
        for (type in listOf(ItemType.SCREENSHOT, ItemType.PHOTO, ItemType.LINK, ItemType.TEXT)) assertEquals("", AutoTags.of("", type))
    }

    @Test
    fun tagsAreLowerCaseWordsEachOnce() {
        // A paid bill is a payment and a bill; both say "receipt".
        val paidBill = "TAX INVOICE\nInvoice No: 42\nGrand Total ₹1,180.00\nPaid via UPI. Transaction ID 627912345678"
        assertEquals(listOf(Kind.PAYMENT, Kind.BILL), kinds(paidBill))
        val tags = pictureTags(paidBill)
        assertEquals("payment receipt transaction invoice bill", tags)
        assertEquals(tags, tags.lowercase())
        assertEquals(tags.split(' ').size, tags.split(' ').toSet().size)
    }
}
