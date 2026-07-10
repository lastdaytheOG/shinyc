package com.amar.vault

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class ModelManager(private val context: Context) {

    private val db = VaultDatabase.get(context)
    private val dao = db.localModelDao()

    // Route LLM lifecycle through the single owner. Bridged via EntryPoint because this
    // class is still a manual singleton (constructor-injection migration is later-phase).
    private val languageModel: com.amar.vault.retrieval.LanguageModel by lazy {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context, com.amar.vault.retrieval.LanguageModelEntryPoint::class.java
        ).languageModel()
    }

    companion object {
        @Volatile
        private var INSTANCE: ModelManager? = null

        fun getInstance(context: Context): ModelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun getAllModelsFlow(): Flow<List<LocalModel>> = dao.getAllModelsFlow()

    fun getEnabledModelFlow(): Flow<LocalModel?> = dao.getEnabledModelFlow()

    suspend fun getEnabledModel(): LocalModel? = withContext(Dispatchers.IO) {
        dao.getEnabledModel()
    }

    suspend fun getModelById(id: String): LocalModel? = withContext(Dispatchers.IO) {
        dao.getModelById(id)
    }

    suspend fun downloadModel(modelId: String) = withContext(Dispatchers.IO) {
        val model = dao.getModelById(modelId) ?: return@withContext
        if (model.status == "READY" || model.status == "DOWNLOADING" || model.status == "VERIFYING") return@withContext

        model.status = "PENDING"
        model.downloadProgress = 0
        dao.update(model)

        val data = Data.Builder()
            .putString("model_id", modelId)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi required
            .setRequiresCharging(false)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "download_$modelId",
            ExistingWorkPolicy.REPLACE,
            downloadRequest
        )
    }

    suspend fun enableModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val model = dao.getModelById(modelId) ?: return@withContext false
        if (model.status != "READY") return@withContext false

        // Transactional toggle inside DB
        dao.enableModel(modelId)

        // Load into NativeLlamaEngine
        val modelFile = File(context.filesDir, "models/${model.fileName}")
        if (modelFile.exists()) {
            val success = languageModel.loadModel(modelFile.absolutePath)
            android.util.Log.i("ModelManager", "Model $modelId loaded into NativeLlamaEngine: $success")
            return@withContext success
        } else {
            model.status = "PENDING"
            model.isEnabled = false
            dao.update(model)
            return@withContext false
        }
    }

    suspend fun disableModel(modelId: String) = withContext(Dispatchers.IO) {
        val model = dao.getModelById(modelId) ?: return@withContext
        if (model.isEnabled) {
            model.isEnabled = false
            dao.update(model)
            languageModel.unload()
            android.util.Log.i("ModelManager", "Model $modelId disabled and unloaded from NativeLlamaEngine")
        }
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        val model = dao.getModelById(modelId) ?: return@withContext
        
        // Disable first
        if (model.isEnabled) {
            disableModel(modelId)
        }

        // Delete local GGUF file
        val modelFile = File(context.filesDir, "models/${model.fileName}")
        if (modelFile.exists()) {
            modelFile.delete()
        }

        model.status = "PENDING"
        model.downloadProgress = 0
        model.isEnabled = false
        dao.update(model)
        android.util.Log.i("ModelManager", "Deleted local GGUF file for model $modelId")
    }

    /**
     * Re-verify a model's file exists and is intact.
     */
    suspend fun checkModelFileStatus(modelId: String): String = withContext(Dispatchers.IO) {
        val model = dao.getModelById(modelId) ?: return@withContext "UNKNOWN"
        val modelFile = File(context.filesDir, "models/${model.fileName}")
        if (modelFile.exists() && model.status == "READY") {
            return@withContext "READY"
        }
        if (model.status == "READY") {
            model.status = "PENDING"
            model.isEnabled = false
            dao.update(model)
            return@withContext "PENDING"
        }
        return@withContext model.status
    }

    suspend fun onVerificationSuccess(modelId: String) = withContext(Dispatchers.IO) {
        val model = dao.getModelById(modelId) ?: return@withContext
        if (model.status == "READY") {
            enableModel(modelId)
        }
    }
}
