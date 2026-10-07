#!/usr/bin/env python3
"""
Validate an E1 evaluation dataset. Reports only — never edits, except with --adopt-labels.

    python validate_e1.py
    python validate_e1.py --out ../data/val --strict
    python validate_e1.py --json report.json
    python validate_e1.py --adopt-labels        # pull labels/*.txt into ground_truth.tsv

Exit codes:  0 = pass   1 = errors (or warnings under --strict)   2 = could not run
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from e1lib.config import find_default_config, load_config  # noqa: E402
from e1lib.runtime import ensure_utf8_output, setup_logging  # noqa: E402
from e1lib.validate import adopt_labels, validate  # noqa: E402


# Console encoding must be settled before argparse can print anything.
ensure_utf8_output()


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--config", type=Path)
    p.add_argument("--out", type=Path, help="dataset directory (override [paths].out)")
    p.add_argument("--strict", action="store_true", help="treat warnings as failure")
    p.add_argument("--no-hashes", action="store_true", help="skip sha256 drift check (faster)")
    p.add_argument("--json", type=Path, help="also write the report as JSON")
    p.add_argument("--adopt-labels", action="store_true",
                   help="copy non-empty labels/*.txt into the TSV transcription column")
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
        log.error("no ground_truth.tsv at %s — run 08_make_lines.py first", cfg.tsv_path)
        return 2

    if args.adopt_labels:
        n = adopt_labels(cfg)
        log.info("adopted %d transcription(s) from labels/ into ground_truth.tsv", n)

    rep = validate(cfg, check_hashes=not args.no_hashes)

    print(f"E1 validation — {cfg.out_dir}")
    print(f"  rows {rep.rows}   transcribed {rep.transcribed}   images on disk {rep.images_on_disk}")
    if rep.by_script:
        print("  by script : " + "  ".join(f"{k}={v}" for k, v in sorted(rep.by_script.items())))
    if rep.by_stratum:
        print("  by stratum: " + "  ".join(f"{k}={v}" for k, v in sorted(rep.by_stratum.items())))

    if rep.errors:
        print(f"\nERRORS ({len(rep.errors)}) — dataset is NOT usable:")
        for f in rep.errors[:40]:
            loc = f" [{f.location}]" if f.location else ""
            print(f"   x {f.code}{loc}: {f.message}")
        if len(rep.errors) > 40:
            print(f"   ... and {len(rep.errors) - 40} more")

    if rep.warnings:
        print(f"\nWARNINGS ({len(rep.warnings)}):")
        for f in rep.warnings[:40]:
            loc = f" [{f.location}]" if f.location else ""
            print(f"   ! {f.code}{loc}: {f.message}")
        if len(rep.warnings) > 40:
            print(f"   ... and {len(rep.warnings) - 40} more")

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(rep.to_dict(), ensure_ascii=False, indent=2),
                             encoding="utf-8")
        print(f"\n  report: {args.json}")

    if rep.errors:
        print("\nFAIL")
        return 1
    if rep.warnings and args.strict:
        print("\nFAIL (--strict)")
        return 1
    if rep.transcribed == 0:
        print("\nPASS (structure only — nothing transcribed yet)")
        return 0
    print("\nPASS" + (" (with warnings)" if rep.warnings else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
