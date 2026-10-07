# `data/fonts/` — fonts for synthetic data generation (**F2**)

**You supply this — but ONLY IF fine-tuning is entered.** Do not collect fonts yet.

Fine-tuning is entered only if the acceptance gates fail:
- **G3** — Hindi CER more than 1.0 pt worse than ML Kit on E1, at FP32, or
- **G4** — INT8 regression more than 1.0 pt vs the model's own FP32.

Order of operations is strict: **measure pretrained FP32 → measure pretrained INT8 → only then
decide to fine-tune.** Fine-tuning before measuring is how weeks get burned on a problem that
did not exist.

## What to put here (when the time comes)

```
data/fonts/
  NotoSansDevanagari-Regular.ttf
  NotoSerifDevanagari-Regular.ttf
  <the actual fonts used in your books>.ttf
  LICENSES.md          ← one line per font: name, source, license, redistribution terms
```

The fonts that matter most are **the ones your books actually use**. Synthetic data trained on
fonts your corpus does not contain teaches the model the wrong glyph shapes.

## Check the licenses first — this is the sleeper issue

Many font licenses forbid derivative-dataset use. Training a model on a font is *usually* fine
(the model is not the font), but some licenses are aggressive enough to make it a shipping
blocker, and finding that out after generating 200k synthetic lines is expensive.

Prefer permissively licensed families (Noto Sans/Serif Devanagari, etc.). Record every font's
license in `LICENSES.md` **as you add it**, not in a cleanup pass later.
