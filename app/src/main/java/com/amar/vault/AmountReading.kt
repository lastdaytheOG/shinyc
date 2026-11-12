package com.amar.vault

import java.util.Locale

object AmountReading {

    data class Reading(val typed: String, val operator: ConstraintOperator, val low: Double, val high: Double, val label: String)

    private const val NUM = "(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)"
    private const val SCALE = "(?:\\s*(lakh|lakhs|lac|crore|crores|cr|k|thousand|hazaar|hazar|हज़ार|हजार|लाख|करोड़))?"
    private const val CUR = "(?:₹|rs\\.?|inr|रु\\.?|rupees?)?\\s*"

    private val BETWEEN = Regex("(?:between|from)\\s*$CUR$NUM$SCALE\\s*(?:and|to|-)\\s*$CUR$NUM$SCALE", RegexOption.IGNORE_CASE)
    private val HINGLISH_BETWEEN = Regex("$CUR$NUM$SCALE\\s*(?:se|से)\\s*$CUR$NUM$SCALE\\s*(?:ke beech|के बीच|tak|तक)", RegexOption.IGNORE_CASE)
    private val ABOVE = Regex("(?:above|over|greater than|more than|>)\\s*$CUR$NUM$SCALE", RegexOption.IGNORE_CASE)
    private val UNDER = Regex("(?:under|below|less than|upto|up to|<)\\s*$CUR$NUM$SCALE", RegexOption.IGNORE_CASE)
    private val HINGLISH_UNDER = Regex("$CUR$NUM$SCALE\\s*(?:se kam|से कम|tak|तक)", RegexOption.IGNORE_CASE)
    private val HINGLISH_ABOVE = Regex("$CUR$NUM$SCALE\\s*(?:se zyada|se jyada|se adhik|से ज़्यादा|से ज्यादा|से अधिक)", RegexOption.IGNORE_CASE)
    private val AROUND = Regex("(?:around|about|approx\\.?|approximately|roughly|nearly|lagbhag|takreeban|करीब|लगभग|~)\\s*$CUR$NUM$SCALE", RegexOption.IGNORE_CASE)

    fun value(number: String, scale: String?): Double? {
        val base = number.replace(",", "").toDoubleOrNull() ?: return null
        val factor = when (scale?.lowercase(Locale.ROOT)) {
            null, "" -> 1.0
            "k", "thousand", "hazaar", "hazar", "हज़ार", "हजार" -> 1_000.0
            "lakh", "lakhs", "lac", "लाख" -> 100_000.0
            "crore", "crores", "cr", "करोड़" -> 10_000_000.0
            else -> 1.0
        }
        return base * factor
    }

    private fun money(v: Double): String = "₹" + java.text.NumberFormat.getIntegerInstance(Locale("en", "IN")).format(Math.round(v))

    fun read(query: String): Reading? {
        BETWEEN.find(query)?.let { m ->
            val a = value(m.groupValues[1], m.groupValues[2]) ?: return@let
            val b = value(m.groupValues[3], m.groupValues[4]) ?: return@let
            return Reading(m.value.trim(), ConstraintOperator.BETWEEN, minOf(a, b), maxOf(a, b), "Between ${money(minOf(a, b))} and ${money(maxOf(a, b))}")
        }
        HINGLISH_BETWEEN.find(query)?.let { m ->
            val a = value(m.groupValues[1], m.groupValues[2]) ?: return@let
            val b = value(m.groupValues[3], m.groupValues[4]) ?: return@let
            return Reading(m.value.trim(), ConstraintOperator.BETWEEN, minOf(a, b), maxOf(a, b), "Between ${money(minOf(a, b))} and ${money(maxOf(a, b))}")
        }
        AROUND.find(query)?.let { m ->
            val v = value(m.groupValues[1], m.groupValues[2]) ?: return@let
            return Reading(m.value.trim(), ConstraintOperator.BETWEEN, v * 0.9, v * 1.1, "Around ${money(v)}")
        }
        ABOVE.find(query)?.let { m ->
            val v = value(m.groupValues[1], m.groupValues[2]) ?: return@let
            return Reading(m.value.trim(), ConstraintOperator.GREATER_THAN, v, Double.MAX_VALUE, "Over ${money(v)}")
        }
        HINGLISH_ABOVE.find(query)?.let { m ->
            val v = value(m.groupValues[1], m.groupValues[2]) ?: return@let
            return Reading(m.value.trim(), ConstraintOperator.GREATER_THAN, v, Double.MAX_VALUE, "Over ${money(v)}")
        }
        UNDER.find(query)?.let { m ->
            val v = value(m.groupValues[1], m.groupValues[2]) ?: return@let
            return Reading(m.value.trim(), ConstraintOperator.LESS_THAN, 0.0, v, "Under ${money(v)}")
        }
        HINGLISH_UNDER.find(query)?.let { m ->
            val v = value(m.groupValues[1], m.groupValues[2]) ?: return@let
            return Reading(m.value.trim(), ConstraintOperator.LESS_THAN, 0.0, v, "Under ${money(v)}")
        }
        return null
    }
}
