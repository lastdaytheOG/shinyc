import argparse
import json
import math
import pathlib
import time

import torch

from evaluate import load, vocabulary
from rules import fold, key
from teacher import Teacher
from train import HUMAN, read_pairs


def merged(guesses, keyed):
    out = {}
    for g, p in guesses:
        k = key(g) if keyed else g
        out[k] = out.get(k, 0.0) + p
    return sorted(out.items(), key=lambda x: -x[1])


def kept(ranked, vault, min_chance, floor, ratio):
    if vault is None:
        return {k for k, p in ranked if p >= min_chance}
    inside = [(k, p) for k, p in ranked if k in vault]
    if not inside:
        return set()
    best = inside[0][1]
    return {k for k, p in inside if p >= max(floor, ratio * best)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="D:/amar-translit/data/hin")
    ap.add_argument("--teacher", default="D:/amar-translit/teacher")
    ap.add_argument("--students", nargs="+", default=["D:/amar-translit/runs/b/final.pt"])
    ap.add_argument("--split", default="test")
    ap.add_argument("--width", type=int, default=8)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--threads", type=int, default=12)
    ap.add_argument("--no-teacher", action="store_true")
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    data = pathlib.Path(a.data)
    pairs = [p for p in read_pairs(data / f"hin_{a.split}.json") if p[2] in HUMAN]
    words = sorted({p[0] for p in pairs})
    vocab = vocabulary(data)

    def report(name, keys_of):
        hit = wrong = count = 0
        for latin, k, source, native in pairs:
            got = {fold(latin)} | keys_of(latin)
            hit += k in got
            wrong += len([g for g in got if g != k and g in vocab])
            count += len(got)
        n = len(pairs)
        print(f"{name:44s} hit {100 * hit / n:5.1f}%  wrong {100 * wrong / n:5.1f}%  keys {count / n:4.2f}", flush=True)

    print(f"{a.split}: {len(pairs)} words typed by people", flush=True)
    report("rules", lambda w: set())
    if not a.no_teacher:
        cache = pathlib.Path(f"D:/amar-translit/teacher_{a.split}_guesses.json")
        if cache.exists():
            tg = json.loads(cache.read_text(encoding="utf-8"))
        else:
            t = Teacher(a.teacher)
            tg = {}
            for i in range(0, len(words), a.batch):
                chunk = words[i:i + a.batch]
                for w, g in zip(chunk, t.beam(chunk)):
                    tg[w] = [(d, n, r) for d, n, r in g]
            cache.write_text(json.dumps(tg, ensure_ascii=False), encoding="utf-8")
        tr = {w: merged([(d, math.exp(r)) for d, n, r in g], True) for w, g in tg.items()}
        for c in (0.05, 0.1):
            report(f"IndicXlit chance >= {c}", lambda w, c=c: kept(tr[w], None, c, 0, 0))
        report("IndicXlit + vault floor 0.01 ratio 0.3", lambda w: kept(tr[w], vocab, 0, 0.01, 0.3))
    for path in a.students:
        m = load(path, half=True)
        t0 = time.time()
        sg = {w: merged(m.beam(w, a.width), bool(m.symbols)) for w in words}
        ms = 1000 * (time.time() - t0) / len(words)
        name = pathlib.Path(path).parent.name
        print(f"{name}: {sum(p.numel() for p in m.parameters())} weights, {ms:.1f} ms a word in PyTorch", flush=True)
        for c in (0.05, 0.1):
            report(f"{name} chance >= {c}", lambda w, c=c: kept(sg[w], None, c, 0, 0))
        for floor in (0.005, 0.01, 0.02):
            for ratio in (0.2, 0.3, 0.5):
                report(f"{name} + vault floor {floor} ratio {ratio}", lambda w, f=floor, r=ratio: kept(sg[w], vocab, 0, f, r))
        if m.symbols:
            exact = sum(1 for latin, k, s, native in pairs if m.beam(latin, 1)[:1] and m.beam(latin, 1)[0][0] == native)
            print(f"{name} top-1 exact Hindi spelling {100 * exact / len(pairs):.1f}%", flush=True)


if __name__ == "__main__":
    main()
