# `data/train/` — **F1** (real) and **F2** (synthetic) fine-tuning corpora

**CONDITIONAL — do not build these yet.** Entered only if gate **G3** or **G4** fails
(see `../fonts/README.md` for the exact trigger). Expect Devanagari to need it and English not to.

Nothing here is trained from scratch. Every path is
**download → (optionally fine-tune) → export → quantize → validate**. If someone concludes we
need from-scratch training, that is a signal the model family choice was wrong, not that we need
a bigger GPU budget.

---

## F1 — real labeled lines 🧑 you

```
data/train/{en,hi}/real/
  img/line_00001.jpg
  img/line_00002.jpg
  train_list.txt          ← img/line_00001.jpg<TAB>वाक्य का पाठ
```

| Property | Spec |
|---|---|
| Count | **2,000–10,000 lines per script** needing improvement (Devanagari first) |
| Source | Crops from your corpus, transcribed |
| Format | PaddleOCR rec format: relative image path, **TAB**, transcription. UTF-8, no BOM |
| Split | 90/10 train/val — and that 10% val is **separate from E1** |

Bootstrap the same way as E1: have the FP32 model pre-fill, then correct. 3–5× faster than typing.

## F2 — synthetic lines 🤖 me

```
data/train/{en,hi}/synth/
  img/...
  train_list.txt
```

| Property | Spec |
|---|---|
| Count | **50,000–200,000 lines per script** |
| Generator | TextRecognitionDataGenerator (`trdg`) or PaddleOCR synth tooling **[verify current tool]** |
| Corpus text | **Your own extracted text**, from `../corpus_text/` — free, in-domain, zero transcription cost. This is the single best trick available here |
| Fonts | From `../fonts/` |
| Augmentation | Gaussian blur, JPEG artifacts, slight rotation (±2°), background texture, contrast jitter, ink bleed — mimic **rendered-then-rasterized pages, not photos** |
| Ratio | Pretrain on synth, then fine-tune on the real F1 mix (~10–20% real in the final stage) |

The augmentation line matters: this pipeline OCRs *rendered PDF pages*, not phone camera shots.
Training on camera-style augmentation (perspective warp, glare, motion blur) teaches robustness
to distortions that never occur in our input, and spends model capacity doing it.

---

## Two traps

**Decide the final dict BEFORE fine-tuning, never after.** Adding characters to the dict changes
the output layer size and requires re-initializing the head — which throws away the fine-tune you
just paid for. This is exactly what the charset audit (`../corpus_text/`) exists to catch early.

**After every fine-tune the artifact must be re-exported AND re-quantized AND re-validated.**
Fine-tuned FP32 accuracy is not evidence about the INT8 artifact that actually ships.
