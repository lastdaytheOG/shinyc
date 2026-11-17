package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

class FoundLine(val text: String, val confidence: Float, val box: Rect?)

class FoundBlock(val text: String, val box: Rect?, val lines: List<FoundLine>)

class FoundText(val text: String, val blocks: List<FoundBlock>)

interface PictureReader {
    suspend fun read(picture: Bitmap): FoundText

    val trying: Boolean get() = false
}

class LinePlace(val corners: FloatArray, val score: Float) {

    init {
        require(corners.size == 8)
    }

    val left: Float get() = minOf(corners[0], corners[2], corners[4], corners[6])
    val top: Float get() = minOf(corners[1], corners[3], corners[5], corners[7])
    val right: Float get() = maxOf(corners[0], corners[2], corners[4], corners[6])
    val bottom: Float get() = maxOf(corners[1], corners[3], corners[5], corners[7])

    fun box(): Rect = Rect(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())
}

interface LineFinder {
    suspend fun find(picture: Bitmap): List<LinePlace>

    val trying: Boolean get() = false
}

interface LineReader {
    suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine>

    val trying: Boolean get() = false
}
