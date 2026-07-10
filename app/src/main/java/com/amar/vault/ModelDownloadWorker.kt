package com.amar.vault

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Data
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString("model_id") ?: return Result.failure()
        val db = VaultDatabase.get(applicationContext)
        val dao = db.localModelDao()
        val model = dao.getModelById(modelId) ?: return Result.failure()

        try {
            model.status = "DOWNLOADING"
            dao.update(model)

            val destDir = File(applicationContext.filesDir, "models")
            if (!destDir.exists()) destDir.mkdirs()

            val tempFile = File(applicationContext.cacheDir, "download_${modelId}.gguf.tmp")
            val existingLength = if (tempFile.exists()) tempFile.length() else 0L

            val url = URL(model.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            if (existingLength > 0) {
                connection.setRequestProperty("Range", "bytes=$existingLength-")
            }
            connection.connect()

            val responseCode = connection.responseCode
            val append = (responseCode == HttpURLConnection.HTTP_PARTIAL)

            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                model.status = "FAILED"
                dao.update(model)
                return Result.failure()
            }

            val contentLength = connection.contentLength
            val fileLength = if (append) {
                existingLength + contentLength
            } else {
                contentLength.toLong()
            }

            if (!append && tempFile.exists()) {
                tempFile.delete()
            }

            val input = connection.inputStream
            val output = FileOutputStream(tempFile, append)

            val data = ByteArray(4096)
            var total: Long = if (append) existingLength else 0L
            var count: Int
            var lastUpdate = System.currentTimeMillis()

            while (input.read(data).also { count = it } != -1) {
                if (isStopped) {
                    output.close()
                    input.close()
                    tempFile.delete()
                    model.status = "PENDING"
                    model.downloadProgress = 0
                    dao.update(model)
                    return Result.failure()
                }

                // Thermal Check: if thermal status is severe/critical, pause/slow down download
                val state = DeviceCapability.getDeviceState(applicationContext)
                if (state.thermalStatus >= DeviceCapability.ThermalStatus.SEVERE) {
                    android.util.Log.w("ModelDownload", "Thermal status is SEVERE/CRITICAL. Sleeping download for 2 seconds.")
                    kotlinx.coroutines.delay(2000)
                }

                total += count
                output.write(data, 0, count)

                if (fileLength > 0) {
                    val progress = (total * 100 / fileLength).toInt()
                    val now = System.currentTimeMillis()
                    if (progress > model.downloadProgress && now - lastUpdate > 1000) {
                        model.downloadProgress = progress
                        dao.update(model)
                        lastUpdate = now
                    }
                }
            }

            output.flush()
            output.close()
            input.close()

            // Update status to DOWNLOADED
            model.status = "DOWNLOADED"
            model.downloadProgress = 100
            dao.update(model)

            // Trigger Verification
            val verificationData = Data.Builder()
                .putString("model_id", modelId)
                .putString("temp_file_path", tempFile.absolutePath)
                .build()

            val verificationRequest = OneTimeWorkRequestBuilder<ModelVerificationWorker>()
                .setInputData(verificationData)
                .build()

            WorkManager.getInstance(applicationContext).enqueue(verificationRequest)

            return Result.success()

        } catch (e: Exception) {
            android.util.Log.e("ModelDownload", "Download failed for model $modelId", e)
            model.status = "FAILED"
            dao.update(model)
            return Result.failure()
        }
    }
}
