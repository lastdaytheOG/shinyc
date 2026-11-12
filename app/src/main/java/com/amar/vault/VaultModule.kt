package com.amar.vault

import android.content.Context
import com.amar.vault.retrieval.Bm25Index
import com.amar.vault.retrieval.BoostConfig
import com.amar.vault.retrieval.DefaultLexicalRetriever
import com.amar.vault.retrieval.HybridSearchService
import com.amar.vault.retrieval.KeywordIndexFill
import com.amar.vault.retrieval.LexicalRetriever
import com.amar.vault.retrieval.NativeBm25Index
import com.amar.vault.retrieval.RetrievalService
import com.amar.vault.retrieval.SearchRepository
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
    fun provideBm25Index(): Bm25Index = NativeBm25Index()

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
    fun provideMeaningRetriever(photoSearch: com.amar.vault.photos.PhotoSearch): com.amar.vault.retrieval.MeaningRetriever =
        photoSearch.meaning

    @Provides
    @Singleton
    fun providePhotoSearch(@ApplicationContext context: Context, db: VaultDatabase): com.amar.vault.photos.PhotoSearch =
        com.amar.vault.photos.PhotoSearch(context, db)

    @Provides
    @Singleton
    fun providePhotoRetriever(photoSearch: com.amar.vault.photos.PhotoSearch): com.amar.vault.retrieval.PhotoRetriever =
        object : com.amar.vault.retrieval.PhotoRetriever {
            override suspend fun search(query: String, k: Int, only: ((String) -> Boolean)?) =
                photoSearch.search(query, k, only)
        }

    @Provides
    @Singleton
    fun provideRetrievalService(
        repository: SearchRepository,
        lexical: LexicalRetriever,
        boostConfig: BoostConfig,
        photos: com.amar.vault.retrieval.PhotoRetriever,
        meaning: com.amar.vault.retrieval.MeaningRetriever,
        learned: com.amar.vault.vectors.AliasMemory,
        spellings: com.amar.vault.retrieval.SpellingGuesser,
    ): RetrievalService = HybridSearchService(repository, lexical, boostConfig, photos, meaning, learned, spellings)

    @Provides
    @Singleton
    fun provideSpellingGuesser(@ApplicationContext context: Context): com.amar.vault.retrieval.SpellingGuesser =
        com.amar.vault.retrieval.ModelSpellings({
            context.assets.open("hinglish/translit.bin").use { com.amar.vault.retrieval.HinglishModel.read(it) }
        })

    @Provides
    @Singleton
    fun provideAliasMemory(db: VaultDatabase): com.amar.vault.vectors.AliasMemory = com.amar.vault.vectors.AliasMemory(db)
}