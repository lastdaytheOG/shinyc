package com.amar.vault.indexing.ppocr

import android.content.Context
import com.amar.vault.VaultLog
import org.json.JSONObject
import java.io.File

class ReaderFiles(val model: String, val letters: String)

class GpuFinderFiles(val model: String, val width: Int, val height: Int)

class LineModelManifest(
    val id: String,
    val version: Int,
    val finderModel: String,
    val finder: FinderSettings,
    val readers: Map<String, ReaderFiles>,
    val gpuFinder: GpuFinderFiles? = null,
) {
    val files: List<String> get() = listOf(finderModel) + readers.values.flatMap { listOf(it.model, it.letters) }
}

class LineModelFiles(val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, DIR))

    @Volatile
    private var cached: LineModelManifest? = null

    fun manifest(): LineModelManifest? {
        cached?.let { return it }
        val file = File(dir, MANIFEST)
        if (!file.exists()) return null
        return try {
            parse(file.readText()).also { cached = it }
        } catch (e: Exception) {
            VaultLog.w("LineReaders", "unreadable line reader manifest: ${e.message}")
            null
        }
    }

    fun forget() {
        cached = null
    }

    fun present(): Boolean {
        val manifest = manifest() ?: return false
        return ROLES.all { it in manifest.readers } && manifest.files.all { File(dir, it).exists() }
    }

    fun finder(): File = File(dir, manifest()!!.finderModel)

    fun gpuFinder(): File? = manifest()?.gpuFinder?.let { File(dir, it.model) }?.takeIf { it.exists() }

    fun reader(role: String): File = File(dir, manifest()!!.readers.getValue(role).model)

    fun letters(role: String): List<String> = lettersIn(File(dir, manifest()!!.readers.getValue(role).letters).readText())

    companion object {
        const val DIR = "line-readers"
        const val MANIFEST = "manifest.json"
        const val ALL = "all"
        const val NUMBERS = "numbers"
        const val HINDI = "hindi"
        val ROLES = listOf(ALL, NUMBERS, HINDI)

        fun parse(json: String): LineModelManifest {
            val j = JSONObject(json)
            val finder = j.getJSONObject("finder")
            val readers = j.getJSONObject("readers")
            return LineModelManifest(
                id = j.getString("id"),
                version = j.getInt("version"),
                finderModel = finder.getString("model"),
                finder = FinderSettings(
                    longSide = finder.getInt("longSide"),
                    thresh = finder.getDouble("thresh").toFloat(),
                    boxThresh = finder.getDouble("boxThresh").toFloat(),
                    unclip = finder.getDouble("unclip").toFloat(),
                ),
                readers = readers.keys().asSequence().associateWith { role ->
                    val reader = readers.getJSONObject(role)
                    ReaderFiles(reader.getString("model"), reader.getString("letters"))
                },
                gpuFinder = finder.optJSONObject("gpu")?.let {
                    GpuFinderFiles(it.getString("model"), it.getInt("width"), it.getInt("height"))
                },
            )
        }

        fun lettersIn(text: String): List<String> {
            val lines = text.split('\n').map { it.removeSuffix("\r") }
            return if (lines.last().isEmpty()) lines.dropLast(1) else lines
        }
    }
}
