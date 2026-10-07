# E1 — Gold-Standard OCR Evaluation Dataset

E1 is the set that decides whether NPU OCR ships. It is an **evaluation** dataset, never a
training set: its only job is to measure OCR quality (CER / WER / page accuracy) honestly.

> ## The one rule
>
> **Ground truth is typed by a human from the image. Nothing else, ever.**
>
> No OCR, no recognizer, no language model, no "smart" pre-fill. This tooling contains no
> recognition capability at all — not disabled, absent.
>
> This is not purity. E1 exists to compare a candidate engine against the incumbent (ML Kit). If
> transcriptions were seeded from any engine, that engine's mistakes would become the reference:
> it would score near-perfect against its own errors while a genuinely better engine is penalised
> for disagreeing with them. **A contaminated evaluation set is worse than none**, because it
> produces confident numbers pointing the wrong way.

---

## The whole workflow

```
capture → stage → 08_make_lines.py → TRANSCRIBE (you) → validate_e1.py → 11_tsv_to_golden.py → benchmark
```

Everything except transcription is automated.

---

## 1. Capture evaluation pages

On device: **Dev Tools → Benchmarks → "OCR page capture"** → start → import books → stop.

The app writes the exact bitmaps `ImageContentExtractor.extractPdfPageText` receives into
`filesDir/ocr-capture/` as lossless PNG. Pull them:

```bash
adb shell run-as com.amar.vault tar c files/ocr-capture | tar x
```

### Choosing pages

| | |
|---|---|
| Volume | 50–200 transcribed lines per script, plus 10–20 full pages per script |
| Content | Stratified: clean digital, scanned/noisy, small text, tables, headings, multi-column, mixed EN+HI |
| **Separation** | **Use different *documents* than your C1 calibration corpus** |

The separation rule is not bureaucratic. Split by document, not by page — two pages of one book
share fonts, layout and scan quality, so a page-level split leaks calibration data into
evaluation and every number downstream becomes unfalsifiable.

Include multi-column pages from the start. A recognizer can be per-line perfect and still
scramble a two-column page into nonsense, and that failure only appears at page level.

## 2. Stage the pages

One subdirectory per book. The directory name becomes `source_book`:

```
data/val/_staging/
    book_hindi_prashasan/
        page_0001.png
        page_0002.png
    book_english_bio/
        page_0001.png
```

Pages dropped loose in `_staging/` are attributed to `_unsorted` rather than ignored. Page numbers
are parsed from the filename and sorted **numerically**, so `page_2` precedes `page_10`.

## 3. Label each book, then generate

One run consumes the **whole** staging tree and writes **one** `ground_truth.tsv`, so a global
`--script` / `--stratum` is only ever right for a corpus that is entirely one script and one
stratum. Re-running with different flags does not add a group — it reprocesses every book and
relabels the entire TSV.

For a mixed corpus, map each book in `e1_config.toml` instead:

```toml
[books.book_hindi_prashasan]
script  = "hi"
stratum = "clean"

[books.book_english_bio]
script  = "en"
stratum = "clean"

[books.report_govt_annual]
script  = "hi"
stratum = "tables"
```

The key is the staging subdirectory name. An omitted field inherits `[defaults]`, so a book can
override `stratum` alone. Invalid values and unknown keys are rejected at load time — a typo like
`script = "hindi"` fails immediately rather than becoming a TSV column the CER splits are keyed on.

```bash
cd tools/ocr-workbench/scripts
python 08_make_lines.py
```

It prints the resolved script/stratum per book before segmenting, and warns about both failure
modes that otherwise pass silently: a `[books.*]` key matching no staging directory (misspelled),
and a staging directory with no entry (falling back to `[defaults]`).

`--script` / `--stratum` remain available and set the fallback for books the mapping does not
name; a per-book entry always wins. Paths come from `e1_config.toml`; `--staging` / `--out`
override them. Produces:

```
data/val/
    images/       line_000001.png …   natural resolution — READ THESE while transcribing
    images_h48/   line_000001.png …   height-normalized to 48px, aspect preserved
    labels/       line_000001.txt …   blank sidecars (optional editing surface)
    ground_truth.tsv                  canonical; transcription column BLANK
    metadata.json                     checksums, bboxes, provenance, duplicates, warnings
    stats.json
```

Two image sets exist because they serve different consumers. `images_h48/` matches the
recognizer's fixed input height. But the Android benchmark feeds `mediaFile` to
`ImageContentExtractor`, which does its own preprocessing — handing it a pre-shrunk 48 px image
would degrade ML Kit for a reason unrelated to ML Kit, and ML Kit is the baseline every gate is
measured against. The export step therefore ships the natural crops.

**Regeneration is guarded.** If `ground_truth.tsv` already holds transcriptions, the tool refuses
to overwrite it and tells you how many rows are at risk. `--force` overrides.

### How lines are found

1. **Column split** on full-height whitespace gutters — runs first, because a horizontal
   projection over a two-column page merges both columns into one band per row and emits "lines"
   interleaving unrelated text.
2. **Horizontal projection profile** per column, with a merge pass: Devanagari vowel signs sit
   above the shirorekha (ि ी े ै ो ौ ं ँ) and below it (ु ू ृ), often separated by a row or two of
   white. Without merging they become their own "lines" and the real line is cropped with its
   matras sliced off — silently changing the word.
3. **Retry at a looser threshold** for faint or low-contrast scans.
4. **Connected components** when projection fails — handles bullet lists, numbered lists and
   irregular spacing, where a bullet glyph is a separate component sharing its text's baseline.
5. **Whole page, flagged** as a last resort. A flagged page is something you can act on; emitting
   nothing is invisible and emitting garbage is worse.

`metadata.json` records which method produced each line, and `stats.json` breaks it down.

## 4. Transcribe — the manual step

Open `ground_truth.tsv` and fill **only** the `transcription` column.

```
image_path              transcription   script  stratum  source_book  page  line
images/line_000001.png                  hi      clean    book_hindi   1     1
```

| Rule | Why |
|---|---|
| Read `images/`, not `images_h48/` | 48 px is unreadable; the natural crop is the legible one |
| Type what is **printed**, including the book's own typos | you are measuring recognition, not correctness |
| **NFC**, UTF-8, no BOM | the production OCR path NFC-normalizes its output; NFD ground truth scores identical text as entirely wrong |
| Never paste from OCR | see The one rule |
| Leave a row blank to skip it | blank rows are excluded from export, not shipped as empty truth |

With `[books.*]` set up in step 3 the `script` and `stratum` columns are already right per book;
correct only the rows where a book is internally mixed — a `mixed` EN+HI line inside an otherwise
Hindi book, or a table page inside a `clean` one. These columns drive the per-script and
per-stratum CER splits the acceptance gates are stated in.

Prefer editing one file at a time? Type into `labels/line_000001.txt` instead, then
`python validate_e1.py --adopt-labels` pulls them into the TSV. **The TSV is canonical**; if the
two ever disagree, validation reports it as an error rather than silently picking one.

## 5. Validate

```bash
python validate_e1.py            # human report
python validate_e1.py --strict   # warnings also fail
python validate_e1.py --json report.json
```

**Errors — the dataset is not usable:** broken image↔row mapping, duplicate paths, wrong column
count, unreadable or zero-byte images, non-NFC transcriptions, checksum drift against
`metadata.json`, label/TSV divergence, BOM, non-UTF-8.

**Warnings — usable but the numbers will mislead:** untranscribed rows, unknown script/stratum
labels, surviving duplicates, whole-page fallbacks still present, a `script` tag disagreeing with
the text actually typed.

Duplicates deserve emphasis. Repeated running headers are the common case and they are not
harmless: a set where 30 of 200 lines are the same header measures that header 30 times, dragging
the aggregate CER toward whatever it scores and starving the strata that matter. Delete the
redundant rows and their images, or accept the skew knowingly.

## 6. Statistics

```bash
python stats_e1.py
```

Pages, lines, books, per-script / per-stratum / per-book / per-method counts, duplicate count,
average and median line geometry, and a **width histogram of the 48 px crops** — that histogram is
the input to the recognizer bucket decision (asset guide §4.3), where every bucket costs its own
export, quantization run and accuracy validation.

Re-run it after transcribing: script and stratum coverage come from the TSV, so the numbers move
as you type even though the images do not.

## 7. Benchmark

```bash
python 11_tsv_to_golden.py --dest ./e1_golden --name e1
adb push e1_golden/e1.json /data/data/com.amar.vault/files/benchmark/GoldenDataset/
adb push e1_golden/media   /data/data/com.amar.vault/files/benchmark/GoldenDataset/
```

Then **Dev Tools → Benchmarks → OCR**. `GoldenDatasetStore` merges `filesDir/` over the packaged
assets, so transcribe → push → measure needs no Gradle build.

The export refuses to run while validation reports errors (`--skip-validation` overrides).
Exporting a broken dataset produces confident numbers built on a defect — worse than exporting
nothing.

You get `cer.avg`, `cer.en` / `cer.hi` / `cer.mixed`, `cer.clean` / `cer.scanned` / `cer.tables` /
…, `cer.pdfPage` vs `cer.image`, plus the legacy accuracy metrics. Gate definitions live in
`docs/NPU_OCR_ASSET_GUIDE.md` §7; **G7 is the ship gate.**

---

## Configuration

`e1_config.toml`, discovered by walking up from the script directory. Relative paths resolve
against the config file, so the checkout works from any location on any machine. Every value is
overridable per run on the CLI; unknown keys are rejected rather than silently ignored, so a typo
like `target_heigth` fails loudly instead of quietly using the default.

The settings most worth understanding:

- **`paper_percentile` / `paper_factor`** — binarization is paper-relative, *not* Otsu. Otsu
  assumes two comparably-sized pixel classes, but a text page is 98–99% paper. Measured on a
  150 dpi Devanagari page, Otsu chose 135, which kept only the shirorekha and collapsed every line
  to a 1-pixel band; the same page at 160+ segmented correctly.
- **`merge_gap_factor`** — set it to 0 and Devanagari matras get sliced off their own words.
- **`min_gutter_frac`** — too low splits ordinary word spacing into fake columns; too high merges
  real columns.

## Layout

```
tools/ocr-workbench/
    e1_config.toml
    e1lib/          config · runtime · imageops · segmentation · dataset · validate · stats
    scripts/        08_make_lines.py · validate_e1.py · stats_e1.py · 11_tsv_to_golden.py
    tests/          python -m unittest discover -s tests
```

Requires `numpy` and `Pillow`. `tqdm` is used for progress bars if installed and silently skipped
if not — a missing progress bar is never a reason for the pipeline to fail.

```bash
cd tools/ocr-workbench
python -m unittest discover -s tests -v
```
