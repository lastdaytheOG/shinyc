"""
E1 integrity validation.

Reports only — never edits the dataset. Severity is assigned by how badly a defect corrupts the
resulting measurement, not by how unusual it looks.
"""

from __future__ import annotations

import json
import logging
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image

from .config import SCRIPTS, STRATA, E1Config
from .dataset import TSV_COLUMNS
from .imageops import sha256_file

log = logging.getLogger("e1.validate")


@dataclass
class Finding:
    severity: str    # "error" | "warning"
    code: str
    message: str
    location: str = ""


@dataclass
class ValidationReport:
    findings: list[Finding] = field(default_factory=list)
    rows: int = 0
    transcribed: int = 0
    images_on_disk: int = 0
    by_script: dict[str, int] = field(default_factory=dict)
    by_stratum: dict[str, int] = field(default_factory=dict)

    def add(self, severity: str, code: str, message: str, location: str = "") -> None:
        self.findings.append(Finding(severity, code, message, location))

    @property
    def errors(self) -> list[Finding]:
        return [f for f in self.findings if f.severity == "error"]

    @property
    def warnings(self) -> list[Finding]:
        return [f for f in self.findings if f.severity == "warning"]

    @property
    def ok(self) -> bool:
        return not self.errors

    def to_dict(self) -> dict:
        return {
            "ok": self.ok,
            "rows": self.rows,
            "transcribed": self.transcribed,
            "imagesOnDisk": self.images_on_disk,
            "byScript": self.by_script,
            "byStratum": self.by_stratum,
            "errorCount": len(self.errors),
            "warningCount": len(self.warnings),
            "findings": [
                {"severity": f.severity, "code": f.code, "message": f.message,
                 "location": f.location}
                for f in self.findings
            ],
        }


def derive_script(text: str) -> str:
    """
    Mirrors `OcrScript.derive` in the Android benchmark, so a disagreement flagged here predicts
    the same disagreement there rather than surfacing later as an unexplained metric bucket.
    """
    dev = sum(1 for c in text if 0x0900 <= ord(c) <= 0x097F and c.isalpha())
    lat = sum(1 for c in text if ord(c) < 0x250 and c.isalpha())
    total = dev + lat
    if total == 0:
        return "unknown"
    minority = min(dev, lat) / total
    if dev and lat and minority >= 0.10:
        return "mixed"
    return "hi" if dev >= lat else "en"


def validate(cfg: E1Config, check_hashes: bool = True) -> ValidationReport:
    rep = ValidationReport()
    tsv = cfg.tsv_path

    if not tsv.exists():
        rep.add("error", "missing_tsv", f"ground_truth.tsv not found at {tsv}")
        return rep

    raw = tsv.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        rep.add("error", "bom",
                "ground_truth.tsv has a UTF-8 BOM — strip it (must be UTF-8 without BOM)")
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        rep.add("error", "not_utf8", f"ground_truth.tsv is not valid UTF-8: {exc}")
        return rep

    lines = text.split("\n")
    if lines and lines[0].startswith("image_path\t"):
        header = lines[0].split("\t")
        if header != list(TSV_COLUMNS):
            rep.add("error", "bad_header",
                    f"header is {header}, expected {list(TSV_COLUMNS)}")
        lines = lines[1:]

    seen: dict[str, int] = {}
    labelled: dict[str, tuple[str, str, str]] = {}
    order: list[str] = []

    for offset, line in enumerate(lines):
        lineno = offset + 2
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != len(TSV_COLUMNS):
            rep.add("error", "bad_columns",
                    f"{len(parts)} columns, expected {len(TSV_COLUMNS)}", f"line {lineno}")
            continue

        img, tr, script, stratum = parts[0].strip(), parts[1], parts[2].strip(), parts[3].strip()
        if not img:
            rep.add("error", "empty_path", "empty image_path", f"line {lineno}")
            continue
        if img in seen:
            rep.add("error", "duplicate_path",
                    f"image_path '{img}' repeats (first at line {seen[img]}) — one row silently "
                    f"shadows the other", f"line {lineno}")
            continue
        seen[img] = lineno
        labelled[img] = (tr, script, stratum)
        order.append(img)

        if script and script not in SCRIPTS:
            rep.add("warning", "unknown_script",
                    f"script '{script}' is not one of {list(SCRIPTS)} — forms its own metric bucket",
                    f"line {lineno}")
        if stratum and stratum not in STRATA:
            rep.add("warning", "unknown_stratum",
                    f"stratum '{stratum}' is not one of {list(STRATA)} — forms its own metric bucket",
                    f"line {lineno}")

        if tr.strip():
            if unicodedata.normalize("NFC", tr) != tr:
                rep.add("error", "not_nfc",
                        "transcription is not NFC — the production OCR path NFC-normalizes its "
                        "output, so this scores as wrong even when the OCR is correct",
                        f"line {lineno}")
            got = derive_script(tr)
            if script in SCRIPTS and got != "unknown" and got != script:
                rep.add("warning", "script_mismatch",
                        f"tagged '{script}' but the text looks '{got}'", f"line {lineno}")

    rep.rows = len(labelled)
    rep.transcribed = sum(1 for tr, _, _ in labelled.values() if tr.strip())

    # ── deterministic ordering ─────────────────────────────────────────────
    if order != sorted(order):
        rep.add("warning", "unsorted",
                "rows are not in sorted image_path order — regenerating will reorder the file "
                "and make diffs unreadable")

    # ── image <-> label mapping ────────────────────────────────────────────
    on_disk: set[str] = set()
    if cfg.images_dir.is_dir():
        on_disk = {f"images/{p.name}" for p in sorted(cfg.images_dir.iterdir())
                   if p.is_file() and p.suffix.lower() == ".png"}
    else:
        rep.add("error", "missing_images_dir", f"missing image directory: {cfg.images_dir}")
    rep.images_on_disk = len(on_disk)

    for missing in sorted(set(labelled) - on_disk):
        rep.add("error", "missing_image", f"row references a missing image: {missing}")
    for orphan in sorted(on_disk - set(labelled)):
        rep.add("error", "orphan_image", f"image has no row in ground_truth.tsv: {orphan}")

    # ── image readability ──────────────────────────────────────────────────
    for rel in sorted(set(labelled) & on_disk):
        p = cfg.out_dir / rel
        if p.stat().st_size == 0:
            rep.add("error", "empty_image", f"zero-byte image: {rel}")
            continue
        try:
            with Image.open(p) as im:
                im.verify()
        except Exception as exc:
            rep.add("error", "unreadable_image", f"unreadable image {rel}: {exc}")

    # ── metadata cross-check ───────────────────────────────────────────────
    if cfg.metadata_path.exists():
        try:
            meta = json.loads(cfg.metadata_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            rep.add("error", "bad_metadata", f"metadata.json is not valid JSON: {exc}")
            meta = {}

        recs = {r["image_path"]: r for r in meta.get("lines", [])}
        for rel in sorted(set(labelled) - set(recs)):
            rep.add("warning", "no_metadata", f"image has no metadata entry: {rel}")

        if check_hashes:
            drift = [rel for rel in sorted(set(labelled) & on_disk & set(recs))
                     if sha256_file(cfg.out_dir / rel) != recs[rel]["sha256"]]
            if drift:
                rep.add("error", "hash_drift",
                        f"{len(drift)} image(s) changed since generation (sha256 differs from "
                        f"metadata.json) — regenerate or restore them")

        dups = meta.get("duplicates", {})
        live_exact = [g for g in dups.get("exact", [])
                      if sum(1 for n in g if f"images/{n}.png" in labelled) > 1]
        live_near = [g for g in dups.get("near", [])
                     if sum(1 for n in g if f"images/{n}.png" in labelled) > 1]
        if live_exact:
            rep.add("warning", "exact_duplicates",
                    f"{len(live_exact)} exact-duplicate group(s) still present — repeated lines "
                    f"are measured repeatedly and skew the aggregate")
        if live_near:
            rep.add("warning", "near_duplicates",
                    f"{len(live_near)} near-duplicate group(s) still present")

        whole = [r["image_path"] for r in meta.get("lines", []) if r.get("method") == "whole_page"]
        live_whole = [w for w in whole if w in labelled]
        if live_whole:
            rep.add("warning", "whole_page_fallback",
                    f"{len(live_whole)} whole-page fallback crop(s) still present — these are "
                    f"pages, not lines")
    else:
        rep.add("warning", "no_metadata_file",
                "metadata.json missing — hash, duplicate and method checks skipped")

    # ── label sidecars ─────────────────────────────────────────────────────
    if cfg.labels_dir.is_dir():
        for rel, (tr, _, _) in sorted(labelled.items()):
            lp = cfg.labels_dir / (Path(rel).stem + ".txt")
            if not lp.exists():
                rep.add("warning", "missing_label_file", f"no label sidecar for {rel}")
                continue
            side = lp.read_text(encoding="utf-8-sig").strip()
            if side and tr.strip() and side != tr.strip():
                rep.add("error", "label_divergence",
                        f"labels/{lp.name} and ground_truth.tsv disagree — the TSV is canonical; "
                        f"reconcile with --adopt-labels or clear the sidecar", rel)
            elif side and not tr.strip():
                rep.add("warning", "label_only",
                        f"labels/{lp.name} has text but the TSV row is blank — run --adopt-labels",
                        rel)

    # ── coverage ───────────────────────────────────────────────────────────
    for tr, script, stratum in labelled.values():
        if tr.strip():
            rep.by_script[script or "(untagged)"] = rep.by_script.get(script or "(untagged)", 0) + 1
            rep.by_stratum[stratum or "(untagged)"] = rep.by_stratum.get(stratum or "(untagged)", 0) + 1

    if rep.transcribed < rep.rows:
        rep.add("warning", "incomplete",
                f"{rep.rows - rep.transcribed} of {rep.rows} rows still untranscribed")

    return rep


def adopt_labels(cfg: E1Config) -> int:
    """
    Copy non-empty `labels/*.txt` sidecars into the TSV transcription column.

    Explicit and opt-in: the TSV is canonical, so pulling text in from elsewhere must be a
    deliberate act rather than something that quietly happens during validation.
    """
    tsv = cfg.tsv_path
    lines = tsv.read_text(encoding="utf-8-sig").split("\n")
    out: list[str] = []
    changed = 0
    for i, line in enumerate(lines):
        if i == 0 or not line.strip():
            out.append(line)
            continue
        parts = line.split("\t")
        if len(parts) != len(TSV_COLUMNS):
            out.append(line)
            continue
        lp = cfg.labels_dir / (Path(parts[0]).stem + ".txt")
        if lp.exists():
            side = lp.read_text(encoding="utf-8-sig").strip()
            if side and side != parts[1].strip():
                parts[1] = unicodedata.normalize("NFC", side)
                changed += 1
        out.append("\t".join(parts))
    tsv.write_text("\n".join(out), encoding="utf-8", newline="\n")
    return changed
