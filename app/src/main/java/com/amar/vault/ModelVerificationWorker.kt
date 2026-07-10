package com.amar.vault

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ModelVerificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString("model_id") ?: return Result.failure()
        val tempFilePath = inputData.getString("temp_file_path") ?: return Result.failure()
        
        val db = VaultDatabase.get(applicationContext)
        val dao = db.localModelDao()
        val model = dao.getModelById(modelId) ?: return Result.failure()

        try {
            model.status = "VERIFYING"
            dao.update(model)

            val tempFile = File(tempFilePath)
            if (!tempFile.exists()) {
                model.status = "FAILED"
                dao.update(model)
                return Result.failure()
            }

            // Compute SHA-256
            val sha256 = computeSha256(tempFile)
            android.util.Log.i("ModelVerification", "Computed SHA-256: $sha256 | Expected: ${model.sha256}")

            if (sha256.equals(model.sha256, ignoreCase = true)) {
                // Success! Move to models directory
                val destDir = File(applicationContext.filesDir, "models")
                if (!destDir.exists()) destDir.mkdirs()

                val destFile = File(destDir, model.fileName)
                if (destFile.exists()) destFile.delete()

                val success = tempFile.renameTo(destFile)
                if (success || destFile.exists()) {
                    model.status = "READY"
                    dao.update(model)
                    android.util.Log.i("ModelVerification", "Model verification successful, moved file to: ${destFile.absolutePath}")
                    
                    // Trigger auto-activation
                    ModelManager.getInstance(applicationContext).onVerificationSuccess(modelId)
                    return Result.success()
                } else {
                    android.util.Log.e("ModelVerification", "Failed to move verified model file to target folder.")
                }
            }

            // Failed or validation failed
            tempFile.delete()
            model.status = "FAILED"
            dao.update(model)
            return Result.failure()

        } catch (e: Exception) {
            android.util.Log.e("ModelVerification", "Verification exception for model $modelId", e)
            val tempFile = File(tempFilePath)
            if (tempFile.exists()) tempFile.delete()
            model.status = "FAILED"
            dao.update(model)
            return Result.failure()
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        val fis = FileInputStream(file)
        var bytesRead: Int
        while (fis.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        fis.close()
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
