#!/usr/bin/env python3
"""
Compute statistics over an E1 dataset and refresh stats.json.

    python stats_e1.py
    python stats_e1.py --out ../data/val --json

Run it again after transcribing: script/stratum coverage is read from ground_truth.tsv, so the
numbers change as you type even though the images do not.
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
from e1lib.stats import compute_stats, write_stats  # noqa: E402


def _bar(n: int, total: int, width: int = 24) -> str:
    if total <= 0:
        return ""
    filled = int(round(width * n / total))
    return "#" * filled + "." * (width - filled)


# Console encoding must be settled before argparse can print anything.
ensure_utf8_output()


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--config", type=Path)
    p.add_argument("--out", type=Path, help="dataset directory (override [paths].out)")
    p.add_argument("--json", action="store_true", help="print the full stats JSON to stdout")
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

    if not cfg.metadata_path.exists() and not cfg.tsv_path.exists():
        log.error("no dataset at %s — run 08_make_lines.py first", cfg.out_dir)
        return 2

    st = compute_stats(cfg)
    path = write_stats(cfg, st)

    if args.json:
        print(json.dumps(st.to_dict(), ensure_ascii=False, indent=2))
        return 0

    print(f"E1 statistics — {cfg.out_dir}\n")
    print(f"  books        {st.books}")
    print(f"  pages        {st.pages}")
    print(f"  lines        {st.lines}")
    print(f"  transcribed  {st.transcribed} / {st.lines}"
          f"   {_bar(st.transcribed, st.lines)}")
    print(f"  duplicates   {st.duplicate_count} redundant "
          f"({st.exact_duplicate_groups} exact / {st.near_duplicate_groups} near group(s))")

    print(f"\n  line height  avg {st.average_line_height:.1f}  median {st.median_line_height:.0f} px")
    print(f"  line width   avg {st.average_line_width:.1f}  median {st.median_line_width:.0f}  "
          f"range {st.min_line_width}-{st.max_line_width} px")

    for title, data in (("by script", st.lines_per_script),
                        ("by stratum", st.lines_per_stratum),
                        ("by book", st.lines_per_book),
                        ("by segmentation method", st.lines_per_method)):
        if data:
            print(f"\n  {title}:")
            total = sum(data.values())
            for k, v in sorted(data.items(), key=lambda kv: -kv[1]):
                print(f"    {k:<24} {v:>5}  {_bar(v, total)}")

    if st.width_h48_histogram:
        # Feeds the recognizer bucket decision (asset guide §4.3): bucket count should be
        # justified by this histogram, since every bucket is its own export, quantization run
        # and accuracy validation.
        print("\n  width histogram @48px height (recognizer bucket input):")
        total = sum(st.width_h48_histogram.values())
        for k, v in st.width_h48_histogram.items():
            print(f"    {k:<24} {v:>5}  {_bar(v, total)}")

    print(f"\n  {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
