import hashlib
import json
import shutil
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
MODELS = HERE / "models"
PACK = HERE / "pack" / "line-readers"

SOURCES = {
    "v6-tiny-det": "PaddlePaddle/PP-OCRv6_tiny_det_onnx",
    "v6-tiny-rec": "PaddlePaddle/PP-OCRv6_tiny_rec_onnx",
    "v5-en-rec": "PaddlePaddle/en_PP-OCRv5_mobile_rec_onnx",
    "v5-devanagari-rec": "PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx",
}
READERS = {"all": "v6-tiny-rec", "numbers": "v5-en-rec", "hindi": "v5-devanagari-rec"}
FINDER = "v6-tiny-det"
FINDER_SIZES = [(544, 1280), (576, 1280), (608, 1280), (640, 1280), (704, 1280)]
READER_WIDTHS = [320, 640, 960]
FIXED_READERS = {"all", "numbers"}
LONG_SIDE = 1280
VERSION = 2


def settings(name):
    return yaml.safe_load((MODELS / (name + ".yml")).read_text(encoding="utf-8"))


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    if PACK.exists():
        shutil.rmtree(PACK)
    PACK.mkdir(parents=True)
    for name in SOURCES:
        shutil.copyfile(MODELS / (name + ".onnx"), PACK / (name + ".onnx"))
    readers = {}
    for role, name in READERS.items():
        letters = settings(name)["PostProcess"]["character_dict"]
        for letter in letters:
            if not isinstance(letter, str) or letter == "" or "\n" in letter or "\r" in letter:
                raise SystemExit("%s: a dictionary entry cannot be written on a line: %r" % (name, letter))
        (PACK / (name + ".txt")).write_text("\n".join(letters) + "\n", encoding="utf-8", newline="\n")
        readers[role] = {"model": name + ".onnx", "letters": name + ".txt", "widths": READER_WIDTHS}
        if role in FIXED_READERS:
            fixed = []
            merged = "%s-48.tflite" % name
            if (MODELS / merged).exists():
                shutil.copyfile(MODELS / merged, PACK / merged)
                fixed = [{"model": merged, "width": width, "signature": "48x%d" % width} for width in READER_WIDTHS]
            else:
                for width in READER_WIDTHS:
                    file = "%s-48x%d.tflite" % (name, width)
                    if (MODELS / file).exists():
                        shutil.copyfile(MODELS / file, PACK / file)
                        fixed.append({"model": file, "width": width})
            if fixed:
                readers[role]["gpu"] = fixed
    post = settings(FINDER)["PostProcess"]
    sizes = []
    merged = "%s-x%d.tflite" % (FINDER, LONG_SIDE)
    if (MODELS / merged).exists():
        shutil.copyfile(MODELS / merged, PACK / merged)
        sizes = [{"model": merged, "width": w, "height": h, "signature": "%dx%d" % (w, h)} for w, h in FINDER_SIZES]
    else:
        for width, height in FINDER_SIZES:
            file = "%s-%dx%d.tflite" % (FINDER, width, height)
            if (MODELS / file).exists():
                shutil.copyfile(MODELS / file, PACK / file)
                sizes.append({"model": file, "width": width, "height": height})
    gpu = {"gpu": sizes} if sizes else {}
    manifest = {
        "id": "ppocr-v6-tiny-finder-v5-readers",
        "version": VERSION,
        "finder": {
            "model": FINDER + ".onnx",
            "longSide": LONG_SIDE,
            "thresh": post["thresh"],
            "boxThresh": post["box_thresh"],
            "unclip": post["unclip_ratio"],
            **gpu,
        },
        "readers": readers,
        "sources": SOURCES,
    }
    (PACK / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    files = [
        {"name": p.name, "size": p.stat().st_size, "sha256": sha256(p)}
        for p in sorted(PACK.iterdir()) if p.name != "files.json"
    ]
    (PACK / "files.json").write_text(json.dumps({"files": files}, indent=2) + "\n", encoding="utf-8", newline="\n")
    for f in files:
        print("%10d  %s  %s" % (f["size"], f["sha256"][:16], f["name"]))
    print("total %.1f MB in %s" % (sum(f["size"] for f in files) / 1e6, PACK))


if __name__ == "__main__":
    sys.exit(main())
