package com.amar.vault.indexing

import java.net.URLDecoder

/**
 * What the QR codes and barcodes on a picture say, as a line of its text.
 *
 * A code is part of what the picture shows, so what it holds is searchable like the rest: the
 * payee and address of a payment code, the contents of any other. Nothing is added to it.
 * (The line used to end in "upi payment qr scanner" or "qr scanner barcode", so that those
 * words would find the picture; they were not on it. They are tags now: [AutoTags].)
 */
object QrText {

    fun readable(payloads: List<String>): String =
        payloads.map(::readable).filter { it.isNotBlank() }.joinToString(" ")

    private fun readable(payload: String): String {
        if (!payload.startsWith("upi://", ignoreCase = true)) return payload.trim()
        // upi://pay?pa=ravi@okaxis&pn=Ravi%20Kumar&am=250 → the payee's name and address.
        val asked = payload.substringAfter('?', "").split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to (runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: value)
        }.toMap()
        return listOfNotNull(asked["pn"], asked["pa"]).joinToString(" ")
    }

    private val FORMER_WORDS = Regex("(?:^| )(?:upi payment qr scanner|qr scanner barcode)(?= |$)")

    /**
     * [text] of a picture that has codes, without the words that used to be added to its last
     * line. Text that never had them comes back as it was.
     */
    fun withoutFormerWords(text: String): String {
        val lastLine = text.lastIndexOf('\n') + 1
        val was = text.substring(lastLine)
        val cleaned = was.replace(FORMER_WORDS, "")
        if (cleaned == was) return text
        return (text.substring(0, lastLine) + cleaned.trim()).trimEnd('\n')
    }
}
