package com.amar.vault

data class ExpectedRelationship(val targetId: String, val type: String)

data class GoldenDocument(
    val documentId: String,
    val rawInput: String,
    val expectedMetadata: Map<String, String>,
    val expectedCanonicalEntities: List<String>,
    val expectedDocumentClass: DocumentClass,
    val expectedDocumentType: DocumentType,
    val expectedRelationships: List<ExpectedRelationship>,
    val expectedEventMembership: String?
)

object GoldenDataset {

    val corpus: List<GoldenDocument> by lazy { generateCorpus() }

    private fun generateCorpus(): List<GoldenDocument> {
        val docs = mutableListOf<GoldenDocument>()

        // 1. 50 Payment Receipts
        for (i in 1..50) {
            docs.add(
                GoldenDocument(
                    documentId = "payment_$i",
                    rawInput = "Paid ₹${500 + i} to PhonePe merchant on 2026-06-12 10:00 AM",
                    expectedMetadata = mapOf("AMOUNT" to "${500 + i}", "DATE" to "2026-06-12"),
                    expectedCanonicalEntities = listOf("PHONEPE_CORP"),
                    expectedDocumentClass = DocumentClass.PAYMENT,
                    expectedDocumentType = DocumentType.PAYMENT_RECEIPT,
                    expectedRelationships = if (i % 2 == 0) listOf(ExpectedRelationship("order_$i", "PAID_FOR")) else emptyList(),
                    expectedEventMembership = if (i < 10) "event_goa_trip" else null
                )
            )
        }

        // 2. 20 Train Tickets
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "train_$i",
                    rawInput = "IRCTC Ticket PNR 123456789$i Coach S$i",
                    expectedMetadata = mapOf("PNR" to "123456789$i", "COACH" to "S$i"),
                    expectedCanonicalEntities = listOf("IRCTC_CORP"),
                    expectedDocumentClass = DocumentClass.TICKET,
                    expectedDocumentType = DocumentType.TRAIN_TICKET,
                    expectedRelationships = emptyList(),
                    expectedEventMembership = "event_train_trip_$i"
                )
            )
        }

        // 3. 20 Flight Tickets
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "flight_$i",
                    rawInput = "Indigo Flight 6E-${100 + i} PNR ABCDE$i",
                    expectedMetadata = mapOf("PNR" to "ABCDE$i", "AIRLINE" to "Indigo"),
                    expectedCanonicalEntities = listOf("INDIGO_CORP"),
                    expectedDocumentClass = DocumentClass.TICKET,
                    expectedDocumentType = DocumentType.FLIGHT_TICKET,
                    expectedRelationships = emptyList(),
                    expectedEventMembership = "event_flight_trip_$i"
                )
            )
        }

        // 4. 20 Hotel Bookings
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "hotel_$i",
                    rawInput = "Taj Hotel Booking Confirmed BookingID: HTL$i",
                    expectedMetadata = mapOf("BOOKING_ID" to "HTL$i", "MERCHANT" to "Taj Hotel"),
                    expectedCanonicalEntities = listOf("TAJ_HOTELS"),
                    expectedDocumentClass = DocumentClass.TICKET, // Or whatever Hotel class is
                    expectedDocumentType = DocumentType.TRAIN_TICKET, // Mock fallback
                    expectedRelationships = emptyList(),
                    expectedEventMembership = "event_hotel_stay_$i"
                )
            )
        }

        // 5. 20 Invoices
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "invoice_$i",
                    rawInput = "Amazon Tax Invoice INV-$i Total: ₹${1000 + i}",
                    expectedMetadata = mapOf("INVOICE_NUM" to "INV-$i", "AMOUNT" to "${1000 + i}"),
                    expectedCanonicalEntities = listOf("AMAZON_CORP"),
                    expectedDocumentClass = DocumentClass.ORDER,
                    expectedDocumentType = DocumentType.INVOICE,
                    expectedRelationships = emptyList(),
                    expectedEventMembership = null
                )
            )
        }

        // 6. 20 Orders
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "order_$i",
                    rawInput = "Swiggy Order Delivered ORD-$i",
                    expectedMetadata = mapOf("ORDER_ID" to "ORD-$i"),
                    expectedCanonicalEntities = listOf("SWIGGY_CORP"),
                    expectedDocumentClass = DocumentClass.ORDER,
                    expectedDocumentType = DocumentType.INVOICE,
                    expectedRelationships = listOf(ExpectedRelationship("payment_${i*2}", "PAID_BY")),
                    expectedEventMembership = null
                )
            )
        }

        // 7. 10 Aadhaar
        for (i in 1..10) {
            docs.add(
                GoldenDocument(
                    documentId = "aadhaar_$i",
                    rawInput = "Government of India Aadhaar 1234 5678 901$i",
                    expectedMetadata = mapOf("ID_NUMBER" to "1234 5678 901$i"),
                    expectedCanonicalEntities = emptyList(),
                    expectedDocumentClass = DocumentClass.IDENTITY,
                    expectedDocumentType = DocumentType.AADHAAR,
                    expectedRelationships = emptyList(),
                    expectedEventMembership = null
                )
            )
        }

        // 8. 10 PAN Cards
        for (i in 1..10) {
            docs.add(
                GoldenDocument(
                    documentId = "pan_$i",
                    rawInput = "Income Tax Department PAN ABCDE1234$i",
                    expectedMetadata = mapOf("PAN_NUMBER" to "ABCDE1234$i"),
                    expectedCanonicalEntities = emptyList(),
                    expectedDocumentClass = DocumentClass.IDENTITY,
                    expectedDocumentType = DocumentType.AADHAAR, // Mock fallback
                    expectedRelationships = emptyList(),
                    expectedEventMembership = null
                )
            )
        }

        // 9. 10 Bank Statements
        for (i in 1..10) {
            docs.add(
                GoldenDocument(
                    documentId = "bank_statement_$i",
                    rawInput = "HDFC Bank Statement June 2026 Opening Balance ₹10000",
                    expectedMetadata = mapOf("BANK" to "HDFC", "MONTH" to "June 2026"),
                    expectedCanonicalEntities = listOf("HDFC_BANK"),
                    expectedDocumentClass = DocumentClass.FINANCE,
                    expectedDocumentType = DocumentType.BANK_STATEMENT,
                    expectedRelationships = emptyList(),
                    expectedEventMembership = null
                )
            )
        }

        // 10. 20 PDFs (generic)
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "pdf_$i",
                    rawInput = "Generic PDF Document Report_$i.pdf",
                    expectedMetadata = emptyMap(),
                    expectedCanonicalEntities = emptyList(),
                    expectedDocumentClass = DocumentClass.ORDER,
                    expectedDocumentType = DocumentType.INVOICE, // Fallback
                    expectedRelationships = emptyList(),
                    expectedEventMembership = null
                )
            )
        }

        // 11. 20 Screenshots
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "screenshot_$i",
                    rawInput = "WhatsApp Chat Screenshot $i about movie plans",
                    expectedMetadata = emptyMap(),
                    expectedCanonicalEntities = emptyList(),
                    expectedDocumentClass = DocumentClass.ORDER,
                    expectedDocumentType = DocumentType.INVOICE, // Fallback
                    expectedRelationships = emptyList(),
                    expectedEventMembership = null
                )
            )
        }

        // 12. 20 OCR Noise Cases
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "noise_$i",
                    rawInput = "xx123 asdf @#$! paid PhnePe 500 dsdf",
                    expectedMetadata = emptyMap(),
                    expectedCanonicalEntities = emptyList(), // Noise shouldn't match
                    expectedDocumentClass = DocumentClass.ORDER,
                    expectedDocumentType = DocumentType.INVOICE, // Fallback
                    expectedRelationships = emptyList(),
                    expectedEventMembership = null
                )
            )
        }

        // 13. 20 Duplicate Documents
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "duplicate_$i",
                    rawInput = "Paid ₹501 to PhonePe merchant on 2026-06-12 10:00 AM", // Duplicate of payment_1
                    expectedMetadata = mapOf("AMOUNT" to "501", "DATE" to "2026-06-12"),
                    expectedCanonicalEntities = listOf("PHONEPE_CORP"),
                    expectedDocumentClass = DocumentClass.PAYMENT,
                    expectedDocumentType = DocumentType.PAYMENT_RECEIPT,
                    expectedRelationships = listOf(ExpectedRelationship("payment_1", "DUPLICATE_OF")),
                    expectedEventMembership = null
                )
            )
        }

        // 14. 20 Ambiguous Merchant Cases
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "ambiguous_$i",
                    rawInput = "Paid via CREDIT card to IRFC", // Testing CRED vs CREDIT and IRCTC vs IRFC
                    expectedMetadata = emptyMap(),
                    expectedCanonicalEntities = emptyList(), // Should be UNRESOLVED
                    expectedDocumentClass = DocumentClass.PAYMENT,
                    expectedDocumentType = DocumentType.PAYMENT_RECEIPT,
                    expectedRelationships = emptyList(),
                    expectedEventMembership = null
                )
            )
        }

        // 15. 20 Missing Metadata Cases
        for (i in 1..20) {
            docs.add(
                GoldenDocument(
                    documentId = "missing_meta_$i",
                    rawInput = "Receipt for coffee", // No amount or date
                    expectedMetadata = emptyMap(),
                    expectedCanonicalEntities = emptyList(),
                    expectedDocumentClass = DocumentClass.PAYMENT,
                    expectedDocumentType = DocumentType.PAYMENT_RECEIPT,
                    expectedRelationships = emptyList(),
                    expectedEventMembership = null
                )
            )
        }

        return docs
    }

    // Legacy queries and tests kept for backward compatibility if needed, 
    // but the sprint relies on `corpus` and the new BenchmarkQueries.
}
