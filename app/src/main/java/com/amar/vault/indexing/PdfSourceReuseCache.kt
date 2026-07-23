package com.amar.vault.indexing

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Tiny content-addressable reuse index for completed PDFs.
 *
 * The normal text-content hash is available only after OCR, so it cannot prevent a repeat import
 * from doing the expensive work first. This cache maps an exact SHA-256 of source bytes to the
 * already-committed text hash and chunk count. A cache hit is verified against Room before it can
 * skip work; a missing, partial, or corrupt entry fails closed into the normal extractor.
 */
internal object PdfSourceReuseCache {
    data class Entry(val textHash: String, val chunkCount: Int)

    private const val DIRECTORY = "index-cache"
    private const val FILE_NAME = "pdf-source-reuse-v1"
    private val lock = Any()

    fun fingerprint(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use(::fingerprint) ?: return null
    }.getOrNull()

    fun lookup(context: Context, sourceHash: String): Entry? = synchronized(lock) {
        readEntries(file(context))[sourceHash]
    }

    fun remember(context: Context, sourceHash: String, textHash: String, chunkCount: Int) {
        if (sourceHash.isBlank() || textHash.isBlank() || chunkCount <= 0) return
        synchronized(lock) {
            val target = file(context)
            val entries = readEntries(target).toMutableMap()
            entries[sourceHash] = Entry(textHash, chunkCount)
            writeEntries(target, entries)
        }
    }

    fun forget(context: Context, sourceHash: String) {
        synchronized(lock) {
            val target = file(context)
            val entries = readEntries(target).toMutableMap()
            if (entries.remove(sourceHash) != null) writeEntries(target, entries)
        }
    }

    internal fun fingerprint(stream: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun file(context: Context): File = File(context.filesDir, DIRECTORY).let { directory ->
        directory.mkdirs()
        File(directory, FILE_NAME)
    }

    private fun readEntries(file: File): Map<String, Entry> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    val fields = line.split('|')
                    val chunks = fields.getOrNull(2)?.toIntOrNull()
                    if (fields.size == 3 && fields[0].length == 64 && fields[1].isNotBlank() && chunks != null && chunks > 0) {
                        fields[0] to Entry(fields[1], chunks)
                    } else null
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeEntries(target: File, entries: Map<String, Entry>) {
        val content = entries.entries.joinToString("\n") { (source, entry) ->
            "$source|${entry.textHash}|${entry.chunkCount}"
        }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            temporary.writeText(content)
            if (target.exists()) target.delete()
            temporary.renameTo(target)
        }
    }
}
