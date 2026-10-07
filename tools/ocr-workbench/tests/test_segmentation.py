"""Unit tests for line segmentation. Pure numpy — no image files, no I/O."""

from __future__ import annotations

import sys
import unittest
from dataclasses import replace
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from e1lib.config import FallbackConfig, SegmentationConfig
from e1lib.imageops import ink_mask, otsu_threshold
from e1lib.segmentation import (
    connected_components, find_columns, find_lines_projection,
    group_components_into_lines, runs_of_true, segment_page, Box,
)


def page(h: int, w: int) -> np.ndarray:
    """A white page as 8-bit grayscale."""
    return np.full((h, w), 255, dtype=np.uint8)


def draw_line(img: np.ndarray, y: int, x0: int, x1: int, thickness: int = 16,
              value: int = 0) -> None:
    img[y:y + thickness, x0:x1] = value


class TestRuns(unittest.TestCase):
    def test_runs_of_true(self):
        f = np.array([0, 1, 1, 0, 0, 1, 0], dtype=bool)
        self.assertEqual(runs_of_true(f), [(1, 3), (5, 6)])

    def test_run_touching_the_end_is_closed(self):
        f = np.array([0, 1, 1], dtype=bool)
        self.assertEqual(runs_of_true(f), [(1, 3)])

    def test_empty(self):
        self.assertEqual(runs_of_true(np.zeros(5, dtype=bool)), [])


class TestBinarization(unittest.TestCase):
    def test_otsu_is_too_low_on_a_sparse_text_page(self):
        # The defect that made pure-Otsu segmentation fail: a page that is ~99% paper drives Otsu
        # low enough to keep only the darkest core pixels. Guarding it so nobody "simplifies"
        # ink_mask back to plain Otsu.
        img = page(400, 800)
        draw_line(img, 100, 50, 700, thickness=4, value=0)      # solid core
        img[104:118, 50:700] = 190                              # antialiased body
        otsu = otsu_threshold(img)
        mask = ink_mask(img)
        core_only = img < otsu
        self.assertGreater(mask.sum(), core_only.sum(),
                           "paper-relative threshold must keep more than Otsu's core pixels")
        self.assertTrue(mask[110, 300], "antialiased stroke body must count as ink")

    def test_blank_page_has_no_ink(self):
        self.assertFalse(ink_mask(page(100, 100)).any())


class TestColumns(unittest.TestCase):
    def setUp(self):
        self.seg = SegmentationConfig()

    def test_single_column(self):
        img = page(400, 1000)
        draw_line(img, 100, 100, 900)
        cols = find_columns(ink_mask(img), self.seg.min_gutter_frac, self.seg.min_col_frac)
        self.assertEqual(len(cols), 1)

    def test_two_columns_split_on_the_gutter(self):
        img = page(400, 1000)
        for y in (60, 140, 220):
            draw_line(img, y, 50, 420)     # left column
            draw_line(img, y, 580, 950)    # right column
        cols = find_columns(ink_mask(img), self.seg.min_gutter_frac, self.seg.min_col_frac)
        self.assertEqual(len(cols), 2)
        self.assertLess(cols[0].x1, cols[1].x0)

    def test_ordinary_word_spacing_does_not_split(self):
        # A gutter must be empty over the FULL page height; word gaps never are.
        img = page(400, 1000)
        draw_line(img, 60, 50, 300)
        draw_line(img, 60, 340, 950)
        draw_line(img, 140, 50, 950)      # this row spans the gap
        cols = find_columns(ink_mask(img), self.seg.min_gutter_frac, self.seg.min_col_frac)
        self.assertEqual(len(cols), 1)

    def test_narrow_gutter_is_found(self):
        # A tightly-set journal page puts 7-10px between columns. The old 0.030 floor demanded
        # 36px on a page this wide, read the spread as ONE column, and so projected ink from both
        # columns onto every row — which merged every band and lost the page entirely.
        img = page(400, 1000)
        for y in (60, 140, 220, 300):
            draw_line(img, y, 50, 495)
            draw_line(img, y, 505, 950)    # 10px gutter
        cols = find_columns(ink_mask(img), self.seg.min_gutter_frac, self.seg.min_col_frac)
        self.assertEqual(len(cols), 2)

    def test_a_spanning_headline_does_not_veto_the_gutter(self):
        # A masthead crosses the gutter. At zero tolerance that one element vetoes the split for
        # the whole page, which is the ordinary journal layout rather than an edge case — the
        # 1953 Nature pages in this corpus do not split at all without the tolerance.
        #
        # Page proportions matter here: the tolerance is a fraction of ROWS, sized to admit one
        # spanning line and not two, so a masthead has to be drawn at a realistic scale.
        img = page(1600, 1000)             # 1% tolerance == 16 rows
        draw_line(img, 40, 50, 950, thickness=14)
        for y in (200, 400, 600, 800, 1000, 1200):
            draw_line(img, y, 50, 470)
            draw_line(img, y, 530, 950)
        mask = ink_mask(img)
        self.assertEqual(len(find_columns(mask, self.seg.min_gutter_frac,
                                          self.seg.min_col_frac, 0.0)), 1)
        self.assertEqual(len(find_columns(mask, self.seg.min_gutter_frac, self.seg.min_col_frac,
                                          self.seg.gutter_ink_tolerance)), 2)

    def test_tolerance_admits_one_spanning_line_not_a_column_of_them(self):
        # The tolerance must stay small enough that a genuinely full-width block still reads as
        # one column. Three spanning lines is 42 of 1600 rows, well past the 16-row allowance.
        img = page(1600, 1000)
        for y in (40, 100, 160):
            draw_line(img, y, 50, 950, thickness=14)
        for y in (400, 600, 800, 1000):
            draw_line(img, y, 50, 470)
            draw_line(img, y, 530, 950)
        cols = find_columns(ink_mask(img), self.seg.min_gutter_frac, self.seg.min_col_frac,
                            self.seg.gutter_ink_tolerance)
        self.assertEqual(len(cols), 1)

    def test_tolerance_does_not_turn_word_spacing_into_a_gutter(self):
        # The full-height requirement, not the tolerance, is what rejects word gaps — so a gap
        # crossed by many rows must still fail even with the tolerance switched on.
        img = page(400, 1000)
        for y in (60, 100, 140, 180, 220, 260):
            draw_line(img, y, 50, 950)     # every row crosses the middle
        img[300:316, 50:470] = 0
        img[300:316, 530:950] = 0
        cols = find_columns(ink_mask(img), self.seg.min_gutter_frac, self.seg.min_col_frac,
                            self.seg.gutter_ink_tolerance)
        self.assertEqual(len(cols), 1)


class TestProjection(unittest.TestCase):
    def setUp(self):
        self.seg = SegmentationConfig()

    def test_finds_evenly_spaced_lines(self):
        img = page(500, 800)
        for y in (50, 150, 250, 350):
            draw_line(img, y, 40, 760)
        lines = find_lines_projection(ink_mask(img), self.seg)
        self.assertEqual(len(lines), 4)

    def test_varying_line_spacing(self):
        img = page(600, 800)
        for y in (40, 120, 260, 300, 480):
            draw_line(img, y, 40, 760)
        lines = find_lines_projection(ink_mask(img), self.seg)
        # 300 is only 24px below 260's top (thickness 16) -> merged as one line; 4 groups remain.
        self.assertGreaterEqual(len(lines), 4)

    def test_devanagari_matra_gap_is_merged_not_split(self):
        # Body plus a detached mark 3px above it. Without the merge pass this yields two bands and
        # the real line gets cropped with its matra sliced off — silently changing the word.
        img = page(300, 600)
        draw_line(img, 100, 60, 540, thickness=18)   # main body: rows 100-117
        draw_line(img, 94, 100, 300, thickness=3)    # matra: rows 94-96, a 3px gap above the body
        lines = find_lines_projection(ink_mask(img), self.seg)
        self.assertEqual(len(lines), 1, "matra and body must be one line, not two")
        y0, y1 = lines[0]
        self.assertLessEqual(y0, 94, "matra must be inside the crop, not sliced off")
        self.assertGreaterEqual(y1, 118)

    def test_a_full_line_gap_is_still_a_real_break(self):
        # The merge pass must not become a blanket "join everything": a gap comparable to the line
        # height is a genuine line break, and merging it would fuse two independent lines into one
        # crop that no transcription can describe.
        img = page(300, 600)
        draw_line(img, 100, 60, 540, thickness=18)
        draw_line(img, 136, 60, 540, thickness=18)   # 18px gap == one line height
        self.assertEqual(len(find_lines_projection(ink_mask(img), self.seg)), 2)

    def test_speckle_does_not_collapse_the_merge_gap(self):
        # 1px noise rows previously dominated the median line height, driving merge_gap to its
        # floor so the merge pass did nothing and min-height then discarded every real line.
        img = page(500, 800)
        for y in (50, 150, 250):
            draw_line(img, y, 40, 760)
        img[10, 5] = 0
        img[20, 7] = 0
        img[30, 9] = 0
        lines = find_lines_projection(ink_mask(img), self.seg)
        self.assertEqual(len(lines), 3)

    def test_blank_page_yields_nothing(self):
        self.assertEqual(find_lines_projection(ink_mask(page(200, 400)), self.seg), [])


class TestMergeCap(unittest.TestCase):
    """
    Merging is transitive, so each merge extends the band and can swallow the next one.

    On tightly-leaded text that chained until a single band covered most of the page — which
    max_line_height_frac then discarded, so the lines were found, fused, and thrown away. The
    page reported zero lines while its content had been detected all along.
    """

    def setUp(self):
        self.seg = SegmentationConfig()

    def _tight_page(self) -> np.ndarray:
        # 20 lines at a 4px lead: gap (4) is under merge_gap (12*0.35 -> 4), so every
        # adjacent pair is a merge candidate and the chain runs the length of the page.
        img = page(600, 800)
        for i in range(20):
            draw_line(img, 40 + i * 16, 40, 760, thickness=12)
        return ink_mask(img)

    def test_uncapped_merging_collapses_the_page(self):
        seg = replace(self.seg, merge_cap_factor=0.0)
        lines = find_lines_projection(self._tight_page(), seg)
        # One band spanning the text block, then dropped by the height filter: nothing survives.
        self.assertEqual(lines, [], "this is the failure the cap exists to prevent")

    def test_cap_keeps_the_lines(self):
        # The cap bounds the damage rather than eliminating it: at a 4px lead the gap is still
        # under merge_gap, so neighbours fuse in small groups — 20 lines come back as ~10 bands
        # of two. That is the honest result and it is the residual this fix does not address;
        # basing merge_gap on a low quantile rather than the median is what would.
        lines = find_lines_projection(self._tight_page(), self.seg)
        self.assertGreaterEqual(len(lines), 10)
        cap = 12 * self.seg.merge_cap_factor
        for y0, y1 in lines:
            self.assertLessEqual(y1 - y0, cap + 1)

    def test_cap_does_not_block_a_matra_join(self):
        # The cap must stop a runaway chain without stopping the one merge Devanagari needs.
        img = page(300, 600)
        draw_line(img, 100, 60, 540, thickness=18)
        draw_line(img, 94, 100, 300, thickness=3)
        lines = find_lines_projection(ink_mask(img), self.seg)
        self.assertEqual(len(lines), 1)
        self.assertLessEqual(lines[0][0], 94)


class TestConnectedComponents(unittest.TestCase):
    def test_separate_blobs(self):
        m = np.zeros((50, 50), dtype=bool)
        m[5:15, 5:15] = True
        m[30:40, 30:40] = True
        comps = connected_components(m, min_area=4)
        self.assertEqual(len(comps), 2)

    def test_touching_runs_merge_into_one_component(self):
        m = np.zeros((20, 20), dtype=bool)
        m[5, 2:10] = True
        m[6, 8:16] = True     # overlaps the row above
        comps = connected_components(m, min_area=1)
        self.assertEqual(len(comps), 1)
        self.assertEqual(comps[0].x0, 2)
        self.assertEqual(comps[0].x1, 16)

    def test_min_area_filters_speckle(self):
        m = np.zeros((30, 30), dtype=bool)
        m[10:20, 10:20] = True
        m[1, 1] = True
        self.assertEqual(len(connected_components(m, min_area=5)), 1)

    def test_bullet_and_text_group_into_one_line(self):
        # A bullet glyph is its own component on the same baseline as its text. Grouping by
        # vertical overlap is what keeps list items from becoming two "lines".
        bullet = Box(10, 100, 20, 110)
        text = Box(40, 96, 500, 116)
        lines = group_components_into_lines([bullet, text], overlap_frac=0.30)
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0].x0, 10)
        self.assertEqual(lines[0].x1, 500)

    def test_vertically_separate_components_stay_separate(self):
        a = Box(10, 10, 200, 30)
        b = Box(10, 100, 200, 120)
        self.assertEqual(len(group_components_into_lines([a, b], 0.30)), 2)


class TestSegmentPage(unittest.TestCase):
    def setUp(self):
        self.seg = SegmentationConfig()
        self.fb = FallbackConfig()

    def test_projection_is_preferred(self):
        img = page(500, 800)
        for y in (50, 150, 250, 350):
            draw_line(img, y, 40, 760)
        res = segment_page(img, self.seg, self.fb)
        self.assertEqual(res.method, "projection")
        self.assertEqual(len(res.lines), 4)

    def test_blank_page_falls_back_to_whole_page_and_warns(self):
        res = segment_page(page(300, 500), self.seg, self.fb)
        self.assertEqual(res.method, "whole_page")
        self.assertEqual(len(res.lines), 1)
        self.assertTrue(res.warnings)

    def test_two_column_page_keeps_columns_apart(self):
        img = page(400, 1000)
        for y in (60, 140, 220):
            draw_line(img, y, 50, 420)
            draw_line(img, y, 580, 950)
        res = segment_page(img, self.seg, self.fb)
        self.assertEqual(res.columns, 2)
        self.assertEqual(len(res.lines), 6)
        # No crop may straddle the gutter, which is the failure a bare projection produces.
        for ln in res.lines:
            self.assertFalse(ln.box.x0 < 500 < ln.box.x1)

    def test_partial_empty_page_only_yields_the_text(self):
        img = page(800, 600)
        draw_line(img, 40, 30, 570)
        res = segment_page(img, self.seg, self.fb)
        self.assertEqual(len(res.lines), 1)
        self.assertLess(res.lines[0].box.y1, 200)

    def test_result_is_deterministic(self):
        img = page(500, 800)
        for y in (50, 150, 250):
            draw_line(img, y, 40, 760)
        a = segment_page(img, self.seg, self.fb)
        b = segment_page(img, self.seg, self.fb)
        self.assertEqual([tuple(l.box) for l in a.lines], [tuple(l.box) for l in b.lines])


if __name__ == "__main__":
    unittest.main(verbosity=2)
