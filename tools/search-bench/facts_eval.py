import json
import pathlib
import re
import sqlite3
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import score

PH = re.compile(r"⟦[^⟧]*⟧")


def clean_amounts(text):
    out = set()
    for m in re.finditer(r"(?<![\d,.])(\d{1,3}(?:,\d{2,3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)(?![\d,])", PH.sub(" ", text)):
        try:
            out.add(round(float(m.group(1).replace(",", "")), 2))
        except ValueError:
            pass
    lakh = re.finditer(r"(\d+(?:\.\d+)?)\s*(lakh|crore)", text, re.I)
    for m in lakh:
        out.add(round(float(m.group(1)) * (1e5 if m.group(2).lower() == "lakh" else 1e7), 2))
    return out


def main():
    db = sys.argv[1]
    corpus = {c["id"]: c for c in score.load_jsonl(HERE / "corpus" / "bench_corpus.jsonl")}
    con = sqlite3.connect(db)
    facts = {}
    for item, kind, text, num, trust in con.execute("SELECT itemId, kind, valueText, valueNum, trust FROM facts WHERE ruleVersion >= 1"):
        facts.setdefault(item, []).append((kind, text, num, trust))
    labelled = [c for c in corpus.values() if c["facts"] and c["kind"] not in ("wiki", "photo")]
    res = {}
    def tally(k, ok):
        r = res.setdefault(k, [0, 0])
        r[0] += 1 if ok else 0
        r[1] += 1
    for c in labelled:
        text = " ".join(c["pages"])
        amounts = clean_amounts(text)
        clean_ids = re.sub(r"[\s-]", "", PH.sub(" ", text)).upper()
        got = facts.get(c["id"], [])
        for kind, value, num, trust in got:
            if kind == "AMOUNT":
                tally("AMOUNT precision (trust>=0.8)" if trust >= 0.8 else "AMOUNT precision (trust<0.8)", round(num, 2) in amounts)
            elif kind == "ID_NUMBER":
                tally("ID_NUMBER precision", value.upper() in clean_ids)
            elif kind == "PARTY":
                tally("PARTY precision", value.lower() in text.lower())
        gt = c["facts"]
        if "AMOUNT" in gt:
            tally("AMOUNT recall (the paper's total)", any(k == "AMOUNT" and abs(n - gt["AMOUNT"]) < 0.01 for k, _, n, _ in got))
        if "ID_NUMBER" in gt:
            tally("ID_NUMBER recall", any(k == "ID_NUMBER" and v.upper() == str(gt["ID_NUMBER"]).upper() for k, v, _, _ in got))
        if "PARTY" in gt:
            tally("PARTY recall", any(k == "PARTY" and gt["PARTY"].lower() in v.lower() for k, v, _, _ in got))
    out = {k: {"right": r[0], "of": r[1], "share": round(r[0] / r[1], 3)} for k, r in sorted(res.items())}
    print(json.dumps(out, indent=1))
    (HERE / "results" / "facts_eval.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
