package com.amar.vault.ui.renderengine.core

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.amar.vault.StashItemWithVaultItem
import com.amar.vault.ui.renderengine.models.SavedDerivedFacts
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Derives display-only facts (image resolution, PDF page count, file size) straight
 * from the underlying file at read time. This deliberately avoids adding columns to
 * the DB schema — the facts are cheap to recompute and only needed for visible cards.
 *
 * Every method is best-effort and never throws. MUST be called off the main thread.
 */
object SavedFactDeriver {

    fun derive(context: Context, item: StashItemWithVaultItem): SavedDerivedFacts {
        val mime = item.mimeType?.lowercase().orEmpty()
        val uriString = item.uri.ifBlank { item.sourceFile }
        if (uriString.isBlank()) return SavedDerivedFacts.EMPTY

        // Only local content is inspectable; remote http(s) URLs are skipped.
        if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
            return SavedDerivedFacts.EMPTY
        }

        val uri = runCatching { Uri.parse(uriString) }.getOrNull()

        val size = fileSize(context, uri, uriString)
        val isImage = mime.startsWith("image/")
        val resolution = if (isImage) imageResolution(context, uri, uriString) else null
        val pages = if (mime == "application/pdf") pdfPageCount(context, uri, uriString) else null
        val exif = if (isImage) readExif(context, uri, uriString) else SavedDerivedFacts.EMPTY

        return SavedDerivedFacts(
            resolution = resolution,
            pageCount = pages,
            fileSizeBytes = size,
            exifCamera = exif.exifCamera,
            exifLens = exif.exifLens,
            exifDateTaken = exif.exifDateTaken,
            exifSettings = exif.exifSettings
        )
    }

    /** Reads non-location EXIF (camera, lens, date, exposure). GPS is never read. */
    private fun readExif(context: Context, uri: Uri?, raw: String): SavedDerivedFacts {
        return runCatching {
            val exif = openStream(context, uri, raw)?.use { ExifInterface(it) } ?: return SavedDerivedFacts.EMPTY

            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            val camera = when {
                make != null && model != null ->
                    if (model.startsWith(make, ignoreCase = true)) model else "$make $model"
                else -> model ?: make
            }
            val dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val dateFormatted = dateTaken?.let { formatExifDate(it) }

            val settings = buildList {
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.toFloatOrNull()?.let { add("ƒ/$it") }
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { add(formatShutter(it)) }
                @Suppress("DEPRECATION")
                exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { add("ISO $it") }
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { fl ->
                    // Stored as a rational "24/1" — reduce to a plain number.
                    val mm = fl.substringBefore('/').toFloatOrNull()?.let { n ->
                        val d = fl.substringAfter('/', "1").toFloatOrNull() ?: 1f
                        if (d != 0f) n / d else n
                    }
                    if (mm != null) add("${mm.toInt()}mm")
                }
            }.joinToString(" · ").ifBlank { null }

            SavedDerivedFacts(
                exifCamera = camera?.ifBlank { null },
                exifLens = null,
                exifDateTaken = dateFormatted,
                exifSettings = settings
            )
        }.getOrDefault(SavedDerivedFacts.EMPTY)
    }

    private fun formatShutter(seconds: Double): String =
        if (seconds >= 1.0) "${seconds}s" else "1/${(1.0 / seconds).toInt()}s"

    private fun formatExifDate(raw: String): String? = runCatching {
        val parser = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val out = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        parser.parse(raw)?.let { out.format(it) }
    }.getOrNull()

    private fun fileSize(context: Context, uri: Uri?, raw: String): Long? {
        // content:// → query OpenableColumns.SIZE
        if (uri != null && uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                    }
                }
            }
        }
        // file path / file://
        val path = if (uri?.scheme == "file") uri.path else raw
        if (!path.isNullOrBlank()) {
            val f = File(path)
            if (f.exists() && f.length() > 0) return f.length()
        }
        return null
    }

    private fun imageResolution(context: Context, uri: Uri?, raw: String): String? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(context, uri, raw)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) "${opts.outWidth} × ${opts.outHeight}" else null
        }.getOrNull()
    }

    private fun pdfPageCount(context: Context, uri: Uri?, raw: String): Int? {
        return runCatching {
            val pfd: ParcelFileDescriptor? = when {
                uri != null && uri.scheme == "content" ->
                    context.contentResolver.openFileDescriptor(uri, "r")
                else -> {
                    val path = if (uri?.scheme == "file") uri.path else raw
                    if (path != null && File(path).exists())
                        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    else null
                }
            }
            pfd?.use { PdfRenderer(it).use { r -> r.pageCount } }
        }.getOrNull()
    }

    private fun openStream(context: Context, uri: Uri?, raw: String) = runCatching {
        when {
            uri != null && uri.scheme == "content" -> context.contentResolver.openInputStream(uri)
            else -> {
                val path = if (uri?.scheme == "file") uri.path else raw
                if (path != null && File(path).exists()) File(path).inputStream() else null
            }
        }
    }.getOrNull()

    /** Human file size, e.g. "2.4 MB". */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${"%.0f".format(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${"%.1f".format(mb)} MB"
        val gb = mb / 1024.0
        return "${"%.1f".format(gb)} GB"
    }
}
