# Amar Vault — Backup & Privacy Policy (Foundation)

**Status: FROZEN as of Sprint 3B (2026-07).** This document is the single source of
truth for what leaves the device, what is backed up, and what is rebuildable.
Any change to backup behavior must update this file AND the three enforcement
points listed at the bottom, together, in one commit.

## Principles

1. **Offline-first.** Every pipeline (OCR, embedding, BM25, RAG, LLM inference)
   runs on-device. The app functions fully without network.
2. **Privacy-first.** Vault content — OCR text, embeddings, metadata, chat
   history, thumbnails — never leaves the device. No analytics, no telemetry
   upload, no cloud sync.
3. **No accidental cloud leakage.** Android Auto Backup and device-to-device
   transfer are fully opted out, both by the manifest flag and by explicit
   exclude-everything rules (defense in depth against OEM tools that ignore
   `allowBackup`).

## Per-asset policy

| Asset | Location | Backed up? | Rationale |
|---|---|---|---|
| Room database `vault.db` (OCR text, metadata, entities, relationships, chat history, stash, ingestion ledger) | `databases/` | **Never** | Source of truth, but contains raw user content (screenshots' text, receipts, documents). Cloud copies would be a privacy leak. |
| Embedding vectors (native HNSW index files) | `files/` | **Never** | Rebuildable cache derived from Room; also device-scale binary data. |
| Vector id mappings `vector_mappings.json` | `files/` | **Never** | Companion of the HNSW index; meaningless without it; rebuildable via re-index. |
| Embedding manifest `embedding_manifest.json` | `files/` | **Never** | Describes the local vector files; recreated automatically on first init. |
| BM25 native index | `files/` | **Never** | Rebuildable cache — rehydrated from Room at startup. |
| AI models (embedding ONNX, LLM GGUF) | `files/` | **Never** | Large, re-downloadable/re-copyable artifacts; never user data, but never worth backing up. |
| Preferences (scan prefs, saved prefs, dev mode) | `shared_prefs/` | **Never** | Device-local settings; trivially re-set. |
| Temporary caches (share capture temp files, preview/thumbnail caches) | `cache/`, `files/` | **Never** | Ephemeral; `cache/` is always excluded by Android anyway; explicit excludes cover the rest. |

**Consequence (accepted deliberately):** uninstalling the app or losing the
device loses the vault. Restoring user data across devices is a future,
user-initiated, explicit export/import feature (see `ExportEngine`), never an
implicit cloud backup.

## Network use (exhaustive)

The `INTERNET` permission exists for exactly these flows; none of them transmits
vault content:

- **Model downloads** (`ModelDownloadWorker`) — pulls model files from their
  hosting URLs. Nothing is uploaded.
- **Saved-link metadata** (`ShareUrlExtractor` / `ThumbnailFetcher`) — fetches
  the page title/thumbnail of a URL the user explicitly shared into the vault.
- **Local peer sync** (`NearbySyncManager` / `P2PSyncEngine`) — device-to-device
  over local transports, user-initiated, encrypted; never touches a server.

Indexing, OCR, embedding, search, and Ask Vault perform **zero** network I/O.

## Enforcement points (must stay in lockstep)

1. `app/src/main/AndroidManifest.xml` — `android:allowBackup="false"` (primary opt-out).
2. `app/src/main/res/xml/backup_rules.xml` — explicit exclude-everything (API ≤ 30 paths).
3. `app/src/main/res/xml/data_extraction_rules.xml` — explicit exclude-everything for
   both `<cloud-backup>` and `<device-transfer>` (API 31+).
