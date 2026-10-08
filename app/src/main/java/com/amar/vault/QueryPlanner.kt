package com.amar.vault

enum class ConstraintOperator { EQUALS, GREATER_THAN, LESS_THAN, BETWEEN }

data class MetadataConstraint(
    val type: String,
    val value: String = "",
    val numericValue: Double? = null,
    val timestampValue: LongArray? = null,
    val operator: ConstraintOperator = ConstraintOperator.EQUALS,
    /** DATE only: the year and month words the range was read from ("2024", "march"). */
    val typedTerms: List<String> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MetadataConstraint

        if (type != other.type) return false
        if (value != other.value) return false
        if (numericValue != other.numericValue) return false
        if (timestampValue != null) {
            if (other.timestampValue == null) return false
            if (!timestampValue.contentEquals(other.timestampValue)) return false
        } else if (other.timestampValue != null) return false
        if (operator != other.operator) return false
        if (typedTerms != other.typedTerms) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + (numericValue?.hashCode() ?: 0)
        result = 31 * result + (timestampValue?.contentHashCode() ?: 0)
        result = 31 * result + operator.hashCode()
        result = 31 * result + typedTerms.hashCode()
        return result
    }
}

data class QueryPlan(
    val cleanedQuery: String,
    val strict: List<MetadataConstraint>,
    val preferred: List<MetadataConstraint>,
    val sortIntent: SortOrder? = null,
    val limit: Int? = null,
    /**
     * Everything read into the query that is more than a word to look for — the period, the
     * order, the amount — for the screen to show and the user to take back.
     */
    val understood: List<Understood> = emptyList(),
)

object QueryPlanner {

    /**
     * Parses the raw query into a structured QueryPlan containing STRICT and PREFERRED constraints.
     *
     * [forResultList]: the text was typed into the search box, not asked as a question — see
     * [TemporalParser.parse] for what that changes.
     *
     * [asWords]: the keys of readings ([Understood.key]) the user has taken back; the words
     * they were read from are left in the query as plain words.
     */
    fun parse(query: String, forResultList: Boolean = false, asWords: Set<String> = emptySet()): QueryPlan {
        var q = query.trim()
        val strict = mutableListOf<MetadataConstraint>()
        val preferred = mutableListOf<MetadataConstraint>()
        val understood = mutableListOf<Understood>()

        // 1. Temporal Parsing (STRICT)
        val temporalResult = TemporalParser.parse(q, wordsFirst = forResultList, asWords = asWords)
        if (temporalResult.confidence >= 0.5f) {
            q = temporalResult.cleanedQuery
            understood += temporalResult.readings
            val intent = temporalResult.intent
            val timeRange = intent?.timeRange
            if (timeRange != null) {
                strict.add(
                    MetadataConstraint(
                        type = "DATE",
                        timestampValue = longArrayOf(
                            timeRange.startTimeMs,
                            timeRange.endTimeMs
                        ),
                        operator = ConstraintOperator.BETWEEN,
                        typedTerms = temporalResult.calendarTerms
                    )
                )
            }
        }

        // 2. Amount Parsing (STRICT)
        val amountRegex = Regex("(above|greater than|>)\\s*(?:₹|rs\\.?|inr)?\\s*([\\d,]+(?:\\.\\d{2})?)", RegexOption.IGNORE_CASE)
        val amountMatch = amountRegex.find(q)
            ?.takeIf { Understood.key(Understood.Kind.AMOUNT, it.value) !in asWords }
        if (amountMatch != null) {
            val amountStr = amountMatch.groupValues[2].replace(",", "")
            val amount = amountStr.toDoubleOrNull()
            if (amount != null) {
                strict.add(
                    MetadataConstraint(
                        type = "AMOUNT",
                        numericValue = amount,
                        operator = ConstraintOperator.GREATER_THAN
                    )
                )
                understood += Understood(Understood.Kind.AMOUNT, amountMatch.value.trim(), "Over ₹$amountStr")
                q = q.replace(amountMatch.value, "").trim()
            }
        }

        // 3. Organization / Payment App Parsing (PREFERRED)
        val knownApps = listOf("PhonePe", "GPay", "Paytm", "WhatsApp", "Chrome", "Swiggy", "Zomato", "Amazon", "Uber", "IRCTC", "OpenAI")
        for (app in knownApps) {
            if (q.contains(app, ignoreCase = true)) {
                preferred.add(
                    MetadataConstraint(
                        type = if (app in listOf("PhonePe", "GPay", "Paytm")) "PAYMENT_APP" else "ORGANIZATION",
                        value = app,
                        operator = ConstraintOperator.EQUALS
                    )
                )
                // We do NOT remove preferred entities from the cleaned query, as they are Soft constraints.
                // However, we want to allow standard OCR search to use them.
            }
        }

        // 4. Source Type Parsing (PREFERRED)
        // The word stays in the query as an ordinary word, so it matches a document's file name
        // ("notes.pdf") and hides nothing. `type:pdf` (SearchOperators) asks for one kind only.
        val sourceTypes = listOf("pdf", "screenshot", "image", "docx", "xlsx", "epub", "text")
        val qLower = q.lowercase()
        sourceTypes.forEach { type ->
            if (Regex("\\b$type\\b").containsMatchIn(qLower)) {
                preferred.add(MetadataConstraint(type = "SOURCE_TYPE", value = type.uppercase(), operator = ConstraintOperator.EQUALS))
            }
        }

        // 5. Document Type & Class Intent Parsing (PREFERRED)
        // Only an image the classifier has labelled carries this metadata; a PDF never does. As a
        // filter these words hid every document that says or is named "aadhaar", "invoice", ….
        if (qLower.contains("aadhaar")) {
            preferred.add(MetadataConstraint(type = "DOCUMENT_TYPE", value = "AADHAAR", operator = ConstraintOperator.EQUALS))
            preferred.add(MetadataConstraint(type = "DOCUMENT_CLASS", value = "IDENTITY", operator = ConstraintOperator.EQUALS))
        } else if (qLower.contains("train ticket")) {
            preferred.add(MetadataConstraint(type = "DOCUMENT_TYPE", value = "TRAIN_TICKET", operator = ConstraintOperator.EQUALS))
        } else if (qLower.contains("bank statement")) {
            preferred.add(MetadataConstraint(type = "DOCUMENT_TYPE", value = "BANK_STATEMENT", operator = ConstraintOperator.EQUALS))
        } else if (qLower.contains("invoice")) {
            preferred.add(MetadataConstraint(type = "DOCUMENT_TYPE", value = "INVOICE", operator = ConstraintOperator.EQUALS))
        } else if (qLower.contains("receipt")) {
            preferred.add(MetadataConstraint(type = "DOCUMENT_TYPE", value = "PAYMENT_RECEIPT", operator = ConstraintOperator.EQUALS))
        } else if (qLower.contains("ticket")) {
            preferred.add(MetadataConstraint(type = "DOCUMENT_CLASS", value = "TICKET", operator = ConstraintOperator.EQUALS))
        } else if (qLower.contains("identity") || qLower.contains("id card")) {
            preferred.add(MetadataConstraint(type = "DOCUMENT_CLASS", value = "IDENTITY", operator = ConstraintOperator.EQUALS))
        } else if (qLower.contains("payment") || qLower.contains("paid") || qLower.contains("spent")) {
            preferred.add(MetadataConstraint(type = "DOCUMENT_CLASS", value = "PAYMENT", operator = ConstraintOperator.EQUALS))
        }

        return QueryPlan(
            cleanedQuery = q.replace(Regex("\\s+"), " "), // Normalize spaces
            strict = strict,
            preferred = preferred,
            sortIntent = if (temporalResult.confidence >= 0.5f) temporalResult.intent?.sort else null,
            limit = if (temporalResult.confidence >= 0.5f) temporalResult.intent?.limit else null,
            understood = understood,
        )
    }
}
