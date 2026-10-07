# `data/calib/pages/` — **C1**, calibration corpus (full pages)

**You supply this.** No labels needed. **UNBLOCKED as of 2026-07-28** — the capture hook is built
and tested; see "How to capture them" below.

## What to put here

```
data/calib/pages/
  doc001_p0007.png
  doc001_p0012.png
  doc002_p0003.png
  ...
```

| Property | Spec |
|---|---|
| Count | **200–500 pages** |
| Format | PNG, RGB, **exactly as the app renders them** (150 dpi today; add 300 dpi too if the hi-res escalation path is used) |
| Content | Rendered pages from the **actual books you index** |
| Coverage | Both scripts; dense body text, headings, tables, footnotes, multi-column, faint/scanned pages, and **at least a few genuinely bad scans** |
| Labels | **None.** PTQ only observes activation ranges |
| Overlap | **Must not share documents with `data/val/`** — disjoint documents, not just disjoint pages |

## How to capture them

1. **Dev Tools → Benchmarks → "OCR page capture (C1 calibration corpus)"** → tap to start.
2. **Import your books.** Every PDF page that falls back to OCR is written to
   `filesDir/ocr-capture/` as lossless PNG — the *exact* bitmap
   `ImageContentExtractor.extractPdfPageText` receives, before any preprocessing.
3. Watch the counter. It stops at **500 pages** (or 400 MB) and says so.
4. **Turn capture OFF**, then pull:

```bash
adb shell run-as com.amar.vault tar c files/ocr-capture | tar x
```

5. Move the PNGs into this folder. `capture_index.tsv` comes with them —
   `file · width · height · megapixels · sourceId · capturedAtMs`, one row per page. Keep it:
   the width histogram that decides the recognizer bucket set (asset guide §4.3) is computed
   straight off that file.

### Three things that will bite you

- **Do not run latency benchmarks with capture ON.** Writing a PNG per page costs real
  milliseconds. The hook sits outside the OCR ladder's timer so `ocr.*` latency metrics stay
  clean, but total import wall-time still grows.
- **Capture is a separate toggle from Production Evaluation Mode.** That one buffers reports in
  memory; this one writes hundreds of MB to disk. Turning on one does not turn on the other.
- **Only the PDF-page path is captured.** The image/screenshot path is a different input
  distribution, and the golden-set benchmark path is deliberately not hooked — otherwise a
  benchmark run would quietly seed synthetic images into your calibration corpus.

**Do not re-render the PDFs on desktop with PyMuPDF/pdftoppm instead.** A different rasterizer
produces similar-but-not-identical pixels (different anti-aliasing). For PTQ that is *usually*
tolerable — but if an accuracy gap shows up later, it becomes a confound you cannot rule out.
The whole point of calibration is to observe the activation ranges of the **real** input
distribution.

**Do not re-render the PDFs on desktop with PyMuPDF/pdftoppm instead.** A different rasterizer
produces similar-but-not-identical pixels (different anti-aliasing). For PTQ that is *usually*
tolerable — but if an accuracy gap shows up later, it becomes a confound you cannot rule out.
The whole point of calibration is to observe the activation ranges of the **real** input
distribution.

## Why the "bad scans" line is not optional

Calibration sets the numeric range the INT8 model can represent. Calibrate only on clean pages
and the model clips on noisy ones — which is exactly where OCR was already weakest.
