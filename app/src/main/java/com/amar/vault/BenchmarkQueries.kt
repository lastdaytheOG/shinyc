package com.amar.vault

data class QueryValidationTarget(
    val query: String,
    val expectedStrictConstraints: List<String>,
    val expectedPreferredConstraints: List<String>,
    val expectedCardTypes: List<String>,
    val expectedTopResultTypes: List<DocumentType>
)

object BenchmarkQueries {

    val suite: List<QueryValidationTarget> by lazy {
        val base = listOf(
            QueryValidationTarget(
            query = "latest train ticket",
            expectedStrictConstraints = listOf("CLASS:TICKET", "TYPE:TRAIN_TICKET"),
            expectedPreferredConstraints = listOf("SORT:DESC"),
            expectedCardTypes = listOf("KNOWLEDGE_CARD"),
            expectedTopResultTypes = listOf(DocumentType.TRAIN_TICKET)
        ),
        QueryValidationTarget(
            query = "latest flight ticket",
            expectedStrictConstraints = listOf("CLASS:TICKET", "TYPE:FLIGHT_TICKET"),
            expectedPreferredConstraints = listOf("SORT:DESC"),
            expectedCardTypes = listOf("KNOWLEDGE_CARD"),
            expectedTopResultTypes = listOf(DocumentType.FLIGHT_TICKET)
        ),
        QueryValidationTarget(
            query = "phonepe august",
            expectedStrictConstraints = listOf("CANONICAL:PHONEPE_CORP", "DATE_MONTH:08"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("COLLECTION_CARD"),
            expectedTopResultTypes = listOf(DocumentType.PAYMENT_RECEIPT)
        ),
        QueryValidationTarget(
            query = "all invoices",
            expectedStrictConstraints = listOf("CLASS:ORDER", "TYPE:INVOICE"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("COLLECTION_CARD"),
            expectedTopResultTypes = listOf(DocumentType.INVOICE)
        ),
        QueryValidationTarget(
            query = "swiggy payments above 500",
            expectedStrictConstraints = listOf("CANONICAL:SWIGGY_CORP", "CLASS:PAYMENT", "AMOUNT:>500"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("COLLECTION_CARD"),
            expectedTopResultTypes = listOf(DocumentType.PAYMENT_RECEIPT)
        ),
        QueryValidationTarget(
            query = "aadhaar pdf",
            expectedStrictConstraints = listOf("TYPE:AADHAAR", "EXTENSION:PDF"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("KNOWLEDGE_CARD"),
            expectedTopResultTypes = listOf(DocumentType.AADHAAR)
        ),
        QueryValidationTarget(
            query = "identity documents",
            expectedStrictConstraints = listOf("CLASS:IDENTITY"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("COLLECTION_CARD"),
            expectedTopResultTypes = listOf(DocumentType.AADHAAR) // Could be PAN, Aadhar, etc.
        ),
        QueryValidationTarget(
            query = "latest bank statement",
            expectedStrictConstraints = listOf("CLASS:FINANCE", "TYPE:BANK_STATEMENT"),
            expectedPreferredConstraints = listOf("SORT:DESC"),
            expectedCardTypes = listOf("KNOWLEDGE_CARD"),
            expectedTopResultTypes = listOf(DocumentType.BANK_STATEMENT)
        ),
        QueryValidationTarget(
            query = "amazon order",
            expectedStrictConstraints = listOf("CANONICAL:AMAZON_CORP", "CLASS:ORDER"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("KNOWLEDGE_CARD"),
            expectedTopResultTypes = listOf(DocumentType.INVOICE)
        ),
        QueryValidationTarget(
            query = "goa trip",
            expectedStrictConstraints = listOf("EVENT_TYPE:TRIP", "LOCATION:GOA"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("EVENT_CARD"),
            expectedTopResultTypes = listOf(DocumentType.FLIGHT_TICKET, DocumentType.TRAIN_TICKET)
        ),
        QueryValidationTarget(
            query = "payment receipts",
            expectedStrictConstraints = listOf("CLASS:PAYMENT", "TYPE:PAYMENT_RECEIPT"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("COLLECTION_CARD"),
            expectedTopResultTypes = listOf(DocumentType.PAYMENT_RECEIPT)
        ),
        QueryValidationTarget(
            query = "tickets",
            expectedStrictConstraints = listOf("CLASS:TICKET"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("COLLECTION_CARD"),
            expectedTopResultTypes = listOf(DocumentType.TRAIN_TICKET, DocumentType.FLIGHT_TICKET)
        ),
        QueryValidationTarget(
            query = "all trips",
            expectedStrictConstraints = listOf("EVENT_TYPE:TRIP"),
            expectedPreferredConstraints = listOf(),
            expectedCardTypes = listOf("EVENT_COLLECTION_CARD"),
            expectedTopResultTypes = emptyList() // Evaluated strictly on event extraction
        ),
        QueryValidationTarget(
            query = "latest payment",
            expectedStrictConstraints = listOf("CLASS:PAYMENT"),
            expectedPreferredConstraints = listOf("SORT:DESC"),
            expectedCardTypes = listOf("KNOWLEDGE_CARD"),
            expectedTopResultTypes = listOf(DocumentType.PAYMENT_RECEIPT)
        )
        )
        val extra = mutableListOf<QueryValidationTarget>()
        
        // 1. 20 Hinglish payment queries
        for (i in 1..20) {
            extra.add(
                QueryValidationTarget(
                    query = "phonepe se $i payment",
                    expectedStrictConstraints = listOf("CANONICAL:PHONEPE_CORP", "CLASS:PAYMENT"),
                    expectedPreferredConstraints = emptyList(),
                    expectedCardTypes = listOf("COLLECTION_CARD"),
                    expectedTopResultTypes = listOf(DocumentType.PAYMENT_RECEIPT)
                )
            )
        }

        // 2. 20 Hindi invoice queries
        for (i in 1..20) {
            extra.add(
                QueryValidationTarget(
                    query = "अमेज़न रसीद $i",
                    expectedStrictConstraints = listOf("CANONICAL:AMAZON_CORP", "CLASS:ORDER"),
                    expectedPreferredConstraints = emptyList(),
                    expectedCardTypes = listOf("KNOWLEDGE_CARD"),
                    expectedTopResultTypes = listOf(DocumentType.INVOICE)
                )
            )
        }

        // 3. 20 travel ticket queries
        for (i in 1..20) {
            extra.add(
                QueryValidationTarget(
                    query = "delhi yatra ticket $i",
                    expectedStrictConstraints = listOf("CLASS:TICKET"),
                    expectedPreferredConstraints = emptyList(),
                    expectedCardTypes = listOf("COLLECTION_CARD"),
                    expectedTopResultTypes = listOf(DocumentType.TRAIN_TICKET, DocumentType.FLIGHT_TICKET)
                )
            )
        }

        // 4. 20 Swiggy spending queries
        for (i in 1..20) {
            extra.add(
                QueryValidationTarget(
                    query = "swiggy par spent $i",
                    expectedStrictConstraints = listOf("CANONICAL:SWIGGY_CORP", "CLASS:PAYMENT"),
                    expectedPreferredConstraints = emptyList(),
                    expectedCardTypes = listOf("COLLECTION_CARD"),
                    expectedTopResultTypes = listOf(DocumentType.PAYMENT_RECEIPT)
                )
            )
        }

        // 5. 20 relative temporal queries
        for (i in 1..20) {
            extra.add(
                QueryValidationTarget(
                    query = "pichle mahine orders $i",
                    expectedStrictConstraints = listOf("CLASS:ORDER"),
                    expectedPreferredConstraints = emptyList(),
                    expectedCardTypes = listOf("COLLECTION_CARD"),
                    expectedTopResultTypes = listOf(DocumentType.INVOICE)
                )
            )
        }

        base + extra
    }
}
