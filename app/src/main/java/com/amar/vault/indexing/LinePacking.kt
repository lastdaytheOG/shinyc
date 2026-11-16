package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

internal class PackedLine(val text: String, val confidence: Float, val box: Rect)

internal class PackSheet(val bitmap: Bitmap, val indexes: List<Int>, val tops: List<Int>, val heights: List<Int>)

internal object LinePacking {
    const val TAU = 0.85f
    const val PAD = 0.5f
    const val MAX_AREA_SHARE = 0.6f
    const val SHEET_HEIGHT = 2400
    private const val MIN_CHARS = 4

    fun linesOf(found: FoundText): List<PackedLine> =
        found.blocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                line.box?.let { PackedLine(line.text, line.confidence, Rect(it)) }
            }
        }

    fun crops(lines: List<PackedLine>, width: Int, height: Int): List<Rect> {
        val boxes = lines
            .filter { it.confidence < TAU && it.text.count { c -> c.isLetterOrDigit() } >= MIN_CHARS }
            .map {
                val pad = (it.box.height() * PAD).toInt().coerceAtLeast(4)
                Rect(it.box.left - pad, it.box.top - pad, it.box.right + pad, it.box.bottom + pad).also { r ->
                    r.intersect(0, 0, width, height)
                }
            }
            .filter { it.width() > 4 && it.height() > 4 }
        return union(boxes)
    }

    fun area(crops: List<Rect>, width: Int, height: Int): Double =
        crops.sumOf { it.width().toLong() * it.height() }.toDouble() / (width.toLong() * height)

    private fun union(boxes: List<Rect>): List<Rect> {
        val list = boxes.map { Rect(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) for (j in i + 1 until list.size) {
                if (Rect.intersects(list[i], list[j])) {
                    list[i].union(list[j])
                    list.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return list.sortedWith(compareBy({ it.top }, { it.left }))
    }

    fun sheets(source: Bitmap, crops: List<Rect>, gap: Int): List<PackSheet> {
        val out = mutableListOf<PackSheet>()
        var chunk = mutableListOf<Int>()
        var height = 0
        fun flush() {
            if (chunk.isEmpty()) return
            val width = chunk.maxOf { crops[it].width() }
            val total = chunk.sumOf { crops[it].height() } + gap * (chunk.size - 1)
            val bitmap = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            var y = 0
            val tops = mutableListOf<Int>()
            for (i in chunk) {
                val rect = crops[i]
                tops += y
                paint.color = source.getPixel(rect.left, rect.top)
                canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + rect.height()).toFloat(), paint)
                canvas.drawBitmap(source, rect, Rect(0, y, rect.width(), y + rect.height()), null)
                paint.color = 0xFFFFFFFF.toInt()
                canvas.drawRect(0f, (y + rect.height()).toFloat(), width.toFloat(), (y + rect.height() + gap).toFloat(), paint)
                y += rect.height() + gap
            }
            out += PackSheet(bitmap, chunk.toList(), tops, chunk.map { crops[it].height() })
            chunk = mutableListOf()
            height = 0
        }
        for ((i, rect) in crops.withIndex()) {
            if (chunk.isNotEmpty() && height + gap + rect.height() > SHEET_HEIGHT) flush()
            chunk += i
            height += rect.height() + if (chunk.size > 1) gap else 0
        }
        flush()
        return out
    }

    fun assign(sheet: PackSheet, lines: List<PackedLine>, into: Array<MutableList<PackedLine>>) {
        for (line in lines) {
            for ((k, index) in sheet.indexes.withIndex()) {
                val y = line.box.centerY()
                if (y >= sheet.tops[k] && y < sheet.tops[k] + sheet.heights[k]) {
                    into[index].add(line)
                    break
                }
            }
        }
    }

    fun halved(lines: List<PackedLine>): List<PackedLine> =
        lines.map { PackedLine(it.text, it.confidence, Rect(it.box.left / 2, it.box.top / 2, it.box.right / 2, it.box.bottom / 2)) }

    fun inside(box: Rect, region: Rect): Boolean = region.contains(box.centerX(), box.centerY())

    fun ordered(lines: List<PackedLine>): String =
        lines.sortedWith(compareBy({ it.box.top / 8 }, { it.box.left })).joinToString("\n") { it.text }
}
