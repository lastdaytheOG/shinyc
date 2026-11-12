import json
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score


def test_toy():
    cases = [
        {"id": "a", "slice": "exact", "lang": "en", "relevant": {"d1": 2}, "none": False},
        {"id": "b", "slice": "exact", "lang": "en", "relevant": {"d2": 2}, "none": False},
        {"id": "c", "slice": "no_answer", "lang": "en", "relevant": {}, "none": True},
        {"id": "d", "slice": "visual", "lang": "en", "relevant": {"p1": 2}, "none": False},
    ]
    results = {
        "a": {"items": ["d1", "d9"], "photos": [], "finalMs": 10, "firstMs": 5},
        "b": {"items": ["d9", "d8", "d7", "d6", "d5", "d2"], "photos": [], "finalMs": 20, "firstMs": 6},
        "c": {"items": [], "photos": [], "finalMs": 30, "firstMs": None},
        "d": {"groups": [{"kind": "SAYS", "verdict": "POSSIBLE", "ids": ["d1"]}, {"kind": "LOOKS", "verdict": "SURE", "ids": ["p1"]}],
              "finalMs": 40, "firstMs": 7},
    }
    t = score.summarize(cases, results)
    assert t["exact"]["n"] == 2
    assert t["exact"]["hit1"] == 0.5
    assert t["exact"]["hit5"] == 0.5
    assert t["exact"]["hit10"] == 1.0
    assert abs(t["exact"]["mrr"] - (1 + 1 / 6) / 2) < 1e-9
    assert t["no_answer"]["anyResult"] == 0.0
    assert t["visual"]["hit1"] == 0.0 and t["visual"]["hit5"] == 1.0 and t["visual"]["bestGroupHit1"] == 1.0
    assert t["_latency"]["finalP95"] == 40
    assert score.mcnemar(0, 6) < 0.05 and score.mcnemar(3, 3) == 1.0


def test_round_trip():
    with tempfile.TemporaryDirectory() as d:
        p = pathlib.Path(d) / "c.jsonl"
        rows = [{"id": "x", "slice": "exact", "lang": "hi", "query": "बिजली बिल", "relevant": {"a": 2}, "none": False}]
        p.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows), encoding="utf-8")
        assert score.load_jsonl(p) == rows


if __name__ == "__main__":
    test_toy()
    test_round_trip()
    print("ok")
