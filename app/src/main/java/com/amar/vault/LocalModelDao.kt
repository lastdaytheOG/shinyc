package com.amar.vault

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: LocalModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(models: List<LocalModel>)

    @Update
    suspend fun update(model: LocalModel)

    @Query("SELECT * FROM local_models")
    fun getAllModelsFlow(): Flow<List<LocalModel>>

    @Query("SELECT * FROM local_models")
    suspend fun getAllModels(): List<LocalModel>

    @Query("SELECT * FROM local_models WHERE modelId = :id LIMIT 1")
    suspend fun getModelById(id: String): LocalModel?

    @Query("SELECT * FROM local_models WHERE isEnabled = 1 LIMIT 1")
    suspend fun getEnabledModel(): LocalModel?

    @Query("SELECT * FROM local_models WHERE isEnabled = 1 LIMIT 1")
    fun getEnabledModelFlow(): Flow<LocalModel?>

    @Query("UPDATE local_models SET isEnabled = 0")
    suspend fun disableAllModels()

    /** Remove a model row entirely. Used by Developer Tools to drop dev-imported models
     *  (which have no remote source to re-download from). */
    @Query("DELETE FROM local_models WHERE modelId = :id")
    suspend fun deleteById(id: String)

    @Transaction
    suspend fun enableModel(modelId: String) {
        disableAllModels()
        val model = getModelById(modelId)
        if (model != null) {
            model.isEnabled = true
            insert(model)
        }
    }
}
