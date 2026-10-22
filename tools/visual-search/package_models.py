"""Assemble photo search's model folder as the app reads it, and the device parity references.

python package_models.py --photo <photo.onnx> --photo-id <id> --size 256 [--query-dir models/query]
        [--query-onnx model_quantized.onnx] [--out models/apk/photo-search]
        [--parity-images <dir with a few .jpg>]

Writes into --out (the folder a -PphotoSearchModels build carries as assets/photo-search, or that
is pushed to files/photo-search with adb):
  manifest.json        {"id", "dim", "inputSize", "queryModel", "photoModel"}  (PhotoModelFiles)
  gemma_bpe.bin        the tokenizer table (build_tokenizer.py, from --query-dir's tokenizer.json)
  <query-onnx> + <query-onnx>_data   EmbeddingGemma 2's text model: the community int8 export, or
                       prune_query_model.py's trimmed one (--query-onnx query.onnx)
  photo.onnx           the photo model (pixels -> unit vector in the same space)
  query_refs.tsv, photo_refs.tsv, parity/*.jpg       for PhotoSearchDeviceTest
The photo references are made the way the phone makes its input: a 512-pixel thumbnail, then a
bicubic squash to the model's square (PIL's, which the app's PhotoPixels reproduces), scaled to
-1..1.
"""
from __future__ import annotations

import argparse
import base64
import json
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

HERE = Path(__file__).parent


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--photo", required=True)
    ap.add_argument("--photo-id", required=True)
    ap.add_argument("--size", type=int, default=256)
    ap.add_argument("--query-dir", default=str(HERE / "models" / "query"))
    ap.add_argument("--query-onnx", default="model_quantized.onnx")
    ap.add_argument("--out", default=str(HERE / "models" / "apk" / "photo-search"))
    ap.add_argument("--parity-images", default=None)
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    qdir = Path(args.query_dir)

    # The weights file keeps its name: the model refers to it by name.
    for name in (args.query_onnx, args.query_onnx + "_data"):
        shutil.copyfile(qdir / "onnx" / name, out / name)
    shutil.copyfile(args.photo, out / "photo.onnx")
    # The table's cases stay outside the packaged folder (it all goes into the APK), for
    # GemmaTokenizerTest.theShippedTrimmedTableMatchesItsReference.
    subprocess.run([sys.executable, str(HERE / "build_tokenizer.py"), str(qdir), str(out / "gemma_bpe.bin"),
                    str(HERE / "models" / "shipped_tokenizer_cases.tsv")], check=True)

    photo = ort.InferenceSession(str(out / "photo.onnx"), providers=["CPUExecutionProvider"])
    # Measured, not read off the graph: an export can leave the output's width symbolic.
    dim = photo.run(None, {"pixels": np.zeros((1, 3, args.size, args.size), np.float32)})[0].shape[-1]
    (out / "manifest.json").write_text(json.dumps({
        "id": args.photo_id, "dim": int(dim), "inputSize": args.size,
        "queryModel": args.query_onnx, "photoModel": "photo.onnx",
    }, indent=1))

    subprocess.run([sys.executable, str(HERE / "make_query_refs.py"), str(qdir), str(out / "query_refs.tsv"),
                    args.query_onnx], check=True)

    if args.parity_images:
        pdir = out / "parity"
        pdir.mkdir(exist_ok=True)
        rows = []
        for src in sorted(Path(args.parity_images).glob("*.jpg"))[:12]:
            with Image.open(src) as im:
                im = im.convert("RGB")
                im.thumbnail((512, 512), Image.BILINEAR)
                im.save(pdir / src.name, quality=90)
            with Image.open(pdir / src.name) as im:
                # As the model was trained, and as the app's PhotoPixels does it.
                x = np.asarray(im.convert("RGB").resize((args.size, args.size), Image.BICUBIC), dtype=np.float32)
            x = (x / 127.5 - 1.0).transpose(2, 0, 1)[None]
            v = photo.run(None, {"pixels": x})[0][0].astype(np.float32)
            v /= np.linalg.norm(v)
            rows.append(src.name + "\t" + base64.b64encode(v.astype("<f4").tobytes()).decode())
        (out / "photo_refs.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    total = sum(f.stat().st_size for f in out.rglob("*") if f.is_file())
    print(f"{out}: {total / 1e6:.0f} MB, manifest id {args.photo_id}, dim {dim}")


if __name__ == "__main__":
    main()
