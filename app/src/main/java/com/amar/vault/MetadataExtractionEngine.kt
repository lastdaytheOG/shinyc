package com.amar.vault

class MetadataCandidateBuffer(private val vaultItemId: String) {
    private val candidates = mutableMapOf<String, VaultMetadata>()
    val reviewItems = mutableListOf<CanonicalReviewQueue>()

    /**
     * Add a candidate. If a candidate with the same type and value exists,
     * the one with the higher confidence wins. If confidence is equal,
     * priority goes to deterministic sources (Regex > Dictionary > Heuristic > ML).
     */
    fun addCandidate(
        type: String,
        value: String,
        confidence: Float,
        source: String,
        numericValue: Double? = null,
        timestampValue: Long? = null
    ) {
        val key = "${type}_${value.lowercase()}"
        val existing = candidates[key]

        if (existing == null || existing.confidence < confidence || 
            (existing.confidence == confidence && getSourcePriority(source) > getSourcePriority(existing.source))) {
            
            candidates[key] = VaultMetadata(
                vaultItemId = vaultItemId,
                type = type,
                value = value,
                numericValue = numericValue,
                timestampValue = timestampValue,
                confidence = confidence,
                source = source,
                extractionVersion = "v2"
            )
        }
    }

    private fun getSourcePriority(source: String): Int = when(source) {
        "regex" -> 4
        "dictionary" -> 3
        "heuristic" -> 2
        "mlkit_ner" -> 1
        else -> 0
    }

    fun getFinalCandidates(): List<VaultMetadata> {
        // Derive CATEGORY if possible
        val hasAmount = candidates.values.any { it.type == "AMOUNT" }
        val hasPaymentApp = candidates.values.any { it.type == "PAYMENT_APP" }
        
        if (hasAmount && hasPaymentApp) {
            addCandidate("CATEGORY", "PAYMENT", 1.0f, "heuristic")
        } else if (candidates.values.any { it.type == "PNR" || it.type == "FLIGHT" }) {
            addCandidate("CATEGORY", "TICKET", 1.0f, "heuristic")
        }

        return candidates.values.toList()
    }
}

object MetadataExtractionEngine {

    fun extract(vaultItemId: String, text: String): Pair<List<VaultMetadata>, List<CanonicalReviewQueue>> {
        val buffer = MetadataCandidateBuffer(vaultItemId)

        // 1. Regex (Confidence 1.0 or 0.8)
        extractDates(text, buffer)
        extractAmounts(text, buffer)
        extractUpi(text, buffer)

        // 2. Dictionary (Confidence 0.9)
        extractOrganizationsAndApps(text, buffer)

        // 3. Heuristics (Confidence 0.8)
        extractStatus(text, buffer)

        return Pair(buffer.getFinalCandidates(), buffer.reviewItems.distinctBy { it.rawText })
    }

    private fun extractDates(text: String, buffer: MetadataCandidateBuffer) {
        val regexPatterns = listOf(
            Regex("\\b(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})\\b"),
            Regex("\\b(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})\\b")
        )
        
        for (pattern in regexPatterns) {
            pattern.findAll(text).forEach { matchResult ->
                try {
                    val d1 = matchResult.groupValues[1].toInt()
                    val d2 = matchResult.groupValues[2].toInt()
                    val d3 = matchResult.groupValues[3].toInt()
                    
                    var year = 0; var month = 0; var day = 0
                    if (d1 > 1000) { year = d1; month = d2; day = d3 } 
                    else if (d3 > 1000) { year = d3; if (d2 > 12) { day = d2; month = d1 } else { day = d1; month = d2 } }
                    
                    if (year in 1970..2100 && month in 1..12 && day in 1..31) {
                        val c = java.util.Calendar.getInstance()
                        c.set(year, month - 1, day, 0, 0, 0)
                        val timeMs = c.timeInMillis
                        buffer.addCandidate("DATE", timeMs.toString(), 1.0f, "regex", timestampValue = timeMs)
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun extractAmounts(text: String, buffer: MetadataCandidateBuffer) {
        val regex = Regex("(?:₹|Rs\\.?|INR)\\s*([\\d,]+(?:\\.\\d{2})?)", RegexOption.IGNORE_CASE)
        regex.findAll(text).forEach { match ->
            val valueStr = match.groupValues[1].replace(",", "")
            val numeric = valueStr.toDoubleOrNull()
            if (numeric != null) {
                buffer.addCandidate("AMOUNT", valueStr, 0.8f, "regex", numericValue = numeric)
            }
        }
    }

    private fun extractUpi(text: String, buffer: MetadataCandidateBuffer) {
        val regex = Regex("[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}")
        regex.findAll(text).forEach { match ->
            buffer.addCandidate("UPI", match.value, 1.0f, "regex")
        }
    }

    private fun extractOrganizationsAndApps(text: String, buffer: MetadataCandidateBuffer) {
        val tokens = text.split(Regex("\\s+")).filter { it.length >= 4 }
        val resolvedTokens = mutableSetOf<String>()
        
        // 1. Direct substring checks against known aliases
        for (aliasEntry in CanonicalEntityRegistry.aliasMap) {
            val lowerText = text.lowercase()
            if (aliasEntry.isExactMatch && lowerText.contains(aliasEntry.alias)) {
                val res = CanonicalEntityRegistry.resolve(aliasEntry.alias)
                handleResolution(res, buffer, aliasEntry.alias)
                resolvedTokens.add(aliasEntry.alias)
            } else if (!aliasEntry.isExactMatch && lowerText.contains(aliasEntry.alias)) {
                val res = CanonicalEntityRegistry.resolve(aliasEntry.alias)
                handleResolution(res, buffer, aliasEntry.alias)
                resolvedTokens.add(aliasEntry.alias)
            }
        }

        // 2. Token-level fuzzy resolution
        for (token in tokens) {
            val lowerToken = token.lowercase()
            if (resolvedTokens.any { it.contains(lowerToken) }) continue 
            
            val res = CanonicalEntityRegistry.resolve(token)
            if (res.decision != CanonicalizationDecision.UNRESOLVED) {
                handleResolution(res, buffer, token)
            }
        }

        // Source Apps fallback (Not canonicalized for now)
        val sourceApps = listOf("WhatsApp", "Telegram", "Instagram", "Chrome")
        sourceApps.forEach { if (text.contains(it, true)) buffer.addCandidate("SOURCE_APP", it, 0.9f, "dictionary") }
    }

    private fun handleResolution(res: CanonicalResolutionResult, buffer: MetadataCandidateBuffer, rawText: String) {
        if (res.decision == CanonicalizationDecision.UNRESOLVED) {
            buffer.reviewItems.add(CanonicalReviewQueue(res.rawText, res.canonicalId, res.confidence))
        } else {
            val entity = res.canonicalId?.let { CanonicalEntityRegistry.entities[it] } ?: return
            
            if (res.decision == CanonicalizationDecision.RESOLVED) {
                buffer.addCandidate(entity.entityType, res.canonicalId, res.confidence, "dictionary")
            } else if (res.decision == CanonicalizationDecision.CANDIDATE) {
                buffer.addCandidate(entity.entityType, res.rawText, res.confidence, "dictionary")
                buffer.reviewItems.add(CanonicalReviewQueue(res.rawText, res.canonicalId, res.confidence))
            }
        }
    }

    private fun extractStatus(text: String, buffer: MetadataCandidateBuffer) {
        val lowerText = text.lowercase()
        if (lowerText.contains("successful") || lowerText.contains("success")) {
            buffer.addCandidate("STATUS", "Success", 0.8f, "heuristic")
        } else if (lowerText.contains("failed") || lowerText.contains("declined")) {
            buffer.addCandidate("STATUS", "Failed", 0.8f, "heuristic")
        }
    }
}
