package com.amar.vault.vectors

import android.content.Context

object Sensitive {

    private const val PREFS = "sensitive"
    private const val KEY_EXCLUDE = "exclude_from_index"
    const val HIDDEN = "••••"

    @Volatile var excludeFromIndex: Boolean = false
        private set

    fun load(context: Context) {
        excludeFromIndex = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_EXCLUDE, false)
    }

    fun setExcludeFromIndex(context: Context, exclude: Boolean) {
        excludeFromIndex = exclude
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EXCLUDE, exclude).apply()
    }

    private val OTP_WORDS = "(?:otp|one[- ]time (?:password|passcode|pin|code)|verification code|security code|login code|ओटीपी)"
    private val OTP = Regex("(?i)(?:$OTP_WORDS[^\\n\\d]{0,40}?(?<!\\d)(\\d{4,8})(?!\\d))|(?:(?<!\\d)(\\d{4,8})(?!\\d)[^\\n\\d]{0,40}?$OTP_WORDS)")
    private val CARD = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
    private val SECRET = Regex("(?i)\\b(password|passcode|pin|cvv|upi pin)\\s*[:=-]\\s*(\\S{3,40})")

    fun spans(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (m in OTP.findAll(text)) {
            val g = m.groups[1] ?: m.groups[2] ?: continue
            out += g.range
        }
        for (m in CARD.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 13..19 && luhn(digits)) out += m.range
        }
        for (m in SECRET.findAll(text)) m.groups[2]?.let { out += it.range }
        return out.sortedBy { it.first }
    }

    fun mask(text: String): String {
        val spans = spans(text)
        if (spans.isEmpty()) return text
        val sb = StringBuilder()
        var at = 0
        for (r in spans) {
            if (r.first < at) continue
            sb.append(text, at, r.first).append(HIDDEN)
            at = r.last + 1
        }
        sb.append(text, at, text.length)
        return sb.toString()
    }

    fun isSensitive(text: String): Boolean = spans(text).isNotEmpty()

    fun forIndex(text: String): String = if (excludeFromIndex) mask(text) else text

    private fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (double) { d *= 2; if (d > 9) d -= 9 }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}
