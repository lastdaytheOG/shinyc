import argparse
import hashlib
import itertools
import json
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import score

ORDER = ["SAYS", "NAME", "TAG", "NEAR_SPELLING", "FACT", "LOOKS", "MEANING"]
ANSWERABLE = ["paraphrase", "cross_language", "long_natural", "exact", "entity", "typos", "number_fact", "temporal", "visual"]


def half(case_id):
    return "cal" if int(hashlib.md5(case_id.encode()).hexdigest(), 16) % 2 == 0 else "test"


def sig(x):
    return 1 / (1 + math.exp(-max(-40, min(40, x))))


def shown_of(r, p):
    m = r.get("meaning")
    if not m or not p.get("meaning", True):
        return []
    passing = [c for c in m.get("closest", []) if c["standing"] >= p["ks"] and c["margin"] >= p["km"]]
    if not passing:
        return []
    best = max(c["score"] for c in passing)
    return [c for c in passing if c["score"] >= best - p["gap"]][:10]


def features(r, p):
    out = {}
    items = r.get("items", [])
    if items:
        words, said = r.get("keywordWords", 0), r.get("keywordWordsSaid", 0)
        cov = 0.0 if r.get("similarSpellingsOnly") else (said / words if words else 0.0)
        out["SAYS"] = ([cov], list(dict.fromkeys(items)), "NEAR_SPELLING" if r.get("similarSpellingsOnly") else "SAYS")
    photos = r.get("photos", [])
    if photos and p.get("photos", True):
        margins = r.get("photoMargins") or [0.0]
        scores = r.get("photoScores") or [0.0]
        out["LOOKS"] = ([margins[0], scores[0]], photos, "LOOKS")
    shown = shown_of(r, p)
    if shown:
        out["MEANING"] = ([shown[0]["standing"], shown[0]["margin"]], [c["id"] for c in shown], "MEANING")
    return out


def chance(model, x):
    w = model["w"]
    return sig(w[0] + sum(a * b for a, b in zip(w[1:], x)))


def regroup(r, p):
    groups = []
    for key, (x, ids, kind) in features(r, p).items():
        c = chance(p["models"][key], x)
        groups.append({"kind": kind, "chance": c, "verdict": "SURE" if c >= p["sure"][key] else "POSSIBLE", "ids": ids, "key": key})
    full = any(g["kind"] == "SAYS" and g["key"] == "SAYS" and features(r, p)["SAYS"][0][0] >= 1.0 for g in groups)
    groups.sort(key=lambda g: (0 if (full and g["kind"] == "SAYS") else 1, -g["chance"], ORDER.index(g["kind"])))
    seen, out = set(), []
    for g in groups:
        ids = [i for i in g["ids"] if not (i in seen or seen.add(i))]
        if ids:
            out.append({**g, "ids": ids})
    verdict = "SURE" if any(g["verdict"] == "SURE" for g in out) else ("POSSIBLE" if out else "NOTHING")
    meaning = next((g for g in out if g["key"] == "MEANING"), None)
    return {**r, "groups": out, "verdict": verdict, "meaningShown": meaning["ids"] if meaning else []}


def fit(rows, l2=0.1, iters=60):
    if not rows:
        return {"w": [-5.0]}
    d = len(rows[0][0])
    w = [0.0] * (d + 1)
    for _ in range(iters):
        g = [0.0] * (d + 1)
        h = [[0.0] * (d + 1) for _ in range(d + 1)]
        for x, y in rows:
            z = [1.0] + list(x)
            pr = sig(sum(a * b for a, b in zip(w, z)))
            for i in range(d + 1):
                g[i] += (pr - y) * z[i]
                for j in range(d + 1):
                    h[i][j] += pr * (1 - pr) * z[i] * z[j]
        for i in range(d + 1):
            g[i] += l2 * w[i]
            h[i][i] += l2
        w = [a - b for a, b in zip(w, solve(h, g))]
    return {"w": [round(v, 4) for v in w]}


def solve(a, b):
    n = len(b)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for c in range(n):
        piv = max(range(c, n), key=lambda r: abs(m[r][c]))
        m[c], m[piv] = m[piv], m[c]
        if abs(m[c][c]) < 1e-12:
            continue
        for r in range(n):
            if r != c:
                f = m[r][c] / m[c][c]
                m[r] = [x - f * y for x, y in zip(m[r], m[c])]
    return [m[i][n] / m[i][i] if abs(m[i][i]) > 1e-12 else 0.0 for i in range(n)]


def training_rows(cases, results, p, which):
    rows = {"SAYS": [], "LOOKS": [], "MEANING": []}
    for c in cases:
        if which and half(c["id"]) != which:
            continue
        r = results.get(c["id"])
        if r is None:
            continue
        for key, (x, ids, _) in features(r, p).items():
            rows[key].append((x, 1 if (not c.get("none") and ids and ids[0] in c["relevant"]) else 0))
    return rows


def evaluate(cases, results, p, which=None):
    hits, n, none_shown, none_n = 0, 0, 0, 0
    sure = {}
    per = {}
    for c in cases:
        if which and half(c["id"]) != which:
            continue
        r = results.get(c["id"])
        if r is None:
            continue
        g = regroup(r, p)
        for grp in g["groups"]:
            if grp["verdict"] == "SURE":
                s = sure.setdefault(grp["key"], [0, 0])
                s[0] += 1
                s[1] += 1 if (not c.get("none") and grp["ids"][0] in c["relevant"]) else 0
        if c.get("none"):
            none_n += 1
            none_shown += 1 if g["meaningShown"] else 0
            per.setdefault("no_answer_sure", []).append(1 if g["verdict"] == "SURE" else 0)
            continue
        mt = score.metrics(c, g)
        if c["slice"] in ANSWERABLE:
            hits += mt["hit5"]
            n += 1
        for s in score.slices_of(c):
            if ":" not in s:
                per.setdefault(s, []).append(mt["hit5"])
    return {"hit5": hits / max(n, 1), "noneShown": none_shown / max(none_n, 1),
            "sure": {k: f"{v[1]}/{v[0]}" for k, v in sure.items()},
            "slices": {k: round(sum(v) / len(v), 3) for k, v in per.items()}}


def fit_models(cases, results, p, which="cal"):
    rows = training_rows(cases, results, p, which)
    return {k: fit(v) for k, v in rows.items()}


def choose_sure(cases, results, p, which="cal"):
    out = {}
    for key in ("SAYS", "LOOKS", "MEANING"):
        chosen = 1.01
        for t in [0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.93, 0.95, 0.97, 0.99]:
            q = dict(p, sure={**{k: 1.01 for k in ("SAYS", "LOOKS", "MEANING")}, key: t})
            e = evaluate(cases, results, q, which)
            s = e["sure"].get(key)
            if s:
                right, total = map(int, s.split("/"))
                if total >= 10 and right / total >= 0.95:
                    chosen = t
                    break
        out[key] = chosen
    return out


def calibrate(cases, results, lam=0.5):
    best = None
    off = {"SAYS": 1.01, "LOOKS": 1.01, "MEANING": 1.01}
    for ks, km, gap in itertools.product([2.0, 2.5, 3.0, 3.5, 4.0, 5.0], [-0.02, 0.0, 0.02, 0.04, 0.06], [0.04, 0.08, 0.12, 0.2]):
        p = {"ks": ks, "km": km, "gap": gap, "sure": off}
        p["models"] = fit_models(cases, results, p)
        e = evaluate(cases, results, p, "cal")
        obj = e["hit5"] - lam * e["noneShown"]
        if best is None or obj > best[0] + 1e-9:
            best = (obj, p)
    p = best[1]
    p["sure"] = choose_sure(cases, results, p)
    return p, best[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results")
    ap.add_argument("--cases", default=str(HERE / "corpus" / "bench_cases.jsonl"))
    ap.add_argument("--params", default=None)
    ap.add_argument("--save", default=None)
    ap.add_argument("--write", default=None)
    a = ap.parse_args()
    cases = score.load_jsonl(a.cases)
    results = {r["id"]: r for r in score.load_jsonl(a.results)}
    if a.params:
        p = json.loads(pathlib.Path(a.params).read_text())
    else:
        p, obj = calibrate(cases, results)
        print("calibrated on the cal half:", json.dumps(p), "objective", round(obj, 4))
        if a.save:
            pathlib.Path(a.save).write_text(json.dumps(p, indent=1))
    for which in ("cal", "test", None):
        e = evaluate(cases, results, p, which)
        print(which or "all", json.dumps({k: (round(v, 4) if isinstance(v, float) else v) for k, v in e.items() if k != "slices"}))
        print("   ", dict(sorted(e["slices"].items())))
    if a.write:
        out = [regroup(results[c["id"]], p) for c in cases if c["id"] in results]
        pathlib.Path(a.write).write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in out) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
