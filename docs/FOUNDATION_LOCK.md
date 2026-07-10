# AMAR VAULT — FOUNDATION_LOCK

**This is NOT documentation. This is the permanent architectural constitution of Amar Vault.**

These decisions are intentionally frozen. Future contributors must assume they are correct. Changing any of them requires an Architecture Decision Record (ADR) and strong justification. This document exists to prevent architecture drift.

## 1. Project Philosophy

Amar Vault is designed with the following core philosophies:

*   **Offline-first:** The application functions fully without an internet connection. Data is processed and stored locally.
*   **Privacy-first:** User data never leaves the device unless explicitly authorized. All indexing and retrieval happen on-device.
*   **Knowledge Engine:** It is not just a file browser; it is an engine that understands the context and content of the user's data.
*   **Search-first:** The primary interaction model is search and retrieval, not hierarchical folder navigation.
*   **AI Augmentation (not AI-first):** AI is used to enhance retrieval, understanding, and summarization. It is an augmentative layer on top of a robust deterministic foundation.
*   **Local Ownership of Data:** The user retains complete ownership and control over their data, embeddings, and indices.
*   **Minimal Duplication:** Data, indices, and processing pipelines are designed to avoid redundancy to save space and compute resources.
*   **Reliability Before Features:** A stable, deterministic foundation is prioritized over experimental features.

## 2. Core Architectural Principles

*   **One Indexing Pipeline:** There is a single, unified pipeline for ingesting data. *Why:* Prevents fragmentation, ensures all data types are treated equally, and simplifies the mental model of how data enters the system.
*   **One Retrieval Pipeline:** There is a single entry point for querying information. *Why:* Ensures consistent ranking, filtering, and access control across all search surfaces.
*   **One Room Writer:** A single source of truth for writing to the Room database. *Why:* Prevents race conditions, database locks, and ensures transactions are handled predictably.
*   **Commit-before-index:** Data metadata is committed to the database before heavy processing (like OCR or embeddings) begins. *Why:* Ensures data is not lost if the app crashes during intensive operations, allowing for resumable background processing.
*   **Post-commit index updates:** Vector and full-text indices are updated after the database transaction is secure. *Why:* Maintains consistency between the relational database and external index structures.
*   **Thin Orchestrators:** Orchestrator classes coordinate work but do not contain heavy business logic. *Why:* Makes testing easier and separates concerns between flow control and data processing.
*   **Explicit Ownership:** Every piece of data and every pipeline stage has a single, clearly defined owner. *Why:* Prevents hidden dependencies and makes debugging easier.
*   **Dependency Inversion only where valuable:** Interfaces are used where multiple implementations exist or for testing, not blindly applied to every class. *Why:* Avoids over-engineering and keeps the codebase navigable.
*   **No Duplicate Pipelines:** We do not build parallel systems for different data types if they can be unified. *Why:* Reduces maintenance burden and ensures consistent behavior.
*   **No Duplicate OCR:** Text extraction happens once per document. *Why:* OCR is expensive; doing it multiple times wastes battery and time.
*   **No Duplicate Retrieval:** We don't have separate search systems for different parts of the app. *Why:* Consistency in search results and ranking.
*   **No Duplicate AI Pipeline:** A single pipeline handles LLM interactions and embedding generation. *Why:* Resource management and consistent prompt handling.
*   **Offline-first Execution:** All core features must work without a network. *Why:* Core to the privacy and reliability promise.
*   **Deterministic Behavior where possible:** Non-AI components must behave predictably. *Why:* Ensures the foundation is reliable before adding probabilistic AI features.

## 3. Components That Are Frozen

The following components are fundamental to the architecture and must not be redesigned casually:

*   **DiscoveryEngine**
    *   *Responsibility:* Finding and ingesting new files from the filesystem.
    *   *Owner:* Indexing Pipeline.
    *   *Why stable:* Changing how files are discovered risks data loss or incomplete ingestion.
*   **ImageContentExtractor**
    *   *Responsibility:* Extracting text and features from images (OCR, classification).
    *   *Owner:* Content Processing Stage.
    *   *Why stable:* Core to making images searchable.
*   **DocumentContentExtractor**
    *   *Responsibility:* Parsing text from PDFs, Word docs, etc.
    *   *Owner:* Content Processing Stage.
    *   *Why stable:* Essential for document searchability.
*   **MetadataStage**
    *   *Responsibility:* Extracting and standardizing metadata (dates, sizes, types).
    *   *Owner:* Indexing Pipeline.
    *   *Why stable:* Metadata is the foundation of filtering and sorting.
*   **DuplicateDetector**
    *   *Responsibility:* Identifying duplicate files to prevent redundant processing.
    *   *Owner:* Indexing Pipeline.
    *   *Why stable:* Critical for performance and storage optimization.
*   **Chunker**
    *   *Responsibility:* Splitting large texts into smaller, semantically meaningful chunks for embeddings.
    *   *Owner:* Vectorization Stage.
    *   *Why stable:* Changing chunking strategy invalidates existing vector indices.
*   **IndexPersister**
    *   *Responsibility:* The final step that writes metadata to the Room database.
    *   *Owner:* Database Layer.
    *   *Why stable:* The sole entry point for persistent storage.
*   **IndexUpdater**
    *   *Responsibility:* Updating external indices (Vector, BM25) after Room insertion.
    *   *Owner:* Index Layer.
    *   *Why stable:* Ensures data consistency across all search structures.
*   **RetrievalService**
    *   *Responsibility:* The main orchestrator for all search queries.
    *   *Owner:* Search Layer.
    *   *Why stable:* The single unified pipeline for all data retrieval.
*   **HybridSearchService**
    *   *Responsibility:* Combining results from Vector search and BM25 search.
    *   *Owner:* Search Layer.
    *   *Why stable:* The core algorithm for ranking search results.
*   **SearchRepository**
    *   *Responsibility:* Interacting with the Room database for search queries.
    *   *Owner:* Database Layer.
    *   *Why stable:* Centralizes all search-related SQL queries.
*   **NativeBm25Index**
    *   *Responsibility:* Managing the full-text search index.
    *   *Owner:* Index Layer.
    *   *Why stable:* Core to keyword-based retrieval.
*   **LanguageModel**
    *   *Responsibility:* Interface and implementation for interacting with the local LLM.
    *   *Owner:* AI Layer.
    *   *Why stable:* Centralizes all inference logic and resource management.
*   **RagService**
    *   *Responsibility:* Orchestrating Retrieval-Augmented Generation workflows.
    *   *Owner:* AI Layer.
    *   *Why stable:* The entry point for complex AI tasks relying on user data.
*   **VectorSearchManager**
    *   *Responsibility:* Managing the vector database/index.
    *   *Owner:* Index Layer.
    *   *Why stable:* Core to semantic search capabilities.
*   **DeviceCapability**
    *   *Responsibility:* Assessing the device's hardware (RAM, NPU, GPU) to adapt performance.
    *   *Owner:* Core System.
    *   *Why stable:* Ensures the app runs efficiently on various devices without crashing.
*   **ModelRecommendationEngine**
    *   *Responsibility:* Deciding which AI model to load based on device capabilities and task.
    *   *Owner:* AI Layer.
    *   *Why stable:* Prevents OOM errors and optimizes inference speed.

## 4. Data Ownership

To prevent ambiguity, data ownership is strictly defined:

*   **OCR text:** Owned by the `Room` database (and BM25 index). `ImageContentExtractor` generates it, but the database owns it.
*   **Metadata:** Owned by the `Room` database.
*   **Embeddings:** Owned by the Vector Index (managed by `VectorSearchManager`).
*   **BM25:** Owned by the `NativeBm25Index` (or Room FTS tables).
*   **Chunks:** Owned by the `Room` database (as relational data to embeddings).
*   **Parent relationships:** Owned by the `Room` database.
*   **Vector mappings:** Owned by the `Room` database (mapping chunk IDs to vector IDs).
*   **AI models:** Owned by `LanguageModel` implementations.
*   **Runtime metrics:** Owned by a centralized analytics/telemetry component.

## 5. Lifecycle Rules

*   **Index lifecycle:** Created during app initialization, updated incrementally, rebuilt entirely only on schema changes or user request.
*   **Retrieval lifecycle:** Instantiated per query, scoped to the ViewModel or specific UseCase.
*   **Embedding lifecycle:** Generated once per chunk, stored permanently, deleted when the source document is deleted.
*   **OCR lifecycle:** Performed once per image, text stored permanently, never repeated unless forced.
*   **Model lifecycle:** Loaded on demand, kept in memory as long as memory pressure allows, unloaded aggressively on backgrounding or memory warnings.
*   **Document lifecycle:** Discovered -> Indexed (Metadata) -> Processed (OCR/Embeddings) -> Searchable -> Deleted (cascade).
*   **Search lifecycle:** Query input -> Rewrite/Expansion (optional) -> Hybrid Retrieval -> Reranking -> Presentation.
*   **Deletion lifecycle:** Hard delete cascades from Room -> Vector Index -> BM25 Index -> Filesystem (if app owns the file).
*   **Recovery lifecycle:** Handled by a dedicated backup/restore system; indices are typically rebuilt from the database on restore.
*   **Migration lifecycle:** Room handles relational migrations; Vector/BM25 indices may require full rebuilds on major version changes.

## 6. Things That Must Never Happen

*   **Never introduce a second retrieval pipeline:** Bypassing `RetrievalService` leads to inconsistent search results, missing permissions checks, and fragmented ranking.
*   **Never introduce a second indexing pipeline:** Leads to race conditions, missed files, and duplicate data.
*   **Never introduce duplicate OCR:** Wastes massive amounts of battery and compute.
*   **Never introduce duplicate embeddings:** Wastes storage space and processing time.
*   **Never introduce duplicate Room writers:** Causes database locking issues and potential data corruption.
*   **Never introduce duplicate BM25 ownership:** Fragmented search space.
*   **Never introduce multiple ranking engines:** Confuses the user with inconsistent search results across different screens.
*   **Never introduce multiple search engines:** Maintain the single Hybrid approach.
*   **Never introduce business logic inside Compose:** UI must be purely presentational; logic belongs in ViewModels or Domain layers. Hard to test, hard to maintain.
*   **Never introduce hidden singleton ownership:** Use Dependency Injection. Hidden singletons make testing impossible and hide lifecycle leaks.
*   **Never introduce string-based ownership inference:** Use strong types and explicit IDs. Strings are brittle and prone to typos.
*   **Never introduce direct JNI calls from UI:** All native calls must be wrapped in appropriate repository/service classes and executed on background threads to prevent ANRs.
*   **Never bypass RetrievalService:** All queries must go through the unified pipeline.
*   **Never bypass IndexPersister:** All writes must go through the unified pipeline.

## 7. Future Extension Rules

Future features must integrate through existing pipelines. Never create parallel systems.

*   **Audio / Video:** Must hook into the existing DiscoveryEngine and use a new Extractor in the Content Processing Stage.
*   **WhatsApp / Email / Calendar:** Must act as new data sources feeding into the *single* Indexing Pipeline.
*   **Filesystem / Cloud sync:** Sync mechanisms must feed the existing Room database as the source of truth.
*   **Timeline / Entity pages:** Must query data exclusively through the `RetrievalService`.
*   **Document chat / Summaries:** Must utilize the existing `RagService` and `LanguageModel` infrastructure.
*   **Developer tools:** Must observe existing state, not mutate it via backdoors.

## 8. Performance Rules

*   **Correctness before speed:** A fast wrong answer is worse than a slow right answer.
*   **Benchmark before optimization:** Never optimize based on intuition.
*   **No optimization without measurement:** Prove that the optimization actually improved things.
*   **Preserve determinism:** Do not introduce race conditions in the name of parallelism.
*   **Optimize allocations before algorithms:** Garbage collection pauses are the enemy of smooth UI.
*   **Avoid premature caching:** Cache only when profiling shows it's necessary; caching introduces state complexity.
*   **Avoid duplicated work:** Compute once, store, and reuse.

## 9. Privacy Rules

*   **No cloud dependency:** The app must function fully offline.
*   **Local OCR:** Image processing happens on-device.
*   **Local embeddings:** Text vectorization happens on-device.
*   **Local LLM:** Inference happens on-device.
*   **No silent uploads:** Data never leaves the device without explicit, undeniable user intent.
*   **Backup policy:** Backups must be encrypted and under user control.
*   **User ownership:** The user can delete all their data and indices at any time.

## 10. Architecture Decision Rules

Future contributors may change the architecture **only if**:

1.  Benchmark proves the need for change.
2.  The architecture becomes simpler as a result.
3.  Data ownership improves or becomes clearer.
4.  Regression risk decreases.
5.  Documentation (like this file) is updated.

**Otherwise: Do not change it.**

## 11. Things That Future AI Agents Must Read First

Every future AI coding agent must confirm it has read the following before proposing architectural changes:

*   [x] `FOUNDATION_LOCK.md` (This file)
*   [x] `ARCHITECTURE.md` (If exists)
*   [x] `BACKUP_AND_PRIVACY.md` (If exists)

## 12. Current Architecture Status

*   **Architecture Phase:** COMPLETE
*   **Foundation:** LOCKED
*   **Architecture maturity:** Approximately 96–97%
*   **Remaining work:**
    *   Benchmarking
    *   Performance optimization
    *   Retrieval quality improvements
    *   OCR improvements
    *   UX
    *   Product features
    *   **NOT architecture redesign.**

## 13. Final Declaration

Amar Vault's foundation is considered complete. Future development should improve the product, not reinvent its architecture. The default assumption is that existing architectural decisions are correct unless compelling evidence from production or benchmarking demonstrates otherwise.
