package com.amar.vault.indexing.ppocr

import com.amar.vault.indexing.LinePlace
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class Strip(val place: LinePlace) {
    private val c = place.corners
    val across: Int = max(hypot(c[2] - c[0], c[3] - c[1]), hypot(c[4] - c[6], c[5] - c[7])).toInt()
    val down: Int = max(hypot(c[6] - c[0], c[7] - c[1]), hypot(c[4] - c[2], c[5] - c[3])).toInt()
    val turned: Boolean = across > 0 && down.toFloat() / across >= 1.5f
    val length: Int = if (turned) down else across
    val tall: Int = if (turned) across else down
    val readable: Boolean = across > 0 && down > 0
    val shape: Float = if (readable) length.toFloat() / tall else 0f
}

object LineStrips {

    const val HEIGHT = 48
    private const val BASE_WIDTH = 320

    fun batchWidth(strips: List<Strip>): Int {
        var widest = BASE_WIDTH.toFloat() / HEIGHT
        for (strip in strips) widest = max(widest, strip.shape)
        return (HEIGHT * widest).toInt()
    }

    fun width(strip: Strip, batchWidth: Int): Int = min(ceil(HEIGHT * strip.shape).toInt(), batchWidth)

    fun fill(argb: IntArray, width: Int, height: Int, strip: Strip, into: FloatArray, at: Int, batchWidth: Int) {
        if (!strip.readable) return
        val c = strip.place.corners
        val wide = width(strip, batchWidth)
        val plane = HEIGHT * batchWidth
        val alongX = (c[2] - c[0]) / strip.across
        val alongY = (c[3] - c[1]) / strip.across
        val downX = (c[6] - c[0]) / strip.down
        val downY = (c[7] - c[1]) / strip.down
        val stepX = strip.length.toFloat() / wide
        val stepY = strip.tall.toFloat() / HEIGHT
        for (i in 0 until HEIGHT) {
            val sy = ((i + 0.5f) * stepY - 0.5f).coerceIn(0f, (strip.tall - 1).toFloat())
            val y0 = floor(sy).toInt()
            val y1 = min(y0 + 1, strip.tall - 1)
            val fy = sy - y0
            var o = at + i * batchWidth
            for (j in 0 until wide) {
                val sx = ((j + 0.5f) * stepX - 0.5f).coerceIn(0f, (strip.length - 1).toFloat())
                val x0 = floor(sx).toInt()
                val x1 = min(x0 + 1, strip.length - 1)
                val fx = sx - x0
                val a = cut(argb, width, height, c, strip, x0, y0, alongX, alongY, downX, downY)
                val b = cut(argb, width, height, c, strip, x1, y0, alongX, alongY, downX, downY)
                val d = cut(argb, width, height, c, strip, x0, y1, alongX, alongY, downX, downY)
                val e = cut(argb, width, height, c, strip, x1, y1, alongX, alongY, downX, downY)
                into[o] = mixed(a, b, d, e, 0, fx, fy) / 127.5f - 1f
                into[plane + o] = mixed(a, b, d, e, 8, fx, fy) / 127.5f - 1f
                into[2 * plane + o] = mixed(a, b, d, e, 16, fx, fy) / 127.5f - 1f
                o++
            }
        }
    }

    private fun cut(
        argb: IntArray, width: Int, height: Int, c: FloatArray, strip: Strip, x: Int, y: Int,
        alongX: Float, alongY: Float, downX: Float, downY: Float,
    ): Int {
        val cx = if (strip.turned) strip.across - 1 - y else x
        val cy = if (strip.turned) x else y
        val px = c[0] + cx * alongX + cy * downX
        val py = c[1] + cx * alongY + cy * downY
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val fx = px - x0
        val fy = py - y0
        if (fx == 0f && fy == 0f) return argb[y0.coerceIn(0, height - 1) * width + x0.coerceIn(0, width - 1)]
        val left = x0.coerceIn(0, width - 1)
        val right = (x0 + 1).coerceIn(0, width - 1)
        val upper = y0.coerceIn(0, height - 1) * width
        val lower = (y0 + 1).coerceIn(0, height - 1) * width
        val a = argb[upper + left]
        val b = argb[upper + right]
        val d = argb[lower + left]
        val e = argb[lower + right]
        return (mixed(a, b, d, e, 16, fx, fy).toInt() shl 16) or
            (mixed(a, b, d, e, 8, fx, fy).toInt() shl 8) or
            mixed(a, b, d, e, 0, fx, fy).toInt()
    }

    private fun mixed(a: Int, b: Int, c: Int, d: Int, shift: Int, fx: Float, fy: Float): Float {
        val a0 = (a shr shift) and 0xFF
        val b0 = (b shr shift) and 0xFF
        val c0 = (c shr shift) and 0xFF
        val d0 = (d shr shift) and 0xFF
        val upper = a0 + (b0 - a0) * fx
        val lower = c0 + (d0 - c0) * fx
        return (upper + (lower - upper) * fy + 0.5f).toInt().toFloat()
    }
}
