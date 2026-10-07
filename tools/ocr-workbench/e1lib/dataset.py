"""
Dataset assembly: staging discovery, deterministic naming, and writing the E1 artifacts.

Writes images/, labels/, ground_truth.tsv and metadata.json. Transcriptions are always written
**blank** — see the package docstring for why that is a correctness requirement, not a stylistic one.
"""

from __future__ import annotations

import io
import json
import logging
import re
import time
from dataclasses import dataclass, asdict, field
from pathlib import Path

from PIL import Image

from .config import E1Config
from .imageops import (
    DHASH_BITS, Signature, normalize_height, sha256_bytes, signature, to_gray,
)
from .segmentation import segment_page

log = logging.getLogger("e1.dataset")

IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp")

TSV_COLUMNS = (
    "image_path", "transcription", "script", "stratum", "source_book", "page", "line",
)

_PAGE_NUM = re.compile(r"(\d+)")


@dataclass
class StagedPage:
    path: Path
    book: str
    page: int

    @property
    def sort_key(self) -> tuple:
        # (book, page, filename) — page number first so page_2 precedes page_10, which plain
        # lexical sorting would reverse and silently scramble reading order in the dataset.
        return (self.book.lower(), self.page, self.path.name.lower())


@dataclass
class LineRecord:
    line_id: str
    image_path: str
    label_path: str
    source_book: str
    source_page_file: str
    page: int
    line: int
    column: int
    method: str
    bbox: list[int]
    width: int
    height: int
    width_h48: int
    height_h48: int
    sha256: str
    dhash: str
    script: str
    stratum: str


@dataclass
class DroppedDuplicate:
    """A crop that was byte-identical to one already written, and the line that kept it."""
    kept_line_id: str
    source_book: str
    source_page_file: str
    page: int
    line: int


@dataclass
class BuildResult:
    records: list[LineRecord] = field(default_factory=list)
    pages: list[StagedPage] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    exact_duplicates: list[list[str]] = field(default_factory=list)
    near_duplicates: list[list[str]] = field(default_factory=list)
    failed_pages: list[str] = field(default_factory=list)
    dropped_duplicates: list[DroppedDuplicate] = field(default_factory=list)
    excluded_pages: list[StagedPage] = field(default_factory=list)


# ── Staging discovery ───────────────────────────────────────────────────────────

def parse_page_number(name: str) -> int:
    """Last integer in the filename; 0 when absent. `page_0007.png` → 7."""
    matches = _PAGE_NUM.findall(Path(name).stem)
    return int(matches[-1]) if matches else 0


def discover_pages(staging: Path) -> list[StagedPage]:
    """
    Find pages under `staging`, treating each immediate subdirectory as a source book.

    Pages placed directly in the staging root are attributed to the book `_unsorted`, so a
    flat drop still works rather than being silently ignored.
    """
    if not staging.is_dir():
        raise FileNotFoundError(f"staging directory not found: {staging}")

    pages: list[StagedPage] = []
    for path in staging.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in IMAGE_SUFFIXES:
            continue
        rel = path.relative_to(staging)
        book = rel.parts[0] if len(rel.parts) > 1 else "_unsorted"
        pages.append(StagedPage(path=path, book=book, page=parse_page_number(path.name)))

    return sorted(pages, key=lambda p: p.sort_key)


def partition_excluded(cfg: E1Config,
                       pages: list[StagedPage]) -> tuple[list[StagedPage], list[StagedPage]]:
    """
    Split discovered pages into (kept, excluded) per the `exclude` lists in [books].

    Separated from `build_lines` so the caller can report the exclusions. A page dropped
    silently is indistinguishable from a page that was never staged, and the difference
    matters: one is a decision on the record, the other is a missing file.
    """
    kept, excluded = [], []
    for p in pages:
        (excluded if cfg.is_excluded(p.book, p.page) else kept).append(p)
    return kept, excluded


# ── Build ───────────────────────────────────────────────────────────────────────

def build_lines(cfg: E1Config, pages: list[StagedPage],
                progress_iter=None) -> BuildResult:
    """Crop every detected line, write both image variants, and collect records."""
    pages, excluded = partition_excluded(cfg, pages)
    result = BuildResult(pages=pages, excluded_pages=excluded)
    cfg.images_dir.mkdir(parents=True, exist_ok=True)
    if cfg.dataset.write_label_files:
        cfg.labels_dir.mkdir(parents=True, exist_ok=True)

    h48_dir = cfg.out_dir / "images_h48"
    h48_dir.mkdir(parents=True, exist_ok=True)

    signatures: list[tuple[str, Signature]] = []
    # sha-256 of every crop written so far -> the line that owns it, so a repeat can name it.
    seen: dict[str, str] = {}
    index = 1
    iterator = progress_iter(pages) if progress_iter else pages

    for sp in iterator:
        try:
            img = Image.open(sp.path).convert("RGB")
        except Exception as exc:
            log.warning("failed to open %s: %s", sp.path, exc)
            result.failed_pages.append(str(sp.path))
            result.warnings.append(f"{sp.path.name}: could not be opened ({exc})")
            continue

        try:
            seg = segment_page(to_gray(img), cfg.segmentation, cfg.fallback)
        except Exception as exc:
            log.warning("segmentation failed for %s: %s", sp.path, exc)
            result.failed_pages.append(str(sp.path))
            result.warnings.append(f"{sp.path.name}: segmentation failed ({exc})")
            continue

        for w in seg.warnings:
            result.warnings.append(f"{sp.book}/{sp.path.name}: {w}")

        # Labels resolve page -> book -> [defaults]. One run covers the whole staging tree, so a
        # single global default cannot be right for a corpus that mixes scripts and strata; and
        # stratum in particular is not even a book-level property, since ruled tables sit inside
        # otherwise-prose books and have to be labelled where they actually are.
        line_script, line_stratum = cfg.labels_for(sp.book, sp.page)

        pad = cfg.segmentation.pad
        for line_no, region in enumerate(seg.lines, start=1):
            b = region.box
            box = (max(0, b.x0 - pad), max(0, b.y0 - pad),
                   min(img.width, b.x1 + pad), min(img.height, b.y1 + pad))
            crop = img.crop(box)
            if crop.width < 1 or crop.height < 1:
                continue

            # Encode once, in memory: the digest has to be known BEFORE the file is written,
            # because a duplicate must not consume a line number. Writing and then deleting
            # would leave gaps in a sequence that is meant to be contiguous.
            buf = io.BytesIO()
            crop.save(buf, format="PNG", optimize=True)
            data = buf.getvalue()
            digest = sha256_bytes(data)

            kept_by = seen.get(digest)
            if kept_by is not None and cfg.dedup.drop_exact_duplicates:
                result.dropped_duplicates.append(DroppedDuplicate(
                    kept_line_id=kept_by, source_book=sp.book,
                    source_page_file=sp.path.name, page=sp.page, line=line_no))
                continue

            name = f"{cfg.dataset.image_prefix}{index:0{cfg.dataset.image_digits}d}"
            img_rel = f"images/{name}.png"
            (cfg.out_dir / img_rel).write_bytes(data)
            seen.setdefault(digest, name)

            h48 = normalize_height(crop, cfg.segmentation.target_height)
            h48.save(h48_dir / f"{name}.png", format="PNG", optimize=True)

            label_rel = f"labels/{name}.txt"
            if cfg.dataset.write_label_files:
                # Blank by construction. Never seeded from anything.
                (cfg.out_dir / label_rel).write_text("", encoding="utf-8")

            sig = signature(crop)
            signatures.append((name, sig))
            result.records.append(LineRecord(
                line_id=name,
                image_path=img_rel,
                label_path=label_rel if cfg.dataset.write_label_files else "",
                source_book=sp.book,
                source_page_file=sp.path.name,
                page=sp.page,
                line=line_no,
                column=region.column,
                method=region.method,
                bbox=[box[0], box[1], box[2], box[3]],
                width=crop.width, height=crop.height,
                width_h48=h48.width, height_h48=h48.height,
                sha256=digest,
                dhash=f"{sig.dhash:0{DHASH_BITS // 4}x}",
                script=line_script,
                stratum=line_stratum,
            ))
            index += 1

    result.exact_duplicates, result.near_duplicates = detect_duplicates(
        result.records, signatures, cfg.dedup.near_dup_distance)
    return result


def detect_duplicates(records: list[LineRecord], signatures: list[tuple[str, Signature]],
                      max_distance: int) -> tuple[list[list[str]], list[list[str]]]:
    """
    Exact (SHA-256) and near (perceptual) duplicate groups.

    Repeated running headers and footers are the common case for EXACT matches and they are not
    harmless: a set where 30 of 200 lines are the same header measures that header 30 times,
    dragging the aggregate CER toward whatever it happens to score and starving the strata that
    actually matter. With `drop_exact_duplicates` on, `build_lines` never writes them, so the
    exact list returned here is empty by construction and its emptiness is the invariant.

    NEAR matches are reported, never acted on. Two lines can be near-identical to a perceptual
    hash and still be the most valuable pair in the set — a table column of codes reading
    115BBG / 115BBH / 115BBI is precisely what a recognizer has to get right, and dropping one
    would remove the evidence that it cannot. Matching uses a composite [Signature] rather than
    a bare dHash; see its docstring for why featureless crops would otherwise collide, and
    [dhash] for why the grid has to be line-shaped for the claim to mean anything.
    """
    by_sha: dict[str, list[str]] = {}
    for r in records:
        by_sha.setdefault(r.sha256, []).append(r.line_id)
    exact = sorted((sorted(v) for v in by_sha.values() if len(v) > 1))

    near: list[list[str]] = []
    claimed: set[str] = set()
    for i, (name_a, sig_a) in enumerate(signatures):
        if name_a in claimed:
            continue
        group = [name_a]
        for name_b, sig_b in signatures[i + 1:]:
            if name_b not in claimed and sig_a.similar(sig_b, max_distance):
                group.append(name_b)
                claimed.add(name_b)
        if len(group) > 1:
            claimed.add(name_a)
            near.append(sorted(group))
    return exact, sorted(near)


# ── Writers ─────────────────────────────────────────────────────────────────────

def write_tsv(cfg: E1Config, records: list[LineRecord]) -> Path:
    """UTF-8, LF, tab-separated, header row. Transcription column intentionally empty."""
    path = cfg.tsv_path
    with path.open("w", encoding="utf-8", newline="\n") as f:
        f.write("\t".join(TSV_COLUMNS) + "\n")
        for r in records:
            f.write("\t".join([
                r.image_path, "", r.script, r.stratum,
                r.source_book, str(r.page), str(r.line),
            ]) + "\n")
    return path


def write_metadata(cfg: E1Config, result: BuildResult) -> Path:
    payload = {
        "schemaVersion": 1,
        "generatedAtMs": int(time.time() * 1000),
        "generator": "08_make_lines.py",
        "datasetKind": "evaluation",
        "groundTruthPolicy": (
            "Human transcription only. Never OCR-, model- or heuristic-generated. "
            "Seeding transcriptions from any recognizer would make that recognizer's errors the "
            "reference and invalidate every comparison this dataset exists to make."
        ),
        "config": cfg.to_dict(),
        "columns": list(TSV_COLUMNS),
        "counts": {
            "books": len({p.book for p in result.pages}),
            "pages": len(result.pages),
            "lines": len(result.records),
            "failedPages": len(result.failed_pages),
            "excludedPages": len(result.excluded_pages),
            "droppedExactDuplicates": len(result.dropped_duplicates),
        },
        "sourcePages": [
            {"book": p.book, "file": p.path.name, "page": p.page} for p in result.pages
        ],
        # Staged but deliberately not in the dataset. Recorded so the gap between what is on
        # disk and what is measured is auditable rather than something to rediscover.
        "excludedPages": [
            {"book": p.book, "file": p.path.name, "page": p.page} for p in result.excluded_pages
        ],
        "duplicates": {"exact": result.exact_duplicates, "near": result.near_duplicates},
        "droppedExactDuplicates": [asdict(d) for d in result.dropped_duplicates],
        "failedPages": result.failed_pages,
        "warnings": result.warnings,
        "lines": [asdict(r) for r in result.records],
    }
    path = cfg.metadata_path
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def count_transcribed(tsv_path: Path) -> int:
    """How many rows already carry a transcription — used to guard against clobbering work."""
    if not tsv_path.exists():
        return 0
    n = 0
    for i, line in enumerate(tsv_path.read_text(encoding="utf-8-sig").splitlines()):
        if i == 0 and line.startswith("image_path\t"):
            continue
        parts = line.split("\t")
        if len(parts) > 1 and parts[1].strip():
            n += 1
    return n
