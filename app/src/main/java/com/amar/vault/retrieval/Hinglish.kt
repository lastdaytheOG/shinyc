package com.amar.vault.retrieval

import java.util.Locale

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
}
