import argparse
import json
import pathlib
import sqlite3
import sys
import time

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import score

PROMPT = "task: search result | query: "
SLICES = ["paraphrase", "cross_language", "long_natural", "exact", "typos", "entity", "temporal", "number_fact"]


class Model:
    def __init__(self, model_dir, tok_path, threads=8):
        self.tok = Tokenizer.from_file(str(tok_path))
        self.eos = self.tok.token_to_id("<eos>")
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        self.sess = ort.InferenceSession(str(pathlib.Path(model_dir) / "query.onnx"), so, providers=["CPUExecutionProvider"])
        self.feats = {i.name: i.shape[-1] for i in self.sess.get_inputs() if i.name.endswith("_features")}

    def ids(self, text, max_tokens):
        ids = self.tok.encode(text).ids
        if len(ids) > max_tokens:
            ids = ids[:max_tokens - 1] + [self.eos]
        return ids

    def run(self, ids):
        arr = np.array([ids], dtype=np.int64)
        feed = {"input_ids": arr, "attention_mask": np.ones_like(arr)}
        feed.update({n: np.zeros((0, w), dtype=np.float32) for n, w in self.feats.items()})
        v = self.sess.run(["sentence_embedding"], feed)[0][0].astype(np.float32)
        return v / np.linalg.norm(v)


def title_of(row):
    rid, text, source, title, parent, item_type = row
    if parent is None and item_type in ("screenshot", "photo"):
        return None
    name = (title or source or "").strip()
    if not name:
        return None
    base = name.rsplit(".", 1)[0] if "." in name else name
    return base.replace("_", " ").strip() or None


def passage(title, text):
    return f"title: {title.strip() if title and title.strip() else 'none'} | text: {text}"


def quantize(v):
    m = np.abs(v).max()
    s = m / 127 if m > 0 else 1.0
    return np.round(v / s).clip(-127, 127).astype(np.int8), s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--model", default=r"D:\amar-models\photo-search\siglip2-b16-v2cw1-w4a8")
    ap.add_argument("--tok", default=r"D:\amar-vs\models\query-rank250k-mixed\tokenizer.json")
    ap.add_argument("--cache", default=str(HERE / "results" / "replay_cache.npz"))
    ap.add_argument("--out", default=str(HERE / "results" / "replay.json"))
    a = ap.parse_args()
    con = sqlite3.connect(a.db)
    rows = [r for r in con.execute("SELECT id, ocrText, sourceFile, title, parentDocumentId, itemType FROM vault_items")
            if len(r[1].strip()) >= 12 and not r[0].startswith(("scale-", "meaningcontrol-"))]
    doc_of = {r[0]: (r[4] or r[0]) for r in rows}
    m = Model(a.model, a.tok)
    cache = pathlib.Path(a.cache)
    store = dict(np.load(cache, allow_pickle=True)) if cache.exists() else {}
    variants = {
        "long384": lambda r: [m.ids(passage(title_of(r), r[1].strip()[:384 * 8]), 384)],
        "noprefix384": lambda r: [m.ids(passage(None, r[1].strip()[:384 * 8]), 384)],
        "two192": lambda r: split_two(m, title_of(r), r[1].strip()),
    }
    t0 = time.time()
    vecs = {}
    for name, make in variants.items():
        key = f"v_{name}"
        if key in store:
            vecs[name] = store[key]
            continue
        out = []
        for k, r in enumerate(rows):
            out.append([m.run(ids) for ids in make(r)])
            if k % 500 == 0:
                print(name, k, len(rows), round(time.time() - t0), flush=True)
        width = max(len(x) for x in out)
        arr = np.zeros((len(rows), width, 768), dtype=np.float32)
        for i, x in enumerate(out):
            for j, v in enumerate(x):
                arr[i, j] = v
            for j in range(len(x), width):
                arr[i, j] = x[0]
        vecs[name] = arr
        store[key] = arr
        np.savez_compressed(cache, **store)
    cases = [c for c in score.load_jsonl(HERE / "corpus" / "bench_cases.jsonl") if c["slice"] in SLICES + ["no_answer"]]
    qkey = "q_all"
    if qkey in store and len(store[qkey]) == len(cases):
        qv = store[qkey]
    else:
        qv = np.stack([m.run(m.tok.encode(PROMPT + c["query"]).ids) for c in cases])
        store[qkey] = qv
        np.savez_compressed(cache, **store)
    report = {}
    configs = {
        "768 float": ("long384", 768, False), "768 int8": ("long384", 768, True), "256 int8": ("long384", 256, True),
        "128 int8": ("long384", 128, True), "no prefix 768 int8": ("noprefix384", 768, True), "two 192-token windows int8": ("two192", 768, True),
    }
    ids = [r[0] for r in rows]
    for label, (vname, dim, q8) in configs.items():
        arr = vecs[vname][:, :, :dim].copy()
        arr /= np.linalg.norm(arr, axis=2, keepdims=True)
        if q8:
            flat = arr.reshape(-1, dim)
            qs = np.array([quantize(v) for v in flat], dtype=object)
            arr = np.stack([b.astype(np.float32) * s for b, s in qs]).reshape(arr.shape)
        qd = qv[:, :dim] / np.linalg.norm(qv[:, :dim], axis=1, keepdims=True)
        per = {}
        for ci, c in enumerate(cases):
            if c.get("none"):
                continue
            sc = np.einsum("rwd,d->rw", arr, qd[ci]).max(axis=1)
            order = np.argsort(-sc)
            seen, ranked = set(), []
            for i in order:
                d = doc_of[ids[i]]
                if d not in seen:
                    seen.add(d)
                    ranked.append(d)
                if len(ranked) >= 10:
                    break
            first = next((k + 1 for k, d in enumerate(ranked) if d in c["relevant"]), 0)
            for s in score.slices_of(c):
                per.setdefault(s, []).append(1 if 0 < first <= 5 else 0)
        report[label] = {s: [round(100 * sum(v) / len(v), 1), len(v)] for s, v in sorted(per.items()) if ":" not in s}
        print(label, {s: v[0] for s, v in report[label].items()})
    pathlib.Path(a.out).write_text(json.dumps(report, indent=1))


def split_two(m, title, text):
    words = text.split()
    half = max(1, len(words) // 2)
    first, second = " ".join(words[:half]), " ".join(words[half:])
    out = [m.ids(passage(title, first[:192 * 8]), 192)]
    if second.strip():
        out.append(m.ids(passage(title, second[:192 * 8]), 192))
    return out


if __name__ == "__main__":
    main()
