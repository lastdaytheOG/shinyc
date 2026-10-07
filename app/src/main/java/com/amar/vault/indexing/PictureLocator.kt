package com.amar.vault.indexing

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.amar.vault.PictureFacts

/**
 * Finds out what a picture's file is called and where it is kept ([PictureFacts]), which is
 * what [com.amar.vault.PictureKind] decides by. The gallery knows both; for a picture that is
 * only a file, its name is all there is.
 */
class PictureLocator(private val context: Context) {

    /**
     * The facts of the picture at [uri]. [knownName] is its name when the caller has it and
     * the address does not say: a shared picture is read from a private copy named by an id.
     */
    fun facts(uri: String, knownName: String = ""): PictureFacts {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed?.scheme == "content") {
            galleryId(uri)?.let { id -> one(id)?.let { return it } }
            displayName(parsed)?.let { return PictureFacts(it) }
        }
        return PictureFacts(knownName.ifBlank { uri.substringBefore('?').substringAfterLast('/') })
    }

    /** Every picture in the gallery by its id: for looking at many stored pictures at once. */
    fun gallery(): Map<Long, PictureFacts> {
        val found = HashMap<Long, PictureFacts>()
        runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext()) found[c.getLong(0)] = PictureFacts(c.getString(1).orEmpty(), folderOf(c.getString(2)))
            }
        }
        return found
    }

    private fun one(id: Long): PictureFacts? = runCatching {
        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        context.contentResolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            if (c.moveToFirst()) PictureFacts(c.getString(1).orEmpty(), folderOf(c.getString(2))) else null
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Before Android 10 the gallery gives the whole path of the file rather than its folder. */
    private fun folderOf(pathOrFolder: String?): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pathOrFolder.orEmpty()
        else pathOrFolder.orEmpty().substringBeforeLast('/', "")

    companion object {
        private val COLUMNS = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH
            else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA,
        )

        /** The gallery's id for a picture kept there, from its address; null for any other. */
        fun galleryId(uri: String): Long? {
            val where = uri.trim()
            if (!where.startsWith("content://media/", ignoreCase = true) || !where.contains("/images/")) return null
            return where.substringBefore('?').substringAfterLast('/').toLongOrNull()
        }
    }
}
