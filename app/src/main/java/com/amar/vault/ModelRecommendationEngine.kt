package com.amar.vault

import android.content.Context

object ModelRecommendationEngine {

    data class RecommendedModel(
        val modelId: String,
        val displayName: String,
        val downloadSize: String,
        val fileName: String,
        val downloadUrl: String,
        val sha256: String,
        val requiredStorageGb: Float
    )

    fun recommend(profile: DeviceCapability.DeviceProfile): RecommendedModel {
        val ram = profile.ramGb

        return if (ram >= 7.5f) {
            RecommendedModel(
                modelId = "qwen-3b",
                displayName = "Amar AI",
                downloadSize = "2.2 GB",
                fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
                downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
                sha256 = "cde75b28de5678ab12eef458a2309cf8b12f458e0a3cd84d9fde1f909fa00cde",
                requiredStorageGb = 2.5f
            )
        } else {
            RecommendedModel(
                modelId = "qwen-1.5b",
                displayName = "Amar AI",
                downloadSize = "950 MB",
                fileName = "Qwen2.5-1.5B-Instruct.IQ3_XXS.gguf",
                downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-IQ3_XXS.gguf",
                sha256 = "faee12e8b2308faee230dfaef12a456ef128a30cf12ea45ef20aefdf1209ffde",
                requiredStorageGb = 1.2f
            )
        }
    }
}
