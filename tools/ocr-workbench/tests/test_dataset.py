"""Unit tests for dataset assembly, naming, determinism and duplicate detection."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from e1lib.config import (
    BookCoverageError, DatasetConfig, DedupConfig, DefaultsConfig, E1Config, PathsConfig,
    check_book_coverage, format_book_table, load_config,
)
from e1lib.dataset import (
    TSV_COLUMNS, build_lines, count_transcribed, detect_duplicates, discover_pages,
    parse_page_number, write_metadata, write_tsv,
)
from e1lib.imageops import DHASH_BITS, dhash, normalize_height, signature
from e1lib.stats import compute_stats


def write_page(path: Path, rows=(50, 150, 250), h=400, w=800) -> None:
    """
    A synthetic text page: one solid bar per row in `rows`.

    Every bar is given a different length, and the lengths are derived from the page's own path
    so that two pages in different books never draw the same thing either. Without that, every
    bar on every page is byte-identical, `build_lines` drops all but the first as exact
    duplicates, and a test that meant to assert something else ends up asserting the dedup path.
    Deriving from the path rather than a counter keeps the fixture deterministic across runs,
    which the checksum and filename tests depend on.
    """
    a = np.full((h, w), 255, dtype=np.uint8)
    tag = zlib.crc32(path.name.encode()) ^ zlib.crc32(path.parent.name.encode())
    for i, y in enumerate(rows):
        end = w - 40 - (i * 11) - ((tag >> (3 * i)) & 7) * 13
        a[y:y + 16, 40:end] = 0
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(a).save(path)


def write_identical_pages(*paths: Path, rows=(50, 150), h=400, w=800) -> None:
    """Pages that ARE byte-identical, for the tests that exercise duplicate handling itself."""
    a = np.full((h, w), 255, dtype=np.uint8)
    for y in rows:
        a[y:y + 16, 40:w - 40] = 0
    for p in paths:
        p.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray(a).save(p)


class TestPageNumber(unittest.TestCase):
    def test_parses_trailing_number(self):
        self.assertEqual(parse_page_number("page_0007.png"), 7)
        self.assertEqual(parse_page_number("scan-12.jpg"), 12)

    def test_uses_the_last_number(self):
        self.assertEqual(parse_page_number("book2_page_0003.png"), 3)

    def test_missing_number_is_zero(self):
        self.assertEqual(parse_page_number("cover.png"), 0)


class TestDiscovery(unittest.TestCase):
    def test_books_from_subdirectories_and_numeric_page_order(self):
        with tempfile.TemporaryDirectory() as td:
            stg = Path(td)
            for n in (1, 2, 10):
                write_page(stg / "book_a" / f"page_{n:04d}.png")
            write_page(stg / "book_b" / "page_0001.png")
            pages = discover_pages(stg)

            self.assertEqual([p.book for p in pages], ["book_a"] * 3 + ["book_b"])
            # Numeric, not lexical: plain string sorting puts page_10 before page_2 and would
            # silently scramble reading order throughout the dataset.
            self.assertEqual([p.page for p in pages[:3]], [1, 2, 10])

    def test_loose_pages_land_in_unsorted(self):
        with tempfile.TemporaryDirectory() as td:
            stg = Path(td)
            write_page(stg / "page_0001.png")
            self.assertEqual(discover_pages(stg)[0].book, "_unsorted")

    def test_missing_staging_raises(self):
        with self.assertRaises(FileNotFoundError):
            discover_pages(Path("does-not-exist-anywhere"))


class TestBuild(unittest.TestCase):
    def _cfg(self, td: Path, **defaults) -> E1Config:
        return E1Config(
            paths=PathsConfig(staging=str(td / "_staging"), out=str(td / "val")),
            defaults=DefaultsConfig(**defaults) if defaults else DefaultsConfig(),
            root=td,
        )

    def test_build_writes_images_labels_and_blank_tsv(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "book_x" / "page_0001.png")
            cfg = self._cfg(td, script="hi", stratum="clean")
            res = build_lines(cfg, discover_pages(cfg.staging_dir))
            write_tsv(cfg, res.records)

            self.assertEqual(len(res.records), 3)
            for r in res.records:
                self.assertTrue((cfg.out_dir / r.image_path).exists())
                self.assertTrue((cfg.out_dir / r.label_path).exists())
                # Label sidecars must be created EMPTY. Anything else is contamination.
                self.assertEqual((cfg.out_dir / r.label_path).read_text(encoding="utf-8"), "")

            rows = cfg.tsv_path.read_text(encoding="utf-8").strip().split("\n")
            self.assertEqual(rows[0].split("\t"), list(TSV_COLUMNS))
            for row in rows[1:]:
                cells = row.split("\t")
                self.assertEqual(cells[1], "", "transcription column must be blank")
                self.assertEqual(cells[2], "hi")
                self.assertEqual(cells[3], "clean")
                self.assertEqual(cells[4], "book_x")

    def test_filenames_are_deterministic_and_unique(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "b" / "page_0001.png")
            write_page(td / "_staging" / "b" / "page_0002.png")
            cfg = self._cfg(td)
            names_a = [r.line_id for r in build_lines(cfg, discover_pages(cfg.staging_dir)).records]

            cfg2 = E1Config(paths=PathsConfig(staging=cfg.paths.staging, out=str(td / "val2")),
                            root=td)
            names_b = [r.line_id for r in build_lines(cfg2, discover_pages(cfg2.staging_dir)).records]

            self.assertEqual(names_a, names_b)
            self.assertEqual(len(set(names_a)), len(names_a))
            self.assertEqual(names_a[0], "line_000001")

    def test_checksums_are_stable_across_runs(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "b" / "page_0001.png")
            a = build_lines(self._cfg(td), discover_pages(td / "_staging"))
            cfg2 = E1Config(paths=PathsConfig(staging=str(td / "_staging"), out=str(td / "v2")),
                            root=td)
            b = build_lines(cfg2, discover_pages(cfg2.staging_dir))
            self.assertEqual([r.sha256 for r in a.records], [r.sha256 for r in b.records])

    def test_height_normalization_preserves_aspect_ratio(self):
        img = Image.new("RGB", (300, 100), "white")
        out = normalize_height(img, 48)
        self.assertEqual(out.height, 48)
        self.assertEqual(out.width, 144)   # 300 * 48/100

    def test_h48_variant_written_for_every_line(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "b" / "page_0001.png")
            cfg = self._cfg(td)
            res = build_lines(cfg, discover_pages(cfg.staging_dir))
            for r in res.records:
                p = cfg.out_dir / "images_h48" / f"{r.line_id}.png"
                self.assertTrue(p.exists())
                with Image.open(p) as im:
                    self.assertEqual(im.height, 48)

    def test_unreadable_page_is_recorded_not_fatal(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "b" / "page_0001.png")
            bad = td / "_staging" / "b" / "page_0002.png"
            bad.write_bytes(b"this is not a png")
            cfg = self._cfg(td)
            res = build_lines(cfg, discover_pages(cfg.staging_dir))
            self.assertEqual(len(res.failed_pages), 1)
            self.assertGreater(len(res.records), 0, "good pages must still be processed")


class TestDuplicates(unittest.TestCase):
    def test_exact_duplicates_grouped(self):
        class R:
            def __init__(self, i, s):
                self.line_id, self.sha256 = i, s
        recs = [R("a", "X"), R("b", "X"), R("c", "Y")]
        exact, _ = detect_duplicates(recs, [], 3)
        self.assertEqual(exact, [["a", "b"]])

    def test_near_duplicates_found_by_perceptual_hash(self):
        base = Image.new("L", (200, 40), "white")
        for x in range(10, 190, 8):
            base.putpixel((x, 20), 0)
        tweaked = base.copy()
        tweaked.putpixel((3, 3), 0)          # byte-different, visually identical
        _, near = detect_duplicates([], [("a", signature(base)), ("b", signature(tweaked))], 3)
        self.assertEqual(near, [["a", "b"]])

    def test_featureless_images_of_different_luminance_are_not_grouped(self):
        # dHash alone returns all-zero for BOTH, so they would collide on hamming distance.
        # The composite signature separates them by mean luminance.
        a = Image.new("L", (100, 40), "white")
        b = Image.new("L", (100, 40), "black")
        _, near = detect_duplicates([], [("a", signature(a)), ("b", signature(b))], 3)
        self.assertEqual(near, [])

    @staticmethod
    def _text_lines(n: int, w: int = 700, h: int = 28) -> list[Image.Image]:
        """`n` crops of glyph-scale strokes — the shape and stroke pitch of real line crops."""
        out = []
        for k in range(n):
            im = Image.new("L", (w, h), "white")
            px = im.load()
            x, step = 12, 0
            while x < w - 12:
                for dx in range((k + step) % 3 + 2):
                    if x + dx < w:
                        for y in range(7, h - 7):
                            px[x + dx, y] = 0
                step += 1
                x += 7 + ((k * 13 + step * 5) % 9)   # stroke pitch and width vary per crop
            out.append(im)
        return out

    def test_a_square_grid_resolves_far_less_of_a_text_line(self):
        # The reason the hash grid is line-shaped. At an 8x8 grid each cell averages ~78px of a
        # 700px line — nine or ten glyph strokes — so distinct lines collapse onto the same
        # hash. That is how near-duplicate groups came to span multiple books, which cannot
        # happen: two books do not share a rendered line.
        #
        # The claim is comparative, not absolute: a perceptual hash of a text line always loses
        # information, and no grid separates every pair. On the real corpus this widening took
        # cross-book groups from 31 of 99 to 10, and tightening the threshold onto the sharper
        # hash took them to 0.
        lines = self._text_lines(12)
        square = {dhash(im, 8, 8) for im in lines}
        wide = {dhash(im) for im in lines}

        self.assertLess(len(square), len(lines), "8x8 collides distinct lines — the bug")
        self.assertGreater(len(wide), len(square),
                           "the line-shaped grid must resolve strictly more of them")

    def test_the_wide_grid_still_matches_a_genuine_repeat(self):
        # Widening the grid must not make the detector blind: a re-rendered running header
        # differs by a pixel or two and must still group.
        base = Image.new("L", (700, 28), "white")
        for x in range(10, 690, 9):
            for y in range(8, 20):
                base.putpixel((x, y), 0)
        tweaked = base.copy()
        tweaked.putpixel((3, 3), 0)
        _, near = detect_duplicates([], [("a", signature(base)), ("b", signature(tweaked))], 8)
        self.assertEqual(near, [["a", "b"]])

    def test_hash_width_matches_the_declared_bit_count(self):
        im = Image.new("L", (700, 28), "white")
        for x in range(0, 700, 3):
            im.putpixel((x, 14), 0)
        self.assertLess(dhash(im).bit_length(), DHASH_BITS + 1)


class TestExactDuplicateDropping(unittest.TestCase):
    """
    Byte-identical crops are written once. Repeated running headers and table header rows are
    the real case: each extra copy re-weights that one line in the aggregate CER while adding
    no information the set did not already have.
    """

    def _cfg(self, td: Path, **dedup) -> E1Config:
        return E1Config(
            paths=PathsConfig(staging=str(td / "_staging"), out=str(td / "val")),
            dedup=DedupConfig(**dedup) if dedup else DedupConfig(),
            root=td)

    def test_identical_crops_are_written_once_and_recorded(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_identical_pages(td / "_staging" / "bk" / "page_0001.png",
                                  td / "_staging" / "bk" / "page_0002.png")
            cfg = self._cfg(td)
            res = build_lines(cfg, discover_pages(cfg.staging_dir))

            # Two pages x two identical bars = 4 crops, one distinct.
            self.assertEqual(len(res.records), 1)
            self.assertEqual(len(res.dropped_duplicates), 3)
            self.assertTrue(all(d.kept_line_id == "line_000001"
                                for d in res.dropped_duplicates))
            # The kept crop must be the FIRST in reading order.
            self.assertEqual(res.records[0].page, 1)
            self.assertEqual(res.records[0].line, 1)

    def test_line_numbering_stays_contiguous_across_a_drop(self):
        # A dropped duplicate must not consume a line number: the crop is hashed before it is
        # written, so there is never a file to delete and never a hole in the sequence.
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_identical_pages(td / "_staging" / "bk" / "page_0001.png",
                                  td / "_staging" / "bk" / "page_0002.png")
            write_page(td / "_staging" / "bk" / "page_0003.png", rows=(80, 240))
            cfg = self._cfg(td)
            res = build_lines(cfg, discover_pages(cfg.staging_dir))

            ids = [r.line_id for r in res.records]
            self.assertEqual(ids, [f"line_{i:06d}" for i in range(1, len(ids) + 1)])
            for r in res.records:
                self.assertTrue((cfg.out_dir / r.image_path).exists())

    def test_no_orphan_files_are_left_on_disk(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_identical_pages(td / "_staging" / "bk" / "page_0001.png",
                                  td / "_staging" / "bk" / "page_0002.png")
            cfg = self._cfg(td)
            res = build_lines(cfg, discover_pages(cfg.staging_dir))
            on_disk = sorted(p.name for p in cfg.images_dir.glob("*.png"))
            self.assertEqual(on_disk, [f"{r.line_id}.png" for r in res.records])

    def test_dropping_can_be_turned_off(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_identical_pages(td / "_staging" / "bk" / "page_0001.png",
                                  td / "_staging" / "bk" / "page_0002.png")
            cfg = self._cfg(td, drop_exact_duplicates=False)
            res = build_lines(cfg, discover_pages(cfg.staging_dir))
            self.assertEqual(len(res.records), 4)
            self.assertEqual(res.dropped_duplicates, [])
            # With dropping off, the exact groups are reported instead.
            self.assertEqual(len(res.exact_duplicates), 1)

    def test_with_dropping_on_no_exact_group_can_survive(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_identical_pages(td / "_staging" / "bk" / "page_0001.png",
                                  td / "_staging" / "bk" / "page_0002.png")
            cfg = self._cfg(td)
            res = build_lines(cfg, discover_pages(cfg.staging_dir))
            self.assertEqual(res.exact_duplicates, [],
                             "nothing identical was written, so nothing can be grouped")


class TestPageExclusion(unittest.TestCase):
    def _build(self, td: Path, toml: str):
        (td / "e1_config.toml").write_text(
            '[paths]\nstaging = "_staging"\nout = "val"\n' + toml, encoding="utf-8")
        cfg = load_config(td / "e1_config.toml")
        return cfg, build_lines(cfg, discover_pages(cfg.staging_dir))

    def test_excluded_page_produces_no_rows_and_is_recorded(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "bk" / "page_0001.png")
            write_page(td / "_staging" / "bk" / "page_0002.png")
            cfg, res = self._build(td, '[books.bk]\nscript = "hi"\nexclude = [2]\n')

            self.assertEqual({r.page for r in res.records}, {1})
            self.assertEqual([p.page for p in res.excluded_pages], [2])
            # An excluded page is not a processed page, so it must not be counted as one.
            self.assertEqual([p.page for p in res.pages], [1])

    def test_exclusion_is_visible_in_metadata(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "bk" / "page_0001.png")
            write_page(td / "_staging" / "bk" / "page_0002.png")
            cfg, res = self._build(td, '[books.bk]\nscript = "hi"\nexclude = [2]\n')
            write_metadata(cfg, res)
            meta = json.loads(cfg.metadata_path.read_text(encoding="utf-8"))

            # A page dropped silently is indistinguishable from one that was never staged.
            self.assertEqual(meta["counts"]["excludedPages"], 1)
            self.assertEqual([e["page"] for e in meta["excludedPages"]], [2])
            self.assertNotIn(2, [s["page"] for s in meta["sourcePages"]])

    def test_excluding_nothing_leaves_every_page(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "bk" / "page_0001.png")
            cfg, res = self._build(td, '[books.bk]\nscript = "hi"\n')
            self.assertEqual(res.excluded_pages, [])
            self.assertEqual(len(res.pages), 1)


class TestArtifacts(unittest.TestCase):
    def test_metadata_and_stats_agree_with_the_records(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "bk" / "page_0001.png")
            write_page(td / "_staging" / "bk" / "page_0002.png", rows=(60, 200))
            cfg = E1Config(paths=PathsConfig(staging=str(td / "_staging"), out=str(td / "val")),
                           defaults=DefaultsConfig(script="hi", stratum="clean"), root=td)
            res = build_lines(cfg, discover_pages(cfg.staging_dir))
            write_tsv(cfg, res.records)
            write_metadata(cfg, res)

            meta = json.loads(cfg.metadata_path.read_text(encoding="utf-8"))
            self.assertEqual(meta["datasetKind"], "evaluation")
            self.assertEqual(meta["counts"]["lines"], len(res.records))
            self.assertEqual(meta["counts"]["pages"], 2)

            st = compute_stats(cfg)
            self.assertEqual(st.lines, len(res.records))
            self.assertEqual(st.books, 1)
            self.assertEqual(st.transcribed, 0)
            self.assertEqual(st.lines_per_script.get("hi"), len(res.records))

    def test_count_transcribed_reads_the_tsv(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "ground_truth.tsv"
            p.write_text(
                "\t".join(TSV_COLUMNS) + "\n"
                + "images/a.png\t\thi\tclean\tbk\t1\t1\n"
                + "images/b.png\tकुछ पाठ\thi\tclean\tbk\t1\t2\n",
                encoding="utf-8")
            self.assertEqual(count_transcribed(p), 1)


class TestConfig(unittest.TestCase):
    def test_relative_paths_resolve_against_the_config_file(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            (td / "e1_config.toml").write_text(
                '[paths]\nstaging = "pages"\nout = "ds"\n', encoding="utf-8")
            cfg = load_config(td / "e1_config.toml")
            self.assertEqual(cfg.staging_dir, td / "pages")
            self.assertEqual(cfg.out_dir, td / "ds")

    def test_unknown_key_is_rejected_rather_than_silently_ignored(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "e1_config.toml"
            p.write_text('[segmentation]\ntarget_heigth = 48\n', encoding="utf-8")
            with self.assertRaises(ValueError):
                load_config(p)

    def test_digits_and_prefix_are_configurable(self):
        cfg = E1Config(dataset=DatasetConfig(image_prefix="ln_", image_digits=4))
        self.assertEqual(f"{cfg.dataset.image_prefix}{7:0{cfg.dataset.image_digits}d}", "ln_0007")


class TestBookMapping(unittest.TestCase):
    """Per-book script/stratum: one run covers the whole staging tree, so a global default
    cannot label a corpus that mixes scripts and strata."""

    def _load(self, body: str) -> E1Config:
        td = Path(tempfile.mkdtemp())
        p = td / "e1_config.toml"
        p.write_text(body, encoding="utf-8")
        return load_config(p)

    def test_per_book_entry_overrides_defaults(self):
        cfg = self._load(
            '[defaults]\nscript = "en"\nstratum = "clean"\n'
            '[books.hindi_tables]\nscript = "hi"\nstratum = "tables"\n')
        self.assertEqual(cfg.defaults_for("hindi_tables"), ("hi", "tables"))

    def test_unmapped_book_falls_back_to_defaults(self):
        cfg = self._load(
            '[defaults]\nscript = "en"\nstratum = "clean"\n'
            '[books.hindi_tables]\nscript = "hi"\n')
        self.assertEqual(cfg.defaults_for("some_other_book"), ("en", "clean"))

    def test_partial_entry_inherits_the_unset_field(self):
        # A book may override stratum alone and keep the corpus-wide script.
        cfg = self._load(
            '[defaults]\nscript = "hi"\nstratum = "clean"\n'
            '[books.faint_scan]\nstratum = "scanned"\n')
        self.assertEqual(cfg.defaults_for("faint_scan"), ("hi", "scanned"))

    def test_invalid_label_is_rejected_at_load_time(self):
        # "hindi" would otherwise reach the TSV and mislabel the per-script CER split.
        with self.assertRaises(ValueError):
            self._load('[books.b]\nscript = "hindi"\n')
        with self.assertRaises(ValueError):
            self._load('[books.b]\nstratum = "cleen"\n')
        with self.assertRaises(ValueError):
            self._load('[defaults]\nscript = "devanagari"\n')

    def test_unknown_key_in_a_book_table_is_rejected(self):
        with self.assertRaises(ValueError):
            self._load('[books.b]\nscripts = "hi"\n')

    def test_no_mapping_keeps_the_global_default_behaviour(self):
        cfg = self._load('[defaults]\nscript = "hi"\nstratum = "clean"\n')
        self.assertEqual(cfg.books, {})
        self.assertEqual(cfg.defaults_for("anything"), ("hi", "clean"))

    def test_mapping_reaches_the_tsv_columns(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "bk_hi" / "page_0001.png")
            write_page(td / "_staging" / "bk_en" / "page_0001.png")
            (td / "e1_config.toml").write_text(
                '[paths]\nstaging = "_staging"\nout = "val"\n'
                '[defaults]\nscript = ""\nstratum = ""\n'
                '[books.bk_hi]\nscript = "hi"\nstratum = "scanned"\n'
                '[books.bk_en]\nscript = "en"\nstratum = "clean"\n',
                encoding="utf-8")
            cfg = load_config(td / "e1_config.toml")
            result = build_lines(cfg, discover_pages(cfg.staging_dir))
            write_tsv(cfg, result.records)

            rows = [r.split("\t") for r in
                    cfg.tsv_path.read_text(encoding="utf-8").splitlines()[1:] if r]
            by_book = {r[TSV_COLUMNS.index("source_book")]:
                       (r[TSV_COLUMNS.index("script")], r[TSV_COLUMNS.index("stratum")])
                       for r in rows}
            self.assertEqual(by_book["bk_hi"], ("hi", "scanned"))
            self.assertEqual(by_book["bk_en"], ("en", "clean"))

    def test_mapping_is_recorded_in_metadata_provenance(self):
        cfg = self._load('[books.bk]\nscript = "hi"\nstratum = "tables"\n')
        self.assertEqual(
            cfg.to_dict()["books"],
            {"bk": {"script": "hi", "stratum": "tables", "pages": {}, "exclude": ()}})

    def test_page_override_wins_over_the_book_label(self):
        cfg = self._load('[books.bk]\nscript = "hi"\nstratum = "clean"\n'
                         '[books.bk.pages]\n7 = { stratum = "tables" }\n')
        self.assertEqual(cfg.labels_for("bk", 7), ("hi", "tables"))
        # Only the field the page sets is overridden; script still comes from the book.
        self.assertEqual(cfg.labels_for("bk", 6), ("hi", "clean"))

    def test_page_override_inherits_book_then_defaults(self):
        cfg = self._load('[defaults]\nscript = "hi"\nstratum = "clean"\n'
                         '[books.bk]\n'
                         '[books.bk.pages]\n2 = { stratum = "tables" }\n')
        self.assertEqual(cfg.labels_for("bk", 2), ("hi", "tables"))
        self.assertEqual(cfg.labels_for("bk", 1), ("hi", "clean"))

    def test_page_override_reaches_the_tsv_for_that_page_only(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            write_page(td / "_staging" / "bk" / "page_0001.png")
            write_page(td / "_staging" / "bk" / "page_0002.png")
            (td / "e1_config.toml").write_text(
                '[paths]\nstaging = "_staging"\nout = "val"\n'
                '[books.bk]\nscript = "hi"\nstratum = "clean"\n'
                '[books.bk.pages]\n2 = { stratum = "tables" }\n', encoding="utf-8")
            cfg = load_config(td / "e1_config.toml")
            res = build_lines(cfg, discover_pages(cfg.staging_dir))

            by_page = {r.page: r.stratum for r in res.records}
            self.assertEqual(by_page[1], "clean")
            self.assertEqual(by_page[2], "tables")
            self.assertTrue(all(r.script == "hi" for r in res.records))

    def test_invalid_label_in_a_page_override_is_rejected(self):
        with self.assertRaises(ValueError):
            self._load('[books.bk]\n[books.bk.pages]\n3 = { stratum = "tabels" }\n')

    def test_non_numeric_page_key_is_rejected(self):
        with self.assertRaises(ValueError):
            self._load('[books.bk]\n[books.bk.pages]\nseven = { stratum = "tables" }\n')

    def test_unknown_key_in_a_page_override_is_rejected(self):
        with self.assertRaises(ValueError):
            self._load('[books.bk]\n[books.bk.pages]\n3 = { strata = "tables" }\n')

    def test_excluding_and_overriding_the_same_page_is_rejected(self):
        # The override would read as if it were in effect while producing no rows at all.
        with self.assertRaises(ValueError):
            self._load('[books.bk]\nexclude = [3]\n'
                       '[books.bk.pages]\n3 = { stratum = "tables" }\n')

    def test_exclude_must_be_integer_page_numbers(self):
        with self.assertRaises(ValueError):
            self._load('[books.bk]\nexclude = "3"\n')
        with self.assertRaises(ValueError):
            self._load('[books.bk]\nexclude = ["3"]\n')
        with self.assertRaises(ValueError):
            self._load('[books.bk]\nexclude = [3, 3]\n')

    def test_duplicate_book_table_is_rejected_by_the_parser(self):
        # TOML forbids redefining a table, so "exactly one mapping per book" is structural
        # rather than something the loader has to police.
        with self.assertRaises(Exception):
            self._load('[books.bk]\nscript = "hi"\n[books.bk]\nscript = "en"\n')


class TestBookCoverage(unittest.TestCase):
    """
    A populated [books] must name every staged book.

    Inheriting the blank [defaults] is the silent-failure path: the run still emits crops and
    a complete-looking TSV, but the unlabelled rows drop out of the per-script and
    per-stratum CER splits, so the measurement covers fewer books than it reports.
    """

    def _load(self, body: str) -> E1Config:
        td = Path(tempfile.mkdtemp())
        p = td / "e1_config.toml"
        p.write_text(body, encoding="utf-8")
        return load_config(p)

    def test_unmapped_staged_book_raises(self):
        cfg = self._load('[books.bk_hi]\nscript = "hi"\nstratum = "clean"\n')
        with self.assertRaises(BookCoverageError) as ctx:
            check_book_coverage(cfg, ["bk_hi", "bk_en"])
        self.assertIn("bk_en", str(ctx.exception))

    def test_coverage_error_is_a_value_error(self):
        # The scripts catch (ValueError, RuntimeError) as "config error"; coverage must land
        # there rather than escaping as an unhandled traceback.
        cfg = self._load('[books.bk_hi]\nscript = "hi"\n')
        self.assertTrue(issubclass(BookCoverageError, ValueError))
        with self.assertRaises(ValueError):
            check_book_coverage(cfg, ["other"])

    def test_full_coverage_passes_and_reports_no_stale_keys(self):
        cfg = self._load(
            '[books.bk_hi]\nscript = "hi"\nstratum = "clean"\n'
            '[books.bk_en]\nscript = "en"\nstratum = "clean"\n')
        self.assertEqual(check_book_coverage(cfg, ["bk_en", "bk_hi"]), [])

    def test_mapped_name_with_no_staging_directory_is_returned_not_raised(self):
        # A stale key cannot mislabel anything, so it is a warning; only the reverse
        # direction corrupts the splits.
        cfg = self._load(
            '[books.bk_hi]\nscript = "hi"\n[books.typo_bk]\nscript = "en"\n')
        self.assertEqual(check_book_coverage(cfg, ["bk_hi"]), ["typo_bk"])

    def test_empty_books_keeps_the_global_flag_workflow(self):
        cfg = self._load('[defaults]\nscript = "hi"\nstratum = "clean"\n')
        self.assertEqual(check_book_coverage(cfg, ["anything", "at_all"]), [])

    def test_coverage_is_case_sensitive(self):
        # Staging keys are directory names; "Gazette" and "gazette" are different books on a
        # case-sensitive filesystem and must not be silently unified.
        cfg = self._load('[books.Gazette]\nscript = "mixed"\nstratum = "mixed"\n')
        with self.assertRaises(BookCoverageError):
            check_book_coverage(cfg, ["gazette"])

    def test_table_shows_the_resolved_values(self):
        cfg = self._load(
            '[defaults]\nscript = "en"\nstratum = "clean"\n'
            '[books.bk_hi]\nstratum = "scanned"\n')
        table = format_book_table(cfg, ["bk_hi"])
        # stratum overridden per book, script inherited from [defaults]
        self.assertIn("script=en", table)
        self.assertIn("stratum=scanned", table)

    def test_table_is_empty_for_no_books(self):
        self.assertEqual(format_book_table(self._load(""), []), "")


class TestCliConfigResolution(unittest.TestCase):
    """
    resolve_config() applies CLI overrides on top of the loaded config.

    It originally rebuilt E1Config field-by-field, which silently dropped any field the
    call site forgot — [books] loaded correctly and then vanished before use, so the TSV
    came out labelled from [defaults] with no error anywhere. These tests pin the whole
    config surviving an override, not just the field that happened to break.
    """

    @staticmethod
    def _module():
        import importlib.util
        p = Path(__file__).resolve().parent.parent / "scripts" / "08_make_lines.py"
        spec = importlib.util.spec_from_file_location("make_lines_under_test", p)
        m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(m)
        return m

    def test_out_override_preserves_every_other_field(self):
        m = self._module()
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            (td / "e1_config.toml").write_text(
                '[paths]\nstaging = "_staging"\nout = "val"\n'
                '[segmentation]\ntarget_height = 64\n'
                '[dedup]\nnear_dup_distance = 7\n'
                '[dataset]\nimage_prefix = "ln_"\n'
                '[defaults]\nscript = "en"\nstratum = "clean"\n'
                '[books.bk_hi]\nscript = "hi"\nstratum = "scanned"\n', encoding="utf-8")
            args = m.build_parser().parse_args(
                ["--config", str(td / "e1_config.toml"), "--out", str(td / "other")])
            cfg = m.resolve_config(args)

            self.assertEqual(cfg.out_dir, td / "other")
            self.assertEqual(cfg.staging_dir, td / "_staging")  # untouched by --out
            self.assertEqual(cfg.defaults_for("bk_hi"), ("hi", "scanned"))
            self.assertEqual(cfg.segmentation.target_height, 64)
            self.assertEqual(cfg.dedup.near_dup_distance, 7)
            self.assertEqual(cfg.dataset.image_prefix, "ln_")

    def test_script_flag_is_a_fallback_and_does_not_override_the_mapping(self):
        # Precedence: the per-book entry is the more specific statement, so it wins; the
        # flag supplies the value for books the mapping does not name.
        m = self._module()
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            (td / "e1_config.toml").write_text(
                '[books.bk_hi]\nscript = "hi"\nstratum = "scanned"\n', encoding="utf-8")
            args = m.build_parser().parse_args(
                ["--config", str(td / "e1_config.toml"),
                 "--script", "en", "--stratum", "clean"])
            cfg = m.resolve_config(args)

            self.assertEqual(cfg.defaults_for("bk_hi"), ("hi", "scanned"))
            self.assertEqual(cfg.defaults_for("unmapped_book"), ("en", "clean"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
