package com.amar.vault

enum class DocumentClass {
    IDENTITY, PAYMENT, TICKET, ORDER, FINANCE, MEDICAL, UNKNOWN
}

enum class DocumentType {
    AADHAAR, PAN_CARD, TRAIN_TICKET, FLIGHT_TICKET, PAYMENT_RECEIPT, INVOICE, BANK_STATEMENT, PRESCRIPTION, PASSPORT, UNKNOWN
}

data class ClassificationResult(
    val documentClass: DocumentClass,
    val documentType: DocumentType,
    val category: String,
    val confidence: Float
)

object ClassificationEngine {

    fun evaluate(ocrText: String, buffer: MetadataCandidateBuffer): ClassificationResult {
        val candidates = buffer.getFinalCandidates()
        val textLower = ocrText.lowercase()
        
        val hasAmount = candidates.any { it.type == "AMOUNT" }
        val hasPaymentApp = candidates.any { it.type == "PAYMENT_APP" }
        val hasPnr = candidates.any { it.type == "PNR" }
        val hasIrctc = candidates.any { it.type == "ORGANIZATION" && it.value == "IRCTC_CORP" }
        val amountCount = candidates.count { it.type == "AMOUNT" }
        val dateCount = candidates.count { it.type == "DATE" }

        val scores = mutableMapOf<DocumentType, Int>()
        
        // 1. PAYMENT_RECEIPT
        var receiptScore = 0
        if (hasAmount) receiptScore += 40
        if (hasPaymentApp) receiptScore += 40
        if (candidates.any { it.type == "STATUS" }) receiptScore += 20
        if (amountCount > 5 || dateCount > 5) receiptScore -= 50 // Penalty for Bank Statement
        scores[DocumentType.PAYMENT_RECEIPT] = receiptScore

        // 2. BANK_STATEMENT
        var bankScore = 0
        if (dateCount > 5) bankScore += 40
        if (amountCount > 5) bankScore += 40
        if (textLower.contains("closing balance") || textLower.contains("opening balance")) bankScore += 20
        scores[DocumentType.BANK_STATEMENT] = bankScore
        
        // 3. TRAIN_TICKET
        var trainScore = 0
        if (hasPnr) trainScore += 50
        if (hasIrctc || textLower.contains("train") || textLower.contains("coach")) trainScore += 40
        scores[DocumentType.TRAIN_TICKET] = trainScore

        // 4. AADHAAR
        var aadhaarScore = 0
        if (Regex("\\b\\d{4}\\s\\d{4}\\s\\d{4}\\b").containsMatchIn(ocrText)) aadhaarScore += 50
        if (textLower.contains("government of india") || textLower.contains("aadhaar")) aadhaarScore += 50
        scores[DocumentType.AADHAAR] = aadhaarScore

        // 5. INVOICE
        var invoiceScore = 0
        if (hasAmount) invoiceScore += 40
        if (textLower.contains("tax invoice") || textLower.contains("invoice no") || textLower.contains("gstin")) invoiceScore += 50
        scores[DocumentType.INVOICE] = invoiceScore

        val bestType = scores.maxByOrNull { it.value }?.key ?: DocumentType.UNKNOWN
        val bestScore = scores[bestType] ?: 0

        val confidence = bestScore / 100f
        
        if (confidence < 0.80f || bestType == DocumentType.UNKNOWN) {
            return ClassificationResult(DocumentClass.UNKNOWN, DocumentType.UNKNOWN, "UNKNOWN", confidence)
        }

        val docClass = when (bestType) {
            DocumentType.AADHAAR, DocumentType.PAN_CARD, DocumentType.PASSPORT -> DocumentClass.IDENTITY
            DocumentType.PAYMENT_RECEIPT -> DocumentClass.PAYMENT
            DocumentType.TRAIN_TICKET, DocumentType.FLIGHT_TICKET -> DocumentClass.TICKET
            DocumentType.INVOICE -> DocumentClass.ORDER
            DocumentType.BANK_STATEMENT -> DocumentClass.FINANCE
            DocumentType.PRESCRIPTION -> DocumentClass.MEDICAL
            else -> DocumentClass.UNKNOWN
        }

        val category = when (docClass) {
            DocumentClass.PAYMENT -> "PAYMENT"
            DocumentClass.TICKET -> "TICKET"
            DocumentClass.IDENTITY -> "IDENTITY"
            DocumentClass.FINANCE -> "FINANCE"
            else -> "UNKNOWN"
        }

        return ClassificationResult(docClass, bestType, category, confidence)
    }
}
