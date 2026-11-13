import argparse
import collections
import json
import pathlib
import time

import torch

from model import Translit
from rules import fold, key
from train import HUMAN, read_pairs


def load(path, half=False):
    ck = torch.load(path, map_location="cpu")
    sizes = ck["sizes"]
    model = Translit(*sizes[:3], feed=bool(sizes[3]) if len(sizes) > 3 else True, symbols=ck.get("symbols"))
    state = ck["state"]
    if half:
        state = {k: v.half().float() for k, v in state.items()}
    model.load_state_dict(state)
    model.eval()
    return model


def vocabulary(data):
    keys = set()
    for name in ("hin_test.json", "hin_valid.json"):
        for _, _, _, native in read_pairs(data / name):
            keys.add(key(native))
    for _, _, source, native in read_pairs(data / "hin_train.json"):
        if source in HUMAN:
            keys.add(key(native))
    return keys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("--data", default="D:/amar-translit/data/hin")
    ap.add_argument("--split", default="test")
    ap.add_argument("--width", type=int, default=4)
    ap.add_argument("--chances", default="0,0.02,0.05,0.1,0.2,0.3")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--half", action="store_true")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--save", default=None)
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    data = pathlib.Path(a.data)
    model = load(a.ckpt, a.half)
    pairs = read_pairs(data / f"hin_{a.split}.json")
    if a.limit:
        pairs = pairs[:a.limit]
    vocab = vocabulary(data)
    chances = [float(c) for c in a.chances.split(",")]
    guesses = {}
    t0 = time.time()
    for latin in sorted({p[0] for p in pairs}):
        guesses[latin] = model.beam(latin, a.width)
        if model.symbols:
            merged = {}
            for g, p in guesses[latin]:
                k = key(g)
                merged[k] = merged.get(k, 0.0) + p
            guesses[latin] = sorted(merged.items(), key=lambda x: -x[1])
    per_word = (time.time() - t0) / max(1, len(guesses))
    total = collections.Counter()
    rule_hit = collections.Counter()
    hit = {c: collections.Counter() for c in chances}
    false_rule = collections.Counter()
    false_union = {c: collections.Counter() for c in chances}
    keys_count = {c: collections.Counter() for c in chances}
    for latin, k, source, native in pairs:
        total[source] += 1
        r = fold(latin)
        rule_hit[source] += r == k
        false_rule[source] += r != k and r in vocab
        for c in chances:
            got = {r} | {g for g, p in guesses[latin] if p >= c}
            hit[c][source] += k in got
            false_union[c][source] += len([g for g in got if g != k and g in vocab])
            keys_count[c][source] += len(got)
    report = {"per_word_ms": per_word * 1000, "words": len(guesses), "sources": {}}
    groups = dict((s, [s]) for s in total)
    groups["human"] = [s for s in total if s in HUMAN]
    groups["all"] = list(total)
    for g, members in groups.items():
        n = sum(total[s] for s in members)
        if not n:
            continue
        row = {"n": n, "rules": sum(rule_hit[s] for s in members) / n,
               "rules_false": sum(false_rule[s] for s in members) / n}
        for c in chances:
            row[f"hit@{c}"] = sum(hit[c][s] for s in members) / n
            row[f"false@{c}"] = sum(false_union[c][s] for s in members) / n
            row[f"keys@{c}"] = sum(keys_count[c][s] for s in members) / n
        report["sources"][g] = row
    print(json.dumps(report, indent=1))
    if a.save:
        pathlib.Path(a.save).write_text(json.dumps(report, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
