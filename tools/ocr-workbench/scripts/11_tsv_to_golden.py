#!/usr/bin/env python3
"""
E1 → GoldenDataset — convert the transcribed dataset into the JSON the Android benchmark reads.

    python 11_tsv_to_golden.py --out ../data/val --dest ./e1_golden --name e1

Emits `<dest>/<name>.json` plus `<dest>/media/`, then:

    adb push e1.json /data/data/com.amar.vault/files/benchmark/GoldenDataset/
    adb push media   /data/data/com.amar.vault/files/benchmark/GoldenDataset/

`GoldenDatasetStore` merges `filesDir/benchmark/GoldenDataset/` over the packaged assets, so this
is a push-and-run loop with no Gradle build between transcription passes.

Ships the **natural-resolution** crops, never `images_h48/`. `ImageContentExtractor` performs its
own preprocessing, so feeding it a pre-shrunk 48px image would degrade ML Kit for a reason that
has nothing to do with ML Kit — and ML Kit is the baseline every acceptance gate is measured
against.

Untranscribed rows are skipped: the harness reports a blank-ground-truth case as SKIPPED anyway,
so shipping it would only inflate the skip count.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from e1lib.config import find_default_config, load_config  # noqa: E402
from e1lib.dataset import TSV_COLUMNS  # noqa: E402
from e1lib.runtime import ensure_utf8_output, setup_logging  # noqa: E402
from e1lib.validate import validate  # noqa: E402


# Console encoding must be settled before argparse can print anything.
ensure_utf8_output()


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--config", type=Path)
    p.add_argument("--out", type=Path, help="dataset directory (override [paths].out)")
    p.add_argument("--dest", type=Path, required=True, help="where to write the GoldenDataset")
    p.add_argument("--name", default="e1")
    p.add_argument("--content-type", default="PDF",
                   choices=["PDF", "SCREENSHOT", "IMAGE", "RECEIPT", "NOTE", "SAVED_LINK",
                            "MIXED_OCR"],
                   help="PDF routes cases through extractPdfPageText — the path NPU OCR replaces")
    p.add_argument("--skip-validation", action="store_true",
                   help="export even if validation reports errors (not recommended)")
    p.add_argument("-v", "--verbose", action="store_true")
    p.add_argument("-q", "--quiet", action="store_true")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    log = setup_logging(args.verbose, args.quiet)

    cfg = load_config(args.config or find_default_config(Path(__file__).resolve().parent))
    if args.out:
        # `replace` rather than rebuilding field-by-field: a manual E1Config(...) silently
        # drops any field the call forgets, which is how [books] first went missing.
        cfg = replace(cfg, paths=replace(cfg.paths, out=str(args.out.resolve())))

    if not cfg.tsv_path.exists():
        log.error("no ground_truth.tsv at %s", cfg.tsv_path)
        return 2

    # Exporting a broken dataset produces confident numbers built on a defect, which is worse
    # than exporting nothing — so validation gates the export by default.
    rep = validate(cfg, check_hashes=False)
    if rep.errors and not args.skip_validation:
        log.error("validation found %d error(s) — refusing to export.", len(rep.errors))
        for f in rep.errors[:10]:
            log.error("  %s: %s", f.code, f.message)
        log.error("Fix them, or pass --skip-validation to export anyway.")
        return 1

    media = args.dest / "media"
    media.mkdir(parents=True, exist_ok=True)

    lines = cfg.tsv_path.read_text(encoding="utf-8-sig").split("\n")
    if lines and lines[0].startswith("image_path\t"):
        lines = lines[1:]

    cases: list[dict] = []
    skipped = 0
    for line in lines:
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != len(TSV_COLUMNS):
            continue
        rel, tr, script, stratum, book, page, ln = (p.strip() for p in parts)
        if not tr:
            skipped += 1
            continue
        src = cfg.out_dir / rel
        if not src.exists():
            log.warning("missing image referenced by TSV: %s", rel)
            continue

        stem = Path(rel).stem
        shutil.copy2(src, media / f"{stem}.png")
        cases.append({
            "id": f"e1-{stem}",
            "contentType": args.content_type,
            "script": script,
            "stratum": stratum,
            # NFC because the production OCR path NFC-normalizes its output; comparing it against
            # NFD ground truth scores identical Devanagari text as entirely wrong.
            "groundTruthText": unicodedata.normalize("NFC", tr),
            "mediaFile": f"media/{stem}.png",
            "notes": f"E1 line {ln} of page {page}, {book}; human-transcribed, never OCR-derived",
        })

    if not cases:
        log.error("no transcribed rows — nothing to export")
        return 1

    dataset = {
        "name": args.name,
        "_comment": (
            "E1 gold-standard OCR evaluation set. groundTruthText was typed by a human from the "
            "image and never derived from OCR, a model, or any heuristic. contentType PDF routes "
            "cases through extractPdfPageText."
        ),
        "cases": cases,
    }
    out_json = args.dest / f"{args.name}.json"
    out_json.write_text(json.dumps(dataset, ensure_ascii=False, indent=2), encoding="utf-8")

    by_script: dict[str, int] = {}
    by_stratum: dict[str, int] = {}
    for c in cases:
        by_script[c["script"] or "(untagged)"] = by_script.get(c["script"] or "(untagged)", 0) + 1
        by_stratum[c["stratum"] or "(untagged)"] = by_stratum.get(c["stratum"] or "(untagged)", 0) + 1

    print(f"wrote {out_json}  ({len(cases)} cases, {skipped} untranscribed row(s) skipped)")
    print("  by script : " + "  ".join(f"{k}={v}" for k, v in sorted(by_script.items())))
    print("  by stratum: " + "  ".join(f"{k}={v}" for k, v in sorted(by_stratum.items())))
    print(f"  media     : {media}")
    if rep.warnings:
        print(f"  ({len(rep.warnings)} validation warning(s) — run validate_e1.py for detail)")
    print("\nPush (no rebuild needed):")
    print(f"  adb push {out_json.name} /data/data/com.amar.vault/files/benchmark/GoldenDataset/")
    print("  adb push media /data/data/com.amar.vault/files/benchmark/GoldenDataset/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
