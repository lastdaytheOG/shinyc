package com.amar.vault.indexing.ppocr

import com.amar.vault.indexing.LinePlace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

internal object PpOcrCases {

    class Picture(val argb: IntArray, val width: Int, val height: Int)

    class ProbMap(val prob: FloatArray, val width: Int, val height: Int)

    class StripCase(val place: Int, val batchWidth: Int, val wide: Int, val bgr: ByteArray)

    class LineCase(val text: String, val confidence: Float, val best: IntArray, val score: FloatArray)

    private fun open(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("ppocr/$name")) { "ppocr/$name is missing" }

    private fun lines(name: String): List<String> =
        open(name).bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }

    fun picture(name: String): Picture {
        val bytes = GZIPInputStream(open(name)).use { it.readBytes() }
        val head = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val width = head.int
        val height = head.int
        val argb = IntArray(width * height) {
            val at = 8 + it * 3
            (0xFF shl 24) or ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at].toInt() and 0xFF)
        }
        return Picture(argb, width, height)
    }

    fun probMap(): ProbMap {
        val bytes = GZIPInputStream(open("prob.bin.gz")).use { it.readBytes() }
        val head = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val width = head.int
        val height = head.int
        return ProbMap(FloatArray(width * height) { (bytes[8 + it].toInt() and 0xFF) / 255f }, width, height)
    }

    fun places(): List<LinePlace> = lines("places.tsv").map { line ->
        val parts = line.split('\t')
        LinePlace(FloatArray(8) { parts[it].toFloat() }, parts[8].toFloat())
    }

    fun strips(): List<StripCase> {
        val bytes = GZIPInputStream(open("strips.bin.gz")).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(buffer.int) {
            val place = buffer.int
            val batchWidth = buffer.int
            val wide = buffer.int
            StripCase(place, batchWidth, wide, ByteArray(LineStrips.HEIGHT * wide * 3).also { buffer.get(it) })
        }
    }

    fun letters(): List<String> = open("en-letters.tsv").bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }

    fun readLines(): List<LineCase> =
        open("lines.tsv").bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }.map { line ->
            val parts = line.split('\t')
            val steps = parts[2].split(' ')
            LineCase(
                parts[0],
                parts[1].toFloat(),
                IntArray(steps.size) { steps[it].substringBefore(':').toInt() },
                FloatArray(steps.size) { steps[it].substringAfter(':').toFloat() },
            )
        }
}
