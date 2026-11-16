package com.amar.vault.indexing.ppocr

import com.amar.vault.indexing.LinePlace
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class FinderSettings(
    val longSide: Int = 1280,
    val thresh: Float = 0.2f,
    val boxThresh: Float = 0.4f,
    val unclip: Float = 1.4f,
)

class FinderInput(val pixels: FloatArray, val width: Int, val height: Int)

object LineFinding {

    private const val MIN_SIDE = 3f
    private const val MAX_PLACES = 3000
    private const val OUTLINE = 0.5f
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun input(argb: IntArray, width: Int, height: Int, longSide: Int): FinderInput {
        require(argb.size == width * height)
        val longest = max(width, height)
        val ratio = if (longest > longSide) longSide.toDouble() / longest else 1.0
        val w = side((width * ratio).toInt())
        val h = side((height * ratio).toInt())
        val xs = Taps(width, w)
        val ys = Taps(height, h)
        val plane = w * h
        val out = FloatArray(3 * plane)
        for (y in 0 until h) {
            val upper = ys.low[y] * width
            val lower = ys.high[y] * width
            val fy = ys.part[y]
            var i = y * w
            for (x in 0 until w) {
                val x0 = xs.low[x]
                val x1 = xs.high[x]
                val fx = xs.part[x]
                val a = argb[upper + x0]
                val b = argb[upper + x1]
                val c = argb[lower + x0]
                val d = argb[lower + x1]
                out[i] = (mixed(a, b, c, d, 0, fx, fy) / 255f - MEAN[0]) / STD[0]
                out[plane + i] = (mixed(a, b, c, d, 8, fx, fy) / 255f - MEAN[1]) / STD[1]
                out[2 * plane + i] = (mixed(a, b, c, d, 16, fx, fy) / 255f - MEAN[2]) / STD[2]
                i++
            }
        }
        return FinderInput(out, w, h)
    }

    private fun side(length: Int): Int = max((Math.rint(length / 32.0) * 32).toInt(), 32)

    private fun mixed(a: Int, b: Int, c: Int, d: Int, shift: Int, fx: Float, fy: Float): Float {
        val a0 = (a shr shift) and 0xFF
        val b0 = (b shr shift) and 0xFF
        val c0 = (c shr shift) and 0xFF
        val d0 = (d shr shift) and 0xFF
        val upper = a0 + (b0 - a0) * fx
        val lower = c0 + (d0 - c0) * fx
        return (upper + (lower - upper) * fy + 0.5f).toInt().toFloat()
    }

    private class Taps(from: Int, to: Int) {
        val low = IntArray(to)
        val high = IntArray(to)
        val part = FloatArray(to)

        init {
            val scale = from.toDouble() / to
            for (i in 0 until to) {
                val at = (i + 0.5) * scale - 0.5
                var lo = floor(at).toInt()
                var f = (at - lo).toFloat()
                if (lo < 0) {
                    lo = 0
                    f = 0f
                }
                if (lo >= from - 1) {
                    lo = from - 1
                    f = 0f
                }
                low[i] = lo
                high[i] = min(lo + 1, from - 1)
                part[i] = f
            }
        }
    }

    fun places(
        prob: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        width: Int,
        height: Int,
        settings: FinderSettings,
    ): List<LinePlace> {
        require(prob.size >= mapWidth * mapHeight)
        val runs = Runs(prob, mapWidth, mapHeight, settings.thresh)
        val found = ArrayList<LinePlace>()
        for (points in runs.shapes().take(MAX_PLACES)) {
            val rect = smallestRect(hull(points)) ?: continue
            if (min(rect.across, rect.down) < MIN_SIDE) continue
            val score = score(prob, mapWidth, mapHeight, rect.corners)
            if (score < settings.boxThresh) continue
            val grown = grown(rect, settings.unclip)
            if (min(grown.across, grown.down) < MIN_SIDE + 2) continue
            val corners = FloatArray(8)
            for (k in 0 until 4) {
                val x = Math.rint(grown.corners[2 * k].toDouble() / mapWidth * width).toFloat()
                val y = Math.rint(grown.corners[2 * k + 1].toDouble() / mapHeight * height).toFloat()
                corners[2 * k] = x.coerceIn(0f, (width - 1).toFloat())
                corners[2 * k + 1] = y.coerceIn(0f, (height - 1).toFloat())
            }
            val ordered = clockwise(corners)
            val across = hypot(ordered[2] - ordered[0], ordered[3] - ordered[1]).toInt()
            val down = hypot(ordered[6] - ordered[0], ordered[7] - ordered[1]).toInt()
            if (across <= 3 || down <= 3) continue
            found += LinePlace(ordered, score)
        }
        return found.sortedWith(compareBy({ it.corners[1] }, { it.corners[0] }))
    }

    private class Runs(prob: FloatArray, width: Int, height: Int, thresh: Float) {
        private var from = IntArray(1024)
        private var to = IntArray(1024)
        private var row = IntArray(1024)
        private var count = 0
        private var parent = IntArray(0)

        init {
            val rowStart = IntArray(height + 1)
            for (y in 0 until height) {
                rowStart[y] = count
                val base = y * width
                var x = 0
                while (x < width) {
                    if (prob[base + x] > thresh) {
                        val start = x
                        while (x < width && prob[base + x] > thresh) x++
                        add(start, x - 1, y)
                    } else {
                        x++
                    }
                }
            }
            rowStart[height] = count
            parent = IntArray(count) { it }
            for (y in 1 until height) {
                var above = rowStart[y - 1]
                val aboveEnd = rowStart[y]
                for (i in rowStart[y] until rowStart[y + 1]) {
                    while (above < aboveEnd && to[above] < from[i] - 1) above++
                    var j = above
                    while (j < aboveEnd && from[j] <= to[i] + 1) {
                        join(i, j)
                        j++
                    }
                }
            }
        }

        private fun add(start: Int, end: Int, y: Int) {
            if (count == from.size) {
                from = from.copyOf(count * 2)
                to = to.copyOf(count * 2)
                row = row.copyOf(count * 2)
            }
            from[count] = start
            to[count] = end
            row[count] = y
            count++
        }

        private fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var k = i
            while (parent[k] != r) {
                val next = parent[k]
                parent[k] = r
                k = next
            }
            return r
        }

        private fun join(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
        }

        fun shapes(): List<IntArray> {
            val sizes = IntArray(count)
            for (i in 0 until count) sizes[root(i)] += if (from[i] == to[i]) 1 else 2
            val filled = IntArray(count)
            val shapes = arrayOfNulls<IntArray>(count)
            val order = ArrayList<IntArray>()
            for (i in 0 until count) {
                val r = root(i)
                val points = shapes[r] ?: IntArray(sizes[r] * 2).also {
                    shapes[r] = it
                    order += it
                }
                var at = filled[r]
                points[at++] = from[i]
                points[at++] = row[i]
                if (to[i] != from[i]) {
                    points[at++] = to[i]
                    points[at++] = row[i]
                }
                filled[r] = at
            }
            return order
        }
    }

    private fun hull(points: IntArray): FloatArray {
        val n = points.size / 2
        val order = (0 until n).sortedWith(compareBy({ points[2 * it] }, { points[2 * it + 1] }))
        if (n < 3) return FloatArray(points.size) { points[it].toFloat() }
        val chain = IntArray(2 * n)
        var k = 0
        fun cross(o: Int, a: Int, b: Int): Long {
            val ox = points[2 * o].toLong()
            val oy = points[2 * o + 1].toLong()
            return (points[2 * a] - ox) * (points[2 * b + 1] - oy) - (points[2 * a + 1] - oy) * (points[2 * b] - ox)
        }
        for (i in order) {
            while (k >= 2 && cross(chain[k - 2], chain[k - 1], i) <= 0) k--
            chain[k++] = i
        }
        val lower = k + 1
        for (index in n - 2 downTo 0) {
            val i = order[index]
            while (k >= lower && cross(chain[k - 2], chain[k - 1], i) <= 0) k--
            chain[k++] = i
        }
        val size = k - 1
        return FloatArray(size * 2) { points[2 * chain[it / 2] + it % 2].toFloat() }
    }

    private class Turned(val corners: FloatArray, val across: Float, val down: Float)

    private fun smallestRect(hull: FloatArray): Turned? {
        val n = hull.size / 2
        if (n < 3) return null
        var best: FloatArray? = null
        var bestArea = Float.MAX_VALUE
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = hull[2 * j] - hull[2 * i]
            val ey = hull[2 * j + 1] - hull[2 * i + 1]
            val length = hypot(ex, ey)
            if (length == 0f) continue
            val ux = ex / length
            val uy = ey / length
            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            for (p in 0 until n) {
                val u = hull[2 * p] * ux + hull[2 * p + 1] * uy
                val v = -hull[2 * p] * uy + hull[2 * p + 1] * ux
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                best = floatArrayOf(
                    minU * ux - minV * uy, minU * uy + minV * ux,
                    maxU * ux - minV * uy, maxU * uy + minV * ux,
                    maxU * ux - maxV * uy, maxU * uy + maxV * ux,
                    minU * ux - maxV * uy, minU * uy + maxV * ux,
                )
            }
        }
        val corners = clockwise(best ?: return null)
        return Turned(
            corners,
            hypot(corners[2] - corners[0], corners[3] - corners[1]),
            hypot(corners[6] - corners[0], corners[7] - corners[1]),
        )
    }

    private fun clockwise(corners: FloatArray): FloatArray {
        val byX = (0 until 4).sortedBy { corners[2 * it] }
        val (topLeft, bottomLeft) = byX.take(2).sortedBy { corners[2 * it + 1] }
        val (topRight, bottomRight) = byX.drop(2).sortedBy { corners[2 * it + 1] }
        val out = FloatArray(8)
        for ((k, from) in listOf(topLeft, topRight, bottomRight, bottomLeft).withIndex()) {
            out[2 * k] = corners[2 * from]
            out[2 * k + 1] = corners[2 * from + 1]
        }
        return out
    }

    private fun score(prob: FloatArray, width: Int, height: Int, corners: FloatArray): Float {
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (k in 0 until 4) {
            left = min(left, corners[2 * k])
            right = max(right, corners[2 * k])
            top = min(top, corners[2 * k + 1])
            bottom = max(bottom, corners[2 * k + 1])
        }
        val x0 = floor(left).toInt().coerceIn(0, width - 1)
        val x1 = ceil(right).toInt().coerceIn(0, width - 1)
        val y0 = floor(top).toInt().coerceIn(0, height - 1)
        val y1 = ceil(bottom).toInt().coerceIn(0, height - 1)
        val whole = IntArray(8) { corners[it].toInt() }
        val edgeX = FloatArray(4)
        val edgeY = FloatArray(4)
        val slack = FloatArray(4)
        var twice = 0f
        for (k in 0 until 4) {
            val j = (k + 1) % 4
            edgeX[k] = (whole[2 * j] - whole[2 * k]).toFloat()
            edgeY[k] = (whole[2 * j + 1] - whole[2 * k + 1]).toFloat()
            slack[k] = OUTLINE * hypot(edgeX[k], edgeY[k])
            twice += whole[2 * k].toFloat() * whole[2 * j + 1] - whole[2 * j].toFloat() * whole[2 * k + 1]
        }
        val turn = if (twice < 0f) -1f else 1f
        var sum = 0.0
        var count = 0
        for (y in y0..y1) {
            val base = y * width
            for (x in x0..x1) {
                var inside = true
                for (k in 0 until 4) {
                    val side = turn * (edgeX[k] * (y - whole[2 * k + 1]) - edgeY[k] * (x - whole[2 * k]))
                    if (side < -slack[k]) {
                        inside = false
                        break
                    }
                }
                if (inside) {
                    sum += prob[base + x]
                    count++
                }
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun grown(rect: Turned, unclip: Float): Turned {
        val f = rect.corners
        var twice = 0f
        var around = 0f
        for (k in 0 until 4) {
            val j = (k + 1) % 4
            twice += f[2 * k] * f[2 * j + 1] - f[2 * j] * f[2 * k + 1]
            around += hypot(f[2 * j] - f[2 * k], f[2 * j + 1] - f[2 * k + 1])
        }
        val c = FloatArray(8) { f[it].toInt().toFloat() }
        if (around == 0f) return Turned(c, 0f, 0f)
        val by = kotlin.math.abs(twice) / 2f * unclip / around
        var best: FloatArray? = null
        var bestArea = Float.MAX_VALUE
        for (k in 0 until 4) {
            val j = (k + 1) % 4
            val ex = c[2 * j] - c[2 * k]
            val ey = c[2 * j + 1] - c[2 * k + 1]
            val length = hypot(ex, ey)
            if (length == 0f) continue
            val ux = ex / length
            val uy = ey / length
            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            for (p in 0 until 4) {
                val u = c[2 * p] * ux + c[2 * p + 1] * uy
                val v = -c[2 * p] * uy + c[2 * p + 1] * ux
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            minU -= by
            maxU += by
            minV -= by
            maxV += by
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                best = floatArrayOf(
                    minU * ux - minV * uy, minU * uy + minV * ux,
                    maxU * ux - minV * uy, maxU * uy + minV * ux,
                    maxU * ux - maxV * uy, maxU * uy + maxV * ux,
                    minU * ux - maxV * uy, minU * uy + maxV * ux,
                )
            }
        }
        val corners = clockwise(best ?: return Turned(c, 0f, 0f))
        return Turned(
            corners,
            hypot(corners[2] - corners[0], corners[3] - corners[1]),
            hypot(corners[6] - corners[0], corners[7] - corners[1]),
        )
    }
}
