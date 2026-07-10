package com.amar.vault.dev

import android.content.Context
import com.amar.vault.RagService
import com.amar.vault.retrieval.LanguageModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Bridge that lets the (non-injected) Developer Tools composables reach the SAME shared singletons
 * the production app uses — [RagService], [LanguageModel]. It resolves them from the existing Hilt
 * graph rather than constructing new ones, so the AI-model tests exercise the real pipeline. No new
 * implementations are introduced.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DevServicesEntryPoint {
    fun ragService(): RagService
    fun languageModel(): LanguageModel
}

object DevServices {
    private fun ep(context: Context): DevServicesEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, DevServicesEntryPoint::class.java)

    fun rag(context: Context): RagService = ep(context).ragService()
    fun languageModel(context: Context): LanguageModel = ep(context).languageModel()
}
