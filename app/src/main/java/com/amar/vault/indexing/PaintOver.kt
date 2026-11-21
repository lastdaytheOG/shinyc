package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PaintOver {
    const val SURE = 0.75f
    const val SURE_WITH_DIGIT = 0.90f
    private const val GROW_DOWN = 0.15f
    private const val GROW_ALONG = 0.3f
    private const val SPECK_LETTERS = 2
    private const val SPECK_BELOW = 0.5f

    fun settled(line: FoundLine): Boolean {
        val box = line.box ?: return false
        if (box.width() <= 0 || box.height() <= 0) return false
        if (line.text.none { it.isLetterOrDigit() }) return false
        return line.confidence >= if (line.text.any { it.isDigit() }) SURE_WITH_DIGIT else SURE
    }

    fun toPaint(found: FoundText): List<FoundLine> = found.blocks.flatMap { it.lines }.filter(::settled)

    fun withoutSpecks(found: FoundText): FoundText {
        val blocks = found.blocks.mapNotNull { block ->
            val lines = block.lines.filterNot(::speck)
            when {
                lines.size == block.lines.size -> block
                lines.isEmpty() -> null
                else -> FoundBlock(lines.joinToString("\n") { it.text }, block.box, lines)
            }
        }
        if (blocks.size == found.blocks.size && blocks.indices.all { blocks[it] === found.blocks[it] }) return found
        return FoundText(blocks.joinToString("\n") { it.text }, blocks)
    }

    fun speck(line: FoundLine): Boolean =
        line.confidence < SPECK_BELOW && line.text.count { it.isLetterOrDigit() } <= SPECK_LETTERS

    fun cover(box: Rect, width: Int, height: Int): Rect {
        val down = max(2, (box.height() * GROW_DOWN).roundToInt())
        val along = max(2, (box.height() * GROW_ALONG).roundToInt())
        return Rect(
            max(0, box.left - along),
            max(0, box.top - down),
            min(width, box.right + along),
            min(height, box.bottom + down),
        )
    }

    fun painted(picture: Bitmap, found: FoundText, settled: List<FoundLine> = toPaint(found)): Bitmap? {
        val lines = found.blocks.flatMap { it.lines }
        if (settled.isEmpty()) return null
        val copy = picture.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply { style = Paint.Style.FILL }
        for (line in settled) {
            val cover = cover(line.box!!, picture.width, picture.height)
            if (cover.isEmpty) continue
            paint.color = background(ring(picture, cover))
            canvas.drawRect(cover, paint)
        }
        for (line in lines) {
            if (settled.any { it === line }) continue
            val box = line.box ?: continue
            val keep = Rect(max(0, box.left), max(0, box.top), min(picture.width, box.right), min(picture.height, box.bottom))
            if (!keep.isEmpty) canvas.drawBitmap(picture, keep, keep, null)
        }
        return copy
    }

    fun withSettled(hindi: FoundText, settled: List<FoundLine>): FoundText {
        if (settled.isEmpty()) return hindi
        val blocks = hindi.blocks + settled.map { FoundBlock(it.text, Rect(it.box), listOf(it)) }
        return FoundText(blocks.joinToString("\n") { it.text }, blocks)
    }

    fun ring(picture: Bitmap, cover: Rect): IntArray {
        val out = ArrayList<Int>()
        val width = cover.width()
        val height = cover.height()
        fun row(y: Int) {
            if (y < 0 || y >= picture.height || width <= 0) return
            val pixels = IntArray(width)
            picture.getPixels(pixels, 0, width, cover.left, y, width, 1)
            pixels.forEach { out += it }
        }
        fun column(x: Int) {
            if (x < 0 || x >= picture.width || height <= 0) return
            val pixels = IntArray(height)
            picture.getPixels(pixels, 0, 1, x, cover.top, 1, height)
            pixels.forEach { out += it }
        }
        row(cover.top - 1)
        row(cover.bottom)
        column(cover.left - 1)
        column(cover.right)
        return out.toIntArray()
    }

    fun background(pixels: IntArray): Int {
        if (pixels.isEmpty()) return Color.WHITE
        fun middle(channel: (Int) -> Int): Int {
            val values = IntArray(pixels.size) { channel(pixels[it]) }
            values.sort()
            return values[values.size / 2]
        }
        return Color.rgb(middle(Color::red), middle(Color::green), middle(Color::blue))
    }
}
