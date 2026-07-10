# Amar Vault — Evaluation & Benchmark Framework

**Introduced in Sprint 3C (2026-07).** This framework is the permanent source of
truth for evaluating Amar Vault. Every future change — retrieval tuning, OCR work,
model swaps, performance passes — must be judged by a **before/after benchmark run**,
never by feel.

Cardinal rule, enforced by construction: **every number in a report is measured.**
A metric that cannot be measured on the current device/corpus appears with an empty
value and a note explaining why. The framework never estimates, extrapolates
silently, or invents accuracy percentages. (The pre-3C `BenchmarkRunner` /
`UltimateBenchmarkRunner` that returned hardcoded numbers were deleted for exactly
this reason.)

## Architecture

```
                    com.amar.vault.benchmark
 ┌─────────────────────────────────────────────────────────────────┐
 │  GoldenDatasetStore ── assets/benchmark/GoldenDataset/*.json    │
 │        │               filesDir/benchmark/GoldenDataset/*.json  │
 │        ▼               (adb-pushable, no rebuild)               │
 │  BenchmarkRunner (orchestrator; suites: FULL · OCR · RETRIEVAL  │
 │        │          · EMBEDDING · PERFORMANCE)                    │
 │        ├── RetrievalEvaluator ──► RetrievalService  (real)      │
 │        ├── OcrBenchmark ────────► ImageContentExtractor (real)  │
 │        ├── EmbeddingBenchmark ──► AppEmbeddingEngine (real)     │
 │        ├── IndexingBenchmark ───► IndexMetrics + micro-probes   │
 │        ├── PerformanceBenchmark ► retrieve() + public seams     │
 │        ├── MemoryBenchmark ─────► platform counters + sampler   │
 │        ├── StorageBenchmark ────► on-disk bytes + live SQL      │
 │        └── BatteryBenchmark ────► charge-counter sessions       │
 │        ▼                                                        │
 │  RegressionRunner (current vs pinned baseline)                  │
 │        ▼                                                        │
 │  ReportStore ── filesDir/benchmark-results/                     │
 │                   latest.json · latest.csv · latest.md          │
 │                   history/<runId>.json · baseline.json          │
 └─────────────────────────────────────────────────────────────────┘
        ▲
 Dev Tools → Benchmarks (BenchmarkDashboardScreen, Developer-Mode-gated)
```

The framework only *observes* production components through the same Hilt singletons
production uses (`BenchmarkRunner.BenchmarkEntryPoint`). It never re-implements
ranking/OCR/embedding logic, and it never modifies pipelines.

## Supported metrics

| Section | Metrics |
|---|---|
| `retrieval.quality` | precision@1/5/10, recall@5/10, MRR, NDCG@10 (+ per-query rows, skipped-case accounting) |
| `ocr` | character accuracy, word accuracy, latency avg/p50/p95/p99/max, per-content-type accuracy; confidence reported as unmeasured (not exposed by the 5-pass ensemble) |
| `embedding` | generation avg/median/p95, cold-vs-warm query-cache latency, vectors indexed, mappings bytes, dimensions, model id/version/schema, migration readiness |
| `indexing` | observed per-doc OCR/metadata/embedding/total (from `IndexMetrics`), batch volume, probes for chunking (both chunkers), Room write, BM25 add, PDF extraction; OCR outliers, bitmap megapixels, latency by size/trust/type, Tesseract timeout count, and OCR optimization hit rate |
| `retrieval.performance` | end-to-end avg/median/p95/p99, query parsing, BM25, embed, ANN, hydration; fusion/ranking explicitly unmeasured (no public seam) |
| `memory` | sampled peak/avg RAM, Java heap, native heap (JNI), PSS total/dalvik/native, GC count/time, embedding-cache capacity bound |
| `storage` | DB bytes, OCR text bytes, metadata rows, mappings/manifest bytes, native index bytes, model bytes, measured bytes/doc, labeled linear projections for 1k/10k/100k |
| `battery` | per-session charge delta (µAh) + duration; unmeasurable while charging |
| `regression` | regressed/improved/compared counts + per-metric verdict rows |

## Report format

`filesDir/benchmark-results/`:

- **latest.json** — full structured report; the machine input for regression runs.
- **latest.csv** — flat rows: `section,metric,value,unit,higherIsBetter,note`.
- **latest.md** — human-readable summary tables.
- **history/<runId>.json** — every run, for historical comparison.
- **baseline.json** — the pinned "before" snapshot.

Example (`latest.json`, elided):

```json
{
  "runId": "20260705-183000-full",
  "suite": "FULL",
  "deviceModel": "…", "sdkInt": 34,
  "corpusDocumentCount": 128,
  "sections": [
    { "id": "retrieval.quality", "title": "…",
      "metrics": [
        {"name": "precision@5", "value": null, "unit": "ratio",
         "higherIsBetter": true, "note": "no scorable cases (dataset empty or documents not indexed)"}
      ],
      "rows": [ {"caseId": "…", "status": "SKIPPED", "reason": "…"} ] }
  ]
}
```

(Values shown as `null` + note until real golden cases exist — the framework refuses
to print a number it did not measure.)

## Regression workflow (Module 11)

1. On a device with an indexed corpus, run **Full** from Dev Tools → Benchmarks.
2. Tap **Pin latest as baseline** (writes `baseline.json`).
3. Make your code change; rebuild; run **Full** again.
4. The new report automatically carries a `regression` section: every shared metric
   compared direction-aware (`higherIsBetter`), verdicts REGRESSED / IMPROVED /
   UNCHANGED (±5% default threshold) / NOT_COMPARABLE (became unmeasurable — always
   investigate).

## Adding benchmark cases

1. Write a dataset JSON (schema in `assets/benchmark/GoldenDataset/starter.json` —
   templates included). One file = one dataset; multiple datasets are supported and merged.
2. Ship it either:
   - in the repo: `app/src/main/assets/benchmark/GoldenDataset/<name>.json` (rebuild), or
   - on-device: `adb push cases.json /data/data/com.amar.vault/files/benchmark/GoldenDataset/`
     (no rebuild; overrides an asset dataset with the same name). Put referenced media
     under `…/GoldenDataset/media/`.
3. Rules for honest cases:
   - `documentId`/`expectedResults` must be ids of documents **actually indexed** on
     the test device (use Dev Tools → Index Files to push+index them first). Cases
     with missing documents are counted as skipped, never scored.
   - `groundTruthText` must be typed by a human from the image — never from OCR output.
   - `expectedRank` (optional) enables graded NDCG; order most-relevant-first.
4. Content types: `PDF, SCREENSHOT, IMAGE, RECEIPT, NOTE, SAVED_LINK, MIXED_OCR`.

## Extensibility

- **New metric in an existing module**: add a `MetricValue` — regression comparison
  picks it up automatically by (sectionId, name); give it the right `higherIsBetter`.
- **New module**: return a `BenchmarkSection`, register it in
  `BenchmarkRunner.run()` under the right suites. Follow the honesty rule: measure or
  report null+note.
- **New suite**: extend `BenchmarkSuite` and the suite gating in the runner.
- **Stage-level timings the pipeline doesn't expose** (fusion, ranking, Room txn
  inside indexing): the right future step is adding `IndexMetrics`-style self-timing
  to production code (a separate, deliberate change) — the benchmark will surface any
  `IndexMetrics` timer automatically once it exists. Do NOT approximate them by
  subtraction.

## Known measurement boundaries (by design, stated in reports)

- OCR confidence: not exposed by the production ensemble.
- Fusion/ranking latency: internal to `HybridSearchService`; measured only inside
  end-to-end totals.
- Bitmap allocation attribution: contained in Java-heap numbers.
- Battery: device-level charge deltas, valid only unplugged on an idle device.
- Storage at 10k/100k docs: linear projections from measured bytes/doc, labeled as such.
- `IndexingBenchmark` probes: one transient BM25 nonsense-token document per run
  (vanishes at restart; cannot match real queries) and a Room probe row that is
  inserted and deleted within the same call.
