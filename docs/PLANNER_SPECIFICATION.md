# Amar Vault Planner Specification

**Status:** Design gate — no implementation authorized by this document  
**Audience:** indexing, retrieval, storage, performance, and evaluation owners  
**Scope:** document/page indexing execution planning only  
**Version:** 0.1-draft

## 1. Purpose and contract

Amar Vault must not execute a fixed indexing recipe for every document. It must first decide the smallest set of physical operations that can produce a retrieval-complete result under the current device, workload, and quality constraints.

The Planner is a pure decision service at the architecture boundary:

```mermaid
flowchart LR
    A[Source + identity] --> B[Analyzer]
    B --> C[Planner inputs]
    C --> D[Planner]
    D --> E[Execution plan]
    E --> F[Workers]
    F --> G[Artifacts + manifest]
    G --> H[Quality telemetry]
    H --> I[Offline policy update]
    I --> D
```

The Planner does not perform OCR, rendering, embedding, parsing, persistence, or retrieval. It selects and orders operators owned by Workers. A plan may omit an operator only when the omission is justified by a deterministic certificate, a validated quality policy, or an explicit user/SLA choice.

### 1.1 Non-negotiable invariants

1. **No silent retrieval regression.** An incomplete semantic index must be represented as incomplete. The system must either complete the required work before returning a final semantic result or fall back to a retrieval path whose quality contract is known.
2. **No unbounded work.** Every plan has a deadline, peak-memory reservation, cancellation policy, and maximum escalation depth.
3. **No stale reuse.** An artifact is reusable only when its content identity, operator version, model version, language policy, and relevant configuration all match.
4. **No partial publication.** Workers may produce temporary artifacts, but a manifest becomes visible only after all required quality predicates pass.
5. **Replayability.** A decision can be reproduced from its logged inputs, policy version, capability snapshot, and seed.
6. **Planner overhead is bounded.** Deciding what to do must never cost more than the work it is intended to avoid.

### 1.2 Terms

- **Source:** immutable bytes or a stable content provider reference.
- **Document:** logical user object associated with one source identity.
- **Page:** independently addressable document unit. Images may be single-page documents.
- **Artifact:** immutable output of a versioned operator, addressed by an artifact key.
- **Certificate:** machine-readable evidence that an artifact satisfies a quality or reuse predicate.
- **Operator:** a physical action such as native text extraction, rasterisation, OCR, tokenisation, embedding, or vector insertion.
- **Plan:** a versioned directed acyclic graph of operators, predicates, budgets, and commit rules.
- **Quality completeness:** whether the artifacts needed for a requested retrieval mode are present and validated.

## 2. Planner inputs

The Planner receives a single immutable `PlannerInput` record. Inputs are divided into identity, evidence, resources, policy, and history. Workers must not add hidden inputs after planning; if a new fact changes feasibility, the plan is rejected and replanned.

### 2.1 Source and identity inputs

| Input | Required | Meaning |
|---|---:|---|
| `sourceId` | yes | Stable external identity, if available. |
| `contentFingerprint` | yes | Cryptographic fingerprint of canonical source bytes or a trusted stream digest. |
| `sourceType` | yes | PDF, raster image, text/HTML, office document, archive, or unknown. |
| `byteLength` | yes | Size used for I/O and memory estimates. |
| `lastModified` | optional | Freshness hint; never overrides content identity. |
| `documentPolicy` | yes | User/workspace settings: languages, privacy, offline-only, quality mode, and retention. |
| `parentIdentity` | optional | Container/archive or near-duplicate family identity. |

The source fingerprint is the root of reuse. Renames, folder moves, metadata edits, and repeated imports must not invalidate content artifacts.

### 2.2 Analyzer evidence

The Analyzer is a bounded, cheap preflight pass. It may inspect headers, metadata, PDF object/text-layer structure, and small luma thumbnails, but it may not perform full-page OCR or create production embeddings. Its output is evidence, not a decision.

| Feature group | Examples | Planner use |
|---|---|---|
| Container | page count, encrypted/linearised flag, rotation, embedded text presence | choose parser/render path and page-level work |
| Native text | bytes, word count, replacement-character ratio, script distribution, reading-order signals | accept `TEXT_ONLY`, route selected pages to OCR |
| Visual preflight | width/height, megapixels, blankness, text-density estimate, table/photo/handwriting likelihood | choose resolution, tiles, language, and OCR tier |
| Structure | repeated page fingerprints, template/family ID, page similarity | reuse page artifacts and family-specific plans |
| Change set | changed pages/blocks versus prior version | incremental invalidation |
| Security | malformed object flags, decompression limits, untrusted source markers | safe parser and fallback policy |

Analyzer evidence carries `featureVersion`, `measuredAt`, and `confidence`. Unknown values are explicit `UNKNOWN`; they are never converted to a favourable default.

### 2.3 Artifact and cache state

The Planner receives an index of reusable artifacts, not the artifacts' full payloads:

- artifact key and status (`READY`, `PENDING`, `FAILED`, `QUARANTINED`);
- input fingerprint, operator version, model/version, language policy, and schema version;
- quality certificate and certificate expiry/invalidation reason;
- byte size, estimated recomputation cost, last use, and reuse count;
- dependency graph and manifest generation;
- semantic coverage bitmap at document/page/chunk granularity.

An exact artifact hit is a zero-computation candidate. A near-duplicate hit is only a hint; it may not be substituted without a validated transformation certificate.

### 2.4 Runtime capability and resource state

The Capability Registry supplies a snapshot with a monotonic `capabilityEpoch`:

- CPU architecture, available cores, accelerator delegates, and supported operators;
- model residency and cold-load cost;
- available native/Java memory, current worker reservations, and storage free space;
- storage read/write throughput and write-amplification estimate;
- battery/charging state, thermal state, foreground state, and OS background restrictions;
- language packs, OCR engines, and model availability;
- current queue depth and active deadlines.

Capabilities are inputs to a decision, not a reason for a Worker to mutate the plan silently. If the capability epoch changes materially, the Worker returns `REPLAN_REQUIRED`.

### 2.5 Policy and SLA inputs

Policy is explicit and versioned. It includes:

- required retrieval modes: lexical, semantic, hybrid, or exact-only;
- freshness deadline and foreground/background priority;
- minimum OCR/retrieval quality contract;
- maximum acceptable peak memory and energy class;
- whether network/cloud execution is forbidden;
- whether pending semantic enrichment may be exposed to the user;
- maximum planner decision time and maximum escalation depth;
- rollout cohort and experiment assignment.

### 2.6 Historical telemetry features

The Planner may use aggregate, versioned performance history partitioned by source class and device class: p50/p95 latency, failure rates, cache hit rates, OCR disagreement, energy proxy, and observed retrieval impact. It must not depend on an unbounded stream of per-user history.

Historical data is advisory. A new model/operator version starts with conservative priors and cannot use optimistic estimates until validated.

## 3. Planner outputs

The Planner returns one `ExecutionPlan` or a typed refusal. It never returns an informal list of suggestions.

### 3.1 ExecutionPlan contents

Each plan contains:

| Field | Definition |
|---|---|
| `planId` | Unique decision ID; stable for logs and replay. |
| `plannerVersion` | Planner algorithm and schema version. |
| `inputDigest` | Digest of canonical PlannerInput. |
| `operatorDag` | Nodes, dependencies, input/output artifact keys, and worker class. |
| `acceptancePredicates` | Conditions required before publication. |
| `fallbackChain` | Ordered alternatives with trigger predicates and budgets. |
| `resourceReservation` | Peak bytes, model slots, CPU/accelerator tokens, and write budget. |
| `deadlines` | Decision, freshness, execution, and user-visible deadlines. |
| `commitProtocol` | Artifact transaction and manifest publication rule. |
| `qualityContract` | Required completeness and non-inferiority target. |
| `determinismToken` | Seed and normalized input/config digest for replay. |
| `telemetrySpec` | Events, counters, sampling, and redaction policy. |

### 3.2 Plan families

The initial Planner may emit these families:

1. **`REUSE`** — publish an existing valid manifest or only update logical metadata. No decode, OCR, embedding, or rewrite.
2. **`TEXT_ONLY`** — stream valid native text into lexical postings and a document synopsis. No bitmap allocation.
3. **`TEXT_PLUS_SELECTIVE_OCR`** — retain native text and OCR only pages/regions whose certificate fails.
4. **`IMAGE_CASCADE`** — low-cost visual preflight, one cheap OCR tier, then conditional escalation.
5. **`LEXICAL_NOW_SEMANTIC_DEFERRED`** — make lexical retrieval durable immediately and enqueue semantic work under a completeness contract.
6. **`SEMANTIC_MATERIALIZE`** — create document-level vectors, then chunk vectors according to policy and retrieval coverage.
7. **`SAFE_QUARANTINE`** — preserve identity and available metadata, but do not publish untrusted extracted content. Schedule a retry or request user action.

### 3.3 Typed refusals

The Planner may refuse to produce a normal plan when:

- no parser or OCR capability can satisfy the requested quality contract;
- resource limits make every feasible plan unsafe;
- the source is malformed or violates security limits;
- required model/operator versions are unavailable;
- artifact state is contradictory and cannot be safely resolved.

Refusal returns a reason code, safe partial plan if any, retry condition, and user-visible status. A refusal is not an implicit permission to run the legacy full pipeline.

## 4. Latency and resource budgets

Budgets are policy values, not guesses hidden in code. They are measured by source class and device class, then revised through benchmark governance. The values below are **initial design targets** for the first implementation and must be confirmed by the baseline corpus before release.

### 4.1 Planner and Analyzer budgets

| Budget | Target | Hard rule |
|---|---:|---|
| Planner decision p99 | 10 ms | If exceeded, use deterministic safe plan from cached features and log `PLANNER_DEADLINE`. |
| Analyzer p99, metadata/text probe | 50 ms | No full rasterisation or model invocation in Analyzer. |
| Analyzer bytes inspected | 1 MiB or 1% of source, whichever is smaller, unless format requires a bounded index read | Exceeding the cap produces `UNKNOWN`, not an unbounded read. |
| Planner peak memory | 4 MiB target | Planner must not hold document payloads or bitmaps. |

These targets intentionally make planning cheaper than one avoidable OCR or embedding invocation.

### 4.2 Execution budgets

Execution has four freshness classes:

| Class | Intended use | Initial target |
|---|---|---:|
| `FOREGROUND_READY` | user imports/opens/searches immediately | lexical availability p95 ≤ 1 s for valid native text; ≤ 3 s for a single ordinary image/page |
| `FOREGROUND_COMPLETE` | user explicitly requests full semantic readiness | complete under the caller deadline; otherwise return `PENDING` with progress |
| `BACKGROUND_FRESH` | recent imports while device is usable | finish within 15 minutes or before the next scheduler checkpoint |
| `IDLE_BACKFILL` | old corpus semantic/OCR enrichment | opportunistic; stop on thermal, battery, memory, or foreground pressure |

The targets are not quality waivers. A slow hard page may miss `FOREGROUND_READY` and move to `FOREGROUND_COMPLETE`/`PENDING`; the Planner must not lower the quality contract merely to meet a wall-clock target.

### 4.3 Resource limits

Every plan reserves:

- peak native bytes, including all bitmap/tile/model buffers;
- number of heavy OCR and embedding slots;
- maximum pixels rendered and maximum pages escalated;
- bytes written and transaction count;
- CPU/accelerator work estimate and energy class.

Admission is denied if a plan's p95 peak reservation would exceed available memory minus a safety margin. The default safety margin is 20% of available native memory; device policy may increase it. A Worker may use less than its reservation but may not exceed it.

## 5. Cost model

### 5.1 Feasibility before optimisation

The Planner first eliminates infeasible candidates. A candidate is infeasible if any hard constraint fails:

```text
qualityLowerBound < requiredQuality
or peakBytesP95 > memoryBudget
or deadlineP95 > executionDeadline
or writes > writeBudget
or required capability is unavailable
```

No weighted score may compensate for a failed hard constraint. A faster plan with unacceptable retrieval quality is not a candidate.

### 5.2 Objective function

For each feasible plan `p`, compute:

```text
cost(p) =
    wL * latencyP95(p)
  + wT * tailRisk(p)
  + wE * energyProxy(p)
  + wM * peakBytesP95(p)
  + wW * writeBytes(p)
  + wC * coldStartRisk(p)
  + wF * failureRisk(p)
```

Weights are policy inputs and are recorded with every decision. Costs are normalised by source class and device capability so a millisecond, a byte, and a joule proxy are not mixed without calibration.

### 5.3 Component definitions

- **Latency:** predicted wall time including queue wait, model cold start, I/O, and commit. Use p95 for admission and p50 for expected user experience.
- **Tail risk:** probability and severity of timeout, memory pressure, thermal throttling, or escalation to the maximum tier.
- **Energy proxy:** calibrated CPU time, rendered megapixels, accelerator time, model loads, and storage writes. Battery charge-counter deltas are preferred when supported; otherwise report this as a proxy, not a measurement.
- **Peak memory:** simultaneous native arena, raster/tile, engine, model, and marshaling allocations. Use peak, not sum of individual stages.
- **Write cost:** bytes written, fsync/WAL events, and index rebuild work.
- **Cold-start risk:** model load and cache-miss probability under current residency.
- **Failure risk:** observed operator failure probability conditioned on source class and capability epoch.

### 5.4 Quality risk

Quality risk is constrained separately. For a candidate, estimate:

```text
qualityLowerBound(p) = calibratedQualityEstimate(p)
                         - uncertaintyMargin(p)
```

The uncertainty margin grows for new source classes, new model versions, sparse telemetry, and out-of-distribution analyzer features. A plan with insufficient evidence is not treated as high quality by default; it is escalated, quarantined, or marked pending.

### 5.5 Cost-model governance

Cost estimates are versioned artifacts. Changes require:

1. offline replay on the canonical corpus;
2. comparison with a maximum-quality oracle plan;
3. retrieval and OCR non-inferiority analysis;
4. resource and tail analysis;
5. shadow rollout with rollback thresholds.

The online device may collect observations, but it may not self-authorise a new policy globally.

## 6. Fallback, failure, and rollback rules

### 6.1 Fallback chain

Fallback is an explicit ordered chain created at plan time. A fallback may run only when its trigger is recorded:

1. **Exact artifact reuse** → if certificate invalid, discard reuse and replan.
2. **Native text** → if parser integrity or text quality fails, render only affected pages.
3. **Cheap OCR tier** → if coverage/confidence/sanity predicate fails, escalate selected regions/pages.
4. **Specialised OCR tier** → if it fails or times out, use the next approved engine or quarantine.
5. **Semantic materialisation** → if embedding fails, lexical artifacts remain valid and semantic coverage is `INCOMPLETE`.

No fallback may silently expand from one failed tile to a whole corpus. Escalation has a maximum page, pixel, time, and memory budget.

### 6.2 Trigger classes

Triggers are typed:

- `QUALITY_PREDICATE_FAILED`: result exists but is not trustworthy;
- `TIMEOUT`: operator exceeded its plan deadline;
- `RESOURCE_DENIED`: admission or runtime memory reservation failed;
- `CAPABILITY_LOST`: model/delegate/engine unavailable;
- `INPUT_INVALID`: malformed, encrypted, or unsafe source;
- `ARTIFACT_CONFLICT`: version or dependency mismatch;
- `CANCELLED`: user/OS/scheduler cancellation;
- `WORKER_ERROR`: unexpected failure.

Each trigger records the operator, observed evidence, and whether the retry is safe.

### 6.3 Transaction and rollback

Workers write into a temporary generation keyed by `planId`. The manifest publication sequence is:

```text
prepare artifacts -> validate certificates -> validate dependencies
-> write journal record -> atomically publish manifest pointer
```

If any required step fails, the prior manifest remains visible. Temporary artifacts are garbage-collectable after the retention window. A successful partial lexical plan may be published only if the plan explicitly declared lexical readiness independent of semantic readiness.

Rollback occurs when:

- a post-commit validator detects an invalid certificate;
- a model/operator version is recalled;
- retrieval non-inferiority fails in a rollout cohort;
- a storage consistency check fails;
- memory/thermal safety violations exceed the release threshold.

Rollback means switching the manifest pointer to the last known-good generation and quarantining the bad operator/version combination. It does not delete source bytes or destroy recoverable artifacts.

### 6.4 Retry policy

Retries are bounded and typed. Deterministic input failures are not retried blindly. Transient capability/resource failures use exponential backoff with jitter in background classes, but foreground requests receive one bounded replan. Repeating the same operator with the same inputs after two identical failures moves the artifact to `QUARANTINED`.

## 7. Measuring Planner quality

Planner quality is not the same as operator speed. It is the quality of the decisions.

### 7.1 Primary quality gates

Against a fixed golden corpus and query set, compare each candidate policy to a maximum-quality oracle:

- OCR character and word accuracy by source class and language;
- lexical retrieval precision@k, recall@k, MRR, and nDCG;
- semantic/hybrid retrieval metrics with scorable queries;
- answer/evidence coverage where the product uses downstream RAG;
- document/page semantic completeness;
- exact duplicate and incremental-change correctness.

The acceptance rule is non-inferiority, not an average improvement that hides a regression. Thresholds are set per metric and source class before implementation. The current benchmark's zero scored retrieval queries is a release blocker for claims about preserved retrieval quality.

### 7.2 Efficiency gates

Measure:

- end-to-end p50/p95/p99 latency by plan family;
- planner decision latency and analyzer overhead;
- OCR calls/pages/pixels per imported page;
- embeddings generated per document and cache-hit rate;
- peak native/Java memory and bitmap allocation count;
- CPU time, accelerator time, energy measurement/proxy, and thermal transitions;
- bytes written, fsync count, WAL amplification, and index rebuild work;
- fallback, rollback, quarantine, and cancellation rates.

### 7.3 Decision-quality metrics

The Planner itself is evaluated on:

- **avoidable work rate:** operators executed that the oracle later proves unnecessary;
- **unsafe skip rate:** skips that change a golden retrieval result or violate a quality certificate;
- **early-accept precision/recall:** whether the cascade stops at the right tier;
- **cost calibration error:** predicted versus measured p50/p95 latency, memory, and energy proxy;
- **plan stability:** identical inputs producing the same plan in deterministic mode;
- **fallback regret:** cost paid after an initial plan failed versus cost of a safer plan;
- **coverage freshness:** time until required lexical and semantic artifacts become complete.

### 7.4 Evaluation modes

- **Oracle replay:** execute maximum-quality operators offline; establishes reference outputs.
- **Planner replay:** feed recorded PlannerInputs to a candidate policy without running Workers.
- **Shadow execution:** run candidate decisions and expensive experts on a sampled subset; compare outputs without publishing candidate artifacts.
- **Canary:** publish to a small cohort with automatic rollback thresholds.
- **Soak:** long-run memory, thermal, cancellation, and incremental-reimport testing.

## 8. Determinism and reproducibility

### 8.1 Deterministic mode

Planner behavior is deterministic when all of the following are fixed:

- canonical source and `PlannerInput` digest;
- Planner version, policy version, cost-model version, and operator/model registry;
- capability snapshot and queue policy;
- artifact manifest generation and historical feature snapshot;
- tie-break rules and random seed;
- normalized locale/timezone and stable map/set ordering.

In deterministic mode, candidate plans are sorted by `(hard-feasible, totalCost, qualityMargin, planFamilyId, operatorDagHash)`. Floating-point comparisons use a specified tolerance and integer-scaled cost units where possible.

### 8.2 Adaptive mode

Production may use adaptive telemetry, thermal state, cache residency, or queue pressure. That behavior is intentionally not bit-for-bit deterministic across runs. It must still be reproducible by logging the complete decision snapshot. Adaptive mode may change the plan, but may not change the quality contract or commit invariants.

### 8.3 Sources of non-determinism

The following are isolated and logged: OS scheduling, model delegate/kernel variation, thermal transitions, concurrent cache changes, approximate ANN construction, and randomised policy exploration. Exploration is disabled in foreground and privacy-sensitive modes.

## 9. Planner decision logging

Every decision emits one structured `PlannerDecision` record. Logging is designed for replay and debugging, not for storing user content.

### 9.1 Required record

```json
{
  "schema": 1,
  "traceId": "opaque",
  "decisionId": "opaque",
  "sourceFingerprint": "hash",
  "documentClass": "pdf_scanned",
  "plannerVersion": "planner-0.1",
  "policyVersion": "policy-2026-07",
  "costModelVersion": "cost-3",
  "inputDigest": "hash",
  "featureDigest": "hash",
  "capabilityEpoch": 42,
  "resourceSnapshot": {
    "nativeFreeBytes": 0,
    "thermal": "NORMAL",
    "charging": false,
    "foreground": true,
    "modelResidency": ["ocr_light"]
  },
  "candidates": [
    {
      "planFamily": "TEXT_PLUS_SELECTIVE_OCR",
      "feasible": true,
      "predicted": {
        "latencyP50Ms": 0,
        "latencyP95Ms": 0,
        "peakBytes": 0,
        "writesBytes": 0,
        "qualityLowerBound": 0.0
      },
      "rejectedBecause": null
    }
  ],
  "selectedPlan": "plan-hash",
  "fallbackChain": ["OCR_ESCALATE", "SAFE_QUARANTINE"],
  "determinism": {"mode": "DETERMINISTIC", "seed": 0},
  "decisionLatencyMs": 0,
  "redactions": ["document-content", "recognized-text"]
}
```

Zero-valued fields above are placeholders for the schema example; real records contain measured/predicted values. The log must not contain source bytes, raw OCR text, embeddings, filenames, or personal metadata by default. Hashes may be keyed or privacy-scoped.

### 9.2 Decision events

Emit events for `PLANNED`, `ADMITTED`, `STARTED`, `OPERATOR_COMPLETED`, `QUALITY_REJECTED`, `FALLBACK_SELECTED`, `REPLAN_REQUIRED`, `COMMITTED`, `ROLLBACK`, `QUARANTINED`, and `CANCELLED`. Every event includes the same `traceId`, operator/artifact key, monotonic timestamp, worker, and capability epoch.

### 9.3 Sampling and retention

Keep 100% of decisions that fallback, rollback, quarantine, violate a budget, or fail quality validation. Sample successful deterministic decisions by source class and device cohort. Retain aggregate counters longer than payload-free traces. A replay bundle contains only the redacted PlannerInput, registry versions, policy, feature digest, and artifact metadata necessary to reproduce a decision.

### 9.4 Debugging workflow

An engineer must be able to answer, from one trace:

1. What did the Planner know?
2. What candidates were considered and why were they rejected?
3. What cost/quality estimates selected the plan?
4. Which capability or resource state was active?
5. Which operator actually ran, with what measured cost?
6. Why did fallback, rollback, or quarantine occur?
7. Can the decision be replayed under the same versions?

If a decision cannot answer these questions without reproducing the user's source, the log is insufficient.

## 10. Reference decision examples

### 10.1 Identical re-import

Analyzer produces the same `contentFingerprint`; manifest contains valid artifacts for all required modes. Planner emits `REUSE`, with only a metadata/manifest operation. No parser, raster, OCR, chunk, embedding, or vector work is admitted.

### 10. Text PDF with one damaged page

Native text passes on 99 pages and fails the certificate on page 37. Planner emits `TEXT_PLUS_SELECTIVE_OCR`: stream 99 pages directly, render/OCR page 37 only, then commit one generation. The failed page cannot cause a document-wide OCR plan.

### 10. New screenshot

Analyzer detects a small text-heavy image. Planner emits `IMAGE_CASCADE`: one light OCR tier, acceptance predicate, then a bounded grayscale or specialised escalation only if coverage fails. Upscale is not a default operator.

### 10. Semantic request before backfill

Lexical artifacts are complete but semantic coverage is partial. Planner emits a query-scoped semantic materialisation plan or returns `PENDING` according to the caller's deadline. It does not return an apparently complete semantic ranking from a partial index.

## 11. Design gates before implementation

Implementation must not start until the owners approve:

1. the canonical corpus, query set, OCR truth set, and maximum-quality oracle;
2. initial quality non-inferiority margins and source-class minimum sample sizes;
3. provisional latency, memory, energy, and write budgets;
4. artifact-key and manifest schema;
5. deterministic replay format and redaction policy;
6. fallback/rollback thresholds and quarantine ownership;
7. capability/resource reservation semantics;
8. benchmark corrections for the current report's inconsistent aggregates.

The Planner is successful only when it can prove both halves of the objective: it removed work, and the work it removed was not required for the retrieval contract.

