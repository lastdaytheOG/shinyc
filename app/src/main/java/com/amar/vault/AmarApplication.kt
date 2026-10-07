package com.amar.vault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltAndroidApp
class AmarApplication : Application(), Configuration.Provider {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Inject lateinit var bm25Index: com.amar.vault.retrieval.Bm25Index

    // WorkManager uses the Hilt factory so @HiltWorker workers get constructor injection.
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        scope.launch {
            // 1–2. Copy the ONNX embedding model to storage and load it. The model is
            //      optional: a build can ship without it, and a failed load must not stop the
            //      steps below — keyword search (BM25) has to come up either way.
            if (AppEmbeddingEngine.isAvailable(applicationContext)) {
                try {
                    withContext(Dispatchers.IO) {
                        EmbeddingEngine.warmUp(applicationContext)
                    }
                    AppEmbeddingEngine.initialize(applicationContext)
                } catch (e: Exception) {
                    VaultLog.e("AmarApp", "Embedding model failed to load — semantic lanes disabled", e)
                }
            } else {
                VaultLog.i("AmarApp", "No embedding model installed — keyword search only")
            }

            // 3. Initialize native C++ HNSW vector engine
            VectorSearchManager.getInstance(applicationContext).initialize()
            android.util.Log.d("AmarApp", "VectorSearchManager initialized")

            // 4. Initialize native C++ BM25 search engine + hydrate from Room
            withContext(Dispatchers.IO) {
                // bm25Index (Hilt @Singleton) constructs + initEngine()s the native engine.
                val database = VaultDatabase.get(applicationContext)
                val items = database.vaultDao().getAllSearchableData()

                items.forEach { item ->
                    bm25Index.addDocument(item.id, com.amar.vault.retrieval.KeywordText.of(item))
                }

                VaultLog.i("AmarApp", "BM25 hydrated: ${items.size} docs")

                // 4b. Intelligent reconciliation — runs ONLY when justified (interrupted
                //     indexing / explicit dirty flag / DB version change). Reuses the id set
                //     already loaded for BM25 hydration, so it costs no extra query. BM25 is
                //     already reconciled by this full rehydrate; this heals the HNSW mappings.
                val health = IndexHealthState(applicationContext)
                if (health.shouldReconcile(IndexHealthState.CURRENT_DB_VERSION)) {
                    IndexMetrics.increment(IndexMetrics.Event.RECONCILE_RUN)
                    val validIds = items.map { it.id }.toSet()
                    val pruned = VectorSearchManager.getInstance(applicationContext).reconcile(validIds)
                    VaultLog.i("AmarApp", "Reconciliation pruned $pruned orphan vector mapping(s)")
                    health.markReconciled(IndexHealthState.CURRENT_DB_VERSION)
                } else {
                    health.recordDbVersion(IndexHealthState.CURRENT_DB_VERSION)
                }
                IndexMetrics.logSnapshot("AmarApp")

                // 4c. Bring what is stored up to the current rules: what each picture is
                //     (screenshot or photo), then each item's tags. Each does something once per
                //     version of its rule; search is already up, and the engine, which indexes
                //     an item's type and tags, is given each changed item again as it is written.
                val reindex: suspend (List<String>) -> Unit = { ids ->
                    ids.chunked(400).forEach { some ->
                        database.vaultDao().getByIds(some).forEach { item ->
                            bm25Index.addDocument(item.id, com.amar.vault.retrieval.KeywordText.of(item))
                        }
                    }
                }
                try {
                    com.amar.vault.indexing.PictureTypeUpkeep(database, applicationContext).retypeIfRuleChanged(reindex)
                    com.amar.vault.indexing.AutoTagUpkeep(database, applicationContext).retagIfRulesChanged(reindex)
                } catch (e: Exception) {
                    VaultLog.e("AmarApp", "Stored items were not brought up to date; they stay as they were", e)
                }
            }

            // 4d. Read the text of documents that were shared in before sharing indexed them.
            SavedDocumentRepairWorker.enqueue(applicationContext)

            // 5. Schedule nightly job
            NightlyIndexWorker.schedule(applicationContext)

            // 6. Rebuild the timeline from the currently-indexed knowledge. KEEP coalescing
            //    means a build already enqueued/running is left untouched (no rebuild storm).
            com.amar.vault.timeline.engine.TimelineBuildWorker.enqueue(applicationContext)
        }
    }
}