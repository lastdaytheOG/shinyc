#!/usr/bin/env python3
"""
Charset audit — work-order step 3 (docs/NPU_OCR_ASSET_GUIDE.md §9).

Answers one question: **do the characters in your corpus exist in the PaddleOCR dicts?**

Why it is worth running before anything else: a recognition model can only ever emit characters
that are in its dict, because the dict line index IS the output-layer index. A character that is
missing cannot be produced -- not rarely, never. And per asset guide §8, adding a character to
the dict changes the output layer size, which forces a full FINE-TUNE, not just a re-quantization.

So this script is the difference between "we knew in week 1" and "we discovered it in phase N3".

Stdlib only -- runs before `pip install -r requirements.txt`.

Usage:
    python tools/ocr-workbench/scripts/charset_audit.py
    python tools/ocr-workbench/scripts/charset_audit.py --min-count 3
    python tools/ocr-workbench/scripts/charset_audit.py --corpus some/other/dir
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
import time
import unicodedata
from pathlib import Path

WORKBENCH = Path(__file__).resolve().parent.parent
DEFAULT_CORPUS = WORKBENCH / "data" / "corpus_text"
DEFAULT_DICTS = WORKBENCH / "models" / "pretrained" / "dicts"
REPORTS = WORKBENCH / "reports"

# Dicts we expect once step 7 (download) has run. Absent today -- the script degrades to
# inventory-only rather than failing, so it is useful on day one.
DICT_FILES = {
    "en": "en_dict.txt",
    "hi": "devanagari_dict.txt",
}


def classify(ch: str) -> str:
    """Coarse bucket for a character, for the summary table."""
    cp = ord(ch)
    if ch in "\t\n\r":
        return "whitespace"
    if ch == " ":
        return "space"
    if 0x0900 <= cp <= 0x097F:
        return "devanagari"
    if 0xA8E0 <= cp <= 0xA8FF:
        return "devanagari-extended"
    if 0x1CD0 <= cp <= 0x1CFF:
        return "vedic-extensions"
    if cp < 0x80:
        if ch.isascii() and ch.isalpha():
            return "latin-ascii"
        if ch.isdigit():
            return "digit-ascii"
        return "punct-ascii"
    if 0x0080 <= cp <= 0x024F:
        return "latin-extended"
    if 0x2000 <= cp <= 0x206F:
        return "punct-general"      # smart quotes, dashes, ZWJ/ZWNJ neighbours
    if 0x0966 <= cp <= 0x096F:
        return "digit-devanagari"
    return "other"


def load_corpus(corpus_dir: Path) -> tuple[collections.Counter, int, list[Path]]:
    """Read every .txt under corpus_dir, NFC-normalize, count characters."""
    files = sorted(p for p in corpus_dir.rglob("*.txt") if p.name != "README.md")
    counts: collections.Counter = collections.Counter()
    total = 0
    for path in files:
        try:
            raw = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            print(f"  !! {path.name}: not valid UTF-8, skipped "
                  f"(re-save as UTF-8 without BOM)", file=sys.stderr)
            continue
        # NFC because the app NFC-normalizes OCR output. Auditing NFD text would report
        # decomposed combining marks that the runtime will never actually see -- a false alarm
        # that costs a day of chasing.
        text = unicodedata.normalize("NFC", raw)
        counts.update(text)
        total += len(text)
    return counts, total, files


def load_dict(path: Path) -> list[str] | None:
    """PaddleOCR dict: one character per line, order = output layer index."""
    if not path.exists():
        return None
    # Keep the trailing-space entry if present: `use_space_char` semantics depend on it, so
    # stripping newlines only (not spaces) preserves the file's real meaning.
    lines = path.read_text(encoding="utf-8").split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return [ln.rstrip("\r") for ln in lines]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS,
                    help="directory of .txt corpus samples")
    ap.add_argument("--dicts", type=Path, default=DEFAULT_DICTS,
                    help="directory holding en_dict.txt / devanagari_dict.txt")
    ap.add_argument("--min-count", type=int, default=1,
                    help="ignore characters seen fewer than N times (noise filter)")
    args = ap.parse_args()

    if not args.corpus.exists():
        print(f"ERROR: corpus dir not found: {args.corpus}", file=sys.stderr)
        return 2

    print(f"Charset audit -- reading {args.corpus}")
    counts, total, files = load_corpus(args.corpus)

    if not files:
        print(f"\nNo .txt files found in {args.corpus}.")
        print("Drop your extracted corpus text there (UTF-8, no BOM) and re-run.")
        print("See that folder's README.md for what to put in it.")
        return 1

    if args.min_count > 1:
        counts = collections.Counter({c: n for c, n in counts.items() if n >= args.min_count})

    print(f"  {len(files)} file(s), {total:,} chars, {len(counts)} distinct\n")

    # ---- Inventory by category -------------------------------------------------
    by_cat: dict[str, list[tuple[str, int]]] = collections.defaultdict(list)
    for ch, n in counts.items():
        by_cat[classify(ch)].append((ch, n))

    print("Character inventory")
    print("-" * 58)
    for cat in sorted(by_cat, key=lambda c: -sum(n for _, n in by_cat[c])):
        items = sorted(by_cat[cat], key=lambda t: -t[1])
        occ = sum(n for _, n in items)
        print(f"  {cat:<22} {len(items):>4} distinct   {occ:>10,} occurrences")
    print()

    # ---- Dict diff -------------------------------------------------------------
    report: dict = {
        "generatedAtMs": int(time.time() * 1000),
        "corpusDir": str(args.corpus),
        "files": len(files),
        "totalChars": total,
        "distinctChars": len(counts),
        "minCount": args.min_count,
        "byCategory": {c: len(v) for c, v in by_cat.items()},
        "dicts": {},
    }

    any_dict = False
    for label, fname in DICT_FILES.items():
        entries = load_dict(args.dicts / fname)
        if entries is None:
            continue
        any_dict = True
        covered = set(entries)
        # Whitespace is handled by the CTC decoder / use_space_char, not by dict membership.
        relevant = {ch: n for ch, n in counts.items() if ch not in "\t\n\r"}
        missing = sorted(((ch, n) for ch, n in relevant.items() if ch not in covered),
                         key=lambda t: -t[1])

        print(f"{fname}  ({len(entries)} entries)")
        print("-" * 58)
        if not missing:
            print("  OK -- every corpus character is representable.\n")
        else:
            miss_occ = sum(n for _, n in missing)
            print(f"  {len(missing)} corpus characters MISSING "
                  f"({miss_occ:,} occurrences, {miss_occ / total:.4%} of corpus)")
            for ch, n in missing[:40]:
                try:
                    name = unicodedata.name(ch)
                except ValueError:
                    name = "<unnamed>"
                print(f"    U+{ord(ch):04X}  {n:>8,}  {classify(ch):<20} {name}")
            if len(missing) > 40:
                print(f"    ... and {len(missing) - 40} more")
            print()

        report["dicts"][fname] = {
            "entries": len(entries),
            "missingCount": len(missing),
            "missing": [{"cp": f"U+{ord(c):04X}", "char": c, "count": n} for c, n in missing],
        }

    if not any_dict:
        print(f"No dicts found in {args.dicts} -- inventory only, diff skipped.")
        print("Re-run after the pretrained models + dicts are downloaded (work-order step 7).\n")

    REPORTS.mkdir(parents=True, exist_ok=True)
    out = REPORTS / "charset_audit.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Report written: {out}")

    # ---- Verdict ---------------------------------------------------------------
    if any_dict:
        worst = max((d["missingCount"] for d in report["dicts"].values()), default=0)
        if worst:
            print("\nVERDICT: dict extension needed -> the output layer changes -> a FINE-TUNE is")
            print("required, not just re-quantization (asset guide sec.8). Decide the final dict")
            print("BEFORE any fine-tuning starts, or the fine-tune has to be redone.")
            return 3
        print("\nVERDICT: dicts cover the corpus. No dict-driven retraining forced.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
