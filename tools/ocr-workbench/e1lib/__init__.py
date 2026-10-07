"""
e1lib — E1 gold-standard OCR evaluation dataset tooling.

E1 is an **evaluation** set, never a training set. Its only job is to measure OCR quality
(CER / WER / page accuracy) honestly, which imposes one hard constraint on everything in this
package:

    NOTHING HERE MAY PRODUCE TEXT.

No OCR engine, no recognizer, no language model, no heuristic "best guess" transcription. Ground
truth is typed by a human from the image, and nothing else.

The reason is not purity. E1 exists to compare a candidate engine against the incumbent (ML Kit).
If any transcription were seeded from an engine's output, that engine's mistakes would become the
reference: it would score near-perfect against its own errors, and a genuinely better engine would
be penalised for disagreeing with them. A contaminated evaluation set is worse than no evaluation
set, because it produces confident numbers pointing the wrong way.

Modules:
    config       typed configuration, loaded from TOML/JSON, no hardcoded paths
    runtime      logging + progress reporting
    imageops     binarization and image hashing
    segmentation line detection (projection profile, connected-components fallback)
    dataset      dataset writing, records, deterministic naming
    validate     integrity checking
    stats        corpus statistics
"""

__all__ = ["config", "runtime", "imageops", "segmentation", "dataset", "validate", "stats"]
__version__ = "1.0.0"
