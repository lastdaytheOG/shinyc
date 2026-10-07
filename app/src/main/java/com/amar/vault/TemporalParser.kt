package com.amar.vault

import java.util.Calendar

object TemporalParser {

    private val LATEST_WORDS = listOf("latest", "नवीनतम", "newest", "recent", "last", "current", "latest")
    private val OLDEST_WORDS = listOf("oldest", "सबसे पुराना", "first", "पहला", "earliest")
    
    // Time relative expressions
    private val YESTERDAY = listOf("yesterday", "कल", "yesterday's", "kal")
    private val TODAY = listOf("today", "आज", "today's", "aaj")
    private val DAY_BEFORE_YESTERDAY = listOf("parso", "परसों")
    private val LAST_MONTH = listOf("last month", "पिछले महीने", "pichle mahine", "last mahina")
    private val THIS_MONTH = listOf("this month", "इस महीने", "is mahine")
    private val LAST_WEEK = listOf("last week", "पिछले सप्ताह", "पिछले हफ्ते", "pichle hafte")
    private val THIS_WEEK = listOf("this week", "इस सप्ताह", "इस हफ्ते", "is hafte")
    
    private val MONTHS = mapOf(
        "january" to Calendar.JANUARY, "जनवरी" to Calendar.JANUARY, "jan" to Calendar.JANUARY,
        "february" to Calendar.FEBRUARY, "फ़रवरी" to Calendar.FEBRUARY, "feb" to Calendar.FEBRUARY,
        "march" to Calendar.MARCH, "मार्च" to Calendar.MARCH, "mar" to Calendar.MARCH,
        "april" to Calendar.APRIL, "अप्रैल" to Calendar.APRIL, "apr" to Calendar.APRIL,
        "may" to Calendar.MAY, "मई" to Calendar.MAY,
        "june" to Calendar.JUNE, "जून" to Calendar.JUNE, "jun" to Calendar.JUNE,
        "july" to Calendar.JULY, "जुलाई" to Calendar.JULY, "jul" to Calendar.JULY,
        "august" to Calendar.AUGUST, "अगस्त" to Calendar.AUGUST, "aug" to Calendar.AUGUST,
        "september" to Calendar.SEPTEMBER, "सितंबर" to Calendar.SEPTEMBER, "sep" to Calendar.SEPTEMBER, "sept" to Calendar.SEPTEMBER,
        "october" to Calendar.OCTOBER, "अक्टूबर" to Calendar.OCTOBER, "oct" to Calendar.OCTOBER,
        "november" to Calendar.NOVEMBER, "नवंबर" to Calendar.NOVEMBER, "nov" to Calendar.NOVEMBER,
        "december" to Calendar.DECEMBER, "दिसंबर" to Calendar.DECEMBER, "dec" to Calendar.DECEMBER
    )

    /**
     * Ordering words that are as often part of what is being looked for as they are an
     * instruction: "last working day", "first mid term", "current affairs", "recent posts".
     */
    private val ALSO_ORDINARY_WORDS = setOf("last", "first", "recent", "current", "पहला")

    /**
     * [wordsFirst] is for text typed into the search box, where the words are, first of all,
     * what the user is looking for. There the words in [ALSO_ORDINARY_WORDS] are never read as
     * "newest"/"oldest" — typing "last" used to find nothing at all, and "last working days"
     * came back as "working days" in date order — and a query that is nothing but an ordering
     * word ("latest") is a search for that word. A question put to the vault ("my last
     * electricity bill") still reads them as an order.
     */
    fun parse(query: String, wordsFirst: Boolean = false): TemporalParseResult {
        var cleanedQuery = query.lowercase().trim()
        var sort: SortOrder? = null
        var limit: Int? = null
        var timeRange: TimeRange? = null
        var confidence = 0.0f

        // \b treats many Indic vowel marks as non-word characters, which can place a false
        // boundary inside a Devanagari word (for example the final matra in "पिछले").
        // Define a term boundary explicitly over letters, marks, numbers and underscore instead.
        fun termRegex(term: String): Regex = Regex(
            "(?<![\\p{L}\\p{M}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{M}\\p{N}_])"
        )
        
        fun getDayBounds(cal: Calendar): TimeRange {
            val start = cal.clone() as Calendar
            start.set(Calendar.HOUR_OF_DAY, 0)
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            start.set(Calendar.MILLISECOND, 0)
            
            val end = cal.clone() as Calendar
            end.set(Calendar.HOUR_OF_DAY, 23)
            end.set(Calendar.MINUTE, 59)
            end.set(Calendar.SECOND, 59)
            end.set(Calendar.MILLISECOND, 999)
            
            return TimeRange(start.timeInMillis, end.timeInMillis)
        }

        fun getMonthBounds(cal: Calendar): TimeRange {
            val start = cal.clone() as Calendar
            start.set(Calendar.DAY_OF_MONTH, 1)
            start.set(Calendar.HOUR_OF_DAY, 0)
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            start.set(Calendar.MILLISECOND, 0)
            
            val end = cal.clone() as Calendar
            end.set(Calendar.DAY_OF_MONTH, end.getActualMaximum(Calendar.DAY_OF_MONTH))
            end.set(Calendar.HOUR_OF_DAY, 23)
            end.set(Calendar.MINUTE, 59)
            end.set(Calendar.SECOND, 59)
            end.set(Calendar.MILLISECOND, 999)
            
            return TimeRange(start.timeInMillis, end.timeInMillis)
        }

        fun getWeekBounds(cal: Calendar): TimeRange {
            val start = cal.clone() as Calendar
            start.set(Calendar.DAY_OF_WEEK, start.firstDayOfWeek)
            start.set(Calendar.HOUR_OF_DAY, 0)
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            start.set(Calendar.MILLISECOND, 0)
            
            val end = start.clone() as Calendar
            end.add(Calendar.DAY_OF_WEEK, 6)
            end.set(Calendar.HOUR_OF_DAY, 23)
            end.set(Calendar.MINUTE, 59)
            end.set(Calendar.SECOND, 59)
            end.set(Calendar.MILLISECOND, 999)
            
            return TimeRange(start.timeInMillis, end.timeInMillis)
        }
        
        val now = Calendar.getInstance()

        // 1. Evaluate relative expressions (multi-word and single-word date-ranges) first
        for (word in LAST_MONTH) {
            val regex = termRegex(word)
            if (regex.containsMatchIn(cleanedQuery)) {
                val cal = now.clone() as Calendar
                cal.add(Calendar.MONTH, -1)
                timeRange = getMonthBounds(cal)
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }
        
        for (word in THIS_MONTH) {
            val regex = termRegex(word)
            if (regex.containsMatchIn(cleanedQuery)) {
                val cal = now.clone() as Calendar
                timeRange = getMonthBounds(cal)
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }

        for (word in LAST_WEEK) {
            val regex = termRegex(word)
            if (regex.containsMatchIn(cleanedQuery)) {
                val cal = now.clone() as Calendar
                cal.add(Calendar.WEEK_OF_YEAR, -1)
                timeRange = getWeekBounds(cal)
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }

        for (word in THIS_WEEK) {
            val regex = termRegex(word)
            if (regex.containsMatchIn(cleanedQuery)) {
                val cal = now.clone() as Calendar
                timeRange = getWeekBounds(cal)
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }
        
        for (word in YESTERDAY) {
            val regex = termRegex(word)
            if (regex.containsMatchIn(cleanedQuery)) {
                val cal = now.clone() as Calendar
                cal.add(Calendar.DAY_OF_YEAR, -1)
                timeRange = getDayBounds(cal)
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }
        
        for (word in TODAY) {
            val regex = termRegex(word)
            if (regex.containsMatchIn(cleanedQuery)) {
                val cal = now.clone() as Calendar
                timeRange = getDayBounds(cal)
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }

        for (word in DAY_BEFORE_YESTERDAY) {
            val regex = termRegex(word)
            if (regex.containsMatchIn(cleanedQuery)) {
                val cal = now.clone() as Calendar
                cal.add(Calendar.DAY_OF_YEAR, -2)
                timeRange = getDayBounds(cal)
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }

        // 2. Evaluate year and month parsing
        // A year counts only as a word of its own. Inside a longer token it is part of a name
        // ("Aadhaar_Card-2024.pdf", "2024-25"), and cutting it out left a query no file matched.
        val yearRegex = Regex("(?<!\\S)(20\\d{2})(?=[?.!,;:]?(?:\\s|$))")
        val yearMatch = yearRegex.find(cleanedQuery)
        var parsedYear: Int? = null
        val calendarTerms = mutableListOf<String>()
        if (yearMatch != null) {
            parsedYear = yearMatch.groupValues[1].toInt()
            calendarTerms.add(yearMatch.groupValues[1])
            cleanedQuery = cleanedQuery.replaceRange(yearMatch.range, "").trim()
            confidence = maxOf(confidence, 0.9f)
        }

        var parsedMonth: Int? = null
        for ((word, calendarMonth) in MONTHS) {
            val regex = termRegex(word)
            if (regex.containsMatchIn(cleanedQuery)) {
                parsedMonth = calendarMonth
                calendarTerms.add(word)
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 0.7f)
                break
            }
        }
        
        if (parsedYear != null || parsedMonth != null) {
            val cal = now.clone() as Calendar
            if (parsedYear != null) {
                cal.set(Calendar.YEAR, parsedYear)
            } else if (parsedMonth != null) {
                if (parsedMonth > now.get(Calendar.MONTH)) {
                    cal.add(Calendar.YEAR, -1)
                }
            }
            if (parsedMonth != null) {
                cal.set(Calendar.MONTH, parsedMonth)
                timeRange = getMonthBounds(cal)
            } else {
                val start = cal.clone() as Calendar
                start.set(Calendar.MONTH, Calendar.JANUARY)
                start.set(Calendar.DAY_OF_MONTH, 1)
                start.set(Calendar.HOUR_OF_DAY, 0)
                start.set(Calendar.MINUTE, 0)
                start.set(Calendar.SECOND, 0)
                start.set(Calendar.MILLISECOND, 0)
                
                val end = cal.clone() as Calendar
                end.set(Calendar.MONTH, Calendar.DECEMBER)
                end.set(Calendar.DAY_OF_MONTH, 31)
                end.set(Calendar.HOUR_OF_DAY, 23)
                end.set(Calendar.MINUTE, 59)
                end.set(Calendar.SECOND, 59)
                end.set(Calendar.MILLISECOND, 999)
                timeRange = TimeRange(start.timeInMillis, end.timeInMillis)
            }
        }
        
        // 3. Evaluate ordering (latest / oldest) limits after relative date parsing
        val beforeOrdering = cleanedQuery
        val confidenceBeforeOrdering = confidence
        for (word in LATEST_WORDS) {
            if (wordsFirst && word in ALSO_ORDINARY_WORDS) continue
            val regex = termRegex(word)
            if (regex.containsMatchIn(cleanedQuery)) {
                sort = SortOrder.DESC
                if (word == "latest" || word == "नवीनतम" || word == "newest" || word == "last" || word == "current") {
                    limit = 1
                }
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }
        
        for (word in OLDEST_WORDS) {
            if (wordsFirst && word in ALSO_ORDINARY_WORDS) continue
            val regex = Regex("\\b$word\\b")
            if (regex.containsMatchIn(cleanedQuery)) {
                sort = SortOrder.ASC
                if (word == "first" || word == "पहला" || word == "oldest" || word == "सबसे पुराना" || word == "earliest") {
                    limit = 1
                }
                cleanedQuery = cleanedQuery.replace(regex, "").trim()
                confidence = maxOf(confidence, 1.0f)
            }
        }

        // Nothing left to order, and no period asked for: the ordering word was the search.
        if (wordsFirst && sort != null && timeRange == null && cleanedQuery.isBlank()) {
            cleanedQuery = beforeOrdering
            confidence = confidenceBeforeOrdering
            sort = null
            limit = null
        }

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
            calendarTerms = calendarTerms
        )
    }
}
