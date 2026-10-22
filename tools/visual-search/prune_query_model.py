"""Trim EmbeddingGemma 2's text model to the vocabulary that English, Hindi and Hindi typed in
Latin letters need.

python prune_query_model.py <query dir> <out dir> [--ascii-rank N] [--model model_q4.onnx]
        [--table-from model_quantized.onnx]

The model's token table has a row for each of 262,144 tokens in 100+ languages, and it is half
of the model file. A query in English, Hindi or romanised Hindi only ever reads the rows of
tokens made of ASCII, Devanagari and punctuation, so every other row can go without changing a
single vector for such text: anything else typed (an emoji, another script) is spelled with the
byte tokens, which stay.

--ascii-rank N also drops ASCII tokens rarer than the N-th: BPE merge order is frequency order
in the tokenizer's training text, so the kept set stays closed (a kept token's pieces are always
kept). A rarer English word is then spelled with smaller pieces, which does change its vector;
the benchmark kit's eval_query_model.py measures by how much.

--table-from takes the token table from another export of the same model (the int8 one) while
the rest of the graph comes from --model (the 4-bit one): the table is then 8-bit and the
layers 4-bit.

Writes <out dir>/tokenizer.json (ids renumbered, in the old order) and <out dir>/onnx/query.onnx
+ query.onnx_data, whose token table holds the kept rows only. Nothing else in the graph reads
token ids except the image/video/audio markers (258880 and up), which no kept id reaches.
"""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper

TABLE = ("model_embed_tokens_weight_quant", "model_embed_tokens_weight_scales", "model_embed_tokens_weight_zp")


def allowed(ch: str) -> bool:
    o = ord(ch)
    return (o < 128 or ch == "▁" or 0x0900 <= o <= 0x097F or 0xA8E0 <= o <= 0xA8FF or 0x1CD0 <= o <= 0x1CFF
            or 0x2000 <= o <= 0x206F or 0x20A0 <= o <= 0x20CF or 0x00A0 <= o <= 0x00BF or o in (0x00D7, 0x00F7))


def kept_tokens(tok: dict, ascii_rank: int | None) -> set[str]:
    model = tok["model"]
    vocab: dict[str, int] = model["vocab"]
    pairs = [p if isinstance(p, list) else p.split(" ", 1) for p in model["merges"]]
    made_by: dict[str, tuple[int, str, str]] = {}
    for rank, (a, b) in enumerate(pairs):
        made_by.setdefault(a + b, (rank, a, b))
    # Special tokens stay (the prompt's <bos> and <eos> among them), but not the thousands of
    # <unusedN> placeholders, which no text ever produces.
    keep = {a["content"] for a in tok["added_tokens"]
            if (a["special"] or all(allowed(c) for c in a["content"])) and not a["content"].startswith("<unused")}
    keep |= {f"<0x{b:02X}>" for b in range(256)}
    for t in vocab:
        if len(t) == 1:
            # Single characters: ours, plus accented Latin letters so "café" is not spelled in bytes.
            if allowed(t) or 0x00C0 <= ord(t) <= 0x00FF:
                keep.add(t)
        elif t in made_by and all(allowed(c) for c in t):
            ascii_only = all(ord(c) < 128 or c == "▁" for c in t)
            if not ascii_only or ascii_rank is None or made_by[t][0] < ascii_rank:
                keep.add(t)
    # A kept token must still be reachable: its pieces stay too.
    todo = [t for t in keep if t in made_by]
    while todo:
        t = todo.pop()
        _, a, b = made_by[t]
        for piece in (a, b):
            if piece not in keep:
                keep.add(piece)
                if piece in made_by:
                    todo.append(piece)
    return {t for t in keep if t in vocab}


def remap_ids(node, new_id: dict[int, int]):
    if isinstance(node, dict):
        return {k: ([new_id[i] for i in v] if k == "ids" and isinstance(v, list) else remap_ids(v, new_id))
                for k, v in node.items()}
    if isinstance(node, list):
        return [remap_ids(v, new_id) for v in node]
    return node


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--ascii-rank", type=int, default=None)
    ap.add_argument("--model", default="model_quantized.onnx")
    ap.add_argument("--table-from", default=None)
    args = ap.parse_args()
    src, out = Path(args.src), Path(args.out)
    tok = json.loads((src / "tokenizer.json").read_text(encoding="utf-8"))
    vocab: dict[str, int] = tok["model"]["vocab"]
    keep = kept_tokens(tok, args.ascii_rank)
    old_ids = sorted(vocab[t] for t in keep)
    new_id = {old: new for new, old in enumerate(old_ids)}

    pruned = copy.deepcopy(tok)
    pruned["model"]["vocab"] = {t: new_id[i] for t, i in vocab.items() if t in keep}
    merges = tok["model"]["merges"]
    as_list = bool(merges) and isinstance(merges[0], list)
    kept_merges = []
    for p in merges:
        a, b = p if isinstance(p, list) else p.split(" ", 1)
        if a in keep and b in keep and a + b in keep:
            kept_merges.append([a, b] if as_list else f"{a} {b}")
    pruned["model"]["merges"] = kept_merges
    pruned["added_tokens"] = [dict(a, id=new_id[a["id"]]) for a in tok["added_tokens"] if a["id"] in new_id]
    pruned["post_processor"] = remap_ids(tok["post_processor"], new_id)
    (out / "onnx").mkdir(parents=True, exist_ok=True)
    (out / "tokenizer.json").write_text(json.dumps(pruned, ensure_ascii=False), encoding="utf-8")
    # Not tokenizer_config.json: it lists the special tokens under their old ids.
    for name in ("config.json", "special_tokens_map.json"):
        if (src / name).exists():
            (out / name).write_text((src / name).read_text(encoding="utf-8"), encoding="utf-8")

    rows = np.array(old_ids, dtype=np.int64)
    graph = onnx.load(str(src / "onnx" / args.model))
    table_src = onnx.load(str(src / "onnx" / args.table_from)) if args.table_from else graph
    table = {t.name: numpy_helper.to_array(t) for t in table_src.graph.initializer if t.name in TABLE}
    for i, t in enumerate(graph.graph.initializer):
        if t.name in TABLE:
            graph.graph.initializer[i].CopyFrom(numpy_helper.from_array(np.ascontiguousarray(table[t.name][rows]), t.name))
    if args.table_from:
        bits = next(a.i for n in table_src.graph.node if n.op_type == "GatherBlockQuantized"
                    for a in n.attribute if a.name == "bits")
        for n in graph.graph.node:
            if n.op_type == "GatherBlockQuantized":
                for a in n.attribute:
                    if a.name == "bits":
                        a.i = bits
    onnx.save_model(graph, str(out / "onnx" / "query.onnx"), save_as_external_data=True,
                    all_tensors_to_one_file=True, location="query.onnx_data", size_threshold=1024)
    size = sum(f.stat().st_size for f in (out / "onnx").iterdir())
    print(f"kept {len(keep)} of {len(vocab)} tokens, {len(kept_merges)} of {len(merges)} merges; "
          f"model {size / 1e6:.0f} MB")


if __name__ == "__main__":
    main()
