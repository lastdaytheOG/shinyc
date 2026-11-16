package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Rect

class FoundLine(val text: String, val confidence: Float, val box: Rect?)

class FoundBlock(val text: String, val box: Rect?, val lines: List<FoundLine>)

class FoundText(val text: String, val blocks: List<FoundBlock>)

interface PictureReader {
    suspend fun read(picture: Bitmap): FoundText
}
