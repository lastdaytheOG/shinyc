package com.amar.vault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
// TODO(Phase4_AgentRuntime): Restore ReducerEngine import when Agent Runtime is reintegrated.
// import com.amar.vault.agent.runtime.reducer.ReducerEngine
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

    // TODO(Phase4_AgentRuntime): Restore agent runtime injections when Agent Runtime is reintegrated.
    /*
    @Inject lateinit var reducerEngine: ReducerEngine
    @Inject lateinit var accessibilityEventBus: com.amar.vault.agent.runtime.events.AccessibilityEventBus
    @Inject lateinit var imeCoordinator: com.amar.vault.agent.runtime.ime.ImeCoordinator
    @Inject lateinit var semanticBridge: com.amar.vault.agent.runtime.semantic.SemanticBridge
    @Inject lateinit var injectionMetrics: com.amar.vault.agent.runtime.metrics.InjectionMetrics
    @Inject lateinit var overlayDetector: com.amar.vault.agent.runtime.recovery.OverlayDetector
    @Inject lateinit var recoveryEngine: com.amar.vault.agent.runtime.recovery.RecoveryEngine
    @Inject lateinit var phaseOrchestrator: com.amar.vault.agent.runtime.orchestrator.PhaseOrchestrator
    @Inject lateinit var eventHistoryRecorder: com.amar.vault.agent.runtime.replay.EventHistoryRecorder
    @Inject lateinit var adapterManifestLoader: com.amar.vault.agent.runtime.adapters.AdapterManifestLoader
    @Inject lateinit var frameworkAdapterRegistry: com.amar.vault.agent.runtime.adapters.FrameworkAdapterRegistry
    @Inject lateinit var telemetryExporter: com.amar.vault.agent.runtime.telemetry.TelemetryExporter
    @Inject lateinit var worldStateStore: com.amar.vault.agent.runtime.state.WorldStateStore
    */

    override fun onCreate() {
        super.onCreate()
        // TODO(Phase4_AgentRuntime): Restore agent runtime initializations when Agent Runtime is reintegrated.
        /*
        android.util.Log.e("AmarApp", "ONCREATE_ENTERED reducerEngine=${if (::reducerEngine.isInitialized) "injected" else "NOT_INJECTED"}")

        // Step 3: start the WorldState reducer FIRST. It must be live before
        // any other initialization could publish events to the bus. The engine
        // launches its own collector scope; this call returns immediately.
        reducerEngine.start()

        // Step 6: start the IME coordinator and wire it into PerceptionService.
        imeCoordinator.start()
        com.amar.vault.agent.perception.PerceptionService.get()?.bindRuntime(
            bus = accessibilityEventBus,
            imeCoordinator = imeCoordinator,
            worldStateStore = worldStateStore
        )

        // Step 7: start semantic identity resolver bridge.
        semanticBridge.start()
        injectionMetrics.start()
        overlayDetector.start()
        recoveryEngine.start()
        phaseOrchestrator.start()
        eventHistoryRecorder.start()

        // Step 16: discover and register declarative adapters.
        val discovered = adapterManifestLoader.discover()
        discovered.forEach { frameworkAdapterRegistry.registerRuntimeAdapter(it) }
        android.util.Log.i("AmarApp", "STEP16 declarative_adapters=${discovered.size}")
        telemetryExporter.start()
        */

        // If the service isn't connected yet (user enables a11y later), bind
        // again when it connects. For now this no-ops gracefully.

        scope.launch {
            // 1. Copy ONNX model to storage
            withContext(Dispatchers.IO) {
                EmbeddingEngine.warmUp(applicationContext)
            }

            // 2. Load ONNX model into memory
            AppEmbeddingEngine.initialize(applicationContext)

            // 3. Initialize native C++ HNSW vector engine
            VectorSearchManager.getInstance(applicationContext).initialize()
            android.util.Log.d("AmarApp", "VectorSearchManager initialized")

            // 4. Initialize native C++ BM25 search engine + hydrate from Room
            withContext(Dispatchers.IO) {
                // bm25Index (Hilt @Singleton) constructs + initEngine()s the native engine.
                val items = VaultDatabase.get(applicationContext)
                    .vaultDao()
                    .getAllSearchableData()

                items.forEach { item ->
                    val text = "${item.ocrText} ${item.tags} ${item.itemType}"
                    bm25Index.addDocument(item.id, text)
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
            }

            // 5. Schedule nightly job
            NightlyIndexWorker.schedule(applicationContext)

            // 6. Rebuild the timeline from the currently-indexed knowledge. KEEP coalescing
            //    means a build already enqueued/running is left untouched (no rebuild storm).
            com.amar.vault.timeline.engine.TimelineBuildWorker.enqueue(applicationContext)
        }
    }
}