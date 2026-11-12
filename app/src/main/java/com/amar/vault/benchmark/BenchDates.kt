package com.amar.vault.benchmark

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class BenchDates(private val now: Long = System.currentTimeMillis()) {

    fun at(monthsAgo: Int?, day: Int?, daysAgo: Int?): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (daysAgo != null) {
            c.add(Calendar.DAY_OF_YEAR, -daysAgo)
            c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return minOf(c.timeInMillis, now - HOUR)
        }
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, -(monthsAgo ?: 0))
        c.set(Calendar.DAY_OF_MONTH, minOf(day ?: 1, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return minOf(c.timeInMillis, now - HOUR)
    }

    fun resolve(text: String): String = TOKEN.replace(text) { m ->
        val kind = m.groupValues[1]
        val args = m.groupValues[2].split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        when (kind) {
            "D" -> SimpleDateFormat(args["f"] ?: "d MMM yyyy", Locale.ENGLISH)
                .format(at(args["m"]?.toInt(), args["d"]?.toInt(), args["days"]?.toInt()))
            "M" -> SimpleDateFormat("MMMM", Locale.ENGLISH).format(at(args["m"]?.toInt() ?: 0, 15, null))
            else -> m.value
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
        val TOKEN = Regex("⟦([A-Z]+):([^⟧]*)⟧")
    }
}
