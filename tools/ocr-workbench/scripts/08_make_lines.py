#!/usr/bin/env python3
"""
Generate the E1 evaluation dataset from staged page images.

    python 08_make_lines.py
    python 08_make_lines.py --staging ../data/val/_staging --out ../data/val
    python 08_make_lines.py --script hi --stratum clean --verbose

Reads pages from the staging directory (one subdirectory per book), detects text lines, crops and
normalizes them, and writes images/, labels/, ground_truth.tsv and metadata.json.

THIS TOOL NEVER PRODUCES TEXT. No OCR, no recognizer, no model, no heuristic guess. Every
transcription is written blank for a human to type. E1 measures OCR quality, so seeding it from a
recognizer would make that recognizer's mistakes the reference — it would then score near-perfect
against its own errors while a genuinely better engine is penalised for disagreeing with them.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import replace
from functools import partial
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from e1lib.config import (  # noqa: E402
    STRATA, SCRIPTS, E1Config, check_book_coverage, find_default_config, format_book_table,
    load_config,
)
from e1lib.dataset import (  # noqa: E402
    build_lines, count_transcribed, discover_pages, write_metadata, write_tsv,
)
from e1lib.runtime import ensure_utf8_output, progress, setup_logging  # noqa: E402
from e1lib.stats import compute_stats, write_stats  # noqa: E402


# Console encoding must be settled before argparse can print anything.
ensure_utf8_output()


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--config", type=Path, help="e1_config.toml / .json (auto-discovered if omitted)")
    p.add_argument("--staging", type=Path, help="override [paths].staging")
    p.add_argument("--out", type=Path, help="override [paths].out")
    p.add_argument("--script", choices=[*SCRIPTS, ""],
                   help="corpus-wide script column value; used only when [books] is empty, "
                        "since a populated [books] must cover every staged book")
    p.add_argument("--stratum", choices=[*STRATA, ""],
                   help="corpus-wide stratum column value; used only when [books] is empty, "
                        "since a populated [books] must cover every staged book")
    p.add_argument("--force", action="store_true",
                   help="overwrite an existing dataset (REFUSED when transcriptions exist)")
    p.add_argument("--no-progress", action="store_true")
    p.add_argument("--log-file", type=Path)
    p.add_argument("-v", "--verbose", action="store_true")
    p.add_argument("-q", "--quiet", action="store_true")
    return p


def resolve_config(args: argparse.Namespace) -> E1Config:
    cfg_path = args.config or find_default_config(Path(__file__).resolve().parent)
    cfg = load_config(cfg_path)

    # CLI overrides win over the file; absolute paths are honoured as given.
    # `replace` rather than rebuilding field-by-field: a manual E1Config(...) silently
    # drops any field the call forgets, which is how [books] first went missing here.
    if args.staging:
        cfg = replace(cfg, paths=replace(cfg.paths, staging=str(args.staging.resolve())))
    if args.out:
        cfg = replace(cfg, paths=replace(cfg.paths, out=str(args.out.resolve())))
    if args.script is not None:
        cfg = replace(cfg, defaults=replace(cfg.defaults, script=args.script))
    if args.stratum is not None:
        cfg = replace(cfg, defaults=replace(cfg.defaults, stratum=args.stratum))
    return cfg


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    log = setup_logging(args.verbose, args.quiet, args.log_file)

    try:
        cfg = resolve_config(args)
    except (ValueError, RuntimeError) as exc:
        log.error("config error: %s", exc)
        return 2

    log.info("staging : %s", cfg.staging_dir)
    log.info("output  : %s", cfg.out_dir)

    # Guard the single most destructive mistake available here: regenerating over a
    # ground_truth.tsv that already holds hours of typed transcription.
    existing = count_transcribed(cfg.tsv_path)
    if existing and not args.force:
        log.error("REFUSING to overwrite %s — it already holds %d transcribed row(s).",
                  cfg.tsv_path, existing)
        log.error("Use a different --out, or pass --force if you truly mean to discard them.")
        return 3
    if existing and args.force:
        log.warning("--force: discarding %d existing transcription(s)", existing)

    try:
        pages = discover_pages(cfg.staging_dir)
    except FileNotFoundError as exc:
        log.error("%s", exc)
        log.error("Create it and drop page PNGs inside, one subdirectory per book.")
        return 2

    if not pages:
        log.error("no page images found under %s", cfg.staging_dir)
        return 2

    books = sorted({p.book for p in pages})
    log.info("found %d page(s) across %d book(s)", len(pages), len(books))

    # A staging directory with no [books.*] entry is fatal, and a [books.*] key matching no
    # staging directory is a warning. Both are checked here — before segmentation, while it
    # is still cheap — because both corrupt the per-script/per-stratum CER splits the gates
    # are stated in, and only one of them can be recovered from afterwards.
    try:
        stale = check_book_coverage(cfg, books)
    except ValueError as exc:
        log.error("config error: %s", exc)
        return 2
    if stale:
        log.warning("[books] maps %d name(s) with no staging directory — check the "
                    "spelling: %s", len(stale), ", ".join(stale))

    print("\nresolved mapping:")
    print(format_book_table(cfg, books))

    cfg.out_dir.mkdir(parents=True, exist_ok=True)
    prog = partial(progress, desc="segmenting", enabled=not args.no_progress)
    result = build_lines(cfg, pages, progress_iter=prog)

    if not result.records:
        log.error("no lines produced — check that the staged images are rendered text pages")
        return 4

    tsv = write_tsv(cfg, result.records)
    meta = write_metadata(cfg, result)
    stats = compute_stats(cfg)
    stats_path = write_stats(cfg, stats)

    print(f"\nE1 dataset written to {cfg.out_dir}")
    print(f"  books                {stats.books}")
    print(f"  pages                {stats.pages}")
    print(f"  lines                {stats.lines}")
    print(f"  segmentation method  " +
          ", ".join(f"{k}={v}" for k, v in sorted(stats.lines_per_method.items())))
    print(f"  duplicates           {stats.duplicate_count} redundant line(s) "
          f"({stats.exact_duplicate_groups} exact / {stats.near_duplicate_groups} near group(s))")
    if result.dropped_duplicates:
        print(f"  dropped as identical {len(result.dropped_duplicates)} crop(s) "
              f"byte-identical to a line already written")
    if result.excluded_pages:
        print(f"  excluded pages       {len(result.excluded_pages)} "
              f"(per [books.*] exclude): " +
              ", ".join(f"{p.book}/{p.page}" for p in result.excluded_pages))
    print(f"  avg line size        {stats.average_line_width:.0f} x {stats.average_line_height:.0f} px")
    print(f"\n  {tsv}")
    print(f"  {meta}")
    print(f"  {stats_path}")

    if result.failed_pages:
        print(f"\n  {len(result.failed_pages)} page(s) FAILED and produced nothing:")
        for f in result.failed_pages[:10]:
            print("    -", f)

    if result.warnings:
        print(f"\n  {len(result.warnings)} warning(s):")
        for w in result.warnings[:12]:
            print("    -", w)
        if len(result.warnings) > 12:
            print(f"    ... and {len(result.warnings) - 12} more (see metadata.json)")

    print("\nNEXT — transcribe by hand:")
    print(f"  1. open {tsv}")
    print("  2. fill the 'transcription' column from images/ (read those, not images_h48/)")
    print("  3. correct 'script' and 'stratum' per row where the default is wrong")
    print("  4. type what is PRINTED, including the book's own typos; NFC; never paste OCR output")
    print("  5. run  validate_e1.py  before trusting any measurement built on this set")
    return 0


if __name__ == "__main__":
    sys.exit(main())
