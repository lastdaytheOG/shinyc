package com.amar.vault.indexing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One text out of several readings of the same picture.
 *
 * A picture is read several times — as it is, in grey, inverted, enlarged, and by a second
 * engine — because each reading catches something the others miss. The readings mostly say
 * the same lines, each with its own slips. Putting them together used to keep every line
 * that was not letter for letter inside another: a line read five slightly different ways
 * was stored three or four times, with the misreadings beside the right reading, and a short
 * line was thrown away when a longer one happened to contain its letters ("Opens at page 30"
 * was lost to "Opens at page 300").
 *
 * Here the readings are first lined up: a line of one reading and a line of another are the
 * same line of the picture when they say the same but for a few letters. Two lines of one
 * reading are never the same line, and two lines whose numbers differ are never the same
 * line. Then each line of the picture is stored once, in the wording most readings gave it.
 *
 * Tidying must not cost a word that could be searched for, so two things are kept that a
 * plain vote would lose. A word that two readings of a line agree on is stored even when
 * more readings read the line another way: the line is then stored in both wordings, since
 * nothing here can tell which is the misreading. And a line that most readings do not have
 * is kept when it says a word nothing else says — that is what the extra readings are for —
 * and left out only when it repeats words already stored, which is what a second engine's
 * joining of two neighbouring lines looks like.
 *
 * Lines come out in the order the readings had them: a line only a later reading saw goes
 * after the line that reading had before it, or, when it is that reading's first, before the
 * line that reading had after it.
 */
object ReadingMerge {

    /**
     * A line of the merged text: [from] is which reading's wording was kept (its place in the
     * list given), [seenBy] how many of the readings had the line.
     */
    data class Line(val text: String, val from: Int, val seenBy: Int)

    fun text(readings: List<String>): String = lines(readings).joinToString("\n") { it.text }

    fun lines(readings: List<String>): List<Line> {
        val read = readings.map { reading ->
            reading.lineSequence().map { SPACES.replace(it.trim(), " ") }.filter { it.isNotEmpty() }.toList()
        }
        val spots = ArrayList<Spot>()
        for ((reading, lines) in read.withIndex()) {
            if (lines.isNotEmpty()) lineUp(lines.map { Sighting(reading, it, keyOf(it)) }, spots)
        }
        // A single reading has nothing to be compared with: it is the text.
        val told = read.count { it.isNotEmpty() }
        if (told < 2) return spots.map { Line(it.sightings[0].text, it.sightings[0].reading, 1) }

        // What most readings have is the text. Its words are said.
        val kept = HashMap<Spot, List<Sighting>>()
        val said = HashSet<String>()
        for (spot in spots) {
            if (spot.sightings.size * 2 <= told) continue
            kept[spot] = spot.wordings().onEach { said += wordsOf(it.key) }
        }
        // What fewer have is kept for the words it adds, the better attested first.
        val fewer = spots.withIndex().filter { it.value !in kept }.sortedWith(compareBy({ -it.value.sightings.size }, { it.index }))
        for ((_, spot) in fewer) {
            val wording = spot.wording()
            val new = wordsOf(wording.key).filter { it !in said }
            if (new.isEmpty()) continue
            said += new
            kept[spot] = listOf(wording)
        }
        return spots.flatMap { spot -> kept[spot].orEmpty().map { Line(it.text, it.reading, spot.sightings.size) } }
    }

    // ── Lining a reading up with the readings before it ──

    private class Sighting(val reading: Int, val text: String, val key: String) {
        val digits: String by lazy { key.filter(Char::isDigit) }
    }

    /** One line of the picture, and each reading of it. */
    private class Spot(first: Sighting) {
        val sightings = arrayListOf(first)

        /** The wording most readings gave; between equals the one nearest the others, then the earliest. */
        fun wording(): Sighting {
            if (sightings.size == 1) return sightings[0]
            val byText = sightings.groupBy { it.text }.values
            val most = byText.maxOf { it.size }
            val leading = byText.filter { it.size == most }.map { it[0] }
            if (leading.size == 1) return leading[0]
            return leading.minWith(compareBy<Sighting>({ one -> sightings.sumOf { edits(one.key, it.key, Int.MAX_VALUE) ?: 0 } }, { it.reading }))
        }

        /**
         * [wording], and after it any other wording needed for a word two readings agree on
         * that it does not have: "will it", read so twice, beside "willit", read so three times.
         */
        fun wordings(): List<Sighting> {
            val chosen = arrayListOf(wording())
            if (sightings.size < 3) return chosen
            val words = sightings.associateWith { wordsOf(it.key).toSet() }
            val agreed = words.values.flatten().groupingBy { it }.eachCount().filterValues { it >= 2 }.keys.toMutableSet()
            agreed -= words.getValue(chosen[0])
            while (agreed.isNotEmpty()) {
                val next = sightings.maxWith(compareBy<Sighting>({ one -> words.getValue(one).count { it in agreed } }, { -it.reading }))
                chosen += next
                agreed -= words.getValue(next)
            }
            return chosen
        }
    }

    private class Pairing(val line: Int, val spot: Spot, val spotAt: Int, val apart: Double)

    private fun lineUp(lines: List<Sighting>, spots: ArrayList<Spot>) {
        val placed = arrayOfNulls<Spot>(lines.size)
        // Only the spots there before this reading: two lines of one reading are two lines.
        val free = LinkedHashSet(spots)

        // The same line read the same way.
        val byKey = HashMap<String, ArrayList<Spot>>()
        for (spot in spots) for (key in spot.sightings.mapTo(LinkedHashSet()) { it.key }) byKey.getOrPut(key) { ArrayList() } += spot
        for ((i, line) in lines.withIndex()) {
            val spot = byKey[line.key]?.firstOrNull { it in free } ?: continue
            free -= spot
            placed[i] = spot
        }

        // The same line read a little differently: the nearest pairs first.
        val near = ArrayList<Pairing>()
        val stillFree = free.toList()
        val spotAt = HashMap<Spot, Int>().apply { spots.forEachIndexed { at, spot -> put(spot, at) } }
        for ((i, line) in lines.withIndex()) {
            if (placed[i] != null) continue
            for (spot in stillFree) {
                val apart = spot.sightings.mapNotNull { apart(line, it) }.minOrNull() ?: continue
                near += Pairing(i, spot, spotAt.getValue(spot), apart)
            }
        }
        near.sortWith(compareBy({ it.apart }, { it.line }, { it.spotAt }))
        for (pair in near) {
            if (placed[pair.line] != null || pair.spot !in free) continue
            free -= pair.spot
            placed[pair.line] = pair.spot
        }

        // A line none of the earlier readings had goes after the line this reading had before
        // it; the lines it had before any known line go before the first known one.
        val firstKnown = placed.firstOrNull { it != null }
        var at = (if (firstKnown != null) spots.indexOf(firstKnown) else spots.size) - 1
        for ((i, line) in lines.withIndex()) {
            val spot = placed[i]
            if (spot != null) {
                spot.sightings += line
                at = spots.indexOf(spot)
            } else {
                at += 1
                spots.add(at, Spot(line))
            }
        }
    }

    /** How far apart two readings are as a share of their length; null when they are not the same line. */
    private fun apart(a: Sighting, b: Sighting): Double? {
        val longer = max(a.key.length, b.key.length)
        val allowed = (longer * NEAR).toInt()
        if (allowed == 0 || abs(a.key.length - b.key.length) > allowed) return null
        // "Opens at page 30" and "Opens at page 300" are two lines, however alike they look.
        if (a.digits != b.digits) return null
        return edits(a.key, b.key, allowed)?.let { it.toDouble() / longer }
    }

    /** Letters to change, add or drop to make [a] into [b]; null when more than [atMost]. */
    private fun edits(a: String, b: String, atMost: Int): Int? {
        if (a == b) return 0
        var before = IntArray(b.length + 1) { it }
        var row = IntArray(b.length + 1)
        for (i in 1..a.length) {
            row[0] = i
            var least = row[0]
            for (j in 1..b.length) {
                val swap = before[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                row[j] = min(swap, min(before[j], row[j - 1]) + 1)
                if (row[j] < least) least = row[j]
            }
            if (least > atMost) return null
            val spare = before; before = row; row = spare
        }
        return before[b.length].takeIf { it <= atMost }
    }

    // ── What a line says ──

    /** A line as it is compared: small letters, and nothing but letters, digits and single spaces. */
    private fun keyOf(line: String): String {
        val key = StringBuilder(line.length)
        for (c in line.lowercase()) {
            if (c.isWordChar()) key.append(c) else if (key.isNotEmpty() && key.last() != ' ') key.append(' ')
        }
        return key.trimEnd().toString()
    }

    /** Vowel signs and the like are part of a word: "हिंदी" is one word, not three letters. */
    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || when (Character.getType(this).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /**
     * The words of a line that count as saying something: two characters or more, and written
     * in one script. A lone character is as often a misread icon as a letter; "पपeue" is the
     * word "queue" read by the wrong alphabet.
     */
    private fun wordsOf(key: String): List<String> = key.split(' ').filter { it.length >= 2 && it.isOneScript() }

    private fun String.isOneScript(): Boolean {
        var script: Character.UnicodeScript? = null
        var i = 0
        while (i < length) {
            val point = codePointAt(i)
            i += Character.charCount(point)
            val its = Character.UnicodeScript.of(point)
            if (its == Character.UnicodeScript.COMMON || its == Character.UnicodeScript.INHERITED) continue
            if (script == null) script = its else if (script != its) return false
        }
        return true
    }

    /** Two readings are the same line when no more than this share of their letters differ. */
    private const val NEAR = 0.2
    private val SPACES = Regex("\\s+")
}
