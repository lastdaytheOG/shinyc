package com.amar.vault

import android.content.Context
import android.util.JsonReader
import java.io.InputStreamReader

data class TokenizerOutput(
    val inputIds: IntArray,
    val attentionMask: IntArray
)

/**
 * Optimized Unigram SentencePiece tokenizer for BGE-M3
 * Uses U+2581 (Lower One Eighth Block) as the space prefix
 */
class SimpleTokenizer(context: Context) {

    private val vocab = mutableListOf<Pair<String, Float>>()
    private val tokenToId = mutableMapOf<String, Int>()

    private val bosId: Int
    private val eosId: Int
    private val unkId: Int
    private val padId: Int

    // CRITICAL FIX: SentencePiece uses U+2581, not a regular underscore!
    private val SP_SPACE = "\u2581"

    init {
        // CRITICAL FIX: Streaming JsonReader prevents 50MB RAM spikes and ANR crashes
        context.assets.open("tokenizer.json").use { inputStream ->
            val reader = JsonReader(InputStreamReader(inputStream))
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "model") {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "vocab") {
                            reader.beginArray()
                            var index = 0
                            while (reader.hasNext()) {
                                reader.beginArray()
                                val token = reader.nextString()
                                val score = reader.nextDouble().toFloat()
                                reader.endArray()

                                vocab.add(token to score)
                                tokenToId[token] = index
                                index++
                            }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }

        bosId = tokenToId["<s>"]   ?: 0
        padId = tokenToId["<pad>"] ?: 1
        eosId = tokenToId["</s>"]  ?: 2
        unkId = tokenToId["<unk>"] ?: 3
    }

    fun encode(text: String, maxLength: Int): TokenizerOutput {
        val inputIds      = IntArray(maxLength) { padId }
        val attentionMask = IntArray(maxLength) { 0 }

        val tokenIds = unigramTokenize(text.lowercase().trim())

        val maxTokens = maxLength - 2 // Leave room for <s> and </s>
        val truncated = if (tokenIds.size > maxTokens) {
            tokenIds.subList(0, maxTokens)
        } else {
            tokenIds
        }

        inputIds[0]      = bosId
        attentionMask[0] = 1

        truncated.forEachIndexed { i, id ->
            inputIds[i + 1]      = id
            attentionMask[i + 1] = 1
        }

        val sepPos = truncated.size + 1
        if (sepPos < maxLength) {
            inputIds[sepPos]      = eosId
            attentionMask[sepPos] = 1
        }

        return TokenizerOutput(inputIds, attentionMask)
    }

    private fun unigramTokenize(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()

        // Replace all standard spaces with SentencePiece spaces
        val normalizedText = text.replace(" ", SP_SPACE)

        // Ensure the very first character is a SentencePiece space
        val finalString = if (normalizedText.startsWith(SP_SPACE)) {
            normalizedText
        } else {
            "$SP_SPACE$normalizedText"
        }

        return tokenizeWord(finalString)
    }

    private fun tokenizeWord(word: String): List<Int> {
        val n = word.length
        if (n == 0) return emptyList()

        // best[i] = (score, start_pos, token_id)
        val best = Array(n + 1) { Triple(Float.NEGATIVE_INFINITY, -1, unkId) }
        best[0] = Triple(0f, -1, -1)

        for (i in 0 until n) {
            if (best[i].first == Float.NEGATIVE_INFINITY && i > 0) continue

            // Look ahead up to 24 characters (standard max token length)
            for (j in i + 1..minOf(i + 24, n)) {
                val sub = word.substring(i, j)
                val tokenId = tokenToId[sub]

                if (tokenId != null) {
                    val score = vocab[tokenId].second
                    val newScore = best[i].first + score
                    if (newScore > best[j].first) {
                        best[j] = Triple(newScore, i, tokenId)
                    }
                }
            }

            // CRITICAL FIX: Sub-word OOV Fallback.
            // If we can't find a path, don't fail the whole string. Fallback to byte/unk for this single char
            if (best[i + 1].first == Float.NEGATIVE_INFINITY) {
                val singleChar = word.substring(i, i + 1)
                val byteTokenId = tokenToId[singleChar]
                    ?: tokenToId["<0x${singleChar[0].code.toString(16).uppercase().padStart(2, '0')}>"]
                    ?: unkId

                // Assign a heavy penalty (-100f) so it prefers real words if available, but doesn't break
                best[i + 1] = Triple(best[i].first - 100f, i, byteTokenId)
            }
        }

        // Backtrack to find the optimal path
        val tokenIds = mutableListOf<Int>()
        var pos = n
        while (pos > 0) {
            val (_, prevPos, tokenId) = best[pos]
            if (prevPos < 0) break // Safety catch
            tokenIds.add(0, tokenId)
            pos = prevPos
        }

        return tokenIds
    }
}