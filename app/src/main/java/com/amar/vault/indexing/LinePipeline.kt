package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Rect

class LinePipeline(
    private val finder: LineFinder,
    private val all: LineReader,
    private val numbers: LineReader,
    private val hindi: LineReader,
    private val hindiWhole: LineReader? = null,
    private val unsureBelow: Float = 0.90f,
    private val weakWordBelow: Float = 0f,
    private val dropBelow: Float = 0.5f,
    private val wholeShare: Float = 0.5f,
    private val keepHindi: Boolean = false,
    private val grouped: Boolean = false,
) : PictureReader {

    override val trying: Boolean get() = finder.trying || all.trying || numbers.trying

    override suspend fun read(picture: Bitmap): FoundText {
        val places = finder.find(picture)
        if (places.isEmpty()) return FoundText("", emptyList())
        val first = all.read(picture, places)
        val lines = first.toMutableList()
        val unsure = places.indices.filter { first[it].confidence < unsureBelow || first[it].weakest < weakWordBelow }
        val inHindi = HashSet<Int>()
        if (unsure.isNotEmpty()) {
            val whole = hindiWhole?.takeIf { share(places, unsure) >= wholeShare }
            val again = if (whole != null) whole.read(picture, places) else hindi.read(picture, unsure.map { places[it] })
            for ((n, i) in unsure.withIndex()) {
                val read = again[if (whole != null) i else n]
                if (ImageContentExtractor.saysHindi(read.text)) {
                    lines[i] = read
                    inHindi += i
                }
            }
        }
        val withDigit = places.indices.filter { i -> i !in inHindi && first[i].text.any { it in '0'..'9' } }
        if (withDigit.isNotEmpty()) {
            val again = numbers.read(picture, withDigit.map { places[it] })
            for ((n, i) in withDigit.withIndex()) if (again[n].text.isNotBlank()) lines[i] = again[n]
        }
        val kept = lines.filterIndexed { i, line ->
            line.text.isNotBlank() && ((keepHindi && i in inHindi) || line.confidence >= dropBelow)
        }
        val blocks = if (grouped) LineBlocks.of(kept) else kept.map { FoundBlock(it.text, it.box, listOf(it)) }
        return FoundText(blocks.joinToString("\n") { it.text }, blocks)
    }

    private fun share(places: List<LinePlace>, some: List<Int>): Float {
        fun area(place: LinePlace) = ((place.right - place.left) * (place.bottom - place.top)).toDouble().coerceAtLeast(0.0)
        val total = places.sumOf(::area)
        return if (total <= 0.0) 0f else (some.sumOf { area(places[it]) } / total).toFloat()
    }
}

object LineBlocks {
    private const val GAP = 0.8f
    private const val OVERLAP = 0.5f

    fun of(lines: List<FoundLine>): List<FoundBlock> {
        val groups = ArrayList<MutableList<FoundLine>>()
        for (line in lines.sortedWith(compareBy({ it.box?.top ?: 0 }, { it.box?.left ?: 0 }))) {
            val box = line.box
            val home = if (box == null) {
                null
            } else {
                groups.filter { follows(it.last().box, box) }.maxByOrNull { overlap(it.last().box!!, box) }
            }
            if (home != null) home += line else groups += mutableListOf(line)
        }
        return groups.map { group ->
            val boxes = group.mapNotNull { it.box }
            val box = if (boxes.isEmpty()) null else Rect(boxes[0]).apply { boxes.forEach { union(it) } }
            FoundBlock(group.joinToString("\n") { it.text }, box, group)
        }
    }

    private fun follows(above: Rect?, below: Rect): Boolean {
        if (above == null) return false
        val height = minOf(above.height(), below.height())
        if (height <= 0) return false
        if (below.top < above.top + height / 2) return false
        if (below.top - above.bottom > GAP * height) return false
        return overlap(above, below) >= OVERLAP * minOf(above.width(), below.width())
    }

    private fun overlap(a: Rect, b: Rect): Int = minOf(a.right, b.right) - maxOf(a.left, b.left)
}

class PictureLineReader(private val reader: PictureReader) : LineReader {

    override suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine> {
        val boxes = places.map { it.box() }
        val read = Array(places.size) { mutableListOf<FoundLine>() }
        for (block in reader.read(picture).blocks) for (line in block.lines) {
            val box = line.box ?: continue
            val x = box.centerX()
            val y = box.centerY()
            var best = -1
            var bestDistance = Int.MAX_VALUE
            for ((i, place) in boxes.withIndex()) {
                val pad = (place.height() * PAD).toInt()
                if (x < place.left - pad || x > place.right + pad || y < place.top - pad || y > place.bottom + pad) continue
                val distance = kotlin.math.abs(y - place.centerY())
                if (distance < bestDistance) {
                    best = i
                    bestDistance = distance
                }
            }
            if (best >= 0) read[best] += line
        }
        return List(places.size) { i ->
            val parts = read[i].sortedBy { it.box?.left ?: 0 }
            FoundLine(parts.joinToString(" ") { it.text }, parts.minOfOrNull { it.confidence } ?: 0f, boxes[i])
        }
    }

    private companion object {
        const val PAD = 0.25f
    }
}

class SheetLineReader(private val reader: PictureReader) : LineReader {

    override suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine> {
        val boxes = places.map { it.box() }
        val crops = ArrayList<Rect>()
        val owner = ArrayList<Int>()
        for ((i, box) in boxes.withIndex()) {
            val pad = (box.height() * PAD).toInt().coerceAtLeast(MIN_PAD)
            val crop = Rect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
            if (!crop.intersect(0, 0, picture.width, picture.height)) continue
            stretch(crop, picture.width, picture.height)
            if (crop.width() >= MIN_SIDE && crop.height() >= MIN_SIDE) {
                crops += crop
                owner += i
            }
        }
        val read = Array(places.size) { mutableListOf<PackedLine>() }
        if (crops.isNotEmpty()) {
            val onSheet = Array(crops.size) { mutableListOf<PackedLine>() }
            val sheets = LinePacking.sheets(picture, crops, crops.maxOf { it.height() })
            try {
                for (sheet in sheets) LinePacking.assign(sheet, LinePacking.linesOf(reader.read(sheet.bitmap)), onSheet)
            } finally {
                sheets.forEach { it.bitmap.recycle() }
            }
            for ((k, i) in owner.withIndex()) read[i] = onSheet[k]
        }
        return List(places.size) { i ->
            val parts = read[i].sortedBy { it.box.left }
            FoundLine(parts.joinToString(" ") { it.text }, parts.minOfOrNull { it.confidence } ?: 0f, boxes[i])
        }
    }

    private fun stretch(crop: Rect, width: Int, height: Int) {
        if (crop.width() < MIN_SIDE) {
            crop.left = (crop.left - (MIN_SIDE - crop.width()) / 2).coerceIn(0, (width - MIN_SIDE).coerceAtLeast(0))
            crop.right = (crop.left + MIN_SIDE).coerceAtMost(width)
        }
        if (crop.height() < MIN_SIDE) {
            crop.top = (crop.top - (MIN_SIDE - crop.height()) / 2).coerceIn(0, (height - MIN_SIDE).coerceAtLeast(0))
            crop.bottom = (crop.top + MIN_SIDE).coerceAtMost(height)
        }
    }

    private companion object {
        const val PAD = 0.2f
        const val MIN_PAD = 4
        const val MIN_SIDE = 32
    }
}
