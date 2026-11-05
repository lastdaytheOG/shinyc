"""Copy photo search's models out of the build folder into a folder that can be uploaded anywhere.

python pack_for_download.py [--src models/apk/photo-search] [--out D:/amar-models/photo-search]

Writes <out>/<model id>/ holding only what the app loads (manifest.json, the tokenizer, the query
model and its _data file, the photo model; no test material), plus files.json: every file's name,
size and SHA-256, the manifest last. Upload the folder's files side by side (one base URL), and an
app fetches <base>/files.json, then each file, checks size and hash, and puts manifest.json in
files/photo-search/ only once every other file is there and correct (see PhotoModelFiles).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

HERE = Path(__file__).parent


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for block in iter(lambda: f.read(8 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(HERE / "models" / "apk" / "photo-search"))
    ap.add_argument("--out", default="D:/amar-models/photo-search")
    args = ap.parse_args()
    src = Path(args.src)
    manifest = json.loads((src / "manifest.json").read_text(encoding="utf-8"))
    q = manifest["queryModel"]
    names = ["gemma_bpe.bin", q, f"{q}_data", manifest["photoModel"], "manifest.json"]
    out = Path(args.out) / manifest["id"]
    out.mkdir(parents=True, exist_ok=True)
    listed = []
    for name in names:
        s, d = src / name, out / name
        if not d.exists() or d.stat().st_size != s.stat().st_size:
            shutil.copyfile(s, d)
        digest = sha256(d)
        if digest != sha256(s):
            raise SystemExit(f"{name}: copy differs from the source")
        listed.append({"name": name, "size": d.stat().st_size, "sha256": digest})
        print(f"{name:20} {d.stat().st_size:>12,}  {digest}")
    (out / "files.json").write_text(
        json.dumps({"id": manifest["id"], "files": listed}, indent=1) + "\n", encoding="utf-8"
    )
    total = sum(f["size"] for f in listed)
    print(f"{len(listed)} files, {total / 1e6:.1f} MB -> {out}")


if __name__ == "__main__":
    main()
