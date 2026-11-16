package com.amar.vault.indexing.ppocr

class ReadText(val text: String, val confidence: Float)

object CtcText {

    fun letters(dictionary: List<String>, classes: Int): List<String> = when (classes) {
        dictionary.size + 2 -> listOf("") + dictionary + " "
        dictionary.size + 1 -> listOf("") + dictionary
        else -> throw IllegalArgumentException("the reader has $classes classes, its dictionary ${dictionary.size} letters")
    }

    fun read(scores: FloatArray, from: Int, steps: Int, classes: Int, letters: List<String>): ReadText {
        val text = StringBuilder()
        var sum = 0f
        var kept = 0
        var last = 0
        for (t in 0 until steps) {
            val base = from + t * classes
            var best = 0
            var bestScore = scores[base]
            for (k in 1 until classes) {
                val s = scores[base + k]
                if (s > bestScore) {
                    bestScore = s
                    best = k
                }
            }
            if (best != 0 && best != last) {
                text.append(letters[best])
                sum += bestScore
                kept++
            }
            last = best
        }
        return ReadText(text.toString(), if (kept == 0) 0f else sum / kept)
    }
}
