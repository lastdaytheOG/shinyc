package com.amar.vault.indexing

import com.amar.vault.ItemType

/**
 * The tags the app gives an item: one rule set, for a page of a document and for a picture.
 *
 * A tag is a word an item can be found by although it does not say it — "receipt" for a
 * payment screenshot that says "Paid to Ravi". A result found that way is shown as "Tagged …
 * by the app", so a tag has to be something a person looking at the item would agree with.
 *
 * Every rule therefore asks for evidence, and none is set off by a fragment:
 *
 *  - words count only as whole words ("exam" is not in "example", nor "pan" in "company");
 *  - a kind of item is recognised by two or more things that go together — an amount and a
 *    transaction reference, a heading and the sections under it — not by one common word;
 *  - a number is read as what it is only where its surroundings say so.
 *
 * The rules that were here before tagged a page "tax government" for containing "company",
 * and a picture "receipt payment bill invoice" for containing "rs" anywhere ("users").
 *
 * Bump [VERSION] whenever a rule changes: stored items are tagged again from their stored
 * text the next time the app starts ([AutoTagUpkeep]).
 */
object AutoTags {

    const val VERSION = 2

    /**
     * The tags for [text] — a picture's whole text, or all the text of one page of a document
     * ([TagUnits]) — as stored: lower-case words with a space between them.
     */
    fun of(text: String, type: ItemType, qrPayloads: List<String> = emptyList()): String =
        (typeTags(type) + kinds(text).flatMap { it.tags } + codeTags(qrPayloads)).distinct().joinToString(" ")

    /** What [text] was recognised as, for a report of what the rules did. */
    fun kinds(text: String): List<Kind> = Kind.entries.filter { it.evidence(text) }

    /** A document is tagged with what it is; a picture's type says only how it was taken. */
    private fun typeTags(type: ItemType): List<String> = when (type) {
        ItemType.PDF -> listOf("pdf", "document")
        ItemType.WORD -> listOf("word", "document")
        ItemType.EXCEL -> listOf("spreadsheet", "excel")
        ItemType.EPUB -> listOf("ebook", "epub")
        else -> emptyList()
    }

    /** A code that was read off the picture is evidence enough of itself. */
    private fun codeTags(payloads: List<String>): List<String> {
        if (payloads.isEmpty()) return emptyList()
        val tags = mutableListOf<String>()
        // A shop's barcode holds a product number; anything else that was read is a QR code.
        if (payloads.any { !PRODUCT_NUMBER.matches(it.trim()) }) tags += listOf("qr", "code")
        if (payloads.any { PRODUCT_NUMBER.matches(it.trim()) }) tags += "barcode"
        if (payloads.any { it.trim().startsWith("upi://", ignoreCase = true) }) tags += listOf("upi", "payment")
        return tags
    }

    enum class Kind(val tags: List<String>, internal val evidence: (String) -> Boolean) {

        /**
         * A record of a payment made or received: a payment app's screen, a bank's message, a
         * receipt. It names its transaction or says in so many words that it is one, and has
         * two more of: an amount, a word for paying, the channel it went by. An article about
         * money paid by UPI has no transaction of its own and is not one.
         */
        PAYMENT(listOf("payment", "receipt", "transaction"), { text ->
            val reference = PAYMENT_REFERENCE.containsMatchIn(text)
            val title = PAYMENT_TITLE.containsMatchIn(text)
            (reference || title) && listOf(
                reference, title, AMOUNT.containsMatchIn(text), PAYMENT_WORD.containsMatchIn(text),
                PAYMENT_CHANNEL.containsMatchIn(text),
            ).count { it } >= 3
        }),

        /** A bill or an invoice: it is laid out as one, and has an amount or a second such line. */
        BILL(listOf("invoice", "bill", "receipt"), { text ->
            val lines = BILL_LINE.findAll(text).map { it.value.lowercase().replace(SPACES, " ") }.toSet()
            lines.size >= 2 || (lines.size == 1 && AMOUNT.containsMatchIn(text))
        }),

        /** A one-time password: the words for one, and the code beside them. */
        OTP(listOf("otp", "verification", "code"), { text -> OTP_WITH_CODE.containsMatchIn(text) }),

        /** A phone number: ten digits written as one, introduced as a number to call. */
        PHONE(listOf("phone", "number", "contact"), { text -> PHONE_NUMBER.containsMatchIn(text) }),

        /** A marksheet: marks or grade points, and the student they are for. */
        MARKSHEET(listOf("marksheet", "result", "academic"), { text ->
            MARKS.containsMatchIn(text) && STUDENT.containsMatchIn(text)
        }),

        /** A prescription: it says it is one, and names a dose or a form of medicine. */
        PRESCRIPTION(listOf("prescription", "medical"), { text ->
            PRESCRIPTION_WORD.containsMatchIn(text) && (DOSE.containsMatchIn(text) || MEDICINE_FORM.containsMatchIn(text))
        }),

        /** A résumé: its heading, and the sections one has under it. */
        RESUME(listOf("resume", "cv"), { text ->
            val sections = RESUME_SECTIONS.count { it.containsMatchIn(text) }
            // "resume" is also a verb; "curriculum vitae" is not.
            (RESUME_HEADING.containsMatchIn(text) && sections >= 2) || (RESUME_WORD.containsMatchIn(text) && sections >= 3)
        }),

        /** An agreement between parties: it calls itself one and is worded as one. */
        AGREEMENT(listOf("agreement", "contract", "legal"), { text ->
            AGREEMENT_WORD.containsMatchIn(text) && LEGAL_WORDING.count { it.containsMatchIn(text) } >= 2
        }),
    }

    private fun words(pattern: String) = Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE)

    private val SPACES = Regex("\\s+")

    /** "₹250", "Rs. 1,240.00", "INR 25,000". */
    private val AMOUNT = Regex("(?:₹|\\brs\\.?|\\binr\\b)\\s*\\d[\\d,]*(?:\\.\\d{1,2})?", RegexOption.IGNORE_CASE)

    private val PAYMENT_WORD = words("paid|payment|debited|credited|received|sent|transferred")

    /** A transaction's own number: "UPI Ref No 627912345678", "Transaction ID T2610…", "Order ID 404-1234567". */
    private val PAYMENT_REFERENCE = Regex(
        "\\b(?:transaction|txn|upi|utr|reference|ref|order)\\s*(?:id|no|number|ref|reference)?\\.?\\s*[:#-]?\\s*" +
            "(?=[a-z0-9-]*\\d)[a-z0-9][a-z0-9-]{7,}\\b",
        RegexOption.IGNORE_CASE,
    )

    private val PAYMENT_CHANNEL = words("upi|neft|imps|rtgs|vpa|gpay|google pay|phonepe|paytm|bhim|amazon pay|net ?banking")

    private val PAYMENT_TITLE = words(
        "payment (?:receipt|confirmation|successful|received|completed)|transaction (?:receipt|successful|completed)|" +
            "paid successfully|money (?:sent|received)"
    )

    private val BILL_LINE = words(
        "tax invoice|invoice (?:no|number|date)|bill (?:no|number|date|to)|billed to|amount due|total due|" +
            "balance due|gstin|receipt (?:no|number)|cash memo|grand total|sub ?total"
    )

    private const val OTP_WORDS = "(?:\\botp\\b|one[- ]time (?:password|passcode|pin|code)|verification code|security code)"

    /** Four to eight digits standing alone; a four-digit year is not a code. */
    private const val CODE = "(?<!\\d)(?:\\d{5,8}|(?!19|20)\\d{4})(?!\\d)"

    private val OTP_WITH_CODE = Regex("$OTP_WORDS[^\\n]{0,40}?$CODE|$CODE[^\\n]{0,40}?$OTP_WORDS", RegexOption.IGNORE_CASE)

    /**
     * An Indian mobile number, said to be one: "Call 9876543210", "Mob: 98765-43210",
     * "+91 98765 43210". Ten digits with nothing to say what they are may be an account or a
     * reference number, and are left alone.
     */
    private val PHONE_NUMBER = Regex(
        "(?:\\b(?:call|phone|mobile|mob|ph|tel|contact|whatsapp|helpline)\\b[^\\n\\d]{0,20}(?:\\+91[\\s-]?|0)?|\\+91[\\s-]?)" +
            "[6-9]\\d{4}[\\s-]?\\d{5}(?!\\d)",
        RegexOption.IGNORE_CASE,
    )

    private val MARKS = words(
        "cgpa|sgpa|grade point|mark ?sheet|statement of marks|grade card|marks obtained|max(?:imum|\\.)? marks|total marks"
    )

    private val STUDENT = words(
        "roll (?:no|number)|enrol?lment (?:no|number)|registration (?:no|number)|seat (?:no|number)|" +
            "name of (?:the )?student|student name|candidate"
    )

    private val PRESCRIPTION_WORD = Regex("\\b(?:prescription|rx)\\b|℞", RegexOption.IGNORE_CASE)
    private val DOSE = Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|ml|mcg|iu)\\b", RegexOption.IGNORE_CASE)
    private val MEDICINE_FORM = words("tablets?|tab|capsules?|syrup|injection|ointment|drops")

    private val RESUME_HEADING = words("curriculum vitae|bio[- ]?data")
    private val RESUME_WORD = words("resume|résumé")
    private val RESUME_SECTIONS = listOf(
        "experience", "education", "skills", "objective", "projects", "certifications?", "internships?",
        "achievements", "date of birth", "languages", "references", "declaration", "hobbies",
    ).map(::words)

    private val AGREEMENT_WORD = words("agreement|contract|deed|memorandum of understanding")
    private val LEGAL_WORDING = listOf(
        "hereinafter", "whereas", "witnesseth", "party of the first part", "first party", "second party", "lessor",
        "lessee", "licensor", "licensee", "landlord", "tenant", "in witness whereof", "terms and conditions",
    ).map(::words)

    /** The number on a product's barcode: 8, 12, 13 or 14 digits and nothing else. */
    private val PRODUCT_NUMBER = Regex("\\d{8}|\\d{12,14}")
}

/**
 * The pieces of a document that are tagged as one: a kind of item is recognised by things that
 * go together, and a document is cut into pieces of about two hundred words, several to a
 * page, so the heading of an invoice and its total are rarely in the same piece.
 *
 * For a PDF that is its page. A document without pages is taken a stretch at a time, long
 * enough that a short one — a résumé, a letter — is taken whole.
 */
object TagUnits {

    /** Pieces of a document without pages that are read together: about a thousand words. */
    const val STRETCH = 6

    /**
     * [pieces], in their order in the document, as the groups to tag. [page] is the page a
     * piece is on, or null when the document has no pages.
     */
    fun <T> of(pieces: List<T>, page: (T) -> Int?): List<List<T>> {
        if (pieces.isEmpty()) return emptyList()
        if (page(pieces.first()) == null) return pieces.chunked(STRETCH)
        val units = ArrayList<MutableList<T>>()
        var current: Int? = null
        for (piece in pieces) {
            val on = page(piece)
            if (units.isEmpty() || on != current) { units.add(mutableListOf()); current = on }
            units.last().add(piece)
        }
        return units
    }

    /** The text of one group, as the rules read it. */
    fun text(pieces: List<String>): String = pieces.joinToString("\n")
}
