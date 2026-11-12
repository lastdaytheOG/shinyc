import argparse
import json
import math
import pathlib
import sys
from collections import defaultdict

HERE = pathlib.Path(__file__).resolve().parent

ORDER = ["SAYS", "NAME", "TAG", "NEAR_SPELLING", "FACT", "MEANING", "LOOKS"]


def load_jsonl(path):
    return [json.loads(l) for l in pathlib.Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def screen(r):
    groups = r.get("groups") or []
    if groups:
        out = []
        for g in groups:
            for i in g["ids"]:
                if i not in out:
                    out.append(i)
        return out
    out = list(r.get("photos", []))
    for i in r.get("items", []):
        if i not in out:
            out.append(i)
    return out


def best_group_rank(r, rel):
    lists = [g["ids"] for g in (r.get("groups") or [])] or [r.get("photos", []), r.get("items", [])]
    best = 0
    for ids in lists:
        for k, i in enumerate(ids):
            if i in rel:
                best = k + 1 if best == 0 else min(best, k + 1)
                break
    return best


def dcg(ranked, rel, k=10):
    return sum(rel.get(i, 0) / math.log2(n + 2) for n, i in enumerate(ranked[:k]))


def metrics(case, r):
    rel = case["relevant"]
    ranked = screen(r)
    if case.get("none"):
        return {"none": 1, "anyResult": 1 if ranked else 0, "sure": 1 if r.get("verdict") == "SURE" else 0}
    first = next((n + 1 for n, i in enumerate(ranked) if i in rel), 0)
    ideal = sorted(rel.values(), reverse=True)
    idcg = sum(g / math.log2(n + 2) for n, g in enumerate(ideal[:10]))
    m = {
        "hit1": 1 if first == 1 else 0, "hit5": 1 if 0 < first <= 5 else 0, "hit10": 1 if 0 < first <= 10 else 0,
        "recall5": len([i for i in ranked[:5] if i in rel]) / min(len(rel), 5),
        "recall10": len([i for i in ranked[:10] if i in rel]) / min(len(rel), 10),
        "mrr": 1 / first if first else 0.0, "ndcg10": dcg(ranked, rel) / idcg if idcg else 0.0,
        "p5": len([i for i in ranked[:5] if i in rel]) / 5,
        "bestGroupHit5": 1 if 0 < best_group_rank(r, rel) <= 5 else 0,
        "bestGroupHit1": 1 if best_group_rank(r, rel) == 1 else 0,
        "sure": 1 if r.get("verdict") == "SURE" else 0,
        "sureRight": 1 if r.get("verdict") == "SURE" and first == 1 else 0,
    }
    if case["slice"] == "ambiguous":
        spec = case.get("readings", {})
        readings = [k for k in ("kinds", "wiki") for _ in spec.get(k, [])]
        m["readings"] = len(readings)
    return m


def slices_of(case):
    out = [case["slice"], f"{case['slice']}:{case['lang']}"]
    if case["slice"] == "paraphrase" and len(case.get("shared", [])) <= 2:
        out.append("paraphrase_few")
        out.append(f"paraphrase_few:{case['lang']}")
    return out


def pct(xs, p):
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    return xs[min(len(xs) - 1, int(round(p * (len(xs) - 1))))]


def mcnemar(b, c):
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    p = sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n
    return min(1.0, 2 * p)


def summarize(cases, results):
    by = defaultdict(list)
    lat_first, lat_final = [], []
    for c in cases:
        r = results.get(c["id"])
        if r is None:
            continue
        m = metrics(c, r)
        for s in slices_of(c):
            by[s].append(m)
        lat_first.append(r.get("firstMs"))
        lat_final.append(r.get("finalMs"))
    table = {}
    for s, ms in sorted(by.items()):
        keys = sorted({k for m in ms for k in m})
        table[s] = {"n": len(ms), **{k: sum(m.get(k, 0) for m in ms) / len(ms) for k in keys}}
    table["_latency"] = {"firstP50": pct(lat_first, .5), "firstP95": pct(lat_first, .95),
                         "finalP50": pct(lat_final, .5), "finalP95": pct(lat_final, .95), "n": len(lat_final)}
    return table


def fmt_row(s, row, keys):
    n = row["n"]
    if n < 30:
        return f"{s:28s} n={n:4d}  " + "  ".join(f"{k}={round(row[k] * n)}/{n}" for k in keys if k in row)
    return f"{s:28s} n={n:4d}  " + "  ".join(f"{k}={100 * row[k]:5.1f}" for k in keys if k in row)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results", nargs="+")
    ap.add_argument("--cases", default=str(HERE / "corpus" / "bench_cases.jsonl"))
    ap.add_argument("--json", default=None)
    ap.add_argument("--slices", default=None)
    args = ap.parse_args()
    cases = load_jsonl(args.cases)
    runs = {}
    for path in args.results:
        rs = {r["id"]: r for r in load_jsonl(path)}
        runs[pathlib.Path(path).stem.replace("results-", "")] = rs
    tables = {name: summarize(cases, rs) for name, rs in runs.items()}
    keys = ["hit1", "hit5", "hit10", "mrr", "ndcg10", "recall10", "bestGroupHit5", "anyResult", "sure"]
    want = args.slices.split(",") if args.slices else None
    for name, t in tables.items():
        print(f"== {name}")
        for s, row in t.items():
            if s.startswith("_"):
                print(f"   latency ms: {row}")
                continue
            if want and s.split(":")[0] not in want:
                continue
            print("   " + fmt_row(s, row, keys))
    names = list(runs)
    if len(names) >= 2:
        a, b = names[0], names[1]
        print(f"== paired hit5 {a} -> {b}")
        per = defaultdict(lambda: [0, 0, 0])
        for c in cases:
            if c.get("none") or c["id"] not in runs[a] or c["id"] not in runs[b]:
                continue
            ha = metrics(c, runs[a][c["id"]])["hit5"]
            hb = metrics(c, runs[b][c["id"]])["hit5"]
            for s in slices_of(c):
                if ":" in s:
                    continue
                per[s][0] += 1
                if ha and not hb:
                    per[s][1] += 1
                if hb and not ha:
                    per[s][2] += 1
        for s, (n, lost, won) in sorted(per.items()):
            print(f"   {s:20s} n={n:4d} won={won:3d} lost={lost:3d} p={mcnemar(lost, won):.4f}")
    if args.json:
        pathlib.Path(args.json).write_text(json.dumps(tables, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
