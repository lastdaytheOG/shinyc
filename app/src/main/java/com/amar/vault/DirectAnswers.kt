package com.amar.vault

/**
 * An answer read straight off a stored page, with the words it was read from and where they
 * are — no language model involved.
 */
data class DirectAnswer(
    /** What the chat bubble says. */
    val text: String,
    /** The row the answer was read from; it is the first of the answer's sources. */
    val sourceId: String,
    /** The words around the answer, as they stand on the page. */
    val quote: String,
    /** The fact itself when the question asked for one and the page has it ("27 November 2026"); else null. */
    val fact: String? = null,
)

/**
 * Answers a question from the pages search found for it, by reading them.
 *
 * Most questions put to a vault of one's own papers ask for a fact that is written down
 * somewhere: a date, an amount, a number. Finding the page is search; the fact is then a few
 * characters next to the words asked about, and picking it out needs no model, no download
 * and no waiting. What is said always comes with the words it was read from and their page,
 * so it can be checked with one tap.
 *
 * It claims no more than it read: when the page has no fact of the kind asked for, it says
 * so and shows what the page does say.
 */
object DirectAnswers {

    /** The kinds of fact a question can ask for, each with how it is told and how it looks on a page. */
    enum class Fact(val asked: Regex, val onPage: Regex, val called: String) {
        PAN(word("pan"), Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b"), "PAN"),
        AADHAAR(word("aadhaar|aadhar|adhar|adhaar"), Regex("\\b\\d{4}\\s\\d{4}\\s\\d{4}\\b"), "Aadhaar number"),
        IFSC(word("ifsc"), Regex("\\b[A-Z]{4}0[A-Z0-9]{6}\\b"), "IFSC code"),
        UPI(word("upi"), Regex("\\b[\\w.\\-]{2,}@[a-zA-Z]{2,}\\b(?!\\.)"), "UPI id"),
        EMAIL(word("email|e-mail|mail id|gmail"), Regex("\\b[\\w.+\\-]+@[\\w\\-]+(?:\\.[\\w\\-]+)+\\b"), "email address"),
        OTP(word("otp|verification code|one time password"), Regex("\\b\\d{4,8}\\b"), "code"),
        PHONE(
            word("phone|mobile|contact number|phone number"),
            Regex("(?:\\+91[\\s-]?)?\\b[6-9]\\d{4}[\\s-]?\\d{5}\\b"), "phone number",
        ),
        REFERENCE(
            word("reference|transaction id|transaction number|order id|order number|invoice number|invoice no|bill number|bill no|receipt number|receipt no|pnr|booking id"),
            Regex("(?i)(?:\\b(?:no|number|id|ref|reference|pnr)\\b\\.?\\s*[:#\\-]?\\s*)((?=[A-Z0-9\\-/]*\\d)[A-Z0-9][A-Z0-9\\-/]{2,})|\\b[A-Z]{1,6}\\d[A-Z0-9]{6,}\\b"),
            "number",
        ),
        AMOUNT(
            word("how much|amount|price|cost|fee|fees|total|paid|pay|charge|charges|rupees|kitna|kitne|kitni"),
            Regex("(?i)(?:₹|\\brs\\.?|\\binr)\\s?[\\d,]+(?:\\.\\d{1,2})?|\\b[\\d,]+(?:\\.\\d{1,2})?\\s?(?:rupees\\b|/-)"),
            "amount",
        ),
        DATE(
            word("when|date|deadline|due|which day|what day|kab|tarikh"),
            Regex(
                "(?i)\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?,?\\s+\\d{4}\\b" +
                    "|\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4}\\b" +
                    "|\\b\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b"
            ),
            "date",
        ),
    }

    private fun word(alternatives: String) = Regex("(?i)(?<![\\p{L}\\p{N}])(?:$alternatives)(?![\\p{L}\\p{N}])")

    /** Words of a question that ask, rather than say what is asked about. English and Hinglish. */
    private val ASKING_WORDS = setOf(
        "what", "whats", "when", "where", "which", "who", "whose", "how", "much", "many", "is", "are", "was", "were",
        "the", "a", "an", "of", "for", "to", "in", "on", "at", "from", "by", "with", "and", "or", "my", "me", "i", "did",
        "do", "does", "it", "that", "this", "tell", "show", "find", "give", "get", "please", "about", "any", "there",
        "number", "no", "id", "date", "amount", "day", "pay", "paid",
        "kya", "hai", "tha", "thi", "ka", "ki", "ke", "ko", "se", "mera", "meri", "mere", "kab", "kitna", "kitne", "kitni",
        "batao", "bata", "dikhao", "kahan", "kaun",
    )
    private val NOT_A_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")
    private const val WINDOW = 160
    private const val PAGES_READ = 8

    private val HOW_MUCH = word("how much|kitna|kitne|kitni")
    private val WHEN = word("when|kab|what date|which date|which day|what day")

    /**
     * The fact [question] asks for, if it asks for one. "How much" and "when" settle it
     * whatever else the question says: "when did I pay the fee" asks for a date, "how much is
     * due" for an amount.
     */
    fun factAsked(question: String): Fact? = when {
        HOW_MUCH.containsMatchIn(question) -> Fact.AMOUNT
        WHEN.containsMatchIn(question) -> Fact.DATE
        // Of several kinds named, the one named first is the one asked for: "date of birth on
        // my PAN card" asks for a date, "PAN number" for the PAN.
        else -> Fact.entries.mapNotNull { fact -> fact.asked.find(question)?.let { fact to it.range.first } }
            .minByOrNull { it.second }?.first
    }

    /**
     * What [question] is about: its words without the ones that only ask. These are what the
     * pages are searched for — searching for "when", "is" and "the" as well put the pages that
     * say "the" most often in front.
     */
    fun aboutWords(question: String): List<String> {
        val words = question.lowercase().split(NOT_A_WORD).filter { it.length >= 2 }
        val about = words.filter { it !in ASKING_WORDS }.distinct()
        // A question that is only asking words ("what is my pan number") is about the thing it names.
        return about.ifEmpty { words.filter { it.length >= 3 && it !in setOf("what", "the", "how", "who", "when") }.distinct() }
    }

    /**
     * The answer to [question] from [pages], the rows search found for it, best first. Null
     * when there are none, or none of them says anything the question is about.
     */
    fun answer(question: String, pages: List<VaultItem>): DirectAnswer? {
        val about = aboutWords(question)
        val fact = factAsked(question)
        if (pages.isEmpty() || (about.isEmpty() && fact == null)) return null

        // Where, on which page, the most of what is asked about stands together.
        class Place(val page: VaultItem, val text: String, val at: Int, val words: Int, val span: Int)
        var best: Place? = null
        for (page in pages.take(PAGES_READ)) {
            val text = page.ocrText.replace(WHITESPACE, " ").trim()
            if (text.isEmpty()) continue
            val lowered = text.lowercase()
            // Every place a word asked about stands, and from each the words that follow close by.
            val starts = about.flatMap { word -> occurrences(lowered, word) }.distinct().sorted()
            var place = Place(page, text, 0, 0, 0)
            for (start in starts) {
                val next = about.mapNotNull { word -> lowered.indexOf(word, start).takeIf { it in start until start + WINDOW } }
                // More of the words wins; among equals, the place where they stand closest
                // together — "second mid term" is at "Second Mid Term", not at the "Mid Term"
                // of the line before it.
                val span = (next.maxOrNull() ?: start) - start
                if (next.size > place.words || (next.size == place.words && span < place.span)) {
                    place = Place(page, text, start, next.size, span)
                }
            }
            if (best == null || place.words > best.words || (place.words == best.words && place.words > 0 && place.span < best.span)) best = place
            if (place.words == about.size && about.isNotEmpty()) break
        }
        val place = best ?: return null
        if (place.words == 0 && (fact == null || !fact.onPage.containsMatchIn(place.text))) return null

        val source = sourceOf(place.page)
        val quote = quoteAround(place.text, place.at)
        if (fact == null) {
            return DirectAnswer("“$quote”\n\n$source", place.page.id, quote)
        }
        val value = valueNear(fact, place.text, place.at)
            // The page that says the most about it may not be the one with the number on it.
            ?: return pages.take(PAGES_READ).firstNotNullOfOrNull { other ->
                val text = other.ocrText.replace(WHITESPACE, " ").trim()
                val lowered = text.lowercase()
                val at = about.firstNotNullOfOrNull { lowered.indexOf(it).takeIf { i -> i >= 0 } } ?: return@firstNotNullOfOrNull null
                valueNear(fact, text, at)?.let { found ->
                    val words = quoteAround(text, at)
                    DirectAnswer("$found\n\n“$words”\n\n${sourceOf(other)}", other.id, words, found)
                }
            } ?: DirectAnswer(
                "I found the page, but no ${fact.called} on it. It says:\n\n“$quote”\n\n$source", place.page.id, quote,
            )
        return DirectAnswer("$value\n\n“$quote”\n\n$source", place.page.id, quote, value)
    }

    /**
     * The [fact] nearest to position [at] in [text]: the first one that follows within a
     * line's length — a label is followed by its value — and otherwise the closest either way
     * within a few lines. Null when the page has none near.
     */
    internal fun valueNear(fact: Fact, text: String, at: Int): String? {
        val values = fact.onPage.findAll(text).map { match ->
            // A pattern with a label in front of the value keeps the value alone.
            val value = (if (match.groups.size > 1) match.groups[1]?.value else null) ?: match.value
            match.range.first to value.trim()
        }.filter { (_, value) -> fact != Fact.OTP || !(value.startsWith("19") || value.startsWith("20")) || value.length != 4 }
            .toList()
        if (values.isEmpty()) return null
        values.firstOrNull { (start, _) -> start in at..(at + WINDOW) }?.let { return it.second }
        return values.minByOrNull { (start, _) -> kotlin.math.abs(start - at) }
            ?.takeIf { (start, _) -> kotlin.math.abs(start - at) <= WINDOW * 3 }?.second
    }

    private fun occurrences(text: String, word: String): List<Int> {
        val found = ArrayList<Int>()
        var at = text.indexOf(word)
        while (at >= 0 && found.size < 50) {
            found += at
            at = text.indexOf(word, at + word.length)
        }
        return found
    }

    private fun quoteAround(text: String, at: Int): String {
        var start = (at - 40).coerceAtLeast(0)
        var end = (at + WINDOW).coerceAtMost(text.length)
        if (start > 0) start = text.indexOf(' ', start).takeIf { it in 0 until at }?.plus(1) ?: start
        if (end < text.length) end = text.lastIndexOf(' ', end).takeIf { it > at } ?: end
        return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
    }

    /** Where the answer was read: the file and, for a PDF, the page. */
    fun sourceOf(page: VaultItem): String {
        val name = page.title ?: page.sourceFile.substringAfterLast('/').ifBlank {
            if (page.itemType.isImage) "a picture" else "a saved item"
        }
        val pageNumber = page.pdfPage
        return if (pageNumber != null) "From $name, page $pageNumber" else "From $name"
    }
}
