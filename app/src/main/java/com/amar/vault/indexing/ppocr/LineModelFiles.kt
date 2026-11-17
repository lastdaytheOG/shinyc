package com.amar.vault.indexing.ppocr

import android.content.Context
import com.amar.vault.VaultLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FixedReaderFile(val model: String, val width: Int)

class ReaderFiles(
    val model: String,
    val letters: String,
    val widths: List<Int> = LineStrips.WIDTHS,
    val fixed: List<FixedReaderFile> = emptyList(),
)

class FixedFinderFile(val model: String, val width: Int, val height: Int)

class LineModelManifest(
    val id: String,
    val version: Int,
    val finderModel: String,
    val finder: FinderSettings,
    val readers: Map<String, ReaderFiles>,
    val fixedFinders: List<FixedFinderFile> = emptyList(),
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

    fun fixedFinders(): List<FinderModel> = manifest()?.fixedFinders.orEmpty()
        .map { FinderModel(File(dir, it.model).path, it.width, it.height) }
        .filter { File(it.path).exists() }

    fun fixedReaders(role: String): List<ReaderModel> = manifest()?.readers?.get(role)?.fixed.orEmpty()
        .map { ReaderModel(File(dir, it.model).path, it.width) }
        .filter { File(it.path).exists() }

    fun widths(role: String): List<Int> = manifest()!!.readers.getValue(role).widths

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
                    ReaderFiles(
                        reader.getString("model"),
                        reader.getString("letters"),
                        reader.optJSONArray("widths")?.let { list -> List(list.length()) { list.getInt(it) } }
                            ?.takeIf { it.isNotEmpty() }?.sorted() ?: LineStrips.WIDTHS,
                        objects(reader.opt("gpu")).map { FixedReaderFile(it.getString("model"), it.getInt("width")) },
                    )
                },
                fixedFinders = objects(finder.opt("gpu")).map {
                    FixedFinderFile(it.getString("model"), it.getInt("width"), it.getInt("height"))
                },
            )
        }

        private fun objects(value: Any?): List<JSONObject> = when (value) {
            is JSONObject -> listOf(value)
            is JSONArray -> List(value.length()) { value.getJSONObject(it) }
            else -> emptyList()
        }

        fun lettersIn(text: String): List<String> {
            val lines = text.split('\n').map { it.removeSuffix("\r") }
            return if (lines.last().isEmpty()) lines.dropLast(1) else lines
        }
    }
}
