"""Binarization, resizing and hashing. Pure image maths — no recognition of any kind."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image


def to_gray(img: Image.Image) -> np.ndarray:
    return np.asarray(img.convert("L"), dtype=np.uint8)


def otsu_threshold(gray: np.ndarray) -> int:
    """Otsu's method — used only as a floor for unusually dark pages (see [ink_mask])."""
    hist = np.bincount(gray.ravel(), minlength=256).astype(np.float64)
    total = float(gray.size)
    sum_total = float(np.dot(np.arange(256), hist))
    w_b = 0.0
    sum_b = 0.0
    best_var, best_t = -1.0, 127
    for t in range(256):
        w_b += hist[t]
        if w_b == 0:
            continue
        w_f = total - w_b
        if w_f <= 0:
            break
        sum_b += t * hist[t]
        m_b = sum_b / w_b
        m_f = (sum_total - sum_b) / w_f
        var = w_b * w_f * (m_b - m_f) ** 2
        if var > best_var:
            best_var, best_t = var, t
    return best_t


def ink_mask(gray: np.ndarray, paper_percentile: float = 90.0,
             paper_factor: float = 0.85, bias: int = 0) -> np.ndarray:
    """
    True where ink is, thresholded relative to the **paper white point**.

    Otsu is deliberately not the primary here. It assumes two comparably-sized pixel classes, but a
    rendered text page is 98-99% paper. Measured on a 150 dpi Devanagari page, Otsu chose 135,
    which kept only fully-saturated pixels — that is the shirorekha (a solid horizontal bar) and
    nothing else, so every text line collapsed to a 1-pixel band and segmentation failed outright.
    The same page thresholded at 160+ produced correct 17-19 px bands.

    Thin Devanagari stems at typical render resolutions are mostly partial-coverage grey, so a
    threshold tuned to solid black discards the glyph body and keeps only its heaviest stroke.
    Thresholding against the paper level keeps the body, which is what a projection profile needs.

    Assumes dark text on light paper — true by construction for rendered PDF pages, the only input
    this pipeline accepts. Otsu is retained as a floor so unusually dark scans still binarize.
    """
    paper = float(np.percentile(gray, paper_percentile))
    thr = max(int(paper * paper_factor) + bias, otsu_threshold(gray))
    return gray < max(40, min(250, thr))


def normalize_height(img: Image.Image, target_h: int) -> Image.Image:
    """Resize to `target_h` preserving aspect ratio. LANCZOS: crops are usually downscaled."""
    w, h = img.size
    if h == 0:
        return img
    new_w = max(1, round(w * target_h / h))
    return img.resize((new_w, target_h), Image.LANCZOS)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    """
    Digest of the encoded image before it is written.

    Hashing the bytes rather than re-reading the file is what lets an exact duplicate be
    recognised and skipped without ever being written — a write-then-delete would leave the
    sequential line numbering with holes in it.
    """
    return hashlib.sha256(data).hexdigest()


# Difference-hash grid, in cells. Deliberately WIDE, because what is being hashed is a text line.
#
# The conventional 8x8 photo hash is unusable here. Line crops in this corpus run 20:1 to 58:1,
# so an 8x8 grid squashes a 700x28 line into 9x8 and averages every glyph away: measured, that
# collapses ordinary body text onto a handful of hashes like c0c0c0c0c0c0c0c0, and 31 of 99
# reported groups then spanned multiple books — provably wrong, since two books cannot share a
# rendered line. At 32x8 the horizontal structure survives and, at the matching threshold in
# [DedupConfig], no cross-book group is reported at all.
#
# Note the direction of the failure: it is WIDE crops that lose their signal to a square grid,
# not short ones. A minimum-size floor does not address it — the crops involved were all well
# above any plausible floor.
DHASH_COLS = 32
DHASH_ROWS = 8
DHASH_BITS = DHASH_COLS * DHASH_ROWS


def dhash(img: Image.Image, cols: int = DHASH_COLS, rows: int = DHASH_ROWS) -> int:
    """
    Difference hash over a `cols` x `rows` grid — `cols * rows` bits.

    Exact hashing alone is not enough for duplicate detection: repeated running headers are
    re-rendered per page and differ by a pixel or two, so they are byte-distinct but visually
    identical. A perceptual hash catches them; SHA-256 never would.
    """
    g = np.asarray(img.convert("L").resize((cols + 1, rows), Image.LANCZOS), dtype=np.int16)
    bits = (g[:, 1:] > g[:, :-1]).flatten()
    value = 0
    for b in bits:
        value = (value << 1) | int(b)
    return value


def hamming(a: int, b: int) -> int:
    return bin(a ^ b).count("1")


@dataclass(frozen=True)
class Signature:
    """
    Perceptual signature of a line crop: difference hash + mean luminance + aspect ratio.

    dHash alone is not sufficient. It encodes only *adjacent-pixel differences*, so any
    low-variance image hashes to all zeros — a blank white crop and a solid black crop compare as
    identical. Blank and near-blank crops are exactly what a marginal segmentation produces, so
    relying on dHash by itself would silently group unrelated failures into one "duplicate" set.
    Mean luminance separates them, and aspect ratio stops a short heading matching a full-width
    body line that happens to share a stroke rhythm.

    The aspect gate is a *relative* test between two crops, which is the one that matters here.
    An absolute minimum-size floor was tried and measured to do nothing: the crops producing
    false matches were aspect 21 to 58, far above any floor worth setting. The fix that worked
    was widening the hash grid — see [dhash].
    """
    dhash: int
    mean: int
    aspect: float

    def similar(self, other: "Signature", max_distance: int,
                mean_tolerance: int = 12, aspect_tolerance: float = 0.25) -> bool:
        if hamming(self.dhash, other.dhash) > max_distance:
            return False
        if abs(self.mean - other.mean) > mean_tolerance:
            return False
        lo, hi = sorted((self.aspect, other.aspect))
        if hi <= 0:
            return True
        return (hi - lo) / hi <= aspect_tolerance


def signature(img: Image.Image) -> Signature:
    gray = img.convert("L")
    arr = np.asarray(gray, dtype=np.float64)
    aspect = (img.width / img.height) if img.height else 0.0
    return Signature(dhash=dhash(img), mean=int(round(float(arr.mean()))), aspect=aspect)
