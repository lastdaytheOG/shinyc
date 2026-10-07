# NPU OCR — Conversion Workbench

Desktop tooling for producing the OCR model artifacts. **Not part of the Gradle build, never
ships in the APK.** Strategy lives in [`docs/NPU_OCR_ROADMAP.md`](../../docs/NPU_OCR_ROADMAP.md);
the full asset spec lives in [`docs/NPU_OCR_ASSET_GUIDE.md`](../../docs/NPU_OCR_ASSET_GUIDE.md).
This README is the **drop-folder map**: what goes where, and who supplies it.

Status: skeleton created. No models downloaded, no conversions run. Blocked per asset guide §9.

---

## Who supplies what

| | Asset | Folder | Blocked by |
|---|---|---|---|
| 🧑 **you** | Corpus text sample (charset audit) | `data/corpus_text/` | nothing — **start here** |
| 🧑 **you** | **C1** calibration pages | `data/calib/pages/` | nothing — capture hook is **live** |
| 🧑 **you** | **E1** golden line images + transcriptions | `data/val/{en,hi}/` | nothing — **the critical path** |
| 🧑 **you** | **E1** golden full pages | `data/val/pages/{en,hi}/` | nothing |
| 🧑 **you** | Fonts used in your books *(only if fine-tuning)* | `data/fonts/` | G3/G4 failing |
| 🧑 **you** | **F1** real transcribed lines *(only if fine-tuning)* | `data/train/{en,hi}/real/` | G3/G4 failing |
| 🤖 me | **M1–M3** pretrained models | `models/pretrained/` | target SoC named |
| 🤖 me | **C2** line crops | `data/calib/lines/{en,hi}/` | C1 + M1 |
| 🤖 me | **F2** synthetic lines | `data/train/{en,hi}/synth/` | fonts + corpus text |
| 🤖 me | FP32 / static / INT8 exports | `models/onnx_*/` | C1, C2, M1–M3 |
| 🤖 me | **D3** manifest + shipped artifacts | `app/src/main/assets/ocr/` | everything above |

**The two things that unblock the most work: name the target SoC, and transcribe E1.**

---

## Layout

```
tools/ocr-workbench/
  requirements.txt
  scripts/
    charset_audit.py          ← runnable now, needs only data/corpus_text/
    08_make_lines.py          ← pages → line crops + blank ground_truth.tsv (no OCR)
    09_validate_e1.py         ← 1:1 mapping, NFC, duplicates, coverage
    10_tsv_to_golden.py       ← transcriptions → app GoldenDataset JSON + media
    (00_download → 07_manifest, written as each stage unblocks)
  models/
    pretrained/               IMMUTABLE. every file SHA-256'd in CHECKSUMS.txt on arrival
    onnx_fp32/                after paddle2onnx (dynamic shapes)
    onnx_static/              after shape fixing, one file per bucket
    onnx_int8/                after PTQ  ← ship candidates
    tflite_int8/              LiteRT path only
    finetuned/                fine-tune checkpoints + exported inference models
  data/
    corpus_text/              🧑 your extracted text — charset audit input
    calib/pages/              🧑 C1 — full rendered pages, no labels
    calib/lines/{en,hi}/      🤖 C2 — line crops derived from C1
    val/{en,hi}/              🧑 E1 — golden line images + ground_truth.tsv
    val/pages/{en,hi}/        🧑 E1 — golden full pages + page text
    train/{en,hi}/real/       🧑 F1 — conditional
    train/{en,hi}/synth/      🤖 F2 — conditional
    fonts/                    🧑 fonts for synthetic generation — conditional
  reports/                    accuracy + latency reports, one per artifact version
```

**Rule: `models/pretrained/` is immutable.** Reproducibility of a quantized artifact depends on
knowing exactly which weights went in.

**Rule: `data/val/` never feeds calibration or training.** Leakage there invalidates every
accuracy number downstream. Split by *document*, not by page.

---

## Setup

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

Only `scripts/charset_audit.py` runs today, and it needs nothing but `numpy`-free stdlib —
you can run it before installing anything else.
