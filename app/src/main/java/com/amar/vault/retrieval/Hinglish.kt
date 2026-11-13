package com.amar.vault.retrieval

import java.util.Locale

fun interface SpellingGuesser {
    fun keys(word: String): List<String>
    fun hindi(word: String): String? = null
}

interface KnownWords {
    fun has(key: String): Boolean
    val isEmpty: Boolean
}

object HindiVocabulary : KnownWords {
    private val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private const val KEPT = 200_000

    fun addShadows(shadows: String) {
        if (keys.size >= KEPT) return
        for (k in shadows.split(' ')) if (k.isNotEmpty()) keys.add(k)
    }

    override fun has(key: String): Boolean = key in keys
    override val isEmpty: Boolean get() = keys.isEmpty()
}

object Hinglish {

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'क़' to "q", 'ख़' to "kh", 'ग़' to "g", 'ज़' to "z", 'ड़' to "r", 'ढ़' to "rh", 'फ़' to "f", 'य़' to "y",
    )
    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )
    private val SIGNS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o",
    )
    private const val VIRAMA = '्'
    private const val NUKTA = '़'
    private val NASAL = setOf('ं', 'ँ')
    private const val VISARGA = 'ः'
    private val DEVANAGARI_WORD = Regex("[\\u0900-\\u097F]+")
    private val LATIN_WORD = Regex("[a-z]+")

    fun isDevanagari(word: String): Boolean = word.any { it in 'ऀ'..'ॿ' }

    fun romanise(word: String): String {
        val chars = word.toCharArray().filter { it != NUKTA }
        val units = ArrayList<Pair<String, Boolean>>()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            val cons = CONSONANTS[c]
            when {
                cons != null -> {
                    val next = chars.getOrNull(i + 1)
                    when {
                        next == VIRAMA -> { units += cons to false; i++ }
                        next != null && next in SIGNS -> { units += (cons + SIGNS.getValue(next)) to false; i++ }
                        else -> units += (cons + "a") to true
                    }
                }
                c in VOWELS -> units += VOWELS.getValue(c) to false
                c in NASAL -> units += "n" to false
                c == VISARGA -> units += "h" to false
                c in SIGNS -> units += SIGNS.getValue(c) to false
                else -> {}
            }
            i++
        }
        val out = StringBuilder()
        for ((k, unit) in units.withIndex()) {
            val (text, schwa) = unit
            val last = k == units.size - 1
            val nextHasVowel = units.getOrNull(k + 1)?.let { (t, s) -> !s && t.any { it in "aeiou" } } ?: false
            val afterVowel = out.isNotEmpty() && out.last() in "aeiou"
            val drop = schwa && (last && units.size > 1 || (afterVowel && nextHasVowel && k > 0))
            out.append(if (drop) text.dropLast(1) else text)
        }
        return out.toString()
    }

    fun fold(word: String): String {
        var w = word.lowercase(Locale.ROOT)
        for ((a, b) in FOLDS) w = w.replace(a, b)
        val sb = StringBuilder()
        for (ch in w) if (sb.isEmpty() || sb.last() != ch || ch in "aeiou") sb.append(ch)
        return sb.toString().let { if (it.length > 3 && it.endsWith("h") && it[it.length - 2] in "aeiou") it.dropLast(1) else it }
    }

    private val FOLDS = listOf(
        "chh" to "ch", "aa" to "a", "ee" to "i", "ii" to "i", "oo" to "u", "uu" to "u", "w" to "v", "ph" to "f",
        "q" to "k", "z" to "j", "sh" to "s", "ey" to "e", "y" to "i", "ai" to "e", "au" to "o",
    )

    fun shadows(text: String): String {
        val seen = LinkedHashSet<String>()
        for (m in DEVANAGARI_WORD.findAll(text)) {
            val r = fold(romanise(m.value))
            if (r.length >= 2) seen += r
        }
        return seen.joinToString(" ")
    }

    fun foldedQueryWords(query: String): List<String> =
        LATIN_WORD.findAll(query.lowercase(Locale.ROOT)).map { fold(it.value) }.filter { it.length >= 2 }.distinct().toList()

    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val KEYS_KEPT = 30_000

    fun key(devanagari: String): String {
        keys[devanagari]?.let { return it }
        val k = fold(romanise(devanagari))
        if (keys.size < KEYS_KEPT) keys[devanagari] = k
        return k
    }

    private val LITTLE = setOf(
        "ka", "ki", "ke", "ko", "me", "mein", "main", "se", "par", "pe", "hai", "hain", "ho", "tha", "the", "thi",
        "aur", "ya", "to", "bhi", "hi", "ne", "ek", "is", "us", "ye", "yeh", "vo", "woh", "wo", "jo", "kya", "na",
        "nahi", "koi", "kuch", "liye", "wala", "wali", "wale", "tak", "ab", "jab", "sab", "ji",
        "le", "lo", "liya", "kar", "karo", "karke", "kiya", "kiye", "de", "do", "diya", "dena", "raha", "rahi", "rahe",
        "gaya", "gayi", "gaye", "hua", "hui", "hue", "mera", "meri", "mere", "apna", "apni", "apne", "uska", "uski",
        "unka", "unki", "aap", "hum", "tum", "yahan", "wahan", "kab", "kaise", "kyun", "abhi", "phir", "bahut",
    )

    fun isLittle(word: String): Boolean = word in LITTLE

    private val ALSO_ENGLISH = setOf("me", "main", "par", "pe", "ho", "the", "to", "hi", "is", "us", "na", "ab", "jab", "sab",
        "le", "lo", "de", "do", "tum", "hum", "ya", "ye", "vo", "wo", "jo", "ji", "ne", "tak", "phir")

    fun isMarker(word: String): Boolean = word in LITTLE && word !in ALSO_ENGLISH

    private val ALL_LATIN = Regex("[a-z]+")

    fun isLatinWord(word: String): Boolean = ALL_LATIN.matches(word)

    fun keysFor(word: String, guesser: SpellingGuesser?): Set<String> {
        if (!ALL_LATIN.matches(word)) return emptySet()
        val keys = LinkedHashSet<String>()
        fold(word).takeIf { it.length >= 2 }?.let(keys::add)
        if (word.length >= 3 && !isLittle(word)) guesser?.keys(word)?.filter { it.length >= 2 }?.let(keys::addAll)
        return keys
    }

    private val SPELLED = Regex("[\\u0900-\\u0963\\u0971-\\u097F]+")

    class Match(val said: BooleanArray, val inName: BooleanArray, val onPage: Boolean, val run: Int, val spellings: List<String>)

    class Query(val typed: List<String>, private val keys: List<Set<String>>) {
        private val little = BooleanArray(typed.size) { isLittle(typed[it]) }
        val isEmpty = keys.all { it.isEmpty() }
        val allKeys: List<String> = keys.flatten().distinct()
        private val rows = java.util.concurrent.ConcurrentHashMap<String, Any>()

        fun matchRow(id: String, page: String, name: String): Match? {
            rows[id]?.let { return it as? Match }
            val m = match(page, name)
            rows[id] = m ?: NoMatch
            return m
        }

        private object NoMatch

        fun match(page: String, name: String): Match? {
            if (isEmpty || (!isDevanagari(page) && !isDevanagari(name))) return null
            val said = BooleanArray(typed.size)
            val inName = BooleanArray(typed.size)
            val spellings = LinkedHashSet<String>()
            var content = false
            var onPage = false
            var run = 0
            for ((source, isPage) in listOf(page to true, name to false)) {
                var previous = IntArray(typed.size)
                for (m in SPELLED.findAll(source)) {
                    val k = key(m.value)
                    val current = IntArray(typed.size)
                    for (i in typed.indices) {
                        if (k !in keys[i]) continue
                        said[i] = true
                        if (isPage) onPage = true else inName[i] = true
                        if (!little[i]) {
                            content = true
                            spellings += m.value
                        }
                        var best = 1
                        var j = i - 1
                        while (j >= 0) {
                            if (previous[j] > 0) best = maxOf(best, previous[j] + (i - j))
                            if (!little[j]) break
                            j--
                        }
                        current[i] = best
                        if (current[i] > run) run = current[i]
                    }
                    previous = current
                }
            }
            return if (content) Match(said, inName, onPage, run, spellings.toList()) else null
        }

        companion object {
            fun of(typed: List<String>, guesser: SpellingGuesser?): Query =
                Query(typed, typed.map { keysFor(it, guesser) })
        }
    }
}
