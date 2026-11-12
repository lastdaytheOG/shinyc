package com.amar.vault.vectors

import com.amar.vault.VaultItem
import com.amar.vault.indexing.AutoTags
import java.util.Calendar
import java.util.Locale

object FactsStage {

    const val RULE_VERSION = 1

    fun of(row: VaultItem): List<Fact> = of(row.parentDocumentId ?: row.id, row.id, row.ocrText)

    fun of(itemId: String, rowId: String, text: String): List<Fact> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Fact>()
        val taken = ArrayList<IntRange>()
        fun add(kind: String, value: String, num: Double?, date: Long?, range: IntRange, trust: Float) {
            if (kind == FactKind.AMOUNT && taken.any { it.first <= range.last && range.first <= it.last }) return
            if (kind == FactKind.AMOUNT) taken += range
            out += Fact(itemId = itemId, rowId = rowId, kind = kind, valueText = value, valueNum = num, valueDate = date,
                spanStart = range.first, spanEnd = range.last + 1, trust = trust, ruleVersion = RULE_VERSION)
        }
        for (m in LABELLED_AMOUNT.findAll(text)) {
            val g = m.groups[1] ?: continue
            Numbers.amount(g.value, m.groups[2]?.value)?.let { add(FactKind.AMOUNT, it.toString(), it, null, g.range, 0.9f) }
        }
        for (m in CURRENCY_AMOUNT.findAll(text)) {
            val g = m.groups[1] ?: continue
            Numbers.amount(g.value, m.groups[2]?.value)?.let { add(FactKind.AMOUNT, it.toString(), it, null, g.range, 0.8f) }
        }
        for (m in GROUPED_AMOUNT.findAll(text)) {
            Numbers.amount(m.value, null)?.let { add(FactKind.AMOUNT, it.toString(), it, null, m.range, 0.5f) }
        }
        for (m in LABELLED_ID.findAll(text)) {
            val g = m.groups[2] ?: continue
            val id = g.value.replace(Regex("[\\s-]"), "").uppercase(Locale.ROOT)
            if (id.length >= 4 && id.any { it.isDigit() }) add(FactKind.ID_NUMBER, id, null, null, g.range, 0.9f)
        }
        for (m in PHONE.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }.takeLast(10)
            if (digits.length == 10) add(FactKind.PHONE, digits, null, null, m.range, 0.7f)
        }
        for (m in DATE_NUMERIC.findAll(text)) {
            Dates.numeric(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let {
                add(FactKind.DATE, m.value, null, it, m.range, 0.8f)
            }
        }
        for (m in DATE_WORDS.findAll(text)) {
            val month = Dates.month(m.groupValues[2]) ?: continue
            Dates.numeric(m.groupValues[1].toInt(), month, m.groupValues[3].toInt())?.let {
                add(FactKind.DATE, m.value, null, it, m.range, 0.8f)
            }
        }
        PARTY_PATTERNS.forEach { re ->
            re.find(text)?.groups?.get(1)?.let { g ->
                val name = g.value.trim().trimEnd('.', ',')
                if (name.length in 3..60) add(FactKind.PARTY, name, null, null, g.range, 0.6f)
            }
        }
        firstLineParty(text)?.let { (name, range) -> add(FactKind.PARTY, name, null, null, range, 0.5f) }
        AutoTags.kinds(text).forEach { k -> add(FactKind.DOC_KIND, k.name.lowercase(Locale.ROOT), null, null, 0..0, 0.6f) }
        return out
    }

    private fun firstLineParty(text: String): Pair<String, IntRange>? {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (!ORG_WORD.containsMatchIn(line) || line.length > 60 || line.count { it.isDigit() } > 2) return null
        val start = text.indexOf(line)
        return line to (start until start + line.length)
    }

    private val ORG_WORD = Regex(
        "\\b(?:ltd|limited|pvt|store|stores|bank|pharmacy|medical|restaurant|hotel|cafe|insurance|airlines|travels|" +
            "telecom|broadband|fibre|school|college|university|hospital|clinic|cinemas|supermart|mart|services|corporation|nigam|" +
            "petroleum|electricals|dairy|hardware)\\b|निगम|विभाग|बैंक",
        RegexOption.IGNORE_CASE,
    )

    private const val NUMBER = "(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)"
    private const val SCALE = "(?:\\s*(lakh|lakhs|lac|crore|crores|cr|हज़ार|हजार|लाख|करोड़))?"

    private val LABELLED_AMOUNT = Regex(
        "(?:total|net amount payable|net amount|amount payable|amount paid|amount|net pay|fare|total fare|fare paid|premium paid|premium|" +
            "closing balance|balance|total received|grand total|payable|price|देय राशि|कुल देय राशि|कुल|राशि)" +
            "\\s*(?:\\(?(?:rs\\.?|inr|₹)\\)?)?\\s*[:\\-]?\\s*(?:rs\\.?|inr|₹)?\\s*$NUMBER$SCALE",
        RegexOption.IGNORE_CASE,
    )
    private val CURRENCY_AMOUNT = Regex("(?:₹|\\brs\\.?|\\binr\\b|रु\\.?)\\s*$NUMBER$SCALE", RegexOption.IGNORE_CASE)
    private val GROUPED_AMOUNT = Regex("(?<![\\d,.])\\d{1,3}(?:,\\d{2})*,\\d{3}\\.\\d{2}(?![\\d,])")

    private val LABELLED_ID = Regex(
        "(pnr|booking reference(?: \\(pnr\\))?|policy number|policy no\\.?|consumer no\\.?|consumer number|account|customer id|" +
            "order id|booking id|ticket no|receipt no\\.?|bill no|upi transaction id|transaction id|serial number|serial|" +
            "dl no|invoice|tax invoice|code|उपभोक्ता क्रमांक|कनेक्शन संख्या|पहचान संख्या|लेनदेन आईडी)" +
            "\\s*[:#]?\\s*(?:\\(pnr\\)\\s*)?([A-Z0-9][A-Z0-9-]{3,24}(?: [0-9]{4}){0,2})",
        RegexOption.IGNORE_CASE,
    )
    private val PHONE = Regex("(?<!\\d)(?:\\+91[\\s-]?)?[6-9]\\d{4}[\\s-]?\\d{5}(?!\\d)")
    private val DATE_NUMERIC = Regex("(?<!\\d)(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})(?!\\d)")
    private val DATE_WORDS = Regex(
        "(?<!\\d)(\\d{1,2})\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?,?\\s+(\\d{4})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val PARTY_PATTERNS = listOf(
        Regex("(?:paid to|sent to|received from)\\s+([A-Z][\\w.&' ]{2,50}?)(?:\\n|$|,)", RegexOption.IGNORE_CASE),
        Regex("(?:^|\\n)([^\\n]{3,50}?) को भेजा गया"),
    )
}

object Numbers {

    fun amount(digits: String, scale: String?): Double? {
        val base = digits.replace(",", "").toDoubleOrNull() ?: return null
        val factor = when (scale?.lowercase(Locale.ROOT)) {
            null, "" -> 1.0
            "हज़ार", "हजार" -> 1_000.0
            "lakh", "lakhs", "lac", "लाख" -> 100_000.0
            "crore", "crores", "cr", "करोड़" -> 10_000_000.0
            else -> 1.0
        }
        val v = base * factor
        return if (v > 0 && v < 1e12) Math.round(v * 100) / 100.0 else null
    }
}

object Dates {

    fun numeric(day: Int, month: Int, year: Int): Long? {
        if (month !in 1..12 || day !in 1..31 || year !in 1950..2100) return null
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, 1, 12, 0, 0)
        if (day > c.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
        c.set(Calendar.DAY_OF_MONTH, day)
        return c.timeInMillis
    }

    fun month(name: String): Int? = when (name.lowercase(Locale.ROOT).take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
        else -> null
    }
}
