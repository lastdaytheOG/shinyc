package com.amar.vault.retrieval

import com.amar.vault.NativeLlamaEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Boundary over the native llama.cpp inference engine.
 *
 * Justified as an interface by raw-JNI/FFI isolation: `RagService`'s testable logic
 * (prompt assembly, failure→message mapping) must not transitively require the native
 * `.so`, and this gives the LLM engine's lifecycle a single owner.
 *
 * Ownership split (per the Phase 2A validation): this adapter owns engine **lifecycle**
 * (load / unload / reload-recovery); `RagService` retains failure **policy** (retry budget
 * and user-facing messages). Result/enum types are reused from [NativeLlamaEngine] to keep
 * this change behaviour-neutral.
 */
interface LanguageModel {
    fun isLoaded(): Boolean
    suspend fun loadModel(path: String): Boolean
    suspend fun generateBlocking(prompt: String): NativeLlamaEngine.GenerationResult
    suspend fun unload()
    /** Cooperatively cancel any in-flight generation (fire-and-forget). */
    fun cancelGeneration()
}

/** Single owner of the native llama engine, delegating to the process singleton. */
class NativeLanguageModel : LanguageModel {
    override fun isLoaded(): Boolean = NativeLlamaEngine.isLoaded()
    override suspend fun loadModel(path: String): Boolean = NativeLlamaEngine.loadModel(path)
    override suspend fun generateBlocking(prompt: String): NativeLlamaEngine.GenerationResult =
        NativeLlamaEngine.generateBlocking(prompt)
    override suspend fun unload() = NativeLlamaEngine.unload()
    override fun cancelGeneration() = NativeLlamaEngine.cancelGeneration()
}

/**
 * Bridge for components not yet migrated to constructor injection (the `ModelManager`
 * process-singleton). Lets them reach the single [LanguageModel] owner without
 * reintroducing direct native access.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LanguageModelEntryPoint {
    fun languageModel(): LanguageModel
}
