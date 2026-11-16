package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Rect

object LineReadingControl {
    @Volatile
    var enabled: Boolean = false
}

class LinePipeline(
    private val finder: LineFinder,
    private val all: LineReader,
    private val numbers: LineReader,
    private val hindi: LineReader,
    private val unsureBelow: Float = 0.90f,
    private val dropBelow: Float = 0.5f,
) : PictureReader {

    override suspend fun read(picture: Bitmap): FoundText {
        val places = finder.find(picture)
        if (places.isEmpty()) return FoundText("", emptyList())
        val first = all.read(picture, places)
        val lines = first.toMutableList()
        val withDigit = places.indices.filter { i -> first[i].text.any { it in '0'..'9' } }
        if (withDigit.isNotEmpty()) {
            val again = numbers.read(picture, withDigit.map { places[it] })
            for ((n, i) in withDigit.withIndex()) if (again[n].text.isNotBlank()) lines[i] = again[n]
        }
        val unsure = places.indices.filter { first[it].confidence < unsureBelow }
        if (unsure.isNotEmpty()) {
            val again = hindi.read(picture, unsure.map { places[it] })
            for ((n, i) in unsure.withIndex()) if (ImageContentExtractor.saysHindi(again[n].text)) lines[i] = again[n]
        }
        val kept = lines.filter { it.text.isNotBlank() && it.confidence >= dropBelow }
        return FoundText(kept.joinToString("\n") { it.text }, kept.map { FoundBlock(it.text, it.box, listOf(it)) })
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
