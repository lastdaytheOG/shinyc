package com.amar.vault

import android.content.Context
import com.amar.vault.retrieval.Bm25Index
import com.amar.vault.retrieval.BoostConfig
import com.amar.vault.retrieval.DefaultLexicalRetriever
import com.amar.vault.retrieval.DefaultSemanticRetriever
import com.amar.vault.retrieval.HybridSearchService
import com.amar.vault.retrieval.KeywordIndexFill
import com.amar.vault.retrieval.LanguageModel
import com.amar.vault.retrieval.LexicalRetriever
import com.amar.vault.retrieval.NativeBm25Index
import com.amar.vault.retrieval.NativeLanguageModel
import com.amar.vault.retrieval.RetrievalService
import com.amar.vault.retrieval.SearchRepository
import com.amar.vault.retrieval.SemanticRetriever
import com.amar.vault.retrieval.VaultSearchRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @Singleton
    fun provideVaultDatabase(@ApplicationContext context: Context): VaultDatabase {
        return VaultDatabase.get(context)
    }

    @Provides
    @Singleton
    fun provideVectorSearchManager(@ApplicationContext context: Context): VectorSearchManager {
        return VectorSearchManager.getInstance(context)
    }

    // ── Native-engine boundaries (Phase 2A) — single owners behind JNI-isolating interfaces ──

    @Provides
    @Singleton
    fun provideBm25Index(): Bm25Index = NativeBm25Index()

    @Provides
    @Singleton
    fun provideLanguageModel(): LanguageModel = NativeLanguageModel()

    @Provides
    @Singleton
    fun provideBoostConfig(): BoostConfig = BoostConfig()

    // ── Injectable wrappers over existing manual singletons (Phase 2A worker DI) ──
    // These delegate to the current factories so instances are unchanged; they let
    // workers inject collaborators instead of calling getInstance() inside doWork().

    @Provides
    @Singleton
    fun provideIndexingPipeline(@ApplicationContext context: Context): IndexingPipeline =
        IndexingPipeline.getInstance(context)

    @Provides
    @Singleton
    fun provideDocumentIndexer(@ApplicationContext context: Context): DocumentIndexer =
        DocumentIndexer.getInstance(context)

    @Provides
    @Singleton
    fun provideIndexHealthState(@ApplicationContext context: Context): IndexHealthState =
        IndexHealthState(context)

    @Provides
    @Singleton
    fun provideDiscoveryEngine(@ApplicationContext context: Context): com.amar.vault.indexing.DiscoveryEngine =
        com.amar.vault.indexing.DiscoveryEngine(context)

    // ── Retrieval layer (Phase 1) ────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideSearchRepository(db: VaultDatabase): SearchRepository =
        VaultSearchRepository(db)

    @Provides
    @Singleton
    fun provideKeywordIndexFill(db: VaultDatabase, bm25: Bm25Index): KeywordIndexFill =
        KeywordIndexFill(db, bm25)

    @Provides
    @Singleton
    fun provideLexicalRetriever(db: VaultDatabase, bm25: Bm25Index, fill: KeywordIndexFill): LexicalRetriever =
        DefaultLexicalRetriever(db, bm25, fill)

    @Provides
    @Singleton
    fun provideSemanticRetriever(
        @ApplicationContext context: Context,
        vectorSearchManager: VectorSearchManager,
    ): SemanticRetriever = DefaultSemanticRetriever(context, vectorSearchManager)

    @Provides
    @Singleton
    fun provideRetrievalService(
        repository: SearchRepository,
        lexical: LexicalRetriever,
        semantic: SemanticRetriever,
        boostConfig: BoostConfig,
    ): RetrievalService = HybridSearchService(repository, lexical, semantic, boostConfig)
}