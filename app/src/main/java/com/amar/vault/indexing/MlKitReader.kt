package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class MlKitReader(private val client: TextRecognizer) : PictureReader {

    override suspend fun read(picture: Bitmap): FoundText =
        found(client.process(InputImage.fromBitmap(picture, 0)).await())

    companion object {
        fun english(): MlKitReader = MlKitReader(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))

        fun hindi(): MlKitReader =
            MlKitReader(TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()))

        internal fun found(text: Text): FoundText = FoundText(
            text.text,
            text.textBlocks.map { block ->
                FoundBlock(
                    block.text,
                    block.boundingBox?.let { Rect(it) },
                    block.lines.map { line ->
                        FoundLine(line.text, line.confidence, line.boundingBox?.let { Rect(it) })
                    },
                )
            },
        )
    }
}
