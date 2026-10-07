"""Unit tests for E1 validation."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from e1lib.config import DefaultsConfig, E1Config, PathsConfig
from e1lib.dataset import (
    TSV_COLUMNS, build_lines, discover_pages, write_metadata, write_tsv,
)
from e1lib.validate import adopt_labels, derive_script, validate


def write_page(path: Path, rows=(50, 150, 250)) -> None:
    a = np.full((400, 800), 255, dtype=np.uint8)
    for y in rows:
        a[y:y + 16, 40:760] = 0
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(a).save(path)


class Fixture:
    """A freshly generated, structurally valid dataset."""

    def __init__(self, td: Path, script: str = "hi", stratum: str = "clean"):
        self.td = td
        write_page(td / "_staging" / "bk" / "page_0001.png")
        self.cfg = E1Config(
            paths=PathsConfig(staging=str(td / "_staging"), out=str(td / "val")),
            defaults=DefaultsConfig(script=script, stratum=stratum), root=td)
        res = build_lines(self.cfg, discover_pages(self.cfg.staging_dir))
        write_tsv(self.cfg, res.records)
        write_metadata(self.cfg, res)
        self.records = res.records

    def rows(self) -> list[str]:
        return self.cfg.tsv_path.read_text(encoding="utf-8").rstrip("\n").split("\n")

    def set_rows(self, rows: list[str]) -> None:
        self.cfg.tsv_path.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")

    def transcribe(self, texts: list[str]) -> None:
        rows = self.rows()
        for i, t in enumerate(texts, start=1):
            cells = rows[i].split("\t")
            cells[1] = t
            rows[i] = "\t".join(cells)
        self.set_rows(rows)


class TestDeriveScript(unittest.TestCase):
    def test_pure_scripts(self):
        self.assertEqual(derive_script("यह एक वाक्य है"), "hi")
        self.assertEqual(derive_script("This is a sentence"), "en")

    def test_mixed(self):
        self.assertEqual(derive_script("यह एक Computer Science वाक्य है"), "mixed")

    def test_digits_alone_are_unknown(self):
        self.assertEqual(derive_script("12345 -- 67.89"), "unknown")

    def test_single_stray_token_does_not_flip_the_script(self):
        text = "यह एक बहुत लंबा हिंदी वाक्य है जिसमें बहुत सारे शब्द हैं और यह जारी रहता है ok"
        self.assertEqual(derive_script(text), "hi")


class TestValidate(unittest.TestCase):
    def test_freshly_generated_dataset_passes(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            rep = validate(fx.cfg)
            self.assertTrue(rep.ok, [f.message for f in rep.errors])
            self.assertEqual(rep.transcribed, 0)

    def test_orphan_image_is_an_error(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            src = fx.cfg.images_dir / f"{fx.records[0].line_id}.png"
            (fx.cfg.images_dir / "line_099999.png").write_bytes(src.read_bytes())
            rep = validate(fx.cfg)
            self.assertIn("orphan_image", [f.code for f in rep.errors])

    def test_row_referencing_a_missing_image_is_an_error(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            rows = fx.rows()
            rows.append("\t".join(["images/ghost.png", "x", "hi", "clean", "bk", "1", "9"]))
            fx.set_rows(rows)
            rep = validate(fx.cfg)
            self.assertIn("missing_image", [f.code for f in rep.errors])

    def test_duplicate_image_path_is_an_error(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            rows = fx.rows()
            rows.append(rows[1])
            fx.set_rows(rows)
            rep = validate(fx.cfg)
            self.assertIn("duplicate_path", [f.code for f in rep.errors])

    def test_wrong_column_count_is_an_error(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            rows = fx.rows()
            rows[1] = "images/x.png\tonly three\tcols"
            fx.set_rows(rows)
            self.assertIn("bad_columns", [f.code for f in validate(fx.cfg).errors])

    def test_non_nfc_transcription_is_an_error(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            # U+0958 QA is a Unicode composition exclusion: NFC decomposes it, so a string
            # containing it is by definition not NFC.
            fx.transcribe(["क़ज़ दस्तावेज़"])
            self.assertIn("not_nfc", [f.code for f in validate(fx.cfg).errors])

    def test_nfc_transcription_passes(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            fx.transcribe(["लोक प्रशासन एक क्रिया के रूप में"])
            self.assertNotIn("not_nfc", [f.code for f in validate(fx.cfg).errors])

    def test_bom_is_an_error(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            raw = fx.cfg.tsv_path.read_bytes()
            fx.cfg.tsv_path.write_bytes(b"\xef\xbb\xbf" + raw)
            self.assertIn("bom", [f.code for f in validate(fx.cfg).errors])

    def test_hash_drift_is_detected(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            p = fx.cfg.images_dir / f"{fx.records[0].line_id}.png"
            Image.new("RGB", (60, 30), "black").save(p)
            self.assertIn("hash_drift", [f.code for f in validate(fx.cfg).errors])

    def test_unknown_script_is_a_warning_not_an_error(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            rows = fx.rows()
            cells = rows[1].split("\t")
            cells[2] = "klingon"
            rows[1] = "\t".join(cells)
            fx.set_rows(rows)
            rep = validate(fx.cfg)
            self.assertIn("unknown_script", [f.code for f in rep.warnings])
            self.assertTrue(rep.ok)

    def test_script_mismatch_is_flagged(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            fx.transcribe(["This line is clearly English"])
            self.assertIn("script_mismatch", [f.code for f in validate(fx.cfg).warnings])

    def test_untranscribed_rows_warn(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            self.assertIn("incomplete", [f.code for f in validate(fx.cfg).warnings])

    def test_label_divergence_is_an_error(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            fx.transcribe(["पाठ एक"])
            (fx.cfg.labels_dir / f"{fx.records[0].line_id}.txt").write_text(
                "something else", encoding="utf-8")
            self.assertIn("label_divergence", [f.code for f in validate(fx.cfg).errors])

    def test_adopt_labels_pulls_sidecars_into_the_tsv(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            (fx.cfg.labels_dir / f"{fx.records[0].line_id}.txt").write_text(
                "लोक प्रशासन", encoding="utf-8")
            changed = adopt_labels(fx.cfg)
            self.assertEqual(changed, 1)
            self.assertIn("लोक प्रशासन", fx.cfg.tsv_path.read_text(encoding="utf-8"))
            self.assertTrue(validate(fx.cfg).ok)

    def test_zero_byte_image_is_an_error(self):
        with tempfile.TemporaryDirectory() as td:
            fx = Fixture(Path(td))
            (fx.cfg.images_dir / f"{fx.records[0].line_id}.png").write_bytes(b"")
            self.assertIn("empty_image", [f.code for f in validate(fx.cfg).errors])


if __name__ == "__main__":
    unittest.main(verbosity=2)
