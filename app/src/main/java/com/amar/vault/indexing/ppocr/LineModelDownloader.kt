package com.amar.vault.indexing.ppocr

import com.amar.vault.VaultLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class LineModelDownloader(private val files: LineModelFiles, baseUrl: String) {

    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private class Entry(val name: String, val size: Long, val sha256: String)

    suspend fun download(onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dir = files.dir.apply { mkdirs() }
                val entries = entries(text(base + FILES) ?: return@withContext failed("no $FILES at $base"))
                val manifest = entries.firstOrNull { it.name == LineModelFiles.MANIFEST }
                    ?: return@withContext failed("$FILES names no manifest")
                val total = entries.sumOf { it.size }
                var done = 0L
                for (entry in entries) {
                    if (entry === manifest) continue
                    coroutineContext.ensureActive()
                    val target = File(dir, entry.name)
                    if (!(target.length() == entry.size && sha256(target) == entry.sha256)) {
                        val part = File(dir, entry.name + PART)
                        if (!fetch(entry, part) { onProgress(done + it, total) }) {
                            return@withContext failed("${entry.name} did not arrive whole")
                        }
                        if (target.exists()) {
                            File(dir, LineModelFiles.MANIFEST).delete()
                            files.forget()
                            target.delete()
                        }
                        if (!part.renameTo(target)) return@withContext failed("${entry.name} could not be put in place")
                    }
                    done += entry.size
                    onProgress(done, total)
                }
                val part = File(dir, LineModelFiles.MANIFEST + PART)
                if (!fetch(manifest, part) { }) return@withContext failed("the manifest did not arrive whole")
                val target = File(dir, LineModelFiles.MANIFEST)
                target.delete()
                if (!part.renameTo(target)) return@withContext failed("the manifest could not be put in place")
                val named = entries.map { it.name }.toSet()
                dir.listFiles()?.filter { it.isFile && it.name !in named }?.forEach { it.delete() }
                onProgress(total, total)
                files.forget()
                files.present()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e.message ?: e.javaClass.simpleName)
            }
        }

    private fun failed(why: String): Boolean {
        VaultLog.w(TAG, "line readers not downloaded: $why")
        return false
    }

    private fun entries(json: String): List<Entry> {
        val array = JSONObject(json).getJSONArray("files")
        return List(array.length()) {
            val entry = array.getJSONObject(it)
            val name = entry.getString("name")
            require(name.isNotEmpty() && name.none { c -> c == '/' || c == '\\' } && name != ".." && name != FILES) { "bad file name $name" }
            Entry(name, entry.getLong("size"), entry.getString("sha256").lowercase())
        }
    }

    private fun text(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
        }
        return try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun fetch(entry: Entry, part: File, onBytes: (Long) -> Unit): Boolean {
        var have = if (part.exists()) part.length() else 0L
        if (have > entry.size) {
            part.delete()
            have = 0L
        }
        if (have < entry.size) {
            val connection = (URL(base + entry.name).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) return false
                RandomAccessFile(part, "rw").use { out ->
                    if (code == HttpURLConnection.HTTP_PARTIAL) {
                        out.seek(have)
                    } else {
                        out.setLength(0)
                        have = 0L
                    }
                    val buffer = ByteArray(64 * 1024)
                    connection.inputStream.use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            have += read
                            onBytes(have)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        if (have == entry.size && sha256(part) == entry.sha256) return true
        part.delete()
        return false
    }

    private fun sha256(file: File): String {
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "LineReaders"
        const val FILES = "files.json"
        const val PART = ".part"
        const val CONNECT_MS = 15_000
        const val READ_MS = 30_000
    }
}
