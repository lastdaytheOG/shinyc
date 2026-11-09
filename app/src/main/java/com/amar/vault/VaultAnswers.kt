package com.amar.vault

data class VaultAnswer(
    val text: String,
    val sources: List<VaultItem>,
    val knowledgeCard: KnowledgeCard? = null,
    val collectionCard: CollectionCard? = null,
)

object AnswerBuilders {

    fun fallback(queryText: String, results: List<VaultItem>, direct: DirectAnswer? = null): String {
        if (results.isEmpty()) {
            return "I couldn't find anything matching \"$queryText\" in your vault."
        }

        val qLower = queryText.lowercase()
        val allText = results.map { it.ocrText.trim() }

        val isMoneyQuery = listOf("expense", "spend", "payment", "upi", "paid", "amount",
            "total", "calculate", "money", "transaction", "rupee", "cost", "bill",
            "recharge", "credited", "debited", "received", "transfer").any { qLower.contains(it) }

        if (isMoneyQuery) return payment(allText, results)

        if (qLower.contains("otp") || qLower.contains("code") || qLower.contains("verification")) {
            return otp(allText)
        }

        val isContactQuery = listOf("contact", "number", "call", "missed", "dial", "phone")
            .any { qLower.contains(it) }
        if (isContactQuery) return contact(allText, results)

        return direct?.text ?: generic(qLower, allText, results)
    }

    fun payment(texts: List<String>, results: List<VaultItem>): String {
        data class Transaction(val amount: Int, val name: String, val date: String, val type: String)

        val transactions = mutableListOf<Transaction>()

        texts.forEach { text ->
            Regex("₹\\s?([\\d,]+)").findAll(text).forEach { match ->
                val amount = match.groupValues[1].replace(",", "").toIntOrNull() ?: return@forEach
                val pos = match.range.first
                val contextStart = (pos - 200).coerceAtLeast(0)
                val context = text.substring(contextStart, (pos + 50).coerceAtMost(text.length))
                val contextLower = context.lowercase()

                val type = when {
                    contextLower.contains("received") || contextLower.contains("credited") -> "received"
                    contextLower.contains("paid") || contextLower.contains("debited") ||
                            contextLower.contains("payment") -> "paid"
                    contextLower.contains("recharge") -> "recharge"
                    else -> "transaction"
                }

                val name = Regex("(?:from|to)\\s+([A-Z][A-Za-z ]{2,20})").find(context)
                    ?.groupValues?.getOrNull(1)?.trim() ?: ""

                val date = Regex("\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(?:\\s+\\d{4})?")
                    .find(context)?.value ?: ""

                transactions.add(Transaction(amount, name, date, type))
            }
        }

        if (transactions.isEmpty()) {
            return "Found ${results.size} results but couldn't extract amounts."
        }

        val sb = StringBuilder()
        val totalSpent = transactions.filter { it.type != "received" }.sumOf { it.amount }
        val totalReceived = transactions.filter { it.type == "received" }.sumOf { it.amount }

        sb.append("💰 Found ${transactions.size} transaction${if (transactions.size > 1) "s" else ""}:\n\n")

        transactions.distinctBy { "${it.amount}_${it.name}" }.take(6).forEach { t ->
            val icon = when (t.type) {
                "received" -> "⬇️"; "paid" -> "⬆️"
                "recharge" -> "📱"; else -> "💳"
            }
            val nameStr = if (t.name.isNotBlank()) " — ${t.name}" else ""
            val dateStr = if (t.date.isNotBlank()) " (${t.date})" else ""
            sb.append("$icon ₹${t.amount}$nameStr$dateStr\n")
        }

        if (totalSpent > 0 || totalReceived > 0) {
            sb.append("\n")
            if (totalSpent > 0) sb.append("Spent: ₹$totalSpent\n")
            if (totalReceived > 0) sb.append("Received: ₹$totalReceived\n")
            if (totalSpent > 0 && totalReceived > 0) {
                val net = totalReceived - totalSpent
                sb.append("Net: ${if (net >= 0) "+" else ""}₹$net")
            }
        }

        return sb.toString().trim()
    }

    fun otp(texts: List<String>): String {
        texts.forEach { text ->
            val tLower = text.lowercase()
            if (tLower.contains("otp") || tLower.contains("code") || tLower.contains("verif")) {
                val candidates = Regex("\\b(\\d{4,8})\\b").findAll(text)
                    .map { it.groupValues[1] }
                    .filter { it.length in 4..8 && !it.startsWith("20") && !it.startsWith("19") }
                    .toList()

                if (candidates.isNotEmpty()) {
                    return "🔑 OTP: ${candidates.last()}\n\nFrom: ${text.take(80)}…"
                }
            }
        }
        return "Couldn't extract a specific OTP."
    }

    fun contact(texts: List<String>, results: List<VaultItem>): String {
        val phones = mutableSetOf<String>()
        val contactInfo = mutableListOf<String>()

        texts.forEach { text ->
            Regex("(?:\\+91[\\s-]?)?[6-9][\\d\\s-]{8,12}").findAll(text).forEach { m ->
                val digits = m.value.replace(Regex("[^0-9]"), "")
                when {
                    digits.length == 10 -> phones.add("+91 ${digits.take(5)} ${digits.drop(5)}")
                    digits.length == 12 && digits.startsWith("91") ->
                        phones.add("+${digits.take(2)} ${digits.substring(2, 7)} ${digits.drop(7)}")
                }
            }

            Regex("([A-Z][a-z]{2,15}(?:\\s[A-Z][a-z]{2,15})?)\\s*(?:Mobile|Call|Phone|\\+91|[6-9]\\d{4})")
                .findAll(text).forEach {
                    contactInfo.add(it.groupValues[1])
                }

            Regex("(Missed call|Outgoing call|Incoming call|Video call)[^\\d]*(\\d{1,2}:\\d{2}\\s*(?:am|pm)?)",
                RegexOption.IGNORE_CASE).findAll(text).forEach {
                contactInfo.add("${it.groupValues[1]} — ${it.groupValues[2]}")
            }
        }

        if (phones.isEmpty() && contactInfo.isEmpty()) {
            return generic("contact", texts, results)
        }

        val sb = StringBuilder("Found ${results.size} result${if (results.size > 1) "s" else ""}:\n\n")
        contactInfo.distinct().take(3).forEach { sb.append("👤 $it\n") }
        phones.distinct().take(5).forEach { sb.append("📱 $it\n") }

        return sb.toString().trim()
    }

    fun generic(queryText: String, texts: List<String>, results: List<VaultItem>): String {
        val sb = StringBuilder("Found ${results.size} result${if (results.size > 1) "s" else ""}:\n\n")

        val queryWords = queryText.split(Regex("\\s+")).filter { it.length >= 2 }

        results.take(3).forEach { item ->
            val text = item.ocrText.trim()
            val typeIcon = item.itemType.rowIcon

            val bestLine = text.lines()
                .filter { it.isNotBlank() && it.length > 3 }
                .maxByOrNull { line -> queryWords.count { line.lowercase().contains(it) } }
                ?: text.lines().firstOrNull { it.isNotBlank() }

            sb.append("$typeIcon ${bestLine?.trim()?.take(80) ?: text.take(80)}\n")
        }

        return sb.toString().trim()
    }
}
