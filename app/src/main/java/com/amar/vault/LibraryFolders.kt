package com.amar.vault

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.amar.vault.indexing.FileToRead

object PhotoFolders {

    data class Folder(val path: String, val count: Int) {
        val name: String get() = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
    }

    private class Preset(val folders: List<String>, val isOn: (ScanPreferences.Prefs) -> Boolean, val set: (ScanPreferences.Prefs, Boolean) -> ScanPreferences.Prefs)

    private val PRESETS = listOf(
        Preset(ScanPreferences.SCREENSHOT_FOLDERS, { it.scanScreenshots }, { p, on -> p.copy(scanScreenshots = on) }),
        Preset(ScanPreferences.CAMERA_FOLDERS, { it.scanCamera }, { p, on -> p.copy(scanCamera = on) }),
        Preset(ScanPreferences.WHATSAPP_FOLDERS, { it.scanWhatsApp }, { p, on -> p.copy(scanWhatsApp = on) }),
        Preset(ScanPreferences.DOWNLOAD_FOLDERS, { it.scanDownloads }, { p, on -> p.copy(scanDownloads = on) }),
    )

    private fun covers(entry: String, path: String) = entry.isNotBlank() && path.contains(entry.trimEnd('/'), ignoreCase = true)

    fun isOn(prefs: ScanPreferences.Prefs, path: String): Boolean =
        prefs.scanAll || prefs.foldersToScan().any { covers(it, path) }

    fun turnedOn(prefs: ScanPreferences.Prefs, path: String): ScanPreferences.Prefs {
        if (isOn(prefs, path)) return prefs
        val preset = PRESETS.firstOrNull { p -> p.folders.any { covers(it, path) } }
        return preset?.set?.invoke(prefs, true) ?: prefs.copy(customFolders = prefs.customFolders + path)
    }

    fun turnedOff(prefs: ScanPreferences.Prefs, path: String): ScanPreferences.Prefs {
        var next = prefs.copy(customFolders = prefs.customFolders.filterNot { covers(it, path) }.toSet())
        PRESETS.forEach { p -> if (p.isOn(next) && p.folders.any { covers(it, path) }) next = p.set(next, false) }
        return next
    }

    fun chosenCount(prefs: ScanPreferences.Prefs, folders: List<Folder>): Int = folders.count { isOn(prefs, it.path) }

    fun list(context: Context): List<Folder> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val column = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH
        else MediaStore.Images.Media.DATA
        val counts = HashMap<String, Int>()
        runCatching {
            context.contentResolver.query(collection, arrayOf(column), null, null, null)?.use { cursor ->
                val at = cursor.getColumnIndex(column)
                while (cursor.moveToNext()) {
                    val raw = if (at >= 0) cursor.getString(at).orEmpty() else ""
                    val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) raw else relativeOf(raw)
                    if (path.isNotBlank()) counts[path] = (counts[path] ?: 0) + 1
                }
            }
        }
        return counts.map { (path, count) -> Folder(path, count) }.sortedByDescending { it.count }
    }

    private fun relativeOf(data: String): String {
        val parent = data.substringBeforeLast('/', "")
        val marker = "/0/"
        val at = parent.indexOf(marker)
        return if (at >= 0) parent.substring(at + marker.length) + "/" else ""
    }
}

object DocumentFolders {

    const val MOST_FILES = 2000

    fun documentsIn(context: Context, tree: Uri): List<FileToRead> {
        val found = mutableListOf<FileToRead>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        while (pending.isNotEmpty() && found.size < MOST_FILES) {
            val parent = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            runCatching {
                context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext() && found.size < MOST_FILES) {
                        val id = cursor.getString(0) ?: continue
                        val declared = cursor.getString(1)
                        val name = cursor.getString(2)
                        if (declared == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.addLast(id)
                        } else {
                            val type = DocumentIndexer.resolveMimeType(declared, name) ?: continue
                            found += FileToRead(DocumentsContract.buildDocumentUriUsingTree(tree, id), type, name)
                        }
                    }
                }
            }
        }
        return found
    }
}
