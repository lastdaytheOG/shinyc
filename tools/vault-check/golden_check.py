"""golden_check.py <golden.json> <vault.db>

Checks a golden query set against a copy of the vault it was written for, before it is used
to score ranking. It never looks at what search returns: it reads the stored text and says,
for each query, which documents have the query as a phrase, which have all of its words, and
whether the documents the set names as right are in the vault at all. A query whose expected
documents are not the ones that say it is a mistake in the set, and is better found here
than as a mysterious low score.
"""
import io, json, re, sqlite3, sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
golden = json.load(open(sys.argv[1], encoding="utf-8"))
db = sqlite3.connect(sys.argv[2])

# What each thing in the vault is called, and everything it says.
texts = {}
for name, text in db.execute(
    "select d.name, v.ocrText from vault_items v join documents d on d.id = v.parentDocumentId"
):
    texts.setdefault(name, []).append(text)
for name, title, text in db.execute(
    "select sourceFile, title, ocrText from vault_items where parentDocumentId is null"
):
    texts.setdefault(title or name, []).append(text)
texts = {name: re.sub(r"\s+", " ", " ".join(parts)).lower() for name, parts in texts.items()}

WORD = re.compile(r"[^\W_]+", re.UNICODE)
problems = 0
for case in golden["cases"]:
    expected = case["expectedFiles"]
    missing = [f for f in expected if f not in texts]
    for query in case["queries"]:
        q = query.lower()
        words = WORD.findall(q)
        phrase = [n for n, t in texts.items() if q in t or q in n.lower()]
        all_words = [n for n, t in texts.items() if all(w in t or w in n.lower() for w in words)]
        note = ""
        if missing:
            note = "  NOT IN VAULT: %s" % missing
            problems += 1
        elif case.get("kind") not in ("typo", "form") and not any(f in all_words for f in expected):
            note = "  NO EXPECTED DOCUMENT HAS ALL THE WORDS"
            problems += 1
        others = [n for n in phrase if n not in expected]
        print("%-34s expected=%s" % (query, expected))
        print("    says the phrase: %s%s" % (phrase[:6], "  (+ not expected: %s)" % others[:5] if others else ""))
        print("    has every word:  %d documents%s" % (len(all_words), note))
print("\n%d cases, %d queries, %d problems" % (
    len(golden["cases"]), sum(len(c["queries"]) for c in golden["cases"]), problems))
