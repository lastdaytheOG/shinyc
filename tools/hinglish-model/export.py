import argparse
import pathlib
import struct

import numpy as np
import torch

from evaluate import load
from model import VOCAB

MAGIC = 0x484C5431

REFERENCE_WORDS = [
    "paani", "pani", "bijli", "bijlee", "pehchan", "patra", "mehndi", "parcel", "shaadi", "shadi", "chhutti",
    "kiraya", "bhejne", "sadak", "marammat", "jaankari", "matdata", "bhugtan", "safal", "taiyari", "rasm",
    "nagar", "nigam", "madhumeh", "jalvayu", "parivartan", "prakash", "sanshleshan", "bill", "card", "message",
    "application", "ka", "ki", "ke", "aaj", "shaam", "tak", "dhwani", "parmanu", "vyayam", "hriday", "rakt",
    "kapas", "ganna", "computer", "internet", "x", "zzz", "aaaaaaaaaaaaaaaaaaaaaaaa",
]


def tensors(model):
    sd = {k: v.detach().float() for k, v in model.state_dict().items()}
    order = [
        "src_emb.weight",
        "encoder.weight_ih_l0", "encoder.weight_hh_l0", "encoder.bias_ih_l0", "encoder.bias_hh_l0",
        "encoder.weight_ih_l0_reverse", "encoder.weight_hh_l0_reverse", "encoder.bias_ih_l0_reverse",
        "encoder.bias_hh_l0_reverse",
        "bridge.weight", "bridge.bias",
        "tgt_emb.weight",
        "cell.weight_ih", "cell.weight_hh", "cell.bias_ih", "cell.bias_hh",
        "attn.weight",
        "out.weight", "out.bias",
    ]
    return [sd[n] for n in order]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("--out", required=True)
    ap.add_argument("--reference", required=True)
    ap.add_argument("--width", type=int, default=4)
    a = ap.parse_args()
    model = load(a.ckpt, half=True)
    emb, enc, dec = model.sizes[:3]
    feed = 1 if model.feed else 0
    out = pathlib.Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as f:
        f.write(struct.pack("<6i", MAGIC, emb, enc, dec, VOCAB, feed))
        for t in tensors(model):
            f.write(t.numpy().astype("<f2").tobytes())
    tab, newline = chr(9), chr(10)
    lines = [f"width{tab}{a.width}"]
    for w in REFERENCE_WORDS:
        lines.append(tab.join([w] + [f"{k}:{p:.6g}" for k, p in model.beam(w, a.width)]))
    pathlib.Path(a.reference).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(a.reference).write_text(newline.join(lines) + newline, encoding="utf-8")
    print(out, out.stat().st_size, "bytes;", len(lines) - 1, "reference words")


if __name__ == "__main__":
    main()
