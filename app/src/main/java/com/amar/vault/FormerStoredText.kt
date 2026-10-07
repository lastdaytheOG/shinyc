package com.amar.vault

/**
 * How an item's text was stored until database version 14, and how to take it apart.
 *
 * The indexers glued the item's tags onto the end of what they had read, as a last line in
 * square brackets, and on a picture the contents of its QR codes after them:
 *
 *     …where its total turnover or the gross receipt in the previous year
 *     [pdf document invoice billing receipt]
 *
 *     Scan to pay
 *     [receipt payment bill invoice qr_data:upi://pay?pa=ravi@okaxis&pn=Ravi Kumar]
 *
 * Only the version-14 migration reads this form. It has to tell that last line from a line of
 * the page that happens to start with a bracket — a statute is full of them ("[30th March,
 * 2026]", "[excluding dividend income") — and from a page that happens to end with one. So a
 * last line is taken for tags only when it is made of nothing but words the indexers wrote.
 */
internal object FormerStoredText {

    class Parts(
        /** What was read from the page or off the picture. */
        val page: String,
        /** The tags, with a space between them; empty when there were none. */
        val tags: String,
        /** What the QR codes held, in the order they were stored. */
        val qrPayloads: List<String>,
    )

    private const val QR_MARK = "qr_data:"

    /**
     * Every word either indexer ever wrote as a tag: the four document families, the ten
     * document rules, the four picture rules. The rules did not change between the first
     * version of the app and version 13 (checked against the repository's history).
     */
    private val TAG_WORDS: Set<String> = (
        "pdf document word spreadsheet excel ebook epub " +
            "invoice billing receipt contract agreement legal financial payment monetary " +
            "resume cv career confidential sensitive meeting minutes notes report analysis " +
            "medical health academic education tax government " +
            "qr code scanner barcode bill otp verification contact phone number call"
        ).split(' ').toSet()

    fun split(stored: String): Parts {
        if (stored.endsWith("]")) {
            // The tag line is the last line that starts with a bracket — unless a QR code's
            // contents have such a line in them, in which case it is an earlier one.
            var start = stored.lastIndexOf("\n[")
            while (start >= 0) {
                tagLine(stored.substring(start + 2, stored.length - 1))?.let { (tags, payloads) ->
                    return Parts(stored.substring(0, start), tags, payloads)
                }
                start = if (start == 0) -1 else stored.lastIndexOf("\n[", start - 1)
            }
        }
        return Parts(stored, tags = "", qrPayloads = emptyList())
    }

    /** The tags and QR contents that [inside] the brackets holds; null when it is not a tag line. */
    private fun tagLine(inside: String): Pair<String, List<String>>? {
        val qrAt = when {
            inside.startsWith(QR_MARK) -> 0
            else -> inside.indexOf(" $QR_MARK").let { if (it < 0) -1 else it + 1 }
        }
        val words = (if (qrAt < 0) inside else inside.substring(0, qrAt)).split(' ').filter { it.isNotEmpty() }
        if (words.any { it !in TAG_WORDS }) return null
        val payloads = if (qrAt < 0) emptyList()
        else inside.substring(qrAt + QR_MARK.length).split(" $QR_MARK").filter { it.isNotBlank() }
        if (words.isEmpty() && payloads.isEmpty()) return null
        return words.joinToString(" ") to payloads
    }
}

/**
 * What the QR codes and barcodes on a picture hold, as kept in `vault_items.qrPayload`: one
 * string, the codes' contents with a record separator between them. A code's contents can be
 * several lines (a contact card) and can have spaces in it (a payee's name), so neither can
 * separate them.
 */
object QrPayloads {
    private const val SEPARATOR = '\u001E'

    /** Null when [payloads] has nothing in it: a picture without codes stores nothing. */
    fun join(payloads: List<String>): String? =
        payloads.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR.toString())

    fun split(stored: String?): List<String> =
        stored?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
}
