# `models/pretrained/` — **M1–M3**, downloaded base models

**I download these.** Blocked on the target SoC decision (asset guide §9 step 1), because the
SoC decides ORT+QNN vs LiteRT vs GPU-only, and that decides the export format.

## Expected layout

```
models/pretrained/
  CHECKSUMS.txt               ← SHA-256 of every file, recorded at download time
  det_v4_mobile/              M1  PP-OCRv4_mobile_det          ~4–5 MB INT8
  rec_en_v4_mobile/           M2  en_PP-OCRv4_mobile_rec       ~7–10 MB INT8
  rec_hi_v4_mobile/           M3  devanagari_PP-OCRv4_mobile_rec (v3 if v4 absent)
  dicts/
    en_dict.txt               D1
    devanagari_dict.txt       D2
```

## THIS FOLDER IS IMMUTABLE

Downloaded files are never edited, re-saved, or "cleaned up" in place. Every file gets a SHA-256
recorded in `CHECKSUMS.txt` **at download time**.

The reason is not tidiness. A quantized INT8 artifact is a function of (weights × calibration
data × quantization config). If the weights are not pinned, an accuracy regression six weeks from
now is unattributable — you cannot tell whether the model changed, the data changed, or the
config changed. Derived work goes in `onnx_fp32/`, `onnx_static/`, `onnx_int8/`, `finetuned/`.

## The dicts are load-bearing, not metadata

`en_dict.txt` / `devanagari_dict.txt` are one character per line, and **the line order is the
model's output layer index**. A dict/model mismatch does not throw — it produces *confident
garbage*. So:

- Ship the dict **next to** its model and version them **together** in the manifest (D3).
- Hash the dict, record it in the manifest, verify at load.
- `use_space_char` semantics (whether a trailing space entry exists) must match how the model
  was exported. **[verify per model]**

## On the download URLs

Exact filenames and URLs on the PaddleOCR model zoo move between releases. Treat the **model
identity** (family, variant, script) as the spec, and resolve the URL against the current PP-OCR
model list at download time. Do not hardcode a URL from a blog post.

Open question that changes the plan: **the v4 Devanagari recognizer may not exist** — the
multilingual Devanagari model may only be published at v3. A v3 backbone is likely CRNN, whose
LSTM head quantizes badly and is often unsupported on NPUs → graph split → silent CPU fallback.
Resolve this **before** committing to a framework.
