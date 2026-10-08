package com.amar.vault

import java.util.Calendar

/**
 * Reads the words of a query that say when ("last month", "कल", "march 2024") or in what
 * order ("latest", "oldest"), in English, Hindi and Hindi written in Latin letters.
 *
 * What it reads is a guess: "last" is an order in "my last electricity bill" and a plain word
 * in "last working day"; "may" is a month in "may 2024" and a verb nearly everywhere else. So
 * each reading is reported ([TemporalParseResult.readings]) for the caller to show, and each
 * can be switched off: by the caller, with the reading's key among `asWords`, or by whoever
 * typed the query, by putting the words in double quotes.
 */
object TemporalParser {

    private class Period(val phrases: List<String>, val range: (Calendar) -> TimeRange, val label: (TimeRange) -> String)

    /** Words that ask for the newest first; those in [ONLY_ONE] ask for the newest one. */
    private val LATEST_WORDS = listOf("latest", "नवीनतम", "newest", "recent", "last", "current")
    private val OLDEST_WORDS = listOf("oldest", "सबसे पुराना", "first", "पहला", "earliest")
    private val ONLY_ONE = setOf("latest", "नवीनतम", "newest", "last", "current", "first", "पहला", "oldest", "सबसे पुराना", "earliest")

    /**
     * Ordering words that are as often part of what is being looked for as they are an
     * instruction: "last working day", "first mid term", "current affairs", "recent posts".
     */
    internal val ALSO_ORDINARY_WORDS = setOf("last", "first", "recent", "current", "पहला")

    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    private val MONTHS = linkedMapOf(
        "january" to Calendar.JANUARY, "जनवरी" to Calendar.JANUARY, "jan" to Calendar.JANUARY,
        "february" to Calendar.FEBRUARY, "फ़रवरी" to Calendar.FEBRUARY, "फरवरी" to Calendar.FEBRUARY, "feb" to Calendar.FEBRUARY,
        "march" to Calendar.MARCH, "मार्च" to Calendar.MARCH, "mar" to Calendar.MARCH,
        "april" to Calendar.APRIL, "अप्रैल" to Calendar.APRIL, "apr" to Calendar.APRIL,
        "may" to Calendar.MAY, "मई" to Calendar.MAY,
        "june" to Calendar.JUNE, "जून" to Calendar.JUNE, "jun" to Calendar.JUNE,
        "july" to Calendar.JULY, "जुलाई" to Calendar.JULY, "jul" to Calendar.JULY,
        "august" to Calendar.AUGUST, "अगस्त" to Calendar.AUGUST, "aug" to Calendar.AUGUST,
        "september" to Calendar.SEPTEMBER, "सितंबर" to Calendar.SEPTEMBER, "sep" to Calendar.SEPTEMBER, "sept" to Calendar.SEPTEMBER,
        "october" to Calendar.OCTOBER, "अक्टूबर" to Calendar.OCTOBER, "oct" to Calendar.OCTOBER,
        "november" to Calendar.NOVEMBER, "नवंबर" to Calendar.NOVEMBER, "nov" to Calendar.NOVEMBER,
        "december" to Calendar.DECEMBER, "दिसंबर" to Calendar.DECEMBER, "dec" to Calendar.DECEMBER,
    )

    /** In the order they are looked for; where two are in one query the later one is the period. */
    private val PERIODS = listOf(
        Period(listOf("last month", "पिछले महीने", "pichle mahine", "last mahina"), { monthOf(it, -1) }, ::monthLabel),
        Period(listOf("this month", "इस महीने", "is mahine"), { monthOf(it, 0) }, ::monthLabel),
        Period(listOf("last week", "पिछले सप्ताह", "पिछले हफ्ते", "pichle hafte"), { weekOf(it, -1) }, { "Last week (${spanLabel(it)})" }),
        Period(listOf("this week", "इस सप्ताह", "इस हफ्ते", "is hafte"), { weekOf(it, 0) }, { "This week (${spanLabel(it)})" }),
        Period(listOf("yesterday", "कल", "yesterday's", "kal"), { dayOf(it, -1) }, { "Yesterday (${dayLabel(it.startTimeMs)})" }),
        Period(listOf("today", "आज", "today's", "aaj"), { dayOf(it, 0) }, { "Today (${dayLabel(it.startTimeMs)})" }),
        Period(listOf("parso", "परसों"), { dayOf(it, -2) }, { "Two days ago (${dayLabel(it.startTimeMs)})" }),
    )

    /**
     * A year counts only as a word of its own. Inside a longer token it is part of a name
     * ("Aadhaar_Card-2024.pdf", "2024-25"), and cutting it out left a query no file matched.
     */
    private val YEAR = Regex("(?<!\\S)(20\\d{2})(?=[?.!,;:]?(?:\\s|$))")

    /** What makes "may" the month: a day next to it, or a word that leads up to a date. */
    private val MAY_AS_A_MONTH = Regex(
        "(?:(?<![\\p{L}\\p{M}\\p{N}_])(?:\\d{1,2}(?:st|nd|rd|th)?|in|of|from|since|during|till|until|before|after)\\s+may(?![\\p{L}\\p{M}\\p{N}_]))" +
            "|(?:(?<![\\p{L}\\p{M}\\p{N}_])may\\s+\\d{1,2}(?:st|nd|rd|th)?(?![\\p{L}\\p{M}\\p{N}_]))"
    )

    private val QUOTED = Regex("\"([^\"]*)\"")
    /** Stands in for a quoted stretch while the rest is read: no letter, mark or digit. */
    private const val HELD = ''

    // \b treats many Indic vowel marks as non-word characters, which can place a false
    // boundary inside a Devanagari word (for example the final matra in "पिछले").
    // Define a term boundary explicitly over letters, marks, numbers and underscore instead.
    private fun termRegex(term: String): Regex = Regex(
        "(?<![\\p{L}\\p{M}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{M}\\p{N}_])"
    )

    /**
     * [wordsFirst] is for text typed into the search box, where the words are, first of all,
     * what the user is looking for. There the words in [ALSO_ORDINARY_WORDS] are never read as
     * "newest"/"oldest" — typing "last" used to find nothing at all, and "last working days"
     * came back as "working days" in date order — and a query that is nothing but an ordering
     * word ("latest") is a search for that word. A question put to the vault ("my last
     * electricity bill") still reads them as an order.
     *
     * [asWords] holds the keys of readings to leave alone ([Understood.key]): those words stay
     * in the query as words. Anything typed in double quotes is left alone as well.
     */
    fun parse(
        query: String,
        wordsFirst: Boolean = false,
        asWords: Set<String> = emptySet(),
        now: Calendar = Calendar.getInstance(),
    ): TemporalParseResult {
        // What is in quotes is held aside and put back, without the quotes, at the end.
        val held = ArrayList<String>()
        var cleanedQuery = QUOTED.replace(query.lowercase().trim()) { match ->
            held += match.groupValues[1]
            "$HELD${held.size - 1}$HELD"
        }
        val periodWordsOff = Understood.wordsOf(asWords, Understood.Kind.PERIOD)
        val orderWordsOff = Understood.wordsOf(asWords, Understood.Kind.ORDER)
        val readings = ArrayList<Understood>()

        var sort: SortOrder? = null
        var limit: Int? = null
        var timeRange: TimeRange? = null
        var confidence = 0.0f

        // 1. Evaluate relative expressions (multi-word and single-word date-ranges) first
        for (period in PERIODS) {
            for (phrase in period.phrases) {
                if (phrase in periodWordsOff) continue
                val regex = termRegex(phrase)
                if (!regex.containsMatchIn(cleanedQuery)) continue
                val range = period.range(now)
                timeRange = range
                readings += Understood(Understood.Kind.PERIOD, phrase, period.label(range))
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }

        // 2. Evaluate year and month parsing
        val yearMatch = YEAR.find(cleanedQuery)?.takeIf { it.groupValues[1] !in periodWordsOff }
        var parsedYear: Int? = null
        val calendarTerms = mutableListOf<String>()
        if (yearMatch != null) {
            parsedYear = yearMatch.groupValues[1].toInt()
            calendarTerms.add(yearMatch.groupValues[1])
        }

        var parsedMonth: Int? = null
        var monthWord: String? = null
        for ((word, calendarMonth) in MONTHS) {
            if (word in periodWordsOff) continue
            if (!termRegex(word).containsMatchIn(cleanedQuery)) continue
            // "may" is a month beside a year or a day, or after a word that leads up to a date.
            if (word == "may" && parsedYear == null && !MAY_AS_A_MONTH.containsMatchIn(cleanedQuery)) continue
            parsedMonth = calendarMonth
            monthWord = word
            break
        }

        if (yearMatch != null) {
            cleanedQuery = cleanedQuery.replaceRange(yearMatch.range, "").trim()
            confidence = maxOf(confidence, 0.9f)
        }
        if (monthWord != null) {
            calendarTerms.add(monthWord)
            cleanedQuery = cleanedQuery.replace(termRegex(monthWord), "").trim()
            confidence = maxOf(confidence, 0.7f)
        }

        if (parsedYear != null || parsedMonth != null) {
            val cal = now.clone() as Calendar
            if (parsedYear != null) {
                cal.set(Calendar.YEAR, parsedYear)
            } else if (parsedMonth != null && parsedMonth > now.get(Calendar.MONTH)) {
                // A month that has not come yet this year is last year's.
                cal.add(Calendar.YEAR, -1)
            }
            val range: TimeRange
            val label: String
            if (parsedMonth != null) {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.MONTH, parsedMonth)
                range = monthBounds(cal)
                label = monthLabel(range)
            } else {
                val start = (cal.clone() as Calendar).apply { set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1) }
                val end = (cal.clone() as Calendar).apply { set(Calendar.MONTH, Calendar.DECEMBER); set(Calendar.DAY_OF_MONTH, 31) }
                range = TimeRange(startOfDay(start), endOfDay(end))
                label = parsedYear.toString()
            }
            timeRange = range
            // As typed: the month before the year, whichever was typed first.
            readings += Understood(Understood.Kind.PERIOD, listOfNotNull(monthWord, parsedYear?.toString()).joinToString(" "), label)
        }

        // 3. Evaluate ordering (latest / oldest) limits after relative date parsing
        val beforeOrdering = cleanedQuery
        val confidenceBeforeOrdering = confidence
        val readingsBeforeOrdering = readings.size
        fun readOrder(words: List<String>, order: SortOrder, label: String) {
            for (word in words) {
                if (wordsFirst && word in ALSO_ORDINARY_WORDS) continue
                // A word of a period that was taken back is a word too: with "last month" left
                // as words, "last" is not then read as "the newest".
                if (word in orderWordsOff || word in periodWordsOff) continue
                val regex = termRegex(word)
                if (!regex.containsMatchIn(cleanedQuery)) continue
                sort = order
                if (word in ONLY_ONE) limit = 1
                readings += Understood(Understood.Kind.ORDER, word, label)
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }
        readOrder(LATEST_WORDS, SortOrder.DESC, "Newest first")
        readOrder(OLDEST_WORDS, SortOrder.ASC, "Oldest first")

        // Nothing left to order, and no period asked for: the ordering word was the search.
        if (wordsFirst && sort != null && timeRange == null && cleanedQuery.isBlank()) {
            cleanedQuery = beforeOrdering
            confidence = confidenceBeforeOrdering
            sort = null
            limit = null
            while (readings.size > readingsBeforeOrdering) readings.removeAt(readings.size - 1)
        }

        held.forEachIndexed { i, words -> cleanedQuery = cleanedQuery.replace("$HELD$i$HELD", words) }
        cleanedQuery = cleanedQuery
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([?.!,;:])"), "$1")
            .trim()

        val intent = if (sort != null || timeRange != null) {
            TemporalIntent(timeRange = timeRange, sort = sort, limit = limit)
        } else {
            null
        }

        return TemporalParseResult(
            cleanedQuery = cleanedQuery,
            intent = intent,
            confidence = confidence,
            calendarTerms = calendarTerms,
            readings = readings,
        )
    }

    // ── Periods ─────────────────────────────────────────────────────────────────────────

    private fun startOfDay(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun endOfDay(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    private fun dayOf(now: Calendar, daysFromToday: Int): TimeRange {
        val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, daysFromToday) }
        return TimeRange(startOfDay(day), endOfDay(day))
    }

    private fun monthBounds(cal: Calendar): TimeRange {
        val start = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val end = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)) }
        return TimeRange(startOfDay(start), endOfDay(end))
    }

    private fun monthOf(now: Calendar, monthsFromThis: Int): TimeRange =
        monthBounds((now.clone() as Calendar).apply { add(Calendar.MONTH, monthsFromThis) })

    private fun weekOf(now: Calendar, weeksFromThis: Int): TimeRange {
        val start = (now.clone() as Calendar).apply {
            add(Calendar.WEEK_OF_YEAR, weeksFromThis)
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_WEEK, 6) }
        return TimeRange(startOfDay(start), endOfDay(end))
    }

    // ── What a period is called on screen ───────────────────────────────────────────────

    private fun at(timeMs: Long): Calendar = Calendar.getInstance().apply { timeInMillis = timeMs }

    private fun monthLabel(range: TimeRange): String =
        at(range.startTimeMs).let { "${MONTH_NAMES[it.get(Calendar.MONTH)]} ${it.get(Calendar.YEAR)}" }

    private fun dayLabel(timeMs: Long): String =
        at(timeMs).let { "${it.get(Calendar.DAY_OF_MONTH)} ${MONTH_NAMES[it.get(Calendar.MONTH)]}" }

    private fun spanLabel(range: TimeRange): String {
        val from = at(range.startTimeMs)
        val to = at(range.endTimeMs)
        fun short(c: Calendar) = "${c.get(Calendar.DAY_OF_MONTH)} ${MONTH_NAMES[c.get(Calendar.MONTH)].take(3)}"
        return "${short(from)} to ${short(to)}"
    }
}
