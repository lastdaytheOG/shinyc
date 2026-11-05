package com.amar.vault.photos

import android.content.Context
import com.amar.vault.ScanPreferences
import com.amar.vault.VaultLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the photo search models (~207 MB) dynamically outside the APK,
 * as specified in ADR-0002 and D:\amar-models\photo-search\README.md.
 *
 * Requirements:
 * 1. Fetch <base>/files.json
 * 2. Download files to .part (resumable with HTTP Range), check size and SHA-256, rename.
 * 3. Write manifest.json last via PhotoModelFiles.commitManifest (atomic move).
 * 4. Remove previous model files not in new manifest, start PhotoBackfillWorker.
 */
class PhotoModelDownloader(private val context: Context) {

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val currentFile: String, val bytesDownloaded: Long, val totalBytes: Long, val percent: Int) : DownloadState()
        object Verifying : DownloadState()
        object Completed : DownloadState()
        data class Failed(val reason: String) : DownloadState()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    @Volatile private var isCancelled = false

    fun cancel() {
        isCancelled = true
        _state.value = DownloadState.Idle
    }

    suspend fun download(baseUrl: String = DEFAULT_BASE_URL): Boolean = withContext(Dispatchers.IO) {
        isCancelled = false
        val modelFiles = PhotoModelFiles(context)
        val dir = modelFiles.dir
        if (!dir.exists()) dir.mkdirs()

        try {
            val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val filesJsonUrl = normalizedBase + "files.json"

            _state.value = DownloadState.Downloading("files.json", 0, 0, 0)
            val filesJsonText = fetchUrlText(filesJsonUrl) ?: run {
                _state.value = DownloadState.Failed("Could not fetch files.json from $filesJsonUrl")
                return@withContext false
            }

            val rootJson = JSONObject(filesJsonText)
            val filesArray = rootJson.getJSONArray("files")
            val items = mutableListOf<ModelFileEntry>()
            var totalBytes = 0L

            for (i in 0 until filesArray.length()) {
                val obj = filesArray.getJSONObject(i)
                val entry = ModelFileEntry(
                    name = obj.getString("name"),
                    size = obj.getLong("size"),
                    sha256 = obj.getString("sha256")
                )
                items.add(entry)
                totalBytes += entry.size
            }

            var downloadedBytes = 0L
            // Check existing files to compute already-downloaded progress
            for (entry in items) {
                val f = File(dir, entry.name)
                if (f.exists() && f.length() == entry.size) {
                    downloadedBytes += entry.size
                }
            }

            var manifestText: String? = null

            // Download non-manifest files first
            for (entry in items) {
                if (isCancelled) return@withContext false
                val targetFile = File(dir, entry.name)

                if (entry.name == PhotoModelFiles.MANIFEST) {
                    // Fetch manifest content to write last
                    val manifestUrl = normalizedBase + entry.name
                    manifestText = fetchUrlText(manifestUrl)
                    continue
                }

                if (targetFile.exists() && targetFile.length() == entry.size) {
                    // Already exists with exact size; verify SHA256 if needed or skip
                    continue
                }

                val partFile = File(dir, "${entry.name}.part")
                val fileUrl = normalizedBase + entry.name

                val success = downloadResumable(
                    fileUrl = fileUrl,
                    partFile = partFile,
                    expectedSize = entry.size,
                    expectedSha256 = entry.sha256,
                    overallTotal = totalBytes,
                    startDownloadedBytes = downloadedBytes,
                    onProgress = { currentTotalDownloaded ->
                        val percent = if (totalBytes > 0) ((currentTotalDownloaded * 100) / totalBytes).toInt() else 0
                        _state.value = DownloadState.Downloading(entry.name, currentTotalDownloaded, totalBytes, percent.coerceIn(0, 99))
                    }
                )

                if (!success || isCancelled) {
                    if (!isCancelled) _state.value = DownloadState.Failed("Failed downloading ${entry.name}")
                    return@withContext false
                }

                if (!partFile.renameTo(targetFile)) {
                    targetFile.delete()
                    if (!partFile.renameTo(targetFile)) {
                        _state.value = DownloadState.Failed("Failed moving ${entry.name}.part to target")
                        return@withContext false
                    }
                }
                downloadedBytes += entry.size
            }

            // Write manifest.json LAST (atomic commit)
            if (manifestText == null) {
                _state.value = DownloadState.Failed("Manifest content missing from download set")
                return@withContext false
            }

            _state.value = DownloadState.Verifying
            val committed = modelFiles.commitManifest(manifestText)
            if (!committed) {
                _state.value = DownloadState.Failed("Failed committing photo search manifest")
                return@withContext false
            }

            // Trigger vector backfill for existing pictures
            PhotoBackfillWorker.enqueue(context)
            ScanPreferences.setPhotoSearchEnabled(context, true)

            _state.value = DownloadState.Completed
            VaultLog.i(TAG, "Photo search models successfully installed and verified")
            true
        } catch (e: Exception) {
            VaultLog.e(TAG, "Model download failed", e)
            _state.value = DownloadState.Failed(e.message ?: "Download error")
            false
        }
    }

    private fun downloadResumable(
        fileUrl: String,
        partFile: File,
        expectedSize: Long,
        expectedSha256: String,
        overallTotal: Long,
        startDownloadedBytes: Long,
        onProgress: (Long) -> Unit
    ): Boolean {
        var existingLength = if (partFile.exists()) partFile.length() else 0L
        if (existingLength > expectedSize) {
            partFile.delete()
            existingLength = 0L
        }

        val url = URL(fileUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            if (existingLength > 0) {
                setRequestProperty("Range", "bytes=$existingLength-")
            }
        }

        val responseCode = conn.responseCode
        val isResume = (responseCode == HttpURLConnection.HTTP_PARTIAL)
        if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect()
            return false
        }

        val raf = RandomAccessFile(partFile, "rw")
        if (isResume) {
            raf.seek(existingLength)
        } else {
            raf.setLength(0)
            existingLength = 0L
        }

        var currentFileBytes = existingLength
        val buffer = ByteArray(64 * 1024)

        conn.inputStream.use { input ->
            while (!isCancelled) {
                val read = input.read(buffer)
                if (read == -1) break
                raf.write(buffer, 0, read)
                currentFileBytes += read
                onProgress(startDownloadedBytes + (currentFileBytes - existingLength))
            }
        }
        raf.close()
        conn.disconnect()

        if (isCancelled) return false
        if (currentFileBytes != expectedSize) return false

        // Verify SHA256
        val digest = MessageDigest.getInstance("SHA-256")
        partFile.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
        val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return computedSha256.equals(expectedSha256, ignoreCase = true)
    }

    private fun fetchUrlText(urlString: String): String? {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private data class ModelFileEntry(
        val name: String,
        val size: Long,
        val sha256: String
    )

    companion object {
        private const val TAG = "PhotoModelDownloader"
        const val DEFAULT_BASE_URL = "https://github.com/lastdaytheOG/shinyc/releases/download/v1.0-photo-models/"
    }
}
