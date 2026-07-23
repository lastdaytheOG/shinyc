package com.amar.vault.indexing

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

/** One native Tesseract instance and one mutex for the entire app process. */
internal object TesseractRuntime {
    private const val TESS_DATA_DIR = "tessdata"
    private val mutex = Mutex()
    private var api: TessBaseAPI? = null

    suspend fun <T> withApi(context: Context, block: suspend (TessBaseAPI) -> T): T = mutex.withLock {
        val shared = api ?: create(context.applicationContext).also { api = it }
        block(shared)
    }

    private fun create(context: Context): TessBaseAPI {
        val tessDir = File(context.filesDir, TESS_DATA_DIR)
        tessDir.mkdirs()
        listOf("eng.traineddata", "hin.traineddata").forEach { filename ->
            val destination = File(tessDir, filename)
            if (!destination.exists()) {
                runCatching {
                    context.assets.open("$TESS_DATA_DIR/$filename").use { input ->
                        FileOutputStream(destination).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        return TessBaseAPI().also { created ->
            if (!created.init(context.filesDir.absolutePath, "eng+hin")) {
                android.util.Log.e("Tesseract", "Init failed")
            }
        }
    }
}
