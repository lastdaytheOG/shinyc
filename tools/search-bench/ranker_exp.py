import hashlib
import json
import math
import pathlib
import re
import sqlite3
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import score

SLICES = {"exact", "entity", "typos", "paraphrase", "cross_language", "long_natural", "number_fact", "temporal", "hinglish", "ambiguous"}
WORD = re.compile(r"[\wऀ-ॿ]+")
HAND = [1.2, 1.1, 0.6, 0.10, 0.06, 0.04, 0.10]
NAMES = ["bm25 rrf", "substring rrf", "fuzzy rrf", "phrase", "word share", "name share", "words in order"]


def held_out(case_id):
    return int(hashlib.md5(("ranker" + case_id).encode()).hexdigest(), 16) % 10 < 3


def features(query, text, name, ranks):
    q = query.lower()
    words = [w for w in WORD.findall(q)]
    page = text.lower()
    nm = name.lower()
    f = [1 / (60 + ranks[k]) if k in ranks else 0.0 for k in ("bm25", "substring", "fuzzy")]
    f.append(1.0 if q in page or q in nm else 0.0)
    said = [w for w in words if w in page or w in nm]
    f.append(len(said) / max(1, len(words)))
    f.append(sum(1 for w in words if w in nm) / max(1, len(words)))
    pw = WORD.findall(page)
    best = run = 0
    for i in range(len(pw)):
        run = 0
        for j, w in enumerate(words):
            if i + j < len(pw) and pw[i + j] == w:
                run += 1
            else:
                break
        best = max(best, run)
    f.append((best - 1) / (len(words) - 1) if len(words) > 1 and best >= 2 else 0.0)
    return f


def sig(x):
    return 1 / (1 + math.exp(-max(-40, min(40, x))))


def fit(rows, l2=1e-3, epochs=300, lr=0.5):
    w = [h * 10 for h in HAND]
    b = -3.0
    scale = [max(1e-6, max(abs(x[i]) for x, _ in rows)) for i in range(len(HAND))]
    for _ in range(epochs):
        gw = [0.0] * len(w)
        gb = 0.0
        for x, y in rows:
            z = b + sum(wi * xi / si for wi, xi, si in zip(w, x, scale))
            d = sig(z) - y
            gb += d
            for i in range(len(w)):
                gw[i] += d * x[i] / scale[i]
        n = len(rows)
        b -= lr * gb / n
        w = [max(0.0, wi - lr * (gi / n + l2 * wi)) for wi, gi in zip(w, gw)]
    return [wi / si for wi, si in zip(w, scale)]


def evaluate(cases, data, weights):
    rr, top1, n = 0.0, 0, 0
    for c in cases:
        cands = data[c["id"]]
        if not cands:
            n += 1
            continue
        best = {}
        for doc, x in cands:
            s = sum(w * xi for w, xi in zip(weights, x))
            best[doc] = max(best.get(doc, -1e9), s)
        order = sorted(best, key=lambda d: -best[d])
        first = next((i + 1 for i, d in enumerate(order) if d in c["relevant"]), 0)
        rr += 1 / first if first else 0
        top1 += 1 if first == 1 else 0
        n += 1
    return {"mrr": round(rr / max(n, 1), 4), "hit1": round(top1 / max(n, 1), 4), "n": n}


def main():
    db, results = sys.argv[1], sys.argv[2]
    con = sqlite3.connect(db)
    rows = {r[0]: r for r in con.execute("SELECT id, ocrText, sourceFile, title, parentDocumentId FROM vault_items")}
    cases = [c for c in score.load_jsonl(HERE / "corpus" / "bench_cases.jsonl") if c["slice"] in SLICES and not c.get("none")]
    res = {r["id"]: r for r in score.load_jsonl(results)}
    data = {}
    for c in cases:
        lanes = (res.get(c["id"]) or {}).get("lanes") or {}
        ranks = {}
        for lane, ids in lanes.items():
            for k, rid in enumerate(ids):
                ranks.setdefault(rid, {})[lane] = k
        cands = []
        for rid, rk in ranks.items():
            r = rows.get(rid)
            if r is None:
                continue
            cands.append((r[4] or r[0], features(c["query"], r[1], r[3] or r[2], rk)))
        data[c["id"]] = cands
    train = [c for c in cases if not held_out(c["id"])]
    test = [c for c in cases if held_out(c["id"])]
    pts = [(x, 1 if doc in c["relevant"] else 0) for c in train for doc, x in data[c["id"]]]
    fitted = fit(pts)
    out = {"features": NAMES, "hand": HAND, "fitted": [round(v, 5) for v in fitted],
           "held_out_hand": evaluate(test, data, HAND), "held_out_fitted": evaluate(test, data, fitted),
           "train_hand": evaluate(train, data, HAND), "train_fitted": evaluate(train, data, fitted)}
    print(json.dumps(out, indent=1))
    (HERE / "results" / "ranker_exp.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
