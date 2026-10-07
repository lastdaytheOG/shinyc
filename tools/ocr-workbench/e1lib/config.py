"""Typed configuration. No hardcoded paths — every location is resolved from config or CLI."""

from __future__ import annotations

import json
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any

try:  # Python 3.11+
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - exercised only on older interpreters
    tomllib = None  # type: ignore[assignment]

SCRIPTS: tuple[str, ...] = ("hi", "en", "mixed")
STRATA: tuple[str, ...] = ("clean", "scanned", "tables", "smalltext", "mixed")


@dataclass(frozen=True)
class PathsConfig:
    """All paths are relative to the config file's directory unless absolute."""
    staging: str = "data/val/_staging"
    out: str = "data/val"


@dataclass(frozen=True)
class SegmentationConfig:
    target_height: int = 48
    min_line_height: int = 12
    max_line_height_frac: float = 0.40
    max_lines_per_page: int = 200
    row_ink_frac: float = 0.012
    # 0.030 (36px on a 1211px page) missed the 12-17px gutter of a tightly-set two-column
    # journal page, which then projected as one column: ink from both columns on every row,
    # every band merged, and the whole page discarded by max_line_height_frac. A narrow
    # gutter is still a strong signal because it must be clear over the FULL page height,
    # which ordinary word spacing never is. Measured corpus-wide, pages losing most of their
    # ink went 15 (at 0.030) -> 3 (0.012) -> 2 (0.008); 0.012 still demands 14.5px on the page
    # whose real gutter is 12px.
    min_gutter_frac: float = 0.008
    min_col_frac: float = 0.12
    # A spanning headline or masthead crosses the gutter and, at zero tolerance, vetoes it for
    # the whole page. Allowing ink in this fraction of rows lets the gutter survive the header
    # while still requiring it to be clear through the body.
    gutter_ink_tolerance: float = 0.01
    pad: int = 3
    # Paper-relative binarization. Otsu assumes two comparably-sized pixel classes; a text page is
    # 98-99% paper, and under that imbalance Otsu keeps only fully-saturated pixels — measured on a
    # Devanagari page it captured the shirorekha alone and collapsed every line to a 1px band.
    paper_percentile: float = 90.0
    paper_factor: float = 0.85
    retry_bias: int = 20
    # Gaps below this fraction of a typical line height are intra-line (Devanagari matras sit above
    # and below the main body and are often separated by a row or two of white), not line breaks.
    merge_gap_factor: float = 0.35
    # Ceiling on what the merge pass may produce, as a multiple of the median band height.
    #
    # Merging is transitive, so without a ceiling each merge extends the band and can swallow the
    # next one indefinitely. On tightly-leaded text that chained until a single band covered 80% of
    # the page, which max_line_height_frac then discarded — the content was found, fused, and thrown
    # away. Measured over the E1 corpus this took pages losing most of their ink rows from 15 to 4.
    # Raise it to merge more aggressively; 0 disables the ceiling and restores the old behaviour.
    merge_cap_factor: float = 3.0


@dataclass(frozen=True)
class FallbackConfig:
    enable_connected_components: bool = True
    cc_min_area: int = 12
    cc_vertical_overlap: float = 0.30
    # Projection is considered to have failed if it returns nothing, or returns one band covering
    # most of the page (everything merged), or an implausible count.
    projection_merged_frac: float = 0.80


@dataclass(frozen=True)
class DedupConfig:
    # Max Hamming distance for a near-duplicate claim, over the DHASH_BITS-bit hash in
    # [e1lib.imageops]. 8 of 256 bits is where cross-book groups reach zero on this corpus while
    # 38 within-book groups are still reported. Cross-book matches are the available ground
    # truth for a false positive: two different books cannot share a rendered line, so any group
    # spanning books is wrong regardless of how it looks. Raising this past 12 brings them back.
    near_dup_distance: int = 8
    # Exact (byte-identical) duplicates are pure redundancy: repeated table header rows and
    # running headers, measured 48 times in this corpus. Each extra copy is one more sample of
    # the same pixels, so it weights that one line's score N times in the aggregate CER while
    # contributing no new information. The first occurrence in reading order is kept and the
    # rest are never written.
    drop_exact_duplicates: bool = True


@dataclass(frozen=True)
class DefaultsConfig:
    """Pre-filled into the TSV. Never inferred from pixels — see module docstring."""
    script: str = ""
    stratum: str = ""


@dataclass(frozen=True)
class PageOverride:
    """Per-page override of its book's labels. Empty fields inherit the book, then [defaults]."""
    script: str = ""
    stratum: str = ""


@dataclass(frozen=True)
class BookConfig:
    """
    Per-book override of [defaults], keyed by staging subdirectory name.

    One run of 08_make_lines.py consumes the whole staging tree and writes a single
    ground_truth.tsv, so a global --script/--stratum can only ever be right for a
    single-script, single-stratum corpus. Mapping each book to its own labels is what
    makes a mixed corpus correct in one pass instead of hand-edited afterwards.

    An empty field falls back to [defaults] rather than to "", so a book can override
    stratum alone and still inherit the corpus-wide script.

    `pages` exists because stratum is not actually a book-level property. Tables in
    particular are scattered through otherwise-prose books, so no whole-book label can
    describe them; without page-level overrides a quarter of this corpus would be scored
    under the wrong stratum and the tables split would be unmeasurable.

    `exclude` drops pages from the dataset entirely — for pages carrying nothing a human
    could transcribe (blank leaves) or nothing this pipeline can segment correctly. Keeping
    such a page as a whole-page crop is worse than dropping it: it enters the TSV as a
    "line" that is really a page and silently contributes a huge outlier to every metric.
    """
    script: str = ""
    stratum: str = ""
    pages: dict[int, PageOverride] = field(default_factory=dict)
    exclude: tuple[int, ...] = ()


@dataclass(frozen=True)
class DatasetConfig:
    image_prefix: str = "line_"
    image_digits: int = 6
    write_label_files: bool = True


@dataclass(frozen=True)
class E1Config:
    paths: PathsConfig = field(default_factory=PathsConfig)
    segmentation: SegmentationConfig = field(default_factory=SegmentationConfig)
    fallback: FallbackConfig = field(default_factory=FallbackConfig)
    dedup: DedupConfig = field(default_factory=DedupConfig)
    defaults: DefaultsConfig = field(default_factory=DefaultsConfig)
    dataset: DatasetConfig = field(default_factory=DatasetConfig)
    #: staging subdirectory name -> per-book script/stratum override
    books: dict[str, BookConfig] = field(default_factory=dict)
    #: directory the config was loaded from; relative paths resolve against it
    root: Path = field(default_factory=Path.cwd)

    def defaults_for(self, book: str) -> tuple[str, str]:
        """(script, stratum) for a staging subdirectory, falling back to [defaults]."""
        return self.labels_for(book)

    def labels_for(self, book: str, page: int | None = None) -> tuple[str, str]:
        """
        (script, stratum) for one page, resolved page -> book -> [defaults].

        Each level overrides only the fields it sets, so a page can be retagged `tables`
        without restating the script its book already gets right.
        """
        b = self.books.get(book)
        if b is None:
            return self.defaults.script, self.defaults.stratum
        script, stratum = b.script, b.stratum
        if page is not None:
            po = b.pages.get(page)
            if po is not None:
                script = po.script or script
                stratum = po.stratum or stratum
        return (script or self.defaults.script, stratum or self.defaults.stratum)

    def is_excluded(self, book: str, page: int) -> bool:
        b = self.books.get(book)
        return bool(b and page in b.exclude)

    # ── path helpers ────────────────────────────────────────────────────────
    def _resolve(self, raw: str) -> Path:
        p = Path(raw)
        return p if p.is_absolute() else (self.root / p)

    @property
    def staging_dir(self) -> Path:
        return self._resolve(self.paths.staging)

    @property
    def out_dir(self) -> Path:
        return self._resolve(self.paths.out)

    @property
    def images_dir(self) -> Path:
        return self.out_dir / "images"

    @property
    def labels_dir(self) -> Path:
        return self.out_dir / "labels"

    @property
    def tsv_path(self) -> Path:
        return self.out_dir / "ground_truth.tsv"

    @property
    def metadata_path(self) -> Path:
        return self.out_dir / "metadata.json"

    @property
    def stats_path(self) -> Path:
        return self.out_dir / "stats.json"

    def to_dict(self) -> dict[str, Any]:
        d = {k: asdict(v) for k, v in (
            ("paths", self.paths), ("segmentation", self.segmentation),
            ("fallback", self.fallback), ("dedup", self.dedup),
            ("defaults", self.defaults), ("dataset", self.dataset),
        )}
        # Recorded in metadata.json: the mapping is what produced the script/stratum
        # columns, so it belongs with the provenance rather than only in the config file.
        d["books"] = {name: asdict(b) for name, b in sorted(self.books.items())}
        return d


def _section(data: dict[str, Any], name: str, cls):
    """Build a config dataclass from a section, ignoring unknown keys but reporting them."""
    raw = data.get(name, {}) or {}
    known = {f for f in cls.__dataclass_fields__}
    unknown = set(raw) - known
    if unknown:
        raise ValueError(f"unknown key(s) in [{name}]: {', '.join(sorted(unknown))}")
    return cls(**{k: v for k, v in raw.items() if k in known})


def _check_labels(where: str, script: str, stratum: str) -> None:
    """
    Reject bad label values at load time.

    These strings become TSV columns that the per-script and per-stratum CER splits are
    keyed on. A typo like "hindi" is not caught until validate_e1.py reports an unknown
    label — by which point the transcription has already been typed against it.
    """
    if script and script not in SCRIPTS:
        raise ValueError(
            f"[{where}] script = {script!r} — must be one of {', '.join(SCRIPTS)}")
    if stratum and stratum not in STRATA:
        raise ValueError(
            f"[{where}] stratum = {stratum!r} — must be one of {', '.join(STRATA)}")


def _books(data: dict[str, Any]) -> dict[str, BookConfig]:
    """Parse [books.<staging-subdirectory>] tables, rejecting typos rather than ignoring them."""
    raw = data.get("books", {}) or {}
    if not isinstance(raw, dict):
        raise ValueError('[books] must be a table of per-book tables, e.g. [books.my_book]')

    out: dict[str, BookConfig] = {}
    for name, entry in raw.items():
        if not isinstance(entry, dict):
            raise ValueError(
                f'[books.{name}] must be a table, e.g.\n'
                f'    [books.{name}]\n    script  = "hi"\n    stratum = "clean"')
        unknown = set(entry) - set(BookConfig.__dataclass_fields__)
        if unknown:
            raise ValueError(f"unknown key(s) in [books.{name}]: {', '.join(sorted(unknown))}")

        fields = dict(entry)
        fields["pages"] = _page_overrides(name, fields.pop("pages", None))
        fields["exclude"] = _exclude_list(name, fields.pop("exclude", None))

        book = BookConfig(**fields)
        _check_labels(f"books.{name}", book.script, book.stratum)
        for page, po in book.pages.items():
            _check_labels(f"books.{name}.pages.{page}", po.script, po.stratum)
        overlap = sorted(set(book.pages) & set(book.exclude))
        if overlap:
            raise ValueError(
                f"[books.{name}] page(s) {overlap} are both excluded and given a label "
                f"override — an excluded page produces no rows, so the override is dead "
                f"config that reads as if it were in effect")
        out[name] = book
    return out


def _page_overrides(book: str, raw) -> dict[int, PageOverride]:
    """Parse [books.<book>.pages]. TOML keys are strings; page numbers are integers."""
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        raise ValueError(
            f'[books.{book}.pages] must be a table of per-page tables, e.g.\n'
            f'    [books.{book}.pages]\n    7 = {{ stratum = "tables" }}')
    out: dict[int, PageOverride] = {}
    for key, entry in raw.items():
        try:
            page = int(key)
        except (TypeError, ValueError):
            raise ValueError(
                f"[books.{book}.pages] key {key!r} is not a page number — keys are the page "
                f"numbers parsed from the filenames (page_0007.png -> 7)") from None
        if not isinstance(entry, dict):
            raise ValueError(
                f'[books.{book}.pages] {key} must be a table, e.g. {{ stratum = "tables" }}')
        unknown = set(entry) - set(PageOverride.__dataclass_fields__)
        if unknown:
            raise ValueError(
                f"unknown key(s) in [books.{book}.pages] {key}: {', '.join(sorted(unknown))}")
        out[page] = PageOverride(**entry)
    return out


def _exclude_list(book: str, raw) -> tuple[int, ...]:
    if raw is None:
        return ()
    if isinstance(raw, (str, bytes)) or not isinstance(raw, (list, tuple)):
        raise ValueError(
            f"[books.{book}] exclude must be a list of page numbers, e.g. exclude = [6, 10]")
    pages: list[int] = []
    for v in raw:
        if isinstance(v, bool) or not isinstance(v, int):
            raise ValueError(
                f"[books.{book}] exclude contains {v!r} — page numbers only, as integers")
        pages.append(int(v))
    dupes = sorted({p for p in pages if pages.count(p) > 1})
    if dupes:
        raise ValueError(f"[books.{book}] exclude repeats page(s) {dupes}")
    return tuple(sorted(pages))


class BookCoverageError(ValueError):
    """
    A staged book has no [books.*] entry.

    Subclasses ValueError so the existing `except (ValueError, RuntimeError)` config-error
    handling in the scripts reports it as the config problem it is.
    """


def check_book_coverage(cfg: E1Config, books: list[str]) -> list[str]:
    """
    Require a [books.*] entry for every staged book. Returns the mapped names that matched
    no staging directory (a warning, not an error — a stale key cannot mislabel anything).

    Raises BookCoverageError when a staged book is unmapped. Falling back to [defaults]
    there is the dangerous outcome, not the safe one: the run still produces crops and a
    TSV that looks complete, but those rows carry the blank default script/stratum and drop
    out of the per-script and per-stratum CER splits. The measurement then silently covers
    fewer books than it claims to.

    An empty [books] keeps the original whole-corpus behaviour, where --script/--stratum
    are the deliberate mechanism rather than an oversight.
    """
    if not cfg.books:
        return []

    unmapped = [b for b in books if b not in cfg.books]
    if unmapped:
        known = ", ".join(sorted(cfg.books)) or "(none)"
        raise BookCoverageError(
            f"{len(unmapped)} staged book(s) have no [books.*] entry: "
            f"{', '.join(unmapped)}\n"
            f"  Add a [books.<name>] table for each, keyed by the staging subdirectory name "
            f"exactly as it appears on disk (case-sensitive).\n"
            f"  Mapped names are: {known}\n"
            f"  Refusing to fall back to [defaults] (script={cfg.defaults.script!r} "
            f"stratum={cfg.defaults.stratum!r}) — unlabelled rows disappear from the "
            f"per-script and per-stratum CER splits instead of failing visibly.")

    return sorted(set(cfg.books) - set(books))


def format_book_table(cfg: E1Config, books: list[str]) -> str:
    """
    The resolved script/stratum per staged book — what actually reaches the TSV columns.

    Page overrides and exclusions are summarised per book rather than listed, but they are
    summarised: a book printed as plain `clean` when six of its pages are really `tables`
    would make this table the thing that hides the mapping instead of showing it.
    """
    if not books:
        return ""
    width = max(len(b) for b in books)
    lines = []
    for b in books:
        script, stratum = cfg.defaults_for(b)
        row = f"{b:<{width}} -> script={script or '(unset)':<7} stratum={stratum or '(unset)'}"
        bc = cfg.books.get(b)
        if bc and bc.pages:
            strata = sorted({po.stratum for po in bc.pages.values() if po.stratum})
            row += f"  [+{len(bc.pages)} page override(s)"
            row += f": {', '.join(strata)}]" if strata else "]"
        if bc and bc.exclude:
            row += f"  [excluded: {', '.join(str(p) for p in bc.exclude)}]"
        lines.append(row)
    return "\n".join(lines)


def load_config(path: Path | None = None, root: Path | None = None) -> E1Config:
    """
    Load config from TOML or JSON. With no path, returns defaults rooted at `root` (or cwd).

    Relative paths inside the config resolve against the config file's directory, so a checkout
    can be moved or cloned anywhere without editing anything.
    """
    if path is None:
        return E1Config(root=(root or Path.cwd()).resolve())

    path = path.resolve()
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() == ".json":
        data = json.loads(text)
    elif path.suffix.lower() == ".toml":
        if tomllib is None:
            raise RuntimeError(
                "TOML config needs Python 3.11+ (tomllib). Use a .json config on older versions.")
        data = tomllib.loads(text)
    else:
        raise ValueError(f"unsupported config format: {path.suffix} (use .toml or .json)")

    data.pop("_comment", None)
    defaults = _section(data, "defaults", DefaultsConfig)
    _check_labels("defaults", defaults.script, defaults.stratum)
    return E1Config(
        paths=_section(data, "paths", PathsConfig),
        segmentation=_section(data, "segmentation", SegmentationConfig),
        fallback=_section(data, "fallback", FallbackConfig),
        dedup=_section(data, "dedup", DedupConfig),
        defaults=defaults,
        dataset=_section(data, "dataset", DatasetConfig),
        books=_books(data),
        root=(root or path.parent).resolve(),
    )


def find_default_config(start: Path) -> Path | None:
    """Look for e1_config.toml / .json in `start` and its parents."""
    for d in [start, *start.parents]:
        for name in ("e1_config.toml", "e1_config.json"):
            c = d / name
            if c.is_file():
                return c
    return None
