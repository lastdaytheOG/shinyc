package com.amar.vault

/**
 * One thing the app read into a query that is more than "look for this word": a period, an
 * order, a filter, a longer meaning for an abbreviation.
 *
 * Every such reading is a guess about what was meant, and the person who typed the query is
 * the one who knows. So each is shown to them ([label]) next to the words it was read from
 * ([typedAs]), and each can be taken back: with its [key] among the readings to leave alone,
 * the same words are looked for as plain words.
 */
data class Understood(
    val kind: Kind,
    /** The part of the query it was read from, as typed: "last month", "latest", "type:pdf". */
    val typedAs: String,
    /** What it was read as, in words for the screen: "September 2026", "Newest first", "PDFs only". */
    val label: String,
) {
    enum class Kind {
        /** A stretch of time: only items from it are listed. */
        PERIOD,
        /** Newest or oldest first. */
        ORDER,
        /** An amount of money to be over. */
        AMOUNT,
        /** A filter written out as one (`type:pdf`, `after:2024`). */
        FILTER,
        /** What an abbreviation stands for, looked for as well as the abbreviation. */
        MEANING,
    }

    val key: String get() = key(kind, typedAs)

    /** What its chip on the search screen says: the words typed, and what they were read as. */
    val chip: String
        get() = when (kind) {
            Kind.FILTER, Kind.MEANING -> label
            Kind.PERIOD, Kind.ORDER, Kind.AMOUNT -> "\u201C$typedAs\u201D read as $label"
        }

    /** True for a reading that narrows the list, and so can leave it empty. */
    val narrows: Boolean get() = kind == Kind.PERIOD || kind == Kind.AMOUNT || kind == Kind.FILTER

    companion object {
        fun key(kind: Kind, typedAs: String): String = "$kind:${typedAs.trim().lowercase()}"

        /** The words of the readings of [kind] among [keys]: each phrase, and each word of it. */
        internal fun wordsOf(keys: Set<String>, kind: Kind): Set<String> =
            keys.filter { it.startsWith("$kind:") }.map { it.substringAfter(':') }
                .flatMapTo(HashSet()) { phrase -> listOf(phrase) + phrase.split(' ') }
    }
}

/**
 * Tells an order from a word in a question put to the vault, by asking the vault.
 *
 * "last" is an order in "my last electricity bill" and part of what is looked for in "when is
 * the last working day". Nothing in the question says which. What the vault holds does: if
 * some page says "last working", the question is about those words.
 */
object OrderWordsInPhrases {
    private val NOT_A_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /**
     * The keys ([Understood.key]) of the order words in [question] that are to be read as
     * words: each one that [vaultSays] together with the word after it.
     */
    suspend fun asWords(question: String, vaultSays: suspend (phrase: String) -> Boolean): Set<String> {
        // Only the words that would be read as an order: the "last" of "last month" is not one.
        val readAsOrder = TemporalParser.parse(question).readings
            .filter { it.kind == Understood.Kind.ORDER }.mapTo(HashSet()) { it.typedAs }
        val words = question.lowercase().split(NOT_A_WORD).filter { it.isNotEmpty() }
        val keys = HashSet<String>()
        for (i in 0 until words.size - 1) {
            val word = words[i]
            if (word !in readAsOrder || word !in TemporalParser.ALSO_ORDINARY_WORDS || words[i + 1].length < 3) continue
            if (vaultSays("$word ${words[i + 1]}")) keys += Understood.key(Understood.Kind.ORDER, word)
        }
        return keys
    }
}

/** What the screen says about how a query was read, beyond the chips. */
object ReadingWords {

    /**
     * Said when a reading left nothing and the query was searched as plain words instead: the
     * list on screen is not what the reading asked for, and must not look as if it were.
     */
    fun droppedBecauseEmpty(dropped: List<Understood>): String? {
        val periods = dropped.filter { it.kind == Understood.Kind.PERIOD }
        val amounts = dropped.filter { it.kind == Understood.Kind.AMOUNT }
        return when {
            periods.isNotEmpty() && amounts.isNotEmpty() ->
                "Nothing from ${periods.joinToString(" and ") { it.label }} ${amounts.first().label.replaceFirstChar { it.lowercase() }}. " +
                    "Showing results for the words instead."
            periods.isNotEmpty() ->
                "Nothing from ${periods.joinToString(" and ") { it.label }}. Showing results for the words \"${periods.joinToString(" ") { it.typedAs }}\" instead."
            amounts.isNotEmpty() ->
                "Nothing ${amounts.first().label.replaceFirstChar { it.lowercase() }}. Showing results for the words instead."
            else -> null
        }
    }

    /** Said when filters written into the query hide every result there is. */
    fun hiddenByFilters(hidden: Int, filters: List<Understood>): String {
        val what = filters.joinToString(", ") { it.label }
        return if (hidden == 1) "1 result is hidden by the filter: $what. Tap the filter to remove it."
        else "$hidden results are hidden by the filter: $what. Tap the filter to remove it."
    }

    /** For an answer: what was read into the question, and how to say it was meant as words. */
    fun forAnAnswer(understood: List<Understood>): String? {
        val shown = understood.filter { it.kind != Understood.Kind.MEANING }
        if (shown.isEmpty()) return null
        val readings = shown.joinToString("; ") { "\"${it.typedAs}\" as ${inASentence(it)}" }
        return "I read $readings. If you meant the words themselves, put them in quotes."
    }

    /** A reading's label inside a sentence: a month keeps its capital, "Newest first" does not. */
    private fun inASentence(reading: Understood): String =
        if (reading.kind == Understood.Kind.PERIOD) reading.label else reading.label.replaceFirstChar { it.lowercase() }

    /** For an answer: nothing is in the period that was asked about. */
    fun nothingInPeriod(understood: List<Understood>): String? {
        val narrowing = understood.filter { it.kind == Understood.Kind.PERIOD || it.kind == Understood.Kind.AMOUNT }
        if (narrowing.isEmpty()) return null
        val what = narrowing.joinToString(" and ") { u ->
            if (u.kind == Understood.Kind.PERIOD) "from ${u.label}" else u.label.replaceFirstChar { it.lowercase() }
        }
        return "Nothing in your vault $what matches. I read ${narrowing.joinToString(" and ") { "\"${it.typedAs}\"" }} " +
            "as a filter; if you meant the words themselves, put them in quotes."
    }
}
