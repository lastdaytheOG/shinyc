"""
Text-line segmentation: column split → horizontal projection → connected-components fallback.

Geometry only. This module locates where lines *are*; it never reads them.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import NamedTuple

import numpy as np

from .config import FallbackConfig, SegmentationConfig
from .imageops import ink_mask

log = logging.getLogger("e1.segment")


class Box(NamedTuple):
    """Axis-aligned box in page pixels, half-open on the far edge."""
    x0: int
    y0: int
    x1: int
    y1: int

    @property
    def width(self) -> int:
        return self.x1 - self.x0

    @property
    def height(self) -> int:
        return self.y1 - self.y0


@dataclass
class LineRegion:
    box: Box
    column: int
    line_in_column: int
    method: str          # "projection" | "components" | "whole_page"


@dataclass
class PageSegmentation:
    lines: list[LineRegion]
    method: str
    columns: int
    warnings: list[str]


# ── Column segmentation ─────────────────────────────────────────────────────────

def find_columns(mask: np.ndarray, min_gutter_frac: float, min_col_frac: float,
                 ink_tolerance: float = 0.0) -> list[Box]:
    """
    Split a page into column bands on full-height vertical whitespace gutters.

    Runs BEFORE line detection, and is what makes multi-column pages work at all: a horizontal
    projection across a two-column page sees ink from both columns on the same rows, merging them
    into one band per physical row and producing "lines" that interleave two unrelated columns.
    Requiring a gutter to be clear over the entire page height keeps ordinary word spacing from
    triggering a split.

    `ink_tolerance` is the fraction of rows a gutter may still contain ink in. At zero, a single
    spanning element — a masthead, a centred headline, a full-width rule — puts ink in the gutter
    column and vetoes the split for the whole page, which is the common journal layout rather than
    an edge case. Keep it small: the full-height requirement, not the tolerance, is what stops word
    spacing being mistaken for a gutter.
    """
    h, w = mask.shape
    col_ink = mask.sum(axis=0)
    content = np.flatnonzero(col_ink > 0)
    if content.size == 0:
        return []
    x0, x1 = int(content[0]), int(content[-1]) + 1

    min_gutter = max(4, int(w * min_gutter_frac))
    min_col = max(8, int(w * min_col_frac))
    ink_limit = h * ink_tolerance

    cols: list[Box] = []
    start = x0
    run_start: int | None = None
    for x in range(x0, x1 + 1):
        empty = x < x1 and col_ink[x] <= ink_limit
        if empty:
            if run_start is None:
                run_start = x
        else:
            if run_start is not None and (x - run_start) >= min_gutter:
                if run_start - start >= min_col:
                    cols.append(Box(start, 0, run_start, h))
                start = x
            run_start = None
    if x1 - start >= min_col:
        cols.append(Box(start, 0, x1, h))
    return cols or [Box(x0, 0, x1, h)]


# ── Projection profile ──────────────────────────────────────────────────────────

def runs_of_true(flags: np.ndarray) -> list[tuple[int, int]]:
    """Contiguous True runs as half-open [start, end) pairs."""
    if flags.size == 0:
        return []
    padded = np.concatenate(([0], flags.astype(np.int8), [0]))
    edges = np.flatnonzero(np.diff(padded))
    return [(int(s), int(e)) for s, e in zip(edges[0::2], edges[1::2])]


def find_lines_projection(mask: np.ndarray, cfg: SegmentationConfig) -> list[tuple[int, int]]:
    """
    Horizontal projection → line bands, with a merge pass tuned for Devanagari.

    The merge pass is not optional for Hindi. Vowel signs sit above the shirorekha (ि ी े ै ो ौ ं ँ)
    and below it (ु ू ृ), frequently separated from the main body by a row or two of white. Without
    merging they become their own "lines", and — worse — the real line is cropped with its matras
    sliced off, silently changing the word that gets transcribed.
    """
    if mask.size == 0:
        return []
    row_ink = mask.sum(axis=1)
    peak = int(row_ink.max()) if row_ink.size else 0
    if peak == 0:
        return []

    bands = runs_of_true(row_ink > max(1.0, peak * cfg.row_ink_frac))
    if not bands:
        return []

    # Estimate a typical line height from SUBSTANTIVE bands only. Speckle and stray 1-2px bands
    # would otherwise dominate the median, collapsing the merge gap to its floor so the merge pass
    # silently does nothing — after which the min-height filter discards every real line.
    heights = sorted(b - a for a, b in bands)
    substantive = [h for h in heights if h >= 3]
    basis = substantive or heights
    median_h = basis[len(basis) // 2]
    merge_gap = max(2, int(median_h * cfg.merge_gap_factor))

    # Ceiling on the merged band. Merging is transitive, so without one each merge extends the
    # band and can keep swallowing its neighbour: on tightly-leaded text that chained until a
    # single band covered most of the page, which the height filter below then discarded — the
    # lines were found, fused, and dropped. The cap stops the chain at a plausible line height
    # while still letting a matra, a descender or a two-line table cell join its body.
    cap = median_h * cfg.merge_cap_factor if cfg.merge_cap_factor > 0 else None

    merged: list[list[int]] = [list(bands[0])]
    for a, b in bands[1:]:
        last = merged[-1]
        if a - last[1] <= merge_gap and (cap is None or (b - last[0]) <= cap):
            last[1] = b
        else:
            merged.append([a, b])

    max_h = int(mask.shape[0] * cfg.max_line_height_frac)
    return [(a, b) for a, b in merged if cfg.min_line_height <= (b - a) <= max(max_h, cfg.min_line_height + 1)]


# ── Connected components (fallback) ─────────────────────────────────────────────

def connected_components(mask: np.ndarray, min_area: int) -> list[Box]:
    """
    8-connected components via run-length union-find.

    Run-based rather than per-pixel: a 1240x1754 page is ~2.2M pixels but only a few thousand
    horizontal ink runs, so this stays fast in pure Python/NumPy without pulling in SciPy or
    OpenCV — neither of which this workbench should require on a research machine.
    """
    h, w = mask.shape
    parent: list[int] = []

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            # Lower id always becomes the root, so component identity is deterministic.
            lo, hi = (ra, rb) if ra < rb else (rb, ra)
            parent[hi] = lo

    all_runs: list[tuple[int, int, int, int]] = []   # (y, start, end, id)
    prev: list[tuple[int, int, int]] = []            # (start, end, id)

    for y in range(h):
        cur: list[tuple[int, int, int]] = []
        for s, e in runs_of_true(mask[y]):
            rid = len(parent)
            parent.append(rid)
            cur.append((s, e, rid))
            all_runs.append((y, s, e, rid))

        # Two-pointer overlap merge; both run lists are sorted by start.
        i = j = 0
        while i < len(cur) and j < len(prev):
            cs, ce, cid = cur[i]
            ps, pe, pid = prev[j]
            # 8-connectivity: runs touching only at a diagonal corner still connect.
            if ps < ce and cs < pe:
                union(cid, pid)
            if ce < pe:
                i += 1
            else:
                j += 1
        prev = cur

    boxes: dict[int, list[int]] = {}
    areas: dict[int, int] = {}
    for y, s, e, rid in all_runs:
        r = find(rid)
        if r in boxes:
            b = boxes[r]
            b[0] = min(b[0], s)
            b[1] = min(b[1], y)
            b[2] = max(b[2], e)
            b[3] = max(b[3], y + 1)
            areas[r] += e - s
        else:
            boxes[r] = [s, y, e, y + 1]
            areas[r] = e - s

    out = [Box(*b) for r, b in boxes.items() if areas[r] >= min_area]
    return sorted(out, key=lambda b: (b.y0, b.x0))


def group_components_into_lines(comps: list[Box], overlap_frac: float) -> list[Box]:
    """
    Cluster components into text lines by vertical overlap.

    This is what makes the fallback handle layouts a projection profile struggles with:
    bullet glyphs and list numbers are separate components sitting on the same baseline as their
    text, and short headings beside figures do not span a full row. Grouping by vertical overlap
    keeps them on one line instead of emitting each fragment separately.
    """
    if not comps:
        return []
    ordered = sorted(comps, key=lambda b: (b.y0, b.x0))
    lines: list[list[Box]] = [[ordered[0]]]

    for box in ordered[1:]:
        cur = lines[-1]
        top = min(b.y0 for b in cur)
        bot = max(b.y1 for b in cur)
        inter = min(bot, box.y1) - max(top, box.y0)
        smaller = max(1, min(bot - top, box.height))
        if inter > 0 and (inter / smaller) >= overlap_frac:
            cur.append(box)
        else:
            lines.append([box])

    merged: list[Box] = []
    for grp in lines:
        merged.append(Box(
            min(b.x0 for b in grp), min(b.y0 for b in grp),
            max(b.x1 for b in grp), max(b.y1 for b in grp),
        ))
    return merged


# ── Page-level orchestration ────────────────────────────────────────────────────

def _plausible(regions: list[LineRegion], mask_h: int, seg: SegmentationConfig,
               fb: FallbackConfig) -> bool:
    """A segmentation is implausible if it found nothing, too much, or one all-covering band."""
    if not regions:
        return False
    if len(regions) > seg.max_lines_per_page:
        return False
    if len(regions) == 1:
        only = regions[0].box
        if only.height >= mask_h * fb.projection_merged_frac:
            return False
    return True


def segment_page(gray: np.ndarray, seg: SegmentationConfig,
                 fb: FallbackConfig) -> PageSegmentation:
    """
    Locate text lines on a page.

    Strategy ladder, each step used only when the previous produced implausible output:
      1. projection profile at the default threshold
      2. projection profile at a looser threshold (faint or low-contrast scans)
      3. connected components (irregular layouts: bullets, numbered lists, mixed spacing)
      4. whole page, flagged — a human can act on a flagged page, but silently emitting nothing
         is invisible and silently emitting garbage is worse
    """
    warnings: list[str] = []
    h, w = gray.shape

    for attempt, bias in enumerate((0, seg.retry_bias)):
        mask = ink_mask(gray, seg.paper_percentile, seg.paper_factor, bias)
        if not mask.any():
            continue
        columns = find_columns(mask, seg.min_gutter_frac, seg.min_col_frac,
                               seg.gutter_ink_tolerance)
        regions: list[LineRegion] = []
        for ci, col in enumerate(columns):
            sub = mask[:, col.x0:col.x1]
            for li, (y0, y1) in enumerate(find_lines_projection(sub, seg)):
                regions.append(LineRegion(
                    box=Box(col.x0, y0, col.x1, y1),
                    column=ci, line_in_column=li, method="projection",
                ))
        if _plausible(regions, h, seg, fb):
            if attempt > 0:
                warnings.append("needed a loosened threshold — check these crops")
            return PageSegmentation(regions, "projection", len(columns), warnings)

    # ── fallback: connected components ──────────────────────────────────────
    if fb.enable_connected_components:
        mask = ink_mask(gray, seg.paper_percentile, seg.paper_factor, seg.retry_bias)
        if mask.any():
            comps = connected_components(mask, fb.cc_min_area)
            columns = find_columns(mask, seg.min_gutter_frac, seg.min_col_frac,
                                   seg.gutter_ink_tolerance)
            regions = []
            for ci, col in enumerate(columns):
                in_col = [c for c in comps if c.x0 >= col.x0 and c.x1 <= col.x1]
                max_h = max(int(h * seg.max_line_height_frac), seg.min_line_height + 1)
                for li, box in enumerate(group_components_into_lines(in_col, fb.cc_vertical_overlap)):
                    if seg.min_line_height <= box.height <= max_h:
                        regions.append(LineRegion(box=box, column=ci, line_in_column=li,
                                                  method="components"))
            if _plausible(regions, h, seg, fb):
                warnings.append("projection failed — segmented by connected components")
                return PageSegmentation(regions, "components", len(columns), warnings)

    warnings.append("no lines detected — emitted WHOLE PAGE for manual handling")
    return PageSegmentation(
        [LineRegion(box=Box(0, 0, w, h), column=0, line_in_column=0, method="whole_page")],
        "whole_page", 1, warnings,
    )
