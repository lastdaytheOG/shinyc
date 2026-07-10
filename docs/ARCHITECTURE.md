# Amar Vault — Foundation Architecture

**Status: FROZEN as of Sprint 3B (2026-07).** This is the ownership map of the
vault's core. A contributor should be able to answer "who owns X?" from this file
without reading implementation code. After Sprint 3B, changes to this structure
require a real production issue, not preference.

Companion policy doc: [BACKUP_AND_PRIVACY.md](BACKUP_AND_PRIVACY.md).

---

## 1. The pipelines (data flow)

```
                         INDEXING (write side)
 ┌──────────────────────────────────────────────────────────────────────┐
 │ Triggers: BulkScanWorker · ScreenshotObserver · NightlyIndexWorker   │
 │           ShareImportWorker · DocumentPicker · FolderSyncObserver    │
 │           (batching/discovery owned by indexing.DiscoveryEngine)     │
 └───────────────┬────────────────────────────────┬─────────────────────┘
                 │  images / screenshots          │  documents (PDF, DOCX, …)
                 ▼                                ▼
        IndexingPipeline                    DocumentIndexer
    OCR → MetadataStage →              DocumentContentExtractor →
    IndexPersister (Room commit)       DuplicateDetector →
         │                             IndexPersister (Room commit)
         │ commit-then-index                  │ commit-then-index
         ▼                                    ▼
    WordWindowChunker →                SentenceAwareChunker (inside extract) →
    embed (EmbeddingEngine) →          Bm25IndexUpdater → native BM25
    VectorIndexUpdater →
    VectorSearchManager (HNSW)

                         RETRIEVAL (read side)
 ┌──────────────────────────────────────────────────────────────────────┐
 │ SearchViewModel / RagService / EntityAggregator / … call             │
 │              RetrievalService.retrieve(RetrievalRequest)             │
 └──────────────────────────────┬───────────────────────────────────────┘
                                ▼
                      HybridSearchService
      BM25 lane ── LexicalRetriever ── Bm25Index ── NativeSearchEngine
      Vector lane ─ SemanticRetriever ─ VectorSearchManager ─ hnswlib
      Substring + fuzzy lanes ─ SearchRepository.allItemsSnapshot()
                                │
              RRF fusion → metadata boosts → temporal re-rank
                                ▼
                        RetrievalResult(items)

                         RAG / ASK VAULT
      query → RetrievalService → context assembly → prompt templates
            → LanguageModel (NativeLlamaEngine, llama.cpp) → answer
      (RagService owns failure policy; LanguageModel owns engine lifecycle)
```

Invariants (do not break):

- **One indexing pipeline per modality**, both funneling all Room writes through
  `IndexPersister`.
- **Commit-before-index**: derived indexes (BM25, HNSW) are updated only after
  the Room transaction commits. A crash can leave Room ahead of the indexes,
  never behind — indexes are rebuildable, Room is not.
- **One retrieval pipeline**: everything that searches goes through
  `RetrievalService` (implemented by `HybridSearchService`). There is no second
  ranking implementation.
- **One Room writer** on the indexing path (`IndexPersister`), **one Room
  reader** on the retrieval path (`SearchRepository` / `VaultSearchRepository`).

## 2. Ownership map

| Concern | Owner (single) | Notes |
|---|---|---|
| Room database `vault.db` | `VaultDatabase` (schema v12) | **Source of truth.** Everything else is a rebuildable cache. |
| Indexing-path Room writes | `indexing.IndexPersister` | Owns all transaction boundaries on the write path. |
| Retrieval-path Room reads | `retrieval.SearchRepository` → `VaultSearchRepository` | Batched hydration + metadata-constraint queries only. |
| Image indexing orchestration | `IndexingPipeline` | OCR → metadata → persist → chunk → embed → HNSW. |
| Document indexing orchestration | `DocumentIndexer` | Extract → dedupe → chunk rows → persist → BM25. No embedding at index time. |
| Discovery & batching | `indexing.DiscoveryEngine` + `BatchPolicy` | What to scan, in what batches. |
| Chunking | `indexing.Chunker` (`WordWindowChunker` images, `SentenceAwareChunker` docs) | Static per modality. |
| Embedding inference | `EmbeddingEngine` (via `AppEmbeddingEngine`) | BGE-M3 int4 ONNX, dim from `VaultConfig.Embedding.DIM`. |
| Embedding identity | `EmbeddingManifest` (written by `VectorSearchManager.initialize`) | See §3. |
| Vector index (HNSW) | `VectorSearchManager` → `NativeVectorEngine` (hnswlib) | Owns index files, id mappings, delete propagation, orphan reconcile. |
| Vector id ↔ chunk mapping | `VectorIdMapper` (`ChunkRecord`) | Explicit parentId/chunkIndex/totalChunks per vector. See §4. |
| BM25 index | `retrieval.Bm25Index` → `NativeBm25Index` (native) | Rebuildable, rehydrated from Room at startup; no persistence by design. |
| Retrieval strategy | `retrieval.HybridSearchService` behind `RetrievalService` | Lanes → RRF fusion → boosts → gating → temporal re-rank. Tuning constants frozen in `VaultConfig.Retrieval`. |
| Retrieval boosts config | `retrieval.BoostConfig` | |
| RAG orchestration | `RagService.executeRag` | Retrieval → prompt → generation → grounding. Also reused by Summaries / Document Chat. |
| LLM engine lifecycle | `retrieval.LanguageModel` → `NativeLanguageModel` (llama.cpp) | RagService keeps failure policy; the adapter keeps lifecycle. |
| Suggestions / recent searches | `search.core.SuggestionEngine` + `HistoryManager` (in-memory) | The only surviving consumers of the legacy `search.core` package. |
| Backup & privacy policy | `docs/BACKUP_AND_PRIVACY.md` + manifest + res/xml rules | Nothing is backed up. See policy doc. |

## 3. Embedding lifecycle & model versioning

Identity constants (single source of truth): `VaultConfig.Embedding.MODEL_ID`,
`MODEL_VERSION`, `SCHEMA_VERSION`, `DIM`.

```
index time   chunk text ── EmbeddingEngine.embed ── FloatArray(DIM)
                    │
                    ▼
             VectorSearchManager.indexVector(chunkId, vec, parentId, chunkIndex, totalChunks)
                    │            (numeric id assigned by VectorIdMapper)
                    ▼
             native HNSW graph (files/)  +  vector_mappings.json (schema v2)
                    ▲
init time    EmbeddingManifest.ensure(files/embedding_manifest.json)
             — records embeddingModelId / embeddingModelVersion / embeddingSchemaVersion / dim
             — adopts current identity on first run (pre-3B vectors were produced by
               the same, never-changed model, so adoption is exact)
             — logs a mismatch if the running identity ever differs (migration hook)
```

The vault runs **one embedding space at a time**; identity is therefore recorded
once at store level (manifest + mappings-file header), covering every stored
vector, instead of being duplicated per entry. There are no mixed-model vectors
by construction.

**Model upgrade protocol (future):** bump `MODEL_ID`/`MODEL_VERSION`/`SCHEMA_VERSION`
→ on next init the manifest mismatch is detected → a background migration
re-embeds all chunks from Room (source of truth), rebuilds the HNSW index and
mappings, then rewrites the manifest. Old and new vectors are never mixed in one
index. Until that migration ships, the running model must not be changed.

## 4. Chunk lifecycle & ownership

A *chunk* is the unit both native indexes operate on. Ownership is **explicit**
(Sprint 3B, Task 2) — never parsed from an id string:

- **Document chunks** are Room rows in `vault_items` carrying
  `parentDocumentId` (the logical document id), `chunkIndex`, `totalChunks`.
  Standalone items (images, screenshots, shared links) have
  `parentDocumentId = NULL` — they are their own root.
- **Vector chunks** are entries in `vector_mappings.json` (schema v2), each a
  `ChunkRecord(chunkId, parentId, chunkIndex, totalChunks)`.
- `indexing.IndexEntry` carries the same fields from the orchestrators into the
  index updaters.

Migration of pre-3B data happens exactly once per store:

- Room: `MIGRATION_11_12` backfills the columns from the historical
  `"${parent}_chunk${i}"` convention.
- Vector mappings: a v1 flat file is adopted into `ChunkRecord`s at first load
  (`VectorSearchManager.adoptV1Mappings`) and rewritten as v2 on next persist.

These two sites are the sanctioned, final uses of the id convention. Chunk ids
keep their historical shape as *keys*, but the shape is no longer a schema.

Delete propagation and orphan reconciliation (`VectorSearchManager.removeItem` /
`reconcile`) match on `ChunkRecord.parentId`. This unlocks (future): document
reconstruction, chunk replacement, per-document re-indexing.

## 5. Persistence & recovery matrix

| Store | File(s) | Durability | Recovery |
|---|---|---|---|
| Room | `databases/vault.db` | Transactional, migrated (v12) | It IS the recovery source. |
| HNSW vectors | `files/` (native) + `vector_mappings.json` | `saveState()` flush points; mappings coalesced-persisted | Re-embed/re-index from Room; orphans pruned by `reconcile`. |
| Embedding manifest | `files/embedding_manifest.json` | Written once at init | Recreated (re-adopted) automatically. |
| BM25 | in-memory native | None by design | Rebuilt from Room at every launch. |
| FTS4 `vault_fts` | inside `vault.db` | Maintained by Room triggers | Rebuilt with the content table. |
| Models | `files/` (ONNX, GGUF) | n/a | Re-copied from assets / re-downloaded. |

## 6. Database migration strategy

- Migrations are explicit `Migration(n, n+1)` objects in `VaultDatabase` —
  additive `ALTER TABLE` + backfill; **never** destructive fallback
  (`fallbackToDestructiveMigration` is not used and must not be added).
- New NOT NULL columns declare `@ColumnInfo(defaultValue = …)` matching the
  migration SQL exactly.
- Native index compatibility across migrations is guaranteed by the reconcile
  path, not by migrating index files: if indexes and Room disagree, Room wins
  and the index entries are pruned/rebuilt.
- Embedding-space migrations are a separate mechanism (manifest mismatch, §3),
  not Room migrations.

## 7. Legacy status (post-3B audit)

Removed in Sprint 3B (dead in production, verified no callers):
`search.core.SearchEngine`, `RankingPipeline` (incl. `FutureOCRScore`),
`SearchObserver`, `SearchDocumentBuilder`, `SearchNormalizer`, `SearchTokenizer`,
`search.models.SearchResult`/`SearchSession`, and the entire unreferenced
`collections.core`/`collections.models` package (its only consumer was the dead
`SearchEngine`).

Retained deliberately:

- `search.core.SearchIndexManager` + `SearchRepository` + `SearchAncillaries`
  (`HistoryManager`, `SuggestionEngine`, `SearchCache`) and
  `search.models.SearchDocument` — the live suggestion/recent-search path in
  `SearchViewModel` depends on them. Note: nothing populates
  `SearchIndexManager` today (its feeder, `SearchObserver`, was never started),
  so suggestions come from history; the plumbing is kept because it is
  production-reachable UI behavior.
- `VectorStore.kt` — the pre-native JVM HNSW store. **Unused** (zero
  references) but out of the Task-4 audit list; flagged for deletion in the
  next cleanup sprint.

Known pre-existing quirk (documented, deliberately NOT changed in 3B — fixing it
would change retrieval behavior): the vector lane hydrates chunk ids via
`SearchRepository.getByIds`; image vectors are keyed `"${id}_chunk${i}"` while
the image's Room row id is bare `id`, so those hits only resolve for document
chunk rows. Image recall is carried by the BM25/substring/fuzzy/FTS lanes.
Revisit only with a retrieval-quality evaluation in hand (Phase 0B).
