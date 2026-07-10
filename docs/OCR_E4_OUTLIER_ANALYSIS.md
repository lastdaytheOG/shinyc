# Sprint E4 OCR Outlier Analysis

This sprint keeps the existing OCR engines and strategy set intact. All changes are either
instrumentation or guarded optimizations that are visible in the benchmark reports.

## Root Cause

The E3 profiler showed OCR as the dominant indexing stage, with Tesseract carrying the
long tail: p50 around 301 ms, p95 around 2.5 s, and max around 95 s. The measured profile
and code audit point to three compounding causes:

- Tesseract was run at full bitmap resolution using `eng+hin`, while ML Kit internally
  downscales large inputs. Multi-megapixel photos therefore make Tesseract scan far more
  pixels than the other strategies.
- Tesseract is serialized behind a process-wide mutex. One pathological page blocks
  strategy 5 for other concurrent documents, which can amplify a single slow page into
  a document-level outlier.
- Strategy 4 always performed a 2x bitmap upscale. On already-large sources this creates
  large transient bitmaps without adding source detail, increasing memory pressure and
  latency.

Outliers are classified only from measured fields:

- `timeout_capped`: OCR time reached the Tesseract watchdog deadline.
- `extremely_large_bitmap`: measured input size is at or above the Tesseract megapixel cap.
- `dense_text`: recognized character volume is high enough to explain slow line analysis.
- `low_contrast_or_noise`: page trust score is near zero.
- `other`: no measured evidence explains the outlier.

Language issues, memory pressure, and repeated retries are not guessed. They remain future
categories until per-page evidence is recorded for them.

## Optimizations

- Tesseract input downscale: Tesseract-only input is capped at 8 MP. ML Kit strategies still
  receive the original bitmap.
- Tesseract watchdog: `TessBaseAPI.stop()` interrupts recognition after 20 s, roughly 8x the
  measured healthy p95.
- Upscale bypass: sources at or above 4 MP reuse the shared grayscale bitmap for strategy 4
  instead of creating a 2x transient bitmap.
- Queue timing: time waiting for the Tesseract mutex is now measured separately from engine
  time.
- Engine timing: per OCR record now separates preprocessing, ML Kit engine time, Tesseract
  engine time, merge time, and total OCR time.

## Benchmark Evidence

The following report fields were added or expanded:

- `ocr.tesseract.timeout.count`
- `ocr.outliers.gt3s`, `ocr.outliers.gt5s`, `ocr.outliers.gt10s`
- `ocr.bitmap.megapixels.p50`, `p95`, `p99`, `max`
- `ocr.latency.by.size.*`
- `ocr.latency.by.trust*`
- `ocr.latency.by.type.*`
- `ocr.optimization.tessInputDownscaled.count`
- `ocr.optimization.upscaleBypassed.count`
- `ocr.optimization.hitRate`
- `ocr.total.latency.p50`, `p95`, `p99`, `max`
- `ocr.latency.p50`, `p95`, `p99`, `max`

Each `ocrPage` row in the OCR contribution reports includes dimensions, megapixels,
grayscale time, upscale time, invert time, ML Kit time, Tesseract time, merge time, and
total OCR time.

## Before/After And Accuracy

No before/after numbers are written into this document because benchmark fabrication is
explicitly disallowed. The supported workflow is:

1. Run the benchmark and pin `baseline.json`.
2. Run the same benchmark after the E4 changes.
3. Read the regression section for before/current/delta rows and the added latency
   improvement metrics.

OCR accuracy is verified by the existing golden OCR metrics (`charAccuracy.avg` and
`wordAccuracy.avg`) and retrieval quality by the retrieval section. Any regression in those
metrics is surfaced by `RegressionRunner`.
