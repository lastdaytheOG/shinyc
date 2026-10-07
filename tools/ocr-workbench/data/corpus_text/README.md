# `data/corpus_text/` — corpus text sample (charset audit input)

**You supply this. It is the cheapest, most valuable thing you can do right now, and nothing
blocks it.**

## What to put here

Plain-text extracts of the documents you actually index — **especially the Hindi ones**.

```
data/corpus_text/
  book_hindi_01.txt
  book_hindi_02.txt
  book_english_01.txt
  ...
```

- Any number of `.txt` files, any names, subfolders fine.
- **UTF-8, no BOM.**
- Text from *trusted* (non-OCR) PDF pages is ideal — it is ground truth for free.
- 50k–500k characters of Hindi is plenty. More is fine, it costs nothing.

## Why this matters

`devanagari_dict.txt` is a fixed list of characters. Its line order **is** the model's output
layer. If your books contain a character that dict does not have, the model **cannot ever emit
it** — and per asset guide §8, adding a character to the dict changes the output layer size,
which forces a **full fine-tune**, not just a re-quantization.

Finding that out now costs you a copy-paste. Finding it out in phase N3 costs a week.

## How to run the audit

```bash
python tools/ocr-workbench/scripts/charset_audit.py
```

It prints the character inventory of your corpus and, once the PaddleOCR dicts are downloaded
into `models/pretrained/dicts/`, diffs your charset against them and lists exactly which
characters are missing.

You can run it **before** the dicts exist — it will report your charset and skip the diff.
