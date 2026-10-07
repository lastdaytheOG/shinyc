"""Corpus statistics over a generated E1 dataset."""

from __future__ import annotations

import json
import statistics
from dataclasses import dataclass, field
from pathlib import Path

from .config import E1Config
from .dataset import TSV_COLUMNS


@dataclass
class E1Stats:
    pages: int = 0
    lines: int = 0
    books: int = 0
    transcribed: int = 0
    lines_per_script: dict[str, int] = field(default_factory=dict)
    lines_per_stratum: dict[str, int] = field(default_factory=dict)
    lines_per_book: dict[str, int] = field(default_factory=dict)
    lines_per_method: dict[str, int] = field(default_factory=dict)
    duplicate_count: int = 0
    exact_duplicate_groups: int = 0
    near_duplicate_groups: int = 0
    average_line_height: float = 0.0
    average_line_width: float = 0.0
    median_line_height: float = 0.0
    median_line_width: float = 0.0
    min_line_width: int = 0
    max_line_width: int = 0
    average_width_h48: float = 0.0
    #: width histogram of the 48px-normalized crops — this is the input to the recognizer
    #: bucket decision (asset guide §4.3), so it is computed here rather than eyeballed later.
    width_h48_histogram: dict[str, int] = field(default_factory=dict)

    def to_dict(self) -> dict:
        d = self.__dict__.copy()
        return d


_BUCKET_EDGES = (160, 240, 320, 480, 640, 960)


def _histogram(widths: list[int]) -> dict[str, int]:
    hist: dict[str, int] = {}
    for w in widths:
        label = None
        prev = 0
        for edge in _BUCKET_EDGES:
            if w <= edge:
                label = f"{prev + 1}-{edge}"
                break
            prev = edge
        hist[label or f">{_BUCKET_EDGES[-1]}"] = hist.get(label or f">{_BUCKET_EDGES[-1]}", 0) + 1
    return dict(sorted(hist.items(), key=lambda kv: (len(kv[0]), kv[0])))


def compute_stats(cfg: E1Config) -> E1Stats:
    st = E1Stats()

    meta_path = cfg.metadata_path
    records: list[dict] = []
    if meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        records = meta.get("lines", [])
        st.pages = int(meta.get("counts", {}).get("pages", 0))
        dups = meta.get("duplicates", {})
        st.exact_duplicate_groups = len(dups.get("exact", []))
        st.near_duplicate_groups = len(dups.get("near", []))
        # Count members beyond the first in each group: that is how many lines are redundant.
        st.duplicate_count = sum(max(0, len(g) - 1)
                                 for g in dups.get("exact", []) + dups.get("near", []))

    st.lines = len(records)
    st.books = len({r.get("source_book", "") for r in records}) if records else 0

    for r in records:
        book = r.get("source_book", "(unknown)")
        method = r.get("method", "(unknown)")
        st.lines_per_book[book] = st.lines_per_book.get(book, 0) + 1
        st.lines_per_method[method] = st.lines_per_method.get(method, 0) + 1

    heights = [int(r["height"]) for r in records if "height" in r]
    widths = [int(r["width"]) for r in records if "width" in r]
    w48 = [int(r["width_h48"]) for r in records if "width_h48" in r]

    if heights:
        st.average_line_height = round(statistics.fmean(heights), 2)
        st.median_line_height = float(statistics.median(heights))
    if widths:
        st.average_line_width = round(statistics.fmean(widths), 2)
        st.median_line_width = float(statistics.median(widths))
        st.min_line_width = min(widths)
        st.max_line_width = max(widths)
    if w48:
        st.average_width_h48 = round(statistics.fmean(w48), 2)
        st.width_h48_histogram = _histogram(w48)

    # script / stratum come from the TSV, which is canonical and hand-edited after generation.
    tsv = cfg.tsv_path
    if tsv.exists():
        lines = tsv.read_text(encoding="utf-8-sig").split("\n")
        if lines and lines[0].startswith("image_path\t"):
            lines = lines[1:]
        for line in lines:
            if not line.strip():
                continue
            parts = line.split("\t")
            if len(parts) != len(TSV_COLUMNS):
                continue
            tr, script, stratum = parts[1], parts[2].strip(), parts[3].strip()
            st.lines_per_script[script or "(untagged)"] = \
                st.lines_per_script.get(script or "(untagged)", 0) + 1
            st.lines_per_stratum[stratum or "(untagged)"] = \
                st.lines_per_stratum.get(stratum or "(untagged)", 0) + 1
            if tr.strip():
                st.transcribed += 1

    return st


def write_stats(cfg: E1Config, st: E1Stats) -> Path:
    path = cfg.stats_path
    path.write_text(json.dumps(st.to_dict(), ensure_ascii=False, indent=2), encoding="utf-8")
    return path
