package com.amar.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiOnboardingViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val modelManager = ModelManager.getInstance(application)

    data class UiState(
        val isLoadingProfile: Boolean = true,
        val profile: DeviceCapability.DeviceProfile? = null,
        val recommendedModel: ModelRecommendationEngine.RecommendedModel? = null,
        val downloadState: DownloadState = DownloadState.IDLE,
        val downloadProgress: Int = 0,
        val showStorageWarning: Boolean = false,
        val navigateToAskVault: Boolean = false
    )

    enum class DownloadState {
        IDLE, DOWNLOADING, VERIFYING, PREPARING, FAILED, READY
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadProfile()
        observeActiveModel()
    }

    private fun loadProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = DeviceCapability.getDeviceProfile(getApplication())
            val recommended = ModelRecommendationEngine.recommend(profile)
            _uiState.update { 
                it.copy(
                    isLoadingProfile = false,
                    profile = profile,
                    recommendedModel = recommended
                ) 
            }
            // Once we have the recommendation, start observing its download state
            observeModelState(recommended.modelId)
        }
    }

    private fun observeModelState(recommendedId: String) {
        viewModelScope.launch {
            modelManager.getAllModelsFlow().collect { models ->
                val modelState = models.find { it.modelId == recommendedId }
                
                val newState = when (modelState?.status) {
                    "DOWNLOADING" -> DownloadState.DOWNLOADING
                    "VERIFYING" -> DownloadState.VERIFYING
                    "PENDING", "DOWNLOADED" -> DownloadState.PREPARING
                    "READY" -> DownloadState.READY
                    "FAILED" -> DownloadState.FAILED
                    else -> DownloadState.IDLE
                }

                _uiState.update {
                    it.copy(
                        downloadState = newState,
                        downloadProgress = modelState?.downloadProgress ?: 0
                    )
                }
            }
        }
    }

    private fun observeActiveModel() {
        viewModelScope.launch {
            modelManager.getEnabledModelFlow().collect { activeModel ->
                if (activeModel?.isEnabled == true && activeModel.status == "READY") {
                    _uiState.update { it.copy(navigateToAskVault = true) }
                }
            }
        }
    }

    fun onDownloadClicked() {
        val state = _uiState.value
        val profile = state.profile ?: return
        val recommended = state.recommendedModel ?: return

        if (profile.freeStorageGb < recommended.requiredStorageGb) {
            _uiState.update { it.copy(showStorageWarning = true) }
        } else {
            viewModelScope.launch {
                modelManager.downloadModel(recommended.modelId)
            }
        }
    }

    fun dismissStorageWarning() {
        _uiState.update { it.copy(showStorageWarning = false) }
    }

    fun onNavigated() {
        _uiState.update { it.copy(navigateToAskVault = false) }
    }
}
