"""Logging and progress reporting. No hard third-party dependency."""

from __future__ import annotations

import logging
import os
import sys
import time
from collections.abc import Iterable, Iterator
from pathlib import Path
from typing import TypeVar

T = TypeVar("T")

_LOG_FORMAT = "%(asctime)s %(levelname)-7s %(name)-14s %(message)s"
_DATE_FORMAT = "%H:%M:%S"


def ensure_utf8_output() -> None:
    """
    Force stdout/stderr to UTF-8.

    Required for cross-platform operation, not cosmetic. A default Windows console is cp1252, and
    this tooling prints Devanagari transcriptions, book paths and typographic dashes — writing any
    of those to a cp1252 stream raises UnicodeEncodeError and kills the run. It bit `--help`
    before this existed: the tool crashed before doing anything at all.

    `errors="replace"` so an exotic glyph degrades to a placeholder instead of aborting a
    long-running segmentation pass.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except (ValueError, OSError):  # pragma: no cover - detached/odd streams
                pass


def setup_logging(verbose: bool = False, quiet: bool = False,
                  log_file: Path | None = None) -> logging.Logger:
    """Configure root logging. Logs go to stderr so stdout stays clean for reports."""
    level = logging.DEBUG if verbose else (logging.WARNING if quiet else logging.INFO)
    root = logging.getLogger()
    root.setLevel(logging.DEBUG)
    for h in list(root.handlers):
        root.removeHandler(h)

    stream = logging.StreamHandler(sys.stderr)
    stream.setLevel(level)
    stream.setFormatter(logging.Formatter(_LOG_FORMAT, _DATE_FORMAT))
    root.addHandler(stream)

    if log_file is not None:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        fh = logging.FileHandler(log_file, encoding="utf-8")
        fh.setLevel(logging.DEBUG)
        fh.setFormatter(logging.Formatter(_LOG_FORMAT, _DATE_FORMAT))
        root.addHandler(fh)

    return logging.getLogger("e1")


def _supports_progress() -> bool:
    if os.environ.get("E1_NO_PROGRESS"):
        return False
    return sys.stderr.isatty()


def progress(items: Iterable[T], desc: str = "", total: int | None = None,
             enabled: bool = True) -> Iterator[T]:
    """
    Progress bar over `items`. Uses tqdm when installed, otherwise a minimal built-in bar.

    Deliberately dependency-optional: this package must run on a bare research machine with only
    numpy and Pillow, and a missing progress bar is never a reason for the pipeline to fail.
    """
    seq = list(items) if total is None and not hasattr(items, "__len__") else items
    n = total if total is not None else (len(seq) if hasattr(seq, "__len__") else None)  # type: ignore[arg-type]

    if not enabled or not _supports_progress() or n == 0:
        yield from seq  # type: ignore[misc]
        return

    try:
        from tqdm import tqdm  # type: ignore[import-not-found]
        yield from tqdm(seq, desc=desc, total=n, unit="item", file=sys.stderr, leave=False)
        return
    except ImportError:
        pass

    start = time.monotonic()
    width = 28
    last_len = 0
    for i, item in enumerate(seq, start=1):  # type: ignore[arg-type]
        yield item
        if n:
            frac = i / n
            filled = int(width * frac)
            elapsed = time.monotonic() - start
            eta = (elapsed / frac - elapsed) if frac > 0 else 0.0
            line = (f"\r{desc} [{'#' * filled}{'.' * (width - filled)}] "
                    f"{i}/{n}  {frac * 100:5.1f}%  eta {eta:4.0f}s")
            last_len = max(last_len, len(line))
            sys.stderr.write(line.ljust(last_len))
            sys.stderr.flush()
    if n:
        sys.stderr.write("\r" + " " * last_len + "\r")
        sys.stderr.flush()
